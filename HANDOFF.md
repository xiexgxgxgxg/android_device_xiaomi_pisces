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

### Bluetooth — WORKS (session 3)

Three separate bugs, all fixed:

1. **No `vnd_pisces.txt`.**  libbt-vendor was built from `vnd_generic.txt`,
   which does not set `UART_TARGET_BAUD_RATE`, so it used the 3000000 default
   from `bt_vendor_brcm.h`.  The BCM43341 never followed that switch - after
   the firmware config it still answered HCI Reset at 115200 and answered
   nothing at 3 Mbit (verified with a raw `01 03 0c 00` probe on the tty).
   `include/vnd_pisces.txt` now pins the rate to 115200.
2. **The HCD download hangs the chip.**  After libbt downloads
   `bcm43341.hcd` the controller stops answering HCI at *any* baud until
   BT_REG_ON is power cycled (`echo 0/1 > /sys/class/rfkill/rfkill0/state`
   brings it back).  `hardware.c` honours `persist.pisces.bt.skip_fw` (set from
   `init.pisces.rc`) and skips the download; the ROM firmware speaks HCI.
3. **The app crashed on start-up**: PBAP opened ContactsProvider2 without
   READ_CONTACTS -> `SecurityException` -> AdapterService restart loop -> the
   adapter stayed OFF.  `/system/etc/default-permissions/pisces-bluetooth.xml`
   pre-grants the runtime permissions.

`dumpsys bluetooth_manager` now reports `enabled: true`, `state: ON` with the
controller's real address, stable across reboots.  Remaining noise: one
unsupported vendor command (0xfc27) logged as "no matching command".

### Bluetooth — historical note (before session 3)

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

## Audio (session 4): what the hardware actually looks like

There is **no sound** and the reason is structural.  Findings:

- The framework runs the AOSP **stub** HAL (`/vendor/lib/hw/audio.primary.default.so`)
  because the real one is installed as `/system/lib/hw/audio.primary.tegra.so`
  while `hw_get_module("audio.primary")` looks for `audio.primary.pisces.so`.
- Renaming the vendor HAL makes it load (after also providing the NVIDIA
  `libtinyalsa.so`, which exports `mixer_dup`/`mixer_ctl_convert_from_db`; the
  MIUI dump has a working one) but `open()` then returns `EINVAL`.  It is an
  Android 4.x HAL and waits on services A9 does not have (`media.audio_policy`,
  `nvaudioservice`), so porting it is not a rename job.
- The topology is I2S -> **ES325 voice processor** (`cpu_dai_name =
  "es325-portd"` in `tegra_cs42l73.c`) -> **CS42L73** codec -> speaker.  The
  ES325 has to be programmed (its `Internal Route Config`/`Preset` controls,
  currently "Audio route reset") before any audio reaches the codec.
- The speaker itself is on the **ESL** path of the codec, not the SPK path:
  `Int Spk` <- `SPKLINEOUT` <- `SPKLO Amp` <- `ESL DAC` <- `ESL Mixer` <-
  `ESL-ASP Mux`, and `Int Spk` drives `gpio-108 (spkr_en)` which enables the
  external speaker amp.  Useful mixer controls: `ESL-ASP Mono Volume` (0 now),
  `Ear Speaker Digital Playback Volume` (188), `Int Spk Switch` (on).
- Plain `tinyplay` on pcm0p and pcm2p leaves every DAPM widget Off
  (`/sys/kernel/debug/asoc/tegra-cs42l73/...`), i.e. the chain never powers -
  the ES325 front-end PCM needs configuration that the vendor HAL used to do.

Next step: write a small A9 audio HAL (`audio.primary.pisces.so`, the A9 HIDL
wrapper still accepts a legacy `audio_hw_device_t`) that opens the ALSA PCM
with tinyalsa, programs the ES325 route and the codec's ESL path, and enables
`spkr_en`.  The nvaudio_conf.xml route tables are the reference.

## Audio (session 5): 90% there, DMA pacing is the last issue

Working now:
- `audio.primary.pisces` (custom legacy HAL, tinyalsa) is loaded by the
  framework and the primary output opens.
- The ES325 firmware is loaded at boot by `audience_fw`/`vpcmd`, without it
  every es325 i2c write NAKs and the codec DAPM never powers.
- Speaker output is audible (sound settings, volume UI).
- The HAL no longer blocks/crashes audioserver: writer thread + ring buffer,
  lazy PCM open, XRUN recovery, `PCM_NORESTART`, 44.1 kHz everywhere.
- `AudioResampler` no longer aborts on a float track at a music rate.

Remaining: longer playback (ringtone preview) plays 2-3 s then stutters.
Measured:

- `i2s1` bit clock during playback is 1411200 Hz (44100*2*16) and `pll_a_out0`
  is 11289600 - the clock tree is right;
- a 10 s 44.1 kHz file through tinyplay returns in ~0.95 s while the PCM stays
  RUNNING, i.e. the DMA accepts data far faster than real time;
- through the HAL the writer completes ~10 writes of 8192 bytes per second
  (~92 KB/s) where 44.1 kHz stereo needs 176 KB/s, so the ring overflows and
  frames are dropped after 2-3 s;
