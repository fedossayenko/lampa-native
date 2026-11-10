package local.lampanative;

import java.util.List;

class CubTest {
    // CUB's own answers (2026-10-31), the device code shortened.
    void testCode() throws Exception {
        Cub.Code c = Cub.code("{\"secuses\":true,\"device_code\":\"d1\",\"user_code\":\"QNA3-79JM\",\"verification_uri\":"
            + "\"https://cub.red/activate\",\"verification_uri_complete\":\"https://cub.red/activate?code=QNA3-79JM\","
            + "\"expires_in\":600,\"interval\":5}");
        assert c.user().equals("QNA3-79JM") && c.device().equals("d1") && c.site().equals("cub.red/activate") : c;
        assert c.expires() == 600 && c.interval() == 5;
        assert Cub.code("{\"device_code\":\"d\",\"user_code\":\"u\",\"interval\":0}").interval() == 2 : "never a busy loop";
    }

    void testToken() throws Exception {
        assert Cub.token("{\"secuses\":true,\"status\":\"pending\",\"interval\":5}") == null;
        assert Cub.token("{\"status\":\"approved\",\"token\":\"t\",\"email\":\"e\"}").equals("t");
        assert Cub.newCode("{\"error\":true,\"code\":804,\"text\":\"Код устарел, запросите новый\"}");
        assert Cub.newCode("{\"error\":true,\"code\":803}");
        assert !Cub.newCode("{\"error\":true,\"code\":700}") && !Cub.newCode("<html>bad gateway");
    }

    void testProfiles() throws Exception {
        List<Cub.Profile> p = Cub.profiles("{\"secuses\":true,\"profiles\":[{\"id\":101,\"name\":\"Alex\",\"main\":1},"
            + "{\"id\":7,\"name\":\"Kids\",\"main\":0}]}");
        assert p.equals(List.of(new Cub.Profile(101, "Alex", true), new Cub.Profile(7, "Kids", false))) : p;
    }

    void testAccountFile() {
        Cub.Account a = new Cub.Account("t\"oken", 101, "Alex");
        assert a.equals(Cub.Account.of(a.save()));
        assert Cub.Account.of(null) == null && Cub.Account.of("") == null && Cub.Account.of("{\"token\":\"\"}") == null;
    }

    void testLearned() {
        assert Cub.Mine.parse("{\"rows\":[],\"learned\":{\"movie_603\":93}}").learned().equals(java.util.Map.of("movie_603", "93"));
        assert Cub.Mine.parse("{\"rows\":[]}").learned().isEmpty() && Cub.Mine.parse("").learned().isEmpty();
        assert Cub.Mine.parse("{\"learned\":{}}").learnBound() && Cub.Mine.parse("{\"learned\":{\"movie_1\":5}}").learnBound();
        assert !Cub.Mine.parse("{\"rows\":[]}").learnBound() && !Cub.Mine.parse("").learnBound();
    }

    void testMarks() {
        assert Cub.Mine.parse("{\"rows\":[],\"marks\":{\"tv_93740\":\"scheduled\",\"movie_7340\":\"thrown\"}}").marks()
            .equals(java.util.Map.of("tv_93740", "scheduled", "movie_7340", "thrown"));
        assert Cub.Mine.parse("{\"rows\":[]}").marks().isEmpty() && Cub.Mine.parse("").marks().isEmpty();
        assert Cub.markName("scheduled").equals("Finish later") && Cub.markName("look") == null && Cub.markName(null) == null;
    }

