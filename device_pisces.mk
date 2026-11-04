# vim:et:ts=4:sts=4
LOCAL_PATH := device/xiaomi/pisces

$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/languages_full.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base_telephony.mk)
$(call inherit-product, frameworks/native/build/phone-xhdpi-2048-dalvik-heap.mk)

$(call inherit-product-if-exists, vendor/xiaomi/pisces/pisces-vendor.mk)

DEVICE_PACKAGE_OVERLAYS += $(LOCAL_PATH)/overlay
PRODUCT_CHARACTERISTICS := nosdcard

PRODUCT_DEFAULT_PROPERTY_OVERRIDES += \
    persist.sys.usb.config=adb

PRODUCT_PROPERTY_OVERRIDES += \
    drm.service.enabled=true

# permissions file from AOSP
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/egl.cfg:system/lib/egl/egl.cfg \
    frameworks/base/nfc-extras/com.android.nfc_extras.xml:system/etc/permissions/com.android.nfc_extras.xml \
    frameworks/native/data/etc/android.hardware.bluetooth_le.xml:system/etc/permissions/android.hardware.bluetooth_le.xml \
    frameworks/native/data/etc/android.hardware.camera.flash-autofocus.xml:system/etc/permissions/android.hardware.camera.flash-autofocus.xml \
    frameworks/native/data/etc/android.hardware.camera.front.xml:system/etc/permissions/android.hardware.camera.front.xml \
    frameworks/native/data/etc/android.hardware.camera.xml:system/etc/permissions/android.hardware.camera.xml \
    frameworks/native/data/etc/android.hardware.ethernet.xml:system/etc/permissions/android.hardware.ethernet.xml \
    frameworks/native/data/etc/android.hardware.location.gps.xml:system/etc/permissions/android.hardware.location.gps.xml \
    frameworks/native/data/etc/android.hardware.nfc.hce.xml:system/etc/permissions/android.hardware.nfc.hce.xml \
    frameworks/native/data/etc/android.hardware.nfc.xml:system/etc/permissions/android.hardware.nfc.xml \
    frameworks/native/data/etc/android.hardware.sensor.accelerometer.xml:system/etc/permissions/android.hardware.sensor.accelerometer.xml \
    frameworks/native/data/etc/android.hardware.sensor.barometer.xml:system/etc/permissions/android.hardware.sensor.barometer.xml \
    frameworks/native/data/etc/android.hardware.sensor.compass.xml:system/etc/permissions/android.hardware.sensor.compass.xml \
    frameworks/native/data/etc/android.hardware.sensor.gyroscope.xml:system/etc/permissions/android.hardware.sensor.gyroscope.xml \
    frameworks/native/data/etc/android.hardware.sensor.light.xml:system/etc/permissions/android.hardware.sensor.light.xml \
    frameworks/native/data/etc/android.hardware.sensor.proximity.xml:system/etc/permissions/android.hardware.sensor.proximity.xml \
    frameworks/native/data/etc/android.hardware.telephony.gsm.xml:system/etc/permissions/android.hardware.telephony.gsm.xml \
    frameworks/native/data/etc/android.hardware.touchscreen.multitouch.jazzhand.xml:system/etc/permissions/android.hardware.touchscreen.multitouch.jazzhand.xml \
    frameworks/native/data/etc/android.hardware.usb.accessory.xml:system/etc/permissions/android.hardware.usb.accessory.xml \
    frameworks/native/data/etc/android.hardware.usb.host.xml:system/etc/permissions/android.hardware.usb.host.xml \
    frameworks/native/data/etc/android.hardware.wifi.direct.xml:system/etc/permissions/android.hardware.wifi.direct.xml \
    frameworks/native/data/etc/android.hardware.wifi.xml:system/etc/permissions/android.hardware.wifi.xml \
    frameworks/native/data/etc/android.software.sip.voip.xml:system/etc/permissions/android.software.sip.voip.xml \
    frameworks/native/data/etc/android.software.sip.xml:system/etc/permissions/android.software.sip.xml \
    frameworks/native/data/etc/handheld_core_hardware.xml:system/etc/permissions/handheld_core_hardware.xml

