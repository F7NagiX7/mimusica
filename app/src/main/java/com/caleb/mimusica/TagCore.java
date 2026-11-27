package com.caleb.mimusica;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Escribe la letra dentro de las etiquetas del archivo (MP3 → ID3 USLT, FLAC → LYRICS).
 * Java puro: lee el archivo original y crea uno nuevo con la etiqueta reescrita; el audio se copia igual.
 */
final class TagCore {
    private TagCore() { }

    static final int UNSUPPORTED = 0, MP3 = 1, FLAC = 2;

    static int rewrite(File src, File dst, String lyrics) throws IOException {
        lyrics = lyrics.replace("\r\n", "\n").replace('\r', '\n').trim();
        try (RandomAccessFile in = new RandomAccessFile(src, "r");
             OutputStream out = new BufferedOutputStream(new FileOutputStream(dst), 1 << 16)) {
            byte[] h = new byte[10];
            int n = in.read(h);
            if (n >= 4 && h[0] == 'f' && h[1] == 'L' && h[2] == 'a' && h[3] == 'C') {
                return flac(in, out, lyrics) ? FLAC : UNSUPPORTED;
            }
            if (n >= 10 && h[0] == 'I' && h[1] == 'D' && h[2] == '3') {
                return id3(in, h, out, lyrics) ? MP3 : UNSUPPORTED;
            }
            if (n >= 2 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xE0) == 0xE0) {
                return id3(in, null, out, lyrics) ? MP3 : UNSUPPORTED;   // mp3 sin etiqueta
            }
            return UNSUPPORTED;
        }
    }

    private static long syncsafe(byte[] b, int o) {
        return ((b[o] & 0x7F) << 21) | ((b[o + 1] & 0x7F) << 14) | ((b[o + 2] & 0x7F) << 7) | (b[o + 3] & 0x7F);
    }

    private static void putSyncsafe(byte[] b, int o, long v) {
        b[o] = (byte) ((v >> 21) & 0x7F);
        b[o + 1] = (byte) ((v >> 14) & 0x7F);
        b[o + 2] = (byte) ((v >> 7) & 0x7F);
        b[o + 3] = (byte) (v & 0x7F);
    }

    private static long be32(byte[] b, int o) {
        return ((long) (b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    private static void putBe32(byte[] b, int o, long v) {
        b[o] = (byte) (v >> 24);
        b[o + 1] = (byte) (v >> 16);
        b[o + 2] = (byte) (v >> 8);
        b[o + 3] = (byte) v;
    }

    private static void copyRest(RandomAccessFile in, long from, OutputStream out) throws IOException {
        in.seek(from);
        byte[] buf = new byte[1 << 16];
        int r;
        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
    }

    /* ---------------- MP3 ---------------- */

    private static boolean id3(RandomAccessFile in, byte[] h, OutputStream out, String lyrics) throws IOException {
        int ver = 3;
        long oldEnd = 0;
        ArrayList<byte[]> kept = new ArrayList<>();
        if (h != null) {
            ver = h[3] & 0xFF;
            int flags = h[5] & 0xFF;
            if (ver < 3 || ver > 4 || (flags & 0x80) != 0) return false;   // v2.2 o "unsynchronisation": no se toca
            long size = syncsafe(h, 6);
            oldEnd = 10 + size + ((flags & 0x10) != 0 ? 10 : 0);
            long end = 10 + size;
            long pos = 10;
            if ((flags & 0x40) != 0) {                                        // cabecera extendida: se descarta
                byte[] e = new byte[4];
                in.seek(10);
                in.readFully(e);
                pos = 10 + (ver == 4 ? syncsafe(e, 0) : be32(e, 0) + 4);
            }
            while (pos + 10 <= end) {
                in.seek(pos);
                byte[] fh = new byte[10];
                in.readFully(fh);
                if (fh[0] == 0) break;
                long fs = ver == 4 ? syncsafe(fh, 4) : be32(fh, 4);
                if (fs < 0 || fs > 16_000_000 || pos + 10 + fs > end) break;
                String id = new String(fh, 0, 4, StandardCharsets.ISO_8859_1);
                byte[] fr = new byte[(int) (10 + fs)];
                System.arraycopy(fh, 0, fr, 0, 10);
                in.readFully(fr, 10, (int) fs);
                boolean drop = id.equals("USLT");
                if (id.equals("TXXX") && fs >= 2) {
                    String d = new String(fr, 11, (int) Math.min(fs - 1, 40), StandardCharsets.ISO_8859_1)
                            .replace("\u0000", "").toLowerCase(Locale.ROOT);
                    if (d.contains("lyric")) drop = true;
                }
                if (!drop) kept.add(fr);
                pos += 10 + fs;
            }
        }
        byte[] body;
        if (ver == 4) {
            byte[] t = lyrics.getBytes(StandardCharsets.UTF_8);
            body = new byte[5 + t.length];
            body[0] = 3;
            body[1] = body[2] = body[3] = 'X';
            body[4] = 0;
            System.arraycopy(t, 0, body, 5, t.length);
        } else {
            byte[] t = lyrics.getBytes(StandardCharsets.UTF_16LE);
            body = new byte[4 + 4 + 2 + t.length];
            body[0] = 1;
            body[1] = body[2] = body[3] = 'X';
            body[4] = (byte) 0xFF;
            body[5] = (byte) 0xFE;                 // descripción vacía: BOM + terminador
            body[6] = 0;
            body[7] = 0;
            body[8] = (byte) 0xFF;
            body[9] = (byte) 0xFE;                 // texto: BOM + UTF-16 little endian
            System.arraycopy(t, 0, body, 10, t.length);
        }
        byte[] us = new byte[10 + body.length];
        us[0] = 'U'; us[1] = 'S'; us[2] = 'L'; us[3] = 'T';
        if (ver == 4) putSyncsafe(us, 4, body.length); else putBe32(us, 4, body.length);
        System.arraycopy(body, 0, us, 10, body.length);

        long total = us.length;
        for (byte[] k : kept) total += k.length;
        int padding = 1024;
        byte[] head = new byte[10];
        head[0] = 'I'; head[1] = 'D'; head[2] = '3'; head[3] = (byte) ver;
        putSyncsafe(head, 6, total + padding);
        out.write(head);
        for (byte[] k : kept) out.write(k);
        out.write(us);
        out.write(new byte[padding]);
        copyRest(in, oldEnd, out);
        out.flush();
        return true;
    }

    /* ---------------- FLAC ---------------- */

    private static final class Blk {
        final int type;
        byte[] data;

        Blk(int type, byte[] data) {
            this.type = type;
            this.data = data;
        }
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    private static void putLe32(java.io.ByteArrayOutputStream o, int v) {
        o.write(v);
        o.write(v >> 8);
        o.write(v >> 16);
        o.write(v >> 24);
    }

    private static boolean flac(RandomAccessFile in, OutputStream out, String lyrics) throws IOException {
        in.seek(4);
        ArrayList<Blk> blocks = new ArrayList<>();
        int vorbisAt = -1;
        byte[] oldV = null;
        while (true) {
            int b = in.read();
            if (b < 0) return false;
            boolean last = (b & 0x80) != 0;
            int type = b & 0x7F;
            int len = (in.read() << 16) | (in.read() << 8) | in.read();
            if (len < 0 || len > 16_000_000) return false;
            byte[] d = new byte[len];
            in.readFully(d);
            if (type == 4) {
                vorbisAt = blocks.size();
                oldV = d;
                blocks.add(new Blk(4, null));
            } else if (type != 1) {
                blocks.add(new Blk(type, d));
            }
            if (last) break;
        }
        long audio = in.getFilePointer();
        if (blocks.isEmpty() || blocks.get(0).type != 0) return false;

        java.io.ByteArrayOutputStream v = new java.io.ByteArrayOutputStream();
        byte[] vendor = "MiMusica".getBytes(StandardCharsets.UTF_8);
        ArrayList<byte[]> comments = new ArrayList<>();
        if (oldV != null && oldV.length >= 8) {
            int vl = le32(oldV, 0);
            if (vl >= 0 && 4 + vl + 4 <= oldV.length) {
                vendor = java.util.Arrays.copyOfRange(oldV, 4, 4 + vl);
                int p = 4 + vl;
                int cnt = le32(oldV, p);
                p += 4;
                for (int i = 0; i < cnt && p + 4 <= oldV.length; i++) {
                    int l = le32(oldV, p);
                    p += 4;
                    if (l < 0 || p + l > oldV.length) break;
                    byte[] c = java.util.Arrays.copyOfRange(oldV, p, p + l);
                    p += l;
                    String s = new String(c, StandardCharsets.UTF_8);
                    int eq = s.indexOf('=');
                    String k = eq > 0 ? s.substring(0, eq).toUpperCase(Locale.ROOT) : "";
                    if (k.equals("LYRICS") || k.equals("UNSYNCEDLYRICS") || k.equals("SYNCEDLYRICS")) continue;
                    comments.add(c);
                }
            }
        }
        comments.add(("LYRICS=" + lyrics).getBytes(StandardCharsets.UTF_8));
        putLe32(v, vendor.length);
        v.write(vendor);
        putLe32(v, comments.size());
        for (byte[] c : comments) {
            putLe32(v, c.length);
            v.write(c);
        }
        byte[] vb = v.toByteArray();
        if (vb.length > 16_000_000) return false;
        if (vorbisAt >= 0) blocks.get(vorbisAt).data = vb;
        else blocks.add(new Blk(4, vb));

        out.write(new byte[]{'f', 'L', 'a', 'C'});
        for (Blk k : blocks) {
            out.write(k.type);
            out.write(k.data.length >> 16);
            out.write(k.data.length >> 8);
            out.write(k.data.length);
            out.write(k.data);
        }
        out.write(0x81);                                  // relleno final (último bloque)
        out.write(0);
        out.write(1024 >> 8);
        out.write(1024 & 0xFF);
        out.write(new byte[1024]);
        copyRest(in, audio, out);
        out.flush();
        return true;
    }
}
