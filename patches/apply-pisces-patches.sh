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
echo "all patches applied"
