# 2026-11-13 - Spreadtrum modem / baseband bring-up

`getprop gsm.version.baseband` used to be empty and `rild` crashed every
~31 s in `at_channel_writer_destroy()` because no AT channel existed.
`fild` was stuck on "Modem power is off, try to turn it on." for three
independent reasons:

1. **fild created its state FIFO with mode 0300.**
   `bin/fild` calls `mknod("/data/fild/fild_fifo", S_IFIFO | 0300)` and then
   tries to fix it up with `system("#hmod 600 ...")` - a typo in the vendor
   blob (`hmod`) which is also commented out with `#`.  The daemon runs as
   `radio`, opens the FIFO `O_RDONLY`, and fails with EACCES, so every read
   is treated as state 0 (PWROFF).

   `bin/fild` is patched in place at file offset `0x1a18`:
   `f4 4f 86 51` (`mov.w r1, #0x10c0`) -> `f4 4f 8c 51` (`mov.w r1, #0x1180`),
   i.e. the FIFO is created `0600`.  The same change should be made if the
   blob is ever re-imported.

2. **SELinux denied the state path.**  `system/sepolicy/private/pisces.te`
   (exported as `patches/2026-11-12/system-sepolicy.patch`) now allows:
   - `fild self:netlink_kobject_uevent_socket { create bind read write setopt }`
     (without it the uevent thread exits before writing the initial state);
   - `fild fild_data_file:fifo_file rw_file_perms`
     (the FIFO is labeled `fild_data_file`, not `system_data_file`);
   - `fild shell_exec:file rx_file_perms` (fild uses `system("echo 1 > ...")`);
   - `set_prop(fild, ctl_start_prop/ctl_stop_prop)`.

3. **Warm reboot left `BP_PWRON` high.**  The driver early-returned on
   "modem has been powered on already", skipping `sc8800g_gpio_init()` and
   the reset release; the modem state machine stayed at PWROFF.  Fixed in
   `android_kernel_xiaomi_pisces`:
   `drivers/modem/serial_sc8800g.c`, `sc8800g_mdm_active()` power-cycles the
   rail when the GPIO is already asserted and falls through to the normal
   bring-up.

The init rc also chowns `pwr_onoff`, `state` and `usb_switch` to `root:radio`
(they are exported `0640 root:root`) and no longer tries the neverallowed
`write .../reset 1` from init.

Verified on the device (enforcing, Magisk 31.1 installed):

```
gsm.version.baseband = DM_BASE_13A_8803G_W13.44 | 20140120092332
init.svc.ril-daemon  = running
init.svc.fil-daemon  = running
tombstones mentioning rild: 0
```

## Magisk 31.1 under enforcing

Flashing the Magisk zip from TWRP patches the boot ramdisk, but the app then
shows either "not installed" or "runtime environment needs repair".  The
reason is MLS, not the daemon or su:

* The Magisk app targets SDK 37, so seapp_contexts gives it
  `levelFrom=all` and its process level is `s0:c84,c256,c512,c768`.
* magiskd's control socket is `u:object_r:magisk_file:s0`.  Writing it is
  covered by `mlsconstrain sock_file { write ... }` with `l1 eq l2`; the
  app's categories break that equality, so the su helper is denied
  `{ write }` on `magisk_file:sock_file` and the app cannot query the
  daemon (root from a shell without categories still worked).

Fix (all in the system image, enforcing stays on):

1. `system/sepolicy/private/mac_permissions.xml` assigns the Magisk signing
   certificate (SHA-256
   `B4CB83B4DAD99F997DBE872F013AA16C14EEC41D167021F371F7E1330F273EE6`)
   the dedicated seinfo `magisk`.
2. `device/xiaomi/pisces/selinux/plat_seapp_contexts` adds, before the
   generic app rules:
   ```
   user=_app seinfo=magisk name=com.topjohnwu.magisk domain=untrusted_app type=app_data_file levelFrom=none
   ```
   The manager therefore runs at level `s0` (no categories) and the socket
   write satisfies `l1 eq l2`.  Only this one app changes; every other app
   keeps its categories and the rest of the policy stays enforcing.

Verified with a clean magisk.db (no policy rows, default mount namespace
mode): the home screen shows "Magisk 31.1 (31100) installed", a
`com.topjohnwu.magisk:root:0` process runs in `u:r:magisk:s0`, and the
in-app "direct install" / "reinstall" flow completes.  TWRP alone only
patches the ramdisk; the first in-app reinstall is still what finishes the
environment setup, so document that step for users.

## Regressions a clean OTA build exposed (and their fixes)

The hand-patched system image had accumulated fixes that were not in the
tree; the first full OTA build made them visible.  All are fixed now:

1. **fdl could not read the modem partitions.**
   `fdl` opens `/dev/block/platform/.../by-name/{MDM,MLO,MRO,MRW}`, which
   resolve to `mmcblk0p10/p11/p12/p16`.  AOSP neverallows raw
   `block_device:blk_file` access.  ueventd now labels those four nodes
   `u:object_r:modem_block_device:s0` and `fild` gets
   `allow fild modem_block_device:blk_file rw_file_perms;`
   plus `toolbox_exec` exec (its shell helpers) and
   `block_device:dir/lnk_file` read for the by-name path.
2. **Duplicate platform/media signer in mac_permissions.xml.**
   This port unifies all signing keys, so `@PLATFORM` and `@MEDIA` resolve
   to the same certificate.  Two signer stanzas for one cert made PMS use
   `media` instead of `platform`; every platform app became
   `default:privapp...`, `selinux_android_setcontext()` for
   `com.android.phone` failed (`seapp_context_lookup: No match ... seinfo
   default`) and the phone process stayed in the zygote domain, leaving
   `gsm.version.baseband` empty.  The `@MEDIA` stanza is removed; the
   single platform signer covers media too.  The Magisk signer stays a
   separate stanza.
3. **zygote ashmem fd use.**  Some early processes remain in the zygote
   domain (setcon workaround) and receive `MemoryIntArray` ashmem fds from
   system_server.  Without `allow zygote system_server:fd use;` every
   Bundle carrying one fails with "Error unparceling MemoryIntArray"
   (telephony provider / CryptKeeper crash).

After these, a clean data + clean OTA build boots with baseband
`DM_BASE_13A_8803G_W13.44 | 20140120092332`, `com.android.phone` in
`u:r:radio:s0`, and enforcing.

## MTP / USB file transfer

Selecting "File transfer" fell back to charging (or the PC saw the MTP
interface but could not open it) because of three missing rules:

```
allow hal_usb_gadget_default property_socket:sock_file write;
allow hal_usb_gadget_default init:unix_stream_socket connectto;
allow platform_app mtp_device:chr_file rw_file_perms;
```

The gadget HAL could not set `sys.usb.config` (EACCES), so
`setCurrentUsbFunctions()` failed and UsbDeviceManager reverted to charging.
MtpServer runs in MediaProvider's `platform_app` process on this port (the
`mediaprovider` domain is only used by `android.process.media`), so it also
needed `/dev/mtp_usb`.  After the fix `dumpsys usb` reports
`current_functions=MTP, current_functions_applied=true` and the host mounts
`mtp://Xiaomi_MI_3_095ac70c/` with the internal storage listed.
