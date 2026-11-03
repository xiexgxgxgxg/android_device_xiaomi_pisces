#!/bin/bash
# Apply the Android 9 source patches needed by the pisces 3.4 kernel.
# Run from the root of the LineageOS 16.0 tree after repo sync.
set -e
ROOT=$(cd "$(dirname "$0")/../../../.." && pwd)
P="$ROOT/device/xiaomi/pisces/patches"
cd "$ROOT"
for p in 0001-system-core-init-selinux.patch:system/core \
         0002-bionic-property-area.patch:bionic \
         0004-hwservicemanager-skip-selinux.patch:system/hwservicemanager \
         0005-servicemanager-skip-selinux.patch:frameworks/native \
         0006-gles-log-missing-entry.patch:frameworks/native \
         0007-zygote-skip-selinux-setcontext.patch:frameworks/base \
         0008-contextimpl-create-db-dir.patch:frameworks/base \
         0009-installd-skip-restorecon.patch:frameworks/native \
         0010-netd-break-zygote-loop.patch:system/netd \
         0011-alarm-timerfd-fallback.patch:frameworks/base; do
    patch="${p%%:*}"; dir="${p##*:}"
    echo "== $patch -> $dir"
    git -C "$dir" apply "$P/$patch"
done
echo "all patches applied"
