package com.caleb.mimusica;

import android.content.ContentUris;
import android.net.Uri;
import android.provider.MediaStore;

final class Track {
    final long id;
    final String title;
    final String artist;
    final long duration;

    Track(long id, String title, String artist, long duration) {
        this.id = id;
        this.title = (title == null || title.isEmpty()) ? "Sin título" : title;
        this.artist = artist;
        this.duration = duration;
    }

    Uri uri() {
        return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
    }
}
