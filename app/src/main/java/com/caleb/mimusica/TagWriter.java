package com.caleb.mimusica;

import android.app.RecoverableSecurityException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IntentSender;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;

/** Guarda la letra dentro del archivo de música (pide permiso al sistema cuando Android lo exige). */
final class TagWriter {
    private TagWriter() { }

    static final int OK = 1, UNSUPPORTED = 2, DENIED = 3, ERROR = 4;

    /** Puede lanzar SecurityException: entonces hay que pedir permiso con consent(). */
    static int write(Context c, Uri uri, String text, File cache) {
        ContentResolver cr = c.getContentResolver();
        try (ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "rw")) {   // solo comprueba el permiso, no toca nada
            if (pfd == null) return ERROR;
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            return ERROR;
        }
        File src = new File(cache, "tag_src.tmp"), dst = new File(cache, "tag_out.tmp");
        boolean keepSrc = false;
        try {
            try (InputStream in = cr.openInputStream(uri); OutputStream o = new FileOutputStream(src)) {
                if (in == null) return ERROR;
                copy(in, o);
            }
            if (TagCore.rewrite(src, dst, text) == TagCore.UNSUPPORTED) return UNSUPPORTED;
            String back;
            try (FileInputStream f = new FileInputStream(dst)) {
                back = Lyrics.read(f);
            }
            if (back == null || back.isEmpty()) return ERROR;          // si no se puede leer de vuelta, no se escribe
            try (OutputStream o = cr.openOutputStream(uri, "wt"); FileInputStream f = new FileInputStream(dst)) {
                if (o == null) return ERROR;
                copy(f, o);
            } catch (IOException | RuntimeException e) {
                try (OutputStream o = cr.openOutputStream(uri, "wt"); FileInputStream f = new FileInputStream(src)) {
                    if (o != null) copy(f, o);                          // se devuelve el archivo original
                } catch (Exception e2) {
                    keepSrc = true;
                }
                return ERROR;
            }
            return OK;
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            return ERROR;
        } finally {
            dst.delete();
            if (!keepSrc) src.delete();
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] b = new byte[1 << 16];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        out.flush();
    }

    /** Pantalla de permiso del sistema para modificar ese archivo (null si no hay forma). */
    static IntentSender consent(Context c, Uri uri, SecurityException e) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                return MediaStore.createWriteRequest(c.getContentResolver(), Collections.singletonList(uri)).getIntentSender();
            }
            if (Build.VERSION.SDK_INT >= 29 && e instanceof RecoverableSecurityException) {
                return ((RecoverableSecurityException) e).getUserAction().getActionIntent().getIntentSender();
            }
        } catch (Exception ignored) { }
        return null;
    }
}
