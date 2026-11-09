package com.likefolder.app;

import android.net.Uri;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import java.util.Scanner;

/** Small networking helper; credentials are ONLY sent to the API host. */
final class Net {
    private static final ApiRequestQueue MUSIC_REQUESTS = new ApiRequestQueue();
    interface ConnectionFactory { HttpURLConnection open(URL url) throws IOException; }
    static final class ApiError extends ApiRequestQueue.HttpError {
        ApiError(int status, String message) { this(status, message, 0); }
        ApiError(int status, String message, long retryAfterMillis) { super(status, message, retryAfterMillis); }
    }
    static String form(String... fields) {
        if (fields.length % 2 != 0) throw new IllegalArgumentException("Expected pairs");
        Uri.Builder builder = new Uri.Builder();
        for (int i = 0; i < fields.length; i += 2) builder.appendQueryParameter(fields[i], fields[i + 1]);
        String encoded = builder.build().getEncodedQuery();
        return encoded == null ? "" : encoded;
    }
    static JSONObject json(String url, String method, String body, String oauth) throws IOException {
        return json(url, method, body, oauth, MUSIC_REQUESTS,
                parsed -> (HttpURLConnection) parsed.openConnection());
    }
    static JSONObject json(String url, String method, String body, String oauth,
                           ApiRequestQueue queue, ConnectionFactory connections) throws IOException {
        DownloadCoordinator.check();
        URL parsed = new URL(url);
        if (!parsed.getProtocol().equals("https")) throw new IOException("HTTPS обязателен");
        if (parsed.getHost().equalsIgnoreCase("api.music.yandex.net"))
            return queue.execute(() -> jsonOnce(parsed, method, body, oauth, connections));
        // Device-code polling already has its own timing/retry rules. Do not alter OAuth here.
        return jsonOnce(parsed, method, body, oauth, connections);
    }
    private static JSONObject jsonOnce(URL url, String method, String body, String oauth,
                                       ConnectionFactory connections) throws IOException {
        DownloadCoordinator.check();
        HttpURLConnection conn = connections.open(url);
        try {
            DownloadCoordinator.register(conn);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod(method);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Accept-Language", "ru");
            conn.setRequestProperty("User-Agent", "LikeFolder/0.1 (Android)");
            if (oauth != null && !oauth.trim().isEmpty()) {
                if (!url.getHost().equalsIgnoreCase("api.music.yandex.net"))
                    throw new IOException("Заблокирована передача токена на посторонний сервер");
                conn.setRequestProperty("Authorization", "OAuth " + oauth);
            }
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
                byte[] data = body.getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = conn.getOutputStream()) { out.write(data); }
            }
            int code = conn.getResponseCode();
            InputStream input = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String result = readText(input);
            DownloadCoordinator.check();
            if (code >= 400) {
                String detail = result.length() > 250 ? result.substring(0, 250) : result;
                long retryAfter = ApiRequestQueue.retryAfterMillis(conn.getHeaderField("Retry-After"), System.currentTimeMillis());
                throw new ApiError(code, "HTTP " + code + " (" + detail + ")", retryAfter);
            }
            try { return new JSONObject(result); }
            catch (Exception e) { throw new IOException("Некорректный JSON от сервера", e); }
        } finally { disconnect(conn); }
    }
    static String readText(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream stream = in; Scanner scanner = new Scanner(stream, StandardCharsets.UTF_8.name())) {
            scanner.useDelimiter("\\A");
            String result = scanner.hasNext() ? scanner.next() : "";
            DownloadCoordinator.check();
            if (scanner.ioException() != null) throw scanner.ioException();
            return result;
        }
    }
    static HttpURLConnection connectStream(String url) throws IOException {
        DownloadCoordinator.check();
        URL parsed = new URL(url);
        if (!parsed.getProtocol().equals("https")) throw new IOException("Только HTTPS аудио");
        HttpURLConnection conn = (HttpURLConnection) parsed.openConnection();
        try {
            DownloadCoordinator.register(conn);
            conn.setConnectTimeout(15000); conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(false); // do not follow arbitrary redirects
            conn.setRequestProperty("User-Agent", "LikeFolder/0.1 (Android)");
            int code = conn.getResponseCode();
            DownloadCoordinator.check();
            if (code != 200) throw new IOException("CDN вернул HTTP " + code);
            return conn;
        } catch (IOException | RuntimeException ex) {
            disconnect(conn);
            DownloadCoordinator.check();
            throw ex;
        }
    }
    static void disconnect(HttpURLConnection connection) {
        DownloadCoordinator.release(connection);
        try { connection.disconnect(); } catch (RuntimeException ignored) { }
    }
    private Net() { }
}
