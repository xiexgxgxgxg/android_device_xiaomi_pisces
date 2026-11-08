# Running the vendor OMX HAL as a system process

The Nvidia OMX components (`OMX.Nvidia.*`, the hardware H.264 encoder/decoder)
live in `/system/lib/libnvomx*.so`. The stock `android.hardware.media.omx`
service runs as a **vendor** process, whose linker namespace cannot load those
system libraries, so the framework only ever saw the software codecs.

This directory carries what is needed to run the same HAL from a **system**
process instead (SELinux is permissive on this 3.4 kernel, so the domain does
not matter):

* `lib/libstagefrighthw.so` - the `NVOMXPlugin` shim (from the stock ROM),
  installed to `/system/lib/`;
* `etc/init/android.hardware.media.omx@1.0-service.rc` - the vendor rc replaced
  by a service stanza pointing at `/system/bin/media_omx_service`, a copy of
  the vendor binary.  The linker namespace follows the executable path, so the
  service gets the system namespace and can load the nvomx libraries.

Install (as root, with `/system` remounted read-write):

    cp lib/libstagefrighthw.so /system/lib/
    cp /system/vendor/bin/hw/android.hardware.media.omx@1.0-service /system/bin/media_omx_service
    cp etc/init/android.hardware.media.omx@1.0-service.rc /system/vendor/etc/init/
    reboot

After this the framework instantiates `OMX.Nvidia.h264.encoder` (verified: a
recording session logs `makeComponentInstance(OMX.Nvidia.h264.encoder)`).
