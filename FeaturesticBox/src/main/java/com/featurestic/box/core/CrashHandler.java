package com.featurestic.box.core;

import android.content.Context;
import android.database.sqlite.SQLiteException;
import android.os.DeadObjectException;
import android.os.Looper;
import android.os.RemoteException;

import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.core.env.BEnvironment;
import com.featurestic.box.security.SdkLicenseGuard;
import com.featurestic.box.utils.FileUtils;
import com.featurestic.box.utils.Slog;
import com.featurestic.box.utils.compat.RealDeviceHelper;
import com.featurestic.sdk.FeaturesticBox;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Universal SDK Core Crash Sentinel & Real Device Telemetry Engine.
 *
 * Centralizes crash detection and panel telemetry strictly inside FeaturesticBox.aar.
 * Automatically installed across all host, server, and virtual guest processes.
 * Captures real physical hardware device info (Poco, Vivo, Samsung, Realme, etc.)
 * even when 120 FPS gaming spoofs (OnePlus 12 / ROG 8) are active in guest games.
 */
public class CrashHandler implements Thread.UncaughtExceptionHandler {
    private static final String TAG = "CrashHandler";
    private static final String TELEMETRY_ENDPOINT = "https://featurestic-box-panel.onrender.com/api/telemetry/crash";

    private static final AtomicBoolean sInstalled = new AtomicBoolean(false);
    private static volatile CrashHandler sInstance = null;
    private static volatile long sLastReportTime = 0;

    private final Thread.UncaughtExceptionHandler mDefaultHandler;

    public static void create() {
        install(null);
    }

    public static synchronized void install(Context context) {
        if (context != null) {
            try {
                RealDeviceHelper.recordHostDevice(context);
            } catch (Throwable ignored) {
            }
        }
        if (sInstalled.compareAndSet(false, true)) {
            sInstance = new CrashHandler();
        }
    }

    public CrashHandler() {
        mDefaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(this);
    }

    private static boolean isSwallowableBackgroundException(Thread t, Throwable e) {
        if (t == null || e == null) return false;
        if (e instanceof OutOfMemoryError || e instanceof StackOverflowError) {
            return false;
        }
        Looper mainLooper = Looper.getMainLooper();
        if (mainLooper != null && t == mainLooper.getThread()) {
            return false;
        }
        String tName = t.getName() != null ? t.getName().toLowerCase() : "";
        if (tName.contains("firebase") || tName.contains("scion") || tName.contains("measurement")
                || tName.contains("gms") || tName.contains("bugly") || tName.contains("appsflyer")
                || tName.contains("adjust") || tName.contains("adworker") || tName.contains("analytics")) {
            return true;
        }
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth < 10) {
            if (cur instanceof SecurityException
                    || cur instanceof RemoteException
                    || cur instanceof DeadObjectException
                    || cur instanceof IllegalStateException
                    || cur instanceof IllegalArgumentException
                    || cur instanceof SQLiteException
                    || cur instanceof NullPointerException) {
                return true;
            }
            cur = cur.getCause();
            depth++;
        }
        return false;
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        if (isSwallowableBackgroundException(t, e)) {
            Slog.w(TAG, "Shielded non-fatal background exception on thread: " + (t != null ? t.getName() : "unknown"));
            return;
        }

        // Silent, bulletproof real hardware telemetry dispatch
        reportCrashToTelemetry(e);

        if (FeaturesticBoxCore.get().getExceptionHandler() != null) {
            FeaturesticBoxCore.get().getExceptionHandler().uncaughtException(t, e);
        }
        if (mDefaultHandler != null) {
            mDefaultHandler.uncaughtException(t, e);
        }
    }

    public static void reportCrashToTelemetry(Throwable e) {
        if (e == null) return;
        long now = System.currentTimeMillis();
        if (now - sLastReportTime < 5000) return;
        sLastReportTime = now;

        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            final String stacktrace = sw.toString();
            final String crashType = e.getClass().getSimpleName();

            final Context ctx = FeaturesticBoxCore.getContext();

            // Local fallback persistence
            saveLocalCrashDump(ctx, stacktrace);

            Thread reporter = new Thread(new Runnable() {
                @Override
                public void run() {
                    HttpURLConnection conn = null;
                    try {
                        URL url = new URL(TELEMETRY_ENDPOINT);
                        conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("POST");
                        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                        conn.setConnectTimeout(2500);
                        conn.setReadTimeout(2500);
                        conn.setDoOutput(true);

                        JSONObject json = new JSONObject();

                        // Genuine physical device hardware (anti-spoof)
                        json.put("brand", RealDeviceHelper.getRealBrand());
                        json.put("model", RealDeviceHelper.getRealModel());
                        json.put("manufacturer", RealDeviceHelper.getRealManufacturer());
                        json.put("android_version", RealDeviceHelper.getRealAndroidVersion());
                        json.put("sdk_int", RealDeviceHelper.getRealSdkInt());

                        // Active SDK License key & Device HWID
                        String userKey = SdkLicenseGuard.getSdkKey(ctx);
                        if (userKey != null && !userKey.trim().isEmpty()) {
                            json.put("user_key", userKey.trim());
                        }

                        String hwid = SdkLicenseGuard.getDeviceHwid(ctx);
                        if (hwid != null && !hwid.trim().isEmpty()) {
                            json.put("device_id", hwid.trim());
                        }

                        json.put("app_version", FeaturesticBox.VERSION);
                        json.put("crash_type", crashType);
                        json.put("crash_category", "sdk_aar");
                        json.put("stacktrace", stacktrace);

                        byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
                        try (OutputStream os = conn.getOutputStream()) {
                            os.write(body);
                            os.flush();
                        }
                        conn.getResponseCode();
                    } catch (Throwable ignored) {
                    } finally {
                        if (conn != null) {
                            try {
                                conn.disconnect();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            });

            reporter.setName("FBoxCrashReporter");
            reporter.setDaemon(true);
            reporter.start();
            reporter.join(1500);
        } catch (Throwable ignored) {
        }
    }

    private static void saveLocalCrashDump(Context context, String stacktrace) {
        try {
            byte[] bytes = stacktrace.getBytes(StandardCharsets.UTF_8);
            if (context != null) {
                File localDump = new File(context.getFilesDir(), "last_crash.txt");
                try (FileOutputStream fos = new FileOutputStream(localDump)) {
                    fos.write(bytes);
                } catch (Throwable ignored) {
                }
            }
            File sysDir = BEnvironment.getSystemDir();
            if (sysDir != null) {
                FileUtils.mkdirs(sysDir);
                File sysDump = new File(sysDir, "last_crash.txt");
                try (FileOutputStream fos = new FileOutputStream(sysDump)) {
                    fos.write(bytes);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
