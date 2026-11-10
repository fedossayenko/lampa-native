# Lampa Native — plan

> The build log this app grew from, kept as it was written: each step's plan, measurements and decisions. "User"
> is the owner who asked for a change. Commit hashes are from the history before the first public commit, and the
> "canvas" boards, `lampa-home` (a launcher), `proto/` and the A/B scripts were never in this repository.

A Lampa-like app for the Fire TV stick that does what Lampa + the TorBox plugin do for us, natively,
with as little RAM and CPU as possible. Lampa stays installed as the fallback (with its own sources when TorBox is down)
and shares history with this app through the same CUB account. Everything is in English: UI, film names and
descriptions (TMDB `en-US`). Sources show every audio language until Settings → Audio languages picks some (RU/UA at first; all since step 10).

## The rule for every step

Investigate the lightest way → build it → measure on the stick with `tools/measure.sh <package>` → write the
numbers in the log (docs/measurements.md) → only then the next step. A step over budget gets fixed before moving on.

## The stick

1.7 GB RAM, 4 × ARMv8 2.0 GHz with 32-bit userland, PowerVR GE9215, UI 1920×1080 at density 2.0
(1 dp = 2 px), 60 Hz, Java heap limit 192 MB. ADB over Wi-Fi on port 5555.

## Budgets

| State | Lampa today | Budget |
|---|---|---|
| Menus (home, scrolling, film page, sources), RAM | ~290 MB (app ~170 + web engine ~120) | ≤ 80 MB (was 100; aim 75) |
| In background while DDD plays | 131 MB (app 72 + web engine 59) | ≤ 40 MB |
| CPU idle on a screen | step 0 | ~0 % |
| CPU while scrolling | step 0 | ≤ half of Lampa |
| Dropped frames while scrolling | step 0 | < 5 % |
| Cold start to posters on screen | step 0 | < 2 s |

Why not 50 MB in menus (measured 2026-10-31): any app drawing a 1080p window costs ~21 MB (lampa-home, text only:
GPU 10.8, native 1.7). Our fresh Home is 51 MB: GPU 17, native 20 (24 MB allocated, only 3.4 free), code 10. Going
lower means fewer posters kept near the screen, i.e. decoding again on every scroll: CPU for memory (and too
small a margin shows: ¼ screen down made posters blink in moving down, log). For 75: Home under a film page
keeps only the posters on the screen (to measure). Background (DDD playing) matters more: aim ≤ 35 MB, measured in step 4.

## Design rules (each one from a measurement or a check)

1. **Java + platform Views, no Kotlin, no Compose, no libraries.** Same as lampa-home (24 MB on the stick),
   built with plain SDK tools (`build.sh`, no Gradle). Every library adds dex, heap and class-loading CPU.
   If rows need view recycling, write a small one instead of pulling in androidx RecyclerView.
2. **Images are the main cost, so size them exactly.** Cards are 150×225 dp = 300×450 px → TMDB `w300`
   posters (34 KB, no scaling needed). GPU drawing decodes as HARDWARE (the picture only in the GPU: Home
   70 → 55 MB, the film page 100 → 58 counted with the GPU's own memory, log); software drawing as RGB_565
   (half of ARGB_8888). TMDB returns WebP when asked
   (`Accept: image/webp`, half the bytes) but it decodes 40 % slower on the stick, so posters stay JPEG.
   Film page backdrop `w1280`: `w780` looks soft at 1080p, `original` doubles the page's RAM (log, step 2).
   The page's poster is the card's own bitmap. Disk cache: platform `HttpResponseCache`. In memory only the
   posters near the viewport; no `LruCache` (the budget was met without one).
3. **No blur, no animations, no backdrop on Home**: a white halo marks focus, rows jump by whole cards
   (one redraw per press, not a 15-frame scroll animation). (Lampa's CSS has 20 backdrop-filter blurs,
   18 blur filters, 42 keyframe animations, 324 transitions, and it draws a blurred background on a canvas.)
   Measured on the film page too: a 200 ms backdrop fade doubles GPU time per frame and only gets ~5 frames.
4. **Nothing left in RAM while DDD plays.** `onStop` drops every poster bitmap, the film page's pictures and
   its backdrop; `onStart` reloads them from the disk cache. That is how the background budget is met.
5. **The stick does as little as possible; the worker does the rest.** The existing Cloudflare worker
   (`worker/`) holds the TMDB key and returns one small JSON per screen
   with only the fields we draw: home rows merged into one response, the film page into one ~1 KB answer;
   Torrentio search + TorBox cached check merged into one `/streams` response. Check the Workers CPU limit
   (free plan: 10 ms per request; trimming JSON is far below that).
6. **AOT-compile after install** (`cmd package compile -m speed -f <pkg>`) so there is no JIT warm-up CPU.
   Measure against the default.
7. **One process, no services, no timers.** Sync with CUB only on events (leaving the player, bookmarking).
8. **A poster carries at most one badge a corner** (user ask 2026-11-01). A card is 150 dp, about a tenth of the
   screen's width; 15 sp is the smallest text read from the sofa, so a fourth badge would cover the picture. Top left:
   New · S3 E5, else a voice match's %, else your grade (Lampa's tag); top right: a started film's time left; bottom
   right: the score. A badge only says what changes the choice of what to watch; what is the same for a whole row
   ("4K" in New in 4K) or already under the poster (year) stays off it, and series get no time left (which episode?).
