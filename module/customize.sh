#!/system/bin/sh
# Magisk module install-time customization for the DeviceSpoofLab Zygisk module.

# Abort if the installer is not Magisk/KernelSU with Zygisk support.
if [ "$BOOTMODE" != "true" ]; then
  ui_print "! Please install from Magisk Manager / KernelSU Manager"
  abort "! Installation from recovery is not supported"
fi

# Magisk exports ZYGISK_ENABLED=1, but KernelSU/APatch + ZygiskNext builds do
# not always expose that variable to the module installer. Only abort when the
# installer explicitly says Zygisk is disabled; otherwise allow install and let
# the active Zygisk implementation load zygisk/*.so after reboot.
if [ "$ZYGISK_ENABLED" = "0" ]; then
  ui_print "! Zygisk is disabled"
  ui_print "! Enable Magisk Zygisk or install/enable ZygiskNext, then reinstall"
  abort "! Aborting: Zygisk required"
elif [ "$ZYGISK_ENABLED" != "1" ]; then
  ui_print "- Zygisk status variable not provided by installer"
  ui_print "- Continuing for KernelSU/APatch + ZygiskNext compatibility"
fi

ui_print "- Installing DeviceSpoofLabs-Hooks (Zygisk)"
ui_print "- ABI: $ARCH"

# The zygisk/ directory ships the per-ABI .so files; Magisk loads them itself.
# Just fix up permissions on everything we ship.
set_perm_recursive "$MODPATH" 0 0 0755 0644

ui_print "- Done. Reboot to activate."
ui_print "- Configure target apps and spoof profile from the DeviceSpoofLab app."
