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
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.Random;

/** Reproduce la música en segundo plano, con notificación y botones de la pantalla de bloqueo. */
public class PlayerService extends Service
        implements MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener,
        AudioManager.OnAudioFocusChangeListener {

    static final String CH = "mimusica";
    static final String A_INIT = "init", A_TOGGLE = "toggle", A_NEXT = "next", A_PREV = "prev", A_STOP = "stop";
    static final int NID = 7;

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

    private ArrayList<Track> queue = new ArrayList<>();
    private int idx = -1;
    private MediaPlayer mp;
    private boolean prepared, fg, resumeOnFocus;
    private int fails;
    private MediaSession session;
    private AudioManager am;
    private AudioFocusRequest focusReq;
    private NotificationManager nm;

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
        });
        session.setActive(true);
        registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
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
        if (!isPlaying()) stopAll();
    }

    @Override
    public void onDestroy() {
        try { unregisterReceiver(noisy); } catch (Exception ignored) { }
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

    void playQueue(ArrayList<Track> q, int pos) {
        queue = q;
        start(pos);
    }

    private void start(int i) {
        if (i < 0 || i >= queue.size()) return;
        idx = i;
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
                fails = 0;
                resumeOnFocus = false;
                if (!fg) goForeground();
            }
        } catch (Exception e) {
            prepared = false;
            if (++fails < 5 && queue.size() > 1) {
                h.post(() -> next(true));
                return;
            }
        }
        sessionMeta();
        afterChange();
    }

    void toggle() {
        if (mp == null || !prepared) {
            if (!queue.isEmpty()) start(idx < 0 ? 0 : idx);
            return;
        }
        if (isPlaying()) pause(); else resume();
    }

    private void resume() {
        if (mp == null || !prepared || !requestFocus()) return;
        mp.start();
        if (!fg) goForeground();
        afterChange();
    }

    void pause() {
        if (mp == null || !prepared) return;
        try { if (mp.isPlaying()) mp.pause(); } catch (Exception ignored) { }
        if (fg) {
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
            sessionState();
        }
    }

    private void stopAll() {
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
                    pause();
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
        if (idx >= 0) nm.notify(NID, buildNotification());
        if (onChange != null) onChange.run();
    }

    private void sessionMeta() {
        Track t = current();
        if (t == null) return;
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, dur())
                .build());
    }

    private void sessionState() {
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO)
                .setState(isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        pos(), 1f)
                .build());
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
                .addAction(android.R.drawable.ic_media_previous, "Anterior", act(A_PREV, 1))
                .addAction(pl ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        pl ? "Pausa" : "Reproducir", act(A_TOGGLE, 2))
                .addAction(android.R.drawable.ic_media_next, "Siguiente", act(A_NEXT, 3))
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }

    private PendingIntent act(String action, int code) {
        return PendingIntent.getService(this, code,
                new Intent(this, PlayerService.class).setAction(action),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
