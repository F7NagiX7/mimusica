package com.caleb.mimusica;

import android.Manifest;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements ServiceConnection {

    static final int C_BG = 0xFF0C0C0E, C_PANEL = 0xFF17181C, C_CARD = 0xFF1C1D22, C_CHIP = 0xFF1F2025,
            C_TXT = 0xFFFFFFFF, C_MUT = 0xFF9A9CA5, C_ACC = 0xFF1ED760;
    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    /** Un álbum o un artista con sus canciones. */
    static final class Group {
        final String name, sub;
        final ArrayList<Track> tracks = new ArrayList<>();

        Group(String name, String sub) {
            this.name = name;
            this.sub = sub;
        }
    }

    /** Imagen cuadrada: el alto siempre es igual al ancho. */
    static final class Sq extends ImageView {
        Sq(Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, w);
        }
    }

    final ArrayList<Track> all = new ArrayList<>(), searchRes = new ArrayList<>(), favs = new ArrayList<>();
    final HashMap<Long, Track> byId = new HashMap<>();
    final ArrayList<Group> albums = new ArrayList<>(), artists = new ArrayList<>();
    final ArrayList<Track> raw = new ArrayList<>();
    final LinkedHashMap<String, Integer> folderCount = new LinkedHashMap<>();
    final ExecutorService lyrEx = Executors.newSingleThreadExecutor();
    Lyrics.Doc lyr;
    long lyrFor = -1;
    int lyrIdx = -2, lyrCur = -1;
    final Handler h = new Handler(Looper.getMainLooper());
    final TextView[] chips = new TextView[4];
    final ImageView[] navIcon = new ImageView[3];
    final TextView[] navLbl = new TextView[3];

    PlayerService svc;
    String emptyText = "Leyendo tu música…";
    boolean dragging;
    int page, tab;
    static final int DEF_TINT = 0xFF1F3A33;
    int acc = C_ACC, tint = DEF_TINT;
    long themeFor = -1;
    Fade winFade, playerFade, lyrFade;
    GradientDrawable playBg;
    ValueAnimator tintAnim;
    boolean lastPl;
    final LinearInterpolator lin = new LinearInterpolator();

    FrameLayout root, pages, content;
    LinearLayout pageHome, pageSearch, pageLib, homeBox, mini, nav;
    ScrollView homeScroll, player;
    ListView musicList, searchList, libList;
    GridView albumGrid, artistGrid;
    TrackAdapter musicAdapter, searchAdapter, libAdapter;
    GroupAdapter albumAdapter, artistAdapter;
    TextView greetTv, libInfo, libEmpty;
    EditText q;

    ImageView miniArt, pArt, nArt;
    TextView miniTitle, miniSub, pSource, pTitle, pArtist, pCur, pDur, nTitle, nArtist;
    ImageButton miniPlay, pPlay, pShuf, pRep, pHeart;
    View miniFill, miniRest;
    LinearLayout nextCard, lyricsCard, lyrBox;
    ScrollView lyrScroll;
    TextView lyrBody, lyrFullTv, lyrTitle;
    SeekBar pSeek;

    final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            tick();
            h.postDelayed(this, 300);
        }
    };

    /* ====================== Utilidades de interfaz ====================== */

    int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    TextView tv(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    TextView one(TextView t) {
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    ImageButton ib(int res, int tint) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(res);
        b.setColorFilter(tint);
        b.setBackgroundColor(0x00000000);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        b.setPadding(dp(10), dp(10), dp(10), dp(10));
        return b;
    }

    GradientDrawable rect(int color, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r);
        return g;
    }

    /** Aparece con un fundido y un leve deslizamiento hacia arriba. */
    void fadeIn(View v, long delay) {
        v.animate().cancel();
        v.setAlpha(0f);
        v.setTranslationY(dp(12));
        v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(260)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    /** Un pulso corto para confirmar un toque (play, corazón, aleatorio, repetir). */
    void pop(final View v) {
        v.animate().cancel();
        v.animate().scaleX(0.82f).scaleY(0.82f).setStartDelay(0).setDuration(70)
                .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(160)
                        .setInterpolator(new DecelerateInterpolator()).start()).start();
    }

    /** "Vidrio" barato: blanco muy transparente con un borde fino. No desenfoca nada. */
    GradientDrawable glass(int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x1AFFFFFF);
        g.setCornerRadius(r);
        g.setStroke(dp(1), 0x2EFFFFFF);
        return g;
    }

    /** Al tocar, la vista se encoge un poquito (solo transformación: casi no cuesta). */
    void press(View v) {
        v.setOnTouchListener((vw, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                vw.animate().scaleX(0.96f).scaleY(0.96f).setStartDelay(0).setDuration(90).start();
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                vw.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(140).start();
            }
            return false;
        });
    }

    /** base = color más vivo de la portada (0 si no hay). Cambia degradado y acento. */
    void applyTheme(int base) {
        int nt = DEF_TINT, na = C_ACC;
        if (base != 0) {
            float[] hsv = new float[3];
            Color.colorToHSV(base, hsv);
            if (hsv[1] >= 0.12f) {
                float hue = hsv[0], sat = hsv[1];
                hsv[1] = Math.min(sat, 0.75f);
                hsv[2] = 0.42f;
                nt = Color.HSVToColor(hsv);
                hsv[0] = hue;
                hsv[1] = Math.max(0.45f, Math.min(sat, 0.85f));
                hsv[2] = 0.95f;
                na = Color.HSVToColor(hsv);
            } else {
                nt = 0xFF2B2D33;
            }
        }
        acc = na;
        if (tintAnim != null) tintAnim.cancel();
        tintAnim = ValueAnimator.ofObject(new ArgbEvaluator(), tint, nt);
        tintAnim.setDuration(380);
        tintAnim.addUpdateListener(an -> {
            tint = (Integer) an.getAnimatedValue();
            winFade.setTop(tint);
            playerFade.setTop(tint);
            lyrFade.setTop(tint);
        });
        tintAnim.start();
        applyAccent();
    }

    void applyAccent() {
        if (playBg == null) return;
        playBg.setColor(acc);
        miniFill.setBackgroundColor(acc);
        pSeek.setProgressTintList(ColorStateList.valueOf(acc));
        if (svc != null) {
            Track t = svc.current();
            if (t != null) refreshHeart(t);
            pShuf.setColorFilter(svc.shuffle ? acc : C_MUT);
            pRep.setColorFilter(svc.repeat > 0 ? acc : C_MUT);
        }
        musicAdapter.notifyDataSetChanged();
        searchAdapter.notifyDataSetChanged();
        libAdapter.notifyDataSetChanged();
    }

    void round(View v, final int r) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View vw, Outline o) {
                o.setRoundRect(0, 0, vw.getWidth(), vw.getHeight(), r);
            }
        });
        v.setClipToOutline(true);
    }

    void makeCircle(View v) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View vw, Outline o) {
                o.setOval(0, 0, vw.getWidth(), vw.getHeight());
            }
        });
        v.setClipToOutline(true);
    }

    ListView listView(BaseAdapter a) {
        ListView l = new ListView(this);
        l.setDivider(null);
        l.setSelector(android.R.color.transparent);
        l.setAdapter(a);
        l.setFastScrollEnabled(true);
        return l;
    }

    GridView grid(BaseAdapter a, int cols) {
        GridView g = new GridView(this);
        g.setNumColumns(cols);
        g.setHorizontalSpacing(dp(12));
        g.setVerticalSpacing(dp(16));
        g.setPadding(dp(16), dp(8), dp(16), dp(8));
        g.setClipToPadding(false);
        g.setSelector(android.R.color.transparent);
        g.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        g.setAdapter(a);
        return g;
    }

    static String fmt(int ms) {
        int s = Math.max(ms, 0) / 1000;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    String greeting() {
        int hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hr >= 6 && hr < 12) return "Buenos días";
        if (hr >= 12 && hr < 20) return "Buenas tardes";
        return "Buenas noches";
    }

    long dayKey() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.YEAR) * 1000L + c.get(Calendar.DAY_OF_YEAR);
    }

    /* ====================== Adaptadores ====================== */

    class TrackAdapter extends BaseAdapter {
        final ArrayList<Track> d;

        TrackAdapter(ArrayList<Track> d) {
            this.d = d;
        }

        @Override public int getCount() { return d.size(); }
        @Override public Object getItem(int i) { return d.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View v, ViewGroup parent) {
            if (v == null) {
                LinearLayout r = new LinearLayout(MainActivity.this);
                r.setGravity(Gravity.CENTER_VERTICAL);
                r.setPadding(dp(16), dp(7), dp(16), dp(7));
                ImageView iv = new ImageView(MainActivity.this);
                round(iv, dp(8));
                r.addView(iv, new LinearLayout.LayoutParams(dp(50), dp(50)));
                LinearLayout col = new LinearLayout(MainActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                TextView a = one(tv("", 15, C_TXT, false));
                TextView b = one(tv("", 12, C_MUT, false));
                col.addView(a);
                col.addView(b);
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, WRAP, 1f);
                cp.leftMargin = dp(12);
                cp.rightMargin = dp(8);
                r.addView(col, cp);
                TextView du = tv("", 12, C_MUT, false);
                r.addView(du);
                r.setTag(new View[]{iv, a, b, du});
                v = r;
            }
            View[] w = (View[]) v.getTag();
            Track t = d.get(i);
            Track cur = svc == null ? null : svc.current();
            Art.bind(MainActivity.this, (ImageView) w[0], t, 128);
            ((TextView) w[1]).setText(t.title);
            ((TextView) w[1]).setTextColor(cur != null && cur.id == t.id ? acc : C_TXT);
            ((TextView) w[2]).setText(t.album.isEmpty() ? t.artist : t.artist + " · " + t.album);
            ((TextView) w[3]).setText(fmt((int) t.duration));
            return v;
        }
    }

    class GroupAdapter extends BaseAdapter {
        final ArrayList<Group> d;
        final boolean circular;

        GroupAdapter(ArrayList<Group> d, boolean circular) {
            this.d = d;
            this.circular = circular;
        }

        @Override public int getCount() { return d.size(); }
        @Override public Object getItem(int i) { return d.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View v, ViewGroup parent) {
            if (v == null) {
                LinearLayout c = new LinearLayout(MainActivity.this);
                c.setOrientation(LinearLayout.VERTICAL);
                Sq iv = new Sq(MainActivity.this);
                if (circular) makeCircle(iv);
                else round(iv, dp(14));
                c.addView(iv, new LinearLayout.LayoutParams(MATCH, WRAP));
                TextView a = one(tv("", 13, C_TXT, true));
                TextView b = one(tv("", 11, C_MUT, false));
                if (circular) {
                    a.setGravity(Gravity.CENTER);
                    b.setGravity(Gravity.CENTER);
                }
                LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(MATCH, WRAP);
                ap.topMargin = dp(6);
                c.addView(a, ap);
                c.addView(b, new LinearLayout.LayoutParams(MATCH, WRAP));
                c.setTag(new View[]{iv, a, b});
                v = c;
            }
            View[] w = (View[]) v.getTag();
            Group g = d.get(i);
            Art.bind(MainActivity.this, (ImageView) w[0], g.tracks.get(0), 320);
            ((TextView) w[1]).setText(g.name);
            int n = g.tracks.size();
            ((TextView) w[2]).setText(circular ? n + (n == 1 ? " canción" : " canciones") : g.sub);
            return v;
        }
    }

    /* ====================== Construcción de pantallas ====================== */

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        winFade = new Fade(tint, C_BG, 0.6f);
        playerFade = new Fade(tint, C_BG, 0.65f);
        lyrFade = new Fade(tint, C_BG, 1f);
        getWindow().setBackgroundDrawable(winFade);
        getWindow().setStatusBarColor(0x00000000);
        getWindow().setNavigationBarColor(C_BG);

        root = new FrameLayout(this);

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        pages = new FrameLayout(this);
        buildHome();
        buildSearch();
        buildLib();
        pages.addView(pageHome, new FrameLayout.LayoutParams(MATCH, MATCH));
        pages.addView(pageSearch, new FrameLayout.LayoutParams(MATCH, MATCH));
        pages.addView(pageLib, new FrameLayout.LayoutParams(MATCH, MATCH));
        main.addView(pages, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        buildMini();
        main.addView(mini);
        buildNav();
        LinearLayout.LayoutParams navp = new LinearLayout.LayoutParams(MATCH, WRAP);
        navp.setMargins(dp(12), dp(4), dp(12), dp(8));
        main.addView(nav, navp);
        root.addView(main, new FrameLayout.LayoutParams(MATCH, MATCH));

        buildPlayer();
        root.addView(player, new FrameLayout.LayoutParams(MATCH, MATCH));
        player.setVisibility(View.GONE);
        buildLyricsFull();
        root.addView(lyrBox, new FrameLayout.LayoutParams(MATCH, MATCH));

        setContentView(root);
        showPage(0);
        showTab(0);

        bindService(new Intent(this, PlayerService.class), this, BIND_AUTO_CREATE);
        ask();
    }

    void buildHome() {
        pageHome = new LinearLayout(this);
        pageHome.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), dp(10), dp(6), 0);
        LinearLayout tb = new LinearLayout(this);
        tb.setOrientation(LinearLayout.VERTICAL);
        tb.addView(tv("Mi Música", 20, C_TXT, true));
        tb.addView(tv("Tu música, sin internet", 12, C_MUT, false));
        head.addView(tb, new LinearLayout.LayoutParams(0, WRAP, 1f));
        ImageButton bRef = ib(R.drawable.ic_refresh, C_TXT);
        bRef.setOnClickListener(v -> ask());
        ImageButton bSearch = ib(R.drawable.ic_search, C_TXT);
        bSearch.setOnClickListener(v -> showPage(1));
        ImageButton bFolder = ib(R.drawable.ic_folder, C_TXT);
        bFolder.setOnClickListener(v -> pickFolders());
        head.addView(bFolder, new LinearLayout.LayoutParams(dp(44), dp(44)));
        head.addView(bRef, new LinearLayout.LayoutParams(dp(44), dp(44)));
        head.addView(bSearch, new LinearLayout.LayoutParams(dp(44), dp(44)));
        pageHome.addView(head);

        greetTv = tv(greeting(), 30, C_TXT, true);
        greetTv.setPadding(dp(16), dp(14), dp(16), 0);
        pageHome.addView(greetTv);
        TextView sub = tv("Que la música te acompañe hoy.", 14, C_MUT, false);
        sub.setPadding(dp(16), dp(2), dp(16), dp(12));
        pageHome.addView(sub);

        HorizontalScrollView cs = new HorizontalScrollView(this);
        cs.setHorizontalScrollBarEnabled(false);
        LinearLayout cr = new LinearLayout(this);
        cr.setPadding(dp(16), 0, dp(8), dp(6));
        String[] names = {"Inicio", "Música", "Álbumes", "Artistas"};
        for (int i = 0; i < 4; i++) {
            final int ti = i;
            TextView c = tv(names[i], 14, C_TXT, false);
            c.setPadding(dp(18), dp(9), dp(18), dp(9));
            c.setOnClickListener(v -> showTab(ti));
            press(c);
            chips[i] = c;
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(WRAP, WRAP);
            cp.rightMargin = dp(8);
            cr.addView(c, cp);
        }
        cs.addView(cr);
        pageHome.addView(cs);

        content = new FrameLayout(this);
        homeScroll = new ScrollView(this);
        homeScroll.setVerticalScrollBarEnabled(false);
        homeBox = new LinearLayout(this);
        homeBox.setOrientation(LinearLayout.VERTICAL);
        homeBox.setPadding(0, 0, 0, dp(16));
        homeScroll.addView(homeBox);

        musicAdapter = new TrackAdapter(all);
        musicList = listView(musicAdapter);
        musicList.setOnItemClickListener((p, v, pos, id) -> playList(all, pos, "Mi música"));
        albumAdapter = new GroupAdapter(albums, false);
        albumGrid = grid(albumAdapter, 2);
        albumGrid.setOnItemClickListener((p, v, pos, id) -> playGroup(albums.get(pos)));
        artistAdapter = new GroupAdapter(artists, true);
        artistGrid = grid(artistAdapter, 3);
        artistGrid.setOnItemClickListener((p, v, pos, id) -> playGroup(artists.get(pos)));

        content.addView(homeScroll, new FrameLayout.LayoutParams(MATCH, MATCH));
        content.addView(musicList, new FrameLayout.LayoutParams(MATCH, MATCH));
        content.addView(albumGrid, new FrameLayout.LayoutParams(MATCH, MATCH));
        content.addView(artistGrid, new FrameLayout.LayoutParams(MATCH, MATCH));
        pageHome.addView(content, new LinearLayout.LayoutParams(MATCH, 0, 1f));
    }

    void buildSearch() {
        pageSearch = new LinearLayout(this);
        pageSearch.setOrientation(LinearLayout.VERTICAL);
        TextView t = tv("Buscar", 30, C_TXT, true);
        t.setPadding(dp(16), dp(16), dp(16), dp(10));
        pageSearch.addView(t);
        q = new EditText(this);
        q.setHint("¿Qué quieres escuchar?");
        q.setHintTextColor(C_MUT);
        q.setTextColor(C_TXT);
        q.setTextSize(15);
        q.setSingleLine(true);
        q.setBackground(glass(dp(26)));
        q.setPadding(dp(18), dp(12), dp(18), dp(12));
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(MATCH, WRAP);
        qp.setMargins(dp(16), 0, dp(16), dp(8));
        pageSearch.addView(q, qp);
        searchAdapter = new TrackAdapter(searchRes);
        searchList = listView(searchAdapter);
        searchList.setOnItemClickListener((p, v, pos, id) -> playList(searchRes, pos, "Búsqueda"));
        pageSearch.addView(searchList, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        q.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) { filterSearch(); }
        });
    }

    void buildLib() {
        pageLib = new LinearLayout(this);
        pageLib.setOrientation(LinearLayout.VERTICAL);
        TextView t = tv("Tu biblioteca", 30, C_TXT, true);
        t.setPadding(dp(16), dp(16), dp(16), 0);
        pageLib.addView(t);
        libInfo = tv("", 13, C_MUT, false);
        libInfo.setPadding(dp(16), dp(2), dp(16), dp(10));
        pageLib.addView(libInfo);
        libEmpty = tv("Todavía no tienes favoritas.\nToca el corazón en el reproductor para guardar una canción aquí.", 14, C_MUT, false);
        libEmpty.setGravity(Gravity.CENTER);
        libEmpty.setPadding(dp(32), dp(48), dp(32), dp(16));
        pageLib.addView(libEmpty);
        libAdapter = new TrackAdapter(favs);
        libList = listView(libAdapter);
        libList.setOnItemClickListener((p, v, pos, id) -> playList(favs, pos, "Tus favoritas"));
        pageLib.addView(libList, new LinearLayout.LayoutParams(MATCH, 0, 1f));
    }

    void buildMini() {
        mini = new LinearLayout(this);
        mini.setOrientation(LinearLayout.VERTICAL);
        mini.setPadding(dp(12), dp(4), dp(12), dp(4));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(glass(dp(18)));
        round(card, dp(18));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(8), dp(4), dp(6));
        miniArt = new ImageView(this);
        round(miniArt, dp(8));
        row.addView(miniArt, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout tc = new LinearLayout(this);
        tc.setOrientation(LinearLayout.VERTICAL);
        miniTitle = one(tv("", 14, C_TXT, true));
        miniSub = one(tv("", 12, C_MUT, false));
        tc.addView(miniTitle);
        tc.addView(miniSub);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        tp.leftMargin = dp(10);
        row.addView(tc, tp);
        miniPlay = ib(R.drawable.ic_play, C_TXT);
        ImageButton mn = ib(R.drawable.ic_next, C_TXT);
        row.addView(miniPlay, new LinearLayout.LayoutParams(dp(44), dp(44)));
        row.addView(mn, new LinearLayout.LayoutParams(dp(44), dp(44)));
        card.addView(row);
        FrameLayout bar = new FrameLayout(this);
        miniRest = new View(this);
        miniRest.setBackgroundColor(0x33FFFFFF);
        miniFill = new View(this);
        miniFill.setBackgroundColor(acc);
        miniFill.setPivotX(0f);
        miniFill.setScaleX(0f);
        bar.addView(miniRest, new FrameLayout.LayoutParams(MATCH, MATCH));
        bar.addView(miniFill, new FrameLayout.LayoutParams(MATCH, MATCH));
        card.addView(bar, new LinearLayout.LayoutParams(MATCH, dp(3)));
        mini.addView(card);
        mini.setVisibility(View.GONE);
        card.setOnClickListener(v -> openPlayer());
        press(card);
        miniPlay.setOnClickListener(v -> toggle());
        press(miniPlay);
        mn.setOnClickListener(v -> {
            if (svc != null) svc.next(false);
        });
    }

    void buildNav() {
        nav = new LinearLayout(this);
        nav.setBackground(glass(dp(28)));
        int[] ic = {R.drawable.ic_home, R.drawable.ic_search, R.drawable.ic_library};
        String[] lb = {"Inicio", "Buscar", "Tu biblioteca"};
        for (int i = 0; i < 3; i++) {
            final int pi = i;
            LinearLayout it = new LinearLayout(this);
            it.setOrientation(LinearLayout.VERTICAL);
            it.setGravity(Gravity.CENTER);
            it.setPadding(0, dp(8), 0, dp(8));
            ImageView ivn = new ImageView(this);
            ivn.setImageResource(ic[i]);
            navIcon[i] = ivn;
            navLbl[i] = tv(lb[i], 11, C_MUT, false);
            navLbl[i].setGravity(Gravity.CENTER);
            it.addView(ivn, new LinearLayout.LayoutParams(dp(24), dp(24)));
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(MATCH, WRAP);
            nlp.topMargin = dp(2);
            it.addView(navLbl[i], nlp);
            it.setOnClickListener(v -> showPage(pi));
            press(it);
            nav.addView(it, new LinearLayout.LayoutParams(0, WRAP, 1f));
        }
    }

    void buildPlayer() {
        player = new ScrollView(this);
        player.setBackground(playerFade);
        player.setFillViewport(true);
        player.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(8), dp(20), dp(16));

        /* barra superior */
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton down = ib(R.drawable.ic_down, C_TXT);
        down.setOnClickListener(v -> closePlayer());
        top.addView(down, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView fromLbl = tv("Reproduciendo desde", 11, C_MUT, false);
        fromLbl.setGravity(Gravity.CENTER);
        mid.addView(fromLbl, new LinearLayout.LayoutParams(MATCH, WRAP));
        pSource = one(tv("", 13, C_TXT, true));
        pSource.setGravity(Gravity.CENTER);
        mid.addView(pSource, new LinearLayout.LayoutParams(MATCH, WRAP));
        top.addView(mid, new LinearLayout.LayoutParams(0, WRAP, 1f));
        top.addView(new View(this), new LinearLayout.LayoutParams(dp(44), dp(44)));
        col.addView(top);

        /* portada */
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int side = Math.min(dm.widthPixels - dp(40), (int) (dm.heightPixels * 0.40f));
        pArt = new ImageView(this);
        round(pArt, dp(20));
        pArt.setElevation(dp(14));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(side, side);
        ap.gravity = Gravity.CENTER_HORIZONTAL;
        ap.topMargin = dp(14);
        col.addView(pArt, ap);

        /* título, artista y corazón */
        LinearLayout tr = new LinearLayout(this);
        tr.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout tc = new LinearLayout(this);
        tc.setOrientation(LinearLayout.VERTICAL);
        pTitle = one(tv("", 26, C_TXT, true));
        pArtist = one(tv("", 16, C_MUT, false));
        tc.addView(pTitle);
        tc.addView(pArtist);
        tr.addView(tc, new LinearLayout.LayoutParams(0, WRAP, 1f));
        pHeart = ib(R.drawable.ic_heart_o, C_TXT);
        pHeart.setOnClickListener(v -> {
            Track t = svc == null ? null : svc.current();
            if (t == null) return;
            Store.toggleFav(this, t.id);
            refreshHeart(t);
            pop(pHeart);
            svc.syncSession();
            if (page == 2) loadFavs();
        });
        tr.addView(pHeart, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout.LayoutParams trp = new LinearLayout.LayoutParams(MATCH, WRAP);
        trp.topMargin = dp(18);
        col.addView(tr, trp);

        /* barra de tiempo */
        pSeek = new SeekBar(this);
        pSeek.setProgressTintList(ColorStateList.valueOf(acc));
        pSeek.setThumbTintList(ColorStateList.valueOf(C_TXT));
        pSeek.setProgressBackgroundTintList(ColorStateList.valueOf(0x33FFFFFF));
        col.addView(pSeek, new LinearLayout.LayoutParams(MATCH, WRAP));
        LinearLayout times = new LinearLayout(this);
        times.setPadding(dp(16), 0, dp(16), 0);
        pCur = tv("0:00", 12, C_MUT, false);
        pDur = tv("0:00", 12, C_MUT, false);
        times.addView(pCur);
        times.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        times.addView(pDur);
        col.addView(times);

        /* controles */
        LinearLayout ctl = new LinearLayout(this);
        ctl.setGravity(Gravity.CENTER_VERTICAL);
        pShuf = ib(R.drawable.ic_shuffle, C_MUT);
        ImageButton prev = ib(R.drawable.ic_prev, C_TXT);
        pPlay = ib(R.drawable.ic_play, C_TXT);
        ImageButton next = ib(R.drawable.ic_next, C_TXT);
        pRep = ib(R.drawable.ic_repeat, C_MUT);
        playBg = new GradientDrawable();
        playBg.setShape(GradientDrawable.OVAL);
        playBg.setColor(acc);
        pPlay.setBackground(playBg);
        pPlay.setColorFilter(0xFF111111);
        pPlay.setPadding(dp(17), dp(17), dp(17), dp(17));
        ctl.setBackground(glass(dp(40)));
        ctl.setPadding(dp(6), dp(8), dp(6), dp(8));
        ctl.addView(pShuf, new LinearLayout.LayoutParams(0, dp(52), 1f));
        ctl.addView(prev, new LinearLayout.LayoutParams(0, dp(56), 1f));
        ctl.addView(pPlay, new LinearLayout.LayoutParams(dp(66), dp(66)));
        ctl.addView(next, new LinearLayout.LayoutParams(0, dp(56), 1f));
        ctl.addView(pRep, new LinearLayout.LayoutParams(0, dp(52), 1f));
        LinearLayout.LayoutParams cpp = new LinearLayout.LayoutParams(MATCH, WRAP);
        cpp.topMargin = dp(14);
        col.addView(ctl, cpp);

        /* letra */
        lyricsCard = new LinearLayout(this);
        lyricsCard.setOrientation(LinearLayout.VERTICAL);
        lyricsCard.setBackground(glass(dp(22)));
        lyricsCard.setPadding(dp(18), dp(12), dp(10), dp(18));
        LinearLayout lh = new LinearLayout(this);
        lh.setGravity(Gravity.CENTER_VERTICAL);
        lh.addView(tv("Letra", 13, C_TXT, true), new LinearLayout.LayoutParams(0, WRAP, 1f));
        ImageButton lx = ib(R.drawable.ic_expand, C_MUT);
        lh.addView(lx, new LinearLayout.LayoutParams(dp(40), dp(40)));
        lyricsCard.addView(lh);
        lyrBody = tv("", 19, 0x66FFFFFF, true);
        lyrBody.setLineSpacing(dp(5), 1f);
        lyrBody.setMaxLines(6);
        lyrBody.setEllipsize(TextUtils.TruncateAt.END);
        lyrBody.setPadding(0, 0, dp(8), 0);
        lyricsCard.addView(lyrBody);
        lyricsCard.setVisibility(View.GONE);
        lyricsCard.setOnClickListener(v -> openLyrics());
        lx.setOnClickListener(v -> openLyrics());
        LinearLayout.LayoutParams lcp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lcp.topMargin = dp(18);
        col.addView(lyricsCard, lcp);

        /* a continuación */
        nextCard = new LinearLayout(this);
        nextCard.setOrientation(LinearLayout.VERTICAL);
        nextCard.setBackground(glass(dp(22)));
        nextCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        nextCard.addView(tv("A continuación", 12, C_MUT, true));
        LinearLayout nr = new LinearLayout(this);
        nr.setGravity(Gravity.CENTER_VERTICAL);
        nArt = new ImageView(this);
        round(nArt, dp(8));
        nr.addView(nArt, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout nc = new LinearLayout(this);
        nc.setOrientation(LinearLayout.VERTICAL);
        nTitle = one(tv("", 14, C_TXT, true));
        nArtist = one(tv("", 12, C_MUT, false));
        nc.addView(nTitle);
        nc.addView(nArtist);
        LinearLayout.LayoutParams ncp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        ncp.leftMargin = dp(12);
        nr.addView(nc, ncp);
        LinearLayout.LayoutParams nrp = new LinearLayout.LayoutParams(MATCH, WRAP);
        nrp.topMargin = dp(8);
        nextCard.addView(nr, nrp);
        LinearLayout.LayoutParams ncd = new LinearLayout.LayoutParams(MATCH, WRAP);
        ncd.topMargin = dp(18);
        col.addView(nextCard, ncd);

        player.addView(col);

        pPlay.setOnClickListener(v -> toggle());
        press(pPlay);
        next.setOnClickListener(v -> {
            if (svc != null) svc.next(false);
        });
        prev.setOnClickListener(v -> {
            if (svc != null) svc.prev();
        });
        pShuf.setOnClickListener(v -> {
            if (svc != null) {
                svc.toggleShuffle();
                pop(pShuf);
            }
        });
        pRep.setOnClickListener(v -> {
            if (svc != null) {
                svc.cycleRepeat();
                pop(pRep);
            }
        });
        pSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user) pCur.setText(fmt(p));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                if (svc != null) svc.seekTo(s.getProgress());
            }
        });
    }

    /* ====================== Letra ====================== */

    void buildLyricsFull() {
        lyrBox = new LinearLayout(this);
        lyrBox.setOrientation(LinearLayout.VERTICAL);
        lyrBox.setBackground(lyrFade);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(8), dp(8), dp(4));
        ImageButton close = ib(R.drawable.ic_down, C_TXT);
        close.setOnClickListener(v -> closeLyrics());
        top.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
        lyrTitle = one(tv("Letra", 15, C_TXT, true));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        tp.leftMargin = dp(8);
        top.addView(lyrTitle, tp);
        lyrBox.addView(top);
        lyrScroll = new ScrollView(this);
        lyrScroll.setVerticalScrollBarEnabled(false);
        lyrFullTv = tv("", 28, 0x59FFFFFF, true);
        lyrFullTv.setLineSpacing(dp(10), 1f);
        lyrFullTv.setPadding(dp(24), dp(16), dp(24), dp(120));
        lyrScroll.addView(lyrFullTv);
        lyrBox.addView(lyrScroll, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        lyrBox.setVisibility(View.GONE);
    }

    boolean lyricsOpen() {
        return lyrBox.getVisibility() == View.VISIBLE;
    }

    void openLyrics() {
        if (lyr == null) return;
        Track t = svc == null ? null : svc.current();
        lyrTitle.setText(t == null ? "Letra" : t.title + " — " + t.artist);
        lyrBox.setVisibility(View.VISIBLE);
        lyrBox.setTranslationY(root.getHeight());
        lyrBox.animate().cancel();
        lyrBox.animate().translationY(0).setStartDelay(0).setDuration(300)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        renderLyrics(true);
    }

    void closeLyrics() {
        lyrBox.animate().translationY(root.getHeight()).setStartDelay(0).setDuration(240)
                .setInterpolator(new AccelerateInterpolator(1.2f))
                .withEndAction(() -> lyrBox.setVisibility(View.GONE)).start();
    }

    /** Texto de la letra; la línea que suena va en blanco y negrita. */
    CharSequence lyrText(int idx, boolean full) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int n = lyr.lines.length;
        int from = 0, to = n;
        if (!full && lyr.times != null) {
            from = Math.max(0, idx - 1);
            to = Math.min(n, from + 6);
        }
        lyrCur = -1;
        for (int i = from; i < to; i++) {
            int st = sb.length();
            sb.append(lyr.lines[i]);
            if (i == idx) {
                sb.setSpan(new ForegroundColorSpan(C_TXT), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new StyleSpan(Typeface.BOLD), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                lyrCur = st;
            }
            if (i < to - 1) sb.append('\n');
        }
        return sb;
    }

    void renderLyrics(boolean force) {
        if (lyr == null || svc == null) return;
        int idx = lyr.times == null ? -1 : lyr.indexAt(svc.pos());
        if (!force && idx == lyrIdx) return;
        lyrIdx = idx;
        int base = lyr.times == null ? 0xF2FFFFFF : 0x66FFFFFF;
        lyrBody.setTextColor(base);
        lyrBody.setText(lyrText(idx, false));
        if (lyricsOpen()) {
            lyrFullTv.setTextColor(lyr.times == null ? 0xFFFFFFFF : 0x59FFFFFF);
            lyrFullTv.setText(lyrText(idx, true));
            final int off = lyrCur;
            if (off >= 0) {
                lyrFullTv.post(() -> {
                    Layout lay = lyrFullTv.getLayout();
                    if (lay == null) return;
                    int y = lay.getLineTop(lay.getLineForOffset(off));
                    lyrScroll.smoothScrollTo(0, Math.max(0, y - lyrScroll.getHeight() / 3));
                });
            }
        }
    }

    /** Busca la letra dentro del archivo de la canción (en segundo plano). */
    void loadLyrics(final Track t) {
        if (t.id == lyrFor) return;
        lyrFor = t.id;
        lyr = null;
        lyrIdx = -2;
        lyricsCard.setVisibility(View.GONE);
        if (lyricsOpen()) closeLyrics();
        lyrEx.execute(() -> {
            Lyrics.Doc d = null;
            try (java.io.InputStream in = getContentResolver().openInputStream(t.uri())) {
                if (in != null) {
                    String txt = Lyrics.read(in);
                    if (txt != null) d = Lyrics.parse(txt);
                }
            } catch (Exception ignored) { }
            final Lyrics.Doc fd = d;
            h.post(() -> {
                if (lyrFor != t.id) return;
                lyr = fd;
                if (fd == null) return;
                lyricsCard.setVisibility(View.VISIBLE);
                renderLyrics(true);
            });
        });
    }

    /* ====================== Carpetas ====================== */

    static String folderOf(String path) {
        int i = path == null ? -1 : path.lastIndexOf('/');
        return i > 0 ? path.substring(0, i) : "";
    }

    static String folderLabel(String dir) {
        String s = dir.replaceFirst("^/storage/emulated/\\d+/?", "");
        if (s.equals(dir)) s = dir.replaceFirst("^/storage/([^/]+)/?", "SD · ");
        return s.isEmpty() ? "Almacenamiento interno (raíz)" : s;
    }

    void pickFolders() {
        if (folderCount.isEmpty()) return;
        final ArrayList<String> paths = new ArrayList<>(folderCount.keySet());
        Collections.sort(paths, (a, b) -> folderLabel(a).compareToIgnoreCase(folderLabel(b)));
        HashMap<String, Boolean> on = Store.folderStates(this, paths);
        String[] labels = new String[paths.size()];
        final boolean[] checked = new boolean[paths.size()];
        for (int i = 0; i < paths.size(); i++) {
            labels[i] = folderLabel(paths.get(i)) + "  (" + folderCount.get(paths.get(i)) + ")";
            checked[i] = on.get(paths.get(i));
        }
        new AlertDialog.Builder(this)
                .setTitle("¿Qué carpetas son tu música?")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Aplicar", (d, w) -> {
                    Store.setFolders(this, paths, checked);
                    applyLibrary();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    /* ====================== Navegación ====================== */

    void showPage(int p) {
        int old = page;
        page = p;
        pageHome.setVisibility(p == 0 ? View.VISIBLE : View.GONE);
        pageSearch.setVisibility(p == 1 ? View.VISIBLE : View.GONE);
        pageLib.setVisibility(p == 2 ? View.VISIBLE : View.GONE);
        if (p != old) fadeIn(p == 0 ? pageHome : (p == 1 ? pageSearch : pageLib), 0);
        for (int i = 0; i < 3; i++) {
            int col = i == p ? C_TXT : C_MUT;
            navIcon[i].setColorFilter(col);
            navLbl[i].setTextColor(col);
        }
        final InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (p == 1) {
            q.requestFocus();
            h.postDelayed(() -> {
                if (imm != null) imm.showSoftInput(q, 0);
            }, 120);
        } else if (imm != null) {
            imm.hideSoftInputFromWindow(q.getWindowToken(), 0);
        }
        if (p == 2) loadFavs();
        if (p == 0 && tab == 0) rebuildHome();
    }

    void styleChip(TextView t, boolean on) {
        t.setBackground(on ? rect(0xFFFFFFFF, dp(20)) : glass(dp(20)));
        t.setTextColor(on ? 0xFF000000 : C_TXT);
    }

    void showTab(int i) {
        int old = tab;
        tab = i;
        for (int k = 0; k < 4; k++) styleChip(chips[k], k == i);
        homeScroll.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
        musicList.setVisibility(i == 1 ? View.VISIBLE : View.GONE);
        albumGrid.setVisibility(i == 2 ? View.VISIBLE : View.GONE);
        artistGrid.setVisibility(i == 3 ? View.VISIBLE : View.GONE);
        if (i != old && i != 0) fadeIn(i == 1 ? musicList : (i == 2 ? albumGrid : artistGrid), 0);
        if (i == 0) rebuildHome(i != old);
    }

    boolean playerOpen() {
        return player.getVisibility() == View.VISIBLE;
    }

    void openPlayer() {
        if (svc == null || svc.current() == null) return;
        player.animate().cancel();
        player.setVisibility(View.VISIBLE);
        player.setTranslationY(root.getHeight());
        player.animate().translationY(0).setStartDelay(0).setDuration(320)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        refresh();
    }

    void closePlayer() {
        player.animate().translationY(root.getHeight()).setStartDelay(0).setDuration(240)
                .setInterpolator(new AccelerateInterpolator(1.2f))
                .withEndAction(() -> player.setVisibility(View.GONE)).start();
    }

    @Override
    public void onBackPressed() {
        if (lyricsOpen()) closeLyrics();
        else if (playerOpen()) closePlayer();
        else if (page != 0) showPage(0);
        else if (tab != 0) showTab(0);
        else moveTaskToBack(true);
    }

    /* ====================== Inicio ====================== */

    View sectionHeader(String title, final Runnable seeAll) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(18), dp(16), dp(10));
        r.addView(tv(title, 19, C_TXT, true), new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView all2 = tv("Ver todo", 12, C_MUT, false);
        all2.setPadding(dp(8), dp(4), 0, dp(4));
        all2.setOnClickListener(v -> seeAll.run());
        r.addView(all2);
        return r;
    }

    HorizontalScrollView hscroll() {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout r = new LinearLayout(this);
        r.setPadding(dp(16), 0, dp(4), 0);
        hs.addView(r);
        return hs;
    }

    void addItem(HorizontalScrollView hs, View item, int w) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, WRAP);
        p.rightMargin = dp(12);
        ((LinearLayout) hs.getChildAt(0)).addView(item, p);
    }

    View card(Track art, String t1, String t2, View.OnClickListener ck) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        ImageView iv = new ImageView(this);
        round(iv, dp(14));
        Art.bind(this, iv, art, 320);
        c.addView(iv, new LinearLayout.LayoutParams(dp(110), dp(110)));
        TextView a = one(tv(t1, 13, C_TXT, true));
        TextView b = one(tv(t2, 11, C_MUT, false));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(MATCH, WRAP);
        ap.topMargin = dp(6);
        c.addView(a, ap);
        c.addView(b, new LinearLayout.LayoutParams(MATCH, WRAP));
        c.setOnClickListener(ck);
        press(c);
        return c;
    }

    View artistCard(final Group g) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView iv = new ImageView(this);
        makeCircle(iv);
        Art.bind(this, iv, g.tracks.get(0), 320);
        c.addView(iv, new LinearLayout.LayoutParams(dp(80), dp(80)));
        TextView a = one(tv(g.name, 12, C_TXT, false));
        a.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(MATCH, WRAP);
        ap.topMargin = dp(8);
        c.addView(a, ap);
        c.setOnClickListener(v -> playGroup(g));
        press(c);
        return c;
    }

    View emptyView() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER_HORIZONTAL);
        b.setPadding(dp(24), dp(48), dp(24), dp(24));
        TextView t = tv(emptyText, 15, C_MUT, false);
        t.setGravity(Gravity.CENTER);
        b.addView(t);
        TextView bt = tv("Buscar música otra vez", 14, 0xFF000000, true);
        bt.setPadding(dp(20), dp(10), dp(20), dp(10));
        bt.setBackground(rect(acc, dp(22)));
        bt.setOnClickListener(v -> ask());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.topMargin = dp(18);
        b.addView(bt, lp);
        return b;
    }

    void rebuildHome() {
        rebuildHome(false);
    }

    void rebuildHome(boolean anim) {
        if (homeBox == null) return;
        greetTv.setText(greeting());
        homeBox.removeAllViews();
        if (all.isEmpty()) {
            homeBox.addView(emptyView());
            return;
        }

        ArrayList<Track> rec = new ArrayList<>();
        for (Long rid : Store.recents(this)) {
            Track t = byId.get(rid);
            if (t != null) rec.add(t);
            if (rec.size() >= 12) break;
        }
        final String recTitle = rec.isEmpty() ? "Para empezar" : "Escuchado recientemente";
        if (rec.isEmpty()) {
            ArrayList<Track> sh = new ArrayList<>(all);
            Collections.shuffle(sh, new Random(dayKey()));
            rec.addAll(sh.subList(0, Math.min(12, sh.size())));
        }
        final ArrayList<Track> recF = rec;
        homeBox.addView(sectionHeader(recTitle, () -> showTab(1)));
        HorizontalScrollView hs1 = hscroll();
        for (int i = 0; i < recF.size(); i++) {
            final int pos = i;
            Track t = recF.get(i);
            addItem(hs1, card(t, t.title, t.artist, v -> playList(recF, pos, recTitle)), dp(110));
        }
        homeBox.addView(hs1);

        if (!artists.isEmpty()) {
            homeBox.addView(sectionHeader("Artistas que te gustan", () -> showTab(3)));
            HorizontalScrollView hs2 = hscroll();
            for (int i = 0; i < Math.min(10, artists.size()); i++) {
                addItem(hs2, artistCard(artists.get(i)), dp(88));
            }
            homeBox.addView(hs2);
        }

        if (!albums.isEmpty()) {
            ArrayList<Group> rc = new ArrayList<>(albums);
            Collections.shuffle(rc, new Random(dayKey()));
            homeBox.addView(sectionHeader("Álbumes recomendados", () -> showTab(2)));
            HorizontalScrollView hs3 = hscroll();
            for (int i = 0; i < Math.min(10, rc.size()); i++) {
                final Group g = rc.get(i);
                addItem(hs3, card(g.tracks.get(0), g.name, g.sub, v -> playGroup(g)), dp(110));
            }
            homeBox.addView(hs3);
        }
        if (anim) {
            for (int k = 0; k < homeBox.getChildCount(); k++) fadeIn(homeBox.getChildAt(k), 45L * k);
        }
    }

    /* ====================== Búsqueda y favoritas ====================== */

    void filterSearch() {
        String s = q.getText().toString().trim().toLowerCase(Locale.ROOT);
        searchRes.clear();
        for (Track t : all) {
            if (s.isEmpty() || t.title.toLowerCase(Locale.ROOT).contains(s)
                    || t.artist.toLowerCase(Locale.ROOT).contains(s)
                    || t.album.toLowerCase(Locale.ROOT).contains(s)) {
                searchRes.add(t);
            }
        }
        searchAdapter.notifyDataSetChanged();
    }

    void loadFavs() {
        java.util.Set<String> ids = Store.favs(this);
        favs.clear();
        for (Track t : all) {
            if (ids.contains(String.valueOf(t.id))) favs.add(t);
        }
        libAdapter.notifyDataSetChanged();
        libInfo.setText(favs.size() + (favs.size() == 1 ? " canción favorita" : " canciones favoritas"));
        libEmpty.setVisibility(favs.isEmpty() ? View.VISIBLE : View.GONE);
        libList.setVisibility(favs.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /* ====================== Reproducción ====================== */

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
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Exception ignored) { }
    }

    void playList(ArrayList<Track> list, int pos, String src) {
        if (svc == null || list.isEmpty()) return;
        startSvc();
        svc.playQueue(new ArrayList<>(list), pos, src);
    }

    void playGroup(Group g) {
        playList(g.tracks, 0, g.name);
    }

    void toggle() {
        if (svc == null) return;
        if (svc.current() == null) {
            playList(all, 0, "Mi música");
            return;
        }
        if (!svc.isPlaying()) startSvc();
        svc.toggle();
    }

    /* ====================== Permisos y biblioteca ====================== */

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
        if (checkSelfPermission(audioPerm()) == PackageManager.PERMISSION_GRANTED) {
            load();
        } else {
            emptyText = "Necesito permiso para leer tu música.";
            rebuildHome();
        }
    }

    void load() {
        new Thread(() -> {
            ArrayList<Track> r = new ArrayList<>();
            String sel = "(" + MediaStore.Audio.Media.IS_MUSIC + "!=0 OR "
                    + MediaStore.Audio.Media.MIME_TYPE + " LIKE 'audio/mpeg') AND "
                    + MediaStore.Audio.Media.DURATION + ">=20000";
            try (Cursor c = getContentResolver().query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
                            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
                            MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
                            MediaStore.Audio.Media.TRACK, MediaStore.Audio.Media.DATA},
                    sel, null, MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")) {
                if (c != null) {
                    while (c.moveToNext()) {
                        String ar = c.getString(2);
                        if (ar == null || ar.equals("<unknown>")) ar = "Artista desconocido";
                        String al = c.getString(3);
                        if (al == null || al.equals("<unknown>")) al = "";
                        r.add(new Track(c.getLong(0), c.getString(1), ar, al,
                                c.getLong(4), c.getLong(5), c.getInt(6), folderOf(c.getString(7))));
                    }
                }
            } catch (Exception ignored) { }
            h.post(() -> {
                raw.clear();
                raw.addAll(r);
                applyLibrary();
            });
        }).start();
    }

    void applyLibrary() {
        folderCount.clear();
        for (Track t : raw) {
            Integer n = folderCount.get(t.folder);
            folderCount.put(t.folder, n == null ? 1 : n + 1);
        }
        HashMap<String, Boolean> on = Store.folderStates(this, folderCount.keySet());
        all.clear();
        for (Track t : raw) {
            if (on.get(t.folder)) all.add(t);
        }
        byId.clear();
        for (Track t : all) byId.put(t.id, t);

        LinkedHashMap<String, Group> am = new LinkedHashMap<>();
        LinkedHashMap<String, Group> rm = new LinkedHashMap<>();
        for (Track t : all) {
            String ak = t.albumId > 0 ? "a" + t.albumId : "n" + t.album;
            Group g = am.get(ak);
            if (g == null) {
                g = new Group(t.album.isEmpty() ? "Álbum desconocido" : t.album, t.artist);
                am.put(ak, g);
            }
            g.tracks.add(t);
            Group a = rm.get(t.artist);
            if (a == null) {
                a = new Group(t.artist, "");
                rm.put(t.artist, a);
            }
            a.tracks.add(t);
        }
        albums.clear();
        albums.addAll(am.values());
        Collections.sort(albums, (x, y) -> x.name.compareToIgnoreCase(y.name));
        for (Group g : albums) {
            Collections.sort(g.tracks, (x, y) -> x.trackNo != y.trackNo
                    ? Integer.compare(x.trackNo, y.trackNo)
                    : x.title.compareToIgnoreCase(y.title));
        }
        artists.clear();
        artists.addAll(rm.values());
        final HashMap<String, Integer> pc = new HashMap<>();
        for (Group a : artists) pc.put(a.name, Store.plays(this, a.name));
        Collections.sort(artists, (x, y) -> {
            int d = pc.get(y.name) - pc.get(x.name);
            if (d != 0) return d;
            d = y.tracks.size() - x.tracks.size();
            return d != 0 ? d : x.name.compareToIgnoreCase(y.name);
        });

        emptyText = all.isEmpty()
                ? (raw.isEmpty() ? "No encontré música en tu teléfono."
                : "No hay canciones en las carpetas activas.\nToca el icono de carpeta (arriba) para elegir cuáles usar.")
                : "";
        musicAdapter.notifyDataSetChanged();
        albumAdapter.notifyDataSetChanged();
        artistAdapter.notifyDataSetChanged();
        filterSearch();
        loadFavs();
        rebuildHome(true);
        refresh();
    }

    /* ====================== Estado en pantalla ====================== */

    void refreshHeart(Track t) {
        boolean f = Store.isFav(this, t.id);
        pHeart.setImageResource(f ? R.drawable.ic_heart : R.drawable.ic_heart_o);
        pHeart.setColorFilter(f ? acc : C_TXT);
    }

    void refresh() {
        if (svc == null) return;
        Track t = svc.current();
        boolean pl = svc.isPlaying();
        boolean miniWas = mini.getVisibility() == View.VISIBLE;
        mini.setVisibility(t == null ? View.GONE : View.VISIBLE);
        if (t != null && !miniWas) fadeIn(mini, 0);
        if (t != null) {
            miniTitle.setText(t.title);
            miniSub.setText(t.artist);
            Art.bind(this, miniArt, t, 128);
            pTitle.setText(t.title);
            pArtist.setText(t.artist);
            pSource.setText(svc.source);
            Art.bind(this, pArt, t, 700);
            if (t.id != themeFor) {
                themeFor = t.id;
                final Track tt = t;
                fadeIn(pTitle, 0);
                fadeIn(pArtist, 60);
                fadeIn(miniTitle, 0);
                fadeIn(miniSub, 40);
                pArt.setScaleX(0.92f);
                pArt.setScaleY(0.92f);
                pArt.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(340)
                        .setInterpolator(new DecelerateInterpolator()).start();
                Art.color(this, tt, col -> {
                    if (themeFor == tt.id) applyTheme(col);
                });
            }
            refreshHeart(t);
            loadLyrics(t);
            Track n = svc.nextTrack();
            nextCard.setVisibility(n == null ? View.GONE : View.VISIBLE);
            if (n != null) {
                nTitle.setText(n.title);
                nArtist.setText(n.artist);
                Art.bind(this, nArt, n, 128);
            }
        } else if (playerOpen()) {
            closePlayer();
        }
        int icon = pl ? R.drawable.ic_pause : R.drawable.ic_play;
        miniPlay.setImageResource(icon);
        pPlay.setImageResource(icon);
        if (pl != lastPl) {
            lastPl = pl;
            pop(pPlay);
            pop(miniPlay);
        }
        pShuf.setColorFilter(svc.shuffle ? acc : C_MUT);
        pRep.setImageResource(svc.repeat == 2 ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
        pRep.setColorFilter(svc.repeat > 0 ? acc : C_MUT);
        musicAdapter.notifyDataSetChanged();
        searchAdapter.notifyDataSetChanged();
        libAdapter.notifyDataSetChanged();
        tick();
    }

    void setW(View v, float w) {
        LinearLayout.LayoutParams l = (LinearLayout.LayoutParams) v.getLayoutParams();
        if (l.weight != w) {
            l.weight = w;
            v.setLayoutParams(l);
        }
    }

    void tick() {
        if (svc == null) return;
        if (lyr != null && (playerOpen() || lyricsOpen())) renderLyrics(false);
        int d = svc.dur(), p = svc.pos();
        if (mini.getVisibility() == View.VISIBLE) {
            float f = d > 0 ? Math.min(1f, (float) p / d) : 0f;
            miniFill.animate().scaleX(f).setStartDelay(0).setDuration(300).setInterpolator(lin).start();
        }
        if (!dragging && playerOpen()) {
            pSeek.setMax(Math.max(d, 1));
            if (Build.VERSION.SDK_INT >= 24) pSeek.setProgress(p, true);
            else pSeek.setProgress(p);
            pCur.setText(fmt(p));
            pDur.setText(fmt(d));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        if (page == 0 && tab == 0 && !all.isEmpty()) rebuildHome();
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
        try {
            unbindService(this);
        } catch (Exception ignored) { }
        super.onDestroy();
    }
}
