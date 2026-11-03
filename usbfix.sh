#!/system/bin/sh
# Re-arm the legacy android_usb gadget until it enumerates.
# On some boots the Tegra UDC stays DISCONNECTED until the cable is
# replugged; toggling enable makes the kernel re-run the connect path.
S=/sys/class/android_usb/android0
i=0
while [ $i -lt 24 ]; do
    state=$(cat $S/state 2>/dev/null)
    [ "$state" = "CONFIGURED" ] && break
    echo 0 > $S/enable 2>/dev/null
    echo ffs > $S/functions 2>/dev/null
    echo 1 > $S/enable 2>/dev/null
    i=$((i+1))
    sleep 5
done
