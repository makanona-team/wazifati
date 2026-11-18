package com.feras.wazifati;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** إعادة جدولة المزامنة بعد إعادة تشغيل الهاتف أو تحديث التطبيق. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Sync.schedule(c);
    }
}
