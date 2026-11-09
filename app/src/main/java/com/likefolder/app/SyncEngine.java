package com.likefolder.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Downloads one explicit queue. DownloadCoordinator serializes jobs across all entry points. */
final class SyncEngine {
    enum Mode { NEW_ONLY, ALL, SELECTED }
    private final Context context;
    private final Store store;
    SyncEngine(Context ctx) { context = ctx.getApplicationContext(); store = new Store(context); }

    String run(Mode mode) throws IOException {
        DownloadCoordinator.Job job = DownloadCoordinator.tryStart();
        if (job == null) return "Загрузка уже выполняется";
        try { return run(mode, Collections.emptyList(), job); }
        finally { job.close(); }
    }

    String run(Mode mode, List<String> selected, DownloadCoordinator.Job job) throws IOException {
        job.bind();
        if (!store.isLoggedIn()) throw new IOException("Аккаунт не подключён");
        if (mode == Mode.NEW_ONLY && store.prefs.getBoolean("wifi_only", true) && !unmetered())
            return status("Ожидаю Wi-Fi для автоматической загрузки");

        YandexApi api = new YandexApi(store);
        LibraryRepository library = new LibraryRepository(context);
        List<String> candidates;
        if (mode == Mode.SELECTED) {
            // Do not fetch the entire liked collection or append new likes to an explicit selection.
            candidates = selected == null ? Collections.emptyList() : new ArrayList<>(selected);
        } else {
            status("Получаю список понравившихся треков…");
            candidates = api.likes();
            DownloadCoordinator.check();
            Set<String> allLiked = new HashSet<>(candidates);
            boolean needsBaseline = !store.hasBaseline();
            DownloadCoordinator.commit(() -> {
                store.likedCount(allLiked.size());
                if (needsBaseline) { store.setIds("seen", allLiked); store.markBaseline(); }
            });
            if (needsBaseline && mode == Mode.NEW_ONLY)
                return status("Подключено: найдено " + allLiked.size() + " лайков. Новые буду сохранять автоматически.");
        }

        List<String> todo = DownloadSelection.pending(candidates, store.ids("downloaded"),
                store.ids("seen"), mode == Mode.NEW_ONLY);
        job.progress(0, todo.size(), "Подготовка треков…");
        if (todo.isEmpty()) return status(mode == Mode.SELECTED
                ? "Выбранные треки уже скачаны или список пуст"
                : "Синхронизировано: " + store.likedCount() + " лайков, новых MP3 нет");

        Mp3Writer writer = new Mp3Writer(context, store);
        int ok = 0, errors = 0;
        String lastError = "";
        for (int offset = 0; offset < todo.size(); offset += 35) {
            DownloadCoordinator.check();
            List<String> batch = todo.subList(offset, Math.min(offset + 35, todo.size()));
            Map<String, YandexApi.Track> byId = new HashMap<>();
            for (YandexApi.Track item : api.tracks(batch)) byId.put(item.id, item);
            DownloadCoordinator.check();
            List<YandexApi.Track> resolved = new ArrayList<>();
            for (String id : batch) {
                YandexApi.Track found = byId.get(id);
                if (found == null) found = byId.get(id.split(":")[0]);
                if (found != null) resolved.add(new YandexApi.Track(id, found.title, found.artist, found.album));
            }
            try { library.remember(resolved); }
            catch (RuntimeException ignored) { /* Cache failure must not prevent an otherwise valid MP3 download. */ }

            Map<String, YandexApi.Track> tracks = new HashMap<>();
            for (YandexApi.Track track : resolved) tracks.put(track.id, track);
            for (String id : batch) {
                DownloadCoordinator.check();
                YandexApi.Track track = tracks.get(id);
                if (track == null) {
                    errors++;
                    lastError = "Нет информации о треке " + id;
                } else {
                    String title = track.artist + " — " + track.title;
                    job.progress(ok + errors, todo.size(), title);
                    status("Сохраняю MP3: " + (ok + errors + 1) + "/" + todo.size() + " — " + title);
                    try {
                        writer.save(track, api.mp3Url(id));
                        // The writer publishes the file and records its ID as one cancellation boundary.
                        ok++;
                    } catch (IOException ex) {
                        DownloadCoordinator.check();
                        errors++;
                        lastError = ex.getMessage();
                    }
                }
                job.progress(ok + errors, todo.size(), track == null ? id : track.artist + " — " + track.title);
                if (ok == 0 && errors >= 5)
                    return status("Выгрузка остановлена: первые 5 загрузок завершились ошибкой. " + lastError);
            }
        }
        return status("Готово: сохранено " + ok + " MP3"
                + (errors == 0 ? "" : ", ошибок " + errors + " (" + lastError + ")"));
    }

    private String status(String message) throws IOException {
        DownloadCoordinator.commit(() -> store.status(message));
        return message;
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
