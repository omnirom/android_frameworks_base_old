/*
 * Copyright 2013, The Android Open Source Project
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
#define LOG_TAG "MediaDrm-JNI"
#include <utils/Log.h>

#include "android_media_MediaDrm.h"

#include "android_runtime/AndroidRuntime.h"
#include "android_runtime/Log.h"
#include "android_os_Parcel.h"
#include "jni.h"
#include "JNIHelp.h"

#include <binder/IServiceManager.h>
#include <binder/Parcel.h>
#include <media/IDrm.h>
#include <media/IMediaPlayerService.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/MediaErrors.h>

namespace android {

#define FIND_CLASS(var, className) \
    var = env->FindClass(className); \
    LOG_FATAL_IF(! var, "Unable to find class " className);

#define GET_FIELD_ID(var, clazz, fieldName, fieldDescriptor) \
    var = env->GetFieldID(clazz, fieldName, fieldDescriptor); \
    LOG_FATAL_IF(! var, "Unable to find field " fieldName);

#define GET_METHOD_ID(var, clazz, fieldName, fieldDescriptor) \
    var = env->GetMethodID(clazz, fieldName, fieldDescriptor); \
    LOG_FATAL_IF(! var, "Unable to find method " fieldName);

#define GET_STATIC_FIELD_ID(var, clazz, fieldName, fieldDescriptor) \
    var = env->GetStaticFieldID(clazz, fieldName, fieldDescriptor); \
    LOG_FATAL_IF(! var, "Unable to find field " fieldName);

#define GET_STATIC_METHOD_ID(var, clazz, fieldName, fieldDescriptor) \
    var = env->GetStaticMethodID(clazz, fieldName, fieldDescriptor); \
    LOG_FATAL_IF(! var, "Unable to find static method " fieldName);


struct RequestFields {
    jfieldID data;
    jfieldID defaultUrl;
};

struct ArrayListFields {
    jmethodID init;
    jmethodID add;
};

struct HashmapFields {
    jmethodID init;
    jmethodID get;
    jmethodID put;
    jmethodID entrySet;
};

struct SetFields {
    jmethodID iterator;
};

struct IteratorFields {
    jmethodID next;
    jmethodID hasNext;
};

struct EntryFields {
    jmethodID getKey;
    jmethodID getValue;
};

struct EventTypes {
    jint kEventProvisionRequired;
    jint kEventKeyRequired;
    jint kEventKeyExpired;
    jint kEventVendorDefined;
} gEventTypes;

struct KeyTypes {
    jint kKeyTypeStreaming;
    jint kKeyTypeOffline;
    jint kKeyTypeRelease;
} gKeyTypes;

struct fields_t {
    jfieldID context;
    jmethodID post_event;
    RequestFields keyRequest;
    RequestFields provisionRequest;
    ArrayListFields arraylist;
    HashmapFields hashmap;
    SetFields set;
    IteratorFields iterator;
    EntryFields entry;
};

static fields_t gFields;

// ----------------------------------------------------------------------------
// ref-counted object for callbacks
class JNIDrmListener: public DrmListener
{
public:
    JNIDrmListener(JNIEnv* env, jobject thiz, jobject weak_thiz);
    ~JNIDrmListener();
    virtual void notify(DrmPlugin::EventType eventType, int extra, const Parcel *obj = NULL);
private:
    JNIDrmListener();
    jclass      mClass;     // Reference to MediaDrm class
    jobject     mObject;    // Weak ref to MediaDrm Java object to call on
};

JNIDrmListener::JNIDrmListener(JNIEnv* env, jobject thiz, jobject weak_thiz)
{
    // Hold onto the MediaDrm class for use in calling the static method
    // that posts events to the application thread.
    jclass clazz = env->GetObjectClass(thiz);
    if (clazz == NULL) {
        ALOGE("Can't find android/media/MediaDrm");
        jniThrowException(env, "java/lang/Exception",
                          "Can't find android/media/MediaDrm");
        return;
    }
    mClass = (jclass)env->NewGlobalRef(clazz);

    // We use a weak reference so the MediaDrm object can be garbage collected.
    // The reference is only used as a proxy for callbacks.
    mObject  = env->NewGlobalRef(weak_thiz);
}

JNIDrmListener::~JNIDrmListener()
{
    // remove global references
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    env->DeleteGlobalRef(mObject);
    env->DeleteGlobalRef(mClass);
}

void JNIDrmListener::notify(DrmPlugin::EventType eventType, int extra,
                            const Parcel *obj)
{
    jint jeventType;

    // translate DrmPlugin event types into their java equivalents
    switch(eventType) {
        case DrmPlugin::kDrmPluginEventProvisionRequired:
            jeventType = gEventTypes.kEventProvisionRequired;
            break;
        case DrmPlugin::kDrmPluginEventKeyNeeded:
            jeventType = gEventTypes.kEventKeyRequired;
            break;
        case DrmPlugin::kDrmPluginEventKeyExpired:
            jeventType = gEventTypes.kEventKeyExpired;
            break;
        case DrmPlugin::kDrmPluginEventVendorDefined:
            jeventType = gEventTypes.kEventVendorDefined;
            break;
        default:
            ALOGE("Invalid event DrmPlugin::EventType %d, ignored", (int)eventType);
            return;
    }

    JNIEnv *env = AndroidRuntime::getJNIEnv();
    if (obj && obj->dataSize() > 0) {
        jobject jParcel = createJavaParcelObject(env);
        if (jParcel != NULL) {
            Parcel* nativeParcel = parcelForJavaObject(env, jParcel);
            nativeParcel->setData(obj->data(), obj->dataSize());
            env->CallStaticVoidMethod(mClass, gFields.post_event, mObject,
                    jeventType, extra, jParcel);
            env->DeleteLocalRef(jParcel);
        }
    }

    if (env->ExceptionCheck()) {
        ALOGW("An exception occurred while notifying an event.");
        LOGW_EX(env);
        env->ExceptionClear();
    }
}


static bool throwExceptionAsNecessary(
        JNIEnv *env, status_t err, const char *msg = NULL) {

    const char *drmMessage = NULL;

    switch(err) {
    case ERROR_DRM_UNKNOWN:
        drmMessage = "General DRM error";
        break;
    case ERROR_DRM_NO_LICENSE:
        drmMessage = "No license";
        break;
    case ERROR_DRM_LICENSE_EXPIRED:
        drmMessage = "License expired";
        break;
    case ERROR_DRM_SESSION_NOT_OPENED:
        drmMessage = "Session not opened";
        break;
    case ERROR_DRM_DECRYPT_UNIT_NOT_INITIALIZED:
        drmMessage = "Not initialized";
        break;
    case ERROR_DRM_DECRYPT:
        drmMessage = "Decrypt error";
        break;
    case ERROR_DRM_CANNOT_HANDLE:
        drmMessage = "Unsupported scheme or data format";
        break;
    case ERROR_DRM_TAMPER_DETECTED:
        drmMessage = "Invalid state";
        break;
    default:
        break;
    }

    String8 vendorMessage;
    if (err >= ERROR_DRM_VENDOR_MIN && err <= ERROR_DRM_VENDOR_MAX) {
        vendorMessage.format("DRM vendor-defined error: %d", err);
        drmMessage = vendorMessage.string();
    }

    if (err == BAD_VALUE) {
        jniThrowException(env, "java/lang/IllegalArgumentException", msg);
        return true;
    } else if (err == ERROR_DRM_NOT_PROVISIONED) {
        jniThrowException(env, "android/media/NotProvisionedException", msg);
        return true;
    } else if (err == ERROR_DRM_RESOURCE_BUSY) {
        jniThrowException(env, "android/media/ResourceBusyException", msg);
        return true;
    } else if (err == ERROR_DRM_DEVICE_REVOKED) {
        jniThrowException(env, "android/media/DeniedByServerException", msg);
        return true;
    } else if (err != OK) {
        String8 errbuf;
        if (drmMessage != NULL) {
            if (msg == NULL) {
                msg = drmMessage;
            } else {
                errbuf.format("%s: %s", msg, drmMessage);
                msg = errbuf.string();
            }
        }
        ALOGE("Illegal state exception: %s", msg);
        jniThrowException(env, "java/lang/IllegalStateException", msg);
        return true;
    }
    return false;
}

static sp<IDrm> GetDrm(JNIEnv *env, jobject thiz) {
    JDrm *jdrm = (JDrm *)env->GetIntField(thiz, gFields.context);
    return jdrm ? jdrm->getDrm() : NULL;
}

JDrm::JDrm(
        JNIEnv *env, jobject thiz, const uint8_t uuid[16]) {
    mObject = env->NewWeakGlobalRef(thiz);
    mDrm = MakeDrm(uuid);
    if (mDrm != NULL) {
        mDrm->setListener(this);
    }
}

JDrm::~JDrm() {
    mDrm.clear();

    JNIEnv *env = AndroidRuntime::getJNIEnv();

    env->DeleteWeakGlobalRef(mObject);
    mObject = NULL;
}

// static
sp<IDrm> JDrm::MakeDrm() {
    sp<IServiceManager> sm = defaultServiceManager();

    sp<IBinder> binder =
        sm->getService(String16("media.player"));

    sp<IMediaPlayerService> service =
        interface_cast<IMediaPlayerService>(binder);

    if (service == NULL) {
        return NULL;
    }

    sp<IDrm> drm = service->makeDrm();

    if (drm == NULL || (drm->initCheck() != OK && drm->initCheck() != NO_INIT)) {
        return NULL;
    }

    return drm;
}

// static
sp<IDrm> JDrm::MakeDrm(const uint8_t uuid[16]) {
    sp<IDrm> drm = MakeDrm();

    if (drm == NULL) {
        return NULL;
    }

    status_t err = drm->createPlugin(uuid);

    if (err != OK) {
        return NULL;
    }

    return drm;
}

status_t JDrm::setListener(const sp<DrmListener>& listener) {
    Mutex::Autolock lock(mLock);
    mListener = listener;
    return OK;
}

void JDrm::notify(DrmPlugin::EventType eventType, int extra, const Parcel *obj) {
    sp<DrmListener> listener;
    mLock.lock();
    listener = mListener;
    mLock.unlock();

    if (listener != NULL) {
        Mutex::Autolock lock(mNotifyLock);
        listener->notify(eventType, extra, obj);
    }
}


// static
bool JDrm::IsCryptoSchemeSupported(const uint8_t uuid[16], const String8 &mimeType) {
    sp<IDrm> drm = MakeDrm();

    if (drm == NULL) {
        return false;
    }

    return drm->isCryptoSchemeSupported(uuid, mimeType);
}

status_t JDrm::initCheck() const {
    return mDrm == NULL ? NO_INIT : OK;
}

// JNI conversion utilities
static Vector<uint8_t> JByteArrayToVector(JNIEnv *env, jbyteArray const &byteArray) {
    Vector<uint8_t> vector;
    size_t length = env->GetArrayLength(byteArray);
    vector.insertAt((size_t)0, length);
    env->GetByteArrayRegion(byteArray, 0, length, (jbyte *)vector.editArray());
    return vector;
}

static jbyteArray VectorToJByteArray(JNIEnv *env, Vector<uint8_t> const &vector) {
    size_t length = vector.size();
    jbyteArray result = env->NewByteArray(length);
    if (result != NULL) {
        env->SetByteArrayRegion(result, 0, length, (jbyte *)vector.array());
    }
    return result;
}

static String8 JStringToString8(JNIEnv *env, jstring const &jstr) {
    String8 result;

    const char *s = env->GetStringUTFChars(jstr, NULL);
    if (s) {
        result = s;
        env->ReleaseStringUTFChars(jstr, s);
    }
    return result;
}

/*
    import java.util.HashMap;
    import java.util.Set;
    import java.Map.Entry;
    import jav.util.Iterator;

    HashMap<k, v> hm;
    Set<Entry<k, v> > s = hm.entrySet();
    Iterator i = s.iterator();
    Entry e = s.next();
*/

