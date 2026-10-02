-- Vinyl: the track as a record, played on a turntable that has seen some use.
--
-- What a record does to music:
--
--   * the platter turns at 33 1/3 rpm - 0.556 Hz - and the hole is never quite
--     in the center, so the pitch sways once per turn, plus a faster wobble
--     from the belt;
--   * the groove cannot hold deep bass in stereo, so the bottom is cut in mono,
--     which is why a record sounds centered down there;
--   * a groove has ends: nothing under about 40 Hz survives being cut, and the
--     top goes first as the record wears, steeply, the way a stylus that no
--     longer sits right in the groove loses it;
--   * the two walls of one groove are never fully apart, so the further the
--     record is gone the more each side is heard on the other;
--   * a stylus tracing a bend it cannot follow rounds it off, which is heard as
--     harmonics on anything loud, and as a lift around 3 kHz where the cartridge
--     has its own resonance;
--   * the surface is never silent - a bed of noise, a scatter of crackle, the
--     occasional pop, and the motor's rumble underneath;
--   * and there is a scratch: every 30 seconds the stylus crosses it, loud,
--     in both channels at once.
--
-- All of it is synthesized. The crackle is white noise with a threshold on it,
-- so only the rare peaks get through, and each one is rung through a filter
-- into a tick. Turning Crackle up lowers the threshold, so there are more
-- ticks.
--
-- Each channel's noise sources take their own seeds: one seed for both would
-- put the same dust in both ears, heard as a click in the middle of the head.

plugin {
  id      = "nl.mattix.andamp.example.vinyl",
  name    = "Vinyl",
  version = "1.3.0",
  author  = "Andamp examples",
  about   = "Record emulation: 0.556 Hz eccentricity plus 7 Hz belt flutter on a 3 ms line, elliptical bass below 130 Hz, 40–85 Hz high-pass, 24 dB/oct top from 14 kHz to 3.5 kHz, channel separation from 20 dB down to 7 dB, a 3 kHz cartridge lift and tanh tracing distortion, over per-channel surface noise, threshold-gated crackle, pops, a 30 s scratch burst and 35 Hz rumble."
}

local crackle = param.number {
  id = "crackle", name = "Crackle", min = 0, max = 1, default = 0.4,
  unit = "pc", display = { scale = 100, zero = "CLEAN" },
  help = "How many ticks, not how loud: each peaks up to −11 dBFS, about 5 a second above −20 dBFS at 25 %, 11 at 40 % and 36 at 100 %, over a bed of noise and the odd pop; plus a scratch every 30 s near −4 dBFS from 40 % up. At 0 the surface is silent.",
}
local wow = param.number {
  id = "wow", name = "Wow", min = 0, max = 1, default = 0.4,
  unit = "pc", display = { scale = 100, zero = "TRUE" },
  help = "Pitch sway from an off-center hole: up to ±0.5 % once per turn, with ±0.1 % of 7 Hz flutter over it.",
}
local age = param.number {
  id = "age", name = "Age", min = 0, max = 1, default = 0.5,
  unit = "pc", display = { scale = 100, zero = "MINT" },
  help = "How far gone the pressing is: the top falls from 14 kHz to 3.5 kHz at 24 dB/oct, the bottom is cut from 40 Hz to 85 Hz, the channels bleed into each other from 20 dB apart down to 7 dB, a 3 kHz lift and tracing distortion come up, and the motor rumbles underneath.",
}
local mix = param.number {
  id = "mix", name = "Mix", min = 0, max = 1, default = 1,
  unit = "pc", display = { scale = 100, zero = "DRY" },
  help = "Crossfade from the file to the record.",
}

presets {
  { name = "New pressing", values = { crackle = 0.12, wow = 0.15, age = 0.1,  mix = 1 } },
  { name = "Second-hand",  values = { crackle = 0.45, wow = 0.4,  age = 0.45, mix = 1 } },
  { name = "Charity shop", values = { crackle = 0.85, wow = 0.7,  age = 0.85, mix = 1 } },
}

