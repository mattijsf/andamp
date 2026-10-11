// SPDX-License-Identifier: GPL-3.0-or-later

// JNI glue for projectm-eval, the ns-eel2 reimplementation AVS scripts in.
//
// One context per component instance; variables are registered once and then
// written and read through the pointer the evaluator hands back, so a per-point
// loop sets x and y, runs the code and reads them back without marshalling.

#include <jni.h>

#include <projectM-eval.h>

extern "C" {
// the context's function list; see addAvsFunctions
#include <CompilerTypes.h>
}

#include <chrono>
#include <cstdlib>
#include <cstring>
#include <string>

namespace {

// What AVS's getosc and getspec read: this frame's 576 samples, as the bytes
// AVS holds them in. The app's tap is mono, so there is one channel, and it
// answers for left, right and center alike. One preset renders on one thread,
// so these are plain globals, written between frames.
constexpr int kSamples = 576;
int g_waveform[kSamples] = {};
int g_spectrum[kSamples] = {};

PRJM_EVAL_F argument(prjm_eval_exptreenode* node, int index)
{
    PRJM_EVAL_F value = 0.0;
    PRJM_EVAL_F* result = &value;
    node->args[index]->func(node->args[index], &result);
    return *result;
}

// vis_avs avs_eelif.cpp getvis: the mean of `width` samples around `band`,
// both given as a fraction of the 576, scaled so a full-scale sample is 1.
PRJM_EVAL_F meanOf(const int* samples, PRJM_EVAL_F band, PRJM_EVAL_F width, PRJM_EVAL_F channel)
{
    const int which = static_cast<int>(channel + 0.5);
    if (which != 0 && which != 1 && which != 2) {
        return 0.0;
    }
    int at = static_cast<int>(band * kSamples);
    int count = static_cast<int>(width * kSamples);
    if (count < 1) {
        count = 1;
    }
    at -= count / 2;
    if (at < 0) {
        count += at;
        at = 0;
    }
    if (at > kSamples - 1) {
        at = kSamples - 1;
    }
    if (at + count > kSamples) {
        count = kSamples - at;
    }
    if (count < 1) {
        return 0.0;
    }
    int sum = 0;
    for (int i = 0; i < count; i++) {
        sum += samples[at + i];
    }
    return static_cast<PRJM_EVAL_F>(sum) / (static_cast<PRJM_EVAL_F>(count) * 127.5);
}

void avsGetOsc(prjm_eval_exptreenode* node, PRJM_EVAL_F** result)
{
    **result = meanOf(g_waveform, argument(node, 0), argument(node, 1), argument(node, 2));
}

void avsGetSpec(prjm_eval_exptreenode* node, PRJM_EVAL_F** result)
{
    **result = meanOf(g_spectrum, argument(node, 0), argument(node, 1), argument(node, 2)) * 0.5;
}

// gettime(0) is a clock in seconds, and gettime(t) the seconds since t. AVS
// answers -1 and -2 with the song's position and length, which this engine is
// not told: both read as 0.
void avsGetTime(prjm_eval_exptreenode* node, PRJM_EVAL_F** result)
{
    const PRJM_EVAL_F since = argument(node, 0);
    if ((since > -1.001 && since < -0.999) || (since > -2.001 && since < -1.999)) {
        **result = 0.0;
        return;
    }
    const auto now = std::chrono::steady_clock::now().time_since_epoch();
    **result = std::chrono::duration<PRJM_EVAL_F>(now).count() - since;
}

// getkbmouse reads the PC's mouse and keys. There are none: nothing is pressed
// and the pointer rests in the middle.
void avsGetKbMouse(prjm_eval_exptreenode* node, PRJM_EVAL_F** result)
{
    argument(node, 0);
    **result = 0.0;
}

void avsSetMousePos(prjm_eval_exptreenode* node, PRJM_EVAL_F** result)
{
    argument(node, 0);
    argument(node, 1);
    **result = 0.0;
}

// The functions AVS adds to ns-eel (avs_eelif.cpp AVS_EEL_IF_init), put at the
// head of this context's list. The context frees the list, the definitions
// and the names when it is destroyed, as it does the ones it starts with.
void addAvsFunctions(projectm_eval_context* context)
{
    struct Extra {
        const char* name;
        prjm_eval_expr_func_t* func;
        int args;
    };
    static const Extra extras[] = {
            {"getosc", avsGetOsc, 3},
            {"getspec", avsGetSpec, 3},
            {"gettime", avsGetTime, 1},
            {"getkbmouse", avsGetKbMouse, 1},
            {"setmousepos", avsSetMousePos, 2},
    };
    for (const Extra& extra : extras) {
        auto* item = static_cast<prjm_eval_function_list_item_t*>(malloc(sizeof(prjm_eval_function_list_item_t)));
        auto* def = static_cast<prjm_eval_function_def_t*>(malloc(sizeof(prjm_eval_function_def_t)));
        if (item == nullptr || def == nullptr) {
            free(item);
            free(def);
            return;
        }
        def->name = strdup(extra.name);
        def->func = extra.func;
        def->arg_count = extra.args;
        // read from outside the expression, so never folded to a constant
        def->is_const_eval = false;
        def->is_state_changing = false;
        item->function = def;
        item->next = context->functions.first;
        context->functions.first = item;
    }
}

inline projectm_eval_context* contextOf(jlong handle)
{
    return reinterpret_cast<projectm_eval_context*>(handle);
}

inline projectm_eval_code* codeOf(jlong handle)
{
    return reinterpret_cast<projectm_eval_code*>(handle);
}

inline PRJM_EVAL_F* variableOf(jlong handle)
{
    return reinterpret_cast<PRJM_EVAL_F*>(handle);
}

} // namespace

