package com.devicespooflab.hooks;

import android.util.Log;

import com.devicespooflab.hooks.hooks.SystemServerAccountHooks;
import com.devicespooflab.hooks.hooks.SystemServerBatteryHooks;
import com.devicespooflab.hooks.hooks.SystemServerPackageHooks;
import com.devicespooflab.hooks.utils.ConfigManager;

import java.util.HashMap;

public final class SystemServerEntry {

    private static final String TAG = "DeviceSpoofLab-SystemServer";
    private static volatile boolean sInstalled = false;

    private SystemServerEntry() {}

    public static synchronized void installWithProfile(HashMap<String, String> profile) {
        Log.w(TAG, "audit:entry profileSize=" + (profile == null ? -1 : profile.size()));
        if (sInstalled) {
            Log.w(TAG, "audit:entry already installed");
            return;
        }

        try {
            ConfigManager.init();
            if (profile != null && !profile.isEmpty()) {
                ConfigManager.setProperties(profile);
            }
            Log.w(TAG, "audit:entry config ready scope="
                    + ConfigManager.getSystemServerScopePackagesRaw()
                    + " hidden=" + ConfigManager.getHiddenPackagesRaw());
        } catch (Throwable t) {
            Log.w(TAG, "audit:entry config failed: " + t, t);
            return;
        }

        sInstalled = true;
        if (!ConfigManager.isSystemServerHooksEnabled()) {
            Log.w(TAG, "audit:entry disabled debug.system_server_hooks=0");
            return;
        }
        SystemServerPackageHooks.installAsync();
        SystemServerAccountHooks.installAsync();
        SystemServerBatteryHooks.installAsync();
    }
}
