package com.caleb.mimusica;

import android.content.ContentUris;
import android.net.Uri;
import android.provider.MediaStore;

final class Track {
    final long id;
    final long albumId;
    final long duration;
    final int trackNo;
    final String title;
    final String artist;
    final String album;

    Track(long id, String title, String artist, String album, long albumId, long duration, int trackNo) {
        this.id = id;
        this.title = (title == null || title.isEmpty()) ? "Sin título" : title;
        this.artist = artist == null ? "Artista desconocido" : artist;
        this.album = album == null ? "" : album;
        this.albumId = albumId;
        this.duration = duration;
        this.trackNo = trackNo;
    }

    Uri uri() {
        return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
    }
}
