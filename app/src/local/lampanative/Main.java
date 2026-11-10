package local.lampanative;

import static local.lampanative.Net.*;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.icu.util.ULocale;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.net.http.HttpResponseCache;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Home screen: Lampa's TMDB rows from the worker's /home, drawn with plain views. Memory rules from PLAN.md:
 * only pictures near the viewport of the level shown hold a bitmap, and none do while the app is in the background.
 */
@SuppressWarnings("this-escape") // the screens' parts (ux, filmPage, ...) are made with the activity and only keep it
public class Main extends Activity {
    static final String TAG = "LampaNative";
    static final String POSTER = "https://image.tmdb.org/t/p/w300"; // 300×450 = the card at density 2, no scaling
    // w780 looked soft on the 1080p screen; w1280 costs +6 MB and +20-40 ms CPU per page, original 2× the memory
    static final String BACKDROP = "https://image.tmdb.org/t/p/w1280";
 // the cast's 185×278 px photos, cut to a circle
    static final String STILL = "https://image.tmdb.org/t/p/w300"; // an episode's 300×169 = 150×84 dp at density 2
    static final String TILE = "https://image.tmdb.org/t/p/w780"; // a Continue tile: decoded at half size, 390×219
    static final int SURFACE = 0xff1d1f20; // the screens' ground: picture corners and the backdrop's fades go to it
    static final int[] FOCUSED = {android.R.attr.state_focused};

