package com.devicespooflab.hooks;

import android.app.Application;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.devicespooflab.hooks.hooks.AccountHooks;
import com.devicespooflab.hooks.hooks.AdvertisingIdHooks;
import com.devicespooflab.hooks.hooks.AppSetIdHooks;
import com.devicespooflab.hooks.hooks.BatteryHooks;
import com.devicespooflab.hooks.hooks.BuildHooks;
import com.devicespooflab.hooks.hooks.CameraHooks;
import com.devicespooflab.hooks.hooks.DisplayHooks;
import com.devicespooflab.hooks.hooks.EuiccHooks;
import com.devicespooflab.hooks.hooks.HardwareHooks;
import com.devicespooflab.hooks.hooks.InputDeviceHooks;
import com.devicespooflab.hooks.hooks.LocaleHooks;
import com.devicespooflab.hooks.hooks.MediaDrmHooks;
import com.devicespooflab.hooks.hooks.NetworkHooks;
import com.devicespooflab.hooks.hooks.PackageInfoHooks;
import com.devicespooflab.hooks.hooks.PackageManagerHooks;
import com.devicespooflab.hooks.hooks.SensorHooks;
import com.devicespooflab.hooks.hooks.SettingsHooks;
import com.devicespooflab.hooks.hooks.StorageHooks;
import com.devicespooflab.hooks.hooks.SystemPropertiesHooks;
import com.devicespooflab.hooks.hooks.TelephonyHooks;
import com.devicespooflab.hooks.hooks.WebViewHooks;
import com.devicespooflab.hooks.utils.ConfigManager;
import com.devicespooflab.hooks.xposed.LoadPackageParam;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Standalone entry point called from native (java_loader.cpp InstallJavaHooks)
 * via JNI after Zygisk postAppSpecialize / postServerSpecialize completes.
 *
 * Replaces MainHook.handleLoadPackage from the old LSPosed flow. Builds a
 * LoadPackageParam from ActivityThread static accessors (process name,
 * package name, classloader) instead of receiving one from a hook framework,
 * then runs the same hook installation chain in the same order.
 *
 * Config now comes from the root companion through the native layer
 * (ds::g_props -> NativeHooks.getAllFromNative), so the Application.attach
 * hook + XposedServiceBridge wiring from MainHook is intentionally omitted.
 */
public final class HookEntry {

    private static final String TAG = "DeviceSpoofLab";
    private static final int MAX_RUNTIME_RETRIES = 40;
    private static final long RUNTIME_RETRY_DELAY_MS = 150L;

    private static volatile boolean sInstalled = false;
    private static int sRuntimeRetries = 0;
    private static boolean sRetryScheduled = false;
    private static boolean sCrashLoggerInstalled = false;

    private HookEntry() {}

    public static synchronized void installAll() {
        installAllInternal(null);
    }

    public static synchronized void installAllWithProfile(HashMap<String, String> profile) {
        installAllInternal(profile);
    }

