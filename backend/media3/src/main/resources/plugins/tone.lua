-- Bass & Treble: two shelves, and nothing else.
--
-- Two shelves at fixed corners, like the tone controls of an amplifier: 100 Hz
-- and 8 kHz.
--
-- A boost can take a loud track past full scale. There is no clipper here: the
-- output is turned down by the larger of the two boosts, in dB. So a boost
-- changes the tonal balance and does not make the whole track louder. A cut
-- takes no headroom.

plugin {
  id      = "nl.mattix.andamp.tone",
  name    = "Bass & Treble",
  version = "1.0.0",
  author  = "Andamp",
  about   = "RBJ low shelf at 100 Hz and high shelf at 8 kHz (Q = 1), ±12 dB; output reduced by the larger boost to keep headroom.",
}

local bass = param.number {
  id = "bass", name = "Bass", min = -12, max = 12, default = 0, unit = "db",
  help = "Low-shelf gain at 100 Hz. A boost lowers the output by the same number of dB, so a track that fitted still fits.",
}
local treble = param.number {
  id = "treble", name = "Treble", min = -12, max = 12, default = 0, unit = "db",
  help = "High-shelf gain at 8 kHz. A boost lowers the output by the same number of dB, so a track that fitted still fits.",
}

presets {
  { name = "Flat",     values = { bass = 0, treble = 0 } },
  { name = "Warm",     values = { bass = 4, treble = -2 } },
  { name = "Bright",   values = { bass = 0, treble = 4 } },
  { name = "Rich",     values = { bass = 6, treble = 3 } },
}

local BASS_HZ   = 100
local TREBLE_HZ = 8000
function build(g, ctx)
  -- the headroom a boost needs, taken off the output: 0 dB while nothing is boosted
  local headroom = g.mul(g.max(g.max(bass, treble), 0), -1)
  local out = {}
  for ch = 0, ctx.channels - 1 do
    local low = g.biquad { input = g.input(ch), kind = "lowshelf", freq = BASS_HZ, q = 1, gainDb = bass }
    local toned = g.biquad { input = low, kind = "highshelf", freq = TREBLE_HZ, q = 1, gainDb = treble }
    out[ch] = g.gain { input = toned, db = headroom }
  end
  return out
end
