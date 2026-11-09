package com.likefolder.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.HashSet;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Private settings and Android Keystore-protected OAuth credentials. */
final class Store {
    private static final String KEY_ALIAS = "likefolder.oauth.v1";
    private final Context context;
    final SharedPreferences prefs;
    Store(Context ctx) {
        context = ctx.getApplicationContext();
        prefs = context.getSharedPreferences("likefolder", Context.MODE_PRIVATE);
    }

    synchronized String getAccess() { return decrypt(prefs.getString("access", "")); }
    synchronized String getRefresh() { return decrypt(prefs.getString("refresh", "")); }
    synchronized void saveTokens(String access, String refresh) {
        SharedPreferences.Editor e = prefs.edit().putString("access", encrypt(access));
        if (refresh != null && !refresh.trim().isEmpty()) e.putString("refresh", encrypt(refresh));
        e.apply();
    }
    synchronized boolean isLoggedIn() { return !getAccess().trim().isEmpty(); }
    synchronized void signOut() {
        new LibraryRepository(context).clear();
        prefs.edit().clear().apply();
    }
    synchronized void markDownloaded(String id) {
        Set<String> downloaded = ids("downloaded"), seen = ids("seen");
        downloaded.add(id); seen.add(id);
        prefs.edit().putStringSet("downloaded", downloaded).putStringSet("seen", seen).apply();
    }
    synchronized Set<String> ids(String key) { return new HashSet<>(prefs.getStringSet(key, new HashSet<>())); }
    synchronized void addId(String key, String value) {
        Set<String> items = ids(key); items.add(value);
        prefs.edit().putStringSet(key, items).apply();
    }
    synchronized void setIds(String key, Set<String> items) {
        prefs.edit().putStringSet(key, new HashSet<>(items)).apply();
    }
    String folder() { return prefs.getString("folder", "YandexLiked"); }
    synchronized void folder(String value) { prefs.edit().putString("folder", value).apply(); }
    boolean hasBaseline() { return prefs.getBoolean("baseline", false); }
    synchronized void markBaseline() { prefs.edit().putBoolean("baseline", true).apply(); }
    synchronized void status(String s) { prefs.edit().putString("status", s).putLong("status_at", System.currentTimeMillis()).apply(); }
    String status() { return prefs.getString("status", "Ожидаю подключения"); }
    boolean monitoring() { return prefs.getBoolean("monitoring", false); }
    void monitoring(boolean enabled) { prefs.edit().putBoolean("monitoring", enabled).apply(); }
    void likedCount(int count) { prefs.edit().putInt("liked_count", count).apply(); }
    int likedCount() { return prefs.getInt("liked_count", 0); }

    private static SecretKey secretKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) return (SecretKey) ks.getKey(KEY_ALIAS, null);
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return g.generateKey();
    }
    private static String encrypt(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey());
            byte[] iv = cipher.getIV();
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return Base64.encodeToString(combined, Base64.NO_WRAP);
        } catch (Exception ex) { throw new IllegalStateException("Не удалось защитить токен", ex); }
    }
    private static String decrypt(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        try {
            byte[] combined = Base64.decode(value, Base64.NO_WRAP);
            if (combined.length < 13) return "";
            byte[] iv = java.util.Arrays.copyOfRange(combined, 0, 12);
            byte[] encoded = java.util.Arrays.copyOfRange(combined, 12, combined.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encoded), StandardCharsets.UTF_8);
        } catch (Exception ex) { return ""; }
    }
}