- a 32-bit file instead blocks for minutes.

So the tegra PCM's period pacing (APB-DMA request sizing / period elapsed) does
not match the I2S rate.  Leads: `sound/soc/tegra/tegra_pcm.c`
(`setup_dma_tx_request`, `req->source_bus_width = 32` vs the PCM's 16-bit
samples, `dma_req[i].size = params_period_bytes`), the AHUB CIF bit-width
configuration in `tegra30_i2s_hw_params`, and `periods_max = 8` /
`period_bytes_max = PAGE_SIZE * 2` constraints (our 8192-byte period is exactly
at the maximum).


## USB tethering (session 6): the phone side works, the Settings switch does not

Verified working chain (log evidence):
- `IUsbGadget` HAL (`hardware/nvidia/pisces/usbgadget/`) maps the HAL function
  bits to the legacy android_usb sysfs; it must return `Status::SUCCESS`, not
  `FUNCTIONS_APPLIED`, or UsbDeviceManager logs "Setting default fuctions" and
  reverts immediately.
- adb and rndis cannot be composed on this kernel: with "adb,rndis" or
  "rndis,adb" `rndis0` is never created (tested both orders, dump in
  /data/local/tmp/combo.log).  The HAL therefore drops adb when rndis is
  requested, and adb returns when tethering stops.
- `config_ethernet_iface_regex` must be `(eth|rndis|usb)\\d` in the overlay XML -
  a single backslash is eaten by aapt and becomes a plain "d".
- `UsbHandlerHal` used to read /sys/class/android_usb/android0/state only in its
  constructor, so mConnected stayed false: every USB_STATE broadcast said
  "not connected", the USB preferences page stayed disabled and Tethering took
  the !usbConnected branch and actively UNtethered rndis0.  It now refreshes the
  state after each function switch; dumpsys usb shows connected=true.
- Tethering.java now drives the USB state from the rndis flag instead of
  usbConnected/usbConfigured (those do not settle on the legacy gadget).

Result: `rndis0` gets 192.168.42.129/24, its route is installed and it becomes
the default network (ConnectivityService "Switching to new default network").
The host sees a usbteth interface but needs to run a DHCP client on it.

Still broken:
- the Settings "USB tethering" switch flips back immediately.  It is driven by
  TetherSettings.updateUsbState() -> mCm.getTetheredIfaces() plus mUsbConnected,
  so the framework's tethered list or the connected flag is still not what the
  UI expects at that moment,
- the USB preferences page stays greyed for the same family of reasons
  (UsbBackend.getDataRole() / the UsbPortManager port).


## USB preferences / MTP (session 6, end state)

- USB preferences page: fixed. UsbBackend.updatePorts() only accepted a port
  whose UsbPortStatus.isConnected() was true; on this legacy gadget the port
  reports currentMode=MODE_NONE while dataRole=DEVICE, so the page stayed
  disabled. updatePorts() now also accepts a port whose current data role is
  DEVICE. Verified: "UsbPrefDbg: connected=true powerRole=2 dataRole=2".
- MTP: the AOSP path opens the FunctionFS control node /dev/ffs-mtp/ep0 while
  UsbDeviceManager starts; with the default composition being "adb" that node
  does not exist, nativeOpenControl() falls back to /dev/null and the MTP
  descriptors are written into the void. persist.sys.usb.config is now set to
  "mtp,adb" from init.pisces.rc's on boot (the old debugging block that forced
  "adb" has been replaced) so the ffs nodes exist from the start.
  Still to verify: sys.usb.config comes out as "adb" because the framework
  applies its own default after init; the ffs-mtp node therefore still does not
  appear. Next step is UsbHandlerHal/HAL side: enable the mtp function as part
  of the boot composition (or make nativeOpenControl retry once the function is
  enabled instead of falling back to /dev/null).

Debug logging still in place (search for TetherDbg / UsbPrefDbg) - remove before
shipping.


### MTP (session 7): WORKS - three bugs, all fixed

The host auto-mounts the phone and can list/read /storage/emulated/0 (gvfs:
17 entries, PNG read back with the right header).  adb and MTP run together
(18d1:4ee2, kernel functions `mtp,ffs`).

1. **`usbd` forced MTP-only at every boot.**  `system/core/usbd/usbd.cpp`
   starts from late_start and, unless `persist.sys.usb.config` is exactly
   `adb`, called `IUsbGadget.setCurrentUsbFunctions(MTP)`.  That ran ~1 s
   after init had installed `mtp,adb`, so every boot ended MTP only
   (idProduct 4ee1, adbd stopped): the "no adb, charging only" state.
   The daemon no longer touches the gadget; it only starts adbd when the
   persistent prop is `adb`.  `init.pisces.rc` owns the boot composition.
2. **The gadget HAL built the composition string in the wrong order.**
   MTP+ADB became `adb,mtp`; no init rule matches that spelling, so
   `sys.usb.state` never changed, the callback timed out and UsbDeviceManager
   reverted - which is why choosing "File transfer" never stuck.  It now emits
   `mtp,adb` / `ptp,adb` (data function first, adb last) and maps the kernel's
   `ffs` back to ADB in `getCurrentUsbFunctions`.
