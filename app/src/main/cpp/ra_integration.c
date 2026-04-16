#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <stdarg.h>
#include <pthread.h>
#include <android/log.h>
#include "rc_client.h"
#include "rc_consoles.h"
#include "rc_libretro.h"
#include "rc_api_runtime.h"
#include "rc_api_user.h"

#define LOG_TAG "RA"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* Bridge functions from libretro_bridge.cpp */
extern void *bridge_get_memory_data(unsigned id);
extern size_t bridge_get_memory_size(unsigned id);
extern const struct retro_memory_map *bridge_get_memory_map(void);

/* ---- state ---- */
static rc_client_t *g_client = NULL;
static JavaVM *g_jvm = NULL;
static jobject g_manager = NULL;
static jmethodID g_onServerCall = NULL;
static jmethodID g_onEvent = NULL;
static jmethodID g_onLoginResult = NULL;
static jmethodID g_onAwardResult = NULL;
static jmethodID g_onLog = NULL;
static pthread_mutex_t g_queue_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_client_mutex = PTHREAD_MUTEX_INITIALIZER;

static long g_trylock_skip_count = 0;
static long g_frame_count = 0;

static void ra_log_to_kotlin(const char *fmt, ...) {
    if (!g_jvm || !g_manager || !g_onLog) return;
    char buf[512];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);

    JNIEnv *env;
    int attached = 0;
    if ((*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        (*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL);
        attached = 1;
    }
    jstring jMsg = (*env)->NewStringUTF(env, buf);
    if (jMsg) {
        (*env)->CallVoidMethod(env, g_manager, g_onLog, jMsg);
        (*env)->DeleteLocalRef(env, jMsg);
    }
    if (attached) (*g_jvm)->DetachCurrentThread(g_jvm);
}

static char *g_pending_rom_path = NULL;
static uint32_t g_pending_console_id = 0;
static uint32_t g_pending_game_id = 0;
static rc_libretro_memory_regions_t g_memory_regions;
static int g_memory_initialized = 0;

static void ra_load_game_callback(int result, const char *error_message, rc_client_t *client, void *userdata);

/* ---- HTTP response callback holder ---- */
/* Allocated per outgoing rc_client server request, freed when the matching
 * response arrives in nativeHttpResponse. Response bodies are processed
 * inline on the HTTP delivery thread under g_client_mutex so rc_client
 * state mutation never happens on the GL thread. */
typedef struct QueuedResponse {
    rc_client_server_callback_t callback;
    void *callback_data;
} QueuedResponse;

/* ---- rcheevos callbacks ---- */

static void ra_get_core_memory(unsigned id, rc_libretro_core_memory_info_t *info) {
    info->data = (uint8_t *)bridge_get_memory_data(id);
    info->size = bridge_get_memory_size(id);
    LOGI("get_core_memory(%u): data=%p size=%zu", id, info->data, info->size);
}

static int g_memory_read_logged = 0;

static uint32_t ra_read_memory(uint32_t address, uint8_t *buffer, uint32_t num_bytes, rc_client_t *client) {
    (void)client;
    if (!g_memory_initialized) {
        if (!g_memory_read_logged) {
            g_memory_read_logged = 1;
            ra_log_to_kotlin("read_memory: BLOCKED (memory not initialized) addr=0x%x bytes=%u", address, num_bytes);
        }
        return 0;
    }
    uint32_t result = rc_libretro_memory_read(&g_memory_regions, address, buffer, num_bytes);
    if (!g_memory_read_logged) {
        g_memory_read_logged = 1;
        ra_log_to_kotlin("read_memory: first read addr=0x%x bytes=%u result=%u", address, num_bytes, result);
    }
    return result;
}

