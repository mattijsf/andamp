# Andamp engineering notes

Andamp is a Winamp 2.8 replica in Jetpack Compose (`nl.mattix.andamp`). It has three
classic windows: the main player (275x116 virtual pixels), the equalizer (275x116) and the
playlist (275 wide, as tall as the space left). They are docked in a vertical stack until
you drag them apart. A phone is portrait only; a tablet or a Chromebook rotates. Classic
`.wsz` skins work.

The skin UI is the front of a real media player. Behind it are several playback backends:
local files and radio through Media3, and music sources that install as separate APKs
(Jellyfin and Subsonic, for example). Code that would block a second backend or a second
UI surface is wrong, even if it works today.

## Commands

```bash
./gradlew gate                        # format, detekt, the JVM tests, the goldens
./gradlew assembleDebug               # build the app
./gradlew test                        # JVM unit tests
./gradlew spotlessApply               # format (ktlint)
./gradlew detekt                      # static analysis (detekt.yml, compose-rules included)
./gradlew verifyRoborazziDebug        # unit tests plus golden screenshot comparison
./gradlew recordRoborazziDebug        # re-record goldens in app/src/test/snapshots (review the diff)
./gradlew apiDocs                     # the KDoc as an HTML API reference, in build/api-docs
./gradlew docsSite                    # the "Extend Andamp" developer site, in build/docs-site
./gradlew publishToMavenLocal         # the source SDK's five artifacts, into ~/.m2, unsigned
./gradlew installDebug                # install on all connected devices
adb shell am start -n nl.mattix.andamp/.MainActivity
```

### Setup

After cloning, run `git submodule update --init --recursive`. That fetches projectM, its
vendored `projectm-eval`, and the separate pin of the same evaluator that AVS uses. Without
them the native builds stop with a message that says so.

Then run `lefthook install` once (`brew install lefthook` if you do not have it). It wires
the pre-push hook, which runs `./gradlew gate`. If the hook fails, fix the problem; do not
push with `--no-verify`.

The local hook is the main gate. GitHub Actions (`.github/workflows/ci.yml`) only runs when
started by hand, from the Actions tab or with `gh workflow run ci.yml`. It checks out with
`submodules: recursive` and runs `gate assembleDebug`. A run is useful before a release or
after a toolchain upgrade.

### The gate

`gate` is defined in the root `build.gradle.kts` and runs:

- `spotlessCheck`
- `detekt` in every module that applies it
- `verifyRoborazziDebug`, which is `:app`'s unit tests with the golden comparison on
- the JVM unit tests of `:core:model`, `:core:playback`, `:core:player`, `:core:dsp`,
  `:core:plugin`, `:core:packapi`, `:backend:media3` (release variant, because its
  benchmarks measure that one), `:backend:pack`, `:pack:common`, `:visualizer:avs` and
  `:visualizer:projectm`

It is a single task so the hook, CI and this file cannot disagree about what it contains.
A module that gains tests is added to it. The push hook also runs
`python3 -m unittest discover -s tools/tests`, which tests the release scripts.

The gate does not assemble the app. Before a release, run:

```bash
./gradlew gate assembleDebug
```

### Build notes

Toolchain: AGP 9.3.1, Gradle 9.5.0 (wrapper), Kotlin 2.4.10, Compose BOM 2026.06.01,
JDK 17, compile and target SDK 36, min SDK 26. The SDK levels, build tools and Java level
are set once in the root build for every Android module.

- **AGP 9 has Kotlin built in.** Do not apply `org.jetbrains.kotlin.android`; the build
  fails. Apply only `com.android.application` and `org.jetbrains.kotlin.plugin.compose`.
  There is no `kotlinOptions {}`; the JVM target follows `compileOptions`.
- **One APK, no flavors.** Nothing source-specific is compiled into `:app`, so the tasks
  are the plain ones: `assembleDebug`, `testDebugUnitTest`, `bundleRelease`,
  `publishReleaseBundle`.
