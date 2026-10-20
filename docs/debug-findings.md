# DeviceSpoofLab Debug Findings

This file records the main issues found while debugging the APatch/ZygiskNext
build and the apps tested so far. Keep it updated when a new target app needs a
compat mode.

## Current baseline

- Current module marker: `v1.40-java-hook-controls`.
- Build artifact to flash: `DeviceSpoofLabs-Hooks-Zygisk.zip`.
- Standalone UI APK should still be installed separately for launcher icon and
  reliable UI access.
- Local Windows build was not verified because `JAVA_HOME`/`java` is missing in
  this workspace. GitHub Actions remains the working build path.

## Global issues found

### APatch/ZygiskNext dependency loading

Symptom:

```text
Not found: 'liblsplt.so' needed by 'devicespooflab_hooks'
Not found: 'liblsplant.so' needed by 'devicespooflab_hooks'
Not found: 'libc++_shared.so' needed by 'devicespooflab_hooks'
preload failed
```

Finding:

- APatch/ZygiskNext loaded the Zygisk entrypoint, but dependent helper libraries
  were not resolved from the original `zygisk/<abi>` layout.
- Even when files existed on disk, the loader namespace/path did not resolve
  them reliably.

Mitigation:

- Keep the Zygisk entrypoint self-contained.
- Package helper libs under `system/lib*` and `lib/<abi>`.
- Load Java helper libs from the module path via `/proc/self/fd/<moduleFd>`.

### APatch-mounted APK and launcher icon

Symptom:

- The APK in `system/priv-app/DeviceSpoofLab/DeviceSpoofLab.apk` was collected
  by APatch magic mount, but the launcher icon did not reliably appear.

Finding:

- APatch module-mounted priv-app is not a reliable UI install path.
- The module APK is still useful as a classpath fallback for Java hooks.

Mitigation:

- Upload and install the standalone APK artifact separately.
- Keep `apk/DeviceSpoofLab.apk` inside the module ZIP for hook class loading.

### system_server Java helper loading

Symptom:

```text
avc: denied { execute } ... /data/app/.../lib/arm64/liblsplant.so
InstallJavaHooks: dlopen failed
```

Finding:

- Loading APK-owned helper libraries inside `system_server` can hit SELinux and
  namespace restrictions.

Mitigation:

- Do not load APK-owned helper libraries in `system_server`.
- v1.36 adds a narrow `system_server` PackageManager hook path that loads the
  helper libraries from the module/system lib directory only. This is gated by
  `debug.system_server_package_hiding=1` and is used for package/root-app
  hiding, so target apps no longer need local PackageManager method hooks for
  that group.
- Keep other framework hooks app-side unless a server-side replacement exists.

### Early Java runtime/classloader

Symptom:

```text
classLoader=dalvik.system.PathClassLoader[DexPathList[[directory "."]...]]
appInfo=null
runtime not ready
```

Finding:

- `postAppSpecialize` can run before the real app classloader and
  `ApplicationInfo` are ready.
- Installing Java hooks at this point can crash or silently install into the
  wrong loader.

Mitigation:

- `HookEntry` now retries until the runtime has a usable package name and
  classloader containing `/data/app/`, `/mnt/expand/`, or `.apk`.

### LSPlant backend instability

Symptom:

```text
audit:LSPlant init attempt ...
LSPlant init failed
nativeHookMethod: LSPlant init failed
audit:LSPlant backend unavailable; Java method hooks disabled
```

Finding:

- `libds_java_hooks.so` can load, but LSPlant init may fail on this ART/device
  combination.
- Without guarding this, Java method hook calls can crash or throw repeatedly.

Mitigation:

- LSPlant init is lazy.
- If init fails, `XposedBridge` marks the backend unavailable and method hooks
  become no-op.
- Static field writes and Java-only logic can still run where safe.

### PackageInfo timestamp patch

Symptom:

- Some apps became unstable around package metadata hooks.

Finding:

- Rewriting `PackageInfo` timestamps is risky for apps that verify their own
  package metadata.

Mitigation:

- `hooks.package_info_times=0` is the default.
- Installer/source hooks remain available.
- `hooks.hide_detection_packages=1` hides configured package names from
  `getPackageInfo`, `getApplicationInfo`, `getPackageUid`,
  `getInstalledPackages`, and `getInstalledApplications` while leaving the
  target app's own package visible.
- Default hidden list:

```ini
hooks.hidden_packages=com.devicespooflab.hooks,com.topjohnwu.magisk,io.github.vvb2060.magisk,io.github.huskydg.magisk,bin.mt.plus,bin.mt.plus.canary,me.bmax.apatch,me.yervant.yapatch,me.weishu.kernelsu,org.lsposed.manager,org.meowcat.edxposed.manager,com.solohsu.android.edxp.manager,com.saurik.substrate,eu.chainfire.supersu,com.noshufou.android.su,com.koushikdutta.superuser,com.kingroot.kinguser
```

### Native hook policy

Finding:

- Immediate PLT/GOT hooks are useful for broad native spoofing, but some debug
  apps scan GOT/PLT during their early startup window.
- Turning native hooks off avoids the crash, but it also removes the spoofing
  surface we need.

Mitigation:

- Native hooks are now selected through one policy parser shared by all scoped
  apps.
- Global default keeps the old broad behavior:

```ini
debug.native_hook_mode=full
debug.native_hook_delay_ms=0
```

- Any scoped app can be tested with delayed native hooks by changing the global
  mode:

```ini
debug.native_hook_mode=late-full
debug.native_hook_delay_ms=3000
```

- Known compat apps can override the global policy with package-specific keys.
- When a `late-*` mode is used, native install is reserved immediately so the
  Java fallback does not install the same PLT hooks too early.

## App-specific findings

## io.liankong.riskdetector

Important log signs:

```text
audit:preApp package=io.liankong.riskdetector
scopeOk=1 inScope=1
profileOk=1 profileSize=...
audit:riskdetector compat active ...
```

Issues seen:

- Optional native hooks could crash or trigger checks.
- Java helper stages were needed to isolate crashes around loader/JNI/static
  hook stages.
- Runtime could initially be too early, with `directory "."` classloader.

Current handling:

- Treat exact package and subprocesses like `io.liankong.riskdetector_zygote`
  as compat targets.
- Keep property native hooks for this package and skip optional native hooks by
  default.
- Use `debug.riskdetector.java_stage` to control Java hook depth.

Useful stages:

```ini
debug.riskdetector.java_stage=all
debug.riskdetector.native_mode=core
debug.riskdetector.native_delay_ms=0
```

Other Java stages remain useful for debugging regressions:

```ini
debug.riskdetector.java_stage=none
debug.riskdetector.java_stage=load
debug.riskdetector.java_stage=static
debug.riskdetector.java_stage=basic
```

Status:

- `all` was reported working after the compat path and staged loader handling.

## ru.andr7e.deviceinfohw

Issues seen:

- This app was used as a practical control target for Java hook loading.
- It helped confirm that waiting for the real app classloader was necessary.

Current handling:

- No special compat mode is required beyond the general runtime readiness
  checks.

Status:

- App opened and Java-side hooks were observed working after runtime readiness
  and LSPlant failure handling were improved.

## com.reveny.nativecheck

Important log signs:

```text
audit:moduleBuild=v1.31-holmes-pkginfo-debug
audit:preApp package=com.reveny.nativecheck
audit:nativecheck compat active ...
```

Issues seen:

- `native_mode=off` is stable.
- Immediate native modes crash:
  - `get`
  - `callback`
  - `find`
  - `core`
  - `system`
  - `dlopen`
  - `full`
- The smallest crashing case is `get`, which only patches
  `__system_property_get`.

Conclusion:

- The crash is not caused by spoofed values.
- The app likely detects PLT/GOT patching or a changed libc call path.
- Immediate native PLT hook strategy is not safe for this app.

Current handling:

- Exact package and subprocesses like
  `com.reveny.nativecheck:iso:...` are treated as compat targets.
- Default now keeps the hook by delaying it:

```ini
debug.nativecheck.java_stage=none
debug.nativecheck.native_mode=late-full
debug.nativecheck.native_delay_ms=3000
```

Late hook strategy:

- Late modes delay native hook installation until after app startup/self-check.
- User reported that all late modes work.

Lower-risk test flags if `late-full` needs narrowing:

```ini
debug.nativecheck.java_stage=none
debug.nativecheck.native_mode=late-get
debug.nativecheck.native_delay_ms=3000
```

Other late modes:

```ini
debug.nativecheck.native_mode=late-core
debug.nativecheck.native_mode=late-full
```

Expected log:

```text
audit:nativecheck compat active ... rawMode=late-full late=1 delayMs=3000
audit:native hook plan package=com.reveny.nativecheck ... rawMode=late-full late=1
audit:late native hook scheduled package=com.reveny.nativecheck mode=late-full delayMs=3000
audit:late native hook begin package=com.reveny.nativecheck mode=late-full
audit:RegisterHook(prop): req_get=1 req_find=1 req_cb=1 ...
audit:late native hook end package=com.reveny.nativecheck mode=late-full
```

Status:

- Late native modes work.
- This indicates the self-check is likely concentrated during early process
  startup. Delayed PLT patching is the current best path for keeping native
  hooks in this app.
- Remaining risk: values read before the delay can be cached by the target app.
  Increase or decrease `debug.nativecheck.native_delay_ms` based on logs and
  observed spoof coverage.

## me.garfieldhan.holmes

Important log signs:

```text
audit:moduleBuild=v1.33-holmes-pkginfo-strategy
audit:preApp package=me.garfieldhan.holmes
audit:holmes compat active javaStage=pkginfo ...
DeviceSpoofLab-PackageInfo: audit:plan pkg=me.garfieldhan.holmes mode=getpackageinfo trace=true strategy=passthrough
audit:moduleBuild=v1.34-holmes-ipm-proxy
DeviceSpoofLab-PackageInfo: audit:plan pkg=me.garfieldhan.holmes mode=getpackageinfo trace=true strategy=passthrough backend=ipm-proxy
DeviceSpoofLab-PackageInfo: audit:plan pkg=me.garfieldhan.holmes mode=getpackageinfo trace=true strategy=before backend=ipm-proxy
audit:moduleBuild=v1.35-holmes-lsplant-free
audit:InstallJavaHooks holmes lsplant-free stage=pkginfo backend=ipm-proxy
```

Current handling:

- Treat the package as a compat target while debugging.
- Keep native hooks active through `late-full`.
- Package/root-app hiding is now delegated to `system_server` by default.
  Holmes may still load the `pkginfo` Java stage for audit, but app-side
  `PackageInfoHooks` should skip local visibility hooks when backend is
  `system-server`.
- Trace every package lookup and whether it is hidden.
- For Holmes, avoid direct LSPlant patching of
  `ApplicationPackageManager.getPackageInfo`. v1.34 used an app-local
  `IPackageManager` proxy; v1.36 moves the same package-hiding behavior to
  `system_server` so Holmes does not need local PackageManager method hooks.
- For Holmes `pkginfo + system-server`, do not load the LSPlant-backed native
  Java helper in the Holmes process. The local stage is only audit/config glue,
  while the real package visibility filtering should happen in `system_server`.

Validated flags:

```ini
debug.holmes.java_stage=pkginfo
debug.holmes.native_mode=late-full
debug.holmes.native_delay_ms=3000
debug.holmes.package_info_mode=getpackageinfo
debug.holmes.package_info_strategy=before
debug.holmes.package_info_backend=system-server
debug.holmes.package_info_trace=1
```

## v1.36/v1.37 system_server PackageManager hooks

Goal:

- Move package/root-app hiding out of scoped app processes and into
  `system_server`, following the HMA-style architecture where PackageManager
  results are filtered before they return over Binder.

Implementation:

- `preServerSpecialize` now reads both the spoof profile and `scope.list` from
  the companion. The scope is injected into the profile as
  `hooks.scope_packages`.
- `InstallSystemServerHooks` loads `SystemServerEntry` from the module APK and
  registers the LSPlant-backed bridge only inside `system_server`.