static KeyedVector<String8, String8> HashMapToKeyedVector(JNIEnv *env, jobject &hashMap) {
    jclass clazz;
    FIND_CLASS(clazz, "java/lang/String");
    KeyedVector<String8, String8> keyedVector;

    jobject entrySet = env->CallObjectMethod(hashMap, gFields.hashmap.entrySet);
    if (entrySet) {
        jobject iterator = env->CallObjectMethod(entrySet, gFields.set.iterator);
        if (iterator) {
            jboolean hasNext = env->CallBooleanMethod(iterator, gFields.iterator.hasNext);
            while (hasNext) {
                jobject entry = env->CallObjectMethod(iterator, gFields.iterator.next);
                if (entry) {
                    jobject obj = env->CallObjectMethod(entry, gFields.entry.getKey);
                    if (!env->IsInstanceOf(obj, clazz)) {
                        jniThrowException(env, "java/lang/IllegalArgumentException",
                                          "HashMap key is not a String");
                    }
                    jstring jkey = static_cast<jstring>(obj);

                    obj = env->CallObjectMethod(entry, gFields.entry.getValue);
                    if (!env->IsInstanceOf(obj, clazz)) {
                        jniThrowException(env, "java/lang/IllegalArgumentException",
                                          "HashMap value is not a String");
                    }
                    jstring jvalue = static_cast<jstring>(obj);

                    String8 key = JStringToString8(env, jkey);
                    String8 value = JStringToString8(env, jvalue);
                    keyedVector.add(key, value);

                    env->DeleteLocalRef(jkey);
                    env->DeleteLocalRef(jvalue);
                    hasNext = env->CallBooleanMethod(iterator, gFields.iterator.hasNext);
                }
                env->DeleteLocalRef(entry);
            }
            env->DeleteLocalRef(iterator);
        }
        env->DeleteLocalRef(entrySet);
    }
    return keyedVector;
}

