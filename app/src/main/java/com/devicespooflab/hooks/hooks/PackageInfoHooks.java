package com.devicespooflab.hooks.hooks;

import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import com.devicespooflab.hooks.utils.ConfigManager;

import com.devicespooflab.hooks.xposed.XC_MethodHook;
import com.devicespooflab.hooks.xposed.XposedBridge;
import com.devicespooflab.hooks.xposed.XposedHelpers;
import com.devicespooflab.hooks.xposed.LoadPackageParam;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

// Install times reported as 60-120 days ago, derived from android_id so the
// per-install value stays stable across reads.
public class PackageInfoHooks {

    private static final String TAG = "DeviceSpoofLab-PackageInfo";
    private static final long DAY_MS = 86_400_000L;

    public static void hook(LoadPackageParam lpparam) {
        PackageInfoPlan plan = PackageInfoPlan.from(lpparam);
        XposedBridge.log(TAG + ": audit:plan pkg="
                + (lpparam == null ? "null" : lpparam.packageName)
                + " mode=" + plan.mode + " trace=" + plan.trace
                + " strategy=" + plan.strategy
                + " backend=" + plan.backend
                + " getPackageInfo=" + plan.getPackageInfo
                + " applicationInfo=" + plan.applicationInfo
                + " packageUid=" + plan.packageUid
                + " lists=" + plan.lists
                + " installer=" + plan.installer
                + " source=" + plan.source
                + " times=" + plan.times);
        hookPackageVisibility(lpparam, plan);
        if (plan.times) {
            hookPackageInfoFields(lpparam);
        } else {
            XposedBridge.log(TAG + ": getPackageInfo time patch disabled for stability");
        }
        if (plan.installer) {
            hookGetInstallerPackageName(lpparam);
        }
        if (plan.source) {
            hookGetInstallSourceInfo(lpparam);
        }
    }

    private static void hookPackageVisibility(LoadPackageParam lpparam,
                                              PackageInfoPlan plan) {
        if (!ConfigManager.isHideDetectionPackagesEnabled()) {
            XposedBridge.log(TAG + ": package visibility hiding disabled");
            return;
        }
        if (plan.useSystemServer()) {
            XposedBridge.log(TAG + ": audit:package visibility delegated to system_server backend");
            return;
        }
        Class<?> appPm = XposedHelpers.findClassIfExists(
                "android.app.ApplicationPackageManager", lpparam.classLoader);
        if (appPm == null) return;

        if (plan.useIpmProxy()) {
            installIpmProxy(lpparam, plan);
            return;
        }

        if (plan.getPackageInfo) {
            hookPackageLookup(appPm, lpparam, plan, "getPackageInfo",
                    "android.content.pm.PackageManager$PackageInfoFlags");
        }
        if (plan.applicationInfo) {
            hookPackageLookup(appPm, lpparam, plan, "getApplicationInfo",
                    "android.content.pm.PackageManager$ApplicationInfoFlags");
        }
        if (plan.packageUid) {
            hookPackageLookup(appPm, lpparam, plan, "getPackageUid",
                    "android.content.pm.PackageManager$PackageInfoFlags");
        }
        if (plan.lists) {
            hookInstalledLists(appPm, lpparam, plan);
        }
    }

