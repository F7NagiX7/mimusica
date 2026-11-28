package com.caleb.mimusica;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lee la letra que viene guardada dentro de las etiquetas del propio archivo
 * (mp3 → ID3 USLT/TXXX, m4a/aac → ©lyr, flac → LYRICS). Java puro: no depende de Android.
 * Solo recorre las cabeceras y salta la portada, así que no gasta memoria.
 */
final class Lyrics {
    private Lyrics() { }

    /** Letra separada en líneas. times (ms) es null si la letra no trae marcas de tiempo. */
    static final class Doc {
        final String[] lines;
        final long[] times;
        /** Tiempos por palabra (null si la letra no los trae). Por línea: texto, inicio y fin en ms. */
        String[][] unitText;
        long[][] unitStart, unitEnd;

        Doc(String[] lines, long[] times) {
            this.lines = lines;
            this.times = times;
        }

        /** Línea que suena en ese instante (-1 si aún no empieza o no hay tiempos). */
        int indexAt(long ms) {
            int r = -1;
            if (times == null) return r;
            for (int i = 0; i < times.length; i++) {
                if (times[i] <= ms) r = i;
                else break;
            }
            return r;
        }
    }

    /* ---------------- Lectura ---------------- */

    static String read(InputStream raw) {
        try {
            BufferedInputStream b = new BufferedInputStream(raw, 1 << 15);
            b.mark(32);
            byte[] h = new byte[12];
            int n = 0;
            while (n < h.length) {
                int r = b.read(h, n, h.length - n);
                if (r < 0) break;
                n += r;
            }
            b.reset();
            if (n < h.length) return null;
            DataInputStream in = new DataInputStream(b);
            String r;
            if (h[0] == 'I' && h[1] == 'D' && h[2] == '3') r = id3(in);
            else if (h[0] == 'f' && h[1] == 'L' && h[2] == 'a' && h[3] == 'C') r = flac(in);
            else if (h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p') r = atoms(in, Long.MAX_VALUE, "");
            else r = null;
            return clean(r);
        } catch (Exception e) {
            return null;
        }
    }

    private static String clean(String s) {
        if (s == null) return null;
        s = s.replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "").trim();
        s = repair(s);
        return s.isEmpty() ? null : s;
    }

    /** Arregla letras con acentos rotos ("canciÃ³n" → "canción"): UTF-8 que se leyó como Latin-1. */
    static String repair(String s) {
        if (s.indexOf('Ã') < 0 && s.indexOf('Â') < 0 && s.indexOf('â') < 0) return s;
        try {
            String r = new String(s.getBytes(Charset.forName("windows-1252")), StandardCharsets.UTF_8);
            if (r.indexOf('\uFFFD') < 0 && r.length() < s.length()) return r;
        } catch (Exception ignored) { }
        return s;
    }

