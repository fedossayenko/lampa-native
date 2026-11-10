package local.lampanative;

import java.util.List;

class HomeTest {
    static final String JSON = """
        {"rows": [
          ["Watching now", [[791523, "movie", "Wildwood", "2026", "/a.jpg", 86, "/b.jpg"],
                            [1399, "tv", "Game of Thrones", "2011", "/p.jpg", 85, ""]]],
          ["Top series", []]
        ]}""";

    void testParsesRowsInOrder() throws Exception {
        List<Home.Row> rows = Home.parse(JSON);
        assert rows.size() == 2 : rows;
        assert rows.get(0).title().equals("Watching now") : rows.get(0).title();
        assert rows.get(1).title().equals("Top series") && rows.get(1).cards().isEmpty() : rows.get(1);
    }

    void testParsesEveryCardField() throws Exception {
        Home.Card first = Home.parse(JSON).get(0).cards().get(0);
        assert first.equals(new Home.Card(791523, "movie", "Wildwood", "2026", "/a.jpg", 86, "/b.jpg")) : first;
        assert Home.parse(JSON).get(0).cards().get(1).type().equals("tv");
    }

    // A card without a score (TMDB has no votes yet) or a backdrop (a /home cached from before it had one) still
    // parses: no badge, no backdrop.
    void testMissingScoreAndBackdrop() throws Exception {
        Home.Card c = Home.parse("{\"rows\": [[\"r\", [[1, \"movie\", \"n\", \"2020\", \"/p\"]]]]}").get(0).cards().get(0);
        assert c.score() == 0 && c.backdrop().isEmpty() : c;
    }

    void testBrokenJsonThrows() {
        try {
            Home.parse("{\"rows\": 5}");
            assert false : "expected an exception";
        } catch (org.json.JSONException expected) {
            // the screen shows its retry message
        }
    }

    void testScoreColorsLikeLampa() {
        assert Home.scoreColor(70) == Home.GREEN && Home.scoreColor(97) == Home.GREEN;
        assert Home.scoreColor(50) == Home.ORANGE && Home.scoreColor(69) == Home.ORANGE;
        assert Home.scoreColor(49) == Home.RED && Home.scoreColor(1) == Home.RED;
    }

    // The open film survives process death (killed while DDD plays): onSaveInstanceState → onCreate.
    void testCardRoundTrip() {
        Home.Card c = new Home.Card(155, "movie", "The Dark Knight", "2008", "/p.jpg", 85, "");
        assert Home.Card.of(c.save()).equals(c);
        assert Home.Card.of(null) == null;
    }

    // First setup (Main.configure): an https host with no path, a long enough token, nothing else.
    void testValidSetup() {
        assert Home.valid("server", "https://w.me.workers.dev") && Home.valid("server", "https://10.0.0.2:8443");
        assert !Home.valid("server", "http://w.me.workers.dev") && !Home.valid("server", "https://w.dev/x")
            && !Home.valid("server", "https://w.dev/");
        assert Home.valid("token", "0123456789abcdef0123") && !Home.valid("token", "short")
            && !Home.valid("token", "has space 0123456789");
    }

    // Where focus goes when your rows are drawn again: the film that had it, wherever it moved, else the first.
    void testFocusIndex() {
        Home.Card a = new Home.Card(1, "movie", "A", "", "", 0, ""), b = new Home.Card(2, "movie", "B", "", "", 0, ""),
            tv = new Home.Card(2, "tv", "B", "", "", 0, "");
        List<Home.Card> now = java.util.Arrays.asList(a, null, tv, b);
        assert Home.focusIndex(now, b, false) == 3 : "a film is found by id and type, a series 2 is not movie 2";
        assert Home.focusIndex(now, tv, false) == 2;
        assert Home.focusIndex(now, new Home.Card(9, "movie", "Gone", "", "", 0, ""), false) == 0 : "left the rows: the first";
        assert Home.focusIndex(now, HISTORY_TAG, false) == 0 : "History tile had it: the first (the tile itself is Main's)";
        assert Home.focusIndex(now, null, true) == 0 : "the card Home focused at start moves in";
        assert Home.focusIndex(now, null, false) == -1 : "focus was somewhere else: left alone";
        assert Home.focusIndex(List.of(), b, true) == -1;
    }

    private static final String HISTORY_TAG = "history";

    // /mine on disk: only an answer that changed and was drawn.
    void testKeepMine() {
        assert Home.keepMine("old", "new", "new");
        assert !Home.keepMine("same", "same", "same") : "unchanged";
        assert !Home.keepMine("old", "garbage", "old") : "not valid: not drawn, the good one stays on disk";
    }
}