    private static synchronized void installAllInternal(Map<String, String> profile) {
        Log.w(TAG, "audit:HookEntry.installAll entered profileSize="
                + (profile == null ? -1 : profile.size()));
        if (sInstalled) {
            Log.w(TAG, "audit:HookEntry.installAll already ran; skipping");
            return;
        }

        try {
            ConfigManager.init();
            Log.w(TAG, "audit:ConfigManager.init ok");
        } catch (Throwable t) {
            Log.e(TAG, "ConfigManager.init failed: " + t.getMessage(), t);
            return;
        }

        if (profile != null && !profile.isEmpty()) {
            try {
                ConfigManager.setProperties(profile);
                Log.i(TAG, "Loaded profile from Zygisk core entries=" + profile.size()
                        + " imei=" + ConfigManager.getIdentifierValue("imei")
                        + " gaid=" + ConfigManager.getIdentifierValue("gaid"));
                Log.w(TAG, "audit:profile injected entries=" + profile.size()
                        + " brand=" + ConfigManager.getBuildBrand()
                        + " model=" + ConfigManager.getBuildModel()
                        + " fp=" + ConfigManager.getBuildFingerprint());
            } catch (Throwable t) {
                Log.w(TAG, "profile injection failed: " + t.getMessage());
            }
        } else {
            // Companion already pushed the profile through native; pull it into
            // the Java config layer so getter methods return spoofed values.
            try {
                boolean loaded = ConfigManager.loadFromRemotePreferences();
                Log.i(TAG, "Loaded profile from native companion=" + loaded
                        + " imei=" + ConfigManager.getIdentifierValue("imei")
                        + " gaid=" + ConfigManager.getIdentifierValue("gaid"));
            } catch (Throwable t) {
                Log.w(TAG, "loadFromRemotePreferences failed: " + t.getMessage());
            }
        }

        LoadPackageParam lpparam = buildLoadPackageParam();
        boolean verbose = ConfigManager.isVerboseLoggingEnabled();
        final int realDeviceSdk = Build.VERSION.SDK_INT;
        Log.w(TAG, "audit:installAll runtime pkg=" + lpparam.packageName
                + " proc=" + lpparam.processName
                + " classLoader=" + lpparam.classLoader
                + " appInfo=" + (lpparam.appInfo == null ? "null" : lpparam.appInfo.packageName)
                + " sdk=" + realDeviceSdk);
        if (lpparam.packageName != null && !lpparam.packageName.isEmpty()) {
            installCrashLoggerIfNeeded(lpparam.packageName);
        }
        if (!isRuntimeReady(lpparam)) {
            scheduleRuntimeRetry(profile, lpparam);
            return;
        }
        if (Looper.getMainLooper() == null) {
            Log.w(TAG, "audit:runtime ready without main looper; installing Java hooks on current thread");
        } else if (Looper.myLooper() != Looper.getMainLooper()) {
            Log.w(TAG, "audit:runtime ready on worker; installing Java hooks immediately");
        }
        if (lpparam.appInfo == null) {
            Log.w(TAG, "audit:runtime ready without appInfo; installing early Java hooks for "
                    + lpparam.packageName);
        }

        sInstalled = true;
        sRuntimeRetries = 0;
        sRetryScheduled = false;
        Log.i(TAG, "installAll start pkg=" + lpparam.packageName
                + " proc=" + lpparam.processName
                + " verbose=" + verbose);
        Log.w(TAG, "audit:runtime ready; installing Java hooks for " + lpparam.packageName);

        final boolean isOwnPackage =
                ConfigManager.isOwnPackageProcess(lpparam.processName)
                        || "com.devicespooflab.hooks".equals(lpparam.packageName);
        final boolean compatibilityMode = isCompatibilityModePackage(lpparam.packageName);
        if (compatibilityMode) {
            String compatStage = getCompatibilityJavaStage(lpparam.packageName);
            Log.w(TAG, "audit:compat mode active for " + lpparam.packageName);
            Log.w(TAG, "audit:compat java debug stage=" + compatStage);
            runCompatibilityDebugStage(compatStage, lpparam, realDeviceSdk, verbose);
            Log.w(TAG, "audit:NativeHooks skipped in compat mode; Zygisk native hooks already active");
            Log.w(TAG, "audit:All hooks initialized for " + lpparam.packageName
                    + " compat=3 stage=" + compatStage);
            return;
        }

        // BuildHooks first so direct Build.* reads see spoofed values.
        runHook("BuildHooks", verbose, () -> BuildHooks.hook(lpparam));

        if (!isOwnPackage) {
            runHook("SystemPropertiesHooks", verbose,
                    () -> SystemPropertiesHooks.hook(lpparam));
        } else {
            logSkip(verbose, "SystemPropertiesHooks");
        }

        runHook("HardwareHooks",   verbose, () -> HardwareHooks.hook(lpparam));
        runHook("TelephonyHooks",  verbose, () -> TelephonyHooks.hook(lpparam));
        runHook("SettingsHooks",   verbose, () -> SettingsHooks.hook(lpparam));

        runHook("AdvertisingIdHooks", verbose, () -> AdvertisingIdHooks.hook(lpparam));

        if (realDeviceSdk >= 30) {
            runHook("AppSetIdHooks", verbose,
                    () -> AppSetIdHooks.hook(lpparam, realDeviceSdk));
        }

        runHook("MediaDrmHooks",        verbose, () -> MediaDrmHooks.hook(lpparam));
        runHook("WebViewHooks",         verbose, () -> WebViewHooks.hook(lpparam));
        runHook("PackageManagerHooks",  verbose, () -> PackageManagerHooks.hook(lpparam));
        runHook("NetworkHooks",         verbose, () -> NetworkHooks.hook(lpparam));

        if (!isOwnPackage) {
            runHook("DisplayHooks", verbose, () -> DisplayHooks.hook(lpparam));
        } else {
            logSkip(verbose, "DisplayHooks");
        }

        runHook("SensorHooks",  verbose, () -> SensorHooks.hook(lpparam));
        runHook("CameraHooks",  verbose, () -> CameraHooks.hook(lpparam));
        runHook("StorageHooks", verbose, () -> StorageHooks.hook(lpparam));

        if (!isOwnPackage) {
            runHook("LocaleHooks", verbose, () -> LocaleHooks.hook(lpparam));
        } else {
            logSkip(verbose, "LocaleHooks");
        }

        if (ConfigManager.isHideAccountsEnabled()) {
            runHook("AccountHooks", verbose, () -> AccountHooks.hook(lpparam));
        } else if (verbose) {
            Log.i(TAG, "AccountHooks skipped (hooks.hide_accounts=0)");
        }

        runHook("PackageInfoHooks", verbose, () -> PackageInfoHooks.hook(lpparam));
        runHook("BatteryHooks",     verbose, () -> BatteryHooks.hook(lpparam));

        if (realDeviceSdk >= 28) {
            runHook("EuiccHooks", verbose,
                    () -> EuiccHooks.hook(lpparam, realDeviceSdk));
        }

        runHook("InputDeviceHooks", verbose, () -> InputDeviceHooks.hook(lpparam));

        if (!isOwnPackage) {
            try {
                boolean ok = NativeHooks.tryInstall(ConfigManager.getAllSpoofedProperties());
                Log.w(TAG, ok
                        ? "audit:NativeHooks loaded"
                        : "audit:NativeHooks unavailable (Java-only spoofing active)");
            } catch (Throwable t) {
                Log.w(TAG, "NativeHooks failed: " + t.getMessage());
            }
        } else {
            logSkip(verbose, "NativeHooks");
        }

        Log.w(TAG, "audit:All hooks initialized for " + lpparam.packageName);
    }

