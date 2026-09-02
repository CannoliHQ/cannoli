/* RetroAchievements ROM hashing for the launcher.
 *
 * The game process has its own rcheevos inside RetroArch, but it only exists once a game is
 * running. Preload has to identify a game before that, from the launcher, which is why this small
 * library exists rather than reaching across to the other process.
 *
 * An empty string means "no hash", which is what the Kotlin side turns back into null. Every
 * failure path returns it rather than reporting a reason: a caller can do nothing with the
 * difference between an unreadable file and an unsupported console. */

#include <jni.h>
#include <string.h>

#include "rc_hash.h"

JNIEXPORT jstring JNICALL
Java_dev_cannoli_scorza_achievements_RaHasher_nativeHashRom(
        JNIEnv *env, jobject thiz, jstring path, jint consoleId)
{
    /* 32 hex characters and a terminator, the size rc_hash_generate writes. */
    char hash[33];
    const char *cpath;

    (void)thiz;
    hash[0] = '\0';

    if (!path)
        return (*env)->NewStringUTF(env, hash);

    cpath = (*env)->GetStringUTFChars(env, path, NULL);
    if (cpath) {
        rc_hash_iterator_t iterator;
        rc_hash_initialize_iterator(&iterator, cpath, NULL, 0);
        if (!rc_hash_generate(hash, (uint32_t)consoleId, &iterator))
            hash[0] = '\0';
        rc_hash_destroy_iterator(&iterator);
        (*env)->ReleaseStringUTFChars(env, path, cpath);
    }

    return (*env)->NewStringUTF(env, hash);
}
