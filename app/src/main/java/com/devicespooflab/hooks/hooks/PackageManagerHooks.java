package com.devicespooflab.hooks.hooks;

import android.content.pm.FeatureInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.devicespooflab.hooks.utils.ConfigManager;
import com.devicespooflab.hooks.xposed.XC_MethodHook;
import com.devicespooflab.hooks.xposed.XposedBridge;
import com.devicespooflab.hooks.xposed.XposedHelpers;
import com.devicespooflab.hooks.xposed.LoadPackageParam;

public class PackageManagerHooks {

    private static final String TAG = "DeviceSpoofLab-PackageManager";

    public static void hook(LoadPackageParam lpparam) {
        if (ConfigManager.isSystemServerFeatureSpoofingEnabled()) {
            XposedBridge.log(TAG + ": audit:feature spoofing delegated to system_server backend");
            return;
        }
        try {
            Class<?> appPackageManagerClass = XposedHelpers.findClassIfExists(
                "android.app.ApplicationPackageManager", lpparam.classLoader);

            if (appPackageManagerClass != null) {
                hookHasSystemFeature(appPackageManagerClass);
                hookGetSystemAvailableFeatures(appPackageManagerClass);
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to hook PackageManager: " + e.getMessage());
        }
    }

    private static void hookHasSystemFeature(Class<?> pmClass) {
        try {
            XposedHelpers.findAndHookMethod(pmClass, "hasSystemFeature",
                String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        String feature = (String) param.args[0];

                        if (feature == null) {
                            return;
                        }

                        if (PackageFeaturePolicy.shouldDenyFeature(feature)) {
                            param.setResult(false);
                            return;
                        }

                        if (PackageFeaturePolicy.shouldForceFeature(feature)) {
                            param.setResult(true);
                        }
                    }
                });
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to hook hasSystemFeature(String): " + e.getMessage());
        }

        try {
            XposedHelpers.findAndHookMethod(pmClass, "hasSystemFeature",
                String.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        String feature = (String) param.args[0];

                        if (feature == null) {
                            return;
                        }

                        if (PackageFeaturePolicy.shouldDenyFeature(feature)) {
                            param.setResult(false);
                            return;
                        }

                        if (PackageFeaturePolicy.shouldForceFeature(feature)) {
                            param.setResult(true);
                        }
                    }
                });
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to hook hasSystemFeature(String, int): " + e.getMessage());
        }
    }

    private static void hookGetSystemAvailableFeatures(Class<?> pmClass) {
        try {
            XposedHelpers.findAndHookMethod(pmClass, "getSystemAvailableFeatures",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object[] features = (Object[]) param.getResult();
                        if (features == null) {
                            return;
                        }

                        Class<?> featureInfoClass = features.getClass().getComponentType();

                        List<Object> filtered = new ArrayList<>();
                        Set<String> present = new HashSet<>();
                        for (Object feature : features) {
                            try {
                                String name = (String) XposedHelpers.getObjectField(feature, "name");
                                if (name != null) {
                                    if (!PackageFeaturePolicy.shouldDenyFeature(name)) {
                                        filtered.add(feature);
                                        present.add(name);
                                    }
                                } else {
                                    filtered.add(feature);
                                }
                            } catch (Exception e) {
                                filtered.add(feature);
                            }
                        }

                        for (String forced : PackageFeaturePolicy.forcedFeatures()) {
                            if (present.contains(forced)) {
                                continue;
                            }
                            Object info = newFeatureInfo(featureInfoClass, forced);
                            if (info != null) {
                                filtered.add(info);
                            }
                        }

                        Object typedArray = java.lang.reflect.Array.newInstance(
                            featureInfoClass, filtered.size());
                        for (int i = 0; i < filtered.size(); i++) {
                            java.lang.reflect.Array.set(typedArray, i, filtered.get(i));
                        }
                        param.setResult(typedArray);
                    }
                });
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to hook getSystemAvailableFeatures(): " + e.getMessage());
        }
    }

    private static Object newFeatureInfo(Class<?> component, String name) {
        try {
            Object info;
            if (component != null && component != Object.class
                    && FeatureInfo.class.isAssignableFrom(component)) {
                info = component.getDeclaredConstructor().newInstance();
            } else {
                info = new FeatureInfo();
            }
            XposedHelpers.setObjectField(info, "name", name);
            return info;
        } catch (Throwable t) {
            return null;
        }
    }
}
