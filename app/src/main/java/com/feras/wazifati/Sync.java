package com.feras.wazifati;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** المزامنة مع الخادم: المستخدم، الإعدادات، الإعلانات الجديدة، والإشعارات. */
public final class Sync {
    private Sync() { }

    public static final String CH_JOBS = "jobs";
    public static final String CH_UPD = "upd";
    static final int JOB_ID = 501;
    private static final Object LOCK = new Object();

    // ===== الجدولة كل 30 دقيقة =====
    public static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        for (JobInfo ji : js.getAllPendingJobs()) if (ji.getId() == JOB_ID) return;
        JobInfo info = new JobInfo.Builder(JOB_ID, new ComponentName(c, SyncJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(30 * 60_000L, 10 * 60_000L)
                .setPersisted(true)
                .build();
        js.schedule(info);
    }

    // ===== المستخدم =====
    public static void ensureUser(Context c) throws Exception {
        SharedPreferences sp = Store.p(c);
        String q = "/user?dev=" + Api.enc(Store.dev(c)) + "&ver=" + Api.enc(Updater.current(c)) + "&model=" + Api.enc(Build.MANUFACTURER + " " + Build.MODEL);
        JSONObject u = Api.get(q);
        sp.edit().putString("code", u.optString("code")).apply();
        JSONObject prefs = u.optJSONObject("prefs");
        long srvUpd = u.optLong("updated", 0);
        long locUpd = sp.getLong("prefs_updated", 0), pushed = sp.getLong("prefs_pushed", 0);
        if (prefs != null && srvUpd > pushed && locUpd <= pushed) {
            Store.applyPrefs(c, prefs, srvUpd);       // تثبيت جديد أو تعديل من هاتف آخر بنفس الرمز
        } else if (locUpd > pushed) {
            pushPrefsNow(c);
        }
    }

    public static void pushPrefsNow(Context c) throws Exception {
        JSONObject b = new JSONObject();
        b.put("dev", Store.dev(c));
        b.put("prefs", Store.prefsJson(c));
        JSONObject r = Api.post("/user", b);
        long t = r.optLong("updated", System.currentTimeMillis());
        Store.p(c).edit().putLong("prefs_pushed", t).putLong("prefs_updated", t).putString("code", r.optString("code", Store.p(c).getString("code", ""))).apply();
    }

    public static void pushPrefs(Context c) {
        Context app = c.getApplicationContext();
        new Thread(() -> { try { pushPrefsNow(app); } catch (Throwable ignored) { } }).start();
    }

    // ===== الإعلانات =====
    /** يعيد عدد الإعلانات الجديدة المطابقة للفلتر. */
    public static int syncJobs(Context c, boolean notify) throws Exception {
        synchronized (LOCK) {
            SharedPreferences sp = Store.p(c);
            Store st = Store.get(c);
            long last = sp.getLong("last_id", 0);
            boolean first = last == 0 || st.count() == 0;
            if (first) last = 0;
            Store.Filter f = Store.Filter.of(c);
            ArrayList<Store.Job> matched = new ArrayList<>();
            for (int round = 0; round < 10; round++) {
                JSONObject r = Api.get("/jobs?since=" + last + "&limit=800");
                long max = r.optLong("max", 0);
                if (last > 0 && max < last) {           // الخادم أُعيد ضبطه: نبدأ من جديد
                    st.getWritableDatabase().delete("jobs", null, null);
                    last = 0;
                    first = true;
                    continue;
                }
                JSONArray arr = r.optJSONArray("jobs");
                List<Store.Job> l = new ArrayList<>();
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Store.Job j = new Store.Job();
                    j.id = o.optLong("id"); j.title = o.optString("t"); j.org = o.optString("o"); j.specs = o.optString("s");
                    j.govs = o.optString("g"); j.sector = o.optString("c"); j.deadline = o.optString("d"); j.posted = o.optLong("p");
                    j.src = o.optString("src"); j.nSrc = o.optInt("n", 1); j.url = o.optString("u"); j.apply = o.optString("a");
                    j.text = o.optString("x"); j.shared = o.optInt("sh"); j.seen = first ? 1 : 0;
                    l.add(j);
                    if (!first && f.match(j) && !Store.expired(j)) matched.add(j);
                }
                st.insert(l);
                last = Math.max(last, r.optLong("last", last));
                sp.edit().putLong("last_id", last).putLong("last_sync", System.currentTimeMillis()).apply();
                if (!r.optBoolean("more")) break;
            }
            st.purge();
            if (notify && !matched.isEmpty() && Store.bool(c, "notify")) notifyJobs(c, matched);
            return matched.size();
        }
    }

    /** مزامنة كاملة (تُستدعى من الخلفية ومن زر التحديث). */
    public static int run(Context c, boolean notify) throws Exception {
        try { ensureUser(c); } catch (Throwable ignored) { }
        int n = syncJobs(c, notify);
        Updater.autoCheck(c);
        return n;
    }

    // ===== الإشعارات =====
    public static void channels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel a = new NotificationChannel(CH_JOBS, "فرص جديدة", NotificationManager.IMPORTANCE_DEFAULT);
        a.setDescription("إشعار بالفرص الجديدة المطابقة لفلترك");
        nm.createNotificationChannel(a);
        nm.createNotificationChannel(new NotificationChannel(CH_UPD, "تحديثات التطبيق", NotificationManager.IMPORTANCE_LOW));
    }

    public static boolean canPost(Context c) {
        return Build.VERSION.SDK_INT < 33 || c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
    }

    static void notifyJobs(Context c, List<Store.Job> jobs) {
        if (!canPost(c)) return;
        channels(c);
        int n = jobs.size();
        Intent o = new Intent(c, MainActivity.class).putExtra("tab", 0).putExtra("new", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(c, 1, o, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.InboxStyle style = new Notification.InboxStyle();
        StringBuilder first = new StringBuilder();
        for (int i = 0; i < Math.min(6, n); i++) {
            Store.Job j = jobs.get(i);
            String g = Labels.names(Labels.GOVS, j.govs);
            String line = j.title + (g.isEmpty() ? "" : " – " + g);
            style.addLine(line);
            if (i == 0) first.append(line);
        }
        if (n > 6) style.setSummaryText("و" + (n - 6) + " غيرها");
        String title = n == 1 ? "فرصة جديدة تناسبك" : n + " فرص جديدة تناسبك";
        style.setBigContentTitle(title);
        Notification note = new Notification.Builder(c, CH_JOBS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(first.toString())
                .setStyle(style)
                .setNumber(n)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(10, note);
    }
}