static jobject KeyedVectorToHashMap (JNIEnv *env, KeyedVector<String8, String8> const &map) {
    jclass clazz;
    FIND_CLASS(clazz, "java/util/HashMap");
    jobject hashMap = env->NewObject(clazz, gFields.hashmap.init);
    for (size_t i = 0; i < map.size(); ++i) {
        jstring jkey = env->NewStringUTF(map.keyAt(i).string());
        jstring jvalue = env->NewStringUTF(map.valueAt(i).string());
        env->CallObjectMethod(hashMap, gFields.hashmap.put, jkey, jvalue);
        env->DeleteLocalRef(jkey);
        env->DeleteLocalRef(jvalue);
    }
    return hashMap;
}

static jobject ListOfVectorsToArrayListOfByteArray(JNIEnv *env,
                                                   List<Vector<uint8_t> > list) {
    jclass clazz;
    FIND_CLASS(clazz, "java/util/ArrayList");
    jobject arrayList = env->NewObject(clazz, gFields.arraylist.init);
    List<Vector<uint8_t> >::iterator iter = list.begin();
    while (iter != list.end()) {
        jbyteArray byteArray = VectorToJByteArray(env, *iter);
        env->CallBooleanMethod(arrayList, gFields.arraylist.add, byteArray);
        env->DeleteLocalRef(byteArray);
        iter++;
    }

    return arrayList;
}

}  // namespace android

using namespace android;

static sp<JDrm> setDrm(
        JNIEnv *env, jobject thiz, const sp<JDrm> &drm) {
    sp<JDrm> old = (JDrm *)env->GetIntField(thiz, gFields.context);
    if (drm != NULL) {
        drm->incStrong(thiz);
    }
    if (old != NULL) {
        old->decStrong(thiz);
    }
    env->SetIntField(thiz, gFields.context, (int)drm.get());

    return old;
}

static bool CheckSession(JNIEnv *env, const sp<IDrm> &drm, jbyteArray const &jsessionId)
{
    if (drm == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException", "MediaDrm obj is null");
        return false;
    }

    if (jsessionId == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "sessionId is null");
        return false;
    }
    return true;
}

