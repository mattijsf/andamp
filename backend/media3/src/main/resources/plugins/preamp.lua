-- Preamp: louder or quieter, before every effect.
--
-- The rack keeps it first, so it sets the level every effect after it works
-- with: turned down, a boost further along has headroom; turned up, a quiet
-- recording drives the effects as a loud one does.
--
-- It is a gain with no clipper. The stages run in float, so a boost here is
-- not clipped until the output stage, where the peak limiter holds it when it
-- is switched on.

plugin {
  id      = "nl.mattix.andamp.preamp",
  name    = "Preamp",
  version = "1.0.0",
  author  = "Andamp",
  about   = "Broadband gain, ±12 dB, applied to every channel ahead of the rack.",
}

local gain = param.number {
  id = "gain", name = "Gain", min = -12, max = 12, default = 0, unit = "db",
  help = "Gain in dB before all effects. Above 0 dB the signal can exceed 0 dBFS; the chain runs in float, so nothing is clipped until the output stage, where the peak limiter can hold it.",
}

presets {
  { name = "−6 dB", values = { gain = -6 } },
  { name = "0 dB",  values = { gain = 0 } },
  { name = "+6 dB", values = { gain = 6 } },
}

function build(g, ctx)
  local out = {}
  for ch = 0, ctx.channels - 1 do
    out[ch] = g.gain { input = g.input(ch), db = gain }
  end
  return out
end
