package local.lampanative;

import java.util.List;

class FilmTest {
    // The worker's answer for Squid Game (trimmed overview and cast; a collection made up: series have none).
    static final String JSON = """
        {"name": "Squid Game", "original": "오징어 게임", "line": "2021 · 2 seasons · Drama",
         "overview": "Hundreds of players accept an invitation.",
         "scores": [["Excellent", "90", 90], ["TMDB", "8.4", 84], ["RT", "99%", 99]],
         "seasons": [["S1", "8.2", 82]], "imdb": "tt10919420", "episodes": [[1, 9], [2, 7]],
         "people": "Created by Hwang Dong-hyuk · Cast Lee Jung-jae",
         "cast": [["Lee Jung-jae", "Seong Gi-hun", "/a.jpg", 1]], "collection": "Squid Collection",
         "parts": [[93405, "tv", "Squid Game", "2021", "/p.jpg", 79, "/b.jpg"]], "trailer": "oqxAJKy0ii4"}""";

    void testParsesEveryField() throws Exception {
        Film f = Film.parse(JSON);
        assert f.equals(new Film("Squid Game", "오징어 게임", "2021 · 2 seasons · Drama",
            "Hundreds of players accept an invitation.",
            List.of(new Film.Score("Excellent", "90", 90), new Film.Score("TMDB", "8.4", 84),
                new Film.Score("RT", "99%", 99)),
            List.of(new Film.Score("S1", "8.2", 82)), "tt10919420",
            List.of(new Film.Season(1, 9), new Film.Season(2, 7)), "Created by Hwang Dong-hyuk · Cast Lee Jung-jae",
            List.of(new Film.Actor("Lee Jung-jae", "Seong Gi-hun", "/a.jpg", 1)), "Squid Collection",
            List.of(new Home.Card(93405, "tv", "Squid Game", "2021", "/p.jpg", 79, "/b.jpg")), "oqxAJKy0ii4")) : f;
    }

    // A film's minutes: when it ends if started now; a series' (0) none.
    void testRuntime() throws Exception {
        assert Film.parse("{\"name\": \"F\", \"original\": \"\", \"line\": \"\", \"overview\": \"\", \"scores\": [], "
            + "\"seasons\": [], \"people\": \"\", \"runtime\": 166}").runtime() == 166;
        assert Film.endsAt(166, java.time.LocalTime.of(20, 55)).equals("ends at 23:41");
        assert Film.endsAt(130, java.time.LocalTime.of(23, 0)).equals("ends at 01:10"); // past midnight
        assert Film.endsAt(0, java.time.LocalTime.of(20, 55)).isEmpty();
        assert Film.length(166).equals("2h 46m") && Film.length(58).equals("58m");
    }

    // What the worker sends when TMDB knows nothing but the name and MDBList is down; also a /card cached on the stick
    // from before it had imdb, episodes, cast and the collection.
    void testEmptyPage() throws Exception {
        Film f = Film.parse("""
            {"name": "Film", "original": "", "line": "", "overview": "",
             "scores": [], "seasons": [], "people": ""}""");
        assert f.name().equals("Film") && f.scores().isEmpty() && f.seasons().isEmpty() : f;
        assert f.imdb().isEmpty() && f.episodes().isEmpty() : f;
        assert f.cast().isEmpty() && f.collection().isEmpty() && f.parts().isEmpty() && f.similar().isEmpty() : f;
    }

    // More like this (8b): TMDB's recommendations, as Home cards.
    void testSimilar() throws Exception {
        Film f = Film.parse("""
            {"name": "Dune: Part Two", "original": "", "line": "", "overview": "", "scores": [], "seasons": [],
             "people": "", "similar": [[438631, "movie", "Dune", "2021", "/d.jpg", 78, ""]]}""");
        assert f.similar().equals(List.of(new Home.Card(438631, "movie", "Dune", "2021", "/d.jpg", 78, ""))) : f;
    }

    // A /card cached on the stick from before the cast had person ids: the photos show, OK on them does nothing.
    void testCastWithoutIds() throws Exception {
        Film f = Film.parse("""
            {"name": "Film", "original": "", "line": "", "overview": "", "scores": [], "seasons": [], "people": "",
             "cast": [["Lee Jung-jae", "Seong Gi-hun", "/a.jpg"]]}""");
        assert f.cast().equals(List.of(new Film.Actor("Lee Jung-jae", "Seong Gi-hun", "/a.jpg", 0))) : f.cast();
    }

