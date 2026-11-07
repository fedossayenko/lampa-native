package local.lampanative;

import java.util.List;

class SourcesTest {
    // The worker's live answer for Dune: Part Two, RU/UA, trimmed to one row.
    static final String JSON = """
        {"streams": [["9c1cf8aa4f0a00fda70d149238e7d0fe0b352a00", 0, "Dune.Part.Two.2024.2160p.UHD.BDREMUX.mkv",
                      ["4K", "DV", "HDR", "REMUX"], "69.35 GB", 359, "Rutracker", ["RU", "UA", "EN"], 1]],
         "next": 8, "total": 39, "languages": [["RU", 39], ["UA", 33], ["EN", 100]]}""";

    void testParsesAPage() throws Exception {
        Sources.Page p = Sources.parse(JSON);
        assert p.streams().equals(List.of(new Sources.Source("9c1cf8aa4f0a00fda70d149238e7d0fe0b352a00", 0,
            "Dune.Part.Two.2024.2160p.UHD.BDREMUX.mkv", List.of("4K", "DV", "HDR", "REMUX"), "69.35 GB", 359,
            "Rutracker", List.of("RU", "UA", "EN"), 1))) : p.streams();
        assert p.next() == 8 && p.total() == 39 : p;
        assert p.languages().equals(List.of(new Sources.Language("RU", 39), new Sources.Language("UA", 33),
            new Sources.Language("EN", 100))) : p.languages();
    }

    // The row a source list focuses: the release asked for (Continue), else the first.
    void testFocus() {
        java.util.function.Function<String, Sources.Source> src = h -> new Sources.Source(h, 0, "", List.of(), "", 0,
            "", List.of(), 1);
        List<Sources.Source> list = List.of(src.apply("a"), src.apply("b"), src.apply("c"));
        assert Sources.focus(list, "c") == 2;
        assert Sources.focus(list, "x") == 0 && Sources.focus(list, null) == 0;
    }

    // Torrentio refused the worker (its 502) lately: the stick asks it for 10 minutes, every page.
    void testFallsBack() {
        assert Sources.fallsBack(502) : "Torrentio refused the worker";
        assert !Sources.fallsBack(500) && !Sources.fallsBack(404) && !Sources.fallsBack(400);
    }

    void testRefusedLately() {
        assert !Sources.refusedLately(0, 5_000_000);
        assert Sources.refusedLately(1_000_000, 1_000_000 + 599_000);
        assert !Sources.refusedLately(1_000_000, 1_000_000 + 600_000);
    }

    void testUrls() {
        assert Sources.url("tt15239678", 0, 0, "RU,UA", 0).equals(Home.worker + "/streams/movie/tt15239678?lang=RU,UA");
        assert Sources.url("tt10919420", 1, 2, "RU,UA", 8)
            .equals(Home.worker + "/streams/series/tt10919420/1/2?lang=RU,UA&from=8");
        assert Sources.url("tt1", 0, 0, "", 0).endsWith("/streams/movie/tt1?lang=") : "empty = every language";
        assert Sources.path("tt1", 1, 2, "RU", 0).equals("/streams/series/tt1/1/2?lang=RU") : "for ask(): POST";
        // the stick asks the streams addon itself when the worker can't (429)
        assert Sources.addon("https://a.example/", "tt1", 0, 0).equals("https://a.example/stream/movie/tt1.json");
        assert Sources.addon("https://a.example/c=x", "tt2", 1, 2).equals("https://a.example/c=x/stream/series/tt2:1:2.json");
    }