- **The unsigned-release check reads the task graph.** `:app` refuses a release that would
  come out unsigned because a keystore password is missing. It checks
  `gradle.taskGraph.whenReady`, not the task names typed on the command line, because an
  abbreviation such as `pRB` expands to the same graph as `publishReleaseBundle`.
- **detekt covers every source set.** Its default is main and test only, which skips
  `androidTest` and `testFixtures`. The root build sets `source.setFrom(files("src"))` for
  every module that applies the plugin. The excludes for `FunctionNaming` and
  `TooManyFunctions` are `**/src/test*/**`, anchored at `src/`. detekt matches the whole
  path, so a bare `**/test*/**` could match a directory above the checkout and switch the
  rule off everywhere.
- **Native libraries are compiled with `-ffile-prefix-map`.** Without it, every assert's
  `__FILE__` puts the absolute checkout path into the APK.

### The source SDK

Five modules are published under one version, `andampSdk` in `gradle/libs.versions.toml`:

| Module | Artifact |
|---|---|
| `:core:model` | `nl.mattix.andamp:model` |
| `:core:playback` (with its contract suites as test fixtures) | `nl.mattix.andamp:playback` |
| `:core:network` | `nl.mattix.andamp:network` |
| `:core:packapi` | `nl.mattix.andamp:source-api` |
| `:pack:common` | `nl.mattix.andamp:source-common` |

They are Apache-2.0 and the rest of the repository is GPL, so a source may carry any
license. The names, the POM and the license are set once in the root build. A module joins
by applying the publish plugin and being listed in that map. `publishToMavenLocal` needs no
key. `-PsdkRelease` adds signing and Maven Central, on a machine that has the credentials.

### Documentation builds

`./gradlew apiDocs` runs Dokka over `:core:packapi`, `:core:model`, `:core:playback`,
`:core:player` and `:backend:pack`, and writes one HTML site to `build/api-docs`. These
modules are the wire a source implements and both ends of it. `:core:packapi` also
documents its generated Java (`suppressGeneratedFiles = false`), because `IMusicSourcePack`
is an `.aidl` file and its comments are the contract. `:app` is left out: none of it is a
contract for anybody else.

`./gradlew docsSite` builds the developer site with MkDocs and Material into
`build/docs-site`. It contains `docs/extend.md` as the front page, the source and DSP
plug-in pages, and the `apiDocs` output under `api/`. The output is plain files that work
from a disk without a network.

The published pages are an allowlist in the root build file. The build copies the named
pages to `build/docs-src`, and MkDocs never reads `docs/` itself. This way a new file in
`docs/` is not published by accident. The files stay where they are, because
`CatalogueMatchesSpecTest` reads `docs/dsp-plugin-spec.md` and the KDoc cites those paths.

MkDocs runs from a virtualenv in `tools/docs-venv` (gitignored, pinned by
`tools/docs-requirements.txt`). If it is missing, the task prints the two commands that
create it.

Neither task is part of `gate`.

## Architecture

### Layers and dependency direction

```
ui  ──▶  player domain  ◀──  backends  ──▶  dsp        skin (standalone)
         (contracts,         (mock, local,   (graph engine,
          facade, state)      a pack, ...)    plug-ins)
```

- `ui` depends on the player domain only. It never sees a backend type or an SDK.
- Backends implement the domain contracts. SDK types such as Media3's stay inside their own
  module. The domain model is the only thing that crosses.
- `skin` (loader, sprite maps, parsers) knows nothing about playback. `ui` reads it.
- Nothing depends on `ui`.

### Module layout