- `SystemServerPackageHooks` waits for the `package` service, resolves the
  concrete `IPackageManager`/PMS classes, and hooks PackageManager visibility
  methods such as `getPackageInfo`, `getApplicationInfo`,
  `getInstalledPackages`, query methods, `getPackageStates`, and
  `shouldFilterApplication`.
- Filtering is caller-aware: it checks `Binder.getCallingUid()`, resolves the
  caller packages with `getPackagesForUid`, and only hides configured
  `hooks.hidden_packages` from callers listed in `scope.list`.
- App-side `PackageInfoHooks` now supports
  `debug.package_info_backend=system-server` and skips local PackageManager
  visibility hooks when that backend is active.
- `debug.system_server_package_hiding=1` forces the app-side package visibility
  backend to `system-server` even if an older saved profile still contains
  `debug.package_info_backend=direct` or `ipm-proxy`. Disable the server flag
  before using those fallback backends.
- v1.37 also delegates `PackageManagerHooks` feature spoofing to
  `system_server` with `debug.system_server_feature_spoofing=1`. Scoped apps
  no longer need local `ApplicationPackageManager.hasSystemFeature` or
  `getSystemAvailableFeatures` hooks for this group.

Expected v1.37 signs:

```text
audit:moduleBuild=v1.37-system-server-features
audit:preServer profileOk=1 ... scopeOk=1 scopeSize=...
audit:InstallSystemServerHooks natives registered
DeviceSpoofLab-SystemServer: audit:entry config ready scope=...
DeviceSpoofLab-SystemPM: audit:package service ready class=...
DeviceSpoofLab-SystemPM: audit:system_server package hooks installed count=...
DeviceSpoofLab-PackageInfo: audit:package visibility delegated to system_server backend
DeviceSpoofLab-PackageManager: audit:feature spoofing delegated to system_server backend
DeviceSpoofLab-SystemPM: audit:feature-force-before method=hasSystemFeature ...
DeviceSpoofLab-SystemPM: audit:feature-list-add method=getSystemAvailableFeatures ...
```

## v1.38 system_server AccountManager hooks

Goal:

- Move account hiding out of scoped app processes while keeping the hook effect.
  This follows the same direction as PackageManager hiding: scoped apps call the
  framework service over Binder, and `system_server` filters the result before it
  returns.

Implementation:

- Added `SystemServerAccountHooks`, loaded from `SystemServerEntry`.
- The hook waits for the `account` service, resolves `IAccountManager`, and hooks
  account-list methods such as `getAccounts`, `getAccountsAsUser`,
  `getAccountsForPackage`, `getAccountsByTypeForPackage`, and
  `getAccountsAndVisibilityForPackage`.
- Filtering is caller-aware via `Binder.getCallingUid()` and
  `getPackagesForUid`, and only applies to packages present in `scope.list`.
- App-side `AccountHooks` now skips local `android.accounts.AccountManager`
  hooks when `debug.system_server_account_hiding=1`.
- `hooks.hide_accounts` still controls whether account hiding is active. Default
  remains `0`; set it to `1` when testing this group.
- Added `me.yervant.yapatch` to the default hidden package list.

Expected v1.38 signs when `hooks.hide_accounts=1`:

```text
audit:moduleBuild=v1.38-system-server-accounts
DeviceSpoofLab-SystemAM: audit:account service ready class=...
DeviceSpoofLab-SystemAM: audit:system_server account hooks installed count=...
DeviceSpoofLab-Account: audit:account hiding delegated to system_server backend
DeviceSpoofLab-SystemAM: audit:hide-before method=getAccounts ...
```

Fallback:

- If the server hook path is unstable on a ROM, set
  `debug.system_server_package_hiding=0` and use the previous app-side backend:
  `debug.package_info_backend=ipm-proxy` or `direct`.
- For AccountManager only, set `debug.system_server_account_hiding=0` to return
  to the old app-side `AccountManager.getAccounts*` hook path.

## v1.39 system_server BatteryManager hooks

Goal:

