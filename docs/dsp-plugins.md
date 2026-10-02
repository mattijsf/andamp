# DSP plug-ins: design note

An effect plug-in is a `.lua` file. The script declares its parameters and returns a graph
of built-in primitives. Kotlin checks that graph, compiles it and runs it. No Lua runs while
audio is playing.

What a plug-in author writes is in [dsp-plugin-spec.md](dsp-plugin-spec.md). This note
describes why the design has this shape and how the host implements it.

## Why Lua does not process samples

The Lua runtime is LuaJ 3.0.1, a pure-JVM interpreter, so the app needs no native code for
it. LuaJ is too slow to run on the audio path, for three reasons.

**Throughput.** One-time measurements on a Pixel 9 Pro, 44.1 kHz stereo:

| Workload | Speed |
| --- | --- |
| Two biquads and a gain, in Kotlin | 3104x real time |
| The same two biquads, in Lua | 7.9-8.3x real time |
| A ten-band EQ, in Lua | 2.5-3.6x real time |
| A Freeverb-shaped reverb, in Lua | 1.7x real time, with a 29.5 ms 99th-percentile buffer |

Handing Lua a whole buffer instead of one sample at a time does not change this. In the
same measurements a per-buffer call over the host's `float[]` was 7% faster than a
per-sample call. The time goes to LuaJ's arithmetic, and the call boundary is a small part
of it.

**Allocation.** LuaJ carries every number as a boxed `LuaDouble`. On a desktop JVM that
measured 24 bytes of heap per arithmetic operation, and 194 MB/s of garbage for the
ten-band EQ. An effect written in Lua would allocate continuously on the playback thread.

**The watchdog.** A host for other people's scripts needs a way to stop one that loops
forever, and LuaJ's only mechanism is its debug hook. In the same measurements, loading
`DebugLib` into the globals slowed the ten-band EQ from 3.59x to 1.96x real time. A
deadline short enough to protect playback also stops effects that are only slow: with a
15 ms budget per buffer the Lua reverb was stopped on 136 of 500 buffers.

The effect processors run on ExoPlayer's playback thread, which writes into an `AudioTrack`
buffer ahead of the hardware. One late buffer is therefore not audible. A sustained
throughput deficit or a steady allocation rate is, and those are what Lua on the audio
path produces.

## The design

The script runs on a worker thread, once for each audio format, because a graph is built
for one sample rate and channel count. `PluginLoader` returns a `PluginSpec`: the metadata,
the parameters, the widget tree, the presets and a `GraphSpec`. All of it is plain data. No
Lua object is kept, and the interpreter's `Globals` is discarded when the load ends.

```kotlin
data class GraphSpec(
    val api: Int = API,
    val sampleRate: Int,
    val channels: Int,
    val params: List<GraphParam> = emptyList(),  // id, range, default, smoothing, readout
    val nodes: List<NodeSpec> = emptyList(),     // primitive, edges, constants, rate tag
    val outputs: List<Int> = emptyList(),        // the node feeding each channel
    val delayFrames: Int = 0,                    // total delay memory, checked against the nodes
)
```

A `GraphSpec` carries no evaluation order, no slot assignment and no buffer size. The
compiler derives all of them, so a script cannot crash the audio thread by declaring them
wrong.

Because Lua runs only at build time:

- a script that never finishes is a failed load,
- there is no watchdog cost while audio plays,
- `build` may loop, branch and call functions freely, because the result is unrolled into
  nodes before playback.

There are three rates:

| Rate | What happens | What runs it |
| --- | --- | --- |
| Build | the script declares parameters and widgets and returns the graph | Lua, on a worker thread, within 3 s, once per audio format |
| Control | a parameter walks toward the value its slider was set to | Kotlin, on the playback thread, every 32 frames |
| Audio | the graph renders | Kotlin, on the playback thread, every frame |

Every plug-in parameter is a live value. Moving a slider retunes existing nodes through a
parameter slot the engine reads, with no rebuild, no Lua and no allocation. A plug-in has
no structural parameters in api 1: `ctx` carries only the sample rate and the channel
count, so a plug-in with modes builds every arrangement and selects between them with
arithmetic. The built-in Modulation effect does have one. Its mode chooses which nodes
exist, and changing it rebuilds that effect's graph off the audio thread while the rack
fades through the dry signal.

