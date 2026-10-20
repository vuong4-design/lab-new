package com.devicespooflab.hooks.hooks;

import android.graphics.Bitmap;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import com.devicespooflab.hooks.utils.ConfigManager;
import com.devicespooflab.hooks.xposed.XposedBridge;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;

final class FakeSimFactory {

    private static final String TAG = "DeviceSpoofLab-FakeSim";
    private static volatile Object cachedInfo;

    private FakeSimFactory() {
    }

    static Object getSubscriptionInfo(Class<?> subscriptionInfoClass) {
        if (subscriptionInfoClass == null) return null;
        Object cached = cachedInfo;
        if (cached != null && subscriptionInfoClass.isInstance(cached)) return cached;

        synchronized (FakeSimFactory.class) {
            if (cachedInfo != null
                    && subscriptionInfoClass.isInstance(cachedInfo)) {
                return cachedInfo;
            }
            cachedInfo = create(subscriptionInfoClass);
            return cachedInfo;
        }
    }

    private static Object create(Class<?> cls) {
        String numeric = ConfigManager.getSystemProperty(
                "gsm.sim.operator.numeric", "310260");
        String mcc = numeric != null && numeric.length() >= 5
                ? numeric.substring(0, 3) : "310";
        String mnc = numeric != null && numeric.length() >= 5
                ? numeric.substring(3) : "260";
        String country = ConfigManager.getSystemProperty(
                "gsm.sim.operator.iso-country", "us");
        String iccid = ConfigManager.getICCID();
        if (iccid == null) iccid = "";
        String phone = ConfigManager.getPhoneNumber();
        String display = ConfigManager.getFakeSimDisplayName();
        if (display == null) display = "T-Mobile";
        int subId = ConfigManager.getFakeSimSubscriptionId();
        int slot = ConfigManager.getFakeSimSlotIndex();

        if (Build.VERSION.SDK_INT >= 33) {
            Object info = createFromParcel(cls, subId, slot, iccid, phone,
                    display, mcc, mnc, country);
            if (info != null) return info;
        }
        return createFromConstructor(cls, subId, slot, iccid, phone, display,
                mcc, mnc, country);
    }

    private static Object createFromParcel(Class<?> cls, int subId, int slot,
            String iccid, String phone, String display, String mcc,
            String mnc, String country) {
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeInt(subId);
            parcel.writeString(iccid);
            parcel.writeInt(slot);
            TextUtils.writeToParcel(display, parcel, 0);
            TextUtils.writeToParcel(display, parcel, 0);
            parcel.writeInt(1);
            parcel.writeInt(0);
            parcel.writeString(phone);
            parcel.writeInt(0);
            parcel.writeString(mcc);
            parcel.writeString(mnc);
            parcel.writeString(country);
            parcel.writeBoolean(false);
            parcel.writeTypedArray(null, 0);
            parcel.writeString(iccid);
            parcel.writeInt(-1);
            parcel.writeInt(0);
            parcel.writeBoolean(false);
            parcel.writeInt(-1);
            parcel.writeBoolean(false);
            parcel.writeInt(-1);
            parcel.writeInt(-1);
            parcel.writeInt(0);
            parcel.writeStringArray(null);
            parcel.writeStringArray(null);
            parcel.writeString(null);
            parcel.writeTypedArray(null, 0);
            parcel.writeBoolean(true);
            parcel.writeInt(0);
            parcel.writeBoolean(false);
            parcel.writeInt(0);
            parcel.writeInt(0);
            parcel.writeBoolean(false);
            parcel.setDataPosition(0);

            Object creator = cls.getField("CREATOR").get(null);
            if (!(creator instanceof Parcelable.Creator)) {
                XposedBridge.log(TAG + ": SubscriptionInfo CREATOR missing");
                return null;
            }
            Object info = ((Parcelable.Creator<?>) creator).createFromParcel(parcel);
            XposedBridge.log(TAG + ": created synthetic SubscriptionInfo (parcel)");
            return info;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": parcel SubscriptionInfo failed: " + t);
            return null;
        } finally {
            parcel.recycle();
        }
    }

    private static Object createFromConstructor(Class<?> cls, int subId,
            int slot, String iccid, String phone, String display, String mcc,
            String mnc, String country) {
        Constructor<?> best = null;
        for (Constructor<?> constructor : cls.getDeclaredConstructors()) {
            if (isBaseConstructor(constructor.getParameterTypes())) {
                best = constructor;
                break;
            }
        }
        if (best == null) {
            XposedBridge.log(TAG + ": no 16-argument SubscriptionInfo constructor");
            return null;
        }

        try {
            Class<?>[] types = best.getParameterTypes();
            Object[] args = new Object[types.length];
            args[0] = subId;
            args[1] = iccid;
            args[2] = slot;
            args[3] = display;
            args[4] = display;
            args[5] = 1;
            args[6] = 0;
            args[7] = phone;
            args[8] = 0;
            args[9] = null;
            args[10] = mcc;
            args[11] = mnc;
            args[12] = country;
            args[13] = false;
            args[14] = Array.newInstance(types[14].getComponentType(), 0);
            args[15] = iccid;

            best.setAccessible(true);
            Object info = best.newInstance(args);
            XposedBridge.log(TAG + ": created synthetic SubscriptionInfo (ctor)");
            return info;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": constructor SubscriptionInfo failed: " + t);
            return null;
        }
    }

    private static boolean isBaseConstructor(Class<?>[] t) {
        return t.length == 16
                && t[0] == int.class
                && t[1] == String.class
                && t[2] == int.class
                && t[3] == CharSequence.class
                && t[4] == CharSequence.class
                && t[5] == int.class
                && t[6] == int.class
                && t[7] == String.class
                && t[8] == int.class
                && t[9] == Bitmap.class
                && t[10] == String.class
                && t[11] == String.class
                && t[12] == String.class
                && t[13] == boolean.class
                && t[14].isArray()
                && t[15] == String.class;
    }
}
