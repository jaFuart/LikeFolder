package com.likefolder.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** All sync jobs are serialized to prevent concurrent downloads. */
final class SyncEngine {
    static final Object MUTEX = new Object();
    enum Mode { NEW_ONLY, ALL }
    private final Context context;
    private final Store store;
    SyncEngine(Context ctx) { context = ctx.getApplicationContext(); store = new Store(context); }

    String run(Mode mode) throws IOException {
        synchronized (MUTEX) {
            if (!store.isLoggedIn()) throw new IOException("Аккаунт не подключён");
            if (mode == Mode.NEW_ONLY && store.prefs.getBoolean("wifi_only", true) && !unmetered()) {
                store.status("Ожидаю Wi-Fi для автоматической загрузки");
                return store.status();
            }
            YandexApi api = new YandexApi(store);
            store.status("Получаю список понравившихся треков…");
            List<String> liked = api.likes();
            store.likedCount(liked.size());
            Set<String> allLiked = new HashSet<>(liked);
            if (!store.hasBaseline()) {
                store.setIds("seen", allLiked);
                store.markBaseline();
                if (mode == Mode.NEW_ONLY) {
                    String status = "Подключено: найдено " + liked.size() + " лайков. Новые буду сохранять автоматически.";
                    store.status(status);
                    return status;
                }
            }
            Set<String> seen = store.ids("seen");
            Set<String> downloaded = store.ids("downloaded");
            List<String> todo = new ArrayList<>();
            for (String id : liked) {
                if (downloaded.contains(id)) continue;
                if (mode == Mode.ALL || !seen.contains(id)) todo.add(id);
            }
            if (todo.isEmpty()) {
                String status = "Синхронизировано: " + liked.size() + " лайков, новых MP3 нет";
                store.status(status);
                return status;
            }
            Mp3Writer writer = new Mp3Writer(context, store);
            int ok = 0, errors = 0;
            String lastError = "";
            for (int offset = 0; offset < todo.size(); offset += 35) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Задача остановлена");
                List<String> batch = todo.subList(offset, Math.min(offset + 35, todo.size()));
                List<YandexApi.Track> details = api.tracks(batch);
                Map<String, YandexApi.Track> byId = new HashMap<>();
                for (YandexApi.Track item : details) byId.put(item.id, item);
                for (String id : batch) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Задача остановлена");
                    YandexApi.Track found = byId.get(id);
                    if (found == null) found = byId.get(id.split(":")[0]);
                    if (found == null) {
                        errors++;
                        lastError = "Нет информации о треке " + id;
                        continue;
                    }
                    YandexApi.Track track = new YandexApi.Track(id, found.title, found.artist, found.album);
                    store.status("Сохраняю MP3: " + (ok + errors + 1) + "/" + todo.size() + " — " + track.artist + " — " + track.title);
                    try {
                        writer.save(track, api.mp3Url(id));
                        store.addId("downloaded", id);
                        store.addId("seen", id);
                        ok++;
                    } catch (IOException ex) {
                        errors++;
                        lastError = ex.getMessage();
                        // Prevent a failing API / expired entitlement from causing hundreds of network calls.
                        if (ok == 0 && errors >= 5) {
                            String status = "Выгрузка остановлена: первые 5 загрузок завершились ошибкой. " + lastError;
                            store.status(status);
                            return status;
                        }
                    }
                }
            }
            String status = "Готово: сохранено " + ok + " MP3" + (errors == 0 ? "" : ", ошибок " + errors + " (" + lastError + ")");
            store.status(status);
            return status;
        }
    }
    private boolean unmetered() {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network network = cm.getActiveNetwork();
        NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
    }
}
