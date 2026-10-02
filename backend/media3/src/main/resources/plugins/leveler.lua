-- Leveler: quiet parts up, loud parts down.
--
-- For listening where the room is not quiet (a train, a car, a kitchen), or
-- where it has to stay quiet at night.
--
-- It measures how loud the music is and moves it toward one level, -18 dBFS.
-- Amount is how far: at 100 % it closes 70 % of the distance, by at most 12 dB
-- either way. The detector follows a rise over 300 ms and a fall over 900 ms,
-- so the gain comes down faster than it goes back up. Below -50 dBFS is taken
-- for silence between songs and is not raised.
--
-- The channels are measured together and moved together, so the stereo image
-- does not shift.

plugin {
  id      = "nl.mattix.andamp.leveler",
  name    = "Leveler",
  version = "1.0.0",
  author  = "Andamp",
  about   = "Stereo-linked RMS leveler towards −18 dBFS, up to ≈3.3:1 either way within ±12 dB, with a 5 ms look-ahead peak limiter.",
}

local amount = param.number {
  id = "amount", name = "Amount", min = 0, max = 1, default = 0.5,
  unit = "pc", display = { scale = 100, zero = "OFF" },
  help = "Share of the distance to −18 dBFS RMS that is closed: 100 % is ≈3.3:1 above and below. Detector 300 ms rising, 900 ms falling; no boost below −50 dBFS.",
}

presets {
  { name = "Light", values = { amount = 0.3 } },
  { name = "Car",   values = { amount = 0.6 } },
  { name = "Night", values = { amount = 0.9 } },
}

local TARGET_DB   = -18
local SILENCE_DB  = -50   -- below this nothing is raised
local MOST_UP_DB  = 12
local MOST_DOWN_DB = -12
local RATIO       = 0.7   -- at 100 %, how much of the distance to the target is closed
local CEILING     = 0.891  -- -1 dBFS: the limiter keeps every peak under this
local AHEAD_S     = 0.005  -- how far ahead it looks, and so how late the track is
local RELEASE     = 250    -- ms to come back up after a peak
local DB_PER_OCTAVE = 6.0206  -- 20 * log10(2): a level's log2 in decibels

--- The channels in [signals] turned down together, ahead of any peak over CEILING.
-- The same limiter as in virtualbass.lua: it changes the gain and does not
-- reshape the wave.
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
  -- one measurement for all channels: the loudest of them, as an RMS level
  local levels = {}
  for ch = 0, ctx.channels - 1 do
    levels[#levels + 1] = g.envelope { input = g.input(ch), attack = 300, release = 900, kind = "rms" }
  end
  local level = levels[1]
  for i = 2, #levels do level = g.max(level, levels[i]) end

  local levelDb = g.mul(g.math { input = g.max(level, 0.00001), fn = "log2" }, DB_PER_OCTAVE)
  local distance = g.sub(TARGET_DB, levelDb)
  -- the boost fades in over the 10 dB above SILENCE_DB, so a fade-out is not raised
  local audible = g.clip { input = g.mul(g.sub(levelDb, SILENCE_DB), 0.1), min = 0, max = 1 }
  local up = g.mul(g.max(distance, 0), audible)
  local down = g.min(distance, 0)
  local gainDb = g.clip {
    input = g.mul(g.add(up, down), g.mul(amount, RATIO)),
    min = MOST_DOWN_DB, max = MOST_UP_DB,
  }

  local levelled = {}
  for ch = 0, ctx.channels - 1 do
    levelled[ch] = g.gain { input = g.input(ch), db = gainDb }
  end
  -- the detector takes 300 ms to follow a rise, so a sudden hit after a quiet
  -- stretch arrives while the gain is still up: the limiter turns it down
  -- before it reaches full scale
  local limited = limit(g, ctx, levelled)
  local engaged = g.clip { input = g.mul(amount, 20), min = 0, max = 1 }
  local out = {}
  for ch = 0, ctx.channels - 1 do
    out[ch] = g.crossfade(g.input(ch), limited[ch], engaged)
  end
  return out
end
