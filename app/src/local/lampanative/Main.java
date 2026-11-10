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
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
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
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.CharacterStyle;
import android.text.style.ReplacementSpan;
import android.util.Log;
import android.util.StateSet;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
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
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PrimitiveIterator;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Home screen: Lampa's TMDB rows from the worker's /home, drawn with plain views. Memory rules from PLAN.md:
 * only pictures near the viewport of the level shown hold a bitmap, and none do while the app is in the background.
 */
public class Main extends Activity {
    static final String TAG = "LampaNative";
    static final String POSTER = "https://image.tmdb.org/t/p/w300"; // 300×450 = the card at density 2, no scaling
    // w780 looked soft on the 1080p screen; w1280 costs +6 MB and +20-40 ms CPU per page, original 2× the memory
    static final String BACKDROP = "https://image.tmdb.org/t/p/w1280";
    static final String PHOTO = "https://image.tmdb.org/t/p/w185"; // the cast's 185×278 px photos, cut to a circle
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
    final List<ImageView> posters = new ArrayList<>(); // Home's
    List<ImageView> pictures = List.of(); // the page's top level's: its poster, photos, collection posters
    final Set<ImageView> loading = new HashSet<>(); // UI thread only
    // Bumped when another level is shown: a load queued for one not shown any more skips its download.
    volatile int shown;
    final int[] at = new int[2];
    ScrollView scroll;
    LinearLayout rows;
    // Your rows (/mine) above the TMDB ones; their posters (in posters too) and the answer they show ("" none)
    LinearLayout mine;
    final List<ImageView> minePosters = new ArrayList<>();
    String mineShown = "";
    Map<String, String> cubMarks = Map.of(); // the films' marks, from /mine
    Map<String, String> cubGrades = Map.of(); // and their grades
    Map<String, String> cubLeft = Map.of(); // started films' seconds left, started series' "26 ep"
    Map<String, String> cubLearned = Map.of(); // films' learned percent (the Learn button)
    boolean learnBound; // /mine says the learn extension is there
    Map<String, String> cubPercent = Map.of(); // started films' percent watched: a Continue tile's bar
    Map<String, String> cubNew = Map.of(); // series with a new episode out: "S3 E5"
    Map<String, String> cubNext = Map.of(); // started series' episode to go on with: "S3 E5 · up next"
    List<Cub.Coming> cubComing = List.of(); // Coming up's
    Set<String> cubList = Set.of(); // what My list holds
    Set<String> cubSeen = Set.of(); // films played to 90 %: a genre page hides them
    Set<Integer> cubFollows = Set.of(); // the people you follow (TMDB's person ids)
    List<Cub.News> cubNews = List.of(); // What's new, newest first
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
    // Dropped posters are garbage that the tiny Java heap (~5 MB) rarely collects, and until then their pixels
    // (native) and GPU textures stay. One concurrent GC a second after scrolling stops (~33 ms on a pool thread,
    // 0.1 ms pause) frees them, and new posters reuse the textures. Measured by hand: native 37 → 24 MB.
    final Runnable collect = () -> pool.execute(Runtime.getRuntime()::gc); // collectSoon()
    String prefetched; // the film (its /card URL) whose page was last preloaded or opened
    volatile boolean stopped; // audioReads' thread reads it too
    static final int PLAY = 1;
    static final String DDD = "top.rootu.dddplayer";
    Timeline.Playing playing; // the playback DDD will answer about
    MeterView meter; // Settings → Stats overlay, while shown
    boolean soft; // Settings → Drawing: Software (pictures made their shown size: fit)
    static final int DIM = 0xff606060; // the backdrop's darkening (MULTIPLY)
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
    volatile String addon; // its address (the worker's /addon), asked the first time it refuses
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
        playing = saved == null ? null : Timeline.Playing.of(saved.getStringArray("playing")); // killed while DDD played
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
        scroll.getViewTreeObserver().addOnScrollChangedListener(this::updateWindow);
        scroll.getViewTreeObserver().addOnGlobalLayoutListener(this::updateWindow);
        // Killed while DDD played (or in the background): the film again, and its source list if it was open. Home
        // waits for the Back that closes the page: nothing built under it while DDD hands back.
        Home.Card film = saved == null ? null : Home.Card.of(saved.getStringArray("film"));
        if (film != null) openFilm(film, null, saved.getIntArray("sources"));
        else loadHome();
    }

    @Override
    protected void onStart() {
        super.onStart();
        boolean back = stopped; // false the first time: the page (a restored one too) loads its own backdrop
        stopped = false;
        if (settings().getBoolean("stats", false)) showMeter(true);
        if (back && playing == null) loadMine(); // Lampa may have played something meanwhile; DDD's: its answer
        updateWindow();
        if (back && page != null) showBackdrop(System.currentTimeMillis());
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
        collectSoon();
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
        async(() -> {
            try {
                // lang: Coming up's "out" is out in these audio languages (the app English, the audio
                // Russian)
                Answer r = send(withCub(open(Home.worker + "/mine?lang=" + langs(), 20000), a), null, null);
                if (r.code() != 200) throw new Status(r.code());
                ui.post(() -> {
                    if (account() != a) return; // signed out (or in again) meanwhile
                    showMine(r.body());
                    try {
                        Files.write(mineFile().toPath(), r.body().getBytes(StandardCharsets.UTF_8)); // ~2 KB
                    } catch (IOException e) {
                        Log.w(TAG, "mine not kept", e);
                    }
                });
            } catch (IOException e) {
                Log.w(TAG, "mine", e); // the rows shown stay
            }
        });
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
        cubMarks = Cub.marks(body);
        cubGrades = Cub.grades(body);
        cubNew = Cub.fresh(body);
        cubNext = Cub.next(body);
        cubComing = Cub.coming(body);
        long out = cubComing.stream().filter(x -> x.state().equals("out")).count();
        comingTab.setText(comingLabel(out));
        cubLeft = Cub.left(body);
        cubPercent = Cub.percent(body);
        cubLearned = Cub.learned(body);
        learnBound = Cub.learnBound(body);
        cubList = Cub.listed(body);
        cubSeen = Cub.seen(body);
        cubFollows = Cub.follows(body);
        cubNews = Cub.news(body);
        bell(bellTab);
        View old = page != null ? from : overlay != null ? overlayFrom : scroll.findFocus();
        Object was = old != null && inside(old, mine) ? old.getTag() : null;
        posters.removeAll(minePosters);
        minePosters.clear();
        // Home's TMDB rows are built once: their badges follow your grades and films started (a film finished
        // mustn't keep its "53m left" for the days the app stays open)
        for (ImageView p : posters) {
            if (p.getParent() instanceof FrameLayout f && cardOf(p).getTag() instanceof Home.Card c) badges(f, c, null);
        }
        mine.removeAllViews();
        int before = posters.size();
        for (Home.Row row : data) {
            TextView title = text(row.title(), 22, Color.WHITE);
            // Continue browsing as landscape tiles with how far in, the rest as posters
            boolean tiles = row.title().equals("Continue browsing");
            boolean rate = row.title().equals("Rate what you watched"); // OK grades it here (rateBar)
            LinearLayout line = strip(mine, title, (tiles ? dp(195) : cardW) + gap);
            for (Home.Card c : row.cards()) {
                View card = tiles ? continueTile(c, title) : card(c, title);
                if (rate) card.setOnClickListener(v -> rateBar(c, v, true));
                line.addView(card);
            }
            if (tiles) line.addView(historyTile = historyTile(title));
        }
        minePosters.addAll(posters.subList(before, posters.size()));
        View target = null;
        for (ImageView p : minePosters) {
            View card = cardOf(p);
            if (target == null && (was != null || old == null || old == firstCard)) target = card; // the first
            if (was instanceof Home.Card w && card.getTag() instanceof Home.Card c && c.id() == w.id()
                && c.type().equals(w.type())) {
                target = card;
                break;
            }
        }
        if (HISTORY.equals(was)) target = historyTile;
        if (target == null && was != null && !posters.isEmpty()) target = cardOf(posters.get(0));
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

    // The card a Home picture is on: picture → its frame (with the badges) → the halo → the card.
    static View cardOf(ImageView picture) {
        return (View) picture.getParent().getParent().getParent();
    }

    static boolean inside(View v, ViewGroup group) {
        for (View p = v; p != null; p = p.getParent() instanceof View parent ? parent : null) if (p == group) return true;
        return false;
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
        TextView retry = text(Home.worker.isEmpty() ? "No worker set: on a computer, run tools/connect.sh (README, Setup)."
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
            TextView title = text(row.title(), 22, Color.WHITE);
            LinearLayout line = strip(rows, title, stride);
            for (Home.Card c : row.cards()) line.addView(card(c, title));
        }
        if (!posters.isEmpty()) {
            firstCard = cardOf(posters.get(0));
            firstCard.requestFocus();
        }
    }

    // A row title and a strip under it that scrolls sideways by whole cards; returns the strip's line for the cards.
    // The strip starts a halo left of the title (its parent's padding is a halo less), so the pictures line up with
    // the title and the first card's halo isn't cut by the parent's padding.
    LinearLayout strip(LinearLayout parent, TextView title, int stride) {
        title.setPadding(halo, dp(16), 0, dp(8) - halo); // the halo's room above the pictures
        title.setTypeface(medium);
        parent.addView(title);
        HorizontalScrollView strip = new HorizontalScrollView(this) {
            // Every scroll the row makes for focus comes through here, so it lands on whole cards (LayoutTest).
            // A scroll in the focus listener instead would get overridden by this one.
            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) {
                return Layout.alignedScroll(getScrollX(), getWidth(), r.left, r.right, stride) - getScrollX();
            }
        };
        strip.setHorizontalScrollBarEnabled(false);
        strip.setSmoothScrollingEnabled(false); // jump: one redraw per press instead of a 15-frame animation
        LinearLayout line = line();
        strip.addView(line);
        parent.addView(strip);
        return line;
    }

    // A horizontal line of focusables (a row's cards, a film's buttons) that Left and Right never leave: at its ends
    // they stop, instead of jumping to the nearest card further left or right in another row, on the screen or off
    // it in a row scrolled sideways (the row "goes on" in the next one).
    LinearLayout line() {
        return new LinearLayout(this) {
            @Override
            public View focusSearch(View focused, int direction) {
                View next = super.focusSearch(focused, direction);
                boolean sideways = direction == FOCUS_LEFT || direction == FOCUS_RIGHT;
                return sideways && (next == null || !inside(next, this)) ? focused : next;
            }
        };
    }

    // A Home card: Home's poster window loads its poster; focus puts its row at the top.
    View card(Home.Card c, TextView rowTitle) {
        ImageView poster = new Pic(10 * dp, 0);
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
            percent = Integer.parseInt(cubPercent.getOrDefault(key, "0"));
        } catch (NumberFormatException e) {
            percent = 0;
        }
        Pic picture = new Pic(12 * dp, percent);
        picture.setTag(c.backdrop().isEmpty() ? null : TILE + c.backdrop()); // none: the gray stand-in stays
        posters.add(picture);
        FrameLayout frame = new FrameLayout(this);
        frame.addView(picture, w, h);
        String fresh = cubNew.get(key);
        if (fresh != null) frame.addView(chip(text("New · " + fresh, 15, 0xff111111), 0xff21d07a), badge(Gravity.TOP | Gravity.START));
        String left = Timeline.posterLeft(cubLeft.get(key)), next = cubNext.get(key); // a series: "S3 E5 · up next"
        return homeCard(c, tile(frame, w, h, 12 * dp, c.name(), next != null ? next : left != null ? left : c.year()),
            rowTitle);
    }

    // Home's card: focus puts its row at the top; OK opens the film.
    View homeCard(Home.Card c, View card, TextView rowTitle) {
        card.setTag(c); // the same film found again when your rows are drawn again
        card.setOnFocusChangeListener((v, focused) -> {
            if (focused) scroll.scrollTo(0, rowTitle.getTop()); // focused row at the top with its title, like Lampa
            prefetch(c, focused);
        });
        card.setOnClickListener(v -> openFilm(c, v, null));
        return card;
    }

    // A film page's Trailer: the worker's YouTube video in SmartTube when installed, else whatever opens YouTube links.
    void playTrailer(String video) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + video));
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
        return tile(frame, cardW, cardH, 10 * dp, c.name(), c.year());
    }

    // A poster's badges (all its frame holds but the picture), again when /mine changes them (showMine).
    void badges(FrameLayout frame, Home.Card c, String sure) {
        frame.removeViews(1, frame.getChildCount() - 1);
        String key = Timeline.card(c);
        String fresh = cubNew.get(key);
        String[] grade = fresh == null && sure == null ? Cub.posterGrade(cubGrades.get(key)) : null;
        if (fresh != null || sure != null || grade != null) {
            TextView badge = fresh != null ? text("New · " + fresh, 15, 0xff111111)
                : sure != null ? text(sure, 15, Color.WHITE)
                : text(grade[1], 15, Color.parseColor(grade[2])); // GRADES' own colors (CubTest checks each)
            frame.addView(chip(badge, fresh != null ? 0xff21d07a : 0xcc000000), badge(Gravity.TOP | Gravity.START));
        }
        String left = Timeline.posterLeft(cubLeft.get(key));
        if (left != null) {
            frame.addView(chip(text(left, 15, Color.WHITE), 0xcc000000), badge(Gravity.TOP | Gravity.END));
        }
        int score = c.score();
        if (score > 0) {
            frame.addView(chip(text(String.valueOf(score), 15, Home.scoreColor(score)), 0xcc000000),
                badge(Gravity.BOTTOM | Gravity.END));
        }
    }

    // A poster's badge: the text on its round background, padded.
    TextView chip(TextView t, int background) {
        t.setBackground(shape(6 * dp, background, 0));
        t.setPadding(dp(6), dp(2), dp(6), dp(2));
        return t;
    }

    // Where a badge sits: in a corner, 6 dp in (clear of the picture's round corner).
    FrameLayout.LayoutParams badge(int gravity) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, gravity);
        int in = dp(6);
        lp.setMargins(in, in, in, in);
        return lp;
    }

    // A card: a picture (w × h, corners radius; < 0: a circle) in its focus halo, a name and a gray line under it, up
    // to two lines each. The halo is a ring drawn by the card only while focused (the old frame sat inside the
    // picture, over the badges); the card reaches a halo past the picture on each side, inside the gap.
    View tile(View picture, int w, int h, float radius, String name, String sub) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setFocusable(true);
        FrameLayout ring = new FrameLayout(this);
        GradientDrawable on = shape(radius < 0 ? 0 : radius + halo, 0, Color.WHITE);
        on.setStroke(dp(3), Color.WHITE);
        if (radius < 0) on.setShape(GradientDrawable.OVAL);
        StateListDrawable states = new StateListDrawable();
        states.addState(FOCUSED, on);
        // Over the picture, not under it: software drawing paints a picture's corners the ground's color, and a corner
        // square reaches past a 10-12 dp curve into the ring (dark notches in it). Over it, the ring
        // hides them; in the 3 dp gap they are the ground's color anyway. A margin, not padding: a FrameLayout's
        // foreground is drawn inside its padding.
        ring.setForeground(states);
        ring.setDuplicateParentStateEnabled(true);
        FrameLayout.LayoutParams in = new FrameLayout.LayoutParams(w, h);
        in.setMargins(halo, halo, halo, halo);
        ring.addView(picture, in);
        card.addView(ring, w + 2 * halo, h + 2 * halo);
        TextView n = text(name, 15, Color.WHITE);
        n.setTextColor(new ColorStateList(new int[][] {FOCUSED, {}}, new int[] {Color.WHITE, 0xffdddddd}));
        n.setDuplicateParentStateEnabled(true);
        n.setMaxLines(2);
        n.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams under = new LinearLayout.LayoutParams(w, -2);
        under.setMargins(halo, dp(4), halo, 0); // 10 dp under the picture
        card.addView(n, under);
        TextView s = text(sub, 13, 0xff9a9a9a);
        s.setMaxLines(2);
        s.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams sub2 = new LinearLayout.LayoutParams(w, -2);
        sub2.setMargins(halo, 0, halo, 0);
        card.addView(s, sub2);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w + 2 * halo, -2);
        lp.setMargins(0, 0, gap - 2 * halo, 0); // the same stride as before: picture + gap
        card.setLayoutParams(lp);
        return card;
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

    // The film page; sources: after process death, the source list {season, episode} to open again over it.
    void openFilm(Home.Card c, View card, int[] sources) {
        openPage(card);
        prefetched = Film.url(c); // in the disk cache now: resting on this card again after Back reads nothing
        showFilm(c, sources);
    }

    // A page (a film, the settings) takes Home's place in the same window. Home's views stay, so Back is instant
    // and lands on from.
    void openPage(View from) {
        trim(posters, false);
        page = new FrameLayout(this);
        page.setBackgroundColor(SURFACE);
        pageBackdrop = new ImageView(this);
        pageBackdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        page.addView(pageBackdrop, -1, -1); // dimmed and faded once when it loads (showBackdrop), no overlay
        this.from = from;
        setContentView(page);
    }

    // A film over its backdrop (no poster, the backdrop shows through on the right), the
    // info, and under it (the page scrolls) the cast and the film's collection. Drawn at once from the card, again
    // from the worker's /card. A film of the collection goes on top as one more level; Back comes back here.
    void showFilm(Home.Card c, int[] sources) {
        long t = System.currentTimeMillis();
        ScrollView sv = new ScrollView(this);
        sv.setSmoothScrollingEnabled(false);
        sv.setTag(c); // the film level of this film (backTo, where())
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(60);
        column.setPadding(pad - halo, dp(84), pad, pad); // the info takes the halo back (strip())
        sv.addView(column);
        List<ImageView> pics = new ArrayList<>();
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(halo, 0, 0, 0);
        column.addView(info);
        fill(column, info, Film.of(c), c, pics, false);
        pushLevel(sv, c.backdrop(), pics, sources == null);
        restoring = sources != null;

        async(() -> {
            try {
                Film f = Film.parse(new String(read(Film.url(c)), StandardCharsets.UTF_8));
                ui.post(() -> {
                    restoring = false;
                    if (!alive(sv)) return;
                    // A film's first sources into the disk cache while the page is read, so Watch shows them at
                    // once (a series waits for its episode). Before the page's photos and posters on the pool.
                    if (c.type().equals("movie") && !f.imdb().isEmpty() && pageTop == sv
                        && !Sources.refusedLately(refusedAt, System.currentTimeMillis())) { // else a 502 for nothing
                        String url = Sources.url(f.imdb(), 0, 0, langs(), 0);
                        preload = pool.submit(() -> {
                            cache(url);
                            Log.i(TAG, "sources preloaded " + (System.currentTimeMillis() - t) + " ms after OK");
                            return null;
                        });
                    }
                    fill(column, info, f, c, pics, true);
                    loadMarks(f);
                    Log.i(TAG, "film details " + (System.currentTimeMillis() - t) + " ms after OK");
                    if (sources != null && pageTop == sv) reopen(f, c, sources[0], sources[1], null, false);
                });
            } catch (Exception e) {
                Log.w(TAG, "film " + c.id(), e); // the page keeps what the card gave it
                ui.post(() -> restoring = false);
                // ponytail: a restore whose /card fails stays without its backdrop; Back and in again shows it
            }
        });
    }

    // Whether this level is still on the page (on top or under it): loaders check it before drawing.
    boolean alive(View level) {
        return level == pageTop || levels.stream().anyMatch(l -> l.view() == level);
    }

    // The top level's backdrop: from the network the first time, from the disk cache when Back returns to it.
    void showBackdrop(long t) {
        View top = pageTop;
        String path = pageBackdropPath;
        if (path.isEmpty()) return;
        int sw = getResources().getDisplayMetrics().widthPixels, sh = getResources().getDisplayMetrics().heightPixels;
        async(() -> {
            try {
                // Dimmed, and faded to the ground on the left and at the bottom, in one pass when it loads (a few
                // ms on the pool; per frame: nothing). Software: at the screen's size; GPU: at its own (the GPU
                // scales it for free), then into the GPU only.
                Bitmap raw = decode(read(BACKDROP + path), false, 1);
                if (raw == null) throw new IOException("not a picture");
                Bitmap b = fit(raw, soft ? sw : raw.getWidth(), soft ? sh : raw.getHeight(), true);
                if (!soft) {
                    Bitmap g = b.copy(Bitmap.Config.HARDWARE, false);
                    b.recycle();
                    b = g;
                }
                Bitmap done = b;
                ui.post(() -> {
                    if (stopped || pageTop != top) return; // left meanwhile
                    pageBackdrop.setImageBitmap(done);
                    Log.i(TAG, "film backdrop " + (System.currentTimeMillis() - t) + " ms after OK");
                });
            } catch (IOException e) {
                Log.w(TAG, "backdrop " + path, e);
            }
        });
    }

    // The film's info; then its cast and its collection (but itself) under it, their pictures into pics.
    // loaded: f is the worker's /card, not the card's name and year alone.
    void fill(LinearLayout column, LinearLayout info, Film f, Home.Card c, List<ImageView> pics,
                      boolean loaded) {
        boolean series = c.type().equals("tv");
        info.removeAllViews();
        int textW = dp(540);
        TextView name = text(f.name(), 44, Color.WHITE);
        name.setTypeface(medium);
        name.setMaxWidth(textW);
        info.addView(name, wrap()); // a max width holds only for a wrapped one
        if (!f.original().isEmpty()) info.addView(text(f.original(), 18, 0xff9a9a9a));
        // a film: when it ends if started now
        String ends = series ? "" : Film.endsAt(f.runtime(), LocalTime.now());
        TextView line = text(ends.isEmpty() ? f.line() : f.line() + " · " + ends, 18, 0xffdddddd);
        line.setPadding(0, dp(8), 0, 0);
        line.setMaxWidth(textW);
        info.addView(line, wrap());
        for (List<Film.Score> scores : List.of(f.scores(), f.seasons())) {
            if (scores.isEmpty()) continue;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = dp(16);
            info.addView(scoreRow(scores), lp);
        }
        LinearLayout buttons = line();
        boolean watchable = !f.imdb().isEmpty() && (!series || !f.episodes().isEmpty()); // a series with no episodes yet: none
        // Settings → Play picks: Best cached: a film's ▶ Play starts the release picked
        // for it (pickSource, under the buttons), Sources lists them all
        boolean picks = !series && picks();
        TextView plays = text("", 15, 0xffdddddd);
        if (watchable) {
            Sources.Source[] best = new Sources.Source[1]; // ▶ Play's release, once the first sources are read
            TextView watch = button(picks ? "▶  Play" : "▶  Watch", v -> {
                if (series) new Episodes(this, f, c, Timeline.resume(f.episodes(), (s, e) -> marks.get(Timeline.key(f, s, e)))).show();
                else if (best[0] != null) play(f, c, 0, 0, best[0].hash(), best[0].fileIdx(), best[0].title(), (View) column.getParent());
                else showSources(f, c, 0, 0, null, false); // none cached, or not read yet
            });
            watch.setOnFocusChangeListener((v, focused) -> { // up from the rows: the whole top again
                if (focused) ((ScrollView) column.getParent()).scrollTo(0, 0);
            });
            View cont = continueButton(f, c, watch, column, picks);
            LinearLayout.LayoutParams before = new LinearLayout.LayoutParams(-2, -2);
            before.setMarginEnd(dp(12)); // its own gap: hidden, Watch starts the row
            buttons.addView(cont, before); // first, as the design: the one to press
            buttons.addView(watch);
            if (picks) buttons.addView(button("Sources", v -> showSources(f, c, 0, 0, null, false)), spaced());
            (cont.getVisibility() == View.VISIBLE ? cont : watch).requestFocus(); // marks known: Continue
            if (picks && loaded) pickSource(f, best, plays, (View) column.getParent());
        }
        // each film's trailer on its page, not a row of trailers
        if (!f.trailer().isEmpty()) {
            buttons.addView(button("Trailer", v -> playTrailer(f.trailer())),
                buttons.getChildCount() > 0 ? spaced() : new LinearLayout.LayoutParams(-2, -2));
        }
        // the page's top (alive() knows it), not column: given column, the status's answer was always dropped
        if (loaded && learnBound && !series && !f.imdb().isEmpty()) {
            learnButton(f, c, buttons, (View) column.getParent());
        }
        // Story so far: a series with episodes watched before the one Continue goes on with. Its tag shows it
        // once the marks are known (remark), as Continue's
        if (series && loaded && watchable) {
            Supplier<List<int[]>> last = () -> Film.before(f.episodes(),
                Timeline.continueTo(true, f, marks::get, last(c)), 3);
            TextView story = button("Story so far", v -> {
                List<int[]> now = last.get();
                if (!now.isEmpty()) storySoFar(c, now);
            });
            Runnable mark = () -> story.setVisibility(last.get().isEmpty() ? View.GONE : View.VISIBLE);
            story.setTag(mark);
            mark.run();
            buttons.addView(story, buttons.getChildCount() > 0 ? spaced() : new LinearLayout.LayoutParams(-2, -2));
        }
        // ⋯ and ★ need only the sign-in: a film with nothing to watch is still marked and graded (e.g.
        // The Miniaturist's "TV Movie" entry on TMDB has no IMDb id, so no sources, and had no ★ either)
        if (loaded && account() != null) {
            buttons.addView(gradeButton(c, f), buttons.getChildCount() > 0 ? spaced() : new LinearLayout.LayoutParams(-2, -2));
            buttons.addView(markButton(c), spaced());
            if (!watchable) buttons.getChildAt(0).requestFocus();
        }
        if (buttons.getChildCount() > 0) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = dp(20);
            info.addView(buttons, lp);
        }
        if (picks && watchable && loaded) {
            plays.setPadding(dp(4), dp(12), 0, 0);
            info.addView(plays);
        }
        if (loaded && !watchable) {
            TextView none = text(f.imdb().isEmpty() ? "Nothing to watch: TMDB gives this one no IMDb id, so no sources."
                + (!series ? " Look for it as a series too." : "") : "No episodes out yet.", 16, 0xff9a9a9a);
            none.setPadding(0, dp(10), 0, 0);
            info.addView(none);
        }
        TextView overview = text(f.overview(), 18, 0xffdddddd);
        overview.setPadding(0, dp(20), 0, 0);
        overview.setLineSpacing(0, 1.5f);
        overview.setMaxWidth(dp(480));
        overview.setMaxLines(3);
        overview.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(overview, wrap());
        TextView people = text(f.people(), 16, 0xff9a9a9a);
        people.setPadding(0, dp(12), 0, 0);
        people.setMaxWidth(textW);
        people.setMaxLines(2);
        people.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(people, wrap());
        if (!f.cast().isEmpty()) {
            // w185's 185×278 cut to a 184 px circle (92 dp): software drawing makes it that size once (a third less
            // memory a photo), the GPU crops and clips it
            int w = dp(92);
            LinearLayout cast = strip(column, text("Cast", 22, Color.WHITE), w + gap);
            // OK: their page (showPerson). A /card from before person ids (0) only scrolls the row.
            // ponytail: a 185×278 photo replacing the 300×450 stand-in costs a layout pass per photo loaded or
            // dropped (≤ 12); a photo-sized stand-in if gfxinfo shows it while scrolling to the Cast row
            Iterator<Film.Actor> members = f.cast().iterator();
            addRows(cast, () -> {
                if (!members.hasNext()) return null;
                Film.Actor p = members.next();
                ImageView photo = picture(PHOTO + p.photo(), pics, -1);
                View t = tile(photo, w, w, -1, p.name(), p.character());
                if (p.id() > 0) t.setOnClickListener(v -> showPerson(p, photo.getDrawable()));
                return t;
            }, 8); // 7.7 fit: built in one frame with the info, so no more than that
        }
        Iterator<Home.Card> parts = f.parts().iterator(); // the others: the worker leaves this one out
        if (parts.hasNext()) {
            addRows(strip(column, text(f.collection(), 22, Color.WHITE), cardW + gap),
                () -> parts.hasNext() ? filmCard(parts.next(), pics) : null, 7); // 5.5 fit; Bond has 27
        }
        // More like this: TMDB's recommendations of it, without what was graded or watched already
        Iterator<Home.Card> similar = f.similar().stream().filter(x -> !cubGrades.containsKey(Timeline.card(x))
            && !cubSeen.contains(Timeline.card(x))).iterator();
        if (similar.hasNext()) {
            addRows(strip(column, text("More like this", 22, Color.WHITE), cardW + gap),
                () -> similar.hasNext() ? filmCard(similar.next(), pics) : null, 7);
        }
    }

    // A film's card on a page's row (a collection, an actor's): OK opens it on top, or goes back to it when it is
    // open below.
    View filmCard(Home.Card c, List<ImageView> pics) {
        View card = cardView(c, picture(POSTER + c.poster(), pics, 10 * dp));
        card.setOnClickListener(v -> {
            if (!backTo(l -> isFilm(l, c))) showFilm(c, null);
        });
        return card;
    }

    // Back to the level already open below (a film: A → B → A; an actor) instead of one more, so the stack is never
    // deeper than the collection. False when it isn't open.
    boolean backTo(Predicate<View> level) {
        if (levels.stream().noneMatch(l -> level.test(l.view()))) return false;
        while (!level.test(pageTop)) popLevel(false);
        showBackdrop(System.currentTimeMillis()); // none for an actor (its backdrop is "")
        return true;
    }

    // An actor's page, one more level (OK on a cast photo): the photo, the name, born and where, what TMDB says of
    // them, then the worker's rows (Best rated, Films, Series) as Home cards. OK on one opens it on top, or goes back
    // to it when it is open below; an actor open below is gone back to the same way (backTo). Drawn at once from
    // the cast photo; the rest when /person answers. Sized so the first row fits under the top: focus on its first
    // card scrolls nothing (36 dp + the ~160 dp info + the row's title and cards ≈ 515 of the stick's 540 dp). The
    // photo is the cast row's w185 at the cast row's size: drawn 1:1, no scaling each frame in software drawing.
    // ponytail: film → actor → film → actor… stacks levels without bound (views only: a level under another keeps
    // no pictures); cap it if a deep chain shows in the heap
    void showPerson(Film.Actor a, Drawable photo) {
        if (backTo(l -> l.getTag() instanceof Film.Actor o && o.id() == a.id())) return;
        rowsPage(a, a.name(), PHOTO + a.photo(), photo, Person.url(a.id()), "OK on the photo");
    }

    // A genre's page (Search before typing: OK on a genre): its name, then the worker's rows (New, Popular, Best
    // rated films, then series) as an actor's.
    void showGenre(String genre) {
        if (backTo(l -> ("genre " + genre).equals(l.getTag()))) return;
        new Discover(this, genre).show();
    }

    // An actor's or a genre's page: tag (backTo finds it by it), the name, a photo (null: none), the worker's rows.
    void rowsPage(Object tag, String title, String photoUrl, Drawable photo, String url, String again) {
        ScrollView sv = new ScrollView(this);
        sv.setSmoothScrollingEnabled(false);
        sv.setTag(tag);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(60);
        column.setPadding(pad - halo, dp(36), pad, pad); // the photo and name take the halo back (strip())
        sv.addView(column);
        List<ImageView> pics = new ArrayList<>();
        LinearLayout content = new LinearLayout(this);
        content.setPadding(halo, 0, 0, 0);
        if (photoUrl != null) {
            Pic cover = (Pic) picture(photoUrl, pics, -1);
            cover.shaded = true; // the cast photo's bitmap, from over the film's backdrop: its corners aren't painted
            if (photo != null && photo != empty) cover.setImageDrawable(photo.getConstantState().newDrawable(getResources()));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(92), dp(92));
            lp.setMarginEnd(dp(40));
            content.addView(cover, lp);
        }
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(title, 36, Color.WHITE);
        name.setTypeface(medium);
        name.setSingleLine(); // one line, the born line one, the biography 3: the info stays ~160 dp
        name.setEllipsize(TextUtils.TruncateAt.END);
        if (tag instanceof Film.Actor a && account() != null) { // Follow beside the name: the info stays as high
            LinearLayout top = line();
            top.setGravity(Gravity.CENTER_VERTICAL);
            name.setMaxWidth(getResources().getDisplayMetrics().widthPixels * 2 / 3 - dp(220));
            top.addView(name, wrap());
            top.addView(followButton(a), spaced());
            info.addView(top);
        } else {
            info.addView(name);
        }
        content.addView(info, getResources().getDisplayMetrics().widthPixels * 2 / 3, -2);
        column.addView(content);
        pushLevel(sv, "", pics, false);
        async(() -> {
            try {
                Person p = Person.parse(new String(read(url), StandardCharsets.UTF_8));
                ui.post(() -> {
                    if (!alive(sv)) return;
                    fillPerson(column, info, p, pics);
                });
            } catch (Exception e) {
                Log.w(TAG, "rows " + url, e);
                ui.post(() -> {
                    if (alive(sv)) info.addView(text("TMDB didn't answer. Back, and " + again + " again.", 18, 0xff9a9a9a));
                });
            }
        });
    }

    // Follow on a person's page: their coming films (directed, or a lead) go to Coming up.
    // The worker keeps it per CUB account and profile (Lampa's own Subscribe is CUB Premium).
    TextView followButton(Film.Actor a) {
        TextView b = button("", null);
        b.setMinHeight(dp(44));
        b.setTextSize(18);
        Runnable label = () -> b.setText(cubFollows.contains(a.id()) ? "✓  Following" : "Follow");
        label.run();
        b.setOnClickListener(v -> {
            boolean on = !cubFollows.contains(a.id());
            Cub.Account acc = account();
            b.setText(on ? "Following…" : "Unfollowing…");
            async(() -> {
                int done = post("/mine/" + (on ? "follow/" : "unfollow/") + a.id(), acc, new byte[0], "text/plain");
                ui.post(() -> {
                    if (done == 204) {
                        Set<Integer> f = new HashSet<>(cubFollows);
                        if (on) f.add(a.id());
                        else f.remove(a.id());
                        cubFollows = f;
                        loadMine(); // Coming up: their films
                    } else {
                        Toast.makeText(this, "Didn't save. Try again.", Toast.LENGTH_LONG).show();
                    }
                    label.run();
                });
            });
        });
        return b;
    }

    // The actor's line and biography (3 lines: more push the first row under the screen), then the rows. A genre
    // has neither.
    void fillPerson(LinearLayout column, LinearLayout info, Person p, List<ImageView> pics) {
        if (!p.line().isEmpty() || !p.about().isEmpty()) {
            TextView line = text(p.line(), 18, 0xffdddddd);
            line.setPadding(0, dp(8), 0, dp(8));
            line.setSingleLine();
            line.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(line);
            TextView about = text(p.about(), 18, 0xffbbbbbb);
            about.setMaxLines(3);
            about.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(about);
        }
        fillRows(column, p.rows(), pics, true);
    }

    // A genre's rows without the films you watched (Cub.watched; the worker sends 100 a row, 20 are shown, so a row
    // stays full), or with them: the switch under the name, remembered (hidden by default).
    // An actor's or a genre's rows under the name; focus: its first card (not when the genre's switch has it).
    void fillRows(LinearLayout column, List<Home.Row> rows, List<ImageView> pics, boolean focus) {
        if (rows.isEmpty()) return;
        LinearLayout first = personRow(column, rows.get(0), pics, true);
        if (focus && first.getChildCount() > 0) first.getChildAt(0).requestFocus();
        // the rows under the screen a frame later: 21 cards in one frame would hold it ~35 ms (review); not when the
        // genre's switch drew them again meanwhile
        ui.post(() -> {
            if (first.isAttachedToWindow()) for (Home.Row r : rows.subList(1, rows.size())) personRow(column, r, pics, false);
        });
    }

    // Focus on a card puts its row at the top with its title, as Home's; the first row: the page's top, the name too
    // (back up, the row's title and the name stayed above the screen).
    LinearLayout personRow(LinearLayout column, Home.Row row, List<ImageView> pics, boolean first) {
        Iterator<Home.Card> cards = row.cards().iterator();
        TextView title = text(row.title(), 22, Color.WHITE);
        LinearLayout line = strip(column, title, cardW + gap);
        addRows(line, () -> {
            if (!cards.hasNext()) return null;
            View card = filmCard(cards.next(), pics);
            card.setOnFocusChangeListener((v, on) -> {
                if (on) ((ScrollView) column.getParent()).scrollTo(0, first ? 0 : title.getTop());
            });
            return card;
        }, 7); // 5.5 fit
        return line;
    }

    // A picture of the page's level (corners radius, < 0: a circle): the poster window loads and drops it like Home's
    // posters (updateWindow).
    ImageView picture(String url, List<ImageView> pics, float radius) {
        ImageView v = new Pic(radius, 0);
        v.setTag(url);
        pics.add(v);
        return v;
    }

    // A picture with round corners (radius px; < 0: a circle) and, on a Continue tile, how far in (percent, a bar along
    // its bottom). Software drawing paints both into the bitmap once when it loads (bake(), on the pool); the GPU clips
    // to the outline and draws the bar's two rects. Not loaded: a gray stand-in in the same shape.
    // Over a film's backdrop (shaded) corners painted the ground's color would show as dark squares: software drawing
    // fills the round shape with the picture instead (a bitmap shader: about a plain draw's cost, no new memory).
    final class Pic extends ImageView {
        final float radius;
        int percent; // an episode's still: set again when its mark changes (episodeRow)
        boolean shaded; // software drawing, over a backdrop: the shape is filled with the picture each frame
        boolean onRow; // in a row that turns white on focus (an episode's still): corners never painted, shaded too
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        Bitmap filled; // the bitmap fill's shader is of

        Pic(float radius, int percent) {
            super(Main.this);
            this.radius = radius;
            this.percent = percent;
            setScaleType(ScaleType.CENTER_CROP);
            setImageDrawable(empty);
            if (!soft) {
                setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View v, Outline o) {
                        if (radius < 0) o.setOval(0, 0, v.getWidth(), v.getHeight());
                        else o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
                    }
                });
                setClipToOutline(true);
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            boolean blank = getDrawable() == empty;
            float r = radius < 0 ? getWidth() / 2f : radius;
            if (blank) {
                c.drawRoundRect(0, 0, getWidth(), getHeight(), r, r, gray);
            } else if (soft && shaded && getDrawable() instanceof BitmapDrawable d && d.getBitmap() != null) {
                if (filled != d.getBitmap()) { // the picture at its shown size (fit()): no matrix
                    filled = d.getBitmap();
                    fill.setShader(new BitmapShader(filled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                }
                c.drawRoundRect(0, 0, getWidth(), getHeight(), r, r, fill);
            } else {
                super.onDraw(c);
            }
            if (blank || !soft) bar(c, getWidth(), getHeight(), percent, dp); // software: in the bitmap
        }

        // Software drawing: the bar, then (not over a backdrop) the corners painted the ground's color, into the
        // picture (made its shown size by fit(), so mutable) once.
        void bake(Bitmap b, boolean corners) {
            Canvas c = new Canvas(b);
            bar(c, b.getWidth(), b.getHeight(), percent, dp);
            if (!corners) return;
            Path p = new Path();
            if (radius < 0) p.addOval(0, 0, b.getWidth(), b.getHeight(), Path.Direction.CW);
            else p.addRoundRect(0, 0, b.getWidth(), b.getHeight(), radius, radius, Path.Direction.CW);
            p.setFillType(Path.FillType.INVERSE_WINDING);
            Paint ground = new Paint(Paint.ANTI_ALIAS_FLAG);
            ground.setColor(SURFACE);
            c.drawPath(p, ground);
        }
    }

    // How far in: two rects along the bottom, a dark track and the white part watched.
    static void bar(Canvas c, int w, int h, int percent, float dp) {
        if (percent <= 0) return;
        Paint p = new Paint();
        p.setColor(0x80000000);
        c.drawRect(0, h - 4 * dp, w, h, p);
        p.setColor(Color.WHITE);
        c.drawRect(0, h - 4 * dp, w * Math.min(percent, 100) / 100f, h, p);
    }

    // Lampa's rate line on one glass pill: each score in its color, its source beside it.
    // As many as fit the page's width (a long series' seasons: the first ones), none cut in half.
    View scoreRow(List<Film.Score> scores) {
        LinearLayout row = new LinearLayout(this);
        row.setBackground(shape(pill, 0x14ffffff, 0x26ffffff));
        int side = dp(18), between = dp(20), beside = dp(6);
        row.setPadding(side, dp(8), side, dp(8));
        float room = getResources().getDisplayMetrics().widthPixels - 120 * dp - 2 * side; // the page's 60 dp sides
        for (Film.Score s : scores) {
            TextView score = text(s.text(), 20, Home.scoreColor(s.score()));
            score.setTypeface(medium);
            TextView label = text(s.label(), 13, 0xffb0b0b0);
            float w = score.getPaint().measureText(s.text()) + beside + label.getPaint().measureText(s.label());
            if (row.getChildCount() > 0) w += between;
            if (w > room) break;
            room -= w;
            LinearLayout.LayoutParams lp = wrap();
            if (row.getChildCount() > 0) lp.setMarginStart(between);
            row.addView(score, lp);
            LinearLayout.LayoutParams beside2 = wrap();
            beside2.setMarginStart(beside);
            row.addView(label, beside2);
        }
        return row;
    }

    // ▶ Play's release: the biggest TorBox has cached in your languages (Sources.best), from the first sources the
    // page preloaded (read from the disk: no second ask). None: ▶ Play opens the list. level: the film's.
    void pickSource(Film f, Sources.Source[] best, TextView plays, View level) {
        String url = Sources.url(f.imdb(), 0, 0, langs(), 0);
        Future<?> before = preload;
        async(() -> {
            Sources.Source s = null;
            boolean read = false;
            try {
                if (before != null) before.get();
            } catch (Exception e) {
                // read below, from the network
            }
            try {
                s = Sources.best(Sources.parse(new String(read(url), StandardCharsets.UTF_8)).streams());
                read = true;
            } catch (Exception e) {
                Log.w(TAG, "pick " + f.imdb(), e); // ▶ Play opens the list
            }
            Sources.Source got = s;
            boolean known = read;
            ui.post(() -> {
                if (!alive(level)) return;
                best[0] = got;
                plays.setText(got != null ? playsLine(got) : known ? "Nothing cached in " + shownLangs()
                    + ": ▶ Play lists the sources" : "");
            });
        });
    }

    // Learn: asks the worker's /ext/learn extension (a service the user runs beside it) for the film's status once when
    // the page opens, and shows the button only when it answers. OK asks for it, or looks again while it is waiting.
    void learnButton(Film f, Home.Card c, LinearLayout buttons, View level) {
        Learn.State[] state = {null};
        boolean[] busy = {false}; // a request is out (disabling the button would drop its focus)
        String key = Timeline.card(c);
        TextView b = button("Learn", null);
        b.setVisibility(View.GONE);
        Runnable draw = () -> b.setText(Learn.label(state[0], cubLearned.get(key)));
        b.setOnClickListener(v -> {
            boolean start = state[0] == null || state[0].askable();
            if (busy[0]) return;
            busy[0] = true;
            b.setText("Learn · …");
            pool.execute(() -> {
                Learn.State got = null;
                try {
                    Answer a = start ? ask("/ext/learn/queue", Learn.queueBody(f.imdb(), c.id(), f.name())
                        .getBytes(StandardCharsets.UTF_8)) : ask("/ext/learn/status?imdb=" + f.imdb(), null);
                    if (a.code() == 200) got = Learn.parse(a.body());
                } catch (IOException e) {
                    Log.w(TAG, "learn", e);
                }
                Learn.State now = got;
                ui.post(() -> {
                    busy[0] = false;
                    if (!alive(level)) return;
                    if (now != null) state[0] = now;
                    draw.run();
                });
            });
        });
        buttons.addView(b, buttons.getChildCount() > 0 ? spaced() : new LinearLayout.LayoutParams(-2, -2));
        pool.execute(() -> {
            Learn.State got = null;
            try {
                Answer a = ask("/ext/learn/status?imdb=" + f.imdb(), null);
                if (a.code() == 200) got = Learn.parse(a.body());
            } catch (IOException e) {
                Log.w(TAG, "learn status", e);
            }
            Learn.State now = got;
            if (now == null) return; // no extension, or it is down: no button
            ui.post(() -> {
                if (!alive(level)) return;
                state[0] = now;
                draw.run();
                b.setVisibility(View.VISIBLE);
            });
        });
    }

    // "Plays [4K] [DV] [REMUX] · RU · 74.3 GB · ⚡ Cached": the picture's badges as the source rows' chips.
    CharSequence playsLine(Sources.Source s) {
        SpannableStringBuilder line = new SpannableStringBuilder("Plays  ");
        for (String b : Sources.picture(s)) {
            int start = line.length();
            line.append(b);
            line.setSpan(new Chip(b, Sources.badgeColor(b)), start, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            line.append("  ");
        }
        line.append(Sources.tracks(s.languages(), langs())).append(" · ").append(s.size()).append(" · ");
        int start = line.length();
        line.append(Sources.BOLT + " Cached");
        line.setSpan(new Tint(Home.GREEN, Home.GREEN), start, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return line;
    }

    // Continue, beside Watch while there is somewhere to go on (marks: the worker's points, DDD's): a series to the
    // episode being watched or the next one, a film being watched. Its source last played again when it is that
    // episode (or the film); else that episode's list, and the same release (a season's torrent) plays from it
    // at once when it has that episode, else it is only focused.
    // hides: watch is ▶ Play, which Continue stands in for while it shows (both resume; a shorter row).
    View continueButton(Film f, Home.Card c, View watch, View column, boolean hides) {
        boolean series = c.type().equals("tv");
        TextView b = button("", v -> {
            Timeline.Last last = last(c);
            int[] to = Timeline.continueTo(series, f, marks::get, last);
            if (to == null) return; // hidden meanwhile
            if (Timeline.replays(last, to)) {
                play(f, c, to[0], to[1], last.hash(), last.fileIdx(), last.name(), (View) column.getParent());
            } else {
                reopen(f, c, to[0], to[1], last != null ? last.hash() : null, true);
            }
        });
        Runnable mark = () -> {
            int[] to = Timeline.continueTo(series, f, marks::get, last(c));
            boolean was = b.getVisibility() == View.VISIBLE;
            b.setVisibility(to == null ? View.GONE : View.VISIBLE);
            if (hides && to == null) watch.setVisibility(View.VISIBLE);
            if (to == null) return;
            String left = series ? null : Timeline.posterLeft(cubLeft.get(Timeline.card(c)));
            b.setText(series ? "▶  Continue S" + to[0] + " E" + to[1] : left == null ? "▶  Continue" : "▶  Continue · " + left);
            if (!was && watch.hasFocus()) b.requestFocus(); // shown now: the one to press
            if (hides) watch.setVisibility(View.GONE);
        };
        b.setVisibility(View.GONE);
        b.setTag(mark);
        mark.run();
        return b;
    }

    // ⋯ beside Watch, a side sheet: Add to (Remove from) My list, then marks for leaving Continue browsing (
    // finish later, or not at all): Finish later, Not interested, Watched it, or none; the film's own ✓.
    // One character: the info column is half the screen and a worded button wrapped onto three lines next to
    // "Continue S1 E9". Home's rows follow.
    TextView markButton(Home.Card c) {
        return round(button("⋯", v -> marksSheet(c, true)));
    }

    // The ⋯ sheet: My list (withList: from the film page; ≡ on a poster has its own row), then the marks.
    void marksSheet(Home.Card c, boolean withList) {
        String now = cubMarks.get(Timeline.card(c)), name = Cub.markName(now);
        LinearLayout list = sheet(c.name(), name == null ? null : "Marked: " + name, null);
        if (withList) {
            boolean listed = cubList.contains(Timeline.card(c));
            list.addView(sheetRow(listed ? "Remove from My list" : "Add to My list", "Lampa's Bookmarks, its Later too",
                null, r -> toggleList(c)));
            list.addView(divider());
        }
        for (String[] m : Cub.MARKS) list.addView(sheetRow(m[1], m[2], check(m[0].equals(now)), r -> setMark(c, m[0])));
        if (now != null) list.addView(sheetRow("No mark", "Back in Continue browsing while it's being watched",
            null, r -> setMark(c, "none")));
    }

    // Add to (Remove from) My list: Lampa's Bookmarks (its Later too when removing).
    void toggleList(Home.Card c) {
        String key = Timeline.card(c);
        boolean listed = cubList.contains(key);
        postMine(c, listed ? "unbook" : "book", () -> {
            Set<String> l = new HashSet<>(cubList);
            if (listed) l.remove(key);
            else l.add(key);
            cubList = l;
        });
    }

    // ★ beside ⋯: the film's grade, Lampa's (shared through the worker's /grades), the star in its color; OK: the
    // grade bar under the buttons. A graded film leaves Home's Rate what you watched.
    TextView gradeButton(Home.Card c, Film f) {
        TextView b = button("★", null);
        paintGrade(b, cubGrades.get(Timeline.card(c)));
        Consumer<String> under = asked -> {
            View buttons = (View) b.getParent();
            int[] p = new int[2];
            buttons.getLocationInWindow(p); // the window is the screen (fullscreen), and the sheets' parent fills it
            int in = dp(16); // the bar's padding: its pills line up with the buttons
            gradeBar(c, f.original(), p[0] - in, p[1] + buttons.getHeight() + in, g -> paintGrade(b, g), null, asked);
        };
        b.setOnClickListener(v -> under.accept(null));
        askGradeKey = Timeline.card(c);
        askGradeRun = () -> {
            if (b.isAttachedToWindow()) under.accept(c.name());
        };
        ui.post(this::askGrade); // a page built after DDD answered (the app was killed while it played)
        return round(b);
    }

    // How was it?: back from DDD with a film watched to the end and not graded, its page's
    // grade bar opens by itself with Later. The film's ★ sets what to run (its page may be built after DDD answers).
    String askGradeFor; // the film ("movie_603") whose grade to ask, until its page shows
    String askGradeKey; // the film whose ★ is on the page, and what opens its bar
    Runnable askGradeRun;

    void askGrade() {
        if (askGradeFor == null || !askGradeFor.equals(askGradeKey) || askGradeRun == null) return;
        askGradeFor = null;
        ui.postDelayed(askGradeRun, 400); // laid out again after DDD
    }

    // OK on a Rate what you watched card: the grade bar under it, without opening the film (no /card, no backdrop).
    // Graded, the film leaves the row: focus goes on to the card after it first, so it stays in the row.
    // rate: from Rate what you watched (else ≡ on a poster: No grade instead of Open film, focus stays).
    void rateBar(Home.Card c, View card, boolean rate) {
        View strip = (View) card.getParent().getParent(); // under the row: a card beside may take two lines
        int[] p = new int[2];
        strip.getLocationInWindow(p);
        gradeBar(c, "", p[0], p[1] + strip.getHeight() + dp(12), g -> {
            if (!rate) return;
            ViewGroup line = (ViewGroup) card.getParent();
            int i = line.indexOfChild(card);
            View next = line.getChildAt(i + 1 < line.getChildCount() ? i + 1 : i - 1);
            if (next != null) next.requestFocus();
        }, rate ? () -> openFilm(c, card, null) : null, null);
    }

    // The grade bar: the grades as pills on an opaque bar at x, y, and what the focused
    // one does under them. saved: after a grade is saved (the page's ★ repainted). open: Home's "Open film" (null on
    // the film page, which offers No grade instead). asked: the film's name when it opened by itself after the film
    //: "How was it?" over the pills and Later after them.
    void gradeBar(Home.Card c, String original, int x, int y, Consumer<String> saved, Runnable open,
                          String asked) {
        String now = cubGrades.get(Timeline.card(c));
        LinearLayout pills = trapped();
        TextView hint = text("", 15, 0xffdddddd);
        String after = open != null ? ". Graded, " + c.name() + " leaves this row." : "";
        for (String[] g : Cub.GRADES) {
            SpannableStringBuilder label = new SpannableStringBuilder("★  " + g[1] + (g[0].equals(now) ? "  ✓" : ""));
            label.setSpan(new Tint(Color.parseColor(g[2]), 0xff111111), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            gradePill(pills, label, g[3] + after, hint, v -> setGrade(c, original, g[0], saved));
        }
        if (open == null && now != null) {
            gradePill(pills, "No grade", "Back in Rate what you watched", hint, v -> setGrade(c, original, null, saved));
        }
        if (open != null) {
            gradePill(pills, "Open film  →", "Its page: watch it, mark it", hint, v -> {
                closeOverlay();
                open.run();
            }).setBackground(glass(pill, 0, 0x26ffffff));
        }
        if (asked != null) {
            gradePill(pills, "Later", "Grade it from ★ on its page any time", hint, v -> closeOverlay())
                .setBackground(glass(pill, 0, 0x26ffffff));
        }
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        int in = dp(16);
        bar.setPadding(in, in, in, in);
        bar.setBackground(shape(24 * dp, 0xff2a2d30, 0x26ffffff));
        if (asked != null) {
            TextView title = text("How was " + asked + "?", 22, Color.WHITE);
            title.setPadding(dp(4), 0, 0, dp(12));
            bar.addView(title);
        }
        bar.addView(pills);
        hint.setPadding(dp(4), dp(12), 0, 0);
        bar.addView(hint);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2);
        lp.leftMargin = x;
        lp.topMargin = y;
        showOverlay(bar, lp, null);
    }

    TextView gradePill(LinearLayout pills, CharSequence label, String does, TextView hint,
                               View.OnClickListener action) {
        TextView p = button("", action);
        p.setText(label);
        p.setTextSize(17);
        p.setPadding(dp(16), 0, dp(16), 0);
        p.setMinHeight(dp(44));
        p.setBackground(glass(pill, 0x22ffffff, 0));
        p.setOnFocusChangeListener((v, focused) -> {
            if (focused) hint.setText(does);
        });
        LinearLayout.LayoutParams lp = wrap();
        if (pills.getChildCount() > 0) lp.setMarginStart(dp(8));
        pills.addView(p, lp);
        return p;
    }

    // A one-character button (★, ⋯): a 48 dp circle.
    TextView round(TextView b) {
        b.setSingleLine(); // sets its minimum to one line: the 48 dp after it
        b.setMinHeight(dp(48));
        b.setTextSize(22);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(dp(48));
        return b;
    }

    static void paintGrade(TextView b, String g) {
        String[] x = Cub.grade(g);
        b.setTextColor(new ColorStateList(new int[][] {{android.R.attr.state_focused}, {}},
            new int[] {0xff111111, x == null ? Color.WHITE : Color.parseColor(x[2])}));
    }

    void setGrade(Home.Card c, String original, String g, Consumer<String> saved) {
        Cub.Account a = account();
        View panel = overlay;
        Toast.makeText(this, "Saving…", Toast.LENGTH_SHORT).show();
        byte[] body = Cub.gradeBody(c, original, g, System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8);
        async(() -> {
            int done = post("/grades", a, body, "application/json");
            ui.post(() -> {
                if (done != 200) {
                    Toast.makeText(this, "The grade wasn't saved. Try again.", Toast.LENGTH_LONG).show();
                    return;
                }
                Map<String, String> m = new HashMap<>(cubGrades);
                if (g == null) m.remove(Timeline.card(c));
                else m.put(Timeline.card(c), g);
                cubGrades = m;
                if (overlay == panel) closeOverlay(); // not Back meanwhile
                saved.accept(g);
                loadMine(); // Home's Rate what you watched
            });
        });
    }

    void setMark(Home.Card c, String mark) {
        postMine(c, mark, () -> {
            Map<String, String> m = new HashMap<>(cubMarks);
            if (mark.equals("none")) m.remove(Timeline.card(c));
            else m.put(Timeline.card(c), mark);
            cubMarks = m;
        });
    }

    // POST /mine/<list>/<type>/<id> (a mark, My list); saved: what the app keeps once CUB took it. Home's rows follow.
    void postMine(Home.Card c, String list, Runnable saved) {
        Cub.Account a = account();
        View panel = overlay;
        Toast.makeText(this, "Saving…", Toast.LENGTH_SHORT).show();
        async(() -> {
            int done = post("/mine/" + list + "/" + c.type() + "/" + c.id(), a, new byte[0], "text/plain");
            ui.post(() -> {
                if (done != 204) {
                    Toast.makeText(this, "CUB didn't take it. Try again.", Toast.LENGTH_LONG).show();
                    return;
                }
                saved.run();
                if (overlay == panel) closeOverlay();
                loadMine(); // Home's rows: out of Continue browsing, into Finish later or My list
            });
        });
    }

    // The source last played of this film or series (play() keeps it), or null.
    Timeline.Last last(Home.Card c) {
        return Timeline.Last.of(getSharedPreferences("last", MODE_PRIVATE).getString(Timeline.card(c), null));
    }

    // The worker's points for a film or a series' episodes (one D1 read, ~0.2 s, Lampa's too): the marks.
    // ponytail: each remark() hashes every episode's key again (~3000 on a 1000-episode series, a few ms, only when
    // marks come); keep the keys per film if that shows up
    void loadMarks(Film f) {
        async(() -> {
            try {
                Answer a = ask("/timeline?hash=" + Timeline.keys(f), null); // up to 1000 keys: off the UI thread
                if (a.code() != 200) throw new IOException("HTTP " + a.code());
                Map<String, Timeline.Point> m = Timeline.marks(a.body());
                ui.post(() -> {
                    Timeline.merge(marks, m, played);
                    remark();
                });
            } catch (IOException | JSONException e) {
                Log.w(TAG, "marks", e); // no marks
            }
        });
    }

    LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(0, 0, 0, dp(36));
        return l;
    }

    // A row whose line (its child) shows watch marks: set now, and again by remark().
    static void marked(LinearLayout r, int child, Supplier<CharSequence> text) {
        Runnable set = () -> ((TextView) r.getChildAt(child)).setText(text.get());
        set.run();
        r.setTag(set);
    }

    // The marks again (seasons' and episodes' rows, Continue), on top or under it: the worker's points came, or DDD's.
    void remark() {
        if (pageTop != null) remark(pageTop);
        for (Level l : levels) remark(l.view());
    }

    static void remark(View v) {
        if (v.getTag() instanceof Runnable mark) mark.run();
        if (v instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) remark(g.getChildAt(i));
    }

    // TorBox sources, biggest first, in the chosen audio languages: the first 6 (5 rows fit on the screen), then the
    // rest is asked for at once and built as focus nears it (addRows).
    // prefer: the release to focus (Continue: the one last played), null: the first. autoplay (Continue): when that
    // release has this episode too (a season's torrent), it plays at once, no OK.
    void showSources(Film f, Home.Card c, int season, int episode, String prefer, boolean autoplay) {
        long t = System.currentTimeMillis();
        String title = title(f, season, episode);
        // The head: "Sources", what for and how many under it, the audio languages on a
        // button at the right (Up from the first source)
        LinearLayout list = pushList("Sources");
        TextView head = (TextView) list.getChildAt(0);
        list.removeView(head);
        head.setPadding(0, 0, 0, 0);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(head);
        TextView sub = text(title, 16, 0xff9a9a9a);
        sub.setPadding(0, dp(2), 0, 0);
        words.addView(sub);
        top.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        TextView audio = button("Audio: " + shownLangs().replace(",", ""), null);
        audio.setTextSize(18);
        audio.setMinHeight(dp(44));
        audio.setPadding(dp(20), 0, dp(20), 0);
        top.addView(audio);
        LinearLayout.LayoutParams under = new LinearLayout.LayoutParams(-1, -2);
        under.bottomMargin = dp(18);
        list.addView(top, 0, under);
        TextView status = text("Loading sources…", 16, 0xff9a9a9a);
        list.addView(status);
        // A fast Watch waits for the preload instead of asking TorBox twice.
        // ponytail: after a collection film and Back, it waits for that film's preload (≤ 0.8 s); key it by level
        // if that shows up
        Future<?> before = preload;
        preload = null;
        SourceList req = new SourceList(f, c, season, episode, title, langs(), list, status, sub, audio,
            new ArrayDeque<>(), t, prefer, autoplay, new int[] {AUDIO_READS});
        ((View) list.getParent()).setTag(req); // onSaveInstanceState: the list to open again after process death
        loadSources(req, 0, before);
    }

    static String title(Film f, int season, int episode) {
        return season > 0 ? f.name() + " · S" + season + " E" + episode : f.name();
    }

    // One source list being filled: what it lists, where, the rows not built yet, and when Watch was pressed.
    // audioReads[0]: how many more rows may ask the worker for their audio (rows are built on the UI thread).
    record SourceList(Film f, Home.Card c, int season, int episode, String title, String langs, LinearLayout list,
                      TextView status, TextView sub, TextView audio, ArrayDeque<Sources.Source> pending, long t,
                      String prefer, boolean autoplay, int[] audioReads) {}

    // Torrentio refusing the worker (its 502: 429 from Cloudflare's shared IPs) sends this and the next 10 minutes'
    // pages through the stick.
    void loadSources(SourceList req, int from, Future<?> before) {
        async(() -> {
            try {
                if (before != null) before.get();
            } catch (Exception e) {
                // the preload failed: asked again below
            }
            Sources.Page p;
            String imdb = req.f().imdb();
            String json = null;
            try {
                if (!Sources.refusedLately(refusedAt, System.currentTimeMillis())) {
                    try {
                        json = new String(read(Sources.url(imdb, req.season(), req.episode(), req.langs(), from)),
                            StandardCharsets.UTF_8);
                    } catch (Status e) {
                        if (!Sources.fallsBack(e.code)) throw e;
                        Log.w(TAG, "sources: the addon refused the worker (429), asked from here for 10 minutes");
                        refusedAt = System.currentTimeMillis();
                    }
                }
                if (json == null) { // the addon answers the stick's own IP: its list (~40-300 KB; its max-age keeps
                    // it on the disk for the next page) to the worker to filter and check with TorBox
                    if (addon == null) {
                        Answer at = ask("/addon", null);
                        if (at.code() != 200) throw new IOException("addon: HTTP " + at.code());
                        addon = at.body().trim();
                    }
                    // ponytail: each page posts the whole list again (and the preload's 502 isn't counted); have
                    // the worker keep a posted list ~10 min if refusals last
                    Answer a = ask(Sources.path(imdb, req.season(), req.episode(), req.langs(), from),
                        read(Sources.addon(addon, imdb, req.season(), req.episode())));
                    if (a.code() != 200) throw new IOException("sources: HTTP " + a.code());
                    json = a.body();
                }
                p = Sources.parse(json);
            } catch (Exception e) {
                Log.w(TAG, "sources " + imdb, e);
                ui.post(() -> {
                    if (req.status().getParent() == null) req.list().addView(req.status()); // under the first 6
                    req.status().setText("Can't load the sources. Press Back and try again.");
                });
                return;
            }
            ui.post(() -> {
                if (!alive((View) req.list().getParent())) return; // left
                for (Sources.Source s : p.streams()) { // each release once in all the pages: it plays once
                    if (req.autoplay() && s.hash().equals(req.prefer())) {
                        play(req.f(), req.c(), req.season(), req.episode(), s.hash(), s.fileIdx(), s.title(),
                            (View) req.list().getParent());
                    }
                }
                req.pending().addAll(p.streams());
                Supplier<View> next = () -> req.pending().isEmpty() ? null : sourceRow(req.pending().poll(), req);
                if (from > 0) {
                    addRows(req.list(), next, 5); // the others as focus gets near them
                    return;
                }
                req.list().removeView(req.status());
                String shown = req.langs().isEmpty() ? "all languages" : req.langs().replace(",", ", ");
                req.sub().setText(req.title() + " · " + p.total() + " found, biggest first");
                req.audio().setOnClickListener(v -> showLanguages(p.languages(), () -> {
                    if (pageTop != req.list().getParent()) return; // left meanwhile
                    popLevel(false); // no backdrop: the new list goes straight on top
                    showSources(req.f(), req.c(), req.season(), req.episode(), null, false);
                }));
                if (p.total() == 0) req.list().addView(text("Nothing in " + shown + ".", 16, 0xff9a9a9a));
                addRows(req.list(), next, p.streams().size());
                // the first source, under the head (Continue: the release last played, when among them); nothing
                // found: OK goes straight to the languages
                int at = 1 + Sources.focus(p.streams(), req.prefer());
                (p.streams().isEmpty() ? req.audio() : req.list().getChildAt(at)).requestFocus();
                Log.i(TAG, "sources " + (System.currentTimeMillis() - req.t()) + " ms after Watch");
                if (p.next() > 0) loadSources(req, p.next(), null);
            });
        });
    }

    // Rows built as focus nears the end of the list: count now, 5 more when one of the last 3 gets focus, until next
    // gives null. A list that isn't scrolled builds nothing more (a row is ~1.5-2 ms on the stick; 89 at once froze
    // a frame for 121 ms).
    void addRows(LinearLayout list, Supplier<View> next, int count) {
        for (int i = 0; i < count; i++) {
            View r = next.get();
            if (r == null) return;
            View.OnFocusChangeListener own = r.getOnFocusChangeListener(); // the row's own (a genre's: scroll)
            r.setOnFocusChangeListener((v, focused) -> {
                if (own != null) own.onFocusChange(v, focused);
                if (focused && list.indexOfChild(v) >= list.getChildCount() - 3) addRows(list, next, 5);
            });
            list.addView(r);
        }
    }

    // ⚡ Cached, the badges on their colors, then size · seeders · tracker · languages. A cached release whose audio
    // isn't read yet (the first AUDIO_READS of a list) asks the worker for it and gets its line again with the tracks.
    View sourceRow(Sources.Source s, SourceList req) {
        SpannableStringBuilder cached = new SpannableStringBuilder(Sources.cachedLabel(s.cached()));
        cached.setSpan(s.cached() == 1 ? new Tint(Home.GREEN, 0xff0a7d3e) : new Tint(0xff8a8a8a, 0xff4a4a4a), 0,
            cached.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        LinearLayout r = row(s.title(), sourceLine(s, req.langs()), cached, v -> play(req.f(), req.c(), req.season(),
            req.episode(), s.hash(), s.fileIdx(), s.title(), (View) req.list().getParent()));
        if (Sources.readsAudio(s, req.autoplay(), req.prefer(), req.audioReads()[0])) {
            req.audioReads()[0]--;
            TextView detail = r.findViewWithTag(DETAIL);
            View level = (View) req.list().getParent();
            audioReads.execute(() -> {
                // left before its turn (or covered by a page: read on the next Watch, Back doesn't rebuild the row),
                // or DDD plays: no TorBox add for a row nobody sees (alive() is the UI thread's; a level off screen is
                // detached; read off the UI thread, a stale answer only costs or skips one read). ponytail: one already
                // asking runs on (a socket waiting <= 30 s) and keeps the closed list's few views until it ends: cut
                // off, the worker would stop before taking its add out of the account again.
                if (stopped || !level.isAttachedToWindow()) return;
                try {
                    Answer a = ask(Sources.audioPath(s), null);
                    if (a.code() != 200) return; // 429: the worker's TorBox budget, next time; 502: not readable
                    Sources.Source read = s.withAudio(Sources.tracks(new JSONObject(a.body()).getJSONArray("tracks")));
                    ui.post(() -> {
                        if (alive(level)) detail.setText(sourceLine(read, req.langs()));
                    });
                } catch (Exception e) {
                    Log.w(TAG, "audio " + s.hash(), e);
                }
            });
        }
        return r;
    }

    static final int AUDIO_READS = 4; // the first page's cached ones (5 rows fit on the screen)
    static final String DETAIL = "detail";

    SpannableStringBuilder sourceLine(Sources.Source s, String langs) {
        SpannableStringBuilder line = new SpannableStringBuilder();
        List<Sources.Heard> heard = Sources.heard(s, langs);
        for (String b : Sources.badges(s, Sources.fileRead(s))) chip(line, b);
        // size and tracker before the audio groups: a one-line row cut at its end keeps what rows are compared by
        line.append(Sources.details(s, heard.isEmpty())).append("  ");
        for (Sources.Heard h : heard) { // its chips, then where they come from in the line's own color
            for (String c : h.chips()) chip(line, c);
            line.append(h.from()).append("  ");
        }
        return line;
    }

    void chip(SpannableStringBuilder line, String b) {
        int start = line.length();
        line.append(b);
        line.setSpan(new Chip(b, Sources.badgeColor(b)), start, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        line.append("  ");
    }

    // A text color that follows its view's focus (a list row turns white under focus): a span can't take a
    // ColorStateList, but the paint it gets carries the view's state.
    static final class Tint extends CharacterStyle {
        final int color, focused;

        Tint(int color, int focused) {
            this.color = color;
            this.focused = focused;
        }

        @Override
        public void updateDrawState(TextPaint tp) {
            tp.setColor(StateSet.stateSetMatches(FOCUSED, tp.drawableState) ? focused : color);
        }
    }

    // A source's badge (4K, DV, REMUX…): white text on a 6 dp round chip of its color. It draws its own label: in a
    // line cut with "…" the text it is handed has its letters swapped for U+FFFC (drawn as "OBJ").
    final class Chip extends ReplacementSpan {
        final String label;
        final int color;

        Chip(String label, int color) {
            this.label = label;
            this.color = color;
        }

        @Override
        public int getSize(Paint p, CharSequence t, int start, int end, Paint.FontMetricsInt fm) {
            return Math.round(p.measureText(label) + 14 * dp);
        }

        @Override
        public void draw(Canvas c, CharSequence t, int start, int end, float x, int top, int y, int bottom, Paint p) {
            int was = p.getColor();
            p.setColor(color);
            c.drawRoundRect(x, top, x + p.measureText(label) + 14 * dp, bottom, 6 * dp, 6 * dp, p);
            p.setColor(Color.WHITE);
            c.drawText(label, x + 7 * dp, y, p);
            p.setColor(was);
        }
    }

    // OK on a source: TorBox's link from the worker (~1 s) with the resume point (Lampa's key: the worker shares it
    // with Lampa), then DDD from there. DDD hands back where it stopped: onActivityResult.
    // level: the source list (or the film page, Continue) it was asked from: gone meanwhile, no DDD.
    void play(Film f, Home.Card c, int season, int episode, String hash, int fileIdx, String name,
                      View level) {
        if (asking) { // a second OK while the link comes: one DDD, one playback
            Toast.makeText(this, "Still getting the link…", Toast.LENGTH_SHORT).show();
            return;
        }
        asking = true;
        String key = Timeline.key(f, season, episode);
        String path = Timeline.playPath(hash, fileIdx, name, key, season > 0 ? Timeline.original(f) : null);
        Toast.makeText(this, "Getting the link…", Toast.LENGTH_SHORT).show();
        // Our DDD fork's skip times (intro, recap, credits) from the worker, asked alongside the link
        String skipPath = Timeline.segmentsPath(c, f.imdb(), season, episode, hash, fileIdx, name, Timeline.original(f));
        FutureTask<Answer> skipsAsked = skipPath == null ? null : new FutureTask<>(() -> ask(skipPath, null));
        // The season's stills and overviews for our DDD fork's next episode card and paused screen (the page has
        // read it: the HTTP cache's)
        FutureTask<List<Film.Episode>> stillsAsked = season < 1 ? null : new FutureTask<>(() -> Film.season(
            new String(read(Film.seasonUrl(c, season)), StandardCharsets.UTF_8)));
        // their own threads: the link's task below waits for them, and the pool's three may all be busy
        if (skipsAsked != null) new Thread(skipsAsked, "segments").start();
        if (stillsAsked != null) new Thread(stillsAsked, "stills").start();
        async(() -> {
            String message;
            try {
                Answer a = ask(path, null);
                Timeline.Link l = Timeline.link(a.code(), a.body());
                if (l.url() != null) {
                    Map<String, String> skips = Map.of();
                    try { // a second more at most: no skip button beats a late film
                        if (skipsAsked != null) {
                            Answer s = skipsAsked.get(1, TimeUnit.SECONDS);
                            skips = Timeline.segments(s.code(), s.body());
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "segments " + skipPath, e);
                    }
                    Map<String, String> skip = skips;
                    Map<Integer, Film.Episode> of = new HashMap<>(); // this season's episodes by number
                    try {
                        if (stillsAsked != null) {
                            for (Film.Episode e : stillsAsked.get(1, TimeUnit.SECONDS)) of.put(e.number(), e);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "stills " + c.id() + "/" + season, e);
                    }
                    String[] stills = l.playlist().stream().map(e -> e.season() == season && of.containsKey(e.episode())
                        && !of.get(e.episode()).still().isEmpty() ? STILL + of.get(e.episode()).still() : "")
                        .toArray(String[]::new);
                    String[] overviews = l.playlist().stream().map(e -> season < 1 ? f.overview()
                        : e.season() == season && of.containsKey(e.episode()) ? of.get(e.episode()).overview() : "")
                        .toArray(String[]::new);
                    ui.post(() -> {
                        asking = false;
                        // Back, Home or onDestroy meanwhile: no DDD (level null: from Home, ≡ on a poster)
                        if (stopped || level != null && !alive(level)) return;
                        // Continue plays it again (Last)
                        // ponytail: ~150 B a film played, in their own file (settings stays small); trim past ~1000
                        getSharedPreferences("last", MODE_PRIVATE).edit().putString(Timeline.card(c),
                            new Timeline.Last(season, episode, hash, fileIdx, name).save()).apply();
                        addHistory(c);
                        String[] titles = l.playlist().stream().map(e -> title(f, e.season(), e.episode()))
                            .toArray(String[]::new);
                        startPlayer(l.url(), title(f, season, episode), Sources.audio(langs()),
                            new Timeline.Playing(key, Timeline.card(c), season, episode, l.startMs(), l.playlist()),
                            titles, stills, overviews, skip);
                    });
                    return;
                }
                message = l.message();
            } catch (Exception e) {
                Log.w(TAG, "play " + hash, e);
                message = e instanceof FileNotFoundException ? e.getMessage() : "Can't get the link. Try again.";
            }
            String m = message;
            ui.post(() -> {
                asking = false;
                Toast.makeText(this, m, Toast.LENGTH_LONG).show();
            });
        });
    }

    // titles: the playlist's (an episode and the ones after it in its torrent): DDD goes on to the next by itself.
    // skips: the worker's skip times by "season:episode" ("0:0": a film).
    void startPlayer(String url, String title, String audio, Timeline.Playing p, String[] titles,
                             String[] stills, String[] overviews, Map<String, String> skips) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.parse(url), "video/*");
        i.setPackage(DDD);
        i.putExtra("title", title);
        if (titles.length > 1) { // position (below) is for the item at url, the first
            i.putExtra("video_list", p.playlist().stream().map(e -> Uri.parse(e.url())).toArray(Uri[]::new));
            i.putExtra("video_list.name", titles);
            i.putExtra("video_list.thumbnail", stills); // our DDD fork's next episode card ("" for none)
            i.putExtra("video_list.overview", overviews); // and its paused screen
            // Our DDD fork: a button to skip each one's intro, recap or credits ("" for none)
            i.putExtra("video_list.segments", p.playlist().stream()
                .map(e -> skips.getOrDefault(e.season() + ":" + e.episode(), "")).toArray(String[]::new));
        } else if (skips.containsKey(p.season() + ":" + p.episode())) {
            i.putExtra("segments", skips.get(p.season() + ":" + p.episode()));
        }
        if (titles.length <= 1 && overviews.length > 0 && !overviews[0].isEmpty()) i.putExtra("overview", overviews[0]);
        i.putExtra("position", p.startMs()); // ms; 0: from the start
        if (!audio.isEmpty()) i.putExtra("audio_lang", audio); // our DDD fork: this film's audio, "ru,uk"
        // Our DDD fork: the film's or series' tracks and speed as DDD last answered them (onActivityResult). The audio
        // key goes even empty: DDD then carries a track picked now on to the playlist's next episodes.
        SharedPreferences tracks = getSharedPreferences("tracks", MODE_PRIVATE);
        i.putExtra("audio_track", tracks.getString(p.card() + " audio", ""));
        String sub = tracks.getString(p.card() + " sub", null);
        if (sub != null) i.putExtra("subtitle_track", sub);
        if (tracks.contains(p.card() + " speed")) i.putExtra("speed", tracks.getFloat(p.card() + " speed", 1f));
        i.putExtra("return_result", true); // else DDD sends nothing back
        i.putExtra("return_progress", true); // our DDD fork: the position while it plays too (Progress)
        playing = p;
        getSharedPreferences("playing", MODE_PRIVATE).edit()
            .putString("now", new JSONArray(List.of(p.save())).toString()).putString("url", url).apply();
        try {
            startActivityForResult(i, PLAY);
        } catch (ActivityNotFoundException e) {
            playing = null;
            Toast.makeText(this, "DDD player isn't installed.", Toast.LENGTH_LONG).show();
        }
    }

    // DDD's answer: position and duration (ms), end_by "completion" or "user", and the address it stopped on (a
    // playlist: a later episode; the ones before it ended). Saved by the worker (stamped there: newest wins), where
    // Lampa picks it up; the worker also marks an episode watched when DDD goes on from it (/stream), in case DDD
    // never answers.
    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        Timeline.Playing started = playing;
        playing = null;
        if (request != PLAY || started == null || result != RESULT_OK || data == null) return;
        String at = data.getData() == null ? null : data.getData().toString();
        Timeline.Playing p = started.at(at);
        for (Timeline.Episode e : started.before(at)) {
            marks.put(e.key(), new Timeline.Point(0, 0, 100));
            played.add(e.key());
        }
        if (p != started) { // Continue goes on from the episode DDD stopped on, the same release
            SharedPreferences last = getSharedPreferences("last", MODE_PRIVATE);
            Timeline.Last was = Timeline.Last.of(last.getString(p.card(), null));
            Timeline.Episode e = started.playlist().stream().filter(x -> x.key().equals(p.key())).findFirst().orElseThrow();
            if (was != null) last.edit().putString(p.card(), new Timeline.Last(e.season(), e.episode(), was.hash(), -1,
                e.name()).save()).apply();
        }
        // The tracks and speed it played with, for the next time (startPlayer). No speed: DDD's default.
        SharedPreferences.Editor tracks = getSharedPreferences("tracks", MODE_PRIVATE).edit();
        String audio = data.getStringExtra("audio_track"), sub = data.getStringExtra("subtitle_track");
        if (audio != null) tracks.putString(p.card() + " audio", audio);
        if (sub != null) tracks.putString(p.card() + " sub", sub);
        if (data.hasExtra("speed")) tracks.putFloat(p.card() + " speed", data.getFloatExtra("speed", 1f));
        else tracks.remove(p.card() + " speed");
        tracks.apply();
        // DDD puts Kotlin Longs (checked: 4030 s of The Dark Knight came back)
        long[] t = p.saved(data.getLongExtra("position", 0), data.getLongExtra("duration", 0),
            "completion".equals(data.getStringExtra("end_by")));
        if (t == null) return;
        marks.put(p.key(), new Timeline.Point((int) t[0], (int) t[1], (int) t[2]));
        played.add(p.key());
        remark(); // the episode's row under the sources
        if (p.season() == 0 && t[2] >= 90 && !cubGrades.containsKey(p.card()) && account() != null) {
            askGradeFor = p.card();
            askGrade();
        }
        String json = p.body(t);
        async(() -> {
            try {
                int code = ask("/timeline", json.getBytes(StandardCharsets.UTF_8)).code();
                if (code != 204) Log.w(TAG, "timeline: HTTP " + code);
                ui.post(this::loadMine); // the film just played, in Continue browsing
            } catch (IOException e) {
                Log.w(TAG, "timeline", e); // ponytail: lost; keep it on the stick and send it later if this shows up
            }
        });
    }

    // Our DDD fork's report while it plays (return_progress: every 30 s, on pause, when it leaves the screen), saved
    // like its answer above. The answer comes only when DDD closes: the projector powers the stick by USB, and
    // switching it off mid-film lost the whole evening's position. Now 30 s at most.
    // Only the worker's point: Home's rows and marks catch up from /mine when the app is back.
    public static class Progress extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent data) {
            Timeline.Playing started;
            try {
                JSONArray a = new JSONArray(context.getSharedPreferences("playing", MODE_PRIVATE).getString("now", "[]"));
                String[] s = new String[a.length()];
                for (int i = 0; i < s.length; i++) s[i] = a.getString(i);
                started = s.length == 0 ? null : Timeline.Playing.of(s);
            } catch (JSONException e) {
                started = null;
            }
            // Only about the address playing: the worker signs it, so another app can't know it
            String at = data.getDataString();
            SharedPreferences now = context.getSharedPreferences("playing", MODE_PRIVATE);
            if (started == null || at == null || !at.equals(now.getString("url", ""))
                && started.playlist().stream().noneMatch(e -> e.url().equals(at))) return;
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
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (playing != null) out.putStringArray("playing", playing.save());
        // Where the page is: the top film, and its source list when that is above it (levels: top first)
        if (pageTop != null && where(pageTop, out)) return;
        for (Level l : levels) if (where(l.view(), out)) return;
    }

    // A film level or a source list saves its film (and the list's season, episode); other lists save nothing.
    static boolean where(View level, Bundle out) {
        if (level.getTag() instanceof SourceList s) {
            out.putStringArray("film", s.c().save());
            out.putIntArray("sources", new int[] {s.season(), s.episode()});
            return true;
        }
        if (level.getTag() instanceof Home.Card c) {
            out.putStringArray("film", c.save());
            return true;
        }
        return false;
    }

    // TMDB's films and series number apart (movie 1399 isn't series 1399): an actor's rows hold both.
    static boolean isFilm(View level, Home.Card film) {
        return level.getTag() instanceof Home.Card c && c.id() == film.id() && c.type().equals(film.type());
    }

    // The source list open before process death, over its film read again: Back goes the same way as before.
    // prefer: Continue's release to focus in the source list; autoplay: Continue's, it plays at once when there.
    void reopen(Film f, Home.Card c, int season, int episode, String prefer, boolean autoplay) {
        if (season > 0) {
            if (f.episodes().stream().noneMatch(x -> x.number() == season)) { // the series changed meanwhile: the
                showBackdrop(System.currentTimeMillis()); // film page, with its backdrop
                return;
            }
            new Episodes(this, f, c, new int[] {season, episode}).show();
        }
        showSources(f, c, season, episode, prefer, autoplay);
    }

    // A worker route only this device may call (X-Play-Token: tools/connect.sh put it in files/play_token): the
    // answer's status and body. Never cached.

    Answer ask(String path, byte[] json) throws IOException {
        return ask(path, json, "application/json");
    }

    Answer ask(String path, byte[] body, String type) throws IOException {
        HttpURLConnection c = open(Home.worker + path, 30000); // TorBox can take a while
        c.setRequestProperty("X-Play-Token", token());
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
        list.addView(text(cubComing.isEmpty() ? account() == null ? "Sign in to CUB in Settings: what's coming comes "
            + "from your history." : "Nothing new for the series you watch in the next four weeks." : "Next episodes of "
            + "the series you watch, and films from My list, for the next four weeks. The days are when an episode airs"
            + (audio == null ? "; Out once a release of it is." : " in English; " + audio + " audio usually follows a few "
            + "days later, and Out means a release with it is there."), 16, 0xff9a9a9a));
        LocalDate today = LocalDate.now();
        LinearLayout day = null;
        String at = null;
        View first = null;
        for (Cub.Coming x : cubComing) {
            if (!x.date().equals(at)) { // a day: its name and date on the left, its rows on the right
                at = x.date();
                String[] d = Cub.day(at, today);
                LinearLayout group = new LinearLayout(this);
                LinearLayout when = new LinearLayout(this);
                when.setOrientation(LinearLayout.VERTICAL);
                when.setPadding(0, dp(10), 0, 0);
                TextView name = text(d[0], 18, Color.WHITE);
                name.setTypeface(medium);
                when.addView(name);
                when.addView(text(d[1], 15, 0xff9a9a9a));
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
            end.setSpan(x.state().equals("out") ? new Tint(Home.GREEN, 0xff0a7d3e) : new Tint(0xff9a9a9a, 0xff4a4a4a), 0,
                end.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            LinearLayout r = row(x.card().name(), x.what(), end, v -> showFilm(x.card(), null));
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
        TextView picture = text("↺", 40, 0xff9a9a9a);
        picture.setGravity(Gravity.CENTER);
        picture.setBackground(shape(12 * dp, 0x14ffffff, 0x26ffffff));
        View card = tile(picture, w, h, 12 * dp, "History", "Played here and in Lampa");
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
        historyList.addView(text("Played here and in Lampa, newest first · ≡ marks one unwatched or removes it", 16,
            0xff9a9a9a));
        fillHistory(historyList, 0);
    }

    // History's rows (again after a change: focus on the row at that place).
    void fillHistory(LinearLayout list, int focus) {
        list.removeViews(2, list.getChildCount() - 2); // the title and its line stay
        Cub.Account a = account();
        TextView note = text(a == null ? "Sign in to CUB in Settings: History comes from your CUB account." : "Loading…",
            18, 0xffdddddd);
        note.setPadding(0, dp(16), 0, 0);
        list.addView(note);
        if (a == null) return;
        View level = (View) list.getParent();
        int tz = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000;
        async(() -> {
            try {
                Answer r = send(withCub(open(Home.worker + "/mine/history?tz=" + tz, 20000), a), null, null);
                if (r.code() != 200) throw new Status(r.code());
                List<Cub.Played> items = Cub.history(r.body());
                ui.post(() -> {
                    if (alive(level)) showHistory(list, note, items, focus);
                });
            } catch (IOException | JSONException e) {
                Log.w(TAG, "history", e);
                ui.post(() -> note.setText("Can't load History. Back, then open it again."));
            }
        });
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
                TextView name = text(d[0], 18, Color.WHITE);
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
            end.setSpan(new Tint(0xff9a9a9a, 0xff4a4a4a), 0, end.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            LinearLayout r = row(p.card().name(), historyLine(p), end, v -> showFilm(p.card(), null));
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
            s.setSpan(new Tint(Home.GREEN, 0xff0a7d3e), from, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else if (left != null) {
            s.append(" · ").append(left);
        }
        String[] g = Cub.posterGrade(cubGrades.get(Timeline.card(p.card())));
        if (g != null) {
            s.append(" · ");
            int from = s.length();
            s.append("★ ").append(g[1]);
            s.setSpan(new Tint(Color.parseColor(g[2]), 0xff111111), from, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (p.lampa()) s.append(" · in Lampa");
        return s;
    }

    // ≡ on a History row: its page, Mark unwatched (its points back to the start; Lampa takes them too), Remove (out
    // of CUB's history, so Lampa's too). Both are CUB's: the worker writes them.
    void historySheet(Cub.Played p) {
        boolean series = p.card().type().equals("tv");
        LinearLayout list = sheet(p.card().name(), historyLine(p).toString(), null);
        list.addView(sheetRow(series ? "Series page" : "Film page", null, null, v -> {
            closeOverlay();
            showFilm(p.card(), null);
        }));
        list.addView(divider());
        int at = historyShown.indexOf(p);
        if (!p.hashes().isEmpty()) {
            String body = new JSONArray(p.hashes()).toString();
            list.addView(sheetRow("Mark unwatched", (p.what().isEmpty() ? "Back to the start" : p.what() + " back to the "
                + "start") + ", here and in Lampa", null, v -> historyChange("/mine/unwatch",
                ("{\"hashes\":" + body + "}").getBytes(StandardCharsets.UTF_8), at)));
        }
        list.addView(sheetRow("Remove from history", "Lampa's history loses it too", null,
            v -> historyChange("/mine/unhistory/" + p.card().type() + "/" + p.card().id(), new byte[0], at)));
    }

    // A History change sent (204: CUB or the timeline took it), then the page and Home's rows again.
    void historyChange(String path, byte[] body, int at) {
        Cub.Account a = account();
        LinearLayout list = historyList;
        Toast.makeText(this, "Saving…", Toast.LENGTH_SHORT).show();
        async(() -> {
            int done = post(path, a, body, "application/json");
            ui.post(() -> {
                if (done != 204) {
                    Toast.makeText(this, "CUB didn't take it. Try again.", Toast.LENGTH_LONG).show();
                    return;
                }
                closeOverlay();
                if (list != null && alive((View) list.getParent())) fillHistory(list, Math.max(0, at));
                loadMine(); // Continue browsing, the posters' badges
            });
        });
    }

    // An out episode's "Out" → "⚡ Cached" once TorBox has a release of it in your audio languages (the design's):
    // the same first sources page as ▶ Play's pick.
    void cachedLabel(Cub.Coming x, TextView label) {
        String langs = langs();
        async(() -> {
            try {
                Film film = Film.parse(new String(read(Film.url(x.card())), StandardCharsets.UTF_8));
                if (film.imdb().isEmpty()) return;
                Sources.Page p = Sources.parse(new String(read(Sources.url(film.imdb(), x.season(), x.episode(), langs,
                    0)), StandardCharsets.UTF_8));
                if (Sources.best(p.streams()) == null) return;
                SpannableStringBuilder cached = new SpannableStringBuilder(Sources.BOLT + " Cached");
                cached.setSpan(new Tint(Home.GREEN, 0xff0a7d3e), 0, cached.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                ui.post(() -> label.setText(cached));
            } catch (IOException | JSONException e) {
                Log.w(TAG, "coming " + x.card().id(), e); // stays "Out"
            }
        });
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
            s.results.addView(text("This app may not use the microphone. Allow it in Fire TV's Settings → Applications"
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
            c.setRequestProperty("X-Play-Token", Voice.homeToken(token()));
            Answer a = send(c, Voice.understandBody(text).getBytes(StandardCharsets.UTF_8), "application/json");
            if (a.code() == 200) return a.body();
            Log.w(TAG, "voice: Claude Code answered " + a.code());
        } catch (IOException | JSONException e) {
            Log.w(TAG, "voice: no Claude Code", e);
        }
        return null;
    }

    // A section's title on a page (Search's): 22 sp medium, 12 dp over what it heads.
    TextView heading(String title, int top) {
        TextView t = text(title, 22, Color.WHITE);
        t.setTypeface(medium);
        t.setPadding(0, top, 0, dp(12));
        return t;
    }

    // A keyboard key, `wide` keys wide.
    TextView key(String label, int wide, View.OnClickListener action) {
        TextView k = button(label, action);
        int size = dp(52), high = dp(46), space = dp(4); // 46: the tab bar over them
        k.setBackground(glass(12 * dp, 0x22ffffff, 0)); // a rounded square, no edge
        k.setTypeface(Typeface.DEFAULT);
        k.setMinHeight(0);
        k.setPadding(0, 0, 0, 0);
        k.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size * wide + (wide - 1) * space, high);
        lp.setMargins(0, 0, space, space);
        k.setLayoutParams(lp);
        return k;
    }

    // ≡ on a Home poster (fewer presses to play, for no memory): the film's sheet, no page
    // opened, no backdrop. A film: ▶ Play (Play picks' release, Settings), its Sources; a series: its episodes from
    // the one to go on with. Then the page, My list, the grade (the bar under the row), the mark, and Settings. Its
    // /card and first sources mostly come from the disk (the poster's prefetch).
    void quick(Home.Card c, View card) {
        boolean series = c.type().equals("tv");
        String key = Timeline.card(c);
        LinearLayout list = sheet(c.name(), c.year(), null);
        TextView sub = (TextView) list.getChildAt(1);
        Film[] film = new Film[1];
        Sources.Source[] best = new Sources.Source[1];
        TextView playLine = null, sourcesLine = null;
        if (series) {
            String next = cubNext.get(key);
            list.addView(sheetRow("▶  Episodes", next, null, v -> {
                if (film[0] == null) return;
                closeOverlay();
                openPage(card);
                loadMarks(film[0]);
                new Episodes(this, film[0], c, Cub.episode(next)).show();
            }));
        } else {
            LinearLayout play = sheetRow("▶  Play", picks() ? "Finding a source…" : "The sources list", null, v -> {
                if (film[0] == null) return;
                closeOverlay();
                if (best[0] != null) {
                    play(film[0], c, 0, 0, best[0].hash(), best[0].fileIdx(), best[0].title(), null);
                } else {
                    openPage(card);
                    showSources(film[0], c, 0, 0, null, false);
                }
            });
            LinearLayout sources = sheetRow("Sources", shownLangs(), null, v -> {
                if (film[0] == null) return;
                closeOverlay();
                openPage(card);
                showSources(film[0], c, 0, 0, null, false);
            });
            list.addView(play);
            list.addView(sources);
            playLine = (TextView) play.getChildAt(1);
            sourcesLine = (TextView) sources.getChildAt(1);
        }
        list.addView(sheetRow("Film page", null, null, v -> {
            closeOverlay();
            openFilm(c, card, null);
        }));
        if (account() != null) {
            list.addView(divider());
            list.addView(sheetRow(cubList.contains(key) ? "Remove from My list" : "Add to My list", null, null,
                v -> toggleList(c)));
            String[] g = Cub.grade(cubGrades.get(key));
            SpannableStringBuilder star = new SpannableStringBuilder("★  Your grade");
            if (g != null) star.setSpan(new Tint(Color.parseColor(g[2]), 0xff111111), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            list.addView(sheetRow(star, null, g == null ? "None" : g[1], v -> {
                rateBar(c, card, false);
                overlayBack = () -> quick(c, card);
            }));
            String m = Cub.markName(cubMarks.get(key));
            list.addView(sheetRow("Mark", null, m == null ? "None" : m, v -> {
                marksSheet(c, false);
                overlayBack = () -> quick(c, card);
            }));
        }
        list.addView(divider());
        list.addView(sheetRow("⚙  Settings", null, null, v -> {
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
        LinearLayout list = sheet("Settings", null, null);
        settingsRows(list, footer(list), true);
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
        settingsHelp = text("", 18, 0xff9a9a9a);
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
        list.addView(divider());
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
        list.addView(divider());
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
        async(() -> { // TorBox's plan and torrents (~0.5 s)
            String line;
            try {
                Answer t = ask("/torbox", null);
                JSONObject o = new JSONObject(t.body());
                line = t.code() == 200 ? o.getString("plan") + " · " + o.getInt("torrents") + " torrents" : "Not reachable";
            } catch (IOException | JSONException e) {
                line = "Not reachable";
            }
            String shown = line;
            ui.post(() -> {
                torboxLine = shown;
                if (torbox.isAttachedToWindow()) ((TextView) torbox.getChildAt(1)).setText(shown);
                else settingsAgain(); // the tab's rows drawn again meanwhile
            });
        });
    }

    @SuppressLint("DiscouragedApi") // by name: build.sh makes no R class
    Drawable drawable(String name) {
        return getDrawable(getResources().getIdentifier(name, "drawable", getPackageName()));
    }

    // A setting's row: its name and value on one line; focused, what it does at the sheet's foot.
    LinearLayout setting(LinearLayout list, TextView help, String title, String value, String does,
                                 View.OnClickListener action) {
        LinearLayout r = sheetRow(title, null, value, action);
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
        LinearLayout list = sheet("Profile", "Lampa uses the main one unless switched there", null);
        TextView wait = text("Asking CUB…", 16, 0xff9a9a9a);
        wait.setPadding(dp(12), 0, 0, 0);
        list.addView(wait);
        View panel = overlay;
        async(() -> {
            try {
                List<Cub.Profile> all = Cub.profiles(cub("profiles/all", null, a.token()).body());
                ui.post(() -> {
                    if (overlay != panel) return;
                    list.removeView(wait);
                    for (Cub.Profile p : all) {
                        list.addView(sheetRow(p.name(), p.main() ? "Main profile" : null, check(p.id() == a.profile()), v -> {
                            Cub.Account now = new Cub.Account(a.token(), p.id(), p.name());
                            try {
                                Files.write(cubFile().toPath(), now.save().getBytes(StandardCharsets.UTF_8));
                            } catch (IOException e) {
                                Toast.makeText(this, "Couldn't save it: " + e.getMessage(), Toast.LENGTH_LONG).show();
                                return;
                            }
                            cubAccount = Optional.of(now);
                            closeOverlay();
                            loadMine(); // that profile's rows
                        }));
                    }
                    panel.requestFocus();
                });
            } catch (IOException | JSONException e) {
                ui.post(() -> wait.setText("CUB doesn't answer. Try again later."));
            }
        });
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

    File cubFile() {
        return new File(getFilesDir(), "cub");
    }

    void signOut(Runnable done) {
        LinearLayout list = sheet("CUB account", account().name().isEmpty() ? null : account().name(), null);
        list.addView(sheetRow("Sign out", "History leaves Home; CUB keeps it, and Lampa stays signed in", null, v -> {
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
        LinearLayout list = sheet("Audio languages", "OK adds or removes one.", () -> {
            if (reload != null && !langs().equals(before)) reload.run();
        });
        list.addView(sheetRow("All languages", null, null, v -> {
            setLangs("");
            closeOverlay();
        }));
        list.addView(divider());
        for (Sources.Language l : all) {
            String code = l.code();
            list.addView(sheetRow(language(code), l.releases() < 0 ? code : code + " · " + l.releases() + " releases",
                check(Sources.selected(langs(), code)), v -> {
                    setLangs(Sources.toggle(langs(), code));
                    ((TextView) ((LinearLayout) v).getChildAt(1)).setText(check(Sources.selected(langs(), code)));
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

    // Story so far's language (in the language heard): the first audio language, "RU"; null: English
    // (English among them, or any)
    String storyLang() {
        List<String> codes = Sources.codes(langs());
        return codes.isEmpty() || codes.contains("EN") ? null : codes.get(0);
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
        TextView head = text(title, 24, Color.WHITE);
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
            trim(pictures, !pics.isEmpty()); // pictures: a film goes on top
            levels.push(new Level(pageTop, pageTop.findFocus(), pageBackdropPath, pictures));
            page.removeView(pageTop);
        }
        show(top, backdrop, pics);
        if (load) showBackdrop(System.currentTimeMillis()); // from the network the first time
    }

    // Back to the level below, with its focus. backdrop: false when another list goes on top at once (no decode
    // for nothing).
    boolean popLevel(boolean backdrop) {
        if (levels.isEmpty()) return false;
        Level below = levels.pop();
        page.removeView(pageTop);
        show(below.view(), below.backdrop(), below.pictures());
        if (backdrop) showBackdrop(System.currentTimeMillis()); // from the disk cache
        collectSoon(); // the level left
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

    // A group the arrows never leave (a side sheet, the grade bar's pills): at its edges they stop.
    LinearLayout trapped() {
        return new LinearLayout(this) {
            @Override
            public View focusSearch(View focused, int direction) {
                View next = super.focusSearch(focused, direction);
                return next != null && inside(next, this) ? next : focused;
            }
        };
    }

    // Story so far: the last three episodes watched with TMDB's overviews, and over them
    // Claude's summary of the series up to the last one: written once per episode on the home server
    // (voice-claude.py's /recap, Opus, nothing after it) and kept there. Away from home: the overviews only.
    void storySoFar(Home.Card c, List<int[]> last) {
        int[] upto = last.get(last.size() - 1);
        LinearLayout list = sheet("Story so far", "Up to S" + upto[0] + " E" + upto[1], null);
        LinearLayout claude = sheetRow("Previously", "Claude is writing it…", null, v -> {});
        TextView summary = (TextView) claude.getChildAt(1);
        summary.setMaxLines(60);
        if (!homeServer.isEmpty()) list.addView(claude); // Claude's recap: only with a home server
        list.addView(section("Last time"));
        String lang = storyLang();
        async(() -> {
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
                    LinearLayout r = sheetRow(e == null || e[1].isEmpty() ? label : label + " · " + e[1],
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
                h.setRequestProperty("X-Play-Token", Voice.homeToken(token()));
                Answer a = send(h, Film.recapBody(c, upto[0], upto[1], told, lang == null ? "English" : language(lang))
                    .getBytes(StandardCharsets.UTF_8),
                    "application/json");
                if (a.code() == 200) text = new JSONObject(a.body()).getString("text");
                else Log.w(TAG, "story so far: Claude answered " + a.code());
            } catch (IOException | JSONException e) {
                Log.w(TAG, "story so far: no Claude", e);
            }
            String shown = text != null ? text : "Claude isn't answering (the home server): the episodes below.";
            ui.post(() -> summary.setText(shown));
        });
    }

    // A side sheet (⋯, Settings, Audio languages): 392 dp on the right, its title, an optional gray line, then its
    // rows (sheetRow, section, divider). closed: runs when it goes.
    LinearLayout sheet(String title, String sub, Runnable closed) {
        LinearLayout panel = trapped();
        panel.setOrientation(LinearLayout.VERTICAL);
        int p12 = dp(12);
        panel.setPadding(p12, dp(24), p12, p12);
        panel.setBackground(shape(24 * dp, 0xff2a2d30, 0x26ffffff));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        TextView head = text(title, 24, Color.WHITE);
        head.setTypeface(medium);
        head.setPadding(p12, 0, p12, sub == null ? p12 : 0);
        list.addView(head);
        if (sub != null) {
            TextView s = text(sub, 15, 0xff9a9a9a);
            s.setPadding(p12, dp(2), p12, p12);
            list.addView(s);
        }
        ScrollView sv = new ScrollView(this);
        sv.setSmoothScrollingEnabled(false);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(list);
        panel.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        int m = dp(16);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(392), -1, Gravity.END);
        lp.setMargins(m, m, m, m);
        showOverlay(panel, lp, closed);
        return list;
    }

    // A sheet's line at its foot, over a rule (Settings: what the focused row does).
    TextView footer(LinearLayout list) {
        LinearLayout panel = (LinearLayout) list.getParent().getParent();
        View rule = new View(this);
        rule.setBackgroundColor(0x1affffff);
        panel.addView(rule, -1, Math.max(1, (int) dp));
        TextView t = text("", 15, 0xffdddddd);
        t.setPadding(dp(12), dp(12), dp(12), dp(4));
        panel.addView(t);
        return t;
    }

    // A sheet's row: a list row, tighter, its detail up to 3 lines (the sheet is narrow).
    LinearLayout sheetRow(CharSequence title, CharSequence detail, CharSequence end,
                                  View.OnClickListener action) {
        LinearLayout r = row(title, detail, end, action);
        int p12 = dp(12), p8 = dp(8);
        r.setPadding(p12, p8, p12, p8);
        ((LinearLayout.LayoutParams) r.getLayoutParams()).bottomMargin = dp(4);
        if (detail != null) {
            TextView d = (TextView) ((LinearLayout) (end != null ? r.getChildAt(0) : r)).getChildAt(1);
            d.setSingleLine(false);
            d.setMaxLines(3);
            d.setPadding(0, dp(2), 0, 0);
        }
        return r;
    }

    // A sheet's group heading (Settings' Display, Account, Playback).
    TextView section(String title) {
        TextView t = text(title, 15, 0xff9a9a9a);
        t.setTypeface(medium);
        t.setPadding(dp(12), dp(16), dp(12), dp(4));
        return t;
    }

    View divider() {
        View d = new View(this);
        d.setBackgroundColor(0x1affffff);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, (int) dp));
        lp.setMargins(dp(12), dp(4), dp(12), dp(8));
        d.setLayoutParams(lp);
        return d;
    }

    // The ✓ at a sheet row's end: the current mark, a chosen language ("" none); green, darker on a focused row.
    static CharSequence check(boolean on) {
        if (!on) return "";
        SpannableStringBuilder s = new SpannableStringBuilder("✓");
        s.setSpan(new Tint(Home.GREEN, 0xff0a7d3e), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
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

    static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(-2, -2);
    }

    // A button after another in a row: a gap before it.
    LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginStart(dp(12));
        return lp;
    }

    // A focusable text button (Watch, Search, Settings, the keyboard's keys): glass, a flat round shape, white at 13 %
    // with a 1 dp edge; white with dark text under focus, so it can't be missed (a lighter gray was).
    TextView button(String label, View.OnClickListener action) {
        TextView b = text(label, 20, Color.WHITE);
        b.setTypeface(medium);
        b.setPadding(dp(24), 0, dp(24), 0);
        b.setMinHeight(dp(48));
        b.setGravity(Gravity.CENTER);
        b.setBackground(glass(pill, 0x22ffffff, 0x26ffffff));
        b.setTextColor(new ColorStateList(new int[][] {FOCUSED, {}}, new int[] {0xff111111, Color.WHITE}));
        b.setFocusable(true);
        b.setOnClickListener(action);
        return b;
    }

    // The tabs on one glass pill, centered, the one shown lit: on Home, and on top of Search, Coming up and Settings
    // (tabs, not screens over Home). OK on another one switches to it.
    FrameLayout tabBar(String here) {
        LinearLayout tabs = new LinearLayout(this);
        int p4 = dp(4);
        tabs.setPadding(p4, p4, p4, p4);
        tabs.setBackground(shape(pill, 0x14ffffff, 0x26ffffff));
        for (String name : List.of("Home", "Search", "Coming up", "Settings")) {
            TextView t = tab(name, name.equals(here), v -> goTab(name, v));
            if (name.equals("Coming up")) t.setText(comingLabel(cubComing.stream().filter(x -> x.state().equals("out")).count()));
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
                return sideways && (next == null || !inside(next, this)) ? focused : next;
            }
        };
        bar.addView(tabs, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL));
        TextView bell = tab("", false, null);
        bell.setOnClickListener(v -> bellSheet(bell));
        bell.setBackground(glass(pill, 0x14ffffff, 0x26ffffff));
        bell(bell);
        bar.addView(bell, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        return bar;
    }

    // What's new's pill: "New  3" with what came since you last looked,
    // "New" with nothing since; gone while there is nothing at all.
    void bell(TextView b) {
        if (b == null) return;
        long seen = settings().getLong("bell_seen", 0);
        long fresh = cubNews.stream().filter(n -> n.t() > seen).count();
        SpannableStringBuilder s = new SpannableStringBuilder("New");
        if (fresh > 0) {
            s.append("  ").append(String.valueOf(fresh));
            s.setSpan(new Tint(Home.GREEN, 0xff0a7d3e), 5, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        b.setText(s);
        b.setVisibility(cubNews.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // What's new: episodes out in your audio languages and films the people you follow announced, newest first; the
    // ones since you last looked over a line, the others dimmed under it. Opening it is looking: the count goes. OK
    // opens the film or series (the list comes with /mine: nothing runs in the background).
    void bellSheet(TextView from) {
        long seen = settings().getLong("bell_seen", 0);
        long fresh = cubNews.stream().filter(n -> n.t() > seen).count();
        LinearLayout list = sheet("What's new", fresh == 0 ? "Nothing since you last looked" : fresh + " since you last "
            + "looked", null);
        LocalDate today = LocalDate.now();
        ZoneId zone = ZoneId.systemDefault();
        boolean ruled = false;
        for (Cub.News n : cubNews) {
            boolean old = n.t() <= seen;
            if (old && !ruled && fresh > 0) {
                list.addView(divider());
                ruled = true;
            }
            SpannableStringBuilder end = new SpannableStringBuilder(Cub.ago(LocalDateTime.ofInstant(Instant.ofEpochMilli(n.t()),
                zone), today));
            end.setSpan(new Tint(0xff9a9a9a, 0xff4a4a4a), 0, end.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            LinearLayout r = sheetRow(n.head(), n.line(), end, v -> {
                closeOverlay();
                if (page == null) openFilm(n.card(), from, null);
                else showFilm(n.card(), null);
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
            coming.setSpan(new Tint(Home.GREEN, 0xff0a7d3e), 11, coming.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
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

    // One of the tabs: the screen shown lit (here), the others plain; white under focus.
    TextView tab(String label, boolean here, View.OnClickListener action) {
        TextView t = text(label, 18, Color.WHITE);
        if (here) t.setTypeface(medium);
        t.setPadding(dp(22), 0, dp(22), 0);
        t.setMinHeight(dp(38));
        t.setGravity(Gravity.CENTER);
        t.setBackground(glass(pill, here ? 0x2effffff : 0, 0));
        t.setTextColor(new ColorStateList(new int[][] {FOCUSED, {}}, new int[] {0xff111111, here ? Color.WHITE : 0xffdddddd}));
        t.setFocusable(true);
        t.setOnClickListener(action);
        return t;
    }

    // A focusable list row: a title line, an optional detail line and an optional label at its end (a source's
    // ⚡ Cached); OK runs the action. Resting it draws nothing; focused, a white round row with dark text.
    LinearLayout row(CharSequence title, CharSequence detail, View.OnClickListener action) {
        return row(title, detail, null, action);
    }

    LinearLayout row(CharSequence title, CharSequence detail, CharSequence end, View.OnClickListener action) {
        LinearLayout r = new LinearLayout(this);
        r.setFocusable(true);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(FOCUSED, shape(14 * dp, Color.WHITE, 0));
        bg.addState(new int[] {android.R.attr.state_selected}, shape(14 * dp, 0x22ffffff, 0)); // a season shown
        r.setBackground(bg);
        r.setPadding(dp(18), dp(10), dp(18), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(6);
        r.setLayoutParams(lp);
        LinearLayout lines = r; // the title and detail: the row itself, or its first column when there's an end label
        if (end != null) {
            r.setGravity(Gravity.CENTER_VERTICAL);
            lines = new LinearLayout(this);
            lines.setDuplicateParentStateEnabled(true);
            r.addView(lines, new LinearLayout.LayoutParams(0, -2, 1));
        }
        lines.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(title, 18, Color.WHITE);
        t.setTextColor(new ColorStateList(new int[][] {FOCUSED, {}}, new int[] {0xff111111, Color.WHITE}));
        t.setDuplicateParentStateEnabled(true);
        t.setSingleLine();
        t.setEllipsize(TextUtils.TruncateAt.END);
        lines.addView(t);
        if (detail != null) {
            TextView d = text(detail, 15, 0xffb0b0b0);
            d.setTextColor(new ColorStateList(new int[][] {FOCUSED, {}}, new int[] {0xff4a4a4a, 0xffb0b0b0}));
            d.setDuplicateParentStateEnabled(true);
            d.setSingleLine();
            d.setEllipsize(TextUtils.TruncateAt.END);
            d.setPadding(0, dp(6), 0, 0);
            d.setTag(DETAIL); // a source row's, set again when its audio is read
            lines.addView(d);
        }
        if (end != null) {
            TextView e = text(end, 18, Color.WHITE);
            e.setTextColor(new ColorStateList(new int[][] {FOCUSED, {}}, new int[] {0xff111111, 0xffdddddd}));
            e.setDuplicateParentStateEnabled(true);
            e.setPadding(dp(16), 0, 0, 0);
            r.addView(e);
        }
        r.setOnClickListener(action);
        return r;
    }

    // A flat round shape: fill, and a 1 dp edge when edge isn't 0.
    GradientDrawable shape(float radius, int fill, int edge) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(radius);
        d.setColor(fill);
        if (edge != 0) d.setStroke(Math.max(1, (int) dp), edge);
        return d;
    }

    // A button's background: the shape in glass, white under focus.
    Drawable glass(float radius, int fill, int edge) {
        StateListDrawable bg = new StateListDrawable();
        bg.addState(FOCUSED, shape(radius, Color.WHITE, 0));
        bg.addState(new int[0], shape(radius, fill, edge));
        return bg;
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
        collectSoon(); // the page's bitmaps
    }

    TextView text(CharSequence s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    // Load the pictures of the level shown near the viewport (Layout.near), drop the rest: Home's posters, or the
    // page's top level's. 160 checks per scroll frame cost well under 1 ms. Only the level shown: views not shown
    // report (0, 0) on screen, so all 160 of Home's would load under a film page (+42 MB).
    void updateWindow() {
        if (stopped) return;
        boolean dropped = false;
        for (ImageView p : page == null ? posters : pictures) {
            boolean near = near(p);
            if (near && p.getDrawable() == empty && p.getTag() != null && !loading.contains(p)) load(p);
            else if (!near && p.getDrawable() != empty) {
                p.setImageDrawable(empty);
                dropped = true;
            }
        }
        if (dropped) collectSoon();
    }

    // A level going under (Home under a film page too) keeps only its pictures on the screen (all: none).
    void trim(List<ImageView> pics, boolean all) {
        var screen = getResources().getDisplayMetrics();
        for (ImageView p : pics) {
            p.getLocationOnScreen(at);
            if (all || !Layout.onScreen(at[0], at[1], p.getWidth(), p.getHeight(), screen.widthPixels,
                screen.heightPixels)) p.setImageDrawable(empty);
        }
        collectSoon();
    }

    // The dropped posters' memory goes a second after the last drop, not at some later GC (collect).
    void collectSoon() {
        ui.removeCallbacks(collect);
        ui.postDelayed(collect, 1000);
    }

    boolean near(ImageView p) {
        p.getLocationOnScreen(at);
        var screen = getResources().getDisplayMetrics();
        return Layout.near(at[0], at[1], p.getWidth(), p.getHeight(), screen.widthPixels, screen.heightPixels);
    }

    void load(ImageView p) {
        loading.add(p);
        String url = (String) p.getTag();
        int level = shown, w = soft ? p.getWidth() : 0, h = soft ? p.getHeight() : 0;
        boolean shaded = page != null && !pageBackdropPath.isEmpty() // a film's pictures, over its backdrop
            || p instanceof Pic pic && pic.onRow;
        async(() -> {
            Bitmap b = null;
            try {
                if (level == shown) { // else its level went away
                    b = fit(decode(read(url), !soft, url.startsWith(TILE) ? 2 : 1), w, h, false);
                    if (b != null && soft && p instanceof Pic pic) pic.bake(b, !shaded);
                }
            } catch (IOException e) {
                if (e instanceof Status st && (st.code == 404 || st.code == 410)) { // gone: gray, never asked again
                    Log.w(TAG, "picture " + url + " is gone");
                    ui.post(() -> {
                        loading.remove(p);
                        p.setTag(null);
                    });
                    return;
                }
                Log.w(TAG, "picture " + url, e); // stays gray until it's near again
            }
            Bitmap done = b;
            ui.post(() -> {
                loading.remove(p);
                // A view not shown (Home under a page, a level under another) can't tell if it's near; showing it
                // again reloads what is.
                if (stopped || !p.isAttachedToWindow() || !near(p)) return;
                if (done == null) {
                    if (level != shown) load(p); // skipped, but its level is back: Back came before this ran
                    return;
                }
                if (startedAt > 0) {
                    Log.i(TAG, "first poster " + (System.currentTimeMillis() - startedAt) + " ms after start");
                    startedAt = 0;
                }
                if (p instanceof Pic pic) pic.shaded = shaded;
                p.setImageBitmap(done);
            });
        });
    }

    // Software drawing scales a picture on every frame that draws it, and dims the backdrop on every frame (the GPU
    // does both for free): there each picture is made the size it is shown, once, on the pool. 0 × 0: as it is.
    // dim: a film's backdrop, also faded to the ground: on the left under the info (to 72 % of the width) and at the
    // bottom (to 46 % of the height), as the design's two gradients.
    static Bitmap fit(Bitmap b, int w, int h, boolean dim) {
        if (b == null || w <= 0 || h <= 0 || !dim && b.getWidth() == w && b.getHeight() == h) return b;
        float[] m = Layout.crop(b.getWidth(), b.getHeight(), w, h);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(out);
        c.save();
        c.translate(m[1], m[2]);
        c.scale(m[0], m[0]);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        if (dim) p.setColorFilter(new PorterDuffColorFilter(DIM, PorterDuff.Mode.MULTIPLY));
        c.drawBitmap(b, 0, 0, p);
        c.restore();
        b.recycle(); // never shown
        if (dim) {
            int clear = SURFACE & 0xffffff;
            // dithered: into RGB_565 a fade steps a level at once (a hard edge ~40 dp in, where the picture's few %
            // first round up)
            Paint fade = new Paint(Paint.DITHER_FLAG);
            fade.setShader(new LinearGradient(0, 0, w * 0.72f, 0, new int[] {SURFACE, 0xe0000000 | clear, clear},
                new float[] {0, 0.32f / 0.72f, 1}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w * 0.72f, h, fade);
            fade.setShader(new LinearGradient(0, h, 0, h * 0.54f, SURFACE, clear, Shader.TileMode.CLAMP));
            c.drawRect(0, h * 0.54f, w, h, fade);
        }
        return out;
    }

    // GPU drawing: HARDWARE, the picture only in the GPU (the decoder uploads it off the UI thread); RGB_565 kept a
    // CPU copy too (measured with PowerVR's own count, /proc/pvr/memtrack_stats: Home 70-73 → 55-57 MB, the film
    // page 100-102 → 58-61, same CPU and frames). Software drawing can't draw those: RGB_565, half of ARGB_8888
    // (posters and backdrops have no transparency), made their shown size by fit().
    // sample: 2 decodes at half the size (a Continue tile's w780 backdrop). Software: mutable, so Pic.bake() can
    // paint into a picture already its shown size (fit() returns it as it is).
    static Bitmap decode(byte[] data, boolean gpu, int sample) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = gpu ? Bitmap.Config.HARDWARE : Bitmap.Config.RGB_565;
        o.inMutable = !gpu;
        o.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }

}
