LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := $(call all-java-files-under, src)
LOCAL_PACKAGE_NAME := PiscesStub
# pisces: keep the crash-looping NFC app out of the system image.  Bluetooth
# must NOT be listed here any more: this stub is installed after the product
# request, and LOCAL_OVERRIDES_PACKAGES silently replaces the real
# com.android.bluetooth app, so the framework has no IBluetooth service and
# Settings can never turn Bluetooth on (the app was last seen in a build from
# 2026-11-04, right before this override was added).
LOCAL_OVERRIDES_PACKAGES := NfcNci
LOCAL_CERTIFICATE := platform
LOCAL_PRIVATE_PLATFORM_APIS := true
LOCAL_PROGUARD_ENABLED := disabled
include $(BUILD_PACKAGE)
