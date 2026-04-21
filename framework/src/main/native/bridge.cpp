#include "bridge_internal.h"
#include "bridge_capture.h"
#include "bridge_frame_buffer.h"
#include "bridge_preview.h"

#include <android/bitmap.h>

#include <cstdlib>
#include <cstring>

static JavaVM *g_vm = nullptr;
static jclass g_driver_clz = nullptr;
static jmethodID g_capture_frame_method = nullptr;
static jmethodID g_touch_down_method = nullptr;
static jmethodID g_touch_move_method = nullptr;
static jmethodID g_touch_up_method = nullptr;
static jmethodID g_key_down_method = nullptr;
static jmethodID g_key_up_method = nullptr;
static jmethodID g_start_app_method = nullptr;
static jmethodID g_stop_app_method = nullptr;
static jmethodID g_input_text_method = nullptr;

bool CheckJNIException(JNIEnv *env, const char *context);

static bool BootstrapDriverBindings(JNIEnv *env, jclass driverClass) {
    if (!env || !driverClass) {
        return false;
    }
    if (g_driver_clz) {
        env->DeleteGlobalRef(g_driver_clz);
        g_driver_clz = nullptr;
    }
    g_capture_frame_method = nullptr;
    g_touch_down_method = nullptr;
    g_touch_move_method = nullptr;
    g_touch_up_method = nullptr;
    g_key_down_method = nullptr;
    g_key_up_method = nullptr;
    g_start_app_method = nullptr;
    g_stop_app_method = nullptr;
    g_input_text_method = nullptr;

    g_driver_clz = static_cast<jclass>(env->NewGlobalRef(driverClass));
    if (!g_driver_clz) {
        return false;
    }

    g_capture_frame_method = env->GetStaticMethodID(g_driver_clz, "captureFrame", "()Landroid/graphics/Bitmap;");
    g_touch_down_method = env->GetStaticMethodID(g_driver_clz, "touchDown", "(III)Z");
    g_touch_move_method = env->GetStaticMethodID(g_driver_clz, "touchMove", "(III)Z");
    g_touch_up_method = env->GetStaticMethodID(g_driver_clz, "touchUp", "(III)Z");
    g_key_down_method = env->GetStaticMethodID(g_driver_clz, "keyDown", "(II)Z");
    g_key_up_method = env->GetStaticMethodID(g_driver_clz, "keyUp", "(II)Z");
    g_start_app_method = env->GetStaticMethodID(g_driver_clz, "startApp", "(Ljava/lang/String;IZ)Z");
    g_stop_app_method = env->GetStaticMethodID(g_driver_clz, "stopApp", "(Ljava/lang/String;I)Z");
    g_input_text_method = env->GetStaticMethodID(g_driver_clz, "inputText", "(Ljava/lang/String;)Z");

    if (CheckJNIException(env, "GetStaticMethodID(DriverClass)") ||
        !g_capture_frame_method || !g_touch_down_method || !g_touch_move_method ||
        !g_touch_up_method || !g_key_down_method || !g_key_up_method ||
        !g_start_app_method || !g_stop_app_method || !g_input_text_method) {
        env->DeleteGlobalRef(g_driver_clz);
        g_driver_clz = nullptr;
        return false;
    }
    return true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_maaframework_android_bridge_NativeBridgeLib_bootstrap(JNIEnv *env, jclass clazz, jclass driverClass) {
    (void) clazz;
    if (!BootstrapDriverBindings(env, driverClass)) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Failed to bootstrap DriverClass JNI bindings");
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_maaframework_android_bridge_NativeBridgeLib_ping(JNIEnv *env, jclass clazz) {
    (void) clazz;
    return env->NewStringUTF("MaaFrameworkBridge");
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_maaframework_android_bridge_NativeBridgeLib_setupNativeCapturer(JNIEnv *env, jclass clazz, jint width, jint height) {
    (void) clazz;
    return SetupNativeCapturer(env, width, height);
}

extern "C" JNIEXPORT void JNICALL
Java_com_maaframework_android_bridge_NativeBridgeLib_releaseNativeCapturer(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    ReleaseNativeCapturer();
}

extern "C" JNIEXPORT void JNICALL
Java_com_maaframework_android_bridge_NativeBridgeLib_setPreviewSurface(JNIEnv *env, jclass clazz, jobject jSurface) {
    (void) clazz;
    SetPreviewSurface(env, jSurface);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_maaframework_android_bridge_NativeBridgeLib_getFrameCount(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return static_cast<jlong>(GetFrameCount());
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_maaframework_android_bridge_NativeBridgeLib_capturePreviewFrame(JNIEnv *env, jclass clazz) {
    (void) clazz;
    return CreateFrameBufferBitmap(env);
}

struct FrameBufferHolder {
    uint8_t *data = nullptr;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t stride = 0;
    uint32_t length = 0;
};

bool CheckJNIException(JNIEnv *env, const char *context) {
    if (!env || !env->ExceptionCheck()) {
        return false;
    }
    LOGE("JNI exception in %s", context);
    env->ExceptionDescribe();
    env->ExceptionClear();
    return true;
}

static JNIEnv *GetJNIEnv(bool *needs_detach) {
    *needs_detach = false;
    if (!g_vm) {
        return nullptr;
    }

    JNIEnv *env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK) {
        return env;
    }
    if (g_vm->AttachCurrentThreadAsDaemon(&env, nullptr) != JNI_OK) {
        return nullptr;
    }
    *needs_detach = true;
    return env;
}

static int CallBool(JNIEnv *env, jmethodID method, jint a, jint b, jint c) {
    if (!env || !g_driver_clz || !method) {
        return -1;
    }
    jboolean ok = env->CallStaticBooleanMethod(g_driver_clz, method, a, b, c);
    if (CheckJNIException(env, "CallStaticBooleanMethod")) {
        return -1;
    }
    return ok ? 0 : -1;
}

static int CallBoolString(JNIEnv *env, jmethodID method, const char *text, jint displayId, jboolean flag) {
    if (!env || !g_driver_clz || !method || !text) {
        return -1;
    }
    jstring jText = env->NewStringUTF(text);
    jboolean ok = env->CallStaticBooleanMethod(g_driver_clz, method, jText, displayId, flag);
    env->DeleteLocalRef(jText);
    if (CheckJNIException(env, "CallStaticBooleanMethod(String)")) {
        return -1;
    }
    return ok ? 0 : -1;
}

static int CallBoolStringSimple(JNIEnv *env, jmethodID method, const char *text) {
    if (!env || !g_driver_clz || !method || !text) {
        return -1;
    }
    jstring jText = env->NewStringUTF(text);
    jboolean ok = env->CallStaticBooleanMethod(g_driver_clz, method, jText);
    env->DeleteLocalRef(jText);
    if (CheckJNIException(env, "CallStaticBooleanMethod(StringSimple)")) {
        return -1;
    }
    return ok ? 0 : -1;
}

static const char *MethodName(int method) {
    switch (method) {
        case TOUCH_DOWN:
            return "TOUCH_DOWN";
        case TOUCH_MOVE:
            return "TOUCH_MOVE";
        case TOUCH_UP:
            return "TOUCH_UP";
        case KEY_DOWN:
            return "KEY_DOWN";
        case KEY_UP:
            return "KEY_UP";
        case START_GAME:
            return "START_GAME";
        case STOP_GAME:
            return "STOP_GAME";
        case INPUT:
            return "INPUT";
        default:
            return "UNKNOWN";
    }
}

BRIDGE_API FrameInfo GetLockedPixels() {
    FrameInfo result {};
    LOGI("GetLockedPixels called");
    bool needs_detach = false;
    JNIEnv *env = GetJNIEnv(&needs_detach);
    if (!env || !g_capture_frame_method || !g_driver_clz) {
        return result;
    }

    jobject bitmap = CreateFrameBufferBitmap(env);
    if (!bitmap) {
        bitmap = env->CallStaticObjectMethod(g_driver_clz, g_capture_frame_method);
    }
    if (CheckJNIException(env, "captureFrame")) {
        if (needs_detach && g_vm) {
            g_vm->DetachCurrentThread();
        }
        return result;
    }
    if (!bitmap) {
        if (needs_detach && g_vm) {
            g_vm->DetachCurrentThread();
        }
        return result;
    }

    AndroidBitmapInfo info {};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        env->DeleteLocalRef(bitmap);
        if (needs_detach && g_vm) {
            g_vm->DetachCurrentThread();
        }
        return result;
    }

    void *pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels) {
        env->DeleteLocalRef(bitmap);
        if (needs_detach && g_vm) {
            g_vm->DetachCurrentThread();
        }
        return result;
    }

    auto *holder = new FrameBufferHolder();
    holder->width = info.width;
    holder->height = info.height;
    holder->stride = info.width * 3;
    holder->length = holder->stride * info.height;
    holder->data = static_cast<uint8_t *>(std::malloc(holder->length));
    if (!holder->data) {
        AndroidBitmap_unlockPixels(env, bitmap);
        env->DeleteLocalRef(bitmap);
        delete holder;
        if (needs_detach && g_vm) {
            g_vm->DetachCurrentThread();
        }
        return result;
    }

    auto *src = static_cast<uint8_t *>(pixels);
    for (uint32_t y = 0; y < info.height; ++y) {
        auto *srcRow = src + y * info.stride;
        auto *dstRow = holder->data + y * holder->stride;
        for (uint32_t x = 0; x < info.width; ++x) {
            dstRow[x * 3 + 0] = srcRow[x * 4 + 2];
            dstRow[x * 3 + 1] = srcRow[x * 4 + 1];
            dstRow[x * 3 + 2] = srcRow[x * 4 + 0];
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    env->DeleteLocalRef(bitmap);
    if (needs_detach && g_vm) {
        g_vm->DetachCurrentThread();
    }

    result.width = holder->width;
    result.height = holder->height;
    result.stride = holder->stride;
    result.length = holder->length;
    result.data = holder->data;
    result.frame_ref = holder;
    LOGI("GetLockedPixels success width=%u height=%u stride=%u", result.width, result.height, result.stride);
    return result;
}

BRIDGE_API int UnlockPixels(FrameInfo info) {
    auto *holder = reinterpret_cast<FrameBufferHolder *>(info.frame_ref);
    if (!holder) {
        return 0;
    }
    std::free(holder->data);
    delete holder;
    return 0;
}

BRIDGE_API int DispatchInputMessage(MethodParam param) {
    switch (param.method) {
        case TOUCH_DOWN:
        case TOUCH_MOVE:
        case TOUCH_UP:
            LOGI(
                    "DispatchInputMessage method=%s display=%d x=%d y=%d",
                    MethodName(param.method),
                    param.display_id,
                    param.args.touch.p.x,
                    param.args.touch.p.y
            );
            break;
        case KEY_DOWN:
        case KEY_UP:
            LOGI(
                    "DispatchInputMessage method=%s display=%d keyCode=%d",
                    MethodName(param.method),
                    param.display_id,
                    param.args.key.key_code
            );
            break;
        default:
            LOGI("DispatchInputMessage method=%s display=%d", MethodName(param.method), param.display_id);
            break;
    }
    bool needs_detach = false;
    JNIEnv *env = GetJNIEnv(&needs_detach);
    if (!env) {
        return -1;
    }

    int ret = -1;
    switch (param.method) {
        case TOUCH_DOWN:
            ret = CallBool(env, g_touch_down_method, param.args.touch.p.x, param.args.touch.p.y, param.display_id);
            break;
        case TOUCH_MOVE:
            ret = CallBool(env, g_touch_move_method, param.args.touch.p.x, param.args.touch.p.y, param.display_id);
            break;
        case TOUCH_UP:
            ret = CallBool(env, g_touch_up_method, param.args.touch.p.x, param.args.touch.p.y, param.display_id);
            break;
        case KEY_DOWN:
            ret = CallBool(env, g_key_down_method, param.args.key.key_code, param.display_id, 0);
            break;
        case KEY_UP:
            ret = CallBool(env, g_key_up_method, param.args.key.key_code, param.display_id, 0);
            break;
        case START_GAME:
            ret = CallBoolString(env, g_start_app_method, param.args.start_game.package_name, param.display_id,
                                 static_cast<jboolean>(param.args.start_game.force_stop != 0));
            break;
        case STOP_GAME:
            ret = CallBoolString(env, g_stop_app_method, param.args.stop_game.client_type, param.display_id, JNI_FALSE);
            break;
        case INPUT:
            ret = CallBoolStringSimple(env, g_input_text_method, param.args.input.text);
            break;
        default:
            ret = 0;
            break;
    }

    if (needs_detach && g_vm) {
        g_vm->DetachCurrentThread();
    }
    LOGI("DispatchInputMessage result method=%s display=%d ret=%d", MethodName(param.method), param.display_id, ret);
    return ret;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    g_vm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved) {
    (void) reserved;

    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK && env) {
        SetPreviewSurface(env, nullptr);
        if (g_driver_clz) {
            env->DeleteGlobalRef(g_driver_clz);
            g_driver_clz = nullptr;
        }
    }
}
