# Pisces (Xiaomi Mi 3 TD) LineageOS 16.0 bring-up — handoff

Date: 2026-11-04 (late session). Device: Xiaomi Mi 3 TD, `pisces`, Tegra 4 T114,
32-bit, 1080x1920, 16 MB recovery, eMMC 458 GB userdata at `/data`.

## Environment

- Project root: `/data/twrp` (build scripts: `env.sh`, `build-lineage.sh`,
  `build-twrp.sh`; JDK8 at `/data/twrp/toolchain/jdk8`).
- Lineage tree: `/data/lineage-16.0` (mainline, `lineage_pisces-eng`).
- Kernel source inside tree: `/data/lineage-16.0/kernel/xiaomi/pisces`;
  standalone copy `/data/twrp/kernel/src` (same files).
- TWRP tree: `/data/twrp/source-9.0`, device tree
  `/data/twrp/source-9.0/device/xiaomi/pisces`.
- Device repo clone: `/data/twrp/device-repo-real` (branch `lineage-16.0`).
- Git: remotes are HTTPS with a PAT baked into the URL (user-provided, keep
  it out of public pastes). SSH to GitHub is intercepted by the phone proxy
  (banner `SSH-2.0-5ec5cb2f`), so always use HTTPS.

Build:
```bash
cd /data/lineage-16.0
export LC_ALL=C JAVA_HOME=/data/twrp/toolchain/jdk8 \
  PATH=/data/twrp/toolchain/bin:/data/twrp/toolchain/python2/bin:/data/twrp/toolchain/jdk8/bin:$PATH \
  LD_LIBRARY_PATH=/data/twrp/toolchain/lib
source build/envsetup.sh && lunch lineage_pisces-eng
mka bootimage -j16          # ~1.5 min
mka libui -j16              # ~10 s
mka android.hardware.graphics.composer@2.1-impl -j16
```

## Flashing / testing

- Never `fastboot flash boot` (ignored/unsafe experiments): Mi 3 bootloader
  accepts `fastboot boot recovery.img` and that is how TWRP is entered.
- Flashing boot from TWRP or a rooted system:
  ```bash
  adb push out/target/product/pisces/boot.img /sdcard/boot.img
  adb shell 'dd if=/sdcard/boot.img of=/dev/block/mmcblk0p19 bs=4096; sync'
  ```
  then verify md5 of the first `boot.img` size bytes vs `/dev/block/mmcblk0p19`.
- Full OTA: `/tmp/flash-auto.sh` (TWRP: push zip, install, reboot; log
  `/tmp/flash-auto.log`). TWRP adb offline: `adb reconnect offline`.
- Partitions: LNX=p19 boot, SOS=p21 recovery, APP=p24 system, CAC=p26 cache,
  UDA=p27 data, MSC=p7.
- `/proc/last_kmsg` **works in TWRP** and holds the previous boot's kernel
  log — best tool for boot/reset debugging.

## Boot power workaround (must stay)

The 2013 battery cannot carry stock boot current: PMIC (`palmas 4-0058`)
raises `VSYS_MON` (threshold 2950 mV) and does a POR reset ~3.4 s into boot.

Fix in `device/xiaomi/pisces/ramdisk/init.pisces.rc`:
- `on early-init`: bring cpu1-3 online, governor `interactive`,
  `scaling_max_freq 510000`; `on boot`: same cap for cpu1-3.
- `on boot`: backlight brightness 20 for `lm3533-backlight0/1`.
- `on property:sys.boot_completed=1`: raise all cores to `1020000`.

Verified: `boot_completed=1`, `VSYS_MON=0`, animation finishes. Do **not**
restore 1.8 GHz boot without re-testing.

## Graphics stack (this session's core work)

Kernel (`lineage-16.0-113`, commit `e3d9500e`):
- binder: `BINDER_TYPE_FDA` + SG/PTR support for A9 libhwbinder.
- nvmap: allow `NVMAP_IOC_FROM_ID` cross-process import (gralloc in SF).
- `.gitignore` for kernel build artifacts (never `git add -A` in kernel tree).

