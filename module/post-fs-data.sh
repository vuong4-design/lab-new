#!/system/bin/sh
# DeviceSpoofLabs Hooks - post-fs-data
# Runs early boot, before most services start.

MODDIR=${0%/*}

# APatch users install the standalone APK artifact for the launcher/UI. The
# module ZIP intentionally keeps native Zygisk/helper libraries separate.
if pm path com.devicespooflab.hooks 2>/dev/null | grep -q '/data/app/'; then
  log -t DeviceSpoofLab "User-installed UI APK detected at /data/app; this is OK on APatch."
fi