3. **adb was bound to the legacy f_adb function.**  f_adb waits for
   `/dev/android_adb` and keeps the whole gadget disabled until userspace opens
   it, while Android 9's adbd only knows FunctionFS (`/dev/usb-ffs/adb`).
   The init rules now bind `mtp,ffs` / `ptp,ffs` / `ffs` (TWRP's
   `/init.recovery.usb.rc` uses the same trick) and `start adbd`, while
   `sys.usb.state` still carries the name the framework requested.

MTP itself uses the legacy kernel driver: `MtpServer` picks `MtpDevHandle` when
`/dev/usb-ffs/mtp/ep0` is absent and opens `/dev/mtp_usb` (0660 root:mtp).
MediaProvider requests `android.permission.ACCESS_MTP`, which `platform.xml`
maps to gid `mtp`, so the media process gets group 1024 and can open it - no
extra chmod needed.  (The kernel's `mtp_file_range`/`mtp_event` ABI matches
`bionic/libc/kernel/android/uapi/linux/usb/f_mtp.h` exactly.)

Verified after flashing (2026-11-06 ~00:20):

- boot: `sys.usb.config=mtp,adb`, kernel functions `mtp,ffs`, 4ee2, adb up.
- after `boot_completed` the framework applies charging functions: adb-only
  (4ee7) - stock Android behaviour.  Selecting File transfer from the USB
  notification, or `svc usb setFunctions mtp`, gives 4ee2 again.
- `dumpsys usb`: connected=true configured=true current_functions=MTP
  applied=true kernel_state=CONFIGURED kernel_function_list=mtp,ffs.
- MtpService: "starting MTP server in MTP mode ... unlocked",
  "Adding MTP storage:/storage/emulated/0"; media process fd -> /dev/mtp_usb.
- PTP: 4ee6, MtpService restarts in PTP mode.
- charging only: 4ee7, functions=ffs.
- simulated cable pull (`echo disconnect > /sys/class/udc/tegra-udc.0/soft_connect`):
  the gadget re-enumerated by itself as 4ee7 ~9 s later and adb came back.

`/cache/usb-debug.txt` (written by `/system/etc/usbfix.sh`... actually by
`usbdebug.sh` at post-fs-data) dumps the gadget state each boot and is the
quickest way to see the composition without adb; `usbfix.sh` (which rewrote
`functions ffs` in a loop and fought the framework) has been removed from
init.pisces.rc.

## Session 8 (night): MTP end-to-end, WiFi hotspot, vibration, screen-on crash

All four were verified on the device (2026-11-06 ~01:00-02:30 host time).
Final image: `lineage_pisces-ota-eng.xie.zip` built 02:21, flashed and
re-verified: MTP write+read (md5 match both ways), hotspot in TetheredState
for 75 s+, vibration countdown, no system_server crash on screen on/off.
The hotspot was left running so it can be checked by hand.

Things to look at in the morning: plug the phone into a PC and copy a file
both ways; toggle the hotspot from Settings and connect a laptop to it
(it has no internet upstream because the modem/RIL is still broken); feel the
vibration when tapping the keyboard.

### MTP now transfers files in both directions

Building on the three fixes above, the last blocker was **permissions on the
storage root**: vold's `VolumeManager::linkPrimary()` runs
`fs_prepare_dir("/storage/emulated/0", 0755, AID_ROOT, AID_ROOT)` every time
user 0 starts.  On a normal device that path is a FUSE mount point and the
chmod is harmless; here it *is* the storage directory (/data/media/0, bind
mounted), so the storage root was root:root 0755 and MtpServer's SendObject
died with `Failed to create folder ...: Permission denied` (host saw EIO).
Changed to 0777 in `system/vold/VolumeManager.cpp`; `init.pisces.rc` also
re-applies 0777 at `boot_completed` as a safety net and dumps the mode to
`/cache/storage-mode.txt`.

Verified with the host's gvfs mount: `cp` 80 KB host->phone and 297 KB
phone->host, md5 identical both ways.

### WiFi hotspot works

Two independent blockers:

1. The wifi HAL ran as `wifi`; starting SoftAP makes it reconfigure the chip
   (mode 0 -> mode 1) and bring wlan0 up itself, which needs CAP_NET_ADMIN.
   3.4 has no ambient capabilities, so the vendor rc's
   `capabilities NET_ADMIN ...` is dropped by init:
   `Could not set interface flags for wlan0 (Permission denied)` ->
   `Failed to start legacy HAL` -> `configureChip error: 9`.
   init.pisces.rc now overrides the service with `user root`.
2. Android 9's netd installs every ip rule with a `FRA_UID_RANGE` attribute
   (modern uapi value 20).  The 3.4 kernel's FRA enum ended at
   `FRA_OIFNAME` (17), so `nlmsg_parse()` rejected the whole RTM_NEWRULE with
   -EINVAL: `Error adding IPv4 rule: Invalid argument` ->
   `addInterfaceToNetwork() failed` -> tethering reported
   IFACE_IP_MODE_CONFIGURATION_ERROR and `WifiServiceImpl` stopped the AP
   ("no LOHS requests, stop softap") - the 1-2 second on/off.
   Backported the attribute in `include/linux/fib_rules.h`,
   `include/net/fib_rules.h` and `net/core/fib_rules.c`: it is accepted,
   stored and dumped, but not matched (3.4 has no socket uid in the flow).
   The root-only rule that netd builds with uid 0..0 behaves exactly like the
   modern kernel there anyway.

