package com.feras.wazifati;

import java.util.LinkedHashMap;

/** أسماء الاختصاصات والمحافظات وأنواع الجهات (مطابقة لما يستخدمه الخادم). */
public final class Labels {
    private Labels() { }

    public static final LinkedHashMap<String, String> SPECS = new LinkedHashMap<>();
    public static final LinkedHashMap<String, String> GOVS = new LinkedHashMap<>();
    public static final LinkedHashMap<String, String> SECTORS = new LinkedHashMap<>();

    static {
        String[][] s = {
                {"engineering", "هندسة ومأوى وإعادة تأهيل"},
                {"oil", "نفط وطاقة"},
                {"meal", "متابعة وتقييم MEAL"},
                {"management", "إدارة برامج وتنسيق"},
                {"admin", "إدارة وموارد بشرية"},
                {"finance", "مالية ومحاسبة"},
                {"logistics", "مشتريات ولوجستيك ومستودعات"},
                {"protection", "حماية وإدارة حالة"},
                {"health", "صحة وتغذية"},
                {"wash", "مياه وإصحاح WASH"},
                {"livelihoods", "سبل عيش وأمن غذائي وزراعة"},
                {"education", "تعليم"},
                {"it", "تقنية معلومات وبرمجة وتصميم"},
                {"media", "إعلام وتواصل وترجمة"},
                {"legal", "قانون"},
                {"drivers", "سائقون وحراسة وخدمات وصيانة"},
                {"sales", "مبيعات وتسويق وخدمة عملاء"},
                {"training", "تدريب ومنح وتطوع"},
                {"tender", "مناقصات وعروض أسعار"},
                {"other", "غير مصنف"},
        };
        for (String[] a : s) SPECS.put(a[0], a[1]);

        String[][] g = {
                {"deirezzor", "دير الزور"}, {"raqqa", "الرقة"}, {"hasakah", "الحسكة"},
                {"aleppo", "حلب"}, {"idlib", "إدلب"}, {"damascus", "دمشق"}, {"rifdimashq", "ريف دمشق"},
                {"homs", "حمص"}, {"hama", "حماة"}, {"latakia", "اللاذقية"}, {"tartus", "طرطوس"},
                {"daraa", "درعا"}, {"sweida", "السويداء"}, {"quneitra", "القنيطرة"},
                {"all", "كل سوريا"}, {"remote", "عن بعد"}, {"abroad", "خارج سوريا"}, {"unknown", "غير محدد"},
        };
        for (String[] a : g) GOVS.put(a[0], a[1]);

        String[][] c = {{"ngo", "منظمات"}, {"un", "أمم متحدة"}, {"gov", "حكومي"}, {"private", "قطاع خاص"}, {"unknown", "غير محدد"}};
        for (String[] a : c) SECTORS.put(a[0], a[1]);
    }

    public static String names(LinkedHashMap<String, String> map, String csv) {
        if (csv == null || csv.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String k : csv.split(",")) {
            String v = map.get(k.trim());
            if (v == null) continue;
            if (sb.length() > 0) sb.append(" • ");
            sb.append(v);
        }
        return sb.toString();
    }
}
