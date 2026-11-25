package com.caleb.mimusica;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
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
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
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
    final Handler h = new Handler(Looper.getMainLooper());
    final TextView[] chips = new TextView[4];
    final ImageView[] navIcon = new ImageView[3];
    final TextView[] navLbl = new TextView[3];

    PlayerService svc;
    String emptyText = "Leyendo tu música…";
    boolean dragging;
    int page, tab;

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
    LinearLayout nextCard;
    SeekBar pSeek;

    final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            tick();
            h.postDelayed(this, 500);
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
            ((TextView) w[1]).setTextColor(cur != null && cur.id == t.id ? C_ACC : C_TXT);
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
                else round(iv, dp(10));
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
        getWindow().setStatusBarColor(C_BG);
        getWindow().setNavigationBarColor(C_BG);

        root = new FrameLayout(this);
        root.setBackgroundColor(C_BG);

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
        main.addView(nav);
        root.addView(main, new FrameLayout.LayoutParams(MATCH, MATCH));

        buildPlayer();
        root.addView(player, new FrameLayout.LayoutParams(MATCH, MATCH));
        player.setVisibility(View.GONE);

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
        head.addView(bRef, new LinearLayout.LayoutParams(dp(44), dp(44)));
        head.addView(bSearch, new LinearLayout.LayoutParams(dp(44), dp(44)));
        pageHome.addView(head);

        greetTv = tv(greeting(), 26, C_TXT, true);
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
        TextView t = tv("Buscar", 24, C_TXT, true);
        t.setPadding(dp(16), dp(16), dp(16), dp(10));
        pageSearch.addView(t);
        q = new EditText(this);
        q.setHint("¿Qué quieres escuchar?");
        q.setHintTextColor(C_MUT);
        q.setTextColor(C_TXT);
        q.setTextSize(15);
        q.setSingleLine(true);
        q.setBackground(rect(C_CARD, dp(24)));
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
        TextView t = tv("Tu biblioteca", 24, C_TXT, true);
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
        mini.setPadding(dp(8), dp(4), dp(8), dp(4));
        mini.setBackgroundColor(C_BG);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rect(C_CARD, dp(12)));
        round(card, dp(12));
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
        LinearLayout bar = new LinearLayout(this);
        miniFill = new View(this);
        miniFill.setBackgroundColor(C_ACC);
        miniRest = new View(this);
        miniRest.setBackgroundColor(0xFF33353D);
        bar.addView(miniFill, new LinearLayout.LayoutParams(0, MATCH, 0f));
        bar.addView(miniRest, new LinearLayout.LayoutParams(0, MATCH, 1f));
        card.addView(bar, new LinearLayout.LayoutParams(MATCH, dp(3)));
        mini.addView(card);
        mini.setVisibility(View.GONE);
        card.setOnClickListener(v -> openPlayer());
        miniPlay.setOnClickListener(v -> toggle());
        mn.setOnClickListener(v -> {
            if (svc != null) svc.next(false);
        });
    }

    void buildNav() {
        nav = new LinearLayout(this);
        nav.setBackgroundColor(C_BG);
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
            it.addView(ivn, new LinearLayout.LayoutParams(dp(24), dp(24)));
            it.addView(navLbl[i]);
            it.setOnClickListener(v -> showPage(pi));
            nav.addView(it, new LinearLayout.LayoutParams(0, WRAP, 1f));
        }
    }

    void buildPlayer() {
        player = new ScrollView(this);
        player.setBackgroundColor(C_BG);
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
        mid.addView(tv("Reproduciendo desde", 11, C_MUT, false));
        pSource = one(tv("", 13, C_TXT, true));
        mid.addView(pSource);
        top.addView(mid, new LinearLayout.LayoutParams(0, WRAP, 1f));
        top.addView(new View(this), new LinearLayout.LayoutParams(dp(44), dp(44)));
        col.addView(top);

        /* portada */
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int side = Math.min(dm.widthPixels - dp(40), (int) (dm.heightPixels * 0.40f));
        pArt = new ImageView(this);
        round(pArt, dp(16));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(side, side);
        ap.gravity = Gravity.CENTER_HORIZONTAL;
        ap.topMargin = dp(14);
        col.addView(pArt, ap);

        /* título, artista y corazón */
        LinearLayout tr = new LinearLayout(this);
        tr.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout tc = new LinearLayout(this);
        tc.setOrientation(LinearLayout.VERTICAL);
        pTitle = one(tv("", 24, C_TXT, true));
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
            if (page == 2) loadFavs();
        });
        tr.addView(pHeart, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout.LayoutParams trp = new LinearLayout.LayoutParams(MATCH, WRAP);
        trp.topMargin = dp(18);
        col.addView(tr, trp);

        /* barra de tiempo */
        pSeek = new SeekBar(this);
        pSeek.setProgressTintList(ColorStateList.valueOf(C_ACC));
        pSeek.setThumbTintList(ColorStateList.valueOf(C_TXT));
        pSeek.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF3A3C44));
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
        GradientDrawable ov = new GradientDrawable();
        ov.setShape(GradientDrawable.OVAL);
        ov.setColor(0x00000000);
        ov.setStroke(dp(2), C_TXT);
        pPlay.setBackground(ov);
        pPlay.setPadding(dp(18), dp(18), dp(18), dp(18));
        ctl.addView(pShuf, new LinearLayout.LayoutParams(0, dp(52), 1f));
        ctl.addView(prev, new LinearLayout.LayoutParams(0, dp(56), 1f));
        ctl.addView(pPlay, new LinearLayout.LayoutParams(dp(74), dp(74)));
        ctl.addView(next, new LinearLayout.LayoutParams(0, dp(56), 1f));
        ctl.addView(pRep, new LinearLayout.LayoutParams(0, dp(52), 1f));
        LinearLayout.LayoutParams cpp = new LinearLayout.LayoutParams(MATCH, WRAP);
        cpp.topMargin = dp(8);
        col.addView(ctl, cpp);

        /* a continuación */
        nextCard = new LinearLayout(this);
        nextCard.setOrientation(LinearLayout.VERTICAL);
        nextCard.setBackground(rect(C_CARD, dp(16)));
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
        next.setOnClickListener(v -> {
            if (svc != null) svc.next(false);
        });
        prev.setOnClickListener(v -> {
            if (svc != null) svc.prev();
        });
        pShuf.setOnClickListener(v -> {
            if (svc != null) {
                svc.shuffle = !svc.shuffle;
                refresh();
            }
        });
        pRep.setOnClickListener(v -> {
            if (svc != null) {
                svc.repeat = (svc.repeat + 1) % 3;
                refresh();
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

    /* ====================== Navegación ====================== */

    void showPage(int p) {
        page = p;
        pageHome.setVisibility(p == 0 ? View.VISIBLE : View.GONE);
        pageSearch.setVisibility(p == 1 ? View.VISIBLE : View.GONE);
        pageLib.setVisibility(p == 2 ? View.VISIBLE : View.GONE);
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
        t.setBackground(rect(on ? 0xFFFFFFFF : C_CHIP, dp(20)));
        t.setTextColor(on ? 0xFF000000 : C_TXT);
    }

    void showTab(int i) {
        tab = i;
        for (int k = 0; k < 4; k++) styleChip(chips[k], k == i);
        homeScroll.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
        musicList.setVisibility(i == 1 ? View.VISIBLE : View.GONE);
        albumGrid.setVisibility(i == 2 ? View.VISIBLE : View.GONE);
        artistGrid.setVisibility(i == 3 ? View.VISIBLE : View.GONE);
        if (i == 0) rebuildHome();
    }

    boolean playerOpen() {
        return player.getVisibility() == View.VISIBLE;
    }

    void openPlayer() {
        if (svc == null || svc.current() == null) return;
        player.setVisibility(View.VISIBLE);
        player.setTranslationY(root.getHeight());
        player.animate().translationY(0).setDuration(220).start();
        refresh();
    }

    void closePlayer() {
        player.animate().translationY(root.getHeight()).setDuration(200)
                .withEndAction(() -> player.setVisibility(View.GONE)).start();
    }

    @Override
    public void onBackPressed() {
        if (playerOpen()) closePlayer();
        else if (page != 0) showPage(0);
        else if (tab != 0) showTab(0);
        else moveTaskToBack(true);
    }

    /* ====================== Inicio ====================== */

    View sectionHeader(String title, final Runnable seeAll) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(18), dp(16), dp(10));
        r.addView(tv(title, 17, C_TXT, true), new LinearLayout.LayoutParams(0, WRAP, 1f));
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
        round(iv, dp(10));
        Art.bind(this, iv, art, 320);
        c.addView(iv, new LinearLayout.LayoutParams(dp(110), dp(110)));
        TextView a = one(tv(t1, 13, C_TXT, true));
        TextView b = one(tv(t2, 11, C_MUT, false));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(MATCH, WRAP);
        ap.topMargin = dp(6);
        c.addView(a, ap);
        c.addView(b, new LinearLayout.LayoutParams(MATCH, WRAP));
        c.setOnClickListener(ck);
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
        bt.setBackground(rect(C_ACC, dp(22)));
        bt.setOnClickListener(v -> ask());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.topMargin = dp(18);
        b.addView(bt, lp);
        return b;
    }

    void rebuildHome() {
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
                            MediaStore.Audio.Media.TRACK},
                    sel, null, MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")) {
                if (c != null) {
                    while (c.moveToNext()) {
                        String ar = c.getString(2);
                        if (ar == null || ar.equals("<unknown>")) ar = "Artista desconocido";
                        String al = c.getString(3);
                        if (al == null || al.equals("<unknown>")) al = "";
                        r.add(new Track(c.getLong(0), c.getString(1), ar, al,
                                c.getLong(4), c.getLong(5), c.getInt(6)));
                    }
                }
            } catch (Exception ignored) { }
            h.post(() -> applyLibrary(r));
        }).start();
    }

    void applyLibrary(ArrayList<Track> r) {
        all.clear();
        all.addAll(r);
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

        emptyText = all.isEmpty() ? "No encontré música en tu teléfono." : "";
        musicAdapter.notifyDataSetChanged();
        albumAdapter.notifyDataSetChanged();
        artistAdapter.notifyDataSetChanged();
        filterSearch();
        loadFavs();
        rebuildHome();
        refresh();
    }

    /* ====================== Estado en pantalla ====================== */

    void refreshHeart(Track t) {
        boolean f = Store.isFav(this, t.id);
        pHeart.setImageResource(f ? R.drawable.ic_heart : R.drawable.ic_heart_o);
        pHeart.setColorFilter(f ? C_ACC : C_TXT);
    }

    void refresh() {
        if (svc == null) return;
        Track t = svc.current();
        boolean pl = svc.isPlaying();
        mini.setVisibility(t == null ? View.GONE : View.VISIBLE);
        if (t != null) {
            miniTitle.setText(t.title);
            miniSub.setText(t.artist);
            Art.bind(this, miniArt, t, 128);
            pTitle.setText(t.title);
            pArtist.setText(t.artist);
            pSource.setText(svc.source);
            Art.bind(this, pArt, t, 700);
            refreshHeart(t);
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
        pShuf.setColorFilter(svc.shuffle ? C_ACC : C_MUT);
        pRep.setImageResource(svc.repeat == 2 ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
        pRep.setColorFilter(svc.repeat > 0 ? C_ACC : C_MUT);
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
        int d = svc.dur(), p = svc.pos();
        if (mini.getVisibility() == View.VISIBLE) {
            float f = d > 0 ? Math.min(1f, (float) p / d) : 0f;
            setW(miniFill, f);
            setW(miniRest, 1f - f);
        }
        if (!dragging && playerOpen()) {
            pSeek.setMax(Math.max(d, 1));
            pSeek.setProgress(p);
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
