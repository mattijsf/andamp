-- Headphone Crossfeed: a little of each side in the other ear.
--
-- On speakers each ear hears both speakers, the far one slightly later and
-- duller because the head is in the way. Headphones remove that, so a
-- hard-panned stereo mix sits inside the head. This mixes the other side back
-- in, low-passed at 700 Hz and 0.3 ms late, and rescales the output so that
-- sound common to both channels keeps its level.

plugin {
  id      = "nl.mattix.andamp.crossfeed",
  name    = "Headphone Crossfeed",
  version = "1.0.0",
  author  = "Andamp",
  about   = "Opposite channel low-passed at 700 Hz (Q 0.5), delayed 0.3 ms and mixed in; output normalized for centered content.",
}

local amount = param.number {
  id = "amount", name = "Amount", min = 0, max = 1, default = 0.5,
  unit = "pc", display = { scale = 100, zero = "OFF" },
  help = "Crossfeed level, up to −6 dB at 100 %. The output is scaled by 1 / (1 + level), so correlated low frequencies keep their level.",
}

presets {
  { name = "Light",  values = { amount = 0.3 } },
  { name = "Normal", values = { amount = 0.6 } },
  { name = "Strong", values = { amount = 1.0 } },
}

local HEAD_HZ  = 700      -- the low-pass corner for the far ear
local LATE_S   = 0.0003   -- how much later the far ear hears it
local MOST     = 0.5      -- at 100 %, the other side arrives 6 dB down

function build(g, ctx)
  if ctx.channels < 2 then
    local out = {}
    for ch = 0, ctx.channels - 1 do out[ch] = g.input(ch) end
    return out
  end
  local late = LATE_S * ctx.sampleRate
  local share = g.mul(amount, MOST)
  -- what both channels share comes back in at (1 + share); dividing by it keeps
  -- centered bass where it was
  local keep = g.div(1, g.add(1, share))
  local function across(from)
    local dull = g.biquad { input = g.input(from), kind = "lowpass", freq = HEAD_HZ, q = 0.5 }
    return g.mul(g.delay { input = dull, time = late, maxTime = late + 2, interp = "linear" }, share)
  end
  local out = {
    [0] = g.mul(g.add(g.input(0), across(1)), keep),
    [1] = g.mul(g.add(g.input(1), across(0)), keep),
  }
  for ch = 2, ctx.channels - 1 do out[ch] = g.input(ch) end
  return out
end