    final ExecutorService pool = Executors.newFixedThreadPool(3); // one core left for the UI thread
    // Backdrop preloads go one at a time on a low-priority thread, so posters coming on screen aren't stuck behind a
    // 300 KB download.
    final ExecutorService background = Executors.newSingleThreadExecutor(r -> {
        Thread th = new Thread(r, "preload");
        th.setPriority(Thread.MIN_PRIORITY);
        return th;
    });
    // Source rows' audio reads (~1-3 s each, up to the worker's 30 s): their own low-priority thread, one at a time,
    // so neither the pool's pages nor background's preloads wait behind them. The thread goes 5 s after the last.
    final ExecutorService audioReads = new ThreadPoolExecutor(0, 1, 5, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
        Thread th = new Thread(r, "audio");
        th.setPriority(Thread.MIN_PRIORITY);
        return th;
    });
    final Handler ui = new Handler(Looper.getMainLooper());
    final SourcesScreen sourcesScreen = new SourcesScreen(this); // a film's or episode's source list
    final FilmPage filmPage = new FilmPage(this); // the film page, an actor's, marks and grades
    final Playback playback = new Playback(this); // OK on a source: the link, DDD, its answer
    final Posters pix = new Posters(this); // pictures: window, load, drop, Pic
    final Ui ux = new Ui(this); // widgets: text, buttons, rows, sheets, tiles
    final List<ImageView> posters = new ArrayList<>(); // Home's
    List<ImageView> pictures = List.of(); // the page's top level's: its poster, photos, collection posters
 // UI thread only
    // Bumped when another level is shown: a load queued for one not shown any more skips its download.
    volatile int shown;
    final int[] at = new int[2];
    ScrollView scroll;
    LinearLayout rows;
    // Your rows (/mine) above the TMDB ones; their posters (in posters too) and the answer they show ("" none)
    LinearLayout mine;
    final List<ImageView> minePosters = new ArrayList<>();
    String mineShown = "";
    Cub.Mine cub = Cub.Mine.NONE; // what /mine says: marks, grades, time left, Coming up, My list, follows, What's new
    TextView bellTab; // Home's "New 3"
    View firstCard; // the card Home focuses at start: still focused when /mine comes, it moves into your rows
    static final String HISTORY = "history"; // the History tile's tag (Continue browsing's last)
    View historyTile;
    LinearLayout historyList; // the History page's, while open
    List<Cub.Played> historyShown = List.of();
    // A card-sized stand-in while a picture isn't loaded (Pic draws it gray in its own shape): a 300×450 poster then
    // replaces a 300×450 drawable, so loading or dropping one doesn't make the ImageView request a layout pass (it
    // does from or to null).
    GradientDrawable empty;
    final Paint gray = new Paint(Paint.ANTI_ALIAS_FLAG);
    Typeface medium; // titles, buttons: the system's sans-serif-medium, no font file
    FrameLayout page; // the open film page, null on Home
    View pageTop, from; // the page's current level and the card it was opened from
    ImageView pageBackdrop;
    String pageBackdropPath;
    // Levels under the current one (film → episodes → sources, or film → a film of its collection → …), each with
    // its focused child, its backdrop ("" for a list) and its pictures.
    record Level(View view, View focus, String backdrop, List<ImageView> pictures) {}
    final ArrayDeque<Level> levels = new ArrayDeque<>();
    Future<?> preload; // the open film's first sources going into the disk cache (UI thread only)
    // A side sheet or the grade bar over the screen (showOverlay), the focus under it when it opened, the screen's
    // own focusability, and what runs when it closes (the language picker reloads the sources)
    View overlay, overlayFrom;
    int overlayWas;
    Runnable overlayClosed;
    Runnable overlayBack; // Back on a sheet opened from another (Settings → Profile): that one again
    String settingsAt; // the row showSettings focuses (back from its sub-sheet), else its first
    Runnable prefetch;
 // collectSoon()
    String prefetched; // the film (its /card URL) whose page was last preloaded or opened
    volatile boolean stopped; // audioReads' thread reads it too
    Timeline.Playing playing; // the playback DDD will answer about
    MeterView meter; // Settings → Stats overlay, while shown
    boolean soft; // Settings → Drawing: Software (pictures made their shown size: fit)
 // the backdrop's darkening (MULTIPLY)
    TextView comingTab; // "Coming up 2": episodes out with your audio (told in the app, no Telegram)
    final Map<String, TextView> homeTabs = new HashMap<>(); // Home's own: Back from a tab lands on its one
    LinearLayout settingsList; // the Settings tab's rows: drawn again when a sheet or a level over them goes
    TextView settingsHelp; // beside them: what the focused one does
    String torboxLine = "…"; // Settings' TorBox line as last read: drawn again with the rows, asked again after a minute
    long torboxAt;
    View settingsButton; // Back from Search opened by the voice button lands here when nothing on Home had focus
    boolean asking; // a /play under way (UI thread)
    volatile boolean recording; // the voice button held: what is said goes to the recorder thread (listen())
    boolean restoring; // a restored film page waits for its /card to open its source list again (≡ waits too)
    // Episodes' resume points (Timeline.marks: hash → {time, duration, percent}) for the seasons' and episodes'
    // marks: from the worker when a series' seasons open, and from DDD's answers (UI thread)
    final Map<String, Timeline.Point> marks = new HashMap<>();
    final Set<String> played = new HashSet<>(); // keys DDD answered about this session: newer than the worker's
    volatile long refusedAt; // when the streams addon last refused the worker (Sources.refusedLately); 0: never
 // its address (the worker's /addon), asked the first time it refuses
    String homeServer; // the build's HOME_SERVER, "host:port" ("" none): Claude for voice search and Story so far
    Optional<Cub.Account> cubAccount; // account(): null until files/cub is read
    long startedAt;
    float dp;
    int cardW, cardH, gap;
    // The focus halo: a 3 dp ring 3 dp out from the picture, inside the 16 dp between cards
    int halo;
    float pill; // a radius big enough to round a button's ends

    // A screen of Main kept in its own file (Discover, Episodes, SearchScreen, SignIn): m is the activity, its views,
    // pools and helpers. Set by this constructor, so it is there for the part's own field initializers, run after it.
    abstract static class Part {
        final Main m;

        Part(Main m) {
            this.m = m;
        }
    }

    // Every task of the pool goes through here. A task that throws more than the IOException it catches (a decoder's
    // RuntimeException, an out-of-memory) would end its pool thread with an uncaught exception, which ends the app: it
    // is logged and the screen keeps what it has. After onDestroy (the pool is shut down) a running task's late post
    // can still reach a loader (loadMine after a grade saved): nothing is asked then.
    void async(Runnable task) {
        if (pool.isShutdown()) return;
        pool.execute(() -> {
            try {
                task.run();
            } catch (Throwable t) { // NOPMD: the point
                Log.w(TAG, "task failed", t);
            }
        });
    }

    // Work off the UI thread and its answer on it, for a screen: work runs on the pool; what it returns goes to ok on the
    // UI thread, while the level that asked (a page's top, a sheet's panel's screen) is still on the page; null: always.
    // If work throws, `what` and the exception are logged and fail runs, under the same check. Every loader wrote this
    // try, post and alive() again.
    <T> void load(View level, String what, Callable<T> work, Consumer<T> ok, Runnable fail) {
        async(() -> {
            T got;
            try {
                got = work.call();
            } catch (Exception e) {
                Log.w(TAG, what, e);
                ui.post(() -> {
                    if (level == null || alive(level)) fail.run();
                });
                return;
            }
            ui.post(() -> {
                if (level == null || alive(level)) ok.accept(got);
            });
        });
    }

    // A thread of our own, with async()'s guard: what its body throws is logged, not an uncaught exception, which ends
    // the app.
    static void thread(String name, Runnable body) {
        new Thread(() -> {
            try {
                body.run();
            } catch (Throwable t) { // NOPMD: the point
                Log.w(TAG, name + " failed", t);
            }
        }, name).start();
    }

    // dp to whole pixels: (int) (v * dp)
    int dp(float v) {
        return (int) (v * dp);
    }

    @Override
    protected void onCreate(Bundle saved) {
        // The manifest draws in software (the default); the GPU is switched on here when Settings
        // choose it (Android can switch a window's GPU on, never off). Software: about a third of the memory, ~30 ms
        // more a scroll step.
        soft = software();
        if (!soft) {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        }
        // Software drawing into a 16-bit window: its pictures are 16-bit already (RGB_565), and each frame fills half the
        // bytes. Measured over 39 presses: the UI thread 42 → 21 ms a press, the window's 3 buffers 24 → 12 MB,
        // still composed by the display hardware. (The GPU renderer keeps 32-bit surfaces whatever is asked: the log.)
        if (soft) getWindow().setFormat(PixelFormat.RGB_565);
        super.onCreate(saved);
        configure(getIntent());
        server(this);
        try {
            Bundle meta = getPackageManager().getApplicationInfo(getPackageName(), PackageManager.GET_META_DATA).metaData;
            homeServer = meta == null ? "" : meta.getString("home_server", "");
        } catch (PackageManager.NameNotFoundException e) {
            homeServer = "";
        }
        // killed while DDD played: its answer comes to this restored one (the file is the one Progress reads; it is
        // cleared when the answer is in, so a fresh start never finds a playback that ended some other way)
        playing = saved == null ? null
            : Timeline.Playing.fromJson(getSharedPreferences("playing", MODE_PRIVATE).getString("now", ""));
        startedAt = System.currentTimeMillis();
        try {
            HttpResponseCache.install(new File(getCacheDir(), "http"), 50L << 20);
        } catch (IOException e) {
            Log.w(TAG, "no HTTP cache", e);
        }
        dp = getResources().getDisplayMetrics().density;
        cardW = dp(150);
        cardH = dp(225);
        gap = dp(16);
        halo = dp(6);
        pill = 999 * dp;
        empty = new GradientDrawable(); // no color: draws nothing
        empty.setSize(cardW, cardH);
        gray.setColor(0xff2a2c2e);
        medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);

        scroll = new ScrollView(this);
        scroll.setBackgroundColor(SURFACE);
        scroll.setSmoothScrollingEnabled(false);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        // a halo less on the left: the rows' titles take it back, their first card's halo shows (strip())
        rows.setPadding(dp(40) - halo, dp(24), 0, dp(40));
        scroll.addView(rows);
        // Above the rows (up from the first one), centered: the tabs, Home lit
        View bar = tabBar("Home");
        bar.setPadding(0, 0, dp(40) - halo, 0); // the rows' left padding: centered on the screen
        rows.addView(bar);
        bellTab = (TextView) ((ViewGroup) bar).getChildAt(1);
        comingTab = homeTabs.get("Coming up");
        settingsButton = homeTabs.get("Settings");
        mine = new LinearLayout(this);
        mine.setOrientation(LinearLayout.VERTICAL);
        rows.addView(mine);
        setContentView(scroll);
        scroll.getViewTreeObserver().addOnScrollChangedListener(pix::updateWindow);
        scroll.getViewTreeObserver().addOnGlobalLayoutListener(pix::updateWindow);
        // Killed while DDD played (or in the background): the film again, and its source list if it was open. Home
        // waits for the Back that closes the page: nothing built under it while DDD hands back.
        Home.Card film = saved == null ? null : Home.Card.of(saved.getStringArray("film"));
        if (film != null) filmPage.openFilm(film, null, saved.getIntArray("sources"));
        else loadHome();
    }

    @Override
    protected void onStart() {
        super.onStart();
        boolean back = stopped; // false the first time: the page (a restored one too) loads its own backdrop
        stopped = false;
        if (settings().getBoolean("stats", false)) showMeter(true);
        if (back && playing == null) loadMine(); // Lampa may have played something meanwhile; DDD's: its answer
        pix.updateWindow();
        if (back && page != null) filmPage.showBackdrop(System.currentTimeMillis());
    }

    // In the background (DDD playing) we keep no bitmaps; the disk cache makes the way back cheap. The GPU context
    // (~22 MB) stays until Android runs short and sends TRIM_MEMORY_COMPLETE (58 → 33 MB, measured): the call
    // that would release it now (WindowManagerGlobal.trimMemory) is a blocked hidden API on Android 11.
    @Override
    protected void onStop() {
        stopped = true;
        recording = false;
        showMeter(false); // back at onStart: no sampling behind DDD
        for (ImageView p : posters) p.setImageDrawable(empty);
        for (ImageView p : pictures) p.setImageDrawable(empty);
        for (Level l : levels) for (ImageView p : l.pictures()) p.setImageDrawable(empty); // a film under a list
        if (page != null) pageBackdrop.setImageDrawable(null);
        pix.collectSoon();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        // Loaders still running post back here when done: with the page closed their checks stop them before they
        // call the shut pool. Pending posts go too.
        closePage();
        ui.removeCallbacksAndMessages(null);
        pool.shutdownNow();
        background.shutdownNow();
        audioReads.shutdownNow();
        super.onDestroy();
    }

    void loadHome() {
        boolean signedIn = account() != null;
        async(() -> {
            try {
                // From the disk even past its hour (≤ a day; measured: first poster 370-410 ms after start instead
                // of 580-700), then a fresh one into the disk for the next start. Your rows as last seen, with it.
                List<Home.Row> data = Home.parse(new String(read(Home.worker + "/home", true), StandardCharsets.UTF_8));
                String saved = signedIn ? readFile(mineFile()) : "";
                ui.post(() -> {
                    if (mineShown.isEmpty()) showMine(saved); // not over a fresh one that came first
                    showRows(data);
                });
                background.execute(() -> {
                    try {
                        cache(Home.worker + "/home");
                    } catch (IOException e) {
                        // the next start shows this one again
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "home failed", e);
                ui.post(this::showError);
            }
        });
        loadMine();
    }

    // A fresh /mine (the worker reads CUB: ~0.5-1 s), drawn when it differs and kept on the stick for the next start.
    void loadMine() {
        Cub.Account a = account();
        if (a == null) return;
        load(null, "mine", () -> {
            // lang: Coming up's "out" is out in these audio languages (the app English, the audio Russian)
            Answer r = send(withCub(open(Home.worker + "/mine?lang=" + langs(), 20000), a), null, null);
            if (r.code() != 200) throw new Status(r.code());
            return r;
        }, r -> {
            if (account() != a) return; // signed out (or in again) meanwhile
            String before = mineShown;
            showMine(r.body());
            if (!Home.keepMine(before, r.body(), mineShown)) return; // the same, or not drawn (not valid)
            try {
                Files.write(mineFile().toPath(), r.body().getBytes(StandardCharsets.UTF_8)); // ~2 KB
            } catch (IOException e) {
                Log.w(TAG, "mine not kept", e);
            }
        }, () -> { }); // the rows shown stay
    }

    // Your rows above the TMDB ones, drawn again only when the answer changed. Focus stays on the same film when it
    // was in them (the card a film page was opened from too), moves into them from the card Home focused at start,
    // and a row below keeps its place at the top.
    void showMine(String body) {
        if (body.equals(mineShown)) return;
        List<Home.Row> data;
        try {
            data = body.isEmpty() ? List.of() : Home.parse(body);
        } catch (JSONException e) {
            Log.w(TAG, "mine", e);
            return;
        }
        mineShown = body;
        cub = Cub.Mine.parse(body);
        comingTab.setText(comingLabel(cub.comingOut()));
        bell(bellTab);
        View old = page != null ? from : overlay != null ? overlayFrom : scroll.findFocus();
        Object was = old != null && Ui.inside(old, mine) ? old.getTag() : null;
        posters.removeAll(minePosters);
        minePosters.clear();
        // Home's TMDB rows are built once: their badges follow your grades and films started (a film finished
        // mustn't keep its "53m left" for the days the app stays open)
        for (ImageView p : posters) {
            if (p.getParent() instanceof FrameLayout f && Ui.cardOf(p).getTag() instanceof Home.Card c) badges(f, c, null);
        }
        mine.removeAllViews();
        int before = posters.size();
        for (Home.Row row : data) {
            TextView title = ux.text(row.title(), 22, Color.WHITE);
            // Continue browsing as landscape tiles with how far in, the rest as posters
            boolean tiles = row.title().equals("Continue browsing");
            boolean rate = row.title().equals("Rate what you watched"); // OK grades it here (rateBar)
            LinearLayout line = ux.strip(mine, title, (tiles ? dp(195) : cardW) + gap);
            for (Home.Card c : row.cards()) {
                View card = tiles ? continueTile(c, title) : card(c, title);
                if (rate) card.setOnClickListener(v -> filmPage.rateBar(c, v, true));
                line.addView(card);
            }
            if (tiles) line.addView(historyTile = historyTile(title));
        }
        minePosters.addAll(posters.subList(before, posters.size()));
        List<Home.Card> tags = new ArrayList<>();
        for (ImageView p : minePosters) tags.add(Ui.cardOf(p).getTag() instanceof Home.Card c ? c : null);
        int at = Home.focusIndex(tags, was, old == null || old == firstCard);
        View target = at < 0 ? null : Ui.cardOf(minePosters.get(at));
        if (HISTORY.equals(was)) target = historyTile;
        if (target == null && was != null && !posters.isEmpty()) target = Ui.cardOf(posters.get(0));
        if (page != null) {
            if (was != null) from = target; // Back lands there
        } else if (overlay != null) {
            if (target != null) overlayFrom = target; // the sheet's close lands there
        } else if (target != null) {
            target.requestFocus();
        } else if (old != null) { // its row back at the top once laid out (the card's own focus listener)
            scroll.post(() -> {
                if (old.hasFocus() && old.getOnFocusChangeListener() != null) old.getOnFocusChangeListener().onFocusChange(old, true);
            });
        }
    }

    File mineFile() {
        return new File(getFilesDir(), "mine.json");
    }

    // A film played here goes to the front of Lampa's history (the worker writes it to CUB), as Lampa puts one
    // there when it plays one: Lampa's Continue browsing and recommendations come from it.
    void addHistory(Home.Card c) {
        Cub.Account a = account();
        if (a == null) return;
        async(() -> {
            int code = post("/mine/history/" + c.type() + "/" + c.id(), a, new byte[0], "text/plain");
            if (code != 204) Log.w(TAG, "history: HTTP " + code); // ponytail: lost; Lampa's history misses this one
        });
    }

    // First setup from a computer (tools/connect.sh): adb shell am start -S -n local.lampanative/.Main --es server
    // https://<worker> --es token <token>. Taken together and only while neither is set, so another app on the TV can't
    // point a set-up app elsewhere, nor slip its own worker in first unnoticed: connect.sh reads the "setup:" line.
    // To set them again: adb shell pm clear local.lampanative.
    void configure(Intent intent) {
        String server = intent.getStringExtra("server"), token = intent.getStringExtra("token");
        if (server == null && token == null) return;
        File s = new File(getFilesDir(), "server"), t = new File(getFilesDir(), "play_token");
        if (s.exists() != t.exists()) {
            Log.i(TAG, "setup: half set before, adb shell pm clear local.lampanative first");
        } else if (s.exists()) {
            Log.i(TAG, "setup: kept " + readFile(s).trim() + ", set before (adb shell pm clear local.lampanative first)");
        } else if (server == null || token == null || !Home.valid("server", server) || !Home.valid("token", token)) {
            Log.i(TAG, "setup: refused, it takes an https server and a token");
        } else {
            try {
                Files.write(t.toPath(), token.getBytes(StandardCharsets.UTF_8));
                Files.write(s.toPath(), server.getBytes(StandardCharsets.UTF_8));
                Log.i(TAG, "setup: connected to " + server);
            } catch (IOException e) {
                Log.w(TAG, "setup", e);
                t.delete(); // nothing half set
                s.delete();
            }
        }
    }

    // The worker's address and the play token (files/server, files/play_token): by the screen, or by Progress with the
    // app closed. Net sends the token with every request to the worker.
    static void server(Context c) {
        if (Home.worker.isEmpty()) Home.worker = readFile(new File(c.getFilesDir(), "server")).trim();
        if (Net.token == null) {
            String t = readFile(new File(c.getFilesDir(), "play_token")).trim();
            Net.token = t.isEmpty() ? null : t;
        }
    }

    void showError() {
        TextView retry = ux.text(Home.worker.isEmpty() ? "No worker set: on a computer, run tools/connect.sh (README, Setup)."
            : "Can't load the home screen. Press OK to try again.", 22, Color.WHITE);
        retry.setPadding(halo, 0, 0, 0);
        retry.setFocusable(true);
        retry.setOnClickListener(v -> {
            rows.removeView(retry);
            loadHome();
        });
        rows.addView(retry);
        retry.requestFocus();
    }

    void showRows(List<Home.Row> data) {
        int stride = cardW + gap;
        for (Home.Row row : data) {
            TextView title = ux.text(row.title(), 22, Color.WHITE);
            LinearLayout line = ux.strip(rows, title, stride);
            for (Home.Card c : row.cards()) line.addView(card(c, title));
        }
        if (!posters.isEmpty()) {
            firstCard = Ui.cardOf(posters.get(0));
            firstCard.requestFocus();
        }
    }

    // A Home card: Home's poster window loads its poster; focus puts its row at the top.
    View card(Home.Card c, TextView rowTitle) {
        ImageView poster = pix.pic(10 * dp, 0);
        poster.setTag(POSTER + c.poster());
        posters.add(poster);
        return homeCard(c, cardView(c, poster), rowTitle);
    }

    // A Continue browsing tile: the backdrop (w780 decoded at half size, 390×219 px, about
    // 170 KB against a poster's 270), how far in as a bar along its bottom, the name, and the time left under it.
    View continueTile(Home.Card c, TextView rowTitle) {
        int w = dp(195), h = dp(110);
        String key = Timeline.card(c);
        int percent;
        try {
            percent = Integer.parseInt(cub.percent().getOrDefault(key, "0"));
        } catch (NumberFormatException e) {
            percent = 0;
        }
        Posters.Pic picture = pix.pic(12 * dp, percent);
        picture.setTag(c.backdrop().isEmpty() ? null : TILE + c.backdrop()); // none: the gray stand-in stays
        posters.add(picture);
        FrameLayout frame = new FrameLayout(this);
        frame.addView(picture, w, h);
        String fresh = cub.fresh().get(key);
        if (fresh != null) frame.addView(ux.chip(ux.text("New · " + fresh, 15, 0xff111111), 0xff21d07a), ux.badge(Gravity.TOP | Gravity.START));
        String left = Timeline.posterLeft(cub.left().get(key)), next = cub.next().get(key); // a series: "S3 E5 · up next"
        return homeCard(c, ux.tile(frame, w, h, 12 * dp, c.name(), next != null ? next : left != null ? left : c.year()),
            rowTitle);
    }

    // Home's card: focus puts its row at the top; OK opens the film.
    View homeCard(Home.Card c, View card, TextView rowTitle) {
        card.setTag(c); // the same film found again when your rows are drawn again
        card.setOnFocusChangeListener((v, focused) -> {
            if (focused) scroll.scrollTo(0, rowTitle.getTop()); // focused row at the top with its title, like Lampa
            prefetch(c, focused);
        });
        card.setOnClickListener(v -> filmPage.openFilm(c, v, null));
        return card;
    }

    // A film page's Trailer: the worker's YouTube video in SmartTube when installed, else whatever opens YouTube links.
    void playTrailer(String video) {
        String address = Film.trailerUrl(video);
        if (address == null) return;
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(address));
        try {
            startActivity(new Intent(i).setPackage("org.smarttube.stable"));
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(i);
            } catch (ActivityNotFoundException none) {
                Toast.makeText(this, "No app here plays YouTube videos (SmartTube does).", Toast.LENGTH_LONG).show();
            }
        }
    }

    View cardView(Home.Card c, ImageView poster) {
        return cardView(c, poster, null);
    }

    // The poster with the score badge, the name and year under it. Top left, the first there is: "New · S3 E5" when a
    // new episode is out, a voice result's "97%" (how sure it is the one), your grade (Lampa's tag: you watched it).
    // Top right: a started film's time left, a started series' episodes left. One badge a corner (a 150 dp poster: more hides the picture); a graded
    // film has no time left (the worker), so the two top ones rarely meet.
    View cardView(Home.Card c, ImageView poster, String sure) {
        FrameLayout frame = new FrameLayout(this);
        frame.addView(poster, cardW, cardH);
        badges(frame, c, sure);
        return ux.tile(frame, cardW, cardH, 10 * dp, c.name(), c.year());
    }

    // A poster's badges (all its frame holds but the picture), again when /mine changes them (showMine).
    void badges(FrameLayout frame, Home.Card c, String sure) {
        frame.removeViews(1, frame.getChildCount() - 1);
        String key = Timeline.card(c);
        String fresh = cub.fresh().get(key);
        String[] grade = fresh == null && sure == null ? Cub.posterGrade(cub.grades().get(key)) : null;
        if (fresh != null || sure != null || grade != null) {
            TextView badge = fresh != null ? ux.text("New · " + fresh, 15, 0xff111111)
                : sure != null ? ux.text(sure, 15, Color.WHITE)
                : ux.text(grade[1], 15, Color.parseColor(grade[2])); // GRADES' own colors (CubTest checks each)
            frame.addView(ux.chip(badge, fresh != null ? 0xff21d07a : 0xcc000000), ux.badge(Gravity.TOP | Gravity.START));
        }
        String left = Timeline.posterLeft(cub.left().get(key));
        if (left != null) {
            frame.addView(ux.chip(ux.text(left, 15, Color.WHITE), 0xcc000000), ux.badge(Gravity.TOP | Gravity.END));
        }
        int score = c.score();
        if (score > 0) {
            frame.addView(ux.chip(ux.text(String.valueOf(score), 15, Home.scoreColor(score)), 0xcc000000),
                ux.badge(Gravity.BOTTOM | Gravity.END));
        }
    }

    // Focus resting on a card (Home's, Search's) for 0.5 s: its /card (~1.5 KB; cold 0.2-0.9 s, from the disk
    // 24-32 ms) and its backdrop go to the disk cache (bytes only, no decode, no RAM), so OK shows the whole page at
    // once. The worker keeps /card at the edge a day: a film costs one MDBList lookup a day (1,000 allowed).
    // ponytail: OK pressed mid-download fetches it twice; share the download if that shows up
    void prefetch(Home.Card c, boolean focused) {
        ui.removeCallbacks(prefetch);
        if (!focused || Film.url(c).equals(prefetched)) return; // not again after Back
        prefetch = () -> {
            prefetched = Film.url(c);
            background.execute(() -> {
                try {
                    cache(Film.url(c));
                    if (!c.backdrop().isEmpty()) cache(BACKDROP + c.backdrop());
                } catch (IOException e) {
                    // OK will try again
                }
            });
        };
        ui.postDelayed(prefetch, 500);
    }

    // A page (a film, the settings) takes Home's place in the same window. Home's views stay, so Back is instant
    // and lands on from.
    void openPage(View from) {
        pix.trim(posters, false);
        page = new FrameLayout(this);
        page.setBackgroundColor(SURFACE);
        pageBackdrop = new ImageView(this);
        pageBackdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        page.addView(pageBackdrop, -1, -1); // dimmed and faded once when it loads (showBackdrop), no overlay
        this.from = from;
        setContentView(page);
    }

    // Whether this level is still on the page (on top or under it): loaders check it before drawing.
    boolean alive(View level) {
        return level == pageTop || levels.stream().anyMatch(l -> l.view() == level);
    }

 // the first page's cached ones (5 rows fit on the screen)

    // Our DDD fork's report while it plays (return_progress: every 30 s, on pause, when it leaves the screen), saved
    // like its answer above. The answer comes only when DDD closes: the projector powers the stick by USB, and
    // switching it off mid-film lost the whole evening's position. Now 30 s at most.
    // Only the worker's point: Home's rows and marks catch up from /mine when the app is back.
    public static class Progress extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent data) {
            Timeline.Playing started = Timeline.Playing.fromJson(
                context.getSharedPreferences("playing", MODE_PRIVATE).getString("now", ""));
            // Only about the address playing: the worker signs it, so another app can't know it
            String at = data.getDataString();
            SharedPreferences now = context.getSharedPreferences("playing", MODE_PRIVATE);
            if (started == null || !started.reports(at, now.getString("url", ""))) return;
            server(context);
            Timeline.Playing p = started.at(at);
            long[] t = p.saved(data.getLongExtra("position", 0), data.getLongExtra("duration", 0), false);
            if (t == null) return;
            byte[] json = p.body(t).getBytes(StandardCharsets.UTF_8);
            PendingResult done = goAsync(); // ~10 s for the request before Android counts it hung
            new Thread(() -> {
                try {
                    HttpURLConnection c = open(Home.worker + "/timeline", 8000); // set: it answered the link; the
                    c.setConnectTimeout(5000);                                    // token: Net.open, read by server()
                    int code = send(c, json, "application/json").code();
                    if (code != 204) Log.w(TAG, "progress: HTTP " + code);
                } catch (IOException e) {
                    Log.w(TAG, "progress", e); // the next report, 30 s on, tries again
                } finally {
                    done.finish();
                }
            }).start();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        playback.result(request, result, data);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        // Where the page is: the top film, and its source list when that is above it (levels: top first)
        if (pageTop != null && FilmPage.where(pageTop, out)) return;
        for (Level l : levels) if (FilmPage.where(l.view(), out)) return;
    }

    // A worker route only this device may call (X-Play-Token: tools/connect.sh put it in files/play_token): the
    // answer's status and body. Never cached.

    Answer ask(String path, byte[] json) throws IOException {
        return ask(path, json, "application/json");
    }

    Answer ask(String path, byte[] body, String type) throws IOException {
        token(); // read again after a refusal, or a missing one is told here (open() sends it)
        HttpURLConnection c = open(Home.worker + path, 30000); // TorBox can take a while
        Answer a = send(c, body, type);
        if (a.code() == 401 || a.code() == 403) Net.token = null; // set up again: read the file next time
        return a;
    }

    // Coming up: the next episodes of the series you follow, and My list's films not out
    // yet, four weeks ahead, by day (/mine's, drawn as it last came). OK opens one.
    void openComing(View from) {
        openPage(from);
        LinearLayout list = tabPage("Coming up");
        String audio = audioNames();
        list.addView(ux.text(cub.coming().isEmpty() ? account() == null ? "Sign in to CUB in Settings: what's coming comes "
            + "from your history." : "Nothing new for the series you watch in the next four weeks." : "Next episodes of "
            + "the series you watch, and films from My list, for the next four weeks. The days are when an episode airs"
            + (audio == null ? "; Out once a release of it is." : " in English; " + audio + " audio usually follows a few "
            + "days later, and Out means a release with it is there."), 16, 0xff9a9a9a));
        LocalDate today = LocalDate.now();
        LinearLayout day = null;
        String at = null;
        View first = null;
        for (Cub.Coming x : cub.coming()) {
            if (!x.date().equals(at)) { // a day: its name and date on the left, its rows on the right
                at = x.date();
                String[] d = Cub.day(at, today);
                LinearLayout group = new LinearLayout(this);
                LinearLayout when = new LinearLayout(this);
                when.setOrientation(LinearLayout.VERTICAL);
                when.setPadding(0, dp(10), 0, 0);
                TextView name = ux.text(d[0], 18, Color.WHITE);
                name.setTypeface(medium);
                when.addView(name);
                when.addView(ux.text(d[1], 15, 0xff9a9a9a));
                group.addView(when, dp(120), -2);
                day = new LinearLayout(this);
                day.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 1);
                right.setMarginStart(dp(20));
                group.addView(day, right);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = dp(14);
                list.addView(group, lp);
            }
            SpannableStringBuilder end = new SpannableStringBuilder(switch (x.state()) {
                case "out" -> "Out";
                case "waiting" -> audio == null ? "Waiting for a release" : "Waiting for " + audio;
                case "airs" -> "Airs";
                default -> "Film";
            });
            end.setSpan(x.state().equals("out") ? new Ui.Tint(Home.GREEN, 0xff0a7d3e) : new Ui.Tint(0xff9a9a9a, 0xff4a4a4a), 0,
                end.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            LinearLayout r = ux.row(x.card().name(), x.what(), end, v -> filmPage.showFilm(x.card(), null));
            if (x.state().equals("out") && x.season() > 0) cachedLabel(x, (TextView) r.getChildAt(1));
            r.setPadding(dp(18), dp(8), dp(18), dp(8));
            ((LinearLayout.LayoutParams) r.getLayoutParams()).bottomMargin = dp(4);
            day.addView(r);
            if (first == null) first = r;
        }
        if (first != null) list.post(first::requestFocus);
    }

    // Continue browsing's last tile: History.
    View historyTile(TextView rowTitle) {
        int w = dp(195), h = dp(110);
        TextView picture = ux.text("↺", 40, 0xff9a9a9a);
        picture.setGravity(Gravity.CENTER);
        picture.setBackground(ux.shape(12 * dp, 0x14ffffff, 0x26ffffff));
        View card = ux.tile(picture, w, h, 12 * dp, "History", "Played here and in Lampa");
        card.setTag(HISTORY);
        card.setOnFocusChangeListener((v, focused) -> {
            if (focused) scroll.scrollTo(0, rowTitle.getTop());
        });
        card.setOnClickListener(this::openHistory);
        return card;
    }

    // History: what was played here and in Lampa, newest first, by day (the worker's
    // /mine/history, asked each time it opens: ~16 KB). OK opens the film; ≡ marks it unwatched or removes it.
    // ponytail: text rows, no posters (Coming up's look); add thumbnails if the rows read poorly
    void openHistory(View from) {
        openPage(from);
        historyList = pushList("History");
        historyList.addView(ux.text("Played here and in Lampa, newest first · ≡ marks one unwatched or removes it", 16,
            0xff9a9a9a));
        fillHistory(historyList, 0);
    }

    // History's rows (again after a change: focus on the row at that place).
    void fillHistory(LinearLayout list, int focus) {
        list.removeViews(2, list.getChildCount() - 2); // the title and its line stay
        Cub.Account a = account();
        TextView note = ux.text(a == null ? "Sign in to CUB in Settings: History comes from your CUB account." : "Loading…",
            18, 0xffdddddd);
        note.setPadding(0, dp(16), 0, 0);
        list.addView(note);
        if (a == null) return;
        View level = Ui.levelOf(list);
        int tz = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000;
        load(level, "history", () -> {
            Answer r = send(withCub(open(Home.worker + "/mine/history?tz=" + tz, 20000), a), null, null);
            if (r.code() != 200) throw new Status(r.code());
            return Cub.history(r.body());
        }, items -> showHistory(list, note, items, focus), () -> note.setText("Can't load History. Back, then open it again."));
    }

    void showHistory(LinearLayout list, TextView note, List<Cub.Played> items, int focus) {
        historyShown = items;
        if (items.isEmpty()) {
            note.setText("Nothing played yet.");
            return;
        }
        list.removeView(note);
        LocalDate today = LocalDate.now();
        ZoneId zone = ZoneId.systemDefault();
        LinearLayout day = null;
        String at = null;
        List<View> rows = new ArrayList<>();
        for (Cub.Played p : items) {
            String[] d = Cub.played(LocalDateTime.ofInstant(Instant.ofEpochMilli(p.t()), zone), today);
            if (!d[0].equals(at)) { // a group: its name on the left, its rows on the right (Coming up's layout)
                at = d[0];
                LinearLayout group = new LinearLayout(this);
                TextView name = ux.text(d[0], 18, Color.WHITE);
                name.setTypeface(medium);
                name.setPadding(0, dp(10), 0, 0);
                group.addView(name, dp(120), -2);
                day = new LinearLayout(this);
                day.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 1);
                right.setMarginStart(dp(20));
                group.addView(day, right);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = dp(14);
                list.addView(group, lp);
            }
            SpannableStringBuilder end = new SpannableStringBuilder(d[1]);
            end.setSpan(new Ui.Tint(0xff9a9a9a, 0xff4a4a4a), 0, end.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            LinearLayout r = ux.row(p.card().name(), historyLine(p), end, v -> filmPage.showFilm(p.card(), null));
            r.setTag(p); // ≡ on it: historySheet
            r.setPadding(dp(18), dp(8), dp(18), dp(8));
            ((LinearLayout.LayoutParams) r.getLayoutParams()).bottomMargin = dp(4);
            day.addView(r);
            rows.add(r);
        }
        rows.get(Math.min(focus, rows.size() - 1)).requestFocus();
    }

    // A History row's gray line: "S1 E6 and E7 · Watched · ★ Good · in Lampa", "Film · 1h 5m left".
    CharSequence historyLine(Cub.Played p) {
        SpannableStringBuilder s = new SpannableStringBuilder(!p.what().isEmpty() ? p.what()
            : p.card().type().equals("tv") ? "Series" : "Film");
        String left = Timeline.posterLeft(String.valueOf(p.left()));
        if (p.left() == 0) {
            s.append(" · ");
            int from = s.length();
            s.append("Watched");
            s.setSpan(new Ui.Tint(Home.GREEN, 0xff0a7d3e), from, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else if (left != null) {
            s.append(" · ").append(left);
        }
        String[] g = Cub.posterGrade(cub.grades().get(Timeline.card(p.card())));
        if (g != null) {
            s.append(" · ");
            int from = s.length();
            s.append("★ ").append(g[1]);
            s.setSpan(new Ui.Tint(Color.parseColor(g[2]), 0xff111111), from, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (p.lampa()) s.append(" · in Lampa");
        return s;
    }

    // ≡ on a History row: its page, Mark unwatched (its points back to the start; Lampa takes them too), Remove (out
    // of CUB's history, so Lampa's too). Both are CUB's: the worker writes them.
    void historySheet(Cub.Played p) {
        boolean series = p.card().type().equals("tv");
        LinearLayout list = ux.sheet(p.card().name(), historyLine(p).toString(), null);
        list.addView(ux.sheetRow(series ? "Series page" : "Film page", null, null, v -> {
            closeOverlay();
            filmPage.showFilm(p.card(), null);
        }));
        list.addView(ux.divider());
        int at = historyShown.indexOf(p);
        if (!p.hashes().isEmpty()) {
            String body = new JSONArray(p.hashes()).toString();
            list.addView(ux.sheetRow("Mark unwatched", (p.what().isEmpty() ? "Back to the start" : p.what() + " back to the "
                + "start") + ", here and in Lampa", null, v -> historyChange("/mine/unwatch",
                ("{\"hashes\":" + body + "}").getBytes(StandardCharsets.UTF_8), at, p.hashes())));
        }
        list.addView(ux.sheetRow("Remove from history", "Lampa's history loses it too", null,
            v -> historyChange("/mine/unhistory/" + p.card().type() + "/" + p.card().id(), new byte[0], at, List.of())));
    }

    // A History change sent (204: CUB or the timeline took it), then the page and Home's rows again.
    // unwatched: the keys of the points taken back to the start: the marks kept here (and DDD's answers of this
    // session, which the worker's answers never overwrite) forget them, or the episodes would show Watched until the
    // app is closed.
    void historyChange(String path, byte[] body, int at, List<String> unwatched) {
        Cub.Account a = account();
        LinearLayout list = historyList;
        Toast.makeText(this, "Saving…", Toast.LENGTH_SHORT).show();
        load(null, "history change", () -> post(path, a, body, "application/json"), done -> {
            if (done != 204) {
                Toast.makeText(this, "CUB didn't take it. Try again.", Toast.LENGTH_LONG).show();
                return;
            }
            closeOverlay();
            Timeline.forget(marks, played, unwatched);
            filmPage.remark();
            if (list != null && alive(Ui.levelOf(list))) fillHistory(list, Math.max(0, at));
            loadMine(); // Continue browsing, the posters' badges
        }, () -> { });
    }

    // An out episode's "Out" → "⚡ Cached" once TorBox has a release of it in your audio languages (the design's):
    // the same first sources page as ▶ Play's pick.
    void cachedLabel(Cub.Coming x, TextView label) {
        String langs = langs();
        load(null, "coming " + x.card().id(), () -> {
            Film film = Film.parse(new String(read(Film.url(x.card())), StandardCharsets.UTF_8));
            if (film.imdb().isEmpty()) return null;
            Sources.Page p = Sources.parse(new String(read(Sources.url(film.imdb(), x.season(), x.episode(), langs,
                0)), StandardCharsets.UTF_8));
            if (Sources.best(p.streams()) == null) return null;
            SpannableStringBuilder cached = new SpannableStringBuilder(Sources.BOLT + " Cached");
            cached.setSpan(new Ui.Tint(Home.GREEN, 0xff0a7d3e), 0, cached.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return cached;
        }, cached -> {
            if (cached != null) label.setText(cached);
        }, () -> { }); // stays "Out"
    }

    // Search: an alphabetical keyboard (EN or RU) on the left, the results on the right while typing; the worker
    // is asked 0.3 s after the last key, and one letter already finds well-known titles. Before typing, the last
    // searches. OK on a result opens it on top (that search is remembered); Back returns here.
    void openSearch(View from) {
        openPage(from);
        new SearchScreen(this).show();
    }

    // Voice search (Voice): the remote's voice button held opens Search on top (or uses the open one) and records
    // until it is let go (Voice.MAX_MS at most). The microphone: adb's pm grant RECORD_AUDIO, or Fire TV's
    // Settings. Android's own question can't be asked from here: Fire OS closes it at once when the voice button
    // asks (seen: six presses, each closed in 30 ms, the button "not working").
    void listen() {
        if (recording || asking || restoring) return;
        closeOverlay(); // Search goes over the screen, not under a sheet
        SearchScreen s = pageTop != null && pageTop.getTag() instanceof SearchScreen o ? o : null;
        if (s == null) {
            View focused = scroll.findFocus();
            if (page == null) openPage(focused != null ? focused : settingsButton);
            s = new SearchScreen(this);
            s.show();
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            s.say("No microphone");
            s.clear();
            s.results.addView(ux.text("This app may not use the microphone. Allow it in Fire TV's Settings → Applications"
                + " → Manage Installed Applications → Lampa Native.", 18, 0xff9a9a9a));
            return;
        }
        int min = AudioRecord.getMinBufferSize(Voice.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, Voice.RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, Voice.RATE * 2));
        if (r.getState() != AudioRecord.STATE_INITIALIZED) {
            r.release();
            s.say("No microphone");
            return;
        }
        int ask = s.listening();
        recording = true;
        SearchScreen on = s;
        new Thread(() -> {
            ByteArrayOutputStream said = new ByteArrayOutputStream();
            byte[] b = new byte[3200]; // 0.1 s at most a read
            long end = System.nanoTime() + Voice.MAX_MS * 1_000_000L;
            int shownLevel = -1;
            try { // the microphone let go and the next press possible whatever a read throws
                r.startRecording();
                // not waiting for a full piece: the remote stops streaming as the button is let go, and a read waiting
                // for one would never return (seen: stuck until the next press, which it then took too)
                while (recording && System.nanoTime() < end) {
                    int n = r.read(b, 0, b.length, AudioRecord.READ_NON_BLOCKING);
                    if (n < 0) break;
                    if (n == 0) {
                        SystemClock.sleep(20);
                        continue;
                    }
                    said.write(b, 0, n);
                    int level = Voice.level(b, n);
                    if (level != shownLevel) {
                        shownLevel = level;
                        ui.post(() -> on.level(ask, level));
                    }
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "voice: recording", e);
            } finally {
                r.release(); // stops it too
                recording = false;
            }
            ui.post(() -> heard(on, ask, said.toByteArray()));
        }, "voice").start();
    }

    // What was said: heard by the worker, understood by Claude Code on the home server (or the worker's own model),
    // found on TMDB. One sure title opens at once; Back shows the others. ask: this press's number, an older
    // press's answers are dropped.
    void heard(SearchScreen s, int ask, byte[] pcm) {
        if (pageTop != s.screen || ask != s.asks) return; // Back, or pressed again, meanwhile
        if (pcm.length < Voice.MIN_BYTES) {
            s.say("Hold the button while you speak");
            s.clear();
            return;
        }
        s.thinking("Hearing…");
        async(() -> {
            String text = null;
            Voice.Answer found = null;
            try {
                Answer h = ask("/voice/hear", Voice.wav(pcm, pcm.length), "audio/wav");
                if (h.code() != 200) throw new IOException("voice/hear: " + h.code());
                text = Voice.heard(h.body());
                if (!text.isEmpty()) {
                    String t = text;
                    ui.post(() -> s.heard(ask, t));
                    Answer f = ask("/voice/find", Voice.findBody(text, understand(text)).getBytes(StandardCharsets.UTF_8));
                    if (f.code() == 200) found = Voice.parse(f.body());
                }
            } catch (Exception e) {
                Log.w(TAG, "voice", e);
            }
            String t = text;
            Voice.Answer a = found;
            ui.post(() -> s.found(ask, t, a));
        });
    }

    // Claude Code on the home server: what the request means, as it answered; null when it didn't (off, too slow,
    // the plan's limit): the worker then reads the text itself.
    String token() throws IOException {
        server(this); // read again after a refusal
        String t = Net.token;
        if (t == null) throw new FileNotFoundException("No play token: run tools/connect.sh (README, Setup).");
        return t;
    }

    String understand(String text) {
        if (homeServer.isEmpty()) return null;
        try {
            HttpURLConnection c = open("http://" + homeServer + "/understand", 25000);
            // the home network: no answer in 3 s = not there (a computer just woken or busy took over 1.5 s); the
            // worker's own understanding meanwhile
            c.setConnectTimeout(3000);
            byte[] body = Voice.understandBody(text).getBytes(StandardCharsets.UTF_8);
            String[] auth = Voice.homeAuth(token(), body, System.currentTimeMillis());
            c.setRequestProperty("X-Home-Time", auth[0]);
            c.setRequestProperty("X-Home-Sig", auth[1]);
            Answer a = send(c, body, "application/json");
            if (a.code() == 200) return Voice.understood(a.body());
            Log.w(TAG, "voice: Claude Code answered " + a.code());
        } catch (IOException | JSONException e) {
            Log.w(TAG, "voice: no Claude Code", e);
        }
        return null;
    }

    // ≡ on a Home poster (fewer presses to play, for no memory): the film's sheet, no page
    // opened, no backdrop. A film: ▶ Play (Play picks' release, Settings), its Sources; a series: its episodes from
    // the one to go on with. Then the page, My list, the grade (the bar under the row), the mark, and Settings. Its
    // /card and first sources mostly come from the disk (the poster's prefetch).
    void quick(Home.Card c, View card) {
        boolean series = c.type().equals("tv");
        String key = Timeline.card(c);
        LinearLayout list = ux.sheet(c.name(), c.year(), null);
        TextView sub = (TextView) list.getChildAt(1);
        Film[] film = new Film[1];
        Sources.Source[] best = new Sources.Source[1];
        TextView playLine = null, sourcesLine = null;
        if (series) {
            String next = cub.next().get(key);
            list.addView(ux.sheetRow("▶  Episodes", next, null, v -> {
                if (film[0] == null) return;
                if (!film[0].watchable(true)) { // no season to open: Episodes shows the first
                    Toast.makeText(this, "No episodes out yet.", Toast.LENGTH_SHORT).show();
                    return;
                }
                closeOverlay();
                openPage(card);
                filmPage.loadMarks(film[0]);
                new Episodes(this, film[0], c, Cub.episode(next)).show();
            }));
        } else {
            LinearLayout play = ux.sheetRow("▶  Play", picks() ? "Finding a source…" : "The sources list", null, v -> {
                if (film[0] == null) return;
                closeOverlay();
                if (best[0] != null) {
                    playback.play(film[0], c, 0, 0, best[0].hash(), best[0].fileIdx(), best[0].title(), null);
                } else {
                    openPage(card);
                    sourcesScreen.showSources(film[0], c, 0, 0, null, false);
                }
            });
            LinearLayout sources = ux.sheetRow("Sources", shownLangs(), null, v -> {
                if (film[0] == null) return;
                closeOverlay();
                openPage(card);
                sourcesScreen.showSources(film[0], c, 0, 0, null, false);
            });
            list.addView(play);
            list.addView(sources);
            playLine = (TextView) play.getChildAt(1);
            sourcesLine = (TextView) sources.getChildAt(1);
        }
        list.addView(ux.sheetRow("Film page", null, null, v -> {
            closeOverlay();
            filmPage.openFilm(c, card, null);
        }));
        if (account() != null) {
            list.addView(ux.divider());
            list.addView(ux.sheetRow(cub.list().contains(key) ? "Remove from My list" : "Add to My list", null, null,
                v -> filmPage.toggleList(c)));
            String[] g = Cub.grade(cub.grades().get(key));
            SpannableStringBuilder star = new SpannableStringBuilder("★  Your grade");
            if (g != null) star.setSpan(new Ui.Tint(Color.parseColor(g[2]), 0xff111111), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            list.addView(ux.sheetRow(star, null, g == null ? "None" : g[1], v -> {
                filmPage.rateBar(c, card, false);
                overlayBack = () -> quick(c, card);
            }));
            String marked = Cub.markName(cub.marks().get(key));
            list.addView(ux.sheetRow("Mark", null, marked == null ? "None" : marked, v -> {
                filmPage.marksSheet(c, false);
                overlayBack = () -> quick(c, card);
            }));
        }
        list.addView(ux.divider());
        list.addView(ux.sheetRow("⚙  Settings", null, null, v -> {
            showSettings();
            overlayBack = () -> quick(c, card);
        }));
        View panel = overlay;
        TextView playing = playLine, listing = sourcesLine;
        boolean picks = picks();
        async(() -> {
            try {
                Film f = Film.parse(new String(read(Film.url(c)), StandardCharsets.UTF_8));
                String ends = Film.endsAt(f.runtime(), LocalTime.now());
                ui.post(() -> {
                    if (overlay != panel) return;
                    film[0] = f;
                    sub.setText(series || f.runtime() == 0 ? f.line() : c.year() + " · " + Film.length(f.runtime()) + " · " + ends);
                });
                if (series || f.imdb().isEmpty()) return;
                Sources.Page p = Sources.parse(new String(read(Sources.url(f.imdb(), 0, 0, langs(), 0)),
                    StandardCharsets.UTF_8));
                Sources.Source s = Sources.best(p.streams());
                ui.post(() -> {
                    if (overlay != panel) return;
                    if (picks) {
                        best[0] = s;
                        playing.setText(s != null ? Sources.playsText(s, langs()) : "Nothing cached: OK lists the sources");
                    }
                    listing.setText(p.total() + " found · " + shownLangs());
                });
            } catch (Exception e) {
                Log.w(TAG, "quick " + c.id(), e);
                ui.post(() -> {
                    if (overlay == panel && playing != null && best[0] == null) playing.setText("The sources list");
                });
            }
        });
    }

    // ≡ or Home's Settings: a side sheet over whatever is shown: each setting's name and
    // value, what the focused one does at the sheet's foot. OK flips or picks. Drawing restarts the app: a window's
    // GPU is chosen before it is shown, and the GPU's context and driver pools stay with the process once made.
    void showSettings() {
        showSettings(null);
    }

    void showSettings(String at) {
        settingsAt = at;
        LinearLayout list = ux.sheet("Settings", null, null);
        settingsRows(list, ux.footer(list), true);
    }

    // The Settings tab (a tab, not a sheet over Home): its rows on the left, what the focused one
    // does on the right, as TV settings show it.
    void openSettings(View from) {
        openPage(from);
        LinearLayout tab = tabPage("Settings");
        LinearLayout cols = new LinearLayout(this);
        settingsList = new LinearLayout(this);
        settingsList.setOrientation(LinearLayout.VERTICAL);
        cols.addView(settingsList, dp(560), -2);
        settingsHelp = ux.text("", 18, 0xff9a9a9a);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0, -2, 1);
        hp.setMarginStart(dp(48));
        hp.topMargin = dp(12);
        cols.addView(settingsHelp, hp);
        tab.addView(cols);
        settingsAt = "Drawing"; // its first row, as Search's first key and Coming up's first day
        settingsRows(settingsList, settingsHelp, false);
    }

    // The Settings tab shown again (a sheet over it closed, a level over it went): its values may have changed.
    void settingsAgain() {
        if (settingsList == null || !settingsList.isAttachedToWindow()) return;
        settingsList.removeAllViews();
        settingsRows(settingsList, settingsHelp, false); // focus on the row it had (settingsAt)
    }

    // sheet: ≡'s (Back from its sub-sheets opens it again), else the tab's (shown again by settingsAgain()).
    void settingsRows(LinearLayout list, TextView help, boolean sheet) {
        setting(list, help, "Drawing", drawing(), "GPU: smoothest scrolling. Software: about a third of the memory, "
            + "~30 ms more per scroll step. OK switches and restarts the app.", v -> {
                //noinspection ApplySharedPref: on disk now, this process ends below
                settings().edit().putBoolean("software", !software()).commit();
                startActivity(new Intent(this, Main.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                Process.killProcess(Process.myPid());
            });
        setting(list, help, "Stats overlay", stats(), "Memory, CPU and frames in the top right corner.", v -> {
            boolean on = !settings().getBoolean("stats", false);
            settings().edit().putBoolean("stats", on).apply();
            ((TextView) ((LinearLayout) v).getChildAt(1)).setText(stats()); // the value, right
            showMeter(on);
        });
        list.addView(ux.divider());
        Cub.Account a = account();
        setting(list, help, "CUB account", a == null ? "Sign in" : "Signed in", "Lampa's account: your history on Home "
            + "and in Lampa, My list, marks and grades. OK " + (a == null ? "signs in with a code." : "signs out."), v -> {
                Runnable done = this::loadMine; // signed in: your rows; signed out: nothing (signOut's showMine)
                if (account() != null) {
                    signOut(done);
                    if (sheet) overlayBack = () -> showSettings("CUB account");
                    return;
                }
                closeOverlay();
                if (page == null) openPage(scroll.findFocus()); // the code needs the screen: a page over Home
                new SignIn(this, done).show();
            });
        if (a != null) {
            setting(list, help, "Profile", a.name().isEmpty() ? "Main" : a.name(), "Whose history, marks and grades: CUB "
                + "keeps them per profile, as Lampa. OK picks another.", v -> {
                    showProfiles();
                    if (sheet) overlayBack = () -> showSettings("Profile");
                });
        }
        LinearLayout torbox = setting(list, help, "TorBox", torboxLine, "The TorBox account the worker plays from: its plan "
            + "and the torrents in it.", v -> { });
        setting(list, help, "Worker", Home.worker.isEmpty() ? "Not set" : Uri.parse(Home.worker).getHost(), "The worker "
            + "this app talks to, set once from a computer (README, Setup). To change it: adb shell pm clear "
            + "local.lampanative, then set it up again.", v -> { });
        LinearLayout about = setting(list, help, "About", "", "Lampa Native is unofficial: not made by or affiliated with "
            + "Lampa or CUB. This application uses TMDB and the TMDB APIs but is not endorsed, certified, or otherwise "
            + "approved by TMDB.", v -> { });
        ((TextView) about.getChildAt(1)).setCompoundDrawablesRelativeWithIntrinsicBounds(drawable("tmdb"), null, null, null);
        list.addView(ux.divider());
        setting(list, help, "Play picks", picks() ? "Best cached" : "Ask each time", "Best cached: a film's ▶ Play "
            + "starts the biggest release TorBox has cached in your audio languages. Ask each time: the sources list.", v -> {
                settings().edit().putBoolean("picks", !picks()).apply();
                ((TextView) ((LinearLayout) v).getChildAt(1)).setText(picks() ? "Best cached" : "Ask each time");
            });
        setting(list, help, "Audio languages", shownLangs().replace("all languages", "All"), "Sources in these "
            + "languages first. OK picks them.", v -> {
                // no source list here: the app's default ones and those chosen, no counts
                Set<String> codes = new LinkedHashSet<>(List.of("RU", "UA", "EN"));
                codes.addAll(Sources.codes(langs()));
                List<Sources.Language> all = new ArrayList<>();
                for (String code : codes) all.add(new Sources.Language(code, -1));
                showLanguages(all, null);
                if (sheet) overlayBack = () -> showSettings("Audio languages");
            });
        long now = SystemClock.elapsedRealtime();
        if (now - torboxAt < 60_000) return; // the tab's rows drawn again after a sub-sheet: no new request, no "…"
        torboxAt = now;
        // TorBox's plan and torrents (~0.5 s)
        load(null, "torbox", () -> {
            Answer t = ask("/torbox", null);
            JSONObject o = new JSONObject(t.body());
            return t.code() == 200 ? o.getString("plan") + " · " + o.getInt("torrents") + " torrents" : "Not reachable";
        }, shown -> {
            torboxLine = shown;
            if (torbox.isAttachedToWindow()) ((TextView) torbox.getChildAt(1)).setText(shown);
            else settingsAgain(); // the tab's rows drawn again meanwhile
        }, () -> {
            torboxLine = "Not reachable";
            if (torbox.isAttachedToWindow()) ((TextView) torbox.getChildAt(1)).setText(torboxLine);
            else settingsAgain();
        });
    }

    @SuppressLint("DiscouragedApi") // by name: build.sh makes no R class
    Drawable drawable(String name) {
        return getDrawable(getResources().getIdentifier(name, "drawable", getPackageName()));
    }

    // A setting's row: its name and value on one line; focused, what it does at the sheet's foot.
    LinearLayout setting(LinearLayout list, TextView help, String title, String value, String does,
                                 View.OnClickListener action) {
        LinearLayout r = ux.sheetRow(title, null, value, action);
        r.setPadding(dp(12), dp(10), dp(12), dp(10));
        ((LinearLayout.LayoutParams) r.getLayoutParams()).bottomMargin = dp(2);
        r.setOnFocusChangeListener((v, focused) -> {
            if (!focused) return;
            help.setText(does);
            settingsAt = title; // the tab's rows drawn again keep it
        });
        list.addView(r);
        if (title.equals(settingsAt)) r.requestFocus();
        return r;
    }

    // Settings → Profile: the account's CUB profiles (Lampa's; history, marks and grades are each one's), the one used
    // ✓. OK switches: Home's rows are that profile's.
    void showProfiles() {
        Cub.Account a = account();
        LinearLayout list = ux.sheet("Profile", "Lampa uses the main one unless switched there", null);
        TextView wait = ux.text("Asking CUB…", 16, 0xff9a9a9a);
        wait.setPadding(dp(12), 0, 0, 0);
        list.addView(wait);
        View panel = overlay;
        load(null, "profiles", () -> Cub.profiles(cub("profiles/all", null, a.token()).body()), all -> {
            if (overlay != panel) return;
            list.removeView(wait);
            for (Cub.Profile p : all) {
                list.addView(ux.sheetRow(p.name(), p.main() ? "Main profile" : null, Ui.check(p.id() == a.profile()), v -> {
                    try {
                        saveAccount(new Cub.Account(a.token(), p.id(), p.name()));
                    } catch (IOException e) {
                        Toast.makeText(this, "Couldn't save it: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        return;
                    }
                    closeOverlay();
                    loadMine(); // that profile's rows
                }));
            }
            panel.requestFocus();
        }, () -> wait.setText("CUB doesn't answer. Try again later."));
    }

    // The CUB account signed in (files/cub), or null. Read once; signing in or out sets it.
    Cub.Account account() {
        if (cubAccount == null) {
            try {
                cubAccount = Optional.ofNullable(Cub.Account.of(new String(Files.readAllBytes(cubFile().toPath()),
                    StandardCharsets.UTF_8)));
            } catch (IOException e) {
                cubAccount = Optional.empty(); // never signed in
            }
        }
        return cubAccount.orElse(null);
    }

    // The account signed in or switched to a profile: in files/cub (private to the app) and in memory.
    void saveAccount(Cub.Account a) throws IOException {
        Files.write(cubFile().toPath(), a.save().getBytes(StandardCharsets.UTF_8));
        cubAccount = Optional.of(a);
    }

    File cubFile() {
        return new File(getFilesDir(), "cub");
    }

    void signOut(Runnable done) {
        LinearLayout list = ux.sheet("CUB account", account().name().isEmpty() ? null : account().name(), null);
        list.addView(ux.sheetRow("Sign out", "History leaves Home; CUB keeps it, and Lampa stays signed in", null, v -> {
            //noinspection ResultOfMethodCallIgnored: gone or never there, signed out either way
            cubFile().delete();
            //noinspection ResultOfMethodCallIgnored: the same
            mineFile().delete();
            cubAccount = Optional.empty();
            closeOverlay();
            showMine("");
            done.run();
        }));
    }

    String drawing() {
        return software() ? "Software" : "GPU";
    }

    String stats() {
        return settings().getBoolean("stats", false) ? "On" : "Off";
    }

    boolean software() {
        return settings().getBoolean("software", true); // the lighter one
    }

    SharedPreferences settings() {
        return getSharedPreferences("settings", MODE_PRIVATE);
    }

    // The stats overlay goes on the window's decor, so it stays over Home and pages alike.
    void showMeter(boolean on) {
        if (on == (meter != null)) return;
        ViewGroup decor = (ViewGroup) getWindow().getDecorView();
        if (on) {
            meter = new MeterView(this, getWindow(), !soft, background, ui); // not the poster threads
            decor.addView(meter, new FrameLayout.LayoutParams(dp(330), dp(150),
                Gravity.TOP | Gravity.END));
            meter.start();
        } else {
            meter.stop();
            decor.removeView(meter);
            meter = null;
        }
    }

    // A side sheet: OK on a language adds or removes it, All languages takes them all. Saved for next time; when it
    // closes with the choice changed, reload runs (the source list asks again; null from Settings). releases < 0: not
    // counted (from Settings).
    void showLanguages(List<Sources.Language> all, Runnable reload) {
        String before = langs();
        LinearLayout list = ux.sheet("Audio languages", "OK adds or removes one.", () -> {
            if (reload != null && !langs().equals(before)) reload.run();
        });
        list.addView(ux.sheetRow("All languages", null, null, v -> {
            setLangs("");
            closeOverlay();
        }));
        list.addView(ux.divider());
        for (Sources.Language l : all) {
            String code = l.code();
            list.addView(ux.sheetRow(language(code), l.releases() < 0 ? code : code + " · " + l.releases() + " releases",
                Ui.check(Sources.selected(langs(), code)), v -> {
                    setLangs(Sources.toggle(langs(), code));
                    ((TextView) ((LinearLayout) v).getChildAt(1)).setText(Ui.check(Sources.selected(langs(), code)));
                }));
        }
    }

    // A Torrentio flag's language, in English: its country's likely one, from the platform's own data (UA →
    // Ukrainian, BR → Portuguese). EN is the worker's own (🇬🇧, or no flag).
    static String language(String code) {
        if (code.equals("EN")) return "English";
        String name = ULocale.addLikelySubtags(new ULocale("und_" + code)).getDisplayLanguage(ULocale.ENGLISH);
        return name.isEmpty() || name.startsWith("Unknown") || name.equalsIgnoreCase("und") ? code : name;
    }

    String langs() {
        return settings().getString("langs", ""); // "": all languages
    }

    // The audio languages by name, "Russian or Ukrainian" (Coming up); null: any language, or English among them
    String audioNames() {
        List<String> codes = Sources.codes(langs());
        if (codes.isEmpty() || codes.contains("EN")) return null;
        return codes.stream().map(Main::language).distinct().collect(java.util.stream.Collectors.joining(" or "));
    }

    // "RU, UA", "all languages"
    String shownLangs() {
        return langs().isEmpty() ? "all languages" : langs().replace(",", ", ");
    }

    // Settings → Play picks: Best cached (true, the default) or Ask each time.
    boolean picks() {
        return settings().getBoolean("picks", true);
    }

    void setLangs(String langs) {
        settings().edit().putString("langs", langs).apply();
        ui.removeCallbacks(langsChanged); // Coming up's "out" follows them: /mine again once the toggling stops
        ui.postDelayed(langsChanged, 2000);
    }

    final Runnable langsChanged = this::loadMine;

    // A new level on the film page: a title over a scrolling list, on the page's plain background.
    LinearLayout pushList(String title) {
        ScrollView sv = new ScrollView(this);
        sv.setSmoothScrollingEnabled(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(60), dp(36), dp(60), dp(36));
        TextView head = ux.text(title, 24, Color.WHITE);
        head.setTypeface(medium);
        head.setPadding(0, 0, 0, dp(12));
        list.addView(head);
        sv.addView(list);
        pushLevel(sv, "", List.of(), false);
        list.post(() -> {
            if (list.getChildCount() > 1 && list.findFocus() == null) list.getChildAt(1).requestFocus();
        });
        return list;
    }

    // A new level on top of the page; the one shown goes under it with its focus. Only a film shows its backdrop:
    // under another level it is dropped (1.8 MB + its texture). Under a list (Watch) a film keeps the pictures on
    // the screen, so Back shows them at once; under another film it keeps none, so films stacked deep cost views,
    // not bitmaps. Back gets the rest from the disk cache.
    // load: false when a source list covers it at once (a restore): no backdrop downloaded and decoded for nothing.
    void pushLevel(View top, String backdrop, List<ImageView> pics, boolean load) {
        if (pageTop != null) {
            pix.trim(pictures, !pics.isEmpty()); // pictures: a film goes on top
            levels.push(new Level(pageTop, pageTop.findFocus(), pageBackdropPath, pictures));
            page.removeView(pageTop);
        }
        show(top, backdrop, pics);
        if (load) filmPage.showBackdrop(System.currentTimeMillis()); // from the network the first time
    }

    // Back to the level below, with its focus. backdrop: false when another list goes on top at once (no decode
    // for nothing).
    boolean popLevel(boolean backdrop) {
        if (levels.isEmpty()) return false;
        Level below = levels.pop();
        page.removeView(pageTop);
        show(below.view(), below.backdrop(), below.pictures());
        if (backdrop) filmPage.showBackdrop(System.currentTimeMillis()); // from the disk cache
        pix.collectSoon(); // the level left
        // its focus when it went under; else one set on it since (levels pushed in one go: Continue, a restore);
        // else its first row
        View focus = below.focus() != null ? below.focus() : below.view().findFocus();
        (focus != null ? focus : below.view()).requestFocus();
        settingsAgain(); // back on the Settings tab from signing in
        return true;
    }

    // Back: the level below, else Home.
    void back() {
        if (popLevel(true)) return;
        closePage();
        setContentView(scroll);
        if (from != null) from.requestFocus();
        else loadHome(); // after a restore: Home now (~0.3 s from the disk cache), its first card focused
    }

    // A side sheet or the grade bar: an opaque panel over the screen shown, which stays as
    // it is under it: no level pushed, nothing dimmed, no backdrop dropped and decoded again on Back. The arrows
    // never leave it (trapped()) and the screen under it takes no focus meanwhile (a source list loading under
    // Settings would). One at a time: a new one replaces it. Back closes it; closed runs then.
    void showOverlay(ViewGroup panel, FrameLayout.LayoutParams lp, Runnable closed) {
        closeOverlay();
        overlayBack = null; // set by its opener after this
        FrameLayout root = findViewById(android.R.id.content);
        ViewGroup under = (ViewGroup) root.getChildAt(0);
        overlayFrom = root.findFocus();
        overlayWas = under.getDescendantFocusability();
        root.addView(panel, lp);
        overlay = panel;
        overlayClosed = closed;
        panel.post(() -> { // after the caller's rows
            if (overlay == panel && !panel.hasFocus()) panel.requestFocus();
        });
        under.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
    }

    // False when none is open.
    boolean closeOverlay() {
        if (overlay == null) return false;
        overlayBack = null; // closed by a pick: no way back
        FrameLayout root = findViewById(android.R.id.content);
        root.removeView(overlay);
        overlay = null;
        ViewGroup under = (ViewGroup) root.getChildAt(0);
        under.setDescendantFocusability(overlayWas);
        if (overlayFrom != null && overlayFrom.isAttachedToWindow()) overlayFrom.requestFocus();
        else under.requestFocus();
        overlayFrom = null;
        Runnable r = overlayClosed;
        overlayClosed = null;
        if (r != null) r.run();
        settingsAgain(); // a sub-sheet of the Settings tab: a value may have changed
        return true;
    }

    // Story so far: the last three episodes watched with TMDB's overviews, and over them
    // Claude's summary of the series up to the last one: written once per episode on the home server
    // (voice-claude.py's /recap, Opus, nothing after it) and kept there. Away from home: the overviews only.
    void storySoFar(Home.Card c, List<int[]> last) {
        int[] upto = last.get(last.size() - 1);
        LinearLayout list = ux.sheet("Story so far", "Up to S" + upto[0] + " E" + upto[1], null);
        LinearLayout claude = ux.sheetRow("Previously", "Claude is writing it…", null, v -> {});
        TextView summary = (TextView) claude.getChildAt(1);
        summary.setMaxLines(60);
        if (!homeServer.isEmpty()) list.addView(claude); // Claude's recap: only with a home server
        list.addView(ux.section("Last time"));
        String lang = Sources.storyLang(langs());
        // its own thread, as play()'s: the recap may wait 150 s on the home server, and one of the pool's three would
        // sit there while posters and pages queue behind it
        thread("story", () -> {
            // the seasons of the last three, and the one before the last's (Claude's overviews)
            Set<Integer> numbers = new java.util.TreeSet<>();
            for (int[] e : last) numbers.add(e[0]);
            if (upto[0] > 1) numbers.add(upto[0] - 1);
            Map<Integer, List<Film.Episode>> seasons = new HashMap<>();
            for (int n : numbers) {
                try {
                    seasons.put(n, Film.season(new String(read(Film.seasonUrl(c, n, lang)), StandardCharsets.UTF_8)));
                } catch (IOException | JSONException e) {
                    Log.w(TAG, "story so far: season " + n, e);
                }
            }
            List<String[]> told = new ArrayList<>(); // [label, name, overview] up to the last one watched
            for (int n : numbers) {
                for (Film.Episode e : seasons.getOrDefault(n, List.of())) {
                    if (n < upto[0] || e.number() <= upto[1]) told.add(new String[] {"S" + n + " E" + e.number(),
                        e.name(), e.overview()});
                }
            }
            ui.post(() -> {
                if (!list.isAttachedToWindow()) return;
                for (int[] at : last) {
                    String label = "S" + at[0] + " E" + at[1];
                    String[] e = told.stream().filter(x -> x[0].equals(label)).findFirst().orElse(null);
                    LinearLayout r = ux.sheetRow(e == null || e[1].isEmpty() ? label : label + " · " + e[1],
                        e == null || e[2].isEmpty() ? "No summary on TMDB" : e[2], null, v -> {});
                    ((TextView) r.getChildAt(1)).setMaxLines(20);
                    list.addView(r);
                }
            });
            String text = null;
            if (homeServer.isEmpty()) return;
            if (told.isEmpty()) {
                ui.post(() -> summary.setText("TMDB isn't answering: try again in a minute."));
                return;
            }
            try {
                HttpURLConnection h = open("http://" + homeServer + "/recap", 150000); // Opus: ~20-40 s the first time
                // the home network: no answer in 5 s = not there (a computer just woken or busy took over 1.5 s); the
                // episodes are on the sheet meanwhile
                h.setConnectTimeout(5000);
                byte[] body = Film.recapBody(c, upto[0], upto[1], told, lang == null ? "English" : language(lang))
                    .getBytes(StandardCharsets.UTF_8);
                String[] auth = Voice.homeAuth(token(), body, System.currentTimeMillis());
                h.setRequestProperty("X-Home-Time", auth[0]);
                h.setRequestProperty("X-Home-Sig", auth[1]);
                Answer a = send(h, body, "application/json");
                if (a.code() == 200) text = new JSONObject(a.body()).getString("text");
                else Log.w(TAG, "story so far: Claude answered " + a.code());
            } catch (IOException | JSONException e) {
                Log.w(TAG, "story so far: no Claude", e);
            }
            String shown = text != null ? text : "Claude isn't answering (the home server): the episodes below.";
            ui.post(() -> summary.setText(shown));
        });
    }

    // The level shown; its pictures load as the layout pass after this calls updateWindow.
    void show(View top, String backdrop, List<ImageView> pics) {
        page.addView(top, -1, -1);
        pageTop = top;
        pageBackdropPath = backdrop;
        pageBackdrop.setImageDrawable(null);
        pictures = pics;
        shown++;
    }

    // The tabs on one glass pill, centered, the one shown lit: on Home, and on top of Search, Coming up and Settings
    // (tabs, not screens over Home). OK on another one switches to it.
    FrameLayout tabBar(String here) {
        LinearLayout tabs = new LinearLayout(this);
        int p4 = dp(4);
        tabs.setPadding(p4, p4, p4, p4);
        tabs.setBackground(ux.shape(pill, 0x14ffffff, 0x26ffffff));
        for (String name : List.of("Home", "Search", "Coming up", "Settings")) {
            TextView t = ux.tab(name, name.equals(here), v -> goTab(name, v));
            if (name.equals("Coming up")) t.setText(comingLabel(cub.comingOut()));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (tabs.getChildCount() > 0) lp.setMarginStart(p4);
            tabs.addView(t, lp);
            if (here.equals("Home")) homeTabs.put(name, t);
            if (name.equals(here)) tabs.setTag(t); // focused when the page has nothing else to focus
        }
        // Left and Right stay in the bar (the tabs, then What's new at its right end), as a row's cards (line())
        FrameLayout bar = new FrameLayout(this) {
            @Override
            public View focusSearch(View focused, int direction) {
                View next = super.focusSearch(focused, direction);
                boolean sideways = direction == FOCUS_LEFT || direction == FOCUS_RIGHT;
                return sideways && (next == null || !Ui.inside(next, this)) ? focused : next;
            }
        };
        bar.addView(tabs, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL));
        TextView bell = ux.tab("", false, null);
        bell.setOnClickListener(v -> bellSheet(bell));
        bell.setBackground(ux.glass(pill, 0x14ffffff, 0x26ffffff));
        bell(bell);
        bar.addView(bell, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        return bar;
    }

    // What's new's pill: "New  3" with what came since you last looked,
    // "New" with nothing since; gone while there is nothing at all.
    void bell(TextView b) {
        if (b == null) return;
        long seen = settings().getLong("bell_seen", 0);
        long fresh = cub.news().stream().filter(n -> n.t() > seen).count();
        SpannableStringBuilder s = new SpannableStringBuilder("New");
        if (fresh > 0) {
            s.append("  ").append(String.valueOf(fresh));
            s.setSpan(new Ui.Tint(Home.GREEN, 0xff0a7d3e), 5, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        b.setText(s);
        b.setVisibility(cub.news().isEmpty() ? View.GONE : View.VISIBLE);
    }

    // What's new: episodes out in your audio languages and films the people you follow announced, newest first; the
    // ones since you last looked over a line, the others dimmed under it. Opening it is looking: the count goes. OK
    // opens the film or series (the list comes with /mine: nothing runs in the background).
    void bellSheet(TextView from) {
        long seen = settings().getLong("bell_seen", 0);
        long fresh = cub.news().stream().filter(n -> n.t() > seen).count();
        LinearLayout list = ux.sheet("What's new", fresh == 0 ? "Nothing since you last looked" : fresh + " since you last "
            + "looked", null);
        LocalDate today = LocalDate.now();
        ZoneId zone = ZoneId.systemDefault();
        boolean ruled = false;
        for (Cub.News n : cub.news()) {
            boolean old = n.t() <= seen;
            if (old && !ruled && fresh > 0) {
                list.addView(ux.divider());
                ruled = true;
            }
            SpannableStringBuilder end = new SpannableStringBuilder(Cub.ago(LocalDateTime.ofInstant(Instant.ofEpochMilli(n.t()),
                zone), today));
            end.setSpan(new Ui.Tint(0xff9a9a9a, 0xff4a4a4a), 0, end.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            LinearLayout r = ux.sheetRow(n.head(), n.line(), end, v -> {
                closeOverlay();
                if (page == null) filmPage.openFilm(n.card(), from, null);
                else filmPage.showFilm(n.card(), null);
            });
            if (old) r.setAlpha(0.6f);
            list.addView(r);
        }
        settings().edit().putLong("bell_seen", System.currentTimeMillis()).apply();
        bell(from);
        if (from != bellTab) bell(bellTab);
    }

    static CharSequence comingLabel(long out) {
        SpannableStringBuilder coming = new SpannableStringBuilder("Coming up");
        if (out > 0) {
            coming.append("  ").append(String.valueOf(out));
            coming.setSpan(new Ui.Tint(Home.GREEN, 0xff0a7d3e), 11, coming.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return coming;
    }

    // A tab: its page in place of the one shown (Back from it: Home, on that tab). Home's Home: down to the rows.
    void goTab(String name, View tab) {
        if (page == null && name.equals("Home")) {
            View down = tab.focusSearch(View.FOCUS_DOWN);
            if (down != null) down.requestFocus();
            return;
        }
        if (page != null) {
            boolean restored = from == null; // a page restored after process death: no Home under it yet
            closePage();
            setContentView(scroll);
            if (restored) loadHome();
        }
        TextView at = homeTabs.get(name);
        switch (name) {
            case "Search" -> openSearch(at);
            case "Coming up" -> openComing(at);
            case "Settings" -> openSettings(at);
            default -> at.requestFocus();
        }
    }

    // A tab's page (Search draws its own): the tab bar, then what the tab shows, all scrolling. Nothing to focus in
    // it: the tab.
    LinearLayout tabPage(String here) {
        ScrollView sv = new ScrollView(this);
        sv.setSmoothScrollingEnabled(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(60), dp(24), dp(60), dp(36)); // Home's bar's place
        FrameLayout bar = tabBar(here);
        bar.setPadding(0, 0, 0, dp(24));
        list.addView(bar);
        sv.addView(list);
        pushLevel(sv, "", List.of(), false);
        list.post(() -> {
            if (list.findFocus() == null) ((View) bar.getChildAt(0).getTag()).requestFocus();
        });
        return list;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (Voice.isKey(keyCode)) { // held: Search listens until it is let go (onKeyUp)
            if (event.getRepeatCount() == 0) listen();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) { // ≡ on the remote: the settings sheet, over whatever is shown
            // not held down, not over a sheet or the grade bar (Settings itself; the language picker's close
            // reloads the sources), not while a link comes (DDD would start over Settings and come back to it)
            if (event.getRepeatCount() == 0 && overlay == null && !asking) {
                View on = page == null ? scroll.findFocus() : page.findFocus(); // ≡ on a Home poster: its own sheet
                if (page == null && on != null && on.getTag() instanceof Home.Card c) quick(c, on);
                else if (on != null && on.getTag() instanceof Cub.Played p) historySheet(p); // on a History row
                else if (settingsList == null || !settingsList.isAttachedToWindow()) showSettings(); // not on its tab
            }
            return true;
        }
        Runnable up = overlayBack;
        if (keyCode == KeyEvent.KEYCODE_BACK && closeOverlay()) {
            if (up != null) up.run();
            return true;
        }
        if (keyCode != KeyEvent.KEYCODE_BACK || page == null) return super.onKeyDown(keyCode, event);
        back();
        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (!Voice.isKey(keyCode)) return super.onKeyUp(keyCode, event);
        recording = false; // the recorder thread stops and hands over what was said
        return true;
    }

    // The page, its levels and its backdrop go with these; loaders still running see page != theirs and stop.
    void closePage() {
        page = null;
        restoring = false; // Back during a restore: its /card answer finds no page
        pageTop = null;
        pageBackdrop = null;
        levels.clear();
        if (preload != null) preload.cancel(true); // not started yet: no Torrentio and TorBox for nothing
        preload = null;
        pictures = List.of();
        shown++;
        pix.collectSoon(); // the page's bitmaps
    }

}
