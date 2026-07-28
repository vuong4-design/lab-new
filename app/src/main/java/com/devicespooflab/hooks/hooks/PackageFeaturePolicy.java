package com.devicespooflab.hooks.hooks;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

final class PackageFeaturePolicy {

    private static final Set<String> PIXEL_7_PRO_FEATURES = new HashSet<>(Arrays.asList(
            "android.hardware.camera",
            "android.hardware.camera.autofocus",
            "android.hardware.camera.flash",
            "android.hardware.camera.front",
            "android.hardware.camera.any",
            "android.hardware.camera.ar",
            "android.hardware.camera.capability.manual_post_processing",
            "android.hardware.camera.capability.manual_sensor",
            "android.hardware.camera.capability.raw",
            "android.hardware.sensor.accelerometer",
            "android.hardware.sensor.gyroscope",
            "android.hardware.sensor.compass",
            "android.hardware.sensor.barometer",
            "android.hardware.sensor.light",
            "android.hardware.sensor.proximity",
            "android.hardware.sensor.stepcounter",
            "android.hardware.sensor.stepdetector",
            "android.hardware.telephony",
            "android.hardware.telephony.gsm",
            "android.hardware.telephony.cdma",
            "android.hardware.telephony.ims",
            "android.hardware.wifi",
            "android.hardware.wifi.direct",
            "android.hardware.wifi.aware",
            "android.hardware.bluetooth",
            "android.hardware.bluetooth_le",
            "android.hardware.nfc",
            "android.hardware.nfc.hce",
            "android.hardware.nfc.hcef",
            "android.hardware.nfc.ese",
            "android.hardware.nfc.uicc",
            "android.hardware.fingerprint",
            "android.hardware.biometrics.face",
            "android.hardware.touchscreen",
            "android.hardware.touchscreen.multitouch",
            "android.hardware.touchscreen.multitouch.distinct",
            "android.hardware.touchscreen.multitouch.jazzhand",
            "android.hardware.screen.portrait",
            "android.hardware.screen.landscape",
            "android.hardware.location",
            "android.hardware.location.gps",
            "android.hardware.location.network",
            "android.hardware.audio.output",
            "android.hardware.audio.low_latency",
            "android.hardware.audio.pro",
            "android.hardware.microphone",
            "android.hardware.usb.host",
            "android.hardware.usb.accessory",
            "android.hardware.vulkan.level",
            "android.hardware.vulkan.version",
            "android.hardware.vulkan.compute",
            "android.hardware.opengles.aep",
            "android.software.device_admin",
            "android.software.managed_users",
            "android.software.webview",
            "android.software.backup",
            "android.software.app_widgets",
            "android.software.voice_recognizers",
            "android.software.home_screen",
            "android.software.input_methods",
            "android.software.connectionservice",
            "android.software.autofill",
            "android.software.verified_boot",
            "android.software.secure_lock_screen"
    ));

    private static final Set<String> DENIED_FEATURE_TOKENS = new HashSet<>(Arrays.asList(
            "android.hardware.sensor.emulator",
            "goldfish"
    ));

    private PackageFeaturePolicy() {}

    static boolean shouldDenyFeature(String feature) {
        if (feature == null) return false;
        String lower = feature.toLowerCase(java.util.Locale.ROOT);
        for (String denied : DENIED_FEATURE_TOKENS) {
            if (lower.contains(denied.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    static boolean shouldForceFeature(String feature) {
        return feature != null && PIXEL_7_PRO_FEATURES.contains(feature);
    }

    static Set<String> forcedFeatures() {
        return PIXEL_7_PRO_FEATURES;
    }
}
