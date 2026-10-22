package com.devicespooflab.hooks.hooks;

import com.devicespooflab.hooks.utils.ConfigManager;
import com.devicespooflab.hooks.xposed.LoadPackageParam;
import com.devicespooflab.hooks.xposed.XC_MethodHook;
import com.devicespooflab.hooks.xposed.XposedBridge;
import com.devicespooflab.hooks.xposed.XposedHelpers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ServiceProbeHooks {

    private static final String TAG = "DeviceSpoofLab-Service";
    private static final String SERVICE_MANAGER_CLASS = "android.os.ServiceManager";
    private static final Set<Class<?>> HOOKED_CLASSES =
            Collections.newSetFromMap(new ConcurrentHashMap<Class<?>, Boolean>());

    private ServiceProbeHooks() {}

    public static void hook(LoadPackageParam lpparam) {
        if (!ConfigManager.isServiceListSpoofEnabled()) {
            XposedBridge.log(TAG + ": service-list spoof disabled");
            return;
        }
        hookClassLoader(lpparam == null ? null : lpparam.classLoader);
        try {
            ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();
            if (systemClassLoader != null
                    && (lpparam == null || systemClassLoader != lpparam.classLoader)) {
                hookClassLoader(systemClassLoader);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void hookClassLoader(ClassLoader classLoader) {
        Class<?> serviceManager = XposedHelpers.findClassIfExists(
                SERVICE_MANAGER_CLASS, classLoader);
        if (serviceManager == null || !HOOKED_CLASSES.add(serviceManager)) {
            return;
        }

        XC_MethodHook listHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object result = param.getResult();
                if (!(result instanceof String[])) return;
                String[] filtered = filterServiceNames((String[]) result);
                if (filtered != result) {
                    param.setResult(filtered);
                    XposedBridge.log(TAG + ": audit:service-list method="
                            + param.method.getName()
                            + " hidden=" + (((String[]) result).length - filtered.length)
                            + " total=" + ((String[]) result).length);
                }
            }
        };

        XC_MethodHook lookupHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length == 0
                        || !(param.args[0] instanceof String)) {
                    return;
                }
                String name = (String) param.args[0];
                if (shouldHideService(name)) {
                    param.setResult(null);
                    XposedBridge.log(TAG + ": audit:service-hide method="
                            + param.method.getName() + " name=" + name);
                }
            }
        };

        for (String method : new String[]{"listServices", "getServiceNames"}) {
            try {
                XposedHelpers.findAndHookMethod(serviceManager, method, listHook);
            } catch (Throwable ignored) {
            }
        }
        for (String method : new String[]{"getService", "checkService"}) {
            try {
                XposedHelpers.findAndHookMethod(serviceManager, method,
                        String.class, lookupHook);
            } catch (Throwable ignored) {
            }
        }
        XposedBridge.log(TAG + ": audit:service-list hooks installed class="
                + serviceManager.getName());
    }

    private static String[] filterServiceNames(String[] names) {
        if (names == null || names.length == 0) return names;
        List<String> filtered = new ArrayList<>(names.length);
        for (String name : names) {
            if (!shouldHideService(name)) {
                filtered.add(name);
            }
        }
        if (filtered.size() == names.length) return names;
        return filtered.toArray(new String[0]);
    }

    private static boolean shouldHideService(String name) {
        if (name == null || name.isEmpty()) return false;
        String lowerName = name.toLowerCase(Locale.ROOT);
        String raw = ConfigManager.getServiceHiddenKeywordsRaw();
        if (raw == null || raw.trim().isEmpty()) return false;
        for (String item : raw.split("[,;|\\s]+")) {
            String keyword = item == null ? "" : item.trim().toLowerCase(Locale.ROOT);
            if (keyword.length() >= 2 && lowerName.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
