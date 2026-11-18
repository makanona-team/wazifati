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
import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** المزامنة مع الخادم: الحساب، الإعدادات، الفرص، التصحيحات، الإعلانات، والإشعارات. */
public final class Sync {
    private Sync() { }

    public static final String CH_JOBS = "jobs", CH_UPD = "upd", CH_REMIND = "remind", CH_ADMIN = "admin", CH_ANN = "ann";
    static final int JOB_ID = 501;
    private static final Object LOCK = new Object();

    public static void init(Context c) {
        Api.token = Store.token(c);
    }

    public static boolean loggedIn(Context c) {
        return !Store.token(c).isEmpty();
    }

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

    // ===== الحساب والإعدادات =====
    public static void ensureUser(Context c) throws Exception {
        init(c);
        SharedPreferences sp = Store.p(c);
        JSONObject u = Api.get("/me?ver=" + Api.enc(Updater.current(c)) + "&model=" + Api.enc(Build.MANUFACTURER + " " + Build.MODEL));
        Store.applyMe(c, u);
        JSONObject prefs = u.optJSONObject("prefs");
        long srvUpd = u.optLong("updated", 0);
        long locUpd = sp.getLong("prefs_updated", 0), pushed = sp.getLong("prefs_pushed", 0);
        if (prefs != null && srvUpd > pushed && locUpd <= pushed) {
            Store.applyPrefs(c, prefs, srvUpd);
        } else if (locUpd > pushed) {
            pushPrefsNow(c);
        }
    }

    public static void pushPrefsNow(Context c) throws Exception {
        init(c);
        JSONObject b = new JSONObject();
        b.put("prefs", Store.prefsJson(c));
        JSONObject r = Api.post("/me", b);
        long t = r.optLong("updated", System.currentTimeMillis());
        Store.p(c).edit().putLong("prefs_pushed", t).putLong("prefs_updated", t).apply();
    }

    public static void pushPrefs(Context c) {
        Context app = c.getApplicationContext();
        new Thread(() -> { try { pushPrefsNow(app); } catch (Throwable ignored) { } }).start();
    }

