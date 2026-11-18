package com.feras.wazifati;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import java.util.List;

/** ويدجت: عدد الفرص الجديدة اليوم وآخر 3 عناوين تطابق فلترك. */
public class JobsWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        update(c);
    }

    public static void update(Context c) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new ComponentName(c, JobsWidget.class));
            if (ids == null || ids.length == 0) return;
            List<Store.Job> all = Store.get(c).all();
            Store.Filter f = Store.Filter.of(c);
            long dayAgo = System.currentTimeMillis() - Store.DAY;
            int today = 0;
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for (Store.Job j : all) {
                if (j.tender() || !f.match(j) || Store.expired(j)) continue;
                if (j.posted > dayAgo) today++;
                if (shown < 3) {
                    String g = Labels.names(Labels.GOVS, j.govs);
                    sb.append("• ").append(j.title).append(g.isEmpty() ? "" : " – " + g).append("\n");
                    shown++;
                }
            }
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
            v.setTextViewText(R.id.w_count, today + " جديدة اليوم");
            v.setTextViewText(R.id.w_list, Sync.loggedIn(c) ? (sb.length() == 0 ? "لا توجد فرص حالياً" : sb.toString().trim()) : "سجّل الدخول من التطبيق");
            Intent o = new Intent(c, MainActivity.class).putExtra("tab", 0).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(c, 9, o, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            m.updateAppWidget(ids, v);
        } catch (Throwable ignored) { }
    }
}
