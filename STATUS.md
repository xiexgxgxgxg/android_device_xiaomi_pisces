# LineageOS 16.0 (pisces) bring-up status

Updated: 2026-11-04 06:35

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

## Current blockers (2026-11-04 06:35)

1. zygote <-> netd restart loop: netd and zygote each had `onrestart restart`
   on the other; once one died the whole framework reset every ~10 s.
   Patch 0010 removes netd -> zygote (zygote -> netd kept).
   Not yet verified on device (device was stuck in recovery with adb offline).
2. audioserver crash loop (`AudioFlinger::loadHwModule_l`, missing audio HAL).
3. gps-daemon exits immediately: `glgps_nvidiaTegra2android` needs
   android::Singleton<SensorManager>::sInstance (removed in A9).
4. /data/user_de/0 keeps being emptied by the missing vold; current workaround
   is ContextImpl creating the databases dir on demand (patch 0008).
5. vold does not run at all (open issue).

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

Apply with `patches/apply-pisces-patches.sh` after repo sync.

## Next steps

- Verify patch 0010 on device (reboot, watch `sys.boot_completed`)
- Fix audioserver: build android.hardware.audio@2.0 service or stub the HAL
- Fix gps-daemon: shim SensorManager::sInstance or drop the binary
- Make vold work (fstab/userdata) or keep patching around it
- Then UI/launcher, display composition and hardware feature bring-up
