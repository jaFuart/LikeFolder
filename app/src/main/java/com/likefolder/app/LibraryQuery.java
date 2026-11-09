package com.likefolder.app;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Search and presentation order, independent of Android and the cached source list. */
final class LibraryQuery {
    enum Filter { ALL, DOWNLOADED, NOT_DOWNLOADED }
    enum Sort { LIKED_NEWEST, LIKED_OLDEST, TITLE_ASC, TITLE_DESC, ARTIST_ASC, ARTIST_DESC }

    static List<LibraryTrack> select(List<LibraryTrack> tracks, Set<String> downloaded,
            String query, Filter filter, Sort sort) {
        String needle = normalize(query).trim();
        List<LibraryTrack> selected = new ArrayList<>();
        for (LibraryTrack track : tracks) {
            boolean saved = downloaded.contains(track.id);
            if (filter == Filter.DOWNLOADED && !saved) continue;
            if (filter == Filter.NOT_DOWNLOADED && saved) continue;
            if (!needle.isEmpty() && !normalize(track.title + " " + track.artist).contains(needle)) continue;
            selected.add(track);
        }

        Collator collator = Collator.getInstance(new Locale("ru"));
        collator.setStrength(Collator.PRIMARY);
        Comparator<LibraryTrack> byTitle = (a, b) -> collator.compare(a.title, b.title);
        Comparator<LibraryTrack> byArtist = (a, b) -> collator.compare(a.artist, b.artist);
        Comparator<LibraryTrack> order;
        switch (sort) {
            case TITLE_ASC: order = byTitle; break;
            case TITLE_DESC: order = byTitle.reversed(); break;
            case ARTIST_ASC: order = byArtist.thenComparing(byTitle); break;
            case ARTIST_DESC: order = byArtist.reversed().thenComparing(byTitle); break;
            case LIKED_OLDEST: order = (a, b) -> compareDate(a, b, false); break;
            case LIKED_NEWEST:
            default: order = (a, b) -> compareDate(a, b, true); break;
        }
        selected.sort(order.thenComparingInt(track -> track.likeOrder).thenComparing(track -> track.id));
        return selected;
    }

    private static int compareDate(LibraryTrack a, LibraryTrack b, boolean newest) {
        // Never present a missing date as 1970 or let it jump ahead of known dates.
        if (a.likedAt == 0 || b.likedAt == 0) {
            if (a.likedAt != b.likedAt) return a.likedAt == 0 ? 1 : -1;
            return newest ? Integer.compare(a.likeOrder, b.likeOrder) : Integer.compare(b.likeOrder, a.likeOrder);
        }
        return newest ? Long.compare(b.likedAt, a.likedAt) : Long.compare(a.likedAt, b.likedAt);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    private LibraryQuery() { }
}