```
:core:model          pure Kotlin: Track, Transport, BackendState, Capabilities
:core:playback       the PlaybackBackend contract, TransportRules, the audio seams.
                     The contract tests and MockBackend are its test fixtures. Published.
:core:player         the player's own half: PlayerFacade, the queue that mixes sources,
                     and what the visualizers read samples and pace by
:core:dsp            the audio-effect graph: tape encoding, compiler, engine
:core:plugin         .lua effect plug-ins: loader, spec, sandbox, UI binding
:core:network        whether the phone has a network. Shared by the player's radio and
                     the server sources.
:core:packapi        the AIDL and the parcels that a source and the player exchange
:backend:media3      ExoPlayer playback. The only module that sees Media3 types.
:backend:pack        the client side of a source: a PlaybackBackend and a BrowseSource
                     that forward over the binder, and the states for a source that is
                     absent, outdated, signed out or ready
:pack:common         the source side of the wire: PackServiceBase (the binder, the
                     lifetime, the listeners), the audio crossing, the state relay,
                     paging, and HTTP stream playback (MediaCodec, gapless queue)
:visualizer:core     the TextureView every engine renders through: thread, gate, commands
:visualizer:projectm libprojectM through JNI. The only module that sees GL.
:visualizer:avs      AVS: parser, pipeline, and the ns-eel evaluator through JNI
:app                 the rest: skin engine, UI, state, the home-screen widget
```

`skin/` is a Python build, not a Gradle module. It writes the three bundled skins from one
source and verifies them (declared color roles, contrast sweeps, a geometry audit).
`:app:bundledSkins` copies the `.wsz` files and the two template zips from `skin/dist/`
into the packaged assets.

**The split rule.** A backend that needs an external SDK gets its own module right away,
as `:backend:media3` did. A backend can also be an app of its own, a source (see
Sources). `skin/` and `ui/` split out of `:app` when a second UI surface exists.

Dependency injection is manual. `WinampViewModel` takes a backend factory that defaults to
`PlaybackRoot.backend(app)`. That is always a `MixedQueueBackend` over the phone's own
Media3 player, with one more lane for each installed source (`SourceLanes`). Tests inject
`MockBackend`.

### Visualizers

The plug-in window runs one of two engines. The listener picks it in the "Select plug-in"
menu or in Preferences, and the choice is remembered, along with the preset pack each
engine was on.

- **AVS** (`:visualizer:avs`, the default) is Winamp's visualizer rebuilt in Kotlin over
  an `IntArray` framebuffer. Only the ns-eel evaluator is native. It runs real `.avs`
  presets. See [docs/avs-integration.md](docs/avs-integration.md).
- **Milkdrop** (`:visualizer:projectm`) is projectM running real `.milk` presets.

Both read PCM through `PcmSource` and pace with `FramePacer`, which live in `:core:player`.

projectM is a git submodule in `visualizer/projectm/third_party/projectm`, pinned to a
release tag. CMake builds it from source into `libprojectM-4.so` and
`libprojectM-4-playlist.so`, for `arm64-v8a` and `x86_64`. It must stay a shared library:
the LGPL relinking requirement depends on that. Read NOTICE.md before you change how it is
linked or move the pin. [docs/projectm-integration.md](docs/projectm-integration.md)
explains why projectM was chosen and how it is wired in.

### Effects

The rack behind Preferences > Effects is a separate pipeline. An effect is described as a
graph, which `:core:dsp` compiles to a tape (one `IntArray` program, one `FloatArray` bus)
and runs. The three built-in effects (karaoke, pitch modulation, reverb) are graphs in
`:backend:media3`'s `BuiltInGraphs`. The six bundled plug-ins are `.lua` files in that
module's `src/main/resources/plugins`.

`:core:plugin` lets a `.lua` file describe an effect: its parameters, UI and graph. Kotlin
runs it. Lua never touches samples, because it is too slow for that; the measurements are
in [docs/dsp-plugins.md](docs/dsp-plugins.md). The plug-in format is in
[docs/dsp-plugin-spec.md](docs/dsp-plugin-spec.md).

`setDsp` and `setPlugins` are part of the `PlaybackBackend` contract. A backend that cannot
host effects says so through `Capabilities.hasDsp`.

### Sources

A music source is a separate app that the listener installed. The contract is described
for source authors in [docs/source-packs.md](docs/source-packs.md). This section is about
the player's side.