# permissions file from vendor
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/permissions/com.broadcom.bt.xml:system/etc/permissions/com.broadcom.bt.xml \
    $(LOCAL_PATH)/permissions/com.broadcom.nfc.xml:system/etc/permissions/com.broadcom.nfc.xml \
    $(LOCAL_PATH)/permissions/com.nvidia.graphics.xml:system/etc/permissions/com.nvidia.graphics.xml \
    $(LOCAL_PATH)/permissions/com.nvidia.miracast.xml:system/etc/permissions/com.nvidia.miracast.xml \
    $(LOCAL_PATH)/permissions/com.nvidia.nvsi.xml:system/etc/permissions/com.nvidia.nvsi.xml \
    $(LOCAL_PATH)/permissions/com.nvidia.nvstereoutils.xml:system/etc/permissions/com.nvidia.nvstereoutils.xml \
    $(LOCAL_PATH)/permissions/com.nvidia.wifi.xml:system/etc/permissions/com.nvidia.wifi.xml \
    $(LOCAL_PATH)/permissions/com.vzw.nfc.xml:system/etc/permissions/com.vzw.nfc.xml \
    $(LOCAL_PATH)/permissions/org.simalliance.openmobileapi.xml:system/etc/permissions/org.simalliance.openmobileapi.xml

PRODUCT_COPY_FILES += \
    $(call find-copy-subdir-files,*,$(LOCAL_PATH)/prebuilt/system,system)

# boot logo
TARGET_BOOTANIMATION_HALF_RES := true

# init
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/ramdisk/adb_keys:root/adb_keys \
    $(LOCAL_PATH)/ramdisk/fstab.pisces:root/fstab.pisces \
    $(LOCAL_PATH)/ramdisk/fstab.zram_128:root/fstab.zram_128 \
    $(LOCAL_PATH)/ramdisk/fstab.zram_256:root/fstab.zram_256 \
    $(LOCAL_PATH)/ramdisk/fstab.zram_512:root/fstab.zram_512 \
    $(LOCAL_PATH)/ramdisk/init.hdcp.rc:root/init.hdcp.rc \
    $(LOCAL_PATH)/ramdisk/init.modem_imc.rc:root/init.modem_imc.rc \
    $(LOCAL_PATH)/ramdisk/init.modem_sprd.rc:root/init.modem_sprd.rc \
    $(LOCAL_PATH)/ramdisk/init.nv_dev_board.usb.rc:root/init.nv_dev_board.usb.rc \
    $(LOCAL_PATH)/ramdisk/init.pisces.rc:root/init.pisces.rc \
    $(LOCAL_PATH)/ramdisk/init.quickboot.rc:root/init.quickboot.rc \
    $(LOCAL_PATH)/ramdisk/pisces_cpu.sh:root/pisces_cpu.sh \
    $(LOCAL_PATH)/ramdisk/ueventd.pisces.rc:root/ueventd.pisces.rc

# safe mount_ext4, dualboot/mount helper script
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/bin/mount_ext4.sh:system/bin/mount_ext4.sh

PRODUCT_PACKAGES += \
    dumpe2fs

# ril
PRODUCT_PROPERTY_OVERRIDES += \
    persist.radio.apm_sim_not_pwdn=1

# powerhal
PRODUCT_PACKAGES +=

# gps
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/gps/gps.conf:system/etc/gps.conf \
    $(LOCAL_PATH)/gps/gpsconfigftm.xml:system/etc/gpsconfigftm.xml \
    $(LOCAL_PATH)/gps/gpsconfig.xml:system/etc/gps/gpsconfig.xml

# bluetooth
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/bluetooth/bt_vendor.conf:system/etc/bluetooth/bt_vendor.conf