Verified: `iw dev wlan0 info` = type AP, `192.168.43.242/24`, hostapd up,
`dumpsys connectivity` = `wlan0 - TetheredState`, dnsmasq listening on
0.0.0.0:67 and the AP address:53, stable for 2+ minutes.  (No upstream: the
modem is not working, so clients get an IP but no internet.)

### Vibration works (fixed in two steps)

Step 1 (session 8): the Immersion userspace stack cannot initialise
(`VibeDriverInitialize: Insufficient kernel parameters`, status -4), so the
HAL was rewritten to use the kernel's timed_output interface
(`/sys/class/timed_output/vibrator/enable`) - but the motor still did not move:
the timer counted down and nothing else happened.

Step 2 (session 9): the kernel driver is built with `DRV2604_USE_PWM_MODE 1`
and the timed_output path only does `pwm_duty_enable(pwm, 0)` - the stock ROM
always went through immvibed's force-sample stream, and that is what updated
the PWM duty, so with no samples the duty stayed 0.  The driver already has a
complete RTP path (`RTP=0x7F` + `MODE_REAL_TIME_PLAYBACK`, the DRV2604 runs
its closed-loop LRA drive, back to `MODE_STANDBY` on timeout), so
`drivers/tspdrv/ImmVibeSPI.c` now sets `DRV2604_USE_PWM_MODE 0`, and
`drv2604_set_rtp_val()` was moved out of the `#if SKIP_LRA_AUTOCAL == 0`
guard (this board is built with SKIP_LRA_AUTOCAL == 1, which used to hide it
and broke the compile).

Verified: dmesg shows `write 0x02, 0x7f` / `write 0x01, 0x05` on vibrate and
`write 0x01, 0x40` (standby) at the end; the motor actually vibrates.

The Immersion stack cannot initialise on this kernel:
`VibeDriverInitialize: Insufficient kernel parameters` / `status=-4` (libpparam
and the kernel driver disagree about the parameter set).  The kernel side is
fine: `/dev/tspdrv` reports `DRV2604 Rev:1 v5.0.36.6` and Xiaomi added a
timed_output interface.  The HAL (`hardware/nvidia/pisces/vibrator`) now
writes the timeout to `/sys/class/timed_output/vibrator/enable` exactly like
AOSP's default HAL, so no ImmVibe is involved.  `vibrator.pisces` had been
dropped from PRODUCT_PACKAGES at some point and the image still carried a
stale ImmVibe .so - it is back in PRODUCT_PACKAGES now.

Verified: writing 5000 shows the driver countdown (4985 -> 4454 -> ...),
`cmd vibrator vibrate 200` reaches VibratorService without errors.

### Screen-on system_server crash (this was killing the hotspot)

The device overlay forced `config_animateScreenLights=true`.  The color fade
takes a screenshot into a SurfaceTexture, the capture fails on this gralloc
(`RuntimeException: Error during updateTexImage` inside system_server) and the
whole system restarts on the first screen-on - which also tore down the
hotspot.  The overlay now keeps AOSP's default (false).  Three off/on cycles
in a row no longer crash.

### Camera: state after tonight

- The AVP idea is a dead end and was based on a misreading: stock is
  `CONFIG_TEGRA_AVP=n / TEGRA_MEDIASERVER=n / TEGRA_NVAVP=y`, and this kernel
  is the same.  `/dev/tegra_avpchannel` exists, nvavp allocated IOVM at
  0x0ff00000, and TRPC registered only `local`.  The `avp-remote` node would
  need `avp.c` plus the `nvrm_avp_*` firmware, which the device does not have
  (`/etc/firmware` has only `nvavp_os_0ff00000.bin` and the aud/vid ucodes).
- `tegra_v4l2_camera.c` (the only driver in this tree that would create
  /dev/video*) does **not compile** for T114: it is the Tegra 2/3 nvhost API
  (`struct nvhost_device` is an incomplete type on this kernel).  Enabling
  `CONFIG_VIDEO_TEGRA` (which needs `SOC_CAMERA`) breaks the build.
- What happens now: the wrapper enumerates 2 cameras, `NvCameraHal::
  setupHalInLock` fails with NvError 0x3 after ~5 s, `NvCameraHal:300`
  propagates it, and cameraserver's enumeration (it opens camera 0 to look
  for a flash unit) times out the same way.  strace of the provider shows
  `/dev/video0` and `/dev/video1` (ENOENT) and then /etc/nvcamera.conf; the
  blocking ioctl is `_IOC(WRITE, 'r', 0x30, 4)` = TEGRA_SEMA_IOCTL_WAIT on
  `/dev/tegra_sema`.