    private interface HookTask {
        void run() throws Throwable;
    }

    private static void runHook(String name, boolean verbose, HookTask task) {
        try {
            task.run();
            Log.w(TAG, "audit:" + name + " loaded");
        } catch (Throwable t) {
            Log.w(TAG, name + " failed: " + t.getMessage(), t);
        }
    }

    private static void logSkip(boolean verbose, String name) {
        Log.w(TAG, "audit:" + name + " skipped for module process");
    }

    private static boolean isCompatibilityModePackage(String packageName) {
        return "io.liankong.riskdetector".equals(packageName)
                || "com.reveny.nativecheck".equals(packageName)
                || "me.garfieldhan.holmes".equals(packageName);
    }

    private static String getCompatibilityJavaStage(String packageName) {
        String key;
        if ("io.liankong.riskdetector".equals(packageName)) {
            key = "debug.riskdetector.java_stage";
        } else if ("com.reveny.nativecheck".equals(packageName)) {
            key = "debug.nativecheck.java_stage";
        } else if ("me.garfieldhan.holmes".equals(packageName)) {
            key = "debug.holmes.java_stage";
        } else {
            key = "debug.compat.java_stage";
        }
        String stage = ConfigManager.getRawProperty(key);
        if (stage == null) {
            return "none";
        }
        stage = stage.trim().toLowerCase(java.util.Locale.ROOT);
        return stage.isEmpty() ? "none" : stage;
    }

