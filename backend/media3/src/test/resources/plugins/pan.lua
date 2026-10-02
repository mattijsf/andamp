-- Constant-power pan.
--
-- The two channels follow a quarter circle, so sweeping across the image
-- keeps its loudness steady.
--
-- The trigonometry is in the graph, so the control is live: the cos and sin
-- nodes are fed only by a parameter, and the engine runs them once per control
-- tick.

plugin {
  id      = "nl.mattix.andamp.pan",
  name    = "Pan",
  version = "1.0.0",
  author  = "Andamp",
  about   = "Places the signal across the image at constant power.",
}

local pan = param.number {
  id      = "pan",
  min     = 0,
  max     = 1,
  default = 0.5,
}

function build(g, ctx)
  local angle = g.mul(pan, math.pi / 2)
  local gains = {
    g.math { input = angle, fn = "cos" },
    g.math { input = angle, fn = "sin" },
  }

  local out = {}
  for ch = 0, ctx.channels - 1 do
    out[ch] = g.mul(g.input(ch), gains[(ch % 2) + 1])
  end
  return out
end
