package com.feras.wazifati;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** التحديثات من صفحة Releases في مستودع GitHub، و«ما الجديد» من ملفات changelogs المضمّنة. */
public final class Updater {

    static final String REPO = "makanona-team/wazifati";
    static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";

    public static class Info {
        public String version = "", notes = "", apkUrl = "", page = "";
        public boolean pre;
    }

    public interface Progress { void on(int pct); }

    private Updater() { }

    static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(20_000);
        con.setReadTimeout(30_000);
        con.setInstanceFollowRedirects(true);
        con.setRequestProperty("User-Agent", "Wazifati-Android");
        return con;
    }

    static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    /** أحدث إصدار رقمي (يتجاهل أي إصدار اسمه ليس رقماً مثل «adhan»، والتجريبية والمسودات). */
    public static Info fetch(Context c) throws Exception {
        boolean pre = c != null && Store.admin(c);   // الأدمن يرى النسخ التجريبية، والمستخدم يرى المنشورة فقط
        String cur = c != null ? current(c) : "0";
        java.util.TreeMap<String, String> notesByVer = new java.util.TreeMap<>((a, b) -> cmp(b, a));
        HttpURLConnection con = open("https://api.github.com/repos/" + REPO + "/releases?per_page=30");
        con.setRequestProperty("Accept", "application/vnd.github+json");
        int code = con.getResponseCode();
        if (code != 200) throw new Exception("HTTP " + code);
        String body;
        try (InputStream in = con.getInputStream()) { body = readAll(in); }
        JSONArray arr = new JSONArray(body);
        Info best = null;
        for (int r = 0; r < arr.length(); r++) {
            JSONObject j = arr.getJSONObject(r);
            if (j.optBoolean("draft")) continue;
            String tag = j.optString("tag_name", "");
            if (!tag.matches("[vV]?\\d+(\\.\\d+)*")) continue;
            notesByVer.put(tag.replaceFirst("^[vV]", ""), j.optString("body", ""));
            if (j.optBoolean("prerelease") && !pre) continue;
            Info i = new Info();
            i.version = tag.replaceFirst("^[vV]", "");
            i.notes = j.optString("body", "");
            i.page = j.optString("html_url", "https://github.com/" + REPO + "/releases");
            JSONArray as = j.optJSONArray("assets");
            if (as != null) for (int k = 0; k < as.length(); k++) {
                JSONObject a = as.getJSONObject(k);
                if (a.optString("name").toLowerCase().endsWith(".apk")) {
                    i.apkUrl = a.optString("browser_download_url");
                    break;
                }
            }
            if (i.apkUrl.isEmpty()) continue;
            if (best == null || cmp(i.version, best.version) > 0) best = i;
        }
        if (best == null) throw new Exception("لا يوجد إصدار منشور بعد");
        best.pre = isPre(arr, best.version);
        // «ما الجديد» مجمّعاً: كل النسخ بين المثبّتة والمعروضة (حتى التجريبية التي لم تصل المستخدم)
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (java.util.Map.Entry<String, String> e : notesByVer.entrySet()) {
            if (cmp(e.getKey(), best.version) > 0 || cmp(e.getKey(), cur) <= 0) continue;
            String nb = e.getValue().split("\n---\n")[0].trim();
            if (nb.isEmpty()) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("— ").append(e.getKey()).append(" —\n").append(nb);
            if (++n >= 8) break;
        }
        if (n > 1) best.notes = sb.toString();
        return best;
    }

    static boolean isPre(JSONArray arr, String ver) {
        for (int r = 0; r < arr.length(); r++) {
            JSONObject j = arr.optJSONObject(r);
            if (j != null && j.optString("tag_name").replaceFirst("^[vV]", "").equals(ver)) return j.optBoolean("prerelease");
        }
        return false;
    }

    /** للأدمن: آخر نسخة منشورة للمستخدمين، وأحدث نسخة تجريبية. */
    public static String[] channels() throws Exception {
        HttpURLConnection con = open("https://api.github.com/repos/" + REPO + "/releases?per_page=30");
        con.setRequestProperty("Accept", "application/vnd.github+json");
        if (con.getResponseCode() != 200) throw new Exception("HTTP " + con.getResponseCode());
        String body;
        try (InputStream in = con.getInputStream()) { body = readAll(in); }
        JSONArray arr = new JSONArray(body);
        String stable = "", pre = "";
        for (int r = 0; r < arr.length(); r++) {
            JSONObject j = arr.getJSONObject(r);
            String v = j.optString("tag_name").replaceFirst("^[vV]", "");
            if (j.optBoolean("draft") || !v.matches("\\d+(\\.\\d+)*")) continue;
            if (j.optBoolean("prerelease")) { if (pre.isEmpty() || cmp(v, pre) > 0) pre = v; }
            else if (stable.isEmpty() || cmp(v, stable) > 0) stable = v;
        }
        if (!pre.isEmpty() && !stable.isEmpty() && cmp(pre, stable) <= 0) pre = "";
        return new String[]{stable, pre};
    }

    public static String current(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    public static long currentCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** مقارنة أرقام الإصدارات «1.10» > «1.9». */
    public static int cmp(String a, String b) {
        String[] x = a.split("[^0-9]+"), y = b.split("[^0-9]+");
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            long p = i < x.length && !x[i].isEmpty() ? Long.parseLong(x[i]) : 0;
            long q = i < y.length && !y[i].isEmpty() ? Long.parseLong(y[i]) : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    /** فحص تلقائي مرة كل 24 ساعة؛ إشعار عند وجود إصدار جديد. */
    /** يحفظ آخر إصدار منشور ليعرضه التطبيق كشريط. force=false: لا أكثر من مرة كل 3 ساعات. يُستدعى من خيط خلفي. */
    public static Info refreshLatest(Context c, boolean force) {
        SharedPreferences p = Store.p(c);
        long now = System.currentTimeMillis();
        if (!force && now - p.getLong("upd_chk", 0) < 3 * 3_600_000L) return stored(c);
        try {
            Info i = fetch(c);
            if (!i.version.isEmpty()) p.edit().putLong("upd_chk", now).putString("upd_ver", i.version).putString("upd_notes", i.notes).putString("upd_apk", i.apkUrl).apply();
            return stored(c);
        } catch (Throwable e) { return stored(c); }
    }

    /** آخر إصدار معروف إن كان أحدث من المثبّت، وإلا null. */
    public static Info newer(Context c) {
        Info i = stored(c);
        return !i.version.isEmpty() && cmp(i.version, current(c)) > 0 ? i : null;
    }

    static Info stored(Context c) {
        SharedPreferences p = Store.p(c);
        Info i = new Info();
        i.version = p.getString("upd_ver", "");
        i.notes = p.getString("upd_notes", "");
        i.apkUrl = p.getString("upd_apk", "");
        return i;
    }

    public static void autoCheck(Context c) {
        SharedPreferences p = Store.p(c);
        if (!p.getBoolean("auto_update", true)) return;
        long now = System.currentTimeMillis();
        if (now - p.getLong("upd_last", 0) < 24 * 3_600_000L) return;
        p.edit().putLong("upd_last", now).apply();
        Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                Info i = fetch(app);
                if (!i.version.isEmpty() && cmp(i.version, current(app)) > 0 && !i.version.equals(p.getString("upd_notified", ""))) {
                    p.edit().putString("upd_notified", i.version).apply();
                    notifyUpdate(app, i);
                }
            } catch (Throwable e) {
                p.edit().putLong("upd_last", now - 23 * 3_600_000L).apply(); // لا إنترنت: يعيد المحاولة بعد ساعة
            }
        }).start();
    }

    static void notifyUpdate(Context c, Info i) {
        if (!Sync.canPost(c)) return;
        Sync.channels(c);
        Intent o = new Intent(c, MainActivity.class).putExtra("update", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(c, 3, o, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, Sync.CH_UPD)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("تحديث جديد: وظيفتي " + i.version)
                .setContentText("اضغط للتحميل والتثبيت")
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(4, n);
    }

    public static File download(Context c, String url, Progress pr) throws Exception {
        File f = new File(c.getCacheDir(), "update.apk");
        HttpURLConnection con = open(url);
        int code = con.getResponseCode();
        if (code != 200) throw new Exception("HTTP " + code);
        long total = con.getContentLengthLong();
        try (InputStream in = con.getInputStream(); FileOutputStream out = new FileOutputStream(f)) {
            byte[] b = new byte[65536];
            long done = 0;
            int n, last = -1;
            while ((n = in.read(b)) > 0) {
                out.write(b, 0, n);
                done += n;
                if (total > 0) {
                    int pct = (int) (done * 100 / total);
                    if (pct != last) { last = pct; pr.on(pct); }
                }
            }
        }
        if (total > 0 && f.length() != total) throw new Exception("التحميل لم يكتمل");
        return f;
    }

    public static void install(Context c, File f) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sp.setAppPackageName(c.getPackageName());
        int sid = pi.createSession(sp);
        try (PackageInstaller.Session s = pi.openSession(sid)) {
            try (OutputStream out = s.openWrite("wazifati.apk", 0, f.length()); InputStream in = new FileInputStream(f)) {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                s.fsync(out);
            }
            Intent i = new Intent(c, InstallReceiver.class);
            int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent p = PendingIntent.getBroadcast(c, 77, i, fl);
            s.commit(p.getIntentSender());
        }
    }

    /** نص «ما الجديد» لكل الإصدارات، الأحدث أولاً. */
    public static String whatsNew(Context c) {
        try {
            String[] fs = c.getAssets().list("");
            ArrayList<String> vs = new ArrayList<>();
            if (fs != null) for (String f : fs) if (f.endsWith(".md") && Character.isDigit(f.charAt(0))) vs.add(f.substring(0, f.length() - 3));
            vs.sort((a, b) -> cmp(b, a));
            StringBuilder sb = new StringBuilder();
            for (String v : vs) {
                String t;
                try (InputStream in = c.getAssets().open(v + ".md")) { t = readAll(in); }
                sb.append("📌 الإصدار ").append(v).append("\n").append(clean(t)).append("\n\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    static String clean(String s) {
        return s.replace("**", "").replace("`", "")
                .replaceAll("(?m)^#+\\s*.*$\\n?", "")
                .replaceAll("(?m)^\\s*[-*] ", "• ")
                .trim();
    }
}