    private static void runCompatibilityDebugStage(String stage,
                                                   LoadPackageParam lpparam,
                                                   int realDeviceSdk,
                                                   boolean verbose) {
        if ("none".equals(stage) || "0".equals(stage) || "off".equals(stage)
                || "false".equals(stage)) {
            Log.w(TAG, "audit:compat Java stage=none; no Java helper actions for "
                    + lpparam.packageName);
            return;
        }

        Log.w(TAG, "audit:compat stage install begin stage=" + stage);
        switch (stage) {
            case "preload":
            case "deps":
            case "dlopen":
            case "so":
            case "helper":
            case "jni":
            case "onload":
            case "classloader":
            case "loader":
            case "register-core":
            case "core":
            case "register-xposed":
            case "register":
            case "natives":
            case "entry":
            case "hookentry":
                Log.w(TAG, "audit:compat loader stage reached Java unexpectedly stage="
                        + stage);
                break;
            case "load":
            case "java":
                Log.w(TAG, "audit:compat Java helper loaded; no Java hook/static mutation requested");
                break;
            case "static":
            case "build-static":
                runHook("BuildStaticFields", verbose,
                        () -> BuildHooks.refreshStaticFields(lpparam.classLoader));
                break;
            case "static-build":
            case "build-fields":
                runHook("BuildFieldsOnly", verbose,
                        () -> BuildHooks.refreshBuildFieldsOnly(lpparam.classLoader));
                break;
            case "static-version":
            case "version-fields":
                runHook("BuildVersionFieldsOnly", verbose,
                        () -> BuildHooks.refreshVersionFieldsOnly(lpparam.classLoader));
                break;
            case "build":
                runHook("BuildHooks", verbose, () -> BuildHooks.hook(lpparam));
                break;
            case "system":
            case "systemprops":
                runHook("SystemPropertiesHooks", verbose,
                        () -> SystemPropertiesHooks.hook(lpparam));
                break;
            case "hardware":
                runHook("HardwareHooks", verbose, () -> HardwareHooks.hook(lpparam));
                break;
            case "telephony":
                runHook("TelephonyHooks", verbose, () -> TelephonyHooks.hook(lpparam));
                break;
            case "settings":
                runHook("SettingsHooks", verbose, () -> SettingsHooks.hook(lpparam));
                break;
            case "pm":
            case "package":
            case "packagemanager":
                runHook("PackageManagerHooks", verbose, () -> PackageManagerHooks.hook(lpparam));
                break;
            case "network":
                runHook("NetworkHooks", verbose, () -> NetworkHooks.hook(lpparam));
                break;
            case "ids":
                runHook("AdvertisingIdHooks", verbose, () -> AdvertisingIdHooks.hook(lpparam));
                if (realDeviceSdk >= 30) {
                    runHook("AppSetIdHooks", verbose,
                            () -> AppSetIdHooks.hook(lpparam, realDeviceSdk));
                }
                break;
            case "media":
                runHook("MediaDrmHooks", verbose, () -> MediaDrmHooks.hook(lpparam));
                break;
            case "webview":
                runHook("WebViewHooks", verbose, () -> WebViewHooks.hook(lpparam));
                break;
            case "display":
                runHook("DisplayHooks", verbose, () -> DisplayHooks.hook(lpparam));
                break;
            case "camera":
                runHook("CameraHooks", verbose, () -> CameraHooks.hook(lpparam));
                break;
            case "storage":
                runHook("StorageHooks", verbose, () -> StorageHooks.hook(lpparam));
                break;
            case "locale":
                runHook("LocaleHooks", verbose, () -> LocaleHooks.hook(lpparam));
                break;
            case "pkginfo":
            case "packageinfo":
                runHook("PackageInfoHooks", verbose, () -> PackageInfoHooks.hook(lpparam));
                break;
            case "battery":
                runHook("BatteryHooks", verbose, () -> BatteryHooks.hook(lpparam));
                break;
            case "euicc":
                if (realDeviceSdk >= 28) {
                    runHook("EuiccHooks", verbose,
                            () -> EuiccHooks.hook(lpparam, realDeviceSdk));
                }
                break;
            case "input":
                runHook("InputDeviceHooks", verbose, () -> InputDeviceHooks.hook(lpparam));
                break;
            case "basic":
                runHook("BuildHooks", verbose, () -> BuildHooks.hook(lpparam));
                runHook("SystemPropertiesHooks", verbose,
                        () -> SystemPropertiesHooks.hook(lpparam));
                runHook("HardwareHooks", verbose, () -> HardwareHooks.hook(lpparam));
                runHook("TelephonyHooks", verbose, () -> TelephonyHooks.hook(lpparam));
                runHook("SettingsHooks", verbose, () -> SettingsHooks.hook(lpparam));
                runHook("PackageManagerHooks", verbose, () -> PackageManagerHooks.hook(lpparam));
                runHook("NetworkHooks", verbose, () -> NetworkHooks.hook(lpparam));
                break;
            case "all":
                runHook("BuildHooks", verbose, () -> BuildHooks.hook(lpparam));
                runHook("SystemPropertiesHooks", verbose,
                        () -> SystemPropertiesHooks.hook(lpparam));
                runHook("HardwareHooks", verbose, () -> HardwareHooks.hook(lpparam));
                runHook("TelephonyHooks", verbose, () -> TelephonyHooks.hook(lpparam));
                runHook("SettingsHooks", verbose, () -> SettingsHooks.hook(lpparam));
                runHook("AdvertisingIdHooks", verbose, () -> AdvertisingIdHooks.hook(lpparam));
                if (realDeviceSdk >= 30) {
                    runHook("AppSetIdHooks", verbose,
                            () -> AppSetIdHooks.hook(lpparam, realDeviceSdk));
                }
                runHook("MediaDrmHooks", verbose, () -> MediaDrmHooks.hook(lpparam));
                runHook("WebViewHooks", verbose, () -> WebViewHooks.hook(lpparam));
                runHook("PackageManagerHooks", verbose, () -> PackageManagerHooks.hook(lpparam));
                runHook("NetworkHooks", verbose, () -> NetworkHooks.hook(lpparam));
                runHook("DisplayHooks", verbose, () -> DisplayHooks.hook(lpparam));
                runHook("SensorHooks", verbose, () -> SensorHooks.hook(lpparam));
                runHook("CameraHooks", verbose, () -> CameraHooks.hook(lpparam));
                runHook("StorageHooks", verbose, () -> StorageHooks.hook(lpparam));
                runHook("LocaleHooks", verbose, () -> LocaleHooks.hook(lpparam));
                runHook("PackageInfoHooks", verbose, () -> PackageInfoHooks.hook(lpparam));
                runHook("BatteryHooks", verbose, () -> BatteryHooks.hook(lpparam));
                if (realDeviceSdk >= 28) {
                    runHook("EuiccHooks", verbose,
                            () -> EuiccHooks.hook(lpparam, realDeviceSdk));
                }
                runHook("InputDeviceHooks", verbose, () -> InputDeviceHooks.hook(lpparam));
                break;
            default:
                Log.w(TAG, "audit:compat unknown java stage=" + stage
                        + "; valid=none,preload,dlopen,jni,classloader,register-core,register-xposed,entry,load,static,static-build,static-version,build,system,hardware,telephony,settings,pm,network,ids,media,webview,display,camera,storage,locale,pkginfo,battery,euicc,input,basic,all; no Java action taken");
                break;
        }
        Log.w(TAG, "audit:compat stage install end stage=" + stage);
    }