static void android_media_MediaDrm_release(JNIEnv *env, jobject thiz) {
    sp<JDrm> drm = setDrm(env, thiz, NULL);
    if (drm != NULL) {
        drm->setListener(NULL);
    }
}

static void android_media_MediaDrm_native_init(JNIEnv *env) {
    jclass clazz;
    FIND_CLASS(clazz, "android/media/MediaDrm");
    GET_FIELD_ID(gFields.context, clazz, "mNativeContext", "I");
    GET_STATIC_METHOD_ID(gFields.post_event, clazz, "postEventFromNative",
                         "(Ljava/lang/Object;IILjava/lang/Object;)V");

    jfieldID field;
    GET_STATIC_FIELD_ID(field, clazz, "EVENT_PROVISION_REQUIRED", "I");
    gEventTypes.kEventProvisionRequired = env->GetStaticIntField(clazz, field);
    GET_STATIC_FIELD_ID(field, clazz, "EVENT_KEY_REQUIRED", "I");
    gEventTypes.kEventKeyRequired = env->GetStaticIntField(clazz, field);
    GET_STATIC_FIELD_ID(field, clazz, "EVENT_KEY_EXPIRED", "I");
    gEventTypes.kEventKeyExpired = env->GetStaticIntField(clazz, field);
    GET_STATIC_FIELD_ID(field, clazz, "EVENT_VENDOR_DEFINED", "I");
    gEventTypes.kEventVendorDefined = env->GetStaticIntField(clazz, field);

    GET_STATIC_FIELD_ID(field, clazz, "KEY_TYPE_STREAMING", "I");
    gKeyTypes.kKeyTypeStreaming = env->GetStaticIntField(clazz, field);
    GET_STATIC_FIELD_ID(field, clazz, "KEY_TYPE_OFFLINE", "I");
    gKeyTypes.kKeyTypeOffline = env->GetStaticIntField(clazz, field);
    GET_STATIC_FIELD_ID(field, clazz, "KEY_TYPE_RELEASE", "I");
    gKeyTypes.kKeyTypeRelease = env->GetStaticIntField(clazz, field);

    FIND_CLASS(clazz, "android/media/MediaDrm$KeyRequest");
    GET_FIELD_ID(gFields.keyRequest.data, clazz, "mData", "[B");
    GET_FIELD_ID(gFields.keyRequest.defaultUrl, clazz, "mDefaultUrl", "Ljava/lang/String;");

    FIND_CLASS(clazz, "android/media/MediaDrm$ProvisionRequest");
    GET_FIELD_ID(gFields.provisionRequest.data, clazz, "mData", "[B");
    GET_FIELD_ID(gFields.provisionRequest.defaultUrl, clazz, "mDefaultUrl", "Ljava/lang/String;");

    FIND_CLASS(clazz, "java/util/ArrayList");
    GET_METHOD_ID(gFields.arraylist.init, clazz, "<init>", "()V");
    GET_METHOD_ID(gFields.arraylist.add, clazz, "add", "(Ljava/lang/Object;)Z");

    FIND_CLASS(clazz, "java/util/HashMap");
    GET_METHOD_ID(gFields.hashmap.init, clazz, "<init>", "()V");
    GET_METHOD_ID(gFields.hashmap.get, clazz, "get", "(Ljava/lang/Object;)Ljava/lang/Object;");
    GET_METHOD_ID(gFields.hashmap.put, clazz, "put",
                  "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    GET_METHOD_ID(gFields.hashmap.entrySet, clazz, "entrySet", "()Ljava/util/Set;");

    FIND_CLASS(clazz, "java/util/Set");
    GET_METHOD_ID(gFields.set.iterator, clazz, "iterator", "()Ljava/util/Iterator;");

    FIND_CLASS(clazz, "java/util/Iterator");
    GET_METHOD_ID(gFields.iterator.next, clazz, "next", "()Ljava/lang/Object;");
    GET_METHOD_ID(gFields.iterator.hasNext, clazz, "hasNext", "()Z");

    FIND_CLASS(clazz, "java/util/Map$Entry");
    GET_METHOD_ID(gFields.entry.getKey, clazz, "getKey", "()Ljava/lang/Object;");
    GET_METHOD_ID(gFields.entry.getValue, clazz, "getValue", "()Ljava/lang/Object;");
}

static void android_media_MediaDrm_native_setup(
        JNIEnv *env, jobject thiz,
        jobject weak_this, jbyteArray uuidObj) {

    if (uuidObj == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "uuid is null");
        return;
    }

    Vector<uint8_t> uuid = JByteArrayToVector(env, uuidObj);

    if (uuid.size() != 16) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "invalid UUID size, expected 16 bytes");
        return;
    }

    sp<JDrm> drm = new JDrm(env, thiz, uuid.array());

    status_t err = drm->initCheck();

    if (err != OK) {
        jniThrowException(
                env,
                "android/media/UnsupportedSchemeException",
                "Failed to instantiate drm object.");
        return;
    }

    sp<JNIDrmListener> listener = new JNIDrmListener(env, thiz, weak_this);
    drm->setListener(listener);
    setDrm(env, thiz, drm);
}

