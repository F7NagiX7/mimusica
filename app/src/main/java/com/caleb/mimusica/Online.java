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
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Busca la letra en varios servicios gratuitos y sin clave, uno tras otro:
 * LRCLIB (con tiempos), NetEase (con tiempos, muy completo en música latina y asiática)
 * y lyrics.ovh (solo texto, último recurso). Ningún servicio las tiene todas, por eso son varios.
 */
final class Online {
    private Online() { }

    private static final String LRC = "https://lrclib.net/api/";
    private static final Pattern STAMP = Pattern.compile("\\[\\d{1,3}:\\d{2}");

    /* ---------- Limpieza de nombres (los metadatos suelen venir sucios) ---------- */

    static String cleanTitle(String s) {
        if (s == null) return "";
        String o = s;
        s = s.replace('_', ' ');
        s = s.replaceFirst("^\\d{1,3}\\s*[-._)]\\s*", "");
        s = s.replaceAll("[\\(\\[\\{].*?[\\)\\]\\}]", " ");
        s = s.replaceAll("(?i)\\b(official|oficial|lyrics?|letra|videoclip|video clip|lyric video)\\b.*$", " ");
        s = s.replaceAll("(?i)\\s+(ft\\.?|feat\\.?|featuring)\\s+.*$", " ");
        s = s.replaceAll("(?i)www\\.\\S+|\\S+\\.(com|net|org|info)\\b", " ");
        s = s.replaceAll("\\s+", " ").trim();
        s = s.replaceAll("^[\\-–—\\s]+|[\\-–—\\s]+$", "");
        return s.isEmpty() ? o.trim() : s;
    }

    static String cleanArtist(String s) {
        if (s == null) return "";
        if (s.equals("<unknown>") || s.equals("Artista desconocido")) return "";
        s = s.replace('_', ' ');
        String[] p = s.split("(?i)\\s*(,|&|;|\\bfeat\\.?\\s|\\bft\\.?\\s|\\bx\\b|\\band\\b)\\s*");
        return p.length > 0 ? p[0].trim() : s.trim();
    }

    private static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static boolean like(String a, String b) {
        String x = norm(a), y = norm(b);
        if (x.isEmpty() || y.isEmpty()) return true;
        return x.equals(y) || (x.length() >= 3 && y.contains(x)) || (y.length() >= 3 && x.contains(y));
    }

    private static boolean synced(String t) {
        return t != null && STAMP.matcher(t).find();
    }

    /* ---------- Búsqueda ---------- */

    private static String best; // letra sin tiempos de reserva (mientras se busca una con tiempos)

    /** Devuelve la letra (con tiempos si alguien la tiene) o null. Lanza IOException si no hubo internet. */
    static synchronized String find(String rawTitle, String rawArtist, long durMs) throws IOException {
        best = null;
        String title = cleanTitle(rawTitle);
        String artist = cleanArtist(rawArtist);
        int dash = title.indexOf(" - ");               // "Artista - Título" metido en el título
        if (dash > 0 && dash < title.length() - 3) {
            String left = title.substring(0, dash).trim(), right = title.substring(dash + 3).trim();
            if (artist.isEmpty()) {
                artist = cleanArtist(left);
                title = right;
            } else if (like(left, artist)) {
                title = right;
            } else if (like(right, artist)) {   // "Título - Artista"
                title = left;
            }
        }
        double want = durMs / 1000.0;
        boolean answered = false;
        IOException last = null;
        String r;

        String[] steps = {"lrcget", "lrcsearch", "netease", "ovh"};
        for (String step : steps) {
            try {
                switch (step) {
                    case "lrcget":
                        r = lrcGet(rawTitle, rawArtist, want);
                        if (r == null && !artist.isEmpty()) r = lrcGet(title, artist, want);
                        break;
                    case "lrcsearch":
                        r = lrcSearch(title + " " + artist, title, artist, want, false);
                        if (r == null || !synced(r)) {
                            String r2 = lrcSearch(title, title, artist, want, true);
                            if (r2 != null && (r == null || synced(r2))) r = r2;
                        }
                        break;
                    case "netease":
                        r = netease(title + " " + artist, title, artist, want);
                        if (r == null) r = netease(title, title, artist, want);
                        break;
                    default:
                        r = artist.isEmpty() ? null : ovh(artist, title);
                        break;
                }
                answered = true;
            } catch (IOException e) {
                last = e;
                r = null;
            }
            if (r != null) {
                if (synced(r)) {
                    if (r.indexOf('<') < 0) {            // si NetEase tiene tiempos por palabra, se prefieren
                        try {
                            String wl = wordLevel(title, artist, want);
                            if (wl != null) return wl;
                        } catch (IOException ignored) {
                        }
                    }
                    return r;
                }
                if (best == null) best = r;
            }
        }
        if (best != null) return best;
        if (!answered && last != null) throw last;
        return null;
    }

