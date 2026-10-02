-- Virtual Bass: bass heard from its overtones, on a speaker that cannot play it.
--
-- A small speaker does not reproduce low bass. The ear fills a low note in
-- from its overtones, so this makes overtones: the band below 150 Hz is
-- saturated with a tanh, and only what the saturation added, from 150 to
-- 700 Hz, is mixed in beside the untouched track.
--
-- The saturation acts on a signal that is already below 150 Hz, so the
-- harmonics it adds fall off quickly and do not reach the treble.
--
-- Mixing the overtones in raises the peaks, so the result goes through a
-- limiter that looks 5 ms ahead: it turns the whole track down before a peak
-- would pass -1 dBFS, and lets it back up over 250 ms. It changes the gain and
-- does not reshape the wave.

plugin {
  id      = "nl.mattix.andamp.virtualbass",
  name    = "Virtual Bass",
  version = "1.0.0",
  author  = "Andamp",
  about   = "Psychoacoustic bass: the band below 150 Hz is saturated (tanh) and its harmonics, band-limited to 150–700 Hz, are mixed over the dry signal.",
}

local amount = param.number {
  id = "amount", name = "Amount", min = 0, max = 1, default = 0.5,
  unit = "pc", display = { scale = 100, zero = "OFF" },
  help = "Level of the generated harmonics against the dry signal. Lows are isolated by a 4th-order low-pass at 150 Hz; peaks are held under −1 dBFS by a 5 ms look-ahead limiter.",
}

presets {
  { name = "Subtle", values = { amount = 0.3 } },
  { name = "Phone",  values = { amount = 0.6 } },
  { name = "Max",    values = { amount = 1.0 } },
}

local LOWS_HZ   = 150  -- what counts as bass: the notes a small speaker drops
local TOP_HZ    = 700  -- the added overtones stop here
local DRIVE     = 6    -- how hard the lows are driven into the tanh
local CEILING   = 0.891  -- -1 dBFS: the limiter keeps every peak under this
local AHEAD_S   = 0.005  -- how far ahead it looks, and so how late the track is
local RELEASE   = 250    -- ms to come back up after a peak

--- The channels in [signals] turned down together, ahead of any peak over CEILING.
--
-- A peak sets how far down the gain has to go. That is held, eased in within
-- the look-ahead, and released over RELEASE. The audio is delayed by the
-- look-ahead, so the gain is in place when the peak comes out. The delay is a
-- whole number of samples, because a fractional one interpolates, which dulls
-- the treble.
local function limit(g, ctx, signals)
  local peak
  for ch = 0, ctx.channels - 1 do
    local level = g.math { input = signals[ch], fn = "abs" }
    peak = peak and g.max(peak, level) or level
  end
  local wanted = g.sub(1, g.div(CEILING, g.max(peak, CEILING)))
  -- held at once and released slowly, so a peak a few samples wide counts in
  -- full; then eased in, so the gain change is a slope and not a step
  local held = g.envelope { input = wanted, attack = 0, release = RELEASE, kind = "peak" }
  local down = g.envelope { input = held, attack = AHEAD_S * 1000 / 6, release = 1, kind = "peak" }
  local turn = g.sub(1, down)
  local ahead = AHEAD_S * ctx.sampleRate
  ahead = ahead - ahead % 1
  local out = {}
  for ch = 0, ctx.channels - 1 do
    out[ch] = g.mul(g.delay { input = signals[ch], time = ahead, maxTime = ahead + 1, interp = "none" }, turn)
  end
  return out
end

function build(g, ctx)
  local mixed = {}
  for ch = 0, ctx.channels - 1 do
    local dry = g.input(ch)
    -- two passes for a 4th-order slope, so less of the midrange is saturated
    local lows = g.biquad {
      input = g.biquad { input = dry, kind = "lowpass", freq = LOWS_HZ, q = 0.707 },
      kind = "lowpass", freq = LOWS_HZ, q = 0.707,
    }
    local bent = g.math { input = g.mul(lows, DRIVE), fn = "tanh" }
    -- only what the saturation added: the fundamental is already in the dry
    -- track, and overtones above TOP_HZ are filtered out
    local added = g.biquad {
      input = g.biquad { input = bent, kind = "highpass", freq = LOWS_HZ, q = 0.707 },
      kind = "lowpass", freq = TOP_HZ, q = 0.707,
    }
    mixed[ch] = g.add(dry, g.mul(added, amount))
  end
  local limited = limit(g, ctx, mixed)
  -- at OFF the output is the input: not delayed, not limited
  local engaged = g.clip { input = g.mul(amount, 20), min = 0, max = 1 }
  local out = {}
  for ch = 0, ctx.channels - 1 do
    out[ch] = g.crossfade(g.input(ch), limited[ch], engaged)
  end
  return out
end