**Missing process groups (found tonight).**  The provider runs as
`user cameraserver`, `group audio camera input drmrpc wakelock`, but it also
needs:
  - `/dev/tegra_sema`, `/dev/tegra_rpc`, `/dev/tegra_avpchannel` - media:media
  - `/dev/knvmap` - system:system
  (`/dev/imx135`, `/dev/nvhost-vi`, `/dev/nvhost-isp` are media:camera, which
  the stock list already covers.)  Adding `media system` to the service makes
  the HAL get **much** further: the sensor powers up
  (`NvOdmImagerSetPowerLevel Level:3`), `ApplyChangedSettings()` runs, and the
  open takes ~1.8 s instead of failing at 5 s.  But then the NvMM block RPC
  **deadlocks**: 13 threads sit in tegra_sema_wait forever and the open never
  returns, wedging cameraserver and making every app that touches the camera
  hang.  That is why the group list is left at the stock value for now - a
  clean 0x3 after 5 s is better than a hung camera service.  The comment in
  `init.pisces.rc` records this.

- `/sys/kernel/debug/tegra_rpc/ports` shows two ports with both peers `local`
  and `ready: yes`, so the CPU-side TRPC pairing is fine; what never comes
  back is the block completion (the AVP side).  nvavp loads
  `nvavp_os_0ff00000.bin` and `nvavp_vid_ucode_alt.bin` (`nvavp_vid_ucode.bin`
  is absent, harmless); there is no separate nvmm daemon in the image, the
  blocks run inside the provider process.

Next camera experiments, in order: (1) understand why the block RPC never
completes on the AVP side (the deadlock above) - the group fix must come back
together with it; (2) check whether libnvmm expects an AVP-side block service
that `nvavp_os_0ff00000.bin` does not carry; (3) port `tegra_v4l2_camera.c`
to T114 (it is the Tegra 2/3 nvhost API and does not compile) as the fallback
capture path.

## Session 9: the camera deadlock was OUR OWN SHIM

The `NvCameraHal::setupHalInLock()` -> `pthread_join()` deadlock was not NvMM
at all: the joined thread is `SetupSensorListener` -> `NvSensorListener::
initialize()` -> `SensorEventQueue::read()`, and that call landed on
`libpisces_camshim.so`, which had been compiled into an infinite loop

    e84: e7fe   b.n e84      ; "b ." - two bytes and nothing else

because the shim defined the five `SensorEventQueue` methods with their real
mangled names and implemented them as
`static_cast<SensorEventQueue*>(self)->read(...)` - i.e. it called itself, and
clang turned that tail recursion into `b .`.  The sensor listener never
returned and 13 NvMM worker threads piled up behind it in `tegra_sema_wait`.

Fixes in `hardware/nvidia/pisces/shims/camshim.cpp`:
 - the five methods are plain stubs now (read -> 0 events, getFd -> a dead
   pipe read end, enable/disable/setEventRate -> 0): there is no sensors HAL,
   so there is nothing to forward to;
 - `SensorManager::getDefaultSensor` returns NULL instead of asking the real
   SensorManager (which blocked forever in
   `ServiceManager::getService("sensorservice")`);
 - the dummy queue is kept alive by a static `sp<>` - the HAL drops its
   reference on close and RefBase then deleted the static object (provider died
   in ifree()).

With those three the HAL really opens the sensor and
`CameraProviderManager` reports "Camera provider legacy/0 ready with 1 camera
devices".

## Session 10: single-open drivers and framework probes

The 2013 HAL keeps its /dev nodes open across a camera close, so after the
first session every later open failed with -EBUSY.  Reference-counted the
open in: `imx135.c` (/dev/imx135), `ad5823.c` (/dev/focuser - this board uses
ad5823, not dw9718), `max77665-flash.c` (/dev/torch) and
`drivers/video/tegra/camera/camera.c` (/dev/tegra_camera, the VI/CSI control
node); `t114.c` no longer marks the VI channel `.exclusive`
(`Opening channel /dev/nvhost-vi (0xc) failed` / 0x30012).

Two framework probes opened the camera before any app did and left the HAL
state dirty; both are disabled now:
 - `CameraProviderManager`'s DeviceInfo1 "open to check for a flash unit";
 - `CameraFlashlight::findFlashUnits()` (it reports a flash for the rear
   camera without touching the device).

`frameworks/av/.../CameraService.cpp`: the "cameraserver" API1 client seen at
every boot (`initializeShimMetadata`) is the legacy-parameter probe.  Its
parameter blob is now cached in
`/data/misc/cameraserver/pisces_shim_params.txt` and served from there on
later boots, so the camera is not opened before the app opens it.  The first
boot after a flash still pays the probe and writes the cache.

The wrapper owns the camera list: `camera_get_number_of_cameras()` returns 2
and `camera_get_camera_info()` reports back (id 0, orientation 90) and front
(id 1, orientation 270) directly - calling the vendor's versions powered the
ODM imager at enumeration and never released it.

## Session 11: both cameras take photos

Verified on the device: rear IMX135 photos (3120x4208 JPEG) and front IMX132
photos (1080x1920) - `/sdcard/DCIM/Camera/`.  Preview goes through the MDP
overlay path, so `screencap` returns an empty file while previewing; use the
shutter and look at the file.