**Discovery.** `PackFinder` queries `Intent("nl.mattix.andamp.source.BIND")`. `PackSources`
keeps one `PackClient` for each package that answers and one `PackSource` for each source
that has described itself. Everything that needs the list reads `PackSources.found`. A
source's id is the scheme of its rows and its label is the name it gives itself; both come
from its `PackDescriptor`. A source that the player has never bound has no name yet, so it
joins the list after its first `describe()`. On later launches it is there at once, from
`PackClient.card()`. Two installed sources are two rows in Preferences, two Media Library
entries and two lanes in a mixed queue.

**Five conditions.** The player carries no source's code or name and does not depend on
what is installed beside it. It keeps five conditions for that. Condition 4 is checked by
a test and by build tasks; the others hold because the code and the manifest do not
contain what they forbid:

1. **Bind a service, never load foreign code.** No `DexClassLoader`, no
   `CONTEXT_INCLUDE_CODE`, no library loaded from another package's path. A source's code
   runs in its own process under its own user id.
2. **Never request `REQUEST_INSTALL_PACKAGES`.**
3. **Never request `QUERY_ALL_PACKAGES`.** Discovery is the `<queries><intent>` entry in
   `app/src/main/AndroidManifest.xml` for the two intent actions, and neither action names
   a source.
4. **The APK names no service that a source plays.** No string, constant, package name or
   asset.
5. **The app downloads and installs no apps.** A link to a web page is fine. A button
   that fetches an APK is not. (An effect plug-in is a `.lua` description, not code the
   platform runs, and the listener adds it by hand.)

The interface is generic on purpose: it is documented and anyone can implement it.

**How condition 4 is checked.** `MusicSource` is a value, not an enum, and the shared code
knows only `MusicSource.LOCAL`. A row from a source that is not installed is named from its
own address by `SourceForRows` (`example:track:…` gives "Example"), so the name comes from the
listener's data. Two checks read the build:

- `NamesNoServiceTest` (`app/src/test`) reads the unit-test classpath: classes, the Lua
  plug-ins, service files, the merged manifest, the resource table and every merged asset,
  with skins opened as zips. It requires `PackSources` to be among what it read, so a scan
  that reads nothing cannot pass. Paths are compared from the checkout down, so the name of
  the checkout directory does not matter.
- `verifyDebugApkNamesNothing` and `verifyReleaseBundleNamesNothing`
  (`app/build.gradle.kts`) read the packaged APK and bundle, which includes library code in
  every dex and every native library. `assemble*` depends on the APK check. `bundle*` is
  finalized by the bundle check, and the publish tasks depend on it. Text is matched as
  UTF-8 and as UTF-16, because binary XML and resource tables store UTF-16.

**Audio.** The source decodes and the player renders. `openAudio()` returns the read end of
a pipe that carries raw frames. The player plays them through its own chain (equalizer,
balance, effects, volume, visualizer tap), the same as a local file. On a seek, a track
change or a stop, the source closes its end of the pipe, everything already written is
dropped, and the player asks for a new pipe that starts at the new position.

So a source makes no sound of its own. It has no foreground service and no notification,
and it needs only the INTERNET and network state permissions. Its process stays alive
because the player binds it
with `BIND_INCLUDE_CAPABILITIES` and is in the foreground while anything plays.

Both halves of the wire are in `:core:packapi`. The rules for changing a parcel are at the
top of its `Wire.kt`. Parcels are read by position, so any change to a parcel or a call
needs a new `PackApi.PACK_API`, and the player refuses a source whose version differs.

**Trust.** Anybody may write a source. There is no pinned key and no list of approved
authors. Andamp trusts what the listener installed and shows who is answering on the
source's page: the app's name, its package and the start of its signing fingerprint
(`PackIdentity`). Android's rule that an update must carry the same key ties a source's
versions together. In the other direction, a source answers whoever binds it. A source
that guards something asks the listener once for each calling package.

