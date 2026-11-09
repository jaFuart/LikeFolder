package com.likefolder.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;


import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Unofficial API, based on publicly documented methods. No DRM or cache decryption. */
final class YandexApi {
    private static final String API = "https://api.music.yandex.net/";
    private static final String OAUTH = "https://oauth.yandex.ru/";
    // Community OAuth app from open-source clients; may be revoked. No user password is handled.
    static final String CLIENT_ID = "23cabbbdc6cd418abb4b39c32c41195d";
    static final String CLIENT_SECRET = "53bc75238f0c4d08a118e51fe9203300";
    private static final String CDN_SALT = "XGRlBW9FXlekgbPrRHuSiA";
    private static final ReentrantLock REFRESH_LOCK = new ReentrantLock();
    private final Store store;
    YandexApi(Store store) { this.store = store; }

    static JSONObject requestCode(String deviceId) throws IOException {
        return Net.json(OAUTH + "device/code", "POST", Net.form(
                "client_id", CLIENT_ID,
                "device_id", deviceId,
                "device_name", "LikeFolder Android"), null);
    }
    static JSONObject pollCode(String code) throws IOException {
        return Net.json(OAUTH + "token", "POST", Net.form(
                "grant_type", "device_code", "code", code,
                "client_id", CLIENT_ID, "client_secret", CLIENT_SECRET), null);
    }
    private String refresh(Store.AuthSnapshot expected) {
        boolean locked = false;
        try {
            // Serialize refreshes without holding the auth lock during network I/O or blocking logout.
            REFRESH_LOCK.lockInterruptibly(); locked = true;
            DownloadCoordinator.check();
            Store.AuthSnapshot current = store.authSnapshot();
            if (current.generation != expected.generation || current.access.isEmpty()) return null;
            // Another request may already have refreshed this same session while we waited.
            if (!current.access.equals(expected.access)) return current.access;
            String rt = store.getRefreshForAccess(expected);
            if (rt.isEmpty()) return null;
            JSONObject r = Net.json(OAUTH + "token", "POST", Net.form(
                    "grant_type", "refresh_token", "refresh_token", rt,
                    "client_id", CLIENT_ID, "client_secret", CLIENT_SECRET), null);
            String access = r.getString("access_token"), refresh = r.optString("refresh_token", rt);
            boolean[] saved = {false};
            DownloadCoordinator.commit(() -> saved[0] = store.saveRefreshedTokens(expected, access, refresh));
            return saved[0] ? access : null;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); return null;
        } catch (Exception ex) { return null; }
        finally { if (locked) REFRESH_LOCK.unlock(); }
    }
    private JSONObject request(String path, String body) throws IOException {
        Store.AuthSnapshot auth = store.authSnapshot();
        String token = auth.access;
        if (token.trim().isEmpty()) throw new IOException("Нет авторизации, подключите аккаунт");
        try {
            return Net.json(API + path, body == null ? "GET" : "POST", body, token);
        } catch (Net.ApiError ex) {
            if (ex.status != 401) throw ex;
            String refreshed = refresh(auth);
            if (refreshed == null) throw ex;
            return Net.json(API + path, body == null ? "GET" : "POST", body, refreshed);
        }
    }
    long uid() throws IOException {
        try { return request("account/status", null).getJSONObject("result").getJSONObject("account").getLong("uid"); }
        catch (org.json.JSONException ex) { throw new IOException("Нет идентификатора пользователя", ex); }
    }
    List<String> likes() throws IOException {
        List<String> ids = new ArrayList<>();
        for (Like like : likedEntries()) ids.add(like.id);
        return ids;
    }
    List<Like> likedEntries() throws IOException {
        try {
            JSONArray all = request("users/" + uid() + "/likes/tracks", null)
                    .getJSONObject("result").getJSONObject("library").getJSONArray("tracks");
            List<Like> likes = new ArrayList<>();
            for (int i = 0; i < all.length(); i++) {
                JSONObject entry = all.getJSONObject(i);
                String id = entry.getString("id");
                if (id.matches("[0-9]+(:[0-9]+)?"))
                    likes.add(new Like(id, parseLikeTimestamp(entry.optString("timestamp", "")), i));
            }
            return likes;
        } catch (org.json.JSONException ex) { throw new IOException("Не удалось прочитать плейлист Мне нравится", ex); }
    }
    static long parseLikeTimestamp(String value) {
        if (value == null || value.trim().isEmpty()) return 0;
        try { return Math.max(0, Instant.parse(value).toEpochMilli()); }
        catch (DateTimeParseException ex) {
            try { return Math.max(0, OffsetDateTime.parse(value).toInstant().toEpochMilli()); }
            catch (DateTimeParseException ignored) { return 0; }
        }
    }
    List<Track> tracks(List<String> ids) throws IOException {
        if (ids.isEmpty()) return new ArrayList<>();
        try {
            String data = Net.form("track-ids", String.join(",", ids), "with-positions", "false");
            JSONArray array = request("tracks", data).getJSONArray("result");
            ArrayList<Track> tracks = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj == null) continue;
                String id = obj.optString("id", "");
                String title = obj.optString("title", "Track " + id);
                String album = "";
                JSONArray albums = obj.optJSONArray("albums");
                if (albums != null && albums.length() > 0 && albums.optJSONObject(0) != null)
                    album = albums.optJSONObject(0).optString("title", "");
                JSONArray artists = obj.optJSONArray("artists");
                ArrayList<String> names = new ArrayList<>();
                if (artists != null) for (int j = 0; j < artists.length(); j++) {
                    JSONObject a = artists.optJSONObject(j);
                    if (a != null) names.add(a.optString("name", ""));
                }
                tracks.add(new Track(id, title, String.join(", ", names), album));
            }
            return tracks;
        } catch (org.json.JSONException ex) { throw new IOException("Не удалось прочитать сведения о треках", ex); }
    }
    String mp3Url(String trackId) throws IOException {
        // Only regular, unencrypted MP3 format exposed by the legacy download-info API.
        try {
            JSONArray infos = request("tracks/" + trackId + "/download-info", null).getJSONArray("result");
            ArrayList<JSONObject> mp3 = new ArrayList<>();
            for (int i = 0; i < infos.length(); i++) {
                JSONObject info = infos.optJSONObject(i);
                if (info != null && "mp3".equalsIgnoreCase(info.optString("codec"))) mp3.add(info);
            }
            if (mp3.isEmpty()) throw new IOException("Для этого трека нет доступного MP3-потока");
            mp3.sort(Comparator.comparingInt((JSONObject o) -> o.optInt("bitrateInKbps", 0)).reversed());
            String infoUrl = mp3.get(0).getString("downloadInfoUrl");
            if (!infoUrl.startsWith("https://")) throw new IOException("Недопустимый URL метаданных аудио");
            java.net.HttpURLConnection conn = Net.connectStream(infoUrl);
            String xml;
            try { xml = Net.readText(conn.getInputStream()); }
            finally { Net.disconnect(conn); }
            return mp3UrlFromXml(xml);
        } catch (org.json.JSONException ex) { throw new IOException("Не удалось получить ссылку на MP3", ex); }
    }
    static String mp3UrlFromXml(String xml) throws IOException {
        String host = xmlTag(xml, "host");
        String path = xmlTag(xml, "path");
        String ts = xmlTag(xml, "ts");
        String s = xmlTag(xml, "s");
        if (!host.matches("[A-Za-z0-9.-]+"))
            throw new IOException("Неподдерживаемый ответ CDN: неверное поле host");
        if (!path.startsWith("/"))
            throw new IOException("Неподдерживаемый ответ CDN: неверное поле path");
        // CDN timestamps contain hexadecimal digits. Preserve the original URL token verbatim.
        if (!ts.matches("[0-9A-Fa-f]+"))
            throw new IOException("Неподдерживаемый ответ CDN: неверное поле ts");
        String sign = md5(CDN_SALT + path.substring(1) + s);
        return "https://" + host + "/get-mp3/" + sign + "/" + ts + path;
    }
    private static String xmlTag(String xml, String tag) throws IOException {
        Matcher m = Pattern.compile("<" + tag + ">([^<]{1,2000})</" + tag + ">").matcher(xml);
        if (!m.find()) throw new IOException("Нет поля " + tag + " в CDN-ответе");
        return m.group(1);
    }
    private static String md5(String text) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return hex.toString();
        } catch (Exception ex) { throw new IOException("MD5 недоступен", ex); }
    }
    static final class Like {
        final String id;
        final long timestampMillis;
        final int order;
        Like(String id, long timestampMillis, int order) {
            this.id = id; this.timestampMillis = timestampMillis; this.order = order;
        }
    }
    static final class Track {
        final String id, title, artist, album;
        Track(String id, String title, String artist, String album) {
            this.id = id; this.title = title; this.artist = artist.trim().isEmpty() ? "Unknown artist" : artist; this.album = album;
        }
    }
}