Remaining camera issue: **only the first session in a provider process
works**.  The HAL's module-global state (focus motor `NvFmInitialize: Failed
already initialized`, NvMM block container, VI/CSI bookkeeping) is not reset
by its close path, so reopening the app or switching rear<->front failed with
0xd (the front after the rear: 30 s black preview, green uninitialised
frames, app crash).  `dlclose()`ing the vendor module does not help - the
linker keeps it mapped.  The fix in the next build: the wrapper kills the
provider right after every camera close (`fork()` + `sh -c "sleep 0.15;
kill -9 $PPID"`; init restarts the service automatically) and
`CameraProviderManager::openSession()` retries the open for ~4 s while the
fresh provider registers.

## Session 12: torch and headphones

**Torch**: works.  `max77665-flash` exposes the LED as
`/sys/class/leds/flashlight`; `CameraFlashlight::setTorchMode()` writes it
directly (0/255) instead of opening the camera, and `findFlashUnits()` claims
the flash for the rear camera without probing.

**Headphones**: work (sound comes out of the headset, jack recognised).  The
chain:
 1. `TEGRA_GPIO_HP_DET` is -1 on this board, so the kernel never reported the
    jack.  The FSA8108 switch driver (`drivers/input/misc/fsa8108.c`, event6)
    does see the plug; it now publishes the h2w state through the new
    `tegra_asoc_utils_set_h2w_state()` export
    (`sound/soc/tegra/tegra_asoc_utils.c`), i.e. `/sys/class/switch/h2w`
    (0 = unplugged, 1 = headset, 2 = headphones).
 2. `WiredAccessoryManager` then connects AUDIO_DEVICE_OUT_WIRED_HEADSET.
 3. `device/xiaomi/pisces/audio/audio_policy_configuration.xml` was missing
    the wired ports ("could not find HW module for device 00000004"); it now
    declares Wired Headphones / Wired Headset / Wired Headset Mic + routes.
 4. The HAL (`hardware/nvidia/pisces/audio/audio_hw_pisces.c`) implements
    `get_supported_devices()`, parses `routing=<hex>` and applies the
    headphone mixer route (Int Spk Switch 0, Headphone Switch 1, Headphone
    Analog Playback Volume 63/63).

## Session 13: front camera after a rear session

The front sensor (IMX132) works when it is the *first* session of a provider
process: it streams and takes 1080x1920 photos.  After a rear (IMX135) session
the same sensor is opened successfully - `imx132_set_mode ... stream on.` is
visible in dmesg - but the VI/CSI never delivers frames: `Camera Timeout: Sync
Point ID: 15` (csi_vi_0), `AlterSyncPointStatus failed`, black preview, then
uninitialised (green) buffers and a Snap crash.  The HAL's VI/CSI routing for
the previous sensor is not reprogrammed, and unloading the vendor module in
process does not help (the linker keeps it mapped).

Current approach: the camera wrapper kills the provider right after every
camera close (`fork()` + `sh -c "sleep 0.15; kill -9 $PPID"`; init restarts the
service automatically) and `CameraProviderManager::openSession()` retries the
open for ~4 s while the fresh provider registers.  A switch therefore costs
one or two seconds but the front sensor gets the same "first session" state in
which it does stream.  `CameraClient::getParameters()` falls back to
`/data/misc/cameraserver/pisces_shim_params.txt` while the client has no
hardware, which avoids the "getParameters failed (empty parameters)" crash
that the restart used to cause.

## Session 14: where the rear/front switch stands

What is verified working on the device (2026-11-06):

- rear camera (IMX135): preview, autofocus, still capture (13 MP JPEG);
- front camera (IMX132): opens, streams and captures (1080x1920 JPEG) when it
  is the first camera session of a provider process;
- torch/flashlight (LED sysfs), wired headset audio + jack detection;
- MTP both directions, WiFi hotspot, vibration, screen on/off.

The rear<->front switch is the last open item.  Everything up to the frame
delivery works:

  Closing camera 0 -> provider restart -> camera service retries the open ->
  Opening camera 1 -> IMX132 set_mode + "[imx132]: stream on." in dmesg.

(`CameraProviderManager::openSession()` retries for ~4 s while the restarted
provider registers, and `CameraClient::getParameters()` serves the cached blob
while the client has no hardware, so neither the open nor getParameters fail
any more.)

What still fails is the app side of the switch: right after the provider
restart Snap logs `setPreviewDisplay failed, surface is destoried`, closes the
freshly opened front camera and then falls back to the cached parameters, so
the user sees a black preview followed by uninitialised (green) buffers.

Root cause of the whole family of problems: the 2013 HAL never releases what
it opens.  After a session the provider still holds /dev/imx135,
/dev/focuser, /dev/torch, /dev/tegra_camera and the nvhost VI channel (and
the VI channel therefore shares its hardware context with the next session,
which is why the front's CSI never produces frames when it reuses the rear's
channel).

Next ideas, in order:
1. Close the HAL's leaked fds from the wrapper after each close (the wrapper
   does not know them, but /proc/self/fd can be scanned for the vendor nodes
   and closed - that would free the VI channel and the sensor, giving the next
   session a genuinely fresh HAL without restarting the provider).
