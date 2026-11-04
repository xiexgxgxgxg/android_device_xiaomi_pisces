# LineageOS 16.0 (pisces) bring-up status

Updated: 2026-11-04 17:45

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

## Remaining blocker

Selecting a language and pressing "next" in SetupWizard soft-restarts the
framework after a while. Symptoms in logs:

- `GraphicBufferAllocator: Failed to allocate (1080 x 72) usage 900: 2`
- `MapperHal: registerBuffer -> -12` (intermittent, most imports return 0)
- `OpenGLRenderer: Failed to create EGLSurface ... EGL_BAD_ALLOC`
- SystemUI then crashes in a loop; AMS eventually kills the system process.

The nvmap FROM_ID permission issue is fixed; what remains is an intermittent
ENOMEM inside the legacy gralloc `registerBuffer`. Planned directions:

1. Instrument gralloc's handle table/bookkeeping (blob, so limited) or
   reproduce with a native allocate+import test.
2. Consider allocating buffers locally in SurfaceFlinger (bypass the
   cross-process allocator/import path) or switching the allocator to a
   passthrough path.
3. Once SystemUI stops crashing, re-run the SetupWizard flow.

## Commits

- kernel (`lineage-16.0-113`): `d340a3d6` nvmap cross-process import
  (NOT pushed yet: GitHub SSH auth failed with "Permission denied (publickey)")
- device (`lineage-16.0`): `6b9344e`, `a4200fa`, `58ad659`, `52b306e`, ...
- patches 0001-0023 in device/xiaomi/pisces/patches
