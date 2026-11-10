#!/bin/bash
# Apply the complete pisces source changes as recorded on 2026-11-10.
# Run from a clean LineageOS 16.0 tree:  bash apply-pisces-snapshot.sh
# These are consolidated diffs of the exact tree the working OTA was built from.
set -e
ROOT=$(cd "$(dirname "$0")/../../../.." && pwd)
P="$ROOT/device/xiaomi/pisces/patches"
cd "$ROOT"
apply() {
    local patch="$1" dir="$2"
    [ -f "$P/$patch" ] || { echo "missing: $patch"; return 0; }
    echo "== $patch -> $dir"
    git -C "$dir" apply --whitespace=nowarn "$P/$patch"
}
apply SNAP-hardware_interfaces.patch  hardware/interfaces
apply SNAP-frameworks_native.patch    frameworks/native
apply SNAP-frameworks_base.patch      frameworks/base
apply SNAP-frameworks_av.patch        frameworks/av
apply SNAP-system_core.patch          system/core
apply SNAP-packages_apps_Snap.patch   packages/apps/Snap
echo "== done"