    private static String pick(JSONObject o) {
        String sy = o.isNull("syncedLyrics") ? "" : o.optString("syncedLyrics", "");
        if (!sy.trim().isEmpty()) return sy;
        String pl = o.isNull("plainLyrics") ? "" : o.optString("plainLyrics", "");
        return pl.trim().isEmpty() ? null : pl;
    }

    private static String lrcGet(String title, String artist, double want) throws IOException {
        if (artist == null || artist.isEmpty() || title == null || title.isEmpty()) return null;
        String u = LRC + "get?track_name=" + enc(title) + "&artist_name=" + enc(artist)
                + (want > 0 ? "&duration=" + Math.round(want) : "");
        String body = get(u, null);
        if (body == null) return null;
        try {
            return pick(new JSONObject(body));
        } catch (Exception e) {
            return null;
        }
    }

    private static String lrcSearch(String q, String title, String artist, double want, boolean strictArtist) throws IOException {
        String body = get(LRC + "search?q=" + enc(q.trim()), null);
        if (body == null) return null;
        try {
            JSONArray a = new JSONArray(body);
            String bs = null, bp = null;
            double bsd = 1e9, bpd = 1e9;
            for (int i = 0; i < a.length() && i < 20; i++) {
                JSONObject o = a.getJSONObject(i);
                if (!like(o.optString("trackName", ""), title)) continue;
                if (!artist.isEmpty() && !like(o.optString("artistName", ""), artist)) continue;
                double d = o.optDouble("duration", 0);
                double diff = (want > 0 && d > 0) ? Math.abs(d - want) : 0;
                if (diff > 12) continue;
                String sy = o.isNull("syncedLyrics") ? "" : o.optString("syncedLyrics", "");
                String pl = o.isNull("plainLyrics") ? "" : o.optString("plainLyrics", "");
                if (!sy.trim().isEmpty() && diff < bsd) { bs = sy; bsd = diff; }
                if (!pl.trim().isEmpty() && diff < bpd) { bp = pl; bpd = diff; }
            }
            return bs != null ? bs : bp;
        } catch (Exception e) {
            return null;
        }
    }

