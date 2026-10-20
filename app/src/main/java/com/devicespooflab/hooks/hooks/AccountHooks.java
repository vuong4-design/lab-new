package com.devicespooflab.hooks.hooks;

import android.accounts.Account;

import com.devicespooflab.hooks.utils.ConfigManager;

import java.util.LinkedHashMap;

import com.devicespooflab.hooks.xposed.XC_MethodHook;
import com.devicespooflab.hooks.xposed.XposedBridge;
import com.devicespooflab.hooks.xposed.XposedHelpers;
import com.devicespooflab.hooks.xposed.LoadPackageParam;

// App-side fallback only; server-side mode handles broader AccountManager queries.
public class AccountHooks {

    private static final String TAG = "DeviceSpoofLab-Account";

    public static void hook(LoadPackageParam lpparam) {
        if (!ConfigManager.isHideAccountsEnabled()) {
            return;
        }
        if (ConfigManager.isSystemServerAccountHidingEnabled()) {
            XposedBridge.log(TAG + ": audit:account hiding delegated to system_server backend");
            return;
        }

        Class<?> am = XposedHelpers.findClassIfExists(
                "android.accounts.AccountManager", lpparam.classLoader);
        if (am == null) return;

        hookEmptyAccounts(am, "getAccounts");
        hookEmptyAccounts(am, "getAccountsAsUser", int.class);
        hookEmptyAccounts(am, "getAccountsByType", String.class);
        hookEmptyAccounts(am, "getAccountsByTypeAsUser", String.class, int.class);
        hookEmptyAccounts(am, "getAccountsForPackage", String.class, int.class);
        hookEmptyAccounts(am, "getAccountsByTypeForPackage", String.class, String.class);
        hookEmptyAccountMap(am, "getAccountsAndVisibilityForPackage",
                String.class, String.class);
    }

    private static final XC_MethodHook EMPTY_ACCOUNTS = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            param.setResult(new Account[0]);
        }
    };

    private static final XC_MethodHook EMPTY_ACCOUNT_MAP = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            param.setResult(new LinkedHashMap<String, Integer>());
        }
    };

    private static void hookEmptyAccounts(Class<?> clazz, String methodName,
                                          Class<?>... parameterTypes) {
        hook(clazz, methodName, EMPTY_ACCOUNTS, parameterTypes);
    }

    private static void hookEmptyAccountMap(Class<?> clazz, String methodName,
                                            Class<?>... parameterTypes) {
        hook(clazz, methodName, EMPTY_ACCOUNT_MAP, parameterTypes);
    }

    private static void hook(Class<?> clazz, String methodName,
                             XC_MethodHook callback, Class<?>... parameterTypes) {
        Object[] args = new Object[parameterTypes.length + 1];
        for (int i = 0; i < parameterTypes.length; i++) {
            args[i] = parameterTypes[i];
        }
        args[args.length - 1] = callback;
        try {
            XposedHelpers.findAndHookMethod(clazz, methodName, args);
        } catch (NoSuchMethodError ignored) {
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to hook " + methodName + ": " + t);
        }
    }
}
