#!/system/bin/sh
# DeviceSpoofLabs Hooks - late_start service
# Runs after boot_completed.

MODDIR=${0%/*}

# Wait for boot to fully settle before doing anything.
until [ "$(getprop sys.boot_completed)" = "1" ]; do
  sleep 2
done

pm_path="$(pm path com.devicespooflab.hooks 2>/dev/null || true)"
if [ -z "$pm_path" ]; then
  log -t DeviceSpoofLab "Zygisk module v1.40 active. Companion APK: NOT INSTALLED"
  log -t DeviceSpoofLab "Install the standalone APK artifact manually for UI; module ZIP carries Java hook fallback."
else
  log -t DeviceSpoofLab "Zygisk module v1.40 active. Companion APK: $pm_path"
  launcher_activity="$(cmd package resolve-activity --brief com.devicespooflab.hooks 2>/dev/null | tr '\n' ' ')"
  log -t DeviceSpoofLab "Launcher resolve: ${launcher_activity:-NOT FOUND}"
fi
