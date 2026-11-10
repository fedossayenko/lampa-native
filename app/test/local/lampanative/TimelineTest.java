package local.lampanative;

import java.util.List;

class TimelineTest {
    private static final Timeline.Playing FROM_START = new Timeline.Playing("1", "movie_1", 0, 0, 0);

    // Lampa's own keys (its Utils.hash), computed with its JavaScript; the formula matched 57 of the 58 resume
    // points Lampa had saved on the stick.
    void testKeysAreLampas() {
        assert Timeline.key("The Dark Knight", 0, 0).equals("13906648") : Timeline.key("The Dark Knight", 0, 0);
        assert Timeline.key("Foo", 1, 5).equals("46902274");     // "15Foo"
        assert Timeline.key("Foo", 10, 5).equals("1448807152");  // "105Foo": no ':' at season 10
        assert Timeline.key("Foo", 11, 5).equals("1996049771");  // "11:5Foo": after it
        assert Timeline.key("오징어 게임", 0, 0).equals("1826858475");
    }

    // Lampa keys a film by its original title (TMDB's original_title / original_name), not the English name.
    void testKeyOfAFilm() {
        Home.Card squid = new Home.Card(93405, "tv", "Squid Game", "2021", "", 0, "");
        Film f = Film.of(squid);
        Film korean = new Film("Squid Game", "오징어 게임", "", "", List.of(), List.of(), "", List.of(), "", List.of(), "",
            List.of());
        assert Timeline.key(korean, 0, 0).equals("1826858475") : "the original, not the name";
        assert Timeline.key(korean, 1, 5).equals(Timeline.key("오징어 게임", 1, 5));
        assert Timeline.key(f, 0, 0).equals(Timeline.key("Squid Game", 0, 0)) : "no original: the name";
    }

    // A series' keys for the worker's ?hash=: every episode of every season, in order.
    void testKeysOfASeries() {
        Film f = new Film("Foo", "", "", "", List.of(), List.of(), "", List.of(new Film.Season(1, 2),
            new Film.Season(11, 1)), "", List.of(), "", List.of());
        String all = String.join(",", Timeline.key("Foo", 1, 1), Timeline.key("Foo", 1, 2), Timeline.key("Foo", 11, 1));
        assert Timeline.keys(f).equals(all) : Timeline.keys(f);
        Film soap = new Film("Foo", "", "", "", List.of(), List.of(), "", List.of(new Film.Season(1, 1500)), "",
            List.of(), "", List.of());
        assert Timeline.keys(soap).split(",").length == 1000 : "the URL stays under Cloudflare's 16 KB";
    }

    // A film's key for its own mark (no episodes).
    void testKeysOfAFilm() {
        Film f = new Film("Foo", "", "", "", List.of(), List.of(), "", List.of(), "", List.of(), "", List.of());
        assert Timeline.keys(f).equals(Timeline.key("Foo", 0, 0));
    }

    // Where a series goes on: the furthest episode with a point; being watched → it, watched → the next one (into
    // the next season); nothing played, or the last one watched → nowhere.
    void testResume() {
        List<Film.Season> s = List.of(new Film.Season(1, 3), new Film.Season(2, 2));
        Timeline.Point watched = new Timeline.Point(2300, 2400, 95), watching = new Timeline.Point(600, 2400, 25);
        assert Timeline.resume(s, (a, b) -> null) == null;
        assert java.util.Arrays.equals(Timeline.resume(s, (a, b) -> a == 1 && b == 2 ? watching
            : a == 1 && b == 1 ? watched : null), new int[] {1, 2});
        assert java.util.Arrays.equals(Timeline.resume(s, (a, b) -> a == 1 ? watched : null), new int[] {2, 1})
            : "season 1 all watched: season 2's first";
        assert java.util.Arrays.equals(Timeline.resume(s, (a, b) -> a == 1 && b == 1 ? watched : null),
            new int[] {1, 2});
        assert java.util.Arrays.equals(Timeline.resume(s, (a, b) -> a == 1 && b == 1 ? watching
            : a == 2 && b == 1 ? watched : null), new int[] {2, 2}) : "the furthest, not the first";
        assert Timeline.resume(s, (a, b) -> watched) == null : "all watched";
    }

    // A point's state: watched from 90 % (Lampa stops resuming there), being watched past its first 10 s.
    void testState() {
        assert Timeline.state(null) == Timeline.State.NONE;
        assert Timeline.state(new Timeline.Point(8, 2400, 0)) == Timeline.State.NONE;
        assert Timeline.state(new Timeline.Point(600, 2400, 25)) == Timeline.State.WATCHING;
        assert Timeline.state(new Timeline.Point(5, 2400, 95)) == Timeline.State.WATCHED;
    }

