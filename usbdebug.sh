#!/system/bin/sh
# Dump USB gadget state to /cache so it can be pulled from recovery.
sleep 3
LOG=/cache/usb-debug.txt
{
  echo "== date =="; date
  echo "== android_usb sysfs =="
  ls -l /sys/class/android_usb/android0/ 2>&1
  for f in enable state functions idVendor idProduct bDeviceClass; do
    printf "%s = " "$f"; cat /sys/class/android_usb/android0/$f 2>&1
  done
  echo "== udc =="
  ls /sys/class/udc/ 2>&1
  for u in /sys/class/udc/*; do
    [ -e "$u" ] || continue
    echo "-- $u"; cat "$u/state" 2>&1
  done
  echo "== functionfs =="
  ls -l /dev/usb-ffs/ /dev/usb-ffs/adb/ 2>&1
  mount | grep -i ffs
  echo "== props =="
  getprop | grep -i -E "usb|adb"
  echo "== adbd =="
  ps -A 2>/dev/null | grep -E "adbd|usbd"
  echo "== dmesg tail =="
  dmesg 2>/dev/null | grep -iE "android_usb|udc|ffs|gadget" | tail -30
} > $LOG 2>&1
chmod 0644 $LOG
