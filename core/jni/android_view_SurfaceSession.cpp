/*
 * Copyright (C) 2012 The Android Open Source Project
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

#define LOG_TAG "SurfaceSession"

#include "JNIHelp.h"

#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/android_view_SurfaceSession.h>
#include <utils/Log.h>
#include <utils/RefBase.h>

#include <gui/SurfaceComposerClient.h>

namespace android {

static struct {
    jfieldID mNativeClient;
} gSurfaceSessionClassInfo;


sp<SurfaceComposerClient> android_view_SurfaceSession_getClient(
        JNIEnv* env, jobject surfaceSessionObj) {
    return reinterpret_cast<SurfaceComposerClient*>(
            env->GetIntField(surfaceSessionObj, gSurfaceSessionClassInfo.mNativeClient));
}


static jint nativeCreate(JNIEnv* env, jclass clazz) {
    SurfaceComposerClient* client = new SurfaceComposerClient();
    client->incStrong((void*)nativeCreate);
    return reinterpret_cast<jint>(client);
}

static void nativeDestroy(JNIEnv* env, jclass clazz, jint ptr) {
    SurfaceComposerClient* client = reinterpret_cast<SurfaceComposerClient*>(ptr);
    client->decStrong((void*)nativeCreate);
}

static void nativeKill(JNIEnv* env, jclass clazz, jint ptr) {
    SurfaceComposerClient* client = reinterpret_cast<SurfaceComposerClient*>(ptr);
    client->dispose();
}


static JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    { "nativeCreate", "()I",
            (void*)nativeCreate },
    { "nativeDestroy", "(I)V",
            (void*)nativeDestroy },
    { "nativeKill", "(I)V",
            (void*)nativeKill }
};

int register_android_view_SurfaceSession(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "android/view/SurfaceSession",
            gMethods, NELEM(gMethods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register native methods.");

    jclass clazz = env->FindClass("android/view/SurfaceSession");
    gSurfaceSessionClassInfo.mNativeClient = env->GetFieldID(clazz, "mNativeClient", "I");
    return 0;
}

} // namespace android