    private static void installCrashLoggerIfNeeded(String packageName) {
        if (sCrashLoggerInstalled) {
            return;
        }
        sCrashLoggerInstalled = true;
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Log.e(TAG, "audit:uncaught pkg=" + packageName
                    + " thread=" + (thread == null ? "null" : thread.getName())
                    + " error=" + throwable, throwable);
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }

    private static boolean isRuntimeReady(LoadPackageParam lp) {
        return lp != null
                && lp.packageName != null
                && !lp.packageName.isEmpty()
                && hasUsableAppClassLoader(lp);
    }

    private static boolean hasUsableAppClassLoader(LoadPackageParam lp) {
        if (lp.classLoader == null) {
            return false;
        }
        if (lp.appInfo != null) {
            return true;
        }
        String loader = String.valueOf(lp.classLoader);
        return loader.contains("/data/app/")
                || loader.contains("/mnt/expand/")
                || loader.contains(".apk");
    }

    private static void scheduleRuntimeRetry(Map<String, String> profile, LoadPackageParam lp) {
        if (sRuntimeRetries >= MAX_RUNTIME_RETRIES) {
            Log.w(TAG, "audit:runtime not ready after retries; Java hooks skipped pkg="
                    + (lp == null ? "null" : lp.packageName)
                    + " proc=" + (lp == null ? "null" : lp.processName)
                    + " appInfo=" + (lp == null || lp.appInfo == null ? "null" : lp.appInfo.packageName)
                    + " classLoader=" + (lp == null ? "null" : lp.classLoader));
            return;
        }
        if (sRetryScheduled) {
            Log.w(TAG, "audit:runtime retry already scheduled attempt="
                    + sRuntimeRetries + "/" + MAX_RUNTIME_RETRIES);
            return;
        }

        final HashMap<String, String> retryProfile =
                profile == null ? null : new HashMap<>(profile);
        sRuntimeRetries++;
        sRetryScheduled = true;
        Looper mainLooper = Looper.getMainLooper();
        Log.w(TAG, "audit:runtime not ready; scheduling retry="
                + sRuntimeRetries + "/" + MAX_RUNTIME_RETRIES
                + " via=" + (mainLooper == null ? "thread" : "handler")
                + " pkg=" + (lp == null ? "null" : lp.packageName)
                + " proc=" + (lp == null ? "null" : lp.processName)
                + " appInfo=" + (lp == null || lp.appInfo == null ? "null" : lp.appInfo.packageName)
                + " classLoader=" + (lp == null ? "null" : lp.classLoader));
        Runnable retry = () -> {
            synchronized (HookEntry.class) {
                sRetryScheduled = false;
            }
            installAllInternal(retryProfile);
        };
        if (mainLooper != null) {
            new Handler(mainLooper).postDelayed(retry, RUNTIME_RETRY_DELAY_MS);
            return;
        }

        Thread retryThread = new Thread(() -> {
            try {
                Thread.sleep(RUNTIME_RETRY_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            retry.run();
        }, "DeviceSpoofLab-runtime-retry");
        retryThread.start();
    }

    /**
     * Build the LoadPackageParam from runtime state. Runs inside the target
     * process at postAppSpecialize/postServerSpecialize time, so ActivityThread
     * is initialized but Application.attach may or may not have completed.
     * appInfo can therefore be null. A context classloader that only contains
     * directory "." is still zygote/early runtime state, so Java hooks wait
     * until Application info or an APK-backed classloader is visible.
     */
    private static LoadPackageParam buildLoadPackageParam() {
        LoadPackageParam lp = new LoadPackageParam();
        lp.packageName = readActivityThreadString("currentPackageName");
        lp.processName = readActivityThreadString("currentProcessName");
        Application app = readCurrentApplication();
        if (app != null) {
            lp.classLoader = app.getClassLoader();
            lp.appInfo = app.getApplicationInfo();
            if ((lp.packageName == null || lp.packageName.isEmpty()) && lp.appInfo != null) {
                lp.packageName = lp.appInfo.packageName;
            }
        }
        if (lp.classLoader == null) {
            lp.classLoader = Thread.currentThread().getContextClassLoader();
        }
        if (lp.packageName == null) {
            // system_server has no package; fall back to processName so the
            // own-package guard below still does something sensible.
            lp.packageName = lp.processName != null ? lp.processName : "";
        }
        if (lp.processName == null) {
            lp.processName = lp.packageName;
        }
        return lp;
    }

    private static String readActivityThreadString(String methodName) {
        try {
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            Method m = atCls.getMethod(methodName);
            Object value = m.invoke(null);
            return value instanceof String ? (String) value : null;
        } catch (Throwable t) {
            Log.w(TAG, "ActivityThread." + methodName + "() failed: " + t.getMessage());
            return null;
        }
    }

    private static Application readCurrentApplication() {
        try {
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            Object currentApp = atCls.getMethod("currentApplication").invoke(null);
            if (currentApp instanceof Application) {
                return (Application) currentApp;
            }
            Object thread = atCls.getMethod("currentActivityThread").invoke(null);
            if (thread == null) return null;
            Object app = atCls.getMethod("getApplication").invoke(thread);
            if (app instanceof Application) {
                return (Application) app;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }
}
