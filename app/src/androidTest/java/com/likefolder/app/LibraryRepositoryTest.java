package com.likefolder.app;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.WorkManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Real SQLite checkpoints with a synthetic API; no credentials or network are needed. */
@RunWith(AndroidJUnit4.class)
public final class LibraryRepositoryTest {
    private Context context;
    private Store store;

    @Before public void prepareCache() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        WorkManager.getInstance(context).cancelAllWork().getResult().get(5, TimeUnit.SECONDS);
        store = new Store(context);
        store.signOut();
    }

    @After public void clearCache() {
        if (store != null) store.signOut();
    }

    @Test public void failedSecondBatchKeepsFirstBatchAndRetryFetchesOnlyMissingTracks() throws Exception {
        FakeSource source = new FakeSource(likes(0));
        LibraryRepository repository = new LibraryRepository(context, source);
        repository.refresh(null);
        assertTrue(repository.lastRefreshAt() > 0);

        source.likes = likes(70);
        source.failBatch = 2;
        Set<String> seenBefore = Collections.singleton("999");
        store.setIds("seen", seenBefore);
        List<Integer> progress = new ArrayList<>();
        try {
            repository.refresh((loaded, total) -> {
                assertEquals(70, total);
                assertEquals("Progress must describe rows already persisted", loaded, repository.load().size());
                progress.add(loaded);
            });
            fail("The second metadata batch should fail");
        } catch (IOException expected) {
            assertEquals("synthetic HTTP 429", expected.getMessage());
        }
        assertEquals(Arrays.asList(0, 35), progress);
        assertEquals(35, repository.load().size());
        assertEquals(0, repository.lastRefreshAt());
        assertEquals(70, store.likedCount());
        assertFalse("Reading the library must not create a download baseline", store.hasBaseline());
        assertEquals(seenBefore, store.ids("seen"));
        LibraryTrack saved = repository.load().get(0);
        assertEquals(1000L, saved.likedAt);
        assertEquals(0, saved.likeOrder);

        source.failBatch = 0;
        source.batches.clear();
        progress.clear();
        repository.refresh((loaded, total) -> progress.add(loaded));
        assertEquals(Arrays.asList(35, 70), progress);
        assertEquals(1, source.batches.size());
        List<String> remaining = new ArrayList<>();
        for (int id = 36; id <= 70; id++) remaining.add(String.valueOf(id));
        assertEquals(remaining, source.batches.get(0));
        assertEquals(70, repository.load().size());
        assertTrue(repository.lastRefreshAt() > 0);
        assertEquals(seenBefore, store.ids("seen"));
        assertFalse(store.hasBaseline());
    }

    @Test public void warmRefreshUpdatesDatesAndOrderWithoutRefetchingMetadata() throws Exception {
        FakeSource source = new FakeSource(likes(2));
        LibraryRepository repository = new LibraryRepository(context, source);
        repository.refresh(null);
        source.batches.clear();
        source.likes = Arrays.asList(new YandexApi.Like("2", 9000, 0), new YandexApi.Like("1", 8000, 1));
        repository.refresh((loaded, total) -> {
            assertEquals(2, loaded);
            List<LibraryTrack> checkpoint = repository.load();
            assertEquals("2", checkpoint.get(0).id);
            assertEquals(9000L, checkpoint.get(0).likedAt);
        });
        assertTrue("Cached metadata should require no track requests", source.batches.isEmpty());
        List<LibraryTrack> rows = repository.load();
        assertEquals("2", rows.get(0).id);
        assertEquals(9000L, rows.get(0).likedAt);
        assertEquals(0, rows.get(0).likeOrder);
        assertEquals("1", rows.get(1).id);
        assertEquals(8000L, rows.get(1).likedAt);
        assertEquals(1, rows.get(1).likeOrder);

        // A later refresh failing on a new track must also preserve the previous good snapshot.
        source.likes = Arrays.asList(new YandexApi.Like("1", 10000, 0), new YandexApi.Like("3", 9500, 1));
        source.failBatch = 1;
        try {
            repository.refresh(null);
            fail("The new track request should fail");
        } catch (IOException expected) {
            assertEquals("synthetic HTTP 429", expected.getMessage());
        }
        assertEquals(new HashSet<>(Arrays.asList("1", "2")), ids(repository.load()));
        assertEquals(10000L, find(repository.load(), "1").likedAt);
        assertEquals(0, repository.lastRefreshAt());
    }

    @Test public void logoutDuringMetadataFetchCannotRestoreOldAccountCache() throws Exception {
        FakeSource source = new FakeSource(likes(35));
        LibraryRepository repository = new LibraryRepository(context, source);
        repository.refresh(null);
        assertEquals(35, repository.load().size());
        source.likes = likes(70);
        source.onTracks = store::signOut;
        try {
            repository.refresh(null);
            fail("The old account refresh must be invalidated by logout");
        } catch (InterruptedIOException expected) {
            // Models a logout completing while the old HTTP response is still in flight.
        }
        LibraryRepository afterLogout = new LibraryRepository(context);
        assertTrue(afterLogout.load().isEmpty());
        assertEquals(0, afterLogout.lastRefreshAt());
        assertEquals(0, store.likedCount());
        assertFalse(store.isLoggedIn());
    }

    @Test public void completedRefreshRetainsUnlikedDownloadedTracksOnly() throws Exception {
        FakeSource source = new FakeSource(likes(3));
        LibraryRepository repository = new LibraryRepository(context, source);
        repository.refresh(null);
        store.setIds("downloaded", Collections.singleton("2"));
        source.likes = Collections.singletonList(new YandexApi.Like("1", 12000, 0));
        source.batches.clear();
        repository.refresh(null);

        assertEquals(new HashSet<>(Arrays.asList("1", "2")), ids(repository.load()));
        assertEquals("Title 2", find(repository.load(), "2").title);
        assertTrue(source.batches.isEmpty());
        assertEquals(1, store.likedCount());
        assertEquals(Collections.singleton("2"), store.ids("downloaded"));
        assertTrue(repository.lastRefreshAt() > 0);
    }

    @Test public void placeholderTitleStillNeedsMetadataEvenWithAnAlbumSuffix() throws Exception {
        FakeSource source = new FakeSource(Arrays.asList(
                new YandexApi.Like("1:99", 1000, 0), new YandexApi.Like("2", 2000, 1)));
        LibraryRepository repository = new LibraryRepository(context, source);
        repository.remember(Arrays.asList(new YandexApi.Track("1:99", "Track 1", "", ""),
                new YandexApi.Track("2", "Title 2", "Artist 2", "Album")));
        repository.refresh(null);

        assertEquals(1, source.batches.size());
        assertEquals(Collections.singletonList("1:99"), source.batches.get(0));
        assertEquals("Title 1:99", find(repository.load(), "1:99").title);
        assertEquals(1000L, find(repository.load(), "1:99").likedAt);
    }

    private static List<YandexApi.Like> likes(int count) {
        List<YandexApi.Like> result = new ArrayList<>();
        for (int id = 1; id <= count; id++) result.add(new YandexApi.Like(String.valueOf(id), id * 1000L, id - 1));
        return result;
    }

    private static Set<String> ids(List<LibraryTrack> rows) {
        Set<String> result = new HashSet<>();
        for (LibraryTrack row : rows) result.add(row.id);
        return result;
    }

    private static LibraryTrack find(List<LibraryTrack> rows, String id) {
        for (LibraryTrack row : rows) if (row.id.equals(id)) return row;
        throw new AssertionError("Missing cached track " + id);
    }

    private static final class FakeSource implements LibraryRepository.Source {
        List<YandexApi.Like> likes;
        final List<List<String>> batches = new ArrayList<>();
        int failBatch;
        Runnable onTracks;

        FakeSource(List<YandexApi.Like> likes) { this.likes = likes; }

        @Override public List<YandexApi.Like> likedEntries() { return new ArrayList<>(likes); }

        @Override public List<YandexApi.Track> tracks(List<String> ids) throws IOException {
            batches.add(new ArrayList<>(ids));
            if (batches.size() == failBatch) throw new IOException("synthetic HTTP 429");
            if (onTracks != null) onTracks.run();
            List<YandexApi.Track> result = new ArrayList<>();
            for (String id : ids) result.add(new YandexApi.Track(id, "Title " + id, "Artist " + id, "Album"));
            return result;
        }
    }
}
