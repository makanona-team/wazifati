package com.feras.wazifati;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** التخزين المحلي: الإعلانات في SQLite، والإعدادات في SharedPreferences. */
public final class Store extends SQLiteOpenHelper {

    public static final class Job {
        public long id, posted;
        public String title = "", org = "", specs = "", govs = "", sector = "", deadline = "", src = "", url = "", apply = "", text = "", image = "";
        public int doubt;
        public String kind = "job", tcat = "", bond = "";
        public boolean tender() { return "tender".equals(kind); }
        public int nSrc, shared, seen;
        public String hay;   // نص البحث المطبَّع (يُحسب عند الحاجة)
        public int match = -1;   // نسبة التطابق مع السيرة (مخزّنة مؤقتاً)
    }

    private static Store inst;
    public static final long DAY = 86_400_000L;

    public static synchronized Store get(Context c) {
        if (inst == null) inst = new Store(c.getApplicationContext());
        return inst;
    }

    private final Context ctx;

    private Store(Context c) {
        super(c, "wazifati.db", null, 3);
        ctx = c;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE jobs (id INTEGER PRIMARY KEY, t TEXT, o TEXT, s TEXT, g TEXT, c TEXT, d TEXT, p INTEGER, src TEXT, n INTEGER, u TEXT, a TEXT, x TEXT, sh INTEGER, seen INTEGER DEFAULT 0, img TEXT, dt INTEGER DEFAULT 0, k TEXT DEFAULT 'job', tc TEXT, bd TEXT)");
        db.execSQL("CREATE INDEX jobs_p ON jobs(p)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int o, int n) {
        if (o < 2) {
            try { db.execSQL("ALTER TABLE jobs ADD COLUMN img TEXT"); } catch (Exception ignored) { }
            try { db.execSQL("ALTER TABLE jobs ADD COLUMN dt INTEGER DEFAULT 0"); } catch (Exception ignored) { }
        }
        if (o < 3) {
            try { db.execSQL("ALTER TABLE jobs ADD COLUMN k TEXT DEFAULT 'job'"); } catch (Exception ignored) { }
            try { db.execSQL("ALTER TABLE jobs ADD COLUMN tc TEXT"); } catch (Exception ignored) { }
            try { db.execSQL("ALTER TABLE jobs ADD COLUMN bd TEXT"); } catch (Exception ignored) { }
        }
    }

    // ===== الإعدادات =====
    public static SharedPreferences p(Context c) {
        return c.getSharedPreferences("w", Context.MODE_PRIVATE);
    }

    @SuppressLint("HardwareIds")
    public static String dev(Context c) {
        String id = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID);
        return id == null || id.isEmpty() ? "x" + p(c).getString("rnd", newRnd(c)) : id;
    }

    private static String newRnd(Context c) {
        String r = Long.toHexString(Double.doubleToLongBits(Math.random())) + Long.toHexString(System.nanoTime());
        p(c).edit().putString("rnd", r).apply();
        return r;
    }