    // Continue: a series goes where resume() says; a film only while it's being watched and its source is kept
    // here (else Watch is the same thing). It plays that source again when it is that episode (or the film).
    void testContinue() {
        Film film = new Film("Foo", "", "", "", List.of(), List.of(), "", List.of(), "", List.of(), "", List.of());
        Film show = new Film("Bar", "", "", "", List.of(), List.of(), "", List.of(new Film.Season(1, 3)), "",
            List.of(), "", List.of());
        Timeline.Point watching = new Timeline.Point(600, 2400, 25), watched = new Timeline.Point(2300, 2400, 95);
        Timeline.Last filmLast = new Timeline.Last(0, 0, "h", 0, "Foo.mkv");
        assert Timeline.continueTo(false, film, k -> watching, null) == null : "no source kept: Watch";
        assert java.util.Arrays.equals(Timeline.continueTo(false, film, k -> watching, filmLast), new int[] {0, 0});
        assert Timeline.continueTo(false, film, k -> watched, filmLast) == null;
        String e2 = Timeline.key(show, 1, 2);
        int[] to = Timeline.continueTo(true, show, k -> k.equals(e2) ? watched : null, null);
        assert java.util.Arrays.equals(to, new int[] {1, 3});
        assert Timeline.replays(new Timeline.Last(1, 3, "h", 2, "x"), to);
        assert !Timeline.replays(new Timeline.Last(1, 2, "h", 1, "x"), to) : "the next episode: its list";
        assert !Timeline.replays(null, to);
    }

    // The worker's answer goes into the marks, but never over a point DDD handed back this session (its POST may
    // land after the answer left).
    void testMerge() {
        java.util.Map<String, Timeline.Point> marks = new java.util.HashMap<>();
        marks.put("1", new Timeline.Point(900, 1000, 90));
        Timeline.merge(marks, java.util.Map.of("1", new Timeline.Point(10, 1000, 1), "2", new Timeline.Point(5, 6, 7)),
            java.util.Set.of("1"));
        assert marks.get("1").equals(new Timeline.Point(900, 1000, 90)) && marks.get("2").equals(new Timeline.Point(5, 6, 7));
    }

    // The source last played of a film or series, kept on the stick: Continue plays it again.
    void testLastSource() {
        Timeline.Last l = new Timeline.Last(1, 9, "ab12", 3, "Foundation.S01E09 · 4K | x.mkv");
        assert Timeline.Last.of(l.save()).equals(l);
        assert Timeline.Last.of(null) == null && Timeline.Last.of("junk") == null;
    }

    // The worker's points: hash → {time, duration, percent}.
    void testMarksParsed() throws Exception {
        java.util.Map<String, Timeline.Point> m = Timeline.marks(
            "{\"timelines\":{\"7\":{\"time\":600,\"duration\":2400,\"percent\":25,\"updated\":1,\"card\":\"tv_1\"}}}");
        assert m.size() == 1 && m.get("7").equals(new Timeline.Point(600, 2400, 25));
        assert Timeline.marks("{\"timelines\":{}}").isEmpty();
        // a point without duration (Lampa's) keeps the others' marks
        m = Timeline.marks("{\"timelines\":{\"7\":{\"time\":600,\"percent\":25},\"8\":{\"time\":1,\"duration\":2,\"percent\":50}}}");
        assert m.size() == 2 && m.get("7").equals(new Timeline.Point(600, 0, 25));
    }

    // An episode's mark: watched from 90 % (where Lampa stops resuming), else the minutes left; nothing in the
    // first 10 s (Lampa starts those over).
    void testEpisodeMark() {
        assert Timeline.episodeMark(null).isEmpty();
        assert Timeline.episodeMark(new Timeline.Point(8, 2400, 0)).isEmpty();
        assert Timeline.episodeMark(new Timeline.Point(2300, 2400, 95)).equals("Watched");
        assert Timeline.episodeMark(new Timeline.Point(600, 2400, 25)).equals("Watching · 30 min left");
        assert Timeline.episodeMark(new Timeline.Point(2380, 2400, 89)).equals("Watching · 1 min left") : "never 0";
        assert Timeline.episodeMark(new Timeline.Point(600, 0, 25)).equals("Watching") : "no duration from Lampa";
    }

