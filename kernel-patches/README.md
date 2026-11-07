# Kernel changes that live in the build tree

`/data/lineage-16.0/kernel/xiaomi/pisces` is not a git project, so the full
modified files are kept here.  After a kernel re-sync, copy them back:

    cp kernel-patches/files/board-pisces.c           ../../../kernel/xiaomi/pisces/arch/arm/mach-tegra/
    cp kernel-patches/files/tegra11_pisces_user_defconfig ../../../kernel/xiaomi/pisces/arch/arm/configs/

## 0001-nl80211-report-iface-mac.patch

`nl80211_send_iface()` did not report `NL80211_ATTR_MAC`, so Android 9's
wificond discarded every interface ("Failed to get interface mac address" ->
"No usable interface found") and WiFi could never be set up.

## board-pisces.c / defconfig

`CONFIG_VIDEO_TEGRA` was off and no `tegra_camera` platform device was
registered, so there is no `/dev/video0`.  `libnvmm_camera.so` wants it.
The device/channel is registered now but the V4L2 host still does not create
the node (see HANDOFF.md) - the stock board probably registers an nvhost "vi"
device carrying `tegra_camera_platform_data` instead of a platform device.

## leds-lm3533.c / LED brightness

The LM3533 board data gives red/green a hardware blink pattern
(`delay_on`/`delay_off` in `board-pisces-panel.c`), which sets
`patten_enabled` at probe time.  `lm3533_led_set()` then skipped the
workqueue, so a plain `echo 255 > /sys/class/leds/red/brightness` was
silently dropped (the brightness register kept reading 0) and only writing
`/sys/class/leds/red/update` reached the chip.  That broke the stock
charger binary (`chargeonlymode`), which writes the brightness nodes
directly, and made the LED look dead.

The fixed `drivers/leds/leds-lm3533.c` always queues the brightness work.
Copy it back with:

    cp kernel-patches/files/leds-lm3533.c ../../../kernel/xiaomi/pisces/drivers/leds/

The rest of the LED fix is userspace: `config_deviceLightCapabilities`
(overlay) + the battery LED colours, see the device overlay tree.
