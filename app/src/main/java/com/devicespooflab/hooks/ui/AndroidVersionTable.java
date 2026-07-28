package com.devicespooflab.hooks.ui;

import com.devicespooflab.hooks.utils.ConfigManager;

import java.util.LinkedHashMap;
import java.util.Map;

public final class AndroidVersionTable {

    public static final class Entry {
        public final int sdk;
        public final String release;
        public final String displayName;

        Entry(int sdk, String release, String displayName) {
            this.sdk = sdk;
            this.release = release;
            this.displayName = displayName;
        }
    }

    private static final Entry[] ENTRIES = {
            new Entry(26, "8.0", "Android 8 (SDK 26)"),
            new Entry(27, "8.1", "Android 8.1 (SDK 27)"),
            new Entry(28, "9", "Android 9 (SDK 28)"),
            new Entry(29, "10", "Android 10 (SDK 29)"),
            new Entry(30, "11", "Android 11 (SDK 30)"),
            new Entry(31, "12", "Android 12 (SDK 31)"),
            new Entry(32, "12.1", "Android 12L (SDK 32)"),
            new Entry(33, "13", "Android 13 (SDK 33)"),
            new Entry(34, "14", "Android 14 (SDK 34)"),
            new Entry(35, "15", "Android 15 (SDK 35)"),
            new Entry(36, "16", "Android 16 (SDK 36)"),
    };

    public static final int DEFAULT_INDEX = 10;

    public static int size() {
        return ENTRIES.length;
    }

    public static Entry get(int index) {
        if (index < 0) return ENTRIES[0];
        if (index >= ENTRIES.length) return ENTRIES[ENTRIES.length - 1];
        return ENTRIES[index];
    }

    public static int currentIndex() {
        String sdkStr = ConfigManager.getRawProperty("ro.build.version.sdk");
        int sdk;
        try { sdk = Integer.parseInt(sdkStr.trim()); }
        catch (NumberFormatException e) { return DEFAULT_INDEX; }
        for (int i = 0; i < ENTRIES.length; i++) {
            if (ENTRIES[i].sdk == sdk) return i;
        }
        return DEFAULT_INDEX;
    }

    public static Map<String, String> buildUpdates(Entry entry) {
        Map<String, String> out = new LinkedHashMap<>();
        String release = entry.release;
        String sdk = Integer.toString(entry.sdk);

        out.put("ro.build.version.release", release);
        out.put("ro.build.version.release_or_codename", release);
        out.put("ro.build.version.release_or_preview_display", release);
        out.put("ro.build.version.sdk", sdk);
        out.put("ro.product.build.version.release", release);
        out.put("ro.product.build.version.release_or_codename", release);
        out.put("ro.product.build.version.sdk", sdk);

        for (String partition : new String[]{
                "vendor", "vendor_dlkm", "odm", "bootimage", "system_dlkm"}) {
            out.put("ro.%s.build.version.release".replace("%s", partition), release);
            out.put("ro.%s.build.version.release_or_codename".replace("%s", partition), release);
        }

        rewriteFingerprintKey(out, "ro.build.fingerprint", release);
        rewriteFingerprintKey(out, "ro.product.build.fingerprint", release);
        rewriteFingerprintKey(out, "ro.system.build.fingerprint", release);
        rewriteFingerprintKey(out, "ro.system_ext.build.fingerprint", release);
        rewriteFingerprintKey(out, "ro.vendor.build.fingerprint", release);
        rewriteFingerprintKey(out, "ro.odm.build.fingerprint", release);
        rewriteFingerprintKey(out, "ro.bootimage.build.fingerprint", release);
        rewriteFingerprintKey(out, "ro.system_dlkm.build.fingerprint", release);
        rewriteFingerprintKey(out, "ro.vendor_dlkm.build.fingerprint", release);

        rewriteDescription(out, release);

        return out;
    }

    private static void rewriteFingerprintKey(
            Map<String, String> out, String key, String release) {
        String fp = ConfigManager.getRawProperty(key);
        String updated = replaceFingerprintRelease(fp, release);
        if (updated != null && !updated.isEmpty()) {
            out.put(key, updated);
        }
    }

    // Swap the release segment between the first ':' and the next '/' in a
    // fingerprint like google/cheetah/cheetah:15/AP4A.241205.013/12621605:user/release-keys.
    static String replaceFingerprintRelease(String fp, String newRelease) {
        if (fp == null || fp.isEmpty()) return fp;
        int colonIdx = fp.indexOf(':');
        if (colonIdx < 0) return fp;
        int nextSlash = fp.indexOf('/', colonIdx + 1);
        if (nextSlash < 0) return fp.substring(0, colonIdx + 1) + newRelease;
        return fp.substring(0, colonIdx + 1) + newRelease + fp.substring(nextSlash);
    }

    private static void rewriteDescription(Map<String, String> out, String release) {
        String desc = ConfigManager.getRawProperty("ro.build.description");
        if (desc == null || desc.isEmpty()) return;
        String[] parts = desc.split(" ");
        if (parts.length >= 2) {
            parts[1] = release;
            StringBuilder rebuilt = new StringBuilder(desc.length());
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) rebuilt.append(' ');
                rebuilt.append(parts[i]);
            }
            out.put("ro.build.description", rebuilt.toString());
        }
    }

    private AndroidVersionTable() {}
}
