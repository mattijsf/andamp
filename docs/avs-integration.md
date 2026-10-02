# AVS

`:visualizer:avs` is Winamp's Advanced Visualization Studio rebuilt in Kotlin. It parses real
`.avs` presets, runs them on an `IntArray` framebuffer, and is the plug-in window's default
engine (`VisPlugin.Avs`), beside projectM (`VisPlugin.Milkdrop`, see
[projectm-integration.md](projectm-integration.md)). Native code is confined to the ns-eel
evaluator, which ships as `libandamp_avs.so`.

## What AVS is

Winamp shipped two unrelated visualizers, AVS and MilkDrop, both loaded through the same
`vis_*.dll` plug-in API:

| | AVS | MilkDrop |
|---|---|---|
| Format | `.avs`, binary | `.milk`, ini-like text |
| Model | a chain of components | one equation set driving frame feedback |
| Renders | CPU, 2D framebuffer | GPU |
| Scripting | ns-eel per component | ns-eel per frame and per vertex, plus shaders |

An AVS preset is a stack of components, each with its own binary configuration. projectM
does not run AVS presets, so AVS is a second engine.

[avs-census.py](avs-census.py) counts which components the presets in a directory use, and
how many presets run completely with the most common N components. It reads the
component-id table from a checkout of
[grandchild/AVS-File-Decoder](https://github.com/grandchild/AVS-File-Decoder).

## The format

`.avs` is a header plus a flat component stream, nested through Effect Lists.

```
"Nullsoft AVS Preset 0.2\x1a"   24 bytes
clearEveryFrame                  1 byte
components...
```

Each component:

```
code    uint32     built-in id; 0xFFFFFFFE = Effect List; >= 16384 = APE
name    32 bytes   APEs only
size    uint32
body    size bytes
```

An Effect List's body carries its own config (enabled, clear-frame, in/out blend mode, in/out
buffer, on-beat gating), optionally an "AVS 2.8+ Effect List Config" block with an ns-eel
snippet, then its children as a nested component stream. Other bodies are a flat sequence of
typed fields - `Int32`, `Color`, `Bool`, `NtString`, bitfields.

The component ids (`AvsComponents`) and the framing (`AvsParser`) are transcribed from
AVS-File-Decoder's `src/lib/components.ts`. The project is not a run-time dependency. The
parser keeps a component's body as bytes, and the component's own reader decodes it. Layout
details:

- Colors in a file are `0x00RRGGBB`, not Windows `COLORREF`s.
- The 8-byte blend field (the decoder's `Map8`) is two int32s. Both blend readers are in
  `BodyFields.kt`.
- Custom BPM's mode is three radio flags, one int32 per button, last nonzero wins.
- The 2.8+ Effect List marker is 36 bytes. Its code block is skipped and not run.
- Dynamic Shift stores its sections init/frame/beat, not in Super Scope's order.
- Buffer numbers are one-based in the file, in Buffer Save and in the Effect List's buffer
  fields alike; `AvsBuffers` is the zero-based bank of eight both index into.

## Engine model

AVS is a framebuffer pipeline:

1. A frame starts with the previous frame's buffer, or cleared if `clearEveryFrame`.
2. Each component reads and writes that buffer in order. **Render** components draw into it
   (Super Scope, Moving Particle, Starfield); **Trans** components transform what is there
   (Blur, Movement, Invert, FadeOut); **Misc** components carry state (Buffer Save, Set Render
   Mode, Custom BPM).
3. An Effect List can render its children into a canvas of its own and blend the result back,
   under one of fourteen blend modes on the way in and on the way out (`AvsBlendMode`).

`AvsEngine.renderList` follows vis_avs's `e_effectlist.cpp`: a REPLACE-in/REPLACE-out list has no canvas and its children draw straight
onto the frame below; any other list keeps its canvas across frames and frees it when it goes
disabled; each list resets the render mode for its children and restores the outer one after.

Movement and Dynamic Movement are a warp mesh (`WarpMesh`): ns-eel evaluated per grid vertex,
producing where each pixel is read from, interpolated between vertices - the same shape as
MilkDrop's "per-pixel" equations. AVS's built-in Movement effects are themselves scripts, so a
preset picking one by number and a preset carrying its own take the same path; Dynamic
Movement differs only in rebuilding the mesh each frame.

The beat is shared render state, because a Custom BPM component in a preset rewrites the
beat for every component after it.

## What runs

`AvsEngine.supported` lists the components this build runs. It is the key set of
`AvsEngine.READERS`, the map the engine builds renderers from. It holds 36 components: 34 of
AVS's 46 built-ins, plus two APEs (Color Reduction, Channel Shift). Effect List is handled by
the engine itself and is not in the map. The built-ins not
implemented are Water, Water Bump, Bump, Oscilliscope Star, Dot Plane, Dot Fountain, Rotating
Stars, Scatter, SVP, Text, Picture and AVI.

- A component the build lacks is named in `AvsEngine.unimplemented` and skipped; the rest of
  the preset still draws.
- A scripted component reports what would not compile and keeps running what did
  (`AvsScripted`); its errors are listed with the missing components.
- The engine builds one runtime node per *position* in the preset, so two identical stateful
  components run as two instances.
- Every component's code runs in its own ns-eel context, as each AVS component had its own VM
  (`SharedEelTest`).
- The blend modes, the blur kernels, the Effect List's frame flow and the beat detector are
  transcribed from vis_avs, and the code says so where it is. Blur differs
  from the original only in the outermost pixel ring, where a missing neighbor reads the
  center pixel.

## The evaluator

ns-eel is the one piece that is not Kotlin. `Eel` wraps
[projectm-eval](https://github.com/projectM-visualizer/projectm-eval), an MIT ns-eel2
reimplementation, pinned as this module's own submodule at
`visualizer/avs/third_party/projectm-eval` and linked statically into `libandamp_avs.so`
together with `avs_eel_jni.cpp`. Its API is the shape a component binding needs:

```c
struct projectm_eval_context* projectm_eval_context_create(...);
PRJM_EVAL_F* projectm_eval_context_register_variable(ctx, const char* name);
struct projectm_eval_code* projectm_eval_code_compile(ctx, const char* code);
PRJM_EVAL_F  projectm_eval_code_execute(code_handle);
```

A binding registers a variable, gets a `double*`, writes it, runs compiled code and reads the
variable back.

The module has its own copy of the evaluator because `libprojectM-4.so` does not export
projectM's. The two visualizer modules are therefore independent, and the AVS module does not
link an LGPL library. The module's `CMakeLists.txt` disables finding flex and bison, so the
build uses the generated parser that upstream ships and does not write into the submodule.

## Module layout

```
visualizer/avs/
  src/main/cpp/          avs_eel_jni.cpp, CMakeLists.txt: the ns-eel binding
  src/main/kotlin/…/avs/
    AvsParser, AvsPreset, AvsComponents, BodyFields     reading a preset
    AvsEngine, AvsFrame, AvsBuffers, AvsBlend           the pipeline
    *Components.kt, *Renderer.kt, WarpMesh, AvsDraw     the components
    Eel                                                 the evaluator binding
    AvsAudio, AvsAudioFrame, AvsBeat                    what components hear
    AvsView, AvsPlaylist, AvsIdlePreset                 what the app sees
    author/                                             writing presets: AvsWriter, AndAmpPack
  third_party/projectm-eval   git submodule
```

The parser and the pipeline use no `android.*` classes, so they are tested on the JVM.

## In the app

- `AvsView` is a `VisualizerView` (from `:visualizer:core`, shared with `ProjectMView`) that
  renders on its own thread and paints the engine's `IntArray` into a `Bitmap`, drawn onto the
  surface's canvas without filtering. The engine renders at most 196 pixels wide, keeping the
  surface's shape, and is scaled up without smoothing.
- `AvsPlaylist` walks a directory of `.avs` files, with shuffle and no timer.
- `AvsAudio` keeps a ring of the app's PCM (`PcmSource`, from `:core:player`) and cuts AVS's
  classic 576-sample window from it each frame, with a 512-point FFT spread across 576 bins.
  `AvsBeat` is AVS's own beat detector, transcribed from vis_avs's `main.cpp`.
- `AvsIdlePreset` plays when nothing is loaded. It is Andamp's own preset, built from the
  components' writers. It uses no script, so it also runs in JVM tests.
- `author/AndAmpPack` is a pack of original presets written in code and serialized through
  `AvsWriter`. `PresetOps` installs it into the preset library on first run
  (`PresetLibrary.install`) and selects it for AVS.
- `PresetLibrary` imports zips holding `.milk`, `.avs` and texture files, counts `.milk` and
  `.avs` separately, and a pack knows which engines it runs on (`InstalledPack.runsOn`).
  `VisualizerStore` remembers the engine and, per engine, the pack.
- In the app, one `EngineSurface` composable (in `MilkdropWindow.kt`) hosts both engines. It
  holds the lifecycle, the published `VisualCommands` and the preset-name announcement.
  `EngineParityTest` lists every engine once and asserts the contract they share.

## Testing

Everything that does not need the evaluator is tested on the JVM and is part of `gate`: the
parser, the pipeline and blend modes, the pixel and warp components, the audio window, the
beat detector, the playlist, the idle preset, and the writers and pack in `author/`. The
tests assert shapes with a known answer and do not compare golden images. For example, a
scope told to draw down the middle must light the middle column, and a point off the edge
must be clipped.

Anything that compiles ns-eel needs the native library and runs on a device or emulator:

```bash
./gradlew :visualizer:avs:connectedDebugAndroidTest
```

That covers `Eel` itself, the scripted renderers (Super Scope, Movement, Dynamic Movement),
the per-component variable pools, a whole preset parsed, compiled and drawn
(`AvsRealPresetTest`), and every preset in `AndAmpPack` rendering.

`AvsCorpusTest` runs against a local directory of presets, which are not in this repository.
It is skipped unless the environment variable is set:

```bash
ANDAMP_AVS_CORPUS=~/avs-presets ./gradlew :visualizer:avs:test
```

It asserts that at least 99% of the files parse, prints how many presets run with nothing
missing and which components are missing most often, and renders two frames of every
runnable preset that needs no evaluator.

## Licensing

| | |
|---|---|
| `projectm-eval` (the evaluator) | MIT - linked statically, no relink obligation |
| AVS-File-Decoder (ids and framing) | MIT - transcribed with attribution |
| vis_avs (Nullsoft's original) | BSD 3-clause - arithmetic and field semantics transcribed with attribution; no C++ vendored or linked |
| Preset packs | their authors' terms - not vendored, imported at run time |
| `AndAmpPack`, `AvsIdlePreset` | Andamp's own |

NOTICE.md carries the attributions.