- Move the low-risk `BatteryManager` charge/energy property spoofing out of
  scoped app processes while keeping the same result values.

Implementation:

- Added `SystemServerBatteryHooks`, loaded from `SystemServerEntry`.
- The hook waits for `batteryproperties`/`battery` services and hooks
  `getProperty(int, BatteryProperty)` in BatteryService binder classes.
- Filtering is caller-aware via `Binder.getCallingUid()` and
  `getPackagesForUid`, and only applies to packages present in `scope.list`.
- App-side `BatteryHooks` now skips local
  `android.os.BatteryManager.getIntProperty/getLongProperty` hooks when
  `debug.system_server_battery_spoofing=1`.
- The spoof surface is intentionally the same as before:
  `BATTERY_PROPERTY_CHARGE_COUNTER` and `BATTERY_PROPERTY_ENERGY_COUNTER`.

Expected v1.39 signs:

```text
audit:moduleBuild=v1.39-system-server-battery
DeviceSpoofLab-SystemBattery: audit:batteryproperties service ready class=...
DeviceSpoofLab-SystemBattery: audit:system_server battery hooks installed count=...
DeviceSpoofLab-Battery: audit:battery spoofing delegated to system_server backend
DeviceSpoofLab-SystemBattery: audit:spoof-before method=getProperty ...
```

Fallback:

- Set `debug.system_server_battery_spoofing=0` to return to the old app-side
  `BatteryManager` hook path.

## v1.40 Java hook controls

Goal:

- Make app compatibility testing easier by disabling app-process Java hooks
  without removing the app from `scope.list` and without disabling native hooks.

Config:

```ini
# Disable app-side HookEntry Java hooks for every scoped app.
debug.java_hooks=0

# Disable app-side Java hooks only for selected packages. Matching covers
# child processes, for example com.example.app also matches com.example.app:remote.
debug.java_hooks_disabled_packages=com.example.app,com.other.app

# Disable Java hooks installed inside system_server. Native hooks in scoped app
# processes are controlled separately by debug.native_hook_mode.
debug.system_server_hooks=0
```

Expected v1.40 signs:

```text
audit:moduleBuild=v1.40-java-hook-controls
audit:native hooks requested ... globalJava=0 javaDisabled=0 ...
audit:java hooks skipped by debug.java_hooks=0
audit:native hooks requested ... globalJava=1 javaDisabled=1 matched=com.example.app
audit:java hooks skipped package=com.example.app matched=com.example.app by debug.java_hooks_disabled_packages
audit:system_server hooks disabled by debug.system_server_hooks=0
```

Notes:

- `debug.java_hooks=0` and `debug.java_hooks_disabled_packages` only stop the
  app-process Java loader. Native hooks still run according to
  `debug.native_hook_mode`.
- Server-side PackageManager/Account/Battery hooks are controlled by
  `debug.system_server_hooks` plus their individual group flags.
- Reboot after changing `debug.system_server_hooks` or server-side group flags,
  because `system_server` is specialized once at boot. For app-side Java hook
  changes, force-stop and reopen the target app is usually enough.

PackageInfo debug modes:

```ini
debug.holmes.package_info_mode=getpackageinfo
debug.holmes.package_info_mode=lookup
debug.holmes.package_info_mode=visibility
debug.holmes.package_info_mode=all
```

PackageInfo strategies:

```ini
debug.holmes.package_info_strategy=passthrough
debug.holmes.package_info_strategy=after
debug.holmes.package_info_strategy=before
```

PackageInfo backends:

```ini
debug.holmes.package_info_backend=system-server
debug.holmes.package_info_backend=ipm-proxy
debug.holmes.package_info_backend=direct
```

Validated outcome:

- Direct LSPlant hook on
  `ApplicationPackageManager.getPackageInfo(String,int)` crashed Holmes even
  with `strategy=passthrough`, where the callback only logged and did not hide,
  throw, or mutate the result.
- Moving the hook to `IPackageManager` proxy fixed the crash. The proxy patched
  both `ActivityThread.sPackageManager` and the current
  `ApplicationPackageManager.mPM` instance.
