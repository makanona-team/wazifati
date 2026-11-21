package com.feras.wazifati;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** دليل المهارات: جلب من الخادم، تخزين محلي، ومطابقة مهارات الوظيفة مع السيرة الذاتية. */
public final class Skills {
    private Skills() { }

    public static JSONArray list(Context c) {
        try { return new JSONArray(Store.p(c).getString("skills_json", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    /** يجلب الدليل (مرة كل 6 ساعات ما لم يُطلب إجباراً). يُستدعى من خيط خلفي. */
    public static void refresh(Context c, boolean force) {
        try {
            SharedPreferences sp = Store.p(c);
            if (!Store.flag(c, "learn_tab")) return;
            long now = System.currentTimeMillis();
            if (!force && now - sp.getLong("skills_at", 0) < 6 * 3_600_000L) return;
            JSONObject r = Api.get("/skills");
            JSONArray a = r.optJSONArray("skills");
            if (a != null) sp.edit().putString("skills_json", a.toString()).putLong("skills_at", now).apply();
        } catch (Throwable ignored) { }
    }

    static boolean hit(String text, String alias) {
        String a = Search.light(alias).trim();
        if (a.isEmpty()) return false;
        if (a.matches("[a-z0-9 .+#&-]+")) return Pattern.compile("(^|[^a-z0-9])" + Pattern.quote(a) + "($|[^a-z0-9])").matcher(text).find();
        return text.contains(a);
    }

    private static boolean mentions(String text, JSONObject sk) {
        JSONArray al = sk.optJSONArray("al");
        if (al == null) return false;
        for (int i = 0; i < al.length(); i++) if (hit(text, al.optString(i))) return true;
        return false;
    }

    /** مهارات الدليل التي يطلبها الإعلان وليست في السيرة (أو كلها إن لم تُرفع سيرة). */
    public static List<JSONObject> missingFor(Context c, Store.Job j, boolean[] hasCv) {
        List<JSONObject> out = new ArrayList<>();
        JSONArray all = list(c);
        if (all.length() == 0) return out;
        String jt = Search.light(j.title + " " + j.org + " " + j.text);
        Match.Profile pf = Match.profile(c);
        hasCv[0] = pf != null;
        String cv = "";
        if (pf != null) cv = Search.light(String.join(" ", pf.skills) + " " + String.join(" ", pf.titles) + " " + String.join(" ", pf.words));
        for (int i = 0; i < all.length(); i++) {
            JSONObject sk = all.optJSONObject(i);
            if (sk == null || !mentions(jt, sk)) continue;
            if (pf != null && mentions(cv, sk)) continue;
            out.add(sk);
        }
        return out;
    }
}