**In the app.** A source reaches the rest of the app through one interface, `ExtraSource`:
its player, its library, its Preferences row and its page. `PackSource` is the only
implementation and answers from a `PackClient` and a `PackDescriptor`. Preferences lists
This Phone first, then each source that was found, then a "More sources" link to
`andamp.nl/extensions/source`. An account, and what it takes to get one, is the
source's business and lives behind its own settings screen.

**Settings and updates.** A source's settings are an activity in its own APK that answers
`nl.mattix.andamp.source.SETTINGS`. The source's row opens it by intent. A source reports
a sign-in or sign-out through `IPackListener.onAccount`, and `SourceOps.reconcile` also
reads the state again when Preferences comes to the front. An update is an `update.json` of the form
`{ "version": …, "page": … }`. The app shows it in an Updates section and opens the page
in the browser. The listener installs the update (condition 5).

**Skins per source.** A source that is `skinnable` (the default; the phone itself is not)
gets a Skin row on its page: Default, or any installed skin. `SourceSkinOps` puts that
skin on when the current track comes from that source and takes it off for any other, via
`SkinOps.wear`. The listener's own choice is not changed: `SkinLibrary.currentId` stays
theirs, and `SkinLibrary.wearing` records the source's skin so the widget and a relaunch
draw what is on screen. If a skin is removed from the library, every source that used it
goes back to Default.

### Playback backend contract

The contract is `PlaybackBackend` in `:core:playback`. Its methods are fire-and-forget:
results arrive through `StateFlow<BackendState>`, and an implementation with an async SDK
launches internally. The backend owns queue advance, shuffle order and repeat, because real
backends control their own progression.

The Winamp transport rules are `TransportRules` in `:core:playback`. `MockBackend`,
`MixedQueueBackend` and `:pack:common`'s `StreamBackend` all apply them. A source applies
them on its side of the binder, over its own queue, and the player draws the resulting
`PackState`. The client in `:backend:pack` is a `PlaybackBackend` like any other, so the
code above it does not know that a call crosses a process.

`MixedQueueBackend` is the exception to "the backend owns advance". It is one playlist
over several players, where each row is played by the player that can open it. It cuts the
queue into runs of consecutive rows for one player and gives each player its run as a
queue. Each player advances inside its run, and the composite advances between runs,
through `TransportRules`. It is used on every phone, even with no source installed, because
the player is built once per process and a source installed later has to join as a lane. A
queue that belongs to one player is handed over whole, and that player's own shuffle,
repeat and advance apply. It passes the shared contract test twice: with one lane, and with
rows that alternate between two.

Rules:

- **Degrade by capability.** No crashes and no dead controls. Without `canSeek` the
  position bar renders but ignores drags, as Winamp does on a stream. Without `audioTap`
  the analyzer and oscilloscope draw a fake signal (`VisualizerFeed`) and switch to the
  real one when a tap appears. The volume slider always changes something: the backend's
  volume, or the device's when the backend cannot attenuate (`canAttenuate`).
- **Time comes from the backend.** Position, duration and ticking all come from the state
  flow. The UI never runs a playback clock. Only `MockBackend` fakes one.
- **One `Track` model.** Fields a backend cannot supply (bitrate, sample rate, artwork)
  are null, and the UI hides or blanks the readout, as Winamp did for streams.
- **Backends are held to the shared contract test** (see Testing).

### State

- **Domain.** `PlayerFacade` (`:core:player`) is the UI's only entry to playback. It
  exposes the backend's `StateFlow<BackendState>` and forwards intents. Capability checks
  live here; `seekToFraction` does nothing without `canSeek`, for example.
- **UI.** `WinampState` mirrors render state with one `mutableStateOf` per field, updated
  by a single collector in `WinampViewModel`. Per-field state matters: one large state
  object would redraw all three canvases on every 60 Hz drag or visualizer tick.
  Visualizer data stays in plain arrays and is invalidated through the `visFrame` counter.