    // The second line of a row: size, tracker, audio languages; seeders only when TorBox doesn't have it cached
    // (then TorBox has to download it, and seeders say how fast; a cached one streams from TorBox's copy).
    void testDetails() throws Exception {
        Sources.Source s = Sources.parse(JSON).streams().get(0);
        assert Sources.details(s).equals("69.35 GB · Rutracker · RU UA EN") : Sources.details(s);
        for (int cached : new int[] {0, -1}) {
            Sources.Source other = new Sources.Source(s.hash(), s.fileIdx(), s.title(), s.badges(), s.size(), s.seeders(),
                s.tracker(), s.languages(), cached);
            assert Sources.details(other).equals("69.35 GB · 359 seeders · Rutracker · RU UA EN") : Sources.details(other);
        }
        assert Sources.cachedLabel(1).equals("⚡\uFE0E Cached") && Sources.cachedLabel(0).equals("Not cached");
        assert Sources.cachedLabel(-1).isEmpty() : "unknown: say nothing";
        Sources.Source bare = new Sources.Source("h", 0, "t", List.of(), "1 GB", 5, "1337x", List.of(), 1);
        assert Sources.details(bare).equals("1 GB · 1337x") : "no languages, no trailing dot: " + Sources.details(bare);
    }

    void testBadgeColorsByKind() {
        assert Sources.badgeColor("DV") == Sources.VISION;
        assert Sources.badgeColor("HDR") == Sources.HDR && Sources.badgeColor("HDR10+") == Sources.HDR;
        assert Sources.badgeColor("REMUX") == Sources.REMUX;
        for (String audio : List.of("Atmos", "TrueHD", "DTS", "DD+", "DD")) assert Sources.badgeColor(audio) == Sources.AUDIO;
        for (String other : List.of("4K", "1080p", "720p", "HEVC", "AV1", "new")) assert Sources.badgeColor(other) == Sources.PLAIN;
    }

    // The language setting: "RU,UA" by default, "" for all; toggling keeps the order the codes were added in.
    void testToggleLanguages() {
        assert Sources.toggle("RU,UA", "EN").equals("RU,UA,EN");
        assert Sources.toggle("RU,UA,EN", "UA").equals("RU,EN");
        assert Sources.toggle("RU", "RU").isEmpty() : "none left = all";
        assert Sources.toggle("", "EN").equals("EN") : "from all languages to one";
    }

    // The picker's tick marks.
    void testSelected() {
        assert Sources.selected("RU,UA", "UA") && !Sources.selected("RU,UA", "EN");
        assert !Sources.selected("RU,UAE", "UA") : "whole codes only";
        assert !Sources.selected("", "RU") : "all languages: nothing ticked, the All row says so";
    }

    // DDD's audio for this film (its audio_lang extra): Russian first when RU or UA is picked; else DDD's own setting.
    void testAudioLanguages() {
        assert Sources.audio("RU,UA").equals("ru,uk");
        assert Sources.audio("UA,EN,RU").equals("ru,uk") : "Russian first whatever the order";
        assert Sources.audio("UA").equals("uk");
        assert Sources.audio("EN").isEmpty() && Sources.audio("").isEmpty() : "no RU/UA: DDD decides";
    }

    // ▶ Play's pick: the biggest cached, never a disc; its line.
    void testBest() {
        Sources.Source disc = new Sources.Source("a", 0, "Disc", List.of("Disc", "4K"), "90 GB", 9, "x", List.of("RU"), 1);
        Sources.Source big = new Sources.Source("b", 0, "Big", List.of("4K", "DV", "REMUX", "HEVC", "Atmos"), "74.3 GB", 9, "x",
            List.of("RU"), 0);
        Sources.Source cached = new Sources.Source("c", 0, "Cached", List.of("4K", "DV", "HDR", "DD+"), "21.7 GB", 9, "x",
            List.of("RU", "UA"), 1);
        assert Sources.best(List.of(disc, big, cached)) == cached;
        assert Sources.best(List.of(disc, big)) == null;
        assert Sources.picture(big).equals(List.of("4K", "DV", "REMUX"));
        assert Sources.playsText(cached, "RU").equals("4K DV HDR · RU UA · ⚡\uFE0E cached, 21.7 GB")
            : Sources.playsText(cached, "RU");
        List<String> many = List.of("EN", "JP", "RU", "IT", "UA", "FR");
        assert Sources.tracks(many, "RU,UA").equals("RU UA +4") : Sources.tracks(many, "RU,UA");
        assert Sources.tracks(many, "").equals("EN JP RU +3") : Sources.tracks(many, "");
        assert Sources.tracks(many, "DE").equals("EN JP RU +3");
    }
}
