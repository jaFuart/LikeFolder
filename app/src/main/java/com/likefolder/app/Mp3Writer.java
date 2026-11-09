package com.likefolder.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Writes a genuine standalone audio/mpeg file into Music/<chosen folder> via MediaStore. */
final class Mp3Writer {
    private final ContentResolver resolver;
    private final Store store;
    Mp3Writer(Context context, Store store) { resolver = context.getContentResolver(); this.store = store; }

    void save(YandexApi.Track track, String mp3Url) throws IOException {
        DownloadCoordinator.check();
        String folder = store.folder();
        if (!folder.matches("[\\p{L}\\p{N} _.-]{1,60}") || folder.equals(".") || folder.equals(".."))
            throw new IOException("Неверное имя папки");
        String directory = Environment.DIRECTORY_MUSIC + "/" + folder + "/";
        String base = trimName(track.artist + " - " + track.title);
        String name = base + ".mp3";
        if (exists(directory, name)) name = base + " (" + track.id.replace(':', '-') + ").mp3";
        if (exists(directory, name)) throw new IOException("Файл уже существует: " + name);

        HttpURLConnection conn = Net.connectStream(mp3Url);
        Uri target = null;
        boolean saved = false;
        try (BufferedInputStream in = new BufferedInputStream(conn.getInputStream())) {
            in.mark(16);
            int a = in.read(), b = in.read(), c = in.read();
            if (a < 0 || b < 0 || c < 0) throw new IOException("Пустой ответ аудиосервера");
            boolean id3 = a == 'I' && b == 'D' && c == '3';
            boolean mpeg = a == 0xFF && (b & 0xE0) == 0xE0;
            if (!id3 && !mpeg) throw new IOException("Сервер вернул не MP3 (возможно, формат изменился)");
            in.reset();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Audio.Media.TITLE, track.title);
            values.put(MediaStore.Audio.Media.ARTIST, track.artist);
            values.put(MediaStore.Audio.Media.ALBUM, track.album);
            values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg");
            values.put(MediaStore.Audio.Media.RELATIVE_PATH, directory);
            values.put(MediaStore.Audio.Media.IS_PENDING, 1);
            DownloadCoordinator.check();
            target = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
            if (target == null) throw new IOException("Android не разрешил создать аудиофайл");
            long size = 0;
            try (OutputStream out = resolver.openOutputStream(target, "w")) {
                if (out == null) throw new IOException("Невозможно открыть файл для записи");
                // If the stream has no ID3, embed title/artist/album as standards-compliant ID3v2.3 tags.
                if (!id3) out.write(id3Tag(track));
                byte[] buffer = new byte[65536];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    DownloadCoordinator.check();
                    out.write(buffer, 0, n);
                    size += n;
                }
            }
            if (size < 2048) throw new IOException("Сервер вернул подозрительно маленький MP3");
            DownloadCoordinator.check();
            ContentValues finished = new ContentValues();
            finished.put(MediaStore.Audio.Media.IS_PENDING, 0);
            Uri completed = target;
            DownloadCoordinator.commit(() -> {
                if (resolver.update(completed, finished, null, null) < 1)
                    throw new IOException("Файл записан, но не опубликован в медиатеке");
                store.markDownloaded(track.id);
            });
            target = null;
            saved = true;
        } catch (Exception ex) {
            // A close error after publishing must not turn a completed, recorded MP3 into a failure.
            if (saved) return;
            if (target != null) {
                try { resolver.delete(target, null, null); }
                catch (RuntimeException cleanup) { ex.addSuppressed(cleanup); }
            }
            DownloadCoordinator.check();
            if (ex instanceof IOException) throw (IOException) ex;
            throw new IOException("Ошибка записи MP3", ex);
        } finally { Net.disconnect(conn); }
    }
    private boolean exists(String folder, String name) {
        String selection = MediaStore.Audio.Media.DISPLAY_NAME + "=? AND " + MediaStore.Audio.Media.RELATIVE_PATH + "=?";
        try (Cursor c = resolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Audio.Media._ID}, selection, new String[]{name, folder}, null)) {
            return c != null && c.moveToFirst();
        }
    }
    private static String trimName(String raw) {
        String x = raw.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_").trim();
        x = x.replaceAll("[. ]+$", "");
        if (x.isEmpty()) x = "Unknown track";
        return x.length() > 120 ? x.substring(0, 120) : x;
    }
    static byte[] id3Tag(YandexApi.Track track) throws IOException {
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        writeFrame(frames, "TIT2", track.title);
        writeFrame(frames, "TPE1", track.artist);
        if (!track.album.isEmpty()) writeFrame(frames, "TALB", track.album);
        byte[] payload = frames.toByteArray();
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write(new byte[]{'I', 'D', '3', 3, 0, 0});
        int size = payload.length;
        result.write(new byte[]{ (byte) ((size >> 21) & 0x7f), (byte) ((size >> 14) & 0x7f),
                (byte) ((size >> 7) & 0x7f), (byte) (size & 0x7f) });
        result.write(payload);
        return result.toByteArray();
    }
    private static void writeFrame(ByteArrayOutputStream output, String id, String text) throws IOException {
        if (text == null || text.isEmpty()) return;
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        content.write(1);  // ID3v2.3: UTF-16 with BOM
        content.write(new byte[]{(byte)0xFF, (byte)0xFE});
        content.write(text.getBytes(StandardCharsets.UTF_16LE));
        byte[] bytes = content.toByteArray();
        output.write(id.getBytes(StandardCharsets.US_ASCII));
        output.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(bytes.length).array());
        output.write(new byte[]{0, 0});
        output.write(bytes);
    }
}
