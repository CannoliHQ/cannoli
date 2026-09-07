/* Native game id extraction for the launcher.
 *
 * sigil_extract_from_path does the whole job: it routes CHD, CSO, raw CD tracks, ZArchive and zip
 * containers itself, so this shim is one call and never opens a file of its own.
 *
 * The platform is always passed explicitly, never SIGIL_PLATFORM_AUTO, because the rom's tag
 * already says what the game is and sigil refuses to guess between PSX, PS2, PSP, Wii, GameCube,
 * Dreamcast and Xbox for the .iso, .bin and .chd they share. The switch below maps Cannoli's own
 * ordinals onto named constants, so a renumbering upstream is a compile error here rather than a
 * platform silently becoming another one.
 *
 * Every call returns seven strings. Index 0 is the status, "OK" or sigil's own message for the
 * failure, and the six after it are the result, empty when there is nothing to report. One shape
 * for both outcomes means the Kotlin side never branches on the array's length, and it keeps the
 * reason a failure happened, which the SD log wants and which a bare null would throw away.
 */

#include <jni.h>
#include <string.h>

#include "sigil.h"

#define FIELD_COUNT 7

static sigil_platform platform_for(jint ordinal)
{
    switch (ordinal) {
    case 0: return SIGIL_PLATFORM_PSX;
    case 1: return SIGIL_PLATFORM_PS2;
    case 2: return SIGIL_PLATFORM_PSP;
    case 3: return SIGIL_PLATFORM_PSVITA;
    case 4: return SIGIL_PLATFORM_GAMECUBE;
    case 5: return SIGIL_PLATFORM_WII;
    case 6: return SIGIL_PLATFORM_WIIU;
    case 7: return SIGIL_PLATFORM_3DS;
    case 8: return SIGIL_PLATFORM_DREAMCAST;
    default: return SIGIL_PLATFORM_AUTO;
    }
}

static const char *usage_name(sigil_usage usage)
{
    switch (usage) {
    case SIGIL_USAGE_FOLDER_EXACT:  return "FOLDER_EXACT";
    case SIGIL_USAGE_FOLDER_PREFIX: return "FOLDER_PREFIX";
    case SIGIL_USAGE_FILE_EXACT:    return "FILE_EXACT";
    case SIGIL_USAGE_FILE_PREFIX:   return "FILE_PREFIX";
    case SIGIL_USAGE_FOLDER_SPLIT:  return "FOLDER_SPLIT";
    default: return "";
    }
}

static void put(JNIEnv *env, jobjectArray out, jsize index, const char *value)
{
    jstring s = (*env)->NewStringUTF(env, value ? value : "");
    if (s) {
        (*env)->SetObjectArrayElement(env, out, index, s);
        (*env)->DeleteLocalRef(env, s);
    }
}

JNIEXPORT jobjectArray JNICALL
Java_dev_cannoli_scorza_sigil_SigilNative_nativeExtract(
        JNIEnv *env, jobject thiz, jstring path, jint platform)
{
    jobjectArray out;
    jclass string_class;
    const char *cpath;
    sigil_result result;
    sigil_platform hint;
    int rc;

    (void)thiz;

    string_class = (*env)->FindClass(env, "java/lang/String");
    if (!string_class)
        return NULL;

    out = (*env)->NewObjectArray(env, FIELD_COUNT, string_class, NULL);
    if (!out)
        return NULL;

    for (jsize i = 0; i < FIELD_COUNT; i++)
        put(env, out, i, "");

    hint = platform_for(platform);
    if (!path || hint == SIGIL_PLATFORM_AUTO) {
        put(env, out, 0, sigil_strerror(SIGIL_ERR_INVALID_ARG));
        return out;
    }

    cpath = (*env)->GetStringUTFChars(env, path, NULL);
    if (!cpath) {
        put(env, out, 0, sigil_strerror(SIGIL_ERR_OOM));
        return out;
    }

    memset(&result, 0, sizeof(result));
    result.struct_version = SIGIL_RESULT_V2;
    rc = sigil_extract_from_path(cpath, hint, NULL, &result);
    (*env)->ReleaseStringUTFChars(env, path, cpath);

    if (rc != SIGIL_OK) {
        put(env, out, 0, sigil_strerror(rc));
        return out;
    }

    put(env, out, 0, "OK");
    put(env, out, 1, result.title_id);
    put(env, out, 2, result.save_id);
    put(env, out, 3, result.raw_serial);
    put(env, out, 4, usage_name(result.usage));
    put(env, out, 5, result.source == SIGIL_SOURCE_FILENAME ? "FILENAME" : "BINARY");
    put(env, out, 6, result.experimental ? "1" : "0");
    return out;
}
