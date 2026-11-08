package com.likefolder.app;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import androidx.work.WorkManager;
import java.util.concurrent.TimeUnit;

/** Android's background scheduling: minimum 15 minutes, actual execution may be later. */
public final class SyncWorker extends Worker {
    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters params) { super(context, params); }
    @NonNull @Override public Result doWork() {
        Store store = new Store(getApplicationContext());
        if (!store.isLoggedIn()) return Result.success();
        try { new SyncEngine(getApplicationContext()).run(SyncEngine.Mode.NEW_ONLY); return Result.success(); }
        catch (Exception ex) { store.status("Ошибка фоновой проверки: " + ex.getMessage()); return Result.retry(); }
    }
    static void schedule(Context context) {
        PeriodicWorkRequest work = new PeriodicWorkRequest.Builder(SyncWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("liked-track-sync",
                ExistingPeriodicWorkPolicy.KEEP, work);
    }
    static void cancel(Context context) { WorkManager.getInstance(context).cancelUniqueWork("liked-track-sync"); }
}
