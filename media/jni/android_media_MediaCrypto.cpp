/*
 * Copyright 2012, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

//#define LOG_NDEBUG 0
#define LOG_TAG "MediaCrypto-JNI"
#include <utils/Log.h>

#include "android_media_MediaCrypto.h"

#include "android_runtime/AndroidRuntime.h"
#include "jni.h"
#include "JNIHelp.h"

#include <binder/IServiceManager.h>
#include <media/ICrypto.h>
#include <media/IMediaPlayerService.h>
#include <media/stagefright/foundation/ADebug.h>

namespace android {

struct fields_t {
    jfieldID context;
};

static fields_t gFields;

static sp<JCrypto> getCrypto(JNIEnv *env, jobject thiz) {
    return (JCrypto *)env->GetIntField(thiz, gFields.context);
}

JCrypto::JCrypto(
        JNIEnv *env, jobject thiz,
        const uint8_t uuid[16], const void *initData, size_t initSize) {
    mObject = env->NewWeakGlobalRef(thiz);

    mCrypto = MakeCrypto(uuid, initData, initSize);
}

JCrypto::~JCrypto() {
    mCrypto.clear();

    JNIEnv *env = AndroidRuntime::getJNIEnv();

    env->DeleteWeakGlobalRef(mObject);
    mObject = NULL;
}

// static
sp<ICrypto> JCrypto::MakeCrypto() {
    sp<IServiceManager> sm = defaultServiceManager();

    sp<IBinder> binder =
        sm->getService(String16("media.player"));

    sp<IMediaPlayerService> service =
        interface_cast<IMediaPlayerService>(binder);

    if (service == NULL) {
        return NULL;
    }

    sp<ICrypto> crypto = service->makeCrypto();

    if (crypto == NULL || (crypto->initCheck() != OK && crypto->initCheck() != NO_INIT)) {
        return NULL;
    }

    return crypto;
}

// static
sp<ICrypto> JCrypto::MakeCrypto(
        const uint8_t uuid[16], const void *initData, size_t initSize) {
    sp<ICrypto> crypto = MakeCrypto();

    if (crypto == NULL) {
        return NULL;
    }

    status_t err = crypto->createPlugin(uuid, initData, initSize);

    if (err != OK) {
        return NULL;
    }

    return crypto;
}

bool JCrypto::requiresSecureDecoderComponent(const char *mime) const {
    if (mCrypto == NULL) {
        return false;
    }

    return mCrypto->requiresSecureDecoderComponent(mime);
}

// static
bool JCrypto::IsCryptoSchemeSupported(const uint8_t uuid[16]) {
    sp<ICrypto> crypto = MakeCrypto();

    if (crypto == NULL) {
        return false;
    }

    return crypto->isCryptoSchemeSupported(uuid);
}

status_t JCrypto::initCheck() const {
    return mCrypto == NULL ? NO_INIT : OK;
}

// static
sp<ICrypto> JCrypto::GetCrypto(JNIEnv *env, jobject obj) {
    jclass clazz = env->FindClass("android/media/MediaCrypto");
    CHECK(clazz != NULL);

    if (!env->IsInstanceOf(obj, clazz)) {
        return NULL;
    }

    sp<JCrypto> jcrypto = getCrypto(env, obj);

    if (jcrypto == NULL) {
        return NULL;
    }

    return jcrypto->mCrypto;
}

}  // namespace android

using namespace android;

static sp<JCrypto> setCrypto(
        JNIEnv *env, jobject thiz, const sp<JCrypto> &crypto) {
    sp<JCrypto> old = (JCrypto *)env->GetIntField(thiz, gFields.context);
    if (crypto != NULL) {
        crypto->incStrong(thiz);
    }
    if (old != NULL) {
        old->decStrong(thiz);
    }
    env->SetIntField(thiz, gFields.context, (int)crypto.get());

    return old;
}

static void android_media_MediaCrypto_release(JNIEnv *env, jobject thiz) {
    setCrypto(env, thiz, NULL);
}

static void android_media_MediaCrypto_native_init(JNIEnv *env) {
    jclass clazz = env->FindClass("android/media/MediaCrypto");
    CHECK(clazz != NULL);

    gFields.context = env->GetFieldID(clazz, "mNativeContext", "I");
    CHECK(gFields.context != NULL);
}

static void android_media_MediaCrypto_native_setup(
        JNIEnv *env, jobject thiz,
        jbyteArray uuidObj, jbyteArray initDataObj) {
    jsize uuidLength = env->GetArrayLength(uuidObj);

    if (uuidLength != 16) {
        jniThrowException(
                env,
                "java/lang/IllegalArgumentException",
                NULL);
        return;
    }

    jboolean isCopy;
    jbyte *uuid = env->GetByteArrayElements(uuidObj, &isCopy);

    jsize initDataLength = 0;
    jbyte *initData = NULL;

    if (initDataObj != NULL) {
        initDataLength = env->GetArrayLength(initDataObj);
        initData = env->GetByteArrayElements(initDataObj, &isCopy);
    }

    sp<JCrypto> crypto = new JCrypto(
            env, thiz, (const uint8_t *)uuid, initData, initDataLength);

    status_t err = crypto->initCheck();

    if (initDataObj != NULL) {
        env->ReleaseByteArrayElements(initDataObj, initData, 0);
        initData = NULL;
    }

    env->ReleaseByteArrayElements(uuidObj, uuid, 0);
    uuid = NULL;

    if (err != OK) {
        jniThrowException(
                env,
                "android/media/MediaCryptoException",
                "Failed to instantiate crypto object.");
        return;
    }

    setCrypto(env,thiz, crypto);
}

static void android_media_MediaCrypto_native_finalize(
        JNIEnv *env, jobject thiz) {
    android_media_MediaCrypto_release(env, thiz);
}

static jboolean android_media_MediaCrypto_isCryptoSchemeSupportedNative(
        JNIEnv *env, jobject thiz, jbyteArray uuidObj) {
    jsize uuidLength = env->GetArrayLength(uuidObj);

    if (uuidLength != 16) {
        jniThrowException(
                env,
                "java/lang/IllegalArgumentException",
                NULL);
        return false;
    }

    jboolean isCopy;
    jbyte *uuid = env->GetByteArrayElements(uuidObj, &isCopy);

    bool result = JCrypto::IsCryptoSchemeSupported((const uint8_t *)uuid);

    env->ReleaseByteArrayElements(uuidObj, uuid, 0);
    uuid = NULL;

    return result;
}

static jboolean android_media_MediaCrypto_requiresSecureDecoderComponent(
        JNIEnv *env, jobject thiz, jstring mimeObj) {
    if (mimeObj == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return false;
    }

    sp<JCrypto> crypto = getCrypto(env, thiz);

    if (crypto == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return false;
    }

    const char *mime = env->GetStringUTFChars(mimeObj, NULL);

    if (mime == NULL) {
        return false;
    }

    bool result = crypto->requiresSecureDecoderComponent(mime);

    env->ReleaseStringUTFChars(mimeObj, mime);
    mime = NULL;

    return result;
}

static JNINativeMethod gMethods[] = {
    { "release", "()V", (void *)android_media_MediaCrypto_release },
    { "native_init", "()V", (void *)android_media_MediaCrypto_native_init },

    { "native_setup", "([B[B)V",
      (void *)android_media_MediaCrypto_native_setup },

    { "native_finalize", "()V",
      (void *)android_media_MediaCrypto_native_finalize },

    { "isCryptoSchemeSupportedNative", "([B)Z",
      (void *)android_media_MediaCrypto_isCryptoSchemeSupportedNative },

    { "requiresSecureDecoderComponent", "(Ljava/lang/String;)Z",
      (void *)android_media_MediaCrypto_requiresSecureDecoderComponent },
};

int register_android_media_Crypto(JNIEnv *env) {
    return AndroidRuntime::registerNativeMethods(env,
                "android/media/MediaCrypto", gMethods, NELEM(gMethods));
}

