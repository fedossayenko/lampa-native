package local.lampanative;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The worker's /home response as rows of cards. No android.* here, so it is tested on the Mac (HomeTest). */
final class Home {
    // The worker's address, "https://name.account.workers.dev": files/server, set once from a computer (Main.server)
    static volatile String worker = "";
    static final int GREEN = 0xff2ecc71, ORANGE = 0xfff39c12, RED = 0xffe74c3c;

    record Card(int id, String type, String name, String year, String poster, int score, String backdrop) {
        // The open film kept across process death (onSaveInstanceState).
        String[] save() {
            return new String[] {String.valueOf(id), type, name, year, poster, String.valueOf(score), backdrop};
        }

        static Card of(String[] a) {
            return a == null ? null
                : new Card(Integer.parseInt(a[0]), a[1], a[2], a[3], a[4], Integer.parseInt(a[5]), a[6]);
        }
    }

    record Row(String title, List<Card> cards) {}

    private Home() {}

    // The first setup's values (Main.configure): the worker's https address with no path, and a play token.
    static boolean valid(String name, String value) {
        return name.equals("server") ? value.matches("https://[A-Za-z0-9.-]+(:[0-9]{1,5})?")
            : value.matches("[A-Za-z0-9_-]{16,128}");
    }

    // {"rows": [[title, [[id, type, name, year, poster_path, score, backdrop_path], ...]], ...]}; 0 and "" = none
    static List<Row> parse(String json) throws JSONException {
        return rows(new JSONObject(json));
    }

    // An answer's "rows": Home's, an actor's (Person).
    static List<Row> rows(JSONObject o) throws JSONException {
        JSONArray rows = o.getJSONArray("rows");
        List<Row> out = new ArrayList<>(rows.length());
        for (int r = 0; r < rows.length(); r++) {
            JSONArray row = rows.getJSONArray(r), items = row.getJSONArray(1);
            List<Card> cards = new ArrayList<>(items.length());
            for (int i = 0; i < items.length(); i++) cards.add(card(items.getJSONArray(i)));
            out.add(new Row(row.getString(0), cards));
        }
        return out;
    }

    // [id, type, name, year, poster_path, score, backdrop_path]: also a film page's collection (Film.parts).
    static Card card(JSONArray m) throws JSONException {
        return new Card(m.getInt(0), m.getString(1), m.getString(2), m.getString(3), m.getString(4), m.optInt(5),
            m.optString(6));
    }

    // Which of your rows' cards (the tags of the cards, null where one has none) Home focuses when /mine is drawn
    // again: the film that had focus (was: its card; it may have moved), else the first when nothing had focus, or
    // the card Home focused at start (atStart), or anything else of yours did (was: a tile's tag, History); -1 none.
    static int focusIndex(List<Card> cards, Object was, boolean atStart) {
        if (was instanceof Card w) {
            for (int i = 0; i < cards.size(); i++) {
                Card c = cards.get(i);
                if (c != null && c.id() == w.id() && c.type().equals(w.type())) return i;
            }
        }
        return !cards.isEmpty() && (was != null || atStart) ? 0 : -1;
    }

    // Lampa's score colors: green from 70, orange from 50, red below.
    static int scoreColor(int score) {
        return score >= 70 ? GREEN : score >= 50 ? ORANGE : RED;
    }
}
