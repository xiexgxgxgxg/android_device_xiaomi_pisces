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

# pisces: NFC still has no working firmware and crash-loops on first boot, so
# keep it out of the image.  Bluetooth is enabled again in device_pisces.mk
# (do NOT filter it here - this file is inherited after device_pisces.mk, so a
# filter here silently undoes the request).
PRODUCT_PACKAGES := $(filter-out NfcNci,$(PRODUCT_PACKAGES))
