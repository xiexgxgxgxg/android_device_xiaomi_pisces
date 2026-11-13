#!/system/bin/sh
# Grant the runtime permissions Bluetooth's PBAP needs.  On a clean flash the
# default-permissions XML already grants them; when the Bluetooth APK is added
# by an OTA on top of existing data the policy is not re-applied and PBAP
# throws SecurityException on ContactsProvider2, crash-looping the stack.
#
# boot_completed can fire before PackageManager has scanned the package
# ("DefaultPermGrantPolicy: Unknown package"), so wait for it first.
i=0
while [ $i -lt 60 ]; do
    if pm path com.android.bluetooth >/dev/null 2>&1; then
        break
    fi
    sleep 1
    i=$((i+1))
done

for p in ACCESS_COARSE_LOCATION READ_CONTACTS WRITE_CONTACTS READ_CALL_LOG \
         WRITE_CALL_LOG READ_EXTERNAL_STORAGE WRITE_EXTERNAL_STORAGE; do
    pm grant com.android.bluetooth android.permission.$p
done