    void testGrades() throws Exception {
        assert Cub.Mine.parse("{\"rows\":[],\"grades\":{\"movie_603\":2,\"tv_1399\":-1,\"movie_10\":\"skip\"}}").grades()
            .equals(java.util.Map.of("movie_603", "2", "tv_1399", "-1", "movie_10", "skip"));
        assert Cub.Mine.parse("{\"rows\":[]}").grades().isEmpty() && Cub.Mine.parse("").grades().isEmpty();
        assert Cub.Mine.parse("{\"rows\":[],\"new\":{\"tv_93740\":\"S3 E5\"}}").fresh().equals(java.util.Map.of("tv_93740", "S3 E5"));
        assert Cub.Mine.parse("{\"rows\":[]}").fresh().isEmpty();
        assert Cub.Mine.parse("{\"rows\":[],\"left\":{\"movie_603\":4000}}").left().equals(java.util.Map.of("movie_603", "4000"));
        assert Cub.Mine.parse("{\"rows\":[]}").left().isEmpty();
        assert Cub.Mine.parse("{\"rows\":[],\"percent\":{\"movie_603\":43}}").percent().equals(java.util.Map.of("movie_603", "43"));
        // a poster says your grade (Lampa's tag); "not mine" says nothing
        assert Cub.posterGrade("2")[1].equals("Loved") && Cub.posterGrade("-1")[1].equals("Bad");
        assert Cub.posterGrade("skip") == null && Cub.posterGrade(null) == null;
        // the colors are the app's own (GRADES), never the worker's: each one Color.parseColor reads
        for (String[] g : Cub.GRADES) assert g[2].matches("#[0-9a-f]{6}") : g[2];
        assert Cub.Mine.parse("{\"rows\":[],\"mylist\":[\"movie_603\",\"tv_1399\"]}").list().equals(java.util.Set.of("movie_603", "tv_1399"));
        assert Cub.Mine.parse("{\"rows\":[]}").list().isEmpty() && Cub.Mine.parse("").list().isEmpty();
        assert Cub.Mine.parse("{\"rows\":[],\"seen\":[\"movie_8\"]}").seen().equals(java.util.Set.of("movie_8"));
        // a genre page hides what you watched
        Cub.Mine watched = Cub.Mine.parse("{\"grades\":{\"movie_1\":\"skip\"},\"marks\":{\"movie_2\":\"viewed\",\"movie_3\":\"thrown\","
            + "\"movie_4\":\"scheduled\"},\"seen\":[\"movie_5\"]}");
        for (String k : new String[] {"movie_1", "movie_2", "movie_3", "movie_5"}) assert watched.watched(k) : k;
        assert !watched.watched("movie_4") && !watched.watched("movie_6"); // Finish later: not yet
        assert Cub.grade("2")[1].equals("Loved") && Cub.grade("skip")[1].startsWith("Didn't watch") && Cub.grade(null) == null;
        // what Lampa's grades plugin keeps (grades-patch.js setGrade): a film's and a series' card by Lampa's fields
        org.json.JSONObject film = new org.json.JSONObject(Cub.gradeBody(
            new Home.Card(603, "movie", "The Matrix", "1999", "/m.jpg", 82, "/b.jpg"), "", "2", 5L))
            .getJSONObject("grades").getJSONObject("movie_603");
        assert film.getInt("g") == 2 && film.getLong("t") == 5L : film;
        assert film.getJSONObject("card").similar(new org.json.JSONObject("{\"id\":603,\"title\":\"The Matrix\","
            + "\"original_title\":\"The Matrix\",\"release_date\":\"1999\",\"poster_path\":\"/m.jpg\","
            + "\"backdrop_path\":\"/b.jpg\",\"vote_average\":8.2,\"source\":\"tmdb\"}")) : film;
        org.json.JSONObject tv = new org.json.JSONObject(Cub.gradeBody(
            new Home.Card(93740, "tv", "Foundation", "2021", "/f.jpg", 0, ""), "Фонд", "skip", 6L))
            .getJSONObject("grades").getJSONObject("tv_93740");
        assert tv.getString("g").equals("skip") && tv.getJSONObject("card").similar(new org.json.JSONObject(
            "{\"id\":93740,\"name\":\"Foundation\",\"original_name\":\"Фонд\",\"first_air_date\":\"2021\","
            + "\"poster_path\":\"/f.jpg\",\"source\":\"tmdb\"}")) : tv;
        org.json.JSONObject none = new org.json.JSONObject(Cub.gradeBody(
            new Home.Card(1, "movie", "X", "", "", 0, ""), "", null, 7L)).getJSONObject("grades").getJSONObject("movie_1");
        assert none.isNull("g") && none.getLong("t") == 7L && !none.has("card") : "removed: kept as g null";
    }

