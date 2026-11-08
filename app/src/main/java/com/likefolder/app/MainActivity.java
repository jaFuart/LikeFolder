package com.likefolder.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lightweight native Android UI, no overlays or hooks into the official Yandex app. */
public final class MainActivity extends Activity {
    private static final int BACKGROUND = Color.rgb(12, 16, 26);
    private static final int CARD = Color.rgb(24, 29, 43);
    private static final int MUTED = Color.rgb(159, 170, 191);
    private static final int ACCENT = Color.rgb(244, 206, 99);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Store store;
    private LinearLayout loginCard, controls;
    private TextView statusText, countText, folderLabel, codeLabel, codeHint;
    private Button codeButton, copyButton;
    private EditText folderEdit;
    private Switch quickSwitch;
    private CheckBox wifiOnly;
    private boolean changingSwitch = false;
    private volatile boolean authorizing = false;
    private volatile String verificationUrl = "", userCode = "";
    private final Runnable ticker = new Runnable() {
        @Override public void run() { refresh(); handler.postDelayed(this, 2000); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new Store(this);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 41);
        buildUI();
        if (store.isLoggedIn()) SyncWorker.schedule(this);
    }
    @Override protected void onResume() {
        super.onResume(); handler.removeCallbacks(ticker); ticker.run();
    }
    @Override protected void onPause() { handler.removeCallbacks(ticker); super.onPause(); }
    @Override protected void onDestroy() { executor.shutdownNow(); super.onDestroy(); }

