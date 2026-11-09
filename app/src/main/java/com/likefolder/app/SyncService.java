package com.likefolder.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Foreground downloads have one queue, a visible progress state, and an immediate stop action. */
public final class SyncService extends Service {
    static final String ACTION_MONITOR = "com.likefolder.app.MONITOR";
    static final String ACTION_STOP = "com.likefolder.app.STOP";
    static final String ACTION_ALL = "com.likefolder.app.ALL";
    static final String ACTION_ONCE = "com.likefolder.app.ONCE";
    static final String ACTION_SELECTED = "com.likefolder.app.SELECTED";
    static final String ACTION_CANCEL_DOWNLOAD = "com.likefolder.app.CANCEL_DOWNLOAD";
    static final String EXTRA_TRACK_IDS = "track_ids";
    private static final String CHANNEL = "likefolder.sync";
    private static final int ID = 15;
    private final Object lifecycle = new Object();
    private final ExecutorService tasks = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ScheduledFuture<?> monitorTask;
    private Store store;
    private boolean monitoring, destroyed, foreground, ownedByMonitor;
    private DownloadCoordinator.Job ownedJob;
    private long monitorStarted;
    private int latestStartId;
    private final Runnable notificationTicker = new Runnable() {
        @Override public void run() {
            synchronized (lifecycle) {
                if (destroyed) return;
                if (foreground) update(store.status());
            }
            handler.postDelayed(this, 1000);
        }
    };

    /** Called from an activity: cancellation is set before another queued service command can run. */
    static void stopDownloads(Context context) {
        DownloadCoordinator.cancelActive();
        new Store(context).status(DownloadCoordinator.snapshot().running
                ? "Останавливаю загрузку…" : "Загрузка остановлена");
        // This also updates/stops an existing foreground service. Worker cancellation is already applied.
        try { context.startService(new Intent(context, SyncService.class).setAction(ACTION_CANCEL_DOWNLOAD)); }
        catch (IllegalStateException ignored) { /* The active job still observes cancellation. */ }
    }

    @Override public void onCreate() {
        super.onCreate(); store = new Store(this);
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Синхронизация музыки", NotificationManager.IMPORTANCE_LOW));
        handler.post(notificationTicker);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        synchronized (lifecycle) {
            latestStartId = startId;
            String action = intent == null ? (store.monitoring() ? ACTION_MONITOR : ACTION_STOP) : intent.getAction();
            if (ACTION_CANCEL_DOWNLOAD.equals(action)) {
                DownloadCoordinator.cancelActive();
                store.status(DownloadCoordinator.snapshot().running ? "Останавливаю загрузку…" : "Загрузка остановлена");
                stopIfIdle();
                return START_NOT_STICKY;
            }
            if (ACTION_STOP.equals(action)) {
                monitoring = false; store.monitoring(false);
                if (monitorTask != null) { monitorTask.cancel(false); monitorTask = null; }
                // Turning off quick mode does not destroy an unrelated manual export.
                if (ownedJob != null && ownedByMonitor) ownedJob.cancel();
                stopIfIdle();
                return START_NOT_STICKY;
            }

            foreground = true;
            startForeground(ID, notification("Подготовка загрузки…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            if (ACTION_MONITOR.equals(action)) {
                if (!monitoring) monitorStarted = SystemClock.elapsedRealtime();
                monitoring = true; store.monitoring(true);
                if (monitorTask == null || monitorTask.isCancelled()) {
                    monitorTask = scheduler.scheduleWithFixedDelay(() -> {
                        synchronized (lifecycle) {
                            if (destroyed || !monitoring) return;
                            if (Build.VERSION.SDK_INT >= 35
                                    && SystemClock.elapsedRealtime() - monitorStarted > TimeUnit.HOURS.toMillis(5)) {
                                monitoring = false; store.monitoring(false);
                                store.status("Быстрая проверка остановлена по лимиту Android; фоновая проверка продолжится");
                                handler.post(this::stopIfIdle);
                                return;
                            }
                            submit(SyncEngine.Mode.NEW_ONLY, Collections.emptyList(), true);
                        }
                    }, 0, 2, TimeUnit.MINUTES);
                }
                return START_STICKY;
            }

            List<String> selected = intent == null ? null : intent.getStringArrayListExtra(EXTRA_TRACK_IDS);
            SyncEngine.Mode mode = ACTION_SELECTED.equals(action) ? SyncEngine.Mode.SELECTED
                    : ACTION_ALL.equals(action) ? SyncEngine.Mode.ALL : SyncEngine.Mode.NEW_ONLY;
            submit(mode, selected == null ? Collections.emptyList() : new ArrayList<>(selected), false);
            stopIfIdle();
            return START_NOT_STICKY;
        }
    }

    private void submit(SyncEngine.Mode mode, List<String> selected, boolean fromMonitor) {
        synchronized (lifecycle) {
            if (destroyed || ownedJob != null || (fromMonitor && !monitoring)) return;
            DownloadCoordinator.Job job = DownloadCoordinator.tryStart();
            if (job == null) return; // No hidden queue behind an ongoing service or WorkManager job.
            ownedJob = job; ownedByMonitor = fromMonitor;
            tasks.execute(() -> {
                try {
                    new SyncEngine(this).run(mode, selected, job);
                } catch (DownloadCoordinator.CancelledException ex) {
                    store.status("Загрузка остановлена");
                } catch (Exception ex) {
                    store.status(job.isCancelled() ? "Загрузка остановлена" : "Ошибка: " + ex.getMessage());
                } finally {
                    synchronized (lifecycle) {
                        job.close();
                        if (ownedJob == job) { ownedJob = null; ownedByMonitor = false; }
                    }
                    handler.post(this::stopIfIdle);
                }
            });
        }
    }

    private void stopIfIdle() {
        synchronized (lifecycle) {
            if (destroyed || monitoring || ownedJob != null) return;
            foreground = false;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelfResult(latestStartId);
        }
    }

    private void update(String message) {
        getSystemService(NotificationManager.class).notify(ID, notification(message));
    }

    private Notification notification(String message) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        DownloadCoordinator.Snapshot state = DownloadCoordinator.snapshot();
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_heart)
                .setContentTitle(state.running ? "LikeFolder · Загрузка MP3" : "LikeFolder · Синхронизация")
                .setContentText(message).setContentIntent(open).setOnlyAlertOnce(true)
                .setOngoing(monitoring || state.running);
        if (state.running) {
            builder.setProgress(state.total, state.done, state.total == 0);
            PendingIntent stop = PendingIntent.getService(this, 1,
                    new Intent(this, SyncService.class).setAction(ACTION_CANCEL_DOWNLOAD),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause,
                    "Остановить", stop).build());
        }
        return builder.build();
    }

    @Override public void onTimeout(int startId, int fgsType) {
        synchronized (lifecycle) {
            monitoring = false; store.monitoring(false);
            if (ownedJob != null) ownedJob.cancel();
            store.status("Синхронизация остановлена лимитом Android");
            stopSelf(startId);
        }
    }

    @Override public void onDestroy() {
        synchronized (lifecycle) {
            destroyed = true; monitoring = false; foreground = false;
            store.monitoring(false);
            if (monitorTask != null) monitorTask.cancel(false);
            if (ownedJob != null) ownedJob.cancel();
            // Let an accepted but not-yet-started task run its cancellation cleanup and release its slot.
            tasks.shutdown(); scheduler.shutdownNow();
        }
        handler.removeCallbacks(notificationTicker);
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