    // Continue browsing's series lines and Coming up, from /mine.
    void testComingUp() throws Exception {
        String json = "{\"rows\":[],\"next\":{\"tv_1\":\"S3 E5 · up next\"},\"coming\":[[\"2026-11-02\","
            + "[1,\"tv\",\"Foundation\",\"2021\",\"/p.jpg\",76,\"/b.jpg\"],\"S3 E5 · out with RU/UA audio\",\"out\",[3,5]],"
            + "[\"\",[2,\"movie\",\"Dune: Part Three\",\"2026\",\"\",0,\"\"],\"From My list\",\"film\"]]}";
        assert Cub.Mine.parse(json).next().equals(java.util.Map.of("tv_1", "S3 E5 · up next"));
        assert java.util.Arrays.equals(Cub.episode("S3 E5 · up next"), new int[] {3, 5});
        assert java.util.Arrays.equals(Cub.episode("S12 E10 · 12m left"), new int[] {12, 10});
        assert Cub.episode(null) == null && Cub.episode("26 ep") == null;
        List<Cub.Coming> c = Cub.Mine.parse(json).coming();
        assert c.size() == 2 && c.get(0).card().name().equals("Foundation") && c.get(0).state().equals("out") : c;
        assert c.get(0).season() == 3 && c.get(0).episode() == 5 : c;
        assert c.get(1).date().isEmpty() && c.get(1).card().type().equals("movie") && c.get(1).season() == 0 : c;
        assert Cub.Mine.parse("{\"rows\":[]}").coming().isEmpty(); // a /mine from before it
        java.time.LocalDate mon = java.time.LocalDate.of(2026, 11, 2);
        assert java.util.Arrays.equals(Cub.day("2026-11-02", mon), new String[] {"Today", "Mon 2 Nov"});
        assert java.util.Arrays.equals(Cub.day("2026-11-03", mon), new String[] {"Tomorrow", "Tue 3 Nov"});
        assert java.util.Arrays.equals(Cub.day("2026-11-05", mon), new String[] {"Thursday", "5 Nov"});
        assert java.util.Arrays.equals(Cub.day("2026-11-13", mon), new String[] {"Friday", "13 Nov"});
        assert java.util.Arrays.equals(Cub.day("", mon), new String[] {"Later", "no date yet"});
    }

    void testNews() {
        List<Cub.News> n = Cub.Mine.parse("{\"news\":[[1762113600000,[93740,\"tv\",\"Foundation\",\"2021\",\"/f.jpg\",77,\"\"],"
            + "\"Foundation · S3 E6\",\"Out in Russian\"]]}").news();
        assert n.size() == 1 && n.get(0).card().id() == 93740 && n.get(0).head().equals("Foundation · S3 E6") : n;
        assert n.get(0).line().equals("Out in Russian") && n.get(0).t() == 1762113600000L;
        assert Cub.Mine.parse("{\"rows\":[]}").news().isEmpty() && Cub.Mine.parse("").news().isEmpty();
        java.time.LocalDate mon = java.time.LocalDate.of(2026, 11, 2);
        java.util.function.Function<String, String> a = (at) -> Cub.ago(java.time.LocalDateTime.parse(at), mon);
        assert a.apply("2026-11-02T18:02").equals("18:02") && a.apply("2026-11-01T09:00").equals("Yesterday");
        assert a.apply("2026-10-30T21:00").equals("Friday") && a.apply("2026-10-12T10:00").equals("12 Oct");
    }

    void testFollows() {
        assert Cub.Mine.parse("{\"follows\":[137427,505710]}").follows().equals(java.util.Set.of(137427, 505710));
        assert Cub.Mine.parse("{\"rows\":[]}").follows().isEmpty() && Cub.Mine.parse("").follows().isEmpty();
    }

