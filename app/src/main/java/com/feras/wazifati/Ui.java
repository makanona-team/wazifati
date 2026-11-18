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

    public static final int PRIMARY = 0xFF1565C0, PRIMARY_LIGHT = 0xFFE3EDFB, BG = 0xFFF3F6FB, CARD = 0xFFFFFFFF,
            TEXT = 0xFF1C2333, MUTED = 0xFF6B7280, GREEN = 0xFF2E7D32, RED = 0xFFC62828, ORANGE = 0xFFE65100, LINE = 0xFFE5E9F0;

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
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33000000),
                filled ? round(c, PRIMARY, 12) : outline(c, CARD, PRIMARY, 12), null));
        b.setPadding(dp(c, 12), 0, dp(c, 12), 0);
        b.setMinHeight(dp(c, 42));
        b.setMinimumHeight(dp(c, 42));
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
        l.setBackground(round(c, CARD, 14));
        l.setElevation(dp(c, 1));
        int p = dp(c, 14);
        l.setPadding(p, p, p, p);
        return l;
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
