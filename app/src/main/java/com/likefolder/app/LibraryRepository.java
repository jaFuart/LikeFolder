package com.likefolder.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Persistent metadata cache. Download status stays in Store for compatibility with existing installs. */
final class LibraryRepository {
    interface Progress { void onProgress(int loaded, int total); }
    private static final Object LOCK = new Object();
    private static long generation;
    private final Context context;
    private final long sessionGeneration;

    LibraryRepository(Context context) {
        this.context = context.getApplicationContext();
        synchronized (LOCK) { sessionGeneration = generation; }
    }

    List<LibraryTrack> load() {
        synchronized (LOCK) {
            Database helper = new Database(context);
            try { return read(helper.getReadableDatabase()); }
            finally { helper.close(); }
        }
    }

    void clear() {
        synchronized (LOCK) {
            ++generation; // An old account's in-flight refresh must not repopulate the cache.
            Database helper = new Database(context);
            try { helper.getWritableDatabase().delete("tracks", null, null); }
            finally { helper.close(); }
        }
    }

    void refresh(Progress listener) throws IOException {
        Store store = new Store(context);
        YandexApi api = new YandexApi(store);
        checkActive();
        List<YandexApi.Like> likes = api.likedEntries();
        checkActive();
        Map<String, LibraryTrack> previous = new HashMap<>();
        for (LibraryTrack row : load()) previous.put(row.id, row);
        LinkedHashMap<String, LibraryTrack> refreshed = new LinkedHashMap<>();
        if (listener != null) listener.onProgress(0, likes.size());
        for (int offset = 0; offset < likes.size(); offset += 35) {
            checkActive();
            List<YandexApi.Like> batch = likes.subList(offset, Math.min(offset + 35, likes.size()));
            List<String> ids = new ArrayList<>();
            for (YandexApi.Like like : batch) ids.add(like.id);
            Map<String, YandexApi.Track> metadata = new HashMap<>();
            for (YandexApi.Track track : api.tracks(ids)) metadata.put(track.id, track);
            checkActive();
            for (YandexApi.Like like : batch) {
                YandexApi.Track details = metadata.get(like.id);
                if (details == null) details = metadata.get(like.id.split(":")[0]);
                LibraryTrack old = previous.get(like.id);
                refreshed.put(like.id, new LibraryTrack(like.id,
                        details != null ? details.title : old == null ? null : old.title,
                        details != null ? details.artist : old == null ? null : old.artist,
                        details != null ? details.album : old == null ? null : old.album,
                        like.timestampMillis, like.order));
            }
            if (listener != null) listener.onProgress(Math.min(offset + 35, likes.size()), likes.size());
        }
        checkActive();
        synchronized (LOCK) {
            checkActive();
            Database helper = new Database(context);
            try {
                SQLiteDatabase db = helper.getWritableDatabase();
                // Include files saved earlier even if the user has since removed their like.
                Set<String> downloaded = store.ids("downloaded");
                for (LibraryTrack row : read(db)) {
                    if (downloaded.contains(row.id) && !refreshed.containsKey(row.id)) refreshed.put(row.id, row);
                }
                db.beginTransaction();
                try {
                    db.delete("tracks", null, null);
                    for (LibraryTrack row : refreshed.values()) db.insertOrThrow("tracks", null, values(row));
                    checkActive();
                    db.setTransactionSuccessful();
                } finally { db.endTransaction(); }
            } finally { helper.close(); }
        }
    }

    /** Cache details already fetched by the downloader without disturbing dates or other rows. */
    void remember(List<YandexApi.Track> tracks) {
        if (tracks.isEmpty() || Thread.currentThread().isInterrupted()) return;
        synchronized (LOCK) {
            if (sessionGeneration != generation) return;
            Database helper = new Database(context);
            try {
                SQLiteDatabase db = helper.getWritableDatabase();
                Map<String, LibraryTrack> existing = new HashMap<>();
                int nextOrder = 0;
                for (LibraryTrack row : read(db)) {
                    existing.put(row.id, row);
                    nextOrder = Math.max(nextOrder, row.likeOrder + 1);
                }
                db.beginTransaction();
                try {
                    for (YandexApi.Track details : tracks) {
                        LibraryTrack old = existing.get(details.id);
                        LibraryTrack row = new LibraryTrack(details.id, details.title, details.artist, details.album,
                                old == null ? 0 : old.likedAt, old == null ? nextOrder++ : old.likeOrder);
                        db.insertWithOnConflict("tracks", null, values(row), SQLiteDatabase.CONFLICT_REPLACE);
                    }
                    db.setTransactionSuccessful();
                } finally { db.endTransaction(); }
            } finally { helper.close(); }
        }
    }

    private void checkActive() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Загрузка медиатеки остановлена");
        synchronized (LOCK) {
            if (sessionGeneration != generation) throw new InterruptedIOException("Медиатека очищена после выхода из аккаунта");
        }
    }

    private static List<LibraryTrack> read(SQLiteDatabase db) {
        List<LibraryTrack> rows = new ArrayList<>();
        try (Cursor cursor = db.query("tracks", new String[]{"id", "title", "artist", "album", "liked_at", "like_order"},
                null, null, null, null, "like_order ASC, id ASC")) {
            while (cursor.moveToNext()) rows.add(new LibraryTrack(cursor.getString(0), cursor.getString(1),
                    cursor.getString(2), cursor.getString(3), cursor.getLong(4), cursor.getInt(5)));
        }
        return rows;
    }

    private static ContentValues values(LibraryTrack track) {
        ContentValues values = new ContentValues();
        values.put("id", track.id); values.put("title", track.title); values.put("artist", track.artist);
        values.put("album", track.album); values.put("liked_at", track.likedAt); values.put("like_order", track.likeOrder);
        return values;
    }

    private static final class Database extends SQLiteOpenHelper {
        Database(Context context) { super(context, "library.db", null, 1); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE tracks (id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL, "
                    + "album TEXT NOT NULL, liked_at INTEGER NOT NULL, like_order INTEGER NOT NULL)");
        }
        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            throw new IllegalStateException("Unsupported library database upgrade");
        }
    }
}
