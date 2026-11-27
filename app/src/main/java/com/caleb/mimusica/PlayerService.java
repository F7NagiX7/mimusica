package com.caleb.mimusica;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Reproduce la música en segundo plano, con notificación y botones de la pantalla de bloqueo. */
public class PlayerService extends Service
        implements MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener,
        AudioManager.OnAudioFocusChangeListener {

    static final String CH = "mimusica";
    static final String A_INIT = "init", A_TOGGLE = "toggle", A_NEXT = "next", A_PREV = "prev", A_STOP = "stop";
    static final int NID = 7;
    static final String CA_SHUF = "shuffle", CA_FAV = "fav";

    class LocalBinder extends Binder {
        PlayerService get() {
            return PlayerService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private final AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();

    Runnable onChange;
    boolean shuffle;
    int repeat; // 0 = no repetir, 1 = toda la lista, 2 = una canción
    String source = "Mi música";

    private ArrayList<Track> queue = new ArrayList<>();
    private int idx = -1;
    private MediaPlayer mp;
    private boolean prepared, fg, resumeOnFocus;
    private int fails;
    private MediaSession session;
    private AudioManager am;
    private AudioFocusRequest focusReq;
    private NotificationManager nm;
    private Bitmap art;
    private long artFor = -1;
    private final ExecutorService artEx = Executors.newSingleThreadExecutor();
    private int lastVol = -1;
    private boolean pausedByVol;
    private ContentObserver volObs;
    private boolean quiet; // restaurada al abrir: sin notificación hasta que suene
    private final Runnable saver = new Runnable() {
        @Override
        public void run() {
            savePos();
            if (isPlaying()) h.postDelayed(this, 3000);
        }
    };
    private BroadcastReceiver volRx;

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            pause(); // se desconectaron los audífonos
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                    new NotificationChannel(CH, "Reproducción", NotificationManager.IMPORTANCE_LOW));
        }
        session = new MediaSession(this, "MiMusica");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { if (!isPlaying()) toggle(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { next(false); }
            @Override public void onSkipToPrevious() { prev(); }
            @Override public void onSeekTo(long p) { seekTo((int) p); }
            @Override public void onCustomAction(String action, Bundle extras) {
                if (CA_SHUF.equals(action)) toggleShuffle();
                else if (CA_FAV.equals(action)) toggleFavorite();
            }
        });
        session.setSessionActivity(PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        session.setActive(true);
        registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        watchVolume();
    }

    @Override
    public IBinder onBind(Intent i) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        String a = i == null ? null : i.getAction();
        if (A_STOP.equals(a)) {
            stopAll();
            return START_NOT_STICKY;
        }
        if (A_TOGGLE.equals(a)) toggle();
        else if (A_NEXT.equals(a)) next(false);
        else if (A_PREV.equals(a)) prev();
        if (A_INIT.equals(a) && !fg) goForeground();
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        savePos();
        if (!isPlaying()) stopAll();
    }

    @Override
    public void onDestroy() {
        savePos();
        h.removeCallbacks(saver);
        try { unregisterReceiver(noisy); } catch (Exception ignored) { }
        if (volRx != null) {
            try { unregisterReceiver(volRx); } catch (Exception ignored) { }
        }
        if (volObs != null) getContentResolver().unregisterContentObserver(volObs);
        artEx.shutdownNow();
        try { if (mp != null) mp.release(); } catch (Exception ignored) { }
        mp = null;
        abandonFocus();
        session.release();
        super.onDestroy();
    }

    /* ---------- Estado que lee la pantalla ---------- */

    Track current() {
        return idx >= 0 && idx < queue.size() ? queue.get(idx) : null;
    }

    boolean isPlaying() {
        try { return mp != null && prepared && mp.isPlaying(); } catch (Exception e) { return false; }
    }

    int pos() {
        try { return mp != null && prepared ? mp.getCurrentPosition() : 0; } catch (Exception e) { return 0; }
    }

    int dur() {
        try { return mp != null && prepared ? mp.getDuration() : 0; } catch (Exception e) { return 0; }
    }

    /* ---------- Control ---------- */

    void playQueue(ArrayList<Track> q, int pos, String src) {
        queue = q;
        source = src;
        Store.saveQueue(this, q, src);
        start(pos);
    }

    /** Suelta el archivo un momento (para poder reescribir su etiqueta). Devuelve {posición, sonaba}. */
    int[] detach() {
        int p = pos();
        boolean was = isPlaying();
        h.removeCallbacks(saver);
        try { if (mp != null) { if (mp.isPlaying()) mp.pause(); mp.release(); } } catch (Exception ignored) { }
        mp = null;
        prepared = false;
        return new int[]{p, was ? 1 : 0};
    }

    /** Vuelve a abrir la misma canción en el mismo punto (y la sigue si sonaba). */
    void attach(int posMs, boolean play) {
        Track t = current();
        if (t == null) return;
        try {
            mp = new MediaPlayer();
            mp.setAudioAttributes(attrs);
            mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            mp.setOnCompletionListener(this);
            mp.setOnErrorListener(this);
            mp.setDataSource(this, t.uri());
            mp.prepare();
            prepared = true;
            mp.seekTo(Math.max(0, Math.min(posMs, mp.getDuration() - 300)));
        } catch (Exception e) {
            prepared = false;
            try { if (mp != null) mp.release(); } catch (Exception ignored) { }
            mp = null;
            afterChange();
            return;
        }
        if (play) resume(); else afterChange();
    }

    /** Guarda en qué canción y en qué milisegundo va (para seguir ahí al volver a abrir la app). */
    void savePos() {
        Track t = current();
        if (t == null || mp == null || !prepared) return;
        Store.savePos(this, t.id, pos(), shuffle, repeat);
    }

    /** Deja lista la última canción en su minuto exacto, en pausa y sin notificación. */
    void restore(ArrayList<Track> q, int i, int posMs, String src, boolean sh, int rep) {
        if (current() != null || i < 0 || i >= q.size()) return;
        queue = q;
        source = src;
        shuffle = sh;
        repeat = rep;
        idx = i;
        pausedByVol = false;
        prepared = false;
        try {
            mp = new MediaPlayer();
            mp.setAudioAttributes(attrs);
            mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            mp.setOnCompletionListener(this);
            mp.setOnErrorListener(this);
            mp.setDataSource(this, q.get(i).uri());
            mp.prepare();
            prepared = true;
            int d = mp.getDuration();
            mp.seekTo(Math.max(0, Math.min(posMs, d - 500)));
            quiet = true;
        } catch (Exception e) {
            prepared = false;
            try { if (mp != null) mp.release(); } catch (Exception ignored) { }
            mp = null;
            idx = -1;
            return;
        }
        sessionMeta();
        loadArt(q.get(i));
        sessionState();
        if (onChange != null) onChange.run();
    }

    /** Canción que sigue, o null si no se puede saber (aleatorio) o es la última. */
    Track nextTrack() {
        if (shuffle || queue.isEmpty() || idx < 0) return null;
        if (idx + 1 < queue.size()) return queue.get(idx + 1);
        return repeat == 1 ? queue.get(0) : null;
    }

    void toggleShuffle() {
        shuffle = !shuffle;
        savePos();
        afterChange();
    }

    void cycleRepeat() {
        repeat = (repeat + 1) % 3;
        savePos();
        afterChange();
    }

    void toggleFavorite() {
        Track t = current();
        if (t == null) return;
        Store.toggleFav(this, t.id);
        afterChange();
    }

    /** La pantalla cambió algo (por ejemplo el corazón): refresca los botones de la notificación. */
    void syncSession() {
        sessionState();
    }

    /* ---------- Volumen: en 0 se pausa, al subirlo sigue ---------- */

    private void watchVolume() {
        lastVol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        volRx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                checkVolume();
            }
        };
        try {
            IntentFilter vf = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(volRx, vf, Context.RECEIVER_EXPORTED);
            else registerReceiver(volRx, vf);
        } catch (Exception e) {
            volRx = null;
        }
        volObs = new ContentObserver(h) {
            @Override
            public void onChange(boolean selfChange) {
                checkVolume();
            }
        };
        getContentResolver().registerContentObserver(Settings.System.CONTENT_URI, true, volObs);
    }

    /** Lee el volumen real (no confía en el aviso), así que un aviso falso no hace nada. */
    private void checkVolume() {
        int v = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        int prev = lastVol;
        lastVol = v;
        if (prev < 0 || v == prev) return;
        if (v == 0) {
            if (isPlaying()) {
                pause(true);
                pausedByVol = true;
            }
        } else if (prev == 0 && pausedByVol) {
            pausedByVol = false;
            resume();
        }
    }

    /* ---------- Portada para la notificación y la pantalla de bloqueo ---------- */

    private void loadArt(final Track t) {
        if (t.id == artFor) return;
        artFor = t.id;
        art = null;
        final Context app = getApplicationContext();
        artEx.execute(() -> {
            final Bitmap b = Art.decodeSync(app, t, 512);
            h.post(() -> {
                Track c = current();
                if (c == null || c.id != t.id) return;
                art = b;
                sessionMeta();
                if (idx >= 0 && !quiet) nm.notify(NID, buildNotification());
            });
        });
    }

    private void start(int i) {
        if (i < 0 || i >= queue.size()) return;
        idx = i;
        pausedByVol = false;
        quiet = false;
        prepared = false;
        try {
            if (mp != null) mp.release(); // una sola canción en memoria a la vez
            mp = new MediaPlayer();
            mp.setAudioAttributes(attrs);
            mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            mp.setOnCompletionListener(this);
            mp.setOnErrorListener(this);
            mp.setDataSource(this, queue.get(i).uri());
            mp.prepare();
            prepared = true;
            if (requestFocus()) {
                mp.start();
                Store.played(this, queue.get(i));
                fails = 0;
                resumeOnFocus = false;
                if (!fg) goForeground();
                h.removeCallbacks(saver);
                h.postDelayed(saver, 3000);
            }
        } catch (Exception e) {
            prepared = false;
            if (++fails < 5 && queue.size() > 1) {
                h.post(() -> next(true));
                return;
            }
        }
        sessionMeta();
        loadArt(queue.get(i));
        savePos();
        afterChange();
    }

    void toggle() {
        pausedByVol = false;
        if (mp == null || !prepared) {
            if (!queue.isEmpty()) start(idx < 0 ? 0 : idx);
            return;
        }
        if (isPlaying()) pause(); else resume();
    }

    private void resume() {
        if (mp == null || !prepared || !requestFocus()) return;
        quiet = false;
        mp.start();
        if (!fg) goForeground();
        h.removeCallbacks(saver);
        h.postDelayed(saver, 3000);
        afterChange();
    }

    void pause() {
        pause(false);
    }

    private void pause(boolean keepForeground) {
        if (mp == null || !prepared) return;
        try { if (mp.isPlaying()) mp.pause(); } catch (Exception ignored) { }
        savePos();
        if (fg && !keepForeground) {
            stopForeground(false);
            fg = false;
        }
        afterChange();
    }

    void next(boolean auto) {
        int n = queue.size();
        if (n == 0) return;
        if (auto && repeat == 2) { start(idx); return; }
        if (shuffle && n > 1) {
            int r;
            do { r = rnd.nextInt(n); } while (r == idx);
            start(r);
            return;
        }
        if (idx + 1 < n) start(idx + 1);
        else if (repeat == 1 || !auto) start(0);
        else { pause(); seekTo(0); }
    }

    void prev() {
        if (queue.isEmpty()) return;
        if (pos() > 3000) { seekTo(0); return; }
        start(idx > 0 ? idx - 1 : queue.size() - 1);
    }

    void seekTo(int ms) {
        if (mp != null && prepared) {
            try { mp.seekTo(ms); } catch (Exception ignored) { }
            savePos();
            sessionState();
        }
    }

    private void stopAll() {
        savePos();
        h.removeCallbacks(saver);
        try { if (mp != null) mp.release(); } catch (Exception ignored) { }
        mp = null;
        prepared = false;
        abandonFocus();
        stopForeground(true);
        fg = false;
        nm.cancel(NID);
        sessionState();
        if (onChange != null) onChange.run();
        stopSelf();
    }

    /* ---------- Callbacks del reproductor ---------- */

    @Override
    public void onCompletion(MediaPlayer p) {
        next(true);
    }

    @Override
    public boolean onError(MediaPlayer p, int what, int extra) {
        prepared = false;
        if (++fails < 5) h.post(() -> next(true));
        return true;
    }

    /* ---------- Audio focus (llamadas, otras apps) ---------- */

    private boolean requestFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusReq == null) {
                focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(attrs)
                        .setOnAudioFocusChangeListener(this)
                        .build();
            }
            return am.requestAudioFocus(focusReq) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
        return am.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusReq != null) am.abandonAudioFocusRequest(focusReq);
        } else {
            am.abandonAudioFocus(this);
        }
    }

    @Override
    public void onAudioFocusChange(int change) {
        if (mp == null || !prepared) return;
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                resumeOnFocus = false;
                pause();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (isPlaying()) {
                    resumeOnFocus = true;
                    pause(true); // sigue en primer plano para poder reanudar con la pantalla apagada
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                mp.setVolume(0.25f, 0.25f);
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                mp.setVolume(1f, 1f);
                if (resumeOnFocus) {
                    resumeOnFocus = false;
                    resume();
                }
                break;
            default:
                break;
        }
    }

    /* ---------- Sesión multimedia y notificación ---------- */

    private void afterChange() {
        sessionState();
        if (idx >= 0 && !quiet) nm.notify(NID, buildNotification());
        if (onChange != null) onChange.run();
    }

    private void sessionMeta() {
        Track t = current();
        if (t == null) return;
        MediaMetadata.Builder mb = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, t.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, dur());
        if (art != null) {
            mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
            mb.putBitmap(MediaMetadata.METADATA_KEY_ART, art);
        }
        session.setMetadata(mb.build());
    }

    private void sessionState() {
        PlaybackState.Builder b = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO)
                .setState(isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        pos(), 1f);
        b.addCustomAction(new PlaybackState.CustomAction.Builder(CA_SHUF, "Aleatorio",
                shuffle ? R.drawable.ic_shuffle_on : R.drawable.ic_shuffle).build());
        Track t = current();
        boolean fav = t != null && Store.isFav(this, t.id);
        b.addCustomAction(new PlaybackState.CustomAction.Builder(CA_FAV,
                fav ? "Quitar de favoritas" : "Favorita",
                fav ? R.drawable.ic_heart : R.drawable.ic_heart_o).build());
        session.setPlaybackState(b.build());
    }

    private void goForeground() {
        try {
            Notification n = buildNotification();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NID, n);
            }
            fg = true;
        } catch (Exception e) {
            fg = false;
        }
    }

    private Notification buildNotification() {
        Track t = current();
        boolean pl = isPlaying();
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH)
                : new Notification.Builder(this);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        b.setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(t == null ? "Mi Música" : t.title)
                .setContentText(t == null ? "" : t.artist)
                .setContentIntent(open)
                .setDeleteIntent(act(A_STOP, 4))
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setOngoing(pl)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(android.R.drawable.ic_media_previous, "Anterior", act(A_PREV, 1))
                .addAction(pl ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        pl ? "Pausa" : "Reproducir", act(A_TOGGLE, 2))
                .addAction(android.R.drawable.ic_media_next, "Siguiente", act(A_NEXT, 3))
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        if (art != null) b.setLargeIcon(art);
        return b.build();
    }

    private PendingIntent act(String action, int code) {
        return PendingIntent.getService(this, code,
                new Intent(this, PlayerService.class).setAction(action),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