static void android_media_MediaDrm_native_finalize(
        JNIEnv *env, jobject thiz) {
    android_media_MediaDrm_release(env, thiz);
}

static jboolean android_media_MediaDrm_isCryptoSchemeSupportedNative(
    JNIEnv *env, jobject thiz, jbyteArray uuidObj, jstring jmimeType) {

    if (uuidObj == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return false;
    }

    Vector<uint8_t> uuid = JByteArrayToVector(env, uuidObj);

    if (uuid.size() != 16) {
        jniThrowException(
                env,
                "java/lang/IllegalArgumentException",
                "invalid UUID size, expected 16 bytes");
        return false;
    }

    String8 mimeType;
    if (jmimeType != NULL) {
        mimeType = JStringToString8(env, jmimeType);
    }

    return JDrm::IsCryptoSchemeSupported(uuid.array(), mimeType);
}

static jbyteArray android_media_MediaDrm_openSession(
    JNIEnv *env, jobject thiz) {
    sp<IDrm> drm = GetDrm(env, thiz);

    if (drm == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
                          "MediaDrm obj is null");
        return NULL;
    }

    Vector<uint8_t> sessionId;
    status_t err = drm->openSession(sessionId);

    if (throwExceptionAsNecessary(env, err, "Failed to open session")) {
        return NULL;
    }

    return VectorToJByteArray(env, sessionId);
}

static void android_media_MediaDrm_closeSession(
    JNIEnv *env, jobject thiz, jbyteArray jsessionId) {
    sp<IDrm> drm = GetDrm(env, thiz);

    if (!CheckSession(env, drm, jsessionId)) {
        return;
    }

    Vector<uint8_t> sessionId(JByteArrayToVector(env, jsessionId));

    status_t err = drm->closeSession(sessionId);

    throwExceptionAsNecessary(env, err, "Failed to close session");
}

static jobject android_media_MediaDrm_getKeyRequest(
    JNIEnv *env, jobject thiz, jbyteArray jsessionId, jbyteArray jinitData,
    jstring jmimeType, jint jkeyType, jobject joptParams) {
    sp<IDrm> drm = GetDrm(env, thiz);

    if (!CheckSession(env, drm, jsessionId)) {
        return NULL;
    }

    Vector<uint8_t> sessionId(JByteArrayToVector(env, jsessionId));

    Vector<uint8_t> initData;
    if (jinitData != NULL) {
        initData = JByteArrayToVector(env, jinitData);
    }

    String8 mimeType;
    if (jmimeType != NULL) {
        mimeType = JStringToString8(env, jmimeType);
    }

    DrmPlugin::KeyType keyType;
    if (jkeyType == gKeyTypes.kKeyTypeStreaming) {
        keyType = DrmPlugin::kKeyType_Streaming;
    } else if (jkeyType == gKeyTypes.kKeyTypeOffline) {
        keyType = DrmPlugin::kKeyType_Offline;
    } else if (jkeyType == gKeyTypes.kKeyTypeRelease) {
        keyType = DrmPlugin::kKeyType_Release;
    } else {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "invalid keyType");
        return NULL;
    }

    KeyedVector<String8, String8> optParams;
    if (joptParams != NULL) {
        optParams = HashMapToKeyedVector(env, joptParams);
    }

    Vector<uint8_t> request;
    String8 defaultUrl;

    status_t err = drm->getKeyRequest(sessionId, initData, mimeType,
                                          keyType, optParams, request, defaultUrl);

    if (throwExceptionAsNecessary(env, err, "Failed to get key request")) {
        return NULL;
    }

    // Fill out return obj
    jclass clazz;
    FIND_CLASS(clazz, "android/media/MediaDrm$KeyRequest");

    jobject keyObj = NULL;

    if (clazz) {
        keyObj = env->AllocObject(clazz);
        jbyteArray jrequest = VectorToJByteArray(env, request);
        env->SetObjectField(keyObj, gFields.keyRequest.data, jrequest);

        jstring jdefaultUrl = env->NewStringUTF(defaultUrl.string());
        env->SetObjectField(keyObj, gFields.keyRequest.defaultUrl, jdefaultUrl);
    }

    return keyObj;
}

static jbyteArray android_media_MediaDrm_provideKeyResponse(
    JNIEnv *env, jobject thiz, jbyteArray jsessionId, jbyteArray jresponse) {
    sp<IDrm> drm = GetDrm(env, thiz);

    if (!CheckSession(env, drm, jsessionId)) {
        return NULL;
    }

    Vector<uint8_t> sessionId(JByteArrayToVector(env, jsessionId));

    if (jresponse == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "key response is null");
        return NULL;
    }
    Vector<uint8_t> response(JByteArrayToVector(env, jresponse));
    Vector<uint8_t> keySetId;

    status_t err = drm->provideKeyResponse(sessionId, response, keySetId);

    if (throwExceptionAsNecessary(env, err, "Failed to handle key response")) {
        return NULL;
    }
    return VectorToJByteArray(env, keySetId);
}

static void android_media_MediaDrm_removeKeys(
    JNIEnv *env, jobject thiz, jbyteArray jkeysetId) {
    sp<IDrm> drm = GetDrm(env, thiz);

    if (jkeysetId == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "keySetId is null");
        return;
    }

    Vector<uint8_t> keySetId(JByteArrayToVector(env, jkeysetId));

    status_t err = drm->removeKeys(keySetId);

    throwExceptionAsNecessary(env, err, "Failed to remove keys");
}

