# Overlay for hardware/nvidia/pisces

`hardware/nvidia` is not a git project in this tree, so the files the camera
work touches are kept here as the reference copy.  After a fresh sync, or when
`hardware/nvidia/pisces` is recreated, copy them back:

    cp hardware-overlay/shims/camshim.c       ../../../hardware/nvidia/pisces/shims/
    cp hardware-overlay/shims/Android.bp      ../../../hardware/nvidia/pisces/shims/
    cp hardware-overlay/camera/Android.mk     ../../../hardware/nvidia/pisces/camera/
    cp hardware-overlay/camera/CameraWrapper.cpp ../../../hardware/nvidia/pisces/camera/

What changed and why:

- `CameraWrapper.cpp`: include `<sensor/ISensorServer.h>` (A9 path), and load
  the vendor module with an explicit `dlopen()` after `dlopen()`ing the ABI
  shim with `RTLD_GLOBAL`.  `hw_get_module_by_class()` goes through
  `android_load_sphal_library()`, which puts the vendor module in a linker
  namespace where the shim symbols are not visible.
- `Android.mk`: link `libsensor` for `ISensorServer`.
- `shims/camshim.c`: new library `libpisces_camshim` (LD_PRELOADed into the
  camera provider).  It provides the Android 4.x
  `SensorManager`/`SensorEventQueue`/`Singleton<SensorManager>` symbols the
  2013 blob was linked against, and drops the process target SDK to 22 so
  bionic accepts the text relocations in `libFaceProc.so`.
