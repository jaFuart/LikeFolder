package com.likefolder.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** User-initiated foreground service for quick sync and full-library export. */
public final class SyncService extends Service {
    static final String ACTION_MONITOR = "com.likefolder.app.MONITOR";
    static final String ACTION_STOP = "com.likefolder.app.STOP";
    static final String ACTION_ALL = "com.likefolder.app.ALL";
    static final String ACTION_ONCE = "com.likefolder.app.ONCE";
    private static final String CHANNEL = "likefolder.sync";
    private static final int ID = 15;
    private final ExecutorService tasks = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> monitorTask;
    private Store store;
    private volatile boolean monitoring = false;
    private long monitorStarted = 0;

    @Override public void onCreate() {
        super.onCreate(); store = new Store(this);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Синхронизация музыки", NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? (store.monitoring() ? ACTION_MONITOR : ACTION_STOP) : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            monitoring = false; store.monitoring(false);
            if (monitorTask != null) monitorTask.cancel(false);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(ID, notification("Синхронизация активна"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        if (ACTION_MONITOR.equals(action)) {
            monitoring = true; store.monitoring(true); monitorStarted = SystemClock.elapsedRealtime();
            if (monitorTask == null || monitorTask.isCancelled()) {
                monitorTask = scheduler.scheduleWithFixedDelay(() -> {
                    // Android 15+ limits foreground dataSync services to six hours/24h.
                    if (Build.VERSION.SDK_INT >= 35 && SystemClock.elapsedRealtime() - monitorStarted > TimeUnit.HOURS.toMillis(5)) {
                        store.status("Быстрая проверка остановлена по лимиту Android; фоновая проверка продолжится");
                        stopSelf(); return;
                    }
                    runOne(SyncEngine.Mode.NEW_ONLY);
                }, 0, 2, TimeUnit.MINUTES);
            }
            return START_STICKY;
        }
        tasks.execute(() -> {
            runOne(ACTION_ALL.equals(action) ? SyncEngine.Mode.ALL : SyncEngine.Mode.NEW_ONLY);
            if (!monitoring) stopSelfResult(startId);
        });
        return START_NOT_STICKY;
    }
    private void runOne(SyncEngine.Mode mode) {
        try {
            update("Проверяю понравившиеся треки…");
            String result = new SyncEngine(this).run(mode);
            update(result);
        } catch (Exception ex) {
            store.status("Ошибка: " + ex.getMessage()); update(store.status());
        }
    }
    private void update(String msg) {
        getSystemService(NotificationManager.class).notify(ID, notification(msg));
    }
    private Notification notification(String message) {
        Intent launch = new Intent(this, MainActivity.class);
        PendingIntent open = PendingIntent.getActivity(this, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("LikeFolder · MP3")
                .setContentText(message)
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setOngoing(monitoring)
                .build();
    }
    @Override public void onTimeout(int startId, int fgsType) {
        store.status("Быстрая синхронизация остановлена лимитом Android");
        stopSelf(startId);
    }
    @Override public void onDestroy() {
        monitoring = false;
        store.monitoring(false);
        if (monitorTask != null) monitorTask.cancel(true);
        tasks.shutdownNow(); scheduler.shutdownNow();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