2. Keep the restart but avoid the race: make the camera service hold the
   client's preview surface across the provider restart, or delay the kill
   until the next open is requested.
3. Instrument `NvCameraViCsiInitializeChannel` (the blob) to see whether it
   skips the CSI programming when the channel is already initialised.

## Session 15: what the rear/front switch still needs

Everything up to frame delivery is in place and verified on the device:

  close -> leaked descriptors closed (/dev/imx135, imx132, focuser, torch,
  nvhost-vi, nvhost-isp, tegra_camera, mipi-cal, tegra_sema) -> vendor open
  (retried if it comes back 0xd) -> per-sensor parameter blob cached in
  /data/misc/pisces_camera/params_<id>.txt -> framework configured with the
  right sizes -> set_parameters applied asynchronously so the framework is not
  blocked by the HAL's WaitForStuffThatNeedsPreview() -> preview requested.

What still fails is the front sensor's capture path after a rear session:
`imx132_set_mode` and `[imx132]: stream on.` appear in dmesg, but the VI/CSI
produces no frames (`Camera Timeout: Sync Point ID: 15` / `csi_vi_0`,
`AlterSyncPointStatus failed`), so the preview shows black and then
uninitialised (green) buffers and Snap dies.  The same front sensor streams
correctly when it is the first session of a provider process.

Things already tried and their outcome (do not repeat blindly):
- unloading the vendor module in-process: the linker keeps it mapped, no effect;
- restarting the provider after each close: gives the front a working stream
  (first-session state) but races the framework - Snap logs
  "setPreviewDisplay failed, surface is destoried" and closes the camera;
- closing the leaked descriptors + retrying the vendor open: the open and the
  parameter exchange succeed, the stream still does not;
- async set_parameters: removes the framework deadlock (verified), but does not
  make the CSI deliver frames.

Next ideas, in order:
1. Find out why the HAL does not reprogram the CSI for the second sensor.  It
   is not the kernel (the tegra_camera power/clocks are re-initialised on
   every open and the sensor is streaming); it is the VI/CSI routing inside
   the blob.  Instrumenting nvhost (command stream / syncpoint) or comparing
   the first-session and switch command buffers would show what is skipped.
2. Or: make the camera app survive the provider restart (the framework's
   surface race) - e.g. keep the preview surface alive across the switch - and
   use the restart path, which is known to give a working front stream.

## Session 16: channel lifecycle ruled out

Instrumented nvhost (`nvhost_getchannel` / `nvhost_putchannel`) and observed a
rear->front switch:

    nvhost_getchannel(vi): ok refcount=1 (was 0)
    nvhost_getchannel(isp): ok refcount=1 (was 0)
    vi vi: tegra_camera_open: ++
    ...front session...
    nvhost_putchannel(vi): refcount=1 ctx= (null)

i.e. the VI and ISP nvhost channels are acquired from refcount 0 and released
again for every camera session - a rear->front switch really does get a fresh
channel, fresh cdma and a fresh hwctx.  The missing frames are therefore not a
stale channel/context: the sensor streams (imx132 set_mode + stream on) but the
CSI never raises the frame syncpoint (id 11, csi_vi_0).

What that leaves, in order of likelihood:
1. The CSI (MIPI) receiver is not (re)programmed for the front sensor's lane
   configuration/mode.  `NvCameraViCsiInitializeHw` in libnvmm_camera.so is the
   function to reverse (it is internal to the blob; none of the NvCameraViCsi*
   symbols are exported, and the "already initialized" guard is the shared
   "%s: Failed already initialized!!!" string at .rodata 0x87f64).
2. The front sensor's mode the HAL picks for preview is not the one the CSI is
   configured for (compare the CSI registers/commands of a working
   first-session front capture with a switch).

## Session 17: the blob is now observable

Two things came out of this session that are worth keeping:

1. **The HAL's internal log is now visible.**  `libpisces_camshim` defines the
   imported `NvOsDebugPrintf` and forwards it to logcat with the tag `NvOsDbg`.
   Because the shim is loaded RTLD_GLOBAL before the vendor module, every
   message the blob prints (VI/CSI state machine, "already initialized"
   guards, sync point waits, block container errors) shows up there:

       adb logcat -s NvOsDbg:D

   That is what made the next finding possible.

2. **The "no frames" symptom is a sync point wait failure, and
   /dev/tegra_sema must NOT be closed.**  The blob waits for the CSI sync
   point through semaphores obtained from /dev/tegra_sema; closing those
   descriptors in the wrapper's cleanup made every wait fail with
   `Waiting on the syncpt semaphore failed 0x30003` and the VI never saw a
   frame.  With the semaphores kept, the front session now initialises
   cleanly (`NvOdmImagerSetPowerLevel Level:3`,
   `NvCamGranger HAL_camera_device_enable_msg_type 3`), but the CSI still does
   not advance sync point 15 (`Camera Timeout: Sync Point ID: 15`).

So the remaining defect is squarely in the CSI programming inside
libnvmm_camera.so: `NvCameraViCsiInitializeHw` (and `NvCameraViCsiCtrl*`)
decide the CSI lane/mode configuration, and after a rear session they do not
reprogram it for the front sensor.  With NvOsDbg logging the next step is to
diff the message sequence of

  (a) a working first-session front capture, and
  (b) a rear->front switch

