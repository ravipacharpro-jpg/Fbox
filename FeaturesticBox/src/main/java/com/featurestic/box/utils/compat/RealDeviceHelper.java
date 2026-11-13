package com.featurestic.box.utils.compat;

import android.content.Context;
import android.os.Build;

import com.featurestic.box.core.env.BEnvironment;
import com.featurestic.box.utils.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Properties;

/**
 * Captures, persists and guarantees REAL physical hardware device identity
 * (Brand, Model, Manufacturer, Android Version, SDK Int) so that 120 FPS
 * gaming device spoofs (e.g. OnePlus 12 CPH2581, ROG Phone 8) applied inside
 * virtual container processes NEVER contaminate hardware logs or crash telemetry.
 */
public final class RealDeviceHelper {

    public static final String REAL_MANUFACTURER;
    public static final String REAL_BRAND;
    public static final String REAL_MODEL;
    public static final String REAL_DEVICE;
    public static final String REAL_PRODUCT;
    public static final String REAL_BOARD;
    public static final String REAL_HARDWARE;
    public static final String REAL_ANDROID_VERSION;
    public static final int REAL_SDK_INT;

    private static volatile String sCachedRealModel = null;
    private static volatile String sCachedRealBrand = null;
    private static volatile String sCachedRealManufacturer = null;

