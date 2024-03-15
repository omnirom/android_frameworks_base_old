/* //device/libs/android_runtime/android_util_FileObserver.cpp
**
** Copyright 2006, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License"); 
** you may not use this file except in compliance with the License. 
** You may obtain a copy of the License at 
**
**     http://www.apache.org/licenses/LICENSE-2.0 
**
** Unless required by applicable law or agreed to in writing, software 
** distributed under the License is distributed on an "AS IS" BASIS, 
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
** See the License for the specific language governing permissions and 
** limitations under the License.
*/

#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <nativehelper/ScopedUtfChars.h>
#include "jni.h"
#include "utils/Log.h"
#include "utils/misc.h"
#include "core_jni_helpers.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <errno.h>

#if defined(__linux__)
#include <sys/inotify.h>
#endif

namespace android {

static jmethodID method_onEvent;

static jint android_os_fileobserver_init(JNIEnv* env, jobject object)
{
#if defined(__linux__)
    return (jint)inotify_init1(IN_CLOEXEC);
#else
    return -1;
#endif
}

static void android_os_fileobserver_observe(JNIEnv* env, jobject object, jint fd)
{
#if defined(__linux__)

    char event_buf[512];
    struct inotify_event* event;

    while (1)
    {
        int event_pos = 0;
        int num_bytes = read(fd, event_buf, sizeof(event_buf));

        if (num_bytes < (int)sizeof(*event))
        {
            if (errno == EINTR)
                continue;

            ALOGE("***** ERROR! android_os_fileobserver_observe() got a short event!");
            return;
        }

        while (num_bytes >= (int)sizeof(*event))
        {
            int event_size;
            event = (struct inotify_event *)(event_buf + event_pos);

            jstring path = NULL;

            if (event->len > 0)
            {
                path = env->NewStringUTF(event->name);
            }

            env->CallVoidMethod(object, method_onEvent, event->wd, event->mask, path);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
            if (path != NULL)
            {
                env->DeleteLocalRef(path);
            }

            event_size = sizeof(*event) + event->len;
            num_bytes -= event_size;
            event_pos += event_size;
        }
    }

#endif
}

static void android_os_fileobserver_startWatching(JNIEnv* env, jobject object, jint fd,
                                                       jobjectArray pathStrings, jint mask,
                                                       jintArray wfdArray)
{
    ScopedIntArrayRW wfds(env, wfdArray);
    if (wfds.get() == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException", "Failed to get ScopedIntArrayRW");
    }

#if defined(__linux__)

    if (fd >= 0)
    {
        size_t count = wfds.size();
        for (size_t i = 0; i < count; ++i) {
            jstring pathString = (jstring) env->GetObjectArrayElement(pathStrings, i);

            ScopedUtfChars path(env, pathString);

            wfds[i] = inotify_add_watch(fd, path.c_str(), mask);
        }
    }

#endif
}

static void android_os_fileobserver_stopWatching(JNIEnv* env, jobject object,
                                                 jint fd, jintArray wfdArray)
{
#if defined(__linux__)

    ScopedIntArrayRO wfds(env, wfdArray);
    if (wfds.get() == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException", "Failed to get ScopedIntArrayRO");
    }
    size_t count = wfds.size();
    for (size_t i = 0; i < count; ++i) {
        inotify_rm_watch((int)fd, (uint32_t)wfds[i]);
    }

#endif
}

static const JNINativeMethod sMethods[] = {
     /* name, signature, funcPtr */
    { "init", "()I", (void*)android_os_fileobserver_init },
    { "observe", "(I)V", (void*)android_os_fileobserver_observe },
    { "startWatching", "(I[Ljava/lang/String;I[I)V", (void*)android_os_fileobserver_startWatching },
    { "stopWatching", "(I[I)V", (void*)android_os_fileobserver_stopWatching }

};

int register_android_os_FileObserver(JNIEnv* env)
{
    jclass clazz = FindClassOrDie(env, "android/os/FileObserver$ObserverThread");

    method_onEvent = GetMethodIDOrDie(env, clazz, "onEvent", "(IILjava/lang/String;)V");

    return RegisterMethodsOrDie(env, "android/os/FileObserver$ObserverThread", sMethods,
                                NELEM(sMethods));
}

} /* namespace android */
