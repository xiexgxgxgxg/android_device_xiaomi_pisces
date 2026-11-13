#!/system/bin/sh
# pisces: use the MIUI generate_204 endpoint for captive-portal validation.
# The AOSP default is Google, which is unreachable in China and leaves the
# Wi-Fi icon with an "x"/"!" even when the network works.  Device-tree only:
# this script is started once at boot_completed by init.pisces.rc.
settings put global captive_portal_mode 1
settings put global captive_portal_use_https 0
settings put global captive_portal_http_url http://connect.rom.miui.com/generate_204
settings put global captive_portal_https_url http://connect.rom.miui.com/generate_204
settings put global captive_portal_fallback_url http://connect.rom.miui.com/generate_204
settings put global captive_portal_other_fallback_urls ""
