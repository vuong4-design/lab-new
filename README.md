# DeviceSpoofLab-Hooks

Zygisk module that spoofs device identifiers, build properties, telephony,
network, locale, WebView fingerprinting surfaces, and native libc property
reads inside selected target processes.

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

Debug audit logs:

- Filter logcat with `DeviceSpoofLab` or `DS-Xposed`.
- `audit:preApp ... inScope=1` means the target package matched scope.
- `audit:CommitHook=1` means native PLT hooks were committed.
- `audit:InstallJavaHooks natives registered` means Java hook bridge loaded.
- `audit:LSPlant hook installed ...` means a Java method hook was installed.
- `audit:LSPlant callback first hit ...` means the hooked method was actually
  called by the target process.

Inadequate hooks or Xposed detected:

- Try a newer Zygisk implementation or an LSPosed fork only if you are testing
  the older LSPosed build.
- Open an issue and describe the device, Android version, root manager, target
  app package, and relevant `DeviceSpoofLab` logcat lines.

## License

This project is licensed under the [MIT License](https://opensource.org/licenses/MIT).
