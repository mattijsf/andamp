# Third-party notices

Andamp is built on other people's work. This file lists that work, its license, and what
the license requires.

The licenses were read from each project's own repository. Upstream terms can change, so
check them again before you redistribute.

## Andamp itself: GPL-3.0-or-later

Andamp's own code is under the GNU GPL, version 3 or later (see `LICENSE` and the license
section of the README). There is one additional permission under section 7: effect
plug-ins, visualizer presets and skins may use any license, because they reach the app
only through its documented formats.

The source SDK is Apache-2.0 (`LICENSE-APACHE-2.0`). It consists of `core/model`,
`core/playback`, `core/network`, `core/packapi` and `pack/common`, the modules a music
source compiles against. A source is its author's own app and may carry any license. The
player uses these modules like any other Apache-2.0 library.

Everything below keeps its own terms. None of them conflicts with the GPL: Apache-2.0 and
LGPL-2.1 code can be combined with GPLv3 code, and BSD, MIT and OFL only ask for
attribution. One thing to watch: GPLv2-only code cannot be combined with the Apache-2.0
libraries this app links.

## projectM: LGPL-2.1

<https://github.com/projectM-visualizer/projectm>

The Milkdrop-compatible visualizer engine. Andamp calls its C API to render `.milk`
presets. See `docs/projectm-integration.md`.

What LGPL-2.1 requires here:

- **Dynamic linking.** `libprojectM-4.so` ships as a separate library in the APK and is
  never linked statically into Andamp's code. This keeps the user's right to replace it.
- **The license text.** The build copies projectM's `LICENSE.txt` into the APK as the
  asset `licenses/projectM-LGPL-2.1.txt` (`:app:bundledLicenses`).
- **A record of modifications.** projectM is built unmodified. If a patch is ever
  needed, it gets recorded in `visualizer/projectm/third_party/PATCHES.md`, beside the
  submodule, with a description and a date.
- **Relinking.** The projectM revision is pinned, the NDK and CMake flags are listed
  below, and the JNI glue is in this repository, so a user can rebuild against their own
  projectM.

The current build:

| | |
|---|---|
| Source | `visualizer/projectm/third_party/projectm`, git submodule pinned to tag `v4.1.7` |
| Build | `visualizer/projectm/src/main/cpp/CMakeLists.txt` (`add_subdirectory`; shared libs, playlist on, SDL UI/tests/docs off, vendored GLM and projectM-eval) |
| Toolchain | NDK 28.2.13676358, CMake 3.22.1, `ANDROID_STL=c++_shared` |
| Shipped | `libprojectM-4.so` and `libprojectM-4-playlist.so`, separate shared objects, per ABI |
| Local patches | none |

The LGPL applies to projectM, not to Andamp's own source, as long as projectM stays
dynamically linked.

## AVS (vis_avs): BSD 3-clause

<https://github.com/grandchild/vis_avs>

Winamp's Advanced Visualization Studio. Nullsoft released the source in 2005 under a BSD
3-clause license (Copyright 2005 Nullsoft, Inc.), and the grandchild/vis_avs repository
maintains it. `:visualizer:avs` is a new implementation in Kotlin. Where exact arithmetic
matters (blend modes, blur kernels, the Effect List's frame flow, the beat detector, the
spectrum's log curve, component field semantics) the code is transcribed from that source
and marked "Transcribed from vis_avs". No C++ is vendored or linked. This notice is the
attribution the license asks for. The name Nullsoft is not used to promote this app.

## AVS-File-Decoder: MIT

<https://github.com/grandchild/AVS-File-Decoder>

`AvsParser` and `BodyFields` in `:visualizer:avs` read the `.avs` preset format. The
framing and each component's field layout are transcribed from this decoder's tables, with
attribution in the code. No code is vendored.

## projectM-eval: MIT

<https://github.com/projectM-visualizer/projectm-eval>

The ns-eel expression evaluator that AVS presets are scripted in.
`visualizer/avs/third_party/projectm-eval` is a git submodule, built by
`visualizer/avs/src/main/cpp/CMakeLists.txt` and linked statically into
`libandamp_avs.so`. projectM's build uses its own vendored copy.

## MilkDrop

MilkDrop and MilkDrop 2 are by Ryan Geiss and originally shipped with Winamp. projectM
renders their `.milk` presets. No MilkDrop source code is included.

## Webamp: MIT

<https://github.com/captbaritone/webamp>

By Jordan Eldredge. Its reverse-engineered sprite maps (`js/skinSprites.ts` and the window
CSS) are the reference for every sprite rectangle in `SpriteMap.kt` and for the skin
engine's Winamp quirks. The coordinates are facts about the Winamp skin format,
transcribed with attribution.

## Liberation Sans: SIL OFL 1.1

<https://github.com/liberationfonts/liberation-fonts>

Bundled as `app/src/main/assets/fonts/LiberationSans-Regular.ttf`. It is
metric-compatible with the Arial that `PLEDIT.TXT` names and renders the playlist text.
The license text is in `docs/LiberationSans-LICENSE.txt`, and the build copies it into the
APK as the asset `licenses/LiberationSans-OFL-1.1.txt`.

## Libraries the app links against

These come from Maven and ship inside the APK. All are permissive and ask only for
attribution, which this file and the in-app licenses screen give.

| | | |
|---|---|---|
| AndroidX and Jetpack Compose (incl. Media3, Navigation, Lifecycle) | Apache-2.0 | <https://github.com/androidx/androidx> |
| Kotlin and kotlinx.coroutines | Apache-2.0 | <https://github.com/JetBrains/kotlin> |
| Material Components for Android | Apache-2.0 | <https://github.com/material-components/material-components-android> |
| Coil 3 | Apache-2.0 | <https://github.com/coil-kt/coil> |
| OkHttp | Apache-2.0 | <https://github.com/square/okhttp> |
| LuaJ | MIT | <https://github.com/luaj/luaj> |

## The licenses screen

`app/.../ui/prefs/Notices.kt` holds the same list for the in-app licenses screen, because
LGPL-2.1 and the OFL both require something the installed app has to carry. `NoticesTest`
reads every `implementation(...)` in every module and fails when one has no entry. It also
checks that the projectM revision on that screen is the tag in the table above.

## The intro: DJ Mike Llama's music, terms unverified

"Llama Whippin' Intro" by DJ Mike Llama is the track every Winamp install shipped with.
Its license has not been established. The original file is not in this repository.

The app plays `app/src/main/assets/audio/andamp-intro.mp3`, which is a derivative of that
recording. The instrumental, the drum fill and the animal sounds are the original audio,
unchanged. The two spoken phrases were replaced with a new recording made for this
project. Because the music is the original, the file carries whatever terms the original
carries.

## The bundled skins

AndAmp Dark, AndAmp Light and AndAmp Spot are generated by `skin/`, a Python build in this
repository that writes all three from one source and checks that the output is
reproducible byte for byte. The art is licensed under Creative Commons Attribution 4.0:
use it, change it and share it, with credit to Mattix. The Python that draws it is GPL
like the rest of the code. No Nullsoft skin art is in this repository.

## Preset packs: not bundled

`.milk` presets (for example
[Cream of the Crop](https://github.com/projectM-visualizer/presets-cream-of-the-crop)) and
`.avs` presets, including Winamp's own, carry their authors' terms and are not in this
repository. The app loads them at runtime from storage the user chooses. The one pack the
app ships, the "AndAmp" AVS pack, is this project's own: its presets are written in code
(`visualizer/avs/.../author/AndAmpPack.kt`).
