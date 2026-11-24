package com.caleb.mimusica;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity implements ServiceConnection {

    static final int C_BG = 0xFF0F1115, C_PANEL = 0xFF171A21, C_BTN = 0xFF232834,
            C_TXT = 0xFFE8EAF0, C_MUT = 0xFF8A92A6, C_ACC = 0xFF4ADE80;

    final ArrayList<Track> all = new ArrayList<>();
    final ArrayList<Track> view = new ArrayList<>();
    final Handler h = new Handler(Looper.getMainLooper());

    PlayerService svc;
    BaseAdapter adapter;
    EditText q;
    TextView now, tCur, tDur, status;
    SeekBar seek;
    Button bPlay, bShuf, bRep;
    boolean dragging;

    final Runnable ticker = new Runnable() {
        @Override public void run() {
            tick();
            h.postDelayed(this, 500);
        }
    };

    int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    TextView tv(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    Button btn(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(18);
        b.setTextColor(C_TXT);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(0, 0, 0, 0);
        b.setBackgroundColor(C_BTN);
        return b;
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(C_BG);
        getWindow().setNavigationBarColor(C_PANEL);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        /* Cabecera: título, búsqueda, recargar */
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackgroundColor(C_PANEL);
        head.setPadding(dp(12), dp(8), dp(8), dp(8));
        TextView title = tv("🎵 Mi Música", 16, C_TXT);
        title.setTypeface(null, Typeface.BOLD);
        q = new EditText(this);
        q.setHint("Buscar…");
        q.setHintTextColor(C_MUT);
        q.setTextColor(C_TXT);
        q.setTextSize(14);
        q.setSingleLine(true);
        q.setBackgroundColor(C_BG);
        q.setPadding(dp(10), dp(8), dp(10), dp(8));
        Button bRefresh = btn("↻");
        head.addView(title);
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        qp.setMargins(dp(10), 0, dp(6), 0);
        head.addView(q, qp);
        head.addView(bRefresh, new LinearLayout.LayoutParams(dp(44), dp(40)));
        root.addView(head);

        /* Lista */
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setFastScrollEnabled(true);
        adapter = new BaseAdapter() {
            @Override public int getCount() { return view.size(); }
            @Override public Object getItem(int i) { return view.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup parent) {
                if (v == null) {
                    LinearLayout row = new LinearLayout(MainActivity.this);
                    row.setOrientation(LinearLayout.VERTICAL);
                    row.setPadding(dp(14), dp(9), dp(14), dp(9));
                    TextView a = tv("", 15, C_TXT);
                    TextView b = tv("", 12, C_MUT);
                    a.setSingleLine(true);
                    b.setSingleLine(true);
                    a.setEllipsize(TextUtils.TruncateAt.END);
                    b.setEllipsize(TextUtils.TruncateAt.END);
                    row.addView(a);
                    row.addView(b);
                    row.setTag(new TextView[]{a, b});
                    v = row;
                }
                TextView[] t = (TextView[]) v.getTag();
                Track tr = view.get(i);
                Track cur = svc == null ? null : svc.current();
                boolean on = cur != null && cur.id == tr.id;
                t[0].setText(tr.title);
                t[0].setTextColor(on ? C_ACC : C_TXT);
                t[1].setText(tr.artist);
                return v;
            }
        };
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        /* Panel inferior: reproductor */
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(C_PANEL);
        panel.setPadding(dp(12), dp(10), dp(12), dp(10));

        now = tv("Nada reproduciéndose", 15, C_TXT);
        now.setTypeface(null, Typeface.BOLD);
        now.setSingleLine(true);
        now.setEllipsize(TextUtils.TruncateAt.END);
        panel.addView(now);

        LinearLayout seekRow = new LinearLayout(this);
        seekRow.setOrientation(LinearLayout.HORIZONTAL);
        seekRow.setGravity(Gravity.CENTER_VERTICAL);
        tCur = tv("0:00", 12, C_MUT);
        tDur = tv("0:00", 12, C_MUT);
        seek = new SeekBar(this);
        seek.getProgressDrawable().setColorFilter(C_ACC, PorterDuff.Mode.SRC_IN);
        seek.getThumb().setColorFilter(C_ACC, PorterDuff.Mode.SRC_IN);
        seekRow.addView(tCur);
        seekRow.addView(seek, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        seekRow.addView(tDur);
        panel.addView(seekRow);

        LinearLayout ctl = new LinearLayout(this);
        ctl.setOrientation(LinearLayout.HORIZONTAL);
        bShuf = btn("🔀");
        Button bPrev = btn("⏮");
        bPlay = btn("▶");
        Button bNext = btn("⏭");
        bRep = btn("🔁");
        bPlay.setTextColor(0xFF06240F);
        bPlay.setBackgroundColor(C_ACC);
        for (Button b : new Button[]{bShuf, bPrev, bPlay, bNext, bRep}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1f);
            lp.setMargins(dp(3), dp(6), dp(3), dp(2));
            ctl.addView(b, lp);
        }
        panel.addView(ctl);

        status = tv("", 12, C_MUT);
        status.setGravity(Gravity.CENTER);
        panel.addView(status);
        root.addView(panel);

        setContentView(root);

        /* Eventos */
        list.setOnItemClickListener((p, v, pos, id) -> {
            if (svc == null) return;
            startSvc();
            svc.playQueue(new ArrayList<>(view), pos);
        });
        bPlay.setOnClickListener(v -> {
            if (svc == null) return;
            startSvc();
            if (svc.current() == null) {
                if (!view.isEmpty()) svc.playQueue(new ArrayList<>(view), 0);
            } else {
                svc.toggle();
            }
        });
        bNext.setOnClickListener(v -> { if (svc != null) svc.next(false); });
        bPrev.setOnClickListener(v -> { if (svc != null) svc.prev(); });
        bShuf.setOnClickListener(v -> { if (svc != null) { svc.shuffle = !svc.shuffle; refresh(); } });
        bRep.setOnClickListener(v -> { if (svc != null) { svc.repeat = (svc.repeat + 1) % 3; refresh(); } });
        bRefresh.setOnClickListener(v -> ask());
        q.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) { filter(); }
        });
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user) tCur.setText(fmt(p));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                if (svc != null) svc.seekTo(s.getProgress());
            }
        });

        bindService(new Intent(this, PlayerService.class), this, BIND_AUTO_CREATE);
        ask();
    }

    /* ---------- Servicio ---------- */

    @Override
    public void onServiceConnected(ComponentName n, IBinder b) {
        svc = ((PlayerService.LocalBinder) b).get();
        svc.onChange = this::refresh;
        refresh();
    }

    @Override
    public void onServiceDisconnected(ComponentName n) {
        svc = null;
    }

    void startSvc() {
        Intent i = new Intent(this, PlayerService.class).setAction(PlayerService.A_INIT);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } catch (Exception ignored) { }
    }

    /* ---------- Permisos y biblioteca ---------- */

    String audioPerm() {
        return Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    void ask() {
        if (checkSelfPermission(audioPerm()) == PackageManager.PERMISSION_GRANTED) {
            load();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{audioPerm(), Manifest.permission.POST_NOTIFICATIONS}, 1);
        } else {
            requestPermissions(new String[]{audioPerm()}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (checkSelfPermission(audioPerm()) == PackageManager.PERMISSION_GRANTED) load();
        else status.setText("Necesito permiso para leer tu música. Toca ↻ para pedirlo otra vez.");
    }

    void load() {
        status.setText("Leyendo tu música…");
        new Thread(() -> {
            ArrayList<Track> r = new ArrayList<>();
            String sel = "(" + MediaStore.Audio.Media.IS_MUSIC + "!=0 OR "
                    + MediaStore.Audio.Media.MIME_TYPE + " LIKE 'audio/mpeg') AND "
                    + MediaStore.Audio.Media.DURATION + ">=20000";
            try (Cursor c = getContentResolver().query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
                            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION},
                    sel, null, MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")) {
                if (c != null) {
                    while (c.moveToNext()) {
                        String ar = c.getString(2);
                        if (ar == null || ar.equals("<unknown>")) ar = "Artista desconocido";
                        r.add(new Track(c.getLong(0), c.getString(1), ar, c.getLong(3)));
                    }
                }
            } catch (Exception ignored) { }
            h.post(() -> {
                all.clear();
                all.addAll(r);
                filter();
                status.setText(r.isEmpty() ? "No encontré música en el teléfono" : r.size() + " canciones");
            });
        }).start();
    }

    void filter() {
        String s = q.getText().toString().trim().toLowerCase(Locale.ROOT);
        view.clear();
        for (Track t : all) {
            if (s.isEmpty() || t.title.toLowerCase(Locale.ROOT).contains(s)
                    || t.artist.toLowerCase(Locale.ROOT).contains(s)) {
                view.add(t);
            }
        }
        adapter.notifyDataSetChanged();
    }

    /* ---------- Pantalla ---------- */

    void refresh() {
        if (svc == null) return;
        Track t = svc.current();
        now.setText(t == null ? "Nada reproduciéndose" : t.title + " — " + t.artist);
        bPlay.setText(svc.isPlaying() ? "⏸" : "▶");
        bShuf.setTextColor(svc.shuffle ? C_ACC : C_TXT);
        bRep.setText(svc.repeat == 2 ? "🔂" : "🔁");
        bRep.setTextColor(svc.repeat > 0 ? C_ACC : C_TXT);
        adapter.notifyDataSetChanged();
        tick();
    }

    void tick() {
        if (svc == null || dragging) return;
        int d = svc.dur(), p = svc.pos();
        seek.setMax(Math.max(d, 1));
        seek.setProgress(p);
        tCur.setText(fmt(p));
        tDur.setText(fmt(d));
    }

    static String fmt(int ms) {
        int s = Math.max(ms, 0) / 1000;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        h.post(ticker);
    }

    @Override
    protected void onPause() {
        h.removeCallbacks(ticker);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (svc != null) svc.onChange = null;
        try { unbindService(this); } catch (Exception ignored) { }
        super.onDestroy();
    }
}
