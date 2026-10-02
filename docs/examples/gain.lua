-- Gain: the smallest plug-in with a control.
--
-- One slider, one node. If this loads, the whole path works: picker, sandbox,
-- storage, catalog, rack.

plugin {
  id      = "nl.mattix.andamp.example.gain",
  name    = "Gain",
  version = "1.0.0",
  author  = "Andamp examples",
  about   = "Turns everything up or down. Nothing else.",
}

local level = param.number {
  id = "level", name = "Level", min = 0, max = 2, default = 1,
  help = "1 leaves the signal alone; 2 is twice as loud, which will clip.",
}

function build(g, ctx)
  local out = {}
  for ch = 0, ctx.channels - 1 do
    out[ch] = g.mul(g.input(ch), level)
  end
  return out
end
