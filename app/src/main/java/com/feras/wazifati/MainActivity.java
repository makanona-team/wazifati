package com.feras.wazifati;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
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
import android.text.TextWatcher;
import android.text.util.Linkify;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
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
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.feras.wazifati.Ui.*;

public class MainActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout content;
    private TextView status, syncIcon, bell;
    static final int T_JOBS = 0, T_TENDERS = 1, T_LEARN = 2, T_SAVED = 3, T_FILTER = 4, T_MORE = 5;
    private final TextView[] tabs = new TextView[6];
    private boolean tenderMode = false;
    private String quickCat = null;
    // مسودة الفلتر (لا تُطبّق إلا بزر التأكيد)
    private java.util.HashMap<String, Set<String>> draft = null;
    private boolean draftDirty = false;
    private TextView applyBtnLabel;
    private int tab = 0;
    private List<Store.Job> all = new ArrayList<>();
    private boolean syncing;

    private String query = "";
    private String quickGov = null;
    private boolean useFilter = true, onlyNew = false, onlyShared = false;
    private int savedMode = 0;
    private boolean sortMatch = false;
    private static final int PICK_CV = 77;
    private JobAdapter adapter;
    private TextView countLabel;

    @Override
    protected void onCreate(Bundle b) {
        Ui.apply(this);
        setTheme(Ui.themeRes());
        super.onCreate(b);
        if (Api.VER.isEmpty()) Api.VER = Updater.current(this);
        getWindow().setStatusBarColor(HEADER);
        getWindow().setNavigationBarColor(CARD);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        CrashHandler.install(this);
        Sync.init(this);
        CrashHandler.sendPending(this);
        Sync.pingInstall(this, false);
        Sync.channels(this);
        if (outdated()) { setContentView(forceScreen()); return; }
        checkMinAsync();
        if (!Sync.loggedIn(this) && !Store.guest(this)) {
            Uri link = getIntent().getData();
            if (link != null && "wazifati".equals(link.getScheme())) Store.p(this).edit().putBoolean("guest", true).apply();   // رابط مشاركة: ادخل كضيف مباشرة
            else { setContentView(loginScreen()); return; }
        }
        if (!guest()) {
            Sync.schedule(this);
            if (Build.VERSION.SDK_INT >= 33 && !Sync.canPost(this)) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        buildMain();
        all = Store.get(this).all();
        boolean setup = guest() || Store.p(this).getBoolean("setup_done", false);
        int reopen = Store.p(this).getInt("reopen_tab", -1);
        Store.p(this).edit().remove("reopen_tab").apply();
        show(!setup ? T_FILTER : reopen >= 0 ? reopen : getIntent().getIntExtra("tab", 0));
        if (getIntent().getBooleanExtra("new", false)) { onlyNew = true; show(0); }
        refresh(false);
        if (getIntent().getBooleanExtra("update", false)) checkUpdate(true);
        handleLink(getIntent());
    }

    // ===== التحديث الإجباري =====
    private boolean outdated() {
        String m = Store.p(this).getString("min_ver", "");
        return !m.isEmpty() && Updater.cmp(Updater.current(this), m) < 0;
    }

    /** يقرأ الحد الأدنى للإصدار من الخادم؛ إن صار الإصدار قديماً تُعاد البناء بشاشة الإجبار. */
    private void checkMinAsync() {
        Context app = getApplicationContext();
        new Thread(() -> {
            final String fb = flagsRaw(app);
            try {
                JSONObject r = Api.get("/api");
                Store.p(app).edit().putString("min_ver", r.optString("minVer", "")).putString("min_msg", r.optString("minMsg", "")).apply();
                if (r.has("flags")) Store.saveFlags(app, r.optJSONObject("flags"));
            } catch (Throwable ignored) { return; }
            ui.post(() -> { if (!isFinishing() && (outdated() || !fb.equals(flagsRaw(app)))) recreate(); });
        }).start();
    }

    static String flagsRaw(Context c) { return c.getSharedPreferences("w_id", Context.MODE_PRIVATE).getString("feature_flags", ""); }

    private View forceScreen() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setBackgroundColor(BG);
        l.setPadding(dp(this, 28), dp(this, 28), dp(this, 28), dp(this, 28));
        TextView e = text(this, "⬆️", 60, TEXT, false);
        e.setGravity(Gravity.CENTER);
        l.addView(e);
        TextView t = text(this, "يلزم تحديث التطبيق", 24, PRIMARY, true);
        t.setGravity(Gravity.CENTER);
        l.addView(t);
        String msg = Store.p(this).getString("min_msg", "");
        TextView m = text(this, (msg.isEmpty() ? "هذا الإصدار (" + Updater.current(this) + ") قديم ولم يعد مدعوماً." : msg) + "\nحدّث إلى أحدث إصدار للمتابعة.", 15, MUTED, false);
        m.setGravity(Gravity.CENTER);
        m.setPadding(0, dp(this, 10), 0, dp(this, 24));
        l.addView(m);
        Button up = button(this, "⬇  تحميل وتثبيت التحديث", true);
        up.setMinHeight(dp(this, 54));
        up.setOnClickListener(v -> {
            up.setEnabled(false);
            new Thread(() -> {
                Updater.Info info = null;
                String err = null;
                try { info = Updater.fetch(getApplicationContext()); } catch (Throwable x) { err = x.getMessage(); }
                final Updater.Info fi = info;
                final String fe = err;
                ui.post(() -> {
                    up.setEnabled(true);
                    if (fi == null) Toast.makeText(this, "تعذّر الاتصال: " + fe, Toast.LENGTH_LONG).show();
                    else download(fi);
                });
            }).start();
        });
        l.addView(up, lp(-1, -2));
        Button re = button(this, "🔄 ثبّتّ التحديث — فحص من جديد", false);
        re.setOnClickListener(v -> {
            new Thread(() -> {
                try {
                    JSONObject r = Api.get("/api");
                    Store.p(getApplicationContext()).edit().putString("min_ver", r.optString("minVer", "")).putString("min_msg", r.optString("minMsg", "")).apply();
                } catch (Throwable ignored) { }
                ui.post(this::recreate);
            }).start();
        });
        l.addView(re, lpm(this, -1, -2, 10, 0));
        return l;
    }

    // ===== إشعارات الأدمن (الجرس) =====
    private void showAnnouncements() {
        Dialog[] dh = new Dialog[1];
        LinearLayout l = fullDialog("الإشعارات", dh);
        Dialog d = dh[0];
        JSONArray a;
        try { a = new JSONArray(Store.p(this).getString("ann_list", "[]")); } catch (Exception e) { a = new JSONArray(); }
        long maxId = 0;
        if (Store.admin(this)) {
            Button add = button(this, "➕ إعلان جديد لكل المستخدمين", true);
            add.setOnClickListener(v -> { d.dismiss(); adminAnnounce(); });
            l.addView(add, lpm(this, -1, -2, 0, 10));
        }
        long seen = Store.p(this).getLong("ann_seen", 0);
        if (a.length() == 0) {
            TextView e = text(this, "لا توجد إشعارات.", 15, MUTED, false);
            e.setGravity(Gravity.CENTER);
            e.setPadding(0, dp(this, 40), 0, 0);
            l.addView(e);
        }
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            final long id = o.optLong("id");
            maxId = Math.max(maxId, id);
            LinearLayout c = card(this);
            TextView when = text(this, (id > seen ? "🆕 " : "") + ago(o.optLong("created")), 12, id > seen ? PRIMARY : MUTED, id > seen);
            c.addView(when);
            TextView body = text(this, o.optString("text"), 15, TEXT, false);
            body.setPadding(0, dp(this, 4), 0, 0);
            body.setTextIsSelectable(true);
            body.setAutoLinkMask(Linkify.WEB_URLS);
            body.setLinkTextColor(PRIMARY);
            c.addView(body);
            if (Store.admin(this)) {
                Button del = button(this, "🗑 حذف الإعلان", false);
                del.setTextColor(RED);
                del.setOnClickListener(v -> new AlertDialog.Builder(this).setMessage("حذف هذا الإعلان عند كل المستخدمين؟")
                        .setPositiveButton("حذف", (x, w) -> post("/admin/announce/delete", jo("id", id), r -> {
                            Context app = getApplicationContext();
                            new Thread(() -> {
                                try { Sync.syncAnnouncements(app, false); } catch (Throwable ignored) { }
                                ui.post(() -> { d.dismiss(); setStatus(); showAnnouncements(); });
                            }).start();
                        })).setNegativeButton("إلغاء", null).show());
                c.addView(del, lpm(this, -1, -2, 8, 0));
            }
            l.addView(c, lpm(this, -1, -2, 0, 8));
        }
        Store.p(this).edit().putLong("ann_seen", Math.max(seen, maxId)).apply();
        setStatus();
        d.show();
    }

    /** الأدمن: تحديد أقدم إصدار مسموح. */
    private void adminMinVer() {
        String cur = Store.p(this).getString("min_ver", "");
        EditText ver = input("مثال: 1.9  (فارغ = إلغاء الإجبار)");
        ver.setText(cur);
        ver.setSingleLine(true);
        EditText msg = input("رسالة تظهر للمستخدم (اختيارية)");
        msg.setText(Store.p(this).getString("min_msg", ""));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(this, 18), dp(this, 8), dp(this, 18), 0);
        box.addView(ver, lp(-1, -2));
        box.addView(msg, lpm(this, -1, -2, 8, 0));
        new AlertDialog.Builder(this).setTitle("⬆ تحديث إجباري")
                .setMessage("أي إصدار أقدم من الرقم المكتوب يُحجب ويُطلب منه التحديث.\nالحد الحالي: " + (cur.isEmpty() ? "غير مفعّل" : cur) + "\nإصدارك: " + Updater.current(this)
                        + "\nملاحظة: الإصدارات التي لا تحتوي هذه الميزة (قبل 1.9) لا يمكن إجبارها.")
                .setView(box)
                .setPositiveButton("حفظ", (dd, w) -> {
                    String v = ver.getText().toString().trim();
                    String m = msg.getText().toString().trim();
                    Context app = getApplicationContext();
                    new Thread(() -> {
                        String err = null;
                        try {
                            if (!v.isEmpty()) {
                                String stable = Updater.channels()[0];   // المنشورة للمستخدمين فقط، لا التجريبية
                                if (stable.isEmpty() || Updater.cmp(v, stable) > 0) throw new Exception("الحد (" + v + ") أعلى من آخر إصدار منشور للمستخدمين (" + stable + ")، سيُحجب الجميع بلا تحديث متاح. انشر النسخة للمستخدمين أولاً.");
                            }
                            Api.post("/admin/minver", jo("ver", v, "msg", m));
                            Store.p(app).edit().putString("min_ver", v).putString("min_msg", m).apply();
                        } catch (Throwable x) { err = x.getMessage(); }
                        final String fe = err;
                        ui.post(() -> Toast.makeText(this, fe != null ? "❌ " + fe : (v.isEmpty() ? "✅ أُلغي التحديث الإجباري" : "✅ أي إصدار أقدم من " + v + " سيُحجب"), Toast.LENGTH_LONG).show());
                    }).start();
                })
                .setNegativeButton("إلغاء", null).show();
    }

    /** ضيف = لا حساب (قراءة فقط). */
    private boolean guest() { return !Sync.loggedIn(this); }

    private void goLogin() {
        Store.p(this).edit().putBoolean("guest", false).apply();
        recreate();
    }

    /** رابط مشاركة: wazifati://job/<id> */
    private void handleLink(Intent i) {
        Uri d = i == null ? null : i.getData();
        if (d == null || !"wazifati".equals(d.getScheme())) return;
        i.setData(null);
        long id;
        try { id = Long.parseLong(d.getLastPathSegment()); } catch (Exception e) { return; }
        openJobById(id);
    }

    /** يفتح فرصة محلياً، أو يجلبها من الخادم إن لم تكن محفوظة. */
    private void openJobById(long id) {
        final long fid = id;
        List<Store.Job> l = Store.get(this).byIds(new HashSet<>(Arrays.asList(String.valueOf(fid))));
        if (!l.isEmpty()) { openJob(l.get(0)); return; }
        Context app = getApplicationContext();
        new Thread(() -> {
            Store.Job j = null;
            try { j = Sync.fetchOne(app, fid); } catch (Throwable ignored) { }
            final Store.Job fj = j;
            ui.post(() -> {
                if (fj != null) openJob(fj);
                else Toast.makeText(this, "هذه الفرصة لم تعد متاحة", Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        if (content == null) return;
        handleLink(i);
        if (i.getBooleanExtra("new", false)) { onlyNew = true; show(0); refresh(false); }
        else if (i.hasExtra("tab")) show(i.getIntExtra("tab", 0));
        if (i.getBooleanExtra("update", false)) checkUpdate(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (Sync.loggedIn(this) && Store.p(this).getLong("prefs_updated", 0) > Store.p(this).getLong("prefs_pushed", 0)) Sync.pushPrefs(this);
    }

    @Override
    public void onBackPressed() {
        if (content != null && tab != 0) show(0);
        else super.onBackPressed();
    }

    // =====================================================================
    // تسجيل الدخول
    // =====================================================================
    private View loginScreen() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setBackgroundColor(BG);
        l.setPadding(dp(this, 28), dp(this, 28), dp(this, 28), dp(this, 28));
        TextView logo = text(this, "💼", 44, TEXT, false);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(round(this, PRIMARY_LIGHT, 40));
        l.addView(logo, new LinearLayout.LayoutParams(dp(this, 84), dp(this, 84)));
        TextView t = text(this, "وظيفتي", 30, PRIMARY, true);
        t.setGravity(Gravity.CENTER);
        l.addView(t, lpm(this, -2, -2, 10, 0));
        TextView s = text(this, "فرص العمل والمناقصات في سوريا في مكان واحد", 15, MUTED, false);
        s.setGravity(Gravity.CENTER);
        s.setPadding(0, dp(this, 4), 0, dp(this, 18));
        l.addView(s);
        LinearLayout feats = card(this);
        String[] ft = {"🔎  تصنيف تلقائي حسب اختصاصك ومحافظتك", "🔔  إشعار فوري بما يناسبك", "⭐  احفظ فرصك وتابع تقديماتك"};
        for (String x : ft) feats.addView(text(this, x, 14, TEXT, false), lpm(this, -1, -2, 3, 3));
        l.addView(feats, lpm(this, -1, -2, 0, 22));
        Button g = button(this, "G   الدخول بحساب Google", true);
        g.setTextSize(17);
        g.setMinHeight(dp(this, 54));
        l.addView(g, lp(-1, -2));
        Button gu = button(this, "👀  تصفّح كضيف بدون حساب", false);
        gu.setMinHeight(dp(this, 50));
        gu.setOnClickListener(v -> { Store.p(this).edit().putBoolean("guest", true).apply(); Sync.pingInstall(this, true); recreate(); });
        if (Store.flag(this, "guest_mode")) l.addView(gu, lpm(this, -1, -2, 10, 0));
        TextView note = text(this, "بلا كلمة سر — اختر حسابك بضغطة واحدة، وتعود محفوظاتك على أي هاتف.", 12, MUTED, false);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, dp(this, 16), 0, 0);
        l.addView(note);
        g.setOnClickListener(v -> {
            g.setEnabled(false);
            g.setText("جارٍ الدخول…");
            GoogleLogin.signIn(this, new GoogleLogin.Cb() {
                @Override public void ok(String idToken) { finishLogin(idToken, g); }
                @Override public void err(String msg) {
                    ui.post(() -> {
                        g.setEnabled(true);
                        g.setText("G   الدخول بحساب Google");
                        if (msg != null && !msg.isEmpty()) {
                            if (msg.length() > 80) new AlertDialog.Builder(MainActivity.this).setTitle("تعذّر تسجيل الدخول").setMessage(msg).setPositiveButton("حسناً", null).show();
                            else Toast.makeText(MainActivity.this, "تعذّر الدخول: " + msg, Toast.LENGTH_LONG).show();
                        }
                    });
                }
            });
        });
        return l;
    }

    private void finishLogin(String idToken, Button g) {
        Context app = getApplicationContext();
        new Thread(() -> {
            String err = null;
            try {
                Api.token = "";
                JSONObject r = Api.post("/auth", new JSONObject().put("idToken", idToken).put("dev", Store.dev(app)));
                Store.p(app).edit().putString("token", r.optString("token")).apply();
                Api.token = r.optString("token");
                JSONObject me = r.optJSONObject("me");
                if (me != null) {
                    Store.applyMe(app, me);
                    JSONObject pr = me.optJSONObject("prefs");
                    if (pr != null) Store.applyPrefs(app, pr, me.optLong("updated", System.currentTimeMillis()));
                }
                Sync.pingInstall(app, true);
            } catch (Throwable e) { err = e.getMessage(); CrashHandler.reportAuthFail(app, "server_auth", err); }
            final String fe = err;
            ui.post(() -> {
                if (fe != null) {
                    g.setEnabled(true);
                    g.setText("G   الدخول بحساب Google");
                    Toast.makeText(this, "تعذّر الدخول: " + fe, Toast.LENGTH_LONG).show();
                } else recreate();
            });
        }).start();
    }

    private void signedOut(String msg) {
        Store.logout(this);
        Api.token = "";
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        recreate();
    }

    // =====================================================================
    // الهيكل
    // =====================================================================
    private void buildMain() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        LinearLayout head = row(this);
        head.setBackgroundColor(HEADER);
        head.setPadding(dp(this, 14), dp(this, 8), dp(this, 6), dp(this, 8));
        LinearLayout ht = new LinearLayout(this);
        ht.setOrientation(LinearLayout.VERTICAL);
        ht.addView(text(this, "💼 وظيفتي", 20, Color.WHITE, true));
        status = oneLine(text(this, "", 11, 0xCCFFFFFF, false));
        ht.addView(status);
        head.addView(ht, new LinearLayout.LayoutParams(0, -2, 1));

        if (guest()) {
            TextView in = headBtn("🔑", "دخول");
            in.setOnClickListener(v -> goLogin());
            head.addView(in);
        }
        bell = headBtn("🔔", "إشعارات");
        bell.setOnClickListener(v -> showAnnouncements());
        head.addView(bell);
        TextView theme = headBtn("🌓", "المظهر");
        theme.setOnClickListener(v -> {
            String cur = Store.p(this).getString("theme", "auto");
            String next = cur.equals("auto") ? "light" : cur.equals("light") ? "dark" : "auto";
            Store.p(this).edit().putString("theme", next).putInt("reopen_tab", tab).apply();
            Toast.makeText(this, next.equals("auto") ? "المظهر: تلقائي حسب الهاتف" : next.equals("light") ? "المظهر: نهاري" : "المظهر: ليلي", Toast.LENGTH_SHORT).show();
            recreate();
        });
        head.addView(theme);
        syncIcon = headBtn("🔄", "مزامنة");
        syncIcon.setOnClickListener(v -> refresh(true));
        head.addView(syncIcon);
        root.addView(head);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout nav = row(this);
        nav.setBackgroundColor(CARD);
        nav.setElevation(dp(this, 8));
        String[] names = {"🔎\nالفرص", "📑\nالمناقصات", "🎓\nتعلّم", "⭐\nالمحفوظة", "🎯\nفلتري", "☰\nالمزيد"};
        for (int i = 0; i < 6; i++) {
            final int k = i;
            TextView t = text(this, names[i], 12, MUTED, false);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(this, 6), 0, dp(this, 6));
            t.setOnClickListener(v -> show(k));
            tabs[i] = t;
            if (i == T_TENDERS && !Store.flag(this, "tenders")) t.setVisibility(View.GONE);
            if (i == T_LEARN && !Store.flag(this, "learn_tab")) t.setVisibility(View.GONE);
            nav.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        }
        root.addView(nav);
        setContentView(root);
    }

    private TextView headBtn(String icon, String label) {
        TextView t = text(this, "", 10, 0xE6FFFFFF, false);
        t.setText(headLabel(icon, label));
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(this, 8), dp(this, 2), dp(this, 8), dp(this, 2));
        return t;
    }

    private CharSequence headLabel(String icon, String label) {
        android.text.SpannableString sp = new android.text.SpannableString(icon + "\n" + label);
        sp.setSpan(new android.text.style.RelativeSizeSpan(1.9f), 0, icon.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sp;
    }

    private void setStatus() {
        if (status == null) return;
        if (bell != null) { int un = Sync.annUnread(this); bell.setText(headLabel(un > 0 ? "🔔" + un : "🔔", "إشعارات")); bell.setTextColor(un > 0 ? 0xFFFFD54F : Color.WHITE); }
        long ls = Store.p(this).getLong("last_sync", 0);
        status.setText(Ui.today() + " • " + (syncing ? "جارٍ المزامنة…" : (all.size() + " فرصة • آخر مزامنة " + (ls == 0 ? "—" : ago(ls)))));
        int pend = Store.admin(this) ? Store.p(this).getInt("pending_sources", 0) + Store.p(this).getInt("pending_fixes", 0) + Store.p(this).getInt("pending_flags", 0) + Store.p(this).getInt("pending_edits", 0) + Store.p(this).getInt("pending_reports", 0) : 0;
        tabs[T_MORE].setText(pend > 0 ? "☰ " + pend + "🔴\nالمزيد" : "☰\nالمزيد");
    }

    private void refresh(boolean manual) {
        if (syncing) return;
        syncing = true;
        if (syncIcon != null) syncIcon.setAlpha(0.5f);
        setStatus();
        Context app = getApplicationContext();
        new Thread(() -> {
            String err = null;
            boolean auth = false;
            int n = 0;
            boolean wasSetup = Store.p(app).getBoolean("setup_done", false);
            final String flagsBefore = flagsRaw(app);
            try { n = Sync.run(app, false); }
            catch (Api.AuthException e) { auth = true; err = e.getMessage(); }
            catch (Throwable e) { err = e.getMessage(); }
            final Updater.Info upd = Updater.refreshLatest(app, manual) == null ? null : Updater.newer(app);
            final String fe = err;
            final boolean fa = auth;
            final int fn = n;
            List<Store.Job> l = fa ? new ArrayList<>() : Store.get(app).all();
            ui.post(() -> {
                syncing = false;
                if (syncIcon != null) syncIcon.setAlpha(1f);
                if (outdated() || !flagsBefore.equals(flagsRaw(app))) { recreate(); return; }   // تغيّر مفتاح ميزة ← أعد بناء الواجهة (إخفاء/إظهار التبويبات)
                if (fa) { signedOut(fe == null ? "سجّل الدخول من جديد" : fe); return; }
                all = l;
                setStatus();
                if (fe != null && manual) Toast.makeText(this, "تعذّرت المزامنة: " + fe, Toast.LENGTH_LONG).show();
                else if (manual) Toast.makeText(this, fn > 0 ? "✅ " + fn + " فرصة جديدة تناسبك" : "✅ تمت المزامنة — لا جديد الآن", Toast.LENGTH_SHORT).show();
                if (!wasSetup && Store.p(this).getBoolean("setup_done", false) && tab == T_FILTER) show(T_JOBS);
                else if (tab != T_FILTER) show(tab);
                maybeNagPower();
                if (manual && upd != null) showUpdateDialog(upd);
            });
        }).start();
    }

    private void show(int k) {
        if (tab == T_FILTER && k != T_FILTER && draftDirty && content != null && content.getChildCount() > 0) {
            new AlertDialog.Builder(this).setMessage("لديك تغييرات على الفلتر لم تُطبّق. تطبيقها؟")
                    .setPositiveButton("تطبيق", (d, w) -> { applyDraft(); show(k); })
                    .setNegativeButton("تجاهل", (d, w) -> { draft = null; draftDirty = false; show(k); })
                    .setNeutralButton("رجوع", null).show();
            return;
        }
        if (k == T_TENDERS && !Store.flag(this, "tenders")) { show(T_JOBS); return; }
        if (k == T_LEARN && !Store.flag(this, "learn_tab")) { show(T_JOBS); return; }
        if (k != T_FILTER) { draft = null; draftDirty = false; }
        tab = k;
        for (int i = 0; i < 6; i++) {
            tabs[i].setTextColor(i == k ? PRIMARY : MUTED);
            tabs[i].setTypeface(i == k ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        content.removeAllViews();
        hideKeyboard();
        setStatus();
        if (k == T_JOBS) content.addView(jobsScreen(false));
        else if (k == T_TENDERS) content.addView(jobsScreen(true));
        else if (k == T_LEARN) content.addView(learnScreen());
        else if (k == T_SAVED) content.addView(guest() ? gateScreen("⭐ الفرص المحفوظة", "احفظ الفرص التي تهمك، وسنذكّرك قبل يوم من إغلاق التقديم، وتتابع ما قدّمت عليه.", false) : savedScreen());
        else if (k == T_FILTER) content.addView(guest() ? gateScreen("🎯 فلتري والإشعارات", "اختر اختصاصك ومحافظتك ليصلك إشعار بكل فرصة تناسبك، وارفع سيرتك الذاتية لتعرف نسبة مناسبتك لكل وظيفة.", false) : filterScreen());
        else content.addView(guest() ? guestMore() : moreScreen());
    }

    /** «المزيد» للزائر: جدول يشرح الفرق بين الزائر والمسجّل. */
    private View guestMore() {
        ScrollView sv = new ScrollView(this);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(this, 12), dp(this, 12), dp(this, 12), dp(this, 20));
        sv.addView(l);
        int lj = Store.p(this).getInt("g_lim_j", 10), lt = Store.p(this).getInt("g_lim_t", 2);
        l.addView(text(this, "الفرق بين الزائر والمسجّل", 19, TEXT, true));
        TextView sub = text(this, "التسجيل مجاني، بضغطة واحدة بحساب Google، بلا كلمة سر.", 13, MUTED, false);
        sub.setPadding(0, dp(this, 4), 0, dp(this, 10));
        l.addView(sub);
        String[][] rows = {
                {"الميزة", "👀 زائر", "✅ مسجّل"},
                {"الفرص المعروضة", "أحدث " + lj, "كل الفرص"},
                {"المناقصات المعروضة", "أحدث " + lt, "كل المناقصات"},
                {"البحث والتصفية السريعة", "❌", "✅"},
                {"رابط التقديم والإعلان الأصلي", "✅", "✅"},
                {"مشاركة الفرصة", "✅", "✅"},
                {"حفظ الفرص وتذكير قبل الإغلاق", "❌", "✅"},
                {"فلتري وإشعارات الفرص الجديدة", "❌", "✅"},
                {"السيرة الذاتية ونسبة المناسبة", "❌", "✅"},
                {"استعادة إعداداتك على أي هاتف", "❌", "✅"},
                {"الإبلاغ وتصحيح التصنيف واقتراح مصادر", "❌", "✅"},
        };
        LinearLayout tbl = card(this);
        for (int i = 0; i < rows.length; i++) {
            LinearLayout r = row(this);
            r.setPadding(0, dp(this, 8), 0, dp(this, 8));
            boolean head = i == 0;
            TextView a = text(this, rows[i][0], head ? 13 : 14, head ? MUTED : TEXT, head);
            TextView b = text(this, rows[i][1], head ? 13 : 14, head ? MUTED : TEXT, head);
            TextView c = text(this, rows[i][2], head ? 13 : 14, head ? MUTED : TEXT, head);
            b.setGravity(Gravity.CENTER);
            c.setGravity(Gravity.CENTER);
            r.addView(a, new LinearLayout.LayoutParams(0, -2, 5));
            r.addView(b, new LinearLayout.LayoutParams(0, -2, 2));
            r.addView(c, new LinearLayout.LayoutParams(0, -2, 2));
            tbl.addView(r);
            if (i < rows.length - 1) {
                View line = new View(this);
                line.setBackgroundColor(LINE);
                tbl.addView(line, new LinearLayout.LayoutParams(-1, dp(this, 1)));
            }
        }
        l.addView(tbl, lp(-1, -2));
        Button in = button(this, "G   سجّل الدخول بحساب Google", true);
        in.setOnClickListener(v -> goLogin());
        l.addView(in, lpm(this, -1, -2, 14, 0));
        Button up = button(this, "🔄 البحث عن تحديث للتطبيق", false);
        up.setOnClickListener(v -> checkUpdate(false));
        l.addView(up, lpm(this, -1, -2, 8, 0));
        return sv;
    }

    /** شاشة «للمسجّلين فقط» في وضع الضيف. */
    private View gateScreen(String title, String msg, boolean withUpdate) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setPadding(dp(this, 28), dp(this, 20), dp(this, 28), dp(this, 20));
        TextView lock = text(this, "🔒", 48, TEXT, false);
        lock.setGravity(Gravity.CENTER);
        l.addView(lock);
        TextView t = text(this, title, 19, TEXT, true);
        t.setGravity(Gravity.CENTER);
        l.addView(t);
        TextView m = text(this, msg, 14, MUTED, false);
        m.setGravity(Gravity.CENTER);
        m.setPadding(0, dp(this, 8), 0, dp(this, 18));
        l.addView(m);
        Button in = button(this, "G   الدخول بحساب Google", true);
        in.setOnClickListener(v -> goLogin());
        l.addView(in, lp(-1, -2));
        if (withUpdate) {
            Button up = button(this, "🔄 البحث عن تحديث للتطبيق", false);
            up.setOnClickListener(v -> checkUpdate(false));
            l.addView(up, lpm(this, -1, -2, 10, 0));
        }
        return l;
    }

    private void hideKeyboard() {
        InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null && getCurrentFocus() != null) im.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
    }

    private EditText input(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextColor(TEXT);
        e.setHintTextColor(MUTED);
        e.setTextSize(15);
        e.setBackground(outline(this, CARD, LINE, 12));
        e.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
        return e;
    }

    // =====================================================================
    // شاشة الفرص
    // =====================================================================
    private View jobsScreen(boolean tenders) {
        if (tenderMode != tenders) { quickGov = null; quickCat = null; }
        tenderMode = tenders;
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(this, 10), dp(this, 8), dp(this, 10), 0);

        l.addView(welcomeCard(), lpm(this, -1, -2, 0, 8));
        l.addView(updateBanner(), lpm(this, -1, -2, 0, 8));

        if (guest()) {
            LinearLayout gb = row(this);
            gb.setBackground(round(this, PRIMARY_LIGHT, 12));
            gb.setPadding(dp(this, 12), dp(this, 8), dp(this, 8), dp(this, 8));
            int lj = Store.p(this).getInt("g_lim_j", 10), lt = Store.p(this).getInt("g_lim_t", 2);
            gb.addView(text(this, "👀 تتصفّح كضيف: تظهر لك أحدث " + lj + " فرص و" + lt + " مناقصتين فقط. سجّل الدخول لترى الكل وتفعّل الإشعارات.", 13, TEXT, false), new LinearLayout.LayoutParams(0, -2, 1));
            TextView go = text(this, "دخول", 14, PRIMARY, true);
            go.setPadding(dp(this, 12), dp(this, 6), dp(this, 8), dp(this, 6));
            go.setOnClickListener(v -> goLogin());
            gb.addView(go);
            l.addView(gb, lpm(this, -1, -2, 0, 8));
        }

        if (!guest() && staleSync()) {
            LinearLayout sb = row(this);
            sb.setBackground(round(this, WARN_BG, 12));
            sb.setPadding(dp(this, 12), dp(this, 8), dp(this, 8), dp(this, 8));
            TextView stx = text(this, "⚠ لم تعمل المزامنة في الخلفية منذ مدة، فقد تتأخر إشعاراتك. اضغط لإصلاح ذلك.", 13, TEXT, false);
            stx.setOnClickListener(v -> powerDialog());
            sb.addView(stx, new LinearLayout.LayoutParams(0, -2, 1));
            TextView sx = text(this, "✕", 18, MUTED, true);
            sx.setPadding(dp(this, 12), dp(this, 4), dp(this, 8), dp(this, 4));
            sx.setOnClickListener(v -> { Store.p(this).edit().putLong("pw_stale_hide", System.currentTimeMillis()).apply(); show(tab); });
            sb.addView(sx);
            l.addView(sb, lpm(this, -1, -2, 0, 8));
        }

        LinearLayout sr = row(this);
        EditText search = input(tenders ? "🔍 ابحث في المناقصات: توريد، ترميم، مولدات، الحسكة…" : "🔍 ابحث بأي كلمة: مهندس، محاسب، UNICEF، الرقة…");
        search.setText(query);
        search.setSingleLine(true);
        sr.addView(search, new LinearLayout.LayoutParams(0, -2, 1));
        TextView clr = text(this, "✕", 20, MUTED, true);
        clr.setPadding(dp(this, 12), dp(this, 6), dp(this, 6), dp(this, 6));
        clr.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
        clr.setOnClickListener(v -> { search.setText(""); hideKeyboard(); });
        sr.addView(clr);
        TextView bell = text(this, "🔔", 19, MUTED, true);
        bell.setPadding(dp(this, 8), dp(this, 6), dp(this, 6), dp(this, 6));
        Runnable bellUi = () -> {
            boolean on = Store.savedSearches(this).contains(query);
            bell.setText(on ? "🔔✓" : "🔔");
            bell.setTextColor(on ? PRIMARY : MUTED);
            bell.setVisibility(query.isEmpty() || tenders || guest() ? View.GONE : View.VISIBLE);
        };
        bell.setOnClickListener(v -> {
            List<String> ssq = Store.savedSearches(this);
            if (ssq.remove(query)) Toast.makeText(this, "أُلغي إشعار البحث «" + query + "»", Toast.LENGTH_SHORT).show();
            else {
                if (ssq.size() >= 10) { Toast.makeText(this, "الحد 10 عمليات بحث محفوظة؛ احذف بعضها من «فلتري»", Toast.LENGTH_LONG).show(); return; }
                ssq.add(query);
                Toast.makeText(this, "🔔 سنُشعرك بكل فرصة جديدة تطابق «" + query + "» حتى لو كانت خارج فلترك", Toast.LENGTH_LONG).show();
            }
            Store.putSavedSearches(this, ssq);
            bellUi.run();
        });
        sr.addView(bell);
        bellUi.run();
        l.addView(sr, lp(-1, -2));

        HorizontalScrollView gs = new HorizontalScrollView(this);
        gs.setHorizontalScrollBarEnabled(false);
        LinearLayout gr = row(this);
        gr.setPadding(0, dp(this, 8), 0, 0);
        LinkedHashSet<String> quick = new LinkedHashSet<>(Store.set(this, "govs"));
        quick.remove("unknown"); quick.remove("all"); quick.remove("abroad");
        if (quick.isEmpty()) quick.addAll(Arrays.asList("deirezzor", "raqqa", "hasakah", "aleppo", "damascus", "remote"));
        ArrayList<TextView> gchips = new ArrayList<>();
        ArrayList<String> gkeys = new ArrayList<>();
        gkeys.add(null);
        gkeys.addAll(quick);
        for (String key : gkeys) {
            TextView t = chip(this, key == null ? "📍 كل المحافظات" : Labels.GOVS.get(key), key == null ? quickGov == null : key.equals(quickGov));
            LinearLayout.LayoutParams p = lp(-2, -2);
            p.leftMargin = dp(this, 6);
            gr.addView(t, p);
            gchips.add(t);
            t.setOnClickListener(v -> {
                quickGov = key;
                for (int i = 0; i < gchips.size(); i++) setChip(this, gchips.get(i), gkeys.get(i) == null ? quickGov == null : gkeys.get(i).equals(quickGov));
                applyList();
            });
        }
        gs.addView(gr);
        if (!guest()) l.addView(gs);

        if (tenders) {   // تصنيفات المناقصات السريعة
            HorizontalScrollView cs = new HorizontalScrollView(this);
            cs.setHorizontalScrollBarEnabled(false);
            LinearLayout cr = row(this);
            cr.setPadding(0, dp(this, 6), 0, 0);
            ArrayList<TextView> cchips = new ArrayList<>();
            ArrayList<String> ckeys = new ArrayList<>();
            ckeys.add(null);
            ckeys.addAll(Labels.TCATS.keySet());
            for (String key : ckeys) {
                TextView t = chip(this, key == null ? "📑 كل التصنيفات" : Labels.TCATS.get(key), key == null ? quickCat == null : key.equals(quickCat));
                LinearLayout.LayoutParams p = lp(-2, -2);
                p.leftMargin = dp(this, 6);
                cr.addView(t, p);
                cchips.add(t);
                t.setOnClickListener(v -> {
                    quickCat = key;
                    for (int i = 0; i < cchips.size(); i++) setChip(this, cchips.get(i), ckeys.get(i) == null ? quickCat == null : ckeys.get(i).equals(quickCat));
                    applyList();
                });
            }
            cs.addView(cr);
            if (guest()) cs.setVisibility(View.GONE);
            l.addView(cs);
        }

        boolean hasFilter = tenders ? !Store.TFilter.of(this).isEmpty() : !Store.Filter.of(this).isEmpty();
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = row(this);
        chips.setPadding(0, dp(this, 6), 0, dp(this, 6));
        TextView cFilter = chip(this, "🎯 حسب فلتري", useFilter && hasFilter);
        TextView cNew = chip(this, "🆕 الجديد", onlyNew);
        TextView cExp = chip(this, "⏳ إخفاء المنتهي", Store.bool(this, "hide_expired"));
        TextView cShared = chip(this, "🤝 مشاركات الأصدقاء", onlyShared);
        TextView cRead = chip(this, "✔ تعليم الكل كمقروء", false);
        TextView cSort = chip(this, sortLabel(), true);
        cSort.setOnClickListener(v -> {
            String cur = sortMode();
            boolean canMatch = !tenderMode && Match.enabled(this);
            String next = cur.equals("recent") ? "deadline" : cur.equals("deadline") ? (canMatch ? "match" : "recent") : "recent";
            Store.p(this).edit().putString("sort", next).apply();
            cSort.setText(sortLabel());
            applyList();
        });
        if (tenders) cShared.setVisibility(View.GONE);
        if (guest()) cFilter.setVisibility(View.GONE);
        for (TextView t : new TextView[]{cSort, cFilter, cNew, cExp, cShared, cRead}) {
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
        int locked = Store.p(this).getInt(tenders ? "g_lock_t" : "g_lock_j", 0);
        if (guest() && locked > 0) {
            LinearLayout lk = card(this);
            lk.setGravity(Gravity.CENTER);
            TextView lt = text(this, "🔒 +" + locked + (tenders ? " مناقصة أخرى" : " فرصة أخرى") + " بانتظارك", 17, TEXT, true);
            lt.setGravity(Gravity.CENTER);
            lk.addView(lt);
            TextView lm = text(this, "سجّل الدخول بضغطة واحدة لتفتح كل الفرص، وتصلك إشعارات بما يناسب اختصاصك ومحافظتك.", 13, MUTED, false);
            lm.setGravity(Gravity.CENTER);
            lm.setPadding(0, dp(this, 6), 0, dp(this, 10));
            lk.addView(lm);
            Button lb = button(this, "G   الدخول بحساب Google", true);
            lb.setOnClickListener(v -> goLogin());
            lk.addView(lb, lp(-1, -2));
            lv.addFooterView(lk, null, false);
        }
        lv.setAdapter(adapter);
        lv.setOnItemClickListener((p, v, pos, id) -> { if (pos < adapter.items.size()) openJob(adapter.items.get(pos)); });
        lv.setEmptyView(emptyView(l));
        l.addView(lv, new LinearLayout.LayoutParams(-1, 0, 1));

        cFilter.setOnClickListener(v -> {
            if (!hasFilter) { Toast.makeText(this, tenders ? "لم تحدد فلتر المناقصات بعد — من تبويب «فلتري»" : "لم تحدد فلتراً بعد — من تبويب «فلتري»", Toast.LENGTH_SHORT).show(); return; }
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
            public void afterTextChanged(Editable s) { query = s.toString().trim(); clr.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE); bellUi.run(); applyList(); }
        });
        applyList();
        return l;
    }

    // ===== 2.9: التقديم السريع — يلتقط الإيميل والواتساب والهاتف ونماذج التقديم من نص الإعلان =====
    private String applyMessage(Store.Job j) {
        String name = Store.p(this).getString("uname", "").trim();
        return "السلام عليكم،\n" +
                "أرغب بالتقدّم لشغل وظيفة «" + (j.title.isEmpty() ? "المعلن عنها" : j.title) + "»" + (j.org.isEmpty() ? "" : " لدى " + j.org) + ".\n" +
                "أرفقت سيرتي الذاتية، وأنا جاهز لأي استفسار أو مقابلة.\n" +
                "مع الشكر" + (name.isEmpty() ? "" : "،\n" + name);
    }

    private View quickApply(Store.Job j) {
        String t = j.text == null ? "" : j.text;
        StringBuilder sb = new StringBuilder();
        for (char ch : t.toCharArray()) sb.append(ch >= '٠' && ch <= '٩' ? (char) ('0' + (ch - '٠')) : ch >= '۰' && ch <= '۹' ? (char) ('0' + (ch - '۰')) : ch);
        String tn = sb.toString();
        LinkedHashSet<String> mails = new LinkedHashSet<>(), was = new LinkedHashSet<>(), forms = new LinkedHashSet<>(), phones = new LinkedHashSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+").matcher(tn);
        while (m.find() && mails.size() < 2) mails.add(m.group().replaceAll("[.]+$", ""));
        m = java.util.regex.Pattern.compile("https?://[^\\s<>\"'()\\]]+").matcher(tn);
        while (m.find()) {
            String u = m.group().replaceAll("[.,،؛;:!?)]+$", "");
            if (u.matches("(?i).*(wa\\.me/|api\\.whatsapp\\.com|whatsapp\\.com/send).*")) was.add(u);
            else if (u.matches("(?i).*(docs\\.google\\.com/forms|forms\\.gle|kobotoolbox|forms\\.office\\.com|jotform|typeform|airtable\\.com|surveymonkey|forms\\.zoho).*")) forms.add(u);
        }
        m = java.util.regex.Pattern.compile("(?<![\\d/=])(?:\\+|00)?\\d[\\d \\-]{8,16}\\d(?![\\d/])").matcher(tn);
        while (m.find() && phones.size() < 2) {
            String d = m.group().replaceAll("[^\\d+]", "");
            boolean intl = d.startsWith("+") || d.startsWith("00");
            if (d.startsWith("00")) d = d.substring(2);
            if (d.startsWith("+")) d = d.substring(1);
            if (d.startsWith("09") && d.length() == 10) d = "963" + d.substring(1);
            else if (d.startsWith("05") && d.length() == 11) d = "90" + d.substring(1);
            if ((d.startsWith("9639") && d.length() == 12) || (d.startsWith("905") && d.length() == 12) || (intl && d.length() >= 10 && d.length() <= 13)) phones.add(d);
        }
        boolean waHint = tn.matches("(?is).*(واتس|وتس|whatsapp|wa\\.me).*");
        if (mails.isEmpty() && was.isEmpty() && forms.isEmpty() && phones.isEmpty()) return null;
        String msg = applyMessage(j);
        LinearLayout c = card(this);
        c.addView(text(this, "⚡ تقديم سريع", 15, TEXT, true));
        c.addView(text(this, "التقطنا طرق التواصل من نص الإعلان، مع رسالة جاهزة يمكنك تعديلها. تأكد من إرفاق سيرتك الذاتية.", 12, MUTED, false), lpm(this, -1, -2, 2, 6));
        for (String e : mails) {
            Button b = button(this, "✉️ إيميل: " + e, true);
            b.setOnClickListener(v -> {
                try {
                    Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + e + "?subject=" + Uri.encode("طلب توظيف: " + j.title) + "&body=" + Uri.encode(msg)));
                    startActivity(i);
                    Toast.makeText(this, "📎 لا تنسَ إرفاق سيرتك الذاتية", Toast.LENGTH_LONG).show();
                } catch (Exception ex) { Toast.makeText(this, "لا يوجد تطبيق بريد", Toast.LENGTH_SHORT).show(); }
            });
            c.addView(b, lpm(this, -1, -2, 0, 6));
        }
        for (String u : was) {
            Button b = button(this, "💬 واتساب (رابط الإعلان)", true);
            b.setOnClickListener(v -> openUrl(u + (u.contains("?") ? "&" : "?") + "text=" + Uri.encode(msg)));
            c.addView(b, lpm(this, -1, -2, 0, 6));
        }
        for (String ph : phones) {
            LinearLayout r = row(this);
            Button w = button(this, "💬 واتساب +" + ph, waHint);
            w.setOnClickListener(v -> openUrl("https://wa.me/" + ph + "?text=" + Uri.encode(msg)));
            Button call = button(this, "📞 اتصال", false);
            call.setOnClickListener(v -> { try { startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:+" + ph))); } catch (Exception ignored) { } });
            r.addView(w, weight());
            r.addView(call, weight());
            c.addView(r, lpm(this, -1, -2, 0, 6));
        }
        for (String u : forms) {
            Button b = button(this, "📋 فتح نموذج التقديم", true);
            b.setOnClickListener(v -> openUrl(u));
            c.addView(b, lpm(this, -1, -2, 0, 6));
        }
        Button cp = button(this, "📋 نسخ رسالة التقديم", false);
        cp.setOnClickListener(v -> {
            ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("رسالة التقديم", msg));
            Toast.makeText(this, "نُسخت الرسالة", Toast.LENGTH_SHORT).show();
        });
        c.addView(cp);
        return c;
    }

    /** إغلاق نافذة بأمان حتى لو أُعيد إنشاء الشاشة (تدوير، خروج ورجوع). */
    private void safeDismiss(android.app.Dialog d) {
        try { if (d != null && d.isShowing() && !isFinishing() && !isDestroyed()) d.dismiss(); } catch (Exception ignored) { }
    }

    private TextView emptyView(LinearLayout parent) {
        TextView e = text(this, "", 15, MUTED, false);
        e.setGravity(Gravity.CENTER);
        e.setPadding(dp(this, 24), dp(this, 40), dp(this, 24), dp(this, 40));
        e.setText(all.isEmpty()
                ? (syncing ? "جارٍ جلب الفرص لأول مرة…" : "لا توجد فرص محفوظة بعد.\nاضغط 🔄 مزامنة في الأعلى.")
                : (tenderMode ? "لا توجد مناقصات تطابق البحث أو الفلتر الحالي." : "لا توجد فرص تطابق البحث أو الفلتر الحالي."));
        parent.addView(e, lp(-1, -2));
        e.setVisibility(View.GONE);
        return e;
    }

    private void applyList() {
        if (adapter == null || countLabel == null) return;
        Store.Filter f = Store.Filter.of(this);
        Store.TFilter tf = Store.TFilter.of(this);
        boolean useF = useFilter && (tenderMode ? !tf.isEmpty() : !f.isEmpty());
        boolean hideExp = Store.bool(this, "hide_expired");
        boolean hideDoubt = Store.bool(this, "hide_doubt");
        Set<String> hidden = Store.set(this, "hidden");
        Search.Query sq = Search.parse(query);
        Match.Profile prof = !tenderMode && Match.enabled(this) ? Match.profile(this) : null;
        ArrayList<Store.Job> out = new ArrayList<>();
        for (Store.Job j : all) {
            if (j.tender() != tenderMode) continue;
            if (hidden.contains(String.valueOf(j.id))) continue;
            if (tenderMode) {
                if (quickCat != null && !quickCat.equals(j.tcat.isEmpty() ? "other" : j.tcat)) continue;
                if (quickGov != null) {
                    Set<String> g = new HashSet<>(Arrays.asList(j.govs.split(",")));
                    if (!g.contains(quickGov) && !g.contains("all")) continue;
                } else if (useF && !tf.match(j)) continue;
            } else if (quickGov != null) {
                Set<String> g = new HashSet<>(Arrays.asList(j.govs.split(",")));
                if (!g.contains(quickGov) && !g.contains("all")) continue;
                if (useF && !f.matchNoGov(j)) continue;
            } else if (useF && !f.match(j)) continue;
            if (hideExp && Store.expired(j)) continue;
            if (hideDoubt && j.doubt == 1) continue;
            if (onlyNew && j.seen != 0) continue;
            if (onlyShared && j.shared == 0) continue;
            if (!sq.isEmpty()) {
                if (j.hay == null) j.hay = Search.hay(j);
                if (!sq.matches(j.hay)) continue;
            }
            if (prof != null && j.match < 0) j.match = Match.score(this, j, prof).pct;
            out.add(j);
        }
        String sm = sortMode();
        if (sm.equals("match") && prof != null) out.sort((x, y) -> y.match != x.match ? y.match - x.match : Long.compare(y.posted, x.posted));
        else if (sm.equals("deadline")) out.sort((x, y) -> {
            int dx = deadlineRank(x), dy = deadlineRank(y);
            return dx != dy ? Integer.compare(dx, dy) : Long.compare(y.posted, x.posted);
        });
        adapter.q = sq;
        adapter.set(out);
        if (!sq.isEmpty()) countLabel.setText("🔎 " + out.size() + " نتيجة لـ «" + query + "»" + (useF ? " حسب فلترك" : "") + (quickGov != null ? " في " + Labels.GOVS.get(quickGov) : "") + " • من أصل " + all.size() + " فرصة");
        else countLabel.setText(out.size() + (tenderMode ? " مناقصة" : " فرصة") + (useF ? " حسب فلترك" : "") + (quickGov != null ? " في " + Labels.GOVS.get(quickGov) : "") + (all.size() != out.size() ? " من أصل " + all.size() : ""));
    }

    private String sortMode() {
        String m = Store.p(this).getString("sort", "recent");
        if (m.equals("match") && (tenderMode || !Match.enabled(this))) return "recent";
        return m;
    }

    private String sortLabel() {
        String m = sortMode();
        return m.equals("deadline") ? "↕ ⏳ الأقرب إغلاقاً" : m.equals("match") ? "↕ 🎯 الأنسب لي" : "↕ 🕒 الأحدث نشراً";
    }

    /** للترتيب حسب الإغلاق: الأقرب أولاً، ثم بلا موعد، ثم المنتهي. */
    private static int deadlineRank(Store.Job j) {
        int d = Store.daysLeft(j);
        if (d == Integer.MIN_VALUE) return 100000;
        if (d < 0) return 200000 - d;
        return d;
    }

    static String norm(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[ً-ْـ]", "")
                .replaceAll("[أإآ]", "ا").replace('ة', 'ه').replace('ى', 'ي');
    }

    // =====================================================================
    // بطاقة الوظيفة
    // =====================================================================
    private class JobAdapter extends BaseAdapter {
        List<Store.Job> items = new ArrayList<>();
        Set<String> favs = new HashSet<>(), applied = new HashSet<>();
        Search.Query q = null;
        boolean showMatch;

        void set(List<Store.Job> l) {
            items = l;
            favs = Store.set(MainActivity.this, "favs");
            applied = Store.set(MainActivity.this, "applied");
            showMatch = Match.enabled(MainActivity.this);
            notifyDataSetChanged();
        }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return items.get(i).id; }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            Holder h;
            Context c = MainActivity.this;
            if (cv == null) {
                h = new Holder();
                LinearLayout card = card(c);
                card.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
                LinearLayout r1 = row(c);
                h.badge = pill(c, "جديد", ORANGE);
                h.badge.setTextSize(11);
                h.badge.setPadding(dp(c, 8), dp(c, 1), dp(c, 8), dp(c, 1));
                LinearLayout.LayoutParams bp = lp(-2, -2);
                bp.leftMargin = dp(c, 6);
                r1.addView(h.badge, bp);
                h.title = text(c, "", 16.5f, TEXT, true);
                h.title.setMaxLines(2);
                h.title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                r1.addView(h.title, new LinearLayout.LayoutParams(0, -2, 1));
                h.marks = text(c, "", 14, TEXT, false);
                r1.addView(h.marks);
                h.match = text(c, "", 12, Color.WHITE, true);
                h.match.setPadding(dp(c, 7), dp(c, 2), dp(c, 7), dp(c, 2));
                LinearLayout.LayoutParams mp = lp(-2, -2);
                mp.rightMargin = dp(c, 6);
                r1.addView(h.match, mp);
                card.addView(r1);
                h.org = oneLine(text(c, "", 13, MUTED, false));
                h.org.setPadding(0, dp(c, 3), 0, 0);
                card.addView(h.org);
                h.snip = text(c, "", 12, TEXT, false);
                h.snip.setMaxLines(3);
                h.snip.setPadding(0, dp(c, 4), 0, 0);
                card.addView(h.snip);
                h.tags = text(c, "", 12, PRIMARY, false);
                h.tags.setMaxLines(2);
                h.tags.setPadding(0, dp(c, 4), 0, 0);
                card.addView(h.tags);
                card.addView(divider(c), lpm(c, -1, -2, 8, 0));
                LinearLayout r3 = row(c);
                r3.setPadding(0, dp(c, 8), 0, 0);
                h.deadline = text(c, "", 12, GREEN, true);
                r3.addView(h.deadline, new LinearLayout.LayoutParams(0, -2, 1));
                h.meta = oneLine(text(c, "", 11, MUTED, false));
                r3.addView(h.meta);
                card.addView(r3);
                cv = card;
                cv.setTag(h);
            } else h = (Holder) cv.getTag();

            Store.Job j = items.get(pos);
            h.badge.setVisibility(j.seen == 0 ? View.VISIBLE : View.GONE);
            boolean searching = q != null && !q.isEmpty();
            h.title.setText(searching ? Search.highlight(j.title, q, Search.HL_BG(), Search.HL_FG()) : j.title);
            String sn = "";
            if (searching) {
                CharSequence ht = Search.highlight(j.title + " " + j.org, q, 0, 0);
                if (!(ht instanceof android.text.Spanned)) sn = Search.snippet(j.text, q);
            }
            h.snip.setVisibility(sn.isEmpty() ? View.GONE : View.VISIBLE);
            if (!sn.isEmpty()) h.snip.setText(Search.highlight(sn, q, Search.HL_BG(), Search.HL_FG()));
            String sec = Labels.SECTORS.get(j.sector);
            String org = j.org;
            if (sec != null && !"unknown".equals(j.sector)) org = org.isEmpty() ? sec : org + " • " + sec;
            h.org.setText(org);
            h.org.setVisibility(org.isEmpty() ? View.GONE : View.VISIBLE);
            String g = Labels.names(Labels.GOVS, j.govs), s = Labels.names(Labels.SPECS, j.specs);
            if (j.tender()) {
                String tc = Labels.TCATS.get(j.tcat.isEmpty() ? "other" : j.tcat);
                h.tags.setText(tc + "   📍 " + (g.isEmpty() ? "غير محدد" : g) + (j.bond.isEmpty() ? "" : "   💰 تأمين " + j.bond));
            } else h.tags.setText("📍 " + (g.isEmpty() ? "غير محدد" : g) + "   🏷 " + (s.isEmpty() ? "غير مصنف" : s));
            deadlineText(h.deadline, j);
            h.meta.setText((j.doubt == 1 ? "⚠️ قد لا يكون وظيفة • " : "") + ago(j.posted) + " • " + j.src + (j.nSrc > 1 ? " +" + (j.nSrc - 1) : ""));
            String id = String.valueOf(j.id);
            h.marks.setText((favs.contains(id) ? "⭐" : "") + (applied.contains(id) ? "✅" : "") + (j.shared != 0 ? "🤝" : "") + (j.image.isEmpty() ? "" : "📷"));
            if (showMatch && !j.tender() && j.match < 0) { Match.Profile pf = Match.profile(c); if (pf != null) j.match = Match.score(c, j, pf).pct; }
            if (showMatch && !j.tender() && j.match >= 45) {
                h.match.setVisibility(View.VISIBLE);
                h.match.setText("🎯 " + j.match + "%");
                h.match.setBackground(round(c, Match.color(j.match), 10));
            } else h.match.setVisibility(View.GONE);
            cv.setAlpha(Store.expired(j) ? 0.55f : 1f);
            return cv;
        }
    }

    private static class Holder { TextView badge, title, marks, match, org, snip, tags, deadline, meta; }

    private void deadlineText(TextView t, Store.Job j) {
        int d = Store.daysLeft(j);
        if (d == Integer.MIN_VALUE) { t.setText(""); return; }
        if (d < 0) { t.setText("⛔ انتهى التقديم"); t.setTextColor(RED); }
        else if (d == 0) { t.setText("⏰ يغلق اليوم"); t.setTextColor(RED); }
        else if (d <= 3) { t.setText("⏰ يغلق بعد " + d + (d == 1 ? " يوم" : " أيام")); t.setTextColor(ORANGE); }
        else { t.setText("⏳ يغلق " + j.deadline + " (" + d + " يوم)"); t.setTextColor(GREEN); }
    }

    // =====================================================================
    // نافذة كاملة (للتفاصيل وصفحات الأدمن)
    // =====================================================================
    // ===== ضمان وصول الإشعارات (2.1) =====
    private Dialog powerDlg;
    private LinearLayout powerBox;

    private int powerScore() {
        int n = 0;
        if (Power.notifOk(this)) n++;
        if (Power.isIgnoring(this)) n++;
        if (Store.p(this).getBoolean("pw_auto_done", false)) n++;
        return n;
    }

    private boolean staleSync() {
        android.content.SharedPreferences sp = Store.p(this);
        long bg = sp.getLong("bg_last", 0), now = System.currentTimeMillis();
        if (bg == 0 || now - bg < 4 * 3600_000L) return false;
        return now - sp.getLong("pw_stale_hide", 0) > 24 * 3600_000L;
    }

    /** تذكير مرة بعد أول إعداد ثم حتى 3 مرات بفارق أسبوع إن بقيت الخطوات ناقصة. */
    private void maybeNagPower() {
        android.content.SharedPreferences sp = Store.p(this);
        if (guest() || !sp.getBoolean("setup_done", false) || powerScore() >= 3 || powerDlg != null) return;
        int n = sp.getInt("pw_nag_n", 0);
        long last = sp.getLong("pw_nag_t", 0), now = System.currentTimeMillis();
        if (n >= 4 || (n > 0 && now - last < 7 * 24 * 3600_000L)) return;
        sp.edit().putInt("pw_nag_n", n + 1).putLong("pw_nag_t", now).apply();
        powerDialog();
    }

    private void powerDialog() {
        Dialog[] dh = new Dialog[1];
        powerBox = fullDialog("ضمان وصول الإشعارات", dh);
        powerDlg = dh[0];
        powerDlg.setOnDismissListener(d -> { powerDlg = null; powerBox = null; if (tab == T_MORE) show(T_MORE); });
        buildPower();
        powerDlg.show();
    }

    private void buildPower() {
        if (powerBox == null) return;
        LinearLayout l = powerBox;
        l.removeAllViews();
        int sc = powerScore();
        LinearLayout h = card(this);
        h.addView(text(this, sc >= 3 ? "✅ كل شيء جاهز — ستصلك الإشعارات" : "بعض الهواتف تُغلق التطبيقات في الخلفية فتتأخر الإشعارات أو لا تصل. أكمل الخطوات (" + sc + "/3):", 14, TEXT, sc < 3));
        l.addView(h, lpm(this, -1, -2, 0, 10));

        LinearLayout a = card(this);
        boolean n = Power.notifOk(this);
        a.addView(text(this, (n ? "✅ " : "❌ ") + "1) السماح بالإشعارات", 15, TEXT, true));
        if (!n) {
            Button b = button(this, "السماح", true);
            b.setOnClickListener(v -> {
                if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 77);
                try {
                    Intent i = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
                    if (Build.VERSION.SDK_INT < 33) startActivity(i);
                } catch (Throwable ignored) { }
            });
            a.addView(b, lpm(this, -1, -2, 6, 0));
        }
        l.addView(a, lpm(this, -1, -2, 0, 10));

        LinearLayout b2 = card(this);
        boolean ig = Power.isIgnoring(this);
        b2.addView(text(this, (ig ? "✅ " : "❌ ") + "2) إلغاء توفير البطارية للتطبيق", 15, TEXT, true));
        if (!ig) {
            b2.addView(text(this, "اضغط «السماح» في النافذة التي ستظهر.", 13, MUTED, false));
            Button b = button(this, "إلغاء التقييد", true);
            b.setOnClickListener(v -> Power.requestIgnore(this));
            b2.addView(b, lpm(this, -1, -2, 6, 0));
        }
        l.addView(b2, lpm(this, -1, -2, 0, 10));

        LinearLayout c3 = card(this);
        boolean ad = Store.p(this).getBoolean("pw_auto_done", false);
        c3.addView(text(this, (ad ? "✅ " : "❌ ") + "3) التشغيل التلقائي / الخلفية (" + Power.brandName() + ")", 15, TEXT, true));
        c3.addView(text(this, Power.autostartHelp(), 13, TEXT, false));
        Button o = button(this, "فتح الإعدادات", false);
        o.setOnClickListener(v -> Power.openAutostart(this));
        c3.addView(o, lpm(this, -1, -2, 6, 0));
        Button t = button(this, ad ? "تراجع (لم أفعّله)" : "فعّلته ✔", !ad);
        t.setOnClickListener(v -> { Store.p(this).edit().putBoolean("pw_auto_done", !ad).apply(); buildPower(); });
        c3.addView(t, lpm(this, -1, -2, 6, 0));
        l.addView(c3, lpm(this, -1, -2, 0, 10));

        LinearLayout tip = card(this);
        tip.addView(text(this, "💡 نصيحة: افتح قائمة التطبيقات الأخيرة، واسحب بطاقة وظيفتي للأسفل (أو اضغط عليها مطوّلاً) واختر «قفل»، حتى لا يُغلقها «التنظيف».", 13, TEXT, false));
        l.addView(tip, lpm(this, -1, -2, 0, 10));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (powerDlg != null) buildPower();
        if (pendingUpd != null && getPackageManager().canRequestPackageInstalls()) { Updater.Info u = pendingUpd; pendingUpd = null; download(u); }
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        super.onRequestPermissionsResult(rc, p, r);
        if (powerDlg != null) buildPower();
    }

    private LinearLayout fullDialog(String title, Dialog[] out) {
        Dialog d = new Dialog(this, Ui.themeRes());
        Window w = d.getWindow();
        if (w != null) { w.setStatusBarColor(HEADER); w.setNavigationBarColor(BG); }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        LinearLayout bar = row(this);
        bar.setBackgroundColor(HEADER);
        bar.setPadding(dp(this, 8), dp(this, 10), dp(this, 8), dp(this, 10));
        TextView back = text(this, "→  " + title, 17, Color.WHITE, true);
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
        d.setContentView(root);
        out[0] = d;
        return l;
    }

    // =====================================================================
    // تفاصيل الوظيفة
    // =====================================================================
    private void openJob(Store.Job j) {
        if (j.seen == 0) { j.seen = 1; Store.get(this).markSeen(j.id); if (adapter != null) adapter.notifyDataSetChanged(); }
        Dialog[] dh = new Dialog[1];
        LinearLayout l = fullDialog("رجوع", dh);
        Dialog d = dh[0];

        LinearLayout c = card(this);
        TextView title = text(this, "", 19, TEXT, true);
        title.setText(Search.highlight(j.title, Search.parse(query), Search.HL_BG(), Search.HL_FG()));
        title.setTextIsSelectable(true);
        c.addView(title);
        if (!j.org.isEmpty()) c.addView(text(this, j.org, 15, MUTED, false));
        addInfo(c, "📍 المكان", Labels.names(Labels.GOVS, j.govs));
        if (j.tender()) {
            addInfo(c, "📑 النوع", "مناقصة / طلب عروض");
            addInfo(c, "🗂 التصنيف", Labels.TCATS.get(j.tcat.isEmpty() ? "other" : j.tcat));
            if (!j.bond.isEmpty()) addInfo(c, "💰 التأمين الأولي", j.bond);
        } else addInfo(c, "🏷 الاختصاص", Labels.names(Labels.SPECS, j.specs));
        addInfo(c, "🏢 الجهة", Labels.SECTORS.get(j.sector));
        TextView dl = text(this, "", 14, GREEN, true);
        deadlineText(dl, j);
        if (dl.getText().length() > 0) { dl.setPadding(0, dp(this, 6), 0, 0); c.addView(dl); }
        if (Match.enabled(this) && !j.tender()) {
            Match.Result mr = Match.score(this, j, Match.profile(this));
            TextView mt = text(this, "🎯 مناسبة لك " + mr.pct + "%" + (mr.why.isEmpty() ? "" : "\nيطابق: " + String.join("، ", mr.why)), 14, Match.color(mr.pct), true);
            mt.setPadding(0, dp(this, 8), 0, 0);
            c.addView(mt);
        }
        addInfo(c, "🕒 نُشر", ago(j.posted));
        addInfo(c, "📡 المصدر", j.src + (j.nSrc > 1 ? " (ونُشر في " + (j.nSrc - 1) + " مصدر آخر)" : ""));
        if (j.doubt == 1) {
            TextView dw = text(this, "⚠️ قد لا يكون هذا إعلان وظيفة (نصه قصير أو ينقصه المسمى والموعد). تحقّق قبل التقديم.", 13, ORANGE, false);
            dw.setPadding(0, dp(this, 8), 0, 0);
            c.addView(dw);
        }
        l.addView(c, lpm(this, -1, -2, 0, 10));
        if (!j.image.isEmpty()) l.addView(imageView(j.image), lpm(this, -1, -2, 0, 10));

        LinearLayout r1 = row(this);
        if (j.url.startsWith("http")) {
            Button open = button(this, "📄 الإعلان الأصلي", j.apply.isEmpty());
            open.setOnClickListener(v -> openUrl(j.url));
            r1.addView(open, weight());
        }
        if (!j.apply.isEmpty()) {
            Button ap = button(this, j.apply.startsWith("mailto:") ? (j.tender() ? "✉️ طلب الوثائق بالبريد" : "✉️ التقديم بالبريد") : (j.tender() ? "📝 وثائق المناقصة" : "📝 رابط التقديم"), true);
            ap.setOnClickListener(v -> openUrl(j.apply));
            r1.addView(ap, weight());
        }
        if (r1.getChildCount() > 0) l.addView(r1, lpm(this, -1, -2, 0, 8));
        if (!j.tender()) { View qa = quickApply(j); if (qa != null) l.addView(qa, lpm(this, -1, -2, 0, 8)); }
        if (Store.flag(this, "learn_tab") && !j.tender()) {
            boolean[] hc = new boolean[1];
            List<JSONObject> ms = Skills.missingFor(this, j, hc);
            if (!ms.isEmpty()) {
                Set<String> ids = new HashSet<>();
                for (JSONObject m : ms) ids.add(m.optString("id"));
                Button lb = button(this, "🎓 " + (hc[0] ? "تعلّم ما ينقصك" : "مهارات مطلوبة في الوظيفة") + " (" + ms.size() + ")", false);
                lb.setOnClickListener(v -> { d.dismiss(); learnOnly = ids; learnMode = 1; show(T_LEARN); });
                l.addView(lb, lpm(this, -1, -2, 0, 8));
            }
        }

        String id = String.valueOf(j.id);
        LinearLayout r2 = row(this);
        Button fav = button(this, Store.set(this, "favs").contains(id) ? "⭐ محفوظة" : "☆ حفظ", false);
        fav.setOnClickListener(v -> {
            boolean on = Store.toggle(this, "favs", id);
            fav.setText(on ? "⭐ محفوظة" : "☆ حفظ");
            if (on && Store.bool(this, "remind") && Store.daysLeft(j) != Integer.MIN_VALUE)
                Toast.makeText(this, "سيصلك تذكير قبل يوم من إغلاق التقديم ⏰", Toast.LENGTH_SHORT).show();
            Sync.pushPrefs(this);
        });
        Button app = button(this, Store.set(this, "applied").contains(id) ? "✅ قدّمت" : "قدّمت عليها", false);
        app.setOnClickListener(v -> {
            boolean on = Store.toggle(this, "applied", id);
            app.setText(on ? "✅ قدّمت" : "قدّمت عليها");
            Sync.pushPrefs(this);
        });
        Button share = button(this, "↗ مشاركة", false);
        share.setOnClickListener(v -> {
            String body = j.title + (j.org.isEmpty() ? "" : "\n" + j.org) + "\n📍 " + Labels.names(Labels.GOVS, j.govs)
                    + (j.deadline.isEmpty() ? "" : "\n⏳ آخر موعد: " + j.deadline)
                    + (j.apply.isEmpty() ? "" : "\nللتقديم: " + j.apply.replace("mailto:", ""))
                    + (j.url.startsWith("http") ? "\nالإعلان: " + j.url : "") + "\n\n🔗 " + Api.base() + "/j/" + j.id + "\n— عبر تطبيق وظيفتي";
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body), "مشاركة الفرصة"));
        });
        if (!guest()) { r2.addView(fav, weight()); r2.addView(app, weight()); }
        r2.addView(share, weight());
        l.addView(r2, lpm(this, -1, -2, 0, 8));

        if (!guest()) {
            LinearLayout r3 = row(this);
            Button fix = button(this, "🏷 تصنيف خاطئ؟", false);
            fix.setOnClickListener(v -> fixDialog(j, d));
            boolean isHidden = Store.set(this, "hidden").contains(id);
            Button hide = button(this, isHidden ? "👁 إظهار مجدداً" : "🚫 إخفاء (لا يهمني)", false);
            hide.setOnClickListener(v -> {
                Store.toggle(this, "hidden", id);
                Sync.pushPrefs(this);
                d.dismiss();
                applyList();
                if (tab == T_SAVED) show(T_SAVED);
            });
            r3.addView(fix, weight());
            r3.addView(hide, weight());
            l.addView(r3, lpm(this, -1, -2, 0, 8));
            Button et = button(this, Store.admin(this) ? "✏️ تعديل العنوان والجهة" : "✏️ اقترح تصحيح العنوان", false);
            et.setOnClickListener(v -> editTitleDialog(j, d));
            l.addView(et, lpm(this, -1, -2, 0, 8));
            Button flag = button(this, Store.admin(this) ? "🚩 إعلان غير مفيد (حذف للجميع)" : "🚩 ليس إعلاناً مفيداً", false);
            flag.setTextColor(RED);
            flag.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setMessage(Store.admin(this) ? "حذف هذا الإعلان لكل المستخدمين؟" : "الإبلاغ أن هذا ليس إعلان وظيفة؟ سيختفي عندك فوراً، وإذا أبلغ عنه شخص آخر يختفي عن الجميع.")
                    .setPositiveButton("نعم", (x, w) -> post("/flag", jo("id", j.id), r -> {
                        Store.toggle(this, "hidden", id);
                        if (Store.admin(this)) { Store.get(this).delete(j.id); all.remove(j); }
                        Sync.pushPrefs(this);
                        Toast.makeText(this, "✅ شكراً، تم الإبلاغ", Toast.LENGTH_SHORT).show();
                        d.dismiss();
                        applyList();
                    })).setNegativeButton("إلغاء", null).show());
            l.addView(flag, lpm(this, -1, -2, 0, 10));
        }

        LinearLayout tc = card(this);
        tc.addView(text(this, "نص الإعلان", 14, MUTED, true));
        Search.Query dq = Search.parse(query);
        TextView body = text(this, "", 15, TEXT, false);
        body.setText(Search.highlight(Store.get(this).fullText(j.id), dq, Search.HL_BG(), Search.HL_FG()));
        body.setLineSpacing(0, 1.25f);
        body.setTextIsSelectable(true);
        body.setAutoLinkMask(Linkify.WEB_URLS | Linkify.EMAIL_ADDRESSES | Linkify.PHONE_NUMBERS);
        body.setLinkTextColor(PRIMARY);
        body.setLinksClickable(true);
        body.setPadding(0, dp(this, 6), 0, 0);
        tc.addView(body);
        l.addView(tc, lp(-1, -2));

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


    // ===== تبويب التعلّم (2.7): دورات وورشات تُجمع من نفس المصادر =====
    private JSONArray learnItems = new JSONArray();
    private int learnLocked = 0;
    private int learnMode = 0;            // 0 دورات جديدة، 1 دليل المهارات
    private Set<String> learnOnly = null; // مهارات وظيفة محددة (من تفاصيل الوظيفة)
    private boolean learnFiles = true;
    private boolean learnFree = true, learnLangAr = true, learnLangEn = true;
    private String learnQuery = "";
    private LinearLayout learnList;

    private View learnScreen() {
        ScrollView sv = new ScrollView(this);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(this, 12), dp(this, 8), dp(this, 12), dp(this, 16));
        sv.addView(l);
        LinearLayout seg = row(this);
        TextView mNew = chip(this, "🆕 دورات جديدة", learnMode == 0), mGuide = chip(this, "📚 دليل المهارات", learnMode == 1);
        mNew.setOnClickListener(v -> { learnMode = 0; learnOnly = null; show(T_LEARN); });
        mGuide.setOnClickListener(v -> { learnMode = 1; learnOnly = null; show(T_LEARN); });
        seg.addView(mNew, new LinearLayout.LayoutParams(0, -2, 1));
        seg.addView(mGuide, new LinearLayout.LayoutParams(0, -2, 1));
        mNew.setGravity(Gravity.CENTER); mGuide.setGravity(Gravity.CENTER);
        l.addView(seg, lpm(this, -1, -2, 0, 10));
        if (learnMode == 1) {
            LinearLayout g = new LinearLayout(this);
            g.setOrientation(LinearLayout.VERTICAL);
            l.addView(g);
            final Set<String> only = learnOnly;
            renderLearn(g, only);
            Context app = getApplicationContext();
            final boolean empty = Skills.list(this).length() == 0;
            new Thread(() -> {
                Skills.refresh(app, empty);
                if (empty) ui.post(() -> { if (!isFinishing() && tab == T_LEARN && learnMode == 1 && Skills.list(this).length() > 0) renderLearn(g, only); });
            }).start();
            return sv;
        }
        // ===== 2.8.1: يُعرض فقط ما يخصّ الشخص (اختصاصاته + سيرته + مجالاته المختارة) أو بحثه =====
        EditText q = input("🔍 ابحث: مجال، مهارة، منصة…");
        q.setSingleLine(true);
        q.setText(learnQuery);
        l.addView(q, lpm(this, -1, -2, 0, 6));

        LinearLayout chips = row(this);
        TextView cMine = chip(this, "⚙️ مجالاتي", false), cFree = chip(this, "🆓 مجاني فقط", learnFree),
                cFiles = chip(this, "📄 ملفات وكتب", learnFiles), cAr = chip(this, "عربي", learnLangAr), cEn = chip(this, "English", learnLangEn);
        cMine.setOnClickListener(v -> interestsDialog());
        cFree.setOnClickListener(v -> { learnFree = !learnFree; setChip(this, cFree, learnFree); fillLearn(); });
        cFiles.setOnClickListener(v -> { learnFiles = !learnFiles; setChip(this, cFiles, learnFiles); fillLearn(); });
        cAr.setOnClickListener(v -> { learnLangAr = !learnLangAr; setChip(this, cAr, learnLangAr); fillLearn(); });
        cEn.setOnClickListener(v -> { learnLangEn = !learnLangEn; setChip(this, cEn, learnLangEn); fillLearn(); });
        chips.addView(cMine); chips.addView(cFree); chips.addView(cFiles); chips.addView(cAr); chips.addView(cEn);
        if (Store.admin(this)) {
            TextView cAll = chip(this, "👁 الكل (مراجعة)", learnAll);
            cAll.setOnClickListener(v -> { learnAll = !learnAll; setChip(this, cAll, learnAll); loadLearn(); });
            chips.addView(cAll);
        }
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(chips);
        l.addView(hs, lpm(this, -1, -2, 0, 8));

        learnList = new LinearLayout(this);
        learnList.setOrientation(LinearLayout.VERTICAL);
        l.addView(learnList);
        TextView more = text(this, "🔎 لم تجد ما تريد؟ منصات موصى بها وبحث في يوتيوب وتيليغرام ←", 14, PRIMARY, true);
        more.setPadding(0, dp(this, 12), 0, dp(this, 8));
        more.setOnClickListener(v -> { learnMode = 1; learnOnly = null; show(T_LEARN); });
        l.addView(more);
        Runnable[] pend = new Runnable[1];
        q.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable e) {
                learnQuery = e.toString().trim();
                if (pend[0] != null) ui.removeCallbacks(pend[0]);
                pend[0] = () -> loadLearn();
                ui.postDelayed(pend[0], 700);
            }
        });
        try { learnItems = new JSONArray(Store.p(this).getString(learnCacheKey(), "[]")); } catch (Exception e) { learnItems = new JSONArray(); }
        learnLocked = Store.p(this).getInt("learn_locked", 0);
        fillLearn();
        loadLearn();
        return sv;
    }

    private boolean learnAll = false;
    private String learnWhyEmpty = "";

    private static final String[][] INTERESTS = {
            {"finance", "محاسبة ومالية"}, {"admin", "إدارة وموارد بشرية"}, {"management", "إدارة مشاريع وتنسيق"},
            {"meal", "متابعة وتقييم MEAL"}, {"protection", "حماية وإدارة حالة ودعم نفسي"}, {"health", "صحة وتمريض وتغذية"},
            {"education", "تعليم وتدريس"}, {"engineering", "هندسة"}, {"oil", "نفط وطاقة"}, {"it", "برمجة وتقنية"},
            {"logistics", "لوجستيات ومشتريات"}, {"wash", "مياه وإصحاح"}, {"livelihoods", "سبل عيش وزراعة"},
            {"media", "إعلام وتواصل وترجمة"}, {"legal", "قانون"}, {"sales", "تسويق ومبيعات"}};

    /** اهتمامات الشخص: اختصاصات فلتري + اختصاصات سيرته + مجالاته المختارة، وكلمات من مهاراته ومسمّياته. */
    private String learnBasis = "";

    /** الأولوية: مجالاتي المختارة ← وإلا اختصاصات فلتري ← وإلا سيرتي الذاتية. الاختيار الصريح يلغي ما بعده. */
    private JSONObject learnInterests() {
        Set<String> specs = new LinkedHashSet<>(Store.set(this, "learn_int"));
        List<String> terms = new ArrayList<>();
        if (!specs.isEmpty()) learnBasis = "مجالاتي";
        else {
            specs.addAll(Store.set(this, "specs"));
            specs.remove("other"); specs.remove("tender"); specs.remove("training");
            if (!specs.isEmpty()) learnBasis = "فلتري";
            else {
                Match.Profile pf = Match.profile(this);
                if (pf != null) {
                    specs.addAll(pf.specs.keySet());
                    for (String x : pf.skills) if (terms.size() < 40) terms.add(x);
                    for (String x : pf.titles) if (terms.size() < 55) terms.add(x);
                    learnBasis = "سيرتي الذاتية";
                } else learnBasis = "";
            }
        }
        specs.remove("other"); specs.remove("tender");
        learnBasisSpecs = Labels.names(Labels.SPECS, String.join(",", specs));
        JSONObject b = new JSONObject();
        try {
            b.put("specs", new JSONArray(specs));
            b.put("terms", new JSONArray(terms));
            if (!learnQuery.isEmpty()) b.put("q", learnQuery);
            if (learnAll) b.put("all", true);
        } catch (Exception ignored) { }
        return b;
    }

    private String learnBasisSpecs = "";

    private String learnCacheKey() { return "learn_find_" + (learnAll ? "all" : Integer.toHexString(learnInterests().toString().hashCode())); }

    private void loadLearn() {
        JSONObject body = learnInterests();
        boolean noInterest = body.optJSONArray("specs").length() == 0 && body.optJSONArray("terms").length() == 0;
        if (noInterest && learnQuery.isEmpty() && !learnAll) {
            learnItems = new JSONArray();
            learnWhyEmpty = "pick";
            fillLearn();
            return;
        }
        learnWhyEmpty = "";
        final String key = learnCacheKey(), qAt = learnQuery;
        Context app = getApplicationContext();
        new Thread(() -> {
            JSONObject r = null;
            try { r = Api.post("/learn/find", body); } catch (Throwable ignored) { }
            final JSONObject rr = r;
            ui.post(() -> {
                if (isFinishing() || tab != T_LEARN || learnMode != 0 || !qAt.equals(learnQuery)) return;
                if (rr == null) { if (learnItems.length() == 0) learnWhyEmpty = "net"; fillLearn(); return; }
                learnItems = rr.optJSONArray("items") != null ? rr.optJSONArray("items") : new JSONArray();
                learnLocked = rr.optInt("locked");
                if (learnQuery.isEmpty()) Store.p(app).edit().putString(key, learnItems.toString()).putInt("learn_locked", learnLocked).apply();
                fillLearn();
            });
        }).start();
    }

    private void interestsDialog() {
        Set<String> cur = new LinkedHashSet<>(Store.set(this, "learn_int"));
        String[] names = new String[INTERESTS.length];
        boolean[] on = new boolean[INTERESTS.length];
        for (int i = 0; i < INTERESTS.length; i++) { names[i] = INTERESTS[i][1]; on[i] = cur.contains(INTERESTS[i][0]); }
        new AlertDialog.Builder(this).setTitle("مجالاتك في التعلّم (إن تركتها فارغة نستخدم اختصاصات «فلتري»)")
                .setMultiChoiceItems(names, on, (d, w, c) -> on[w] = c)
                .setPositiveButton("حفظ", (d, w) -> {
                    Set<String> n = new LinkedHashSet<>();
                    for (int i = 0; i < INTERESTS.length; i++) if (on[i]) n.add(INTERESTS[i][0]);
                    Store.putSet(this, "learn_int", n);
                    show(T_LEARN);
                })
                .setNegativeButton("إلغاء", null).show();
    }

    private void fillLearn() {
        if (learnList == null) return;
        learnList.removeAllViews();
        if ("pick".equals(learnWhyEmpty)) {
            LinearLayout c = card(this);
            c.addView(text(this, "🎯 ماذا تريد أن تتعلّم؟", 16, TEXT, true));
            c.addView(text(this, "نعرض لك فقط ما يخصّك. اختر مجالاتك، أو حدّد اختصاصك في «فلتري»، أو ارفع سيرتك الذاتية، أو ابحث بكلمة.", 13, MUTED, false), lpm(this, -1, -2, 4, 8));
            Button b = button(this, "⚙️ اختيار مجالاتي", true);
            b.setOnClickListener(v -> interestsDialog());
            c.addView(b);
            learnList.addView(c);
            return;
        }
        if (learnQuery.isEmpty() && !learnAll && !learnBasis.isEmpty()) {
            TextView bs = text(this, "🎯 نعرض حسب " + learnBasis + ": " + learnBasisSpecs + "  ← تغيير", 12, PRIMARY, true);
            bs.setPadding(dp(this, 2), 0, dp(this, 2), dp(this, 8));
            bs.setOnClickListener(v -> interestsDialog());
            learnList.addView(bs);
        }
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
        int shown = 0;
        for (int i = 0; i < learnItems.length() && shown < 150; i++) {
            JSONObject it = learnItems.optJSONObject(i);
            if (it == null) continue;
            boolean ar = "ar".equals(it.optString("l")), fl = !it.optString("ft").isEmpty();
            if ((ar && !learnLangAr) || (!ar && !learnLangEn)) continue;
            if (fl && !learnFiles) continue;
            if (!fl && learnFree && it.optInt("f") != 1) continue;
            String dl = it.optString("d");
            if (!dl.isEmpty() && dl.compareTo(today) < 0) continue;
            learnList.addView(learnCard(it), lpm(this, -1, -2, 0, 8));
            shown++;
        }
        if (shown == 0) learnList.addView(text(this, "net".equals(learnWhyEmpty) ? "تعذّر الاتصال. اسحب للتحديث أو جرّب لاحقاً."
                : !learnQuery.isEmpty() ? "لا نتائج لبحثك. جرّب كلمة أخرى."
                : learnItems.length() == 0 ? "لا يوجد حالياً ما يطابق مجالاتك. نجمع الجديد كل ساعة؛ جرّب إضافة مجالات من «⚙️ مجالاتي» أو ابحث بكلمة."
                : "لا نتائج بهذه الفلاتر. جرّب إلغاء «مجاني فقط».", 14, MUTED, false), lpm(this, -1, -2, 8, 8));
        if (guest() && learnLocked > 0) {
            LinearLayout lk = card(this);
            lk.addView(text(this, "🔒 يوجد " + learnLocked + " عنصر آخر يناسبك. سجّل الدخول بحساب غوغل لعرضها كلها.", 14, TEXT, true));
            learnList.addView(lk, lpm(this, -1, -2, 8, 0));
        }
    }

    private View learnCard(JSONObject it) {
        LinearLayout c = card(this);
        boolean isFile = !it.optString("ft").isEmpty();
        if (it.optInt("hid") == 1) c.addView(text(this, "🙈 مخفي" + (it.optInt("rp") > 0 ? " • بلاغات: " + it.optInt("rp") : ""), 11, RED, true));
        c.addView(text(this, (isFile ? "📄 " : "") + it.optString("t"), 16, TEXT, true));
        if (!it.optString("w").isEmpty()) c.addView(text(this, "✨ لأنك مهتم بـ: " + it.optString("w"), 12, PRIMARY, false), lpm(this, -1, -2, 2, 0));
        LinearLayout pr = row(this);
        if (isFile) pr.addView(pill(this, it.optString("ft").toUpperCase() + (it.optString("fs").isEmpty() ? "" : " • " + it.optString("fs")), INFO_BG));
        if (!isFile && it.optInt("f") == 1) pr.addView(pill(this, "🆓 مجاني", OK_BG));
        if (it.optInt("c") == 1) pr.addView(pill(this, "🏅 شهادة", INFO_BG));
        pr.addView(pill(this, "ar".equals(it.optString("l")) ? "عربي" : "English", INFO_BG));
        if (!it.optString("d").isEmpty()) pr.addView(pill(this, "⏳ حتى " + it.optString("d"), WARN_BG));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(pr);
        c.addView(hs, lpm(this, -1, -2, 6, 0));
        String x = it.optString("x").replace("\n", " ");
        if (!isFile && !x.isEmpty()) c.addView(text(this, x.length() > 160 ? x.substring(0, 160) + "…" : x, 13, MUTED, false), lpm(this, -1, -2, 6, 0));
        if (!it.optString("p").isEmpty()) c.addView(text(this, "المصدر: " + it.optString("p"), 11, MUTED, false), lpm(this, -1, -2, 4, 0));
        if (!it.optString("h").isEmpty()) c.addView(text(this, "🧭 " + it.optString("h"), 12, PRIMARY, false), lpm(this, -1, -2, 4, 0));
        final String u = it.optString("a");
        final int id = it.optInt("id");
        Button b = button(this, isFile ? "📥 فتح في تيليغرام" : "فتح الدورة", true);
        b.setOnClickListener(v -> { if (!u.isEmpty()) openUrl(u); });
        c.addView(b, lpm(this, -1, -2, 8, 0));
        LinearLayout acts = row(this);
        if (Store.admin(this)) {
            boolean hid = it.optInt("hid") == 1;
            TextView h = text(this, hid ? "👁 إظهار" : "🙈 إخفاء", 12, PRIMARY, true);
            h.setPadding(0, dp(this, 8), dp(this, 16), 0);
            h.setOnClickListener(v -> post("/admin/learn/hide", jo("id", id, "hide", !hid), r -> { Toast.makeText(this, hid ? "أُظهر" : "أُخفي", Toast.LENGTH_SHORT).show(); c.setAlpha(hid ? 1f : 0.4f); }));
            TextView j = text(this, "➡️ نقل للفرص", 12, PRIMARY, true);
            j.setPadding(0, dp(this, 8), dp(this, 16), 0);
            j.setOnClickListener(v -> post("/admin/learn/tojob", jo("id", id), r -> { Toast.makeText(this, "نُقل إلى الفرص", Toast.LENGTH_SHORT).show(); c.setVisibility(View.GONE); }));
            acts.addView(h); acts.addView(j);
        } else if (!guest()) {
            TextView rp = text(this, "🚩 ليس تعليمياً / إبلاغ", 12, MUTED, false);
            rp.setPadding(0, dp(this, 8), 0, 0);
            rp.setOnClickListener(v -> post("/learn/report", jo("id", id), r -> { Toast.makeText(this, "شكراً، سيراجعه المشرف", Toast.LENGTH_SHORT).show(); c.setVisibility(View.GONE); }));
            acts.addView(rp);
        }
        if (acts.getChildCount() > 0) c.addView(acts);
        return c;
    }

    // =====================================================================
    // قسم التعلّم (2.6)
    // =====================================================================
    private boolean learnAr = true;

    private void learnDialog(Set<String> only) {
        Dialog[] dh = new Dialog[1];
        LinearLayout l = fullDialog(only == null ? "🎓 تعلّم: مراجع وكورسات" : "🎓 تعلّم ما ينقصك", dh);
        dh[0].show();
        renderLearn(l, only);
        Context app = getApplicationContext();
        new Thread(() -> {
            Skills.refresh(app, true);
            ui.post(() -> { if (dh[0].isShowing() && !isFinishing()) renderLearn(l, only); });
        }).start();
    }

    private void renderLearn(LinearLayout l, Set<String> only) {
        l.removeAllViews();
        EditText q = input(only == null ? "🔍 ابحث عن مهارة أو موضوع: إكسل، MEAL، أوتوكاد…" : "موضوع البحث الخارجي");
        q.setSingleLine(true);
        String first = "";
        if (only != null) {
            JSONArray all = Skills.list(this);
            for (int i = 0; i < all.length() && first.isEmpty(); i++) {
                JSONObject sk = all.optJSONObject(i);
                if (sk != null && only.contains(sk.optString("id"))) first = learnAr && !sk.optString("ar").isEmpty() ? sk.optString("ar") : sk.optString("en");
            }
            q.setText(first);
        }
        l.addView(q, lpm(this, -1, -2, 0, 8));
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        LinearLayout lr = row(this);
        TextView cAr = chip(this, "عربي", learnAr), cEn = chip(this, "English", !learnAr);
        cAr.setOnClickListener(v -> { learnAr = true; setChip(this, cAr, true); setChip(this, cEn, false); fillSkills(body, only, q.getText().toString()); });
        cEn.setOnClickListener(v -> { learnAr = false; setChip(this, cAr, false); setChip(this, cEn, true); fillSkills(body, only, q.getText().toString()); });
        lr.addView(text(this, "لغة المصادر:  ", 13, MUTED, false));
        lr.addView(cAr);
        lr.addView(cEn, lpm(this, -2, -2, 0, 0));
        l.addView(lr, lpm(this, -1, -2, 0, 8));
        l.addView(body);
        fillSkills(body, only, q.getText().toString());
        if (only == null) q.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable e) { fillSkills(body, null, e.toString()); }
        });

        l.addView(platformsCard(q), lpm(this, -1, -2, 8, 0));

        LinearLayout ex = card(this);
        ex.addView(text(this, "🔎 لم تجد ما تبحث عنه؟ ابحث في:", 15, TEXT, true));
        String[][] sites = {
                {"▶️ يوتيوب", "https://www.youtube.com/results?search_query="},
                {"🔎 غوغل", "https://www.google.com/search?q="},
                {"✈️ تيليغرام", "https://www.google.com/search?q=site%3At.me+"},
                {"💬 ريديت", "https://www.reddit.com/search/?q="},
                {"💼 لينكدإن", "https://www.linkedin.com/search/results/content/?keywords="},
                {"📘 فيسبوك", "https://www.facebook.com/search/posts/?q="},
        };
        for (int i = 0; i < sites.length; i += 2) {
            LinearLayout rr = row(this);
            for (int k = i; k < i + 2 && k < sites.length; k++) {
                final String[] st = sites[k];
                Button b = button(this, st[0], false);
                b.setOnClickListener(v -> {
                    String t = q.getText().toString().trim();
                    if (t.isEmpty()) { Toast.makeText(this, "اكتب الموضوع أولاً", Toast.LENGTH_SHORT).show(); return; }
                    try { openUrl(st[1] + java.net.URLEncoder.encode(t + (learnAr ? " دورة شرح" : " course tutorial"), "UTF-8")); } catch (Exception ignored) { }
                });
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, -2, 1);
                bp.setMargins(dp(this, 3), dp(this, 3), dp(this, 3), 0);
                rr.addView(b, bp);
            }
            ex.addView(rr);
        }
        l.addView(ex, lpm(this, -1, -2, 8, 8));
    }

    /** منصات تعلّم موصى بها: فتح الموقع، أو بحث داخله بموضوع خانة البحث. */
    private View platformsCard(EditText q) {
        LinearLayout c = card(this);
        c.addView(text(this, "🏫 منصات موصى بها", 15, TEXT, true));
        c.addView(text(this, "اكتب موضوعاً في خانة البحث أعلاه ثم اضغط منصة للبحث داخلها، أو اضغطها مباشرة لفتحها.", 12, MUTED, false), lpm(this, -1, -2, 2, 6));
        String[][] ps = {
                {"إدراك", "edraak.org", "عربي • مجاني بشهادة"},
                {"رواق", "rwaq.org", "عربي • مساقات جامعية"},
                {"أكاديمية حسوب", "academy.hsoub.com", "عربي • برمجة وتصميم"},
                {"دروب", "doroob.sa", "عربي • مهارات وظيفية"},
                {"Kaya", "kayaconnect.org", "عربي/English • عمل إنساني ومنظمات"},
                {"DisasterReady", "disasterready.org", "English • إنساني وحماية"},
                {"MSF Tembo", "tembo.msf.org", "English • صحة عامة"},
                {"Coursera", "coursera.org", "English/عربي • مساعدة مالية للشهادة"},
                {"edX", "edx.org", "English • جامعات عالمية"},
                {"Alison", "alison.com", "English • شهادات مجانية"},
                {"Khan Academy", "ar.khanacademy.org", "عربي • أساسيات"},
                {"Microsoft Learn", "learn.microsoft.com", "English • تقنية"},
        };
        for (String[] p : ps) {
            LinearLayout r = row(this);
            r.setPadding(0, dp(this, 7), 0, dp(this, 7));
            LinearLayout tx = new LinearLayout(this);
            tx.setOrientation(LinearLayout.VERTICAL);
            tx.addView(text(this, "🌐 " + p[0], 14, PRIMARY, true));
            tx.addView(text(this, p[2], 11, MUTED, false));
            r.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
            r.setOnClickListener(v -> {
                String t = q.getText().toString().trim();
                try {
                    openUrl(t.isEmpty() ? "https://" + p[1] : "https://www.google.com/search?q=" + java.net.URLEncoder.encode("site:" + p[1] + " " + t, "UTF-8"));
                } catch (Exception ignored) { }
            });
            c.addView(r);
        }
        return c;
    }

    private void fillSkills(LinearLayout body, Set<String> only, String query) {
        body.removeAllViews();
        JSONArray all = Skills.list(this);
        String qn = Search.light(query).trim();
        String lastCat = null;
        int shown = 0;
        for (int i = 0; i < all.length(); i++) {
            JSONObject sk = all.optJSONObject(i);
            if (sk == null) continue;
            if (only != null && !only.contains(sk.optString("id"))) continue;
            if (only == null && !qn.isEmpty()) {
                StringBuilder hay = new StringBuilder(sk.optString("ar") + " " + sk.optString("en"));
                JSONArray al = sk.optJSONArray("al");
                if (al != null) for (int k = 0; k < al.length(); k++) hay.append(' ').append(al.optString(k));
                if (!Search.light(hay.toString()).contains(qn)) continue;
            }
            String cat = sk.optString("cat");
            if (!cat.equals(lastCat)) { body.addView(section(cat.isEmpty() ? "أخرى" : cat)); lastCat = cat; }
            body.addView(skillCard(sk), lpm(this, -1, -2, 0, 8));
            shown++;
        }
        if (shown == 0) body.addView(text(this, all.length() == 0
                ? "نجهّز الآن مصادر وكورسات لأكثر المهارات طلباً في الوظائف. في الأثناء ابحث بالأزرار أدناه."
                : "لا نتائج في الدليل لهذا البحث. جرّب الأزرار أدناه.", 14, MUTED, false), lpm(this, -1, -2, 8, 8));
    }

    private View skillCard(JSONObject sk) {
        LinearLayout c = card(this);
        String ar = sk.optString("ar"), en = sk.optString("en");
        c.addView(text(this, ar + (!ar.isEmpty() && !en.isEmpty() ? "  •  " : "") + en, 16, TEXT, true));
        JSONArray ls = sk.optJSONArray("links");
        if (ls == null) return c;
        String want = learnAr ? "ar" : "en";
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < ls.length(); i++) {
                JSONObject lk = ls.optJSONObject(i);
                if (lk == null) continue;
                boolean match = want.equals(lk.optString("l", "ar"));
                if ((pass == 0) != match) continue;
                final String u = lk.optString("u");
                String ty = lk.optString("ty", "site");
                String icon = ty.equals("course") ? "🎓" : ty.equals("video") ? "▶️" : ty.equals("playlist") ? "📺" : ty.equals("channel") ? "📡" : ty.equals("telegram") ? "✈️" : ty.equals("doc") ? "📄" : "🌐";
                LinearLayout r = row(this);
                r.setPadding(0, dp(this, 8), 0, dp(this, 2));
                LinearLayout tx = new LinearLayout(this);
                tx.setOrientation(LinearLayout.VERTICAL);
                tx.addView(text(this, icon + " " + lk.optString("t"), 14, PRIMARY, true));
                tx.addView(text(this, "en".equals(lk.optString("l")) ? "English" : "عربي", 11, MUTED, false));
                tx.setOnClickListener(v -> openUrl(u));
                r.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
                TextView rep = text(this, "⚠", 16, MUTED, false);
                rep.setPadding(dp(this, 12), dp(this, 6), dp(this, 4), dp(this, 6));
                rep.setOnClickListener(v -> new AlertDialog.Builder(this).setMessage("الإبلاغ عن أن هذا الرابط لا يعمل أو لم يعد مناسباً؟")
                        .setPositiveButton("إبلاغ", (d, w) -> {
                            if (guest()) { Toast.makeText(this, "سجّل الدخول لتتمكن من الإبلاغ", Toast.LENGTH_SHORT).show(); return; }
                            post("/link/report", jo("skill", sk.optString("id"), "url", u), x -> Toast.makeText(this, "شكراً، سنراجع الرابط", Toast.LENGTH_SHORT).show());
                        }).setNegativeButton("إلغاء", null).show());
                r.addView(rep);
                c.addView(r);
            }
        }
        return c;
    }

    // ===== الأدمن: دليل المهارات =====
    // ===== 3.0: قناة الإصدارات (تجريبي/منشور) + إيقاف النسخ القديمة =====
    private void adminReleases() {
        Dialog[] dh = new Dialog[1];
        LinearLayout l = fullDialog("الإصدارات والنسخ القديمة", dh);
        dh[0].show();
        LinearLayout rc = card(this);
        rc.addView(text(this, "📦 الإصدارات", 16, TEXT, true));
        TextView info = text(this, "⏳ جارٍ التحقق من GitHub…", 14, MUTED, false);
        rc.addView(info, lpm(this, -1, -2, 6, 8));
        Button pub = button(this, "📢 نشر للمستخدمين", true);
        pub.setVisibility(View.GONE);
        rc.addView(pub);
        rc.addView(text(this, "كل نسخة جديدة تُبنى «تجريبية»: تصلك أنت والأدمنية فقط. حين تقرر، اضغط «نشر للمستخدمين» ثم في صفحة GitHub اضغط Run workflow؛ فيصل التحديث للجميع مرة واحدة مع «ما الجديد» مجمّعاً.", 12, MUTED, false), lpm(this, -1, -2, 8, 0));
        l.addView(rc, lpm(this, -1, -2, 0, 12));
        new Thread(() -> {
            String[] ch = null;
            try { ch = Updater.channels(); } catch (Throwable ignored) { }
            final String[] fc = ch;
            ui.post(() -> {
                if (fc == null) { info.setText("تعذّر الاتصال بـ GitHub"); return; }
                info.setText("👥 عند المستخدمين: " + (fc[0].isEmpty() ? "—" : fc[0]) + "\n🧪 أحدث تجريبية: " + (fc[1].isEmpty() ? "لا يوجد (المنشورة هي الأحدث)" : fc[1]) + "\n📱 المثبّتة عندك: " + Updater.current(this));
                info.setTextColor(TEXT);
                if (!fc[1].isEmpty()) {
                    pub.setText("📢 نشر " + fc[1] + " للمستخدمين");
                    pub.setVisibility(View.VISIBLE);
                    pub.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("نشر " + fc[1] + " للمستخدمين؟")
                            .setMessage("ستُفتح صفحة GitHub: اضغط «Run workflow» ثم الزر الأخضر. خلال دقيقة يظهر التحديث لكل المستخدمين.")
                            .setPositiveButton("متابعة", (d, w) -> openUrl("https://github.com/makanona-team/wazifati/actions/workflows/publish.yml"))
                            .setNegativeButton("إلغاء", null).show());
                }
            });
        }).start();

        LinearLayout lc = card(this);
        lc.addView(text(this, "🧓 النسخ القديمة (أقدم من 3.0)", 16, TEXT, true));
        TextView ls = text(this, "⏳", 13, MUTED, false);
        lc.addView(ls, lpm(this, -1, -2, 6, 8));
        String[] modes = {"لا إيقاف", "إيقاف 1.8 وأقدم (لا تملك التحديث الإجباري) — المعروفة من حسابها فقط", "إيقاف كل نسخة أقدم من 3.0"};
        LinearLayout ml = new LinearLayout(this);
        ml.setOrientation(LinearLayout.VERTICAL);
        lc.addView(ml);
        lc.addView(text(this, "الموقوفة لا تصلها الفرص، بل بطاقة واحدة وإعلان يدعوانها لتحميل النسخة الجديدة. الترتيب الآمن: انشر النسخة للمستخدمين ← فعّل التحديث الإجباري (أدنى نسخة) ← بعد يوم أو يومين «إيقاف كل نسخة أقدم من 3.0».", 12, MUTED, false), lpm(this, -1, -2, 8, 0));
        l.addView(lc);
        Res[] show = new Res[1];
        show[0] = r -> {
            int mode = r.optInt("mode");
            JSONObject bv = r.optJSONObject("byVer");
            StringBuilder sb = new StringBuilder("أجهزة قديمة اتصلت خلال 7 أيام: " + r.optInt("total") + " (حسابات " + r.optInt("users") + " • زوار " + r.optInt("guests") + ")");
            if (bv != null && bv.length() > 0) {
                sb.append("\nحسب النسخة: ");
                java.util.Iterator<String> it = bv.keys();
                while (it.hasNext()) { String k = it.next(); sb.append(k).append(" ×").append(bv.optInt(k)).append(it.hasNext() ? "، " : ""); }
            }
            ls.setText(sb.toString());
            ml.removeAllViews();
            for (int i = 0; i < modes.length; i++) {
                final int mi = i;
                TextView o = text(this, (i == mode ? "🔘 " : "⚪ ") + modes[i], 14, i == mode ? PRIMARY : TEXT, i == mode);
                o.setPadding(0, dp(this, 8), 0, dp(this, 8));
                o.setOnClickListener(v -> {
                    if (mi == mode) return;
                    Runnable go = () -> post("/admin/legacy", jo("mode", mi), show[0]);
                    if (mi == 2) new AlertDialog.Builder(this).setTitle("إيقاف كل النسخ الأقدم من 3.0؟")
                            .setMessage("تأكد أنك نشرت 3.0 للمستخدمين وفعّلت التحديث الإجباري، وإلا سيُوقف من لم يصله التحديث بعد.")
                            .setPositiveButton("إيقاف", (d, w) -> go.run()).setNegativeButton("إلغاء", null).show();
                    else go.run();
                });
                ml.addView(o);
            }
        };
        post("/admin/legacy", new JSONObject(), show[0]);
    }

    private void adminSkills() {
        Dialog[] dh = new Dialog[1];
        LinearLayout l = fullDialog("دليل المهارات", dh);
        l.addView(text(this, "جارٍ التحميل…", 14, MUTED, false));
        dh[0].show();
        get("/admin/skills/top", r -> {
            l.removeAllViews();
            JSONArray a = r.optJSONArray("skills");
            if (a == null) return;
            l.addView(text(this, "المهارات مرتّبة حسب ظهورها في آخر " + r.optInt("scanned") + " إعلان وظيفة. اضغط مهارة لإضافة روابطها؛ لا تظهر للمستخدمين إلا بعد إضافة رابط واحد على الأقل.", 13, MUTED, false));
            Button nw = button(this, "➕ مهارة جديدة", true);
            nw.setOnClickListener(v -> skillEditor(null, dh[0]));
            l.addView(nw, lpm(this, -1, -2, 8, 0));
            Button rp = button(this, "🚩 روابط مبلَّغ عنها", false);
            rp.setOnClickListener(v -> adminLinkReports());
            l.addView(rp, lpm(this, -1, -2, 6, 8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject it = a.optJSONObject(i);
                if (it == null) continue;
                int nl = it.optJSONArray("links") == null ? 0 : it.optJSONArray("links").length();
                LinearLayout c = card(this);
                c.addView(text(this, it.optInt("n") + "×  " + it.optString("ar") + (it.optString("en").isEmpty() ? "" : "  •  " + it.optString("en")), 15, TEXT, true));
                c.addView(text(this, (nl > 0 ? "✅ " + nl + " رابط" : "➕ بلا روابط") + "  •  " + it.optString("cat"), 12, nl > 0 ? GREEN : MUTED, false));
                c.setOnClickListener(v -> skillEditor(it, dh[0]));
                l.addView(c, lpm(this, -1, -2, 6, 0));
            }
        });
    }

    private void skillEditor(JSONObject it, Dialog parent) {
        Dialog[] dh = new Dialog[1];
        LinearLayout l = fullDialog(it == null ? "مهارة جديدة" : "تعديل المهارة", dh);
        EditText ar = input("الاسم بالعربية"), en = input("الاسم بالإنجليزية"), cat = input("التصنيف (هندسة، منظمات، مالية…)");
        EditText al = input("كلمات المطابقة مع نص الوظيفة، مفصولة بفواصل (excel, اكسل…)");
        EditText ln = input("كل رابط في سطر:\nعنوان | الرابط | ar أو en | النوع\nالنوع: course video playlist channel telegram doc site");
        for (EditText e : new EditText[]{al, ln}) { e.setSingleLine(false); e.setMinLines(e == ln ? 6 : 3); e.setGravity(Gravity.TOP | Gravity.START); }
        ln.setTextDirection(View.TEXT_DIRECTION_LTR);
        java.util.HashMap<String, Long> checked = new java.util.HashMap<>();
        if (it != null) {
            ar.setText(it.optString("ar")); en.setText(it.optString("en")); cat.setText(it.optString("cat"));
            JSONArray aa = it.optJSONArray("aliases");
            StringBuilder sb = new StringBuilder();
            if (aa != null) for (int i = 0; i < aa.length(); i++) sb.append(i > 0 ? ", " : "").append(aa.optString(i));
            al.setText(sb);
            JSONArray la = it.optJSONArray("links");
            StringBuilder lb = new StringBuilder();
            if (la != null) for (int i = 0; i < la.length(); i++) {
                JSONObject k = la.optJSONObject(i);
                if (k == null) continue;
                lb.append(k.optString("t")).append(" | ").append(k.optString("u")).append(" | ").append(k.optString("l", "ar")).append(" | ").append(k.optString("ty", "site")).append('\n');
                checked.put(k.optString("u"), k.optLong("c"));
            }
            ln.setText(lb);
        }
        l.addView(ar, lpm(this, -1, -2, 0, 6)); l.addView(en, lpm(this, -1, -2, 0, 6)); l.addView(cat, lpm(this, -1, -2, 0, 6));
        l.addView(text(this, "كلمات المطابقة (تربط الوظيفة بالمهارة؛ العربية تُطبَّع تلقائياً)", 12, MUTED, false));
        l.addView(al, lpm(this, -1, -2, 2, 8));
        l.addView(text(this, "الروابط (تأكد أن كل رابط يعمل ومجاني قبل إضافته)", 12, MUTED, false));
        l.addView(ln, lpm(this, -1, -2, 2, 8));
        Button save = button(this, "💾 حفظ", true);
        save.setOnClickListener(v -> {
            JSONArray aliases = new JSONArray();
            for (String x : al.getText().toString().split("[,،\n]")) if (!x.trim().isEmpty()) aliases.put(x.trim());
            JSONArray links = new JSONArray();
            for (String line : ln.getText().toString().split("\n")) {
                String[] p = line.split("\\|");
                String t = "", u = "", lg = "ar", ty = "site";
                if (p.length == 1 && p[0].trim().startsWith("http")) u = p[0].trim();
                else if (p.length >= 2) { t = p[0].trim(); u = p[1].trim(); if (p.length >= 3) lg = p[2].trim().toLowerCase().startsWith("en") ? "en" : "ar"; if (p.length >= 4) ty = p[3].trim().toLowerCase(); }
                if (!u.startsWith("http")) continue;
                Long ck = checked.get(u);
                links.put(jo("t", t, "u", u, "l", lg, "ty", ty, "c", ck == null ? 0 : ck));
            }
            post("/admin/skills/save", jo("id", it == null ? "" : it.optString("id"), "ar", ar.getText().toString(), "en", en.getText().toString(),
                    "cat", cat.getText().toString(), "aliases", aliases, "links", links), r -> {
                Toast.makeText(this, "✅ تم الحفظ", Toast.LENGTH_SHORT).show();
                dh[0].dismiss(); parent.dismiss(); adminSkills();
                Skills.refresh(getApplicationContext(), true);
            });
        });
        l.addView(save, lpm(this, -1, -2, 0, 0));
        if (it != null && it.optBoolean("saved")) {
            Button del = button(this, "🗑 حذف المهارة من الدليل", false);
            del.setOnClickListener(v -> new AlertDialog.Builder(this).setMessage("حذف «" + it.optString("ar") + "» وروابطها؟")
                    .setPositiveButton("حذف", (d, w) -> post("/admin/skills/delete", jo("id", it.optString("id")), r -> { dh[0].dismiss(); parent.dismiss(); adminSkills(); }))
                    .setNegativeButton("إلغاء", null).show());
            l.addView(del, lpm(this, -1, -2, 8, 0));
        }
        dh[0].show();
    }

    private void adminLinkReports() {
        Dialog[] dh = new Dialog[1];
        LinearLayout l = fullDialog("روابط مبلَّغ عنها", dh);
        l.addView(text(this, "جارٍ التحميل…", 14, MUTED, false));
        dh[0].show();
        get("/admin/link-reports", r -> {
            l.removeAllViews();
            JSONArray a = r.optJSONArray("reports");
            if (a == null || a.length() == 0) { l.addView(text(this, "لا بلاغات 👌", 15, MUTED, false)); return; }
            for (int i = 0; i < a.length(); i++) {
                JSONObject it = a.optJSONObject(i);
                if (it == null) continue;
                LinearLayout c = card(this);
                c.addView(text(this, "🚩 " + it.optInt("n") + " بلاغ • مهارة: " + it.optString("skill"), 14, TEXT, true));
                c.addView(text(this, it.optString("url"), 12, PRIMARY, false));
                LinearLayout rr = row(this);
                Button op = button(this, "فتح", false);
                op.setOnClickListener(v -> openUrl(it.optString("url")));
                Button ok = button(this, "تمت المعالجة", true);
                ok.setOnClickListener(v -> post("/admin/link-reports/clear", jo("skill", it.optString("skill"), "url", it.optString("url")), x -> { dh[0].dismiss(); adminLinkReports(); }));
                rr.addView(op, new LinearLayout.LayoutParams(0, -2, 1));
                rr.addView(ok, new LinearLayout.LayoutParams(0, -2, 1));
                c.addView(rr, lpm(this, -1, -2, 6, 0));
                l.addView(c, lpm(this, -1, -2, 6, 0));
            }
        });
    }

    // ===== تعديل العنوان =====
    private void editTitleDialog(Store.Job j, Dialog parent) {
        boolean admin = Store.admin(this);
        EditText t = input("عنوان الإعلان");
        t.setText(j.title);
        t.setMinLines(2);
        EditText o = input("الجهة المعلنة (اختياري)");
        o.setText(j.org);
        o.setSingleLine(true);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(this, 18), dp(this, 8), dp(this, 18), 0);
        box.addView(t, lp(-1, -2));
        box.addView(o, lpm(this, -1, -2, 8, 0));
        new AlertDialog.Builder(this).setTitle(admin ? "✏️ تعديل العنوان والجهة" : "✏️ اقتراح تصحيح العنوان")
                .setMessage(admin ? "يُطبّق التعديل عند كل المستخدمين، ويُحفظ الأصل في «المنشورات المصححة» لتسترجعه." : "يصل اقتراحك للأدمن، وبعد موافقته يظهر العنوان الجديد للجميع.")
                .setView(box)
                .setPositiveButton("حفظ", (dd, w) -> {
                    String nt = t.getText().toString().trim(), no = o.getText().toString().trim();
                    if (nt.length() < 3) { Toast.makeText(this, "العنوان قصير جداً", Toast.LENGTH_SHORT).show(); return; }
                    post(admin ? "/admin/job/edit" : "/fix", jo("id", j.id, "title", nt, "org", no), r -> {
                        if (admin) {
                            Store.get(this).updateTitle(j.id, nt, no);
                            j.title = nt; j.org = no;
                            Toast.makeText(this, r.optBoolean("changed", true) ? "✅ تم تعديل العنوان للجميع" : "لم يتغيّر شيء", Toast.LENGTH_SHORT).show();
                            parent.dismiss();
                            openJob(j);
                            applyList();
                            refreshPending();
                        } else Toast.makeText(this, "✅ شكراً! وصل اقتراحك للأدمن", Toast.LENGTH_SHORT).show();
                    });
                })
                .setNegativeButton("إلغاء", null).show();
    }

    private boolean editsAll = false;

    /** الأدمن: قائمة المنشورات التي عُدّل عنوانها أو جهتها، للمراجعة والاسترجاع. */
    private void adminEdits() {
        Dialog[] dh = new Dialog[1];
        LinearLayout l = fullDialog("المنشورات المصححة", dh);
        l.addView(text(this, "جارٍ التحميل…", 14, MUTED, false));
        dh[0].show();
        get("/admin/edits" + (editsAll ? "?filter=all" : ""), r -> {
            l.removeAllViews();
            LinearLayout fr = row(this);
            TextView c1 = chip(this, "🔴 غير المراجَع", !editsAll), c2 = chip(this, "الكل", editsAll);
            c1.setOnClickListener(v -> { editsAll = false; dh[0].dismiss(); adminEdits(); });
            c2.setOnClickListener(v -> { editsAll = true; dh[0].dismiss(); adminEdits(); });
            fr.addView(c1, lp(-2, -2));
            LinearLayout.LayoutParams p2 = lp(-2, -2);
            p2.rightMargin = dp(this, 6);
            fr.addView(c2, p2);
            l.addView(fr, lpm(this, -1, -2, 0, 8));
            JSONArray a = r.optJSONArray("edits");
            if (a == null || a.length() == 0) { l.addView(text(this, editsAll ? "لا توجد منشورات مصححة بعد." : "لا توجد تعديلات بانتظار المراجعة ✅", 15, MUTED, false)); return; }
            if (!editsAll) {
                Button all = button(this, "✔ تمت مراجعة الكل", false);
                all.setOnClickListener(v -> post("/admin/edits/review", jo("all", true), x -> { dh[0].dismiss(); adminEdits(); refreshPending(); }));
                l.addView(all, lpm(this, -1, -2, 0, 8));
            }
            for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.optJSONObject(i);
                if (e == null) continue;
                final long id = e.optLong("id");
                boolean reviewed = e.optInt("edit_reviewed") == 1;
                LinearLayout c = card(this);
                TextView nw = text(this, str(e, "title"), 15, TEXT, true);
                nw.setTextIsSelectable(true);
                c.addView(nw);
                if (!str(e, "title_orig").equals(str(e, "title"))) {
                    TextView old = text(this, "الأصلي: " + str(e, "title_orig"), 12, MUTED, false);
                    old.setPaintFlags(old.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
                    c.addView(old);
                }
                if (!str(e, "org_orig").equals(str(e, "org"))) {
                    c.addView(text(this, "🏢 الجهة: " + (str(e, "org").isEmpty() ? "—" : str(e, "org")) + "   (الأصلية: " + (str(e, "org_orig").isEmpty() ? "—" : str(e, "org_orig")) + ")", 12, MUTED, false));
                }
                c.addView(text(this, "✏️ " + str(e, "edited_by") + (str(e, "edit_via").isEmpty() ? "" : " • اقتراح من " + str(e, "edit_via")) + " • " + ago(e.optLong("edited_at")) + (reviewed ? " • ✔ مراجَع" : " • 🔴 غير مراجَع"), 11, MUTED, false));
                LinearLayout r2 = row(this);
                if (!reviewed) {
                    Button ok = button(this, "✔ مراجعة", true);
                    ok.setOnClickListener(v -> post("/admin/edits/review", jo("id", id), x -> { dh[0].dismiss(); adminEdits(); refreshPending(); }));
                    r2.addView(ok, weight());
                }
                Button rs = button(this, "↩ استرجاع الأصلي", false);
                rs.setOnClickListener(v -> new AlertDialog.Builder(this).setMessage("استرجاع العنوان والجهة الأصليين لكل المستخدمين؟")
                        .setPositiveButton("استرجاع", (x, w) -> post("/admin/edits/restore", jo("id", id), y -> {
                            Toast.makeText(this, "✅ استُرجع الأصل", Toast.LENGTH_SHORT).show();
                            dh[0].dismiss();
                            adminEdits();
                            refreshPending();
                            refresh(false);
                        })).setNegativeButton("إلغاء", null).show());
                Button op = button(this, "👁 فتح", false);
                op.setOnClickListener(v -> openJobById(id));
                r2.addView(rs, weight());
                r2.addView(op, weight());
                c.addView(r2, lpm(this, -1, -2, 8, 0));
                l.addView(c, lpm(this, -1, -2, 0, 8));
            }
        });
    }

    // ===== تصحيح التصنيف =====
    private void fixDialog(Store.Job j, Dialog parent) {
        Dialog[] dh = new Dialog[1];
        LinearLayout l = fullDialog("تصحيح التصنيف", dh);
        Dialog d = dh[0];
        l.addView(text(this, j.title, 16, TEXT, true));
        l.addView(text(this, Store.admin(this) ? "تعديلك يُطبّق مباشرة على كل المستخدمين." : "يصل اقتراحك للأدمن، وبعد موافقته يُصحَّح لكل المستخدمين.", 13, MUTED, false));
        Set<String> specs = new LinkedHashSet<>(Arrays.asList(j.specs.split(",")));
        Set<String> govs = new LinkedHashSet<>(Arrays.asList(j.govs.split(",")));
        l.addView(checkGroup("🏷 الاختصاص الصحيح", Labels.SPECS, specs), lpm(this, -1, -2, 10, 10));
        l.addView(checkGroup("📍 المكان الصحيح", Labels.GOVS, govs), lpm(this, -1, -2, 0, 10));
        Button send = button(this, "إرسال التصحيح", true);
        l.addView(send, lp(-1, -2));
        send.setOnClickListener(v -> {
            specs.remove(""); govs.remove("");
            if (specs.isEmpty() || govs.isEmpty()) { Toast.makeText(this, "اختر اختصاصاً ومكاناً واحداً على الأقل", Toast.LENGTH_SHORT).show(); return; }
            String ss = String.join(",", specs), gg = String.join(",", govs);
            post("/fix", jo("id", j.id, "specs", ss, "govs", gg), r -> {
                if (r.optBoolean("applied")) {
                    Store.get(this).updateClass(j.id, ss, gg, j.sector);
                    j.specs = ss; j.govs = gg;
                    Toast.makeText(this, "✅ تم التصحيح لكل المستخدمين", Toast.LENGTH_SHORT).show();
                } else Toast.makeText(this, "✅ شكراً! وصل اقتراحك للأدمن", Toast.LENGTH_SHORT).show();
                d.dismiss();
                parent.dismiss();
                applyList();
            });
        });
        d.show();
    }

    private View checkGroup(String title, Map<String, String> items, Set<String> sel) {
        LinearLayout c = card(this);
        c.addView(text(this, title, 15, TEXT, true));
        for (Map.Entry<String, String> e : items.entrySet()) {
            CheckBox cb = new CheckBox(this);
            cb.setText(e.getValue());
            cb.setTextColor(TEXT);
            cb.setChecked(sel.contains(e.getKey()));
            cb.setOnCheckedChangeListener((b, on) -> { if (on) sel.add(e.getKey()); else sel.remove(e.getKey()); });
            c.addView(cb);
        }
        return c;
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
            t.setOnClickListener(v -> { savedMode = k; show(T_SAVED); });
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
        if (draft == null) {
            draft = new java.util.HashMap<>();
            for (String k : new String[]{"specs", "govs", "sectors", "t_cats", "t_govs"}) draft.put(k, new LinkedHashSet<>(Store.set(this, k)));
            draftDirty = false;
        }
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(this, 12), dp(this, 12), dp(this, 12), dp(this, 20));
        sv.addView(l);
        outer.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        List<String> saved = Store.savedSearches(this);
        if (!saved.isEmpty() && !guest()) {
            LinearLayout ssCard = card(this);
            ssCard.addView(text(this, "🔔 عمليات البحث المحفوظة", 15, TEXT, true));
            ssCard.addView(text(this, "يصلك إشعار بكل فرصة جديدة تطابق أياً منها، حتى خارج الفلتر. اضغط ✕ للحذف.", 12, MUTED, false), lpm(this, -1, -2, 2, 6));
            for (String q0 : saved) {
                LinearLayout sqRow = row(this);
                sqRow.setPadding(0, dp(this, 6), 0, dp(this, 6));
                TextView sqT = text(this, "🔎 " + q0, 14, PRIMARY, true);
                sqT.setOnClickListener(v -> { query = q0; show(T_JOBS); });
                sqRow.addView(sqT, new LinearLayout.LayoutParams(0, -2, 1));
                TextView sqX = text(this, "✕", 18, RED, true);
                sqX.setPadding(dp(this, 12), 0, dp(this, 6), 0);
                sqX.setOnClickListener(v -> { List<String> s2 = Store.savedSearches(this); s2.remove(q0); Store.putSavedSearches(this, s2); show(T_FILTER); });
                sqRow.addView(sqX);
                ssCard.addView(sqRow);
            }
            l.addView(ssCard, lpm(this, -1, -2, 0, 12));
        }

        if (!Store.p(this).getBoolean("setup_done", false)) {
            LinearLayout w = card(this);
            w.setBackground(round(this, PRIMARY_LIGHT, 14));
            String nm = Store.p(this).getString("uname", "");
            w.addView(text(this, "أهلاً " + nm + " 👋", 18, PRIMARY, true));
            w.addView(text(this, "اختر اختصاصاتك ومحافظاتك ثم اضغط «تطبيق الفلتر» في الأسفل، وسيصلك إشعار بكل فرصة جديدة تناسبك. يمكنك دائماً تصفّح كل الفرص من تبويب «الفرص».", 14, TEXT, false));
            Button skip = button(this, "تخطّي الآن — أتصفّح كل الفرص", false);
            skip.setOnClickListener(v -> { Store.p(this).edit().putBoolean("setup_done", true).apply(); draft = null; draftDirty = false; show(T_JOBS); });
            w.addView(skip, lpm(this, -1, -2, 8, 0));
            l.addView(w, lpm(this, -1, -2, 0, 12));
        }

        LinearLayout sw = card(this);
        sw.addView(switchRow("❔ أظهر الإعلانات غير المصنفة ضمن فلتري (حتى لا تفوتك فرصة)", "unk"));
        sw.addView(switchRow("⏳ إخفاء الإعلانات المنتهية", "hide_expired"));
        sw.addView(switchRow("⚠️ إخفاء الإعلانات المشكوك أنها ليست وظائف", "hide_doubt"));
        l.addView(sw, lpm(this, -1, -2, 0, 12));

        l.addView(section("🔎 فلتر الوظائف"));
        l.addView(group("🏷 الاختصاصات", "specs", Labels.SPECS, "اترك الكل فارغاً لكل الاختصاصات"), lpm(this, -1, -2, 0, 12));
        l.addView(group("📍 المحافظات", "govs", Labels.GOVS, "اترك الكل فارغاً لكل المحافظات. الإعلانات الموجّهة «لكل سوريا» تظهر دائماً."), lpm(this, -1, -2, 0, 12));
        l.addView(group("🏢 نوع الجهة", "sectors", Labels.SECTORS, "اترك الكل فارغاً لكل الجهات"), lpm(this, -1, -2, 0, 12));

        l.addView(section("📑 فلتر المناقصات"));
        l.addView(group("🗂 تصنيف المناقصة", "t_cats", Labels.TCATS, "اترك الكل فارغاً لكل التصنيفات"), lpm(this, -1, -2, 0, 12));
        l.addView(group("📍 محافظات المناقصات", "t_govs", Labels.GOVS, "اترك الكل فارغاً لكل المحافظات"), lpm(this, -1, -2, 0, 12));

        // الشريط الثابت: تطبيق / إلغاء
        LinearLayout bar = row(this);
        bar.setBackgroundColor(CARD);
        bar.setElevation(dp(this, 10));
        bar.setPadding(dp(this, 10), dp(this, 8), dp(this, 10), dp(this, 8));
        Button apply = button(this, "", true);
        applyBtnLabel = apply;
        apply.setOnClickListener(v -> {
            applyDraft();
            Store.p(this).edit().putBoolean("setup_done", true).apply();
            Toast.makeText(this, "✅ تم تطبيق الفلتر", Toast.LENGTH_SHORT).show();
            show(T_JOBS);
        });
        Button cancel = button(this, "↩ إلغاء التغييرات", false);
        cancel.setOnClickListener(v -> { draft = null; draftDirty = false; show(T_FILTER); });
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(0, -2, 2);
        ap.leftMargin = dp(this, 6);
        bar.addView(apply, ap);
        bar.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1));
        outer.addView(bar, lp(-1, -2));
        updateApplyLabel();
        return outer;
    }

    /** عدد الفرص المتوقع مع المسودة الحالية. */
    private void updateApplyLabel() {
        if (applyBtnLabel == null || draft == null) return;
        Store.Filter f = Store.Filter.of(draft.get("specs"), draft.get("govs"), draft.get("sectors"), Store.bool(this, "unk"));
        Store.TFilter tf = Store.TFilter.of(draft.get("t_cats"), draft.get("t_govs"));
        Set<String> hidden = Store.set(this, "hidden");
        boolean hideExp = Store.bool(this, "hide_expired"), hideDoubt = Store.bool(this, "hide_doubt");
        int nj = 0, nt = 0;
        for (Store.Job j : all) {
            if (hidden.contains(String.valueOf(j.id)) || (hideExp && Store.expired(j)) || (hideDoubt && j.doubt == 1)) continue;
            if (j.tender()) { if (tf.match(j)) nt++; }
            else if (f.match(j)) nj++;
        }
        applyBtnLabel.setText((draftDirty ? "✅ تطبيق الفلتر" : "✅ الفلتر مطبّق") + "\n" + nj + " فرصة • " + nt + " مناقصة");
        applyBtnLabel.setAlpha(draftDirty ? 1f : 0.75f);
    }

    private void applyDraft() {
        if (draft == null) return;
        for (Map.Entry<String, Set<String>> e : draft.entrySet()) Store.putSet(this, e.getKey(), e.getValue());
        draftDirty = false;
        Sync.pushPrefs(this);
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
        Set<String> sel = draft != null && draft.containsKey(key) ? draft.get(key) : Store.set(this, key);
        ArrayList<CheckBox> boxes = new ArrayList<>();
        for (Map.Entry<String, String> e : items.entrySet()) {
            if ((key.equals("govs") || key.equals("t_govs")) && e.getKey().equals("all")) continue;
            CheckBox cb = new CheckBox(this);
            cb.setText(e.getValue());
            cb.setTextSize(15);
            cb.setTextColor(TEXT);
            cb.setChecked(sel.contains(e.getKey()));
            cb.setOnCheckedChangeListener((b, on) -> {
                if (on) sel.add(e.getKey()); else sel.remove(e.getKey());
                draftDirty = true;
                updateApplyLabel();
            });
            boxes.add(cb);
            c.addView(cb);
        }
        clear.setOnClickListener(v -> { for (CheckBox bx : boxes) bx.setChecked(false); });
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
        boolean admin = Store.admin(this);

        LinearLayout ac = card(this);
        LinearLayout ar = row(this);
        LinearLayout at = new LinearLayout(this);
        at.setOrientation(LinearLayout.VERTICAL);
        at.addView(text(this, "👤 " + Store.p(this).getString("uname", ""), 16, TEXT, true));
        at.addView(text(this, Store.p(this).getString("email", ""), 13, MUTED, false));
        ar.addView(at, new LinearLayout.LayoutParams(0, -2, 1));
        if (admin) {
            TextView badge = text(this, "أدمن", 12, Color.WHITE, true);
            badge.setBackground(round(this, GREEN, 10));
            badge.setPadding(dp(this, 10), dp(this, 3), dp(this, 10), dp(this, 3));
            ar.addView(badge);
        }
        ac.addView(ar);
        Button out = button(this, "تسجيل الخروج", false);
        out.setOnClickListener(v -> new AlertDialog.Builder(this).setMessage("تسجيل الخروج من هذا الهاتف؟ إعداداتك محفوظة على حسابك.")
                .setPositiveButton("خروج", (d, w) -> {
                    post("/logout", new JSONObject(), null);
                    ui.postDelayed(() -> signedOut("تم تسجيل الخروج"), 300);
                }).setNegativeButton("إلغاء", null).show());
        ac.addView(out, lpm(this, -1, -2, 8, 0));
        Button delAcc = button(this, "🗑 حذف حسابي نهائياً", false);
        delAcc.setTextColor(RED);
        delAcc.setOnClickListener(v -> deleteAccountStep1());
        ac.addView(delAcc, lpm(this, -1, -2, 6, 0));
        l.addView(ac, lpm(this, -1, -2, 0, 12));

        if (Store.flag(this, "cv_match")) l.addView(cvCard(), lpm(this, -1, -2, 0, 12));

        if (!guest()) {
            Button pw = button(this, (powerScore() < 3 ? "⚠ " : "✅ ") + "ضمان وصول الإشعارات", powerScore() < 3);
            pw.setOnClickListener(v -> powerDialog());
            l.addView(pw, lpm(this, -1, -2, 0, 12));
        }

        if (admin) {
            LinearLayout ad = card(this);
            ad.addView(text(this, "🛠 لوحة الأدمن", 16, TEXT, true));
            int ps = Store.p(this).getInt("pending_sources", 0), pf = Store.p(this).getInt("pending_fixes", 0);
            Button bs = button(this, "📡 المصادر" + (ps > 0 ? "  (" + ps + " بانتظار موافقتك 🔴)" : ""), ps > 0);
            bs.setOnClickListener(v -> adminSources());
            boolean isDev = "eng.feras8585@gmail.com".equalsIgnoreCase(Store.p(this).getString("email", ""));
            Button bu = button(this, "👥 المستخدمون", false);
            bu.setOnClickListener(v -> adminUsers());
            if (!isDev) bu.setVisibility(View.GONE);
            Button bf = button(this, "🏷 تصحيحات التصنيف" + (pf > 0 ? "  (" + pf + " 🔴)" : ""), pf > 0);
            bf.setOnClickListener(v -> adminFixes());
            int pfl = Store.p(this).getInt("pending_flags", 0);
            Button bfl = button(this, "🚩 البلاغات" + (pfl > 0 ? "  (" + pfl + " 🔴)" : ""), pfl > 0);
            bfl.setOnClickListener(v -> adminFlags());
            int pe = Store.p(this).getInt("pending_edits", 0);
            Button be = button(this, "✏️ المنشورات المصححة" + (pe > 0 ? "  (" + pe + " 🔴)" : ""), pe > 0);
            be.setOnClickListener(v -> adminEdits());
            Button ba = button(this, "📢 إشعارات المستخدمين (إرسال / حذف)", false);
            ba.setOnClickListener(v -> showAnnouncements());
            Button bm = button(this, "⬆ تحديث إجباري", false);
            bm.setOnClickListener(v -> adminMinVer());
            Button bft = button(this, "🎛 مفاتيح الميزات (تشغيل / إيقاف)", false);
            bft.setOnClickListener(v -> adminFeatures());
            int prp = Store.p(this).getInt("pending_reports", 0);
            Button brp = button(this, "🐞 الأخطاء وفشل الدخول" + (prp > 0 ? "  (" + prp + " 🔴)" : ""), prp > 0);
            brp.setOnClickListener(v -> adminReports());
            Button bt = button(this, "📊 الإحصائيات", false);
            bt.setOnClickListener(v -> adminStats());
            Button bsk = button(this, "🎓 دليل المهارات (التعلّم)", false);
            bsk.setOnClickListener(v -> adminSkills());
            Button brl = button(this, "📦 الإصدارات والنسخ القديمة", false);
            brl.setOnClickListener(v -> adminReleases());
            for (Button bb : new Button[]{bs, bu, bf, bfl, be, brp, bft, bsk, brl, ba, bm, bt}) ad.addView(bb, lpm(this, -1, -2, 6, 0));
            l.addView(ad, lpm(this, -1, -2, 0, 12));
        }

        LinearLayout sc = card(this);
        sc.addView(text(this, admin ? "➕ إضافة مصدر" : "➕ اقترح مصدراً للفرص", 16, TEXT, true));
        sc.addView(text(this, admin ? "قناة تيليغرام أو موقع وظائف — يبدأ الجلب منه خلال ساعة." : "تعرف قناة تيليغرام أو موقعاً ينشر وظائف؟ أرسله، وبعد موافقة الأدمن تصل فرصه للجميع.", 13, MUTED, false));
        EditText src = input("رابط قناة تيليغرام أو موقع وظائف");
        src.setSingleLine(true);
        sc.addView(src, lpm(this, -1, -2, 8, 6));
        Button add = button(this, admin ? "إضافة" : "إرسال الاقتراح", true);
        add.setOnClickListener(v -> suggest(src));
        sc.addView(add, lp(-1, -2));
        if (admin || Store.flag(this, "suggest_source")) l.addView(sc, lpm(this, -1, -2, 0, 12));

        LinearLayout hc = card(this);
        hc.addView(text(this, "🤝 مشاركة فرصة من فيسبوك أو لينكدإن", 16, TEXT, true));
        hc.addView(text(this, "عندما ترى إعلاناً هناك: اضغط «مشاركة» ← «المزيد» ← اختر «وظيفتي». يصل الإعلان لكل المستخدمين ويُصنَّف تلقائياً.", 13, TEXT, false));
        if (Store.flag(this, "share_job")) l.addView(hc, lpm(this, -1, -2, 0, 12));

        l.addView(notifyCard(), lpm(this, -1, -2, 0, 12));

        LinearLayout tc = card(this);
        tc.addView(text(this, "🌓 المظهر", 16, TEXT, true));
        LinearLayout tr = row(this);
        String cur = Store.p(this).getString("theme", "auto");
        String[][] th = {{"auto", "تلقائي"}, {"light", "☀️ نهاري"}, {"dark", "🌙 ليلي"}};
        for (String[] t : th) {
            TextView ch = chip(this, t[1], cur.equals(t[0]));
            ch.setOnClickListener(v -> { Store.p(this).edit().putString("theme", t[0]).putInt("reopen_tab", T_MORE).apply(); recreate(); });
            tr.addView(ch, weight());
        }
        tc.addView(tr, lpm(this, -1, -2, 8, 0));
        l.addView(tc, lpm(this, -1, -2, 0, 12));

        LinearLayout uc = card(this);
        uc.addView(text(this, "⬆️ التطبيق", 16, TEXT, true));
        uc.addView(text(this, "الإصدار الحالي: " + Updater.current(this), 13, MUTED, false));
        LinearLayout ub = row(this);
        Button chk = button(this, "البحث عن تحديث", true);
        chk.setOnClickListener(v -> checkUpdate(false));
        Button news = button(this, "ما الجديد", false);
        news.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("ما الجديد").setMessage(Updater.whatsNew(this)).setPositiveButton("حسناً", null).show());
        ub.addView(chk, weight());
        ub.addView(news, weight());
        uc.addView(ub, lpm(this, -1, -2, 8, 4));
        TextView about = text(this, "وظيفتي — مساعد البحث عن الوظائف في سوريا\nتطوير: المهندس فراس • +963982424717", 13, MUTED, false);
        about.setGravity(Gravity.CENTER);
        about.setPadding(0, dp(this, 8), 0, 0);
        uc.addView(about);
        l.addView(uc, lp(-1, -2));
        return sv;
    }

    // =====================================================================
    // السيرة الذاتية
    // =====================================================================
    private View cvCard() {
        LinearLayout c = card(this);
        c.addView(text(this, "📄 سيرتي الذاتية", 16, TEXT, true));
        Match.Profile p = Match.profile(this);
        if (p == null) {
            c.addView(text(this, "ارفع سيرتك (PDF أو Word) فيقترح التطبيق الاختصاصات المناسبة لك، ويُظهر نسبة تطابق كل فرصة مع خبرتك، ويُشعرك بالفرص الأنسب.", 13, MUTED, false));
            Button up = button(this, "📎 اختر ملف السيرة (PDF أو Word)", true);
            up.setOnClickListener(v -> pickCv());
            c.addView(up, lpm(this, -1, -2, 8, 0));
            return c;
        }
        long upd = Store.p(this).getLong("cv_updated", 0);
        c.addView(oneLine(text(this, "📎 " + Store.p(this).getString("cv_name", "") + " • آخر تحديث " + ago(upd), 12, MUTED, false)));

        c.addView(text(this, "الاختصاصات المناسبة لك:", 14, TEXT, true));
        JSONArray specs = p.raw.optJSONArray("specs");
        if (specs != null) for (int i = 0; i < specs.length(); i++) {
            JSONObject s = specs.optJSONObject(i);
            LinearLayout rr = row(this);
            rr.setPadding(0, dp(this, 3), 0, dp(this, 3));
            rr.addView(oneLine(text(this, s.optString("name"), 13, TEXT, false)), new LinearLayout.LayoutParams(0, -2, 1));
            View bar = new View(this);
            bar.setBackground(round(this, Match.color(Math.max(45, s.optInt("pct"))), 4));
            rr.addView(bar, new LinearLayout.LayoutParams(Math.max(dp(this, 4), dp(this, 90) * s.optInt("pct") / 100), dp(this, 10)));
            TextView pc = text(this, " " + s.optInt("pct") + "%", 12, MUTED, false);
            rr.addView(pc, new LinearLayout.LayoutParams(dp(this, 42), -2));
            c.addView(rr);
        }
        if (!p.titles.isEmpty()) c.addView(info2("💼 المسميات", String.join("، ", p.titles)));
        if (p.years > 0) c.addView(info2("⏳ الخبرة", "حوالي " + p.years + " سنة"));
        if (!p.langs.isEmpty()) c.addView(info2("🗣 اللغات", String.join("، ", p.langs)));
        if (!p.skills.isEmpty()) c.addView(info2("🛠 المهارات", String.join(" • ", p.skills)));

        LinearLayout b1 = row(this);
        Button apply = button(this, "✅ طبّق على فلتري", true);
        apply.setOnClickListener(v -> applyCvToFilter(p));
        Button upd2 = button(this, "🔄 تحديث السيرة", false);
        upd2.setOnClickListener(v -> pickCv());
        b1.addView(apply, weight());
        b1.addView(upd2, weight());
        c.addView(b1, lpm(this, -1, -2, 10, 6));

        c.addView(switchRow("🎯 أظهر نسبة التطابق على الفرص", "show_match"));
        c.addView(switchRow("🔔 أشعرني بالفرص المطابقة لسيرتي (حتى خارج فلتري)", "cv_notify"));
        c.addView(text(this, "أقل نسبة تطابق للإشعار:", 13, TEXT, false));
        c.addView(choiceRow("cv_threshold", new int[]{50, 60, 70, 80}, new String[]{"50%", "60%", "70%", "80%"}));
        c.addView(text(this, "تذكير بتحديث السيرة:", 13, TEXT, false));
        c.addView(choiceRow("cv_remind", new int[]{0, 3, 6}, new String[]{"بدون", "كل 3 أشهر", "كل 6 أشهر"}));

        Button del = button(this, "🗑 حذف سيرتي", false);
        del.setOnClickListener(v -> new AlertDialog.Builder(this).setMessage("حذف سيرتك الذاتية وتحليلها من حسابك نهائياً؟")
                .setPositiveButton("حذف", (d, w) -> post("/cv/delete", new JSONObject(), r -> {
                    Store.applyCv(this, null);
                    all = Store.get(this).all();
                    Toast.makeText(this, "تم حذف السيرة", Toast.LENGTH_SHORT).show();
                    show(T_MORE);
                })).setNegativeButton("إلغاء", null).show());
        c.addView(del, lpm(this, -1, -2, 10, 0));
        return c;
    }

    private TextView info2(String k, String v) {
        TextView t = text(this, k + ":  " + v, 13, TEXT, false);
        t.setPadding(0, dp(this, 6), 0, 0);
        return t;
    }

    private View choiceRow(String key, int[] vals, String[] labels) {
        LinearLayout r = row(this);
        r.setPadding(0, dp(this, 4), 0, dp(this, 6));
        ArrayList<TextView> chips = new ArrayList<>();
        int cur = Store.intv(this, key);
        for (int i = 0; i < vals.length; i++) {
            final int v = vals[i];
            TextView t = chip(this, labels[i], cur == v);
            chips.add(t);
            t.setOnClickListener(x -> {
                Store.putInt(this, key, v);
                Sync.pushPrefs(this);
                for (int k = 0; k < chips.size(); k++) setChip(this, chips.get(k), vals[k] == v);
            });
            r.addView(t, weight());
        }
        return r;
    }

    private void pickCv() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/msword", "text/plain"});
        try { startActivityForResult(i, PICK_CV); }
        catch (Exception e) { Toast.makeText(this, "لا يوجد مدير ملفات على الهاتف", Toast.LENGTH_LONG).show(); }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK_CV || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("جارٍ قراءة السيرة وتحليلها…");
        pd.setCancelable(false);
        pd.show();
        Context app = getApplicationContext();
        new Thread(() -> {
            String err = null;
            JSONObject r = null;
            try {
                String name = CvText.name(app, u);
                String text = CvText.extract(app, u, name);
                r = Api.post("/cv", new JSONObject().put("text", text).put("name", name));
                Store.applyCv(app, r);
            } catch (Throwable e) { err = e.getMessage(); }
            final String fe = err;
            final JSONObject fr = r;
            ui.post(() -> {
                safeDismiss(pd);
                if (fe != null) { new AlertDialog.Builder(this).setTitle("تعذّرت قراءة السيرة").setMessage(fe).setPositiveButton("حسناً", null).show(); return; }
                all = Store.get(this).all();
                show(T_MORE);
                cvResult(fr);
            });
        }).start();
    }

    private void cvResult(JSONObject r) {
        Match.Profile p = Match.profile(this);
        if (p == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("🏷 الاختصاصات المناسبة لك:\n");
        JSONArray specs = p.raw.optJSONArray("specs");
        if (specs != null) for (int i = 0; i < specs.length(); i++) {
            JSONObject s = specs.optJSONObject(i);
            sb.append("• ").append(s.optString("name")).append(" — ").append(s.optInt("pct")).append("%\n");
        }
        if (!p.titles.isEmpty()) sb.append("\n💼 ").append(String.join("، ", p.titles)).append("\n");
        if (p.years > 0) sb.append("⏳ خبرة حوالي ").append(p.years).append(" سنة\n");
        JSONObject d = r == null ? null : r.optJSONObject("diff");
        if (d != null) {
            sb.append("\n🔄 ماذا تغيّر عن النسخة السابقة:\n");
            boolean any = false;
            JSONArray ns = d.optJSONArray("newSkills"), nsp = d.optJSONArray("newSpecs"), lost = d.optJSONArray("lostSkills");
            if (nsp != null && nsp.length() > 0) { sb.append("➕ اختصاصات جديدة: ").append(join(nsp)).append("\n"); any = true; }
            if (ns != null && ns.length() > 0) { sb.append("➕ مهارات جديدة: ").append(join(ns)).append("\n"); any = true; }
            if (lost != null && lost.length() > 0) { sb.append("➖ لم تعد مذكورة: ").append(join(lost)).append("\n"); any = true; }
            JSONArray y = d.optJSONArray("years");
            if (y != null && y.optInt(1) != y.optInt(0)) { sb.append("⏳ الخبرة: ").append(y.optInt(0)).append(" ← ").append(y.optInt(1)).append(" سنة\n"); any = true; }
            if (!any) sb.append("لا تغييرات تُذكر\n");
        }
        new AlertDialog.Builder(this).setTitle("✅ تم تحليل سيرتك").setMessage(sb.toString().trim())
                .setPositiveButton("طبّق على فلتري", (x, w) -> applyCvToFilter(p))
                .setNegativeButton("لاحقاً", null).show();
    }

    private static String join(JSONArray a) {
        ArrayList<String> l = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) l.add(a.optString(i));
        return String.join("، ", l);
    }

    private void applyCvToFilter(Match.Profile p) {
        LinkedHashSet<String> specs = new LinkedHashSet<>();
        for (String id : p.specOrder) { Integer v = p.specs.get(id); if (v != null && v >= 40) specs.add(id); }
        if (specs.isEmpty() && !p.specOrder.isEmpty()) specs.add(p.specOrder.get(0));
        StringBuilder msg = new StringBuilder("سيُضبط فلتر الاختصاصات على:\n");
        for (String id : specs) msg.append("• ").append(Labels.SPECS.get(id)).append("\n");
        boolean setGov = Store.set(this, "govs").isEmpty() && !p.govs.isEmpty();
        if (setGov) {
            msg.append("\nوالمحافظات المذكورة في سيرتك:\n");
            for (String g : p.govs) msg.append("• ").append(Labels.GOVS.get(g)).append("\n");
        }
        msg.append("\nيمكنك تعديله لاحقاً من «فلتري».");
        new AlertDialog.Builder(this).setTitle("تطبيق على فلتري").setMessage(msg.toString())
                .setPositiveButton("تطبيق", (d, w) -> {
                    Store.putSet(this, "specs", specs);
                    if (setGov) Store.putSet(this, "govs", new LinkedHashSet<>(p.govs));
                    Store.p(this).edit().putBoolean("setup_done", true).apply();
                    Sync.pushPrefs(this);
                    Toast.makeText(this, "✅ تم ضبط فلترك حسب سيرتك", Toast.LENGTH_SHORT).show();
                    Store.p(this).edit().putString("sort", "match").apply();
                    show(0);
                }).setNegativeButton("إلغاء", null).show();
    }

    private View notifyCard() {
        LinearLayout c = card(this);
        c.addView(text(this, "🔔 الإشعارات", 16, TEXT, true));
        c.addView(switchRow("إشعار بالفرص الجديدة المطابقة لفلتري", "notify"));
        c.addView(switchRow("⏰ تذكير قبل يوم من إغلاق الفرص المحفوظة", "remind"));
        c.addView(switchRow("📑 إشعار بالمناقصات الجديدة (حسب فلتر المناقصات)", "t_notify"));

        c.addView(text(this, "طريقة الإشعار:", 14, TEXT, false));
        LinearLayout mr = row(this);
        boolean daily = "daily".equals(Store.p(this).getString("notify_mode", "instant"));
        TextView inst = chip(this, "⚡ فوري", !daily);
        TextView dig = chip(this, "📋 ملخص يومي " + hh(Store.intv(this, "digest_hour")), daily);
        inst.setOnClickListener(v -> {
            Store.p(this).edit().putString("notify_mode", "instant").putLong("prefs_updated", System.currentTimeMillis()).apply();
            setChip(this, inst, true); setChip(this, dig, false); Sync.pushPrefs(this);
        });
        dig.setOnClickListener(v -> pickHour("ساعة الملخص اليومي", "digest_hour", h -> {
            Store.p(this).edit().putString("notify_mode", "daily").putLong("prefs_updated", System.currentTimeMillis()).apply();
            dig.setText("📋 ملخص يومي " + hh(h));
            setChip(this, inst, false); setChip(this, dig, true);
            Sync.pushPrefs(this);
        }));
        mr.addView(inst, weight());
        mr.addView(dig, weight());
        c.addView(mr, lpm(this, -1, -2, 6, 8));

        c.addView(switchRow("🌙 ساعات هدوء (بدون إشعارات ليلاً)", "quiet"));
        LinearLayout qr = row(this);
        TextView from = chip(this, "من " + hh(Store.intv(this, "quiet_from")), false);
        TextView to = chip(this, "إلى " + hh(Store.intv(this, "quiet_to")), false);
        from.setOnClickListener(v -> pickHour("بداية الهدوء", "quiet_from", h -> from.setText("من " + hh(h))));
        to.setOnClickListener(v -> pickHour("نهاية الهدوء", "quiet_to", h -> to.setText("إلى " + hh(h))));
        qr.addView(from, weight());
        qr.addView(to, weight());
        c.addView(qr, lpm(this, -1, -2, 4, 0));
        return c;
    }

    private static String hh(int h) { return String.format(Locale.US, "%02d:00", h); }

    private interface IntCb { void on(int v); }

    private void pickHour(String title, String key, IntCb cb) {
        String[] items = new String[24];
        for (int i = 0; i < 24; i++) items[i] = hh(i);
        new AlertDialog.Builder(this).setTitle(title).setItems(items, (d, w) -> {
            Store.putInt(this, key, w);
            Sync.pushPrefs(this);
            cb.on(w);
        }).show();
    }

    private void suggest(EditText in) {
        String v = in.getText().toString().trim();
        if (v.length() < 4) { Toast.makeText(this, "أدخل رابطاً صحيحاً", Toast.LENGTH_SHORT).show(); return; }
        new AlertDialog.Builder(this).setTitle("نوع المصدر")
                .setItems(new String[]{"📡 شامل — الأولوية للوظائف والمناقصات (والدورات تُلتقط أيضاً)", "🎓 شامل — الأولوية للدورات والمنح والملفات (والوظائف تُلتقط أيضاً)"},
                        (dd, w) -> suggestSend(in, v, w == 1 ? "learn" : "jobs"))
                .setNegativeButton("إلغاء", null).show();
    }

    private void suggestSend(EditText in, String v, String purpose) {
        post("/suggest", jo("value", v, "purpose", purpose), r -> {
            if (r.optBoolean("dup")) {
                String nm = r.isNull("name") ? "" : r.optString("name", "");
                String msg = r.optBoolean("pending") ? "⚠️ هذا المصدر مقترح مسبقاً وينتظر الموافقة"
                        : (nm.isEmpty() ? "⚠️ هذا المصدر موجود مسبقاً، شكراً لك" : "⚠️ هذا المصدر موجود مسبقاً باسم «" + nm + "»");
                new AlertDialog.Builder(this).setMessage(msg).setPositiveButton("حسناً", null).show();
            } else {
                in.setText("");
                Toast.makeText(this, r.optBoolean("pending") ? "✅ شكراً لمساهمتك! سيُراجع الأدمن المصدر" : "✅ أُضيف المصدر، يبدأ الجلب منه خلال ساعة", Toast.LENGTH_LONG).show();
            }
        });
    }

    // =====================================================================
    // صفحات الأدمن
    // =====================================================================
    private void adminSources() {
        Dialog[] dh = new Dialog[1];
        LinearLayout l = fullDialog("المصادر", dh);
        l.addView(text(this, "جارٍ التحميل…", 14, MUTED, false));
        dh[0].show();
        get("/admin/sources", r -> {
            l.removeAllViews();
            JSONArray a = r.optJSONArray("sources");
            if (a == null) return;
            JSONObject tg = r.optJSONObject("tg");
            LinearLayout ac = card(this);
            boolean tok = tg != null && tg.optBoolean("ok");
            ac.addView(text(this, "👤 حساب قراءة تيليغرام (للمجموعات والقنوات الخاصة وبوتات المكتبات)", 14, TEXT, true));
            ac.addView(text(this, tg == null ? "⏸ غير مفعّل بعد — سجّل الدخول من GitHub" : tok ? "✅ متصل" + (tg.optString("name").isEmpty() ? "" : " باسم " + tg.optString("name")) + " • " + ago(tg.optLong("at"))
                    : "❌ متوقف: " + tg.optString("err"), 12, tok ? MUTED : RED, false), lpm(this, -1, -2, 4, 0));
            if (!tok) {
                Button lg = button(this, "🔑 فتح صفحة تسجيل الدخول", false);
                lg.setOnClickListener(v -> openUrl("https://github.com/makanona-team/wazifati/actions/workflows/tg-login.yml"));
                ac.addView(lg, lpm(this, -1, -2, 8, 0));
            }
            l.addView(ac, lpm(this, -1, -2, 0, 8));
            boolean pendHeader = false, actHeader = false;
            for (int i = 0; i < a.length(); i++) {
                JSONObject s = a.optJSONObject(i);
                if (s == null) continue;
                boolean pending = s.optInt("pending") == 1;
                if (pending && !pendHeader) { l.addView(section("🔴 بانتظار موافقتك")); pendHeader = true; }
                if (!pending && !actHeader) { l.addView(section("📡 كل المصادر (" + countActive(a) + ")")); actHeader = true; }
                l.addView(pending ? pendingRow(s, dh[0]) : sourceRow(s, dh[0]), lpm(this, -1, -2, 0, 8));
            }
        });
    }

    private int countActive(JSONArray a) {
        int n = 0;
        for (int i = 0; i < a.length(); i++) { JSONObject s = a.optJSONObject(i); if (s != null && s.optInt("pending") == 0) n++; }
        return n;
    }

    private TextView section(String t) {
        TextView v = text(this, t, 15, PRIMARY, true);
        v.setPadding(dp(this, 4), dp(this, 10), dp(this, 4), dp(this, 6));
        return v;
    }

    private String kindIcon(String k) {
        return k.equals("tg") ? "✈️" : (k.equals("tgp") || k.equals("tgc")) ? "🔒" : k.equals("tgbot") ? "🤖" : k.equals("rss") ? "📰" : k.equals("manual") ? "🤝" : k.equals("link") ? "🔗" : "🌐";
    }

    private String sourceLink(JSONObject s) {
        String k = s.optString("kind"), v = s.optString("value");
        if (k.equals("tgbot")) return "https://t.me/" + v;
        if (k.equals("tgc")) return "https://t.me/c/" + v + "/1";
        return k.equals("tg") ? "https://t.me/s/" + v : k.equals("reliefweb") ? "https://reliefweb.int/jobs?search=" + v : v;
    }

    private static String str(JSONObject o, String k) {
        return o.isNull(k) ? "" : o.optString(k, "");
    }

    private View pendingRow(JSONObject s, Dialog d) {
        LinearLayout c = card(this);
        c.addView(oneLine(text(this, kindIcon(s.optString("kind")) + " " + str(s, "name"), 15, TEXT, true)));
        String orig = str(s, "orig");
        c.addView(oneLine(text(this, orig.isEmpty() ? str(s, "value") : orig, 12, MUTED, false)));
        c.addView(text(this, "اقترحه: " + str(s, "added_by") + " • " + ago(s.optLong("created")), 12, MUTED, false));
        c.setOnClickListener(v -> openUrl(sourceLink(s)));
        LinearLayout r = row(this);
        Button ok = button(this, "✅ قبول", true);
        Button no = button(this, "✕ رفض", false);
        ok.setOnClickListener(v -> post("/admin/sources/approve", jo("id", s.optInt("id"), "ok", true), x -> { d.dismiss(); adminSources(); refreshPending(); }));
        no.setOnClickListener(v -> post("/admin/sources/approve", jo("id", s.optInt("id"), "ok", false), x -> { d.dismiss(); adminSources(); refreshPending(); }));
        r.addView(ok, weight());
        r.addView(no, weight());
        c.addView(r, lpm(this, -1, -2, 8, 0));
        return c;
    }

    private View sourceRow(JSONObject s, Dialog d) {
        String kind = s.optString("kind");
        LinearLayout c = card(this);
        LinearLayout r = row(this);
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.addView(oneLine(text(this, kindIcon(kind) + " " + str(s, "name"), 14, TEXT, true)));
        String err = str(s, "last_err");
        String st;
        if (kind.equals("manual")) st = "فيسبوك/لينكدإن — بالمشاركة فقط";
        else if (!err.isEmpty()) st = "❌ " + err;
        else if (s.optLong("last_ok", 0) > 0) st = "✅ " + ago(s.optLong("last_ok")) + " • أضاف " + s.optInt("total") + " إعلاناً" + (s.optInt("learn_total") > 0 ? " و" + s.optInt("learn_total") + " دورة" : "");
        else st = "⏳ بانتظار أول جلب";
        t.addView(oneLine(text(this, st, 12, err.isEmpty() ? MUTED : RED, false)));
        int rej = s.optInt("rejected"), tot = s.optInt("total");
        if (rej > 0) {
            int pct = Math.round(100f * rej / Math.max(1, rej + tot));
            t.addView(oneLine(text(this, "🗑 رُفض " + rej + " منشوراً ليس وظيفة (" + pct + "%)", 11, pct >= 50 ? ORANGE : MUTED, false)));
        }
        if (kind.equals("tgbot")) {
            JSONObject bst = null;
            try { bst = new JSONObject(s.optString("state", "{}")); } catch (Exception ignored) { }
            if (bst != null) {
                int todo = bst.optJSONArray("todo") != null ? bst.optJSONArray("todo").length() : 0;
                long at = (long) (bst.optDouble("at", 0) * 1000), pause = (long) (bst.optDouble("pause_until", 0) * 1000);
                String line = "📚 حُوّل " + bst.optInt("fwd") + " ملفاً للقناة"
                        + (todo > 0 ? " • باقٍ " + todo + " قسم" : at > 0 ? " • آخر فهرسة كاملة: " + ago(at) + " • القادمة بعد 3 أشهر" : "")
                        + (pause > System.currentTimeMillis() ? "\n⏸ " + bst.optString("note", "متوقف مؤقتاً") : "");
                t.addView(text(this, line, 11, PRIMARY, false));
            }
            TextView rc = text(this, "🔄 أعد الفهرسة الآن", 11, PRIMARY, true);
            rc.setPadding(0, dp(this, 4), 0, dp(this, 2));
            rc.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("إعادة فهرسة البوت؟")
                    .setMessage("تبدأ من الجلب القادم على دفعات بطيئة. الملفات المحوّلة سابقاً لا تُكرّر في القناة.")
                    .setPositiveButton("ابدأ", (dd, w) -> post("/admin/sources/recrawl", jo("id", s.optInt("id")), x -> Toast.makeText(this, "ستبدأ الفهرسة من الجلب القادم", Toast.LENGTH_SHORT).show()))
                    .setNegativeButton("إلغاء", null).show());
            t.addView(rc);
        }
        if (!kind.equals("manual")) {
            final boolean isLearn = "learn".equals(s.optString("purpose"));
            TextView pu = text(this, isLearn ? "🎓 شامل • الأولوية للدورات والملفات  ⇄" : "📡 شامل: وظائف + مناقصات + دورات (الأولوية للوظائف)  ⇄", 11, PRIMARY, true);
            pu.setPadding(0, dp(this, 4), 0, dp(this, 2));
            pu.setOnClickListener(v -> post("/admin/sources/purpose", jo("id", s.optInt("id"), "purpose", isLearn ? "jobs" : "learn"), x -> {
                try { s.put("purpose", isLearn ? "jobs" : "learn"); } catch (Exception ignored) { }
                pu.setText(!isLearn ? "🎓 شامل • الأولوية للدورات والملفات  ⇄" : "📡 شامل: وظائف + مناقصات + دورات (الأولوية للوظائف)  ⇄");
                Toast.makeText(this, "تم التغيير، يُطبّق من الجلب القادم", Toast.LENGTH_SHORT).show();
            }));
            t.addView(pu);
        }
        String by = str(s, "added_by");
        if (!by.isEmpty() && !by.equals("seed") && by.contains("@")) t.addView(oneLine(text(this, "أضافه: " + by, 11, MUTED, false)));
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        Switch sw = new Switch(this);
        sw.setChecked(s.optInt("enabled", 1) == 1);
        sw.setOnCheckedChangeListener((b, v) -> post("/admin/sources/toggle", jo("id", s.optInt("id"), "enabled", v), null));
        r.addView(sw);
        c.addView(r);
        t.setOnClickListener(v -> openUrl(sourceLink(s)));
        t.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this).setMessage("حذف المصدر «" + str(s, "name") + "»؟")
                    .setPositiveButton("حذف", (x, w) -> post("/admin/sources/delete", jo("id", s.optInt("id")), y -> { d.dismiss(); adminSources(); }))
                    .setNegativeButton("إلغاء", null).show();
            return true;
        });
        return c;
    }

    private void adminUsers() {
        Dialog[] dh = new Dialog[1];
        LinearLayout l = fullDialog("المستخدمون", dh);
        l.addView(text(this, "جارٍ التحميل…", 14, MUTED, false));
        dh[0].show();
        get("/admin/users", r -> {
            l.removeAllViews();
            JSONArray a = r.optJSONArray("users");
            if (a == null) return;
            l.addView(section("👥 " + a.length() + " مستخدم"));
            java.util.TreeMap<String, Integer> vc = new java.util.TreeMap<>((x, y) -> Updater.cmp(y, x));
            String latest = Store.p(this).getString("upd_ver", "");
            for (int k = 0; k < a.length(); k++) {
                JSONObject uu = a.optJSONObject(k);
                String vv = uu == null ? "" : str(uu, "ver");
                vc.merge(vv.isEmpty() ? "?" : vv, 1, Integer::sum);
            }
            StringBuilder vs = new StringBuilder("📲 النسخ المنتشرة:");
            for (java.util.Map.Entry<String, Integer> e : vc.entrySet()) vs.append("\n  ").append(e.getKey()).append(" ← ").append(e.getValue()).append(" مستخدم");
            LinearLayout vcard = card(this);
            vcard.addView(text(this, vs.toString(), 13, TEXT, false));
            l.addView(vcard, lpm(this, -1, -2, 0, 8));
            String meEmail = Store.p(this).getString("email", "");
            for (int i = 0; i < a.length(); i++) {
                JSONObject u = a.optJSONObject(i);
                if (u == null) continue;
                String email = str(u, "email");
                boolean isAdmin = "admin".equals(str(u, "role")), blocked = "blocked".equals(str(u, "status"));
                LinearLayout c = card(this);
                String name = str(u, "name");
                c.addView(oneLine(text(this, (blocked ? "⛔ " : isAdmin ? "🛠 " : "👤 ") + (name.isEmpty() ? email : name), 15, TEXT, true)));
                c.addView(oneLine(text(this, email, 12, MUTED, false)));
                String model = str(u, "model"), ver = str(u, "ver");
                c.addView(text(this, "🕒 آخر فتح: " + (u.optLong("last_seen") > 0 ? ago(u.optLong("last_seen")) : "—")
                        + "  •  🔄 آخر مزامنة: " + (u.optLong("last_sync") > 0 ? ago(u.optLong("last_sync")) : "—")
                        + "\nمصادر: " + u.optInt("contrib") + " • مشاركات: " + u.optInt("shares")
                        + (model.isEmpty() ? "" : "\n📱 " + model + (ver.isEmpty() ? "" : " • v" + ver + (!latest.isEmpty() && Updater.cmp(latest, ver) > 0 ? " 🟠 قديم" : ""))), 12, MUTED, false));
                if (!email.equals("eng.feras8585@gmail.com") && !email.equals(meEmail)) {
                    LinearLayout r2 = row(this);
                    Button role = button(this, isAdmin ? "إزالة صلاحية الأدمن" : "اجعله أدمن", false);
                    String who = name.isEmpty() ? email : name;
                    role.setOnClickListener(v -> new AlertDialog.Builder(this)
                            .setMessage(isAdmin ? "إزالة صلاحية الأدمن من " + who + "؟" : "هل تريد جعل " + who + " أدمن؟\nسيرى المصادر والتصحيحات والبلاغات ويستطيع الموافقة والإدارة (دون صلاحية عرض المستخدمين أو رفع أدمن).")
                            .setPositiveButton("نعم", (x, w) -> post("/admin/users/role", jo("email", email, "role", isAdmin ? "user" : "admin"), y -> { dh[0].dismiss(); adminUsers(); }))
                            .setNegativeButton("إلغاء", null).show());
                    Button blk = button(this, blocked ? "✅ تفعيل" : "⛔ إيقاف", false);
                    blk.setOnClickListener(v -> new AlertDialog.Builder(this).setMessage((blocked ? "تفعيل " : "إيقاف ") + email + "؟")
                            .setPositiveButton("نعم", (x, w) -> post("/admin/users/status", jo("email", email, "status", blocked ? "active" : "blocked"), y -> { dh[0].dismiss(); adminUsers(); }))
                            .setNegativeButton("إلغاء", null).show());
                    r2.addView(role, weight());
                    r2.addView(blk, weight());
                    c.addView(r2, lpm(this, -1, -2, 8, 0));
                }
                l.addView(c, lpm(this, -1, -2, 0, 8));
            }
        });
    }

    private void adminFixes() {
        Dialog[] dh = new Dialog[1];
        LinearLayout l = fullDialog("تصحيحات التصنيف", dh);
        l.addView(text(this, "جارٍ التحميل…", 14, MUTED, false));
        dh[0].show();
        get("/admin/fixes", r -> {
            l.removeAllViews();
            JSONArray a = r.optJSONArray("fixes");
            if (a == null || a.length() == 0) { l.addView(text(this, "لا توجد تصحيحات بانتظار الموافقة ✅", 15, MUTED, false)); return; }
            for (int i = 0; i < a.length(); i++) {
                JSONObject f = a.optJSONObject(i);
                if (f == null) continue;
                LinearLayout c = card(this);
                String tt = str(f, "title");
                c.addView(text(this, tt.isEmpty() ? "إعلان محذوف" : tt, 15, TEXT, true));
                if (!str(f, "specs").isEmpty() || !str(f, "govs").isEmpty()) {
                    c.addView(text(this, "الحالي: 🏷 " + Labels.names(Labels.SPECS, str(f, "j_specs")) + "  📍 " + Labels.names(Labels.GOVS, str(f, "j_govs")), 12, MUTED, false));
                    c.addView(text(this, "المقترح: 🏷 " + Labels.names(Labels.SPECS, str(f, "specs")) + "  📍 " + Labels.names(Labels.GOVS, str(f, "govs")), 13, GREEN, true));
                }
                if (!str(f, "s_title").isEmpty()) c.addView(text(this, "✏️ العنوان المقترح: " + str(f, "s_title"), 13, GREEN, true));
                if (!str(f, "s_org").isEmpty()) c.addView(text(this, "🏢 الجهة المقترحة: " + str(f, "s_org") + (str(f, "j_org").isEmpty() ? "" : "  (الحالية: " + str(f, "j_org") + ")"), 13, GREEN, true));
                c.addView(text(this, "من: " + str(f, "email") + " • " + ago(f.optLong("created")), 11, MUTED, false));
                LinearLayout r2 = row(this);
                Button ok = button(this, "✅ اعتماد", true);
                Button no = button(this, "✕ رفض", false);
                ok.setOnClickListener(v -> post("/admin/fixes/decide", jo("id", f.optInt("id"), "ok", true), x -> { dh[0].dismiss(); adminFixes(); refreshPending(); }));
                no.setOnClickListener(v -> post("/admin/fixes/decide", jo("id", f.optInt("id"), "ok", false), x -> { dh[0].dismiss(); adminFixes(); refreshPending(); }));
                r2.addView(ok, weight());
                r2.addView(no, weight());
                c.addView(r2, lpm(this, -1, -2, 8, 0));
                l.addView(c, lpm(this, -1, -2, 0, 8));
            }
        });
    }

    private void adminAnnounce() {
        EditText e = input("نص الإعلان…");
        e.setMinLines(3);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(this, 18), dp(this, 8), dp(this, 18), 0);
        box.addView(e, lp(-1, -2));
        new AlertDialog.Builder(this).setTitle("📢 إعلان لكل المستخدمين").setMessage("يظهر في قائمة الإشعارات 🔔 عند الجميع ويصل كإشعار على الهاتف.").setView(box)
                .setPositiveButton("إرسال", (d, w) -> post("/admin/announce", jo("text", e.getText().toString().trim()), r -> {
                    Toast.makeText(this, "✅ أُرسل الإعلان", Toast.LENGTH_SHORT).show();
                    Context app = getApplicationContext();
                    new Thread(() -> {
                        try { Sync.syncAnnouncements(app, false); } catch (Throwable ignored) { }
                        ui.post(() -> { setStatus(); showAnnouncements(); });
                    }).start();
                }))
                .setNegativeButton("إلغاء", null).show();
    }

    private void adminStats() {
        Dialog[] dh = new Dialog[1];
        LinearLayout l = fullDialog("الإحصائيات", dh);
        l.addView(text(this, "جارٍ التحميل…", 14, MUTED, false));
        dh[0].show();
        get("/admin/stats", r -> {
            l.removeAllViews();
            LinearLayout c = card(this);
            c.addView(text(this, "📦 الإعلانات المحفوظة: " + r.optInt("jobs") + "\n🆕 آخر 24 ساعة: " + r.optInt("today")
                    + "\n👥 المستخدمون: " + r.optInt("users") + " (نشطون هذا الأسبوع: " + r.optInt("active7") + ")"
                    + "\n🔄 آخر جلب: " + (r.optLong("lastRun") > 0 ? ago(r.optLong("lastRun")) : "—"), 15, TEXT, false));
            l.addView(c, lpm(this, -1, -2, 0, 10));

            JSONObject ins = r.optJSONObject("installs");
            if (ins != null) {
                LinearLayout ic = card(this);
                ic.addView(text(this, "📱 التثبيتات والأجهزة والزوار", 15, TEXT, true));
                int tot = ins.optInt("total"), reg = ins.optInt("registered"), gst = ins.optInt("guest");
                int regPct = tot > 0 ? Math.round(100f * reg / tot) : 0;
                int gstPct = tot > 0 ? 100 - regPct : 0;
                ic.addView(text(this, "📲 إجمالي الهواتف المثبّتة: " + tot
                        + "\n✅ مسجّلون بحساب Google: " + reg + " (" + regPct + "%)"
                        + "\n👀 زوار بدون حساب: " + gst + " (" + gstPct + "%)"
                        + "\n🕒 نشطون آخر 7 أيام: " + ins.optInt("active7")
                        + (ins.optInt("rooted") > 0 ? "\n🔓 أجهزة عليها روت: " + ins.optInt("rooted") : ""), 14, TEXT, false));
                JSONArray mods = ins.optJSONArray("topModels");
                if (mods != null && mods.length() > 0) {
                    StringBuilder ms = new StringBuilder("\nأكثر الهواتف انتشاراً:");
                    for (int i = 0; i < mods.length(); i++) {
                        JSONObject m = mods.optJSONObject(i);
                        ms.append("\n  • ").append(str(m, "model")).append(" ← ").append(m.optInt("n"));
                    }
                    ic.addView(text(this, ms.toString(), 13, MUTED, false));
                }
                JSONArray avs = ins.optJSONArray("androidVers");
                if (avs != null && avs.length() > 0) {
                    StringBuilder as = new StringBuilder("\nإصدارات أندرويد: ");
                    for (int i = 0; i < avs.length(); i++) {
                        JSONObject a2 = avs.optJSONObject(i);
                        if (i > 0) as.append(" • ");
                        as.append("Android ").append(str(a2, "v")).append(" (").append(a2.optInt("n")).append(")");
                    }
                    ic.addView(text(this, as.toString(), 12, MUTED, false));
                }
                l.addView(ic, lpm(this, -1, -2, 0, 10));
            }
            JSONObject del = r.optJSONObject("deletions");
            if (del != null && del.optInt("total") > 0) {
                LinearLayout dl = card(this);
                dl.addView(text(this, "🗑 الحسابات المحذوفة (" + del.optInt("total") + ")", 15, RED, true));
                JSONArray br = del.optJSONArray("byReason");
                if (br != null) for (int i = 0; i < br.length(); i++) {
                    JSONObject o = br.optJSONObject(i);
                    dl.addView(text(this, "• " + delReasonLabel(str(o, "reason")) + ": " + o.optInt("n"), 13, TEXT, false));
                }
                l.addView(dl, lpm(this, -1, -2, 0, 10));
            }
                        JSONArray days = r.optJSONArray("days");
            if (days != null) {
                LinearLayout dc = card(this);
                dc.addView(text(this, "📈 الفرص الجديدة يومياً (آخر 14 يوم)", 15, TEXT, true));
                int max = 1;
                for (int i = 0; i < days.length(); i++) max = Math.max(max, days.optJSONObject(i).optInt("n"));
                for (int i = 0; i < days.length(); i++) {
                    JSONObject dd = days.optJSONObject(i);
                    LinearLayout rr = row(this);
                    rr.setPadding(0, dp(this, 3), 0, dp(this, 3));
                    String day = dd.optString("day");
                    rr.addView(text(this, day.length() > 5 ? day.substring(5) : day, 12, MUTED, false), new LinearLayout.LayoutParams(dp(this, 48), -2));
                    View bar = new View(this);
                    bar.setBackground(round(this, PRIMARY, 4));
                    int w = Math.max(dp(this, 2), (int) (dp(this, 200) * dd.optInt("n") / (float) max));
                    rr.addView(bar, new LinearLayout.LayoutParams(w, dp(this, 12)));
                    rr.addView(text(this, "  " + dd.optInt("n"), 12, TEXT, false));
                    dc.addView(rr);
                }
                l.addView(dc, lpm(this, -1, -2, 0, 10));
            }
            JSONArray top = r.optJSONArray("topSources");
            if (top != null) {
                LinearLayout tc = card(this);
                tc.addView(text(this, "🏆 أكثر المصادر إفادة", 15, TEXT, true));
                for (int i = 0; i < top.length(); i++) {
                    JSONObject s = top.optJSONObject(i);
                    tc.addView(text(this, (i + 1) + ". " + str(s, "name") + " — " + s.optInt("total") + " إعلان", 13, TEXT, false));
                }
                l.addView(tc, lpm(this, -1, -2, 0, 10));
            }
            JSONArray noisy = r.optJSONArray("noisy");
            if (noisy != null && noisy.length() > 0) {
                LinearLayout nc = card(this);
                nc.addView(text(this, "🗑 أكثر المصادر نشراً لغير الوظائف", 15, ORANGE, true));
                for (int i = 0; i < noisy.length(); i++) {
                    JSONObject s = noisy.optJSONObject(i);
                    int rj = s.optInt("rejected"), tt = s.optInt("total");
                    nc.addView(text(this, "• " + str(s, "name") + ": " + Math.round(100f * rj / Math.max(1, rj + tt)) + "% مرفوض (" + rj + " من " + (rj + tt) + ")", 13, TEXT, false));
                }
                l.addView(nc, lpm(this, -1, -2, 0, 10));
            }
            JSONObject cl = r.optJSONObject("cleanup");
            if (cl != null) {
                LinearLayout cc = card(this);
                cc.addView(text(this, "🧹 التنظيف التلقائي: حُذف " + cl.optInt("removed") + " منشوراً ليس وظيفة، ووُسم " + cl.optInt("doubt") + " كمشكوك", 13, TEXT, false));
                l.addView(cc, lpm(this, -1, -2, 0, 10));
            }
            JSONArray broken = r.optJSONArray("broken");
            if (broken != null && broken.length() > 0) {
                LinearLayout bc = card(this);
                bc.addView(text(this, "⚠️ مصادر معطلة", 15, RED, true));
                for (int i = 0; i < broken.length(); i++) {
                    JSONObject s = broken.optJSONObject(i);
                    bc.addView(text(this, "• " + str(s, "name") + ": " + str(s, "last_err"), 13, TEXT, false));
                }
                l.addView(bc, lp(-1, -2));
            }
        });
    }

    // صورة الإعلان (منشورات تيليغرام بالصورة)
    private View imageView(String url) {
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        iv.setBackground(round(this, CARD, 14));
        iv.setMinimumHeight(dp(this, 160));
        iv.setContentDescription("صورة الإعلان");
        new Thread(() -> {
            android.graphics.Bitmap bm = loadBitmap(url);
            ui.post(() -> {
                if (bm != null) {
                    iv.setImageBitmap(bm);
                    iv.setOnClickListener(v -> {
                        Dialog full = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
                        android.widget.ImageView big = new android.widget.ImageView(this);
                        big.setImageBitmap(bm);
                        big.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                        big.setBackgroundColor(Color.BLACK);
                        big.setOnClickListener(x -> full.dismiss());
                        full.setContentView(big);
                        full.show();
                        Toast.makeText(this, "اضغط على الصورة للإغلاق", Toast.LENGTH_SHORT).show();
                    });
                } else {
                    iv.setMinimumHeight(0);
                    iv.setVisibility(View.GONE);
                }
            });
        }).start();
        return iv;
    }

    private static android.graphics.Bitmap loadBitmap(String url) {
        try {
            java.net.HttpURLConnection con = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            con.setConnectTimeout(15000);
            con.setReadTimeout(30000);
            con.setRequestProperty("User-Agent", "Mozilla/5.0");
            try (java.io.InputStream in = con.getInputStream()) {
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
                return android.graphics.BitmapFactory.decodeStream(in, null, o);
            }
        } catch (Throwable e) {
            return null;
        }
    }

    private void adminFlags() {
        Dialog[] dh = new Dialog[1];
        LinearLayout l = fullDialog("البلاغات", dh);
        l.addView(text(this, "جارٍ التحميل…", 14, MUTED, false));
        dh[0].show();
        get("/admin/flags", r -> {
            l.removeAllViews();
            JSONArray a = r.optJSONArray("flags");
            if (a == null || a.length() == 0) { l.addView(text(this, "لا توجد بلاغات ✅", 15, MUTED, false)); return; }
            l.addView(text(this, "الإعلانات التي أبلغ عنها المستخدمون أنها ليست وظائف. ما وصل لبلاغين أُخفي تلقائياً عن الجميع.", 13, MUTED, false));
            for (int i = 0; i < a.length(); i++) {
                JSONObject f = a.optJSONObject(i);
                if (f == null) continue;
                LinearLayout c = card(this);
                c.addView(text(this, str(f, "title"), 15, TEXT, true));
                c.addView(text(this, "🚩 " + f.optInt("n") + " بلاغ" + (f.optInt("hidden") > 0 ? " • مخفي عن الجميع" : " • ما زال ظاهراً") + "\nمن: " + str(f, "who") + "\nالمصدر: " + str(f, "src"), 12, MUTED, false));
                String u = str(f, "url");
                if (u.startsWith("http")) c.setOnClickListener(v -> openUrl(u));
                LinearLayout r2 = row(this);
                Button del = button(this, "🗑 حذف نهائياً", true);
                Button res = button(this, "↩ إعادة (إعلان سليم)", false);
                del.setOnClickListener(v -> post("/admin/flags/decide", jo("id", f.optInt("id"), "action", "delete"), x -> { dh[0].dismiss(); adminFlags(); refreshPending(); }));
                res.setOnClickListener(v -> post("/admin/flags/decide", jo("id", f.optInt("id"), "action", "restore"), x -> { dh[0].dismiss(); adminFlags(); refreshPending(); }));
                r2.addView(del, weight());
                r2.addView(res, weight());
                c.addView(r2, lpm(this, -1, -2, 8, 0));
                l.addView(c, lpm(this, -1, -2, 0, 8));
            }
        });
    }

    // =====================================================================
    // حذف الحساب نهائياً (2.4)
    // =====================================================================
    private static final String[][] DEL_REASONS = {
            {"found_job", "وجدت وظيفة مناسبة 🎉"},
            {"not_relevant", "الوظائف لا تناسب اختصاصي أو منطقتي"},
            {"too_many_notifs", "كثرة الإشعارات"},
            {"tech_issue", "مشكلة تقنية أو بطء في التطبيق"},
            {"privacy", "أسباب تتعلق بالخصوصية"},
            {"other", "سبب آخر"},
    };

    private static String delReasonLabel(String code) {
        for (String[] r : DEL_REASONS) if (r[0].equals(code)) return r[1];
        return code;
    }

    private void deleteAccountStep1() {
        String[] labels = new String[DEL_REASONS.length];
        for (int i = 0; i < DEL_REASONS.length; i++) labels[i] = DEL_REASONS[i][1];
        final int[] picked = {-1};
        EditText note = input("ملاحظة إضافية لتحسين التطبيق (اختياري)");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(this, 18), dp(this, 4), dp(this, 18), 0);
        box.addView(note, lp(-1, -2));
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("🗑 لماذا تريد حذف حسابك؟")
                .setSingleChoiceItems(labels, -1, (d, which) -> picked[0] = which)
                .setView(box)
                .setPositiveButton("متابعة ←", null)
                .setNegativeButton("إلغاء", null)
                .create();
        dlg.setOnShowListener(x -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (picked[0] < 0) {
                Toast.makeText(this, "يرجى اختيار سبب الحذف أولاً", Toast.LENGTH_SHORT).show();
                return;
            }
            dlg.dismiss();
            deleteAccountStep2(DEL_REASONS[picked[0]][0], note.getText().toString().trim());
        }));
        dlg.show();
    }

    private void deleteAccountStep2(String reason, String note) {
        EditText confirm = input("اكتب كلمة delete هنا للتأكيد");
        confirm.setSingleLine(true);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(this, 18), dp(this, 8), dp(this, 18), 0);
        box.addView(confirm, lp(-1, -2));
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("⚠️ تأكيد الحذف النهائي")
                .setMessage("سيُمسح حسابك وسيرتك الذاتية ومحفوظاتك وإعداداتك نهائياً من الخادم ولا يمكن التراجع.\n\nاكتب عبارة  delete  للتأكيد:")
                .setView(box)
                .setPositiveButton("حذف نهائي", (d, w) -> post("/account/delete", jo("reason", reason, "note", note, "confirm", confirm.getText().toString().trim()), r -> {
                    Sync.pingInstall(this, true);
                    signedOut("تم حذف حسابك وجميع بياناتك نهائياً");
                }))
                .setNegativeButton("تراجع", null)
                .create();
        dlg.setOnShowListener(x -> {
            Button ok = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
            ok.setTextColor(RED);
            ok.setEnabled(false);
            confirm.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                public void onTextChanged(CharSequence s, int a, int b, int c) { }
                public void afterTextChanged(Editable s) {
                    ok.setEnabled("delete".equalsIgnoreCase(s.toString().trim()));
                }
            });
        });
        dlg.show();
    }

    // =====================================================================
    // الأدمن: مفاتيح التشغيل عن بُعد (2.4)
    // =====================================================================
    private void adminFeatures() {
        Dialog[] dh = new Dialog[1];
        LinearLayout l = fullDialog("مفاتيح الميزات عن بُعد", dh);
        l.addView(text(this, "جارٍ التحميل…", 14, MUTED, false));
        dh[0].show();
        get("/admin/features", r -> {
            l.removeAllViews();
            JSONObject f = r.optJSONObject("flags");
            if (f == null) return;
            Store.saveFlags(this, f);
            l.addView(text(this, "تغييراتك تُحفظ فوراً على الخادم وتُطبّق عند المستخدمين في المزامنة التالية دون الحاجة لتحديث التطبيق.", 13, MUTED, false));
            String[][] items = {
                    {"tenders", "📑 قسم المناقصات"},
                    {"cv_match", "📄 السيرة الذاتية ونسبة التطابق"},
                    {"suggest_source", "➕ اقتراح المصادر من المستخدمين"},
                    {"share_job", "🤝 بطاقة مشاركة الفرص"},
                    {"guest_mode", "👀 التصفّح كضيف بدون حساب"},
                    {"learn_tab", "📚 قسم التعلّم والمراجع"},
            };
            for (String[] it : items) {
                LinearLayout c = card(this);
                Switch sw = new Switch(this);
                sw.setText(it[1]);
                sw.setTextSize(15);
                sw.setTextColor(TEXT);
                sw.setChecked(f.optBoolean(it[0], true));
                sw.setOnCheckedChangeListener((b, on) -> post("/admin/features", jo("key", it[0], "enabled", on), res -> {
                    if (res.has("flags")) Store.saveFlags(this, res.optJSONObject("flags"));
                    Toast.makeText(this, on ? "✅ تم التفعيل" : "⛔ تم الإيقاف", Toast.LENGTH_SHORT).show();
                }));
                c.addView(sw);
                l.addView(c, lpm(this, -1, -2, 8, 0));
            }
        });
    }

    // =====================================================================
    // الأدمن: تقارير الأخطاء وفشل تسجيل الدخول (2.4)
    // =====================================================================
    private boolean reportsAll = false;

    private void adminReports() {
        Dialog[] dh = new Dialog[1];
        LinearLayout l = fullDialog("الأخطاء وفشل الدخول", dh);
        l.addView(text(this, "جارٍ التحميل…", 14, MUTED, false));
        dh[0].show();
        get("/admin/reports" + (reportsAll ? "?all=1" : ""), r -> {
            l.removeAllViews();
            LinearLayout fr = row(this);
            TextView c1 = chip(this, "🔴 الجديدة", !reportsAll), c2 = chip(this, "الكل", reportsAll);
            c1.setOnClickListener(v -> { reportsAll = false; dh[0].dismiss(); adminReports(); });
            c2.setOnClickListener(v -> { reportsAll = true; dh[0].dismiss(); adminReports(); });
            fr.addView(c1, lp(-2, -2));
            LinearLayout.LayoutParams p2 = lp(-2, -2);
            p2.rightMargin = dp(this, 6);
            fr.addView(c2, p2);
            l.addView(fr, lpm(this, -1, -2, 0, 8));
            JSONArray a = r.optJSONArray("reports");
            if (a == null || a.length() == 0) { l.addView(text(this, reportsAll ? "لا توجد تقارير أخطاء." : "لا توجد أخطاء جديدة ✅", 15, MUTED, false)); return; }
            if (!reportsAll) {
                Button clrAll = button(this, "✔ تعليم الكل كمحلول", false);
                clrAll.setOnClickListener(v -> post("/admin/reports/clear", jo("all", true), x -> { dh[0].dismiss(); adminReports(); refreshPending(); }));
                l.addView(clrAll, lpm(this, -1, -2, 0, 8));
            }
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                final long id = o.optLong("id");
                boolean authFail = "auth_fail".equals(str(o, "kind"));
                boolean rooted = o.optInt("rooted") == 1;
                LinearLayout c = card(this);
                c.addView(text(this, (authFail ? "🔑 فشل دخول" : "💥 انهيار") + " • " + str(o, "err_code") + " (×" + o.optInt("count", 1) + ")", 15, authFail ? ORANGE : RED, true));
                c.addView(text(this, "📱 " + str(o, "model") + " • Android " + str(o, "android_ver") + " • v" + str(o, "app_ver") + (rooted ? " • 🔓 روت" : ""), 12, MUTED, false));
                c.addView(text(this, "🕒 آخر مرة: " + ago(o.optLong("last_seen")), 11, MUTED, false));
                String msg = str(o, "msg");
                if (!msg.isEmpty()) {
                    TextView mt = text(this, msg, 13, TEXT, false);
                    mt.setPadding(0, dp(this, 4), 0, 0);
                    mt.setTextIsSelectable(true);
                    c.addView(mt);
                }
                String tr = str(o, "trace");
                LinearLayout r2 = row(this);
                if (!tr.isEmpty()) {
                    Button showTr = button(this, "📄 السجل", false);
                    showTr.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("تفاصيل السجل").setMessage(tr).setPositiveButton("إغلاق", null).show());
                    r2.addView(showTr, weight());
                }
                if (o.optInt("resolved") == 0) {
                    Button ok = button(this, "✔ تم الحل", true);
                    ok.setOnClickListener(v -> post("/admin/reports/clear", jo("id", id), x -> { dh[0].dismiss(); adminReports(); refreshPending(); }));
                    r2.addView(ok, weight());
                }
                Button dl = button(this, "🗑 حذف", false);
                dl.setTextColor(RED);
                dl.setOnClickListener(v -> post("/admin/reports/clear", jo("deleteId", id), x -> { dh[0].dismiss(); adminReports(); refreshPending(); }));
                r2.addView(dl, weight());
                c.addView(r2, lpm(this, -1, -2, 8, 0));
                l.addView(c, lpm(this, -1, -2, 0, 8));
            }
        });
    }

    private void refreshPending() {
        Context app = getApplicationContext();
        new Thread(() -> {
            try { Sync.syncAdmin(app, false); } catch (Throwable ignored) { }
            ui.post(() -> { setStatus(); if (tab == T_MORE) show(T_MORE); });
        }).start();
    }

    // =====================================================================
    // أدوات الشبكة
    // =====================================================================
    private interface Res { void on(JSONObject r); }

    private static JSONObject jo(Object... kv) {
        JSONObject j = new JSONObject();
        try { for (int i = 0; i + 1 < kv.length; i += 2) j.put(String.valueOf(kv[i]), kv[i + 1]); } catch (Exception ignored) { }
        return j;
    }

    private void post(String path, JSONObject body, Res after) { net("POST", path, body, after); }

    private void get(String path, Res after) { net("GET", path, null, after); }

    private void net(String method, String path, JSONObject body, Res after) {
        new Thread(() -> {
            String err = null;
            boolean auth = false;
            JSONObject r = null;
            try { r = method.equals("GET") ? Api.get(path) : Api.post(path, body); }
            catch (Api.AuthException e) { auth = true; err = e.getMessage(); }
            catch (Throwable e) { err = e.getMessage(); }
            final String fe = err;
            final boolean fa = auth;
            final JSONObject fr = r;
            ui.post(() -> {
                if (isFinishing() || path.equals("/logout")) return;
                if (fa) { signedOut(fe); return; }
                if (fe != null) Toast.makeText(this, "خطأ: " + fe, Toast.LENGTH_LONG).show();
                else if (after != null) after.on(fr);
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
            try { info = Updater.fetch(getApplicationContext()); } catch (Throwable e) { err = e.getMessage(); }
            final Updater.Info fi = info;
            final String fe = err;
            ui.post(() -> {
                if (isFinishing()) return;
                if (fi == null) { if (!quiet) Toast.makeText(this, "تعذّر الفحص: " + fe, Toast.LENGTH_LONG).show(); return; }
                if (Updater.cmp(fi.version, Updater.current(this)) <= 0) {
                    if (!quiet) Toast.makeText(this, "لديك آخر إصدار ✅", Toast.LENGTH_SHORT).show();
                    return;
                }
                showUpdateDialog(fi);
            });
        }).start();
    }

    private void showUpdateDialog(Updater.Info fi) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this).setTitle("إصدار جديد " + fi.version)
                .setMessage(Updater.clean(fi.notes))
                .setPositiveButton("تحميل وتثبيت", (d, w) -> download(fi))
                .setNegativeButton("لاحقاً", null).show();
    }

    private String roleLabel() {
        if (guest()) return "👀 زائر";
        String em = Store.p(this).getString("email", "");
        if ("eng.feras8585@gmail.com".equalsIgnoreCase(em)) return "🟢 مطوّر التطبيق";
        return Store.admin(this) ? "🔵 مشرف" : "⚪ مستخدم";
    }

    /** شريط التحديث في الصفحة الرئيسية (3.0.2): نسختك + المتوفر، والضغط ينزّل ويثبّت من داخل التطبيق. */
    private View updateBanner() {
        String cur = Updater.current(this);
        Updater.Info n = Updater.newer(this);
        if (n == null) {
            TextView ok = text(this, "✅ نسختك " + cur + " (الأحدث)", 11, MUTED, false);
            ok.setGravity(Gravity.CENTER);
            return ok;
        }
        android.content.SharedPreferences p = Store.p(this);
        boolean snoozed = n.version.equals(p.getString("upd_snooze_v", "")) && System.currentTimeMillis() < p.getLong("upd_snooze", 0);
        if (snoozed) {
            TextView s = text(this, "📱 نسختك " + cur + " • متوفر " + n.version + " — اضغط للتحديث", 12, ORANGE, true);
            s.setGravity(Gravity.CENTER);
            s.setPadding(0, dp(this, 2), 0, dp(this, 2));
            s.setOnClickListener(v -> download(n));
            return s;
        }
        LinearLayout b = row(this);
        b.setBackground(round(this, ORANGE, 12));
        b.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
        b.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.addView(text(this, "📱 نسختك " + cur + "  •  ✨ متوفر " + n.version + (n.pre ? "  🧪 تجريبية" : ""), 14, Color.WHITE, true));
        tx.addView(text(this, "اضغط هنا للتحديث الآن — يُنزَّل ويُثبَّت من داخل التطبيق", 12, Color.WHITE, false));
        b.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
        TextView later = text(this, "لاحقاً", 12, Color.WHITE, true);
        later.setPadding(dp(this, 10), dp(this, 6), 0, dp(this, 6));
        later.setOnClickListener(v -> {
            p.edit().putString("upd_snooze_v", n.version).putLong("upd_snooze", System.currentTimeMillis() + 24 * 3_600_000L).apply();
            show(tab);
        });
        b.addView(later);
        b.setOnClickListener(v -> download(n));
        return b;
    }

    private View welcomeCard() {
        LinearLayout w = row(this);
        w.setBackground(round(this, PRIMARY_LIGHT, 12));
        w.setPadding(dp(this, 12), dp(this, 8), dp(this, 12), dp(this, 8));
        String full = Store.p(this).getString("uname", "").trim();
        String first = full.isEmpty() ? "" : full.split("\\s+")[0];
        String hi = guest() || first.isEmpty() ? "مرحباً بك 👋" : "مرحباً " + first + " 👋";
        w.addView(text(this, hi, 16, TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView badge = pill(this, roleLabel(), guest() ? MUTED : (Store.admin(this) ? GREEN : PRIMARY));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
        bl.setMarginStart(dp(this, 6));
        w.addView(badge, bl);
        return w;
    }


    @SuppressWarnings("deprecation")
    private Updater.Info pendingUpd;

    private void download(Updater.Info info) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            pendingUpd = info;
            Toast.makeText(this, "اسمح لوظيفتي بتثبيت التطبيقات وسيكمل التحديث تلقائياً", Toast.LENGTH_LONG).show();
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
                safeDismiss(pd);
                if (fe != null) Toast.makeText(getApplicationContext(), "فشل التحديث: " + fe, Toast.LENGTH_LONG).show();
            });
        }).start();
    }
}
