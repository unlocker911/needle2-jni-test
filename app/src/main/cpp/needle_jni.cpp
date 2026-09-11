#include <jni.h>
#include <string>
#include <cstring>
#include <cstdlib>
#include <android/log.h>

#include "needle.h"

#define LOG_TAG "Needle2JNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static JavaVM* g_jvm = nullptr;

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    LOGI("Needle2JNI loaded");
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved) {
    g_jvm = nullptr;
    LOGI("Needle2JNI unloaded");
}

// Helper to throw Java exception from native error
static void throwNeedleException(JNIEnv* env, const char* message) {
    jclass exceptionClass = env->FindClass("com/example/needle/NeedleException");
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_needle_NeedleJNI_load(JNIEnv* env, jclass clazz, jbyteArray modelBytes) {
    if (modelBytes == nullptr) {
        throwNeedleException(env, "Model byte array is null");
        return -1;
    }

    jsize length = env->GetArrayLength(modelBytes);
    if (length <= 0) {
        throwNeedleException(env, "Model byte array is empty");
        return -1;
    }

    jbyte* bytes = env->GetByteArrayElements(modelBytes, nullptr);
    if (bytes == nullptr) {
        throwNeedleException(env, "Failed to get byte array elements");
        return -1;
    }

    LOGI("Loading Needle model: %d bytes", length);

    int result = needle_load(
        reinterpret_cast<const unsigned char*>(bytes),
        static_cast<unsigned long long>(length)
    );

    env->ReleaseByteArrayElements(modelBytes, bytes, JNI_ABORT);

    if (result < 0) {
        char errorMsg[256];
        snprintf(errorMsg, sizeof(errorMsg), "needle_load failed with code: %d", result);
        throwNeedleException(env, errorMsg);
        LOGE("%s", errorMsg);
    } else {
        LOGI("needle_load succeeded: %d", result);
    }

    return static_cast<jint>(result);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_needle_NeedleJNI_init(JNIEnv* env, jclass clazz,
                                        jstring systemPrompt, jstring toolsJson, jstring toolIndexPath) {
    const char* sysPrompt = systemPrompt ? env->GetStringUTFChars(systemPrompt, nullptr) : nullptr;
    const char* tools = toolsJson ? env->GetStringUTFChars(toolsJson, nullptr) : nullptr;
    const char* indexPath = toolIndexPath ? env->GetStringUTFChars(toolIndexPath, nullptr) : nullptr;

    LOGI("Initializing Needle with system prompt: %s", sysPrompt ? "yes" : "no");
    LOGI("Tools JSON: %s", tools ? "yes" : "no");
    LOGI("Tool index path: %s", indexPath ? "yes" : "no");

    int result = needle_init(sysPrompt, tools, indexPath);

    if (systemPrompt) env->ReleaseStringUTFChars(systemPrompt, sysPrompt);
    if (toolsJson) env->ReleaseStringUTFChars(toolsJson, tools);
    if (toolIndexPath) env->ReleaseStringUTFChars(toolIndexPath, indexPath);

    if (result < 0) {
        char errorMsg[256];
        snprintf(errorMsg, sizeof(errorMsg), "needle_init failed with code: %d", result);
        throwNeedleException(env, errorMsg);
        LOGE("%s", errorMsg);
    } else {
        LOGI("needle_init succeeded: %d", result);
    }

    return static_cast<jint>(result);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_needle_NeedleJNI_complete(JNIEnv* env, jclass clazz,
                                            jstring input, jint maxNewTokens) {
    if (input == nullptr) {
        throwNeedleException(env, "Input string is null");
        return nullptr;
    }

    const char* inputStr = env->GetStringUTFChars(input, nullptr);
    if (inputStr == nullptr) {
        throwNeedleException(env, "Failed to get input string");
        return nullptr;
    }

    const int capacity = 16384;
    char* outputBuffer = static_cast<char*>(malloc(capacity));
    if (outputBuffer == nullptr) {
        env->ReleaseStringUTFChars(input, inputStr);
        throwNeedleException(env, "Failed to allocate output buffer");
        return nullptr;
    }

    LOGI("Completing: %s", inputStr);

    int result = needle_complete(inputStr, static_cast<int>(maxNewTokens), outputBuffer, capacity);

    env->ReleaseStringUTFChars(input, inputStr);

    if (result < 0) {
        free(outputBuffer);
        char errorMsg[256];
        snprintf(errorMsg, sizeof(errorMsg), "needle_complete failed with code: %d", result);
        throwNeedleException(env, errorMsg);
        LOGE("%s", errorMsg);
        return nullptr;
    }

    LOGI("needle_complete succeeded, output length: %d", result);

    jstring resultStr = env->NewStringUTF(outputBuffer);
    free(outputBuffer);
    return resultStr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_needle_NeedleJNI_reset(JNIEnv* env, jclass clazz) {
    LOGI("Resetting Needle");
    needle_reset();
    LOGI("Needle reset complete");
}