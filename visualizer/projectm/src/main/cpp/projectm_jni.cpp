// SPDX-License-Identifier: GPL-3.0-or-later

// JNI glue for libprojectM. Every function here must be called on the thread
// holding the GL context: libprojectM is not thread-safe, and the Kotlin side
// (ProjectMView's render thread) is what guarantees it.

#include <android/log.h>
#include <jni.h>

#include <projectM-4/projectM.h>
#include <projectM-4/playlist.h>

#include <iostream>
#include <mutex>
#include <streambuf>
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

// Strings for Kotlin as raw bytes, each ended by a zero byte: NewStringUTF wants modified UTF-8
// and aborts on a 4-byte character, which a file name can hold.
jbyteArray zeroEnded(JNIEnv* env, const std::vector<std::string>& strings)
{
    std::string joined;
    for (const auto& item : strings) {
        joined += item;
        joined += '\0';
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(joined.size()));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(joined.size()), reinterpret_cast<const jbyte*>(joined.data()));
    return result;
}

// What projectM writes to std::cerr, which Android drops: which preset it is loading, and
// what of it would not compile or load, after which it draws the preset without that part
// (a fallback shader, no custom wave). Each line goes to logcat and is kept for Kotlin to
// take. Both engines' lines land here; a line is placed by the "Loading preset" line before it.
class ProjectMLog : public std::streambuf
{
public:
    std::vector<std::string> take()
    {
        std::lock_guard<std::mutex> guard(lock);
        std::vector<std::string> taken;
        taken.swap(lines);
        return taken;
    }

protected:
    int overflow(int c) override
    {
        if (c == traits_type::eof()) {
            return 0;
        }
        if (c == '\n') {
            endLine();
        } else {
            line += static_cast<char>(c);
        }
        return c;
    }

    std::streamsize xsputn(const char* chars, std::streamsize count) override
    {
        for (std::streamsize i = 0; i < count; ++i) {
            overflow(static_cast<unsigned char>(chars[i]));
        }
        return count;
    }

private:
    // a player that nobody reads from keeps at most this many
    static constexpr size_t KEPT = 512;

    void endLine()
    {
        __android_log_write(ANDROID_LOG_INFO, "projectM", line.c_str());
        std::lock_guard<std::mutex> guard(lock);
        if (lines.size() >= KEPT) {
            lines.erase(lines.begin());
        }
        lines.push_back(line);
        line.clear();
    }

    std::mutex lock;
    std::vector<std::string> lines;
    std::string line;
};

ProjectMLog& projectMLog()
{
    static ProjectMLog log;
    static std::once_flag installed;
    std::call_once(installed, [] { std::cerr.rdbuf(&log); });
    return log;
}

// The presets a playlist could not switch to since they were last taken, each with
// projectM's reason. Filled by the playlist's callback and read from Kotlin; both happen on
// the render thread, and the lock keeps that an assumption rather than a requirement.
//
// The playlist's built-in retry only calls back once it gives up, so a preset it skipped past
// would never be named. The playlist is set to retry nothing, and this moves on itself: up
// to `retries` presets per switch, in the direction the listener was going.
struct FailureWatch
{
    std::mutex lock;
    std::vector<std::string> fileAndReason;
    projectm_playlist_handle playlist{nullptr};
    uint32_t retries{0};
    uint32_t skipped{0};
    bool backwards{false};
};

void onSwitchFailed(const char* presetFile, const char* message, void* userData)
{
    auto* watch = static_cast<FailureWatch*>(userData);
    {
        std::lock_guard<std::mutex> guard(watch->lock);
        watch->fileAndReason.emplace_back(presetFile != nullptr ? presetFile : "");
        watch->fileAndReason.emplace_back(message != nullptr ? message : "");
    }
    if (watch->skipped >= watch->retries) {
        return;
    }
    watch->skipped++;
    if (watch->backwards) {
        projectm_playlist_play_previous(watch->playlist, true);
    } else {
        projectm_playlist_play_next(watch->playlist, true);
    }
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
    projectMLog();
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

JNIEXPORT jboolean JNICALL
Java_nl_mattix_andamp_visualizer_projectm_ProjectMNative_nativePlaylistAddPreset(
        JNIEnv* env, jobject /* thiz */, jlong playlist, jstring path)
{
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) {
        return JNI_FALSE;
    }
    const bool added = projectm_playlist_add_preset(playlistOf(playlist), chars, false);
    env->ReleaseStringUTFChars(path, chars);
    return added ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_nl_mattix_andamp_visualizer_projectm_ProjectMNative_nativePlaylistWatchFailures(
        JNIEnv* /* env */, jobject /* thiz */, jlong playlist, jint retries)
{
    auto* watch = new FailureWatch();
    watch->playlist = playlistOf(playlist);
    watch->retries = static_cast<uint32_t>(retries);
    projectm_playlist_set_retry_count(watch->playlist, 0);
    projectm_playlist_set_preset_switch_failed_event_callback(watch->playlist, onSwitchFailed, watch);
    return reinterpret_cast<jlong>(watch);
}

JNIEXPORT void JNICALL
Java_nl_mattix_andamp_visualizer_projectm_ProjectMNative_nativeWatchDirection(
        JNIEnv* /* env */, jobject /* thiz */, jlong watch, jboolean backwards)
{
    auto* failures = reinterpret_cast<FailureWatch*>(watch);
    failures->backwards = backwards == JNI_TRUE;
    failures->skipped = 0;
}

JNIEXPORT void JNICALL
Java_nl_mattix_andamp_visualizer_projectm_ProjectMNative_nativePlaylistUnwatchFailures(
        JNIEnv* /* env */, jobject /* thiz */, jlong playlist, jlong watch)
{
    projectm_playlist_set_preset_switch_failed_event_callback(playlistOf(playlist), nullptr, nullptr);
    delete reinterpret_cast<FailureWatch*>(watch);
}

JNIEXPORT jbyteArray JNICALL
Java_nl_mattix_andamp_visualizer_projectm_ProjectMNative_nativeTakeFailures(
        JNIEnv* env, jobject /* thiz */, jlong watch)
{
    auto* failures = reinterpret_cast<FailureWatch*>(watch);
    std::vector<std::string> taken;
    {
        std::lock_guard<std::mutex> guard(failures->lock);
        taken.swap(failures->fileAndReason);
    }
    // taken once a frame: a new switch gets the whole budget again
    failures->skipped = 0;
    return zeroEnded(env, taken);
}

JNIEXPORT jbyteArray JNICALL
Java_nl_mattix_andamp_visualizer_projectm_ProjectMNative_nativeTakeLog(
        JNIEnv* env, jobject /* thiz */)
{
    return zeroEnded(env, projectMLog().take());
}

} // extern "C"
