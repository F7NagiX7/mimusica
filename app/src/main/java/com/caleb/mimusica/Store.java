package com.caleb.mimusica;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** Guarda favoritas, reproducidas recientemente y cuántas veces suena cada artista. */
final class Store {
    private Store() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("mimusica", Context.MODE_PRIVATE);
    }

    static Set<String> favs(Context c) {
        return new HashSet<>(prefs(c).getStringSet("favs", new HashSet<String>()));
    }

    static boolean isFav(Context c, long id) {
        return favs(c).contains(String.valueOf(id));
    }

    /** Devuelve true si quedó como favorita. */
    static boolean toggleFav(Context c, long id) {
        Set<String> s = favs(c);
        String k = String.valueOf(id);
        boolean now = !s.remove(k);
        if (now) s.add(k);
        prefs(c).edit().putStringSet("favs", s).apply();
        return now;
    }

    static ArrayList<Long> recents(Context c) {
        ArrayList<Long> r = new ArrayList<>();
        String s = prefs(c).getString("recents", "");
        if (s.isEmpty()) return r;
        for (String p : s.split(",")) {
            try {
                r.add(Long.parseLong(p));
            } catch (Exception ignored) { }
        }
        return r;
    }

    static void played(Context c, Track t) {
        ArrayList<Long> r = recents(c);
        r.remove(Long.valueOf(t.id));
        r.add(0, t.id);
        while (r.size() > 20) r.remove(r.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (Long l : r) {
            if (sb.length() > 0) sb.append(',');
            sb.append(l);
        }
        SharedPreferences p = prefs(c);
        p.edit()
                .putString("recents", sb.toString())
                .putInt("a:" + t.artist, p.getInt("a:" + t.artist, 0) + 1)
                .apply();
    }

    static int plays(Context c, String artist) {
        return prefs(c).getInt("a:" + artist, 0);
    }
}
