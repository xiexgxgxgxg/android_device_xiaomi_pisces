LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := $(call all-java-files-under, src)
LOCAL_PACKAGE_NAME := PiscesStub
# pisces: keep the crash-looping Bluetooth/NFC apps out of the system image
LOCAL_OVERRIDES_PACKAGES := Bluetooth NfcNci
LOCAL_CERTIFICATE := platform
LOCAL_PRIVATE_PLATFORM_APIS := true
LOCAL_PROGUARD_ENABLED := disabled
include $(BUILD_PACKAGE)