    // A started film's poster (the worker's "left", seconds): hours and minutes, rounded, never 0; nonsense: none.
    void testPosterLeft() {
        assert Timeline.posterLeft("4000").equals("1h 7m left");
        assert Timeline.posterLeft("3570").equals("1h left") : "59.5 min rounds to an hour";
        assert Timeline.posterLeft("1500").equals("25m left");
        assert Timeline.posterLeft("10").equals("1m left") : "never 0";
        assert Timeline.posterLeft(null) == null && Timeline.posterLeft("0") == null && Timeline.posterLeft("x") == null;
        assert Timeline.posterLeft("26 ep").equals("26 ep left") : "a series' episodes left";
    }

    // A season's line: its episodes, how many watched, the last one being watched.
    void testSeasonLine() {
        Timeline.Point watched = new Timeline.Point(2300, 2400, 95), watching = new Timeline.Point(600, 2400, 25);
        assert Timeline.seasonLine(10, e -> null).equals("10 episodes");
        assert Timeline.seasonLine(10, e -> e <= 3 ? watched : e == 4 ? watching : null)
            .equals("10 episodes · 3 watched · watching E4") : Timeline.seasonLine(10, e -> e <= 3 ? watched : null);
        assert Timeline.seasonLine(2, e -> watched).equals("2 episodes · all watched");
        assert Timeline.seasonLine(5, e -> e == 2 || e == 5 ? watching : null).equals("5 episodes · watching E5");
    }

    // Lampa resumes after 10 s and before 90 %; else from the start.
    void testWhereToStart() {
        assert Timeline.startMs(3000, 43) == 3_000_000;
        assert Timeline.startMs(9, 1) == 0 : "the first seconds: from the start";
        assert Timeline.startMs(6400, 91) == 0 : "past 90 %: watched, from the start";
        assert Timeline.startMs(0, 0) == 0;
    }

    // What DDD hands back (ms) as Lampa saves it (seconds, percent); played to the end: 100 %.
    void testSavedAsLampaSavesIt() {
        assert java.util.Arrays.equals(FROM_START.saved(3_000_400, 7_000_000, false), new long[] {3000, 7000, 43});
        assert java.util.Arrays.equals(FROM_START.saved(7_000_000, 7_000_000, true), new long[] {7000, 7000, 100});
        assert FROM_START.saved(5000, -9_223_372_036_854_775L, false) == null : "no duration: nothing to save";
    }

    // Resumed at 50 min and back after a few seconds (or the link died): Lampa's point stays; 0 would win as newest.
    void testAFewSecondsAfterAResumeKeepThePoint() {
        Timeline.Playing resumed = new Timeline.Playing("1", "movie_1", 0, 0, 3_000_000);
        assert resumed.saved(4000, 7_000_000, false) == null;
        assert resumed.saved(3_060_000, 7_000_000, false) != null : "watched on: saved";
        assert FROM_START.saved(4000, 7_000_000, false) != null : "started from the start: saved as Lampa saves it";
    }

    // The worker's /play answer: the link and where to start, or what to tell.
    void testPlayAnswer() {
        Timeline.Link l = Timeline.link(200, "{\"url\":\"https://x/f.mkv\",\"time\":3000,\"percent\":43}");
        assert l.url().equals("https://x/f.mkv") && l.startMs() == 3_000_000 && l.message() == null : l;
        assert Timeline.link(200, "{\"url\":\"u\",\"time\":0,\"percent\":0}").startMs() == 0;
        assert Timeline.link(202, "{\"progress\":37}").message().equals("TorBox is downloading it: 37 %. Try again later.");
        assert Timeline.link(502, "{\"error\":\"no video in it\"}").message().equals("Can't get the link: no video in it");
        assert Timeline.link(503, "<html>Cloudflare</html>").message().equals("Can't get the link: HTTP 503");
        assert Timeline.link(200, "{\"time\":3000}").url() == null : "no link: a message, not DDD on null";
    }

    void testCardKey() {
        assert Timeline.card(new Home.Card(155, "movie", "n", "", "", 0, "")).equals("movie_155");
        assert Timeline.card(new Home.Card(1399, "tv", "n", "", "", 0, "")).equals("tv_1399");
    }

