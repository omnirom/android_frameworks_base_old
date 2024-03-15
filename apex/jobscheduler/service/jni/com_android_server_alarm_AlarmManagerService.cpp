/*
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

#define LOG_TAG "AlarmManagerService"

#include <android-base/file.h>
#include <android-base/unique_fd.h>
#include <nativehelper/JNIHelp.h>
#include <utils/Log.h>
#include <utils/String8.h>
#include <utils/misc.h>
#include "jni.h"

#include <dirent.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/epoll.h>
#include <sys/timerfd.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <stdlib.h>
#include <errno.h>
#include <unistd.h>
#include <linux/ioctl.h>
#include <linux/rtc.h>

#include <array>
#include <limits>
#include <memory>

namespace android {

static constexpr int ANDROID_ALARM_TIME_CHANGE_MASK = 1 << 16;

/**
 * The AlarmManager alarm constants:
 *
 *   RTC_WAKEUP
 *   RTC
 *   REALTIME_WAKEUP
 *   REALTIME
 *   SYSTEMTIME (only defined in old alarm driver header, possibly unused?)
 *
 * We also need an extra CLOCK_REALTIME fd which exists specifically to be
 * canceled on RTC changes.
 */
static const size_t ANDROID_ALARM_TYPE_COUNT = 5;
static const size_t N_ANDROID_TIMERFDS = ANDROID_ALARM_TYPE_COUNT + 1;
static const clockid_t android_alarm_to_clockid[N_ANDROID_TIMERFDS] = {
    CLOCK_REALTIME_ALARM,
    CLOCK_REALTIME,
    CLOCK_BOOTTIME_ALARM,
    CLOCK_BOOTTIME,
    CLOCK_MONOTONIC,
    CLOCK_REALTIME,
};

typedef std::array<int, N_ANDROID_TIMERFDS> TimerFds;

class AlarmImpl
{
public:
    AlarmImpl(const TimerFds &fds, int epollfd)
          : fds{fds}, epollfd{epollfd} {}
    ~AlarmImpl();

    int set(int type, struct timespec *ts);
    int waitForAlarm();
    int getTime(int type, struct itimerspec *spec);

private:
    const TimerFds fds;
    const int epollfd;
};

AlarmImpl::~AlarmImpl()
{
    for (auto fd : fds) {
        epoll_ctl(epollfd, EPOLL_CTL_DEL, fd, nullptr);
        close(fd);
    }

    close(epollfd);
}

int AlarmImpl::set(int type, struct timespec *ts)
{
    if (static_cast<size_t>(type) > ANDROID_ALARM_TYPE_COUNT) {
        errno = EINVAL;
        return -1;
    }

    if (!ts->tv_nsec && !ts->tv_sec) {
        ts->tv_nsec = 1;
    }
    /* timerfd interprets 0 = disarm, so replace with a practically
       equivalent deadline of 1 ns */

    struct itimerspec spec;
    memset(&spec, 0, sizeof(spec));
    memcpy(&spec.it_value, ts, sizeof(spec.it_value));

    return timerfd_settime(fds[type], TFD_TIMER_ABSTIME, &spec, NULL);
}

int AlarmImpl::getTime(int type, struct itimerspec *spec)
{
    if (static_cast<size_t>(type) > ANDROID_ALARM_TYPE_COUNT) {
        errno = EINVAL;
        return -1;
    }

    return timerfd_gettime(fds[type], spec);
}

int AlarmImpl::waitForAlarm()
{
    epoll_event events[N_ANDROID_TIMERFDS];

    int nevents = epoll_wait(epollfd, events, N_ANDROID_TIMERFDS, -1);
    if (nevents < 0) {
        return nevents;
    }

    int result = 0;
    for (int i = 0; i < nevents; i++) {
        uint32_t alarm_idx = events[i].data.u32;
        uint64_t unused;
        ssize_t err = read(fds[alarm_idx], &unused, sizeof(unused));
        // Worth evaluating even if read fails with EAGAIN, since epoll_wait
        // returned. (see b/78560047#comment34)
        if (err < 0 && errno != EAGAIN) {
            if (alarm_idx == ANDROID_ALARM_TYPE_COUNT && errno == ECANCELED) {
                result |= ANDROID_ALARM_TIME_CHANGE_MASK;
            } else {
                return err;
            }
        } else {
            result |= (1 << alarm_idx);
        }
    }

    return result;
}

static void log_timerfd_create_error(clockid_t id)
{
    if (errno == EINVAL) {
        switch (id) {
        case CLOCK_REALTIME_ALARM:
        case CLOCK_BOOTTIME_ALARM:
            ALOGE("kernel missing required commits:");
            ALOGE("https://git.kernel.org/cgit/linux/kernel/git/torvalds/linux.git/commit/?id=6cffe00f7d4e24679eae6b7aae4caaf915288256");
            ALOGE("https://git.kernel.org/cgit/linux/kernel/git/torvalds/linux.git/commit/?id=11ffa9d6065f344a9bd769a2452f26f2f671e5f8");
            LOG_ALWAYS_FATAL("kernel does not support timerfd_create() with alarm timers");
            break;

        case CLOCK_BOOTTIME:
            ALOGE("kernel missing required commit:");
            ALOGE("https://git.kernel.org/cgit/linux/kernel/git/torvalds/linux.git/commit/?id=4a2378a943f09907fb1ae35c15de917f60289c14");
            LOG_ALWAYS_FATAL("kernel does not support timerfd_create(CLOCK_BOOTTIME)");
            break;

        default:
            break;
        }
    }

    ALOGE("timerfd_create(%u) failed: %s", id, strerror(errno));
}

