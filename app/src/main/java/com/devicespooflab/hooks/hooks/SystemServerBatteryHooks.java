package com.devicespooflab.hooks.hooks;

import android.os.BatteryManager;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import com.devicespooflab.hooks.utils.ConfigManager;
import com.devicespooflab.hooks.xposed.XC_MethodHook;
import com.devicespooflab.hooks.xposed.XposedBridge;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class SystemServerBatteryHooks {

    private static final String TAG = "DeviceSpoofLab-SystemBattery";
    private static final int SYSTEM_UID = 1000;
    private static final long WAIT_MS = 200L;
    private static final int WAIT_ATTEMPTS = 300;
    private static final int BATTERY_PROPERTY_SUCCESS = 0;

    private static final Set<String> HOOKED =
            Collections.synchronizedSet(new HashSet<String>());
    private static final Map<Integer, String[]> UID_PACKAGES =
            new ConcurrentHashMap<>();

    private static volatile boolean sWorkerStarted = false;
    private static volatile Object sPackageManager = null;

    private SystemServerBatteryHooks() {}

    public static synchronized void installAsync() {
        if (!ConfigManager.isSystemServerBatterySpoofingEnabled()) {
            Log.w(TAG, "audit:disabled system_server battery hooks");
            return;
        }
        if (sWorkerStarted) {
            Log.w(TAG, "audit:worker already started");
            return;
        }
        sWorkerStarted = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                installWhenBatteryServiceReady();
            }
        }, "DeviceSpoofLab-system-server-battery");
        t.start();
    }

    private static void installWhenBatteryServiceReady() {
        try {
            int hooked = 0;
            hooked += hookBinderService("batteryproperties");
            hooked += hookBinderService("battery");
            hooked += hookClassName("com.android.server.BatteryService$BatteryPropertiesRegistrar");
            hooked += hookClassName("com.android.server.BatteryService$BinderService");

            if (hooked == 0) {
                Log.w(TAG, "audit:battery service not hookable after wait");
                return;
            }
            Log.w(TAG, "audit:system_server battery hooks installed count=" + hooked
                    + " scope=" + ConfigManager.getSystemServerScopePackagesRaw());
        } catch (Throwable t) {
            Log.w(TAG, "audit:system_server battery hooks failed: " + t, t);
        }
    }

    private static int hookBinderService(String serviceName) {
        IBinder binder = waitForService(serviceName);
        if (binder == null) {
            Log.w(TAG, "audit:" + serviceName + " service not ready after wait");
            return 0;
        }
        Log.w(TAG, "audit:" + serviceName + " service ready class="
                + binder.getClass().getName());
        return hookClass(binder.getClass());
    }

    private static IBinder waitForService(String name) {
        for (int i = 0; i < WAIT_ATTEMPTS; i++) {
            try {
                IBinder binder = getService(name);
                if (binder != null) return binder;
            } catch (Throwable t) {
                if (i == 0 || ConfigManager.isSystemServerPackageTraceEnabled()) {
                    Log.w(TAG, "audit:wait " + name + " service attempt=" + i
                            + " failed: " + t);
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

    private static int hookClassName(String className) {
        Class<?> clazz = findClass(className);
        return clazz == null ? 0 : hookClass(clazz);
    }

    private static Class<?> findClass(String className) {
        ClassLoader[] loaders = new ClassLoader[]{
                ClassLoader.getSystemClassLoader(),
                Thread.currentThread().getContextClassLoader(),
                SystemServerBatteryHooks.class.getClassLoader(),
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
                XposedBridge.hookMethod(method, new BatteryServerHook());
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
        if (!"getProperty".equals(method.getName())) return false;
        Class<?>[] params = method.getParameterTypes();
        return params.length >= 2 && params[0] == int.class;
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

    private static final class BatteryServerHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            Method method = param.method instanceof Method ? (Method) param.method : null;
            if (method == null || !ConfigManager.isSystemServerBatterySpoofingEnabled()) return;
            if (param.args == null || param.args.length < 2 || !(param.args[0] instanceof Integer)) {
                return;
            }
            int callerUid = Binder.getCallingUid();
            if (!isScopedCaller(callerUid)) return;

            int propertyId = (Integer) param.args[0];
            Long spoofed = spoofedBatteryProperty(propertyId);
            if (spoofed == null) return;

            Object batteryProperty = param.args[1];
            if (!setBatteryPropertyLong(batteryProperty, spoofed)) {
                Log.w(TAG, "audit:battery property set failed method="
                        + method.getName() + " id=" + propertyId);
                return;
            }
            trace("spoof-before", method.getName(), callerUid, propertyId, spoofed);
            param.setResult(BATTERY_PROPERTY_SUCCESS);
        }
    }

    private static Long spoofedBatteryProperty(int propertyId) {
        if (propertyId == BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) {
            return ConfigManager.getBatteryChargeCounterUah();
        }
        if (propertyId == BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER) {
            return ConfigManager.getBatteryEnergyCounterNwh();
        }
        return null;
    }

    private static boolean setBatteryPropertyLong(Object batteryProperty, long value) {
        if (batteryProperty == null) return false;
        try {
            Method setLong = findMethod(batteryProperty.getClass(), "setLong", long.class);
            if (setLong == null) return false;
            setLong.invoke(batteryProperty, value);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
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
                sPackageManager = pm;
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

    private static Set<String> scopePackages() {
        return parsePackageSet(ConfigManager.getSystemServerScopePackagesRaw());
    }

    private static Set<String> parsePackageSet(String raw) {
        HashSet<String> out = new HashSet<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        String[] items = raw.split("[,;\\s]+");
        for (String item : items) {
            String pkg = item == null ? "" : item.trim();
            if (looksLikePackageName(pkg)) out.add(pkg);
        }
        return out;
    }

    private static boolean looksLikePackageName(String value) {
        if (value == null) return false;
        return value.indexOf('.') > 0
                && value.toLowerCase(Locale.ROOT).equals(value)
                && !value.contains("/")
                && !value.contains(":");
    }

    private static void trace(String phase, String methodName, int callerUid,
                              int propertyId, long value) {
        Log.w(TAG, "audit:" + phase
                + " method=" + methodName
                + " callerUid=" + callerUid
                + " caller=" + join(packagesForUid(callerUid))
                + " propertyId=" + propertyId
                + " value=" + value);
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