static void android_media_MediaDrm_restoreKeys(
    JNIEnv *env, jobject thiz, jbyteArray jsessionId,
    jbyteArray jkeysetId) {

    sp<IDrm> drm = GetDrm(env, thiz);

    if (!CheckSession(env, drm, jsessionId)) {
        return;
    }

    if (jkeysetId == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return;
    }

    Vector<uint8_t> sessionId(JByteArrayToVector(env, jsessionId));
    Vector<uint8_t> keySetId(JByteArrayToVector(env, jkeysetId));

    status_t err = drm->restoreKeys(sessionId, keySetId);

    throwExceptionAsNecessary(env, err, "Failed to restore keys");
}

static jobject android_media_MediaDrm_queryKeyStatus(
    JNIEnv *env, jobject thiz, jbyteArray jsessionId) {
    sp<IDrm> drm = GetDrm(env, thiz);

    if (!CheckSession(env, drm, jsessionId)) {
        return NULL;
    }
    Vector<uint8_t> sessionId(JByteArrayToVector(env, jsessionId));

    KeyedVector<String8, String8> infoMap;

    status_t err = drm->queryKeyStatus(sessionId, infoMap);

    if (throwExceptionAsNecessary(env, err, "Failed to query key status")) {
        return NULL;
    }

    return KeyedVectorToHashMap(env, infoMap);
}

static jobject android_media_MediaDrm_getProvisionRequest(
    JNIEnv *env, jobject thiz) {
    sp<IDrm> drm = GetDrm(env, thiz);

    if (drm == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
                          "MediaDrm obj is null");
        return NULL;
    }

    Vector<uint8_t> request;
    String8 defaultUrl;

    status_t err = drm->getProvisionRequest(request, defaultUrl);

    if (throwExceptionAsNecessary(env, err, "Failed to get provision request")) {
        return NULL;
    }

    // Fill out return obj
    jclass clazz;
    FIND_CLASS(clazz, "android/media/MediaDrm$ProvisionRequest");

    jobject provisionObj = NULL;

    if (clazz) {
        provisionObj = env->AllocObject(clazz);
        jbyteArray jrequest = VectorToJByteArray(env, request);
        env->SetObjectField(provisionObj, gFields.provisionRequest.data, jrequest);

        jstring jdefaultUrl = env->NewStringUTF(defaultUrl.string());
        env->SetObjectField(provisionObj, gFields.provisionRequest.defaultUrl, jdefaultUrl);
    }

    return provisionObj;
}

static void android_media_MediaDrm_provideProvisionResponse(
    JNIEnv *env, jobject thiz, jbyteArray jresponse) {
    sp<IDrm> drm = GetDrm(env, thiz);

    if (drm == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
                          "MediaDrm obj is null");
        return;
    }

    if (jresponse == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "provision response is null");
        return;
    }

    Vector<uint8_t> response(JByteArrayToVector(env, jresponse));

    status_t err = drm->provideProvisionResponse(response);

    throwExceptionAsNecessary(env, err, "Failed to handle provision response");
}

static jobject android_media_MediaDrm_getSecureStops(
    JNIEnv *env, jobject thiz) {
    sp<IDrm> drm = GetDrm(env, thiz);

    if (drm == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
                          "MediaDrm obj is null");
        return NULL;
    }

    List<Vector<uint8_t> > secureStops;

    status_t err = drm->getSecureStops(secureStops);

    if (throwExceptionAsNecessary(env, err, "Failed to get secure stops")) {
        return NULL;
    }

    return ListOfVectorsToArrayListOfByteArray(env, secureStops);
}

static void android_media_MediaDrm_releaseSecureStops(
    JNIEnv *env, jobject thiz, jbyteArray jssRelease) {
    sp<IDrm> drm = GetDrm(env, thiz);

    if (drm == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
                          "MediaDrm obj is null");
        return;
    }

    Vector<uint8_t> ssRelease(JByteArrayToVector(env, jssRelease));

    status_t err = drm->releaseSecureStops(ssRelease);

    throwExceptionAsNecessary(env, err, "Failed to release secure stops");
}

static jstring android_media_MediaDrm_getPropertyString(
    JNIEnv *env, jobject thiz, jstring jname) {
    sp<IDrm> drm = GetDrm(env, thiz);

    if (drm == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
                          "MediaDrm obj is null");
        return NULL;
    }

    if (jname == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "property name String is null");
        return NULL;
    }

    String8 name = JStringToString8(env, jname);
    String8 value;

    status_t err = drm->getPropertyString(name, value);

    if (throwExceptionAsNecessary(env, err, "Failed to get property")) {
        return NULL;
    }

    return env->NewStringUTF(value.string());
}

static jbyteArray android_media_MediaDrm_getPropertyByteArray(
    JNIEnv *env, jobject thiz, jstring jname) {
    sp<IDrm> drm = GetDrm(env, thiz);

    if (drm == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
                          "MediaDrm obj is null");
        return NULL;
    }

    if (jname == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "property name String is null");
        return NULL;
    }

    String8 name = JStringToString8(env, jname);
    Vector<uint8_t> value;

    status_t err = drm->getPropertyByteArray(name, value);

    if (throwExceptionAsNecessary(env, err, "Failed to get property")) {
        return NULL;
    }

    return VectorToJByteArray(env, value);
}