System patches (in `device/xiaomi/pisces/patches`, applied by
`apply-pisces-patches.sh`; mirrors in `/data/twrp/device-repo-real/patches`):
- 0001..0022: init/bionic/hwservicemanager/servicemanager/zygote/installd/
  netd/AlarmManager/audio/media/sdcard-bind/vold/keystore/WiFi patches.
- 0023 `gralloc-local-alloc`: libui allocates with local gralloc0 module and
  registers in-process; also adds `HW_TEXTURE` (0x200) for
  HW_COMPOSER|HW_RENDER buffers because EGL window surfaces need it.
- 0024 `hwc-force-client-composition`: HWC2On1Adapter forces every HWC1
  overlay layer to CLIENT composition.
- 0025 `graphicbuffer-handle-clone` + padded `operator new` in
  `libshim_atomic`: the HWC blob allocates objects with old `sizeof()`
  (GraphicBuffer ABI); shim over-allocates 512 bytes and clones handles.

Why: legacy gralloc fails cross-process `registerBuffer` for usage 0x900
(SystemUI `EGL_BAD_ALLOC`); legacy hwcomposer blob crashes in
`glc_set -> destroy_graphic_buffer -> ~GraphicBuffer` due to the A9 class
layout. With the four pieces the system reaches the launcher and SetupWizard
completes (`user_setup_complete=1`, `device_provisioned=1`).

## Current state (end of session)

Working:
- Boot is reliable (power workaround), SetupWizard completes, launcher and
  SystemUI start, screen off no longer reliably soft-restarts (verify).
- emulated storage (sdcard bind fallback), user 0 unlock, audio HAL,
  keystore, vold, no Bluetooth/NFC (PiscesStub overrides them).

Known remaining:
- **WiFi**: A9 uses HIDL. `PRODUCT_PACKAGES` lacks
  `android.hardware.wifi@1.0-service` and
  `android.hardware.wifi.supplicant@1.2-service`, so `WifiNative` says
  "Failed to start supplicant" and Settings hangs when WiFi is toggled.
  Add the services + manifest entries, rebuild, test.
  Also `wificond` logs `NL80211_CMD_GET_PROTOCOL_FEATURES failed` (3.4
  cfg80211 too old) — may need a tolerance patch or nl80211 backport.
- **Baseband/RIL**: `ro.boot.modem=sprd` (Spreadtrum modem),
  `gsm.version.baseband` empty — RIL is not running; needs the matching
  rild/libril and props.
- Remaining HALs: sensors, camera, light/power, soundtrigger, audio policy
  outputs (partially configured).
- 4 GB system resize module / all-in-one flashable zip still to build.

## Useful debugging recipes

```bash
# Current display stack health
adb shell 'dumpsys window | grep mCurrentFocus'
adb shell 'logcat -d | grep -cE "EGL_BAD_ALLOC|Fatal signal"'
# Gralloc/composer verbose
adb shell 'logcat -d | grep -E "pisces: |forcing layer|registerBuffer"'
# Kernel boot/PMIC diagnostics from TWRP
adb shell 'tail -40 /proc/last_kmsg'
adb shell 'grep -c VSYS_MON /proc/last_kmsg'
# CPU/power state
adb shell 'cat /sys/devices/system/cpu/online; cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq'
```

Notes:
- `top`/heavy adb commands hang under load; prefer short commands.
- If system boot loops 2-3 s after Mi logo: it is the battery/VSYS issue
  unless the 510 MHz cap was lost.
- When editing init.pisces.rc always keep the low-power cap and the
  post-boot restore.
- Each source change should be committed to the device repo and pushed
  (`patches/` + `ramdisk/` + `STATUS.md`).

## Repo commits (latest)

- device `lineage-16.0`: `650018a` (HW_TEXTURE), `df81701` (local alloc +
  client composition + handle clone), `a7801cc` (510 MHz boot), `528285c`,
  `2186b95`, ...
- kernel `lineage-16.0-113`: `e3d9500e` (binder FDA + nvmap import + ignore).