and then, if needed, disassemble `NvCameraViCsiInitializeHw`.  Note the blob's
NvCameraViCsi* symbols are all internal (not exported) and the shared
"already initialized" guard string lives at .rodata 0x87f64.

## Session 18: blob RE toolkit

Reversing libnvmm_camera.so needed two corrections:

1. **Its .text is Thumb-2, not ARM.**  `objdump -d` disassembles it as ARM by
   default and every literal-pool search misses; always use
   `arm-linux-androideabi-objdump -d -M force-thumb`.
2. **The NvCameraViCsi\* symbols are internal and their strings are not
   reachable** through .rel.dyn, PC-relative loads or .data.rel.ro tables.
   The working approach is dynamic: camshim dumps `android::CallStack` for
   every ViCsi / "already initialized" / syncpt message, and the frames give
   the addresses (offsets inside libnvmm_camera.so).

First result from that: the focus-motor guard that produces
"NvFmInitialize: Failed already initialized!!!" and error 0xd is at
**libnvmm_camera.so 0x380e8**:

    380ee: ldr  r4, [r5, #0]     ; static pointer
    380f0: cbz  r4, 38102        ; NULL -> allocate 8 queues and continue
    380f2: ...                   ; non-NULL -> "already initialized", r5 = 13
    38084: (0xd = 13)            ;   and return 0xd

Its teardown counterpart is at 0x380b8 (destroys the 8 queues, NvOsFree,
stores NULL).  The call path is
NvMMCameraOpen+1016 (0x9465) -> 0x7e13 -> 0x11ad9 -> 0x380fd.  A rear->front
switch often hits the guard on the first open attempt and only succeeds on the
wrapper's retry.

The front sensor's stream behaviour after a rear session is now known exactly:
the CSI never raises sync point 15, the HAL waits 5.2 s, then
`NvCameraViCsiFixFence` forces the sync point to the threshold ("syncpt
incremented to 621 (threshold 621)") - which is the green frame the user sees -
and Snap's own 5 s preview timeout aborts the session.

## Session 19: register-level view

camshim now also intercepts `NvRmHostModuleRegWr/RegRd` (libnvrm_graphics.so),
i.e. every host1x register access the blob makes (tags `NvRmReg`, module 11 =
VI, module 12 = ISP).  Measured with identical capture windows:

| session | register writes |
|---|---|
| front as first session | 91 writes, all `mod=11 off=0x5c8 val=0` (a ~50 ms handshake/keepalive) |
| rear->front switch | 26 writes: 14x the same VI 0x5c8 plus 12 writes to ISP 0x928/0x930/0x938/0x894/0x89c |

Consequences for the next attempt:

1. The sensor-specific VI/CSI programming is **not** done through the direct
   register accessors - the only VI register touched in a working session is
   the 0x5c8 handshake.  It must go through the VI channel's command buffer
   (submitted to the hardware), so the next instrumentation point is the
   nvhost channel submission (kernel side, `nvhost_cdma` / the job submit
   ioctl) or `NvRmChannelOpen`/the channel mmap.
2. The ISP registers written during a switch (0x928/0x930/0x938) are the ones
   to look at first: they are the ISP stream enable path, and a working
   first-session front preview never touches them.

The blob's FM guard (0x380e8) and the FixFence fake-increment path (0x52594)
are documented above; both are now fully understood.

## Next steps

1. Camera: flash the build with the provider-restart + openSession retry and
   verify reopen and rear<->front switching.
2. Front camera quality/exposure tuning (it works but is a 2 MP sensor).
3. Sensors HAL (still none; camshim stubs the sensor entries).
4. Baseband/RIL and GPS daemon (libril/linker issues).
5. Gate the nvmap debug prints.


1. Camera (the only big open item): find the 5 s wait inside
   `NvCameraHal::setupHalInLock` (timestamped strace of the provider, then
   check whether the AVP OS image carries the camera block service).  See the
   session-8 notes for the experiments.
2. WiFi STA: never actually connected to an AP - try it now that netd can
   install rules (the same FRA_UID_RANGE fix was blocking the STA network
   setup).
3. Sensors HAL (there is none, which is why the camera wrapper had to skip the
   sensorservice wait).
4. Baseband/RIL: `ril-daemon` and `gps-daemon` start and exit; `ro.boot.modem=sprd`
   and `libril-xiaomi-sprd.so` exist.
5. Gate the nvmap debug prints.
6. The GPS daemon (`glgps_nvidiaTegra2android`) still fails to link against
   `SensorManager::sInstance` - it needs the same shim treatment as the camera
   if GPS is wanted.

## Repo commits (latest)

- device `lineage-16.0`: `343c380` four cores online, `b0b76e6` wifi/bt/camera
  + nvmap leak fix, `4e3118e` camera app + bounded sensorservice wait.
- kernel `lineage-16.0-113`: `e3d9500e`.
- patches: 0026 (gralloc free), 0027 + `hardware-overlay/` (camera wrapper and
  shim). 0023-0025 still need to be applied by hand (they span projects).
