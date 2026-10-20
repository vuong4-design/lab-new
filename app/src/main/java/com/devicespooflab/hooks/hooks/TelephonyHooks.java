package com.devicespooflab.hooks.hooks;

import com.devicespooflab.hooks.utils.ConfigManager;

import com.devicespooflab.hooks.xposed.XC_MethodHook;
import com.devicespooflab.hooks.xposed.XposedBridge;
import com.devicespooflab.hooks.xposed.XposedHelpers;
import com.devicespooflab.hooks.xposed.LoadPackageParam;

public class TelephonyHooks {

    public static void hook(LoadPackageParam lpparam) {
        Class<?> telephonyManager = XposedHelpers.findClassIfExists(
                "android.telephony.TelephonyManager",
                lpparam.classLoader
        );

        if (telephonyManager == null) {
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getDeviceId",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String v = ConfigManager.getIMEI();
                            if (v != null) param.setResult(v);
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getDeviceId", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String v = ConfigManager.getIMEI();
                            if (v != null) param.setResult(v);
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getImei",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String v = ConfigManager.getIMEI();
                            if (v != null) param.setResult(v);
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getImei", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String v = ConfigManager.getIMEI();
                            if (v != null) param.setResult(v);
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getMeid",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String v = ConfigManager.getMEID();
                            if (v != null) param.setResult(v);
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getMeid", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String v = ConfigManager.getMEID();
                            if (v != null) param.setResult(v);
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }

        if (!ConfigManager.isFakeSimEnabled()) {
            return;
        }

        if (!hookFakeSim(lpparam, telephonyManager)) {
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getSubscriberId",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String v = ConfigManager.getIMSI();
                            if (v != null) param.setResult(v);
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getSubscriberId", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (((Integer) param.args[0])
                                    != ConfigManager.getFakeSimSubscriptionId()) {
                                param.setResult(null);
                                return;
                            }
                            String v = ConfigManager.getIMSI();
                            if (v != null) param.setResult(v);
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getSimSerialNumber",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String v = ConfigManager.getICCID();
                            if (v != null) param.setResult(v);
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getSimSerialNumber", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (((Integer) param.args[0])
                                    != ConfigManager.getFakeSimSubscriptionId()) {
                                param.setResult(null);
                                return;
                            }
                            String v = ConfigManager.getICCID();
                            if (v != null) param.setResult(v);
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getLine1Number",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String v = ConfigManager.getPhoneNumber();
                            if (v != null) param.setResult(v);
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getLine1Number", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (((Integer) param.args[0])
                                    != ConfigManager.getFakeSimSubscriptionId()) {
                                param.setResult(null);
                                return;
                            }
                            String v = ConfigManager.getPhoneNumber();
                            if (v != null) param.setResult(v);
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }

        // Hook network operator methods (MCC/MNC)
        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getNetworkOperator",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String mccMnc = ConfigManager.getSystemProperty("gsm.operator.numeric", null);
                            if (mccMnc != null) {
                                param.setResult(mccMnc);
                            }
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getNetworkOperatorName",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String operatorName = ConfigManager.getSystemProperty("gsm.operator.alpha", null);
                            if (operatorName != null) {
                                param.setResult(operatorName);
                            }
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getSimOperator",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String simMccMnc = ConfigManager.getSystemProperty("gsm.sim.operator.numeric", null);
                            if (simMccMnc != null) {
                                param.setResult(simMccMnc);
                            }
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getSimOperatorName",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String simOperatorName = ConfigManager.getSystemProperty("gsm.sim.operator.alpha", null);
                            if (simOperatorName != null) {
                                param.setResult(simOperatorName);
                            }
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getSimCountryIso",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String simCountry = ConfigManager.getSystemProperty("gsm.sim.operator.iso-country", null);
                            if (simCountry != null) {
                                param.setResult(simCountry);
                            }
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getNetworkCountryIso",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String networkCountry = ConfigManager.getSystemProperty("gsm.operator.iso-country", null);
                            if (networkCountry != null) {
                                param.setResult(networkCountry);
                            }
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }

        hookStringResult(telephonyManager, "getNetworkOperator",
                new ValueSupplier() {
                    @Override
                    public String get() {
                        return ConfigManager.getSystemProperty("gsm.operator.numeric", null);
                    }
                }, int.class);
        hookStringResult(telephonyManager, "getNetworkOperatorName",
                new ValueSupplier() {
                    @Override
                    public String get() {
                        return ConfigManager.getSystemProperty("gsm.operator.alpha", null);
                    }
                }, int.class);
        hookStringResult(telephonyManager, "getSimOperator",
                new ValueSupplier() {
                    @Override
                    public String get() {
                        return ConfigManager.getSystemProperty("gsm.sim.operator.numeric", null);
                    }
                }, int.class);
        hookStringResult(telephonyManager, "getSimOperatorName",
                new ValueSupplier() {
                    @Override
                    public String get() {
                        return ConfigManager.getSystemProperty("gsm.sim.operator.alpha", null);
                    }
                }, int.class);
        hookStringResult(telephonyManager, "getSimCountryIso",
                new ValueSupplier() {
                    @Override
                    public String get() {
                        return ConfigManager.getSystemProperty("gsm.sim.operator.iso-country", null);
                    }
                }, int.class);
        hookStringResult(telephonyManager, "getNetworkCountryIso",
                new ValueSupplier() {
                    @Override
                    public String get() {
                        return ConfigManager.getSystemProperty("gsm.operator.iso-country", null);
                    }
                }, int.class);
        hookStringResult(telephonyManager, "getNetworkOperatorForPhone",
                new ValueSupplier() {
                    @Override
                    public String get() {
                        return ConfigManager.getSystemProperty("gsm.operator.numeric", null);
                    }
                }, int.class);
        hookStringResult(telephonyManager, "getSimOperatorNumeric",
                new ValueSupplier() {
                    @Override
                    public String get() {
                        return ConfigManager.getSystemProperty("gsm.sim.operator.numeric", null);
                    }
                });
        hookStringResult(telephonyManager, "getSimOperatorNumeric",
                new ValueSupplier() {
                    @Override
                    public String get() {
                        return ConfigManager.getSystemProperty("gsm.sim.operator.numeric", null);
                    }
                }, int.class);
        hookStringResult(telephonyManager, "getSimOperatorNumericForPhone",
                new ValueSupplier() {
                    @Override
                    public String get() {
                        return ConfigManager.getSystemProperty("gsm.sim.operator.numeric", null);
                    }
                }, int.class);
        hookStringResult(telephonyManager, "getSimOperatorNameForPhone",
                new ValueSupplier() {
                    @Override
                    public String get() {
                        return ConfigManager.getSystemProperty("gsm.sim.operator.alpha", null);
                    }
                }, int.class);
        hookStringResult(telephonyManager, "getSimCountryIsoForPhone",
                new ValueSupplier() {
                    @Override
                    public String get() {
                        return ConfigManager.getSystemProperty("gsm.sim.operator.iso-country", null);
                    }
                }, int.class);
        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getSimState",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(android.telephony.TelephonyManager.SIM_STATE_READY);
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }
        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getSimState", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            int slot = (Integer) param.args[0];
                            param.setResult(slot == ConfigManager.getFakeSimSlotIndex()
                                    ? android.telephony.TelephonyManager.SIM_STATE_READY
                                    : android.telephony.TelephonyManager.SIM_STATE_ABSENT);
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }
        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getSimState",
                    int.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            int slot = (Integer) param.args[0];
                            param.setResult(slot == ConfigManager.getFakeSimSlotIndex()
                                    ? android.telephony.TelephonyManager.SIM_STATE_READY
                                    : android.telephony.TelephonyManager.SIM_STATE_ABSENT);
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }

        // Android 13+ replaced TelephonyManager.getLine1Number() with
        // SubscriptionManager.getPhoneNumber(int) and getPhoneNumber(int, int).
        Class<?> subscriptionManager = XposedHelpers.findClassIfExists(
                "android.telephony.SubscriptionManager", lpparam.classLoader);
        if (subscriptionManager != null) {
            XC_MethodHook phoneNumberHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0 && param.args[0] instanceof Integer
                            && ((Integer) param.args[0])
                            != ConfigManager.getFakeSimSubscriptionId()) {
                        param.setResult(null);
                        return;
                    }
                    String v = ConfigManager.getPhoneNumber();
                    if (v != null) param.setResult(v);
                }
            };
            try {
                XposedHelpers.findAndHookMethod(subscriptionManager, "getPhoneNumber",
                        int.class, phoneNumberHook);
            } catch (NoSuchMethodError ignored) {
            }
            try {
                XposedHelpers.findAndHookMethod(subscriptionManager, "getPhoneNumber",
                        int.class, int.class, phoneNumberHook);
            } catch (NoSuchMethodError ignored) {
            }
            hookStringResult(subscriptionManager, "getSubscriberId",
                    new ValueSupplier() {
                        @Override
                        public String get() {
                            return ConfigManager.getIMSI();
                        }
                    }, int.class);
            hookStringResult(subscriptionManager, "getIccSerialNumber",
                    new ValueSupplier() {
                        @Override
                        public String get() {
                            return ConfigManager.getICCID();
                        }
                    }, int.class);
        }

        Class<?> subscriptionInfo = XposedHelpers.findClassIfExists(
                "android.telephony.SubscriptionInfo", lpparam.classLoader);
        if (subscriptionInfo != null) {
            hookStringResult(subscriptionInfo, "getIccId",
                    new ValueSupplier() {
                        @Override
                        public String get() {
                            return ConfigManager.getICCID();
                        }
                    });
            hookStringResult(subscriptionInfo, "getNumber",
                    new ValueSupplier() {
                        @Override
                        public String get() {
                            return ConfigManager.getPhoneNumber();
                        }
                    });
            hookStringResult(subscriptionInfo, "getCarrierName",
                    new ValueSupplier() {
                        @Override
                        public String get() {
                            return ConfigManager.getSystemProperty("gsm.sim.operator.alpha", null);
                        }
                    });
            hookStringResult(subscriptionInfo, "getDisplayName",
                    new ValueSupplier() {
                        @Override
                        public String get() {
                            return ConfigManager.getSystemProperty("gsm.sim.operator.alpha", null);
                        }
                    });
            hookStringResult(subscriptionInfo, "getCountryIso",
                    new ValueSupplier() {
                        @Override
                        public String get() {
                            return ConfigManager.getSystemProperty("gsm.sim.operator.iso-country", null);
                        }
                    });
            hookStringResult(subscriptionInfo, "getMccString",
                    new ValueSupplier() {
                        @Override
                        public String get() {
                            return mccMnc("gsm.sim.operator.numeric", true);
                        }
                    });
            hookStringResult(subscriptionInfo, "getMncString",
                    new ValueSupplier() {
                        @Override
                        public String get() {
                            return mccMnc("gsm.sim.operator.numeric", false);
                        }
                    });
            hookIntResult(subscriptionInfo, "getMcc",
                    new ValueSupplier() {
                        @Override
                        public String get() {
                            return mccMnc("gsm.sim.operator.numeric", true);
                        }
                    });
            hookIntResult(subscriptionInfo, "getMnc",
                    new ValueSupplier() {
                        @Override
                        public String get() {
                            return mccMnc("gsm.sim.operator.numeric", false);
                        }
                    });
        }
    }

    private static boolean hookFakeSim(LoadPackageParam lpparam, Class<?> telephonyManager) {
        Class<?> subscriptionInfoClass = XposedHelpers.findClassIfExists(
                "android.telephony.SubscriptionInfo", lpparam.classLoader);
        Object subscriptionInfo = FakeSimFactory.getSubscriptionInfo(subscriptionInfoClass);
        if (subscriptionInfo == null) {
            XposedBridge.log("DeviceSpoofLab-Telephony: synthetic SubscriptionInfo unavailable; "
                    + "keeping real SIM state");
            return false;
        }

        int subId = ConfigManager.getFakeSimSubscriptionId();
        int slot = ConfigManager.getFakeSimSlotIndex();
        int phoneCount = ConfigManager.getFakeSimPhoneCount();
        int activeModems = ConfigManager.getFakeSimActiveModemCount();
        int supportedModems = ConfigManager.getFakeSimSupportedModemCount();
        int voiceType = ConfigManager.getFakeSimVoiceNetworkType();
        int dataType = ConfigManager.getFakeSimDataNetworkType();

        hookIntResult(telephonyManager, "getPhoneCount", constant(String.valueOf(phoneCount)));
        hookIntResult(telephonyManager, "getActiveModemCount", constant(String.valueOf(activeModems)));
        hookIntResult(telephonyManager, "getSupportedModemCount", constant(String.valueOf(supportedModems)));
        hookIntResult(telephonyManager, "getNetworkType", constant(String.valueOf(dataType)));
        hookIntResult(telephonyManager, "getDataNetworkType", constant(String.valueOf(dataType)));
        hookIntResult(telephonyManager, "getVoiceNetworkType", constant(String.valueOf(voiceType)));
        hookIntResult(telephonyManager, "getDataState", constant("2"));
        hookIntResult(telephonyManager, "getCallState", constant("0"));
        hookBooleanResult(telephonyManager, "hasIccCard", true);
        hookBooleanResult(telephonyManager, "isDataEnabled", true);

        Class<?> subscriptionManager = XposedHelpers.findClassIfExists(
                "android.telephony.SubscriptionManager", lpparam.classLoader);
        if (subscriptionManager != null) {
            hookIntResult(subscriptionManager, "getActiveSubscriptionInfoCount", constant("1"));
            hookIntResult(subscriptionManager, "getActiveSubscriptionInfoCountMax",
                    constant(String.valueOf(phoneCount)));
            hookIntResult(subscriptionManager, "getDefaultDataSubscriptionId",
                    constant(String.valueOf(subId)));
            hookIntResult(subscriptionManager, "getDefaultSubscriptionId",
                    constant(String.valueOf(subId)));
            hookIntResult(subscriptionManager, "getDefaultSmsSubscriptionId",
                    constant(String.valueOf(subId)));
            hookIntResult(subscriptionManager, "getDefaultVoiceSubscriptionId",
                    constant(String.valueOf(subId)));
            hookActiveSubscriptionInfo(subscriptionManager, subscriptionInfo, subId, slot);
        }

        Class<?> serviceState = XposedHelpers.findClassIfExists(
                "android.telephony.ServiceState", lpparam.classLoader);
        if (serviceState != null) {
            String numeric = ConfigManager.getSystemProperty("gsm.operator.numeric", null);
            String alpha = ConfigManager.getSystemProperty("gsm.operator.alpha", null);
            hookIntResult(serviceState, "getState", constant("0"));
            hookIntResult(serviceState, "getVoiceRegState", constant("0"));
            hookIntResult(serviceState, "getDataRegState", constant("0"));
            hookStringResult(serviceState, "getOperatorNumeric", constant(numeric));
            hookStringResult(serviceState, "getOperatorAlphaLong", constant(alpha));
            hookStringResult(serviceState, "getOperatorAlphaShort", constant(alpha));
            hookStringResult(serviceState, "getDataOperatorNumeric", constant(numeric));
            hookStringResult(serviceState, "getVoiceOperatorNumeric", constant(numeric));
            hookBooleanResult(serviceState, "getRoaming", false);
        }
        return true;
    }

    private static void hookActiveSubscriptionInfo(Class<?> subscriptionManager,
                                                   Object subscriptionInfo,
                                                   int subId, int slot) {
        XC_MethodHook listHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                param.setResult(java.util.Collections.singletonList(subscriptionInfo));
            }
        };
        try {
            XposedHelpers.findAndHookMethod(subscriptionManager,
                    "getActiveSubscriptionInfoList", listHook);
        } catch (NoSuchMethodError ignored) {
        }
        try {
            XposedHelpers.findAndHookMethod(subscriptionManager,
                    "getActiveSubscriptionInfoList", String.class, listHook);
        } catch (NoSuchMethodError ignored) {
        }
        try {
            XposedHelpers.findAndHookMethod(subscriptionManager,
                    "getActiveSubscriptionInfoList", String.class, String.class, listHook);
        } catch (NoSuchMethodError ignored) {
        }

        XC_MethodHook byIdHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object arg = param.args.length > 0 ? param.args[0] : null;
                if (!(arg instanceof Integer) || ((Integer) arg) != subId) {
                    param.setResult(null);
                } else {
                    param.setResult(subscriptionInfo);
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(subscriptionManager,
                    "getActiveSubscriptionInfo", int.class, byIdHook);
        } catch (NoSuchMethodError ignored) {
        }
        try {
            XposedHelpers.findAndHookMethod(subscriptionManager,
                    "getActiveSubscriptionInfo", int.class, String.class, byIdHook);
        } catch (NoSuchMethodError ignored) {
        }

        XC_MethodHook bySlotHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object arg = param.args.length > 0 ? param.args[0] : null;
                if (!(arg instanceof Integer) || ((Integer) arg) != slot) {
                    param.setResult(null);
                } else {
                    param.setResult(subscriptionInfo);
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(subscriptionManager,
                    "getActiveSubscriptionInfoForSimSlotIndex", int.class, bySlotHook);
        } catch (NoSuchMethodError ignored) {
        }
        try {
            XposedHelpers.findAndHookMethod(subscriptionManager,
                    "getActiveSubscriptionInfoForSimSlotIndex", int.class,
                    String.class, bySlotHook);
        } catch (NoSuchMethodError ignored) {
        }
    }

    private static ValueSupplier constant(final String value) {
        return new ValueSupplier() {
            @Override
            public String get() {
                return value;
            }
        };
    }

    private static void hookBooleanResult(Class<?> clazz, String methodName,
                                          final boolean value,
                                          Class<?>... parameterTypes) {
        Object[] args = new Object[parameterTypes.length + 1];
        for (int i = 0; i < parameterTypes.length; i++) {
            args[i] = parameterTypes[i];
        }
        args[args.length - 1] = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                param.setResult(value);
            }
        };
        try {
            XposedHelpers.findAndHookMethod(clazz, methodName, args);
        } catch (NoSuchMethodError ignored) {
        } catch (Throwable t) {
            XposedBridge.log("DeviceSpoofLab-Telephony: failed to hook "
                    + methodName + ": " + t);
        }
    }

    private static String mccMnc(String key, boolean mcc) {
        String numeric = ConfigManager.getSystemProperty(key, null);
        if (numeric == null || numeric.length() < 5) return null;
        return mcc ? numeric.substring(0, 3) : numeric.substring(3);
    }

    private interface ValueSupplier {
        String get();
    }

    private static void hookIntResult(Class<?> clazz, String methodName,
                                      ValueSupplier supplier,
                                      Class<?>... parameterTypes) {
        Object[] args = new Object[parameterTypes.length + 1];
        for (int i = 0; i < parameterTypes.length; i++) {
            args[i] = parameterTypes[i];
        }
        args[args.length - 1] = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                String value = supplier.get();
                if (value == null) return;
                try {
                    param.setResult(Integer.parseInt(value));
                } catch (NumberFormatException ignored) {
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(clazz, methodName, args);
        } catch (NoSuchMethodError ignored) {
        } catch (Throwable t) {
            XposedBridge.log("DeviceSpoofLab-Telephony: failed to hook "
                    + methodName + ": " + t);
        }
    }

    private static void hookStringResult(Class<?> clazz, String methodName,
                                         ValueSupplier supplier,
                                         Class<?>... parameterTypes) {
        Object[] args = new Object[parameterTypes.length + 1];
        for (int i = 0; i < parameterTypes.length; i++) {
            args[i] = parameterTypes[i];
        }
        args[args.length - 1] = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (ConfigManager.isFakeSimEnabled()
                        && param.args.length > 0
                        && param.args[0] instanceof Integer) {
                    int arg = (Integer) param.args[0];
                    if (methodName.endsWith("ForPhone")
                            && arg != ConfigManager.getFakeSimSlotIndex()) {
                        param.setResult(null);
                        return;
                    }
                    if (("getSubscriberId".equals(methodName)
                            || "getIccSerialNumber".equals(methodName))
                            && arg != ConfigManager.getFakeSimSubscriptionId()) {
                        param.setResult(null);
                        return;
                    }
                }
                String value = supplier.get();
                if (value != null) {
                    param.setResult(value);
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(clazz, methodName, args);
        } catch (NoSuchMethodError ignored) {
        } catch (Throwable t) {
            XposedBridge.log("DeviceSpoofLab-Telephony: failed to hook "
                    + methodName + ": " + t);
        }
    }
}
