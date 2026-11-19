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
    static final int T_JOBS = 0, T_TENDERS = 1, T_SAVED = 2, T_FILTER = 3, T_MORE = 4;
    private final TextView[] tabs = new TextView[5];
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
        getWindow().setStatusBarColor(HEADER);
        getWindow().setNavigationBarColor(CARD);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        Sync.init(this);
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
            try {
                JSONObject r = Api.get("/api");
                Store.p(app).edit().putString("min_ver", r.optString("minVer", "")).putString("min_msg", r.optString("minMsg", "")).apply();
            } catch (Throwable ignored) { return; }
            ui.post(() -> { if (!isFinishing() && outdated()) recreate(); });
        }).start();
    }

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
                try { info = Updater.fetch(); } catch (Throwable x) { err = x.getMessage(); }
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
                                Updater.Info latest = Updater.fetch();
                                if (Updater.cmp(v, latest.version) > 0) throw new Exception("الحد (" + v + ") أعلى من آخر إصدار منشور (" + latest.version + ")، سيُحجب الجميع بلا تحديث متاح");
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
        TextView logo = text(this, "💼", 64, TEXT, false);
        logo.setGravity(Gravity.CENTER);
        l.addView(logo);
        TextView t = text(this, "وظيفتي", 30, PRIMARY, true);
        t.setGravity(Gravity.CENTER);
        l.addView(t);
        TextView s = text(this, "فرص العمل والمناقصات في سوريا من تيليغرام ومواقع التوظيف، في مكان واحد.\nنصنّفها حسب اختصاصك ومحافظتك وننبّهك بالجديد.\nتصفّح الآن بلا حساب، أو سجّل الدخول لتحفظ فرصك وتفعّل الإشعارات.", 15, MUTED, false);
        s.setGravity(Gravity.CENTER);
        s.setPadding(0, dp(this, 8), 0, dp(this, 28));
        l.addView(s);
        Button g = button(this, "G   الدخول بحساب Google", true);
        g.setTextSize(17);
        g.setMinHeight(dp(this, 54));
        l.addView(g, lp(-1, -2));
        Button gu = button(this, "👀  تصفّح كضيف بدون حساب", false);
        gu.setMinHeight(dp(this, 50));
        gu.setOnClickListener(v -> { Store.p(this).edit().putBoolean("guest", true).apply(); recreate(); });
        l.addView(gu, lpm(this, -1, -2, 10, 0));
        TextView note = text(this, "بدون كلمة سر — اختر حسابك بضغطة واحدة.\nإعداداتك ومحفوظاتك تُحفظ على حسابك وتعود على أي هاتف.", 13, MUTED, false);
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
                        if (msg != null && !msg.isEmpty()) Toast.makeText(MainActivity.this, "تعذّر الدخول: " + msg, Toast.LENGTH_LONG).show();
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
            } catch (Throwable e) { err = e.getMessage(); }
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
        ht.addView(oneLine(text(this, Ui.today(), 12, 0xEEFFFFFF, false)));
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
        String[] names = {"🔎\nالفرص", "📑\nالمناقصات", "⭐\nالمحفوظة", "🎯\nفلتري", "☰\nالمزيد"};
        for (int i = 0; i < 5; i++) {
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
    }

    private TextView headBtn(String icon, String label) {
        TextView t = text(this, icon + "\n" + label, 11, Color.WHITE, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(this, 10), dp(this, 2), dp(this, 10), dp(this, 2));
        return t;
    }

    private void setStatus() {
        if (status == null) return;
        if (bell != null) { int un = Sync.annUnread(this); bell.setText(un > 0 ? "🔔 " + un + "\nإشعارات" : "🔔\nإشعارات"); bell.setTextColor(un > 0 ? 0xFFFFD54F : Color.WHITE); }
        long ls = Store.p(this).getLong("last_sync", 0);
        status.setText(syncing ? "جارٍ المزامنة…" : (all.size() + " فرصة • آخر مزامنة " + (ls == 0 ? "—" : ago(ls))));
        int pend = Store.admin(this) ? Store.p(this).getInt("pending_sources", 0) + Store.p(this).getInt("pending_fixes", 0) + Store.p(this).getInt("pending_flags", 0) : 0;
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
            try { n = Sync.run(app, false); }
            catch (Api.AuthException e) { auth = true; err = e.getMessage(); }
            catch (Throwable e) { err = e.getMessage(); }
            final String fe = err;
            final boolean fa = auth;
            final int fn = n;
            List<Store.Job> l = fa ? new ArrayList<>() : Store.get(app).all();
            ui.post(() -> {
                syncing = false;
                if (syncIcon != null) syncIcon.setAlpha(1f);
                if (outdated()) { recreate(); return; }
                if (fa) { signedOut(fe == null ? "سجّل الدخول من جديد" : fe); return; }
                all = l;
                setStatus();
                if (fe != null && manual) Toast.makeText(this, "تعذّرت المزامنة: " + fe, Toast.LENGTH_LONG).show();
                else if (manual) Toast.makeText(this, fn > 0 ? "✅ " + fn + " فرصة جديدة تناسبك" : "✅ تمت المزامنة — لا جديد الآن", Toast.LENGTH_SHORT).show();
                if (!wasSetup && Store.p(this).getBoolean("setup_done", false) && tab == T_FILTER) show(T_JOBS);
                else if (tab != T_FILTER) show(tab);
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
        if (k != T_FILTER) { draft = null; draftDirty = false; }
        tab = k;
        for (int i = 0; i < 5; i++) {
            tabs[i].setTextColor(i == k ? PRIMARY : MUTED);
            tabs[i].setTypeface(i == k ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        content.removeAllViews();
        hideKeyboard();
        setStatus();
        if (k == T_JOBS) content.addView(jobsScreen(false));
        else if (k == T_TENDERS) content.addView(jobsScreen(true));
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
            public void afterTextChanged(Editable s) { query = s.toString().trim(); clr.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE); applyList(); }
        });
        applyList();
        return l;
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
                h.badge = text(c, "جديد", 11, Color.WHITE, true);
                h.badge.setBackground(round(c, ORANGE, 8));
                h.badge.setPadding(dp(c, 6), dp(c, 1), dp(c, 6), dp(c, 1));
                LinearLayout.LayoutParams bp = lp(-2, -2);
                bp.leftMargin = dp(c, 6);
                r1.addView(h.badge, bp);
                h.title = text(c, "", 16, TEXT, true);
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
                card.addView(h.org);
                h.snip = text(c, "", 12, TEXT, false);
                h.snip.setMaxLines(3);
                h.snip.setPadding(0, dp(c, 4), 0, 0);
                card.addView(h.snip);
                h.tags = text(c, "", 12, PRIMARY, false);
                h.tags.setMaxLines(2);
                h.tags.setPadding(0, dp(c, 4), 0, 0);
                card.addView(h.tags);
                LinearLayout r3 = row(c);
                r3.setPadding(0, dp(c, 6), 0, 0);
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
        l.addView(ac, lpm(this, -1, -2, 0, 12));

        l.addView(cvCard(), lpm(this, -1, -2, 0, 12));

        if (admin) {
            LinearLayout ad = card(this);
            ad.addView(text(this, "🛠 لوحة الأدمن", 16, TEXT, true));
            int ps = Store.p(this).getInt("pending_sources", 0), pf = Store.p(this).getInt("pending_fixes", 0);
            Button bs = button(this, "📡 المصادر" + (ps > 0 ? "  (" + ps + " بانتظار موافقتك 🔴)" : ""), ps > 0);
            bs.setOnClickListener(v -> adminSources());
            Button bu = button(this, "👥 المستخدمون", false);
            bu.setOnClickListener(v -> adminUsers());
            Button bf = button(this, "🏷 تصحيحات التصنيف" + (pf > 0 ? "  (" + pf + " 🔴)" : ""), pf > 0);
            bf.setOnClickListener(v -> adminFixes());
            int pfl = Store.p(this).getInt("pending_flags", 0);
            Button bfl = button(this, "🚩 البلاغات" + (pfl > 0 ? "  (" + pfl + " 🔴)" : ""), pfl > 0);
            bfl.setOnClickListener(v -> adminFlags());
            Button ba = button(this, "📢 إشعارات المستخدمين (إرسال / حذف)", false);
            ba.setOnClickListener(v -> showAnnouncements());
            Button bm = button(this, "⬆ تحديث إجباري", false);
            bm.setOnClickListener(v -> adminMinVer());
            Button bt = button(this, "📊 الإحصائيات", false);
            bt.setOnClickListener(v -> adminStats());
            for (Button bb : new Button[]{bs, bu, bf, bfl, ba, bm, bt}) ad.addView(bb, lpm(this, -1, -2, 6, 0));
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
        l.addView(sc, lpm(this, -1, -2, 0, 12));

        LinearLayout hc = card(this);
        hc.addView(text(this, "🤝 مشاركة فرصة من فيسبوك أو لينكدإن", 16, TEXT, true));
        hc.addView(text(this, "عندما ترى إعلاناً هناك: اضغط «مشاركة» ← «المزيد» ← اختر «وظيفتي». يصل الإعلان لكل المستخدمين ويُصنَّف تلقائياً.", 13, TEXT, false));
        l.addView(hc, lpm(this, -1, -2, 0, 12));

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
                pd.dismiss();
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
        post("/suggest", jo("value", v), r -> {
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
        return k.equals("tg") ? "✈️" : k.equals("manual") ? "🤝" : k.equals("link") ? "🔗" : "🌐";
    }

    private String sourceLink(JSONObject s) {
        String k = s.optString("kind"), v = s.optString("value");
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
        else if (s.optLong("last_ok", 0) > 0) st = "✅ " + ago(s.optLong("last_ok")) + " • أضاف " + s.optInt("total") + " إعلاناً";
        else st = "⏳ بانتظار أول جلب";
        t.addView(oneLine(text(this, st, 12, err.isEmpty() ? MUTED : RED, false)));
        int rej = s.optInt("rejected"), tot = s.optInt("total");
        if (rej > 0) {
            int pct = Math.round(100f * rej / Math.max(1, rej + tot));
            t.addView(oneLine(text(this, "🗑 رُفض " + rej + " منشوراً ليس وظيفة (" + pct + "%)", 11, pct >= 50 ? ORANGE : MUTED, false)));
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
                c.addView(text(this, "آخر ظهور: " + (u.optLong("last_seen") > 0 ? ago(u.optLong("last_seen")) : "—")
                        + " • مصادر: " + u.optInt("contrib") + " • مشاركات: " + u.optInt("shares")
                        + (model.isEmpty() ? "" : "\n📱 " + model + (ver.isEmpty() ? "" : " • v" + ver)), 12, MUTED, false));
                if (!email.equals("eng.feras8585@gmail.com") && !email.equals(meEmail)) {
                    LinearLayout r2 = row(this);
                    Button role = button(this, isAdmin ? "إزالة صلاحية الأدمن" : "اجعله أدمن", false);
                    String who = name.isEmpty() ? email : name;
                    role.setOnClickListener(v -> new AlertDialog.Builder(this)
                            .setMessage(isAdmin ? "إزالة صلاحية الأدمن من " + who + "؟" : "هل تريد جعل " + who + " أدمن؟\nسيرى المصادر والمستخدمين ويستطيع الموافقة والإيقاف.")
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
                c.addView(text(this, "الحالي: 🏷 " + Labels.names(Labels.SPECS, str(f, "j_specs")) + "  📍 " + Labels.names(Labels.GOVS, str(f, "j_govs")), 12, MUTED, false));
                c.addView(text(this, "المقترح: 🏷 " + Labels.names(Labels.SPECS, str(f, "specs")) + "  📍 " + Labels.names(Labels.GOVS, str(f, "govs")), 13, GREEN, true));
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
