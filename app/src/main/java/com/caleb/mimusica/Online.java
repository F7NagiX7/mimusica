package com.caleb.mimusica;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Busca la letra correcta (con tiempos si existen) en LRCLIB, un servicio gratuito y sin clave. */
final class Online {
    private Online() { }

    private static final String BASE = "https://lrclib.net/api/";

    private static String enc(String s) throws IOException {
        return URLEncoder.encode(s, "UTF-8");
    }

    private static boolean known(String s) {
        return s != null && !s.isEmpty() && !s.equals("<unknown>") && !s.equals("Artista desconocido");
    }

    /** Devuelve el texto de la letra, o null si no la encuentra. Lanza IOException si no hay internet. */
    static String find(Track t) throws IOException {
        boolean art = known(t.artist);
        if (art) {
            String u = BASE + "get?track_name=" + enc(t.title) + "&artist_name=" + enc(t.artist)
                    + (t.album != null && !t.album.isEmpty() ? "&album_name=" + enc(t.album) : "")
                    + "&duration=" + Math.round(t.duration / 1000.0);
            String r = get(u, true);
            if (r != null) {
                try {
                    String best = pick(new JSONObject(r));
                    if (best != null) return best;
                } catch (Exception ignored) { }
            }
        }
        String title = t.title.replaceAll("[\\(\\[].*?[\\)\\]]", " ").replaceAll("\\s+", " ").trim();
        if (title.isEmpty()) title = t.title;
        String q = art ? title + " " + t.artist : title;
        String r = get(BASE + "search?q=" + enc(q), true);
        if (r == null) return null;
        try {
            JSONArray a = new JSONArray(r);
            String bestSynced = null, bestPlain = null;
            double want = t.duration / 1000.0;
            for (int i = 0; i < a.length() && i < 15; i++) {
                JSONObject o = a.getJSONObject(i);
                double d = o.optDouble("duration", 0);
                if (want > 0 && d > 0 && Math.abs(d - want) > 6) continue;
                String sy = o.isNull("syncedLyrics") ? "" : o.optString("syncedLyrics", "");
                String pl = o.isNull("plainLyrics") ? "" : o.optString("plainLyrics", "");
                if (!sy.trim().isEmpty() && bestSynced == null) bestSynced = sy;
                if (!pl.trim().isEmpty() && bestPlain == null) bestPlain = pl;
            }
            return bestSynced != null ? bestSynced : bestPlain;
        } catch (Exception e) {
            return null;
        }
    }

    private static String pick(JSONObject o) {
        String sy = o.isNull("syncedLyrics") ? "" : o.optString("syncedLyrics", "");
        if (!sy.trim().isEmpty()) return sy;
        String pl = o.isNull("plainLyrics") ? "" : o.optString("plainLyrics", "");
        return pl.trim().isEmpty() ? null : pl;
    }

    /** GET que devuelve el cuerpo, o null si el servidor dice 404. */
    private static String get(String url, boolean nullOn404) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "MiMusica/1.0");
            int code = c.getResponseCode();
            if (code == 404 && nullOn404) return null;
            if (code != 200) throw new IOException("HTTP " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) {
                out.write(b, 0, n);
                if (out.size() > 2_000_000) break;
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }
}
