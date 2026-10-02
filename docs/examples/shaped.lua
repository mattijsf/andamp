-- Shaped: the example that asks for a layout.
--
-- The other examples let the host lay their controls out. This one declares a
-- `ui` block: two titled groups, the second switched by a toggle in its own
-- header. A switched-off group is grayed, and stays visible.

plugin {
  id      = "nl.mattix.andamp.example.shaped",
  name    = "Shaped",
  version = "1.0.0",
  author  = "Andamp examples",
  about   = "A tone control and a switched-in resonance, to show what a layout looks like.",
}

local tilt = param.number {
  id = "tilt", name = "Tilt", min = -12, max = 12, default = 0, unit = "db",
  help = "Lifts the top and drops the bottom, or the other way round below zero.",
}
local hardness = param.number {
  id = "hardness", name = "Hardness", min = 0, max = 1, default = 0.2,
  help = "How much the peak is allowed to ring.",
}

local ringOn = param.toggle { id = "ringOn", name = "Resonance", default = false }
local ringFreq = param.number {
  id = "ringFreq", name = "Where", min = 200, max = 6000, default = 1200, unit = "hz",
  help = "The frequency the resonance sits on.",
}
local ringGain = param.number {
  id = "ringGain", name = "How much", min = 0, max = 12, default = 4, unit = "db",
}

ui {
  group("Tone", {
    slider(tilt),
    slider(hardness, { label = "Ring" }),
  }),
  group("Resonance", {
    slider(ringFreq),
    slider(ringGain),
    label("It sits on top of the tone, so a lot of both is a lot."),
  }, { enabledBy = ringOn }),
}

function build(g, ctx)
  local out = {}
  for ch = 0, ctx.channels - 1 do
    local dry = g.input(ch)
    local tilted = g.biquad { input = dry, kind = "highshelf", freq = 900, q = 0.707, gainDb = tilt }
    -- the bypass is written here: the host grays the group, and refuses a
    -- plug-in whose graph never reads the group's toggle
    local ringing = g.biquad { input = tilted, kind = "peaking", freq = ringFreq, q = 4, gainDb = ringGain }
    local damped = g.crossfade(tilted, ringing, hardness)
    out[ch] = g.crossfade(tilted, damped, ringOn)
  end
  return out
end
