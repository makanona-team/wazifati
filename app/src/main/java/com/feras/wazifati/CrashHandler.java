package com.feras.wazifati;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;

/** التقاط الانهيارات محلياً وإرسالها في الفتح التالي، وتقارير فشل تسجيل الدخول (بلا بيانات شخصية). */
public final class CrashHandler {
    private static boolean installed = false;
    private CrashHandler() { }

    /** فحص بسيط لمؤشرات الروت (Root) على الجهاز. */
    public static boolean isRooted() {
        try {
            String tags = Build.TAGS;
            if (tags != null && tags.contains("test-keys")) return true;
            String[] paths = {
                    "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su", "/system/xbin/su",
                    "/data/local/xbin/su", "/data/local/bin/su", "/system/sd/xbin/su",
                    "/sbin/.magisk", "/data/adb/magisk"
            };
            for (String p : paths) if (new File(p).exists()) return true;
        } catch (Throwable ignored) { }
        return false;
    }

    public static String deviceModel() {
        String m = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String d = Build.MODEL == null ? "" : Build.MODEL.trim();
        if (d.toLowerCase().startsWith(m.toLowerCase())) return d;
        return (m + " " + d).trim();
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("w_diag", Context.MODE_PRIVATE);
    }

    public static synchronized void install(Context c) {
        if (installed) return;
        installed = true;
        final Context app = c.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                String tr = sw.toString();
                if (tr.length() > 2400) tr = tr.substring(0, 2400);
                JSONObject j = new JSONObject();
                j.put("kind", "crash");
                j.put("ver", Updater.current(app));
                j.put("android", Build.VERSION.RELEASE == null ? "" : Build.VERSION.RELEASE);
                j.put("model", deviceModel());
                j.put("rooted", isRooted());
                j.put("code", e.getClass().getSimpleName());
                j.put("msg", e.getMessage() == null ? "" : e.getMessage());
                j.put("trace", tr);
                sp(app).edit().putString("pending_crash", j.toString()).commit();
            } catch (Throwable ignored) { }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    /** يرسل الانهيار المحفوظ من الجلسة السابقة عند فتح التطبيق. */
    public static void sendPending(Context c) {
        final Context app = c.getApplicationContext();
        final String raw = sp(app).getString("pending_crash", "");
        if (raw.isEmpty()) return;
        new Thread(() -> {
            try {
                JSONObject r = Api.post("/client/report", new JSONObject(raw));
                sp(app).edit().remove("pending_crash").apply();
                saveTip(app, r);
            } catch (Throwable ignored) { }
        }).start();
    }

    /** يرسل تقريراً فورياً للخادم عند فشل تسجيل الدخول بحساب Google. */
    public static void reportAuthFail(Context c, String code, String msg) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                JSONObject j = new JSONObject();
                j.put("kind", "auth_fail");
                j.put("ver", Updater.current(app));
                j.put("android", Build.VERSION.RELEASE == null ? "" : Build.VERSION.RELEASE);
                j.put("model", deviceModel());
                j.put("rooted", isRooted());
                j.put("code", code == null ? "unknown" : code);
                j.put("msg", msg == null ? "" : msg);
                j.put("trace", "");
                saveTip(app, Api.post("/client/report", j));
            } catch (Throwable ignored) { }
        }).start();
    }

    /** نصيحة من «قاموس الحلول» للمستخدم (مثل: أغلق التطبيق وافتحه) تُعرض مرة واحدة عند فتح التطبيق. */
    static void saveTip(Context app, JSONObject r) {
        String tip = r == null ? "" : r.optString("tip", "");
        if (!tip.isEmpty()) Store.p(app).edit().putString("crash_tip", tip).apply();
    }
}
