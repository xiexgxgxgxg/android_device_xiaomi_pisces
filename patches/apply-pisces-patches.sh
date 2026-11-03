#!/bin/bash
# Apply the Android 9 source patches needed by the pisces 3.4 kernel.
# Run from the root of the LineageOS 16.0 tree after repo sync.
set -e
ROOT=$(cd "$(dirname "$0")/../../../.." && pwd)
cd "$ROOT"
echo "applying 0001-system-core-init-selinux.patch"
git -C system/core apply "$OLDPWD/0001-system-core-init-selinux.patch" 2>/dev/null || \
    git -C system/core apply "device/xiaomi/pisces/patches/0001-system-core-init-selinux.patch"
echo "applying 0002-bionic-property-area.patch"
git -C bionic apply "device/xiaomi/pisces/patches/0002-bionic-property-area.patch"
echo "done"
