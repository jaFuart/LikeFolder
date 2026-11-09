package com.likefolder.app;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import static org.junit.Assert.assertEquals;

public final class DownloadSelectionTest {
    @Test public void explicitSelectionDoesNotExpandAndSkipsSavedDuplicates() {
        assertEquals(Arrays.asList("7", "3:9"), DownloadSelection.pending(
                Arrays.asList("7", "2", "3:9", "7"),
                new HashSet<>(Arrays.asList("2", "99")),
                new HashSet<>(Arrays.asList("7", "3:9")), false));
    }

    @Test public void automaticSyncOnlySelectsUnseenUnsavedTracks() {
        assertEquals(Collections.singletonList("4"), DownloadSelection.pending(
                Arrays.asList("1", "2", "3", "4"),
                new HashSet<>(Arrays.asList("1", "3")),
                new HashSet<>(Arrays.asList("1", "2")), true));
    }

    @Test public void invalidIntentIdsCannotBecomeApiPaths() {
        assertEquals(Arrays.asList("3", "12:34"), DownloadSelection.pending(
                Arrays.asList(null, "", "../account", "12?token=x", "12:34:56", "3", "12:34"),
                Collections.emptySet(), Collections.emptySet(), false));
    }
}