local RPM_HZ      = 33.333 / 60   -- one turn of the platter
local BELT_HZ     = 7             -- the faster wobble, from the belt and the motor
local ECCENTRIC_MS = 1.4          -- ±0.5 % of pitch at 0.556 Hz
local FLUTTER_MS  = 0.023         -- ±0.1 % at 7 Hz
local CENTRE_MS   = 3             -- the line the two wobbles move around
local MONO_BELOW  = 130           -- the groove cannot hold stereo under this
local BOTTOM_NEW_HZ = 40          -- and nothing much under this at all
local BOTTOM_OLD_HZ = 85
local TOP_NEW_HZ  = 14000
local TOP_OLD_HZ  = 3500
local BLEED       = 0.29          -- how much of the other channel an old groove hands over
local HONK_HZ     = 3000          -- where the cartridge has its own resonance
local HONK_DB     = 4
local TRACE_DRIVE = 1.6           -- how hard an old stylus is pushed into its bend
local TRACE_MAKEUP = 0.35         -- what tanh takes off a loud passage, handed back
local HISS_HZ     = 1600          -- where the surface noise sits
local HISS_LEVEL  = 0.02          -- -34 dBFS at Crackle 1
local TICK_EDGE_HZ = 900          -- a tick keeps everything above this, so it is a click and not a tone
local TICK_LEVEL  = 0.3
local TICK_GATE   = 0.0045        -- how much of the noise gets past at Crackle 1
local POP_HZ      = 700
local POP_LEVEL   = 3
local POP_GATE    = 0.00004
local SCRATCH_EVERY_S = 30         -- seconds between scratches
local SCRATCH_MS  = 50            -- how long the stylus is in it
local SCRATCH_GATE = 0.006        -- how much of the noise gets past inside it: a few clicks
local SCRATCH_LEVEL = 2.4         -- a click rung through a wide band keeps about a quarter of itself
local SCRATCH_FULL_AT = 0.4       -- Crackle at which it is as loud as it gets
local RUMBLE_HZ   = 35
local RUMBLE_LEVEL = 0.06

--- White noise past a threshold: silence, with a spike wherever it peaks.
-- [gate] is how much of the noise gets through at Crackle 1, so a spike is
-- rare and what is left of it is small. It is scaled back up to 0..1 by what
-- the threshold let through, so Crackle decides how many specks there are and
-- not how loud each is: a cleaner record has fewer ticks, not fainter ones.
-- Then squared, so most specks are faint and the odd one is loud.
local function specks(g, seed, gate)
  local raw = g.noise { seed = seed }
  local size = g.math { input = raw, fn = "abs" }
  -- squared, so the low half of the slider is a few ticks and the top half is a storm
  local let = g.mul(g.mul(crackle, crackle), gate)
  local over = g.div(g.max(g.sub(size, g.sub(1, let)), 0), g.max(let, 1e-7))
  -- the sign of the noise, so the ticks are not all in one direction
  local sign = g.div(raw, g.max(size, 0.0001))
  return g.mul(g.mul(over, over), sign)
end

--- The scratch: a few loud clicks and a thump, once every SCRATCH_EVERY_S.
-- A slow sine opens a window only at its very top, SCRATCH_MS wide; inside it
-- noise past a low threshold makes the clicks. Regular rather than random, so
-- it cannot go missing for minutes on end, and one for both channels, because
-- a scratch goes through both walls of the groove.
local function scratch(g)
  local slow = g.lfo { rate = 1 / SCRATCH_EVERY_S, shape = "sine" }
  -- how far below its peak the sine is when the window is SCRATCH_MS wide
  local half = SCRATCH_MS / 2000 * 2 * math.pi / SCRATCH_EVERY_S
  local depth = half * half / 2
  -- steep sides, so most of the window is fully open rather than a ramp
  local open = g.clip { input = g.mul(g.sub(slow, 1 - depth), 4 / depth), min = 0, max = 1 }

  local raw = g.noise { seed = 501 }
  local size = g.math { input = raw, fn = "abs" }
  local over = g.max(g.sub(size, 1 - SCRATCH_GATE), 0)
  local clicks = g.mul(g.div(raw, g.max(size, 0.0001)), g.mul(over, 1 / SCRATCH_GATE))
  local crack = g.biquad { input = clicks, kind = "bandpass", freq = 1800, q = 0.5 }
  local loud = g.mul(g.clip { input = g.mul(crackle, 1 / SCRATCH_FULL_AT), min = 0, max = 1 }, SCRATCH_LEVEL)
  -- the stylus dropping into the scratch and climbing out: the window itself,
  -- rung low, is the thud under the clicks
  local thump = g.biquad { input = open, kind = "bandpass", freq = 90, q = 1 }
  return g.mul(g.add(g.mul(crack, open), g.mul(thump, 0.4)), loud)
