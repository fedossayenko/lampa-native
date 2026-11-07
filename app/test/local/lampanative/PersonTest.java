package local.lampanative;

import java.util.List;

class PersonTest {
    void testParsesThePage() throws Exception {
        Person p = Person.parse("""
            {"line": "Born 1953 (73) · Navan, Ireland", "about": "An Irish actor.",
             "rows": [["Best rated", [[20, "tv", "MobLand", "2025", "/p.jpg", 86, ""]]],
             ["Films", [[1, "movie", "GoldenEye", "1995", "/g.jpg", 69, "/b.jpg"]]]]}""");
        assert p.equals(new Person("Born 1953 (73) · Navan, Ireland", "An Irish actor.",
            List.of(new Home.Row("Best rated", List.of(new Home.Card(20, "tv", "MobLand", "2025", "/p.jpg", 86, ""))),
                new Home.Row("Films", List.of(new Home.Card(1, "movie", "GoldenEye", "1995", "/g.jpg", 69, "/b.jpg"))))))
            : p;
    }

    void testUrl() {
        assert Person.url(517).equals(Home.worker + "/person/517?v=2") : Person.url(517);
    }

    // Every genre on Search makes a slug the worker has (GENRE_PAGES')
    void testGenreSlugs() {
        for (String g : Search.GENRES) {
            assert Person.Filters.START.url(g, 1).matches(".*/discover/[a-z]+(-[a-z]+)?\\?type=.*") : g;
        }
    }

    // A genre with filters: the worker's URL and the line saying what the page shows.
    void testFilters() throws Exception {
        Person.Filters f = new Person.Filters(false, "2010", "", "70", "best");
        assert f.url("Science fiction", 2).endsWith("/discover/science-fiction?type=movie&years=2010&country=&score=70"
            + "&sort=best&page=2") : f.url("Science fiction", 2);
        assert f.line(true).equals("Films from the 2010s scored 70 and up, best first, without what you watched") : f.line(true);
        assert new Person.Filters(true, "older", "GB", "", "new").line(false)
            .equals("Series made in United Kingdom before 1980, newest first");
        assert Person.Filters.START.line(false).equals("Films, best first");
        assert Person.Filters.label(Person.Filters.YEARS, "1990").equals("1990s");
        Person.Found found = Person.Found.parse("{\"cards\":[[1,\"movie\",\"Prisoners\",\"2013\",\"/p.jpg\",81,\"\"]],\"pages\":9}");
        assert found.pages() == 9 && found.cards().get(0).name().equals("Prisoners") : found;
    }
}
