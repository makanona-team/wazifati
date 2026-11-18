package com.feras.wazifati;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** يعمل في الخلفية كل 30 دقيقة تقريباً: يجلب الفرص الجديدة ويُشعر بالمطابق منها. */
public class SyncJob extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            boolean retry = false;
            try {
                Sync.run(getApplicationContext(), true);
            } catch (Throwable e) {
                retry = true;
            }
            jobFinished(params, retry);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