    private static void installIpmProxy(LoadPackageParam lpparam, PackageInfoPlan plan) {
        try {
            if (!plan.getPackageInfo && !plan.applicationInfo && !plan.packageUid) {
                XposedBridge.log(TAG + ": audit:ipm-proxy skipped; no supported lookup enabled");
                return;
            }
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object original = readSystemPackageManager(activityThread);
            if (original == null) {
                original = readCurrentApplicationPackageManagerBackend(activityThread);
            }
            if (original == null) {
                XposedBridge.log(TAG + ": audit:ipm-proxy failed; package manager backend is null");
                return;
            }
            if (Proxy.isProxyClass(original.getClass())
                    && original.getClass().getName().contains("$Proxy")) {
                XposedBridge.log(TAG + ": audit:ipm-proxy existing proxy detected class="
                        + original.getClass().getName());
            }
            Class<?> iPackageManager = Class.forName("android.content.pm.IPackageManager");
            Object proxy = Proxy.newProxyInstance(
                    iPackageManager.getClassLoader(),
                    new Class<?>[]{iPackageManager},
                    new PackageManagerProxy(original, lpparam, plan));
            boolean patchedStatic = setStaticFieldQuiet(activityThread, "sPackageManager", proxy);
            boolean patchedCurrent = patchCurrentApplicationPackageManager(activityThread, proxy);
            if (!patchedStatic && !patchedCurrent) {
                XposedBridge.log(TAG + ": audit:ipm-proxy failed; no package manager slot patched");
                return;
            }
            XposedBridge.log(TAG + ": audit:ipm-proxy installed original="
                    + original.getClass().getName()
                    + " proxy=" + proxy.getClass().getName()
                    + " patchedStatic=" + patchedStatic
                    + " patchedCurrentPm=" + patchedCurrent);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": audit:ipm-proxy failed: " + t);
        }
    }

    private static Object readCurrentApplicationPackageManagerBackend(Class<?> activityThread) {
        try {
            Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
            currentApplication.setAccessible(true);
            Object app = currentApplication.invoke(null);
            if (!(app instanceof Application)) {
                return null;
            }
            Object packageManager = ((Application) app).getPackageManager();
            if (packageManager == null) {
                return null;
            }
            Field f = findField(packageManager.getClass(), "mPM");
            return f.get(packageManager);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object readSystemPackageManager(Class<?> activityThread) {
        Object pm = getStaticFieldQuiet(activityThread, "sPackageManager");
        if (pm != null) {
            return pm;
        }
        try {
            Method m = activityThread.getDeclaredMethod("getPackageManager");
            m.setAccessible(true);
            return m.invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean patchCurrentApplicationPackageManager(Class<?> activityThread,
                                                                 Object proxy) {
        try {
            Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
            currentApplication.setAccessible(true);
            Object app = currentApplication.invoke(null);
            if (!(app instanceof Application)) {
                return false;
            }
            Object packageManager = ((Application) app).getPackageManager();
            if (packageManager == null) {
                return false;
            }
            setField(packageManager, "mPM", proxy);
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": audit:ipm-proxy current PM patch failed: " + t);
            return false;
        }
    }

    private static Object getStaticFieldQuiet(Class<?> clazz, String fieldName) {
        try {
            Field f = findField(clazz, fieldName);
            return f.get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean setStaticFieldQuiet(Class<?> clazz, String fieldName, Object value) {
        try {
            Field f = findField(clazz, fieldName);
            f.set(null, value);
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": audit:ipm-proxy static patch failed: " + t);
            return false;
        }
    }

    private static void setField(Object obj, String fieldName, Object value) throws Throwable {
        Field f = findField(obj.getClass(), fieldName);
        f.set(obj, value);
    }

    private static Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        Class<?> c = clazz;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(clazz.getName() + "." + fieldName);
    }

    private static final class PackageManagerProxy implements InvocationHandler {
        private final Object original;
        private final LoadPackageParam lpparam;
        private final PackageInfoPlan plan;

        PackageManagerProxy(Object original, LoadPackageParam lpparam, PackageInfoPlan plan) {
            this.original = original;
            this.lpparam = lpparam;
            this.plan = plan;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return invokeObjectMethod(proxy, method, args);
            }
            String methodName = method.getName();
            String packageName = firstStringArg(args);
            boolean supported = isSupportedLookup(methodName);
            boolean hide = supported && shouldHidePackage(lpparam, packageName);
            if (supported) {
                trace(plan, "ipm-before", methodName, packageName, hide);
            }
            if (hide && plan.hideBefore()) {
                trace(plan, "ipm-hide-before", methodName, packageName, true);
                return hiddenResult(method);
            }
            Object result = invokeOriginal(method, args);
            if (hide && plan.hideAfter()) {
                trace(plan, "ipm-hide-after", methodName, packageName, true);
                return hiddenResult(method);
            }
            return result;
        }

        private boolean isSupportedLookup(String methodName) {
            if (plan.getPackageInfo && "getPackageInfo".equals(methodName)) {
                return true;
            }
            if (plan.applicationInfo && "getApplicationInfo".equals(methodName)) {
                return true;
            }
            return plan.packageUid && "getPackageUid".equals(methodName);
        }

        private Object invokeOriginal(Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(original, args);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                throw cause != null ? cause : e;
            }
        }
    }

    private static Object invokeObjectMethod(Object proxy, Method method, Object[] args) {
        String name = method.getName();
        if ("toString".equals(name)) {
            return "DeviceSpoofLab-IPackageManagerProxy";
        }
        if ("hashCode".equals(name)) {
            return System.identityHashCode(proxy);
        }
        if ("equals".equals(name)) {
            return args != null && args.length == 1 && proxy == args[0];
        }
        return null;
    }

    private static Object hiddenResult(Method method) {
        Class<?> returnType = method.getReturnType();
        if (returnType == int.class || returnType == Integer.class) {
            return -1;
        }
        if (returnType == long.class || returnType == Long.class) {
            return -1L;
        }
        if (returnType == boolean.class || returnType == Boolean.class) {
            return false;
        }
        return null;
    }

    private static void hookPackageLookup(Class<?> appPm,
                                          LoadPackageParam lpparam,
                                          PackageInfoPlan plan,
                                          String methodName,
                                          String flagsClassName) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                String packageName = firstStringArg(param);
                boolean hide = shouldHidePackage(lpparam, packageName);
                trace(plan, "before", methodName, packageName, hide);
                if (hide && plan.hideBefore()) {
                    trace(plan, "throw-before", methodName, packageName, true);
                    param.setThrowable(new PackageManager.NameNotFoundException(packageName));
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!plan.hideAfter()) return;
                String packageName = firstStringArg(param);
                boolean hide = shouldHidePackage(lpparam, packageName);
                trace(plan, "after", methodName, packageName, hide);
                if (hide) {
                    trace(plan, "throw-after", methodName, packageName, true);
                    param.setThrowable(new PackageManager.NameNotFoundException(packageName));
                }
            }
        };

        try {
            XposedHelpers.findAndHookMethod(appPm, methodName,
                    String.class, int.class, hook);
        } catch (Throwable t) { logFail(methodName + "(String,int)", t); }

        try {
            Class<?> flags = XposedHelpers.findClassIfExists(
                    flagsClassName, lpparam.classLoader);
            if (flags != null) {
                XposedHelpers.findAndHookMethod(appPm, methodName,
                        String.class, flags, hook);
            }
        } catch (Throwable t) { /* Android 13+ overload may not exist here */ }
    }

    private static void hookInstalledLists(Class<?> appPm, LoadPackageParam lpparam,
                                           PackageInfoPlan plan) {
        XC_MethodHook listFilter = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object result = param.getResult();
                if (result instanceof List<?>) {
                    if (plan.trace) {
                        XposedBridge.log(TAG + ": audit:listFilter method="
                                + param.method + " size=" + ((List<?>) result).size());
                    }
                    param.setResult(filterPackageList(lpparam, (List<?>) result));
                }
            }
        };

        try {
            XposedHelpers.findAndHookMethod(appPm, "getInstalledPackages",
                    int.class, listFilter);
        } catch (Throwable t) { logFail("getInstalledPackages(int)", t); }

        try {
            XposedHelpers.findAndHookMethod(appPm, "getInstalledApplications",
                    int.class, listFilter);
        } catch (Throwable t) { logFail("getInstalledApplications(int)", t); }

        try {
            Class<?> packageInfoFlags = XposedHelpers.findClassIfExists(
                    "android.content.pm.PackageManager$PackageInfoFlags", lpparam.classLoader);
            if (packageInfoFlags != null) {
                XposedHelpers.findAndHookMethod(appPm, "getInstalledPackages",
                        packageInfoFlags, listFilter);
            }
        } catch (Throwable t) { /* Android 13+ overload may not exist here */ }

        try {
            Class<?> applicationInfoFlags = XposedHelpers.findClassIfExists(
                    "android.content.pm.PackageManager$ApplicationInfoFlags", lpparam.classLoader);
            if (applicationInfoFlags != null) {
                XposedHelpers.findAndHookMethod(appPm, "getInstalledApplications",
                        applicationInfoFlags, listFilter);
            }
        } catch (Throwable t) { /* Android 13+ overload may not exist here */ }
    }

    private static List<Object> filterPackageList(LoadPackageParam lpparam,
                                                  List<?> original) {
        ArrayList<Object> filtered = new ArrayList<>(original.size());
        for (Object item : original) {
            String packageName = packageNameOf(item);
            if (!shouldHidePackage(lpparam, packageName)) {
                filtered.add(item);
            }
        }
        return filtered;
    }

    private static String firstStringArg(XC_MethodHook.MethodHookParam param) {
        if (param == null || param.args == null || param.args.length == 0) {
            return null;
        }
        Object arg = param.args[0];
        return arg instanceof String ? (String) arg : null;
    }

    private static String packageNameOf(Object item) {
        if (item instanceof PackageInfo) {
            return ((PackageInfo) item).packageName;
        }
        if (item instanceof ApplicationInfo) {
            return ((ApplicationInfo) item).packageName;
        }
        return null;
    }

    private static String firstStringArg(Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        Object arg = args[0];
        return arg instanceof String ? (String) arg : null;
    }

    private static boolean shouldHidePackage(LoadPackageParam lpparam,
                                             String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return false;
        }
        if (lpparam != null && packageName.equals(lpparam.packageName)) {
            return false;
        }
        return hiddenPackages().contains(packageName);
    }

    private static void trace(PackageInfoPlan plan,
                              String phase,
                              String methodName,
                              String packageName,
                              boolean hide) {
        if (!plan.trace) return;
        XposedBridge.log(TAG + ": audit:lookup phase=" + phase
                + " method=" + methodName
                + " target=" + packageName + " hide=" + hide
                + " mode=" + plan.mode
                + " strategy=" + plan.strategy
                + " backend=" + plan.backend);
    }

    private static Set<String> hiddenPackages() {
        HashSet<String> result = new HashSet<>();
        String raw = ConfigManager.getHiddenPackagesRaw();
        if (raw == null || raw.trim().isEmpty()) {
            return result;
        }
        for (String item : raw.split(",")) {
            String pkg = item == null ? "" : item.trim();
            if (!pkg.isEmpty()) {
                result.add(pkg);
            }
        }
        return result;
    }

    private static void hookPackageInfoFields(LoadPackageParam lpparam) {
        Class<?> appPm = XposedHelpers.findClassIfExists(
                "android.app.ApplicationPackageManager", lpparam.classLoader);
        if (appPm == null) return;

        XC_MethodHook patcher = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object result = param.getResult();
                if (result instanceof PackageInfo) {
                    patch((PackageInfo) result);
                }
            }
        };

        // getPackageInfo(String, int) and getPackageInfo(String, PackageInfoFlags)
        try {
            XposedHelpers.findAndHookMethod(appPm, "getPackageInfo",
                    String.class, int.class, patcher);
        } catch (Throwable t) { logFail("getPackageInfo(String,int)", t); }

        try {
            Class<?> flags = XposedHelpers.findClassIfExists(
                    "android.content.pm.PackageManager$PackageInfoFlags", lpparam.classLoader);
            if (flags != null) {
                XposedHelpers.findAndHookMethod(appPm, "getPackageInfo",
                        String.class, flags, patcher);
            }
        } catch (Throwable t) { /* Android 13+ overload */ }
    }

    private static void hookGetInstallerPackageName(LoadPackageParam lpparam) {
        Class<?> appPm = XposedHelpers.findClassIfExists(
                "android.app.ApplicationPackageManager", lpparam.classLoader);
        if (appPm == null) return;

        try {
            XposedHelpers.findAndHookMethod(appPm, "getInstallerPackageName",
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String packageName = (String) param.args[0];
                            if (shouldSpoofInstaller(lpparam, packageName)) {
                                param.setResult(ConfigManager.getInstallerPackage());
                            }
                        }
                    });
        } catch (Throwable t) { logFail("getInstallerPackageName", t); }
    }

    private static boolean shouldSpoofInstaller(LoadPackageParam lpparam,
                                                String packageName) {
        if (packageName == null || lpparam.packageName == null) {
            return false;
        }
        if (!lpparam.packageName.equals(packageName)) {
            return false;
        }
        if (ConfigManager.isOwnPackageProcess(lpparam.packageName)) {
            return false;
        }
        if (lpparam.appInfo == null) {
            return true;
        }
        int systemFlags = ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
        return (lpparam.appInfo.flags & systemFlags) == 0;
    }

    private static void hookGetInstallSourceInfo(LoadPackageParam lpparam) {
        Class<?> appPm = XposedHelpers.findClassIfExists(
                "android.app.ApplicationPackageManager", lpparam.classLoader);
        if (appPm == null) return;

        Class<?> sourceInfo = XposedHelpers.findClassIfExists(
                "android.content.pm.InstallSourceInfo", lpparam.classLoader);
        if (sourceInfo == null) return;

        // InstallSourceInfo getters: hook each accessor to return Play Store.
        XC_MethodHook playStoreHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                param.setResult(ConfigManager.getInstallerPackage());
            }
        };

        for (String getter : new String[]{
                "getInstallingPackageName",
                "getInitiatingPackageName",
                "getOriginatingPackageName"
        }) {
            try {
                XposedHelpers.findAndHookMethod(sourceInfo, getter, playStoreHook);
            } catch (Throwable t) { /* getter may be absent on older Android */ }
        }
    }

    private static void patch(PackageInfo pi) {
        if (pi == null) return;
        long install = stableInstallTime();
        pi.firstInstallTime = install;
        // lastUpdateTime: random within 0-14 days after install, stable per app.
        pi.lastUpdateTime = install + (Math.abs(stableHash(pi.packageName)) % (14L * DAY_MS));
    }

    private static long stableInstallTime() {
        // 60-120 days before now, stable per android_id.
        long seed = Math.abs(ConfigManager.getFingerprintSeed());
        long offsetDays = 60 + (seed % 61);
        return System.currentTimeMillis() - offsetDays * DAY_MS;
    }

    private static long stableHash(String s) {
        if (s == null) return 0L;
        long h = 1469598103934665603L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 1099511628211L;
        }
        return h;
    }

    private static void logFail(String what, Throwable t) {
        XposedBridge.log(TAG + ": failed to hook " + what + ": " + t);
    }

    private static final class PackageInfoPlan {
        final String mode;
        final String strategy;
        final String backend;
        final boolean trace;
        final boolean getPackageInfo;
        final boolean applicationInfo;
        final boolean packageUid;
        final boolean lists;
        final boolean installer;
        final boolean source;
        final boolean times;

        private PackageInfoPlan(String mode, String strategy, String backend,
                                boolean trace,
                                boolean getPackageInfo,
                                boolean applicationInfo,
                                boolean packageUid,
                                boolean lists,
                                boolean installer,
                                boolean source,
                                boolean times) {
            this.mode = mode;
            this.strategy = strategy;
            this.backend = backend;
            this.trace = trace;
            this.getPackageInfo = getPackageInfo;
            this.applicationInfo = applicationInfo;
            this.packageUid = packageUid;
            this.lists = lists;
            this.installer = installer;
            this.source = source;
            this.times = times;
        }

        static PackageInfoPlan from(LoadPackageParam lpparam) {
            String packageName = lpparam == null ? "" : lpparam.packageName;
            String mode = ConfigManager.getRawProperty("debug.package_info_mode");
            String strategy = ConfigManager.getRawProperty(
                    "debug.package_info_strategy");
            String backend = ConfigManager.getRawProperty(
                    "debug.package_info_backend");
            String trace = ConfigManager.getRawProperty("debug.package_info_trace");
            if ("me.garfieldhan.holmes".equals(packageName)) {
                String appMode = ConfigManager.getRawProperty(
                        "debug.holmes.package_info_mode");
                String appStrategy = ConfigManager.getRawProperty(
                        "debug.holmes.package_info_strategy");
                String appBackend = ConfigManager.getRawProperty(
                        "debug.holmes.package_info_backend");
                String appTrace = ConfigManager.getRawProperty(
                        "debug.holmes.package_info_trace");
                if (appMode != null && !appMode.trim().isEmpty()) {
                    mode = appMode;
                }
                if (appStrategy != null && !appStrategy.trim().isEmpty()) {
                    strategy = appStrategy;
                }
                if (appBackend != null && !appBackend.trim().isEmpty()) {
                    backend = appBackend;
                }
                if (appTrace != null && !appTrace.trim().isEmpty()) {
                    trace = appTrace;
                }
            }
            mode = cleanMode(mode);
            strategy = cleanStrategy(strategy);
            backend = cleanBackend(backend);
            if (ConfigManager.isSystemServerPackageHidingEnabled()) {
                backend = "system-server";
            }
            Set<String> tokens = parseMode(mode);
            boolean all = tokens.isEmpty() || tokens.contains("all");
            boolean visibility = all || tokens.contains("visibility");
            boolean lookup = visibility || tokens.contains("lookup");
            boolean identity = all || tokens.contains("identity");
            boolean timesEnabled = ConfigManager.isPackageInfoTimeHookEnabled();
            return new PackageInfoPlan(
                    mode,
                    strategy,
                    backend,
                    truthy(trace),
                    lookup || tokens.contains("getpackageinfo")
                            || tokens.contains("packageinfo"),
                    lookup || tokens.contains("applicationinfo")
                            || tokens.contains("appinfo"),
                    lookup || tokens.contains("packageuid")
                            || tokens.contains("uid"),
                    visibility || tokens.contains("lists"),
                    identity || tokens.contains("installer"),
                    identity || tokens.contains("source")
                            || tokens.contains("installsource"),
                    timesEnabled && (all || tokens.contains("times")));
        }

        private static String cleanMode(String mode) {
            if (mode == null) return "all";
            String cleaned = mode.trim().toLowerCase(Locale.ROOT);
            return cleaned.isEmpty() ? "all" : cleaned;
        }

        private static String cleanStrategy(String strategy) {
            if (strategy == null) return "before";
            String cleaned = strategy.trim().toLowerCase(Locale.ROOT);
            if (cleaned.isEmpty()) return "before";
            if ("noop".equals(cleaned) || "trace".equals(cleaned)) {
                return "passthrough";
            }
            if ("before-hide".equals(cleaned)) {
                return "before";
            }
            if ("after-hide".equals(cleaned)) {
                return "after";
            }
            return cleaned;
        }

        private static String cleanBackend(String backend) {
            if (backend == null) return "direct";
            String cleaned = backend.trim().toLowerCase(Locale.ROOT);
            if (cleaned.isEmpty()) return "direct";
            if ("proxy".equals(cleaned) || "ipm".equals(cleaned)
                    || "ipackage".equals(cleaned)
                    || "ipackagemanager".equals(cleaned)) {
                return "ipm-proxy";
            }
            if ("system".equals(cleaned) || "server".equals(cleaned)
                    || "system_server".equals(cleaned) || "system-server".equals(cleaned)
                    || "pms".equals(cleaned)) {
                return "system-server";
            }
            if ("lsplant".equals(cleaned) || "method".equals(cleaned)) {
                return "direct";
            }
            return cleaned;
        }

        boolean useSystemServer() {
            return "system-server".equals(backend);
        }

        boolean useIpmProxy() {
            return "ipm-proxy".equals(backend);
        }

        boolean hideBefore() {
            return "before".equals(strategy);
        }

        boolean hideAfter() {
            return "after".equals(strategy);
        }

        private static Set<String> parseMode(String mode) {
            HashSet<String> tokens = new HashSet<>();
            if (mode == null) return tokens;
            for (String item : mode.split("[,;+|\\s]+")) {
                String token = item == null ? "" : item.trim();
                if (!token.isEmpty() && !"0".equals(token)
                        && !"false".equals(token) && !"none".equals(token)
                        && !"off".equals(token)) {
                    tokens.add(token);
                }
            }
            return tokens;
        }

        private static boolean truthy(String value) {
            if (value == null) return false;
            String v = value.trim();
            return "1".equals(v) || "true".equalsIgnoreCase(v)
                    || "yes".equalsIgnoreCase(v) || "on".equalsIgnoreCase(v);
        }
    }
}
