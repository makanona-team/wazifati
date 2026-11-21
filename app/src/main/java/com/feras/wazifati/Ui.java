package com.feras.wazifati;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** أدوات بناء الواجهة برمجياً. */
public final class Ui {
    private Ui() { }

    // الألوان تتغير حسب الوضع النهاري/الليلي (Ui.apply)
    public static int PRIMARY = 0xFF1565C0, PRIMARY_LIGHT = 0xFFE3EDFB, BG = 0xFFF3F6FB, CARD = 0xFFFFFFFF, HEADER = 0xFF1565C0,
            TEXT = 0xFF1C2333, MUTED = 0xFF5B6472, GREEN = 0xFF2E7D32, RED = 0xFFC62828, ORANGE = 0xFFE65100, LINE = 0xFFE1E6EE,
            WARN_BG = 0xFFFFF3CD, OK_BG = 0xFFDDF3E2, INFO_BG = 0xFFE3EDFB;
    public static boolean DARK = false;

    /** يحدد الوضع حسب إعداد المستخدم: auto / light / dark. */
    public static boolean isDark(Context c) {
        String t = Store.p(c).getString("theme", "auto");
        if (t.equals("dark")) return true;
        if (t.equals("light")) return false;
        return (c.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    public static void apply(Context c) {
        DARK = isDark(c);
        if (DARK) {
            PRIMARY = 0xFF5B9BFF; PRIMARY_LIGHT = 0xFF1B2A44; BG = 0xFF0F1216; CARD = 0xFF1E232B; HEADER = 0xFF161A20;
            TEXT = 0xFFECEEF1; MUTED = 0xFFB4BBC4; GREEN = 0xFF6FCF76; RED = 0xFFFF6B66; ORANGE = 0xFFFFA726; LINE = 0xFF343B44;
            WARN_BG = 0xFF3A3220; OK_BG = 0xFF1F3A27; INFO_BG = 0xFF1B2A44;
        } else {
            PRIMARY = 0xFF1565C0; PRIMARY_LIGHT = 0xFFE3EDFB; BG = 0xFFF3F6FB; CARD = 0xFFFFFFFF; HEADER = 0xFF1565C0;
            TEXT = 0xFF1C2333; MUTED = 0xFF5B6472; GREEN = 0xFF2E7D32; RED = 0xFFC62828; ORANGE = 0xFFE65100; LINE = 0xFFE1E6EE;
            WARN_BG = 0xFFFFF3CD; OK_BG = 0xFFDDF3E2; INFO_BG = 0xFFE3EDFB;
        }
    }

    public static int themeRes() {
        return DARK ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar;
    }

    // ===== التاريخ الميلادي والهجري =====
    static final String[] DAYS = {"الأحد", "الإثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت"};
    static final String[] MONTHS = {"كانون الثاني", "شباط", "آذار", "نيسان", "أيار", "حزيران", "تموز", "آب", "أيلول", "تشرين الأول", "تشرين الثاني", "كانون الأول"};
    static final String[] HIJRI = {"محرم", "صفر", "ربيع الأول", "ربيع الآخر", "جمادى الأولى", "جمادى الآخرة", "رجب", "شعبان", "رمضان", "شوال", "ذو القعدة", "ذو الحجة"};

    public static String today() {
        java.util.Calendar g = java.util.Calendar.getInstance();
        return DAYS[g.get(java.util.Calendar.DAY_OF_WEEK) - 1] + " " + g.get(java.util.Calendar.DAY_OF_MONTH) + " "
                + MONTHS[g.get(java.util.Calendar.MONTH)];
    }

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    public static GradientDrawable round(Context c, int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, r));
        return g;
    }

    public static GradientDrawable outline(Context c, int fill, int stroke, float r) {
        GradientDrawable g = round(c, fill, r);
        g.setStroke(dp(c, 1), stroke);
        return g;
    }

    public static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextDirection(View.TEXT_DIRECTION_ANY_RTL);
        return t;
    }

    public static TextView oneLine(TextView t) {
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    public static Button button(Context c, String s, boolean filled) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTextColor(filled ? Color.WHITE : PRIMARY);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33000000),
                filled ? round(c, PRIMARY, 12) : outline(c, CARD, PRIMARY, 12), null));
        b.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        b.setMinHeight(dp(c, 46));
        b.setMinimumHeight(dp(c, 46));
        b.setStateListAnimator(null);
        return b;
    }

    public static TextView chip(Context c, String s, boolean on) {
        TextView t = text(c, s, 13, on ? Color.WHITE : PRIMARY, false);
        t.setBackground(on ? round(c, PRIMARY, 18) : outline(c, CARD, PRIMARY, 18));
        t.setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6));
        t.setGravity(Gravity.CENTER);
        return t;
    }

    public static void setChip(Context c, TextView t, boolean on) {
        t.setTextColor(on ? Color.WHITE : PRIMARY);
        t.setBackground(on ? round(c, PRIMARY, 18) : outline(c, CARD, PRIMARY, 18));
    }

    public static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(outline(c, CARD, LINE, 16));
        l.setElevation(DARK ? 0 : dp(c, 1));
        int p = dp(c, 14);
        l.setPadding(p, p, p, p);
        return l;
    }

    /** شارة صغيرة موحّدة (جديد، العضوية، تحديث…). */
    public static TextView pill(Context c, String s, int bg) {
        TextView t = text(c, s, 12, Color.WHITE, true);
        t.setBackground(round(c, bg, 10));
        t.setPadding(dp(c, 10), dp(c, 3), dp(c, 10), dp(c, 3));
        t.setGravity(Gravity.CENTER);
        return t;
    }

    /** سطر فاصل رفيع. */
    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.max(1, dp(c, 1))));
        return v;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lpm(Context c, int w, int h, int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = dp(c, top);
        p.bottomMargin = dp(c, bottom);
        return p;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static String ago(long t) {
        long d = System.currentTimeMillis() - t;
        if (d < 0) d = 0;
        long m = d / 60_000;
        if (m < 1) return "الآن";
        if (m < 60) return "منذ " + m + " د";
        long h = m / 60;
        if (h < 24) return "منذ " + h + " س";
        long days = h / 24;
        if (days == 1) return "أمس";
        return "منذ " + days + " يوم";
    }
}
