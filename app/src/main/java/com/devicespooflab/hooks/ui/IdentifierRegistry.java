package com.devicespooflab.hooks.ui;

import com.devicespooflab.hooks.utils.ConfigManager;
import com.devicespooflab.hooks.utils.RandomGenerator;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public final class IdentifierRegistry {

    public interface Generator {
        String generate();
    }

    public static final String CAT_DEVICE = "Device Identifiers";
    public static final String CAT_SIM = "SIM & Carrier";
    public static final String CAT_NETWORK = "Network";
    public static final String CAT_WEB = "Web";

    public static final class Definition {
        public final String id;
        public final String displayName;
        public final String category;
        public final String[] configKeys;
        public final Generator generator;
        public final Pattern validator;

        Definition(String id, String displayName, String category,
                   String[] configKeys, Generator generator, String validatorRegex) {
            this.id = id;
            this.displayName = displayName;
            this.category = category;
            this.configKeys = configKeys;
            this.generator = generator;
            this.validator = validatorRegex == null ? null : Pattern.compile(validatorRegex);
        }

        public String primaryKey() {
            return configKeys[0];
        }

        public boolean isValid(String value) {
            if (value == null || value.isEmpty()) return false;
            return validator == null || validator.matcher(value).matches();
        }
    }

    private static final String MAC_PATTERN = "([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}";
    private static final String UUID_PATTERN =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    private static final Map<String, Definition> BY_ID = new LinkedHashMap<>();
    private static final Map<String, String> KEY_TO_ID = new HashMap<>();

    static {
        add("serial", "Serial Number", CAT_DEVICE,
                new String[]{"SERIAL_NUMBER", "ro.serialno", "ro.boot.serialno"},
                RandomGenerator::generateSerial,
                "[0-9A-Za-z]{6,32}");
        add("android_id", "Android ID", CAT_DEVICE,
                new String[]{"ANDROID_ID"},
                RandomGenerator::generateAndroidId,
                "[0-9a-fA-F]{16}");
        add("gsf_id", "GSF ID", CAT_DEVICE,
                new String[]{"identifier.gsf_id"},
                RandomGenerator::generateGSFId,
                "[0-9a-fA-F]{16}");
        add("gaid", "Google Advertising ID", CAT_DEVICE,
                new String[]{"identifier.gaid"},
                RandomGenerator::generateGAID,
                UUID_PATTERN);
        add("app_set_id", "App Set ID", CAT_DEVICE,
                new String[]{"identifier.app_set_id"},
                RandomGenerator::generateGAID,
                UUID_PATTERN);
        add("media_drm_id", "Media DRM ID", CAT_DEVICE,
                new String[]{"identifier.media_drm_id"},
                () -> RandomGenerator.generateHex(64),
                "[0-9a-fA-F]{64}");

        add("imei", "IMEI", CAT_SIM,
                new String[]{"identifier.imei"},
                RandomGenerator::generateIMEI,
                "\\d{15}");
        add("meid", "MEID", CAT_SIM,
                new String[]{"identifier.meid"},
                RandomGenerator::generateMEID,
                "[0-9A-Fa-f]{14}");
        add("imsi", "IMSI", CAT_SIM,
                new String[]{"identifier.imsi"},
                RandomGenerator::generateIMSI,
                "\\d{15}");
        add("iccid", "ICCID (SIM Serial)", CAT_SIM,
                new String[]{"identifier.iccid"},
                RandomGenerator::generateICCID,
                "\\d{19,20}");
        add("phone_number", "Phone Number", CAT_SIM,
                new String[]{"identifier.phone_number"},
                RandomGenerator::generatePhoneNumber,
                "\\+?\\d{7,15}");
        add("eid", "eSIM EID", CAT_SIM,
                new String[]{"euicc.eid"},
                RandomGenerator::generateEid,
                "\\d{32}");

        // Network
        add("wifi_mac", "WiFi MAC", CAT_NETWORK,
                new String[]{"wifi.mac"},
                RandomGenerator::generateMacAddress,
                MAC_PATTERN);
        add("wifi_bssid", "WiFi BSSID", CAT_NETWORK,
                new String[]{"wifi.bssid"},
                RandomGenerator::generateBssid,
                MAC_PATTERN);
        add("bluetooth_mac", "Bluetooth MAC", CAT_NETWORK,
                new String[]{"bluetooth.mac"},
                RandomGenerator::generateMacAddress,
                MAC_PATTERN);

        // Web
        add("fingerprint_seed", "Web Fingerprint Seed", CAT_WEB,
                new String[]{"fingerprint.seed"},
                () -> Long.toString(RandomGenerator.stableSeed(UUID.randomUUID().toString())),
                "-?\\d+");
    }

    private static void add(String id, String displayName, String category,
                            String[] configKeys, Generator gen, String validator) {
        BY_ID.put(id, new Definition(id, displayName, category, configKeys, gen, validator));
        for (String key : configKeys) {
            KEY_TO_ID.put(key, id);
        }
    }

    public static List<Definition> all() {
        return Collections.unmodifiableList(new java.util.ArrayList<>(BY_ID.values()));
    }

    public static Definition byId(String id) {
        return BY_ID.get(id);
    }

    public static String identifierForKey(String key) {
        return KEY_TO_ID.get(key);
    }

    private IdentifierRegistry() {}
}