9. **Left and Right stay in their row** (user 2026-11-01: at a row's end focus "went on" in the next row). Android
   picks the nearest focusable further left or right anywhere, cards of other rows included, even off the screen in
   a row scrolled sideways; rows and a film's buttons (`line()`) keep it, so at their ends a press does nothing.
   Search's results aren't one: Left from the first result goes back to the keyboard.

## Development (every change)

- **TDD.** Logic lives in classes that import nothing from `android.*` (`Home`, `Layout`, …): write the failing
  test in `app/test` first, then the code. Those tests run on the Mac's JVM in milliseconds
  (`tools/TestRunner.java`; `org.json` pinned by checksum). `Main.java` (1.3k lines; the screens are `Main.Part` classes beside it, 2000 lines a file at most) is the view glue and the
  code that outgrows that: logic found in it moves out to a tested class when the code around it is changed.
- **Java 21**: records, sealed types, pattern `switch`, text blocks. Checked through d8 on the stick
  (2026-10-31). It is compile-time only, so it adds no library and no runtime cost.
- **Every commit** (`.githooks/pre-commit` → `tools/check.sh`, a few seconds), run on the staged files, not the
  working folder: shellcheck, `javac -Xlint:all -Werror`, host tests.
- **Every push** (`.githooks/pre-push`, ~30 s), run on the files of each pushed branch's newest commit: the same, plus the APK build and
  Android Lint with warnings as errors (API levels against minSdk 30, which stays in the manifest for that; TV
  manifest rules). Verified: lint rejects an API 31 call.
- **Reviews are run by hand, not in a hook.** The first in-hook review took 6.5 minutes. `tools/review.sh`
  reviews the commits not yet on origin/main for problems, CPU/memory efficiency and simplifications.
  `tools/review.sh --deep` asks of the whole app whether a from-scratch rewrite would be lighter or simpler; run
  it at the end of every step. Both are read-only (`--tools Read,Grep,Glob`, no MCP servers, no user hooks) and
  exit 1 on `VERDICT: BLOCK`. Claude also reviews in-session before its own pushes.
- **Releases**: a tag `v*` builds the APK signed with the release key from the repo's secrets
  (`.github/workflows/release.yml`). The key, made once and kept safe (lost = every user reinstalls):
  `keytool -genkeypair -keystore release.jks -alias lampa-native -keyalg RSA -keysize 4096 -validity 36500`, then
  `base64 -i release.jks | gh secret set KEYSTORE_BASE64`, `gh secret set KS_PASS`, `gh secret set KEY_ALIAS`.
  The stick's own builds stay on the debug key (`tools/install.sh`): a release APK can't update them in place.
- Hooks: run `tools/install-hooks.sh` once per clone. It links them into `.git/hooks`; core.hooksPath is not used.
- **On the stick: performance only** (`tools/measure.sh`), by hand when the TV is free. Never from a hook, because
  it takes over the screen.
- **Emulator for looking, the stick for measuring** (user, 2026-10-31). `tools/emulator.sh` runs the app on an
  Android TV emulator made like the stick (1920×1080 at density 320, 1792 MB, 4 cores, 192 MB heap; Android 12,
  as no arm64 Android 11 TV image exists), signed in with the stick's CUB sign-in. 5.4 GB of SDK (emulator +
  image) plus the AVD; its GPU is the Mac's, so frame times and memory say nothing about the stick.

## Alternatives (`tools/review.sh --deep`, 2026-10-31)

Keep plain Java Views. A web engine alone is 59 MB in the background; Compose, Flutter and Leanback pay the same
~43 MB GPU and window-surface floor plus more code and runtime; an NDK UI keeps that floor and needs its own text
shaping; custom-drawn rows would only save Java heap, which is 3–6 MB (log rows 7, 51). Re-checked at the end of every step.

## Steps

0. **Measure Lampa against the prototype** (`proto/`: 5 rows × 20 real posters + focused backdrop, every
   poster kept in memory = worst case). Same 39 key presses on both. Gate: the prototype fits the menu budget.
   Needs the TV for ~2 minutes with Lampa on its home screen.
1. **Real home screen.** ✅ 2026-10-31: Lampa's 8 TMDB rows from the worker's `/home`. Within budget in
   menus; background meets it once Android trims (see log). Later, if peaks matter: reuse dropped poster
   bitmaps (`inBitmap`) and skip loads for posters that already scrolled away, so scrolling allocates nothing.
2. **Film page.** ✅ 2026-10-31: worker `/card/{movie|tv}/{id}` (TMDB details + credits + MDBList scores in one
   ~1 KB answer, the verdict computed there). The page takes Home's place in the same window: poster on the
   left (the card's bitmap), title, year · runtime · genres, verdict + TMDB/IMDb/RT/MC/LB, season scores,
   description, director and cast, dimmed `w1280` backdrop. The backdrop path comes with `/home`, so it loads
   alongside `/card` and is preloaded when focus rests on a card. Episodes come with step 3, your grade with
   step 6 (both need more than this page).
3. **TorBox sources.** ✅ 2026-10-31: worker `/streams/{movie|series}/<imdb>[/<s>/<e>]?lang=RU,UA[&from=6]`:
   Torrentio (3–8 ms from the worker, Cloudflare caches it), each torrent once, filtered by audio language
   (Torrentio's flags; none = English), biggest first, badges worked out there (4K/1080p/720p, DV, HDR10+/HDR,
   REMUX, HEVC/AV1, Atmos, TrueHD, DTS, DD+/DD). TorBox's cache is checked for the first 6 only (5 rows fit on
   the screen, measured) and the app asks for the rest at once, so it is there when the list is scrolled. The
   first 6 of a film are fetched while its page is read: the list shows 23 ms after Watch. Series: seasons →
   episodes → sources. The Audio row opens the language picker (RU+UA by default, saved). Seeders only on rows
   TorBox doesn't have cached. Kept Torrentio: Comet, MediaFusion and AIOStreams need a server of ours or get
   the TorBox key; TorBox's own search API (`search-api.torbox.app`) no longer resolves. TorBox key: worker
   secret `TORBOX_API_KEY`, sent as a header only. Within budget after the memory fixes (log): 78 MB at most.
   Long lists (sources, episodes) build their rows as focus nears the end, 5 at a time: an unscrolled list
   builds nothing more. A platform `ListView` that draws only the ~7 visible rows only if that measures short.
   Episodes start with 14 rows (~10 fit), so the screen is full and later rows appear out of sight (user: 8 left
   the screen half empty). **Torrentio refuses the worker at times** (429 seen 2026-10-31: Cloudflare's IPs are
   shared and rate-limited) while the stick's home IP gets it: on the worker's 502 (Torrentio refused; TorBox
   failing only marks rows unknown) the app asks Torrentio itself for the next 10 minutes and POSTs its answer
   (the stick's token only) to the same path for the same filtering and TorBox check (forced on the stick:
   488 ms after Watch instead of 438 ms).
3b. **Cast and collection on the film page** (user, 2026-10-31: interesting before or after a film). Below the
   info the page scrolls to a Cast row (the first 12 with a photo: w185, name, character) and, for a film in a
   series of films, the other films of its collection as Home cards, oldest first (worker: TMDB
   `belongs_to_collection` → `collection/{id}`, 0.14–0.19 s for `/card` either way). The collection's cards are
   built 7 then 5 at a time like long lists (Bond has 27). OK on a collection card opens that film on top
   (one more level, its own backdrop), or goes back to it when it is open below; Back returns. The page's
   pictures go through Home's poster window (near the viewport only). A level going under keeps only the
   pictures on the screen (Home under a film page, a film under its lists), so Back shows them at once; a film
   under another film keeps none, so films stacked deep cost their views, not their bitmaps. Only the level shown
   has its backdrop. Later: OK on an
   actor → their films. Measured (log): the page with its photos 78–79 MB. **Open: back on Home after a
   collection film 85 MB, over the 80 MB budget** (the GL driver keeps its peak until Android trims); software
   rendering (A/B in the log: 25–31 MB) closes it: the default since 2026-11-01 (the user: software is good).
