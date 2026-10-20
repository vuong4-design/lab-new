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
                            param.setResult(android.telephony.TelephonyManager.SIM_STATE_READY);
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
    }

    private interface ValueSupplier {
        String get();
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
