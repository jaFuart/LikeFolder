package com.likefolder.app;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;
import static org.junit.Assert.*;

public final class LibraryQueryTest {
    private static LibraryTrack track(String id, String title, String artist, long date, int order) {
        return new LibraryTrack(id, title, artist, "", date, order);
    }

    private static List<String> ids(List<LibraryTrack> tracks) {
        return tracks.stream().map(track -> track.id).collect(Collectors.toList());
    }

    @Test public void searchFindsTitleAndArtistIgnoringCaseAndYo() {
        List<LibraryTrack> tracks = Arrays.asList(
                track("1", "Всё зависит от нас", "Ёлка", 1, 0),
                track("2", "Другой трек", "Другой исполнитель", 2, 1));
        assertEquals(Arrays.asList("1"), ids(LibraryQuery.select(tracks, Collections.emptySet(),
                "  ЕЛКА  ", LibraryQuery.Filter.ALL, LibraryQuery.Sort.LIKED_NEWEST)));
        assertEquals(Arrays.asList("1"), ids(LibraryQuery.select(tracks, Collections.emptySet(),
                "ВСЕ ЗАВИСИТ", LibraryQuery.Filter.ALL, LibraryQuery.Sort.TITLE_ASC)));
        assertTrue(LibraryQuery.select(tracks, Collections.emptySet(), "несуществующий",
                LibraryQuery.Filter.ALL, LibraryQuery.Sort.TITLE_ASC).isEmpty());
    }

    @Test public void downloadedFiltersUseExistingDownloadedIds() {
        List<LibraryTrack> tracks = Arrays.asList(track("7:8", "A", "B", 1, 0),
                track("9", "C", "D", 2, 1));
        Set<String> downloaded = new HashSet<>(Arrays.asList("7:8", "not-in-cache"));
        assertEquals(Arrays.asList("7:8"), ids(LibraryQuery.select(tracks, downloaded, "",
                LibraryQuery.Filter.DOWNLOADED, LibraryQuery.Sort.LIKED_NEWEST)));
        assertEquals(Arrays.asList("9"), ids(LibraryQuery.select(tracks, downloaded, "",
                LibraryQuery.Filter.NOT_DOWNLOADED, LibraryQuery.Sort.LIKED_NEWEST)));
        assertTrue(LibraryQuery.select(tracks, downloaded, "C", LibraryQuery.Filter.DOWNLOADED,
                LibraryQuery.Sort.TITLE_ASC).isEmpty());
    }

    @Test public void likedDateSortKeepsUnknownDatesLastAndFallsBackToServiceOrder() {
        List<LibraryTrack> tracks = Arrays.asList(
                track("missing-old", "M", "A", 0, 10),
                track("old", "O", "A", 1000, 5),
                track("missing-new", "M", "A", 0, 1),
                track("new", "N", "A", 2000, 0));
        assertEquals(Arrays.asList("new", "old", "missing-new", "missing-old"),
                ids(LibraryQuery.select(tracks, Collections.emptySet(), "", LibraryQuery.Filter.ALL,
                        LibraryQuery.Sort.LIKED_NEWEST)));
        assertEquals(Arrays.asList("old", "new", "missing-old", "missing-new"),
                ids(LibraryQuery.select(tracks, Collections.emptySet(), "", LibraryQuery.Filter.ALL,
                        LibraryQuery.Sort.LIKED_OLDEST)));
    }

    @Test public void titleAndArtistSortWorkInBothDirectionsWithoutChangingCacheOrder() {
        List<LibraryTrack> tracks = Arrays.asList(
                track("b", "Бета", "Ария", 10, 0),
                track("a", "Альфа", "Сплин", 20, 1),
                track("v", "Ветер", "Би-2", 30, 2));
        assertEquals(Arrays.asList("a", "b", "v"), ids(LibraryQuery.select(tracks, Collections.emptySet(),
                "", LibraryQuery.Filter.ALL, LibraryQuery.Sort.TITLE_ASC)));
        assertEquals(Arrays.asList("v", "b", "a"), ids(LibraryQuery.select(tracks, Collections.emptySet(),
                "", LibraryQuery.Filter.ALL, LibraryQuery.Sort.TITLE_DESC)));
        assertEquals(Arrays.asList("b", "v", "a"), ids(LibraryQuery.select(tracks, Collections.emptySet(),
                "", LibraryQuery.Filter.ALL, LibraryQuery.Sort.ARTIST_ASC)));
        assertEquals(Arrays.asList("a", "v", "b"), ids(LibraryQuery.select(tracks, Collections.emptySet(),
                "", LibraryQuery.Filter.ALL, LibraryQuery.Sort.ARTIST_DESC)));
        assertEquals(Arrays.asList("b", "a", "v"), ids(tracks));
    }

    @Test public void equalSortKeysHaveStableTieOrder() {
        List<LibraryTrack> tracks = Arrays.asList(track("b", "Песня", "Артист", 10, 3),
                track("c", "песня", "артист", 10, 1), track("a", "Песня", "Артист", 10, 3));
        assertEquals(Arrays.asList("c", "a", "b"), ids(LibraryQuery.select(tracks, Collections.emptySet(),
                "", LibraryQuery.Filter.ALL, LibraryQuery.Sort.TITLE_ASC)));
    }

    @Test public void missingMetadataRemainsVisibleAndSearchableById() {
        LibraryTrack missing = track("12345", null, "", 0, 0);
        assertEquals("Трек 12345", missing.title);
        assertEquals("Неизвестный исполнитель", missing.artist);
        assertEquals(Arrays.asList("12345"), ids(LibraryQuery.select(Arrays.asList(missing),
                Collections.emptySet(), "12345", LibraryQuery.Filter.ALL, LibraryQuery.Sort.TITLE_ASC)));
    }
}
