package com.feras.wazifati;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.util.Linkify;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.feras.wazifati.Ui.*;

public class MainActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout content;
    private TextView status;
    private TextView[] tabs = new TextView[4];
    private int tab = 0;
    private List<Store.Job> all = new ArrayList<>();
    private boolean syncing;

    // حالة شاشة الفرص
    private String query = "";
    private boolean useFilter = true, onlyNew = false, onlyShared = false;
    private int savedMode = 0; // 0 مفضلة، 1 قدّمت، 2 مخفية
    private JobAdapter adapter;
    private TextView countLabel;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(PRIMARY);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        Sync.channels(this);
        Sync.schedule(this);
        if (Build.VERSION.SDK_INT >= 33 && !Sync.canPost(this)) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        // الشريط العلوي
        LinearLayout head = row(this);
        head.setBackgroundColor(PRIMARY);
        head.setPadding(dp(this, 16), dp(this, 10), dp(this, 8), dp(this, 10));
        LinearLayout ht = new LinearLayout(this);
        ht.setOrientation(LinearLayout.VERTICAL);
        ht.addView(text(this, "💼 وظيفتي", 20, Color.WHITE, true));
        status = oneLine(text(this, "", 12, 0xDDFFFFFF, false));
        ht.addView(status);
        head.addView(ht, new LinearLayout.LayoutParams(0, -2, 1));
        TextView refresh = text(this, "⟳", 26, Color.WHITE, true);
        refresh.setPadding(dp(this, 14), 0, dp(this, 14), 0);
        refresh.setOnClickListener(v -> refresh(true));
        head.addView(refresh);
        root.addView(head);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        // الشريط السفلي
        LinearLayout nav = row(this);
        nav.setBackgroundColor(CARD);
        nav.setElevation(dp(this, 8));
        String[] names = {"🔎\nالفرص", "⭐\nالمحفوظة", "🎯\nفلتري", "☰\nالمزيد"};
        for (int i = 0; i < 4; i++) {
            final int k = i;
            TextView t = text(this, names[i], 12, MUTED, false);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(this, 6), 0, dp(this, 6));
            t.setOnClickListener(v -> show(k));
            tabs[i] = t;
            nav.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        }
        root.addView(nav);
        setContentView(root);

        all = Store.get(this).all();
        boolean setup = Store.p(this).getBoolean("setup_done", false);
        show(setup ? getIntent().getIntExtra("tab", 0) : 2);
        if (getIntent().getBooleanExtra("new", false)) { onlyNew = true; show(0); }
        refresh(false);
        if (getIntent().getBooleanExtra("update", false)) checkUpdate(true);
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        if (i.getBooleanExtra("new", false)) { onlyNew = true; show(0); refresh(false); }
        if (i.getBooleanExtra("update", false)) checkUpdate(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (Store.p(this).getLong("prefs_updated", 0) > Store.p(this).getLong("prefs_pushed", 0)) Sync.pushPrefs(this);
    }

    @Override
    public void onBackPressed() {
        if (tab != 0) show(0);
        else super.onBackPressed();
    }

    private void setStatus() {
        long ls = Store.p(this).getLong("last_sync", 0);
        status.setText(syncing ? "جارٍ جلب الفرص…" : (all.size() + " فرصة محفوظة • آخر تحديث " + (ls == 0 ? "—" : ago(ls))));
    }

    private void refresh(boolean manual) {
        if (syncing) return;
        syncing = true;
        setStatus();
        Context app = getApplicationContext();
        new Thread(() -> {
            String err = null;
            int n = 0;
            boolean wasSetup = Store.p(app).getBoolean("setup_done", false);
            try { n = Sync.run(app, false); } catch (Throwable e) { err = e.getMessage(); }
            final String fe = err;
            final int fn = n;
            List<Store.Job> l = Store.get(app).all();
            ui.post(() -> {
                syncing = false;
                all = l;
                setStatus();
                if (fe != null && manual) Toast.makeText(this, "تعذّر التحديث: " + fe, Toast.LENGTH_LONG).show();
                else if (manual) Toast.makeText(this, fn > 0 ? fn + " فرصة جديدة تناسبك" : "لا جديد الآن", Toast.LENGTH_SHORT).show();
                // تثبيت جديد واسترجع إعداداته تلقائياً من الخادم
                if (!wasSetup && Store.p(this).getBoolean("setup_done", false) && tab == 2) show(0);
                else if (tab == 0 || tab == 1) show(tab);
            });
        }).start();
    }

    private void show(int k) {
        tab = k;
        for (int i = 0; i < 4; i++) {
            tabs[i].setTextColor(i == k ? PRIMARY : MUTED);
            tabs[i].setTypeface(i == k ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        content.removeAllViews();
        hideKeyboard();
        setStatus();
        if (k == 0) content.addView(jobsScreen());
        else if (k == 1) content.addView(savedScreen());
        else if (k == 2) content.addView(filterScreen());
        else content.addView(moreScreen());
    }

    private void hideKeyboard() {
        InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null && getCurrentFocus() != null) im.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
    }

    // =====================================================================
    // شاشة الفرص
    // =====================================================================
    private View jobsScreen() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(this, 10), dp(this, 8), dp(this, 10), 0);

        EditText search = new EditText(this);
        search.setHint("🔍 ابحث: مهندس، محاسب، UNICEF، الرقة…");
        search.setText(query);
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setBackground(outline(this, CARD, LINE, 12));
        search.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
        l.addView(search, lp(-1, -2));

        boolean hasFilter = !Store.Filter.of(this).isEmpty();
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = row(this);
        chips.setPadding(0, dp(this, 8), 0, dp(this, 6));
        TextView cFilter = chip(this, "🎯 حسب فلتري", useFilter && hasFilter);
        TextView cNew = chip(this, "🆕 الجديد", onlyNew);
        TextView cExp = chip(this, "⏳ إخفاء المنتهي", Store.bool(this, "hide_expired"));
        TextView cShared = chip(this, "🤝 مشاركات الأصدقاء", onlyShared);
        TextView cRead = chip(this, "✔ تعليم الكل كمقروء", false);
        for (TextView t : new TextView[]{cFilter, cNew, cExp, cShared, cRead}) {
            LinearLayout.LayoutParams p = lp(-2, -2);
            p.leftMargin = dp(this, 6);
            chips.addView(t, p);
        }
        hs.addView(chips);
        l.addView(hs);

        countLabel = text(this, "", 12, MUTED, false);
        countLabel.setPadding(dp(this, 4), 0, dp(this, 4), dp(this, 4));
        l.addView(countLabel);

        ListView lv = new ListView(this);
        lv.setDivider(null);
        lv.setDividerHeight(dp(this, 8));
        lv.setClipToPadding(false);
        lv.setPadding(0, 0, 0, dp(this, 12));
        adapter = new JobAdapter();
        lv.setAdapter(adapter);
        lv.setOnItemClickListener((p, v, pos, id) -> openJob(adapter.items.get(pos)));
        lv.setEmptyView(emptyView(l));
        l.addView(lv, new LinearLayout.LayoutParams(-1, 0, 1));

        cFilter.setOnClickListener(v -> {
            if (!hasFilter) { Toast.makeText(this, "لم تحدد فلتراً بعد — من تبويب «فلتري»", Toast.LENGTH_SHORT).show(); return; }
            useFilter = !useFilter; setChip(this, cFilter, useFilter); applyList();
        });
        cNew.setOnClickListener(v -> { onlyNew = !onlyNew; setChip(this, cNew, onlyNew); applyList(); });
        cExp.setOnClickListener(v -> {
            boolean on = !Store.bool(this, "hide_expired");
            Store.putBool(this, "hide_expired", on); setChip(this, cExp, on); applyList();
        });
        cShared.setOnClickListener(v -> { onlyShared = !onlyShared; setChip(this, cShared, onlyShared); applyList(); });
        cRead.setOnClickListener(v -> {
            Store.get(this).markAllSeen();
            for (Store.Job j : all) j.seen = 1;
            onlyNew = false; setChip(this, cNew, false); applyList();
        });
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) { query = s.toString().trim(); applyList(); }
        });
        applyList();
        return l;
    }

    private TextView emptyView(LinearLayout parent) {
        TextView e = text(this, "", 15, MUTED, false);
        e.setGravity(Gravity.CENTER);
        e.setPadding(dp(this, 24), dp(this, 40), dp(this, 24), dp(this, 40));
        e.setText(all.isEmpty()
                ? (syncing ? "جارٍ جلب الفرص لأول مرة…" : "لا توجد فرص محفوظة بعد.\nاضغط ⟳ في الأعلى للتحديث.")
                : "لا توجد فرص تطابق البحث أو الفلتر الحالي.");
        parent.addView(e, lp(-1, -2));
        e.setVisibility(View.GONE);
        return e;
    }

    private void applyList() {
        if (adapter == null) return;
        Store.Filter f = Store.Filter.of(this);
        boolean useF = useFilter && !f.isEmpty();
        boolean hideExp = Store.bool(this, "hide_expired");
        Set<String> hidden = Store.set(this, "hidden");
        String q = norm(query);
        String[] words = q.isEmpty() ? new String[0] : q.split("\\s+");
        ArrayList<Store.Job> out = new ArrayList<>();
        for (Store.Job j : all) {
            if (hidden.contains(String.valueOf(j.id))) continue;
            if (useF && !f.match(j)) continue;
            if (hideExp && Store.expired(j)) continue;
            if (onlyNew && j.seen != 0) continue;
            if (onlyShared && j.shared == 0) continue;
            if (words.length > 0) {
                String hay = norm(j.title + " " + j.org + " " + j.src + " " + Labels.names(Labels.GOVS, j.govs) + " " + Labels.names(Labels.SPECS, j.specs) + " " + j.text);
                boolean ok = true;
                for (String w : words) if (!hay.contains(w)) { ok = false; break; }
                if (!ok) continue;
            }
            out.add(j);
        }
        adapter.set(out);
        if (countLabel != null) countLabel.setText(out.size() + " فرصة" + (useF ? " حسب فلترك" : "") + (all.size() != out.size() ? " من أصل " + all.size() : ""));
    }

    static String norm(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[ً-ْـ]", "")
                .replaceAll("[أإآ]", "ا").replace('ة', 'ه').replace('ى', 'ي');
    }

    // =====================================================================
    // بطاقة الوظيفة في القائمة
    // =====================================================================
    private class JobAdapter extends BaseAdapter {
        List<Store.Job> items = new ArrayList<>();
        Set<String> favs, applied;

        void set(List<Store.Job> l) {
            items = l;
            favs = Store.set(MainActivity.this, "favs");
            applied = Store.set(MainActivity.this, "applied");
            notifyDataSetChanged();
        }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return items.get(i).id; }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            Holder h;
            if (cv == null) {
                h = new Holder();
                LinearLayout c = card(MainActivity.this);
                c.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
                LinearLayout r1 = row(MainActivity.this);
                h.badge = text(MainActivity.this, "جديد", 11, Color.WHITE, true);
                h.badge.setBackground(round(MainActivity.this, ORANGE, 8));
                h.badge.setPadding(dp(MainActivity.this, 6), dp(MainActivity.this, 1), dp(MainActivity.this, 6), dp(MainActivity.this, 1));
                LinearLayout.LayoutParams bp = lp(-2, -2);
                bp.leftMargin = dp(MainActivity.this, 6);
                r1.addView(h.badge, bp);
                h.title = text(MainActivity.this, "", 16, TEXT, true);
                h.title.setMaxLines(2);
                h.title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                r1.addView(h.title, new LinearLayout.LayoutParams(0, -2, 1));
                h.marks = text(MainActivity.this, "", 14, TEXT, false);
                r1.addView(h.marks);
                c.addView(r1);
                h.org = oneLine(text(MainActivity.this, "", 13, MUTED, false));
                c.addView(h.org);
                h.tags = text(MainActivity.this, "", 12, PRIMARY, false);
                h.tags.setMaxLines(2);
                h.tags.setPadding(0, dp(MainActivity.this, 4), 0, 0);
                c.addView(h.tags);
                LinearLayout r3 = row(MainActivity.this);
                r3.setPadding(0, dp(MainActivity.this, 6), 0, 0);
                h.deadline = text(MainActivity.this, "", 12, GREEN, true);
                r3.addView(h.deadline, new LinearLayout.LayoutParams(0, -2, 1));
                h.meta = oneLine(text(MainActivity.this, "", 11, MUTED, false));
                r3.addView(h.meta);
                c.addView(r3);
                cv = c;
                cv.setTag(h);
            } else h = (Holder) cv.getTag();

            Store.Job j = items.get(pos);
            h.badge.setVisibility(j.seen == 0 ? View.VISIBLE : View.GONE);
            h.title.setText(j.title);
            String sec = Labels.SECTORS.get(j.sector);
            String org = j.org.isEmpty() ? "" : j.org;
            if (sec != null && !"unknown".equals(j.sector)) org = org.isEmpty() ? sec : org + " • " + sec;
            h.org.setText(org);
            h.org.setVisibility(org.isEmpty() ? View.GONE : View.VISIBLE);
            String g = Labels.names(Labels.GOVS, j.govs), s = Labels.names(Labels.SPECS, j.specs);
            h.tags.setText("📍 " + (g.isEmpty() ? "غير محدد" : g) + "   🏷 " + (s.isEmpty() ? "غير مصنف" : s));
            deadlineText(h.deadline, j);
            h.meta.setText(ago(j.posted) + " • " + j.src + (j.nSrc > 1 ? " +" + (j.nSrc - 1) : ""));
            String id = String.valueOf(j.id);
            h.marks.setText((favs.contains(id) ? "⭐" : "") + (applied.contains(id) ? "✅" : "") + (j.shared != 0 ? "🤝" : ""));
            cv.setAlpha(Store.expired(j) ? 0.55f : 1f);
            return cv;
        }
    }

    private static class Holder { TextView badge, title, marks, org, tags, deadline, meta; }

    private void deadlineText(TextView t, Store.Job j) {
        int d = Store.daysLeft(j);
        if (d == Integer.MIN_VALUE) { t.setText(""); return; }
        if (d < 0) { t.setText("⛔ انتهى التقديم"); t.setTextColor(RED); }
        else if (d == 0) { t.setText("⏰ يغلق اليوم"); t.setTextColor(RED); }
        else if (d <= 3) { t.setText("⏰ يغلق بعد " + d + (d == 1 ? " يوم" : " أيام")); t.setTextColor(ORANGE); }
        else { t.setText("⏳ يغلق " + j.deadline + " (" + d + " يوم)"); t.setTextColor(GREEN); }
    }

    // =====================================================================
    // تفاصيل الوظيفة
    // =====================================================================
    private void openJob(Store.Job j) {
        if (j.seen == 0) { j.seen = 1; Store.get(this).markSeen(j.id); if (adapter != null) adapter.notifyDataSetChanged(); }
        Dialog d = new Dialog(this, android.R.style.Theme_Material_Light_NoActionBar);
        Window w = d.getWindow();
        if (w != null) w.setStatusBarColor(PRIMARY);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        LinearLayout bar = row(this);
        bar.setBackgroundColor(PRIMARY);
        bar.setPadding(dp(this, 8), dp(this, 10), dp(this, 8), dp(this, 10));
        TextView back = text(this, "→  رجوع", 16, Color.WHITE, true);
        back.setPadding(dp(this, 8), dp(this, 4), dp(this, 8), dp(this, 4));
        back.setOnClickListener(v -> d.dismiss());
        bar.addView(back);
        root.addView(bar);

        ScrollView sv = new ScrollView(this);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(this, 12), dp(this, 12), dp(this, 12), dp(this, 24));
        sv.addView(l);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout c = card(this);
        TextView title = text(this, j.title, 19, TEXT, true);
        title.setTextIsSelectable(true);
        c.addView(title);
        if (!j.org.isEmpty()) c.addView(text(this, j.org, 15, MUTED, false));
        addInfo(c, "📍 المكان", Labels.names(Labels.GOVS, j.govs));
        addInfo(c, "🏷 الاختصاص", Labels.names(Labels.SPECS, j.specs));
        addInfo(c, "🏢 الجهة", Labels.SECTORS.get(j.sector));
        TextView dl = text(this, "", 14, GREEN, true);
        deadlineText(dl, j);
        if (dl.getText().length() > 0) { dl.setPadding(0, dp(this, 6), 0, 0); c.addView(dl); }
        addInfo(c, "🕒 نُشر", ago(j.posted));
        addInfo(c, "📡 المصدر", j.src + (j.nSrc > 1 ? " (ونُشر في " + (j.nSrc - 1) + " مصدر آخر)" : ""));
        l.addView(c, lpm(this, -1, -2, 0, 10));

        // الأزرار
        LinearLayout r1 = row(this);
        if (!j.url.isEmpty() && j.url.startsWith("http")) {
            android.widget.Button open = button(this, "📄 الإعلان الأصلي", !j.apply.isEmpty() ? false : true);
            open.setOnClickListener(v -> openUrl(j.url));
            r1.addView(open, weight());
        }
        if (!j.apply.isEmpty()) {
            android.widget.Button ap = button(this, j.apply.startsWith("mailto:") ? "✉️ التقديم بالبريد" : "📝 رابط التقديم", true);
            ap.setOnClickListener(v -> openUrl(j.apply));
            r1.addView(ap, weight());
        }
        if (r1.getChildCount() > 0) l.addView(r1, lpm(this, -1, -2, 0, 8));

        String id = String.valueOf(j.id);
        LinearLayout r2 = row(this);
        android.widget.Button fav = button(this, Store.set(this, "favs").contains(id) ? "⭐ محفوظة" : "☆ حفظ", false);
        fav.setOnClickListener(v -> {
            boolean on = Store.toggle(this, "favs", id);
            fav.setText(on ? "⭐ محفوظة" : "☆ حفظ");
            Sync.pushPrefs(this);
        });
        android.widget.Button app = button(this, Store.set(this, "applied").contains(id) ? "✅ قدّمت" : "قدّمت عليها", false);
        app.setOnClickListener(v -> {
            boolean on = Store.toggle(this, "applied", id);
            app.setText(on ? "✅ قدّمت" : "قدّمت عليها");
            Sync.pushPrefs(this);
        });
        android.widget.Button share = button(this, "↗ مشاركة", false);
        share.setOnClickListener(v -> {
            String body = j.title + (j.org.isEmpty() ? "" : "\n" + j.org) + "\n📍 " + Labels.names(Labels.GOVS, j.govs)
                    + (j.deadline.isEmpty() ? "" : "\n⏳ آخر موعد: " + j.deadline)
                    + (j.apply.isEmpty() ? "" : "\nللتقديم: " + j.apply.replace("mailto:", ""))
                    + (j.url.startsWith("http") ? "\nالإعلان: " + j.url : "") + "\n\n— عبر تطبيق وظيفتي";
            Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body);
            startActivity(Intent.createChooser(s, "مشاركة الفرصة"));
        });
        r2.addView(fav, weight());
        r2.addView(app, weight());
        r2.addView(share, weight());
        l.addView(r2, lpm(this, -1, -2, 0, 8));

        boolean isHidden = Store.set(this, "hidden").contains(id);
        android.widget.Button hide = button(this, isHidden ? "👁 إظهار الإعلان مجدداً" : "🚫 إخفاء هذا الإعلان (لا يهمني)", false);
        hide.setOnClickListener(v -> {
            Store.toggle(this, "hidden", id);
            Sync.pushPrefs(this);
            d.dismiss();
            applyList();
            if (tab == 1) show(1);
        });
        l.addView(hide, lpm(this, -1, -2, 0, 10));

        LinearLayout tc = card(this);
        tc.addView(text(this, "نص الإعلان", 14, MUTED, true));
        TextView body = text(this, Store.get(this).fullText(j.id), 15, TEXT, false);
        body.setLineSpacing(0, 1.25f);
        body.setTextIsSelectable(true);
        body.setAutoLinkMask(Linkify.WEB_URLS | Linkify.EMAIL_ADDRESSES | Linkify.PHONE_NUMBERS);
        body.setLinksClickable(true);
        body.setPadding(0, dp(this, 6), 0, 0);
        tc.addView(body);
        l.addView(tc, lp(-1, -2));

        d.setContentView(root);
        d.setOnDismissListener(x -> { if (adapter != null) adapter.set(adapter.items); });
        d.show();
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
        p.leftMargin = dp(this, 3);
        p.rightMargin = dp(this, 3);
        return p;
    }

    private void addInfo(LinearLayout c, String k, String v) {
        if (v == null || v.isEmpty()) return;
        TextView t = text(this, k + ":  " + v, 14, TEXT, false);
        t.setPadding(0, dp(this, 6), 0, 0);
        c.addView(t);
    }

    private void openUrl(String u) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u))); }
        catch (Exception e) { Toast.makeText(this, "لا يوجد تطبيق لفتح الرابط", Toast.LENGTH_SHORT).show(); }
    }

    // =====================================================================
    // المحفوظة
    // =====================================================================
    private View savedScreen() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(this, 10), dp(this, 8), dp(this, 10), 0);
        Set<String> favs = Store.set(this, "favs"), applied = Store.set(this, "applied"), hidden = Store.set(this, "hidden");
        LinearLayout seg = row(this);
        String[] names = {"⭐ المفضلة (" + favs.size() + ")", "✅ قدّمت (" + applied.size() + ")", "🚫 المخفية (" + hidden.size() + ")"};
        for (int i = 0; i < 3; i++) {
            final int k = i;
            TextView t = chip(this, names[i], savedMode == i);
            t.setOnClickListener(v -> { savedMode = k; show(1); });
            seg.addView(t, weight());
        }
        l.addView(seg, lpm(this, -1, -2, 0, 8));
        Set<String> pick = savedMode == 0 ? favs : savedMode == 1 ? applied : hidden;
        ArrayList<Store.Job> out = new ArrayList<>();
        for (Store.Job j : all) if (pick.contains(String.valueOf(j.id))) out.add(j);
        ListView lv = new ListView(this);
        lv.setDivider(null);
        lv.setDividerHeight(dp(this, 8));
        JobAdapter a = new JobAdapter();
        adapter = a;
        countLabel = null;
        lv.setAdapter(a);
        a.set(out);
        lv.setOnItemClickListener((p, v, pos, id) -> openJob(a.items.get(pos)));
        if (out.isEmpty()) {
            TextView e = text(this, savedMode == 0 ? "لم تحفظ أي فرصة بعد.\nافتح أي فرصة واضغط «☆ حفظ»." : savedMode == 1 ? "لم تعلّم أي فرصة كمُقدَّم عليها بعد." : "لا توجد إعلانات مخفية.", 15, MUTED, false);
            e.setGravity(Gravity.CENTER);
            e.setPadding(dp(this, 20), dp(this, 40), dp(this, 20), 0);
            l.addView(e, lp(-1, -2));
        }
        l.addView(lv, new LinearLayout.LayoutParams(-1, 0, 1));
        return l;
    }

    // =====================================================================
    // فلتري
    // =====================================================================
    private View filterScreen() {
        ScrollView sv = new ScrollView(this);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(this, 12), dp(this, 12), dp(this, 12), dp(this, 30));
        sv.addView(l);

        if (!Store.p(this).getBoolean("setup_done", false)) {
            LinearLayout w = card(this);
            w.setBackground(round(this, PRIMARY_LIGHT, 14));
            w.addView(text(this, "أهلاً بك في وظيفتي 👋", 18, PRIMARY, true));
            w.addView(text(this, "اختر اختصاصاتك ومحافظاتك، وسيصلك إشعار بكل فرصة جديدة تناسبك. يمكنك دائماً تصفّح كل الفرص من تبويب «الفرص».\n\nإذا كان عندك رمز استرجاع من تثبيت سابق، أدخله من تبويب «المزيد».", 14, TEXT, false));
            android.widget.Button done = button(this, "تم — اعرض الفرص", true);
            done.setOnClickListener(v -> {
                Store.p(this).edit().putBoolean("setup_done", true).putLong("prefs_updated", System.currentTimeMillis()).apply();
                Sync.pushPrefs(this);
                show(0);
            });
            w.addView(done, lpm(this, -1, -2, 10, 0));
            l.addView(w, lpm(this, -1, -2, 0, 12));
        }

        LinearLayout sw = card(this);
        sw.addView(switchRow("🔔 إشعارات الفرص الجديدة المطابقة لفلتري", "notify"));
        sw.addView(switchRow("❔ أظهر الإعلانات غير المصنفة ضمن فلتري (حتى لا تفوتك فرصة)", "unk"));
        sw.addView(switchRow("⏳ إخفاء الإعلانات المنتهية", "hide_expired"));
        l.addView(sw, lpm(this, -1, -2, 0, 12));

        l.addView(group("🏷 الاختصاصات", "specs", Labels.SPECS, "اترك الكل فارغاً لكل الاختصاصات"), lpm(this, -1, -2, 0, 12));
        l.addView(group("📍 المحافظات", "govs", Labels.GOVS, "اترك الكل فارغاً لكل المحافظات. الإعلانات الموجّهة «لكل سوريا» تظهر دائماً."), lpm(this, -1, -2, 0, 12));
        l.addView(group("🏢 نوع الجهة", "sectors", Labels.SECTORS, "اترك الكل فارغاً لكل الجهات"), lpm(this, -1, -2, 0, 12));
        return sv;
    }

    private View switchRow(String label, String key) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextSize(14);
        s.setTextColor(TEXT);
        s.setChecked(Store.bool(this, key));
        s.setPadding(0, dp(this, 8), 0, dp(this, 8));
        s.setOnCheckedChangeListener((b, on) -> { Store.putBool(this, key, on); Sync.pushPrefs(this); });
        return s;
    }

    private View group(String title, String key, Map<String, String> items, String hint) {
        LinearLayout c = card(this);
        LinearLayout hr = row(this);
        hr.addView(text(this, title, 16, TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView clear = text(this, "مسح", 13, PRIMARY, true);
        clear.setPadding(dp(this, 10), dp(this, 4), dp(this, 10), dp(this, 4));
        hr.addView(clear);
        c.addView(hr);
        c.addView(text(this, hint, 12, MUTED, false));
        Set<String> sel = Store.set(this, key);
        ArrayList<CheckBox> boxes = new ArrayList<>();
        for (Map.Entry<String, String> e : items.entrySet()) {
            if (key.equals("govs") && e.getKey().equals("all")) continue;
            CheckBox cb = new CheckBox(this);
            cb.setText(e.getValue());
            cb.setTextSize(15);
            cb.setTextColor(TEXT);
            cb.setChecked(sel.contains(e.getKey()));
            cb.setOnCheckedChangeListener((b, on) -> {
                Set<String> s = Store.set(this, key);
                if (on) s.add(e.getKey()); else s.remove(e.getKey());
                Store.putSet(this, key, s);
            });
            boxes.add(cb);
            c.addView(cb);
        }
        clear.setOnClickListener(v -> { for (CheckBox b : boxes) b.setChecked(false); });
        return c;
    }

    // =====================================================================
    // المزيد
    // =====================================================================
    private View moreScreen() {
        ScrollView sv = new ScrollView(this);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(this, 12), dp(this, 12), dp(this, 12), dp(this, 30));
        sv.addView(l);

        // رمز الاسترجاع
        LinearLayout rc = card(this);
        rc.addView(text(this, "🔑 رمز الاسترجاع", 16, TEXT, true));
        rc.addView(text(this, "التطبيق يتعرّف على هاتفك تلقائياً، فلو حذفته وأعدت تثبيته تعود إعداداتك ومحفوظاتك. عند تغيير الهاتف أو ضبط المصنع أدخل هذا الرمز:", 13, MUTED, false));
        String code = Store.p(this).getString("code", "");
        TextView ct = text(this, code.isEmpty() ? "— (يظهر بعد أول اتصال بالإنترنت)" : code, 28, PRIMARY, true);
        ct.setGravity(Gravity.CENTER);
        ct.setLetterSpacing(0.2f);
        ct.setPadding(0, dp(this, 8), 0, dp(this, 8));
        ct.setOnClickListener(v -> copy(code));
        rc.addView(ct);
        LinearLayout rr = row(this);
        EditText in = new EditText(this);
        in.setHint("رمز من تثبيت سابق");
        in.setInputType(InputType.TYPE_CLASS_NUMBER);
        in.setSingleLine(true);
        rr.addView(in, new LinearLayout.LayoutParams(0, -2, 1));
        android.widget.Button rb = button(this, "استرجاع", true);
        rb.setOnClickListener(v -> restore(in.getText().toString().trim()));
        rr.addView(rb);
        rc.addView(rr);
        l.addView(rc, lpm(this, -1, -2, 0, 12));

        // الاسم
        LinearLayout nc = card(this);
        nc.addView(text(this, "🙂 اسمك عند مشاركة الفرص", 16, TEXT, true));
        nc.addView(text(this, "يظهر لأصدقائك بجانب الفرص التي تشاركها (اختياري).", 13, MUTED, false));
        EditText name = new EditText(this);
        name.setText(Store.p(this).getString("name", ""));
        name.setSingleLine(true);
        name.setHint("مثلاً: فراس");
        name.setOnFocusChangeListener((v, f) -> {
            if (!f) {
                Store.p(this).edit().putString("name", name.getText().toString().trim()).putLong("prefs_updated", System.currentTimeMillis()).apply();
                Sync.pushPrefs(this);
            }
        });
        nc.addView(name);
        l.addView(nc, lpm(this, -1, -2, 0, 12));

        // طريقة المشاركة من فيسبوك ولينكدإن
        LinearLayout hc = card(this);
        hc.addView(text(this, "🤝 مشاركة فرصة من فيسبوك أو لينكدإن", 16, TEXT, true));
        hc.addView(text(this, "فيسبوك ولينكدإن يمنعان الجلب التلقائي. عندما ترى إعلاناً هناك: اضغط «مشاركة» ← «المزيد» ← اختر «وظيفتي». يصل الإعلان لكل أصدقائك في التطبيق ويُصنَّف تلقائياً.\nأو انسخ نص الإعلان وشاركه بنفس الطريقة.", 13, TEXT, false));
        l.addView(hc, lpm(this, -1, -2, 0, 12));

        // المصادر
        LinearLayout sc = card(this);
        sc.addView(text(this, "📡 المصادر", 16, TEXT, true));
        sc.addView(text(this, "يُجلب من هذه المصادر كل ساعة. اضغط على المصدر لفتحه، واضغط مطولاً لحذفه.", 13, MUTED, false));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.addView(text(this, "جارٍ التحميل…", 13, MUTED, false));
        sc.addView(list);
        LinearLayout ar = row(this);
        EditText src = new EditText(this);
        src.setHint("رابط قناة تيليغرام أو موقع وظائف");
        src.setSingleLine(true);
        src.setTextSize(14);
        ar.addView(src, new LinearLayout.LayoutParams(0, -2, 1));
        android.widget.Button add = button(this, "إضافة", true);
        add.setOnClickListener(v -> addSource(src.getText().toString().trim(), list, src));
        ar.addView(add);
        sc.addView(ar);
        l.addView(sc, lpm(this, -1, -2, 0, 12));
        loadSources(list);

        // التحديثات وحول
        LinearLayout uc = card(this);
        uc.addView(text(this, "⬆️ التطبيق", 16, TEXT, true));
        uc.addView(text(this, "الإصدار الحالي: " + Updater.current(this), 13, MUTED, false));
        LinearLayout ub = row(this);
        android.widget.Button chk = button(this, "البحث عن تحديث", true);
        chk.setOnClickListener(v -> checkUpdate(false));
        android.widget.Button news = button(this, "ما الجديد", false);
        news.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("ما الجديد").setMessage(Updater.whatsNew(this)).setPositiveButton("حسناً", null).show());
        ub.addView(chk, weight());
        ub.addView(news, weight());
        uc.addView(ub, lpm(this, -1, -2, 8, 4));
        android.widget.Button srv = button(this, "🖥 حالة الخادم والمصادر", false);
        srv.setOnClickListener(v -> openUrl(Api.base() + "/"));
        uc.addView(srv, lpm(this, -1, -2, 4, 8));
        TextView about = text(this, "وظيفتي — مساعد البحث عن الوظائف في سوريا\nتطوير: المهندس فراس • +963982424717", 13, MUTED, false);
        about.setGravity(Gravity.CENTER);
        about.setPadding(0, dp(this, 6), 0, 0);
        uc.addView(about);
        l.addView(uc, lp(-1, -2));
        return sv;
    }

    private void copy(String s) {
        if (s == null || s.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("code", s));
        Toast.makeText(this, "تم النسخ", Toast.LENGTH_SHORT).show();
    }

    private void restore(String code) {
        if (code.length() != 6) { Toast.makeText(this, "الرمز من 6 أرقام", Toast.LENGTH_SHORT).show(); return; }
        Context app = getApplicationContext();
        new Thread(() -> {
            String msg;
            try {
                JSONObject b = new JSONObject().put("dev", Store.dev(app)).put("code", code);
                JSONObject r = Api.post("/restore", b);
                Store.p(app).edit().putString("code", r.optString("code")).apply();
                JSONObject pr = r.optJSONObject("prefs");
                if (pr != null) Store.applyPrefs(app, pr, r.optLong("updated", System.currentTimeMillis()));
                else Store.p(app).edit().putBoolean("setup_done", true).apply();
                msg = "تم استرجاع حسابك ✅";
            } catch (Throwable e) {
                msg = "تعذّر الاسترجاع: " + e.getMessage();
            }
            final String m = msg;
            ui.post(() -> { Toast.makeText(this, m, Toast.LENGTH_LONG).show(); show(3); });
        }).start();
    }

    private void loadSources(LinearLayout list) {
        new Thread(() -> {
            JSONArray arr = null;
            String err = null;
            try { arr = Api.get("/sources?all=1").optJSONArray("sources"); } catch (Throwable e) { err = e.getMessage(); }
            final JSONArray fa = arr;
            final String fe = err;
            ui.post(() -> {
                list.removeAllViews();
                if (fa == null) { list.addView(text(this, "تعذّر التحميل: " + fe, 13, RED, false)); return; }
                for (int i = 0; i < fa.length(); i++) {
                    JSONObject s = fa.optJSONObject(i);
                    if (s != null) list.addView(sourceRow(s, list));
                }
            });
        }).start();
    }

    private View sourceRow(JSONObject s, LinearLayout list) {
        String kind = s.optString("kind"), value = s.optString("value");
        boolean on = s.optInt("enabled", 1) == 1;
        LinearLayout r = row(this);
        r.setPadding(0, dp(this, 8), 0, dp(this, 8));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        String icon = kind.equals("tg") ? "✈️" : kind.equals("manual") ? "🤝" : kind.equals("reliefweb") ? "🌐" : kind.equals("link") ? "🔗" : "🌐";
        t.addView(oneLine(text(this, icon + " " + s.optString("name", value), 14, TEXT, true)));
        String st;
        if (kind.equals("manual")) st = "فيسبوك/لينكدإن — بالمشاركة فقط";
        else if (!s.optString("last_err", "").isEmpty() && !"null".equals(s.optString("last_err"))) st = "❌ " + s.optString("last_err");
        else if (s.optLong("last_ok", 0) > 0) st = "✅ " + ago(s.optLong("last_ok")) + " • أضاف " + s.optInt("total") + " إعلاناً";
        else st = "⏳ بانتظار أول جلب";
        t.addView(oneLine(text(this, st, 12, MUTED, false)));
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        Switch sw = new Switch(this);
        sw.setChecked(on);
        sw.setOnCheckedChangeListener((b, v) -> post("/sources/toggle", jo("id", s.optInt("id"), "enabled", v), null));
        r.addView(sw);
        String link = kind.equals("tg") ? "https://t.me/" + value : kind.equals("reliefweb") ? "https://reliefweb.int/jobs?search=" + value : value;
        t.setOnClickListener(v -> openUrl(link));
        t.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this).setMessage("حذف المصدر «" + s.optString("name") + "» لكل المستخدمين؟")
                    .setPositiveButton("حذف", (d, w) -> post("/sources/delete", jo("id", s.optInt("id")), () -> loadSources(list)))
                    .setNegativeButton("إلغاء", null).show();
            return true;
        });
        return r;
    }

    private void addSource(String v, LinearLayout list, EditText in) {
        if (v.isEmpty()) return;
        post("/sources", jo("value", v, "dev", Store.dev(this)), () -> {
            in.setText("");
            Toast.makeText(this, "أُضيف المصدر ✅ يبدأ الجلب منه خلال ساعة", Toast.LENGTH_LONG).show();
            loadSources(list);
        });
    }

    private static JSONObject jo(Object... kv) {
        JSONObject j = new JSONObject();
        try { for (int i = 0; i + 1 < kv.length; i += 2) j.put(String.valueOf(kv[i]), kv[i + 1]); } catch (Exception ignored) { }
        return j;
    }

    private void post(String path, JSONObject body, Runnable after) {
        new Thread(() -> {
            String err = null;
            try { Api.post(path, body); } catch (Throwable e) { err = e.getMessage(); }
            final String fe = err;
            ui.post(() -> {
                if (fe != null) Toast.makeText(this, "خطأ: " + fe, Toast.LENGTH_LONG).show();
                else if (after != null) after.run();
            });
        }).start();
    }

    // =====================================================================
    // التحديثات
    // =====================================================================
    private void checkUpdate(boolean quiet) {
        new Thread(() -> {
            Updater.Info info = null;
            String err = null;
            try { info = Updater.fetch(); } catch (Throwable e) { err = e.getMessage(); }
            final Updater.Info fi = info;
            final String fe = err;
            ui.post(() -> {
                if (isFinishing()) return;
                if (fi == null) { if (!quiet) Toast.makeText(this, "تعذّر الفحص: " + fe, Toast.LENGTH_LONG).show(); return; }
                if (Updater.cmp(fi.version, Updater.current(this)) <= 0) {
                    if (!quiet) Toast.makeText(this, "لديك آخر إصدار ✅", Toast.LENGTH_SHORT).show();
                    return;
                }
                new AlertDialog.Builder(this).setTitle("إصدار جديد " + fi.version)
                        .setMessage(Updater.clean(fi.notes))
                        .setPositiveButton("تحميل وتثبيت", (d, w) -> download(fi))
                        .setNegativeButton("لاحقاً", null).show();
            });
        }).start();
    }

    @SuppressWarnings("deprecation")
    private void download(Updater.Info info) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "اسمح لوظيفتي بتثبيت التطبيقات ثم أعد المحاولة", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("جارٍ التحميل…");
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(100);
        pd.setCancelable(false);
        pd.show();
        Context app = getApplicationContext();
        new Thread(() -> {
            String err = null;
            try {
                File f = Updater.download(app, info.apkUrl, pct -> ui.post(() -> pd.setProgress(pct)));
                Updater.install(app, f);
            } catch (Throwable e) { err = e.getMessage(); }
            final String fe = err;
            ui.post(() -> {
                pd.dismiss();
                if (fe != null) Toast.makeText(this, "فشل التحديث: " + fe, Toast.LENGTH_LONG).show();
            });
        }).start();
    }
}
