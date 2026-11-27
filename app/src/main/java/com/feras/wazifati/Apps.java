package com.feras.wazifati;

import android.content.Context;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * «طلباتي» (3.2): سجلّ ما قدّم عليه المستخدم وحالته.
 * يُحفظ محلياً في التفضيلات ويُزامَن مع الحساب ضمن prefs (مفتاح apps) فلا يضيع بتغيير الهاتف.
 * كل عنصر: t عنوان، o جهة، dl آخر موعد، s حالة، m طريقة التقديم، n ملاحظة، at وقت التقديم، u آخر تعديل، r1/r2 تذكيرات أُرسلت.
 */
public final class Apps {
    private Apps() { }

    public static final String[] STATUS = {"opened", "later", "applied", "contact", "interview", "accepted", "rejected", "noreply"};
    public static final String[] LABEL = {"👆 فتحت التقديم — لم تؤكد", "⏳ لم أقدّم بعد", "📤 قدّمت", "📞 تواصلوا معي", "🗓 مقابلة", "✅ قُبلت", "❌ رُفضت", "🔕 لا رد"};

    public static String label(String s) {
        for (int i = 0; i < STATUS.length; i++) if (STATUS[i].equals(s)) return LABEL[i];
        return s;
    }

    /** هل الحالة تعني أنه قدّم فعلاً؟ */
    public static boolean applied(String s) {
        return !("opened".equals(s) || "later".equals(s) || s == null || s.isEmpty());
    }

    public static JSONObject all(Context c) {
        try { return new JSONObject(Store.p(c).getString("apps", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    public static JSONObject get(Context c, long id) { return all(c).optJSONObject(String.valueOf(id)); }

    private static void save(Context c, JSONObject a) {
        Store.p(c).edit().putString("apps", a.toString()).apply();
        // توافق مع «قدّمت» القديمة (المحفوظة والتذكيرات تعتمد عليها)
        Set<String> ap = new java.util.LinkedHashSet<>();
        Iterator<String> it = a.keys();
        while (it.hasNext()) { String k = it.next(); JSONObject o = a.optJSONObject(k); if (o != null && applied(o.optString("s"))) ap.add(k); }
        Store.putSet(c, "applied", ap);
        Sync.pushPrefs(c);
    }

    /** يضيف أو يحدّث حالة فرصة. status=null ← حذف. */
    public static void set(Context c, Store.Job j, long id, String status, String method) {
        JSONObject a = all(c);
        String k = String.valueOf(id);
        if (status == null) { a.remove(k); save(c, a); return; }
        JSONObject o = a.optJSONObject(k);
        long now = System.currentTimeMillis();
        try {
            if (o == null) { o = new JSONObject(); o.put("c", now); }
            if (j != null) { o.put("t", j.title); o.put("o", j.org); o.put("dl", j.deadline); }
            if (method != null && !method.isEmpty()) o.put("m", method);
            String old = o.optString("s");
            // «فتحت التقديم» لا يُنزل حالة أعلى منه
            if ("opened".equals(status) && !old.isEmpty() && !"opened".equals(old)) { o.put("u", now); a.put(k, o); save(c, a); return; }
            o.put("s", status);
            if (applied(status) && o.optLong("at") == 0) o.put("at", now);
            o.put("u", now);
            a.put(k, o);
        } catch (Exception ignored) { }
        save(c, a);
    }

    public static void note(Context c, long id, String n) {
        JSONObject a = all(c);
        JSONObject o = a.optJSONObject(String.valueOf(id));
        if (o == null) return;
        try { o.put("n", n); o.put("u", System.currentTimeMillis()); } catch (Exception ignored) { }
        save(c, a);
    }

    /** دمج ما على الخادم مع المحلي: الأحدث (u) يفوز لكل فرصة. */
    public static String merge(String local, JSONObject remote) {
        try {
            JSONObject l = new JSONObject(local == null || local.isEmpty() ? "{}" : local);
            if (remote == null) return l.toString();
            Iterator<String> it = remote.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject r = remote.optJSONObject(k), x = l.optJSONObject(k);
                if (r != null && (x == null || r.optLong("u") > x.optLong("u"))) l.put(k, r);
            }
            return l.toString();
        } catch (Exception e) { return local; }
    }

    public static List<String[]> list(Context c, String filter) {
        JSONObject a = all(c);
        List<String[]> out = new ArrayList<>();
        Iterator<String> it = a.keys();
        while (it.hasNext()) {
            String k = it.next();
            JSONObject o = a.optJSONObject(k);
            if (o == null) continue;
            String s = o.optString("s");
            boolean ok;
            switch (filter) {
                case "wait": ok = "applied".equals(s) || "contact".equals(s) || "opened".equals(s) || "later".equals(s); break;
                case "interview": ok = "interview".equals(s) || "contact".equals(s); break;
                default: ok = true;
            }
            if (ok) out.add(new String[]{k, String.valueOf(o.optLong("u"))});
        }
        out.sort((x, y) -> Long.compare(Long.parseLong(y[1]), Long.parseLong(x[1])));
        return out;
    }

    /** تذكيرات: قبل انتهاء الموعد بيومين لـ«لم أقدّم بعد»، وبعد 14 يوماً من التقديم «هل وصلك رد؟». */
    static void remind(Context c) {
        JSONObject a = all(c);
        boolean dirty = false;
        long now = System.currentTimeMillis();
        int nid = 300;
        Iterator<String> it = a.keys();
        while (it.hasNext()) {
            String k = it.next();
            JSONObject o = a.optJSONObject(k);
            if (o == null) continue;
            String s = o.optString("s"), t = o.optString("t");
            try {
                if (("later".equals(s) || "opened".equals(s)) && !o.optBoolean("r1")) {
                    Store.Job j = new Store.Job();
                    j.deadline = o.optString("dl");
                    int d = Store.daysLeft(j);
                    if (d != Integer.MIN_VALUE && d >= 0 && d <= 2) {
                        o.put("r1", true); dirty = true;
                        Store.p(c).edit().putLong("open_apps", now).apply();
                        Sync.post(c, Sync.CH_REMIND, nid++, d == 0 ? "⏰ اليوم آخر موعد ولم تقدّم بعد" : "⏰ لم تقدّم بعد — ينتهي بعد " + (d == 1 ? "يوم" : "يومين"), t + (o.optString("o").isEmpty() ? "" : " – " + o.optString("o")), MainActivity.T_SAVED);
                    }
                }
                if ("applied".equals(s) && !o.optBoolean("r2") && o.optLong("at") > 0 && now - o.optLong("at") > 14 * Store.DAY) {
                    o.put("r2", true); dirty = true;
                    Store.p(c).edit().putLong("open_apps", now).apply();
                    Sync.post(c, Sync.CH_REMIND, nid++, "📬 هل وصلك رد" + (o.optString("o").isEmpty() ? "؟" : " من " + o.optString("o") + "؟"), "قدّمت على «" + t + "» قبل أسبوعين. حدّث الحالة في «طلباتي».", MainActivity.T_SAVED);
                }
            } catch (Exception ignored) { }
            if (nid > 320) break;
        }
        if (dirty) Store.p(c).edit().putString("apps", a.toString()).apply();
    }
}
