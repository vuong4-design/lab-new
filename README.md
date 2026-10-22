# DeviceSpoofLab-Hooks

Zygisk module that spoofs device identifiers, build properties, telephony,
network, locale, WebView fingerprinting surfaces, and native libc property
reads inside selected target processes.

Current runtime surfaces (`v1.41-uptime-service-times`):

- **Uptime**: scoped apps see a target uptime randomly chosen once per
  companion lifetime (normally one boot; default 1–7 days). Applies to native
  monotonic `clock_gettime`,
  `sysinfo.uptime`, `/proc/uptime`, and `btime`; `system_server`, the module UI,
  and out-of-scope apps keep real uptime.
- **Service list**: `android.os.ServiceManager` list/get/check calls hide
  services whose names contain configured keywords (root, hook, emulator, and
  instrumentation tools by default).
- **Package timestamps**: only the scoped app's own package gets a stable
  `firstInstallTime`/`lastUpdateTime` derived from the spoofed build date, with
  `lastUpdateTime >= firstInstallTime` and `lastUpdateTime <= now`.

Runtime config defaults:

```ini
hooks.uptime_spoof=1
hooks.uptime_target_min_days=1
hooks.uptime_target_max_days=7
hooks.service_list_spoof=1
hooks.package_info_times=1
```

Known limits: direct binder `IServiceManager` calls bypass the Java service
filter; raw syscall/vDSO clock reads and `/proc/self/stat` starttime bypass the
uptime hooks; only the module's own package timestamps are rewritten.

## Requirements

- Rooted Android with Magisk Zygisk, KernelSU + ZygiskNext, or another
  compatible Zygisk implementation.
- Android 8.0+ / SDK 26+ on the target device.

## Install

1. Flash `DeviceSpoofLabs-Hooks-Zygisk.zip` from Magisk/KernelSU Manager.
2. Reboot.
3. Install the standalone APK artifact (`DeviceSpoofLabs-APK` /
   `app-release.apk`) if it is not already installed.
4. Open the DeviceSpoofLab-Hooks app, add target package names in
   **Target apps (scope)**, and randomize identifiers as needed. Add
   `com.google.android.gms` too if you need GAID/App Set ID spoofing through
   Google Play services.
5. Force-stop and relaunch the target apps.

Installing the APK alone only opens the configuration UI. It does not load the
Zygisk hooks.

The ZIP intentionally does not mount the APK as `system/priv-app`; APatch may
not expose that package to the launcher. Install the APK artifact normally for
the UI and Java hook dex path. Native helper libraries still come from the ZIP
module, not from `/data/app`, to avoid SELinux execute denials.

## Troubleshooting

If the target app is not seeing the spoofed values:

- Confirm the target app package name is listed in the in-app scope editor and
  the Zygisk module is enabled.
- Confirm Zygisk is enabled and the device was rebooted after flashing.
- On APatch, install the APK artifact manually for the launcher icon. Keep the
  ZIP module enabled; the APK is the UI/class path, not the Zygisk entrypoint.
- Force-stop the target app afterwards so it picks up the new values cleanly
  instead of caching whatever it read on startup.
- Uptime target is selected by the companion once per boot and stored in the
  profile as `uptime.target_seconds`/`uptime.offset_seconds`. After changing
  `hooks.uptime_target_min_days` or `hooks.uptime_target_max_days`, reboot or
  restart the companion to pick a new target.
- Disable uptime with `hooks.uptime_spoof=0`, service filtering with
  `hooks.service_list_spoof=0`, and self timestamps with
  `hooks.package_info_times=0`; no full path guard is required for uptime.

Debug audit logs:

- Filter logcat with `DeviceSpoofLab` or `DS-Xposed`.
- `audit:preApp ... inScope=1` means the target package matched scope.
- `audit:CommitHook=1` means native PLT hooks were committed.
- `audit:InstallJavaHooks natives registered` means Java hook bridge loaded.
- `audit:LSPlant hook installed ...` means a Java method hook was installed.
- `audit:LSPlant callback first hit ...` means the hooked method was actually
  called by the target process.
- `audit:companion uptime profile ...` shows the target/real/offset chosen for
  this boot.
- `audit:uptime state enabled=1 offset=...` and
  `audit:uptime hooks registered ...` confirm the scoped app process applied it.
- `audit:path guard registered mode=uptime-only` means only `/proc/uptime` and
  `btime` are adjusted; `mode=full+uptime` also enables the full native guard.
- `audit:service-list`/`audit:service-hide` lines show service filtering in the
  app process.

Inadequate hooks or Xposed detected:

- Try a newer Zygisk implementation or an LSPosed fork only if you are testing
  the older LSPosed build.
- Open an issue and describe the device, Android version, root manager, target
  app package, and relevant `DeviceSpoofLab` logcat lines.

## License

This project is licensed under the [MIT License](https://opensource.org/licenses/MIT).
