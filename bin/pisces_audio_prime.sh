#!/system/bin/sh
# pisces: warm reboot leaves the speaker path in a state where the first
# playback only wakes the ES325 and stays silent.  Replicate the proven
# manual sequence at boot: play a silent WAV from /sdcard, stop it, cycle the
# speaker pin, so the user's first real playback is the second (audible) one.
i=0
while [ $i -lt 30 ]; do
    [ -e /dev/snd/controlC0 ] && break
    sleep 1
    i=$((i + 1))
done
input keyevent KEYCODE_WAKEUP 2>/dev/null
wm dismiss-keyguard 2>/dev/null
cp /system/etc/pisces_silence.wav /sdcard/pisces_silence.wav 2>/dev/null
am start -a android.intent.action.VIEW -d file:///sdcard/pisces_silence.wav -t audio/wav -f 0x10000000 >/dev/null 2>&1
sleep 4
am force-stop org.lineageos.eleven
am force-stop org.lineageos.jelly
sleep 1
tinymix "Int Spk Switch" 0
sleep 1
tinymix "Int Spk Switch" 1
