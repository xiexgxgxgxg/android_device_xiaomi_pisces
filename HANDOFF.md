# Pisces (Xiaomi Mi 3 TD) LineageOS 16.0 bring-up — handoff

Date: 2026-11-04/05 (long session). Device: Xiaomi Mi 3 TD, `pisces`, Tegra 4 T114,
32-bit, 1080x1920, 16 MB recovery, eMMC 458 GB userdata at `/data`.

## Environment

- Project root: `/data/twrp` (build scripts: `env.sh`, `build-lineage.sh`,
  `build-twrp.sh`; JDK8 at `/data/twrp/toolchain/jdk8`).
- Lineage tree: `/data/lineage-16.0` (mainline, `lineage_pisces-eng`).
- Kernel source inside tree: `/data/lineage-16.0/kernel/xiaomi/pisces`;
  standalone copy `/data/twrp/kernel/src` (same files).
- TWRP tree: `/data/twrp/source-9.0`, device tree
  `/data/twrp/source-9.0/device/xiaomi/pisces`.
- Device repo (canonical): `/data/twrp/device-repo-real` (branch `lineage-16.0`).
  The build uses `/data/lineage-16.0/device/xiaomi/pisces`, which is a **copy** —
  after editing the repo, copy the changed files over (device_pisces.mk,
  lineage_pisces.mk, manifest.xml, ramdisk/*).
- Build log web view: `python3 /data/twrp/logweb.py` serves
  `build-logs/latest.log` on http://127.0.0.1:8800/ (2 s refresh).

Build:
```bash
cd /data/lineage-16.0
export LC_ALL=C LANG=C LOCPATH=/data/twrp/locale \
  JAVA_HOME=/data/twrp/toolchain/jdk8 \
  PATH=/data/twrp/toolchain/bin:/data/twrp/toolchain/python2/bin:/data/twrp/toolchain/jdk8/bin:$PATH \
  LD_LIBRARY_PATH=/data/twrp/toolchain/lib
source build/envsetup.sh && lunch lineage_pisces-eng
mka bootimage -j16        # ~1.5 min
mka bacon -j16            # full OTA, ~20 min cold, ~5 min incremental
```

## Flashing / testing

- Never `fastboot flash boot`. `fastboot boot recovery.img` enters TWRP.
- From a booted system: `adb reboot recovery` lands in TWRP (verified).
- Flash an OTA from TWRP:
  ```bash
  adb push out/target/product/pisces/lineage_pisces-ota-eng.xie.zip /sdcard/lineage.zip
  adb shell '/sbin/twrp install /sdcard/lineage.zip'    # ~2m15
  adb reboot
  ```
- Pushing a single library without a rebuild (root shell):
  ```bash
  adb root; adb shell 'mount -o rw,remount /system'
  adb push <file> /system/lib/... ; adb shell chmod 644 <dest>
  ```
- Partitions: LNX=p19 boot, SOS=p21 recovery, APP=p24 system, CAC=p26 cache,
  UDA=p27 data, MSC=p7.
- `/proc/last_kmsg` works in TWRP.

## Boot power workaround (must stay)

The 2013 battery cannot carry stock boot current: PMIC (`palmas 4-0058`)
raises `VSYS_MON` (threshold 2950 mV) and does a POR reset ~3.4 s into boot.

Kept in `ramdisk/init.pisces.rc`: 4 cores online at 510 MHz during boot,
backlight 20; after `sys.boot_completed=1` the cap is lifted.

Measured this session with 4 cores at 100% load (battery at 20%):
`816 MHz -> VBAT 3.386 V`, `1.02 -> 3.373`, `1.122 -> 3.403`,
`1.224 -> 3.401`, `1.326 -> 3.363`, `1.428 -> 3.376`, `1.53 -> 3.351`
(temperature 65 C, no reset). Four cores at **1.8 GHz** does reset the phone.

## The three big fixes of this session

### 1. Only one CPU core was online (this is why the UI felt frozen)

`/sys/devices/system/cpu/online` was `0`. The cpuquiet *runnable* governor
parks everything on CPU0 because the A9 UI workload is GPU/sync bound, so the
runnable-thread count it samples stays low and it never brings the other cores
back. One 1.02 GHz A15 cannot drive a 1080p client-composited UI.

`ramdisk/pisces_cpu.sh` (new, in the ramdisk root) switches the cpuquiet
governor to `userspace`, onlines CPU1-3 and only then caps them (a freshly
onlined CPU gets a new cpufreq policy with the default 1.8 GHz limit — capping
before onlining is what makes it possible to reset the phone). Boot keeps
510 MHz; after `boot_completed` the cap is 816 MHz.

### 2. Black screen / SystemUI crash loop — nvmap leak

Symptoms: `EGL_BAD_ALLOC` on `eglCreateWindowSurface`, and
`pisces: local gralloc alloc failed: -12`.
`/sys/kernel/debug/nvmap/iovmm/clients` showed **surfaceflinger: 794 MB** of
862 MB total.

Patch 0023 allocates buffers with the local gralloc0 device and imports them
through the mapper. The mapper **clones** the handle; the nvmap memory belongs
to the original handle and was never freed.

Patch 0026 (`libs/ui/Gralloc2.cpp`) keeps a `clone -> original` map and frees
the original in `Mapper::freeBuffer()`. After the fix the total stays around
250-290 MB and there are no EGL failures.

### 3. Touch misalignment — not reproducible

Raw coordinates verified in TWRP: the four corners report 4.4%/1.6%,
95.2%/2.1%, 5.7%/96.7%, 96.3%/99.2%, centre 50.8%/53.8% — the driver mapping
is correct. A single wrong long-press was reported once, never reproduced.

## WiFi / Bluetooth / camera (this session's new work)

### WiFi — HAL starts, chip config still fails

The BCM43341 `bcmdhd` driver is built into the kernel (`CONFIG_BCMDHD=y`) and
`wlan0`/`p2p0` exist; only the A9 userspace stack was missing. Added
`android.hardware.wifi@1.0-service`, `libwifi-hal`, HIDL `wpa_supplicant`, and
manifest entries (single `<version>` per HAL — libvintf rejects one version per
major twice).

State: the HAL service runs and registers wifi 1.0/1.1/1.2, `wpa_supplicant`
runs, `WifiVendorHal: Vendor Hal started successfully`, then
`HalDevMgr: executeChipReconfiguration: configureChip error: 9` (ERROR_UNKNOWN)
and `Failed to create STA iface`.

`WifiChip::handleChipConfiguration()` fails at either
`WifiModeController::changeFirmwareMode()` (writes
`/sys/module/bcmdhd/parameters/firmware_path`, which was root-only — now chowned
to wifi in init.pisces.rc) or `WifiLegacyHal::start()` (`wifi_initialize()` of
`libwifi-hal-bcm`). The firmware path write now succeeds, so the failure is in
the legacy HAL start; next step is to instrument `libwifi-hal-bcm`'s
`wifi_initialize()`.

Also: `wificond` logs `NL80211_CMD_GET_PROTOCOL_FEATURES failed` (3.4 cfg80211
is too old) — it continues without it.

### Bluetooth — needs the app back in the image

`android.hardware.bluetooth@1.0-service` + `libbt-vendor`
(hardware/broadcom/libbt) are in the image and the HAL registers.
`/dev/ttyHS2` (bluetooth:bluetooth), `bt_vendor.conf` and `bcm43341.hcd` are in
place. The blocker was the `Bluetooth` app: `lineage_pisces.mk` is inherited
*after* `device_pisces.mk` and still had
`PRODUCT_PACKAGES := $(filter-out Bluetooth NfcNci,...)`, which silently undid
the request. That line now only filters NfcNci.

### Camera — HAL loads, open fails inside the blob

Added: `camera.pisces` wrapper, `camera.provider@2.4-service` + `-impl`,
`camera.device@1.0/3.2/3.3/3.4/3.4-external-impl`, `libyuv`, `Snap` +
`Camera2`, and `libpisces_camshim`.

The 2013 blob (2013 `SensorManager` ABI, TEXTREL in `libFaceProc.so`) is loaded
via `LD_PRELOAD=/system/lib/libpisces_camshim.so` on the camera provider
service, defined in `init.pisces.rc` (init parses it before
`/vendor/etc/hw/...rc`, so the stock definition is ignored). The shim provides
the 10 Android 4.x `SensorManager`/`SensorEventQueue`/`Singleton` symbols and
drops the process target SDK to 22 so bionic accepts TEXTREL.

Result: `CameraWrapper: camera count = 2` (both cameras enumerate). Opening
camera 0 fails inside the blob:
`NvCameraHal: setupHalInLock:539 failed to open camera! Error: 0x3`, and the
destructor of the half-constructed `NvCameraHal` (`SetBufferConfigStage`)
crashes the provider. Next step: find out what NvError 0x3 is in
`setupHalInLock` (likely the NvMM/ISP block or sensor power-up).

The wrapper no longer waits minutes for `sensorservice` (there is no sensors
HAL; the sensor entry points are stubbed).

## Kernel

Unchanged this session (`e3d9500e` binder FDA + nvmap import). Known perf issue
not yet addressed: nvmap prints `pr_info("pisces: from_id ...")` for every
cross-process import; those should be gated behind a module parameter.

## Late-session findings (camera / BT / wifi)

### WiFi — now enables (four separate blockers)

1. The HAL service needs the `inet` group: `CONFIG_ANDROID_PARANOID_NETWORK=y`
   makes `socket(AF_INET)` fail with EACCES, and a 3.4 kernel has no ambient
   capabilities so the stock rc `capabilities NET_ADMIN NET_RAW SYS_MODULE`
   line is silently dropped by init (`capabilities requested but the kernel
   does not support ambient capabilities`).  Fixed by overriding the service
   in `init.pisces.rc` with `group wifi gps inet`.
2. `libwifi-hal` failed `wifi_initialize()` because bcmdhd publishes no
   nl80211 `vendor` multicast group ("Could not find group vendor" ->
   "Add membership failed" -> WIFI_ERROR_NOT_AVAILABLE).  Now tolerated.
3. wificond dropped every interface ("No usable interface found") because the
   3.4 `nl80211_send_iface` does not put `NL80211_ATTR_MAC` in the
   `NEW_INTERFACE` dump.  Kernel patch added (see `kernel-patches/`).
4. wpa_supplicant deinitialised wlan0 because
   `/proc/sys/net/ipv4/conf/wlan0/drop_unicast_in_l2_multicast` (Linux 3.14+)
   is missing; `nl80211_write_to_file()` now skips ENOENT.

State: `dumpsys wifi` = enabled, supplicant has the STA interface
(DISCONNECTED = ready), the chip is configured and scans work.
`firewall`/`vendor` group warnings remain but are harmless.

### Camera — icon restored, HAL stops at NvMM

- The missing launcher icon was **Snap's own doing**: `DisableCameraReceiver`
  runs at BOOT_COMPLETED, asks the old camera API for the camera count and
  calls `setComponentEnabledSetting(DISABLED)` on `CameraLauncher` when it is
  0.  With the camera service now enumerating, `pm enable
  org.lineageos.snap/com.android.camera.CameraLauncher` restores the icon.
- `/sys/power/wake_lock` is `radio:wakelock 0660`; the camera HAL needs write
  access or every open fails with "could not acquire camera wake lock".
  Now chmod 0666 in `init.pisces.rc`.
- `libpisces_camshim`'s `createEventQueue()` stub had the ARM sret/this
  argument order swapped, corrupting the SensorManager object and crashing in
  `NvCameraHal::CleanupSensorListener()`.
- Remaining: `libnvmm_camera.so` wants `/dev/video0` and `/dev/video1`, which
  do not exist.  `CONFIG_VIDEO_TEGRA` + a `tegra_camera` platform device were
  added to board-pisces.c, but the V4L2 host still does not create the node
  (`vi vi: initialized` only).  The stock board likely registers an nvhost
  "vi" device carrying `tegra_camera_platform_data` instead.  Until then the
  HAL fails in `NvCameraHalBlockHelpers` ("Component handle is invalid") and
  returns NvError 0x3 (NotFound) from `setupHalInLock`.

### Bluetooth — firmware loads, HCI does not answer

- `bdaddr` and `rfkill0` handled; the adapter now reports
  00:22:33:44:77:00, libbt downloads the firmware
  ("Firmware configured in 12.765s") and the stack starts.
- `bt_hci: command_timed_out ... opcode 0xc03` (HCI Reset) - the controller
  never answers on `/dev/ttyHS2`.  libbt uses `CRTSCTS`.  Next: check the
  post-download baud switch (`UART_TARGET_BAUD_RATE 3000000` in
  `hardware/broadcom/libbt/include/bt_vendor_brcm.h`) and compare with
  `/vendor/bin/nv_hciattach`, the NVIDIA tool the stock ROM used.

## Modem / RIL (baseband version now shows)

`gsm.version.baseband` was empty because nothing ever started the modem.  Four
things were wrong:

1. `fild` and `fdl` (the Spreadtrum modem loader and its image transfer tool)
   were in `bin/` but were never copied into the image; both are in
   `PRODUCT_COPY_FILES` now and `init.modem_sprd.rc` starts `fild` from
   `on boot`.  fild flashes the modem over SPI, sets the GSM0710 line
   discipline ("Line discipline switched to 26"), then starts rild itself.
2. `init.modem_sprd.rc` used the 4.x-only `service_redefine` keyword and the
   `class_start:main` trigger; A9 init rejected the whole block.  Rewritten.
3. rild needs the pre-M libcutils `socket_loopback_client()` for the vendor
   `libril-xiaomi-sprd.so`; `libpisces_rilshim` is LD_PRELOADed into the
   service (same trick as the camera).
4. A9's rild appends `-c <clientId>` to the vendor RIL arguments and the 2012
   Spreadtrum RIL aborts on it (`invalid option -- c`, then
   "xiaomi-ril requires: -p <tcp port> or -d /dev/tty_device", RIL_Init returns
   NULL).  `hardware/ril/rild/rild.c` only passes `-c` for non-zero slots now,
   and `rild.libargs` is `-d /dev/ts0710mux0` (the `-r ts0710mux*` form the old
   file had is not understood by this RIL).

Result: rild registers `android.hardware.radio@1.0/1.1::IRadio/slot1` and
`IOemHook/slot1`, and `gsm.version.baseband` reads
`DM_BASE_13A_8803G_W13.44 | 20140120092332`.

Still broken: SIM/telephony.  The vendor RIL speaks the 2012 RIL API
(`RIL_register: RIL version 6`) and A9's libril parses the responses with the
current structures, so `GET_SIM_STATUS` comes back as
`error 66 INVALID_RESPONSE` (`CARDSTATE_ABSENT`), `currentSignalStrengthInd:
invalid response`, and `RADIO_POWER` returns GENERIC_FAILURE.  The modem itself
is alive (+ECSQ unsolicited every 5 s).  Making calls/data work would mean
adapting the vendor RIL to the RIL v9+ structures (CardStatus, CellInfo,
SignalStrength, ...) - a real porting job, not a one-liner.

## Camera, session 3: where it stands

Fixed since the last notes:

- `libpisces_camshim` is C++ now.  The 2013 HAL calls the five
  SensorEventQueue methods (`getFd`, `read`, `enableSensor`, `disableSensor`,
  `setEventRate`) by *symbol*, not through a vtable, so the shim can hand the
  blob any object: it returns a real queue when a sensors HAL exists and a
  bare RefBase otherwise, with the five symbols forwarding to libsensor.
  `NvCameraHalCore: Fail to initialize SensorListener` is gone.
- The target-SDK-22 constructor has to stay in the shim; without it
  `libFaceProc.so` fails to load with text relocations.

What the trace shows now (`strace -f -y` on the provider):

1. `/dev/imx135` opens fine (a stale manual provider instance had it with
   EBUSY and was poisoning tests - kill it before tracing).
2. `/dev/nvhost-vi`, `/dev/nvhost-isp`, `/dev/knvmap`, `/dev/tegra_sema`,
   `/dev/tegra_rpc` are all opened.
3. The NvMM camera/DZ/JPEG blocks then wait on `/dev/tegra_sema`
   (`TEGRA_SEMA_IOCTL_WAIT`, magic 'r', nr 0x30) and that wait **times out
   after 5 s**, which is the `setupHalInLock:539 Error: 0x3`.

Root cause of the semaphore timeout: the camera's TRPC goes to the
`avp-remote` node, which is registered by `drivers/media/video/tegra/avp/avp.c`
(`CONFIG_TEGRA_AVP`).  That option is **off**, so only the `local` TRPC node
exists.  Turning it on does register `avp-remote` and the semaphore waits do
work - but `avp.c` reserves the AVP boot window at 0x0ff00000 and then
`nvavp` cannot create its OS handle (`nvavp: cannot create os handle`,
`-ENOMEM`), so `/dev/tegra_avpchannel` disappears and the HAL aborts even
earlier.  Moving nvavp to the alternate address fails too (0x0eff00000 is
outside the AVP IOVM window; the vendor's `nvavp_os_eff00000.bin` is for
another tegra variant).

So the camera needs both the `avp-remote` TRPC node and the nvavp channel, and
a single AVP cannot serve both as the drivers are written.  Next ideas, in
order of promise:

- Port `avp.c` to register only the TRPC node and let `nvavp` own the AVP,
  i.e. find where the stock used one driver for both (the MIUI 4.x kernel).
- Or add the TRPC service to the nvavp OS (that is what the 4.x NvMM did:
  `nvavp_os_0ff00000.bin` plausibly carries the trpc service).
- Verify against a stock boot: `dmesg | grep -i 'trpc_node_register'` on MIUI.

## Next steps

1. WiFi: instrument `libwifi-hal-bcm`'s `wifi_initialize()` /
   `WifiLegacyHal::start()` to find why the chip configuration returns
   ERROR_UNKNOWN.
2. Camera: NvError 0x3 from `setupHalInLock`; also make the provider dump a
   tombstone instead of crashing the destructor.
3. Sensors HAL (there is none, which is why the camera wrapper had to skip the
   sensorservice wait).
4. Baseband/RIL: `ril-daemon` and `gps-daemon` start and exit; `ro.boot.modem=sprd`
   and `libril-xiaomi-sprd.so` exist.
5. Gate the nvmap debug prints.

## Repo commits (latest)

- device `lineage-16.0`: `343c380` four cores online, `b0b76e6` wifi/bt/camera
  + nvmap leak fix, `4e3118e` camera app + bounded sensorservice wait.
- kernel `lineage-16.0-113`: `e3d9500e`.
- patches: 0026 (gralloc free), 0027 + `hardware-overlay/` (camera wrapper and
  shim). 0023-0025 still need to be applied by hand (they span projects).
