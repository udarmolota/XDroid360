// XDroid360 launcher-only workaround for a lost Android Vulkan presentation
// surface after Home / Recents. The game library already listens for SDL3's
// WILL_ENTER_BACKGROUND and DID_ENTER_FOREGROUND lifecycle events and uses
// them to disconnect/reconnect Xenia's Vulkan presenter. Sometimes those
// callbacks arrive before SDL has a replacement ANativeWindow.
//
// When the Java SurfaceView is valid again, repeat the pair to force a new
// presenter connection. This lives outside libgame.so so existing game builds
// do not need a rebuild. No rendering or guest state is reset here.
#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>

#define LOG_TAG "XDroid360Surface"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// SDL3 ABI: the SDL_Event union has a 128-byte minimum size and 8-byte
// alignment. We only set its initial Uint32 'type' and zero all other fields.
typedef union {
    uint32_t type;
    uint64_t alignment;
    unsigned char data[128];
} SurfaceRecoveryEvent;

typedef bool (*SDL_PushEventFn)(SurfaceRecoveryEvent* event);
typedef uint32_t (*SDL_WasInitFn)(uint32_t flags);

#define SDL_INIT_VIDEO_COMPAT 0x00000020u
#define SDL_EVENT_WILL_ENTER_BACKGROUND_COMPAT 0x103u
#define SDL_EVENT_DID_ENTER_FOREGROUND_COMPAT 0x106u

JNIEXPORT jboolean JNICALL
Java_com_udarmolota_xdroid360_GameActivity_nativeRequestSurfaceRecovery(
        JNIEnv* env, jclass clazz) {
    (void)env;
    (void)clazz;

    // libSDL3.so was loaded by GameActivity before this library.
    void* sdl = dlopen("libSDL3.so", RTLD_NOW | RTLD_NOLOAD);
    if (!sdl) {
        LOGW("libSDL3.so not loaded yet");
        return JNI_FALSE;
    }
    SDL_WasInitFn was_init = (SDL_WasInitFn)dlsym(sdl, "SDL_WasInit");
    SDL_PushEventFn push_event = (SDL_PushEventFn)dlsym(sdl, "SDL_PushEvent");
    if (!was_init || !push_event || !(was_init(SDL_INIT_VIDEO_COMPAT) & SDL_INIT_VIDEO_COMPAT)) {
        dlclose(sdl);
        return JNI_FALSE;
    }

    SurfaceRecoveryEvent event;
    memset(&event, 0, sizeof(event));
    event.type = SDL_EVENT_WILL_ENTER_BACKGROUND_COMPAT;
    bool lost_sent = push_event(&event);
    event.type = SDL_EVENT_DID_ENTER_FOREGROUND_COMPAT;
    bool ready_sent = push_event(&event);
    dlclose(sdl);
    if (lost_sent && ready_sent) {
        LOGI("queued Vulkan presenter disconnect/reconnect on SDL event loop");
        return JNI_TRUE;
    }
    LOGW("SDL rejected surface recovery events (lost=%d ready=%d)", lost_sent, ready_sent);
    return JNI_FALSE;
}