    private static String netease(String q, String title, String artist, double want) throws IOException {
        String body = get("https://music.163.com/api/search/get/web?csrf_token=&hlpretag=&hlposttag=&type=1&offset=0&total=true&limit=8&s="
                + enc(q.trim()), "https://music.163.com");
        if (body == null) return null;
        try {
            JSONObject res = new JSONObject(body).optJSONObject("result");
            if (res == null) return null;
            JSONArray songs = res.optJSONArray("songs");
            if (songs == null) return null;
            long bestId = -1;
            double bestDiff = 1e9;
            for (int i = 0; i < songs.length(); i++) {
                JSONObject s = songs.getJSONObject(i);
                if (!like(s.optString("name", ""), title)) continue;
                if (!artist.isEmpty()) {
                    boolean ok = false;
                    JSONArray ars = s.optJSONArray("artists");
                    for (int k = 0; ars != null && k < ars.length(); k++) {
                        if (like(ars.getJSONObject(k).optString("name", ""), artist)) ok = true;
                    }
                    if (!ok) continue;
                }
                double d = s.optDouble("duration", 0) / 1000.0;
                double diff = (want > 0 && d > 0) ? Math.abs(d - want) : 0;
                if (diff > 12) continue;
                if (diff < bestDiff) {
                    bestDiff = diff;
                    bestId = s.optLong("id", -1);
                }
            }
            if (bestId < 0) return null;
            String lb = get("https://music.163.com/api/song/lyric?lv=1&kv=1&tv=-1&id=" + bestId, "https://music.163.com");
            if (lb == null) return null;
            JSONObject lo = new JSONObject(lb).optJSONObject("lrc");
            String lrc = lo == null ? "" : lo.optString("lyric", "");
            return lrc.trim().length() > 40 ? lrc : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Letra con tiempo por palabra (formato yrc de NetEase) convertida a LRC mejorado; null si no hay. */
    static String wordLevel(String title, String artist, double want) throws IOException {
        String[] qs = {title + " " + artist, title};
        for (String q : qs) {
            if (q.trim().isEmpty()) continue;
            String body = get("https://music.163.com/api/search/get/web?csrf_token=&hlpretag=&hlposttag=&type=1&offset=0&total=true&limit=8&s="
                    + enc(q.trim()), "https://music.163.com");
            if (body == null) continue;
            try {
                JSONObject res = new JSONObject(body).optJSONObject("result");
                JSONArray songs = res == null ? null : res.optJSONArray("songs");
                if (songs == null) continue;
                long bestId = -1;
                double bestDiff = 1e9;
                for (int i = 0; i < songs.length(); i++) {
                    JSONObject s = songs.getJSONObject(i);
                    if (!like(s.optString("name", ""), title)) continue;
                    if (!artist.isEmpty()) {
                        boolean ok = false;
                        JSONArray ars = s.optJSONArray("artists");
                        for (int k = 0; ars != null && k < ars.length(); k++) {
                            if (like(ars.getJSONObject(k).optString("name", ""), artist)) ok = true;
                        }
                        if (!ok) continue;
                    }
                    double d = s.optDouble("duration", 0) / 1000.0;
                    double diff = (want > 0 && d > 0) ? Math.abs(d - want) : 0;
                    if (diff > 4) continue;
                    if (diff < bestDiff) {
                        bestDiff = diff;
                        bestId = s.optLong("id", -1);
                    }
                }
                if (bestId < 0) continue;
                String[] urls = {
                        "https://music.163.com/api/song/lyric?lv=1&kv=1&tv=-1&yv=1&id=" + bestId,
                        "https://music.163.com/api/song/lyric/v1?id=" + bestId + "&lv=1&yv=1&tv=-1&rv=-1"};
                for (String u : urls) {
                    String lb = get(u, "https://music.163.com");
                    if (lb == null) continue;
                    JSONObject yo = new JSONObject(lb).optJSONObject("yrc");
                    String conv = yo == null ? null : yrcToLrc(yo.optString("lyric", ""));
                    if (conv != null) return conv;
                }
            } catch (IOException e) {
                throw e;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static String mmss(long ms) {
        return String.format(java.util.Locale.US, "%02d:%02d.%03d", ms / 60000, (ms / 1000) % 60, ms % 1000);
    }

    /** yrc: [inicio,dur](ini,dur,0)texto(ini,dur,0)texto…  →  [mm:ss.mmm]<mm:ss.mmm>texto<mm:ss.mmm>texto<fin> */
    static String yrcToLrc(String yrc) {
        if (yrc == null || yrc.isEmpty()) return null;
        java.util.regex.Pattern lp = java.util.regex.Pattern.compile("^\\[(\\d+),(\\d+)\\](.*)$");
        java.util.regex.Pattern wp = java.util.regex.Pattern.compile("\\((\\d+),(\\d+),-?\\d+\\)(.*?)(?=\\(\\d+,\\d+,-?\\d+\\)|$)");
        StringBuilder out = new StringBuilder();
        int n = 0;
        for (String line : yrc.split("\n")) {
            java.util.regex.Matcher m = lp.matcher(line.trim());
            if (!m.matches()) continue;
            java.util.regex.Matcher w = wp.matcher(m.group(3));
            StringBuilder sb = new StringBuilder();
            long end = 0;
            int words = 0;
            while (w.find()) {
                long s = Long.parseLong(w.group(1)), d = Long.parseLong(w.group(2));
                sb.append('<').append(mmss(s)).append('>').append(w.group(3));
                end = s + d;
                words++;
            }
            if (words == 0) continue;
            sb.append('<').append(mmss(end)).append('>');
            out.append('[').append(mmss(Long.parseLong(m.group(1)))).append(']').append(sb).append('\n');
            n++;
        }
        return n >= 5 ? out.toString() : null;
    }

    private static String ovh(String artist, String title) throws IOException {
        String u = "https://api.lyrics.ovh/v1/" + enc(artist).replace("+", "%20") + "/" + enc(title).replace("+", "%20");
        String body = get(u, null);
        if (body == null) return null;
        try {
            String l = new JSONObject(body).optString("lyrics", "");
            return l.trim().length() > 40 ? l : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String enc(String s) throws IOException {
        return URLEncoder.encode(s, "UTF-8");
    }

    /** GET que devuelve el cuerpo, o null si no hay (404). Lanza IOException si no hay conexión. */
    private static String get(String url, String referer) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) MiMusica/1.0");
            if (referer != null) c.setRequestProperty("Referer", referer);
            int code = c.getResponseCode();
            if (code == 404) return null;
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
