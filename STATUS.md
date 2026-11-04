# LineageOS 16.0 (pisces) bring-up status

Updated: 2026-11-04 21:00

## What works now

- Kernel 3.4.113 with binder SG/PTR/FDA, nvmap sysmem, nvmap cross-process
  handle import by id.
- Boot completes, system_server stays up, launcher/SystemUI reach the screen.
- Storage, audio, keystore, vold as before (patches 0001-0022).
- **CPU: all four cores stay online.** `pisces_cpu.sh` (ramdisk) takes
  hotplug over and onlines CPU1-3, then caps them. Boot runs at 510 MHz,
  `sys.boot_completed=1` raises the cap to 816 MHz.

## This session's root causes

### 1. "The screen barely scrolls" - the system only used one core

`/sys/devices/system/cpu/online` was `0`: the cpuquiet *runnable* governor
parks everything on CPU0 because the A9 UI workload here is GPU/sync bound
(client composition), so the runnable-thread count it samples stays low and
it never brings the other cores back. One Cortex-A15 at 1.02 GHz cannot drive
a 1080p client-composited UI, so input dispatch starved and scrolling felt
frozen.

Fix: `ramdisk/pisces_cpu.sh` sets the cpuquiet governor to `userspace`,
onlines CPU1-3 and then caps them, and only lifts the cap to 816 MHz after
`boot_completed`. Ordering matters: a freshly onlined CPU gets a *new* cpufreq
policy with the default 1.8 GHz limit, and four cores at 1.8 GHz sags the aged
battery below the PMIC `VSYS_MON` threshold and resets the phone (this is what
happened during a manual `echo 1 > online` experiment).

Measured with 4 cores under full load, battery at 20%:
`816 MHz -> VBAT min 3.386 V`, `1.02 -> 3.373`, `1.122 -> 3.403`,
`1.224 -> 3.401`, `1.326 -> 3.363`, `1.428 -> 3.376`, `1.53 -> 3.351`
(no reset, 65 C). So 4 x 1.53 GHz is still inside the budget; 1.8 GHz is not.

### 2. Black screen in Settings - nvmap exhausted by a gralloc leak

SystemUI crash-looped with
`Failed to create EGLSurface for window ..., eglErr = EGL_BAD_ALLOC`,
and the allocator said `pisces: local gralloc alloc failed: -12`.
`/sys/kernel/debug/nvmap/iovmm/clients` showed **surfaceflinger: 794 MB** out
of 862 MB total.

Patch 0023 allocates buffers with the local gralloc0 device and then imports
them through the mapper. The mapper **clones** the native handle and keeps the
clone, while the nvmap memory belongs to the original handle - and nothing
ever freed it, so every released buffer leaked its allocation.

Patch 0026 fixes this: libui remembers `clone -> original` and
`Mapper::freeBuffer()` hands the memory back to gralloc after the mapper has
unregistered the clone.

### 3. Touch misalignment - not reproducible

Raw coordinates were verified in TWRP (the same kernel driver): touching the
four screen corners reported 4.4%/1.6%, 95.2%/2.1%, 5.7%/96.7%, 96.3%/99.2%
and the centre 50.8%/53.8% - the driver mapping is correct. A single
long-press landing on the wrong item was reported once and did not reproduce.

## New in this build (to test)

- WiFi: A9 HIDL stack added (`android.hardware.wifi@1.0-service`,
  HIDL `wpa_supplicant`, `libwifi-hal`); the BCM43341 `bcmdhd` driver is
  already built into the kernel and `wlan0`/`p2p0` exist. The supplicant is
  started by hwservicemanager through the `interface` declarations in
  init.pisces.rc. Manifest entries added.
- Bluetooth: `android.hardware.bluetooth@1.0-service` + `libbt-vendor`
  (hardware/broadcom/libbt, BCM43341) + the `Bluetooth` app is back in the
  image. `bcm43341.hcd` is in /system/etc/firmware, UART is /dev/ttyHS2.
- Camera: `camera.pisces` (wrapper) + `camera.provider@2.4-service` +
  `camera.device@1.0-impl`; `camera.tegra.so` is also copied as
  `camera.vendor.tegra.so` because the wrapper loads
  `hw_get_module_by_class("camera", "vendor")`. The wrapper needed the A9
  sensor header (`<sensor/ISensorServer.h>`) and `libsensor`.
- NFC is still filtered out of the image.

## Next

1. Verify the nvmap leak is gone: `cat /sys/kernel/debug/nvmap/iovmm/clients`
   should keep surfaceflinger bounded (tens of MB, not hundreds).
2. WiFi: check `dumpsys wifi`, `logcat | grep -i supplicant`; wificond may
   still object to the old cfg80211 (`GET_PROTOCOL_FEATURES`).
3. Bluetooth: `dumpsys bluetooth_manager`, firmware download on /dev/ttyHS2.
4. Camera: `dumpsys media.camera`, then a camera app.
5. Baseband/RIL still not started (no rild; `libril-xiaomi-sprd.so` exists).

## Commits

- device (`lineage-16.0`): `343c380` four cores online at a battery-safe clock
- kernel (`lineage-16.0-113`): `e3d9500e` binder FDA + nvmap import
- patches 0026 (gralloc free) and 0027 (camera wrapper headers) are new;
  0023-0025 still need to be applied by hand (they span several projects).