static void ra_server_call(const rc_api_request_t *request,
                           rc_client_server_callback_t callback, void *callback_data,
                           rc_client_t *client) {
    (void)client;

    /* Intercept game data fetch when we have a game ID override — replace fake hash with game ID */
    if (g_pending_game_id && request->post_data && strstr(request->post_data, "CANNOLI_")) {
        LOGI("Intercepting request, replacing hash with game ID %u", g_pending_game_id);
        /* Rebuild post data: replace m=CANNOLI_xxx with g=GAMEID */
        char new_post[512];
        const char *p = request->post_data;
        size_t pos = 0;
        while (*p) {
            if (strncmp(p, "m=CANNOLI_", 10) == 0) {
                pos += snprintf(new_post + pos, sizeof(new_post) - pos, "g=%u", g_pending_game_id);
                p += 10;
                while (*p && *p != '&') p++;
            } else {
                new_post[pos++] = *p++;
            }
        }
        new_post[pos] = '\0';
        g_pending_game_id = 0;

        rc_api_request_t patched;
        memset(&patched, 0, sizeof(patched));
        patched.url = request->url;
        patched.post_data = new_post;
        ra_server_call(&patched, callback, callback_data, client);
        return;
    }

    if (!g_jvm || !g_manager) {
        rc_api_server_response_t response;
        memset(&response, 0, sizeof(response));
        response.http_status_code = RC_API_SERVER_RESPONSE_CLIENT_ERROR;
        callback(&response, callback_data);
        return;
    }

    /* Store callback info for later response */
    QueuedResponse *qr = (QueuedResponse *)calloc(1, sizeof(QueuedResponse));
    if (!qr) {
        rc_api_server_response_t response;
        memset(&response, 0, sizeof(response));
        response.http_status_code = RC_API_SERVER_RESPONSE_CLIENT_ERROR;
        callback(&response, callback_data);
        return;
    }
    qr->callback = callback;
    qr->callback_data = callback_data;

    LOGI("server_call: %s", request->url);
    /* Call Kotlin to execute HTTP */
    JNIEnv *env;
    int attached = 0;
    if ((*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        (*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL);
        attached = 1;
    }

    jstring jUrl = (*env)->NewStringUTF(env, request->url);
    if (!jUrl) {
        /* OOM — deliver error response to avoid stalling rcheevos */
        rc_api_server_response_t response;
        memset(&response, 0, sizeof(response));
        response.http_status_code = RC_API_SERVER_RESPONSE_CLIENT_ERROR;
        callback(&response, callback_data);
        free(qr);
        if (attached) (*g_jvm)->DetachCurrentThread(g_jvm);
        return;
    }
    jstring jPost = request->post_data ? (*env)->NewStringUTF(env, request->post_data) : NULL;

    (*env)->CallVoidMethod(env, g_manager, g_onServerCall, jUrl, jPost, (jlong)(uintptr_t)qr);

    (*env)->DeleteLocalRef(env, jUrl);
    if (jPost) (*env)->DeleteLocalRef(env, jPost);
    if (attached) (*g_jvm)->DetachCurrentThread(g_jvm);
}

static void ra_event_handler(const rc_client_event_t *event, rc_client_t *client) {
    (void)client;
    if (!g_jvm || !g_manager) return;

    ra_log_to_kotlin("event: type=%d", event->type);

    if (event->type != RC_CLIENT_EVENT_ACHIEVEMENT_TRIGGERED &&
        event->type != RC_CLIENT_EVENT_GAME_COMPLETED)
        return;

    const char *title = "";
    const char *desc = "";
    int points = 0;
    int achId = 0;
    int type = event->type;

    if (event->achievement) {
        if (event->achievement->title &&
            (strncmp(event->achievement->title, "Warning:", 8) == 0 ||
             strncmp(event->achievement->title, "Unsupported", 11) == 0))
            return;
        title = event->achievement->title;
        desc = event->achievement->description;
        points = event->achievement->points;
        achId = (int)event->achievement->id;
    }

    JNIEnv *env;
    int attached = 0;
    if ((*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        (*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL);
        attached = 1;
    }

    jstring jTitle = (*env)->NewStringUTF(env, title);
    jstring jDesc = (*env)->NewStringUTF(env, desc);
    (*env)->CallVoidMethod(env, g_manager, g_onEvent, type, achId, jTitle, jDesc, points);
    (*env)->DeleteLocalRef(env, jTitle);
    (*env)->DeleteLocalRef(env, jDesc);

    if (attached) (*g_jvm)->DetachCurrentThread(g_jvm);
}

static void ra_log(const char *message, const rc_client_t *client) {
    (void)client;
    LOGI("%s", message);
    ra_log_to_kotlin("rcheevos: %s", message);
}

/* ---- JNI functions ---- */

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_RetroAchievementsManager_nativeInit(JNIEnv *env, jobject thiz) {
    (*env)->GetJavaVM(env, &g_jvm);
    g_manager = (*env)->NewGlobalRef(env, thiz);

    jclass cls = (*env)->GetObjectClass(env, thiz);
    g_onServerCall = (*env)->GetMethodID(env, cls, "onServerCall", "(Ljava/lang/String;Ljava/lang/String;J)V");
    g_onEvent = (*env)->GetMethodID(env, cls, "onAchievementEvent", "(IILjava/lang/String;Ljava/lang/String;I)V");
    g_onLoginResult = (*env)->GetMethodID(env, cls, "onLoginResult", "(ZLjava/lang/String;Ljava/lang/String;)V");
    g_onLog = (*env)->GetMethodID(env, cls, "onNativeLog", "(Ljava/lang/String;)V");
    (*env)->DeleteLocalRef(env, cls);

    g_frame_count = 0;
    g_trylock_skip_count = 0;

    g_client = rc_client_create(ra_read_memory, ra_server_call);
    rc_client_enable_logging(g_client, RC_CLIENT_LOG_LEVEL_INFO, ra_log);
    rc_client_set_hardcore_enabled(g_client, 0);
    rc_client_set_event_handler(g_client, ra_event_handler);

    LOGI("RetroAchievements initialized");
    ra_log_to_kotlin("native init complete");
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_RetroAchievementsManager_nativeDestroy(JNIEnv *env, jobject thiz) {
    (void)thiz;
    ra_log_to_kotlin("destroy: frames=%ld trylockSkips=%ld memInit=%d", g_frame_count, g_trylock_skip_count, g_memory_initialized);
    pthread_mutex_lock(&g_client_mutex);
    if (g_memory_initialized) {
        rc_libretro_memory_destroy(&g_memory_regions);
        g_memory_initialized = 0;
    }
    free(g_pending_rom_path);
    g_pending_rom_path = NULL;
    g_pending_console_id = 0;
    g_pending_game_id = 0;
    if (g_client) {
        rc_client_destroy(g_client);
        g_client = NULL;
    }
    if (g_manager) {
        (*env)->DeleteGlobalRef(env, g_manager);
        g_manager = NULL;
    }
    pthread_mutex_unlock(&g_client_mutex);
    LOGI("RetroAchievements destroyed");
}

static void ra_login_callback(int result, const char *error_message, rc_client_t *client, void *userdata) {
    (void)client; (void)userdata;
    if (!g_jvm || !g_manager) return;

    JNIEnv *env;
    int attached = 0;
    if ((*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        (*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL);
        attached = 1;
    }

    if (result == RC_OK) {
        const rc_client_user_t *user = rc_client_get_user_info(g_client);
        LOGI("Logged in as %s (score: %u)", user->display_name, user->score);
        jstring jName = (*env)->NewStringUTF(env, user->display_name);
        jstring jToken = (*env)->NewStringUTF(env, user->token);
        (*env)->CallVoidMethod(env, g_manager, g_onLoginResult, JNI_TRUE, jName, jToken);
        (*env)->DeleteLocalRef(env, jName);
        (*env)->DeleteLocalRef(env, jToken);

        if (g_pending_rom_path || g_pending_game_id) {
            const struct retro_memory_map *mmap = bridge_get_memory_map();
            int mem_result = rc_libretro_memory_init(&g_memory_regions, mmap, ra_get_core_memory, g_pending_console_id);
            g_memory_initialized = 1;
            ra_log_to_kotlin("memory init: result=%d mmap=%s regions=%u totalSize=%u",
                mem_result, mmap ? "present" : "NULL",
                g_memory_regions.count, g_memory_regions.total_size);

            if (g_pending_game_id) {
                LOGI("Loading game by ID: %u (console %u)", g_pending_game_id, g_pending_console_id);
                char hash[33];
                snprintf(hash, sizeof(hash), "CANNOLI_%010u", g_pending_game_id);
                /* Register a fake hash→gameID mapping so rc_client can load by "hash" */
                rc_client_begin_load_game(g_client, hash, ra_load_game_callback, NULL);
                g_pending_game_id = 0;
            } else {
                LOGI("Loading pending game: %s (console %u)", g_pending_rom_path, g_pending_console_id);
                rc_client_begin_identify_and_load_game(g_client, g_pending_console_id,
                    g_pending_rom_path, NULL, 0, ra_load_game_callback, NULL);
                free(g_pending_rom_path);
                g_pending_rom_path = NULL;
            }
        }
    } else {
        LOGE("Login failed: %s", error_message ? error_message : "unknown error");
        jstring jErr = (*env)->NewStringUTF(env, error_message ? error_message : "Login failed");
        (*env)->CallVoidMethod(env, g_manager, g_onLoginResult, JNI_FALSE, jErr, NULL);
        (*env)->DeleteLocalRef(env, jErr);
    }

    if (attached) (*g_jvm)->DetachCurrentThread(g_jvm);
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_RetroAchievementsManager_nativeLoginWithToken(JNIEnv *env, jobject thiz,
        jstring username, jstring token) {
    (void)thiz;
    if (!g_client) return;
    const char *user = (*env)->GetStringUTFChars(env, username, NULL);
    const char *tok = (*env)->GetStringUTFChars(env, token, NULL);
    if (!user || !tok) {
        if (user) (*env)->ReleaseStringUTFChars(env, username, user);
        if (tok) (*env)->ReleaseStringUTFChars(env, token, tok);
        return;
    }
    pthread_mutex_lock(&g_client_mutex);
    rc_client_begin_login_with_token(g_client, user, tok, ra_login_callback, NULL);
    pthread_mutex_unlock(&g_client_mutex);
    (*env)->ReleaseStringUTFChars(env, username, user);
    (*env)->ReleaseStringUTFChars(env, token, tok);
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_RetroAchievementsManager_nativeLoginWithPassword(JNIEnv *env, jobject thiz,
        jstring username, jstring password) {
    (void)thiz;
    if (!g_client) return;
    const char *user = (*env)->GetStringUTFChars(env, username, NULL);
    const char *pass = (*env)->GetStringUTFChars(env, password, NULL);
    if (!user || !pass) {
        if (user) (*env)->ReleaseStringUTFChars(env, username, user);
        if (pass) (*env)->ReleaseStringUTFChars(env, password, pass);
        return;
    }
    pthread_mutex_lock(&g_client_mutex);
    rc_client_begin_login_with_password(g_client, user, pass, ra_login_callback, NULL);
    pthread_mutex_unlock(&g_client_mutex);
    (*env)->ReleaseStringUTFChars(env, username, user);
    (*env)->ReleaseStringUTFChars(env, password, pass);
}

static void ra_load_game_callback(int result, const char *error_message, rc_client_t *client, void *userdata) {
    (void)client; (void)userdata;
    if (result == RC_OK) {
        const rc_client_game_t *game = rc_client_get_game_info(g_client);
        LOGI("Game loaded: %s (id: %u)", game->title, game->id);
        ra_log_to_kotlin("game loaded: id=%u title=%s", game->id, game->title);
    } else {
        LOGE("Game load failed: %s", error_message ? error_message : "unknown error");
        ra_log_to_kotlin("game load FAILED: %s", error_message ? error_message : "unknown error");
    }
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_RetroAchievementsManager_nativeLoadGame(JNIEnv *env, jobject thiz,
        jstring romPath, jint consoleId) {
    (void)thiz;
    if (!g_client) return;
    const char *path = (*env)->GetStringUTFChars(env, romPath, NULL);
    if (!path) return;
    free(g_pending_rom_path);
    g_pending_rom_path = strdup(path);
    g_pending_console_id = (uint32_t)consoleId;
    (*env)->ReleaseStringUTFChars(env, romPath, path);
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_RetroAchievementsManager_nativeLoadGameById(JNIEnv *env, jobject thiz,
        jint gameId, jint consoleId) {
    (void)env; (void)thiz;
    if (!g_client) return;
    free(g_pending_rom_path);
    g_pending_rom_path = NULL;
    g_pending_console_id = (uint32_t)consoleId;
    g_pending_game_id = (uint32_t)gameId;
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_RetroAchievementsManager_nativeUnloadGame(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    pthread_mutex_lock(&g_client_mutex);
    if (g_memory_initialized) {
        rc_libretro_memory_destroy(&g_memory_regions);
        g_memory_initialized = 0;
    }
    if (g_client) rc_client_unload_game(g_client);
    pthread_mutex_unlock(&g_client_mutex);
}

void ra_process_frame(void) {
    if (!g_client) return;
    if (pthread_mutex_trylock(&g_client_mutex) != 0) {
        g_trylock_skip_count++;
        return;
    }
    g_frame_count++;
    if (g_frame_count % 3600 == 0) {
        ra_log_to_kotlin("frame stats: frames=%ld trylockSkips=%ld gameLoaded=%d memInit=%d",
            g_frame_count, g_trylock_skip_count,
            rc_client_is_game_loaded(g_client), g_memory_initialized);
    }
    if (rc_client_is_game_loaded(g_client))
        rc_client_do_frame(g_client);
    pthread_mutex_unlock(&g_client_mutex);
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_RetroAchievementsManager_nativeDoFrame(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    ra_process_frame();
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_RetroAchievementsManager_nativeIdle(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_RetroAchievementsManager_nativeReset(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    ra_log_to_kotlin("reset: frames=%ld trylockSkips=%ld", g_frame_count, g_trylock_skip_count);
    pthread_mutex_lock(&g_client_mutex);
    if (g_client) rc_client_reset(g_client);
    pthread_mutex_unlock(&g_client_mutex);
}

JNIEXPORT jboolean JNICALL
Java_dev_cannoli_scorza_libretro_RetroAchievementsManager_nativeIsLoggedIn(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    pthread_mutex_lock(&g_client_mutex);
    jboolean result = JNI_FALSE;
    if (g_client) {
        const rc_client_user_t *user = rc_client_get_user_info(g_client);
        if (user && user->token[0]) result = JNI_TRUE;
    }
    pthread_mutex_unlock(&g_client_mutex);
    return result;
}

JNIEXPORT jstring JNICALL
Java_dev_cannoli_scorza_libretro_RetroAchievementsManager_nativeGetUsername(JNIEnv *env, jobject thiz) {
    (void)thiz;
    char name[256];
    name[0] = '\0';
    pthread_mutex_lock(&g_client_mutex);
    if (g_client) {
        const rc_client_user_t *user = rc_client_get_user_info(g_client);
        if (user && user->display_name) {
            strncpy(name, user->display_name, sizeof(name) - 1);
            name[sizeof(name) - 1] = '\0';
        }
    }
    pthread_mutex_unlock(&g_client_mutex);
    return (*env)->NewStringUTF(env, name);
}

JNIEXPORT jint JNICALL
Java_dev_cannoli_scorza_libretro_RetroAchievementsManager_nativeGetGameId(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    pthread_mutex_lock(&g_client_mutex);
    jint result = 0;
    if (g_client && rc_client_is_game_loaded(g_client)) {
        const rc_client_game_t *game = rc_client_get_game_info(g_client);
        if (game) result = (jint)game->id;
    }
    pthread_mutex_unlock(&g_client_mutex);
    return result;
}

JNIEXPORT jstring JNICALL
Java_dev_cannoli_scorza_libretro_RetroAchievementsManager_nativeGetGameTitle(JNIEnv *env, jobject thiz) {
    (void)thiz;
    char title[256];
    title[0] = '\0';
    pthread_mutex_lock(&g_client_mutex);
    if (g_client && rc_client_is_game_loaded(g_client)) {
        const rc_client_game_t *game = rc_client_get_game_info(g_client);
        if (game && game->title) {
            strncpy(title, game->title, sizeof(title) - 1);
            title[sizeof(title) - 1] = '\0';
        }
    }
    pthread_mutex_unlock(&g_client_mutex);
    return (*env)->NewStringUTF(env, title);
}

JNIEXPORT jstring JNICALL
Java_dev_cannoli_scorza_libretro_RetroAchievementsManager_nativeGetUserAgentClause(JNIEnv *env, jobject thiz) {
    (void)thiz;
    char buf[128];
    buf[0] = '\0';
    pthread_mutex_lock(&g_client_mutex);
    if (g_client) {
        size_t n = rc_client_get_user_agent_clause(g_client, buf, sizeof(buf));
        if (n >= sizeof(buf)) n = sizeof(buf) - 1;
        buf[n] = '\0';
    }
    pthread_mutex_unlock(&g_client_mutex);
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_RetroAchievementsManager_nativeHttpResponse(JNIEnv *env, jobject thiz,
        jlong requestPtr, jstring body, jint httpStatus) {
    (void)thiz;
    QueuedResponse *qr = (QueuedResponse *)(uintptr_t)requestPtr;
    if (!qr) return;

    const char *bodyStr = body ? (*env)->GetStringUTFChars(env, body, NULL) : NULL;
    size_t len = bodyStr ? strlen(bodyStr) : 0;
    char *bodyCopy = (char *)malloc(len + 1);
    if (bodyCopy) {
        if (bodyStr) memcpy(bodyCopy, bodyStr, len);
        bodyCopy[len] = '\0';
    }
    if (bodyStr) (*env)->ReleaseStringUTFChars(env, body, bodyStr);

    if (qr->callback) {
        rc_api_server_response_t response;
        memset(&response, 0, sizeof(response));
        response.body = bodyCopy;
        response.body_length = len;
        response.http_status_code = httpStatus;

        pthread_mutex_lock(&g_client_mutex);
        qr->callback(&response, qr->callback_data);
        pthread_mutex_unlock(&g_client_mutex);
    }

    free(bodyCopy);
    free(qr);
}

/* Returns pipe-delimited flat string: id|title|description|points|unlocked|state|unlockTime\n per achievement */
JNIEXPORT jstring JNICALL
Java_dev_cannoli_scorza_libretro_RetroAchievementsManager_nativeGetAchievementData(JNIEnv *env, jobject thiz) {
    (void)thiz;
    pthread_mutex_lock(&g_client_mutex);
    if (!g_client || !rc_client_is_game_loaded(g_client)) {
        pthread_mutex_unlock(&g_client_mutex);
        return (*env)->NewStringUTF(env, "");
    }

    rc_client_achievement_list_t *list = rc_client_create_achievement_list(g_client,
        RC_CLIENT_ACHIEVEMENT_CATEGORY_CORE, RC_CLIENT_ACHIEVEMENT_LIST_GROUPING_LOCK_STATE);
    if (!list) {
        pthread_mutex_unlock(&g_client_mutex);
        return (*env)->NewStringUTF(env, "");
    }

    size_t cap = 4096;
    char *buf = (char *)malloc(cap);
    if (!buf) {
        rc_client_destroy_achievement_list(list);
        pthread_mutex_unlock(&g_client_mutex);
        return (*env)->NewStringUTF(env, "");
    }
    size_t pos = 0;

    for (uint32_t b = 0; b < list->num_buckets; b++) {
        const rc_client_achievement_bucket_t *bucket = &list->buckets[b];
        for (uint32_t a = 0; a < bucket->num_achievements; a++) {
            const rc_client_achievement_t *ach = bucket->achievements[a];
            if (ach->points == 0 && ach->id == 0) continue;
            int softcore = (ach->unlocked & RC_CLIENT_ACHIEVEMENT_UNLOCKED_SOFTCORE) ||
                           ach->state == RC_CLIENT_ACHIEVEMENT_STATE_UNLOCKED ? 1 : 0;
            if (!softcore && (ach->unlocked & RC_CLIENT_ACHIEVEMENT_UNLOCKED_HARDCORE)) continue;

            if (pos + 512 > cap) {
                cap *= 2;
                char *newbuf = (char *)realloc(buf, cap);
                if (!newbuf) {
                    free(buf);
                    rc_client_destroy_achievement_list(list);
                    pthread_mutex_unlock(&g_client_mutex);
                    return (*env)->NewStringUTF(env, "");
                }
                buf = newbuf;
            }
            pos += snprintf(buf + pos, cap - pos, "%u|%s|%s|%u|%d|%d|%ld\n",
                ach->id,
                ach->title ? ach->title : "",
                ach->description ? ach->description : "",
                ach->points, softcore, ach->state, (long)ach->unlock_time);
        }
    }
    if (pos > 0) buf[pos - 1] = '\0'; /* trim trailing newline */
    else buf[0] = '\0';

    rc_client_destroy_achievement_list(list);
    pthread_mutex_unlock(&g_client_mutex);
    jstring result = (*env)->NewStringUTF(env, buf);
    free(buf);
    return result;
}

static void ra_award_response(const rc_api_server_response_t *response, void *userdata) {
    int achId = (int)(intptr_t)userdata;
    int success = (response->http_status_code == 200 && response->body && strstr(response->body, "\"Success\":true"));
    if (success) LOGI("Achievement %d awarded", achId);
    else LOGE("Award %d failed (http=%d)", achId, response->http_status_code);

    if (g_jvm && g_manager && g_onAwardResult) {
        JNIEnv *env;
        int attached = 0;
        if ((*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
            (*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL);
            attached = 1;
        }
        (*env)->CallVoidMethod(env, g_manager, g_onAwardResult, achId, success ? JNI_TRUE : JNI_FALSE);
        if (attached) (*g_jvm)->DetachCurrentThread(g_jvm);
    }
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_RetroAchievementsManager_nativeManualUnlock(JNIEnv *env, jobject thiz, jint achievementId) {
    (void)thiz;
    pthread_mutex_lock(&g_client_mutex);
    if (!g_client) { pthread_mutex_unlock(&g_client_mutex); return; }

    const rc_client_user_t *user = rc_client_get_user_info(g_client);
    if (!user || !user->token[0]) { pthread_mutex_unlock(&g_client_mutex); return; }

    const rc_client_game_t *game = rc_client_get_game_info(g_client);

    LOGI("Manual unlock: achievement %d", achievementId);

    rc_api_award_achievement_request_t params;
    memset(&params, 0, sizeof(params));
    params.username = user->display_name;
    params.api_token = user->token;
    params.achievement_id = (uint32_t)achievementId;
    params.hardcore = 0;
    params.game_hash = game ? game->hash : "";

    rc_api_request_t request;
    if (rc_api_init_award_achievement_request(&request, &params) == RC_OK) {
        /* Send via our HTTP mechanism */
        if (g_jvm && g_manager) {
            JNIEnv *jenv = env;
            jstring jUrl = (*jenv)->NewStringUTF(jenv, request.url);
            jstring jPost = request.post_data ? (*jenv)->NewStringUTF(jenv, request.post_data) : NULL;

            QueuedResponse *qr = (QueuedResponse *)calloc(1, sizeof(QueuedResponse));
            qr->callback = NULL;
            qr->callback_data = NULL;

            (*jenv)->CallVoidMethod(jenv, g_manager, g_onServerCall, jUrl, jPost, (jlong)(uintptr_t)qr);
            (*jenv)->DeleteLocalRef(jenv, jUrl);
            if (jPost) (*jenv)->DeleteLocalRef(jenv, jPost);
        }
        rc_api_destroy_request(&request);
    }
    pthread_mutex_unlock(&g_client_mutex);
}