    // The worker's /play path: the file Torrentio meant, its name encoded, and this point's key.
    void testPlayPath() {
        String p = Timeline.playPath("ab12", 3, "Темный рыцарь / The Dark Knight [2008].mkv", "13906648", null);
        assert p.startsWith("/play/ab12?file=3&name=") && p.endsWith("&tl=13906648") : p;
        assert !p.contains(" ") && !p.contains("/ The") && p.contains("%D0%A2") : p;
        assert Timeline.playPath("ab12", -1, "a&b=c", "1", null).contains("name=a%26b%3Dc") : "& and = stay inside the name";
        assert Timeline.playPath("ab12", 8, "F.S01E09.mkv", "1", "Foundation & Co").endsWith("&tl=1&series=Foundation+%26+Co")
            : "an episode: the series' original name, for the playlist's keys";
    }

    // An episode's /play answer: the playlist, this one first, as the worker's /stream addresses.
    static final String SEASON = "{\"url\":\"https://w/stream/11/4?a\",\"time\":2303,\"percent\":40,\"playlist\":["
        + "[1,9,\"9\",\"F.S01E09.mkv\",\"https://w/stream/11/4?a\"],[1,10,\"10\",\"F.S01E10.mkv\",\"https://w/stream/11/5?b\"],"
        + "[2,1,\"21\",\"F.S02E01.mkv\",\"https://w/stream/11/6?c\"]]}";

    void testPlaylist() {
        Timeline.Link l = Timeline.link(200, SEASON);
        assert l.playlist().size() == 3 && l.startMs() == 2_303_000 : l;
        assert l.playlist().get(1).equals(new Timeline.Episode(1, 10, "10", "F.S01E10.mkv", "https://w/stream/11/5?b"));
        assert Timeline.link(200, "{\"url\":\"u\",\"time\":0,\"percent\":0}").playlist().isEmpty() : "a film";
    }

    // DDD answers with the address it stopped on: that episode is saved, from its start; the ones before it ended.
    void testPlaylistAnswer() {
        Timeline.Playing p = new Timeline.Playing("9", "tv_93740", 1, 9, 2_303_000, Timeline.link(200, SEASON).playlist());
        assert p.at("https://w/stream/11/4?a") == p && p.at(null) == p && p.at("https://elsewhere") == p;
        assert p.before("https://w/stream/11/4?a").isEmpty() && p.before(null).isEmpty();
        Timeline.Playing s2 = p.at("https://w/stream/11/6?c");
        assert s2.equals(new Timeline.Playing("21", "tv_93740", 2, 1, 0)) : s2;
        assert s2.saved(5_000, 3_000_000, false) != null : "a later episode started at 0: its first seconds count";
        assert p.before("https://w/stream/11/6?c").stream().map(Timeline.Episode::key).toList().equals(java.util.List.of("9", "10"));
        assert Timeline.Playing.of(p.save()).equals(p) : "the playlist survives process death too";
    }

    // What DDD is playing survives process death (onSaveInstanceState → onCreate).
    void testPlayingRoundTrip() {
        Timeline.Playing p = new Timeline.Playing("46902274", "tv_1399", 1, 5, 3_000_000);
        assert Timeline.Playing.of(p.save()).equals(p);
        assert Timeline.Playing.of(null) == null;
    }

    // POST /timeline: one point as the worker takes it (it stamps `updated` itself).
    void testTimelineBody() throws Exception {
        String body = new Timeline.Playing("46902274", "tv_1399", 1, 5, 0).body(new long[] {3000, 7000, 43});
        org.json.JSONObject t = new org.json.JSONObject(body).getJSONArray("timelines").getJSONObject(0);
        assert t.getString("hash").equals("46902274") && t.getLong("time") == 3000 && t.getLong("duration") == 7000;
        assert t.getInt("percent") == 43 && t.getString("card").equals("tv_1399");
        assert t.getInt("season") == 1 && t.getInt("episode") == 5 && !t.has("updated") : body;
    }

    // The worker's skip times: an episode and the 5 after it, a film alone, none without an IMDb id.
    void testSegments() {
        Home.Card show = new Home.Card(93740, "tv", "Foundation", "2021", "", 0, "");
        String h = "ab".repeat(20);
        String path = Timeline.segmentsPath(show, "tt0804484", 1, 9, h, 8, "Foundation.S01E09 x.mkv", "Foundation");
        assert path.equals("/segments/tv/93740/tt0804484?e=1:9,1:10,1:11,1:12,1:13,1:14&h=" + h
            + "&f=8&n=Foundation.S01E09+x.mkv&o=Foundation") : path;
        Home.Card film = new Home.Card(872585, "movie", "Oppenheimer", "2023", "", 0, "");
        assert Timeline.segmentsPath(film, "tt15398776", 0, 0, h, 0, "f.mkv", "Oppenheimer")
            .equals("/segments/movie/872585/tt15398776") : "a film: no release, no intro to find";
        assert Timeline.segmentsPath(film, "", 0, 0, h, 0, "f.mkv", "Oppenheimer") == null;
        assert Timeline.segments(200, "{\"1:9\":\"intro:0-58867;credits:3395489-\"}")
            .equals(java.util.Map.of("1:9", "intro:0-58867;credits:3395489-"));
        assert Timeline.segments(403, "{\"error\":\"Forbidden.\"}").isEmpty();
        assert Timeline.segments(502, "<html>").isEmpty();
    }

