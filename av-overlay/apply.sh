#!/bin/bash
#
# Copy the pisces frameworks/av changes into the source tree.
#
# The camera service fixes (provider restart tolerance, torch through sysfs,
# cached parameters, plus the audio/soundtrigger workarounds that came with the
# same bring-up) live in this directory instead of a forked or patch-updated
# frameworks/av checkout, so "repo sync && mka" still produces our build.
#
# Invoked at configure time from BoardConfig.mk; idempotent (only copies files
# whose content differs, so an already-correct tree is not touched).
#
#   apply.sh <top-of-tree>
#
set -u
root=${1:-/data/lineage-16.0}
av=$root/frameworks/av
overlay=$(cd "$(dirname "$0")" && pwd)

[ -d "$av" ] || exit 0

copied=0
while IFS= read -r f; do
    rel=${f#"$overlay"/}
    dst=$av/$rel
    if [ ! -f "$dst" ] || ! cmp -s "$f" "$dst"; then
        mkdir -p "$(dirname "$dst")"
        cp -f "$f" "$dst" || { echo "pisces: av-overlay: failed to update $rel" >&2; exit 1; }
        echo "pisces: av-overlay: updated frameworks/av/$rel"
        printf '%s pisces: av-overlay: updated frameworks/av/%s\n' \
            "$(date '+%Y-%m-%d %H:%M:%S')" "$rel" >> "$root/out/pisces-av-overlay.log" 2>/dev/null
        copied=$((copied + 1))
    fi
done < <(find "$overlay" -type f ! -name 'apply.sh' ! -name 'README.md')

if [ "$copied" -gt 0 ]; then
    # Force the affected modules to rebuild: their sources are current, but
    # ninja only notices the change through the file mtimes we just updated.
    echo "pisces: av-overlay: $copied file(s) copied from device tree"
fi
exit 0
