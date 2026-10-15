/*
 * JNI entry point for Argon2 (org.lujarch.vault.core.crypto.Argon2).
 *
 * The KeePass database uses Argon2d with 64 MiB, 117 passes and 4 lanes; a
 * JVM implementation takes tens of seconds with these parameters, the
 * reference C implementation (jni/argon2) a fraction of that.
 *
 * Every buffer that held key material is wiped before it is freed: the
 * password (the KeePass composite key) is copied out of the Java array,
 * handed to Argon2 with ARGON2_FLAG_CLEAR_PASSWORD, and the output is wiped
 * once it has been copied into the returned Java array.
 *
 * The lanes run as threads that the reference code creates per slice. Left
 * to the scheduler, some land on the slow cluster and every slice waits for
 * them: measured on the Xiaomi 13 with the database's parameters, 6.1 s on
 * all cores, 1.6-2.9 s with the threads kept on the faster clusters (9.7 s
 * on the little cores alone). So the calling thread is pinned to every
 * allowed core but those of the slowest cluster for the duration of the
 * call (new threads inherit the mask), then restored. When the process may
 * only use one cluster (a background cpuset), nothing changes.
 */
#define _GNU_SOURCE
#include <jni.h>
#include <limits.h>
#include <sched.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "argon2.h"

static long max_freq(int cpu) {
    char path[96];
    long freq = -1;
    snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
    FILE *fp = fopen(path, "re");
    if (fp == NULL) return -1;
    if (fscanf(fp, "%ld", &freq) != 1) freq = -1;
    fclose(fp);
    return freq;
}

/* Restricts the calling thread to the allowed cores outside the slowest cluster; 1 when changed. */
static int prefer_fast_cores(cpu_set_t *saved) {
    if (sched_getaffinity(0, sizeof(*saved), saved) != 0) return 0;
    long lowest = LONG_MAX;
    long freq[CPU_SETSIZE];
    for (int cpu = 0; cpu < CPU_SETSIZE; cpu++) {
        freq[cpu] = CPU_ISSET(cpu, saved) ? max_freq(cpu) : -1;
        if (freq[cpu] > 0 && freq[cpu] < lowest) lowest = freq[cpu];
    }
    cpu_set_t fast;
    CPU_ZERO(&fast);
    for (int cpu = 0; cpu < CPU_SETSIZE; cpu++) {
        if (freq[cpu] > lowest) CPU_SET(cpu, &fast);
    }
    if (CPU_COUNT(&fast) == 0) return 0;
    return sched_setaffinity(0, sizeof(fast), &fast) == 0;
}

/* memset that the compiler may not drop although the buffer is freed next. */
static void wipe(void *buffer, size_t size) {
    volatile uint8_t *p = (volatile uint8_t *)buffer;
    while (size--) *p++ = 0;
}

static void throw_exception(JNIEnv *env, const char *class_name, const char *message) {
    jclass cls = (*env)->FindClass(env, class_name);
    if (cls != NULL) (*env)->ThrowNew(env, cls, message);
}

/* Copies a Java byte array into malloc'ed memory; NULL array gives NULL with *size 0. */
static uint8_t *copy_array(JNIEnv *env, jbyteArray array, uint32_t *size, int *failed) {
    *size = 0;
    if (array == NULL) return NULL;
    jsize length = (*env)->GetArrayLength(env, array);
    /* malloc(0) may return NULL; keep one byte so NULL always means "absent". */
    uint8_t *copy = malloc(length > 0 ? (size_t)length : 1);
    if (copy == NULL) {
        *failed = 1;
        throw_exception(env, "java/lang/OutOfMemoryError", "argon2: input copy");
        return NULL;
    }
    (*env)->GetByteArrayRegion(env, array, 0, length, (jbyte *)copy);
    *size = (uint32_t)length;
    return copy;
}

static void free_wiped(uint8_t *buffer, uint32_t size) {
    if (buffer == NULL) return;
    wipe(buffer, size);
    free(buffer);
}

JNIEXPORT jbyteArray JNICALL
Java_org_lujarch_vault_core_crypto_Argon2_nativeHash(
        JNIEnv *env, jclass clazz, jint type, jint version,
        jbyteArray password, jbyteArray salt, jbyteArray secret, jbyteArray associated,
        jint iterations, jint memory_kib, jint parallelism, jint out_length) {
    (void)clazz;
    if (type != Argon2_d && type != Argon2_i && type != Argon2_id) {
        throw_exception(env, "java/lang/IllegalArgumentException", "argon2: unknown type");
        return NULL;
    }
    if (out_length < ARGON2_MIN_OUTLEN || iterations < 1 || memory_kib < 8 || parallelism < 1) {
        throw_exception(env, "java/lang/IllegalArgumentException", "argon2: parameter out of range");
        return NULL;
    }

    int failed = 0;
    uint32_t pwd_len = 0, salt_len = 0, secret_len = 0, ad_len = 0;
    uint8_t *pwd = copy_array(env, password, &pwd_len, &failed);
    uint8_t *salt_copy = failed ? NULL : copy_array(env, salt, &salt_len, &failed);
    uint8_t *secret_copy = failed ? NULL : copy_array(env, secret, &secret_len, &failed);
    uint8_t *ad_copy = failed ? NULL : copy_array(env, associated, &ad_len, &failed);
    uint8_t *out = failed ? NULL : malloc((size_t)out_length);
    jbyteArray result = NULL;

    if (!failed && out == NULL) {
        failed = 1;
        throw_exception(env, "java/lang/OutOfMemoryError", "argon2: output");
    }
    if (!failed) {
        argon2_context ctx;
        memset(&ctx, 0, sizeof(ctx));
        ctx.out = out;
        ctx.outlen = (uint32_t)out_length;
        ctx.pwd = pwd;
        ctx.pwdlen = pwd_len;
        ctx.salt = salt_copy;
        ctx.saltlen = salt_len;
        ctx.secret = secret_copy;
        ctx.secretlen = secret_len;
        ctx.ad = ad_copy;
        ctx.adlen = ad_len;
        ctx.t_cost = (uint32_t)iterations;
        ctx.m_cost = (uint32_t)memory_kib;
        ctx.lanes = (uint32_t)parallelism;
        ctx.threads = (uint32_t)parallelism;
        ctx.version = (uint32_t)version;
        ctx.flags = ARGON2_FLAG_CLEAR_PASSWORD | ARGON2_FLAG_CLEAR_SECRET;

        cpu_set_t saved;
        int pinned = prefer_fast_cores(&saved);
        int rc = argon2_ctx(&ctx, (argon2_type)type);
        if (pinned) sched_setaffinity(0, sizeof(saved), &saved);
        if (rc != ARGON2_OK) {
            throw_exception(env, "java/lang/IllegalStateException", argon2_error_message(rc));
        } else {
            result = (*env)->NewByteArray(env, out_length);
            if (result != NULL) {
                (*env)->SetByteArrayRegion(env, result, 0, out_length, (const jbyte *)out);
            }
        }
    }

    free_wiped(pwd, pwd_len);
    free_wiped(salt_copy, salt_len);
    free_wiped(secret_copy, secret_len);
    free_wiped(ad_copy, ad_len);
    if (out != NULL) free_wiped(out, (uint32_t)out_length);
    return result;
}
