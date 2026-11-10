package local.lampanative;

import java.util.List;

class SearchTest {
    // The worker's /search, the query encoded.
    void testUrl() {
        assert Search.url("тёмный р & co").equals(Home.worker + "/search?v=2&q=%D1%82%D1%91%D0%BC%D0%BD%D1%8B%D0%B9+%D1%80+%26+co")
            : Search.url("тёмный р & co");
    }

    void testParsesHomeCards() throws Exception {
        List<Home.Card> r = Search.parse("{\"results\": [[155, \"movie\", \"The Dark Knight\", \"2008\", \"/k.jpg\", 85, "
            + "\"/kb.jpg\"]]}");
        assert r.size() == 1 && r.get(0).id() == 155 && r.get(0).name().equals("The Dark Knight");
        assert Search.parse("{\"results\": []}").isEmpty();
    }

    // Alphabetical grids, 6 to a row (a remote moves through a b c faster than through QWERTY).
    // A pause ends: asked unless those results are shown, or that query is still out ("dark" → "dark ").
    void testAsks() {
        assert Search.asks("dark", null, null);
        assert !Search.asks("dark", "dark", null);
        assert !Search.asks("dark", "dar", "dark");
        assert Search.asks("dark", "dar", "darkn");
    }

    void testKeyboards() {
        assert Search.EN.length() == 36 && Search.EN.startsWith("abcdef") && Search.EN.endsWith("567890");
        assert Search.RU.length() == 33 && Search.RU.startsWith("абвгде") && Search.RU.contains("ё");
    }

    // Space: never first, never twice; ⌫ takes the last character.
    void testTyping() {
        assert Search.type("dar", "k").equals("dark");
        assert Search.type("dark", " ").equals("dark ") && Search.type("dark ", " ").equals("dark ");
        assert Search.type("", " ").isEmpty();
        assert Search.erase("dark").equals("dar") && Search.erase("").isEmpty();
        assert Search.type("x".repeat(100), "y").length() == 100 : "the worker reads 100";
    }

    // The last searches, newest first, each once, at most 8; kept as lines in the settings.
    void testRecent() {
        assert Search.remember("", "dark").equals("dark");
        assert Search.remember("dark\ninter", "inter").equals("inter\ndark");
        assert Search.remember("a\nb\nc\nd\ne\nf\ng\nh", "x").equals("x\na\nb\nc\nd\ne\nf\ng");
        assert Search.remember("dark", "  ").equals("dark") : "nothing typed: nothing kept";
        assert Search.recent("inter\ndark").equals(List.of("inter", "dark")) && Search.recent("").isEmpty();
    }

    // A heard request with a line break is one line: the saved list is split on them.
    void testRememberOneLine() {
        assert Search.remember("", "dark\n knight  ").equals("dark knight");
        assert Search.recent(Search.remember("a", "b\nc")).equals(java.util.List.of("b c", "a"));
    }
}