static jlong android_server_alarm_AlarmManagerService_init(JNIEnv*, jobject)
{
    int epollfd;
    TimerFds fds;

    epollfd = epoll_create(fds.size());
    if (epollfd < 0) {
        ALOGE("epoll_create(%zu) failed: %s", fds.size(), strerror(errno));
        return 0;
    }

    for (size_t i = 0; i < fds.size(); i++) {
        fds[i] = timerfd_create(android_alarm_to_clockid[i], TFD_NONBLOCK);
        if (fds[i] < 0) {
            log_timerfd_create_error(android_alarm_to_clockid[i]);
            close(epollfd);
            for (size_t j = 0; j < i; j++) {
                close(fds[j]);
            }
            return 0;
        }
    }

    std::unique_ptr<AlarmImpl> alarm{new AlarmImpl(fds, epollfd)};

    for (size_t i = 0; i < fds.size(); i++) {
        epoll_event event;
        event.events = EPOLLIN | EPOLLWAKEUP;
        event.data.u32 = i;

        int err = epoll_ctl(epollfd, EPOLL_CTL_ADD, fds[i], &event);
        if (err < 0) {
            ALOGE("epoll_ctl(EPOLL_CTL_ADD) failed: %s", strerror(errno));
            return 0;
        }
    }

    struct itimerspec spec = {};
    /* 0 = disarmed; the timerfd doesn't need to be armed to get
       RTC change notifications, just set up as cancelable */

    int err = timerfd_settime(fds[ANDROID_ALARM_TYPE_COUNT],
            TFD_TIMER_ABSTIME | TFD_TIMER_CANCEL_ON_SET, &spec, NULL);
    if (err < 0) {
        ALOGE("timerfd_settime() failed: %s", strerror(errno));
        return 0;
    }

    return reinterpret_cast<jlong>(alarm.release());
}

static jlong android_server_alarm_AlarmManagerService_getNextAlarm(JNIEnv*, jobject, jlong nativeData, jint type)
{
    AlarmImpl *impl = reinterpret_cast<AlarmImpl *>(nativeData);
    struct itimerspec spec;
    memset(&spec, 0, sizeof(spec));
    const int result = impl->getTime(type, &spec);
    if (result < 0)
    {
        ALOGE("timerfd_gettime() failed for fd %d: %s\n", static_cast<int>(type), strerror(errno));
        return result;
    }
    struct timespec nextTimespec = spec.it_value;
    long long millis = nextTimespec.tv_sec * 1000LL;
    millis += (nextTimespec.tv_nsec / 1000000LL);
    return static_cast<jlong>(millis);
}

static void android_server_alarm_AlarmManagerService_close(JNIEnv*, jobject, jlong nativeData)
{
    AlarmImpl *impl = reinterpret_cast<AlarmImpl *>(nativeData);
    delete impl;
}

static jint android_server_alarm_AlarmManagerService_set(JNIEnv*, jobject, jlong nativeData, jint type, jlong seconds, jlong nanoseconds)
{
    AlarmImpl *impl = reinterpret_cast<AlarmImpl *>(nativeData);
    struct timespec ts;
    ts.tv_sec = seconds;
    ts.tv_nsec = nanoseconds;

    const int result = impl->set(type, &ts);
    if (result < 0)
    {
        ALOGE("Unable to set alarm to %lld.%09lld: %s\n",
              static_cast<long long>(seconds),
              static_cast<long long>(nanoseconds), strerror(errno));
    }
    return result >= 0 ? 0 : errno;
}

static jint android_server_alarm_AlarmManagerService_waitForAlarm(JNIEnv*, jobject, jlong nativeData)
{
    AlarmImpl *impl = reinterpret_cast<AlarmImpl *>(nativeData);
    int result = 0;

    do
    {
        result = impl->waitForAlarm();
    } while (result < 0 && errno == EINTR);

    if (result < 0)
    {
        ALOGE("Unable to wait on alarm: %s\n", strerror(errno));
        return 0;
    }

    return result;
}

static const JNINativeMethod sMethods[] = {
     /* name, signature, funcPtr */
    {"init", "()J", (void*)android_server_alarm_AlarmManagerService_init},
    {"close", "(J)V", (void*)android_server_alarm_AlarmManagerService_close},
    {"set", "(JIJJ)I", (void*)android_server_alarm_AlarmManagerService_set},
    {"waitForAlarm", "(J)I", (void*)android_server_alarm_AlarmManagerService_waitForAlarm},
    {"getNextAlarm", "(JI)J", (void*)android_server_alarm_AlarmManagerService_getNextAlarm},
};

int register_android_server_alarm_AlarmManagerService(JNIEnv* env)
{
    return jniRegisterNativeMethods(env, "com/android/server/alarm/AlarmManagerService",
                                    sMethods, NELEM(sMethods));
}

} /* namespace android */