    private static void skip(DataInputStream in, long n) throws IOException {
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) {
                if (in.read() < 0) throw new EOFException();
                s = 1;
            }
            n -= s;
        }
    }

    private static long syncsafe(byte[] b, int o) {
        return ((b[o] & 0x7F) << 21) | ((b[o + 1] & 0x7F) << 14) | ((b[o + 2] & 0x7F) << 7) | (b[o + 3] & 0x7F);
    }

    private static long be32(byte[] b, int o) {
        return ((long) (b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    /* ---------------- MP3 (ID3v2) ---------------- */

    private static String id3(DataInputStream in) throws IOException {
        byte[] h = new byte[10];
        in.readFully(h);
        int ver = h[3] & 0xFF;
        int flags = h[5] & 0xFF;
        long left = syncsafe(h, 6);
        if (ver >= 3 && (flags & 0x40) != 0) {            // cabecera extendida
            byte[] e = new byte[4];
            in.readFully(e);
            long extra = ver == 4 ? syncsafe(e, 0) - 4 : be32(e, 0);
            skip(in, extra);
            left -= 4 + extra;
        }
        int hdr = ver == 2 ? 6 : 10;
        String found = null;
        while (left > hdr) {
            byte[] fh = new byte[hdr];
            in.readFully(fh);
            left -= hdr;
            if (fh[0] == 0) break;                         // relleno: ya no hay más frames
            String id = new String(fh, 0, ver == 2 ? 3 : 4, StandardCharsets.ISO_8859_1);
            long fs;
            if (ver == 2) fs = ((fh[3] & 0xFF) << 16) | ((fh[4] & 0xFF) << 8) | (fh[5] & 0xFF);
            else if (ver == 4) fs = syncsafe(fh, 4);
            else fs = be32(fh, 4);
            if (fs < 0 || fs > left) break;
            boolean uslt = id.equals("USLT") || id.equals("ULT");
            boolean txxx = id.equals("TXXX") || id.equals("TXX");
            if ((uslt || txxx) && fs <= 2_000_000) {
                byte[] d = new byte[(int) fs];
                in.readFully(d);
                if (ver == 4 && (fh[9] & 0x01) != 0 && d.length > 4) d = Arrays.copyOfRange(d, 4, d.length);
                String t = uslt ? uslt(d) : txxx(d);
                if (t != null && !t.trim().isEmpty()) {
                    if (uslt) return t;
                    if (found == null) found = t;
                }
            } else {
                skip(in, fs);                              // portada y demás: se salta sin leer
            }
            left -= fs;
        }
        return found;
    }

    private static int skipTerm(byte[] d, int from, int enc) {
        if (enc == 1 || enc == 2) {
            for (int j = from; j + 1 < d.length; j += 2) {
                if (d[j] == 0 && d[j + 1] == 0) return j + 2;
            }
            return -1;
        }
        for (int j = from; j < d.length; j++) {
            if (d[j] == 0) return j + 1;
        }
        return -1;
    }

    private static String decode(byte[] d, int off, int len, int enc) {
        if (len <= 0) return "";
        Charset cs;
        switch (enc) {
            case 1: cs = Charset.forName("UTF-16"); break;
            case 2: cs = Charset.forName("UTF-16BE"); break;
            case 3: cs = StandardCharsets.UTF_8; break;
            default:
                // Dice "Latin-1" pero muchas veces es UTF-8: si cuadra como UTF-8, se usa así.
                try {
                    return StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(d, off, len)).toString();
                } catch (Exception e) {
                    cs = Charset.forName("windows-1252");
                }
                break;
        }
        return new String(d, off, len, cs);
    }

    private static String uslt(byte[] d) {
        if (d.length < 5) return null;
        int enc = d[0] & 0xFF;
        int i = skipTerm(d, 4, enc);
        if (i < 0) return null;
        return decode(d, i, d.length - i, enc);
    }

    private static String txxx(byte[] d) {
        if (d.length < 3) return null;
        int enc = d[0] & 0xFF;
        int i = skipTerm(d, 1, enc);
        if (i < 0) return null;
        String desc = decode(d, 1, i - 1, enc).replace("\u0000", "").trim().toLowerCase(Locale.ROOT);
        if (!desc.contains("lyric")) return null;
        return decode(d, i, d.length - i, enc);
    }

    /* ---------------- FLAC ---------------- */

    private static String flac(DataInputStream in) throws IOException {
        byte[] m = new byte[4];
        in.readFully(m);
        while (true) {
            int b = in.readUnsignedByte();
            boolean last = (b & 0x80) != 0;
            int type = b & 0x7F;
            int len = (in.readUnsignedByte() << 16) | (in.readUnsignedByte() << 8) | in.readUnsignedByte();
            if (type == 4 && len < 4_000_000) {
                byte[] d = new byte[len];
                in.readFully(d);
                return vorbis(d);
            }
            skip(in, len);
            if (last) return null;
        }
    }

    private static String vorbis(byte[] d) {
        int p = 0;
        if (d.length < 8) return null;
        p += 4 + le32(d, p);                               // fabricante
        if (p < 0 || p + 4 > d.length) return null;
        int n = le32(d, p);
        p += 4;
        for (int i = 0; i < n && p + 4 <= d.length; i++) {
            int l = le32(d, p);
            p += 4;
            if (l < 0 || p + l > d.length) break;
            String s = new String(d, p, l, StandardCharsets.UTF_8);
            p += l;
            int eq = s.indexOf('=');
            if (eq > 0) {
                String k = s.substring(0, eq).toUpperCase(Locale.ROOT);
                if (k.equals("LYRICS") || k.equals("UNSYNCEDLYRICS") || k.equals("SYNCEDLYRICS")) {
                    return s.substring(eq + 1);
                }
            }
        }
        return null;
    }

    /* ---------------- M4A / AAC (MP4) ---------------- */

    private static String atoms(DataInputStream in, long limit, String parent) throws IOException {
        long used = 0;
        while (limit - used >= 8) {
            long size = in.readInt() & 0xFFFFFFFFL;
            byte[] t = new byte[4];
            in.readFully(t);
            long hdr = 8;
            if (size == 1) {
                size = in.readLong();
                hdr = 16;
            } else if (size == 0) {
                size = limit == Long.MAX_VALUE ? Long.MAX_VALUE : limit - used;
            }
            if (size < hdr) return null;
            long body = size == Long.MAX_VALUE ? Long.MAX_VALUE : size - hdr;
            String type = new String(t, StandardCharsets.ISO_8859_1);
            String r = null;
            if (type.equals("moov") || type.equals("udta") || type.equals("ilst") || type.equals("©lyr")) {
                r = atoms(in, body, type);
            } else if (type.equals("meta")) {
                skip(in, 4);
                r = atoms(in, body - 4, type);
            } else if (type.equals("data") && parent.equals("©lyr") && body >= 8 && body < 2_000_000) {
                skip(in, 8);
                byte[] d = new byte[(int) (body - 8)];
                in.readFully(d);
                return new String(d, StandardCharsets.UTF_8);
            } else {
                skip(in, body);
            }
            if (r != null) return r;
            if (size == Long.MAX_VALUE) return null;
            used += size;
        }
        if (limit != Long.MAX_VALUE && limit > used) skip(in, limit - used);
        return null;
    }

    /* ---------------- Interpretar el texto (con o sin tiempos .lrc) ---------------- */

    private static final Pattern LINE =
            Pattern.compile("^\\s*((?:\\[\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?\\])+)(.*)$");
    private static final Pattern STAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern WTAG = Pattern.compile("<(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?>");
    private static final Pattern META = Pattern.compile("^\\s*\\[[a-zA-Z]+:.*\\]\\s*$");

    /** Quita las marcas &lt;mm:ss.xx&gt; de palabra (para guardar una letra normal dentro del archivo). */
    static String stripWordTags(String text) {
        return text == null ? null : WTAG.matcher(text).replaceAll("");
    }

    private static long stampMs(String mm, String ss, String f) {
        long ms = Long.parseLong(mm) * 60000L + Long.parseLong(ss) * 1000L;
        if (f != null) {
            long v = Long.parseLong(f);
            ms += f.length() == 1 ? v * 100 : f.length() == 2 ? v * 10 : v;
        }
        return ms;
    }

    /** Parte "<t>palabra <t>otra <t>" en unidades con inicio y fin; null si no hay marcas. */
    private static Object[] units(String body) {
        Matcher w = WTAG.matcher(body);
        ArrayList<Long> st = new ArrayList<>();
        ArrayList<Integer> ps = new ArrayList<>(), pe = new ArrayList<>();
        while (w.find()) {
            st.add(stampMs(w.group(1), w.group(2), w.group(3)));
            ps.add(w.start());
            pe.add(w.end());
        }
        if (st.isEmpty()) return null;
        ArrayList<String> tx = new ArrayList<>();
        ArrayList<Long> a = new ArrayList<>(), b = new ArrayList<>();
        for (int k = 0; k < st.size(); k++) {
            int from = pe.get(k);
            int to = k + 1 < st.size() ? ps.get(k + 1) : body.length();
            String t = body.substring(from, to);
            if (t.isEmpty() && k + 1 >= st.size()) continue;   // marca final sin texto: solo cierra
            long end = k + 1 < st.size() ? st.get(k + 1) : st.get(k) + 700;
            tx.add(t);
            a.add(st.get(k));
            b.add(Math.max(end, st.get(k) + 60));
        }
        if (tx.isEmpty()) return null;
        // la primera unidad sin espacios al inicio y la última sin espacios al final: el texto coincide con la línea mostrada
        tx.set(0, tx.get(0).replaceAll("^\\s+", ""));
        int z = tx.size() - 1;
        tx.set(z, tx.get(z).replaceAll("\\s+$", ""));
        String[] ta = tx.toArray(new String[0]);
        long[] sa = new long[ta.length], sb = new long[ta.length];
        for (int k = 0; k < ta.length; k++) {
            sa[k] = a.get(k);
            sb[k] = b.get(k);
        }
        return new Object[]{ta, sa, sb};
    }

    static Doc parse(String text) {
        String[] raw = text.split("\n", -1);
        ArrayList<Long> ts = new ArrayList<>();
        ArrayList<String> tx = new ArrayList<>();
        ArrayList<Object[]> un = new ArrayList<>();
        for (String line : raw) {
            Matcher m = LINE.matcher(line);
            if (!m.matches()) continue;
            String body = m.group(2).trim();
            Object[] u = units(body);
            if (u != null) {
                StringBuilder sbd = new StringBuilder();
                for (String x : (String[]) u[0]) sbd.append(x);
                body = sbd.toString().trim();
                if (body.isEmpty()) u = null;
            }
            Matcher s = STAMP.matcher(m.group(1));
            while (s.find()) {
                long ms = Long.parseLong(s.group(1)) * 60000L + Long.parseLong(s.group(2)) * 1000L;
                String f = s.group(3);
                if (f != null) {
                    long v = Long.parseLong(f);
                    ms += f.length() == 1 ? v * 100 : f.length() == 2 ? v * 10 : v;
                }
                ts.add(ms);
                tx.add(body.isEmpty() ? "♪" : body);
                un.add(u);
            }
        }
        if (ts.size() >= 2) {
            Integer[] order = new Integer[ts.size()];
            for (int i = 0; i < order.length; i++) order[i] = i;
            final ArrayList<Long> tf = ts;
            Arrays.sort(order, (a, b) -> Long.compare(tf.get(a), tf.get(b)));
            String[] lines = new String[order.length];
            long[] times = new long[order.length];
            for (int i = 0; i < order.length; i++) {
                lines[i] = tx.get(order[i]);
                times[i] = ts.get(order[i]);
            }
            Doc d = new Doc(lines, times);
            int with = 0;
            for (Object[] u : un) if (u != null) with++;
            if (with >= 3) {
                d.unitText = new String[lines.length][];
                d.unitStart = new long[lines.length][];
                d.unitEnd = new long[lines.length][];
                for (int i = 0; i < order.length; i++) {
                    Object[] u = un.get(order[i]);
                    if (u == null) continue;
                    d.unitText[i] = (String[]) u[0];
                    d.unitStart[i] = (long[]) u[1];
                    d.unitEnd[i] = (long[]) u[2];
                }
            }
            return d;
        }
        ArrayList<String> plain = new ArrayList<>();
        for (String line : raw) {
            if (!META.matcher(line).matches()) plain.add(line.trim());
        }
        return new Doc(plain.toArray(new String[0]), null);
    }
}
