package com.devicespooflab.hooks.hooks;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import com.devicespooflab.hooks.utils.ConfigManager;
import com.devicespooflab.hooks.xposed.XC_MethodHook;
import com.devicespooflab.hooks.xposed.XposedBridge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class SystemServerPackageHooks {

    private static final String TAG = "DeviceSpoofLab-SystemPM";
    private static final int SYSTEM_UID = 1000;
    private static final long WAIT_MS = 200L;
    private static final int WAIT_ATTEMPTS = 300;

    private static final Set<String> HOOKED =
            Collections.synchronizedSet(new HashSet<String>());
    private static final Map<Integer, String[]> UID_PACKAGES =
            new ConcurrentHashMap<>();

    private static volatile boolean sWorkerStarted = false;
    private static volatile Object sPackageManager = null;

    private SystemServerPackageHooks() {}

    public static synchronized void installAsync() {
        boolean packageHiding = ConfigManager.isSystemServerPackageHidingEnabled()
                && ConfigManager.isHideDetectionPackagesEnabled();
        boolean featureSpoofing = ConfigManager.isSystemServerFeatureSpoofingEnabled();
        if (!packageHiding && !featureSpoofing) {
            Log.w(TAG, "audit:disabled system_server package hooks packageHiding=0 featureSpoofing=0");
            return;
        }
        if (ConfigManager.isSystemServerPackageHidingEnabled()
                && !ConfigManager.isHideDetectionPackagesEnabled()) {
            Log.w(TAG, "audit:package hiding disabled hooks.hide_detection_packages=0");
        }
        if (sWorkerStarted) {
            Log.w(TAG, "audit:worker already started");
            return;
        }
        sWorkerStarted = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                installWhenPackageServiceReady();
            }
        }, "DeviceSpoofLab-system-server-pm");
        t.start();
    }

    private static void installWhenPackageServiceReady() {
        try {
            Object pm = waitForPackageManager();
            if (pm == null) {
                Log.w(TAG, "audit:package service not ready after wait");
                return;
            }
            sPackageManager = pm;
            Log.w(TAG, "audit:package service ready class=" + pm.getClass().getName()
                    + " scope=" + ConfigManager.getSystemServerScopePackagesRaw());

            int hooked = 0;
            hooked += hookClass(pm.getClass());
            hooked += hookClassName("com.android.server.pm.PackageManagerService");
            hooked += hookClassName("com.android.server.pm.PackageManagerService$IPackageManagerImpl");
            hooked += hookClassName("com.android.server.pm.PackageManagerService$ComputerTracker");
            hooked += hookClassName("com.android.server.pm.ComputerEngine");
            hooked += hookClassName("com.android.server.pm.AppsFilter");
            hooked += hookClassName("com.android.server.pm.AppsFilterImpl");

            Log.w(TAG, "audit:system_server package hooks installed count=" + hooked);
        } catch (Throwable t) {
            Log.w(TAG, "audit:system_server package hooks failed: " + t, t);
        }
    }

    private static Object waitForPackageManager() {
        for (int i = 0; i < WAIT_ATTEMPTS; i++) {
            try {
                IBinder binder = getService("package");
                if (binder != null) {
                    Object pm = asIPackageManager(binder);
                    if (pm != null) return pm;
                }
            } catch (Throwable t) {
                if (i == 0 || ConfigManager.isSystemServerPackageTraceEnabled()) {
                    Log.w(TAG, "audit:wait package service attempt=" + i + " failed: " + t);
                }
            }
            try {
                Thread.sleep(WAIT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private static IBinder getService(String name) throws Throwable {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Method getService = serviceManager.getDeclaredMethod("getService", String.class);
        getService.setAccessible(true);
        Object service = getService.invoke(null, name);
        return service instanceof IBinder ? (IBinder) service : null;
    }

    private static Object asIPackageManager(IBinder binder) throws Throwable {
        Class<?> stub = Class.forName("android.content.pm.IPackageManager$Stub");
        Method asInterface = stub.getDeclaredMethod("asInterface", IBinder.class);
        asInterface.setAccessible(true);
        return asInterface.invoke(null, binder);
    }

    private static int hookClassName(String className) {
        Class<?> clazz = findClass(className);
        return clazz == null ? 0 : hookClass(clazz);
    }

    private static Class<?> findClass(String className) {
        ClassLoader[] loaders = new ClassLoader[]{
                ClassLoader.getSystemClassLoader(),
                Thread.currentThread().getContextClassLoader(),
                SystemServerPackageHooks.class.getClassLoader(),
                null
        };
        for (ClassLoader loader : loaders) {
            try {
                return Class.forName(className, false, loader);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static int hookClass(Class<?> clazz) {
        if (clazz == null) return 0;
        int count = 0;
        for (Method method : clazz.getDeclaredMethods()) {
            if (!isHookCandidate(method)) continue;
            String key = methodKey(method);
            if (!HOOKED.add(key)) continue;
            try {
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new PackageServerHook());
                count++;
                Log.w(TAG, "audit:hooked " + key);
            } catch (Throwable t) {
                HOOKED.remove(key);
                Log.w(TAG, "audit:hook failed " + key + ": " + t);
            }
        }
        return count;
    }

    private static boolean isHookCandidate(Method method) {
        int modifiers = method.getModifiers();
        if (Modifier.isAbstract(modifiers)) return false;
        String name = method.getName();
        if ("getPackagesForUid".equals(name)) return false;
        if ("getNameForUid".equals(name)) return false;
        if ("getNamesForUids".equals(name)) return false;

        return "getPackageInfo".equals(name)
                || "getPackageInfoInternal".equals(name)
                || "getApplicationInfo".equals(name)
                || "getApplicationInfoInternal".equals(name)
                || "getPackageUid".equals(name)
                || "getPackageUidInternal".equals(name)
                || "getInstalledPackages".equals(name)
                || "getInstalledApplications".equals(name)
                || "getPackagesHoldingPermissions".equals(name)
                || "queryIntentActivities".equals(name)
                || "queryIntentReceivers".equals(name)
                || "queryIntentActivityOptions".equals(name)
                || "queryIntentContentProviders".equals(name)
                || "queryIntentServices".equals(name)
                || "queryBroadcastReceivers".equals(name)
                || "queryContentProviders".equals(name)
                || "queryInstrumentation".equals(name)
                || "resolveIntent".equals(name)
                || "resolveService".equals(name)
                || "resolveActivity".equals(name)
                || "resolveContentProvider".equals(name)
                || "getPersistentApplications".equals(name)
                || "getInstallerPackageName".equals(name)
                || "getInstallSourceInfo".equals(name)
                || "getPackageSetting".equals(name)
                || "getPackageSettingInternal".equals(name)
                || "getPackageStates".equals(name)
                || "shouldFilterApplication".equals(name)
                || "filterAppAccess".equals(name)
                || "canQueryPackage".equals(name)
                || "hasSystemFeature".equals(name)
                || "getSystemAvailableFeatures".equals(name);
    }

    private static String methodKey(Method method) {
        StringBuilder sb = new StringBuilder();
        sb.append(method.getDeclaringClass().getName()).append('#')
                .append(method.getName()).append('(');
        Class<?>[] params = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(params[i].getName());
        }
        sb.append(')');
        return sb.toString();
    }

    private static final class PackageServerHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            Method method = param.method instanceof Method ? (Method) param.method : null;
            if (method == null) return;
            String methodName = method.getName();
            int callerUid = Binder.getCallingUid();
            if (!isScopedCaller(callerUid)) return;

            if (isFeatureLookupMethod(methodName)) {
                handleFeatureBefore(param, methodName, callerUid);
                return;
            }

            if (!isPackageHidingActive()) return;
            String target = findTargetPackage(param.args);
            if (target == null) return;
            if (!shouldHidePackage(callerUid, target)) {
                trace("before", methodName, callerUid, target, false);
                return;
            }

            if (isBooleanVisibilityMethod(methodName)) {
                trace("hide-before", methodName, callerUid, target, true);
                param.setResult(booleanHiddenResult(methodName));
                return;
            }
            if (isLookupMethod(methodName)) {
                trace("hide-before", methodName, callerUid, target, true);
                param.setResult(hiddenLookupResult(method));
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            Method method = param.method instanceof Method ? (Method) param.method : null;
            if (method == null) return;
            int callerUid = Binder.getCallingUid();
            if (!isScopedCaller(callerUid)) return;

            String methodName = method.getName();
            if (isFeatureListMethod(methodName)) {
                Object result = param.getResult();
                Object filtered = filterFeatureResult(callerUid, result);
                if (filtered != result) {
                    trace("feature-filter-after", methodName, callerUid, "<features>", true);
                    param.setResult(filtered);
                }
                return;
            }

            if (!isPackageHidingActive()) return;
            Object result = param.getResult();
            Object filtered = filterResult(callerUid, method, result);
            if (filtered != result) {
                trace("filter-after", methodName, callerUid, "<result>", true);
                param.setResult(filtered);
            }
        }
    }

    private static void handleFeatureBefore(XC_MethodHook.MethodHookParam param,
                                            String methodName,
                                            int callerUid) {
        if (!ConfigManager.isSystemServerFeatureSpoofingEnabled()) return;
        String feature = firstStringArg(param.args);
        if (feature == null) return;
        if (PackageFeaturePolicy.shouldDenyFeature(feature)) {
            trace("feature-deny-before", methodName, callerUid, feature, true);
            param.setResult(Boolean.FALSE);
            return;
        }
        if (PackageFeaturePolicy.shouldForceFeature(feature)) {
            trace("feature-force-before", methodName, callerUid, feature, true);
            param.setResult(Boolean.TRUE);
        } else {
            trace("feature-pass-before", methodName, callerUid, feature, false);
        }
    }

    private static String firstStringArg(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof String) return (String) arg;
        }
        return null;
    }

    private static boolean isLookupMethod(String name) {
        return "getPackageInfo".equals(name)
                || "getPackageInfoInternal".equals(name)
                || "getApplicationInfo".equals(name)
                || "getApplicationInfoInternal".equals(name)
                || "getPackageUid".equals(name)
                || "getPackageUidInternal".equals(name)
                || "getInstallerPackageName".equals(name)
                || "getInstallSourceInfo".equals(name)
                || "getPackageSetting".equals(name)
                || "getPackageSettingInternal".equals(name)
                || "resolveIntent".equals(name)
                || "resolveService".equals(name)
                || "resolveActivity".equals(name)
                || "resolveContentProvider".equals(name);
    }

    private static boolean isPackageHidingActive() {
        return ConfigManager.isSystemServerPackageHidingEnabled()
                && ConfigManager.isHideDetectionPackagesEnabled();
    }

    private static boolean isFeatureLookupMethod(String name) {
        return "hasSystemFeature".equals(name);
    }

    private static boolean isFeatureListMethod(String name) {
        return "getSystemAvailableFeatures".equals(name);
    }

    private static boolean isBooleanVisibilityMethod(String name) {
        return "shouldFilterApplication".equals(name)
                || "filterAppAccess".equals(name)
                || "canQueryPackage".equals(name);
    }

    private static Boolean booleanHiddenResult(String name) {
        return "canQueryPackage".equals(name) ? Boolean.FALSE : Boolean.TRUE;
    }

    private static Object hiddenLookupResult(Method method) {
        Class<?> type = method.getReturnType();
        if (type == int.class || type == Integer.class) return -1;
        if (type == long.class || type == Long.class) return -1L;
        if (type == boolean.class || type == Boolean.class) return false;
        return null;
    }

    private static Object filterFeatureResult(int callerUid, Object result) {
        if (!ConfigManager.isSystemServerFeatureSpoofingEnabled() || result == null) {
            return result;
        }
        Object filtered = filterFeatureParceledListSlice(callerUid, result);
        if (filtered != result) return filtered;
        if (result instanceof List<?>) {
            List<Object> list = filterFeatureList(callerUid, (List<?>) result,
                    true);
            return list == result ? result : list;
        }
        Class<?> resultClass = result.getClass();
        if (resultClass.isArray()) {
            return filterFeatureArray(callerUid, result);
        }
        return result;
    }

    private static Object filterFeatureParceledListSlice(int callerUid, Object result) {
        if (!"android.content.pm.ParceledListSlice".equals(result.getClass().getName())) {
            return result;
        }
        try {
            Method getList = result.getClass().getDeclaredMethod("getList");
            getList.setAccessible(true);
            Object listObj = getList.invoke(result);
            if (!(listObj instanceof List<?>)) return result;
            List<Object> filtered = filterFeatureList(callerUid, (List<?>) listObj,
                    true);
            if (filtered == listObj) return result;
            Constructor<?> ctor = result.getClass().getDeclaredConstructor(List.class);
            ctor.setAccessible(true);
            return ctor.newInstance(filtered);
        } catch (Throwable t) {
            Log.w(TAG, "audit:feature ParceledListSlice filter failed: " + t);
            return result;
        }
    }

    private static Object filterFeatureArray(int callerUid, Object original) {
        int length = java.lang.reflect.Array.getLength(original);
        ArrayList<Object> list = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            list.add(java.lang.reflect.Array.get(original, i));
        }
        Class<?> component = original.getClass().getComponentType();
        boolean canAddFeatureInfo = component == Object.class
                || component.isAssignableFrom(FeatureInfo.class);
        List<Object> filtered = filterFeatureList(callerUid, list,
                canAddFeatureInfo);
        if (filtered == list) return original;

        Object array = java.lang.reflect.Array.newInstance(component, filtered.size());
        for (int i = 0; i < filtered.size(); i++) {
            java.lang.reflect.Array.set(array, i, filtered.get(i));
        }
        return array;
    }

    private static List<Object> filterFeatureList(int callerUid, List<?> original,
                                                  boolean addForcedFeatures) {
        ArrayList<Object> out = new ArrayList<>(original.size()
                + PackageFeaturePolicy.forcedFeatures().size());
        HashSet<String> present = new HashSet<>();
        boolean changed = false;

        for (Object item : original) {
            String name = featureNameOf(item);
            if (name != null && PackageFeaturePolicy.shouldDenyFeature(name)) {
                changed = true;
                trace("feature-list-deny", "getSystemAvailableFeatures",
                        callerUid, name, true);
                continue;
            }
            if (name != null) present.add(name);
            out.add(item);
        }

        if (addForcedFeatures) {
            for (String forced : PackageFeaturePolicy.forcedFeatures()) {
                if (present.contains(forced)) continue;
                FeatureInfo info = new FeatureInfo();
                info.name = forced;
                info.version = 0;
                out.add(info);
                changed = true;
                trace("feature-list-add", "getSystemAvailableFeatures",
                        callerUid, forced, true);
            }
        }

        return changed ? out : castSameList(original);
    }

    private static String featureNameOf(Object item) {
        if (item == null) return null;
        if (item instanceof FeatureInfo) {
            return ((FeatureInfo) item).name;
        }
        Object value = readObjectField(item, "name");
        return value instanceof String ? (String) value : null;
    }

    private static Object filterResult(int callerUid, Method method, Object result) {
        if (result == null) return result;
        Object filtered = filterParceledListSlice(callerUid, result);
        if (filtered != result) return filtered;

        if (result instanceof List<?>) {
            List<Object> list = filterList(callerUid, (List<?>) result);
            return list == result ? result : list;
        }
        if (result instanceof String[]) {
            String[] filteredArray = filterStringArray(callerUid, (String[]) result);
            return filteredArray == result ? result : filteredArray;
        }
        if (result instanceof Map<?, ?>) {
            Map<Object, Object> map = filterMap(callerUid, (Map<?, ?>) result);
            return map == result ? result : map;
        }

        String packageName = packageNameOf(result);
        if (shouldHidePackage(callerUid, packageName)) {
            return hiddenLookupResult(method);
        }
        return result;
    }

    private static Object filterParceledListSlice(int callerUid, Object result) {
        if (!"android.content.pm.ParceledListSlice".equals(result.getClass().getName())) {
            return result;
        }
        try {
            Method getList = result.getClass().getDeclaredMethod("getList");
            getList.setAccessible(true);
            Object listObj = getList.invoke(result);
            if (!(listObj instanceof List<?>)) return result;
            List<Object> filtered = filterList(callerUid, (List<?>) listObj);
            if (filtered == listObj) return result;
            Constructor<?> ctor = result.getClass().getDeclaredConstructor(List.class);
            ctor.setAccessible(true);
            return ctor.newInstance(filtered);
        } catch (Throwable t) {
            Log.w(TAG, "audit:ParceledListSlice filter failed: " + t);
            return result;
        }
    }

    private static List<Object> filterList(int callerUid, List<?> original) {
        ArrayList<Object> out = new ArrayList<>(original.size());
        boolean changed = false;
        for (Object item : original) {
            String packageName = packageNameOf(item);
            if (shouldHidePackage(callerUid, packageName)) {
                changed = true;
                continue;
            }
            out.add(item);
        }
        return changed ? out : castSameList(original);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castSameList(List<?> original) {
        return (List<Object>) original;
    }

    private static String[] filterStringArray(int callerUid, String[] original) {
        ArrayList<String> out = new ArrayList<>(original.length);
        boolean changed = false;
        for (String item : original) {
            if (shouldHidePackage(callerUid, item)) {
                changed = true;
                continue;
            }
            out.add(item);
        }
        return changed ? out.toArray(new String[0]) : original;
    }

    private static Map<Object, Object> filterMap(int callerUid, Map<?, ?> original) {
        Map<Object, Object> out = newCompatibleMap(original);
        boolean changed = false;
        for (Map.Entry<?, ?> entry : original.entrySet()) {
            String packageName = packageNameOf(entry.getValue());
            if (packageName == null) {
                packageName = packageNameOf(entry.getKey());
            }
            if (shouldHidePackage(callerUid, packageName)) {
                changed = true;
                continue;
            }
            out.put(entry.getKey(), entry.getValue());
        }
        return changed ? out : castSameMap(original);
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> castSameMap(Map<?, ?> original) {
        return (Map<Object, Object>) original;
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> newCompatibleMap(Map<?, ?> original) {
        try {
            Constructor<?> ctor = original.getClass().getDeclaredConstructor();
            ctor.setAccessible(true);
            Object copy = ctor.newInstance();
            if (copy instanceof Map<?, ?>) {
                return (Map<Object, Object>) copy;
            }
        } catch (Throwable ignored) {
        }
        return new LinkedHashMap<>();
    }

    private static boolean isScopedCaller(int uid) {
        if (uid == SYSTEM_UID || uid == 0 || uid <= 0) {
            return false;
        }
        Set<String> scope = scopePackages();
        if (scope.isEmpty()) return false;
        String[] packages = packagesForUid(uid);
        for (String pkg : packages) {
            if (scope.contains(pkg)) return true;
        }
        return false;
    }

    private static String[] packagesForUid(int uid) {
        String[] cached = UID_PACKAGES.get(uid);
        if (cached != null) return cached;
        String[] packages = lookupPackagesForUid(uid);
        if (packages == null || packages.length == 0) {
            return new String[0];
        }
        UID_PACKAGES.put(uid, packages);
        return packages;
    }

    private static String[] lookupPackagesForUid(int uid) {
        Object pm = sPackageManager;
        if (pm == null) {
            try {
                Class<?> appGlobals = Class.forName("android.app.AppGlobals");
                Method getPackageManager = appGlobals.getDeclaredMethod("getPackageManager");
                getPackageManager.setAccessible(true);
                pm = getPackageManager.invoke(null);
            } catch (Throwable ignored) {
            }
        }
        if (pm == null) return null;
        Method method = findMethod(pm.getClass(), "getPackagesForUid", int.class);
        if (method == null) return null;
        try {
            Object value = method.invoke(pm, uid);
            return value instanceof String[] ? (String[]) value : null;
        } catch (Throwable t) {
            if (ConfigManager.isSystemServerPackageTraceEnabled()) {
                Log.w(TAG, "audit:getPackagesForUid failed uid=" + uid + ": " + t);
            }
            return null;
        }
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>... params) {
        Class<?> c = clazz;
        while (c != null) {
            try {
                Method method = c.getDeclaredMethod(name, params);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private static boolean shouldHidePackage(int callerUid, String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        Set<String> hidden = hiddenPackages();
        if (!hidden.contains(packageName)) return false;
        String[] callerPackages = packagesForUid(callerUid);
        for (String callerPackage : callerPackages) {
            if (packageName.equals(callerPackage)) return false;
        }
        return true;
    }

    private static Set<String> scopePackages() {
        return parsePackageSet(ConfigManager.getSystemServerScopePackagesRaw());
    }

    private static Set<String> hiddenPackages() {
        return parsePackageSet(ConfigManager.getHiddenPackagesRaw());
    }

    private static Set<String> parsePackageSet(String raw) {
        HashSet<String> out = new HashSet<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        String[] items = raw.split("[,;\\s]+");
        for (String item : items) {
            String pkg = item == null ? "" : item.trim();
            if (!pkg.isEmpty()) out.add(pkg);
        }
        return out;
    }

    private static String findTargetPackage(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof String && hiddenPackages().contains(arg)) {
                return (String) arg;
            }
        }
        for (Object arg : args) {
            String pkg = packageNameOf(arg);
            if (pkg != null && hiddenPackages().contains(pkg)) {
                return pkg;
            }
        }
        for (Object arg : args) {
            if (arg instanceof String) {
                String value = (String) arg;
                if (looksLikePackageName(value)) return value;
            }
        }
        for (Object arg : args) {
            String pkg = packageNameOf(arg);
            if (pkg != null) return pkg;
        }
        return null;
    }

    private static boolean looksLikePackageName(String value) {
        if (value == null) return false;
        return value.indexOf('.') > 0
                && value.toLowerCase(Locale.ROOT).equals(value)
                && !value.contains("/")
                && !value.contains(":");
    }

    private static String packageNameOf(Object item) {
        if (item == null) return null;
        if (item instanceof String) return (String) item;
        if (item instanceof Intent) {
            Intent intent = (Intent) item;
            ComponentName component = intent.getComponent();
            if (component != null) return component.getPackageName();
            return intent.getPackage();
        }
        if (item instanceof PackageInfo) return ((PackageInfo) item).packageName;
        if (item instanceof ApplicationInfo) return ((ApplicationInfo) item).packageName;
        if (item instanceof ResolveInfo) {
            ResolveInfo ri = (ResolveInfo) item;
            String pkg = packageNameOf(ri.activityInfo);
            if (pkg != null) return pkg;
            pkg = packageNameOf(ri.serviceInfo);
            if (pkg != null) return pkg;
            return packageNameOf(ri.providerInfo);
        }
        if (item instanceof ActivityInfo) return ((ActivityInfo) item).packageName;
        if (item instanceof ServiceInfo) return ((ServiceInfo) item).packageName;
        if (item instanceof ProviderInfo) return ((ProviderInfo) item).packageName;

        String pkg = callStringGetter(item, "getPackageName");
        if (pkg != null) return pkg;
        pkg = callStringGetter(item, "getManifestPackageName");
        if (pkg != null) return pkg;
        pkg = readStringField(item, "packageName");
        if (pkg != null) return pkg;
        pkg = readStringField(item, "mPackageName");
        if (pkg != null) return pkg;
        pkg = readStringField(item, "name");
        if (looksLikePackageName(pkg)) return pkg;

        Object appInfo = readObjectField(item, "applicationInfo");
        pkg = packageNameOf(appInfo);
        if (pkg != null) return pkg;
        Object pkgObject = readObjectField(item, "pkg");
        return pkgObject == item ? null : packageNameOf(pkgObject);
    }

    private static String callStringGetter(Object obj, String methodName) {
        try {
            Method method = findMethod(obj.getClass(), methodName);
            if (method == null) return null;
            Object value = method.invoke(obj);
            return value instanceof String ? (String) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String readStringField(Object obj, String fieldName) {
        Object value = readObjectField(obj, fieldName);
        return value instanceof String ? (String) value : null;
    }

    private static Object readObjectField(Object obj, String fieldName) {
        if (obj == null) return null;
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                Field field = c.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(obj);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static void trace(String phase, String methodName, int callerUid,
                              String target, boolean hide) {
        if (!hide && !ConfigManager.isSystemServerPackageTraceEnabled()) return;
        Log.w(TAG, "audit:" + phase
                + " method=" + methodName
                + " callerUid=" + callerUid
                + " caller=" + join(packagesForUid(callerUid))
                + " target=" + target
                + " hide=" + hide);
    }

    private static String join(String[] values) {
        if (values == null || values.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (sb.length() > 0) sb.append(',');
            sb.append(value);
        }
        return sb.toString();
    }
}
