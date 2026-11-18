package com.feras.wazifati;

import android.graphics.Color;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** البحث في كامل الإعلان: تطبيع عربي، تجاهل «ال»، مرادفات عربي/إنكليزي، وتمييز الكلمات المطابقة. */
public final class Search {
    private Search() { }

    // مجموعات كلمات متكافئة (أي كلمة منها تجد الباقي)
    private static final String[][] GROUPS = {
            // اختصاصات ومسميات
            {"مهندس", "هندسة", "engineer", "engineering", "eng."},
            {"ميكانيك", "ميكانيكي", "mechanical", "mechanic"},
            {"كهرباء", "كهربائي", "electrical", "electrician", "electric"},
            {"مدني", "civil"},
            {"معماري", "عمارة", "architect", "architecture"},
            {"محاسب", "محاسبة", "accountant", "accounting", "finance", "مالية", "مالي"},
            {"مدقق", "تدقيق", "audit", "auditor"},
            {"مشتريات", "procurement", "purchasing", "purchaser"},
            {"لوجستيك", "لوجستي", "logistics", "logistic", "supply chain", "سلسلة التوريد"},
            {"مستودع", "مستودعات", "warehouse", "storekeeper", "store keeper", "امين مستودع"},
            {"متابعة وتقييم", "meal", "m&e", "monitoring", "evaluation", "رصد", "مساءلة", "accountability"},
            {"بيانات", "data", "database", "قاعدة بيانات"},
            {"حماية", "protection", "safeguarding"},
            {"ادارة حالة", "case management", "case manager", "case worker"},
            {"دعم نفسي", "نفسي اجتماعي", "psychosocial", "pss", "mhpss", "mental health"},
            {"طبيب", "دكتور", "doctor", "physician", "medical doctor"},
            {"ممرض", "ممرضة", "تمريض", "nurse", "nursing"},
            {"قابلة", "midwife", "midwifery"},
            {"صيدلي", "صيدلاني", "صيدلية", "pharmacist", "pharmacy"},
            {"مخبري", "مخبر", "lab", "laboratory"},
            {"صحة", "صحي", "health", "medical"},
            {"تغذية", "nutrition", "nutritionist"},
            {"مياه", "اصحاح", "wash", "water", "sanitation", "hygiene"},
            {"سبل العيش", "livelihood", "livelihoods", "fsl", "امن غذائي", "food security"},
            {"زراعة", "زراعي", "agriculture", "agronomist", "agricultural"},
            {"بيطري", "veterinary", "vet"},
            {"معلم", "مدرس", "استاذ", "teacher", "tutor", "instructor"},
            {"تعليم", "تربية", "education", "educational"},
            {"ميسر", "facilitator"},
            {"مبرمج", "مطور", "برمجة", "developer", "programmer", "software", "coding"},
            {"شبكات", "network", "networking"},
            {"تقنية معلومات", "it", "ict", "information technology", "دعم فني", "it support"},
            {"مصمم", "تصميم", "designer", "design", "graphic"},
            {"منسق", "coordinator", "coordination"},
            {"مدير", "manager", "director", "head of"},
            {"مدير مشروع", "project manager"},
            {"مسؤول", "officer"},
            {"مساعد", "assistant"},
            {"مشرف", "supervisor"},
            {"اخصائي", "specialist", "expert", "خبير"},
            {"مستشار", "استشاري", "consultant", "advisor", "adviser"},
            {"موارد بشرية", "hr", "human resources", "human resource"},
            {"اداري", "ادارة", "admin", "administration", "administrative"},
            {"سكرتير", "secretary", "receptionist", "استقبال"},
            {"مترجم", "ترجمة", "translator", "interpreter", "translation"},
            {"اعلام", "media", "communications", "تواصل"},
            {"تسويق", "marketing", "مبيعات", "sales"},
            {"خدمة عملاء", "customer service", "call center", "مركز اتصال"},
            {"سائق", "driver", "drivers"},
            {"حارس", "حراسة", "guard", "security guard"},
            {"امن وسلامة", "safety", "security", "hse", "سلامة"},
            {"نفط", "بترول", "oil", "petroleum"},
            {"غاز", "gas"},
            {"طاقة", "energy", "power", "solar", "طاقة شمسية"},
            {"صيانة", "maintenance", "فني", "technician"},
            {"مأوى", "shelter", "nfi"},
            {"بناء", "انشاءات", "construction", "اعادة تأهيل", "rehabilitation"},
            {"محامي", "قانوني", "lawyer", "legal"},
            {"تدريب", "training", "trainer", "مدرب"},
            {"منحة", "scholarship", "grant", "fellowship"},
            {"تطوع", "متطوع", "volunteer", "volunteering"},
            {"تدريب داخلي", "internship", "intern"},
            {"عن بعد", "remote", "من المنزل", "online", "اونلاين"},
            {"دوام كامل", "full time", "full-time"},
            {"دوام جزئي", "part time", "part-time"},
            {"منظمة", "ngo", "ingo", "منظمات"},
            {"الامم المتحدة", "un", "united nations", "unicef", "unhcr", "wfp", "undp", "who", "iom", "unops", "fao", "unfpa"},
            {"حكومي", "حكومة", "وزارة", "government", "ministry"},
            {"مناقصة", "tender", "rfq", "rfp", "عرض سعر", "عروض اسعار"},
            // محافظات ومدن
            {"دير الزور", "ديرالزور", "deir ez-zor", "deir ezzor", "deir-ez-zor", "deir al-zour", "deir ez zor", "dez", "الميادين", "البوكمال", "هجين", "الشحيل", "البصيرة", "العشارة", "ذيبان", "mayadin", "albukamal", "abu kamal", "hajin"},
            {"الرقة", "raqqa", "raqqah", "الطبقة", "tabqa", "عين عيسى", "ain issa", "تل ابيض", "tal abyad"},
            {"الحسكة", "hasakah", "hassakeh", "hasaka", "القامشلي", "qamishli", "qamishly", "عامودا", "amuda", "المالكية", "ديريك", "derik", "الشدادي", "shaddadi", "الهول", "al-hol", "راس العين"},
            {"حلب", "aleppo", "منبج", "manbij", "عفرين", "afrin", "اعزاز", "azaz", "الباب", "al-bab", "جرابلس", "jarablus", "كوباني", "عين العرب", "kobani"},
            {"ادلب", "idlib", "idleb", "سرمدا", "sarmada", "الدانا", "dana", "حارم", "harem", "سلقين", "اطمة", "atmeh"},
            {"دمشق", "damascus", "الشام"},
            {"ريف دمشق", "rural damascus", "rif dimashq", "دوما", "douma", "جرمانا", "jaramana", "داريا", "صحنايا", "قدسيا"},
            {"حمص", "homs", "تدمر", "palmyra", "القصير", "الرستن"},
            {"حماة", "hama", "سلمية", "salamiyah", "مصياف", "masyaf", "محردة", "السقيلبية"},
            {"اللاذقية", "latakia", "lattakia", "جبلة", "jableh"},
            {"طرطوس", "tartus", "tartous", "بانياس", "banias", "صافيتا"},
            {"درعا", "daraa", "deraa", "نوى", "بصرى", "ازرع"},
            {"السويداء", "sweida", "suwayda", "شهبا"},
            {"القنيطرة", "quneitra"},
            {"شمال شرق سوريا", "north east syria", "northeast syria", "nes", "الجزيرة"},
            {"شمال غرب سوريا", "north west syria", "northwest syria", "nws"},
            {"تركيا", "turkey", "türkiye", "غازي عنتاب", "gaziantep"},
    };

