#!/bin/bash
# Apply the Android 9 source patches needed by the pisces 3.4 kernel.
# Run from the root of the LineageOS 16.0 tree after repo sync.
set -e
ROOT=$(cd "$(dirname "$0")/../../../.." && pwd)
P="$ROOT/device/xiaomi/pisces/patches"
cd "$ROOT"
apply() {
    local patch="$1" dir="$2"
    echo "== $patch -> $dir"
    git -C "$dir" apply "$P/$patch"
}
apply 0001-system-core-init-selinux.patch            system/core
apply 0002-bionic-property-area.patch               bionic
apply 0004-hwservicemanager-skip-selinux.patch      system/hwservicemanager
apply 0005-servicemanager-skip-selinux.patch        frameworks/native
apply 0006-gles-log-missing-entry.patch             frameworks/native
apply 0007-zygote-skip-selinux-setcontext.patch     frameworks/base
apply 0008-contextimpl-create-db-dir.patch          frameworks/base
apply 0009-installd-skip-restorecon.patch           frameworks/native
apply 0010-netd-break-zygote-loop.patch             system/netd
apply 0011-alarm-timerfd-fallback.patch             frameworks/base
apply 0012-skip-audioservice.patch                  frameworks/base
apply 0013-audio-player-state-null.patch            frameworks/base
apply 0014-mediarouter-null-audio.patch             frameworks/base
apply 0015-media-router-service-null-audio.patch    frameworks/base
apply 0016-soundtrigger-bounded-wait.patch          frameworks/av
apply 0017-audiomanager-ringer-null.patch           frameworks/base
apply 0018-sdcard-bind-fallback.patch               system/core
apply 0020-vold-accept-bind-emulated.patch          system/vold
apply 0021-keystore-state-soft.patch               frameworks/base
apply 0022-wifi-dataintegrity-keystore.patch        frameworks/opt/net/wifi
# 0023-0025 were applied by hand (they touch several projects at once);
# 0026 is the gralloc free path that goes with 0023.
apply 0026-gralloc-free-local-alloc.patch           frameworks/native
# 0025/0027 only touch hardware/nvidia, which is our own repository
# (github.com/xiexgxgxgxg/android_hardware_nvidia) and already carries both
# changes on every sync, so they are informational: try them, skip if present.
patch -p1 -d "$ROOT" --forward --batch -i "$P/0027-camera-wrapper-a9-headers.patch" >/dev/null 2>&1 \
    || echo "0027 already in hardware/nvidia, skipped"
# 0028 is applied automatically at configure time by av-overlay/ (see
# BoardConfig.mk), so this is only a fallback; skip it when already applied.
patch -p1 -d "$ROOT/frameworks/av" --forward --batch \
    -i "$P/0028-frameworks-av-camera-provider-restart.patch" >/dev/null 2>&1 \
    || echo "0028 already applied by av-overlay, skipped"
apply 0029-system-core-init-charger-mode.patch     system/core
# 0028-0039 record the work that had been left uncommitted in the tree
# (camera provider restart, charger mode, dual-provider camera HIDL, and the
# MTP/hotspot/vibration/USB fixes of the earlier sessions).
apply 0030-wpa-supplicant-nl80211-mac.patch         external/wpa_supplicant_8
apply 0031-bt-vendor-libbt.patch                    hardware/broadcom/libbt
apply 0032-bcmdhd-wifi-hal.patch                    hardware/broadcom/wlan
apply 0033-camera-dual-provider-providers.patch     hardware/interfaces
apply 0034-settings-tethering-usb.patch             packages/apps/Settings
apply 0035-libchrome-include-generator.patch        external/libchrome
apply 0036-frameworks-base-tethering-usb.patch      frameworks/base
apply 0037-rild-blocking.patch                      hardware/ril
apply 0038-usbd-fixes.patch                         system/core
apply 0039-vold-volume-manager.patch               system/vold
# 0040: the composer service must not unregister a 2013 nvgralloc handle twice
# (NvGrUnregisterBuffer dereferences a process-local pointer whose page was
# already unmapped, killing SurfaceFlinger -> soft reboot).
apply 0040-gralloc0-no-unregister.patch            hardware/interfaces
# 0041/0042: the 3.4 kernel cannot load the Android 9 SELinux policy, so no
# filesystem supports security.* xattrs and every restorecon fails with
# EOPNOTSUPP.  Tolerate that in the framework, otherwise every app install dies
# with "Failed to restorecon session dir" / "INSTALL_FAILED_INSUFFICIENT_STORAGE".
apply 0041-frameworks-base-selinux-restorecon.patch  frameworks/base
apply 0042-frameworks-base-packageinstaller-restorecon.patch frameworks/base
# 0043: Settings > System > Buttons -> "Enable on-screen nav bar" already adds
# the software navigation bar (LineageSettings.System.FORCE_SHOW_NAVBAR), but the
# capacitive menu/home/back keys kept working.  Swallow them while the switch is
# on, so virtual keys and hardware keys are mutually exclusive.
apply 0043-frameworks-base-navbar-hwkeys.patch          frameworks/base
echo "all patches applied"
