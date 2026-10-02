// SPDX-License-Identifier: GPL-3.0-or-later

// JNI glue for libprojectM. Every function here must be called on the thread
// holding the GL context: libprojectM is not thread-safe, and the Kotlin side
// (ProjectMView's render thread) is what guarantees it.

#include <jni.h>

#include <projectM-4/projectM.h>
#include <projectM-4/playlist.h>

#include <string>
#include <vector>

namespace {

inline projectm_handle handleOf(jlong handle)
{
    return reinterpret_cast<projectm_handle>(handle);
}

inline projectm_playlist_handle playlistOf(jlong handle)
{
    return reinterpret_cast<projectm_playlist_handle>(handle);
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_nl_mattix_andamp_visualizer_projectm_ProjectM_nativeVersion(JNIEnv* env, jobject /* thiz */)
{
    // projectm_get_version_string() hands over ownership; memory.h owns the free.
    char* version = projectm_get_version_string();
    jstring result = env->NewStringUTF(version);
    projectm_free_string(version);
    return result;
}

JNIEXPORT jint JNICALL
Java_nl_mattix_andamp_visualizer_projectm_ProjectMNative_nativeMaxSamples(JNIEnv* /* env */, jobject /* thiz */)
{
    return static_cast<jint>(projectm_pcm_get_max_samples());
}

JNIEXPORT jlong JNICALL
Java_nl_mattix_andamp_visualizer_projectm_ProjectMNative_nativeCreate(
        JNIEnv* /* env */, jobject /* thiz */,
        jint width, jint height, jint meshWidth, jint meshHeight, jint fps)
{
    projectm_handle instance = projectm_create();
    if (instance == nullptr) {
        return 0;
    }
    projectm_set_window_size(instance, static_cast<size_t>(width), static_cast<size_t>(height));
    projectm_set_mesh_size(instance, static_cast<size_t>(meshWidth), static_cast<size_t>(meshHeight));
    projectm_set_fps(instance, fps);
    return reinterpret_cast<jlong>(instance);
}

JNIEXPORT void JNICALL
Java_nl_mattix_andamp_visualizer_projectm_ProjectMNative_nativeDestroy(
        JNIEnv* /* env */, jobject /* thiz */, jlong handle)
{
    projectm_destroy(handleOf(handle));
}

JNIEXPORT void JNICALL
Java_nl_mattix_andamp_visualizer_projectm_ProjectMNative_nativeSetWindowSize(
        JNIEnv* /* env */, jobject /* thiz */, jlong handle, jint width, jint height)
{
    projectm_set_window_size(handleOf(handle), static_cast<size_t>(width), static_cast<size_t>(height));
}

JNIEXPORT void JNICALL
Java_nl_mattix_andamp_visualizer_projectm_ProjectMNative_nativeSetMeshSize(
        JNIEnv* /* env */, jobject /* thiz */, jlong handle, jint width, jint height)
{
    projectm_set_mesh_size(handleOf(handle), static_cast<size_t>(width), static_cast<size_t>(height));
}

JNIEXPORT void JNICALL
Java_nl_mattix_andamp_visualizer_projectm_ProjectMNative_nativeSetFps(
        JNIEnv* /* env */, jobject /* thiz */, jlong handle, jint fps)
{
    projectm_set_fps(handleOf(handle), fps);
}

JNIEXPORT void JNICALL
Java_nl_mattix_andamp_visualizer_projectm_ProjectMNative_nativeRenderFrame(
        JNIEnv* /* env */, jobject /* thiz */, jlong handle)
{
    projectm_opengl_render_frame(handleOf(handle));
}

JNIEXPORT void JNICALL
Java_nl_mattix_andamp_visualizer_projectm_ProjectMNative_nativeAddPcmFloat(
        JNIEnv* env, jobject /* thiz */, jlong handle, jfloatArray samples, jint count, jint channels)
{
    // The render loop must not allocate: a critical region hands over the
    // array's own storage rather than copying it.
    void* raw = env->GetPrimitiveArrayCritical(samples, nullptr);
    if (raw == nullptr) {
        return;
    }
    projectm_pcm_add_float(handleOf(handle), static_cast<const float*>(raw),
                           static_cast<unsigned int>(count),
                           static_cast<projectm_channels>(channels));
    env->ReleasePrimitiveArrayCritical(samples, raw, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_nl_mattix_andamp_visualizer_projectm_ProjectMNative_nativeLoadPresetFile(
        JNIEnv* env, jobject /* thiz */, jlong handle, jstring path, jboolean smooth)
{
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) {
        return;
    }
    projectm_load_preset_file(handleOf(handle), chars, smooth == JNI_TRUE);
    env->ReleaseStringUTFChars(path, chars);
}

JNIEXPORT void JNICALL
Java_nl_mattix_andamp_visualizer_projectm_ProjectMNative_nativeSetTextureSearchPaths(
        JNIEnv* env, jobject /* thiz */, jlong handle, jobjectArray paths)
{
    const jsize count = env->GetArrayLength(paths);
    std::vector<std::string> owned;
    owned.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto element = reinterpret_cast<jstring>(env->GetObjectArrayElement(paths, i));
        const char* chars = env->GetStringUTFChars(element, nullptr);
        owned.emplace_back(chars == nullptr ? "" : chars);
        if (chars != nullptr) {
            env->ReleaseStringUTFChars(element, chars);
        }
        env->DeleteLocalRef(element);
    }

    std::vector<const char*> pointers;
    pointers.reserve(owned.size());
    for (const auto& path : owned) {
        pointers.push_back(path.c_str());
    }
    projectm_set_texture_search_paths(handleOf(handle), pointers.data(), pointers.size());
}

JNIEXPORT jlong JNICALL
Java_nl_mattix_andamp_visualizer_projectm_ProjectMNative_nativePlaylistCreate(
        JNIEnv* /* env */, jobject /* thiz */, jlong handle)
{
    return reinterpret_cast<jlong>(projectm_playlist_create(handleOf(handle)));
}

JNIEXPORT void JNICALL
Java_nl_mattix_andamp_visualizer_projectm_ProjectMNative_nativePlaylistDestroy(
        JNIEnv* /* env */, jobject /* thiz */, jlong playlist)
{
    projectm_playlist_destroy(playlistOf(playlist));
}

JNIEXPORT void JNICALL
Java_nl_mattix_andamp_visualizer_projectm_ProjectMNative_nativePlaylistClear(
        JNIEnv* /* env */, jobject /* thiz */, jlong playlist)
{
    projectm_playlist_clear(playlistOf(playlist));
}

JNIEXPORT jint JNICALL
Java_nl_mattix_andamp_visualizer_projectm_ProjectMNative_nativePlaylistAddPath(
        JNIEnv* env, jobject /* thiz */, jlong playlist, jstring path, jboolean recurse)
{
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) {
        return 0;
    }
    const uint32_t added = projectm_playlist_add_path(playlistOf(playlist), chars,
                                                      recurse == JNI_TRUE, false);
    env->ReleaseStringUTFChars(path, chars);
    return static_cast<jint>(added);
}

JNIEXPORT jint JNICALL
Java_nl_mattix_andamp_visualizer_projectm_ProjectMNative_nativePlaylistSize(
        JNIEnv* /* env */, jobject /* thiz */, jlong playlist)
{
    return static_cast<jint>(projectm_playlist_size(playlistOf(playlist)));
}

JNIEXPORT void JNICALL
Java_nl_mattix_andamp_visualizer_projectm_ProjectMNative_nativePlaylistSetShuffle(
        JNIEnv* /* env */, jobject /* thiz */, jlong playlist, jboolean shuffle)
{
    projectm_playlist_set_shuffle(playlistOf(playlist), shuffle == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_nl_mattix_andamp_visualizer_projectm_ProjectMNative_nativePlaylistSetRetryCount(
        JNIEnv* /* env */, jobject /* thiz */, jlong playlist, jint retries)
{
    projectm_playlist_set_retry_count(playlistOf(playlist), static_cast<uint32_t>(retries));
}

JNIEXPORT jint JNICALL
Java_nl_mattix_andamp_visualizer_projectm_ProjectMNative_nativePlaylistPlayNext(
        JNIEnv* /* env */, jobject /* thiz */, jlong playlist, jboolean hardCut)
{
    return static_cast<jint>(projectm_playlist_play_next(playlistOf(playlist), hardCut == JNI_TRUE));
}

JNIEXPORT jint JNICALL
Java_nl_mattix_andamp_visualizer_projectm_ProjectMNative_nativePlaylistPlayPrevious(
        JNIEnv* /* env */, jobject /* thiz */, jlong playlist, jboolean hardCut)
{
    return static_cast<jint>(projectm_playlist_play_previous(playlistOf(playlist), hardCut == JNI_TRUE));
}

JNIEXPORT jint JNICALL
Java_nl_mattix_andamp_visualizer_projectm_ProjectMNative_nativePlaylistSetPosition(
        JNIEnv* /* env */, jobject /* thiz */, jlong playlist, jint position, jboolean hardCut)
{
    return static_cast<jint>(projectm_playlist_set_position(playlistOf(playlist),
                                                            static_cast<uint32_t>(position),
                                                            hardCut == JNI_TRUE));
}

JNIEXPORT jint JNICALL
Java_nl_mattix_andamp_visualizer_projectm_ProjectMNative_nativePlaylistPosition(
        JNIEnv* /* env */, jobject /* thiz */, jlong playlist)
{
    return static_cast<jint>(projectm_playlist_get_position(playlistOf(playlist)));
}

JNIEXPORT jstring JNICALL
Java_nl_mattix_andamp_visualizer_projectm_ProjectMNative_nativePlaylistItem(
        JNIEnv* env, jobject /* thiz */, jlong playlist, jint index)
{
    char* item = projectm_playlist_item(playlistOf(playlist), static_cast<uint32_t>(index));
    if (item == nullptr) {
        return nullptr;
    }
    jstring result = env->NewStringUTF(item);
    projectm_playlist_free_string(item);
    return result;
}

} // extern "C"
