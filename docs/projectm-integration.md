# projectM

The Milkdrop plug-in (`VisPlugin.Milkdrop`) is libprojectM, built from source in
`:visualizer:projectm` and driven through JNI. It runs real `.milk` presets in the plug-in
window beside AVS (see [avs-integration.md](avs-integration.md)), which is the default engine.
This document explains why projectM is used and how it is wired in.

## Why projectM

Most presets in [Cream of the Crop](https://github.com/projectM-visualizer/presets-cream-of-the-crop),
the preset collection projectM publishes, use MilkDrop 2 shaders (`warp_1=` and `comp_1=`
lines). Running them takes an HLSL-to-GLSL translator, the blur textures, 2D and 3D noise
textures and a GLES pipeline. projectM implements all of these.

MilkDrop's "per-pixel" equations are evaluated per vertex of a warp mesh and interpolated,
so the cost of a frame depends on the mesh size more than on the screen size.

## Module layout

```
visualizer/projectm/
  src/main/cpp/            projectm_jni.cpp, CMakeLists.txt
  src/main/kotlin/…/projectm/
    ProjectM               loads libandamp_projectm.so, reports the linked version
    ProjectMNative         the external functions
    ProjectMEngine         the Kotlin surface over one projectM handle and its playlist
    EglSurface             an ES 3 context on a SurfaceTexture
    ProjectMView           the render loop, on :visualizer:core's VisualizerView
    PresetPack, PresetLoader, PresetRestore   which presets, and what comes back after a rebuild
  third_party/projectm/    git submodule, pinned to v4.1.7
```

The native code lives in its own module. `:app` uses `ProjectMView` and `PresetPack` and
never sees a native handle or GL.

## Vendoring

projectM is a git submodule and is built from source:

```bash
git submodule update --init --recursive
```

The command fetches projectM with its nested `vendor/projectm-eval`, and also
`visualizer/avs/third_party/projectm-eval` for the AVS module. The nested submodule is
required: Android has no system projectm-eval, so the evaluator comes from projectM's
`vendor/` directory.

Building a pinned revision from source keeps the LGPL relinking requirement easy to meet,
keeps binaries out of git, and uses the same NDK and STL as the rest of the app. It costs
build time on a clean build. There are no local patches to projectM.

## The GL surface

`GLSurfaceView` is a `SurfaceView`, and a `SurfaceView` punches a hole through the app's
translucent window. The view is therefore a `TextureView` (`VisualizerView`, shared with AVS),
and `EglSurface` sets up EGL itself on the render thread:

1. On the render thread: an `EGLDisplay`, an ES 3.0 `EGLContext`, and an `EGLSurface` made from
   the `SurfaceTexture`.
2. Each frame: `projectm_opengl_render_frame()`, then `eglSwapBuffers`, then `FramePacer`,
   because swapping does not pace the loop.
3. Teardown when the surface goes or the lifecycle gate closes.

A resize sets a new viewport and calls `projectm_set_window_size`; the engine is kept. Fullscreen (a
double tap on the window) uses the same surface.

## JNI surface

The C API used, from `src/api/include/projectM-4/` and `src/playlist/api/projectM-4/`. Every
call is made on the thread holding the GL context; libprojectM is not thread-safe.

- **Lifecycle:** `projectm_create`, `projectm_destroy`, `projectm_get_version_string`.
- **Configuration:** `projectm_set_window_size`, `projectm_set_mesh_size`, `projectm_set_fps`,
  `projectm_set_texture_search_paths`.
- **Audio:** `projectm_pcm_get_max_samples`, `projectm_pcm_add_float`.
- **Render:** `projectm_opengl_render_frame`.
- **Presets:** `projectm_load_preset_file`, and the playlist library
  (`libprojectM-4-playlist.so`): `create`, `destroy`, `clear`, `add_path`, `size`, `item`,
  `set_shuffle`, `set_retry_count`, `play_next`, `play_previous`, `set_position`,
  `get_position`.

### Kotlin surface

`ProjectMEngine` owns one native handle and, created lazily, one playlist. Its methods are
called only from `ProjectMView`'s render thread. The menus and the tap gesture reach it through
`VisualizerView`'s command queue (`onRenderThread`).

- `create(width, height, meshWidth, meshHeight, fps)`, `resize`, `setMeshSize`, `setFps`,
  `destroy`
- `addPcmFloat(samples, count, stereo)`, `renderFrame()`
- `loadPresetFile`, `setTextureSearchPaths`, `loadPresetDirectory`
- `playlistNext`, `playlistPrevious`, `playlistSetShuffle`, `playlistGoTo`, `playlistPaths`,
  `playlistPosition`, `playlistItem`

With no pack loaded, projectM renders its built-in idle preset, which is what the window shows
before anything is imported.

## Audio

projectM takes raw PCM and does its own analysis. The app's `TapPcmSource` implements
`PcmSource` (`:core:player`) over the backend's audio tap and hands over one frame's worth of
mono PCM per rendered frame: `sampleRate / fps` samples, capped at
`projectm_pcm_get_max_samples()`. The buffer is reused, so the render loop does not allocate.

## Preset storage

projectM loads presets by filesystem path, and Android's document picker hands out `content://`
URIs. So `PresetLibrary` (in `:app`) copies a pack into app-private storage first:

1. The listener picks a `.zip` through the system picker.
2. It is extracted into the preset library directory, one directory per pack, with a progress
   count. Entries that would escape the directory (zip-slip) are refused. Only `.milk`, `.avs`
   and texture files are kept.
3. `projectm_playlist_add_path()` is pointed at that directory.
4. Every `textures/` directory in the pack, and the pack root, go to
   `projectm_set_texture_search_paths()`, because MilkDrop 2 presets refer to texture files
   by name.

Presets are not included in the repository; they carry their
authors' terms (see NOTICE.md).

## Build

`src/main/cpp/CMakeLists.txt` adds projectM as a subdirectory and builds
`libandamp_projectm.so` (the JNI code) against it. Details of the configuration:

- projectM's options are CMake cache variables, set with `set(... CACHE BOOL "" FORCE)` before
  `add_subdirectory()`. Only `-DANDROID_STL=c++_shared` is passed as a Gradle argument.
- `BUILD_TESTING=OFF` turns projectM's tests off.
- `ENABLE_DEBUG_POSTFIX=OFF` keeps the library name `libprojectM-4.so` in a debug build.
- `ENABLE_SYSTEM_PROJECTM_EVAL` and `ENABLE_SYSTEM_GLM` are off, because Android has no
  system copies.
- Finding flex and bison is disabled, so the build uses the generated parser that
  projectm-eval ships and does not write into the submodule.
- `BUILD_SHARED_LIBS=ON`. projectM must stay a shared library, because the LGPL relinking
  requirement depends on it.

The Gradle side (`visualizer/projectm/build.gradle.kts`) pins NDK `28.2.13676358` (r27 is the
floor for 16 KB page alignment, required from Android 15) and CMake `3.22.1` (projectM needs
3.21), and builds `arm64-v8a` plus `x86_64` for the emulator.

Per ABI the APK carries `libprojectM-4.so`, `libprojectM-4-playlist.so`,
`libandamp_projectm.so` and `libc++_shared.so` (shared with `:visualizer:avs`).

## Testing

The Kotlin that does not need the native library is tested on the JVM: `PresetRestoreTest`
(which preset comes back after the render loop is rebuilt), and in `:app` the preset import
and `TapPcmSource`. `FramePacer` is tested in `:core:player`. The native path is tested on a
device or emulator:

```bash
./gradlew :visualizer:projectm:connectedDebugAndroidTest
```

`ProjectMLoadTest` asserts the packaged libraries load and the linked version matches the
pinned tag; `EglSurfaceTest`, `ProjectMEngineTest` and `ProjectMPlaylistTest` cover the context,
the engine and the playlist.

## Current limits

- Only `.zip` packs can be imported, not folders.
- The warp mesh is 48x36 (`ProjectMView.MESH_W` and `MESH_H`).
- `projectm_set_preset_duration` is not called, so presets rotate at projectM's default
  interval.
- Fullscreen uses the same `TextureView` as the docked window.
- The playlist's retry count is 5. A preset that fails to load is skipped for the next one.
