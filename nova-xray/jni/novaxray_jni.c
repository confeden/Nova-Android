// JNI-мост к libnovaxray.so.
//
// Экспортированные Go-функции имеют обычный C ABI, из Kotlin их вызвать
// напрямую нельзя. Этот слой переводит вызовы и, главное, отдаёт Go-стороне
// указатель на функцию защиты сокета, которая уходит в VpnService.protect —
// без неё исходящие соединения Xray заворачивало бы обратно в туннель.

#include <jni.h>
#include <stdlib.h>
#include <string.h>

extern char *NovaXrayStart(char *configJSON);
extern void NovaXrayStop(void);
extern int NovaXrayIsRunning(void);
extern char *NovaXrayVersion(void);
extern void NovaXrayFree(char *value);
extern void NovaXraySetProtector(int (*fn)(int));

static JavaVM *g_vm = NULL;
static jobject g_protector = NULL;   // глобальная ссылка на VpnService
static jmethodID g_protect_method = NULL;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

// Вызывается из Go на потоке, который JVM не знает, поэтому поток
// присоединяется и отсоединяется вручную.
static int nova_protect_fd(int fd) {
    if (g_vm == NULL || g_protector == NULL || g_protect_method == NULL) {
        return 0;
    }
    JNIEnv *env = NULL;
    int attached = 0;
    if ((*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) {
            return 0;
        }
        attached = 1;
    }
    jboolean ok = (*env)->CallBooleanMethod(env, g_protector, g_protect_method, (jint) fd);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        ok = JNI_FALSE;
    }
    if (attached) {
        (*g_vm)->DetachCurrentThread(g_vm);
    }
    return ok == JNI_TRUE ? 1 : 0;
}

JNIEXPORT void JNICALL
Java_com_example_nova_XrayBridge_nativeSetProtector(JNIEnv *env, jclass clazz, jobject service) {
    if (g_protector != NULL) {
        (*env)->DeleteGlobalRef(env, g_protector);
        g_protector = NULL;
        g_protect_method = NULL;
    }
    if (service == NULL) {
        NovaXraySetProtector(NULL);
        return;
    }
    g_protector = (*env)->NewGlobalRef(env, service);
    jclass service_class = (*env)->GetObjectClass(env, service);
    g_protect_method = (*env)->GetMethodID(env, service_class, "protect", "(I)Z");
    (*env)->DeleteLocalRef(env, service_class);
    if (g_protect_method == NULL) {
        (*env)->ExceptionClear(env);
        return;
    }
    NovaXraySetProtector(&nova_protect_fd);
}

JNIEXPORT jstring JNICALL
Java_com_example_nova_XrayBridge_nativeStart(JNIEnv *env, jclass clazz, jstring config) {
    const char *config_utf = (*env)->GetStringUTFChars(env, config, NULL);
    if (config_utf == NULL) {
        return (*env)->NewStringUTF(env, "config: out of memory");
    }
    char *error = NovaXrayStart((char *) config_utf);
    (*env)->ReleaseStringUTFChars(env, config, config_utf);

    jstring result = (*env)->NewStringUTF(env, error == NULL ? "" : error);
    NovaXrayFree(error);
    return result;
}

JNIEXPORT void JNICALL
Java_com_example_nova_XrayBridge_nativeStop(JNIEnv *env, jclass clazz) {
    NovaXrayStop();
}

JNIEXPORT jboolean JNICALL
Java_com_example_nova_XrayBridge_nativeIsRunning(JNIEnv *env, jclass clazz) {
    return NovaXrayIsRunning() != 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_example_nova_XrayBridge_nativeVersion(JNIEnv *env, jclass clazz) {
    char *version = NovaXrayVersion();
    jstring result = (*env)->NewStringUTF(env, version == NULL ? "" : version);
    NovaXrayFree(version);
    return result;
}
