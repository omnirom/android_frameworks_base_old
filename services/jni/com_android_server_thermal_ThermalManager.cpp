/*
 * Copyright (C) 2013 Intel Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "ThermalManager-JNI"

#include "JNIHelp.h"
#include "jni.h"
#include <utils/Log.h>
#include <utils/misc.h>

#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/types.h>
#include <stdlib.h>
#include <unistd.h>

namespace android {

#define THERMAL_ZONE_PATH "/sys/class/thermal/thermal_zone"
#define COOLING_DEV_PATH  "/sys/class/thermal/cooling_device"

static int readFromFile(const char *path, char* buf, size_t size)
{
    if (!path)
        return -1;

    int fd = open(path, O_RDONLY, 0);
    if (fd < 0) {
        ALOGE("Could not open '%s'", path);
        return -1;
    }

    ssize_t count = read(fd, buf, size);
    if (count > 0) {
        while (count > 0 && buf[count-1] == '\n')
            count--;
        buf[count] = '\0';
    } else {
        buf[0] = '\0';
    }

    close(fd);
    return count;
}

static int writeToFile(const char *path, int val)
{
    const int SIZE = 20;
    int ret, fd, len;
    char value[SIZE];

    if (!path)
        return -1;

    fd = open(path, O_RDWR, 0);
    if (fd < 0) {
        ALOGE("Could not open '%s'", path);
        return -1;
    }

    len = snprintf(value, SIZE, "%d\n", val);
    ret = write(fd, value, len);

    close(fd);
    return (ret == len) ? 0 : -1;
}

static int lookup(const char *base_path, const char *name)
{
    const int SIZE = 128;
    char buf[SIZE];
    char full_path[SIZE];
    int count = 0;

    do {
        snprintf(full_path, SIZE, "%s%d/type", base_path, count);
        // Loop through all thermal_zones or cooling_devices until we
        // find a first match. We call it a match when the given
        // 'name' of the thermal_zone (or a cooling_device) matches
        // with the value of 'type' sysfs interface of a thermal_zone
        // (or cooling_device).
        if (readFromFile(full_path, buf, SIZE) < 0) {
            break;
        } else {
            if (!strcmp(name, buf))
                return count;
            count++;
        }
    } while(1);

    // lookup failed.
    return -1;
}

static int lookup_contains(const char *base_path, const char *name)
{
    const int SIZE = 128;
    char buf[SIZE];
    char full_path[SIZE];
    int count = 0;

    do {
        snprintf(full_path, SIZE, "%s%d/type", base_path, count);
        if (readFromFile(full_path, buf, SIZE) < 0) {
            break;
        } else {
            // Check if 'buf' contains 'name'
            if (strstr(buf, name) != NULL)
                return count;
            count++;
        }
    } while(1);

    // lookup failed.
    return -1;
}

static jboolean isFileExists(JNIEnv* env, jobject obj, jstring jPath)
{
    const char *path = NULL;

    path = jPath ? env->GetStringUTFChars(jPath, NULL) : NULL;
    if (!path) {
        return false;
    }

    int fd = open(path, O_RDONLY, 0);
    if (fd < 0) {
        return false;
    }
    close(fd);
    return true;
}

static jint getThermalZoneIndex(JNIEnv* env, jobject obj, jstring jType)
{
    int ret;
    const char *type = NULL;

    type = jType ? env->GetStringUTFChars(jType, NULL) : NULL;
    if (!type) {
        jniThrowNullPointerException(env, "Type");
        return -1;
    }

    ret = lookup(THERMAL_ZONE_PATH, type);
    env->ReleaseStringUTFChars(jType, type);
    return ret;
}

static jint getThermalZoneIndexContains(JNIEnv* env, jobject obj, jstring jType)
{
    int ret;
    const char *type = NULL;

    type = jType ? env->GetStringUTFChars(jType, NULL) : NULL;
    if (!type) {
        jniThrowNullPointerException(env, "Type");
        return -1;
    }

    ret = lookup_contains(THERMAL_ZONE_PATH, type);
    env->ReleaseStringUTFChars(jType, type);
    return ret;
}

static jint getCoolingDeviceIndex(JNIEnv* env, jobject obj, jstring jType)
{
    int ret;
    const char *type = NULL;

    type = jType ? env->GetStringUTFChars(jType, NULL) : NULL;
    if (!type) {
        jniThrowNullPointerException(env, "Type");
        return -1;
    }

    ret = lookup(COOLING_DEV_PATH, type);
    env->ReleaseStringUTFChars(jType, type);
    return ret;
}

static jint getCoolingDeviceIndexContains(JNIEnv* env, jobject obj, jstring jType)
{
    int ret;
    const char *type = NULL;

    type = jType ? env->GetStringUTFChars(jType, NULL) : NULL;
    if (!type) {
        jniThrowNullPointerException(env, "Type");
        return -1;
    }

    ret = lookup_contains(COOLING_DEV_PATH, type);
    env->ReleaseStringUTFChars(jType, type);
    return ret;
}

static jint writeSysfs(JNIEnv* env, jobject obj, jstring jPath, jint jVal)
{
    int ret;
    const char *path = NULL;

    path = jPath ? env->GetStringUTFChars(jPath, NULL) : NULL;
    if (!path) {
        jniThrowNullPointerException(env, "path");
        return -EINVAL;
    }

    ret = writeToFile(path, jVal);
    env->ReleaseStringUTFChars(jPath, path);
    return ret;
}

static jstring readSysfs(JNIEnv* env, jobject obj, jstring jPath)
{
    const char *path = NULL;
    const int SIZE = 128;
    char buf[SIZE];

    path = jPath ? env->GetStringUTFChars(jPath, NULL) : NULL;
    if (!path) {
        jniThrowNullPointerException(env, "path");
        return NULL;
    }

    if (readFromFile(path, buf, SIZE) > 0) {
        env->ReleaseStringUTFChars(jPath, path);
        return env->NewStringUTF(buf);
    } else {
        env->ReleaseStringUTFChars(jPath, path);
        return NULL;
    }
}

static JNINativeMethod sMethods[] = {
     /* name, signature, funcPtr */
        {"native_readSysfs", "(Ljava/lang/String;)Ljava/lang/String;", (void*)readSysfs},
        {"native_writeSysfs", "(Ljava/lang/String;I)I", (void*)writeSysfs},
        {"native_getThermalZoneIndex", "(Ljava/lang/String;)I", (void*)getThermalZoneIndex},
        {"native_getThermalZoneIndexContains", "(Ljava/lang/String;)I",
                 (void*)getThermalZoneIndexContains},
        {"native_getCoolingDeviceIndex", "(Ljava/lang/String;)I", (void*)getCoolingDeviceIndex},
        {"native_getCoolingDeviceIndexContains", "(Ljava/lang/String;)I",
                 (void*)getCoolingDeviceIndexContains},
        {"native_isFileExists", "(Ljava/lang/String;)Z", (void*)isFileExists},
};

int register_android_server_thermal_ThermalManager(JNIEnv* env)
{
    jclass clazz = env->FindClass("com/android/server/thermal/ThermalManager");
    if (clazz == NULL) {
        ALOGE("Can't find com/android/server/thermal/ThermalManager");
        return -1;
    }

    return jniRegisterNativeMethods(env, "com/android/server/thermal/ThermalManager",
            sMethods, NELEM(sMethods));
}

} /* namespace android */