    // /mine/history's rows, and their groups by day in the stick's time zone
    void testHistory() throws Exception {
        List<Cub.Played> h = Cub.history("{\"items\":[[1762113600000,[196322,\"tv\",\"Dark Matter\",\"2024\",\"/d.jpg\",80,\"\"],"
            + "\"S1 E6 and E7\",0,1,[\"15\",\"16\"]],[1746100800000,[12,\"movie\",\"Never\",\"\",\"\",0,\"\"],\"\",-1,0,[]]]}");
        assert h.size() == 2 && h.get(0).card().name().equals("Dark Matter") && h.get(0).what().equals("S1 E6 and E7") : h;
        assert h.get(0).left() == 0 && h.get(0).lampa() && h.get(0).hashes().equals(List.of("15", "16")) : h;
        assert h.get(1).left() == -1 && !h.get(1).lampa() && h.get(1).hashes().isEmpty() : h;
        java.time.LocalDate mon = java.time.LocalDate.of(2026, 11, 2);
        java.util.function.Function<String, String> g = (at) -> String.join(" | ", Cub.played(java.time.LocalDateTime.parse(at), mon));
        assert g.apply("2026-11-02T21:40").equals("Today | 21:40");
        assert g.apply("2026-11-01T23:10").equals("Yesterday | 23:10");
        assert g.apply("2026-10-27T00:30").equals("This week | Tuesday") : g.apply("2026-10-27T00:30");
        assert g.apply("2026-10-26T22:00").equals("October | 26 Oct");
        assert g.apply("2025-05-01T12:00").equals("May 2025 | 1 May");
    }

    // TMDB's date in another shape ("2026-11", text) is no date yet, never an exception on the UI thread.
    void testDatesOfAnotherShape() {
        java.time.LocalDate today = java.time.LocalDate.of(2026, 11, 2);
        for (String odd : new String[] {"2026-11", "soon", "11/10/2026"}) {
            assert Cub.date(odd) == null : odd;
            assert Cub.day(odd, today)[0].equals("Later") : odd;
        }
        assert Cub.date("2026-11-10").equals(java.time.LocalDate.of(2026, 11, 10));
        assert Cub.date("") == null;
    }

    // A date read once: the overload says what the string form says, and nothing for none.
    void testDayOfReadDate() {
        java.time.LocalDate today = java.time.LocalDate.of(2026, 11, 2);
        assert Cub.day(Cub.date("2026-11-03"), today)[0].equals("Tomorrow");
        assert Cub.day((java.time.LocalDate) null, today)[0].equals("Later");
        assert Cub.date("") == null;
    }

    // One parse gives every part; a damaged or missing one is empty and the rest stay; the app's own changes copy it.
    void testMineParsedOnce() {
        Cub.Mine all = Cub.Mine.parse("{\"marks\":{\"tv_1\":\"viewed\"},\"grades\":{\"movie_1\":2},\"mylist\":[\"movie_1\"],"
            + "\"follows\":[7],\"learned\":{},\"coming\":[[\"2026-11-02\",[1,\"tv\",\"F\",\"2021\",\"\",0,\"\"],\"x\",\"out\",[3,5]],"
            + "[\"\",\"broken\"]],\"news\":[[1,[1,\"tv\",\"F\",\"2021\",\"\",0,\"\"],\"h\",\"l\"]]}");
        assert all.marks().equals(java.util.Map.of("tv_1", "viewed")) && all.grades().get("movie_1").equals("2");
        assert all.list().contains("movie_1") && all.follows().contains(7) && all.learnBound();
        assert all.coming().size() == 1 && all.comingOut() == 1 : "a damaged row ends Coming up, what came before stays";
        assert all.news().size() == 1 && all.seen().isEmpty() && all.fresh().isEmpty();
        for (String none : new String[] {"", "not json", "[]", "{\"rows\":[]}"}) {
            Cub.Mine m = Cub.Mine.parse(none);
            assert m.equals(Cub.Mine.NONE) : none;
            assert m.marks().isEmpty() && m.coming().isEmpty() && !m.learnBound() && m.comingOut() == 0;
        }
        Cub.Mine graded = all.withGrades(java.util.Map.of("movie_2", "0")).withList(java.util.Set.of()).withFollows(java.util.Set.of(8))
            .withMarks(java.util.Map.of());
        assert graded.grades().equals(java.util.Map.of("movie_2", "0")) && graded.list().isEmpty() && graded.follows().equals(java.util.Set.of(8));
        assert graded.marks().isEmpty() && graded.coming().equals(all.coming()) && graded.learnBound() : "the rest is carried over";
    }
}