    /** عمليات البحث المحفوظة (2.9): كل نص بحث يُشعَر صاحبه بكل فرصة جديدة تطابقه. */
    public static java.util.List<String> savedSearches(Context c) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(p(c).getString("saved_q", "[]"));
            for (int i = 0; i < a.length(); i++) if (!a.optString(i).trim().isEmpty()) out.add(a.optString(i));
        } catch (Exception ignored) { }
        return out;
    }

    public static void putSavedSearches(Context c, java.util.List<String> l) {
        p(c).edit().putString("saved_q", new org.json.JSONArray(l).toString()).apply();
    }

    public static Set<String> set(Context c, String key) {
        String v = p(c).getString(key, "");
        LinkedHashSet<String> s = new LinkedHashSet<>();
        if (v.isEmpty()) return s;
        for (String x : v.split(",")) if (!x.trim().isEmpty()) s.add(x.trim());
        return s;
    }

    public static void putSet(Context c, String key, Set<String> s) {
        p(c).edit().putString(key, String.join(",", s)).putLong("prefs_updated", System.currentTimeMillis()).apply();
    }

    public static boolean toggle(Context c, String key, String v) {
        Set<String> s = set(c, key);
        boolean on;
        if (s.contains(v)) { s.remove(v); on = false; } else { s.add(v); on = true; }
        putSet(c, key, s);
        return on;
    }

    public static void putBool(Context c, String key, boolean v) {
        p(c).edit().putBoolean(key, v).putLong("prefs_updated", System.currentTimeMillis()).apply();
    }

    /** الإعدادات التي تُحفظ على الخادم (تعود بعد حذف التطبيق وإعادة تثبيته). */
    static final String[] SYNC_SETS = {"specs", "govs", "sectors", "favs", "applied", "hidden", "t_cats", "t_govs"};
    static final String[] SYNC_BOOLS = {"unk", "notify", "hide_expired", "remind", "quiet", "show_match", "cv_notify", "hide_doubt", "t_notify"};
    static final String[] SYNC_INTS = {"digest_hour", "quiet_from", "quiet_to", "cv_threshold", "cv_remind"};

    public static JSONObject prefsJson(Context c) throws Exception {
        JSONObject j = new JSONObject();
        for (String k : SYNC_SETS) j.put(k, new JSONArray(new ArrayList<>(set(c, k))));
        SharedPreferences sp = p(c);
        for (String k : SYNC_BOOLS) j.put(k, sp.getBoolean(k, defBool(k)));
        for (String k : SYNC_INTS) j.put(k, sp.getInt(k, defInt(k)));
        j.put("notify_mode", sp.getString("notify_mode", "instant"));
        j.put("setup_done", sp.getBoolean("setup_done", false));
        try { j.put("apps", new JSONObject(sp.getString("apps", "{}"))); } catch (Exception ignored) { }
        return j;
    }

    public static void applyPrefs(Context c, JSONObject j, long updated) {
        SharedPreferences.Editor e = p(c).edit();
        for (String k : SYNC_SETS) {
            JSONArray a = j.optJSONArray(k);
            if (a == null) continue;
            ArrayList<String> l = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) l.add(a.optString(i));
            e.putString(k, String.join(",", l));
        }
        for (String k : SYNC_BOOLS) if (j.has(k)) e.putBoolean(k, j.optBoolean(k));
        for (String k : SYNC_INTS) if (j.has(k)) e.putInt(k, j.optInt(k, defInt(k)));
        if (j.has("notify_mode")) e.putString("notify_mode", j.optString("notify_mode", "instant"));
        if (j.optJSONObject("apps") != null) e.putString("apps", Apps.merge(p(c).getString("apps", "{}"), j.optJSONObject("apps")));
        e.putLong("prefs_updated", updated).putLong("prefs_pushed", updated).putBoolean("setup_done", true);
        e.apply();
    }

    public static boolean defBool(String k) {
        return k.equals("unk") || k.equals("notify") || k.equals("hide_expired") || k.equals("remind") || k.equals("show_match") || k.equals("cv_notify");
    }

    public static int defInt(String k) {
        switch (k) {
            case "digest_hour": return 20;
            case "quiet_from": return 23;
            case "quiet_to": return 7;
            case "cv_threshold": return 60;
            case "cv_remind": return 6;
            default: return 0;
        }
    }

    public static int intv(Context c, String k) {
        return p(c).getInt(k, defInt(k));
    }

    public static void putInt(Context c, String k, int v) {
        p(c).edit().putInt(k, v).putLong("prefs_updated", System.currentTimeMillis()).apply();
    }

    // ===== الحساب =====
    public static String token(Context c) { return p(c).getString("token", ""); }
    /** تصفّح كضيف: بلا حساب، قراءة فقط. */
    public static boolean guest(Context c) { return p(c).getBoolean("guest", false) && token(c).isEmpty(); }
    public static boolean admin(Context c) { return "admin".equals(p(c).getString("role", "")); }

    public static void applyMe(Context c, JSONObject me) {
        p(c).edit().putString("email", me.optString("email")).putString("uname", me.optString("name"))
                .putString("photo", me.optString("photo")).putString("role", me.optString("role", "user")).apply();
        if (me.has("cv")) applyCv(c, me.optJSONObject("cv"));
    }

    /** السيرة الذاتية المحللة (من الخادم). */
    public static void applyCv(Context c, JSONObject cv) {
        SharedPreferences.Editor e = p(c).edit();
        if (cv == null || cv.optJSONObject("profile") == null) e.remove("cv_profile").remove("cv_name").remove("cv_updated");
        else e.putString("cv_profile", cv.optJSONObject("profile").toString()).putString("cv_name", cv.optString("name")).putLong("cv_updated", cv.optLong("updated"));
        e.apply();
    }

    public static void logout(Context c) {
        SharedPreferences sp = p(c);
        String theme = sp.getString("theme", "auto");
        sp.edit().clear().putString("theme", theme).apply();
        get(c).getWritableDatabase().delete("jobs", null, null);
    }

    /** معرّف تثبيت عشوائي مجهول للإحصائيات (لا يُمسح عند تسجيل الخروج). */
    public static String installId(Context c) {
        SharedPreferences sp = c.getSharedPreferences("w_id", Context.MODE_PRIVATE);
        String id = sp.getString("install_id", "");
        if (id.isEmpty()) {
            id = java.util.UUID.randomUUID().toString();
            sp.edit().putString("install_id", id).apply();
        }
        return id;
    }

    /** حفظ مفاتيح التشغيل عن بُعد محلياً للعمل دون إنترنت. */
    public static void saveFlags(Context c, JSONObject f) {
        if (f == null) return;
        c.getSharedPreferences("w_id", Context.MODE_PRIVATE).edit().putString("feature_flags", f.toString()).apply();
    }

    /** قراءة حالة ميزة؛ تعمل افتراضياً عند غياب القيمة (عدا قسم التعلّم المؤجّل لـ 2.5). */
    public static boolean flag(Context c, String key) {
        boolean def = true;
        try {
            String raw = c.getSharedPreferences("w_id", Context.MODE_PRIVATE).getString("feature_flags", "");
            if (raw.isEmpty()) return def;
            JSONObject o = new JSONObject(raw);
            return o.has(key) ? o.optBoolean(key, def) : def;
        } catch (Exception e) { return def; }
    }

    public static boolean bool(Context c, String k) {
        return p(c).getBoolean(k, defBool(k));
    }

    // ===== الإعلانات =====
    public void insert(List<Job> jobs) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Job j : jobs) {
                ContentValues v = new ContentValues();
                v.put("id", j.id); v.put("t", j.title); v.put("o", j.org); v.put("s", j.specs); v.put("g", j.govs);
                v.put("c", j.sector); v.put("d", j.deadline); v.put("p", j.posted); v.put("src", j.src); v.put("n", j.nSrc);
                v.put("u", j.url); v.put("a", j.apply); v.put("x", j.text); v.put("sh", j.shared); v.put("seen", j.seen);
                v.put("img", j.image); v.put("dt", j.doubt); v.put("k", j.kind); v.put("tc", j.tcat); v.put("bd", j.bond);
                db.insertWithOnConflict("jobs", null, v, SQLiteDatabase.CONFLICT_IGNORE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void purge() {
        getWritableDatabase().delete("jobs", "p < ?", new String[]{String.valueOf(System.currentTimeMillis() - 60 * DAY)});
    }

    public void markSeen(long id) {
        ContentValues v = new ContentValues();
        v.put("seen", 1);
        getWritableDatabase().update("jobs", v, "id=?", new String[]{String.valueOf(id)});
    }

    public void markAllSeen() {
        ContentValues v = new ContentValues();
        v.put("seen", 1);
        getWritableDatabase().update("jobs", v, "seen=0", null);
    }

    public int count() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM jobs", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /** كل الإعلانات بدون النص الكامل (للقوائم)، الأحدث أولاً. */
    public List<Job> all() {
        ArrayList<Job> l = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,t,o,s,g,c,d,p,src,n,u,a,sh,seen,x,img,dt,k,tc,bd FROM jobs ORDER BY p DESC, id DESC", null)) {
            while (c.moveToNext()) {
                Job j = new Job();
                j.id = c.getLong(0); j.title = s(c, 1); j.org = s(c, 2); j.specs = s(c, 3); j.govs = s(c, 4); j.sector = s(c, 5);
                j.deadline = s(c, 6); j.posted = c.getLong(7); j.src = s(c, 8); j.nSrc = c.getInt(9); j.url = s(c, 10); j.apply = s(c, 11);
                j.shared = c.getInt(12); j.seen = c.getInt(13); j.text = s(c, 14); j.image = s(c, 15); j.doubt = c.getInt(16); j.kind = s(c, 17).isEmpty() ? "job" : s(c, 17); j.tcat = s(c, 18); j.bond = s(c, 19);
                l.add(j);
            }
        }
        return l;
    }

    public void delete(long id) {
        getWritableDatabase().delete("jobs", "id=?", new String[]{String.valueOf(id)});
    }

    public void updateKind(long id, String kind, String tcat, String bond) {
        ContentValues v = new ContentValues();
        v.put("k", kind); v.put("tc", tcat); v.put("bd", bond);
        getWritableDatabase().update("jobs", v, "id=?", new String[]{String.valueOf(id)});
    }

    public void updateDoubt(long id, int doubt) {
        ContentValues v = new ContentValues();
        v.put("dt", doubt);
        getWritableDatabase().update("jobs", v, "id=?", new String[]{String.valueOf(id)});
    }

    public void updateClass(long id, String specs, String govs, String sector) {
        ContentValues v = new ContentValues();
        v.put("s", specs); v.put("g", govs); v.put("c", sector);
        getWritableDatabase().update("jobs", v, "id=?", new String[]{String.valueOf(id)});
    }

    public void updateTitle(long id, String title, String org) {
        ContentValues v = new ContentValues();
        v.put("t", title); v.put("o", org == null ? "" : org);
        getWritableDatabase().update("jobs", v, "id=?", new String[]{String.valueOf(id)});
    }

    public List<Job> byIds(Set<String> ids) {
        ArrayList<Job> out = new ArrayList<>();
        if (ids.isEmpty()) return out;
        for (Job j : all()) if (ids.contains(String.valueOf(j.id))) out.add(j);
        return out;
    }

    public String fullText(long id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT x FROM jobs WHERE id=?", new String[]{String.valueOf(id)})) {
            return c.moveToFirst() ? s(c, 0) : "";
        }
    }

    private static String s(Cursor c, int i) {
        String v = c.getString(i);
        return v == null ? "" : v;
    }

    // ===== المطابقة مع فلتر المستخدم =====
    public static final class Filter {
        Set<String> specs, govs, sectors;
        boolean unk;

        public static Filter of(Context c) {
            Filter f = new Filter();
            f.specs = set(c, "specs");
            f.govs = set(c, "govs");
            f.sectors = set(c, "sectors");
            f.unk = bool(c, "unk");
            return f;
        }

        public boolean isEmpty() {
            return specs.isEmpty() && govs.isEmpty() && sectors.isEmpty();
        }

        public static Filter of(Set<String> specs, Set<String> govs, Set<String> sectors, boolean unk) {
            Filter f = new Filter();
            f.specs = specs; f.govs = govs; f.sectors = sectors; f.unk = unk;
            return f;
        }

        public boolean match(Job j) {
            return part(specs, j.specs, "other", null) && part(govs, j.govs, "unknown", "all") && part(sectors, j.sector, "unknown", null);
        }

        /** مطابقة الاختصاص ونوع الجهة فقط (عند اختيار محافظة سريعة). */
        public boolean matchNoGov(Job j) {
            return part(specs, j.specs, "other", null) && part(sectors, j.sector, "unknown", null);
        }

        private boolean part(Set<String> mine, String csv, String unknownKey, String wildcard) {
            if (mine.isEmpty()) return true;
            Set<String> js = new HashSet<>(Arrays.asList((csv == null ? "" : csv).split(",")));
            for (String x : js) if (mine.contains(x)) return true;
            if (wildcard != null && js.contains(wildcard)) return true;
            return unk && (js.contains(unknownKey) || js.isEmpty() || js.equals(Collections.singleton("")));
        }
    }

    /** فلتر المناقصات: التصنيف والمحافظة. */
    public static final class TFilter {
        Set<String> cats, govs;

        public static TFilter of(Context c) { return of(set(c, "t_cats"), set(c, "t_govs")); }

        public static TFilter of(Set<String> cats, Set<String> govs) {
            TFilter f = new TFilter();
            f.cats = cats; f.govs = govs;
            return f;
        }

        public boolean isEmpty() { return cats.isEmpty() && govs.isEmpty(); }

        public boolean match(Job j) {
            if (!cats.isEmpty() && !cats.contains(j.tcat.isEmpty() ? "other" : j.tcat)) return false;
            if (govs.isEmpty()) return true;
            for (String g : j.govs.split(",")) if (govs.contains(g) || g.equals("all") || g.equals("unknown")) return true;
            return false;
        }
    }

    public static boolean expired(Job j) {
        if (j.deadline == null || j.deadline.length() < 10) return false;
        return j.deadline.compareTo(today()) < 0;
    }

    public static String today() {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        return f.format(new java.util.Date());
    }

    /** الأيام المتبقية حتى الإغلاق، أو Integer.MIN_VALUE إن لم يُعرف. */
    public static int daysLeft(Job j) {
        if (j.deadline == null || j.deadline.length() < 10) return Integer.MIN_VALUE;
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            long d = f.parse(j.deadline).getTime();
            long t = f.parse(today()).getTime();
            return (int) Math.round((d - t) / (double) DAY);
        } catch (Exception e) {
            return Integer.MIN_VALUE;
        }
    }
}