# camera
PRODUCT_PACKAGES +=

PRODUCT_COPY_FILES += \
    hardware/nvidia/pisces/camera/model_frontal.xml:system/etc/model_frontal.xml \
    hardware/nvidia/pisces/camera/nvcamera.conf:system/etc/nvcamera.conf

# media
PRODUCT_COPY_FILES += \
    frameworks/av/media/libstagefright/data/media_codecs_google_audio.xml:system/etc/media_codecs_google_audio.xml \
    frameworks/av/media/libstagefright/data/media_codecs_google_telephony.xml:system/etc/media_codecs_google_telephony.xml \
    frameworks/av/media/libstagefright/data/media_codecs_google_video.xml:system/etc/media_codecs_google_video.xml \
    $(LOCAL_PATH)/media/media_codecs.xml:system/etc/media_codecs.xml \
    $(LOCAL_PATH)/media/media_profiles.xml:system/etc/media_profiles.xml \
    $(LOCAL_PATH)/media/enctune.conf:system/etc/enctune.conf

# Audio
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/audio/asound.conf:system/etc/asound.conf \
    $(LOCAL_PATH)/audio/audio_effects.conf:system/vendor/etc/audio_effects.conf \
    $(LOCAL_PATH)/audio/audio_policy.conf:system/etc/audio_policy.conf \
    $(LOCAL_PATH)/audio/nvaudio_conf.xml:system/etc/nvaudio_conf.xml

PRODUCT_PACKAGES += \
    audio.primary.tegra \
    audio.a2dp.default \
    audio.usb.default \
    audio.r_submix.default \
    libaudioutils \
    audio.usb.default \
    tinycap \
    tinymix \
    tinypcminfo \
    tinyplay

# Graphic
PRODUCT_PACKAGES += \
    hwcomposer.tegra \
    libnvcap_video \
    libnvomxadaptor

# Key layout
PRODUCT_COPY_FILES += \
    $(call find-copy-subdir-files,*,$(LOCAL_PATH)/keylayout,system/usr/keylayout)

# Misc hardwares
PRODUCT_PACKAGES += \
    lights.pisces

# Charger
PRODUCT_PACKAGES += \
    charger_res_images

# QuickBoot
PRODUCT_PACKAGES += \
    QuickBoot

PRODUCT_COPY_FILES += \
    bootable/recovery/fonts/18x32.png:root/res/images/font.png

# Misc
PRODUCT_PACKAGES += \
    librs_jni \
    libnetcmdiface  \
    e2fsck

# NFC
PRODUCT_PACKAGES += \
    nfc_nci.bcm2079x.default \
    NfcNci \
    Tag \
    com.android.nfc_extras \
    SmartcardService

# USB
PRODUCT_PACKAGES += \
    com.android.future.usb.accessory

# Vibrator
PRODUCT_PACKAGES += \
    immvibed \
    libImmVibeJ \
    vibrator.pisces

# Vendor Apps
PRODUCT_PACKAGES += \
    AMAPNetworkLocation \
    Cit \
    NvCPLSvc

PRODUCT_PACKAGES += \
    libwpa_client \
    hostapd \
    dhcpcd.conf \
    wpa_supplicant \
    wpa_supplicant.conf

PRODUCT_PACKAGES += \
    libshim_icuuc \
    libshim_list


PRODUCT_PACKAGES += \
    android.hardware.graphics.composer@2.1-service \
    android.hardware.graphics.composer@2.1-impl \
    libshim_atomic

PRODUCT_PACKAGES += \
    android.hardware.keymaster@3.0-service \
    android.hardware.keymaster@3.0-impl

PRODUCT_COPY_FILES += \
    vendor/xiaomi/pisces/proprietary/lib/egl/libEGL_tegra.so:system/lib/egl/libEGL.so \
    vendor/xiaomi/pisces/proprietary/lib/egl/libGLESv1_CM_tegra.so:system/lib/egl/libGLESv1_CM.so \
    vendor/xiaomi/pisces/proprietary/lib/egl/libGLESv2_tegra.so:system/lib/egl/libGLESv2.so

PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/usbdebug.sh:system/etc/usbdebug.sh

PRODUCT_PACKAGES += \
    android.hardware.graphics.mapper@2.0-impl

PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/usbfix.sh:system/etc/usbfix.sh

PRODUCT_PACKAGES += \
    android.hardware.graphics.allocator@2.0-service \
    android.hardware.graphics.allocator@2.0-impl

PRODUCT_COPY_FILES += \
    vendor/xiaomi/pisces/proprietary/lib/hw/gralloc.tegra.so:system/vendor/lib/hw/gralloc.tegra.so

PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/userdirs.sh:system/etc/userdirs.sh

PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/audio/audio_policy_configuration.xml:system/vendor/etc/audio/audio_policy_configuration.xml \
    $(LOCAL_PATH)/audio/audio_policy_volumes.xml:system/vendor/etc/audio/audio_policy_volumes.xml \
    $(LOCAL_PATH)/audio/default_volume_tables.xml:system/vendor/etc/audio/default_volume_tables.xml

PRODUCT_PACKAGES += \
    android.hardware.audio@2.0-impl \
    android.hardware.audio.effect@2.0-impl \
    android.hardware.audio@2.0-service

# pisces: WiFi, Bluetooth and camera for Android 9.
#
# WiFi: the BCM43341 dhd driver is built into the kernel (CONFIG_BCMDHD=y,
# wlan0/p2p0 already exist); what was missing is the A9 HIDL stack.  The
# supplicant is the HIDL-enabled one from external/wpa_supplicant_8 and is
# started by the "wpa_supplicant" service in init.pisces.rc.
PRODUCT_PACKAGES += \
    android.hardware.wifi@1.0-service \
    libwifi-hal \
    wpa_supplicant

# Bluetooth: BCM43341 combo, vendor HAL from hardware/broadcom/libbt.  The
# Bluetooth app itself is not pulled in by full_base.mk (only by
# generic_no_telephony.mk), so ask for it explicitly.
PRODUCT_PACKAGES += \
    Bluetooth \
    android.hardware.bluetooth@1.0-impl \
    android.hardware.bluetooth@1.0-service \
    libbt-vendor

# Camera: the tegra camera HAL is a 2013 hw_module (camera.tegra.so); A9 only
# reaches it through camera.provider@2.4 + the 1.0 device wrapper.  The
# CameraWrapper loads "camera.vendor.<variant>", so the blob is also copied
# under that name (ro.board.platform=tegra).
#
# The passthrough impl libraries have to be in the image by name: they are
# dlopen()ed, not linked by anything that is already installed, and libyuv is
# a dependency of the external-camera provider.  libpisces_camshim provides the
# Android 4.x SensorManager entry points the blob needs and drops the process
# target SDK to 22 so the TEXTREL in libFaceProc.so is accepted.
PRODUCT_PACKAGES += \
    camera.pisces \
    android.hardware.camera.provider@2.4-service \
    android.hardware.camera.provider@2.4-impl \
    camera.device@1.0-impl \
    camera.device@3.2-impl \
    camera.device@3.3-impl \
    camera.device@3.4-impl \
    camera.device@3.4-external-impl \
    libyuv \
    libpisces_camshim

PRODUCT_COPY_FILES += \
    vendor/xiaomi/pisces/proprietary/lib/hw/camera.tegra.so:system/vendor/lib/hw/camera.vendor.tegra.so

# pisces: NFC has no working firmware yet and crash-loops on first boot; keep
# it out of the image.  Bluetooth is back now that libbt-vendor builds.
PRODUCT_PACKAGES := $(filter-out NfcNci,$(PRODUCT_PACKAGES))

PRODUCT_PACKAGES += PiscesStub
