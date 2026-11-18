package com.feras.wazifati;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** نسبة تطابق الفرصة مع السيرة الذاتية (محلياً على الهاتف). */
public final class Match {
    private Match() { }

    public static final class Profile {
        public final Map<String, Integer> specs = new HashMap<>();
        public final List<String> specOrder = new ArrayList<>();
        public final LinkedHashSet<String> words = new LinkedHashSet<>();
        public final LinkedHashSet<String> titleWords = new LinkedHashSet<>();
        public final Set<String> govs = new HashSet<>();
        public final List<String> skills = new ArrayList<>(), titles = new ArrayList<>(), langs = new ArrayList<>();
        public int years;
        public JSONObject raw;
    }

    public static final class Result {
        public int pct;
        public final List<String> why = new ArrayList<>();
    }

    private static Profile cached;
    private static String cachedSrc;
    private static final Pattern REQ_YEARS = Pattern.compile("(\\d{1,2})\\s*\\+?\\s*(?:years?|yrs|سنوات|سنة|عاما|عام)");

    public static Profile profile(Context c) {
        String s = Store.p(c).getString("cv_profile", "");
        if (s.isEmpty()) return null;
        if (s.equals(cachedSrc)) return cached;
        try { cached = parse(new JSONObject(s)); cachedSrc = s; } catch (Exception e) { cached = null; }
        return cached;
    }

    public static boolean enabled(Context c) {
        return Store.bool(c, "show_match") && profile(c) != null;
    }

    public static Profile parse(JSONObject o) {
        Profile p = new Profile();
        p.raw = o;
        JSONArray a = o.optJSONArray("specs");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject s = a.optJSONObject(i);
            if (s == null) continue;
            p.specs.put(s.optString("id"), s.optInt("pct"));
            p.specOrder.add(s.optString("id"));
        }
        addAll(o.optJSONArray("skills"), p.skills);
        addAll(o.optJSONArray("titles"), p.titles);
        addAll(o.optJSONArray("langs"), p.langs);
        List<String> g = new ArrayList<>();
        addAll(o.optJSONArray("govs"), g);
        p.govs.addAll(g);
        List<String> w = new ArrayList<>();
        addAll(o.optJSONArray("words"), w);
        for (String x : w) { String n = Search.norm(x); if (n.length() >= 3) p.words.add(n); }
        for (String t : p.titles) for (String x : Search.norm(t).split(" "))
            if (x.length() >= 4 && !Arrays.asList("senior", "junior", "field", "project", "program", "programme", "assistant", "officer", "manager", "مسؤول", "مساعد").contains(x)) p.titleWords.add(x);
        p.years = o.optInt("years");
        return p;
    }

    private static void addAll(JSONArray a, List<String> out) {
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) { String s = a.optString(i); if (!s.isEmpty()) out.add(s); }
    }

    public static Result score(Context c, Store.Job j, Profile p) {
        Result r = new Result();
        if (p == null) return r;
        if (j.hay == null) j.hay = Search.hay(j);
        String hay = " " + j.hay + " ";
        double pts = 0;

        // 1) الاختصاص (حتى 50)
        int best = 0;
        String bestName = null;
        for (String s : j.specs.split(",")) {
            Integer v = p.specs.get(s);
            if (v != null && v > best) { best = v; bestName = Labels.SPECS.get(s); }
        }
        pts += best * 0.5;
        if (bestName != null && best >= 40) r.why.add(bestName);

        // 2) المهارات والكلمات (حتى 30)
        int hits = 0;
        HashSet<String> shown = new HashSet<>();
        for (String w : p.words) {
            boolean shortLatin = w.length() <= 3 && w.matches("[a-z&]+");
            if (shortLatin ? hay.contains(" " + w + " ") : hay.contains(w)) {
                hits++;
                if (shown.size() < 4 && shown.add(w)) r.why.add(w);
                if (hits >= 3) break;
            }
        }
        pts += Math.min(3, hits) * 10;

        // 3) المسمى (15)
        String tn = " " + Search.norm(j.title) + " ";
        for (String w : p.titleWords) if (tn.contains(w)) { pts += 15; r.why.add("المسمى: " + w); break; }

        // 4) المكان (5)
        Set<String> jg = new HashSet<>(Arrays.asList(j.govs.split(",")));
        Set<String> mine = new HashSet<>(p.govs);
        mine.addAll(Store.set(c, "govs"));
        boolean place = jg.contains("all") || jg.contains("remote");
        for (String g : jg) if (mine.contains(g)) { place = true; break; }
        if (place) pts += 5;
        else if (jg.contains("abroad")) pts -= 10;

        // 5) سنوات الخبرة المطلوبة
        Matcher m = REQ_YEARS.matcher(j.text == null ? "" : j.text.toLowerCase());
        int req = 0;
        while (m.find()) { int y = Integer.parseInt(m.group(1)); if (y <= 20) req = Math.max(req, y); }
        if (req > 0 && p.years > 0) {
            if (p.years >= req) { pts += 5; r.why.add("خبرتك " + p.years + " سنة ≥ المطلوب " + req); }
            else if (req - p.years >= 2) pts -= 10;
        }

        r.pct = (int) Math.max(0, Math.min(100, Math.round(pts)));
        return r;
    }

    public static int color(int pct) {
        return pct >= 70 ? Ui.GREEN : pct >= 45 ? Ui.ORANGE : Ui.MUTED;
    }
}
