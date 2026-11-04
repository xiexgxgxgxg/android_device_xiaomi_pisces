# LineageOS 16.0 (Android 9) product for Xiaomi Mi 3 TD (pisces, Tegra 4)
$(call inherit-product, vendor/lineage/config/common_full_phone.mk)
$(call inherit-product, device/xiaomi/pisces/device_pisces.mk)

PRODUCT_NAME := lineage_pisces
PRODUCT_DEVICE := pisces
PRODUCT_BRAND := Xiaomi
PRODUCT_MODEL := MI 3
PRODUCT_MANUFACTURER := Xiaomi

TARGET_SCREEN_WIDTH := 1080
TARGET_SCREEN_HEIGHT := 1920

# pisces: remove the crash-looping Bluetooth/NFC apps; see device_pisces.mk.
PRODUCT_PACKAGES := $(filter-out Bluetooth NfcNci,$(PRODUCT_PACKAGES))