- **Draw code reads state.** It never mutates and never computes business logic. Widget
  callbacks call the ViewModel, which delegates to the facade.
- **`WinampViewModel` has no playback logic.** It builds the collaborators, runs the
  mirror collector and forwards intents. Disk reads and writes belong to `PersistenceOps`,
  animation clocks to `ui/UiClocks.kt`, and the playlist's file actions to
  `PlaylistFileOps`. A feature that lands in the ViewModel usually belongs somewhere else.

## Pixel rules

1. **Sprite coordinates come from webamp, never from memory.** The reference is
   `packages/webamp/js/skinSprites.ts` (source rectangles) and
   `css/{main,equalizer,playlist}-window.css` (destinations) in
   github.com/captbaritone/webamp. `SpriteMap.kt` keeps webamp's sprite names so entries
   can be compared one to one. For new coordinates: fetch, transcribe, and check on a
   device against https://webamp.org with the same skin.
2. **Everything draws in virtual pixels, 275 wide.** Each window has one uniform
   `withTransform { scale(S, S) }` with integer `S = floor(screenWidthPx / 275)`. All
   coordinates are integers in virtual space. Never scale a sprite on its own.
   The home-screen widget is the one exception, and only when the listener picks
   Preferences > Home screen widget > Size > Fill. The scale is still uniform, and
   `WidgetLayout.px`/`span` is the single rounding that drawing and placing both use.
3. **`FilterQuality.None` on every `drawImage`.** Use the `sprite()` helper. It also
   clamps source rectangles for skin sheets that are too small.
4. **Playlist text uses Winamp's metrics and modern rasterization.**
   `PlaylistTextRasterizer` lays out glyphs in virtual coordinates with the bundled
   Liberation Sans (metric-compatible with the Arial that `PLEDIT.TXT` names), rasterizes
   at device scale with antialiasing, and draws 1:1 through the canvas transform.
   Upscaled text without antialiasing looks too blocky. Do
   not use Compose `TextMeasurer` or `drawText` here: they measure at device density.
5. **Window shapes come from `REGION.TXT`.** The player and the equalizer, in normal and
   shade form, are clipped to the skin's polygons. These are the four sections webamp
   reads: `normal`, `windowshade`, `equalizer`, `equalizerws`. Andamp also reads a section
   of its own, `[Corners]`, which rounds the other windows. The clip is on the window's
   own box, so touches are cut along with the art. A skin without the file is a rectangle.
6. **The skin engine copies Winamp's quirks on purpose.** Case-insensitive zip lookup,
   last entry wins, volume art as the fallback for balance, the NUMS_EX override, PLEDIT
   color truncation. Check webamp before you change odd behavior; it is usually authentic.

Modern UI (settings, dialogs, sign-in screens) is plain Material Compose outside the skin
canvases. Skin art stops at the canvas edge: never draw modern chrome inside the 275 pixel
virtual space. The Material UI takes its colors from the loaded skin.
`SkinColors.schemeOf(skin)` seeds Material's color engine (`QuantizerCelebi` and `Score`
over MAIN.BMP, then `SchemeExpressive`). Those classes are `@RestrictTo` in
`com.google.android.material`, so the call sites carry a suppression with a comment.

## Testing

A change is done when the tiers that apply to it pass.

Two standing rules:

- **Every mechanical bug gets a regression unit test.** Write the failing test first,
  then fix. Mechanical means deterministic: parsing, geometry, value mapping, state
  transitions, off-by-one errors. If a bug cannot be caught in a JVM test, extract the
  logic until it can.
- **A feature ships with its tests in the same change.**

### 1. JVM unit tests

`./gradlew test`. Required for:

- every parser (PLEDIT.TXT, VISCOLOR.TXT, playlists)
- geometry and layout math (playlist quantization, slider value to frame mapping, font
  lookup)
- playback logic (transport transitions, shuffle, repeat, advance, seek clamping)
- each backend's logic outside its SDK

