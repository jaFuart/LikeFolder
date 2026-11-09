package com.likefolder.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Lightweight native Android UI, no overlays or hooks into the official Yandex app. */
public final class MainActivity extends Activity {
    private static final int BACKGROUND = Color.rgb(12, 16, 26);
    private static final int CARD = Color.rgb(24, 29, 43);
    private static final int MUTED = Color.rgb(159, 170, 191);
    private static final int ACCENT = Color.rgb(244, 206, 99);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Store store;
    private LinearLayout loginCard, controls, codeRow, downloadPanel;
    private TextView statusText, countText, folderLabel, codeLabel, codeHint;
    private Button loginButton, codeButton, syncButton, allButton, stopButton;
    private ImageButton copyButton;
    private ProgressBar downloadProgress;
    private TextView downloadText;
    private EditText folderEdit;
    private Switch quickSwitch;
    private CheckBox wifiOnly;
    private boolean changingSwitch = false;
    private boolean authorizing = false;
    private boolean hasReceivedCode = false;
    private int loginAttempt = 0;
    private Future<?> loginTask;
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
    @Override protected void onDestroy() {
        cancelLogin(); handler.removeCallbacksAndMessages(null); executor.shutdownNow(); super.onDestroy();
    }

    private void buildUI() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(24));
        scroll.addView(root);
        setContentView(scroll);
        AppUi.prepareWindow(this, scroll);

        LinearLayout brand = new LinearLayout(this); brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView brandIcon = new ImageView(this); brandIcon.setImageResource(R.drawable.ic_likefolder);
        brandIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(48), dp(48)); iconParams.rightMargin = dp(10);
        brand.addView(brandIcon, iconParams);
        TextView logo = text("LIKEFOLDER", 27, Color.WHITE, true);
        brand.addView(logo); root.addView(brand);
        TextView subtitle = text("Понравившиеся треки — в твоей папке MP3", 15, MUTED, false);
        marginBottom(subtitle, 24); root.addView(subtitle);

        LinearLayout stats = card();
        TextView statsTitle = text("ВАША МЕДИАТЕКА", 11, ACCENT, true);
        stats.addView(statsTitle);
        countText = text("♡  0 понравилось       ↓  0 MP3", 18, Color.WHITE, true);
        marginTop(countText, 9); stats.addView(countText);
        statusText = text("Ожидание подключения", 13, MUTED, false);
        marginTop(statusText, 10); stats.addView(statusText);
        downloadPanel = new LinearLayout(this); downloadPanel.setOrientation(LinearLayout.VERTICAL);
        downloadText = text("", 12, MUTED, false); downloadText.setMaxLines(2);
        marginTop(downloadText, 12); downloadPanel.addView(downloadText);
        downloadProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        downloadProgress.setProgressTintList(ColorStateList.valueOf(ACCENT));
        downloadProgress.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        downloadPanel.addView(downloadProgress, new LinearLayout.LayoutParams(-1, dp(6)));
        stopButton = button("Остановить скачивание", false); stopButton.setId(R.id.download_stop);
        stopButton.setTextColor(AppUi.CORAL);
        stopButton.setOnClickListener(v -> { SyncService.stopDownloads(this); refresh(); });
        marginTop(stopButton, 12); downloadPanel.addView(stopButton);
        downloadPanel.setVisibility(View.GONE); stats.addView(downloadPanel);
        root.addView(stats);

        loginCard = card();
        TextView loginTitle = text("Подключение Яндекс ID", 19, Color.WHITE, true);
        loginCard.addView(loginTitle);
        TextView loginDesc = text("Вход по коду на странице Яндекса. Пароль не вводится в LikeFolder.", 14, MUTED, false);
        marginTop(loginDesc, 8); loginCard.addView(loginDesc);
        loginButton = button("Получить код входа", true);
        loginButton.setId(R.id.login_retry);
        marginTop(loginButton, 16); loginCard.addView(loginButton);
        loginButton.setOnClickListener(v -> startLogin());
        codeRow = new LinearLayout(this); codeRow.setGravity(Gravity.CENTER_VERTICAL);
        codeRow.setBackground(AppUi.ripple(this, BACKGROUND, 14));
        codeRow.setPadding(dp(14), dp(8), dp(6), dp(8));
        marginTop(codeRow, 16); loginCard.addView(codeRow); codeRow.setVisibility(View.GONE);
        codeRow.setOnClickListener(v -> copyLoginCode());
        codeLabel = text("", 26, ACCENT, true); codeLabel.setId(R.id.login_code);
        codeLabel.setLetterSpacing(.08f); codeLabel.setMinHeight(dp(48)); codeLabel.setGravity(Gravity.CENTER_VERTICAL);
        codeLabel.setOnClickListener(v -> copyLoginCode());
        codeRow.addView(codeLabel, new LinearLayout.LayoutParams(0, -2, 1));
        copyButton = AppUi.iconButton(this, R.drawable.ic_copy, "Скопировать код"); copyButton.setId(R.id.login_copy);
        copyButton.setOnClickListener(v -> copyLoginCode());
        codeRow.addView(copyButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        codeHint = text("", 12, MUTED, false);
        marginTop(codeHint, 10);
        loginCard.addView(codeHint);
        codeButton = button("Открыть страницу авторизации", true); codeButton.setId(R.id.open_authorization);
        marginTop(codeButton, 16); loginCard.addView(codeButton);
        codeButton.setVisibility(View.GONE);
        codeButton.setOnClickListener(v -> {
            if (!verificationUrl.isEmpty() && verificationUrl.startsWith("https://")) {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(verificationUrl))); }
                catch (Exception ex) { codeHint.setText("Не удалось открыть браузер. Открой oauth.yandex.ru/device и введи код."); }
            }
        });
        TextView manual = AppUi.link(this, "У меня уже есть OAuth-токен"); manual.setId(R.id.manual_token);
        marginTop(manual, 12); loginCard.addView(manual);
        manual.setOnClickListener(v -> showTokenDialog());
        marginTop(loginCard, 16); root.addView(loginCard);

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        marginTop(controls, 16); root.addView(controls);
        LinearLayout libraryCard = card();
        libraryCard.addView(text("Твоя музыка", 20, Color.WHITE, true));
        TextView libraryNote = text("Найди любимые треки, отметь нужные и скачай их в свою папку.", 13, MUTED, false);
        marginTop(libraryNote, 8); libraryCard.addView(libraryNote);
        Button library = button("Открыть медиатеку", true);
        marginTop(library, 14); libraryCard.addView(library);
        library.setOnClickListener(v -> startActivity(new Intent(this, LibraryActivity.class)));
        controls.addView(libraryCard);
        LinearLayout syncCard = card();
        marginTop(syncCard, 16);
        syncCard.addView(text("Синхронизация", 20, Color.WHITE, true));
        TextView explain = text("Новые лайки проверяются в фоне примерно раз в 15 минут. Android может откладывать проверки.", 13, MUTED, false);
        marginTop(explain, 7); syncCard.addView(explain);
        syncButton = button("Проверить новые лайки", false);
        marginTop(syncButton, 14); syncCard.addView(syncButton);
        syncButton.setOnClickListener(v -> startServiceAction(SyncService.ACTION_ONCE));
        allButton = button("Выгрузить все понравившиеся MP3", false);
        marginTop(allButton, 12); syncCard.addView(allButton);
        allButton.setOnClickListener(v -> new AlertDialog.Builder(this)
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
        marginTop(syncCard, 14); controls.addView(syncCard);

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
        Button shortcut = button("Добавить ярлык на главный экран", false);
        marginTop(shortcut, 12); account.addView(shortcut);
        shortcut.setOnClickListener(v -> pinShortcut());
        Button logout = button("Отключить аккаунт", false);
        marginTop(logout, 12); account.addView(logout);
        logout.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Отключить Яндекс ID?")
                .setMessage("Авторизация и история синхронизации очистятся. Уже сохранённые MP3 останутся на телефоне.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Отключить", (d, w) -> logout()).show());
        marginTop(account, 14); controls.addView(account);
    }
    private void startLogin() {
        if (authorizing && userCode.isEmpty()) return;
        if (authorizing) cancelLogin();
        final int attempt = ++loginAttempt;
        clearLoginCode();
        authorizing = true; loginButton.setEnabled(false);
        codeHint.setText("Получаю код авторизации…");
        loginTask = executor.submit(() -> {
            try {
                JSONObject code = YandexApi.requestCode(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                String device = code.getString("device_code");
                String user = code.getString("user_code");
                String link = code.optString("verification_url", "https://oauth.yandex.ru/device");
                long interval = 1000L * Math.max(5, code.optInt("interval", 5));
                long until = SystemClock.elapsedRealtime() + 1000L * Math.max(0, code.optInt("expires_in", 300));
                postLoginUpdate(attempt, () -> showLoginCode(user, link));
                JSONObject result = DeviceCodePoller.await(() -> YandexApi.pollCode(device),
                        interval, until, SystemClock::elapsedRealtime, Thread::sleep,
                        retrying -> postLoginUpdate(attempt, () -> codeHint.setText(retrying
                                ? "Связь с Яндексом прервалась. Повторяю проверку автоматически — новый код пока не нужен. Если ожидание затянется, переключи Wi-Fi или мобильный интернет."
                                : "Ожидаю подтверждения на странице Яндекса. Вход продолжится автоматически.")));
                String access = result.getString("access_token");
                String refreshToken = result.optString("refresh_token", "");
                postLoginUpdate(attempt, () -> {
                    try {
                        store.saveTokens(access, refreshToken);
                        store.status("Аккаунт подключён. Получаю исходный список лайков…");
                        finishLogin("Вход выполнен");
                        SyncWorker.schedule(this);
                        startServiceAction(SyncService.ACTION_ONCE);
                        refresh();
                    } catch (Exception ex) {
                        finishLogin(loginError(ex));
                    }
                });
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                postLoginUpdate(attempt, () -> finishLogin("Вход отменён. Получи новый код, чтобы повторить."));
            } catch (Exception ex) {
                postLoginUpdate(attempt, () -> finishLogin(loginError(ex)));
            }
        });
    }
    private void postLoginUpdate(int attempt, Runnable update) {
        runOnUiThread(() -> {
            if (attempt == loginAttempt && !isDestroyed() && !isFinishing()) update.run();
        });
    }
    void showLoginCode(String user, String link) {
        verificationUrl = link; userCode = user; hasReceivedCode = true;
        codeLabel.setText(user); codeLabel.setContentDescription("Код входа " + user + ". Нажми, чтобы скопировать");
        codeHint.setText("Нажми на код, чтобы скопировать. Открой Яндекс и подтверди вход — здесь он продолжится автоматически.");
        codeRow.setVisibility(View.VISIBLE); codeButton.setVisibility(View.VISIBLE);
        loginButton.setText("Повторно получить код"); loginButton.setEnabled(true);
        loginButton.setTextColor(MUTED); loginButton.setBackground(AppUi.ripple(this, AppUi.BORDER, 14));
    }
    private void copyLoginCode() {
        if (userCode.isEmpty()) return;
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Код Яндекса", userCode));
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Код скопирован", Toast.LENGTH_SHORT).show();
        codeLabel.announceForAccessibility("Код скопирован");
    }
    private void clearLoginCode() {
        verificationUrl = ""; userCode = "";
        codeLabel.setText("");
        codeButton.setVisibility(View.GONE); codeRow.setVisibility(View.GONE);
    }
    private void finishLogin(String message) {
        authorizing = false; loginTask = null;
        loginButton.setEnabled(true);
        loginButton.setText(hasReceivedCode ? "Повторно получить код" : "Получить код входа");
        clearLoginCode(); codeHint.setText(message);
    }
    private void cancelLogin() {
        ++loginAttempt;
        if (loginTask != null) loginTask.cancel(true);
        finishLogin("");
    }
    private static String loginError(Exception ex) {
        if (ex instanceof UnknownHostException)
            return "Не удалось найти сервер Яндекса. Проверь подключение, VPN и частный DNS или переключи сеть, затем получи новый код.";
        if (ex instanceof SocketTimeoutException || ex instanceof SocketException)
            return "Не удалось связаться с Яндексом. Проверь интернет и получи новый код.";
        return "Не удалось подключиться: " + ex.getMessage();
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
                    cancelLogin();
                    store.saveTokens(token, "");
                    store.status("Проверяю введённый токен…");
                    SyncWorker.schedule(this);
                    startServiceAction(SyncService.ACTION_ONCE);
                    refresh();
                }).show();
    }
    private void logout() {
        cancelLogin();
        SyncService.stopDownloads(this);
        startService(new Intent(this, SyncService.class).setAction(SyncService.ACTION_STOP));
        SyncWorker.cancel(this);
        store.signOut();
        hasReceivedCode = false; loginButton.setText("Получить код входа");
        loginButton.setTextColor(BACKGROUND); loginButton.setBackground(AppUi.ripple(this, ACCENT, 14));
        refresh();
    }
    private void pinShortcut() {
        ShortcutManager shortcuts = getSystemService(ShortcutManager.class);
        if (shortcuts != null && shortcuts.isRequestPinShortcutSupported()) {
            ShortcutInfo shortcut = new ShortcutInfo.Builder(this, "likefolder-home")
                    .setShortLabel("LikeFolder").setLongLabel("LikeFolder — моя музыка")
                    .setIcon(Icon.createWithResource(this, R.drawable.ic_likefolder))
                    .setIntent(new Intent(this, MainActivity.class).setAction(Intent.ACTION_MAIN)).build();
            shortcuts.requestPinShortcut(shortcut, null);
        } else Toast.makeText(this, "Добавь LikeFolder на главный экран из списка приложений", Toast.LENGTH_LONG).show();
    }
    private void startServiceAction(String action) {
        try {
            startForegroundService(new Intent(this, SyncService.class).setAction(action));
            store.status("Задача поставлена на выполнение…");
            handler.postDelayed(this::refresh, 150);
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
        DownloadCoordinator.Snapshot state = DownloadCoordinator.snapshot();
        downloadPanel.setVisibility(state.running ? View.VISIBLE : View.GONE);
        downloadText.setText(state.stopping ? "Останавливаю загрузку…" : state.done + " / " + state.total + " · " + state.currentTitle);
        downloadProgress.setIndeterminate(state.total <= 0);
        downloadProgress.setMax(Math.max(1, state.total)); downloadProgress.setProgress(state.done);
        stopButton.setEnabled(!state.stopping); stopButton.setAlpha(state.stopping ? .4f : 1f);
        syncButton.setEnabled(!state.running); allButton.setEnabled(!state.running);
        syncButton.setAlpha(state.running ? .45f : 1f); allButton.setAlpha(state.running ? .45f : 1f);
        if (logged) {
            String path = "Music/" + store.folder() + "/";
            if (!folderLabel.getText().toString().equals(path)) folderLabel.setText(path);
            changingSwitch = true;
            if (quickSwitch.isChecked() != store.monitoring()) quickSwitch.setChecked(store.monitoring());
            changingSwitch = false;
        }
    }
    private LinearLayout card() {
        return AppUi.card(this);
    }
    private TextView text(String text, int size, int color, boolean bold) {
        return AppUi.text(this, text, size, color, bold);
    }
    private Button button(String title, boolean primary) {
        return AppUi.button(this, title, primary);
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
