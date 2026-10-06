#include <jni.h>
#include <string.h>

#include "rc_hash.h"

JNIEXPORT jstring JNICALL
Java_dev_cannoli_scorza_achievements_RaHasher_nativeHashRom(
        JNIEnv *env, jobject thiz, jstring path, jint consoleId)
{
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
