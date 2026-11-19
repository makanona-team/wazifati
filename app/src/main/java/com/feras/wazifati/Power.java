package com.feras.wazifati;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

/** مساعد بقاء التطبيق حياً في الخلفية: استثناء البطارية + إعدادات التشغيل التلقائي لكل شركة. */
public final class Power {
    private Power() { }

    public static String brand() {
        String m = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase();
        if (m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")) return "xiaomi";
        if (m.contains("huawei") || m.contains("honor")) return "huawei";
        if (m.contains("oppo") || m.contains("realme") || m.contains("oneplus")) return "oppo";
        if (m.contains("vivo") || m.contains("iqoo")) return "vivo";
        if (m.contains("samsung")) return "samsung";
        return "other";
    }

    public static String brandName() {
        switch (brand()) {
            case "xiaomi": return "شاومي / ريدمي / بوكو";
            case "huawei": return "هواوي / هونر";
            case "oppo": return "أوبو / ريلمي / ون بلس";
            case "vivo": return "فيفو";
            case "samsung": return "سامسونغ";
            default: return "هاتفك";
        }
    }

    public static boolean notifOk(Context c) {
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        return nm == null || nm.areNotificationsEnabled();
    }

    public static boolean isIgnoring(Context c) {
        PowerManager pm = c.getSystemService(PowerManager.class);
        return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
    }

    public static void requestIgnore(Context c) {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + c.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return;
        } catch (Throwable ignored) { }
        try {
            Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable ignored) { openAppDetails(c); }
    }

    public static void openAppDetails(Context c) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable ignored) { }
    }

    private static String[][] candidates() {
        switch (brand()) {
            case "xiaomi": return new String[][]{
                    {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                    {"com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"}};
            case "huawei": return new String[][]{
                    {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                    {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"},
                    {"com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"}};
            case "oppo": return new String[][]{
                    {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                    {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
                    {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
                    {"com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgauge.PowerUsageModelActivity"}};
            case "vivo": return new String[][]{
                    {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
                    {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                    {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"}};
            case "samsung": return new String[][]{
                    {"com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"},
                    {"com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"}};
            default: return new String[0][];
        }
    }

    /** يفتح شاشة التشغيل التلقائي/النشاط في الخلفية الخاصة بالشركة، وإلا إعدادات التطبيق. */
    public static void openAutostart(Context c) {
        for (String[] k : candidates()) {
            try {
                Intent i = new Intent();
                i.setComponent(new ComponentName(k[0], k[1]));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
                return;
            } catch (Throwable ignored) { }
        }
        openAppDetails(c);
    }

    public static String autostartHelp() {
        switch (brand()) {
            case "xiaomi": return "افتح «الأمان» ← «التشغيل التلقائي» (Autostart) وفعّل وظيفتي. ثم من إعدادات التطبيق ← «توفير البطارية» اختر «بلا قيود».";
            case "huawei": return "افتح «إطلاق التطبيقات» وأوقف «الإدارة التلقائية» لوظيفتي، ثم فعّل: التشغيل التلقائي + التشغيل الثانوي + العمل في الخلفية.";
            case "oppo": return "افتح «إدارة التشغيل التلقائي» وفعّل وظيفتي. ثم من البطارية ← وظيفتي ← «السماح بالنشاط في الخلفية».";
            case "vivo": return "فعّل «التشغيل التلقائي» لوظيفتي، ثم من البطارية ← «استهلاك الطاقة في الخلفية» اسمح لوظيفتي.";
            case "samsung": return "من البطارية ← «حدود استخدام الخلفية»: أزل وظيفتي من «التطبيقات النائمة» وأضفها إلى «التطبيقات التي لا تنام أبداً».";
            default: return "من إعدادات التطبيق ← البطارية: اختر «بلا قيود» أو «غير محسّن»، وفعّل التشغيل التلقائي إن وُجد.";
        }
    }
}