    // ===== الفرص =====
    /** يعيد الفرص الجديدة المطابقة للفلتر. */
    public static List<Store.Job> syncJobs(Context c) throws Exception {
        synchronized (LOCK) {
            init(c);
            SharedPreferences sp = Store.p(c);
            Store st = Store.get(c);
            long last = sp.getLong("last_id", 0);
            boolean first = last == 0 || st.count() == 0;
            if (first) last = 0;
            Store.Filter f = Store.Filter.of(c);
            Match.Profile prof = Store.bool(c, "cv_notify") && loggedIn(c) ? Match.profile(c) : null;
            int thr = Store.intv(c, "cv_threshold");
            ArrayList<Store.Job> matched = new ArrayList<>();
            ArrayList<Store.Job> tenders = new ArrayList<>();
            Store.TFilter tf = Store.bool(c, "t_notify") ? Store.TFilter.of(c) : null;
            for (int round = 0; round < 10; round++) {
                JSONObject r = Api.get("/jobs?since=" + last + "&limit=800");
                long max = r.optLong("max", 0);
                if (last > 0 && max < last) {
                    st.getWritableDatabase().delete("jobs", null, null);
                    last = 0;
                    first = true;
                    continue;
                }
                JSONArray arr = r.optJSONArray("jobs");
                List<Store.Job> l = new ArrayList<>();
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    Store.Job j = parseJob(arr.getJSONObject(i), first);
                    l.add(j);
                    if (j.tender()) { if (!first && tf != null && tf.match(j) && !Store.expired(j)) tenders.add(j); continue; }
                    if (!first && !Store.expired(j) && !(j.doubt == 1 && Store.bool(c, "hide_doubt")) && (f.match(j) || (prof != null && Match.score(c, j, prof).pct >= thr))) matched.add(j);
                }
                st.insert(l);
                last = Math.max(last, r.optLong("last", last));
                sp.edit().putLong("last_id", last).putLong("last_sync", System.currentTimeMillis()).apply();
                if (!r.optBoolean("more")) break;
            }
            st.purge();
            if (first) sp.edit().putLong("changes_since", System.currentTimeMillis()).apply();
            if (!tenders.isEmpty() && !quietNow(c)) notifyTenders(c, tenders);
            return matched;
        }
    }

    static Store.Job parseJob(JSONObject o, boolean seen) {
        Store.Job j = new Store.Job();
        j.id = o.optLong("id"); j.title = o.optString("t"); j.org = o.optString("o"); j.specs = o.optString("s");
        j.govs = o.optString("g"); j.sector = o.optString("c"); j.deadline = o.optString("d"); j.posted = o.optLong("p");
        j.src = o.optString("src"); j.nSrc = o.optInt("n", 1); j.url = o.optString("u"); j.apply = o.optString("a");
        j.text = o.optString("x"); j.shared = o.optInt("sh"); j.seen = seen ? 1 : 0;
        j.image = o.optString("im"); j.doubt = o.optInt("dt");
        j.kind = o.optString("k", "job"); j.tcat = o.optString("tc"); j.bond = o.optString("bd");
        return j;
    }

    /** يجلب فرصة واحدة (رابط مشاركة) ويحفظها محلياً. */
    static Store.Job fetchOne(Context c, long id) throws Exception {
        init(c);
        JSONArray a = Api.get("/jobs?id=" + id).optJSONArray("jobs");
        if (a == null || a.length() == 0) return null;
        Store.Job j = parseJob(a.getJSONObject(0), true);
        ArrayList<Store.Job> l = new ArrayList<>();
        l.add(j);
        Store.get(c).insert(l);
        return j;
    }

    /** تصحيحات التصنيف التي اعتمدها الأدمن. */
    static void syncChanges(Context c) throws Exception {
        SharedPreferences sp = Store.p(c);
        long since = sp.getLong("changes_since", 0);
        JSONObject r = Api.get("/changes?since=" + since);
        JSONArray a = r.optJSONArray("items");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            long id = o.optLong("id");
            if (o.optInt("h") == 1) { Store.get(c).delete(id); continue; }
            if (!o.isNull("s")) Store.get(c).updateClass(id, o.optString("s"), o.optString("g"), o.optString("c"));
            Store.get(c).updateDoubt(id, o.optInt("dt"));
            if (o.has("k")) Store.get(c).updateKind(id, o.optString("k", "job"), o.optString("tc"), o.optString("bd"));
        }
        sp.edit().putLong("changes_since", r.optLong("now", System.currentTimeMillis())).apply();
    }

    /** إعلانات الأدمن لكل المستخدمين. */
    static void syncAnnouncements(Context c, boolean notify) throws Exception {
        SharedPreferences sp = Store.p(c);
        long since = sp.getLong("ann_last", 0);
        JSONObject r = Api.get("/announce?since=" + since);
        JSONArray a = r.optJSONArray("items");
        if (a == null || a.length() == 0) return;
        JSONObject newest = a.getJSONObject(0);
        sp.edit().putLong("ann_last", newest.optLong("id")).putString("ann_text", newest.optString("text")).putBoolean("ann_dismissed", false).apply();
        if (notify && since > 0) post(c, CH_ANN, 30, "📢 إعلان من وظيفتي", newest.optString("text"), 0);
    }

    /** للأدمن: مصادر مقترحة وتصحيحات بانتظار الموافقة. */
    static void syncAdmin(Context c, boolean notify) throws Exception {
        if (!Store.admin(c)) return;
        SharedPreferences sp = Store.p(c);
        long since = sp.getLong("admin_check", 0);
        JSONObject r = Api.get("/admin/pending?since=" + since);
        JSONArray srcs = r.optJSONArray("sources");
        int nSrc = srcs == null ? 0 : srcs.length();
        int newSrc = 0;
        String who = "";
        if (srcs != null) for (int i = 0; i < srcs.length(); i++) {
            JSONObject s = srcs.getJSONObject(i);
            if (s.optLong("created") > since) { newSrc++; who = s.optString("added_by"); }
        }
        int fixes = r.optInt("fixes"), newFixes = r.optInt("newFixes"), flags = r.optInt("flags"), newFlags = r.optInt("newFlags");
        sp.edit().putInt("pending_sources", nSrc).putInt("pending_fixes", fixes).putInt("pending_flags", flags).putLong("admin_check", System.currentTimeMillis()).apply();
        if (!notify || since == 0) return;
        if (newSrc > 0) post(c, CH_ADMIN, 40, "📡 مصدر جديد مقترح", (newSrc == 1 ? "من " + who : newSrc + " مصادر جديدة") + " — بانتظار موافقتك", 4);
        if (newFixes > 0) post(c, CH_ADMIN, 41, "🏷 تصحيحات تصنيف", newFixes + " تصحيح مقترح بانتظار موافقتك", 4);
        JSONArray off = r.optJSONArray("autoOff");
        if (off != null && off.length() > 0) {
            StringBuilder nm = new StringBuilder();
            for (int i = 0; i < off.length(); i++) nm.append(i > 0 ? "، " : "").append(off.optString(i));
            post(c, CH_ADMIN, 43, "⛔ أُوقف مصدر تلقائياً", nm + " — فشل 3 أيام متتالية. راجع صفحة المصادر.", 4);
        }
        if (r.optJSONObject("systemic") != null) post(c, CH_ADMIN, 44, "⚠️ عطل عام في الجلب", "أغلب المصادر فشلت معاً (غالباً حجب أو عطل شبكة). لم يُوقف شيء تلقائياً.", 4);
        if (newFlags > 0) post(c, CH_ADMIN, 42, "🚩 بلاغات جديدة", newFlags + " بلاغ «ليس إعلان وظيفة» بانتظار مراجعتك", 4);
    }

    /** مزامنة كاملة (من الخلفية ومن زر المزامنة). يعيد عدد الفرص الجديدة المطابقة. */
    public static int run(Context c, boolean background) throws Exception {
        init(c);
        if (!loggedIn(c)) {
            // ضيف: قراءة فقط، بلا إشعارات ولا مزامنة إعدادات
            if (!Store.guest(c) || background) return 0;
            syncJobs(c);
            try { syncChanges(c); } catch (Throwable ignored) { }
            try { syncAnnouncements(c, false); } catch (Throwable ignored) { }
            JobsWidget.update(c);
            Updater.autoCheck(c);
            return 0;
        }
        try { ensureUser(c); } catch (Api.AuthException e) { throw e; } catch (Throwable ignored) { }
        List<Store.Job> matched = syncJobs(c);
        try { syncChanges(c); } catch (Throwable ignored) { }
        try { syncAnnouncements(c, background); } catch (Throwable ignored) { }
        try { syncAdmin(c, background); } catch (Throwable ignored) { }
        deliver(c, matched);
        remindDeadlines(c);
        remindCv(c);
        JobsWidget.update(c);
        Updater.autoCheck(c);
        return matched.size();
    }

    // ===== الإشعارات =====
    public static void channels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel a = new NotificationChannel(CH_JOBS, "فرص جديدة", NotificationManager.IMPORTANCE_DEFAULT);
        a.setDescription("إشعار بالفرص الجديدة المطابقة لفلترك");
        nm.createNotificationChannel(a);
        nm.createNotificationChannel(new NotificationChannel(CH_REMIND, "تذكير قبل الإغلاق", NotificationManager.IMPORTANCE_DEFAULT));
        nm.createNotificationChannel(new NotificationChannel(CH_ANN, "إعلانات التطبيق", NotificationManager.IMPORTANCE_DEFAULT));
        nm.createNotificationChannel(new NotificationChannel(CH_ADMIN, "تنبيهات الأدمن", NotificationManager.IMPORTANCE_DEFAULT));
        nm.createNotificationChannel(new NotificationChannel(CH_UPD, "تحديثات التطبيق", NotificationManager.IMPORTANCE_LOW));
    }

    public static boolean canPost(Context c) {
        return Build.VERSION.SDK_INT < 33 || c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
    }

    static boolean quietNow(Context c) {
        if (!Store.bool(c, "quiet")) return false;
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int from = Store.intv(c, "quiet_from"), to = Store.intv(c, "quiet_to");
        if (from == to) return false;
        return from < to ? (h >= from && h < to) : (h >= from || h < to);
    }

    /** فوري أو ملخص يومي، مع احترام ساعات الهدوء. */
    static void deliver(Context c, List<Store.Job> matched) {
        if (!Store.bool(c, "notify")) return;
        Set<String> queue = Store.set(c, "queue");
        for (Store.Job j : matched) queue.add(String.valueOf(j.id));
        SharedPreferences sp = Store.p(c);
        sp.edit().putString("queue", String.join(",", queue)).apply();
        if (queue.isEmpty() || quietNow(c)) return;
        boolean daily = "daily".equals(sp.getString("notify_mode", "instant"));
        if (daily) {
            Calendar now = Calendar.getInstance();
            String today = Store.today();
            if (now.get(Calendar.HOUR_OF_DAY) < Store.intv(c, "digest_hour") || today.equals(sp.getString("digest_day", ""))) return;
            sp.edit().putString("digest_day", today).apply();
        }
        List<Store.Job> jobs = Store.get(c).byIds(queue);
        Store.Filter f = Store.Filter.of(c);
        Match.Profile prof = Store.bool(c, "cv_notify") ? Match.profile(c) : null;
        int thr = Store.intv(c, "cv_threshold");
        ArrayList<Store.Job> ok = new ArrayList<>();
        for (Store.Job j : jobs) if (!Store.expired(j) && !(j.doubt == 1 && Store.bool(c, "hide_doubt")) && (f.match(j) || (prof != null && Match.score(c, j, prof).pct >= thr))) ok.add(j);
        sp.edit().putString("queue", "").apply();
        if (!ok.isEmpty()) notifyJobs(c, ok, daily);
    }

    static void notifyJobs(Context c, List<Store.Job> jobs, boolean digest) {
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
        String title = digest ? "📋 ملخص اليوم: " + n + (n == 1 ? " فرصة تناسبك" : " فرص تناسبك") : (n == 1 ? "فرصة جديدة تناسبك" : n + " فرص جديدة تناسبك");
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

    static void notifyTenders(Context c, List<Store.Job> l) {
        int n = l.size();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(5, n); i++) sb.append("• ").append(l.get(i).title).append("\n");
        post(c, CH_JOBS, 11, n == 1 ? "📑 مناقصة جديدة تناسبك" : "📑 " + n + " مناقصات جديدة تناسبك", sb.toString().trim(), 1);
    }

    /** تذكير قبل يوم من إغلاق التقديم على الفرص المحفوظة. */
    static void remindDeadlines(Context c) {
        if (!Store.bool(c, "remind") || quietNow(c)) return;
        Set<String> favs = Store.set(c, "favs"), applied = Store.set(c, "applied");
        Set<String> done = Store.set(c, "reminded");
        LinkedHashSet<String> newDone = new LinkedHashSet<>(done);
        int nid = 100;
        for (Store.Job j : Store.get(c).byIds(favs)) {
            String id = String.valueOf(j.id);
            if (done.contains(id) || applied.contains(id)) continue;
            int d = Store.daysLeft(j);
            if (d == 0 || d == 1) {
                post(c, CH_REMIND, nid++, d == 0 ? "⏰ اليوم آخر موعد للتقديم" : "⏰ غداً آخر موعد للتقديم", j.title + (j.org.isEmpty() ? "" : " – " + j.org), 0);
                newDone.add(id);
            }
        }
        if (newDone.size() != done.size()) Store.p(c).edit().putString("reminded", String.join(",", newDone)).apply();
    }

    /** تذكير بتحديث السيرة الذاتية كل 3 أو 6 أشهر. */
    static void remindCv(Context c) {
        SharedPreferences sp = Store.p(c);
        int months = Store.intv(c, "cv_remind");
        long upd = sp.getLong("cv_updated", 0);
        if (months <= 0 || upd == 0 || quietNow(c)) return;
        long now = System.currentTimeMillis();
        if (now - upd < months * 30L * Store.DAY) return;
        if (now - sp.getLong("cv_reminded", 0) < 30L * Store.DAY) return;
        sp.edit().putLong("cv_reminded", now).apply();
        post(c, CH_REMIND, 60, "📄 حان وقت تحديث سيرتك الذاتية؟", "مرّ أكثر من " + months + " أشهر على آخر تحديث. ارفع النسخة الجديدة لتحصل على اقتراحات أدق.", 4);
    }

    static void post(Context c, String ch, int id, String title, String text, int tab) {
        if (!canPost(c)) return;
        channels(c);
        Intent o = new Intent(c, MainActivity.class).putExtra("tab", tab).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(c, id, o, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, ch)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(id, n);
    }
}
