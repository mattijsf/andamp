-- Width: how wide the stereo image is.
--
-- Mid and side: what the two channels share, and what differs between them.
-- Only the side is scaled; there is no delay and no filter. At 0 both speakers
-- play the same thing, at 100 % the track is untouched, and above that what
-- was to one side is pushed further out.
--
-- Widening raises the peak of a hard-panned sound to (1 + width) / 2 of what it
-- was, so above 100 % both channels are scaled by 2 / (1 + width). Below 100 %
-- the level is unchanged.

plugin {
  id      = "nl.mattix.andamp.width",
  name    = "Width",
  version = "1.0.0",
  author  = "Andamp",
  about   = "Mid/side width: the side signal scaled 0–200 %; above 100 % the output is reduced by 2 / (1 + width) to keep headroom.",
}

local width = param.number {
  id = "width", name = "Width", min = 0, max = 2, default = 1,
  unit = "pc", display = { scale = 100 },
  help = "Side level relative to mid. 0 % is mono, (L + R) / 2 on both channels; 100 % leaves the signal unchanged; 200 % doubles the side.",
}

presets {
  { name = "Mono",   values = { width = 0 } },
  { name = "Narrow", values = { width = 0.6 } },
  { name = "Normal", values = { width = 1 } },
  { name = "Wide",   values = { width = 1.5 } },
}

function build(g, ctx)
  -- one channel has nothing to widen
  if ctx.channels < 2 then
    local out = {}
    for ch = 0, ctx.channels - 1 do out[ch] = g.input(ch) end
    return out
  end
  local left, right = g.input(0), g.input(1)
  local mid = g.mul(g.add(left, right), 0.5)
  local side = g.mul(g.sub(left, right), g.mul(width, 0.5))
  -- 2 / (1 + width), from 100 % up: 1 at 100 %, 2/3 at 200 %
  local room = g.div(2, g.add(1, g.max(width, 1)))
  local out = {
    [0] = g.mul(g.add(mid, side), room),
    [1] = g.mul(g.sub(mid, side), room),
  }
  -- anything past the first two channels is left as it came
  for ch = 2, ctx.channels - 1 do out[ch] = g.input(ch) end
  return out
end