    private static List<Set<String>> groups;

    /** تطبيع للمقارنة (قد يغيّر الطول). */
    public static String norm(String s) {
        if (s == null) return "";
        return light(s).replaceAll("[ً-ْـ]", "")
                .replaceAll("[^\\p{L}\\p{Nd}&]+", " ")
                .replaceAll("(^|\\s)(وال|بال|كال|فال|لل|ال)(?=\\S{2,})", "$1")
                .replaceAll("\\s+", " ").trim();
    }

    /** تطبيع لا يغيّر طول النص (لتمييز الكلمات في مكانها). */
    public static String light(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case 'أ': case 'إ': case 'آ': case 'ٱ': c = 'ا'; break;
                case 'ة': c = 'ه'; break;
                case 'ى': c = 'ي'; break;
                case 'ؤ': c = 'و'; break;
                case 'ئ': c = 'ي'; break;
                default: c = Character.toLowerCase(c);
            }
            b.append(c);
        }
        return b.toString();
    }

    private static synchronized List<Set<String>> groups() {
        if (groups != null) return groups;
        groups = new ArrayList<>();
        for (String[] g : GROUPS) {
            LinkedHashSet<String> s = new LinkedHashSet<>();
            for (String w : g) { String n = norm(w); if (!n.isEmpty()) s.add(n); }
            groups.add(s);
        }
        return groups;
    }

    /** استعلام محلَّل: كل عنصر = مجموعة بدائل، ويجب أن يطابق الإعلانُ عنصراً واحداً على الأقل من كل مجموعة. */
    public static final class Query {
        public final List<Set<String>> terms = new ArrayList<>();
        public final String raw;

        Query(String raw) { this.raw = raw; }

        public boolean isEmpty() { return terms.isEmpty(); }

        public boolean matches(String hayNorm) {
            String h = " " + hayNorm + " ";
            for (Set<String> alts : terms) {
                boolean ok = false;
                for (String a : alts) {
                    // الكلمات القصيرة اللاتينية (it, un, hr…) تطابق ككلمة كاملة فقط
                    if (a.length() <= 3 && a.matches("[a-z&]+") ? h.contains(" " + a + " ") : h.contains(a)) { ok = true; break; }
                }
                if (!ok) return false;
            }
            return true;
        }

        /** كل البدائل (لتمييزها بلون). */
        public List<String> allWords() {
            ArrayList<String> out = new ArrayList<>();
            for (Set<String> s : terms) out.addAll(s);
            out.sort((x, y) -> y.length() - x.length());
            return out;
        }
    }

    public static Query parse(String q) {
        Query out = new Query(q);
        String n = " " + norm(q) + " ";
        if (n.trim().isEmpty()) return out;
        // العبارات متعددة الكلمات أولاً (مثل «دير الزور»)
        for (Set<String> g : groups()) {
            for (String term : g) {
                if (!term.contains(" ")) continue;
                if (n.contains(" " + term + " ")) {
                    out.terms.add(new LinkedHashSet<>(g));
                    n = n.replace(" " + term + " ", " ");
                    break;
                }
            }
        }
        for (String w : n.trim().split("\\s+")) {
            if (w.isEmpty()) continue;
            LinkedHashSet<String> alts = new LinkedHashSet<>();
            alts.add(w);
            for (Set<String> g : groups()) {
                for (String term : g) {
                    if (term.equals(w) || (w.length() >= 4 && term.startsWith(w)) || (term.length() >= 4 && w.startsWith(term))) { alts.addAll(g); break; }
                }
            }
            out.terms.add(alts);
        }
        return out;
    }

    /** نص البحث الكامل لإعلان. */
    public static String hay(Store.Job j) {
        return norm(j.title + " " + j.org + " " + j.src + " " + Labels.names(Labels.GOVS, j.govs) + " "
                + Labels.names(Labels.SPECS, j.specs) + " " + Labels.SECTORS.get(j.sector) + " " + j.text);
    }

    /** تمييز الكلمات المطابقة داخل نص. */
    public static CharSequence highlight(String text, Query q, int bg, int fg) {
        if (text == null) return "";
        if (q == null || q.isEmpty()) return text;
        String l = light(text);
        SpannableString sp = new SpannableString(text);
        boolean any = false;
        for (String w : q.allWords()) {
            if (w.length() < 2) continue;
            String lw = light(w);
            int from = 0, i;
            while ((i = l.indexOf(lw, from)) >= 0) {
                boolean shortLatin = lw.length() <= 3 && lw.matches("[a-z&]+");
                boolean okB = !shortLatin || ((i == 0 || !Character.isLetter(l.charAt(i - 1))) && (i + lw.length() >= l.length() || !Character.isLetter(l.charAt(i + lw.length()))));
                if (okB) {
                    sp.setSpan(new BackgroundColorSpan(bg), i, i + lw.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sp.setSpan(new ForegroundColorSpan(fg), i, i + lw.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    any = true;
                }
                from = i + lw.length();
            }
        }
        return any ? sp : text;
    }

    /** مقتطف من النص حول أول كلمة مطابقة (يُعرض في البطاقة عندما لا تكون الكلمة في العنوان). */
    public static String snippet(String text, Query q) {
        if (text == null || q == null || q.isEmpty()) return "";
        String l = light(text);
        int best = -1, len = 0;
        for (String w : q.allWords()) {
            if (w.length() < 2) continue;
            int i = l.indexOf(light(w));
            if (i >= 0 && (best < 0 || i < best)) { best = i; len = w.length(); }
        }
        if (best < 0) return "";
        int s = Math.max(0, best - 45), e = Math.min(text.length(), best + len + 70);
        String out = text.substring(s, e).replace('\n', ' ').trim();
        return (s > 0 ? "…" : "") + out + (e < text.length() ? "…" : "");
    }

    static int HL_BG() { return Ui.DARK ? 0xFF5C4A00 : 0xFFFFE082; }
    static int HL_FG() { return Ui.DARK ? Color.WHITE : 0xFF1C2333; }

    static String lower(String s) { return s.toLowerCase(Locale.ROOT); }
}