4. **Play in DDD with resume** ✅ 2026-10-31 (tested on the stick, see the log). OK on a source → worker `/play`: TorBox
   adds the torrent (or finds it), picks the file Torrentio meant, answers its link with the resume point
   (1.37 s new, 0.92 s already in TorBox) → DDD (`ACTION_VIEW`, `position` ms, `return_result`) → its
   position/duration/end_by → worker `POST /timeline`. Resume points are **shared with Lampa through the worker**
   (D1, Lampa's own keys: `Timeline`, the formula matched 57 of Lampa's 58 saved points): the torbox-en plugin
   pulls them into Lampa's timeline and pushes Lampa's (user, 2026-10-31). Not CUB: its timeline needs CUB
   Premium ("Требуется CUB Premium для использования тайм-кодов"; Lampa uploads only with Premium). Only the
   stick may call `/play` and `/timeline` (tools/connect.sh: a token in the worker and the app's private files).
   Process death during playback: what DDD answers about, the film and its open source list are kept in
   `onSaveInstanceState`; Back from DDD reopens film → (seasons → episodes →) sources, Home only on the Back that
   closes the page. RU or UA picked → DDD's `audio_lang` "ru,uk" (our DDD fork, feat/audio-lang-extra).
   Watch marks (user, 2026-10-31): a season says "10 episodes · 8 watched · watching E9", an episode "Watched"
   (green, from 90 %, Lampa's line) or "Watching · 19 min left"; one `GET /timeline?hash=` for the series' keys
   when the film page opens (Lampa's points too), and DDD's answer updates the rows under the sources. The
   seasons and episodes lists open on the one to go on with (the episode being watched, else the one after the
   furthest watched, into the next season). **Continue** beside Watch goes there at once: the same source again
   when it was last played from here (kept on the stick per film), else that episode's list, where the same
   release (a season's torrent) plays at once when it has that episode (user, 2026-10-31: no second OK), else
   only focused. The background with HARDWARE pictures (the decoder's upload thread has
   its own EGL context) is lighter than with RGB_565, not heavier: 58–63 MB against 84–87 MB (log).
   Done on the stick: play, Back, the same film resumes, Lampa shows it. **Open: the background is 51 MB, over
   the ≤ 40 MB budget** (GPU context kept until Android trims; 33 MB after the trim in step 1). Fix to try before
   step 5: software rendering A/B (no GPU context at all: 24 MB): the code's default since 2026-11-01 (the user's own stick runs GPU since: log row 52). Done on the stick too: Russian
   audio picked, `am kill` during playback → Back lands on the sources (log).
   With GPU drawing and a film page open, the app ends its own process 5 s after DDD covers it (`Timeline.endsBehindPlayer`;
   Progress and DDD's answer need no activity, Back restores the page): to be confirmed on the stick (the background's PSS).
4b. **Settings** (user, 2026-10-31: choose the drawing and test it). Home's Settings button (up from the first
   row) or ≡ anywhere. Drawing: Software (the code's default since 2026-11-01; the user's own stick is set to GPU) or GPU; the manifest draws in software and Main switches the
   GPU on (Android can switch a window's GPU on, never off); a switch restarts the process, because the GPU's
   context stays with the process (measured: 62 → 31 MB only after a restart). Stats overlay: PSS (Java, native,
   GPU), CPU, frames/s and the slowest frame, 15 minutes of PSS and a minute of CPU as lines; Fire OS hides an
   app's own GPU memory from it, so the GPU comes through ActivityManager, refreshed at most every 5 minutes.
5. **Search** (built 2026-10-31; user: simple with a remote, predictions, one letter). Researched: Lampa,
   Netflix, YouTube use their own on-screen grid and search while typing; TMDB has no autocomplete and its
   search matches whole words (measured: "inter" doesn't find Interstellar; 1–2 letters bring junk; 250–350 ms).
   So: an alphabetical 6-a-row keyboard (EN or RU, remembered) beside the results, asked 0.3 s after the last
   key; the worker's `/search` answers Home cards: first well-known titles with a word starting so (D1 index
   `titles`: ~1000 films and series by vote count + popular now + Russian ones, English + original + Russian
   words, ё as е, refreshed daily by two crons because the free plan allows 50 calls a run), then from three
   letters TMDB's own search, each title once. Before typing, the last 8 searches (kept when a result opens).
   An empty answer isn't cached (the index's first day answered nothing: `?v=2`); a result card resting 0.5 s
   preloads its page like Home's.
6. **Your rows from Lampa's CUB home** (investigated 2026-10-31 in Lampa's source, github.com/yumata/lampa-source,
   and on the stick). Lampa's CUB home is CUB's catalog (Watching now, Last addition, Fire!, Top 100…: Russian
   names and posters, the same ground as the TMDB rows here, so left out) with four rows of yours in it: **We
   recommend to see** (core/recomend.js: TMDB recommendations of history films, one scanned every 2 min, kept on
   the device), **Continue browsing** (favorite.js: the history's series but Viewed/Thrown), **For you** and
   **Rate what you watched** (our grades plugin). So no layout setting: your rows go above the TMDB rows when
   signed in. Sign-in = Lampa's device login (device/code → the user confirms at cub.red/activate →
   device/token, then profiles/all), token + profile in the app's files/cub; the worker reads CUB with them
   (X-Cub-Token, X-Cub-Profile, CUB_ACCOUNTS only) and keeps nothing. CUB's `bookmarks/dump`: ~320 KB, 0.3 s, rows
   {type, card_id, card_type, time, data: Lampa's card as JSON text}.
   - **6a** ✅ built 2026-10-31: Settings → CUB account (code on screen); worker `GET /mine` → Continue browsing
     (series, plus films being watched: 10 s–90 % on the timeline, which Lampa leaves out), English cards from
     the search index or TMDB (then indexed); the app draws it from the last answer at once and again when a
     fresh one differs (start, back from Lampa, after DDD's answer). `POST /mine/history/<type>/<id>` puts a film
     played here at the front of Lampa's history (Lampa does it when its torrents open), as Lampa's card.
   - **6a+** ✅ 2026-10-31 (user asks): **series play on** as DDD's playlist (DDD's own `video_list`): `/play
     ?series=<original name>` adds the episodes after it in the same torrent (S01E10 / 1x10 in file names, samples
     out), each as the worker's `/stream/<torrent>/<file>?tl&prev&sig` (HMAC with PLAY_TOKEN; the same address
     every time, so DDD's own per-address resume works; TorBox's link fetched when DDD asks, kept 30 min at the
     edge). DDD answers with the address it stopped on: that episode saved, the ones before it watched; and the
     next episode's first ask marks the previous one watched in the worker (TV off, app killed: Continue still
     goes on). Tested on the stick: E09's last 25 s rolled into E10, E09 marked 100 %. `am start --esal` (strings)
     crashes DDD (ArrayStoreException: it expects Uri[]); the app sends Uri[]. **Continue** plays the same release
     for the next episode at once. **Finished series leave** Continue browsing (last aired episode ≥ 90 %, D1
     `shows`, TMDB a day); **marks** on the film page (Finish later → its own row, Not interested, Watched it) in
     D1 `marks` per account+profile: CUB's marks need Premium (error 555), so Lampa doesn't see them yet.
   - **6e** (user ask) ✅ verified 2026-11-01: audio, subtitles and speed **per film/series**. DDD kept
     audio/subtitle per address (Room video_settings), speed per session, and with `audio_track_memory` (off on the
     stick) the audio chosen within a playlist. Now the DDD fork (feat/audio-lang-extra) returns `audio_track`
     (TrackLogic.audioTrackSignature: name|language|codec|channels), `subtitle_track` (label|language|format or
     "off", only when the video has subtitles) and `speed` (only when chosen), and takes them back as extras; the
     app keeps them per card (prefs "tracks", device only) and sends them on the next play. The address's own
     tracks still win; a track picked mid-playlist carries to the next episodes. On the stick 2026-10-31: DDD
     answered the track picked and the app kept it, and took the audio back on an address with no memory; in use
     since, the stick keeps audio and subtitles for 5 titles (a Russian subtitle picked for one). Speed on the TV
     emulator 2026-11-01: 1.25x picked → saved for the film → the next play started at 1.25x.
   - **6f** (user ask) built, stick test pending: **skip intro, recap and credits**. Times from IntroDB (IMDb id)
     and TheIntroDB (TMDB id), community data, often one person's (Foundation S1 E9: intro ends at 84 s / 59 s):
     worker `/segments` merges them (the intro that ends first, the credits that start last) and keeps them in D1
     a week. TheIntroDB without a key allows 5000 asks a day per address and Cloudflare's are shared (429):
     THEINTRODB_API_KEY set 2026-10-31 (free account, 100,000 a day). The app asks alongside `/play` (a second more at
     most) and sends DDD `segments` / `video_list.segments`; the DDD fork shows "Skip Intro" / "Skip Recap" /
     "Next Episode" / "Skip Credits" while each runs, OK skips (also with DDD's start-up panel up); "Skip Intros Automatically" setting
     (off). Coverage 2026-10-31: series good (Foundation, Dark Matter, Breaking Bad), films few (Oppenheimer,
     Interstellar credits). On the stick: the button and OK work, but **community intros are often a guess**
     (Foundation: "0-84 s" in every episode; S1 E9 is a 30 s recap, then titles 31.9-113.9 s). So the home server
     finds them by **sound** (worker/home-server/intro-detect.py, launchd local.lampa-intros every 10 min, ffmpeg + numpy): the
     first 4 minutes of two episodes of the release played, the longest identical stretch = the titles (±0.5 s on
     E9 by frames); worker `/segments` queues a job per release played (D1 intro_jobs), `/play?probe` gives links
     that mark nothing watched, results in D1 `intros` beat the databases. ~1-2 min a new episode (download), the
     episode played and the 5 after it. A finale with its own opening (S1 E10) gets none (0-0 kept, so its plays
     don't queue it again). **Recap**: an intro starting at 5 s-2 min and a "Previously on…" / «Ранее в…» /
     «Раніше в…» in the release's text subtitles (the file's first 60 MB) make 0 → intro a recap: "Skip Recap",
     then "Skip Intro" (E9: 0-31.9 s; ~15 s more on the home server). Credits from IntroDB
     were right (56:30). "Ends at" counts to the credits. Rejected: MKV chapters (Media3 1.9 ignores them; this
     release has none).
   - **6g** (user ask) built 2026-10-31, stick test pending: **new episodes**. `/mine` finds the series in Continue
     browsing whose next episode (after your furthest watched) is the last aired one or a season's first, aired in
     the last 30 days and after you watched the one before; Torrentio is asked for it (RU/UA flags) at most every 3
     hours until it is out (D1 `fresh`; `shows` keeps TMDB's IMDb id, seasons and air dates). Out: first in
     Continue browsing, "New · S3 E5" on the poster. A week missed (two aired since) gets no label: TMDB's series
     answer dates only those two. Lampa's own points often carry no time (1970), so "after you watched" can't be
     told for them: the 30 days keep old ones out. No Telegram (user 2026-11-02): told in the app, Home's Coming up
     tab counts the episodes out with your audio ("Coming up 2", green).
   - **6g+** (user: a second season can come years later): every series in the history counts (66, not the newest
     30: 45 were outside them), but Not interested and Finish later; TMDB asked again on the next air date, a month
     after it ended, every 3 days while it waits for a date (`shows.status`, `next_air`).
   - **6b** built 2026-10-31, stick test pending: **★** beside ⋯ on the film page, in the grade's color
     (Loved/Good/OK/Bad/Didn't watch → `/grades`, D1 `grades` (one row a film; it was one KV value an account, so two devices posting together overwrote each other), Lampa's plugin's format, so Lampa shows it on its next sync);
     `/mine` brings the grades and **Rate what you watched** (the newest 100 history films past 70 % on the
     timeline, a series by any episode, no grade yet: 11 on 2026-10-31); a graded film leaves it.
   - **6c** built 2026-10-31: **For you**, made by the worker's hourly cron (TMDB recommendations of 240+ graded
     films are more than a request's 50 subrequests): each Loved (×2), Good (×1), Bad (×-1) film's TMDB
     recommendations kept a week (D1 `recs`, 25 an hour), ranked per account as grades-patch.js does (higher in a
     list counts more, 200+ votes, TMDB 6.5+, animation only from animated films) into 60 cards (`foryou`); `/mine`
     leaves out what was played, graded or marked. Lampa's **We recommend to see** (from the history) left out: the
     cron can't read CUB.
   - **6d** built 2026-10-31: **My list** (user: one list, not three). Lampa's Bookmarks, Later and Like are three
     lists that work the same (45, 12, 1); Like is the grades now. The row = Bookmarks + Later, newest first; the
     film page's ⋯ adds (CUB Bookmarks, `/mine/book`) or removes (both, `/mine/unbook`). CUB caps free accounts
     ("maximum limit", number unknown).
   - **6h** built 2026-10-31: **New in 4K**: CUB's list of the latest 4K releases (Lampa's "In high quality"; its 4K
     = 4K with Russian audio: 13 of its newest 15 films had one on Torrentio, none of its WEB-DL ones), read daily
     at 04 UTC by the hourly cron; each title's first day kept (D1 `uhd`), so the row is what turned 4K lately;
     100+ votes or IMDb 7+; what you played, graded or marked left out. Rejected: our own Torrentio watch of last
     year's films (CUB does it; Torrentio refuses the worker at times), TMDB digital dates (no 4K or audio),
     JustWatch (streaming), YTS (English, small).
   - **6i** built 2026-10-31: **Voice search** on the remote's voice (Alexa) button, held while speaking, in this
     app only (Alexa keeps it elsewhere). Fire OS gives the button to the app on screen when its activity has the
     meta-data `can_accept_voice_input` (read in FireTVKeyPolicyManager, fosservices.jar; undocumented); the remote
     streams to the input BTLE-In (16 kHz mono) while held, opened by the Bluetooth stack, not by Alexa. Then:
     worker `/voice/hear` (Deepgram Nova-3 on Workers AI: 0.3-0.6 s, Russian right; Whisper turbo 1.3-2.5 s and
     misspelled titles) → Claude Code (Sonnet) on the home server, the user's own plan, one `claude -p` kept started
     (1.5 s warm against 7 s cold; worker/home-server/voice-claude.py) → worker `/voice/find`: each guess on TMDB
     with its confidence (the card's corner), a recommendation's filters on TMDB discover, one sure title (85%+,
     25 ahead) opened at once; a recommendation leaves out what you graded or marked. No MacBook (off, plan limit):
     the worker's free Llama 4 Scout reads the text (titles fine, recommendations poor). Test of 16 requests (en, ru,
     uk; titles, descriptions, recommendations): Claude Haiku, Sonnet, Opus 12/12 with good picks; free Workers AI
     models 10-12/12 with invented or wrong picks (Saw X for a family evening); GLM 4.7 Flash right but 23 s.
     Rejected: speech to text on the home server (whisper.cpp: small 8.5 s and wrong, turbo 35 s beside the bots),
     audio straight to an LLM (Claude takes none), the Claude API (a paid key for what the plan covers).
6j. ✅ (user ask 2026-11-01) **Posters say what you did**: a started film's time left ("53m left": `/mine`'s
   "left", 5 min in, under 90 %) and your grade (Loved / Good / OK / Bad; "not mine" says nothing) on every poster,
   Home's TMDB rows included (their badges are drawn again with each `/mine`). A graded film is watched: it leaves My
   list, Finish later and Continue browsing (hidden, CUB keeps it); a graded series stays unless "not mine" (say, one graded Good after season 1
   and watched on). For you, New in 4K and Rate what you watched already left graded films out. On
   2026-11-01 no graded film was in your lists (the 9 graded there were series being watched): the rule is for what you
   grade from now on.
6k. ✅ (user ask 2026-11-01) **An actor's page**: OK on a cast photo (the worker's /card now sends TMDB's person id;
   `?v=4`). The worker's `/person/<id>` (one TMDB call, a day at the edge): name, born (age) or the years they lived,
   where, the biography (3 lines on the page), then rows: **Best rated** (TMDB's score weighted by votes, IMDb's
   formula: a 9.5 from 12 votes doesn't top a 7.0 from 4,000), **Films** and **Series** newest first. Rows instead of
   a sort switch: both orders on one page, the remote only goes down. Left out: not out yet (they led Scarlett
   Johansson's films five deep), playing themselves or archive footage, talk/news/reality shows, a series they are in
   once (The Simpsons topped Pierce Brosnan's best), no poster. OK on a film opens it on top (or goes back to it);
   an actor already open below is gone back to. Sized so the first row fits under the header (≈515 of 540 dp).
6l. (user's design 2026-11-02, canvas "Lampa Native 2026") **Glass shapes, effects baked once, nothing moves.**
   Pictures with round corners (posters 10 dp, tiles 12, cast photos a circle): software drawing paints the corners
   the ground's color into the bitmap once when it loads, or over a film's backdrop fills the shape with the picture
   (a bitmap shader, no new memory); the GPU clips to the outline. Focus: a 3 dp halo 3 dp out from the picture,
   drawn by the focused card only, inside the 16 dp gap (cards keep their stride; containers' left padding is a halo
   less so the first card's halo isn't cut). Buttons, Home's tab bar (Home | Search | Settings), the score line and
   the query: flat round fills, white at 8–13 % with a 1 dp edge, white under focus. List rows: nothing at rest, white
   round under focus with dark text (spans follow the focus: `Tint`; source badges are round chips). The film page has
   no poster: its backdrop is dimmed and faded to the ground (left 72 %, bottom 46 %) in one pass when it loads, in
   both drawings. Continue browsing as 195×110 dp tiles: TMDB `w780` backdrops decoded at half size (390×219 px) with
   how far in as a bar (the worker's `/mine` sends `percent` since 2026-11-02). Medium weight
   (`sans-serif-medium`) for titles and buttons only: a focused name changing weight would lay the screen out again
   at every press, so it turns white instead. Search (keys in lower case as typed, "Top results" over the results,
   headings medium) and Settings (each setting's name, what it does, its value on the right, as a source's ⚡ Cached)
   follow the same parts; the sign-in code sits on a glass tile. Left out on purpose: live blur, shadows, scaling or lifting the focused
   card, transitions, a backdrop behind Home or lists.
6m. (the same canvas, its second version 2026-11-02) **Sheets, the grade bar, seasons beside episodes.** ⋯, Settings
   (≡ or Home's tab, now over any screen, Home too) and Audio languages are side sheets: an opaque 392 dp panel on the
   right over the screen, which stays as it is (no level pushed, no backdrop dropped and decoded again on Back). The
   arrows never leave a sheet, and the screen under it takes no focus meanwhile (a source list loading under Settings
   would grab it). Settings groups its rows (Display, Account, Playback) and sets the audio languages too (RU, UA, EN
   and those chosen, no counts); languages show by name (the country's likely language, Android's ICU data). ★ opens
   the grade bar under the film's buttons: pills with each grade's star in its color, what the focused one does under
   them. OK on a Rate what you watched card opens the same bar under the row, without the film's page (no /card, no
   backdrop per film graded), with "Open film"; graded, focus goes on to the next card as the film leaves the row.
   Watch on a series: its seasons on the left, the focused one's episodes on the right (Left from an episode goes to
   its season, Right from a season to its episode to go on with), one level instead of two. Episode titles and
   stills: 6o. Also: the backdrop's left fade is
   dithered (RGB_565 made a hard edge ~40 dp in).
6n. (the same canvas, its third version 2026-11-02) **≡ on a poster, Play picks the source, Coming up, genre
   filters.** ≡ on a Home poster: a side sheet for that title (a film: ▶ Play with what it plays, Sources, Film page;
   a series: ▶ Episodes at its next one; then My list, ★ Your grade, Mark, ⚙ Settings), from /card and the first
   sources page. A film's ▶ Play starts the biggest release TorBox has cached in your audio languages (never a
   disc), read from the page's preloaded sources, with "Plays [4K] [DV] … · RU UA · 50.8 GB · ⚡ Cached" under the
   buttons (many audio tracks: yours and "+N"); none: it opens the list. Settings → Play picks: Best cached / Ask each
   time. The page's line ends with "ends at 17:31" (/card sends a film's runtime). Sources: a header with "Title · N
   found, biggest first" and an Audio: RU UA pill instead of the row. Home's tabs: Home | Search | Coming up |
   Settings. Coming up (the worker's `/mine` "coming"): by day (Today, Tomorrow, a weekday, Later), a new episode out
   with RU/UA audio or only in English so far, the next to air of a series you're caught up on, My list's films not
   out yet. A series tile in Continue browsing: "S3 E5 · up next", or the minutes left of the one being watched
   (`/mine` "next"). Settings: compact rows, what the focused one does at the foot; Profile (CUB's profiles, OK
   switches whose rows Home shows) and TorBox (the plan and torrents, `/torbox`). A genre from Search: pills for
   films/series, decade, country, score, sort, watched hidden or shown, Surprise me (a sideways bar: seven are wider
   than the screen), five posters a line, more pages as focus nears the end (`/discover/{genre}`, a day at the edge).
   Kids opens on series (TMDB has no films in it). Left for later: the board's Kids profile (it needs a CUB profile
   and content rules: skipped, user 2026-11-02), the board's Search keyboard (' and - instead of digits and Clear:
   titles have digits).
6o. (user 2026-11-02) **The canvas's leftovers.** Episodes: the board's details rows, a 150×84 still, "8 · Better
   Call Saul", "47m · Watching · 19 min left" and a line of its overview (the worker's `/season/{id}/{n}`, TMDB's,
   a day at the edge; "Episode 8" rows until it answers); the stills load and drop with the picture window like a
   page's. Coming up: a season starting in four weeks of any series in your history or My list, wherever you are
   in it ("Season 3 starts · you're on S1 E4"; its weekly episodes still only once you're caught up), each row with
   its episode for the app; an out one's "Out" turns "⚡ Cached" when TorBox has it in your audio languages (the
   first sources page, as ▶ Play's). Home's tab: "Coming up 2" in green, the episodes out (no Telegram, user
   2026-11-02). Back on a sheet opened from another (Profile, Audio languages, CUB account from Settings; Mark,
   ★, Settings from ≡) opens that one again, on the row it came from. A watching episode's still has the Continue
   tiles' bar along its bottom (again when DDD's point moves it); stills keep round corners on the white focused
   row (software drawing painted them the ground's color: dark squares, fixed 2026-11-02).
6p. (user 2026-11-02) **Tabs.** Search, Coming up and Settings were screens over Home (Settings a side sheet half over
   the tab bar): now each has Home's tab bar on top, itself lit; OK on another tab switches to it (that page in
   place of this one), Back from any goes Home onto its tab. Settings is a page: its rows on the left, what the
   focused one does on the right; a sub-sheet's change shows when it closes. ≡ still opens Settings as a side
   sheet over a film (not over its own tab). Search's keys 46 dp high (from 52) to fit the bar. An episode not out
   yet says when it airs ("Episode 4 · Airs Friday, 6 Nov": the worker's /season carries TMDB's air dates, `?v=2`),
   not TMDB's stand-in "4 · Episode 4" with nothing under it.