static void android_media_MediaDrm_setPropertyString(
    JNIEnv *env, jobject thiz, jstring jname, jstring jvalue) {
    sp<IDrm> drm = GetDrm(env, thiz);

    if (drm == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
                          "MediaDrm obj is null");
        return;
    }

    if (jname == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "property name String is null");
        return;
    }

    if (jvalue == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "property value String is null");
        return;
    }

    String8 name = JStringToString8(env, jname);
    String8 value = JStringToString8(env, jvalue);

    status_t err = drm->setPropertyString(name, value);

    throwExceptionAsNecessary(env, err, "Failed to set property");
}

static void android_media_MediaDrm_setPropertyByteArray(
    JNIEnv *env, jobject thiz, jstring jname, jbyteArray jvalue) {
    sp<IDrm> drm = GetDrm(env, thiz);

    if (drm == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
                          "MediaDrm obj is null");
        return;
    }

    if (jname == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "property name String is null");
        return;
    }

    if (jvalue == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "property value byte array is null");
        return;
    }

    String8 name = JStringToString8(env, jname);
    Vector<uint8_t> value = JByteArrayToVector(env, jvalue);

    status_t err = drm->setPropertyByteArray(name, value);

    throwExceptionAsNecessary(env, err, "Failed to set property");
}

static void android_media_MediaDrm_setCipherAlgorithmNative(
    JNIEnv *env, jobject thiz, jobject jdrm, jbyteArray jsessionId,
    jstring jalgorithm) {

    sp<IDrm> drm = GetDrm(env, jdrm);

    if (!CheckSession(env, drm, jsessionId)) {
        return;
    }

    if (jalgorithm == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "algorithm String is null");
        return;
    }

    Vector<uint8_t> sessionId(JByteArrayToVector(env, jsessionId));
    String8 algorithm = JStringToString8(env, jalgorithm);

    status_t err = drm->setCipherAlgorithm(sessionId, algorithm);

    throwExceptionAsNecessary(env, err, "Failed to set cipher algorithm");
}

static void android_media_MediaDrm_setMacAlgorithmNative(
    JNIEnv *env, jobject thiz, jobject jdrm, jbyteArray jsessionId,
    jstring jalgorithm) {

    sp<IDrm> drm = GetDrm(env, jdrm);

    if (!CheckSession(env, drm, jsessionId)) {
        return;
    }

    if (jalgorithm == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "algorithm String is null");
        return;
    }

    Vector<uint8_t> sessionId(JByteArrayToVector(env, jsessionId));
    String8 algorithm = JStringToString8(env, jalgorithm);

    status_t err = drm->setMacAlgorithm(sessionId, algorithm);

    throwExceptionAsNecessary(env, err, "Failed to set mac algorithm");
}


static jbyteArray android_media_MediaDrm_encryptNative(
    JNIEnv *env, jobject thiz, jobject jdrm, jbyteArray jsessionId,
    jbyteArray jkeyId, jbyteArray jinput, jbyteArray jiv) {

    sp<IDrm> drm = GetDrm(env, jdrm);

    if (!CheckSession(env, drm, jsessionId)) {
        return NULL;
    }

    if (jkeyId == NULL || jinput == NULL || jiv == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "required argument is null");
        return NULL;
    }

    Vector<uint8_t> sessionId(JByteArrayToVector(env, jsessionId));
    Vector<uint8_t> keyId(JByteArrayToVector(env, jkeyId));
    Vector<uint8_t> input(JByteArrayToVector(env, jinput));
    Vector<uint8_t> iv(JByteArrayToVector(env, jiv));
    Vector<uint8_t> output;

    status_t err = drm->encrypt(sessionId, keyId, input, iv, output);

    if (throwExceptionAsNecessary(env, err, "Failed to encrypt")) {
        return NULL;
    }

    return VectorToJByteArray(env, output);
}

static jbyteArray android_media_MediaDrm_decryptNative(
    JNIEnv *env, jobject thiz, jobject jdrm, jbyteArray jsessionId,
    jbyteArray jkeyId, jbyteArray jinput, jbyteArray jiv) {

    sp<IDrm> drm = GetDrm(env, jdrm);

    if (!CheckSession(env, drm, jsessionId)) {
        return NULL;
    }

    if (jkeyId == NULL || jinput == NULL || jiv == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "required argument is null");
        return NULL;
    }

    Vector<uint8_t> sessionId(JByteArrayToVector(env, jsessionId));
    Vector<uint8_t> keyId(JByteArrayToVector(env, jkeyId));
    Vector<uint8_t> input(JByteArrayToVector(env, jinput));
    Vector<uint8_t> iv(JByteArrayToVector(env, jiv));
    Vector<uint8_t> output;

    status_t err = drm->decrypt(sessionId, keyId, input, iv, output);
    if (throwExceptionAsNecessary(env, err, "Failed to decrypt")) {
        return NULL;
    }

    return VectorToJByteArray(env, output);
}