- `strategy=before` is also stable with `backend=ipm-proxy`, so Holmes does not
  crash because hidden packages become `NameNotFound`/null. The crash was caused
  by the direct method hook surface, not by the package hiding behavior itself.

2026-07-06 log finding:

- `getPackageInfo(String,int)` hook installed successfully.
- LSPlant initialized and callback dispatch worked.
- No Java `FATAL EXCEPTION` appeared in the captured log.
- Holmes queried these package names with `hide=false`:
  - `io.github.vvb2060.magisk`
  - `io.github.huskydg.magisk`
  - `bin.mt.plus`
- These are now included in the built-in hidden package list, and the getter
  merges built-ins with any existing profile value so older profile files still
  receive the expanded list.
- v1.33 changes Holmes default to `package_info_strategy=passthrough` to test
  the user's hypothesis that disabling the behavior around `getPackageInfo`
  resolves the failure while keeping the hook installed.
- v1.33 still crashed with `strategy=passthrough`. The hook callback ran and
  did not throw, hide, or mutate the return value, so the risky part is likely
  the direct LSPlant hook on
  `ApplicationPackageManager.getPackageInfo(String,int)` itself.
- v1.34 changes only Holmes to `package_info_backend=ipm-proxy`. The hook is
  kept logically, but the interception point moves to
  `ActivityThread.sPackageManager` / `ApplicationPackageManager.mPM`.
- v1.34 with `package_info_backend=ipm-proxy` opened cleanly with
  `package_info_strategy=passthrough`. The log showed:

```text
audit:ipm-proxy installed original=android.content.pm.IPackageManager$Stub$Proxy proxy=$Proxy2 patchedStatic=true patchedCurrentPm=true
audit:lookup phase=ipm-before method=getPackageInfo ... backend=ipm-proxy
```

- `package_info_strategy=before` was then tested and remained stable. Keep this
  as the Holmes package-info strategy unless a later app-specific signal proves
  it needs a softer `after`/`passthrough` mode.

2026-07-06 maps detection finding:

- A target app detected:
  - `/data/adb/modules/devicespooflab_hooks/system/lib64/liblsplant.so`
  - `lsplant` in native maps
  - executable anonymous mappings such as `[anonymous]` ranges
- Root cause: `java_loader.cpp` preloaded `liblsplant.so`, and
  `libds_java_hooks.so` also linked against `liblsplant.so`, even for Holmes
  where the active package-info backend was already `ipm-proxy` and did not
  need LSPlant direct method hooks.
- v1.35 adds a Holmes-only LSPlant-free loader path for
  `debug.holmes.java_stage=pkginfo` with
  `debug.holmes.package_info_backend=ipm-proxy`.
- v1.36 generalizes the idea by moving package/root-app hiding to
  `system_server` with `debug.package_info_backend=system-server`.
- Expected v1.35 signs:

```text
audit:moduleBuild=v1.35-holmes-lsplant-free
audit:InstallJavaHooks holmes lsplant-free stage=pkginfo backend=ipm-proxy
audit:InstallJavaHooks helper dlopen skipped for lsplant-free stage
audit:InstallJavaHooks JNI_OnLoad skipped for lsplant-free stage
audit:InstallJavaHooks xposed natives skipped for lsplant-free stage
```

- In this mode there should be no `dlopen helper ok path=...libds_java_hooks.so`
  and no `liblsplant.so` mapping caused by DeviceSpoofLab in Holmes.
- If executable anonymous mappings still appear after v1.35, compare with
  `debug.holmes.native_mode=off`. Remaining anonymous exec regions may come
  from ART/JIT, WebView, Zygisk internals, or other modules, not the
  LSPlant-backed Java hook path.

## Debug decision tree for new apps

1. Confirm scope/profile:

```text
scopeOk=1 inScope=1
profileOk=1 profileSize=...
```

2. Confirm module version:

```text
audit:moduleBuild=...
```

3. If app crashes with full hooks, try native off:

```ini
debug.<app>.native_mode=off
```

4. If off is stable, test immediate native levels:

```ini
native_mode=get
native_mode=callback
native_mode=find
native_mode=core
native_mode=system
native_mode=dlopen
native_mode=full
```

