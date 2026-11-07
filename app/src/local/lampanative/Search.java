package local.lampanative;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;

/** The search screen's logic: its keyboards, typing, the last searches, the worker's /search. No android.*. */
final class Search {
    private Search() {}

    // Alphabetical, 6 to a row: a remote moves through a b c faster than through QWERTY (Netflix, YouTube).
    static final String EN = "abcdefghijklmnopqrstuvwxyz1234567890";
    static final String RU = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя";
    static final int MAX = 100; // the worker reads 100 characters
    // Search before typing, under the recent searches: OK opens the genre's page (the worker's /genre, GENRE_PAGES)
    static final List<String> GENRES = List.of("Action", "Adventure", "Animation", "Comedy", "Crime", "Documentary",
        "Drama", "Family", "Fantasy", "History", "Horror", "Kids", "Music", "Mystery", "Romance", "Science fiction",
        "Thriller", "War", "Western");

    static String url(String q) {
        try {
            // v: bumped when answers cached on the stick must go (2: the index's first day answered nothing)
            return Home.worker + "/search?v=2&q=" + URLEncoder.encode(q, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e); // UTF-8 is always there
        }
    }

    // A pause ends: ask unless those results are shown, or that query is still out ("dark" → "dark ").
    static boolean asks(String asked, String shownFor, String inFlight) {
        return !asked.equals(shownFor) && !asked.equals(inFlight);
    }

    // /search answers Home cards, the best first.
    static List<Home.Card> parse(String json) throws JSONException {
        JSONArray a = new org.json.JSONObject(json).getJSONArray("results");
        List<Home.Card> cards = new ArrayList<>(a.length());
        for (int i = 0; i < a.length(); i++) cards.add(Home.card(a.getJSONArray(i)));
        return cards;
    }

    // A key typed: a space never first or twice in a row.
    static String type(String q, String key) {
        if (q.length() >= MAX || key.equals(" ") && (q.isEmpty() || q.endsWith(" "))) return q;
        return q + key;
    }

    static String erase(String q) {
        return q.isEmpty() ? q : q.substring(0, q.length() - 1);
    }

    // The last searches as lines (newest first, each once, at most 8), with q added.
    static String remember(String saved, String q) {
        String query = q.trim();
        if (query.isEmpty()) return saved;
        List<String> all = new ArrayList<>(recent(saved));
        all.remove(query);
        all.add(0, query);
        return String.join("\n", all.subList(0, Math.min(8, all.size())));
    }

    static List<String> recent(String saved) {
        return saved.isEmpty() ? List.of() : Arrays.asList(saved.split("\n"));
    }
}
