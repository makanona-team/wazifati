package com.feras.wazifati;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONObject;

import static com.feras.wazifati.Ui.*;

/** يستقبل نص إعلان من فيسبوك أو لينكدإن أو أي تطبيق (زر «مشاركة») ويرسله لكل الأصدقاء. */
public class ShareActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Sync.init(this);
        if (!Sync.loggedIn(this)) {
            Toast.makeText(this, "افتح وظيفتي وسجّل الدخول أولاً", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        }
        Intent in = getIntent();
        String subject = in.getStringExtra(Intent.EXTRA_SUBJECT);
        CharSequence cs = in.getCharSequenceExtra(Intent.EXTRA_TEXT);
        String text = cs == null ? "" : cs.toString();
        if (subject != null && !subject.isEmpty() && !text.contains(subject)) text = subject + "\n" + text;

        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        int p = dp(this, 18);
        l.setPadding(p, p, p, p);
        l.setBackgroundColor(CARD);
        l.addView(text(this, "🤝 مشاركة فرصة مع أصدقائك", 18, PRIMARY, true));
        l.addView(text(this, "تصل لكل مستخدمي وظيفتي وتُصنَّف تلقائياً. إن كان الرابط وحده، الصق نص الإعلان أيضاً ليُصنَّف بدقة.", 13, MUTED, false));
        EditText et = new EditText(this);
        et.setText(text.trim());
        et.setMinLines(5);
        et.setMaxLines(12);
        et.setTextSize(14);
        l.addView(et, lpm(this, -1, -2, 10, 10));
        LinearLayout r = row(this);
        Button send = button(this, "مشاركة", true);
        Button cancel = button(this, "إلغاء", false);
        LinearLayout.LayoutParams w1 = new LinearLayout.LayoutParams(0, -2, 1);
        w1.leftMargin = dp(this, 6);
        r.addView(send, w1);
        r.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1));
        l.addView(r);
        setContentView(l);
        getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.94), -2);

        cancel.setOnClickListener(v -> finish());
        send.setOnClickListener(v -> {
            String t = et.getText().toString().trim();
            if (t.length() < 10) { Toast.makeText(this, "النص قصير جداً", Toast.LENGTH_SHORT).show(); return; }
            send.setEnabled(false);
            send.setText("جارٍ الإرسال…");
            Context app = getApplicationContext();
            Handler ui = new Handler(Looper.getMainLooper());
            new Thread(() -> {
                String msg;
                try {
                    JSONObject body = new JSONObject().put("text", t);
                    JSONObject res = Api.post("/share", body);
                    String rr = res.optString("result");
                    msg = "dup".equals(rr) || "seen".equals(rr) ? "هذه الفرصة موجودة مسبقاً في التطبيق 👍" : "تمت المشاركة مع أصدقائك ✅";
                    try { Sync.syncJobs(app); } catch (Throwable ignored) { }
                } catch (Throwable e) {
                    msg = "تعذّرت المشاركة: " + e.getMessage();
                }
                final String m = msg;
                ui.post(() -> { Toast.makeText(app, m, Toast.LENGTH_LONG).show(); finish(); });
            }).start();
        });
    }
}