    // What the page shows at once, from the card, until /card answers.
    void testPageFromTheCard() {
        Film f = Film.of(new Home.Card(155, "movie", "The Dark Knight", "2008", "/p", 85, "/b"));
        assert f.name().equals("The Dark Knight") && f.line().equals("2008") && f.original().isEmpty() : f;
        assert f.imdb().isEmpty() && f.scores().isEmpty() && f.cast().isEmpty() && f.parts().isEmpty() : f;
    }

    void testBrokenJsonThrows() {
        try {
            Film.parse("{\"name\": \"Film\"}");
            assert false : "expected an exception";
        } catch (org.json.JSONException expected) {
            // the page keeps what the card already showed
        }
    }

    void testSeason() throws Exception {
        List<Film.Episode> s = Film.season("{\"episodes\":[[8,\"Better Call Saul\",47,\"Badger is arrested.\",\"/s8.jpg\","
            + "\"2009-04-26\"],[9,\"Episode 9\",0,\"\",\"\",\"2026-11-10\"],[10,\"Episode 10\",0,\"\",\"\"]]}");
        assert s.equals(List.of(new Film.Episode(8, "Better Call Saul", 47, "Badger is arrested.", "/s8.jpg", "2009-04-26"),
            new Film.Episode(9, "Episode 9", 0, "", "", "2026-11-10"),
            new Film.Episode(10, "Episode 10", 0, "", "", ""))) : "an older answer has no date: " + s;
        assert Film.seasonUrl(new Home.Card(1396, "tv", "n", "2008", "/p", 90, ""), 2).endsWith("/season/1396/2?v=2");
        assert Film.seasonUrl(new Home.Card(1396, "tv", "n", "2008", "/p", 90, ""), 2, "RU").endsWith("?v=2&lang=RU");
        assert Film.seasonUrl(new Home.Card(1396, "tv", "n", "2008", "/p", 90, ""), 2, null).endsWith("?v=2");
    }

    // Its row: TMDB's stand-in name once; the day it airs while not out.
    void testEpisodeTitleAndAirs() {
        assert Film.title(8, new Film.Episode(8, "Better Call Saul", 47, "", "", "")).equals("8 · Better Call Saul");
        assert Film.title(9, new Film.Episode(9, "Episode 9", 0, "", "", "")).equals("Episode 9");
        assert Film.title(5, null).equals("Episode 5") : "before /season answers";
        java.time.LocalDate today = java.time.LocalDate.of(2026, 11, 2); // a Monday
        assert Film.airs("2026-11-10", today).equals("Airs Tuesday, 10 Nov") : Film.airs("2026-11-10", today);
        assert Film.airs("2026-11-03", today).equals("Airs tomorrow");
        assert Film.airs("2026-11-02", today).isEmpty() && Film.airs("2009-04-26", today).isEmpty() : "out";
        assert Film.airs("", today).isEmpty() : "no date";
    }

    // Film page URLs: TMDB's two types map to the worker's /card routes; v = the answer's version, so a day-old
    // cached answer of an older one is never read.
    void testCardUrl() {
        assert Film.url(new Home.Card(693134, "movie", "n", "2024", "/p", 81, "/b")).endsWith("/card/movie/693134?v=8");
        assert Film.url(new Home.Card(126308, "tv", "n", "2024", "/p", 84, "")).endsWith("/card/tv/126308?v=8");
    }

    // Story so far: the three episodes before where Continue goes on, back into the season before.
    void testBefore() {
        List<Film.Season> seasons = List.of(new Film.Season(1, 7), new Film.Season(2, 13));
        assert Film.before(seasons, new int[] {2, 2}, 3).stream().map(java.util.Arrays::toString).toList()
            .equals(List.of("[1, 6]", "[1, 7]", "[2, 1]"));
        assert Film.before(seasons, new int[] {1, 2}, 3).size() == 1; // only S1 E1 before it
        assert Film.before(seasons, new int[] {1, 1}, 3).isEmpty() && Film.before(seasons, null, 3).isEmpty();
    }

    void testRecapBody() throws Exception {
        String b = Film.recapBody(new Home.Card(1396, "tv", "Breaking Bad", "2008", "/p", 89, ""), 2, 8,
            List.<String[]>of(new String[] {"S2 E8", "Better Call Saul", "Badger is arrested."}), "Russian");
        org.json.JSONObject o = new org.json.JSONObject(b);
        assert o.getString("key").equals("tv_1396 S2E8") && o.getString("upto").equals("season 2, episode 8") : b;
        assert o.getString("series").equals("Breaking Bad") && o.getString("year").equals("2008") : b;
        assert o.getJSONArray("episodes").getJSONArray(0).getString(2).equals("Badger is arrested.") : b;
        assert o.getString("language").equals("Russian") : b;
    }
}
