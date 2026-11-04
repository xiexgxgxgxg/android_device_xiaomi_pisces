# LineageOS 16.0 (pisces) bring-up status

Updated: 2026-11-04 08:10

## Working

- Kernel 3.4.113 (merged) with:
  - binder multi-device contexts (binder/hwbinder/vndbinder)
  - BC_TRANSACTION_SG / BC_REPLY_SG, BINDER_TYPE_PTR **and BINDER_TYPE_FDA**
  - PR_SET_NO_NEW_PRIVS (tombstones work)
  - CONFIG_NVMAP_ALLOW_SYSMEM=y (gralloc allocations succeed)
- USB/adb: reliable, with self-healing gadget (usbfix) in both system and TWRP;
  3 system<->recovery cycles verified at 02:42
- HIDL: hwservicemanager, composer@2.1, allocator@2.0 register
- HWC1 (`hwcomposer.tegra`) via HWC2On1Adapter, panel 1080x1920 detected
- EGL/GLES: Tegra driver loads (egl.cfg + ro.hardware.egl + exact lib names +
  legacy libstdc++ + atomic/GraphicBuffer shims)
- SurfaceFlinger: EGL init OK and framebuffer buffers allocate
- Boot animation starts (`BootAnimationShownTiming start`)
- system_server: InstallSystemProviders OK, starts services up to
  AlarmManager/ConsumerIr, then has to be looked at further
- installd: restorecon made a no-op (no policy on 3.4)

## Current blockers (2026-11-04 08:10)

1. Audio HAL missing: A9 audioserver loads the legacy audio_policy blob and
   crashes, so media.audio_policy never appears. AudioService is currently
   skipped (patch 0012) and audio clients get null IAudioService
   (patches 0013/0014 guard two of them).
2. Boot now reaches MediaProjectionManagerService but MediaRouter's
   IMediaRouterService is still null at that point (wrong/blocked service
   ordering), so system_server aborts there. Either start MediaRouterService
   earlier, make MediaRouter tolerate null, or provide a stub IAudioService /
   real audio HAL so the normal ordering is restored.
3. gps-daemon exits immediately: `glgps_nvidiaTegra2android` needs
   android::Singleton<SensorManager>::sInstance (removed in A9).
4. vold does not run at all; /data/user_de/0 is populated ad hoc by
   ContextImpl (patch 0008).
5. Kernel lacks timerfd CLOCK_*_ALARM/CLOCK_BOOTTIME support; AlarmManager
   now falls back to CLOCK_REALTIME/MONOTONIC (patch 0011). Alarms cannot
   wake the device from suspend until the kernel side is backported.

## Source patches (device/xiaomi/pisces/patches)

- 0001 system/core: skip SELinux policy load
- 0002 bionic: property area ignores setxattr failure
- 0004 hwservicemanager: allow all without policy
- 0005 frameworks/native servicemanager: allow all without policy
- 0006 frameworks/native: log missing GL entries
- 0007 frameworks/base: zygote setcontext failure non-fatal
- 0008 frameworks/base: ContextImpl creates databases dir
- 0009 frameworks/native installd: restorecon is a no-op
- 0010 system/netd: do not restart zygote from netd
- 0011 frameworks/base AlarmManager JNI: timerfd alarm clock fallback
- 0012 frameworks/base SystemServer: allow skipping AudioService
- 0013 frameworks/base AudioPlayerStateMonitor: tolerate null AudioService
- 0014 frameworks/base MediaRouter: tolerate null AudioService

Apply with `patches/apply-pisces-patches.sh` after repo sync.

## Next steps

- Provide a stub IAudioService (or a working audio HAL) so MediaRouterService,
  MediaProjectionManagerService and others see a non-null audio service.
- Check why IMediaRouterService is not published when MediaProjectionManager
  starts (service ordering / earlier failure).
- Backport timerfd CLOCK_*_ALARM + CLOCK_BOOTTIME to the 3.4 kernel, then drop
  the AlarmManager fallback.
- Fix gps-daemon (SensorManager::sInstance shim) and vold.
- Then launcher/UI, composition, camera/sensors/wifi bring-up.
