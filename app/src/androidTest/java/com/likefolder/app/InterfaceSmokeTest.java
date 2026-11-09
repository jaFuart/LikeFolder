package com.likefolder.app;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.WorkManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import static org.junit.Assert.*;

/** Real Android view checks using synthetic local data; never starts an API request. */
@RunWith(AndroidJUnit4.class)
public final class InterfaceSmokeTest {
    private Context context;
    private Instrumentation instrumentation;

    @Before public void prepareLocalState() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        WorkManager.getInstance(context).cancelAllWork().getResult().get(5, TimeUnit.SECONDS);
        new Store(context).signOut();
        new LibraryRepository(context).clear();
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.getUiAutomation().grantRuntimePermission(
                    context.getPackageName(), Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    @After public void clearSyntheticAccount() throws Exception {
        WorkManager.getInstance(context).cancelAllWork().getResult().get(5, TimeUnit.SECONDS);
        new Store(context).signOut();
        new LibraryRepository(context).clear();
    }

    @Test public void launcherEntryHasAdaptiveIcon() throws Exception {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        assertNotNull("The installed app must have a launcher entry", launch);
        assertEquals(MainActivity.class.getName(), launch.getComponent().getClassName());
        assertTrue(context.getPackageManager().getApplicationIcon(context.getPackageName())
                instanceof AdaptiveIconDrawable);
    }

    @Test public void receivedCodeCanBeCopiedFromCodeAndIcon() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                assertEquals("Получить код входа", ((Button) activity.findViewById(R.id.login_retry)).getText().toString());
                assertEquals(View.GONE, activity.findViewById(R.id.open_authorization).getVisibility());
                hideKeyboard(activity);
            });
            capture(scenario, "01-login-start");
            scenario.onActivity(activity -> {
                activity.showLoginCode("AB12CD34", "https://oauth.yandex.ru/device");
                assertEquals("Повторно получить код", ((Button) activity.findViewById(R.id.login_retry)).getText().toString());
                assertTrue(activity.findViewById(R.id.login_retry).isEnabled());
                assertEquals("AB12CD34", ((TextView) activity.findViewById(R.id.login_code)).getText().toString());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.open_authorization).getVisibility());
                assertTrue(activity.findViewById(R.id.login_copy).isShown());
                hideKeyboard(activity);
            });
            capture(scenario, "02-login-code");
            scenario.onActivity(activity -> {
                ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                try {
                    activity.findViewById(R.id.login_code).performClick();
                    assertEquals("AB12CD34", clipboard.getPrimaryClip().getItemAt(0).getText().toString());

                    clipboard.setPrimaryClip(ClipData.newPlainText("test", "reset"));
                    activity.findViewById(R.id.login_copy).performClick();
                    assertEquals("AB12CD34", clipboard.getPrimaryClip().getItemAt(0).getText().toString());
                } finally { clipboard.clearPrimaryClip(); }
            });
            // SystemUI may retain its clipboard preview after clearPrimaryClip(). Let it expire
            // on this screen so it cannot cover the library screenshots in the next test.
            SystemClock.sleep(7000);
        }
    }

    @Test public void cachedLibrarySupportsSearchFilterSortAndPersistentSelection() throws Exception {
        seedLibrary();
        try (ActivityScenario<LibraryActivity> scenario = ActivityScenario.launch(LibraryActivity.class)) {
            await(scenario, activity -> rows(activity).size() == 6, "Six cached tracks should be visible");
            scenario.onActivity(activity -> {
                assertEquals("1004", rows(activity).get(0));
                hideKeyboard(activity);
            });
            capture(scenario, "03-library");

            scenario.onActivity(activity -> ((Spinner) activity.findViewById(R.id.library_sort)).setSelection(2));
            await(scenario, activity -> !rows(activity).isEmpty() && "1001".equals(rows(activity).get(0)),
                    "Sorting by title should put Вечерний свет first");
            await(scenario, activity -> firstTrackCheckBox(activity, "Вечерний свет") != null,
                    "The first visible row should be laid out with the sorted track");
            scenario.onActivity(activity -> {
                CheckBox check = firstTrackCheckBox(activity, "Вечерний свет");
                assertNotNull("Each unsaved track has a selection checkbox", check);
                assertFalse(check.isChecked());
                check.performClick();
                assertTrue(check.isChecked());
                assertTrue(activity.findViewById(R.id.library_download_selected).isEnabled());
            });
            capture(scenario, "04-library-selected");

            scenario.onActivity(activity -> ((Spinner) activity.findViewById(R.id.library_filter)).setSelection(1));
            await(scenario, activity -> new HashSet<>(rows(activity)).equals(new HashSet<>(Arrays.asList("1002", "1005"))),
                    "Downloaded filter should show only the two downloaded tracks");
            scenario.onActivity(activity -> assertTrue("Selection should survive when filtered out",
                    activity.findViewById(R.id.library_download_selected).isEnabled()));
            capture(scenario, "05-library-downloaded");

            scenario.onActivity(activity -> ((Spinner) activity.findViewById(R.id.library_filter)).setSelection(0));
            await(scenario, activity -> rows(activity).size() == 6, "Removing filter should restore all rows");
            await(scenario, activity -> {
                CheckBox check = firstTrackCheckBox(activity, "Вечерний свет");
                return check != null && check.isChecked();
            }, "Previously selected track must remain checked");

            scenario.onActivity(activity -> ((EditText) activity.findViewById(R.id.library_search)).setText("лунный"));
            await(scenario, activity -> new HashSet<>(rows(activity)).equals(new HashSet<>(Arrays.asList("1001", "1004"))),
                    "Artist search should be case-insensitive");
            scenario.onActivity(InterfaceSmokeTest::hideKeyboard);
            capture(scenario, "06-library-search");

            scenario.onActivity(activity -> activity.findViewById(R.id.library_clear_selection).performClick());
            scenario.onActivity(activity -> assertFalse(activity.findViewById(R.id.library_download_selected).isEnabled()));
            scenario.onActivity(activity -> activity.findViewById(R.id.library_select_all).performClick());
            scenario.onActivity(activity -> {
                Button download = activity.findViewById(R.id.library_download_selected);
                assertTrue(download.isEnabled());
                assertEquals("Скачать выбранные · 2", download.getText().toString());
            });
            // Do not click download or refresh: this fixture has no real credentials.
        }
    }

    private void seedLibrary() {
        Store store = new Store(context);
        store.saveTokens("synthetic-instrumentation-token-not-valid", "");
        store.status("Медиатека готова к выбору треков");
        store.likedCount(6);
        store.setIds("downloaded", new HashSet<>(Arrays.asList("1002", "1005")));
        new LibraryRepository(context).remember(Arrays.asList(
                new YandexApi.Track("1004", "Ночные огни", "Лунный берег", "Город"),
                new YandexApi.Track("1002", "Тихий океан", "Море внутри", "Волны"),
                new YandexApi.Track("1006", "Сквозь облака", "Полюса", "Небо"),
                new YandexApi.Track("1001", "Вечерний свет", "Лунный берег", "Город"),
                new YandexApi.Track("1005", "Тёплый дождь", "Марина", "Лето"),
                new YandexApi.Track("1003", "На заре", "Север", "Дом")));
        // This synthetic fixture represents a complete snapshot and must never trigger API refresh.
        try (SQLiteDatabase db = context.openOrCreateDatabase("library.db", Context.MODE_PRIVATE, null)) {
            db.execSQL("INSERT OR REPLACE INTO metadata (key, value) VALUES (?, ?)",
                    new Object[]{"refreshed_at", System.currentTimeMillis()});
        }
    }

    private static List<String> rows(Activity activity) {
        ListView list = activity.findViewById(R.id.library_list);
        List<String> ids = new ArrayList<>();
        ListAdapter adapter = list.getAdapter();
        if (adapter != null) {
            for (int i = 0; i < adapter.getCount(); i++) ids.add(((LibraryTrack) adapter.getItem(i)).id);
        }
        return ids;
    }

    private static CheckBox firstTrackCheckBox(Activity activity, String title) {
        ListView list = activity.findViewById(R.id.library_list);
        if (list.getFirstVisiblePosition() != 0 || list.getChildCount() == 0) return null;
        CheckBox check = findCheckBox(list.getChildAt(0));
        if (check == null || check.getContentDescription() == null) return null;
        return check.getContentDescription().toString().contains(title) ? check : null;
    }

    private static CheckBox findCheckBox(View view) {
        if (view instanceof CheckBox) return (CheckBox) view;
        if (view instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) view;
            for (int i = 0; i < parent.getChildCount(); i++) {
                CheckBox result = findCheckBox(parent.getChildAt(i));
                if (result != null) return result;
            }
        }
        return null;
    }

    private <A extends Activity> void await(ActivityScenario<A> scenario, Predicate<A> condition,
            String message) {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        do {
            AtomicBoolean ready = new AtomicBoolean();
            scenario.onActivity(activity -> ready.set(condition.test(activity)));
            if (ready.get()) return;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail(message);
    }

    private static void hideKeyboard(Activity activity) {
        InputMethodManager manager = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        manager.hideSoftInputFromWindow(activity.getWindow().getDecorView().getWindowToken(), 0);
        View focus = activity.getCurrentFocus();
        if (focus != null) focus.clearFocus();
    }

    private <A extends Activity> void capture(ActivityScenario<A> scenario, String name) throws Exception {
        await(scenario, activity -> {
            View decor = activity.getWindow().getDecorView();
            return decor.hasWindowFocus() && decor.isShown() && decor.getWidth() > 0 && decor.getHeight() > 0;
        }, "The screenshot window must be visible and focused");
        instrumentation.waitForIdleSync();
        CountDownLatch frameCommitted = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            View decor = activity.getWindow().getDecorView();
            assertTrue("Screenshot checks require hardware rendering", decor.isHardwareAccelerated());
            decor.getViewTreeObserver().registerFrameCommitCallback(frameCommitted::countDown);
            decor.invalidate();
        });
        assertTrue("The updated UI must submit a rendered frame before capture",
                frameCommitted.await(10, TimeUnit.SECONDS));
        // Frame commit precedes display presentation. Allow compositor and window transitions to settle.
        SystemClock.sleep(1000);
        instrumentation.getUiAutomation().waitForIdle(300, 5000);
        Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull("Android should provide a screenshot", screenshot);
        File directory = new File(context.getExternalFilesDir(null), "ui-previews");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File screenshotFile = new File(directory, name + ".png");
        try (FileOutputStream output = new FileOutputStream(screenshotFile)) {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { screenshot.recycle(); }

        // connectedDebugAndroidTest can uninstall the app, removing its external files.
        // Shell-owned copies in Downloads survive that cleanup; all paths here are test constants.
        String exportDirectory = "/sdcard/Download/LikeFolder-ui-previews";
        String exportFile = exportDirectory + "/" + name + ".png";
        shellOutput("mkdir -p " + exportDirectory);
        shellOutput("cp " + screenshotFile.getAbsolutePath() + " " + exportFile);
        assertEquals("The exported screenshot must survive app uninstall",
                Long.toString(screenshotFile.length()), shellOutput("stat -c %s " + exportFile).trim());
    }

    private String shellOutput(String command) throws Exception {
        ParcelFileDescriptor descriptor = instrumentation.getUiAutomation().executeShellCommand(command);
        try (ParcelFileDescriptor.AutoCloseInputStream input =
                     new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            // Reading to EOF waits for each command to finish before the test can be torn down.
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
