LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := SystemUI-proto

LOCAL_SRC_FILES := $(call all-proto-files-under,src)

LOCAL_PROTOC_OPTIMIZE_TYPE := nano
LOCAL_PROTO_JAVA_OUTPUT_PARAMS := optional_field_style=accessors

include $(BUILD_STATIC_JAVA_LIBRARY)

include $(CLEAR_VARS)

LOCAL_MODULE := SystemUI-tags

LOCAL_SRC_FILES := src/com/android/systemui/EventLogTags.logtags

include $(BUILD_STATIC_JAVA_LIBRARY)

# ------------------

include $(CLEAR_VARS)

LOCAL_USE_AAPT2 := true

LOCAL_MODULE_TAGS := optional

RELATIVE_FINGERPRINT_PATH := ../../core/java/android/hardware/fingerprint

LOCAL_SRC_FILES := \
    $(call all-java-files-under, src) \
    $(call all-Iaidl-files-under, src) \
    $(call all-Iaidl-files-under, $(RELATIVE_FINGERPRINT_PATH))

LOCAL_STATIC_ANDROID_LIBRARIES := \
    SystemUIPluginLib \
    SystemUISharedLib \
    android-support-car \
    android-support-v4 \
    android-support-v7-recyclerview \
    android-support-v7-preference \
    android-support-v7-appcompat \
    android-support-v7-mediarouter \
    android-support-v7-palette \
    android-support-v14-preference \
    android-support-v17-leanback \
    android-slices-core \
    android-slices-view \
    android-slices-builders \
    android-arch-core-runtime \
    android-arch-lifecycle-extensions \

LOCAL_STATIC_JAVA_LIBRARIES := \
    SystemUI-tags \
    SystemUI-proto

LOCAL_JAVA_LIBRARIES := telephony-common \
    android.car \
    ims-common

LOCAL_PACKAGE_NAME := SystemUI
LOCAL_PRIVATE_PLATFORM_APIS := true
LOCAL_CERTIFICATE := platform
LOCAL_PRIVILEGED_MODULE := true

LOCAL_PROGUARD_FLAG_FILES := proguard.flags
LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res-keyguard $(LOCAL_PATH)/res

ifneq ($(INCREMENTAL_BUILDS),)
    LOCAL_PROGUARD_ENABLED := disabled
    LOCAL_JACK_ENABLED := incremental
    LOCAL_DX_FLAGS := --multi-dex
    LOCAL_JACK_FLAGS := --multi-dex native
endif

include frameworks/base/packages/SettingsLib/common.mk

LOCAL_AAPT_FLAGS := --extra-packages com.android.keyguard

include $(BUILD_PACKAGE)

include $(call all-makefiles-under,$(LOCAL_PATH))