5. If `get` crashes, suspect PLT/GOT detection. Try late modes:

```ini
native_mode=late-get
native_mode=late-core
native_mode=late-full
```

6. Only after native stability is known, test Java stages:

```ini
java_stage=load
java_stage=static
java_stage=basic
java_stage=all
```

## Current compat flags

RiskDetector:

```ini
debug.riskdetector.java_stage=all
debug.riskdetector.native_mode=core
debug.riskdetector.native_delay_ms=0
```

NativeCheck:

```ini
debug.nativecheck.java_stage=none
debug.nativecheck.native_mode=late-full
debug.nativecheck.native_delay_ms=3000
```

Holmes:

```ini
debug.holmes.java_stage=pkginfo
debug.holmes.native_mode=late-full
debug.holmes.native_delay_ms=3000
debug.holmes.package_info_mode=getpackageinfo
debug.holmes.package_info_strategy=before
debug.holmes.package_info_backend=system-server
debug.holmes.package_info_trace=1
```

## Native-first hardening status (2026-10-21)

Implemented in the working tree:

- Native registry (`module_registry.cpp`) scans executable mappings, dedupes by
  `(dev, inode)`, skips provider modules per hook group, and re-registers hooks
  after late `dlopen`/`android_dlopen_ext`.
- Property hooks register on every importer module; synthetic `prop_info`
  values are enabled only when both find and read hooks have valid originals.
- Kernel/CPU/memory/storage hooks cover `uname`, `gethostname`, `getifaddrs`,
  `sysconf`, `sysinfo`, `sched_getaffinity`, `ioctl(SIOCGIFHWADDR)`, `statfs`,
  and `statvfs`. `/proc/stat`, `/proc/cmdline`, `/proc/bootconfig`, and
  device-tree model/compatible reads are filtered by the opt-in path guard.
- GLES/EGL hooks spoof `GL_VENDOR`, `GL_RENDERER`, optional `GL_VERSION`,
  optional EGL vendor/version, and wrap `eglGetProcAddress` for `glGetString`,
  `glGetStringi`, and `eglQueryString`. Extension lists and Vulkan are not
  synthesized.
- Fake SIM is opt-in (`fake_sim.enabled=1`). It creates one synthetic
  `SubscriptionInfo` through `CREATOR` on API 33+ (hidden-constructor fallback
  on older releases), reports dual-capable modem/phone counts, slot 0 READY and
  slot 1 ABSENT, fakes service/network state, returns no eUICC/EID surface, and
  fails closed if the synthetic object cannot be built.
  With it disabled, only IMEI/MEID/serial-style IDs are spoofed; SIM/IMSI/ICCID/
  phone/subscription state follows the real device.
- Settings integer hooks cover `adb_enabled`,
  `development_settings_enabled`, `device_provisioned`, and
  `stay_on_while_plugged_in`.
- Package UID queries (`getPackageGids`, `getPackagesForUid`, `getNameForUid`,
  `getNamesForUids`) filter hidden detection packages when UID query mode is
  enabled.
- CI pins `ubuntu-24.04`, uses `android-actions/setup-android@v4`, and installs
  platform 36, build-tools 34.0.0/36.0.0, NDK 26.3.11579264, and CMake 3.22.1
  explicitly. The old `@v3` setup action requested Google's removed `tools`
  package and failed before Gradle ran.

Residual bypasses/blockers:

- No local native compiler/NDK; Gradle fails with `NDK not configured. Download
  it with SDK manager. Preferred NDK version is '25.1.8937393'.`
- `getprop` child processes and raw syscalls bypass PLT hooks.
- `dlsym`-resolved GL/EGL pointers bypass the PLT hook unless routed through
  `eglGetProcAddress`.
- Vulkan and GL extension-list synthesis are deferred.
- The synthetic `SubscriptionInfo` parcel layout is version-sensitive and has
  not been verified on a device; the implementation logs and leaves real SIM
  state untouched when construction fails.
- Runtime verification requires a rooted device/emulator with Zygisk/LSPosed;
  no device was available here.