7. **Launcher.** ✅ 2026-11-02 (user): Lampa Native is the default. lampa-home opens it at boot (once the network is
   up) and on the Home key since 2026-11-01; Lampa, Nuvio and Fire TV settings stay in lampa-home's menu (Back out
   of Lampa Native). lampa-home stays the Home app: 107 lines that can't break, so a broken Lampa Native never
   traps the stick, and Lampa Native in the background stays killable while DDD plays 4K (a Home app's process is
   kept). Reboot test passed 2026-11-01 (with Lampa as the default then).
8. (user 2026-11-02) **Canvas v4: late 2026 and the player** (canvas "Lampa Native 2026", rows "New for late 2026"
   and "Player (DDD) and the way back"). Researched 2026-11-02; each a text sheet or row, so memory stays as is
   (Your 2026's five posters ~1.3 MB in software drawing). In order:
   - **8a. The voice you watch with** (Voices) — high value, medium-high effort. The same series switching dub
     studio between episodes is the real annoyance of Play picks. Release titles do name the studios: RuTracker's
     "MVO (LostFilm FoxCrime) + DVO (Кубик в Кубе)" and Rutor's "| LostFilm Кубик в Кубе Пучков" (Torrentio,
     Breaking Bad S2 E8: 26 releases, all named). The worker parses them into each /streams row; Sources gets Voice
     chips with counts and a voice tag per row; the voice of the release last played is kept per series and Play,
     Continue and the next episode prefer a cached release with it. A multi-voice release also needs DDD to pick
     that studio's track: a fork extra `audio_name` (the track whose label holds the studio), next to `audio_lang`.
   - **8b. How was it?** (After) ✅ 2026-11-02 (329d037) — cheap. Back from DDD with a film finished (`end_by` completion or ≥ 90 %): the
     existing grade bar opens by itself over the film page, Later closes it; "More like this" = TMDB's
     recommendations of that film (the worker already asks them for For you; one more route, a day at the edge).
     Built: the bar opens over the film page when DDD answers with a film at 90 %+ and no grade (a page built later,
     after a restore, opens it then), "How was <film>?" over it, Later after the grades. More like this is a row on
     every film page under the collection, without what was graded or watched: TMDB's recommendations appended to
     /card's own TMDB call (no new route), poster, 50+ votes and out only, at most 14 (`?v=7`).
   - **8c. Story so far** (Recap) ✅ 2026-11-02 (a0c8053) — small as an MVP. On a series last watched 2+ weeks ago, a button with a side
     sheet: the overviews of the last three episodes watched (the worker's /season has them). Then, optionally,
     Claude's summary up to the last watched episode, written once per series and episode on the home server
     (`claude -p`, as voice search) and kept in D1.
     Built: a "Story so far" button on a series page whenever episodes were watched before the one Continue goes on
     with (no 2-week rule: there is no date for it on the stick). The sheet: Claude's "Previously" (Opus via
     voice-claude.py's POST /recap on the home server, ~20 s once, then kept in ~/.lampa-intros/recaps.json, not D1),
     then "Last time": the three episodes' TMDB overviews. Away from home or with Claude down: the overviews only.
   - **8d. Tell me when it's out** (Subs, first half) — medium. A film with no RU/UA release: the button keeps it in
     D1, the hourly cron asks Torrentio for RU/UA releases, and when one is cached it shows in Coming up as Out (the
     tab's green count).
   - **8e. Next episode card** (Player-next) — (a) ✅ 2026-11-02 built, (b) waits for 8a. Built: DDD fork branch up/next-card (on up/skip-main): at credits running to the end with a next item, a card (still from `video_list.thumbnail`, title, "Next episode in 8 s" counting only while playing; OK now, Back watches the credits and leaves the Next Episode button), setting `next_card` (off by default, AGENTS.md; on for the stick); lampa-native sends the season's stills (7ee41d8). Was: mostly there: a season pack goes to DDD as a playlist with each
     episode's credits, and DDD moves on at them. New would be the canvas's card in DDD (the still, "Next episode
     in 8 s", Play now, Credits, Stop here) and, for single-episode releases, lampa-native starting the next
     episode's best cached source with the same voice (8a) when DDD returns at the credits.
     Investigated 2026-11-02 (user: "nice"): (a) the card is DDD's credits button grown up: DDD knows the next item
     and shows a poster per item already (`video_list.thumbnail`, the Up info panel); lampa-native would send the
     episodes' stills (w300, from /season) and names; DDD: still + "S1 E10 · Name" + "Next episode in 8 s", then
     `seekToNextMediaItem`; OK now, Back watches the credits. ~150 lines in DDD (layout, 5 locales), ~10 here.
     (b) a single-episode release: on DDD's answer with the episode ended (or stopped in its credits), the next
     episode's /streams, Best cached, a "Next: S1 E10 in 5 s" bar here, then play. ~100 lines; the same voice needs 8a.
   - **8f. Paused screen and subtitles on rewind** (Player-pause, Player-rewind) ✅ 2026-11-02 built. DDD fork branches up/rewind-subs (setting `rewind_subtitles`: a seek back ≤ 30 s with subtitles off turns on full subtitles in the audio's language until the point rewound from, restoring the exact text selection after; a manual pick wins; never saved for the video) and up/pause-info (setting `pause_info`: after 5 s paused, title, "N min left · ends at HH:MM · audio", the overview from new `overview`/`video_list.overview` extras that lampa-native now sends); both off by default upstream, on for the stick. Was: DDD fork, medium each. Paused a
     few seconds: title, episode, "19 min left · ends at 23:41", overview, cast, the audio track's name (DDD's Up
     info panel has most; lampa-native adds the overview and cast to the intent). Rewind ≤ 30 s: subtitles in the
     audio's language turn on until the point rewound from; only when the release has them.
     Investigated 2026-11-02: subtitles on rewind is the small one: DDD's Left seeks back 15 s
     (`PlayerManager.seekBack`); with subtitles off and a text track in the audio's language, select it, remember the
     point rewound from, and the 200 ms `progressUpdater` turns them off past it (a subtitle chosen meanwhile wins).
     ~50 lines and a setting. The paused screen builds on DDD's dimming on pause (`animateDimming`) and its Up panel
     (title, poster): a card with "S1 E9 · Name", "19 min left · ends at 23:41" (DDD has both times), the audio
     track's label, and the overview from a new `overview` extra (per item in `video_list`). ~120 lines in DDD.
     Order: rewind subtitles, the next-episode card, the paused screen, then (b).
   - **8g. Your 2026** (Year) — medium, seasonal: hours, films, episodes, the longest run, top genre and director
     (TMDB credits, cron-cached), Loved this year; from D1's timeline, CUB's history and grades. A December job.
   - **8h. Translated subtitles** (Subs, second half) — high effort: English subtitles from OpenSubtitles (matched by
     the release's hash; an API key, ~20 downloads a day), translated by Claude on the home server (~1 min) and
     handed to DDD as an external track. Timing has to match the release, so a hash match only.
   - **Skipped: Clear dialogue and Night mode** (Player-sound): DDD's mixer has both already (Boost center, Night
     mode presets), but only for sound it decodes; here DD/DD+ go to the soundbar untouched ("Passthrough: Dolby
     only"), so they'd do nothing. The soundbar's own modes do this.
9. (user 2026-11-02) **Canvas row "Chosen": History, follow a person, what's new** (boards History, Follow, Bell;
   Ratings and Speed not looked at yet). Researched 2026-11-02 in the worker, D1 and Lampa's source (yumata/lampa-source
   3c658ff). In order:
   - **9a. History** — medium, nothing new to collect. A page, newest first by day ("Today 21:40"): what was played
     here and in Lampa, a series' episodes of a day on one row ("S1 E6 and E7 · Watched", "S2 E8 · 19 min left"),
     the grade, "in Lampa" when Lampa played it. Data: D1 `timeline` has 433 points, 67 dated (`updated`, since
     2026-08; Lampa's older ones are 1970); lampa-native's carry the card, season and episode, Lampa's only the hash,
     but 51 of Lampa's 58 dated ones match by TMDB data alone (27 episodes from `shows`' seasons and original names,
     24 films by title), the rest by CUB history cards' original titles. Older plays: CUB history's own `time` (CUB
     sets it, Lampa sends none). A worker `GET /mine/history` (one dump, one timeline query; episode names from
     `/season` for the first rows only, else "S2 E8"). ≡ on a row: Remove (CUB `bookmarks/remove` of the history
     bookmark: Lampa loses it too) or Mark unwatched (its points to 0 with `updated` now, which Lampa syncs). Writes
     to CUB, so tested on one entry the user picks. Where: the last tile of Continue browsing, "History".
     ✅ Built 2026-11-02 (user: "let's start with history"). CUB's history `time` is ms, when it last went to the front
     (Lampa's updated_at), checked on the real dump. Worker `GET /mine/history?tz=` (100 rows, ~16 KB, 2.2 s the
     first time while it fetched English names), `POST /mine/unwatch`, `POST /mine/unhistory/<type>/<id>`. A day CUB
     says it was played with no point saved gets a row with nothing known (DDD's answer lost?). Text
     rows like Coming up's, no posters. On the emulator: tile, page, groups, ≡ sheet, Back to the tile. Mark unwatched
     and Remove not yet run on real data (wait for the user's pick).
   - **9b. Follow a person** — medium. Lampa's own Subscribe on a person (`/api/person/subscribe`) is CUB Premium
     (Lampa shows the Premium dialog; ours is free: marks failed with 555), so ours: D1 `follows` (who, person id,
     name, photo, credit ids known, t). The person page gets "Follow" / "✓ Following" and a **Coming** row before
     Best rated (credits with a date after today, which the page leaves out now). Directors: the film page names them
     in text only and /person reads cast credits, so the director goes first in the photos row and /person reads
     crew credits (job Director) when TMDB's `known_for_department` is Directing. The hourly cron asks TMDB
     `person/<id>/combined_credits` for ~10 follows a run (each a day apart): a film that gets a date (a lead role,
     cast `order` ≤ 3, or directed) becomes news ("New film: Dune: Part Three · December") and goes to Coming up
     as My list's films do. No date, no news (actors' lists are full of undated projects).
     ✅ Built 2026-11-02. D1 `follows` (follows.sql); `POST /mine/follow|unfollow/<id>` asks TMDB at once, the hourly
     cron again a day later (10 a run); `/mine` sends `follows` and their dated films into Coming up ("Ryan Coogler's
     new film", "With Zendaya"; past four weeks: Later). Film pages: the director first in the photos row (`/card`
     `?v=8`); /person reads directing credits for directors ("Director · Born…"), a Coming row first (`?v=2`). Follow
     sits beside the name (the page keeps its height). On the emulator: Black Panther → Ryan Coogler → Follow →
     Coming up "Black Panther 3" (Later, 2028) → Unfollow.
   - **9c. What's new behind a bell** — small once 9b exists. Lampa's bell does the same: notices with a time, the
     time last looked kept, the count = newer ones (interaction/notice/lampa.js). `/mine` adds `news`: episodes out in
     your audio (6g's D1 `fresh.out` is that time already), 9b's announcements, later 8d's films. The app keeps the
     time last looked (prefs), a "New 3" pill right of the tab bar, a side sheet (seen ones dim under a line), OK
     opens the film or series. Nothing in the background (`/mine`). Overlaps Coming up's green count until 9b: build
     after it.
     ✅ Built 2026-11-02. `/mine`'s `news` ([t, card, head, line], 30 days, 20 at most): episodes out in your audio
     (`fresh.out`) and D1 `news` (news.sql), which the follows cron fills when a person you follow gets a film dated
     that wasn't before (`follows.known`); following adds none. The tab bar's right end: "New  3" (gone with no news),
     Left/Right reach it from the tabs; the sheet: new ones over a line, seen ones dimmed; prefs `bell_seen`. On the
     emulator with a test row in D1 (deleted after): pill, sheet, count gone after looking. Nothing out yet on
     2026-11-02, so the stick shows no pill.

10. **Open source** (2026-11-07): README, MIT license, CI. The worker moved here (`worker/`, from lampa-torbox-en's
    `proxy/`; `setup.sh` makes its KV, D1 and tables); its streams addon is a secret (`STREAM_ADDON`), and the app
    asks it itself through `/addon` when it refuses the worker. A TV is set up without root (`tools/connect.sh`: the
    worker's address and play token through `adb shell am start`, taken together only while neither is set; the app
    logs `setup: connected to …` or `setup: kept …`, which connect.sh prints). Every request to the worker carries the
    token; `/streams` now needs it too, and the plugin's `?url=` pass-through is off unless `PASS_THROUGH=1`. The home server
    (Claude for voice search and Story so far) is the build's `HOME_SERVER` (`app/local.env`), plain http to it only,
    and signs each request (X-Home-Time in ms, X-Home-Sig = HMAC(play token, "home\n" + time + "\n" + body), once within 60 s) instead of sending the token. Progress acts only on the address playing. Icon, banner,
    Settings → Worker and About (TMDB's logo and notice). Audio languages default to all.

11. **Extensions: a Learn button** ✅ 2026-11-10 (on main; checked on the stick: The Dark Knight shows "Learned 94%"). Worker: `/ext/<name>/<path>` over a service
    binding `EXT_<NAME>` (stick only; the secret `EXT_<NAME>_TOKEN` goes along as `X-Ext-Token`; 16 KB body, an
    extension's 401/403 becomes 502 so the stick keeps its token), and `/mine` asks `EXT_LEARN` for `learned`
    (`{"movie_603": 93}`, 1.5 s at most; no `learned` key when it is missing or down, and the app asks the extension only when the key is there). App: a film's page asks
    `/ext/learn/status?imdb=` once when it opens and shows **Learn** only when it answers; OK queues it (or looks
    again while it waits): "Learn · queued / building / ready", "Learned 93%" once /mine has a percent. Nothing polls.
    Estimate (2026-11-10): the status may carry `estimate: {band, to_next}`: how comfortably you'd watch the film, by
    the share of its words you know (Hard < 90%, Weak 90-95, Good 95-98, Comfortable 98+; words only, from its English
    subtitle or pack) and the words to the next band. A page opening asks `POST /ext/learn/estimate` instead of the
    status (one request; it answers like the status and queues the estimate once; an extension without the route gets
    the plain status); OK looks again. The label reads "Good · 175 words to Comfortable" (or "Comfortable"); beside a
    queued/building/ready status it is short ("Learn · building · Good · 175"): the button shares a row with Play,
    Sources and Trailer. No percent is shown; /mine's `learned` only tells that the extension is there.

## Needs from you

- A TMDB key (free account at themoviedb.org): copy the "API Read Access Token" (the "API Key" also works),
  run `worker/set-tmdb-key.sh` → worker secret `TMDB_API_KEY`, never on disk or in the APK.
- ~~CUB login in the app~~: done in 6a (Settings → CUB account, a code confirmed on the phone).

## Measurement log

In [docs/measurements.md](docs/measurements.md): every measurement, newest last. Add the numbers there.
