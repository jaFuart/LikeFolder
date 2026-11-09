package com.likefolder.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Cached, searchable library. ListView recycles rows instead of inflating the entire collection. */
public final class LibraryActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Set<String> selected = new HashSet<>();
    private Set<String> downloaded = new HashSet<>();
    private List<LibraryTrack> tracks = new ArrayList<>(), visible = new ArrayList<>();
    private Store store;
    private LibraryRepository repository;
    private EditText search;
    private Spinner filter, sort;
    private TextView summary, loadStatus, selectionSummary, empty, jobStatus, selectAll, clearSelection;
    private ImageButton refreshButton;
    private Button downloadButton, stopButton;
    private ProgressBar loading, jobProgress;
    private LinearLayout jobPanel;
    private TrackAdapter adapter;
    private Future<?> loadTask;
    private boolean loadingLibrary = false;
    private int loadGeneration = 0;
    private final Runnable applySearch = this::applyQuery;
    private final Runnable ticker = new Runnable() {
        @Override public void run() { refreshTransfer(); handler.postDelayed(this, 1000); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new Store(this); repository = new LibraryRepository(this);
        if (!store.isLoggedIn()) { finish(); return; }
        if (state != null) {
            ArrayList<String> restored = state.getStringArrayList("selected");
            if (restored != null) selected.addAll(restored);
        }
        buildUi();
        if (state != null) {
            search.setText(state.getString("query", ""));
            filter.setSelection(state.getInt("filter", 0));
            sort.setSelection(state.getInt("sort", 0));
        }
        loadLibrary(false);
    }

    @Override protected void onResume() {
        super.onResume();
        if (store != null && !store.isLoggedIn()) { finish(); return; }
        if (adapter != null) { handler.removeCallbacks(ticker); ticker.run(); }
    }
    @Override protected void onPause() { handler.removeCallbacks(ticker); super.onPause(); }
    @Override protected void onDestroy() {
        ++loadGeneration;
        handler.removeCallbacksAndMessages(null);
        if (loadTask != null) loadTask.cancel(true);
        executor.shutdownNow(); super.onDestroy();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putStringArrayList("selected", new ArrayList<>(selected));
        if (search != null) {
            state.putString("query", search.getText().toString());
            state.putInt("filter", filter.getSelectedItemPosition());
            state.putInt("sort", sort.getSelectedItemPosition());
        }
        super.onSaveInstanceState(state);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        setContentView(root); AppUi.prepareWindow(this, root);

        LinearLayout toolbar = new LinearLayout(this); toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(8), dp(8), dp(12), dp(4));
        ImageButton back = AppUi.iconButton(this, R.drawable.ic_arrow_back, "Назад");
        back.setOnClickListener(v -> finish()); toolbar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView heading = AppUi.text(this, "Медиатека", 24, Color.WHITE, true);
        toolbar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        refreshButton = AppUi.iconButton(this, R.drawable.ic_refresh, "Обновить медиатеку");
        refreshButton.setId(R.id.library_refresh); refreshButton.setOnClickListener(v -> loadLibrary(true));
        toolbar.addView(refreshButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(toolbar);

        LinearLayout tools = new LinearLayout(this); tools.setOrientation(LinearLayout.VERTICAL);
        tools.setPadding(dp(20), dp(4), dp(20), dp(8)); root.addView(tools);
        search = new EditText(this); search.setId(R.id.library_search);
        search.setHint("Название или исполнитель"); search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setTextSize(15); search.setTextColor(Color.WHITE); search.setHintTextColor(AppUi.MUTED);
        search.setPadding(dp(14), dp(10), dp(14), dp(10)); search.setMinimumHeight(dp(52));
        search.setBackground(AppUi.rounded(this, AppUi.CARD, 14));
        Drawable searchIcon = getDrawable(R.drawable.ic_search); searchIcon.setTint(AppUi.MUTED);
        search.setCompoundDrawablesWithIntrinsicBounds(searchIcon, null, null, null);
        search.setCompoundDrawablePadding(dp(10)); tools.addView(search, new LinearLayout.LayoutParams(-1, -2));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                handler.removeCallbacks(applySearch); handler.postDelayed(applySearch, 150);
            }
            @Override public void afterTextChanged(Editable editable) { }
        });

        LinearLayout menus = new LinearLayout(this); menus.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams menusParams = new LinearLayout.LayoutParams(-1, -2); menusParams.topMargin = dp(12);
        tools.addView(menus, menusParams);
        filter = spinner(new String[]{"Все треки", "Скачанные", "Не скачанные"}); filter.setId(R.id.library_filter);
        sort = spinner(new String[]{"Новые лайки", "Старые лайки", "Название А–Я", "Название Я–А", "Исполнитель А–Я", "Исполнитель Я–А"});
        sort.setId(R.id.library_sort);
        LinearLayout.LayoutParams filterParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        filterParams.rightMargin = dp(8); menus.addView(filter, filterParams);
        menus.addView(sort, new LinearLayout.LayoutParams(0, dp(48), 1.3f));
        filter.setContentDescription("Фильтр по скачиванию"); sort.setContentDescription("Сортировка треков");
        AdapterView.OnItemSelectedListener listener = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { applyQuery(); }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        };
        filter.setOnItemSelectedListener(listener); sort.setOnItemSelectedListener(listener);
        summary = AppUi.text(this, "Загружаю медиатеку…", 12, AppUi.MUTED, false);
        summary.setPadding(0, dp(10), 0, dp(2)); tools.addView(summary);
        loadStatus = AppUi.text(this, "", 12, AppUi.ACCENT, false);
        loadStatus.setVisibility(View.GONE); tools.addView(loadStatus);
        loading = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        loading.setIndeterminate(true); loading.setIndeterminateTintList(ColorStateList.valueOf(AppUi.ACCENT));
        loading.setVisibility(View.GONE); tools.addView(loading, new LinearLayout.LayoutParams(-1, dp(3)));

        LinearLayout selectionTools = new LinearLayout(this); selectionTools.setGravity(Gravity.CENTER_VERTICAL);
        selectAll = AppUi.link(this, "Выбрать все"); selectAll.setAlpha(1f); selectAll.setTextColor(AppUi.ACCENT);
        selectAll.setId(R.id.library_select_all); selectAll.setOnClickListener(v -> {
            for (LibraryTrack track : visible) if (!downloaded.contains(track.id)) selected.add(track.id);
            adapter.notifyDataSetChanged(); refreshSelection();
        });
        selectionTools.addView(selectAll, new LinearLayout.LayoutParams(0, dp(48), 1));
        clearSelection = AppUi.link(this, "Снять выбор"); clearSelection.setId(R.id.library_clear_selection);
        clearSelection.setOnClickListener(v -> { selected.clear(); adapter.notifyDataSetChanged(); refreshSelection(); });
        selectionTools.addView(clearSelection, new LinearLayout.LayoutParams(0, dp(48), 1));
        tools.addView(selectionTools);

        FrameLayout listArea = new FrameLayout(this);
        root.addView(listArea, new LinearLayout.LayoutParams(-1, 0, 1));
        ListView list = new ListView(this); list.setId(R.id.library_list);
        list.setDivider(new ColorDrawable(AppUi.BACKGROUND)); list.setDividerHeight(dp(8)); list.setClipToPadding(false);
        list.setPadding(dp(16), 0, dp(16), dp(8)); list.setChoiceMode(ListView.CHOICE_MODE_NONE);
        listArea.addView(list, new FrameLayout.LayoutParams(-1, -1));
        empty = AppUi.text(this, "Медиатека пока пуста", 15, AppUi.MUTED, false);
        empty.setGravity(Gravity.CENTER); empty.setPadding(dp(28), dp(24), dp(28), dp(24));
        listArea.addView(empty, new FrameLayout.LayoutParams(-1, -1)); list.setEmptyView(empty);
        adapter = new TrackAdapter(); list.setAdapter(adapter);

        LinearLayout footer = new LinearLayout(this); footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(dp(20), dp(12), dp(20), dp(14)); footer.setBackgroundColor(AppUi.CARD);
        selectionSummary = AppUi.text(this, "Выбери треки для загрузки", 12, AppUi.MUTED, false);
        footer.addView(selectionSummary);
        downloadButton = AppUi.button(this, "Скачать выбранные", true); downloadButton.setId(R.id.library_download_selected);
        downloadButton.setOnClickListener(v -> downloadSelected());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, -2); buttonParams.topMargin = dp(8);
        footer.addView(downloadButton, buttonParams);
        jobStatus = AppUi.text(this, "", 12, AppUi.MUTED, false); jobStatus.setMaxLines(3);
        jobStatus.setPadding(0, dp(8), 0, dp(4)); footer.addView(jobStatus);
        jobPanel = new LinearLayout(this); jobPanel.setOrientation(LinearLayout.VERTICAL);
        jobProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        jobProgress.setProgressTintList(ColorStateList.valueOf(AppUi.ACCENT));
        jobPanel.addView(jobProgress, new LinearLayout.LayoutParams(-1, dp(5)));
        stopButton = AppUi.button(this, "Остановить скачивание", false); stopButton.setId(R.id.download_stop);
        stopButton.setTextColor(AppUi.CORAL); stopButton.setOnClickListener(v -> { SyncService.stopDownloads(this); refreshTransfer(); });
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(-1, -2); stopParams.topMargin = dp(8);
        jobPanel.addView(stopButton, stopParams); footer.addView(jobPanel); root.addView(footer);
        refreshSelection();
    }

    private Spinner spinner(String[] labels) {
        Spinner view = new Spinner(this, Spinner.MODE_DROPDOWN);
        ArrayAdapter<String> values = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, labels) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView text = (TextView) super.getView(position, convertView, parent);
                text.setTextColor(Color.WHITE); text.setTextSize(13); text.setSingleLine(true);
                text.setEllipsize(TextUtils.TruncateAt.END); text.setPadding(dp(12), 0, dp(8), 0);
                text.setCompoundDrawablesWithIntrinsicBounds(null, null, getDrawable(R.drawable.ic_expand_more), null);
                text.setCompoundDrawablePadding(dp(4));
                return text;
            }
        };
        values.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        view.setAdapter(values); view.setBackground(AppUi.rounded(this, AppUi.CARD, 12));
        return view;
    }

    private void loadLibrary(boolean fetchFromYandex) {
        if (loadingLibrary) return;
        loadingLibrary = true; final int generation = ++loadGeneration;
        refreshButton.setEnabled(false); refreshButton.setAlpha(.4f); loading.setVisibility(View.VISIBLE);
        loadStatus.setText(fetchFromYandex ? "Обновляю названия и даты лайков…" : "Читаю сохранённую медиатеку…");
        loadStatus.setVisibility(View.VISIBLE);
        loadTask = executor.submit(() -> {
            try {
                if (fetchFromYandex) repository.refresh((loaded, total) -> postLoad(generation, () ->
                        loadStatus.setText("Загружаю медиатеку: " + loaded + " / " + total)));
                List<LibraryTrack> result = repository.load();
                boolean needsRefresh = !fetchFromYandex && System.currentTimeMillis() - repository.lastRefreshAt() > 15 * 60 * 1000L;
                postLoad(generation, () -> {
                    tracks = result; downloaded = store.ids("downloaded"); selected.removeAll(downloaded);
                    Set<String> known = new HashSet<>(); for (LibraryTrack track : tracks) known.add(track.id);
                    selected.retainAll(known); finishLoad(); applyQuery();
                    if (needsRefresh) loadLibrary(true);
                });
            } catch (Exception ex) {
                postLoad(generation, () -> {
                    finishLoad(); loadStatus.setText("Не удалось обновить медиатеку. " + ex.getMessage());
                    loadStatus.setVisibility(View.VISIBLE); applyQuery();
                });
            }
        });
    }

    private void postLoad(int generation, Runnable update) {
        runOnUiThread(() -> { if (generation == loadGeneration && !isDestroyed() && !isFinishing()) update.run(); });
    }
    private void finishLoad() {
        loadingLibrary = false; loadTask = null; loading.setVisibility(View.GONE); loadStatus.setVisibility(View.GONE);
        refreshButton.setEnabled(true); refreshButton.setAlpha(1f);
    }
    private void applyQuery() {
        if (adapter == null || filter == null || sort == null) return;
        visible = LibraryQuery.select(tracks, downloaded, search.getText().toString(),
                LibraryQuery.Filter.values()[Math.max(0, filter.getSelectedItemPosition())],
                LibraryQuery.Sort.values()[Math.max(0, sort.getSelectedItemPosition())]);
        int saved = 0; for (LibraryTrack track : tracks) if (downloaded.contains(track.id)) saved++;
        summary.setText("Показано " + visible.size() + " из " + tracks.size() + " · скачано " + saved);
        empty.setText(loadingLibrary ? "Загружаю медиатеку…" : tracks.isEmpty()
                ? "Здесь появятся понравившиеся треки.\nНажми обновление, чтобы загрузить список."
                : "Ничего не найдено.\nПопробуй другой запрос или фильтр.");
        adapter.notifyDataSetChanged(); refreshSelection();
    }
    private void refreshSelection() {
        if (downloadButton == null) return;
        boolean busy = DownloadCoordinator.snapshot().running;
        downloadButton.setEnabled(!selected.isEmpty() && !busy);
        downloadButton.setAlpha(downloadButton.isEnabled() ? 1f : .45f);
        downloadButton.setText(selected.isEmpty() ? "Скачать выбранные" : "Скачать выбранные · " + selected.size());
        selectionSummary.setText(selected.isEmpty() ? "Отметь треки для загрузки" : "Выбрано: " + selected.size() + " · выбор сохраняется при поиске");
        clearSelection.setEnabled(!selected.isEmpty()); clearSelection.setAlpha(selected.isEmpty() ? .3f : .68f);
        boolean canSelect = false; for (LibraryTrack track : visible) if (!downloaded.contains(track.id)) { canSelect = true; break; }
        selectAll.setEnabled(canSelect); selectAll.setAlpha(canSelect ? 1f : .3f);
    }
    private void refreshTransfer() {
        Set<String> current = store.ids("downloaded");
        if (!current.equals(downloaded)) { downloaded = current; selected.removeAll(downloaded); applyQuery(); }
        DownloadCoordinator.Snapshot state = DownloadCoordinator.snapshot();
        jobPanel.setVisibility(state.running ? View.VISIBLE : View.GONE);
        downloadButton.setVisibility(state.running ? View.GONE : View.VISIBLE);
        jobStatus.setText(!state.running ? store.status() : state.stopping ? "Останавливаю загрузку…"
                : state.done + " / " + state.total + " · " + state.currentTitle);
        jobProgress.setIndeterminate(state.total <= 0);
        jobProgress.setMax(Math.max(1, state.total)); jobProgress.setProgress(state.done);
        stopButton.setEnabled(!state.stopping); stopButton.setAlpha(state.stopping ? .4f : 1f);
        refreshSelection();
    }
    private void downloadSelected() {
        if (selected.isEmpty() || DownloadCoordinator.snapshot().running) return;
        ArrayList<String> ids = new ArrayList<>();
        // Use library order, independent of Set iteration and the current display filter.
        for (LibraryTrack track : tracks) if (selected.contains(track.id) && !downloaded.contains(track.id)) ids.add(track.id);
        if (ids.isEmpty()) return;
        new AlertDialog.Builder(this).setTitle("Скачать выбранные треки?")
                .setMessage("Будет сохранено до " + ids.size() + " MP3 в Music/" + store.folder() + "/. Загрузку можно остановить в любой момент.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Скачать", (dialog, which) -> {
                    try {
                        startForegroundService(new Intent(this, SyncService.class).setAction(SyncService.ACTION_SELECTED)
                                .putStringArrayListExtra(SyncService.EXTRA_TRACK_IDS, ids));
                        store.status("Выбранные треки переданы на загрузку…"); handler.postDelayed(this::refreshTransfer, 150);
                    } catch (Exception ex) { loadStatus.setText("Не удалось начать загрузку: " + ex.getMessage()); loadStatus.setVisibility(View.VISIBLE); }
                }).show();
    }

    private final class TrackAdapter extends BaseAdapter {
        private final DateFormat dateFormat = DateFormat.getDateInstance(DateFormat.SHORT);
        @Override public int getCount() { return visible.size(); }
        @Override public LibraryTrack getItem(int position) { return visible.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            Row row;
            if (recycled == null) {
                row = new Row(); recycled = row.root; recycled.setTag(row);
            } else row = (Row) recycled.getTag();
            LibraryTrack track = getItem(position); boolean saved = downloaded.contains(track.id);
            row.title.setText(track.title); row.artist.setText(track.artist);
            String date = track.likedAt == 0 ? "Дата лайка неизвестна" : "Лайк " + dateFormat.format(new Date(track.likedAt));
            row.meta.setText((saved ? "✓ Скачано  ·  " : "") + date);
            row.meta.setTextColor(saved ? 0xFF8FD9BA : AppUi.MUTED);
            row.check.setOnCheckedChangeListener(null); row.check.setChecked(saved || selected.contains(track.id));
            row.check.setEnabled(!saved); row.check.setAlpha(saved ? .45f : 1f);
            row.check.setContentDescription((saved ? "Скачано: " : "Выбрать: ") + track.title + ", " + track.artist);
            row.check.setOnCheckedChangeListener((button, checked) -> {
                if (checked) selected.add(track.id); else selected.remove(track.id);
                notifyDataSetChanged(); refreshSelection();
            });
            row.root.setBackground(AppUi.ripple(LibraryActivity.this,
                    selected.contains(track.id) && !saved ? 0xFF302D27 : AppUi.CARD, 16));
            row.root.setOnClickListener(v -> {
                if (saved) return;
                if (!selected.add(track.id)) selected.remove(track.id);
                notifyDataSetChanged(); refreshSelection();
            });
            return recycled;
        }
    }
    private final class Row {
        final LinearLayout root;
        final CheckBox check;
        final TextView title, artist, meta;
        Row() {
            root = new LinearLayout(LibraryActivity.this); root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(dp(6), dp(10), dp(14), dp(10));
            root.setMinimumHeight(dp(100));
            check = new CheckBox(LibraryActivity.this); check.setButtonTintList(ColorStateList.valueOf(AppUi.ACCENT));
            root.addView(check, new LinearLayout.LayoutParams(dp(48), dp(48)));
            LinearLayout labels = new LinearLayout(LibraryActivity.this); labels.setOrientation(LinearLayout.VERTICAL);
            title = AppUi.text(LibraryActivity.this, "", 15, Color.WHITE, true); title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END);
            artist = AppUi.text(LibraryActivity.this, "", 13, AppUi.MUTED, false); artist.setMaxLines(1); artist.setEllipsize(TextUtils.TruncateAt.END);
            meta = AppUi.text(LibraryActivity.this, "", 11, AppUi.MUTED, false); meta.setPadding(0, dp(6), 0, 0);
            labels.addView(title); labels.addView(artist); labels.addView(meta);
            root.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            root.setLayoutParams(new android.widget.AbsListView.LayoutParams(-1, -2));
        }
    }
    private int dp(int value) { return AppUi.dp(this, value); }
}