static jbyteArray android_media_MediaDrm_signNative(
    JNIEnv *env, jobject thiz, jobject jdrm, jbyteArray jsessionId,
    jbyteArray jkeyId, jbyteArray jmessage) {

    sp<IDrm> drm = GetDrm(env, jdrm);

    if (!CheckSession(env, drm, jsessionId)) {
        return NULL;
    }

    if (jkeyId == NULL || jmessage == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "required argument is null");
        return NULL;
    }

    Vector<uint8_t> sessionId(JByteArrayToVector(env, jsessionId));
    Vector<uint8_t> keyId(JByteArrayToVector(env, jkeyId));
    Vector<uint8_t> message(JByteArrayToVector(env, jmessage));
    Vector<uint8_t> signature;

    status_t err = drm->sign(sessionId, keyId, message, signature);

    if (throwExceptionAsNecessary(env, err, "Failed to sign")) {
        return NULL;
    }

    return VectorToJByteArray(env, signature);
}

static jboolean android_media_MediaDrm_verifyNative(
    JNIEnv *env, jobject thiz, jobject jdrm, jbyteArray jsessionId,
    jbyteArray jkeyId, jbyteArray jmessage, jbyteArray jsignature) {

    sp<IDrm> drm = GetDrm(env, jdrm);

    if (!CheckSession(env, drm, jsessionId)) {
        return false;
    }

    if (jkeyId == NULL || jmessage == NULL || jsignature == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "required argument is null");
        return false;
    }

    Vector<uint8_t> sessionId(JByteArrayToVector(env, jsessionId));
    Vector<uint8_t> keyId(JByteArrayToVector(env, jkeyId));
    Vector<uint8_t> message(JByteArrayToVector(env, jmessage));
    Vector<uint8_t> signature(JByteArrayToVector(env, jsignature));
    bool match;

    status_t err = drm->verify(sessionId, keyId, message, signature, match);

    throwExceptionAsNecessary(env, err, "Failed to verify");
    return match;
}


static JNINativeMethod gMethods[] = {
    { "release", "()V", (void *)android_media_MediaDrm_release },
    { "native_init", "()V", (void *)android_media_MediaDrm_native_init },

    { "native_setup", "(Ljava/lang/Object;[B)V",
      (void *)android_media_MediaDrm_native_setup },

    { "native_finalize", "()V",
      (void *)android_media_MediaDrm_native_finalize },

    { "isCryptoSchemeSupportedNative", "([BLjava/lang/String;)Z",
      (void *)android_media_MediaDrm_isCryptoSchemeSupportedNative },

    { "openSession", "()[B",
      (void *)android_media_MediaDrm_openSession },

    { "closeSession", "([B)V",
      (void *)android_media_MediaDrm_closeSession },

    { "getKeyRequest", "([B[BLjava/lang/String;ILjava/util/HashMap;)"
      "Landroid/media/MediaDrm$KeyRequest;",
      (void *)android_media_MediaDrm_getKeyRequest },

    { "provideKeyResponse", "([B[B)[B",
      (void *)android_media_MediaDrm_provideKeyResponse },

    { "removeKeys", "([B)V",
      (void *)android_media_MediaDrm_removeKeys },

    { "restoreKeys", "([B[B)V",
      (void *)android_media_MediaDrm_restoreKeys },

    { "queryKeyStatus", "([B)Ljava/util/HashMap;",
      (void *)android_media_MediaDrm_queryKeyStatus },

    { "getProvisionRequest", "()Landroid/media/MediaDrm$ProvisionRequest;",
      (void *)android_media_MediaDrm_getProvisionRequest },

    { "provideProvisionResponse", "([B)V",
      (void *)android_media_MediaDrm_provideProvisionResponse },

    { "getSecureStops", "()Ljava/util/List;",
      (void *)android_media_MediaDrm_getSecureStops },

    { "releaseSecureStops", "([B)V",
      (void *)android_media_MediaDrm_releaseSecureStops },

    { "getPropertyString", "(Ljava/lang/String;)Ljava/lang/String;",
      (void *)android_media_MediaDrm_getPropertyString },

    { "getPropertyByteArray", "(Ljava/lang/String;)[B",
      (void *)android_media_MediaDrm_getPropertyByteArray },

    { "setPropertyString", "(Ljava/lang/String;Ljava/lang/String;)V",
      (void *)android_media_MediaDrm_setPropertyString },

    { "setPropertyByteArray", "(Ljava/lang/String;[B)V",
      (void *)android_media_MediaDrm_setPropertyByteArray },

    { "setCipherAlgorithmNative",
      "(Landroid/media/MediaDrm;[BLjava/lang/String;)V",
      (void *)android_media_MediaDrm_setCipherAlgorithmNative },

    { "setMacAlgorithmNative",
      "(Landroid/media/MediaDrm;[BLjava/lang/String;)V",
      (void *)android_media_MediaDrm_setMacAlgorithmNative },

    { "encryptNative", "(Landroid/media/MediaDrm;[B[B[B[B)[B",
      (void *)android_media_MediaDrm_encryptNative },

    { "decryptNative", "(Landroid/media/MediaDrm;[B[B[B[B)[B",
      (void *)android_media_MediaDrm_decryptNative },

    { "signNative", "(Landroid/media/MediaDrm;[B[B[B)[B",
      (void *)android_media_MediaDrm_signNative },

    { "verifyNative", "(Landroid/media/MediaDrm;[B[B[B[B)Z",
      (void *)android_media_MediaDrm_verifyNative },
};

int register_android_media_Drm(JNIEnv *env) {
    return AndroidRuntime::registerNativeMethods(env,
                "android/media/MediaDrm", gMethods, NELEM(gMethods));
}

