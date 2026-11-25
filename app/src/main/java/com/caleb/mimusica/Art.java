package com.caleb.mimusica;

import android.content.ContentUris;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.Size;
import android.widget.ImageView;

import java.io.InputStream;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Carga las portadas en segundo plano y las guarda en una caché pequeña (poca RAM). */
final class Art {
    private Art() { }

    private static final int[] PAL = {0xFF2B3A55, 0xFF3D2B55, 0xFF2B5547, 0xFF55402B, 0xFF552B3A, 0xFF2B4D55};
    private static final LruCache<Long, Bitmap> CACHE = new LruCache<Long, Bitmap>(10 * 1024 * 1024) {
        @Override
        protected int sizeOf(Long k, Bitmap b) {
            return b.getByteCount();
        }
    };
    private static final HashSet<Long> MISS = new HashSet<>();
    private static final ExecutorService EX = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static int bucket(int px) {
        return px <= 160 ? 0 : (px <= 400 ? 1 : 2);
    }

    private static long keyOf(Track t, int px) {
        long base = t.albumId > 0 ? t.albumId : -t.id;
        return base * 3 + bucket(px);
    }

    static void bind(Context ctx, final ImageView iv, final Track t, final int px) {
        final long key = keyOf(t, px);
        iv.setTag(key);
        Bitmap b = CACHE.get(key);
        if (b != null) {
            show(iv, b);
            return;
        }
        placeholder(iv, t);
        if (MISS.contains(key)) return;
        final Context app = ctx.getApplicationContext();
        EX.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap r = decode(app, t, px);
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (r != null) CACHE.put(key, r);
                        else MISS.add(key);
                        Object tag = iv.getTag();
                        if (r != null && tag instanceof Long && ((Long) tag).longValue() == key) show(iv, r);
                    }
                });
            }
        });
    }

    private static void show(ImageView iv, Bitmap b) {
        iv.setBackground(null);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setImageBitmap(b);
    }

    private static void placeholder(ImageView iv, Track t) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(PAL[(t.album.hashCode() & 0x7fffffff) % PAL.length]);
        iv.setBackground(g);
        iv.setScaleType(ImageView.ScaleType.CENTER);
        iv.setImageResource(R.drawable.ic_note_ph);
    }

    private static int sample(int w, int h, int px) {
        int s = 1;
        while (w / (s * 2) >= px && h / (s * 2) >= px) s *= 2;
        return s;
    }

    private static Bitmap decodeBytes(byte[] d, int px) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(d, 0, d.length, o);
        o.inSampleSize = sample(o.outWidth, o.outHeight, px);
        o.inJustDecodeBounds = false;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(d, 0, d.length, o);
    }

    private static Bitmap decodeUri(Context c, Uri u, int px) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            InputStream in = c.getContentResolver().openInputStream(u);
            if (in == null) return null;
            try {
                BitmapFactory.decodeStream(in, null, o);
            } finally {
                in.close();
            }
            o.inSampleSize = sample(o.outWidth, o.outHeight, px);
            o.inJustDecodeBounds = false;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            in = c.getContentResolver().openInputStream(u);
            if (in == null) return null;
            try {
                return BitmapFactory.decodeStream(in, null, o);
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap decode(Context c, Track t, int px) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                Bitmap b = c.getContentResolver().loadThumbnail(t.uri(), new Size(px, px), null);
                if (b != null) return b;
            } catch (Exception ignored) { }
        } else if (t.albumId > 0) {
            Uri u = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), t.albumId);
            Bitmap b = decodeUri(c, u, px);
            if (b != null) return b;
        }
        MediaMetadataRetriever m = new MediaMetadataRetriever();
        try {
            m.setDataSource(c, t.uri());
            byte[] d = m.getEmbeddedPicture();
            if (d != null) return decodeBytes(d, px);
        } catch (Exception ignored) {
        } finally {
            try {
                m.release();
            } catch (Exception ignored) { }
        }
        return null;
    }

    /* ---------- Color más vivo de la portada (para el degradado y el acento) ---------- */

    interface ColorCb {
        void done(int color);
    }

    private static final LruCache<Long, Integer> COLORS = new LruCache<>(128);

    private static long colorKey(Track t) {
        return t.albumId > 0 ? t.albumId : -t.id;
    }

    /** Lo calcula una sola vez por álbum y lo recuerda. Devuelve 0 si no hay portada o es gris. */
    static void color(Context ctx, final Track t, final ColorCb cb) {
        final long key = colorKey(t);
        Integer c = COLORS.get(key);
        if (c != null) {
            cb.done(c);
            return;
        }
        final Context app = ctx.getApplicationContext();
        EX.execute(new Runnable() {
            @Override
            public void run() {
                int col = 0;
                try {
                    Bitmap b = decode(app, t, 96);
                    if (b != null) col = vivid(b);
                } catch (Exception ignored) { }
                final int res = col;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        COLORS.put(key, res);
                        cb.done(res);
                    }
                });
            }
        });
    }

    private static int vivid(Bitmap src) {
        Bitmap b = src;
        if (Build.VERSION.SDK_INT >= 26 && b.getConfig() == Bitmap.Config.HARDWARE) {
            b = b.copy(Bitmap.Config.ARGB_8888, false);
            if (b == null) return 0;
        }
        Bitmap s = Bitmap.createScaledBitmap(b, 12, 12, true);
        int[] px = new int[144];
        s.getPixels(px, 0, 12, 0, 0, 12, 12);
        float[] hsv = new float[3];
        float[] ws = new float[12], hh = new float[12], ss = new float[12], vv = new float[12];
        for (int p : px) {
            Color.colorToHSV(p, hsv);
            if (hsv[2] < 0.15f) continue;
            float w = hsv[1] * hsv[2] + 0.02f;
            int k = Math.min(11, (int) (hsv[0] / 30f));
            ws[k] += w;
            hh[k] += hsv[0] * w;
            ss[k] += hsv[1] * w;
            vv[k] += hsv[2] * w;
        }
        int best = 0;
        for (int k = 1; k < 12; k++) {
            if (ws[k] > ws[best]) best = k;
        }
        if (ws[best] <= 0f) return 0;
        hsv[0] = hh[best] / ws[best];
        hsv[1] = ss[best] / ws[best];
        hsv[2] = vv[best] / ws[best];
        return Color.HSVToColor(hsv);
    }
}