end

function build(g, ctx)
  local sr = ctx.sampleRate
  local scratched = scratch(g)
  local perMs = sr / 1000

  -- the platter: one sway per turn for every channel, since the whole record
  -- is off center, not one groove
  local turn = g.mul(g.lfo { rate = RPM_HZ, shape = "sine" }, ECCENTRIC_MS)
  local belt = g.mul(g.lfo { rate = BELT_HZ, shape = "sine" }, FLUTTER_MS)
  local sway = g.mul(g.add(turn, belt), wow)
  local groove = g.mul(g.add(CENTRE_MS, sway), perMs)
  local top = g.add(TOP_NEW_HZ, g.mul(age, TOP_OLD_HZ - TOP_NEW_HZ))
  local bottom = g.add(BOTTOM_NEW_HZ, g.mul(age, BOTTOM_OLD_HZ - BOTTOM_NEW_HZ))

  local played = {}
  for ch = 0, ctx.channels - 1 do
    played[ch] = g.delay {
      input = g.input(ch),
      time = groove,
      maxTime = (CENTRE_MS + ECCENTRIC_MS + FLUTTER_MS + 2) * perMs,
      interp = "linear",
    }
  end

  -- the bass in mono: what is common to the channels keeps its bottom, what
  -- differs loses it, which is how a groove is cut. The rest of the side is
  -- handed over as the record wears, until the two are barely apart.
  if ctx.channels == 2 then
    local mid = g.mul(g.add(played[0], played[1]), 0.5)
    local side = g.mul(g.sub(played[0], played[1]), 0.5)
    local narrow = g.biquad { input = side, kind = "highpass", freq = MONO_BELOW, q = 0.707 }
    local kept = g.mul(narrow, g.sub(1, g.mul(age, BLEED * 2)))
    played[0] = g.add(mid, kept)
    played[1] = g.sub(mid, kept)
  end

  local out = {}
  for ch = 0, ctx.channels - 1 do
    -- a groove has a bottom end as well as a top, and both close in with age
    local rolled = g.biquad { input = played[ch], kind = "highpass", freq = bottom, q = 0.707 }
    -- 24 dB an octave: two low-pass sections in series
    local lidded = g.biquad {
      input = g.biquad { input = rolled, kind = "lowpass", freq = top, q = 0.54 },
      kind = "lowpass", freq = top, q = 1.31,
    }
    local honk = g.biquad {
      input = lidded, kind = "peaking", freq = HONK_HZ,
      q = 1.2, gainDb = g.mul(age, HONK_DB),
    }
    -- what the stylus cannot trace it rounds off: tanh, driven harder as the
    -- groove wears, and brought back to level afterwards
    local push = g.add(1, g.mul(age, TRACE_DRIVE))
    local traced = g.div(g.math { input = g.mul(honk, push), fn = "tanh" }, push)
    -- tanh lowers anything loud, so part of that level is handed back
    local dull = g.mul(traced, g.add(1, g.mul(age, TRACE_MAKEUP)))

    -- every channel has its own hiss, ticks, pops and rumble; the scratch is
    -- shared
    local hiss = g.mul(g.biquad {
      input = g.noise { seed = 101 + ch },
      kind = "bandpass", freq = HISS_HZ, q = 0.4,
    }, g.mul(crackle, HISS_LEVEL))
    local ticks = g.mul(g.biquad {
      input = specks(g, 201 + ch, TICK_GATE),
      kind = "highpass", freq = TICK_EDGE_HZ, q = 0.707,
    }, TICK_LEVEL)
    local pops = g.mul(g.biquad {
      input = specks(g, 301 + ch, POP_GATE),
      kind = "bandpass", freq = POP_HZ, q = 0.8,
    }, POP_LEVEL)
    local rumble = g.mul(g.biquad {
      input = g.noise { seed = 401 + ch },
      kind = "lowpass", freq = RUMBLE_HZ, q = 0.707,
    }, g.mul(age, RUMBLE_LEVEL))

    local record = g.add(dull, hiss, ticks, pops, rumble, scratched)
    -- the surface sits on top of the music, so a track already near full scale
    -- would go over: rounded off rather than clipped
    out[ch] = g.crossfade(g.input(ch), g.softclip { input = record, drive = 1, knee = 0.9 }, mix)
  end
  return out
end