    private void buildUI() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(30), dp(22), dp(26));
        scroll.addView(root);
        setContentView(scroll);

        TextView logo = text("♥  LIKEFOLDER", 29, Color.WHITE, true);
        root.addView(logo);
        TextView subtitle = text("Понравившиеся треки — в твоей папке MP3", 15, MUTED, false);
        marginBottom(subtitle, 24); root.addView(subtitle);

        LinearLayout stats = card();
        TextView statsTitle = text("ВАША МЕДИАТЕКА", 11, ACCENT, true);
        stats.addView(statsTitle);
        countText = text("♡  0 понравилось       ↓  0 MP3", 18, Color.WHITE, true);
        marginTop(countText, 9); stats.addView(countText);
        statusText = text("Ожидание подключения", 13, MUTED, false);
        marginTop(statusText, 10); stats.addView(statusText);
        root.addView(stats);

        loginCard = card();
        TextView loginTitle = text("Подключение Яндекс ID", 19, Color.WHITE, true);
        loginCard.addView(loginTitle);
        TextView loginDesc = text("Вход по коду на странице Яндекса. Пароль не вводится в LikeFolder.", 14, MUTED, false);
        marginTop(loginDesc, 8); loginCard.addView(loginDesc);
        Button loginBtn = button("Получить код входа", true);
        marginTop(loginBtn, 16); loginCard.addView(loginBtn);
        loginBtn.setOnClickListener(v -> startLogin(loginBtn));
        codeLabel = text("", 26, ACCENT, true);
        codeLabel.setTextIsSelectable(true);
        marginTop(codeLabel, 14);
        loginCard.addView(codeLabel);
        codeHint = text("", 12, MUTED, false);
        loginCard.addView(codeHint);
        codeButton = button("Открыть страницу авторизации", false);
        marginTop(codeButton, 10); loginCard.addView(codeButton);
        codeButton.setVisibility(View.GONE);
        codeButton.setOnClickListener(v -> {
            if (!verificationUrl.isEmpty() && verificationUrl.startsWith("https://"))
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(verificationUrl)));
        });
        copyButton = button("Скопировать код", false);
        loginCard.addView(copyButton);
        copyButton.setVisibility(View.GONE);
        copyButton.setOnClickListener(v -> {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Yandex code", userCode));
            codeHint.setText("Код скопирован. Подтверди вход в Яндексе.");
        });
        Button manual = button("У меня уже есть OAuth-токен", false);
        marginTop(manual, 9); loginCard.addView(manual);
        manual.setOnClickListener(v -> showTokenDialog());
        marginTop(loginCard, 16); root.addView(loginCard);

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        marginTop(controls, 16); root.addView(controls);
        LinearLayout syncCard = card();
        syncCard.addView(text("Синхронизация", 20, Color.WHITE, true));
        TextView explain = text("Новые лайки проверяются в фоне примерно раз в 15 минут. Android может откладывать проверки.", 13, MUTED, false);
        marginTop(explain, 7); syncCard.addView(explain);
        Button sync = button("Проверить новые лайки", true);
        marginTop(sync, 14); syncCard.addView(sync);
        sync.setOnClickListener(v -> startServiceAction(SyncService.ACTION_ONCE));
        Button all = button("Выгрузить все понравившиеся MP3", false);
        marginTop(all, 8); syncCard.addView(all);
        all.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Полная выгрузка")
                .setMessage("Сохранить все доступные MP3 из «Мне нравится»? Это может занять много времени и мобильного трафика.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Начать", (d, w) -> startServiceAction(SyncService.ACTION_ALL)).show());
        quickSwitch = new Switch(this);
        quickSwitch.setText("Быстрый режим · каждые 2 минуты");
        quickSwitch.setTextSize(14); quickSwitch.setTextColor(Color.WHITE);
        quickSwitch.setPadding(0, dp(15), 0, dp(5));
        syncCard.addView(quickSwitch);
        quickSwitch.setOnCheckedChangeListener((button, enabled) -> {
            if (changingSwitch) return;
            if (enabled) startServiceAction(SyncService.ACTION_MONITOR);
            else startService(new Intent(this, SyncService.class).setAction(SyncService.ACTION_STOP));
        });
        TextView quickNote = text("При включении отображается постоянное уведомление. Android 15+ ограничивает время работы такого режима.", 12, MUTED, false);
        syncCard.addView(quickNote);
        wifiOnly = new CheckBox(this);
        wifiOnly.setText("Автоматически скачивать только по Wi-Fi");
        wifiOnly.setTextSize(13); wifiOnly.setTextColor(Color.WHITE);
        wifiOnly.setChecked(store.prefs.getBoolean("wifi_only", true));
        marginTop(wifiOnly, 8); syncCard.addView(wifiOnly);
        wifiOnly.setOnCheckedChangeListener((button, enabled) -> store.prefs.edit().putBoolean("wifi_only", enabled).apply());
        controls.addView(syncCard);

        LinearLayout folderCard = card();
        folderCard.addView(text("Папка сохранения", 20, Color.WHITE, true));
        folderLabel = text("Music/YandexLiked/", 14, ACCENT, true);
        marginTop(folderLabel, 10); folderCard.addView(folderLabel);
        folderEdit = new EditText(this);
        folderEdit.setSingleLine(true);
        folderEdit.setTextColor(Color.WHITE);
        folderEdit.setHintTextColor(MUTED);
        folderEdit.setHint("Например, MyFavoriteMusic");
        folderEdit.setText(store.folder());
        folderEdit.setTextSize(15);
        folderEdit.setInputType(InputType.TYPE_CLASS_TEXT);
        marginTop(folderEdit, 8); folderCard.addView(folderEdit);
        Button saveFolder = button("Сохранить имя папки", false);
        marginTop(saveFolder, 8); folderCard.addView(saveFolder);
        saveFolder.setOnClickListener(v -> {
            String name = folderEdit.getText().toString().trim();
            if (!name.matches("[\\p{L}\\p{N} _.-]{1,60}") || name.equals(".") || name.equals("..")) {
                folderEdit.setError("Используй буквы, цифры, пробел, _, -, . (до 60 символов)"); return;
            }
            store.folder(name); folderLabel.setText("Music/" + name + "/");
            store.status("Папка сохранения изменена");
        });
        marginTop(folderCard, 14); controls.addView(folderCard);

        LinearLayout account = card();
        account.addView(text("Аккаунт", 18, Color.WHITE, true));
        TextView note = text("Приложение не связано с Яндексом. Использует неофициальный API и не может гарантировать доступность MP3 для каждого трека.", 13, MUTED, false);
        marginTop(note, 7); account.addView(note);
        Button logout = button("Отключить аккаунт", false);
        marginTop(logout, 12); account.addView(logout);
        logout.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Отключить Яндекс ID?")
                .setMessage("Авторизация и история синхронизации очистятся. Уже сохранённые MP3 останутся на телефоне.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Отключить", (d, w) -> logout()).show());
        marginTop(account, 14); controls.addView(account);
    }
    private void startLogin(Button loginButton) {
        if (authorizing) return;
        authorizing = true; loginButton.setEnabled(false);
        codeHint.setText("Получаю код авторизации…");
        executor.execute(() -> {
            try {
                JSONObject code = YandexApi.requestCode(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
                String device = code.getString("device_code");
                String user = code.getString("user_code");
                String link = code.optString("verification_url", "https://ya.ru/device");
                int waitSeconds = Math.max(5, code.optInt("interval", 5));
                long until = System.currentTimeMillis() + 1000L * code.optInt("expires_in", 300);
                verificationUrl = link; userCode = user;
                runOnUiThread(() -> {
                    codeLabel.setText(user);
                    codeHint.setText("Открой сайт, введи код и разреши доступ. Здесь вход продолжится автоматически.");
                    codeButton.setVisibility(View.VISIBLE);
                    copyButton.setVisibility(View.VISIBLE);
                });
                while (!Thread.currentThread().isInterrupted() && System.currentTimeMillis() < until) {
                    Thread.sleep(waitSeconds * 1000L);
                    try {
                        JSONObject result = YandexApi.pollCode(device);
                        String access = result.getString("access_token");
                        store.saveTokens(access, result.optString("refresh_token", ""));
                        store.status("Аккаунт подключён. Получаю исходный список лайков…");
                        runOnUiThread(() -> {
                            loginButton.setEnabled(true); codeHint.setText("Вход выполнен");
                            SyncWorker.schedule(this);
                            startServiceAction(SyncService.ACTION_ONCE);
                            refresh();
                        });
                        return;
                    } catch (Net.ApiError ex) {
                        String message = ex.getMessage();
                        if (message.contains("authorization_pending")) continue;
                        if (message.contains("slow_down")) { waitSeconds += 5; continue; }
                        throw ex;
                    }
                }
                throw new IllegalStateException("Время действия кода истекло. Получи новый код.");
            } catch (Exception ex) {
                runOnUiThread(() -> { codeHint.setText("Не удалось подключиться: " + ex.getMessage()); loginButton.setEnabled(true); });
            } finally { authorizing = false; }
        });
    }
    private void showTokenDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("OAuth access token");
        new AlertDialog.Builder(this).setTitle("Ручное подключение")
                .setMessage("Используй только собственный токен Яндекс Музыки. Не отправляй его другим людям.")
                .setView(input)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Подключить", (dialog, which) -> {
                    String token = input.getText().toString().trim();
                    if (token.length() < 20) { store.status("Токен выглядит слишком коротким"); return; }
                    store.saveTokens(token, "");
                    store.status("Проверяю введённый токен…");
                    SyncWorker.schedule(this);
                    startServiceAction(SyncService.ACTION_ONCE);
                    refresh();
                }).show();
    }
    private void logout() {
        startService(new Intent(this, SyncService.class).setAction(SyncService.ACTION_STOP));
        SyncWorker.cancel(this);
        store.signOut();
        verificationUrl = ""; userCode = "";
        codeLabel.setText(""); codeHint.setText("");
        codeButton.setVisibility(View.GONE); copyButton.setVisibility(View.GONE);
        refresh();
    }
    private void startServiceAction(String action) {
        try {
            startForegroundService(new Intent(this, SyncService.class).setAction(action));
            store.status("Задача поставлена на выполнение…");
        } catch (Exception ex) {
            store.status("Не удалось запустить службу: " + ex.getMessage());
            store.monitoring(false);
        }
    }
    private void refresh() {
        if (statusText == null) return;
        boolean logged = store.isLoggedIn();
        loginCard.setVisibility(logged ? View.GONE : View.VISIBLE);
        controls.setVisibility(logged ? View.VISIBLE : View.GONE);
        statusText.setText(store.status());
        countText.setText("♡  " + store.likedCount() + " понравилось      ↓  " + store.ids("downloaded").size() + " MP3");
        if (logged) {
            String path = "Music/" + store.folder() + "/";
            if (!folderLabel.getText().toString().equals(path)) folderLabel.setText(path);
            changingSwitch = true;
            if (quickSwitch.isChecked() != store.monitoring()) quickSwitch.setChecked(store.monitoring());
            changingSwitch = false;
        }
    }
    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(17), dp(18), dp(18));
        card.setBackground(round(CARD, 17));
        return card;
    }
    private TextView text(String text, int size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(text); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setLineSpacing(dp(2), 1f);
        return t;
    }
    private Button button(String title, boolean primary) {
        Button btn = new Button(this);
        btn.setText(title); btn.setAllCaps(false); btn.setTextSize(14);
        btn.setTextColor(primary ? BACKGROUND : Color.WHITE);
        btn.setBackground(round(primary ? ACCENT : Color.rgb(46, 54, 72), 12));
        btn.setPadding(dp(12), dp(8), dp(12), dp(8));
        btn.setMinHeight(dp(48));
        return btn;
    }
    private GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private int dp(int n) { return (int)(getResources().getDisplayMetrics().density * n + 0.5f); }
    private void marginTop(View v, int dp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(dp); v.setLayoutParams(p);
    }
    private void marginBottom(View v, int dp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(dp); v.setLayoutParams(p);
    }
}