The interface is declared in the same way. The script returns a widget tree of groups,
sliders, toggles, choices and labels, and the host draws it in Compose with the app's own
theme. A plug-in cannot draw anything itself.

## The engine

- `GraphValidator` checks a `GraphSpec` and returns every error it finds.
- `GraphCompiler` orders the nodes, infers each node's rate and refuses a spec whose rate
  tag disagrees. It lowers the graph onto a `Tape`: parallel integer arrays of opcodes and
  operand slots over one float bus, with one arena for all delay lines.
- `GraphEngine` runs the tape with one `when` over the opcode per instruction. It
  allocates its bus, state and delay memory when it is built and nothing while it runs.
- Instructions fed only by parameters and constants sit at the front of the tape and run
  once per control tick of 32 frames (`GraphParam.CONTROL_PERIOD`). The rest run every
  frame.
- `BuiltInStages.Graphed` wraps an engine as a `DspStage`, so a plug-in is one more stage
  in the list `DspAudioProcessor` walks.

A change to which effects run is built off the playback thread. The playback thread
receives the new list of stages as one reference and switches to it at the bottom of a
20 ms fade through the dry signal.

The rack has one slot per plug-in `id`, in signal order: the first slot receives the audio
first. The `id` is also the key the slot's switch and values are stored under.

The engine also has a block runner (`BlockRunner`) that runs one instruction over a block
of up to 128 frames. The block is no longer than the shortest declared delay, and a graph
with a feedback pair or a computed delay time runs one frame at a time
(`GraphSpec.safeBlock`). `DspAudioProcessor` hands stages one frame at a time, so the
frame runner is what runs on the audio path.

### The primitive set

The catalog is the table in section 6 of the spec. It is the smallest set that expresses
the hand-written Karaoke, Modulation, Reverb and Pan stages kept in `:backend:media3`'s
test sources: arithmetic and crossfades, biquads in the RBJ shapes, a one-pole, a
first-order all-pass, delays with a build-time maximum and a live length, a second reader
on a delay line, an LFO, an envelope follower, seeded noise, soft and hard clipping, a
sanitizer (`g.sanitise`) and named feedback pairs. Schroeder all-passes and damped combs
are built from these.

The three built-in effects (`BuiltInGraphs`) and the six bundled plug-ins are graphs over
the same catalog. The bundled plug-ins are `.lua` files and load through the same loader
and sandbox as a listener's file.

The primitive set is a public interface once somebody else ships a plug-in. The schema is
versioned (`api = 1`), an unknown primitive or option is refused at load, and removing a
primitive is a breaking change.

### What a graph cannot express

- A per-sample nonlinearity beyond what `g.math`, `g.softclip` and arithmetic compose,
  such as a tube model or a shaper with hysteresis.
- A data-dependent branch per sample, other than one written as arithmetic.
- Spectral effects. There is no FFT primitive.
- Anything that changes the sample count, such as time stretch or pitch shift. The engine
  takes one frame in and gives one frame out.
- An interface the plug-in draws itself.

There is no generic per-sample expression node, and no `process(sample)` or
`process(buffer)` callback into Lua. Either would put an interpreter back on the audio
path.

## What it costs

All figures are multiples of real time at 44.1 kHz stereo.

`GraphCostTest` times the engine against the hand-written stages on the JVM, with the test
sources' graph versions of Karaoke, Modulation (phaser), Reverb and Pan. It prints the
ratio for each graph and for the four together, and asserts that the stages run above 30x
and the graphs, one frame at a time, above 10x. In one run on a desktop JVM the four stages
measured 67x, the graphs 23x per frame and 39x per block.

`IdleChainCostTest` times ExoPlayer's whole processor chain (equalizer, balance and rack)
on the JVM. It asserts more than 100x with every effect off and more than 15x with the
three built-in effects on.

`GraphCostOnDeviceTest` runs the three built-in effects and the six bundled plug-ins
together on a device, one frame at a time, and asserts more than 4x.

`PluginLoadCostOnDeviceTest` loads each bundled plug-in on a device and asserts that the
first load finishes inside the 3 s deadline.

## Limits and safety

**In the graph.** Checked by `GraphValidator` before anything is allocated:

- at most 512 nodes,
- at most 262,144 frames of delay memory in total,
- a cycle must contain a delay line or a feedback pair.

