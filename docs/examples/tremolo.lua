-- Tremolo: an LFO on the volume.
--
-- The example that shows an oscillator. It sways the volume up and down, and
-- its three controls change the speed, the depth and the stereo spread while
-- the music plays.

plugin {
  id      = "nl.mattix.andamp.example.tremolo",
  name    = "Tremolo",
  version = "1.1.0",
  author  = "Andamp examples",
  about   = "Sways the volume up and down at a speed you pick.",
}

local rate = param.number {
  id = "rate", name = "Rate", min = 0.1, max = 12, default = 4, unit = "hz",
  help = "How often the volume sways, in cycles per second.",
}
local depth = param.number {
  id = "depth", name = "Depth", min = 0, max = 1, default = 0.5,
  help = "How far it sways. At 0 nothing happens; at 1 it reaches silence.",
}
local stereo = param.number {
  id = "stereo", name = "Stereo", min = 0, max = 1, default = 0,
  unit = "pc", display = { scale = 100 },
  help = "0 sways both sides together; 100 % sways them in turn, so the sound moves from side to side.",
}

function build(g, ctx)
  local out = {}
  for ch = 0, ctx.channels - 1 do
    -- one oscillator per channel, the odd channels up to half a cycle behind:
    -- at Stereo 0 they move together, at 100 % one is loud while the other is
    -- quiet. The phase is wired to the Stereo control rather than set once, so
    -- moving the slider shifts them apart while the music plays.
    -- g.lfo takes its options as one table, in braces.
    local sweep = g.lfo { rate = rate, shape = "sine", phase = g.mul(stereo, (ch % 2) * 0.5) }
    -- a sine from -1..1, moved to 0..1, then scaled by depth: at depth 0 the
    -- volume stays put, at 1 it dips to silence.
    local half = g.mul(g.add(sweep, 1), 0.5)
    local amount = g.sub(1, g.mul(depth, g.sub(1, half)))
    out[ch] = g.mul(g.input(ch), amount)
  end
  return out
end