Logic that needs `android.*` in a test is in the wrong layer; extract the pure part. The
Compose runtime (`mutableStateOf`, snapshots) works on the JVM. There is no mocking
framework; write fakes by hand. One test class per subject, test names as backticked
sentences, Arrange-Act-Assert.

### 2. Golden screenshot tests

`WindowGoldenTest` uses Roborazzi with Robolectric native graphics. Each window's draw
function renders at scale 1 with the base skin (AndAmp Dark) through `CanvasDrawScope` and
is compared with the goldens in `app/src/test/snapshots/`. `verifyRoborazziDebug` is part
of `gate`. `recordRoborazziDebug` records new goldens.

- Fixtures are deterministic: fixed times, a zeroed visualizer, no clocks.
- Every new window or visual state gets a golden.
- Look at the diff before you commit a re-recorded golden, and understand why the pixels
  changed.
- Load the skin in `@Before`, not `@BeforeClass`. The Robolectric environment is per test
  method.

### 3. Interaction tests

Compose test rule, on Robolectric. Taps and drags through `ScaledWindowCanvas` must land on
the right widget and produce the right state: tap play gives `Transport.Playing`, dragging
the volume gives the matching value, toggling the EQ collapses the window. Every new widget
gets at least one. Before a release, also run a smoke test on an emulator.

### Contract tests

- **`PlaybackBackendContractTest`** (test fixtures of `:core:playback`) runs the whole
  contract on the coroutine test scheduler's virtual clock: transport transitions,
  position advance, seek clamping, wrap-around, advance at track end under shuffle and
  repeat, and that capabilities match behavior. Logic-level backends subclass it (`MockBackendTest`). A
  backend on an SDK cannot run on the virtual clock, so it mirrors the cases against the
  SDK's test rig. `Media3BackendTest` repeats part of the control cases, with the same
  test names, against a real ExoPlayer under Robolectric. Its clock-driven cases are
  checked on a device. The client in `:backend:pack` has its own tests against a fake
  source.
- **`BrowseSourceContractTest`** (same fixtures) does this for the library side: what
  `albums(null)` means, that an album appears once, that an album's tracks come back in
  album order with ten after nine, that every row is playable, and that a source without
  `canSearch` stays silent and does not throw. `MediaStoreBrowseSourceTest` passes it
  under Robolectric against a real content provider over SQLite (`FakeMediaStore`).
- **`WindowParityTest`** lists every floating window (player, equalizer, playlist,
  plug-in, skin browser, library) in one parameterized suite. Each must publish a
  rectangle and a handle, measure the same screen as its neighbors, close on a near-miss
  tap, and anchor its top-left corner with a grip. A new window is added to that list.

A new backend or browse source comes with its contract suite. Anything that is
implemented more than once gets one shared test for all copies.

### Opt-in corpus tests

Presets and skins made by other people cannot be in this repository, so these tests skip
unless you point them at a local directory.

```bash
ANDAMP_AVS_CORPUS=~/avs-presets ./gradlew :visualizer:avs:test
ANDAMP_SKIN_CORPUS=~/skins ./gradlew test
```

`AvsCorpusTest` parses every `.avs` file and asserts the share that parses and that the
components are identified. It was written against Winamp's built-in set of 156 presets
(`default_presets.7z` from the VISBOT archive) and parses 155 of them, the same share that
`docs/avs-census.py` measured.

`SkinCorpusTest` renders a floating window's chrome with every `.wsz` file and asserts
things that hold for any skin: the right frame for the skin's art, a title bar painted end
to end, a title that stays inside its plate. Run it after changing anything that is drawn
from skin art.

### What needs a device

libprojectM has no JVM tier. Its tests are
`./gradlew :visualizer:projectm:connectedDebugAndroidTest` on an attached device.
`ProjectMLoadTest` checks that the packaged libraries load and that the linked version
matches the pinned tag. `ProjectMEngineTest`, `ProjectMPlaylistTest` and `EglSurfaceTest`
cover the engine, the playlist and the GL surface. AVS components that run preset
scripts need the native ns-eel evaluator, so their tests are in
`:visualizer:avs`'s `androidTest` too. The binder between the player and a source also
needs a device: real marshalling, a source that dies, a version mismatch.

