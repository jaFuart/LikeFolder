package com.likefolder.app;

/** A cached library row. A zero likedAt means the service did not supply a date. */
final class LibraryTrack {
    final String id, title, artist, album;
    final long likedAt;
    final int likeOrder;

    LibraryTrack(String id, String title, String artist, String album, long likedAt, int likeOrder) {
        this.id = id;
        this.title = title == null || title.trim().isEmpty() ? "Трек " + id : title;
        this.artist = artist == null || artist.trim().isEmpty() || "Unknown artist".equals(artist)
                ? "Неизвестный исполнитель" : artist;
        this.album = album == null ? "" : album;
        this.likedAt = Math.max(0, likedAt);
        this.likeOrder = likeOrder;
    }
}
