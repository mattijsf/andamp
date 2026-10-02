-- Karaoke: the lead vocal removed by cancelling what both channels share.
--
-- A lead vocal is mixed in the center, so it is the part of the signal the two
-- channels have in common. Subtracting one from the other removes the singer,
-- and the kick and the bass with it, so the mid is lowpassed and mixed back
-- in. The sides go back in antiphase, which keeps the image of what was panned
-- wide.
--
-- Every number that shapes the sound is a control, including the cutoff.
-- Everything feeding the cutoff is a parameter, so the engine computes it once
-- per control tick.

plugin {
  id      = "nl.mattix.andamp.karaoke",
  name    = "Karaoke",
  version = "1.0.0",
  author  = "Andamp",
  about   = "Cancels what both channels share, where a lead vocal sits.",
}

local level = param.number {
  id = "level", min = 0, max = 1, default = 1,
  help = "How much of the cancellation is used. 0 hands back the original audio untouched.",
}

local filter = param.number {
  id = "filter", min = 0, max = 1, default = 0.35,
  help = "Cutoff of the mono low end that is kept, 60 Hz to 500 Hz.",
}

local band = param.number {
  id = "band", min = 0, max = 1, default = 0.5,
  help = "Rolloff of that kept low end, 24 dB/oct at 0 to 12 dB/oct at 1.",
}

local width = param.number {
  id = "width", min = 0, max = 1, default = 0,
  help = "Mixes the untouched stereo back over the result, which is a floor on how much voice goes.",
}

-- 60 Hz to 500 Hz, logarithmic: the middle of the slider is at 173 Hz, and the
-- default of 0.35 at 126 Hz.
local KEEP_MIN_HZ = 60
local KEEP_OCTAVES = 3.0588937   -- log2(500 / 60)
local BUTTERWORTH_Q = 0.70710677
local MAX_CUTOFF_FRACTION = 0.45
local CEILING = 1.5
local MAX_DRY = 0.5

function build(g, ctx)
  local left, right = g.input(0), g.input(1)
  local mid = g.mul(g.add(left, right), 0.5)
  local side = g.mul(g.sub(left, right), 0.5)

  local cutoff =
    g.min(
      g.mul(KEEP_MIN_HZ, g.math { input = g.mul(filter, KEEP_OCTAVES), fn = "exp2" }),
      ctx.sampleRate * MAX_CUTOFF_FRACTION
    )

  -- one section is 12 dB/oct and the two cascaded are 24; crossfading their
  -- outputs sweeps the slope continuously
  local twelve = g.biquad { input = mid, kind = "lowpass", freq = cutoff, q = BUTTERWORTH_Q }
  local twentyFour = g.biquad { input = twelve, kind = "lowpass", freq = cutoff, q = BUTTERWORTH_Q }
  local bass = g.crossfade(twentyFour, twelve, band)

  local stereoBack = g.mul(width, MAX_DRY)
  local wet = { g.add(side, bass), g.sub(bass, side) }

  local out = {}
  for ch = 0, ctx.channels - 1 do
    local dry = (ch % 2 == 0) and left or right
    local widened = g.crossfade(wet[(ch % 2) + 1], dry, stereoBack)
    out[ch] = g.clip { input = g.crossfade(dry, widened, level), min = -CEILING, max = CEILING }
  end
  return out
end