The push hook cannot run device tests, so run them yourself after touching native code or
the binder. Keep what can be pure Kotlin out of the native side (preset import, path
handling, engine state), so the normal tiers cover it. `PresetRestoreTest` is an example:
it decides on the JVM which preset comes back after the render loop is rebuilt.

### Checking on a device

After a UI change: `./gradlew installDebug`, launch, take a screenshot with
`adb exec-out screencap -p > shot.png`, and look at the changed region pixel by pixel. If
coordinates changed, compare with webamp.org using the same skin. `adb shell input
tap/swipe` works for interactions; content is centered, and a virtual coordinate `v` is at
`origin + v*S` on the device. After a change to the skin engine, also test a skin other
than the base one.

## Code style

- **Commit subjects are release notes.** release-please builds CHANGELOG.md and the
  store's "what's new" from `feat:`, `fix:` and `change:` subjects. Write them for somebody who
  installed a music player: "the playlist and visualizer windows get rounded corners like
  the player", not "the playlist is clipped by REGION.TXT". Internal terms go in the
  body. `tools/commit-type.py` (the `commit-msg` hook) checks that a `feat:`, `fix:` or `change:`
  touches something that ships; anything else is `chore:` and stays out of the changelog.
  Squash commits that describe one visible change.
- **Formatting** is Spotless with ktlint (`ktlint_official` style, settings in
  `.editorconfig`, 130 columns, Composables exempt from function naming).
  `./gradlew spotlessApply` fixes it.
- **Static analysis** is detekt with compose-rules, in every module, with no baseline
  file. The thresholds in `detekt.yml` each carry their reason (sprite coordinates are
  exempt from MagicNumber, draw functions from length limits, the transport API from
  TooManyFunctions). `UnusedImports` is on there because ktlint's rule misses them in this
  build. For a new finding, fix the code. If the rule does not fit, change `detekt.yml`
  with a comment. No baseline, and no `@Suppress` without a comment.
- Kotlin official style, 4-space indent, trailing commas in multi-line lists, no wildcard
  imports.
- Widget ids are `window.name` strings (`"main.posbar"`, `"eq.band3"`, `"pl.scroll"`).
  Pressed art is chosen by `state.pressedWidget == id`.
- Draw functions are `DrawScope` extensions (`drawMainWindow(skin, state)`). Widget
  factories are plain functions that return `List<Widget>`. Both are in one file per
  window. A new window follows the same pattern: a `widgets()` factory, a draw function,
  and `SpriteMap` entries from webamp.
- Comments are for constraints the code cannot show, such as sprite-sheet quirks and
  Winamp behavior. Cite the webamp file when the reason is "that is what Winamp does".

## Assets

The three bundled skins (AndAmp Dark, Light and Spot) are built by `skin/` from one Python
source: `python3 skin/build.py all`. `:app:bundledSkins` copies the output into the
packaged assets. Dark is the base skin: a fresh install uses it, a skin that lacks a sheet
borrows from it, and the goldens are drawn with it.

On Android 12 and later, Dark and Light are rebuilt from the phone's palette.
`SkinTemplate` binds the build's role image to `dynamicDarkColorScheme` or
`dynamicLightColorScheme`; the design is in [docs/dynamic-skins.md](docs/dynamic-skins.md).
The two template zips ship next to the `.wsz` files for this. If you change `skin/`, run
`python3 skin/build.py all`, or `SkinTemplateBitmapsTest` fails the gate. The listener can
switch this off under Preferences > Player > Colors > Match wallpaper colors.

Other skins load at runtime through the titlebar menu. The one bundled asset that is not
this project's own is the intro audio; see NOTICE.md.
