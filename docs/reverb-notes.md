# Reverb notes

How the built-in Reverb effect is designed, and how `ReverbQualityTest` judges it.

Sources: Julius O. Smith, *Physical Audio Signal Processing* (CCRMA); Jot and Chaigne on
feedback delay networks; Dattorro, *Effect Design Part 1* (JAES 45(9), 1997); Schroeder and
Logan; Abel and Huang on echo density; Sean Costello's writing on reverb design (Valhalla
DSP).

## The design

`BuiltInGraphs.reverb` is a feedback delay network, built per channel:

- The input is summed to mono.
- Six delay lines at Freeverb's comb lengths (1116 to 1617 samples at 44.1 kHz). Size
  scales them together, from 0.35x to 1.6x.
- In each line's loop: a one-pole lowpass for damping (Air) and a short Schroeder all-pass
  (131 to 353 samples).
- Each line has its own feedback gain, computed from its length and from Decay (T60, 0.3 to
  6 s).
- The lines feed each other through a Householder matrix.
- The sum of the lines goes through three Schroeder all-passes in series.
- Four early-reflection taps read one delay line in front of the network. Near sets the
  balance between them and the tail.
- A soft ceiling limits the wet signal before it is mixed with the dry signal (Level).

The reference it is compared with is `ReverbStage`, in `:backend:media3`'s test sources. It
is the parallel comb bank of Freeverb: the same six combs, each feeding back only into
itself with one shared gain of 0.84, followed by the same three all-passes.

## What the network changes compared with parallel combs

1. **Echo density grows over time.** A room's echoes multiply as the sound keeps
   reflecting. Parallel combs followed by all-passes produce a constant echo rate. An
   all-pass inside a feedback loop is applied again on every round trip, so the density
   builds. Each line has one in its loop.
2. **Every line decays at the same rate.** With one shared feedback gain, a line's T60 is
   proportional to its length, so the longest line (1617 samples) rings 1.45 times as long
   as the shortest (1116), and the tail changes timbre as it fades. Jot's rule gives each
   line a gain of g = a^M for its length M, which gives all lines the same T60. Size then
   changes the room and Decay changes how long it rings, independently.
3. **The lines mix.** A parallel comb bank is a feedback delay network with a diagonal
   feedback matrix: six independent resonators. A Householder matrix feeds every line from
   every other line and is lossless. It costs one sum and one subtraction per line:
   y = x - (2/N) * sum(x).
4. **The all-passes are true all-passes.** Freeverb's all-pass structure is not exactly
   all-pass. The ones here use the Schroeder form, v = x + g * v(n-M) and
   y = v(n-M) - g * v.

## Mode density

Schroeder and Logan's criterion for a smooth tail is M >= 0.15 * T60 * Fs samples of total
delay in the loops. At 44.1 kHz the six lines and their in-loop all-passes hold about 9,300
samples at the default Size, which satisfies the criterion up to a T60 of about 1.4 s. Size
scales the lines and not the in-loop all-passes, so the loops hold about 4,200 samples at
the smallest Size and 14,300 at the largest: enough for about 0.6 s and 2.2 s. Decay reaches
6 s at any Size, so a small room with a long decay is below the criterion.

## Modulating the line lengths

`BuiltInGraphs.reverb` takes a `wander` argument that moves each line's read position
slowly, by that many samples. It defaults to zero, and the shipped effect does not use it.
At zero the graph has whole-sample reads and no oscillators.

A wandering line needs an oscillator and an interpolated read, which cost throughput.
Linear interpolation is also a lowpass, and inside a feedback loop it is applied on every
round trip. `ReverbQualityTest` asserts that wandering by two samples does not raise the
flatness of any of five bands of the tail by 0.05 or more.

## Cost

The network uses delays, one-poles and arithmetic from the existing primitive set.
`IdleChainCostTest` asserts that the rack with all three built-in effects on runs above 15x
real time on the JVM.

## How it is tested

`ReverbQualityTest` measures impulse responses of the network and of `ReverbStage`, with
the same settings:

- **Decay rate early and late in the tail.** A single decay rate is a straight line on a dB
  scale. The test asserts that the slope from 100 to 500 ms and the slope from 500 ms to 1 s
  differ by less than 25%, and by less, relatively, than those of the parallel combs.
- **The Decay control.** With Decay at 2 s, the measured T60 must be within 0.7 s of 2 s.
- **Normalized echo density** (Abel and Huang): the fraction of samples in a window that
  lie outside one standard deviation, divided by that fraction for Gaussian noise. It
  reaches 1 when the tail is as dense as noise. The test asserts that the density from 50
  to 250 ms is higher than that of the parallel combs, and that it grows more between
  20-50 ms and 200-250 ms than theirs does.
- **Spectral flatness of the tail, per band**, at 200 ms, in five octave bands from 250 Hz
  to 8 kHz (`Spectrum.bandFlatness`). A tail with audible pitches has a spectrum with peaks
  and a low flatness. The test asserts that the network's worst band is flatter than the
  parallel combs' worst band.
- **Stability.** Every sample of a two-second response is finite, and the tail decays.

Flatness is measured per band because flatness over the whole spectrum also depends on
brightness: noise with little high-frequency content scores much lower than white noise
although neither has a pitch. `SpectralFlatnessTest` covers both measures.

These tests check that the reverb has the properties described here. Whether it sounds good
is judged by listening.
