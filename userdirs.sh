#!/system/bin/sh
# vold is not functional yet: create the DE/CE dirs system providers need.
for d in \
  /data/user_de/0/org.lineageos.lineagesettings/databases \
  /data/user_de/0/com.android.providers.settings/databases \
  /data/user/0/org.lineageos.lineagesettings/databases \
  /data/user/0/com.android.providers.settings/databases ; do
  mkdir -p "$d" 2>/dev/null
  chown -R system:system "${d%/databases}" 2>/dev/null
  chmod 700 "${d%/databases}" 2>/dev/null
  chmod 700 "$d" 2>/dev/null
done
# Keep a per-boot kernel log so a failed boot can be diagnosed later.
dmesg > /cache/kmsg-$(date +%s).log 2>/dev/null
