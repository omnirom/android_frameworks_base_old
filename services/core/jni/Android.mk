# This file is included by the top level services directory to collect source
# files
LOCAL_REL_DIR := core/jni

LOCAL_CFLAGS += -Wall -Werror -Wno-unused-parameter

LOCAL_SRC_FILES += \
    $(LOCAL_REL_DIR)/com_android_server_AlarmManagerService.cpp \
    $(LOCAL_REL_DIR)/com_android_server_am_BatteryStatsService.cpp \
    $(LOCAL_REL_DIR)/com_android_server_connectivity_Vpn.cpp \
    $(LOCAL_REL_DIR)/com_android_server_ConsumerIrService.cpp \
    $(LOCAL_REL_DIR)/com_android_server_HardwarePropertiesManagerService.cpp \
    $(LOCAL_REL_DIR)/com_android_server_hdmi_HdmiCecController.cpp \
    $(LOCAL_REL_DIR)/com_android_server_input_InputApplicationHandle.cpp \
    $(LOCAL_REL_DIR)/com_android_server_input_InputManagerService.cpp \
    $(LOCAL_REL_DIR)/com_android_server_input_InputWindowHandle.cpp \
    $(LOCAL_REL_DIR)/com_android_server_lights_LightsService.cpp \
    $(LOCAL_REL_DIR)/com_android_server_location_ContextHubService.cpp \
    $(LOCAL_REL_DIR)/com_android_server_location_GnssLocationProvider.cpp \
    $(LOCAL_REL_DIR)/com_android_server_power_PowerManagerService.cpp \
    $(LOCAL_REL_DIR)/com_android_server_SerialService.cpp \
    $(LOCAL_REL_DIR)/com_android_server_SyntheticPasswordManager.cpp \
    $(LOCAL_REL_DIR)/com_android_server_storage_AppFuseBridge.cpp \
    $(LOCAL_REL_DIR)/com_android_server_SystemServer.cpp \
    $(LOCAL_REL_DIR)/com_android_server_tv_TvUinputBridge.cpp \
    $(LOCAL_REL_DIR)/com_android_server_tv_TvInputHal.cpp \
    $(LOCAL_REL_DIR)/com_android_server_vr_VrManagerService.cpp \
    $(LOCAL_REL_DIR)/com_android_server_UsbDeviceManager.cpp \
    $(LOCAL_REL_DIR)/com_android_server_UsbMidiDevice.cpp \
    $(LOCAL_REL_DIR)/com_android_server_UsbHostManager.cpp \
    $(LOCAL_REL_DIR)/com_android_server_VibratorService.cpp \
    $(LOCAL_REL_DIR)/com_android_server_PersistentDataBlockService.cpp \
    $(LOCAL_REL_DIR)/com_android_server_GraphicsStatsService.cpp \
    $(LOCAL_REL_DIR)/onload.cpp

LOCAL_C_INCLUDES += \
    $(JNI_H_INCLUDE) \
    external/scrypt/lib/crypto \
    frameworks/base/services \
    frameworks/base/libs \
    frameworks/base/core/jni \
    frameworks/native/services \
    system/core/libappfuse/include \
    system/gatekeeper/include \
    system/security/keystore/include \
    $(call include-path-for, libhardware)/hardware \
    $(call include-path-for, libhardware_legacy)/hardware_legacy \

LOCAL_SHARED_LIBRARIES += \
    libandroid_runtime \
    libandroidfw \
    libbase \
    libappfuse \
    libbinder \
    libcutils \
    libcrypto \
    liblog \
    libhardware \
    libhardware_legacy \
    libkeystore_binder \
    libnativehelper \
    libutils \
    libui \
    libinput \
    libinputflinger \
    libinputservice \
    libschedulerservicehidl \
    libsensorservice \
    libsensorservicehidl \
    libskia \
    libgui \
    libusbhost \
    libsuspend \
    libEGL \
    libGLESv2 \
    libnetutils \
    libhidlbase \
    libhidltransport \
    libhwbinder \
    libutils \
    libhwui \
    android.hardware.audio.common@2.0 \
    android.hardware.contexthub@1.0 \
    android.hardware.gnss@1.0 \
    android.hardware.ir@1.0 \
    android.hardware.light@2.0 \
    android.hardware.power@1.0 \
    android.hardware.thermal@1.0 \
    android.hardware.tv.cec@1.0 \
    android.hardware.tv.input@1.0 \
    android.hardware.vibrator@1.0 \
    android.hardware.vr@1.0 \
    android.frameworks.schedulerservice@1.0 \
    android.frameworks.sensorservice@1.0 \

LOCAL_STATIC_LIBRARIES += libscrypt_static

ifeq ($(BOARD_USES_QC_TIME_SERVICES),true)
LOCAL_CFLAGS += -DHAVE_QC_TIME_SERVICES
LOCAL_SHARED_LIBRARIES += libtime_genoff
$(shell mkdir -p $(OUT)/obj/SHARED_LIBRARIES/libtime_genoff_intermediates/)
$(shell touch $(OUT)/obj/SHARED_LIBRARIES/libtime_genoff_intermediates/export_includes)
endif
