#include <jni.h>

#include <mutex>
#include <string>

#include "include/tun2proxy.h"

namespace {

JavaVM* g_vm = nullptr;
std::mutex g_state_mutex;
jobject g_service_ref = nullptr;
jmethodID g_log_method = nullptr;

JNIEnv* getEnv(bool* attached) {
    if (attached != nullptr) *attached = false;
    if (g_vm == nullptr) return nullptr;

    JNIEnv* env = nullptr;
    const auto getEnvResult = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (getEnvResult == JNI_OK) return env;
    if (getEnvResult != JNI_EDETACHED) return nullptr;

    if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        return nullptr;
    }
    if (attached != nullptr) *attached = true;
    return env;
}

void clearServiceRef(JNIEnv* env) {
    std::lock_guard<std::mutex> lock(g_state_mutex);
    if (g_service_ref != nullptr && env != nullptr) {
        env->DeleteGlobalRef(g_service_ref);
    }
    g_service_ref = nullptr;
    g_log_method = nullptr;
}

void setServiceRef(JNIEnv* env, jobject service) {
    std::lock_guard<std::mutex> lock(g_state_mutex);
    if (g_service_ref != nullptr) {
        env->DeleteGlobalRef(g_service_ref);
        g_service_ref = nullptr;
    }
    g_service_ref = env->NewGlobalRef(service);
    jclass cls = env->GetObjectClass(service);
    g_log_method = env->GetMethodID(cls, "onTun2ProxyLog", "(Ljava/lang/String;)V");
    env->DeleteLocalRef(cls);
}

void logCallback(enum Tun2proxyVerbosity /*verbosity*/, const char* message, void* /*ctx*/) {
    bool attached = false;
    JNIEnv* env = getEnv(&attached);
    if (env == nullptr) return;

    jobject serviceRef = nullptr;
    jmethodID logMethod = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_state_mutex);
        serviceRef = g_service_ref;
        logMethod = g_log_method;
    }

    if (serviceRef != nullptr && logMethod != nullptr) {
        const char* safeMessage = message != nullptr ? message : "";
        jstring jMessage = env->NewStringUTF(safeMessage);
        env->CallVoidMethod(serviceRef, logMethod, jMessage);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        env->DeleteLocalRef(jMessage);
    }

    if (attached) {
        g_vm->DetachCurrentThread();
    }
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_example_operaproxy_ProxyVpnService_nativeStartTun2proxy(
    JNIEnv* env,
    jclass /*clazz*/,
    jobject service,
    jstring proxyUrl,
    jint tunFd,
    jboolean closeFdOnDrop,
    jchar tunMtu,
    jint dnsStrategy,
    jint verbosity
) {
    if (proxyUrl == nullptr) return -1;

    setServiceRef(env, service);
    tun2proxy_set_log_callback(logCallback, nullptr);

    const char* proxyUrlChars = env->GetStringUTFChars(proxyUrl, nullptr);
    const auto rc = tun2proxy_with_fd_run(
        proxyUrlChars,
        static_cast<int>(tunFd),
        closeFdOnDrop == JNI_TRUE,
        false,
        static_cast<unsigned short>(tunMtu),
        static_cast<Tun2proxyDns>(dnsStrategy),
        static_cast<Tun2proxyVerbosity>(verbosity)
    );
    env->ReleaseStringUTFChars(proxyUrl, proxyUrlChars);

    clearServiceRef(env);
    return static_cast<jint>(rc);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_operaproxy_ProxyVpnService_nativeStopTun2proxy(
    JNIEnv* env,
    jclass /*clazz*/
) {
    const auto rc = tun2proxy_stop();
    clearServiceRef(env);
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}