extern "C" {

// The evaluator asks the host for these so that expressions sharing a memory
// block across threads cannot race. AVS runs one preset on one render thread,
// and every context here is owned by the component that made it, so there is
// nothing to lock, and the header allows an empty function in that case.
// Evaluating on a second thread would need a real mutex here.
void projectm_eval_memory_host_lock_mutex()
{
}

void projectm_eval_memory_host_unlock_mutex()
{
}

JNIEXPORT jlong JNICALL
Java_nl_mattix_andamp_visualizer_avs_EelNative_contextCreate(JNIEnv* /* env */, jobject /* thiz */)
{
    projectm_eval_context* context = projectm_eval_context_create(nullptr, nullptr);
    if (context != nullptr) {
        addAvsFunctions(context);
    }
    return reinterpret_cast<jlong>(context);
}

// This frame's audio, for getosc and getspec: 576 samples in -1..1 and 576
// magnitudes in 0..1, turned into the signed and unsigned bytes AVS holds.
JNIEXPORT void JNICALL
Java_nl_mattix_andamp_visualizer_avs_EelNative_setAudio(
        JNIEnv* env, jobject /* thiz */, jfloatArray waveform, jfloatArray spectrum)
{
    jfloat samples[kSamples] = {};
    jsize count = env->GetArrayLength(waveform);
    env->GetFloatArrayRegion(waveform, 0, count < kSamples ? count : kSamples, samples);
    for (int i = 0; i < kSamples; i++) {
        int value = static_cast<int>(samples[i] * 128.0f);
        g_waveform[i] = value < -128 ? -128 : (value > 127 ? 127 : value);
    }
    memset(samples, 0, sizeof(samples));
    count = env->GetArrayLength(spectrum);
    env->GetFloatArrayRegion(spectrum, 0, count < kSamples ? count : kSamples, samples);
    for (int i = 0; i < kSamples; i++) {
        int value = static_cast<int>(samples[i] * 255.0f);
        g_spectrum[i] = value < 0 ? 0 : (value > 255 ? 255 : value);
    }
}

JNIEXPORT void JNICALL
Java_nl_mattix_andamp_visualizer_avs_EelNative_contextDestroy(
        JNIEnv* /* env */, jobject /* thiz */, jlong context)
{
    projectm_eval_context_destroy(contextOf(context));
}

JNIEXPORT void JNICALL
Java_nl_mattix_andamp_visualizer_avs_EelNative_contextResetVariables(
        JNIEnv* /* env */, jobject /* thiz */, jlong context)
{
    projectm_eval_context_reset_variables(contextOf(context));
}

JNIEXPORT jlong JNICALL
Java_nl_mattix_andamp_visualizer_avs_EelNative_registerVariable(
        JNIEnv* env, jobject /* thiz */, jlong context, jstring name)
{
    const char* chars = env->GetStringUTFChars(name, nullptr);
    if (chars == nullptr) {
        return 0;
    }
    PRJM_EVAL_F* slot = projectm_eval_context_register_variable(contextOf(context), chars);
    env->ReleaseStringUTFChars(name, chars);
    return reinterpret_cast<jlong>(slot);
}

JNIEXPORT jdouble JNICALL
Java_nl_mattix_andamp_visualizer_avs_EelNative_variableGet(
        JNIEnv* /* env */, jobject /* thiz */, jlong variable)
{
    return static_cast<jdouble>(*variableOf(variable));
}

JNIEXPORT void JNICALL
Java_nl_mattix_andamp_visualizer_avs_EelNative_variableSet(
        JNIEnv* /* env */, jobject /* thiz */, jlong variable, jdouble value)
{
    *variableOf(variable) = static_cast<PRJM_EVAL_F>(value);
}

JNIEXPORT jlong JNICALL
Java_nl_mattix_andamp_visualizer_avs_EelNative_codeCompile(
        JNIEnv* env, jobject /* thiz */, jlong context, jstring source)
{
    const char* chars = env->GetStringUTFChars(source, nullptr);
    if (chars == nullptr) {
        return 0;
    }
    projectm_eval_code* code = projectm_eval_code_compile(contextOf(context), chars);
    env->ReleaseStringUTFChars(source, chars);
    return reinterpret_cast<jlong>(code);
}

JNIEXPORT void JNICALL
Java_nl_mattix_andamp_visualizer_avs_EelNative_codeDestroy(
        JNIEnv* /* env */, jobject /* thiz */, jlong code)
{
    projectm_eval_code_destroy(codeOf(code));
}

JNIEXPORT jdouble JNICALL
Java_nl_mattix_andamp_visualizer_avs_EelNative_codeExecute(
        JNIEnv* /* env */, jobject /* thiz */, jlong code)
{
    return static_cast<jdouble>(projectm_eval_code_execute(codeOf(code)));
}

/** The compiler's last complaint, or null. Only meaningful right after a failed compile. */
JNIEXPORT jstring JNICALL
Java_nl_mattix_andamp_visualizer_avs_EelNative_lastError(
        JNIEnv* env, jobject /* thiz */, jlong context)
{
    int line = 0;
    int column = 0;
    const char* error = projectm_eval_get_error(contextOf(context), &line, &column);
    if (error == nullptr) {
        return nullptr;
    }
    const std::string message = std::string(error) + " (line " + std::to_string(line) +
                                ", column " + std::to_string(column) + ")";
    return env->NewStringUTF(message.c_str());
}

} // extern "C"
