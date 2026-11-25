package com.caleb.mimusica;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Guarda favoritas, recientes, veces que suena cada artista y qué carpetas cuentan como música. */
final class Store {
    private Store() { }

    /** Carpetas que casi nunca son música (chats, grabaciones, tonos): quedan apagadas por defecto. */
    private static final String[] JUNK = {"whatsapp", "telegram", "messenger", "viber", "wechat",
            "tiktok", "snapchat", "instagram", "facebook", "recorder", "recording", "voicenote",
            "ringtone", "notification", "alarm"};

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("mimusica", Context.MODE_PRIVATE);
    }

    /* ---------- Favoritas ---------- */

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

    /* ---------- Recientes y artistas ---------- */

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

    /* ---------- Carpetas ---------- */

    static boolean junk(String folder) {
        String n = folder.toLowerCase(Locale.ROOT).replaceAll("[ _\\-]", "");
        for (String j : JUNK) {
            if (n.contains(j)) return true;
        }
        return false;
    }

    /** Lo que eligió el usuario; si no eligió nada, todas las carpetas menos las de chats/grabaciones/tonos. */
    static HashMap<String, Boolean> folderStates(Context c, Collection<String> folders) {
        SharedPreferences p = prefs(c);
        Set<String> on = p.getStringSet("fon", new HashSet<String>());
        Set<String> off = p.getStringSet("foff", new HashSet<String>());
        HashMap<String, Boolean> m = new HashMap<>();
        for (String f : folders) {
            boolean v;
            if (on.contains(f)) v = true;
            else if (off.contains(f)) v = false;
            else v = !junk(f);
            m.put(f, v);
        }
        return m;
    }

    static void setFolders(Context c, ArrayList<String> folders, boolean[] checked) {
        SharedPreferences p = prefs(c);
        Set<String> on = new HashSet<>(p.getStringSet("fon", new HashSet<String>()));
        Set<String> off = new HashSet<>(p.getStringSet("foff", new HashSet<String>()));
        for (int i = 0; i < folders.size(); i++) {
            String f = folders.get(i);
            if (checked[i]) {
                on.add(f);
                off.remove(f);
            } else {
                off.add(f);
                on.remove(f);
            }
        }
        p.edit().putStringSet("fon", on).putStringSet("foff", off).apply();
    }
}
