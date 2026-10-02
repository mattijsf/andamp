-- Warmth: a low shelf you can switch in and out.
--
-- The example that shows a switch. Its `on` control is a toggle: it reaches
-- the graph as 0 or 1 and is smoothed like any other value, so the crossfade
-- it feeds is a bypass that cannot click. Its `lift` is in decibels
-- (`unit = "db"`).

plugin {
  id      = "nl.mattix.andamp.example.warmth",
  name    = "Warmth",
  version = "1.0.0",
  author  = "Andamp examples",
  about   = "Lifts the low end with a shelf, with a switch to hear it in and out.",
}

local on = param.toggle {
  id = "on", name = "Switched in", default = true,
  help = "Off leaves the signal exactly as it arrived, which is what to compare against.",
}
local lift = param.number {
  id = "lift", name = "Lift", min = 0, max = 12, default = 6, unit = "db",
  help = "How much the shelf adds below 200 Hz.",
}

function build(g, ctx)
  local out = {}
  for ch = 0, ctx.channels - 1 do
    local dry = g.input(ch)
    local shelf = g.biquad { input = dry, kind = "lowshelf", freq = 200, q = 0.707, gainDb = lift }
    out[ch] = g.crossfade(dry, shelf, on)
  end
  return out
end
