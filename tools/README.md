# Test tools used while bringing pisces up

These are not part of the product build; they are built by hand when needed.

## PiscesCamTest (camera / LED stress test app)

    cp -a tools/PiscesCamTest ../../../packages/apps/
    cd ../../../ && source build/envsetup.sh && lunch lineage_pisces-eng
    m PiscesCamTest
    # /data does not support security xattrs on this kernel, so 'adb install'
    # fails: push the APK into /system/app and reboot instead
    adb push out/target/product/pisces/system/app/PiscesCamTest/PiscesCamTest.apk \
        /system/app/PiscesCamTest/

Camera switching stress test:

    adb shell am start -n org.pisces.camtest/.MainActivity \
        --es mode cam --ei count 30 --ei gap 100 --ez frames true
    adb logcat -s PiscesCamTest:I

Notification LED test (screen must be off for the LED to light):

    adb shell am start -n org.pisces.camtest/.MainActivity --es mode notify

## lighttest (lights HAL / LM3533 register dump)

    cp -a tools/lighttest ../../../packages/apps/PiscesCamTest/
    m lighttest
    adb push out/target/product/pisces/system/bin/lighttest /system/bin/

    lighttest hal 4 0xffff0000   # legacy lights HAL, notifications, red
    lighttest hidl 4 0xffff0000  # android.hardware.light@2.0, what LightsService uses
    lighttest regs               # dump the LM3533 registers over /dev/i2c-0
