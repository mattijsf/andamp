-- Stereo to mono.
--
-- A plug-in with no controls and no UI, only a graph. It checks that a plug-in
-- which declares nothing still loads, builds and runs.

plugin {
  id      = "nl.mattix.andamp.mono",
  name    = "Mono",
  version = "1.0.0",
  author  = "Andamp",
  about   = "Sums the channels and sends the same signal to each.",
}

function build(g, ctx)
  local sum = g.input(0)
  for ch = 1, ctx.channels - 1 do
    sum = g.add(sum, g.input(ch))
  end
  local mono = g.mul(sum, 1 / ctx.channels)

  local out = {}
  for ch = 0, ctx.channels - 1 do
    out[ch] = mono
  end
  return out
end
