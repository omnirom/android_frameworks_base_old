/*
 * Copyright (C) 2011-2012 The Android Open Source Project
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

#define LOG_TAG "libRS_jni"

#include <stdlib.h>
#include <stdio.h>
#include <fcntl.h>
#include <unistd.h>
#include <math.h>
#include <utils/misc.h>

#include <core/SkBitmap.h>
#include <core/SkPixelRef.h>
#include <core/SkStream.h>
#include <core/SkTemplates.h>

#include <androidfw/Asset.h>
#include <androidfw/AssetManager.h>
#include <androidfw/ResourceTypes.h>

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"
#include "android_runtime/android_view_Surface.h"
#include "android_runtime/android_util_AssetManager.h"

#include <rs.h>
#include <rsEnv.h>
#include <gui/Surface.h>
#include <gui/GLConsumer.h>
#include <gui/Surface.h>
#include <android_runtime/android_graphics_SurfaceTexture.h>

//#define LOG_API ALOGE
#define LOG_API(...)

using namespace android;

class AutoJavaStringToUTF8 {
public:
    AutoJavaStringToUTF8(JNIEnv* env, jstring str) : fEnv(env), fJStr(str) {
        fCStr = env->GetStringUTFChars(str, NULL);
        fLength = env->GetStringUTFLength(str);
    }
    ~AutoJavaStringToUTF8() {
        fEnv->ReleaseStringUTFChars(fJStr, fCStr);
    }
    const char* c_str() const { return fCStr; }
    jsize length() const { return fLength; }

private:
    JNIEnv*     fEnv;
    jstring     fJStr;
    const char* fCStr;
    jsize       fLength;
};

class AutoJavaStringArrayToUTF8 {
public:
    AutoJavaStringArrayToUTF8(JNIEnv* env, jobjectArray strings, jsize stringsLength)
    : mEnv(env), mStrings(strings), mStringsLength(stringsLength) {
        mCStrings = NULL;
        mSizeArray = NULL;
        if (stringsLength > 0) {
            mCStrings = (const char **)calloc(stringsLength, sizeof(char *));
            mSizeArray = (size_t*)calloc(stringsLength, sizeof(size_t));
            for (jsize ct = 0; ct < stringsLength; ct ++) {
                jstring s = (jstring)mEnv->GetObjectArrayElement(mStrings, ct);
                mCStrings[ct] = mEnv->GetStringUTFChars(s, NULL);
                mSizeArray[ct] = mEnv->GetStringUTFLength(s);
            }
        }
    }
    ~AutoJavaStringArrayToUTF8() {
        for (jsize ct=0; ct < mStringsLength; ct++) {
            jstring s = (jstring)mEnv->GetObjectArrayElement(mStrings, ct);
            mEnv->ReleaseStringUTFChars(s, mCStrings[ct]);
        }
        free(mCStrings);
        free(mSizeArray);
    }
    const char **c_str() const { return mCStrings; }
    size_t *c_str_len() const { return mSizeArray; }
    jsize length() const { return mStringsLength; }

private:
    JNIEnv      *mEnv;
    jobjectArray mStrings;
    const char **mCStrings;
    size_t      *mSizeArray;
    jsize        mStringsLength;
};

// ---------------------------------------------------------------------------

static jfieldID gContextId = 0;
static jfieldID gNativeBitmapID = 0;
static jfieldID gTypeNativeCache = 0;

static void _nInit(JNIEnv *_env, jclass _this)
{
    gContextId             = _env->GetFieldID(_this, "mContext", "I");

    jclass bitmapClass = _env->FindClass("android/graphics/Bitmap");
    gNativeBitmapID = _env->GetFieldID(bitmapClass, "mNativeBitmap", "I");
}

// ---------------------------------------------------------------------------

static void
nContextFinish(JNIEnv *_env, jobject _this, RsContext con)
{
    LOG_API("nContextFinish, con(%p)", con);
    rsContextFinish(con);
}

static void
nAssignName(JNIEnv *_env, jobject _this, RsContext con, jint obj, jbyteArray str)
{
    LOG_API("nAssignName, con(%p), obj(%p)", con, (void *)obj);
    jint len = _env->GetArrayLength(str);
    jbyte * cptr = (jbyte *) _env->GetPrimitiveArrayCritical(str, 0);
    rsAssignName(con, (void *)obj, (const char *)cptr, len);
    _env->ReleasePrimitiveArrayCritical(str, cptr, JNI_ABORT);
}

static jstring
nGetName(JNIEnv *_env, jobject _this, RsContext con, jint obj)
{
    LOG_API("nGetName, con(%p), obj(%p)", con, (void *)obj);
    const char *name = NULL;
    rsaGetName(con, (void *)obj, &name);
    if(name == NULL || strlen(name) == 0) {
        return NULL;
    }
    return _env->NewStringUTF(name);
}

static void
nObjDestroy(JNIEnv *_env, jobject _this, RsContext con, jint obj)
{
    LOG_API("nObjDestroy, con(%p) obj(%p)", con, (void *)obj);
    rsObjDestroy(con, (void *)obj);
}

// ---------------------------------------------------------------------------

static jint
nDeviceCreate(JNIEnv *_env, jobject _this)
{
    LOG_API("nDeviceCreate");
    return (jint)rsDeviceCreate();
}

static void
nDeviceDestroy(JNIEnv *_env, jobject _this, jint dev)
{
    LOG_API("nDeviceDestroy");
    return rsDeviceDestroy((RsDevice)dev);
}

static void
nDeviceSetConfig(JNIEnv *_env, jobject _this, jint dev, jint p, jint value)
{
    LOG_API("nDeviceSetConfig  dev(%p), param(%i), value(%i)", (void *)dev, p, value);
    return rsDeviceSetConfig((RsDevice)dev, (RsDeviceParam)p, value);
}

static jint
nContextCreate(JNIEnv *_env, jobject _this, jint dev, jint ver, jint sdkVer, jint ct)
{
    LOG_API("nContextCreate");
    return (jint)rsContextCreate((RsDevice)dev, ver, sdkVer, (RsContextType)ct, 0);
}

static jint
nContextCreateGL(JNIEnv *_env, jobject _this, jint dev, jint ver, jint sdkVer,
                 int colorMin, int colorPref,
                 int alphaMin, int alphaPref,
                 int depthMin, int depthPref,
                 int stencilMin, int stencilPref,
                 int samplesMin, int samplesPref, float samplesQ,
                 int dpi)
{
    RsSurfaceConfig sc;
    sc.alphaMin = alphaMin;
    sc.alphaPref = alphaPref;
    sc.colorMin = colorMin;
    sc.colorPref = colorPref;
    sc.depthMin = depthMin;
    sc.depthPref = depthPref;
    sc.samplesMin = samplesMin;
    sc.samplesPref = samplesPref;
    sc.samplesQ = samplesQ;

    LOG_API("nContextCreateGL");
    return (jint)rsContextCreateGL((RsDevice)dev, ver, sdkVer, sc, dpi);
}

static void
nContextSetPriority(JNIEnv *_env, jobject _this, RsContext con, jint p)
{
    LOG_API("ContextSetPriority, con(%p), priority(%i)", con, p);
    rsContextSetPriority(con, p);
}



static void
nContextSetSurface(JNIEnv *_env, jobject _this, RsContext con, jint width, jint height, jobject wnd)
{
    LOG_API("nContextSetSurface, con(%p), width(%i), height(%i), surface(%p)", con, width, height, (Surface *)wnd);

    ANativeWindow * window = NULL;
    if (wnd == NULL) {

    } else {
        window = android_view_Surface_getNativeWindow(_env, wnd).get();
    }

    rsContextSetSurface(con, width, height, window);
}

static void
nContextDestroy(JNIEnv *_env, jobject _this, RsContext con)
{
    LOG_API("nContextDestroy, con(%p)", con);
    rsContextDestroy(con);
}

static void
nContextDump(JNIEnv *_env, jobject _this, RsContext con, jint bits)
{
    LOG_API("nContextDump, con(%p)  bits(%i)", (RsContext)con, bits);
    rsContextDump((RsContext)con, bits);
}

static void
nContextPause(JNIEnv *_env, jobject _this, RsContext con)
{
    LOG_API("nContextPause, con(%p)", con);
    rsContextPause(con);
}

static void
nContextResume(JNIEnv *_env, jobject _this, RsContext con)
{
    LOG_API("nContextResume, con(%p)", con);
    rsContextResume(con);
}


static jstring
nContextGetErrorMessage(JNIEnv *_env, jobject _this, RsContext con)
{
    LOG_API("nContextGetErrorMessage, con(%p)", con);
    char buf[1024];

    size_t receiveLen;
    uint32_t subID;
    int id = rsContextGetMessage(con,
                                 buf, sizeof(buf),
                                 &receiveLen, sizeof(receiveLen),
                                 &subID, sizeof(subID));
    if (!id && receiveLen) {
        ALOGV("message receive buffer too small.  %i", receiveLen);
    }
    return _env->NewStringUTF(buf);
}

static jint
nContextGetUserMessage(JNIEnv *_env, jobject _this, RsContext con, jintArray data)
{
    jint len = _env->GetArrayLength(data);
    LOG_API("nContextGetMessage, con(%p), len(%i)", con, len);
    jint *ptr = _env->GetIntArrayElements(data, NULL);
    size_t receiveLen;
    uint32_t subID;
    int id = rsContextGetMessage(con,
                                 ptr, len * 4,
                                 &receiveLen, sizeof(receiveLen),
                                 &subID, sizeof(subID));
    if (!id && receiveLen) {
        ALOGV("message receive buffer too small.  %i", receiveLen);
    }
    _env->ReleaseIntArrayElements(data, ptr, 0);
    return id;
}

static jint
nContextPeekMessage(JNIEnv *_env, jobject _this, RsContext con, jintArray auxData)
{
    LOG_API("nContextPeekMessage, con(%p)", con);
    jint *auxDataPtr = _env->GetIntArrayElements(auxData, NULL);
    size_t receiveLen;
    uint32_t subID;
    int id = rsContextPeekMessage(con, &receiveLen, sizeof(receiveLen),
                                  &subID, sizeof(subID));
    auxDataPtr[0] = (jint)subID;
    auxDataPtr[1] = (jint)receiveLen;
    _env->ReleaseIntArrayElements(auxData, auxDataPtr, 0);
    return id;
}

static void nContextInitToClient(JNIEnv *_env, jobject _this, RsContext con)
{
    LOG_API("nContextInitToClient, con(%p)", con);
    rsContextInitToClient(con);
}

static void nContextDeinitToClient(JNIEnv *_env, jobject _this, RsContext con)
{
    LOG_API("nContextDeinitToClient, con(%p)", con);
    rsContextDeinitToClient(con);
}

static void
nContextSendMessage(JNIEnv *_env, jobject _this, RsContext con, jint id, jintArray data)
{
    jint *ptr = NULL;
    jint len = 0;
    if (data) {
        len = _env->GetArrayLength(data);
        jint *ptr = _env->GetIntArrayElements(data, NULL);
    }
    LOG_API("nContextSendMessage, con(%p), id(%i), len(%i)", con, id, len);
    rsContextSendMessage(con, id, (const uint8_t *)ptr, len * sizeof(int));
    if (data) {
        _env->ReleaseIntArrayElements(data, ptr, JNI_ABORT);
    }
}



static jint
nElementCreate(JNIEnv *_env, jobject _this, RsContext con, jint type, jint kind, jboolean norm, jint size)
{
    LOG_API("nElementCreate, con(%p), type(%i), kind(%i), norm(%i), size(%i)", con, type, kind, norm, size);
    return (jint)rsElementCreate(con, (RsDataType)type, (RsDataKind)kind, norm, size);
}

static jint
nElementCreate2(JNIEnv *_env, jobject _this, RsContext con,
                jintArray _ids, jobjectArray _names, jintArray _arraySizes)
{
    int fieldCount = _env->GetArrayLength(_ids);
    LOG_API("nElementCreate2, con(%p)", con);

    jint *ids = _env->GetIntArrayElements(_ids, NULL);
    jint *arraySizes = _env->GetIntArrayElements(_arraySizes, NULL);

    AutoJavaStringArrayToUTF8 names(_env, _names, fieldCount);

    const char **nameArray = names.c_str();
    size_t *sizeArray = names.c_str_len();

    jint id = (jint)rsElementCreate2(con,
                                     (RsElement *)ids, fieldCount,
                                     nameArray, fieldCount * sizeof(size_t),  sizeArray,
                                     (const uint32_t *)arraySizes, fieldCount);

    _env->ReleaseIntArrayElements(_ids, ids, JNI_ABORT);
    _env->ReleaseIntArrayElements(_arraySizes, arraySizes, JNI_ABORT);
    return (jint)id;
}

static void
nElementGetNativeData(JNIEnv *_env, jobject _this, RsContext con, jint id, jintArray _elementData)
{
    int dataSize = _env->GetArrayLength(_elementData);
    LOG_API("nElementGetNativeData, con(%p)", con);

    // we will pack mType; mKind; mNormalized; mVectorSize; NumSubElements
    assert(dataSize == 5);

    uint32_t elementData[5];
    rsaElementGetNativeData(con, (RsElement)id, elementData, dataSize);

    for(jint i = 0; i < dataSize; i ++) {
        _env->SetIntArrayRegion(_elementData, i, 1, (const jint*)&elementData[i]);
    }
}


static void
nElementGetSubElements(JNIEnv *_env, jobject _this, RsContext con, jint id,
                       jintArray _IDs,
                       jobjectArray _names,
                       jintArray _arraySizes)
{
    int dataSize = _env->GetArrayLength(_IDs);
    LOG_API("nElementGetSubElements, con(%p)", con);

    uint32_t *ids = (uint32_t *)malloc((uint32_t)dataSize * sizeof(uint32_t));
    const char **names = (const char **)malloc((uint32_t)dataSize * sizeof(const char *));
    uint32_t *arraySizes = (uint32_t *)malloc((uint32_t)dataSize * sizeof(uint32_t));

    rsaElementGetSubElements(con, (RsElement)id, ids, names, arraySizes, (uint32_t)dataSize);

    for(jint i = 0; i < dataSize; i++) {
        _env->SetObjectArrayElement(_names, i, _env->NewStringUTF(names[i]));
        _env->SetIntArrayRegion(_IDs, i, 1, (const jint*)&ids[i]);
        _env->SetIntArrayRegion(_arraySizes, i, 1, (const jint*)&arraySizes[i]);
    }

    free(ids);
    free(names);
    free(arraySizes);
}

// -----------------------------------

static int
nTypeCreate(JNIEnv *_env, jobject _this, RsContext con, RsElement eid,
            jint dimx, jint dimy, jint dimz, jboolean mips, jboolean faces, jint yuv)
{
    LOG_API("nTypeCreate, con(%p) eid(%p), x(%i), y(%i), z(%i), mips(%i), faces(%i), yuv(%i)",
            con, eid, dimx, dimy, dimz, mips, faces, yuv);

    jint id = (jint)rsTypeCreate(con, (RsElement)eid, dimx, dimy, dimz, mips, faces, yuv);
    return (jint)id;
}

static void
nTypeGetNativeData(JNIEnv *_env, jobject _this, RsContext con, jint id, jintArray _typeData)
{
    // We are packing 6 items: mDimX; mDimY; mDimZ;
    // mDimLOD; mDimFaces; mElement; into typeData
    int elementCount = _env->GetArrayLength(_typeData);

    assert(elementCount == 6);
    LOG_API("nTypeCreate, con(%p)", con);

    uint32_t typeData[6];
    rsaTypeGetNativeData(con, (RsType)id, typeData, 6);

    for(jint i = 0; i < elementCount; i ++) {
        _env->SetIntArrayRegion(_typeData, i, 1, (const jint*)&typeData[i]);
    }
}

// -----------------------------------

static jint
nAllocationCreateTyped(JNIEnv *_env, jobject _this, RsContext con, jint type, jint mips, jint usage, jint pointer)
{
    LOG_API("nAllocationCreateTyped, con(%p), type(%p), mip(%i), usage(%i), ptr(%p)", con, (RsElement)type, mips, usage, (void *)pointer);
    return (jint) rsAllocationCreateTyped(con, (RsType)type, (RsAllocationMipmapControl)mips, (uint32_t)usage, (uint32_t)pointer);
}

static void
nAllocationSyncAll(JNIEnv *_env, jobject _this, RsContext con, jint a, jint bits)
{
    LOG_API("nAllocationSyncAll, con(%p), a(%p), bits(0x%08x)", con, (RsAllocation)a, bits);
    rsAllocationSyncAll(con, (RsAllocation)a, (RsAllocationUsageType)bits);
}

static jobject
nAllocationGetSurface(JNIEnv *_env, jobject _this, RsContext con, jint a)
{
    LOG_API("nAllocationGetSurface, con(%p), a(%p)", con, (RsAllocation)a);

    IGraphicBufferProducer *v = (IGraphicBufferProducer *)rsAllocationGetSurface(con, (RsAllocation)a);
    sp<IGraphicBufferProducer> bp = v;
    v->decStrong(NULL);

    jobject o = android_view_Surface_createFromIGraphicBufferProducer(_env, bp);
    return o;
}

static void
nAllocationSetSurface(JNIEnv *_env, jobject _this, RsContext con, RsAllocation alloc, jobject sur)
{
    LOG_API("nAllocationSetSurface, con(%p), alloc(%p), surface(%p)",
            con, alloc, (Surface *)sur);

    sp<Surface> s;
    if (sur != 0) {
        s = android_view_Surface_getSurface(_env, sur);
    }

    rsAllocationSetSurface(con, alloc, static_cast<ANativeWindow *>(s.get()));
}

static void
nAllocationIoSend(JNIEnv *_env, jobject _this, RsContext con, RsAllocation alloc)
{
    LOG_API("nAllocationIoSend, con(%p), alloc(%p)", con, alloc);
    rsAllocationIoSend(con, alloc);
}

static void
nAllocationIoReceive(JNIEnv *_env, jobject _this, RsContext con, RsAllocation alloc)
{
    LOG_API("nAllocationIoReceive, con(%p), alloc(%p)", con, alloc);
    rsAllocationIoReceive(con, alloc);
}


static void
nAllocationGenerateMipmaps(JNIEnv *_env, jobject _this, RsContext con, jint alloc)
{
    LOG_API("nAllocationGenerateMipmaps, con(%p), a(%p)", con, (RsAllocation)alloc);
    rsAllocationGenerateMipmaps(con, (RsAllocation)alloc);
}

static int
nAllocationCreateFromBitmap(JNIEnv *_env, jobject _this, RsContext con, jint type, jint mip, jobject jbitmap, jint usage)
{
    SkBitmap const * nativeBitmap =
            (SkBitmap const *)_env->GetIntField(jbitmap, gNativeBitmapID);
    const SkBitmap& bitmap(*nativeBitmap);

    bitmap.lockPixels();
    const void* ptr = bitmap.getPixels();
    jint id = (jint)rsAllocationCreateFromBitmap(con,
                                                  (RsType)type, (RsAllocationMipmapControl)mip,
                                                  ptr, bitmap.getSize(), usage);
    bitmap.unlockPixels();
    return id;
}

static int
nAllocationCreateBitmapBackedAllocation(JNIEnv *_env, jobject _this, RsContext con, jint type, jint mip, jobject jbitmap, jint usage)
{
    SkBitmap const * nativeBitmap =
            (SkBitmap const *)_env->GetIntField(jbitmap, gNativeBitmapID);
    const SkBitmap& bitmap(*nativeBitmap);

    bitmap.lockPixels();
    const void* ptr = bitmap.getPixels();
    jint id = (jint)rsAllocationCreateTyped(con,
                                            (RsType)type, (RsAllocationMipmapControl)mip,
                                            (uint32_t)usage, (size_t)ptr);
    bitmap.unlockPixels();
    return id;
}

static int
nAllocationCubeCreateFromBitmap(JNIEnv *_env, jobject _this, RsContext con, jint type, jint mip, jobject jbitmap, jint usage)
{
    SkBitmap const * nativeBitmap =
            (SkBitmap const *)_env->GetIntField(jbitmap, gNativeBitmapID);
    const SkBitmap& bitmap(*nativeBitmap);

    bitmap.lockPixels();
    const void* ptr = bitmap.getPixels();
    jint id = (jint)rsAllocationCubeCreateFromBitmap(con,
                                                      (RsType)type, (RsAllocationMipmapControl)mip,
                                                      ptr, bitmap.getSize(), usage);
    bitmap.unlockPixels();
    return id;
}

static void
nAllocationCopyFromBitmap(JNIEnv *_env, jobject _this, RsContext con, jint alloc, jobject jbitmap)
{
    SkBitmap const * nativeBitmap =
            (SkBitmap const *)_env->GetIntField(jbitmap, gNativeBitmapID);
    const SkBitmap& bitmap(*nativeBitmap);
    int w = bitmap.width();
    int h = bitmap.height();

    bitmap.lockPixels();
    const void* ptr = bitmap.getPixels();
    rsAllocation2DData(con, (RsAllocation)alloc, 0, 0,
                       0, RS_ALLOCATION_CUBEMAP_FACE_POSITIVE_X,
                       w, h, ptr, bitmap.getSize(), 0);
    bitmap.unlockPixels();
}

static void
nAllocationCopyToBitmap(JNIEnv *_env, jobject _this, RsContext con, jint alloc, jobject jbitmap)
{
    SkBitmap const * nativeBitmap =
            (SkBitmap const *)_env->GetIntField(jbitmap, gNativeBitmapID);
    const SkBitmap& bitmap(*nativeBitmap);

    bitmap.lockPixels();
    void* ptr = bitmap.getPixels();
    rsAllocationCopyToBitmap(con, (RsAllocation)alloc, ptr, bitmap.getSize());
    bitmap.unlockPixels();
    bitmap.notifyPixelsChanged();
}

static void ReleaseBitmapCallback(void *bmp)
{
    SkBitmap const * nativeBitmap = (SkBitmap const *)bmp;
    nativeBitmap->unlockPixels();
}


static void
nAllocationData1D_i(JNIEnv *_env, jobject _this, RsContext con, jint alloc, jint offset, jint lod, jint count, jintArray data, int sizeBytes)
{
    jint len = _env->GetArrayLength(data);
    LOG_API("nAllocation1DData_i, con(%p), adapter(%p), offset(%i), count(%i), len(%i), sizeBytes(%i)", con, (RsAllocation)alloc, offset, count, len, sizeBytes);
    jint *ptr = _env->GetIntArrayElements(data, NULL);
    rsAllocation1DData(con, (RsAllocation)alloc, offset, lod, count, ptr, sizeBytes);
    _env->ReleaseIntArrayElements(data, ptr, JNI_ABORT);
}

static void
nAllocationData1D_s(JNIEnv *_env, jobject _this, RsContext con, jint alloc, jint offset, jint lod, jint count, jshortArray data, int sizeBytes)
{
    jint len = _env->GetArrayLength(data);
    LOG_API("nAllocation1DData_s, con(%p), adapter(%p), offset(%i), count(%i), len(%i), sizeBytes(%i)", con, (RsAllocation)alloc, offset, count, len, sizeBytes);
    jshort *ptr = _env->GetShortArrayElements(data, NULL);
    rsAllocation1DData(con, (RsAllocation)alloc, offset, lod, count, ptr, sizeBytes);
    _env->ReleaseShortArrayElements(data, ptr, JNI_ABORT);
}

static void
nAllocationData1D_b(JNIEnv *_env, jobject _this, RsContext con, jint alloc, jint offset, jint lod, jint count, jbyteArray data, int sizeBytes)
{
    jint len = _env->GetArrayLength(data);
    LOG_API("nAllocation1DData_b, con(%p), adapter(%p), offset(%i), count(%i), len(%i), sizeBytes(%i)", con, (RsAllocation)alloc, offset, count, len, sizeBytes);
    jbyte *ptr = _env->GetByteArrayElements(data, NULL);
    rsAllocation1DData(con, (RsAllocation)alloc, offset, lod, count, ptr, sizeBytes);
    _env->ReleaseByteArrayElements(data, ptr, JNI_ABORT);
}

static void
nAllocationData1D_f(JNIEnv *_env, jobject _this, RsContext con, jint alloc, jint offset, jint lod, jint count, jfloatArray data, int sizeBytes)
{
    jint len = _env->GetArrayLength(data);
    LOG_API("nAllocation1DData_f, con(%p), adapter(%p), offset(%i), count(%i), len(%i), sizeBytes(%i)", con, (RsAllocation)alloc, offset, count, len, sizeBytes);
    jfloat *ptr = _env->GetFloatArrayElements(data, NULL);
    rsAllocation1DData(con, (RsAllocation)alloc, offset, lod, count, ptr, sizeBytes);
    _env->ReleaseFloatArrayElements(data, ptr, JNI_ABORT);
}

static void
//    native void rsnAllocationElementData1D(int con, int id, int xoff, int compIdx, byte[] d, int sizeBytes);
nAllocationElementData1D(JNIEnv *_env, jobject _this, RsContext con, jint alloc, jint offset, jint lod, jint compIdx, jbyteArray data, int sizeBytes)
{
    jint len = _env->GetArrayLength(data);
    LOG_API("nAllocationElementData1D, con(%p), alloc(%p), offset(%i), comp(%i), len(%i), sizeBytes(%i)", con, (RsAllocation)alloc, offset, compIdx, len, sizeBytes);
    jbyte *ptr = _env->GetByteArrayElements(data, NULL);
    rsAllocation1DElementData(con, (RsAllocation)alloc, offset, lod, ptr, sizeBytes, compIdx);
    _env->ReleaseByteArrayElements(data, ptr, JNI_ABORT);
}

static void
nAllocationData2D_s(JNIEnv *_env, jobject _this, RsContext con, jint alloc, jint xoff, jint yoff, jint lod, jint face,
                    jint w, jint h, jshortArray data, int sizeBytes)
{
    jint len = _env->GetArrayLength(data);
    LOG_API("nAllocation2DData_s, con(%p), adapter(%p), xoff(%i), yoff(%i), w(%i), h(%i), len(%i)", con, (RsAllocation)alloc, xoff, yoff, w, h, len);
    jshort *ptr = _env->GetShortArrayElements(data, NULL);
    rsAllocation2DData(con, (RsAllocation)alloc, xoff, yoff, lod, (RsAllocationCubemapFace)face, w, h, ptr, sizeBytes, 0);
    _env->ReleaseShortArrayElements(data, ptr, JNI_ABORT);
}

static void
nAllocationData2D_b(JNIEnv *_env, jobject _this, RsContext con, jint alloc, jint xoff, jint yoff, jint lod, jint face,
                    jint w, jint h, jbyteArray data, int sizeBytes)
{
    jint len = _env->GetArrayLength(data);
    LOG_API("nAllocation2DData_b, con(%p), adapter(%p), xoff(%i), yoff(%i), w(%i), h(%i), len(%i)", con, (RsAllocation)alloc, xoff, yoff, w, h, len);
    jbyte *ptr = _env->GetByteArrayElements(data, NULL);
    rsAllocation2DData(con, (RsAllocation)alloc, xoff, yoff, lod, (RsAllocationCubemapFace)face, w, h, ptr, sizeBytes, 0);
    _env->ReleaseByteArrayElements(data, ptr, JNI_ABORT);
}

static void
nAllocationData2D_i(JNIEnv *_env, jobject _this, RsContext con, jint alloc, jint xoff, jint yoff, jint lod, jint face,
                    jint w, jint h, jintArray data, int sizeBytes)
{
    jint len = _env->GetArrayLength(data);
    LOG_API("nAllocation2DData_i, con(%p), adapter(%p), xoff(%i), yoff(%i), w(%i), h(%i), len(%i)", con, (RsAllocation)alloc, xoff, yoff, w, h, len);
    jint *ptr = _env->GetIntArrayElements(data, NULL);
    rsAllocation2DData(con, (RsAllocation)alloc, xoff, yoff, lod, (RsAllocationCubemapFace)face, w, h, ptr, sizeBytes, 0);
    _env->ReleaseIntArrayElements(data, ptr, JNI_ABORT);
}

static void
nAllocationData2D_f(JNIEnv *_env, jobject _this, RsContext con, jint alloc, jint xoff, jint yoff, jint lod, jint face,
                    jint w, jint h, jfloatArray data, int sizeBytes)
{
    jint len = _env->GetArrayLength(data);
    LOG_API("nAllocation2DData_i, con(%p), adapter(%p), xoff(%i), yoff(%i), w(%i), h(%i), len(%i)", con, (RsAllocation)alloc, xoff, yoff, w, h, len);
    jfloat *ptr = _env->GetFloatArrayElements(data, NULL);
    rsAllocation2DData(con, (RsAllocation)alloc, xoff, yoff, lod, (RsAllocationCubemapFace)face, w, h, ptr, sizeBytes, 0);
    _env->ReleaseFloatArrayElements(data, ptr, JNI_ABORT);
}

static void
nAllocationData2D_alloc(JNIEnv *_env, jobject _this, RsContext con,
                        jint dstAlloc, jint dstXoff, jint dstYoff,
                        jint dstMip, jint dstFace,
                        jint width, jint height,
                        jint srcAlloc, jint srcXoff, jint srcYoff,
                        jint srcMip, jint srcFace)
{
    LOG_API("nAllocation2DData_s, con(%p), dstAlloc(%p), dstXoff(%i), dstYoff(%i),"
            " dstMip(%i), dstFace(%i), width(%i), height(%i),"
            " srcAlloc(%p), srcXoff(%i), srcYoff(%i), srcMip(%i), srcFace(%i)",
            con, (RsAllocation)dstAlloc, dstXoff, dstYoff, dstMip, dstFace,
            width, height, (RsAllocation)srcAlloc, srcXoff, srcYoff, srcMip, srcFace);

    rsAllocationCopy2DRange(con,
                            (RsAllocation)dstAlloc,
                            dstXoff, dstYoff,
                            dstMip, dstFace,
                            width, height,
                            (RsAllocation)srcAlloc,
                            srcXoff, srcYoff,
                            srcMip, srcFace);
}

static void
nAllocationData3D_s(JNIEnv *_env, jobject _this, RsContext con, jint alloc, jint xoff, jint yoff, jint zoff, jint lod,
                    jint w, jint h, jint d, jshortArray data, int sizeBytes)
{
    jint len = _env->GetArrayLength(data);
    LOG_API("nAllocation3DData_s, con(%p), adapter(%p), xoff(%i), yoff(%i), w(%i), h(%i), len(%i)", con, (RsAllocation)alloc, xoff, yoff, zoff, w, h, d, len);
    jshort *ptr = _env->GetShortArrayElements(data, NULL);
    rsAllocation3DData(con, (RsAllocation)alloc, xoff, yoff, zoff, lod, w, h, d, ptr, sizeBytes, 0);
    _env->ReleaseShortArrayElements(data, ptr, JNI_ABORT);
}

static void
nAllocationData3D_b(JNIEnv *_env, jobject _this, RsContext con, jint alloc, jint xoff, jint yoff, jint zoff, jint lod,
                    jint w, jint h, jint d, jbyteArray data, int sizeBytes)
{
    jint len = _env->GetArrayLength(data);
    LOG_API("nAllocation3DData_b, con(%p), adapter(%p), xoff(%i), yoff(%i), w(%i), h(%i), len(%i)", con, (RsAllocation)alloc, xoff, yoff, zoff, w, h, d, len);
    jbyte *ptr = _env->GetByteArrayElements(data, NULL);
    rsAllocation3DData(con, (RsAllocation)alloc, xoff, yoff, zoff, lod, w, h, d, ptr, sizeBytes, 0);
    _env->ReleaseByteArrayElements(data, ptr, JNI_ABORT);
}

static void
nAllocationData3D_i(JNIEnv *_env, jobject _this, RsContext con, jint alloc, jint xoff, jint yoff, jint zoff, jint lod,
                    jint w, jint h, jint d, jintArray data, int sizeBytes)
{
    jint len = _env->GetArrayLength(data);
    LOG_API("nAllocation3DData_i, con(%p), adapter(%p), xoff(%i), yoff(%i), w(%i), h(%i), len(%i)", con, (RsAllocation)alloc, xoff, yoff, zoff, w, h, d, len);
    jint *ptr = _env->GetIntArrayElements(data, NULL);
    rsAllocation3DData(con, (RsAllocation)alloc, xoff, yoff, zoff, lod, w, h, d, ptr, sizeBytes, 0);
    _env->ReleaseIntArrayElements(data, ptr, JNI_ABORT);
}

static void
nAllocationData3D_f(JNIEnv *_env, jobject _this, RsContext con, jint alloc, jint xoff, jint yoff, jint zoff, jint lod,
                    jint w, jint h, jint d, jfloatArray data, int sizeBytes)
{
    jint len = _env->GetArrayLength(data);
    LOG_API("nAllocation3DData_f, con(%p), adapter(%p), xoff(%i), yoff(%i), w(%i), h(%i), len(%i)", con, (RsAllocation)alloc, xoff, yoff, zoff, w, h, d, len);
    jfloat *ptr = _env->GetFloatArrayElements(data, NULL);
    rsAllocation3DData(con, (RsAllocation)alloc, xoff, yoff, zoff, lod, w, h, d, ptr, sizeBytes, 0);
    _env->ReleaseFloatArrayElements(data, ptr, JNI_ABORT);
}

static void
nAllocationData3D_alloc(JNIEnv *_env, jobject _this, RsContext con,
                        jint dstAlloc, jint dstXoff, jint dstYoff, jint dstZoff,
                        jint dstMip,
                        jint width, jint height, jint depth,
                        jint srcAlloc, jint srcXoff, jint srcYoff, jint srcZoff,
                        jint srcMip)
{
    LOG_API("nAllocationData3D_alloc, con(%p), dstAlloc(%p), dstXoff(%i), dstYoff(%i),"
            " dstMip(%i), width(%i), height(%i),"
            " srcAlloc(%p), srcXoff(%i), srcYoff(%i), srcMip(%i)",
            con, (RsAllocation)dstAlloc, dstXoff, dstYoff, dstMip, dstFace,
            width, height, (RsAllocation)srcAlloc, srcXoff, srcYoff, srcMip, srcFace);

    rsAllocationCopy3DRange(con,
                            (RsAllocation)dstAlloc,
                            dstXoff, dstYoff, dstZoff, dstMip,
                            width, height, depth,
                            (RsAllocation)srcAlloc,
                            srcXoff, srcYoff, srcZoff, srcMip);
}

static void
nAllocationRead_i(JNIEnv *_env, jobject _this, RsContext con, jint alloc, jintArray data)
{
    jint len = _env->GetArrayLength(data);
    LOG_API("nAllocationRead_i, con(%p), alloc(%p), len(%i)", con, (RsAllocation)alloc, len);
    jint *ptr = _env->GetIntArrayElements(data, NULL);
    jsize length = _env->GetArrayLength(data);
    rsAllocationRead(con, (RsAllocation)alloc, ptr, length * sizeof(int));
    _env->ReleaseIntArrayElements(data, ptr, 0);
}

static void
nAllocationRead_s(JNIEnv *_env, jobject _this, RsContext con, jint alloc, jshortArray data)
{
    jint len = _env->GetArrayLength(data);
    LOG_API("nAllocationRead_i, con(%p), alloc(%p), len(%i)", con, (RsAllocation)alloc, len);
    jshort *ptr = _env->GetShortArrayElements(data, NULL);
    jsize length = _env->GetArrayLength(data);
    rsAllocationRead(con, (RsAllocation)alloc, ptr, length * sizeof(short));
    _env->ReleaseShortArrayElements(data, ptr, 0);
}

static void
nAllocationRead_b(JNIEnv *_env, jobject _this, RsContext con, jint alloc, jbyteArray data)
{
    jint len = _env->GetArrayLength(data);
    LOG_API("nAllocationRead_i, con(%p), alloc(%p), len(%i)", con, (RsAllocation)alloc, len);
    jbyte *ptr = _env->GetByteArrayElements(data, NULL);
    jsize length = _env->GetArrayLength(data);
    rsAllocationRead(con, (RsAllocation)alloc, ptr, length * sizeof(char));
    _env->ReleaseByteArrayElements(data, ptr, 0);
}

static void
nAllocationRead_f(JNIEnv *_env, jobject _this, RsContext con, jint alloc, jfloatArray data)
{
    jint len = _env->GetArrayLength(data);
    LOG_API("nAllocationRead_f, con(%p), alloc(%p), len(%i)", con, (RsAllocation)alloc, len);
    jfloat *ptr = _env->GetFloatArrayElements(data, NULL);
    jsize length = _env->GetArrayLength(data);
    rsAllocationRead(con, (RsAllocation)alloc, ptr, length * sizeof(float));
    _env->ReleaseFloatArrayElements(data, ptr, 0);
}

static jint
nAllocationGetType(JNIEnv *_env, jobject _this, RsContext con, jint a)
{
    LOG_API("nAllocationGetType, con(%p), a(%p)", con, (RsAllocation)a);
    return (jint) rsaAllocationGetType(con, (RsAllocation)a);
}

static void
nAllocationResize1D(JNIEnv *_env, jobject _this, RsContext con, jint alloc, jint dimX)
{
    LOG_API("nAllocationResize1D, con(%p), alloc(%p), sizeX(%i)", con, (RsAllocation)alloc, dimX);
    rsAllocationResize1D(con, (RsAllocation)alloc, dimX);
}

// -----------------------------------

static int
nFileA3DCreateFromAssetStream(JNIEnv *_env, jobject _this, RsContext con, jint native_asset)
{
    ALOGV("______nFileA3D %u", (uint32_t) native_asset);

    Asset* asset = reinterpret_cast<Asset*>(native_asset);

    jint id = (jint)rsaFileA3DCreateFromMemory(con, asset->getBuffer(false), asset->getLength());
    return id;
}

static int
nFileA3DCreateFromAsset(JNIEnv *_env, jobject _this, RsContext con, jobject _assetMgr, jstring _path)
{
    AssetManager* mgr = assetManagerForJavaObject(_env, _assetMgr);
    if (mgr == NULL) {
        return 0;
    }

    AutoJavaStringToUTF8 str(_env, _path);
    Asset* asset = mgr->open(str.c_str(), Asset::ACCESS_BUFFER);
    if (asset == NULL) {
        return 0;
    }

    jint id = (jint)rsaFileA3DCreateFromAsset(con, asset);
    return id;
}

static int
nFileA3DCreateFromFile(JNIEnv *_env, jobject _this, RsContext con, jstring fileName)
{
    AutoJavaStringToUTF8 fileNameUTF(_env, fileName);
    jint id = (jint)rsaFileA3DCreateFromFile(con, fileNameUTF.c_str());

    return id;
}

static int
nFileA3DGetNumIndexEntries(JNIEnv *_env, jobject _this, RsContext con, jint fileA3D)
{
    int32_t numEntries = 0;
    rsaFileA3DGetNumIndexEntries(con, &numEntries, (RsFile)fileA3D);
    return numEntries;
}

static void
nFileA3DGetIndexEntries(JNIEnv *_env, jobject _this, RsContext con, jint fileA3D, jint numEntries, jintArray _ids, jobjectArray _entries)
{
    ALOGV("______nFileA3D %u", (uint32_t) fileA3D);
    RsFileIndexEntry *fileEntries = (RsFileIndexEntry*)malloc((uint32_t)numEntries * sizeof(RsFileIndexEntry));

    rsaFileA3DGetIndexEntries(con, fileEntries, (uint32_t)numEntries, (RsFile)fileA3D);

    for(jint i = 0; i < numEntries; i ++) {
        _env->SetObjectArrayElement(_entries, i, _env->NewStringUTF(fileEntries[i].objectName));
        _env->SetIntArrayRegion(_ids, i, 1, (const jint*)&fileEntries[i].classID);
    }

    free(fileEntries);
}

static int
nFileA3DGetEntryByIndex(JNIEnv *_env, jobject _this, RsContext con, jint fileA3D, jint index)
{
    ALOGV("______nFileA3D %u", (uint32_t) fileA3D);
    jint id = (jint)rsaFileA3DGetEntryByIndex(con, (uint32_t)index, (RsFile)fileA3D);
    return id;
}

// -----------------------------------

static int
nFontCreateFromFile(JNIEnv *_env, jobject _this, RsContext con,
                    jstring fileName, jfloat fontSize, jint dpi)
{
    AutoJavaStringToUTF8 fileNameUTF(_env, fileName);
    jint id = (jint)rsFontCreateFromFile(con,
                                         fileNameUTF.c_str(), fileNameUTF.length(),
                                         fontSize, dpi);

    return id;
}

static int
nFontCreateFromAssetStream(JNIEnv *_env, jobject _this, RsContext con,
                           jstring name, jfloat fontSize, jint dpi, jint native_asset)
{
    Asset* asset = reinterpret_cast<Asset*>(native_asset);
    AutoJavaStringToUTF8 nameUTF(_env, name);

    jint id = (jint)rsFontCreateFromMemory(con,
                                           nameUTF.c_str(), nameUTF.length(),
                                           fontSize, dpi,
                                           asset->getBuffer(false), asset->getLength());
    return id;
}

static int
nFontCreateFromAsset(JNIEnv *_env, jobject _this, RsContext con, jobject _assetMgr, jstring _path,
                     jfloat fontSize, jint dpi)
{
    AssetManager* mgr = assetManagerForJavaObject(_env, _assetMgr);
    if (mgr == NULL) {
        return 0;
    }

    AutoJavaStringToUTF8 str(_env, _path);
    Asset* asset = mgr->open(str.c_str(), Asset::ACCESS_BUFFER);
    if (asset == NULL) {
        return 0;
    }

    jint id = (jint)rsFontCreateFromMemory(con,
                                           str.c_str(), str.length(),
                                           fontSize, dpi,
                                           asset->getBuffer(false), asset->getLength());
    delete asset;
    return id;
}

// -----------------------------------

static void
nScriptBindAllocation(JNIEnv *_env, jobject _this, RsContext con, jint script, jint alloc, jint slot)
{
    LOG_API("nScriptBindAllocation, con(%p), script(%p), alloc(%p), slot(%i)", con, (RsScript)script, (RsAllocation)alloc, slot);
    rsScriptBindAllocation(con, (RsScript)script, (RsAllocation)alloc, slot);
}

static void
nScriptSetVarI(JNIEnv *_env, jobject _this, RsContext con, jint script, jint slot, jint val)
{
    LOG_API("nScriptSetVarI, con(%p), s(%p), slot(%i), val(%i)", con, (void *)script, slot, val);
    rsScriptSetVarI(con, (RsScript)script, slot, val);
}

static jint
nScriptGetVarI(JNIEnv *_env, jobject _this, RsContext con, jint script, jint slot)
{
    LOG_API("nScriptGetVarI, con(%p), s(%p), slot(%i)", con, (void *)script, slot);
    int value = 0;
    rsScriptGetVarV(con, (RsScript)script, slot, &value, sizeof(value));
    return value;
}

static void
nScriptSetVarObj(JNIEnv *_env, jobject _this, RsContext con, jint script, jint slot, jint val)
{
    LOG_API("nScriptSetVarObj, con(%p), s(%p), slot(%i), val(%i)", con, (void *)script, slot, val);
    rsScriptSetVarObj(con, (RsScript)script, slot, (RsObjectBase)val);
}

static void
nScriptSetVarJ(JNIEnv *_env, jobject _this, RsContext con, jint script, jint slot, jlong val)
{
    LOG_API("nScriptSetVarJ, con(%p), s(%p), slot(%i), val(%lli)", con, (void *)script, slot, val);
    rsScriptSetVarJ(con, (RsScript)script, slot, val);
}

static jlong
nScriptGetVarJ(JNIEnv *_env, jobject _this, RsContext con, jint script, jint slot)
{
    LOG_API("nScriptGetVarJ, con(%p), s(%p), slot(%i)", con, (void *)script, slot);
    jlong value = 0;
    rsScriptGetVarV(con, (RsScript)script, slot, &value, sizeof(value));
    return value;
}

static void
nScriptSetVarF(JNIEnv *_env, jobject _this, RsContext con, jint script, jint slot, float val)
{
    LOG_API("nScriptSetVarF, con(%p), s(%p), slot(%i), val(%f)", con, (void *)script, slot, val);
    rsScriptSetVarF(con, (RsScript)script, slot, val);
}

static jfloat
nScriptGetVarF(JNIEnv *_env, jobject _this, RsContext con, jint script, jint slot)
{
    LOG_API("nScriptGetVarF, con(%p), s(%p), slot(%i)", con, (void *)script, slot);
    jfloat value = 0;
    rsScriptGetVarV(con, (RsScript)script, slot, &value, sizeof(value));
    return value;
}

static void
nScriptSetVarD(JNIEnv *_env, jobject _this, RsContext con, jint script, jint slot, double val)
{
    LOG_API("nScriptSetVarD, con(%p), s(%p), slot(%i), val(%lf)", con, (void *)script, slot, val);
    rsScriptSetVarD(con, (RsScript)script, slot, val);
}

static jdouble
nScriptGetVarD(JNIEnv *_env, jobject _this, RsContext con, jint script, jint slot)
{
    LOG_API("nScriptGetVarD, con(%p), s(%p), slot(%i)", con, (void *)script, slot);
    jdouble value = 0;
    rsScriptGetVarV(con, (RsScript)script, slot, &value, sizeof(value));
    return value;
}

static void
nScriptSetVarV(JNIEnv *_env, jobject _this, RsContext con, jint script, jint slot, jbyteArray data)
{
    LOG_API("nScriptSetVarV, con(%p), s(%p), slot(%i)", con, (void *)script, slot);
    jint len = _env->GetArrayLength(data);
    jbyte *ptr = _env->GetByteArrayElements(data, NULL);
    rsScriptSetVarV(con, (RsScript)script, slot, ptr, len);
    _env->ReleaseByteArrayElements(data, ptr, JNI_ABORT);
}

static void
nScriptGetVarV(JNIEnv *_env, jobject _this, RsContext con, jint script, jint slot, jbyteArray data)
{
    LOG_API("nScriptSetVarV, con(%p), s(%p), slot(%i)", con, (void *)script, slot);
    jint len = _env->GetArrayLength(data);
    jbyte *ptr = _env->GetByteArrayElements(data, NULL);
    rsScriptGetVarV(con, (RsScript)script, slot, ptr, len);
    _env->ReleaseByteArrayElements(data, ptr, JNI_ABORT);
}

static void
nScriptSetVarVE(JNIEnv *_env, jobject _this, RsContext con, jint script, jint slot, jbyteArray data, jint elem, jintArray dims)
{
    LOG_API("nScriptSetVarVE, con(%p), s(%p), slot(%i)", con, (void *)script, slot);
    jint len = _env->GetArrayLength(data);
    jbyte *ptr = _env->GetByteArrayElements(data, NULL);
    jint dimsLen = _env->GetArrayLength(dims) * sizeof(int);
    jint *dimsPtr = _env->GetIntArrayElements(dims, NULL);
    rsScriptSetVarVE(con, (RsScript)script, slot, ptr, len, (RsElement)elem,
                     (const size_t*) dimsPtr, dimsLen);
    _env->ReleaseByteArrayElements(data, ptr, JNI_ABORT);
    _env->ReleaseIntArrayElements(dims, dimsPtr, JNI_ABORT);
}


static void
nScriptSetTimeZone(JNIEnv *_env, jobject _this, RsContext con, jint script, jbyteArray timeZone)
{
    LOG_API("nScriptCSetTimeZone, con(%p), s(%p), timeZone(%s)", con, (void *)script, (const char *)timeZone);

    jint length = _env->GetArrayLength(timeZone);
    jbyte* timeZone_ptr;
    timeZone_ptr = (jbyte *) _env->GetPrimitiveArrayCritical(timeZone, (jboolean *)0);

    rsScriptSetTimeZone(con, (RsScript)script, (const char *)timeZone_ptr, length);

    if (timeZone_ptr) {
        _env->ReleasePrimitiveArrayCritical(timeZone, timeZone_ptr, 0);
    }
}

static void
nScriptInvoke(JNIEnv *_env, jobject _this, RsContext con, jint obj, jint slot)
{
    LOG_API("nScriptInvoke, con(%p), script(%p)", con, (void *)obj);
    rsScriptInvoke(con, (RsScript)obj, slot);
}

static void
nScriptInvokeV(JNIEnv *_env, jobject _this, RsContext con, jint script, jint slot, jbyteArray data)
{
    LOG_API("nScriptInvokeV, con(%p), s(%p), slot(%i)", con, (void *)script, slot);
    jint len = _env->GetArrayLength(data);
    jbyte *ptr = _env->GetByteArrayElements(data, NULL);
    rsScriptInvokeV(con, (RsScript)script, slot, ptr, len);
    _env->ReleaseByteArrayElements(data, ptr, JNI_ABORT);
}

static void
nScriptForEach(JNIEnv *_env, jobject _this, RsContext con,
               jint script, jint slot, jint ain, jint aout)
{
    LOG_API("nScriptForEach, con(%p), s(%p), slot(%i)", con, (void *)script, slot);
    rsScriptForEach(con, (RsScript)script, slot, (RsAllocation)ain, (RsAllocation)aout, NULL, 0, NULL, 0);
}
static void
nScriptForEachV(JNIEnv *_env, jobject _this, RsContext con,
                jint script, jint slot, jint ain, jint aout, jbyteArray params)
{
    LOG_API("nScriptForEach, con(%p), s(%p), slot(%i)", con, (void *)script, slot);
    jint len = _env->GetArrayLength(params);
    jbyte *ptr = _env->GetByteArrayElements(params, NULL);
    rsScriptForEach(con, (RsScript)script, slot, (RsAllocation)ain, (RsAllocation)aout, ptr, len, NULL, 0);
    _env->ReleaseByteArrayElements(params, ptr, JNI_ABORT);
}

static void
nScriptForEachClipped(JNIEnv *_env, jobject _this, RsContext con,
                      jint script, jint slot, jint ain, jint aout,
                      jint xstart, jint xend,
                      jint ystart, jint yend, jint zstart, jint zend)
{
    LOG_API("nScriptForEachClipped, con(%p), s(%p), slot(%i)", con, (void *)script, slot);
    RsScriptCall sc;
    sc.xStart = xstart;
    sc.xEnd = xend;
    sc.yStart = ystart;
    sc.yEnd = yend;
    sc.zStart = zstart;
    sc.zEnd = zend;
    sc.strategy = RS_FOR_EACH_STRATEGY_DONT_CARE;
    sc.arrayStart = 0;
    sc.arrayEnd = 0;
    rsScriptForEach(con, (RsScript)script, slot, (RsAllocation)ain, (RsAllocation)aout, NULL, 0, &sc, sizeof(sc));
}

static void
nScriptForEachClippedV(JNIEnv *_env, jobject _this, RsContext con,
                       jint script, jint slot, jint ain, jint aout,
                       jbyteArray params, jint xstart, jint xend,
                       jint ystart, jint yend, jint zstart, jint zend)
{
    LOG_API("nScriptForEachClipped, con(%p), s(%p), slot(%i)", con, (void *)script, slot);
    jint len = _env->GetArrayLength(params);
    jbyte *ptr = _env->GetByteArrayElements(params, NULL);
    RsScriptCall sc;
    sc.xStart = xstart;
    sc.xEnd = xend;
    sc.yStart = ystart;
    sc.yEnd = yend;
    sc.zStart = zstart;
    sc.zEnd = zend;
    sc.strategy = RS_FOR_EACH_STRATEGY_DONT_CARE;
    sc.arrayStart = 0;
    sc.arrayEnd = 0;
    rsScriptForEach(con, (RsScript)script, slot, (RsAllocation)ain, (RsAllocation)aout, ptr, len, &sc, sizeof(sc));
    _env->ReleaseByteArrayElements(params, ptr, JNI_ABORT);
}

// -----------------------------------

static jint
nScriptCCreate(JNIEnv *_env, jobject _this, RsContext con,
               jstring resName, jstring cacheDir,
               jbyteArray scriptRef, jint length)
{
    LOG_API("nScriptCCreate, con(%p)", con);

    AutoJavaStringToUTF8 resNameUTF(_env, resName);
    AutoJavaStringToUTF8 cacheDirUTF(_env, cacheDir);
    jint ret = 0;
    jbyte* script_ptr = NULL;
    jint _exception = 0;
    jint remaining;
    if (!scriptRef) {
        _exception = 1;
        //jniThrowException(_env, "java/lang/IllegalArgumentException", "script == null");
        goto exit;
    }
    if (length < 0) {
        _exception = 1;
        //jniThrowException(_env, "java/lang/IllegalArgumentException", "length < 0");
        goto exit;
    }
    remaining = _env->GetArrayLength(scriptRef);
    if (remaining < length) {
        _exception = 1;
        //jniThrowException(_env, "java/lang/IllegalArgumentException",
        //        "length > script.length - offset");
        goto exit;
    }
    script_ptr = (jbyte *)
        _env->GetPrimitiveArrayCritical(scriptRef, (jboolean *)0);

    //rsScriptCSetText(con, (const char *)script_ptr, length);

    ret = (jint)rsScriptCCreate(con,
                                resNameUTF.c_str(), resNameUTF.length(),
                                cacheDirUTF.c_str(), cacheDirUTF.length(),
                                (const char *)script_ptr, length);

exit:
    if (script_ptr) {
        _env->ReleasePrimitiveArrayCritical(scriptRef, script_ptr,
                _exception ? JNI_ABORT: 0);
    }

    return ret;
}

static jint
nScriptIntrinsicCreate(JNIEnv *_env, jobject _this, RsContext con, jint id, jint eid)
{
    LOG_API("nScriptIntrinsicCreate, con(%p) id(%i) element(%p)", con, id, (void *)eid);
    return (jint)rsScriptIntrinsicCreate(con, id, (RsElement)eid);
}

static jint
nScriptKernelIDCreate(JNIEnv *_env, jobject _this, RsContext con, jint sid, jint slot, jint sig)
{
    LOG_API("nScriptKernelIDCreate, con(%p) script(%p), slot(%i), sig(%i)", con, (void *)sid, slot, sig);
    return (jint)rsScriptKernelIDCreate(con, (RsScript)sid, slot, sig);
}

static jint
nScriptFieldIDCreate(JNIEnv *_env, jobject _this, RsContext con, jint sid, jint slot)
{
    LOG_API("nScriptFieldIDCreate, con(%p) script(%p), slot(%i)", con, (void *)sid, slot);
    return (jint)rsScriptFieldIDCreate(con, (RsScript)sid, slot);
}

static jint
nScriptGroupCreate(JNIEnv *_env, jobject _this, RsContext con, jintArray _kernels, jintArray _src,
    jintArray _dstk, jintArray _dstf, jintArray _types)
{
    LOG_API("nScriptGroupCreate, con(%p)", con);

    jint kernelsLen = _env->GetArrayLength(_kernels) * sizeof(int);
    jint *kernelsPtr = _env->GetIntArrayElements(_kernels, NULL);
    jint srcLen = _env->GetArrayLength(_src) * sizeof(int);
    jint *srcPtr = _env->GetIntArrayElements(_src, NULL);
    jint dstkLen = _env->GetArrayLength(_dstk) * sizeof(int);
    jint *dstkPtr = _env->GetIntArrayElements(_dstk, NULL);
    jint dstfLen = _env->GetArrayLength(_dstf) * sizeof(int);
    jint *dstfPtr = _env->GetIntArrayElements(_dstf, NULL);
    jint typesLen = _env->GetArrayLength(_types) * sizeof(int);
    jint *typesPtr = _env->GetIntArrayElements(_types, NULL);

    int id = (int)rsScriptGroupCreate(con,
                               (RsScriptKernelID *)kernelsPtr, kernelsLen,
                               (RsScriptKernelID *)srcPtr, srcLen,
                               (RsScriptKernelID *)dstkPtr, dstkLen,
                               (RsScriptFieldID *)dstfPtr, dstfLen,
                               (RsType *)typesPtr, typesLen);

    _env->ReleaseIntArrayElements(_kernels, kernelsPtr, 0);
    _env->ReleaseIntArrayElements(_src, srcPtr, 0);
    _env->ReleaseIntArrayElements(_dstk, dstkPtr, 0);
    _env->ReleaseIntArrayElements(_dstf, dstfPtr, 0);
    _env->ReleaseIntArrayElements(_types, typesPtr, 0);
    return id;
}

static void
nScriptGroupSetInput(JNIEnv *_env, jobject _this, RsContext con, jint gid, jint kid, jint alloc)
{
    LOG_API("nScriptGroupSetInput, con(%p) group(%p), kernelId(%p), alloc(%p)", con,
        (void *)gid, (void *)kid, (void *)alloc);
    rsScriptGroupSetInput(con, (RsScriptGroup)gid, (RsScriptKernelID)kid, (RsAllocation)alloc);
}

static void
nScriptGroupSetOutput(JNIEnv *_env, jobject _this, RsContext con, jint gid, jint kid, jint alloc)
{
    LOG_API("nScriptGroupSetOutput, con(%p) group(%p), kernelId(%p), alloc(%p)", con,
        (void *)gid, (void *)kid, (void *)alloc);
    rsScriptGroupSetOutput(con, (RsScriptGroup)gid, (RsScriptKernelID)kid, (RsAllocation)alloc);
}

static void
nScriptGroupExecute(JNIEnv *_env, jobject _this, RsContext con, jint gid)
{
    LOG_API("nScriptGroupSetOutput, con(%p) group(%p)", con, (void *)gid);
    rsScriptGroupExecute(con, (RsScriptGroup)gid);
}

// ---------------------------------------------------------------------------

static jint
nProgramStoreCreate(JNIEnv *_env, jobject _this, RsContext con,
                    jboolean colorMaskR, jboolean colorMaskG, jboolean colorMaskB, jboolean colorMaskA,
                    jboolean depthMask, jboolean ditherEnable,
                    jint srcFunc, jint destFunc,
                    jint depthFunc)
{
    LOG_API("nProgramStoreCreate, con(%p)", con);
    return (jint)rsProgramStoreCreate(con, colorMaskR, colorMaskG, colorMaskB, colorMaskA,
                                      depthMask, ditherEnable, (RsBlendSrcFunc)srcFunc,
                                      (RsBlendDstFunc)destFunc, (RsDepthFunc)depthFunc);
}

// ---------------------------------------------------------------------------

static void
nProgramBindConstants(JNIEnv *_env, jobject _this, RsContext con, jint vpv, jint slot, jint a)
{
    LOG_API("nProgramBindConstants, con(%p), vpf(%p), sloat(%i), a(%p)", con, (RsProgramVertex)vpv, slot, (RsAllocation)a);
    rsProgramBindConstants(con, (RsProgram)vpv, slot, (RsAllocation)a);
}

static void
nProgramBindTexture(JNIEnv *_env, jobject _this, RsContext con, jint vpf, jint slot, jint a)
{
    LOG_API("nProgramBindTexture, con(%p), vpf(%p), slot(%i), a(%p)", con, (RsProgramFragment)vpf, slot, (RsAllocation)a);
    rsProgramBindTexture(con, (RsProgramFragment)vpf, slot, (RsAllocation)a);
}

static void
nProgramBindSampler(JNIEnv *_env, jobject _this, RsContext con, jint vpf, jint slot, jint a)
{
    LOG_API("nProgramBindSampler, con(%p), vpf(%p), slot(%i), a(%p)", con, (RsProgramFragment)vpf, slot, (RsSampler)a);
    rsProgramBindSampler(con, (RsProgramFragment)vpf, slot, (RsSampler)a);
}

// ---------------------------------------------------------------------------

static jint
nProgramFragmentCreate(JNIEnv *_env, jobject _this, RsContext con, jstring shader,
                       jobjectArray texNames, jintArray params)
{
    AutoJavaStringToUTF8 shaderUTF(_env, shader);
    jint *paramPtr = _env->GetIntArrayElements(params, NULL);
    jint paramLen = _env->GetArrayLength(params);

    int texCount = _env->GetArrayLength(texNames);
    AutoJavaStringArrayToUTF8 names(_env, texNames, texCount);
    const char ** nameArray = names.c_str();
    size_t* sizeArray = names.c_str_len();

    LOG_API("nProgramFragmentCreate, con(%p), paramLen(%i)", con, paramLen);

    jint ret = (jint)rsProgramFragmentCreate(con, shaderUTF.c_str(), shaderUTF.length(),
                                             nameArray, texCount, sizeArray,
                                             (uint32_t *)paramPtr, paramLen);

    _env->ReleaseIntArrayElements(params, paramPtr, JNI_ABORT);
    return ret;
}


// ---------------------------------------------------------------------------

static jint
nProgramVertexCreate(JNIEnv *_env, jobject _this, RsContext con, jstring shader,
                     jobjectArray texNames, jintArray params)
{
    AutoJavaStringToUTF8 shaderUTF(_env, shader);
    jint *paramPtr = _env->GetIntArrayElements(params, NULL);
    jint paramLen = _env->GetArrayLength(params);

    LOG_API("nProgramVertexCreate, con(%p), paramLen(%i)", con, paramLen);

    int texCount = _env->GetArrayLength(texNames);
    AutoJavaStringArrayToUTF8 names(_env, texNames, texCount);
    const char ** nameArray = names.c_str();
    size_t* sizeArray = names.c_str_len();

    jint ret = (jint)rsProgramVertexCreate(con, shaderUTF.c_str(), shaderUTF.length(),
                                           nameArray, texCount, sizeArray,
                                           (uint32_t *)paramPtr, paramLen);

    _env->ReleaseIntArrayElements(params, paramPtr, JNI_ABORT);
    return ret;
}

// ---------------------------------------------------------------------------

static jint
nProgramRasterCreate(JNIEnv *_env, jobject _this, RsContext con, jboolean pointSprite, jint cull)
{
    LOG_API("nProgramRasterCreate, con(%p), pointSprite(%i), cull(%i)", con, pointSprite, cull);
    return (jint)rsProgramRasterCreate(con, pointSprite, (RsCullMode)cull);
}


// ---------------------------------------------------------------------------

static void
nContextBindRootScript(JNIEnv *_env, jobject _this, RsContext con, jint script)
{
    LOG_API("nContextBindRootScript, con(%p), script(%p)", con, (RsScript)script);
    rsContextBindRootScript(con, (RsScript)script);
}

static void
nContextBindProgramStore(JNIEnv *_env, jobject _this, RsContext con, jint pfs)
{
    LOG_API("nContextBindProgramStore, con(%p), pfs(%p)", con, (RsProgramStore)pfs);
    rsContextBindProgramStore(con, (RsProgramStore)pfs);
}

static void
nContextBindProgramFragment(JNIEnv *_env, jobject _this, RsContext con, jint pf)
{
    LOG_API("nContextBindProgramFragment, con(%p), pf(%p)", con, (RsProgramFragment)pf);
    rsContextBindProgramFragment(con, (RsProgramFragment)pf);
}

static void
nContextBindProgramVertex(JNIEnv *_env, jobject _this, RsContext con, jint pf)
{
    LOG_API("nContextBindProgramVertex, con(%p), pf(%p)", con, (RsProgramVertex)pf);
    rsContextBindProgramVertex(con, (RsProgramVertex)pf);
}

static void
nContextBindProgramRaster(JNIEnv *_env, jobject _this, RsContext con, jint pf)
{
    LOG_API("nContextBindProgramRaster, con(%p), pf(%p)", con, (RsProgramRaster)pf);
    rsContextBindProgramRaster(con, (RsProgramRaster)pf);
}


// ---------------------------------------------------------------------------

static jint
nSamplerCreate(JNIEnv *_env, jobject _this, RsContext con, jint magFilter, jint minFilter,
               jint wrapS, jint wrapT, jint wrapR, jfloat aniso)
{
    LOG_API("nSamplerCreate, con(%p)", con);
    return (jint)rsSamplerCreate(con,
                                 (RsSamplerValue)magFilter,
                                 (RsSamplerValue)minFilter,
                                 (RsSamplerValue)wrapS,
                                 (RsSamplerValue)wrapT,
                                 (RsSamplerValue)wrapR,
                                 aniso);
}

// ---------------------------------------------------------------------------

//native int  rsnPathCreate(int con, int prim, boolean isStatic, int vtx, int loop, float q);
static jint
nPathCreate(JNIEnv *_env, jobject _this, RsContext con, jint prim, jboolean isStatic, jint _vtx, jint _loop, jfloat q) {
    LOG_API("nPathCreate, con(%p)", con);

    int id = (int)rsPathCreate(con, (RsPathPrimitive)prim, isStatic,
                               (RsAllocation)_vtx,
                               (RsAllocation)_loop, q);
    return id;
}

static jint
nMeshCreate(JNIEnv *_env, jobject _this, RsContext con, jintArray _vtx, jintArray _idx, jintArray _prim)
{
    LOG_API("nMeshCreate, con(%p)", con);

    jint vtxLen = _env->GetArrayLength(_vtx);
    jint *vtxPtr = _env->GetIntArrayElements(_vtx, NULL);
    jint idxLen = _env->GetArrayLength(_idx);
    jint *idxPtr = _env->GetIntArrayElements(_idx, NULL);
    jint primLen = _env->GetArrayLength(_prim);
    jint *primPtr = _env->GetIntArrayElements(_prim, NULL);

    int id = (int)rsMeshCreate(con,
                               (RsAllocation *)vtxPtr, vtxLen,
                               (RsAllocation *)idxPtr, idxLen,
                               (uint32_t *)primPtr, primLen);

    _env->ReleaseIntArrayElements(_vtx, vtxPtr, 0);
    _env->ReleaseIntArrayElements(_idx, idxPtr, 0);
    _env->ReleaseIntArrayElements(_prim, primPtr, 0);
    return id;
}

static jint
nMeshGetVertexBufferCount(JNIEnv *_env, jobject _this, RsContext con, jint mesh)
{
    LOG_API("nMeshGetVertexBufferCount, con(%p), Mesh(%p)", con, (RsMesh)mesh);
    jint vtxCount = 0;
    rsaMeshGetVertexBufferCount(con, (RsMesh)mesh, &vtxCount);
    return vtxCount;
}

static jint
nMeshGetIndexCount(JNIEnv *_env, jobject _this, RsContext con, jint mesh)
{
    LOG_API("nMeshGetIndexCount, con(%p), Mesh(%p)", con, (RsMesh)mesh);
    jint idxCount = 0;
    rsaMeshGetIndexCount(con, (RsMesh)mesh, &idxCount);
    return idxCount;
}

static void
nMeshGetVertices(JNIEnv *_env, jobject _this, RsContext con, jint mesh, jintArray _ids, int numVtxIDs)
{
    LOG_API("nMeshGetVertices, con(%p), Mesh(%p)", con, (RsMesh)mesh);

    RsAllocation *allocs = (RsAllocation*)malloc((uint32_t)numVtxIDs * sizeof(RsAllocation));
    rsaMeshGetVertices(con, (RsMesh)mesh, allocs, (uint32_t)numVtxIDs);

    for(jint i = 0; i < numVtxIDs; i ++) {
        _env->SetIntArrayRegion(_ids, i, 1, (const jint*)&allocs[i]);
    }

    free(allocs);
}

static void
nMeshGetIndices(JNIEnv *_env, jobject _this, RsContext con, jint mesh, jintArray _idxIds, jintArray _primitives, int numIndices)
{
    LOG_API("nMeshGetVertices, con(%p), Mesh(%p)", con, (RsMesh)mesh);

    RsAllocation *allocs = (RsAllocation*)malloc((uint32_t)numIndices * sizeof(RsAllocation));
    uint32_t *prims= (uint32_t*)malloc((uint32_t)numIndices * sizeof(uint32_t));

    rsaMeshGetIndices(con, (RsMesh)mesh, allocs, prims, (uint32_t)numIndices);

    for(jint i = 0; i < numIndices; i ++) {
        _env->SetIntArrayRegion(_idxIds, i, 1, (const jint*)&allocs[i]);
        _env->SetIntArrayRegion(_primitives, i, 1, (const jint*)&prims[i]);
    }

    free(allocs);
    free(prims);
}

// ---------------------------------------------------------------------------


static const char *classPathName = "android/renderscript/RenderScript";

static JNINativeMethod methods[] = {
{"_nInit",                         "()V",                                     (void*)_nInit },

{"nDeviceCreate",                  "()I",                                     (void*)nDeviceCreate },
{"nDeviceDestroy",                 "(I)V",                                    (void*)nDeviceDestroy },
{"nDeviceSetConfig",               "(III)V",                                  (void*)nDeviceSetConfig },
{"nContextGetUserMessage",         "(I[I)I",                                  (void*)nContextGetUserMessage },
{"nContextGetErrorMessage",        "(I)Ljava/lang/String;",                   (void*)nContextGetErrorMessage },
{"nContextPeekMessage",            "(I[I)I",                                  (void*)nContextPeekMessage },

{"nContextInitToClient",           "(I)V",                                    (void*)nContextInitToClient },
{"nContextDeinitToClient",         "(I)V",                                    (void*)nContextDeinitToClient },


// All methods below are thread protected in java.
{"rsnContextCreate",                 "(IIII)I",                               (void*)nContextCreate },
{"rsnContextCreateGL",               "(IIIIIIIIIIIIIFI)I",                    (void*)nContextCreateGL },
{"rsnContextFinish",                 "(I)V",                                  (void*)nContextFinish },
{"rsnContextSetPriority",            "(II)V",                                 (void*)nContextSetPriority },
{"rsnContextSetSurface",             "(IIILandroid/view/Surface;)V",          (void*)nContextSetSurface },
{"rsnContextDestroy",                "(I)V",                                  (void*)nContextDestroy },
{"rsnContextDump",                   "(II)V",                                 (void*)nContextDump },
{"rsnContextPause",                  "(I)V",                                  (void*)nContextPause },
{"rsnContextResume",                 "(I)V",                                  (void*)nContextResume },
{"rsnContextSendMessage",            "(II[I)V",                               (void*)nContextSendMessage },
{"rsnAssignName",                    "(II[B)V",                               (void*)nAssignName },
{"rsnGetName",                       "(II)Ljava/lang/String;",                (void*)nGetName },
{"rsnObjDestroy",                    "(II)V",                                 (void*)nObjDestroy },

{"rsnFileA3DCreateFromFile",         "(ILjava/lang/String;)I",                (void*)nFileA3DCreateFromFile },
{"rsnFileA3DCreateFromAssetStream",  "(II)I",                                 (void*)nFileA3DCreateFromAssetStream },
{"rsnFileA3DCreateFromAsset",        "(ILandroid/content/res/AssetManager;Ljava/lang/String;)I",            (void*)nFileA3DCreateFromAsset },
{"rsnFileA3DGetNumIndexEntries",     "(II)I",                                 (void*)nFileA3DGetNumIndexEntries },
{"rsnFileA3DGetIndexEntries",        "(III[I[Ljava/lang/String;)V",           (void*)nFileA3DGetIndexEntries },
{"rsnFileA3DGetEntryByIndex",        "(III)I",                                (void*)nFileA3DGetEntryByIndex },

{"rsnFontCreateFromFile",            "(ILjava/lang/String;FI)I",              (void*)nFontCreateFromFile },
{"rsnFontCreateFromAssetStream",     "(ILjava/lang/String;FII)I",             (void*)nFontCreateFromAssetStream },
{"rsnFontCreateFromAsset",        "(ILandroid/content/res/AssetManager;Ljava/lang/String;FI)I",            (void*)nFontCreateFromAsset },

{"rsnElementCreate",                 "(IIIZI)I",                              (void*)nElementCreate },
{"rsnElementCreate2",                "(I[I[Ljava/lang/String;[I)I",           (void*)nElementCreate2 },
{"rsnElementGetNativeData",          "(II[I)V",                               (void*)nElementGetNativeData },
{"rsnElementGetSubElements",         "(II[I[Ljava/lang/String;[I)V",          (void*)nElementGetSubElements },

{"rsnTypeCreate",                    "(IIIIIZZI)I",                           (void*)nTypeCreate },
{"rsnTypeGetNativeData",             "(II[I)V",                               (void*)nTypeGetNativeData },

{"rsnAllocationCreateTyped",         "(IIIII)I",                               (void*)nAllocationCreateTyped },
{"rsnAllocationCreateFromBitmap",    "(IIILandroid/graphics/Bitmap;I)I",      (void*)nAllocationCreateFromBitmap },
{"rsnAllocationCreateBitmapBackedAllocation",    "(IIILandroid/graphics/Bitmap;I)I",      (void*)nAllocationCreateBitmapBackedAllocation },
{"rsnAllocationCubeCreateFromBitmap","(IIILandroid/graphics/Bitmap;I)I",      (void*)nAllocationCubeCreateFromBitmap },

{"rsnAllocationCopyFromBitmap",      "(IILandroid/graphics/Bitmap;)V",        (void*)nAllocationCopyFromBitmap },
{"rsnAllocationCopyToBitmap",        "(IILandroid/graphics/Bitmap;)V",        (void*)nAllocationCopyToBitmap },

{"rsnAllocationSyncAll",             "(III)V",                                (void*)nAllocationSyncAll },
{"rsnAllocationGetSurface",          "(II)Landroid/view/Surface;",            (void*)nAllocationGetSurface },
{"rsnAllocationSetSurface",          "(IILandroid/view/Surface;)V",           (void*)nAllocationSetSurface },
{"rsnAllocationIoSend",              "(II)V",                                 (void*)nAllocationIoSend },
{"rsnAllocationIoReceive",           "(II)V",                                 (void*)nAllocationIoReceive },
{"rsnAllocationData1D",              "(IIIII[II)V",                           (void*)nAllocationData1D_i },
{"rsnAllocationData1D",              "(IIIII[SI)V",                           (void*)nAllocationData1D_s },
{"rsnAllocationData1D",              "(IIIII[BI)V",                           (void*)nAllocationData1D_b },
{"rsnAllocationData1D",              "(IIIII[FI)V",                           (void*)nAllocationData1D_f },
{"rsnAllocationElementData1D",       "(IIIII[BI)V",                           (void*)nAllocationElementData1D },
{"rsnAllocationData2D",              "(IIIIIIII[II)V",                        (void*)nAllocationData2D_i },
{"rsnAllocationData2D",              "(IIIIIIII[SI)V",                        (void*)nAllocationData2D_s },
{"rsnAllocationData2D",              "(IIIIIIII[BI)V",                        (void*)nAllocationData2D_b },
{"rsnAllocationData2D",              "(IIIIIIII[FI)V",                        (void*)nAllocationData2D_f },
{"rsnAllocationData2D",              "(IIIIIIIIIIIII)V",                      (void*)nAllocationData2D_alloc },
{"rsnAllocationData3D",              "(IIIIIIIII[II)V",                       (void*)nAllocationData3D_i },
{"rsnAllocationData3D",              "(IIIIIIIII[SI)V",                       (void*)nAllocationData3D_s },
{"rsnAllocationData3D",              "(IIIIIIIII[BI)V",                       (void*)nAllocationData3D_b },
{"rsnAllocationData3D",              "(IIIIIIIII[FI)V",                       (void*)nAllocationData3D_f },
{"rsnAllocationData3D",              "(IIIIIIIIIIIIII)V",                     (void*)nAllocationData3D_alloc },
{"rsnAllocationRead",                "(II[I)V",                               (void*)nAllocationRead_i },
{"rsnAllocationRead",                "(II[S)V",                               (void*)nAllocationRead_s },
{"rsnAllocationRead",                "(II[B)V",                               (void*)nAllocationRead_b },
{"rsnAllocationRead",                "(II[F)V",                               (void*)nAllocationRead_f },
{"rsnAllocationGetType",             "(II)I",                                 (void*)nAllocationGetType},
{"rsnAllocationResize1D",            "(III)V",                                (void*)nAllocationResize1D },
{"rsnAllocationGenerateMipmaps",     "(II)V",                                 (void*)nAllocationGenerateMipmaps },

{"rsnScriptBindAllocation",          "(IIII)V",                               (void*)nScriptBindAllocation },
{"rsnScriptSetTimeZone",             "(II[B)V",                               (void*)nScriptSetTimeZone },
{"rsnScriptInvoke",                  "(III)V",                                (void*)nScriptInvoke },
{"rsnScriptInvokeV",                 "(III[B)V",                              (void*)nScriptInvokeV },
{"rsnScriptForEach",                 "(IIIII)V",                              (void*)nScriptForEach },
{"rsnScriptForEach",                 "(IIIII[B)V",                            (void*)nScriptForEachV },
{"rsnScriptForEachClipped",          "(IIIIIIIIIII)V",                        (void*)nScriptForEachClipped },
{"rsnScriptForEachClipped",          "(IIIII[BIIIIII)V",                      (void*)nScriptForEachClippedV },
{"rsnScriptSetVarI",                 "(IIII)V",                               (void*)nScriptSetVarI },
{"rsnScriptGetVarI",                 "(III)I",                                (void*)nScriptGetVarI },
{"rsnScriptSetVarJ",                 "(IIIJ)V",                               (void*)nScriptSetVarJ },
{"rsnScriptGetVarJ",                 "(III)J",                                (void*)nScriptGetVarJ },
{"rsnScriptSetVarF",                 "(IIIF)V",                               (void*)nScriptSetVarF },
{"rsnScriptGetVarF",                 "(III)F",                                (void*)nScriptGetVarF },
{"rsnScriptSetVarD",                 "(IIID)V",                               (void*)nScriptSetVarD },
{"rsnScriptGetVarD",                 "(III)D",                                (void*)nScriptGetVarD },
{"rsnScriptSetVarV",                 "(III[B)V",                              (void*)nScriptSetVarV },
{"rsnScriptGetVarV",                 "(III[B)V",                              (void*)nScriptGetVarV },
{"rsnScriptSetVarVE",                "(III[BI[I)V",                           (void*)nScriptSetVarVE },
{"rsnScriptSetVarObj",               "(IIII)V",                               (void*)nScriptSetVarObj },

{"rsnScriptCCreate",                 "(ILjava/lang/String;Ljava/lang/String;[BI)I",  (void*)nScriptCCreate },
{"rsnScriptIntrinsicCreate",         "(III)I",                                (void*)nScriptIntrinsicCreate },
{"rsnScriptKernelIDCreate",          "(IIII)I",                               (void*)nScriptKernelIDCreate },
{"rsnScriptFieldIDCreate",           "(III)I",                                (void*)nScriptFieldIDCreate },
{"rsnScriptGroupCreate",             "(I[I[I[I[I[I)I",                        (void*)nScriptGroupCreate },
{"rsnScriptGroupSetInput",           "(IIII)V",                               (void*)nScriptGroupSetInput },
{"rsnScriptGroupSetOutput",          "(IIII)V",                               (void*)nScriptGroupSetOutput },
{"rsnScriptGroupExecute",            "(II)V",                                 (void*)nScriptGroupExecute },

{"rsnProgramStoreCreate",            "(IZZZZZZIII)I",                         (void*)nProgramStoreCreate },

{"rsnProgramBindConstants",          "(IIII)V",                               (void*)nProgramBindConstants },
{"rsnProgramBindTexture",            "(IIII)V",                               (void*)nProgramBindTexture },
{"rsnProgramBindSampler",            "(IIII)V",                               (void*)nProgramBindSampler },

{"rsnProgramFragmentCreate",         "(ILjava/lang/String;[Ljava/lang/String;[I)I",              (void*)nProgramFragmentCreate },
{"rsnProgramRasterCreate",           "(IZI)I",                                (void*)nProgramRasterCreate },
{"rsnProgramVertexCreate",           "(ILjava/lang/String;[Ljava/lang/String;[I)I",              (void*)nProgramVertexCreate },

{"rsnContextBindRootScript",         "(II)V",                                 (void*)nContextBindRootScript },
{"rsnContextBindProgramStore",       "(II)V",                                 (void*)nContextBindProgramStore },
{"rsnContextBindProgramFragment",    "(II)V",                                 (void*)nContextBindProgramFragment },
{"rsnContextBindProgramVertex",      "(II)V",                                 (void*)nContextBindProgramVertex },
{"rsnContextBindProgramRaster",      "(II)V",                                 (void*)nContextBindProgramRaster },

{"rsnSamplerCreate",                 "(IIIIIIF)I",                            (void*)nSamplerCreate },

{"rsnPathCreate",                    "(IIZIIF)I",                             (void*)nPathCreate },
{"rsnMeshCreate",                    "(I[I[I[I)I",                            (void*)nMeshCreate },

{"rsnMeshGetVertexBufferCount",      "(II)I",                                 (void*)nMeshGetVertexBufferCount },
{"rsnMeshGetIndexCount",             "(II)I",                                 (void*)nMeshGetIndexCount },
{"rsnMeshGetVertices",               "(II[II)V",                              (void*)nMeshGetVertices },
{"rsnMeshGetIndices",                "(II[I[II)V",                            (void*)nMeshGetIndices },

};

static int registerFuncs(JNIEnv *_env)
{
    return android::AndroidRuntime::registerNativeMethods(
            _env, classPathName, methods, NELEM(methods));
}

// ---------------------------------------------------------------------------

jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
    JNIEnv* env = NULL;
    jint result = -1;

    if (vm->GetEnv((void**) &env, JNI_VERSION_1_4) != JNI_OK) {
        ALOGE("ERROR: GetEnv failed\n");
        goto bail;
    }
    assert(env != NULL);

    if (registerFuncs(env) < 0) {
        ALOGE("ERROR: MediaPlayer native registration failed\n");
        goto bail;
    }

    /* success -- return valid version number */
    result = JNI_VERSION_1_4;

bail:
    return result;
}
