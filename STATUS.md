# LineageOS 16.0 (pisces) bring-up status

Updated: 2026-11-04 18:15

## What works now

- Kernel 3.4.113 with binder SG/PTR/FDA, nvmap sysmem, and nvmap
  cross-process handle import by id (FROM_ID now succeeds).
- Boot completes (`sys.boot_completed=1`), system_server stays up for long
  periods, user 0 unlocks and stays RUNNING_UNLOCKED.
- Storage: emulated volume mounts (sdcard bind-mount fallback + vold accepts
  it), `/data/data` gets ~109 app data dirs, no more user-0 data wipe
  (installd guard + vold fixes).
- Audio: audio@2.0 legacy-wrapper HAL with the default stub module;
  media.audio_policy/flinger and AudioService are up.
- SetupWizard renders (welcome + locale screens), bootanimation, launcher
  and Settings have all been seen on screen.
- Bluetooth/NFC removed from the image (PiscesStub overrides) because both
  crash-loop on first boot until firmware/config exists.

## Current hardware finding (2026-11-04 18:50)

After the OTA was reflashed, /data formatted and the boot image rewritten from
the zip, the device still rebooted after ~2-3 s. `/proc/last_kmsg` (read from
TWRP) ends with a stream of:

    palmas 4-0058: VSYS_MON interrupt occur

`VSYS_MON` is the PMIC supply-voltage monitor alarm: the system rail sags
below the safe range under boot load, so the PMIC resets the SoC. This is a
power/battery problem, not a software one (the 2013 battery has high internal
resistance; 40% capacity does not mean it can carry boot current).

Next: charge with a good 5V/2A supply, check the battery connector (it was
reseated), and if VSYS_MON persists while on a strong charger, replace the
battery.

## Remaining software blocker

Selecting a language and pressing "next" in SetupWizard soft-restarts the
framework after a while. Symptoms in logs:

- `GraphicBufferAllocator: Failed to allocate (1080 x 72) usage 900: 2`
- `MapperHal: registerBuffer -> -12` (intermittent, most imports return 0)
- `OpenGLRenderer: Failed to create EGLSurface ... EGL_BAD_ALLOC`
- SystemUI then crashes in a loop; AMS eventually kills the system process.

The nvmap FROM_ID permission issue is fixed (verified: imports return 0).
What remains is the legacy gralloc `registerBuffer` failing with -ENOMEM for
buffers allocated with usage 0x900 (HW_COMPOSER|HW_RENDER). Measurements:

- after restarting allocator+SF: 147 imports / 32 failures (~22%);
- adding a retry loop (8x2ms) did not help: the failures are deterministic;
- adding HW_TEXTURE or HW_FB to the usage did not change the failure rate;
- failures carry gralloc handle ints `data[0]` that increase monotonically
  (143,148,153,...), which suggests an internal tracking table/counter.

Result: SystemUI keeps crashing (`EGL_BAD_ALLOC`), the device ends up with
load average >14 and a black screen with backlight on.

Next session plan:

1. Implement local gralloc0 allocation in SurfaceFlinger/libui (allocate and
   register in the same process) to bypass the cross-process import that the
   legacy blob mishandles.
2. Alternatively identify the gralloc table limit and patch the blob's
   expectations (e.g. always allocate through the framebuffer/carveout path).
3. Only then re-run SetupWizard; after that, finish BT/NFC + HALs.

## Commits

- kernel (`lineage-16.0-113`): `d340a3d6` nvmap cross-process import
  (NOT pushed yet: GitHub SSH auth failed with "Permission denied (publickey)")
- device (`lineage-16.0`): `6b9344e`, `a4200fa`, `58ad659`, `52b306e`, ...
- patches 0001-0023 in device/xiaomi/pisces/patches
