# frameworks/av overlay (pisces)

Android 9 hands the camera HAL buffers across processes and the 2013 Tegra
camera stack needs a few framework-side accommodations.  Those changes live
here, inside the device tree, instead of a forked or patched `frameworks/av`:

| file | why |
|---|---|
| `services/camera/libcameraservice/CameraProviderManager.cpp` | a dead camera provider no longer reports its devices as `NOT_PRESENT`; the legacy HAL kills its provider in its own close path and init respawns it |
| `services/camera/libcameraservice/CameraService.cpp` | `connectHelper()`/`getDeviceVersion()` wait for the restarted provider instead of failing with "Unknown camera ID"; legacy shim parameters are cached on disk |
| `services/camera/libcameraservice/CameraFlashlight.cpp` | torch through `/sys/class/leds/flashlight` instead of opening the camera |
| `services/camera/libcameraservice/api1/CameraClient.cpp` | serve the cached parameter blob while the provider restarts |
| `media/libaudioprocessing/AudioResampler.cpp`, `soundtrigger/SoundTrigger.cpp` | small fixes from the same bring-up |

`apply.sh` copies these over the tree at configure time (`BoardConfig.mk` calls
it), so a clean `repo sync` followed by `mka bacon` builds the same ROM without
any manual patching.  The equivalent patch is still in `patches/0028` (and the
fork at `github.com/xiexgxgxgxg/android_frameworks_av`); this overlay is the
self-contained form.


## Guard property

Every workaround in these files is gated on `ro.pisces.camera_quirks`, which
`device_pisces.mk` sets to 1.  A build for another device from the same source
tree therefore compiles the exact same code but runs the pristine paths:

* `CameraHardwareInterface.cpp` - with the property off the HIDL return values
  are handled exactly like upstream (including its abort on a failed call);
* `CameraService.cpp` - no provider-restart retry in `getCameraInfo`;
* `CameraFlashlight.cpp` - the sysfs torch shortcut is skipped;
* `SoundTrigger.cpp` - the original wait-for-HAL loop is used.

The OMX HAL init configuration lives in `etc/init/init_pisces_omx.rc` (device
tree), not in frameworks/av.
