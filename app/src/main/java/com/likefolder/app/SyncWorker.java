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
    private volatile DownloadCoordinator.Job activeJob;
    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters params) { super(context, params); }
    @NonNull @Override public Result doWork() {
        Store store = new Store(getApplicationContext());
        if (!store.isLoggedIn()) return Result.success();
        DownloadCoordinator.Job job = DownloadCoordinator.tryStart();
        if (job == null) return Result.success();
        activeJob = job;
        if (isStopped()) job.cancel();
        try {
            new SyncEngine(getApplicationContext()).run(SyncEngine.Mode.NEW_ONLY,
                    java.util.Collections.emptyList(), job);
            return Result.success();
        } catch (DownloadCoordinator.CancelledException ex) {
            store.status("Загрузка остановлена");
            return Result.success(); // User stop must not enqueue an immediate retry.
        } catch (Exception ex) {
            if (job.isCancelled()) { store.status("Загрузка остановлена"); return Result.success(); }
            store.status("Ошибка фоновой проверки: " + ex.getMessage());
            return Result.retry();
        } finally {
            job.close(); activeJob = null;
        }
    }
    @Override public void onStopped() {
        DownloadCoordinator.Job job = activeJob;
        if (job != null) job.cancel();
        super.onStopped();
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
