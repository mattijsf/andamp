// SPDX-License-Identifier: GPL-3.0-or-later

// JNI glue for projectm-eval, the ns-eel2 reimplementation AVS scripts in.
//
// One context per component instance; variables are registered once and then
// written and read through the pointer the evaluator hands back, so a per-point
// loop sets x and y, runs the code and reads them back without marshalling.

#include <jni.h>

#include <projectM-eval.h>

#include <string>

namespace {

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
    return reinterpret_cast<jlong>(projectm_eval_context_create(nullptr, nullptr));
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
