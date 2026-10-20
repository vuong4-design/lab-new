package com.devicespooflab.hooks.hooks;

import android.accounts.Account;
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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class SystemServerAccountHooks {

    private static final String TAG = "DeviceSpoofLab-SystemAM";
    private static final int SYSTEM_UID = 1000;
    private static final long WAIT_MS = 200L;
    private static final int WAIT_ATTEMPTS = 300;
    private static final Object NO_EMPTY_RESULT = new Object();

    private static final Set<String> HOOKED =
            Collections.synchronizedSet(new HashSet<String>());
    private static final Map<Integer, String[]> UID_PACKAGES =
            new ConcurrentHashMap<>();

    private static volatile boolean sWorkerStarted = false;
    private static volatile Object sPackageManager = null;

    private SystemServerAccountHooks() {}

    public static synchronized void installAsync() {
        if (!isActive()) {
            Log.w(TAG, "audit:disabled system_server account hooks hideAccounts="
                    + ConfigManager.isHideAccountsEnabled()
                    + " server=" + ConfigManager.isSystemServerAccountHidingEnabled());
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
                installWhenAccountServiceReady();
            }
        }, "DeviceSpoofLab-system-server-account");
        t.start();
    }

    private static boolean isActive() {
        return ConfigManager.isHideAccountsEnabled()
                && ConfigManager.isSystemServerAccountHidingEnabled();
    }

    private static void installWhenAccountServiceReady() {
        try {
            Object accountManager = waitForAccountManager();
            if (accountManager == null) {
                Log.w(TAG, "audit:account service not ready after wait");
                return;
            }
            Log.w(TAG, "audit:account service ready class="
                    + accountManager.getClass().getName()
                    + " scope=" + ConfigManager.getSystemServerScopePackagesRaw());

            int hooked = 0;
            hooked += hookClass(accountManager.getClass());
            hooked += hookClassName("com.android.server.accounts.AccountManagerService");
            hooked += hookClassName("com.android.server.accounts.AccountManagerService$AccountManagerInternalImpl");

            Log.w(TAG, "audit:system_server account hooks installed count=" + hooked);
        } catch (Throwable t) {
            Log.w(TAG, "audit:system_server account hooks failed: " + t, t);
        }
    }

    private static Object waitForAccountManager() {
        for (int i = 0; i < WAIT_ATTEMPTS; i++) {
            try {
                IBinder binder = getService("account");
                if (binder != null) {
                    Object accountManager = asIAccountManager(binder);
                    if (accountManager != null) return accountManager;
                }
            } catch (Throwable t) {
                if (i == 0 || ConfigManager.isSystemServerPackageTraceEnabled()) {
                    Log.w(TAG, "audit:wait account service attempt=" + i + " failed: " + t);
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

    private static Object asIAccountManager(IBinder binder) throws Throwable {
        Class<?> stub = Class.forName("android.accounts.IAccountManager$Stub");
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
                SystemServerAccountHooks.class.getClassLoader(),
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
                XposedBridge.hookMethod(method, new AccountServerHook());
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
        return "getAccounts".equals(name)
                || "getAccountsAsUser".equals(name)
                || "getAccountsByType".equals(name)
                || "getAccountsByTypeAsUser".equals(name)
                || "getAccountsForPackage".equals(name)
                || "getAccountsByTypeForPackage".equals(name)
                || "getAccountsAndVisibilityForPackage".equals(name);
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

    private static final class AccountServerHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            Method method = param.method instanceof Method ? (Method) param.method : null;
            if (method == null || !isActive()) return;
            int callerUid = Binder.getCallingUid();
            if (!isScopedCaller(callerUid)) return;

            Object empty = emptyResultFor(method);
            if (empty == NO_EMPTY_RESULT) return;
            trace("hide-before", method.getName(), callerUid);
            param.setResult(empty);
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            Method method = param.method instanceof Method ? (Method) param.method : null;
            if (method == null || !isActive()) return;
            int callerUid = Binder.getCallingUid();
            if (!isScopedCaller(callerUid)) return;

            Object result = param.getResult();
            Object filtered = emptyResultLike(result);
            if (filtered == NO_EMPTY_RESULT) return;
            trace("hide-after", method.getName(), callerUid);
            param.setResult(filtered);
        }
    }

    private static Object emptyResultFor(Method method) {
        Class<?> type = method.getReturnType();
        if (type == Account[].class) return new Account[0];
        if (Map.class.isAssignableFrom(type)) return new LinkedHashMap<Object, Object>();
        return NO_EMPTY_RESULT;
    }

    private static Object emptyResultLike(Object result) {
        if (result instanceof Account[]) return new Account[0];
        if (result instanceof Map<?, ?>) return new LinkedHashMap<Object, Object>();
        return NO_EMPTY_RESULT;
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

    private static void trace(String phase, String methodName, int callerUid) {
        Log.w(TAG, "audit:" + phase
                + " method=" + methodName
                + " callerUid=" + callerUid
                + " caller=" + join(packagesForUid(callerUid)));
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
