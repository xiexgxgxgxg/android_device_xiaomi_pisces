# LineageOS 16.0 (pisces) bring-up status

Updated: 2026-11-04 01:35

## Working

- Kernel 3.4.113 (merged) with:
  - binder multi-device contexts (binder/hwbinder/vndbinder)
  - BC_TRANSACTION_SG / BC_REPLY_SG + BINDER_TYPE_PTR (A9 hwbinder)
  - PR_SET_NO_NEW_PRIVS (tombstones work)
- HIDL: hwservicemanager + composer@2.1 register successfully
- HWC1 (`hwcomposer.tegra`) loads through HWC2On1Adapter, panel 1080x1920 detected
- SurfaceFlinger initializes EGL (Tegra GLES driver loads with the legacy shims)
- zygote forks system_server (`boot_system_server_init` reached)
- shims: libshim_atomic (atomics), legacy GraphicBuffer ctor, DT_NEEDED patched blobs

## Current blockers

1. **SurfaceFlinger never registers** with servicemanager: it hangs inside
   graphics H/W init right after the composer service becomes ready.
   bootanimation then waits (`Waiting for SurfaceFlinger, waited for >100 s`)
   and init eventually reboots.
2. **No adb / USB enumeration**: adbd starts, `/dev/usb-ffs/adb` is mounted,
   `sys.usb.config=adb` action runs, but the gadget never enumerates on the host.
3. audioserver crash-loops in `AudioFlinger::loadHwModule_l` (legacy audio HAL
   vs Android 9).

## Source patches (device/xiaomi/pisces/patches)

- 0001 system/core init: skip SELinux policy load
- 0002 bionic: property area init ignores setxattr failure
- 0004 hwservicemanager: allow all when no policy
- 0005 servicemanager: allow all when no policy
- 0006 frameworks/native: log missing GL/EGL entry points
- 0007 frameworks/base: zygote setcontext failure non-fatal

Apply with `patches/apply-pisces-patches.sh` after repo sync.

## Next steps

- Instrument SurfaceFlinger init to find the exact hang
  (frameworks/native/services/surfaceflinger).
- Compare USB gadget sysfs writes with TWRP (which enumerates fine on the same
  kernel); check `/sys/class/udc/*/state` and `android_usb` `state` file.
- Replace the legacy audio HAL with `android.hardware.audio@2.0` wrapper or
  disable audio until boot completes.