    static {
        String m = Build.MANUFACTURER;
        String b = Build.BRAND;
        String mo = Build.MODEL;
        String d = Build.DEVICE;
        String p = Build.PRODUCT;
        String bo = Build.BOARD;
        String h = Build.HARDWARE;
        String v = Build.VERSION.RELEASE;
        int sdk = Build.VERSION.SDK_INT;

        // Step 1: Check persistent real-device.conf recorded on host bootstrap
        Properties saved = loadPersistedRealDevice();
        if (saved != null && !saved.isEmpty()) {
            String savedMo = saved.getProperty("model", "");
            if (!savedMo.isEmpty() && !isKnownSpoofModel(savedMo)) {
                mo = savedMo;
                m = saved.getProperty("manufacturer", m);
                b = saved.getProperty("brand", b);
                d = saved.getProperty("device", d);
                p = saved.getProperty("product", p);
                bo = saved.getProperty("board", bo);
                h = saved.getProperty("hardware", h);
                v = saved.getProperty("android_version", v);
                try {
                    String savedSdk = saved.getProperty("sdk_int", "");
                    if (!savedSdk.isEmpty()) {
                        sdk = Integer.parseInt(savedSdk.trim());
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        // Step 2: If current model is a known spoof or suspect, recover from unhooked system properties
        if (isKnownSpoofModel(mo) || isKnownSpoofBrand(b)) {
            String sysModel = getFirstNonSpoofedProp(
                    "ro.product.odm.model",
                    "ro.product.vendor.model",
                    "ro.vendor.product.model",
                    "ro.product.system.model",
                    "ro.product.bootimage.model",
                    "ro.boot.product.model",
                    "ro.boot.hardware"
            );
            if (sysModel != null && !sysModel.isEmpty()) {
                mo = sysModel;
            }

            String sysBrand = getFirstNonSpoofedBrand(
                    "ro.product.odm.brand",
                    "ro.product.vendor.brand",
                    "ro.vendor.product.brand",
                    "ro.product.system.brand"
            );
            if (sysBrand != null && !sysBrand.isEmpty()) {
                b = sysBrand;
            }

            String sysM = getFirstNonSpoofedBrand(
                    "ro.product.odm.manufacturer",
                    "ro.product.vendor.manufacturer",
                    "ro.vendor.product.manufacturer",
                    "ro.product.system.manufacturer"
            );
            if (sysM != null && !sysM.isEmpty()) {
                m = sysM;
            }
        }

        REAL_MANUFACTURER = (m != null && !m.trim().isEmpty()) ? m.trim() : "Android";
        REAL_BRAND = (b != null && !b.trim().isEmpty()) ? b.trim() : "Generic";
        REAL_MODEL = (mo != null && !mo.trim().isEmpty()) ? mo.trim() : "RealDevice";
        REAL_DEVICE = (d != null && !d.trim().isEmpty()) ? d.trim() : "device";
        REAL_PRODUCT = (p != null && !p.trim().isEmpty()) ? p.trim() : "product";
        REAL_BOARD = (bo != null && !bo.trim().isEmpty()) ? bo.trim() : "board";
        REAL_HARDWARE = (h != null && !h.trim().isEmpty()) ? h.trim() : "hardware";
        REAL_ANDROID_VERSION = (v != null && !v.trim().isEmpty()) ? v.trim() : "14";
        REAL_SDK_INT = sdk > 0 ? sdk : 34;

        sCachedRealModel = REAL_MODEL;
        sCachedRealBrand = REAL_BRAND;
        sCachedRealManufacturer = REAL_MANUFACTURER;
    }

    private RealDeviceHelper() {
    }

    /**
     * Checks whether a model string belongs to a virtual 120 FPS gaming preset.
     */
    public static boolean isKnownSpoofModel(String model) {
        if (model == null || model.trim().isEmpty()) return false;
        String upper = model.trim().toUpperCase(Locale.US);
        return upper.contains("CPH2581")
                || upper.contains("ASUS_AI2401")
                || upper.contains("AI2401")
                || upper.contains("WW_AI2401")
                || upper.contains("SM-S928")
                || upper.contains("NX769J")
                || upper.contains("NX729J")
                || upper.contains("V2307A")
                || upper.contains("PD2307")
                || upper.contains("23116PN5BC")
                || upper.contains("SM-X910")
                || upper.contains("OP5929")
                || upper.contains("OP595D")
                || upper.contains("PJX110")
                || upper.equals("PINEAPPLE");
    }

    public static boolean isKnownSpoofBrand(String brand) {
        if (brand == null || brand.trim().isEmpty()) return false;
        String upper = brand.trim().toUpperCase(Locale.US);
        // Only consider suspect if brand was Asus/ROG or OnePlus when model is spoofed
        return upper.equals("ASUS") || upper.equals("ROG");
    }

    /**
     * Called in host process (Loader) at bootstrap before any virtual spoofing.
     * Persists host's real hardware specifications so all virtual and guest
     * processes can reliably read the genuine device identity.
     */
    public static void recordHostDevice(Context context) {
        try {
            if (isKnownSpoofModel(Build.MODEL)) {
                return;
            }
            File conf = BEnvironment.getRealDeviceConf();
            if (conf == null) return;
            if (conf.exists() && conf.length() > 0) return;

            FileUtils.mkdirs(conf.getParentFile());
            Properties props = new Properties();
            props.setProperty("manufacturer", Build.MANUFACTURER != null ? Build.MANUFACTURER : "");
            props.setProperty("brand", Build.BRAND != null ? Build.BRAND : "");
            props.setProperty("model", Build.MODEL != null ? Build.MODEL : "");
            props.setProperty("device", Build.DEVICE != null ? Build.DEVICE : "");
            props.setProperty("product", Build.PRODUCT != null ? Build.PRODUCT : "");
            props.setProperty("board", Build.BOARD != null ? Build.BOARD : "");
            props.setProperty("hardware", Build.HARDWARE != null ? Build.HARDWARE : "");
            props.setProperty("android_version", Build.VERSION.RELEASE != null ? Build.VERSION.RELEASE : "");
            props.setProperty("sdk_int", String.valueOf(Build.VERSION.SDK_INT));

            try (FileOutputStream fos = new FileOutputStream(conf)) {
                props.store(fos, "FeaturesticBox Genuine Host Device Identity");
            }
        } catch (Throwable ignored) {
        }
    }

    private static Properties loadPersistedRealDevice() {
        try {
            File conf = BEnvironment.getRealDeviceConf();
            if (conf != null && conf.isFile() && conf.length() > 0) {
                Properties props = new Properties();
                try (FileInputStream fis = new FileInputStream(conf)) {
                    props.load(fis);
                }
                return props;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static String getSystemProperty(String key) {
        if (key == null || key.isEmpty()) return "";
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class, String.class);
            Object res = get.invoke(null, key, "");
            return res != null ? ((String) res).trim() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String getFirstNonSpoofedProp(String... keys) {
        if (keys == null) return null;
        for (String k : keys) {
            String val = getSystemProperty(k);
            if (val != null && !val.trim().isEmpty() && !isKnownSpoofModel(val)) {
                return val.trim();
            }
        }
        return null;
    }

    private static String getFirstNonSpoofedBrand(String... keys) {
        if (keys == null) return null;
        for (String k : keys) {
            String val = getSystemProperty(k);
            if (val != null && !val.trim().isEmpty() && !isKnownSpoofBrand(val)) {
                return val.trim();
            }
        }
        return null;
    }

    public static String getRealModel() {
        if (sCachedRealModel != null && !sCachedRealModel.isEmpty() && !isKnownSpoofModel(sCachedRealModel)) {
            return sCachedRealModel;
        }
        return REAL_MODEL;
    }

    public static String getRealBrand() {
        if (sCachedRealBrand != null && !sCachedRealBrand.isEmpty() && !isKnownSpoofBrand(sCachedRealBrand)) {
            return sCachedRealBrand;
        }
        return REAL_BRAND;
    }

    public static String getRealManufacturer() {
        if (sCachedRealManufacturer != null && !sCachedRealManufacturer.isEmpty()) {
            return sCachedRealManufacturer;
        }
        return REAL_MANUFACTURER;
    }

    public static String getRealAndroidVersion() {
        return REAL_ANDROID_VERSION;
    }

    public static int getRealSdkInt() {
        return REAL_SDK_INT;
    }
}