Every value written into a delay line or a feedback pair has non-finite values zeroed and
is held to ±16 (`GraphValidator.LOOP_CEILING`). A loop whose gain is on a slider can
therefore grow no further than that.

**In the loader.** `Sandbox` and `PluginLoader`:

- The globals are built by adding libraries: `JseBaseLib`, `PackageLib`, `StringLib`,
  `TableLib`, `JseMathLib` and `Bit32Lib`, plus the source compiler. `PackageLib` is loaded
  because the other libraries register through it. Afterwards `package`, `io`, `os`,
  `require`, `debug`, `load`, `loadstring`, `loadfile`, `dofile`, `rawget`, `rawset`,
  `rawequal`, `rawlen`, `setmetatable`, `getmetatable`, `collectgarbage`, `coroutine`,
  `newproxy` and `_G` are set to nil. `CoroutineLib` and `luajava` are never loaded.
- The source is limited to 256 KiB, checked before anything is parsed. Only source text is
  compiled.
- The load has a deadline of 3 s. The host enforces it by no longer waiting for the worker
  thread and marking the load abandoned. The interpreter's per-instruction hook then throws
  a Java `Error` on the worker.

**LuaJ caveats.**

- `pcall` catches `LuaError` and `Exception`. That is why the deadline is signaled with an
  `Error`: a script that wraps its loop in `pcall` cannot absorb it.
- The hook runs between instructions. A script inside one long library call, such as a
  large `string.rep`, runs until that call returns.
- LuaJ has no way to limit a script's memory.
- The metatable all strings share is a static field (`LuaString.s_metatable`), common to
  every `Globals` in the process. `Sandbox` fills it once with a lookup into the string
  library of the load running on the calling thread, so a plug-in that changes `string`
  changes it only for itself.
- LuaJ runs each coroutine on its own Java thread. The sandbox has no coroutines.

**What a plug-in can reach.** Declared parameters, DSP primitives and math: no network,
no file system and no device identifiers. Plug-ins reach the app as bundled files, through
the file picker, or by being opened or shared from another app.

## What the tests check

- `GraphValidationTest`, `GraphCompilerTest`, `GraphEngineTest`, `StatefulPrimitiveTest`
  and the other tests in `:core:dsp` render fixed graphs over fixed input and assert the
  samples and the errors.
- `CatalogueMatchesSpecTest` compares the primitive table in the spec with the primitives
  the host accepts.
- `GraphParityTest` compares the graph versions of the Karaoke, Modulation, Reverb and Pan
  stages with those stages. Modulation, Reverb and Pan must match with no tolerance.
  Karaoke may differ by 1e-7, because the graph's crossfade is exact at its ends and the
  stage's is not.
- `BlockEquivalenceTest` asserts that the block runner and the frame runner produce the
  same audio.
- `PluginLoaderTest`, `PluginParameterTest`, `UiTreeTest` and `ExamplePluginsTest` in
  `:core:plugin` cover the loader, the sandbox and the example files.
- `BundledPluginTest` runs effects written in Lua, and compares a pan and a karaoke written
  in Lua with the Kotlin stages.

## Sources

In this repository:

- `core/dsp`: `Primitive.kt`, `GraphSpec.kt`, `GraphValidator.kt`, `GraphCompiler.kt`,
  `Tape.kt`, `GraphEngine.kt`, `BlockRunner.kt`
- `core/plugin`: `PluginLoader.kt`, `Sandbox.kt`, `GraphBinding.kt`, `UiBinding.kt`
- `backend/media3/src/main/java/nl/mattix/andamp/backend/media3/`: `DspAudioProcessor.kt`,
  `dsp/DspStage.kt`, `dsp/BuiltInStages.kt`, `graph/BuiltInGraphs.kt`,
  `graph/BundledPlugins.kt`
- `backend/media3/src/test/java/nl/mattix/andamp/backend/media3/dsp/`: the hand-written
  stages (`KaraokeStage.kt`, `ModulationStage.kt`, `ReverbStage.kt`, `PanStage.kt`)

The design follows SuperCollider, where the language emits a graph (a SynthDef) and the
server runs it, and libpd, where user patches run on native primitives. See
[SuperCollider's synth definition file format](https://doc.sccode.org/Reference/Synth-Definition-File-Format.html).