    // The receiver's decision: only the address playing, or one of its playlist, is a report about it.
    void testReports() {
        Timeline.Playing p = new Timeline.Playing("k1", "tv_1", 1, 1, 0, List.of(new Timeline.Episode(1, 1, "k1", "a", "https://w/s/1"),
            new Timeline.Episode(1, 2, "k2", "b", "https://w/s/2")));
        assert p.reports("https://w/s/1", "https://w/s/1") && p.reports("https://w/s/2", "https://w/s/1");
        assert !p.reports("https://w/s/3", "https://w/s/1") && !p.reports(null, "https://w/s/1") && !p.reports("", "x");
        Timeline.Playing film = new Timeline.Playing("k", "movie_1", 0, 0, 0);
        assert film.reports("https://w/f", "https://w/f") && !film.reports("https://w/g", "https://w/f");
    }

    // A saved playback that is damaged is none (the receiver runs with the app killed).
    void testPlayingOfDamaged() {
        assert Timeline.Playing.of(null) == null && Timeline.Playing.of(new String[0]) == null;
        assert Timeline.Playing.of(new String[] {"k", "movie_1", "0"}) == null;
        assert Timeline.Playing.of(new String[] {"k", "movie_1", "x", "0", "0"}) == null;
        assert Timeline.Playing.of(new String[] {"k", "movie_1", "0", "0", "0", "1", "two", "k", "n", "u"}) == null;
        assert Timeline.Playing.of(new String[] {"k", "movie_1", "0", "0", "0"}).card().equals("movie_1");
    }

    // Mark unwatched: the points are gone from the marks and from the keys DDD answered, so the worker's next answer
    // (which merge() keeps out of those keys) can tell the truth.
    void testForget() {
        java.util.Map<String, Timeline.Point> marks = new java.util.HashMap<>(java.util.Map.of("a", new Timeline.Point(10, 100, 95),
            "b", new Timeline.Point(5, 100, 5)));
        java.util.Set<String> played = new java.util.HashSet<>(java.util.Set.of("a", "b"));
        Timeline.forget(marks, played, List.of("a", "gone"));
        assert marks.keySet().equals(java.util.Set.of("b")) && played.equals(java.util.Set.of("b"));
        Timeline.merge(marks, java.util.Map.of("a", new Timeline.Point(0, 0, 0)), played);
        assert marks.get("a").percent() == 0 : "the worker's answer lands now";
    }

    // The one saved copy (the "playing" file): what goes in comes out, a damaged one is none.
    void testPlayingJson() {
        Timeline.Playing p = new Timeline.Playing("k1", "tv_1", 1, 2, 5000, List.of(new Timeline.Episode(1, 2, "k1", "a \"b\"\n", "https://w/s/1"),
            new Timeline.Episode(1, 3, "k2", "c", "https://w/s/2")));
        assert Timeline.Playing.fromJson(p.toJson()).equals(p);
        Timeline.Playing film = new Timeline.Playing("k", "movie_1", 0, 0, 0);
        assert Timeline.Playing.fromJson(film.toJson()).equals(film);
        for (String bad : new String[] {"", "[]", "{}", "not json", "[\"k\",\"movie_1\"]", "[\"k\",\"c\",\"x\",\"0\",\"0\"]"}) {
            assert Timeline.Playing.fromJson(bad) == null : bad;
        }
    }

    // The process ends behind DDD only for GPU drawing, our own playback, and a film page to come back to.
    void testEndsBehindPlayer() {
        assert Timeline.endsBehindPlayer(true, true, true);
        assert !Timeline.endsBehindPlayer(false, true, true) : "software is within the budget";
        assert !Timeline.endsBehindPlayer(true, false, true) : "the Home key is no playback";
        assert !Timeline.endsBehindPlayer(true, true, false) : "from Home a restore loses its place";
        assert Timeline.waitsForReport(1, true) : "a report still being sent";
        assert Timeline.waitsForReport(0, false) : "a broadcast may wait on the main thread";
        assert !Timeline.waitsForReport(0, true);
    }
}
