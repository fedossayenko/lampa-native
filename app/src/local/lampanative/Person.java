package local.lampanative;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The worker's /person answer: an actor's page (OK on a cast photo); the name and photo come from the cast entry.
 * Tested on the Mac (PersonTest).
 */
record Person(String line, String about, List<Home.Row> rows) {

    // v: the answer's version, as Film.url's (the disk cache keeps it a day).
    static String url(int id) {
        return Home.worker + "/person/" + id + "?v=2";
    }

    /** A genre with filters: films or series, a decade ("older": before 1980), a country
     * (ISO code), a least score (60, 70, 80) and the order; "" = any. The worker's /discover, 20 a page. */
    record Filters(boolean series, String years, String country, String score, String sort) {
        static final Filters START = new Filters(false, "", "", "", "best");
        // the choices each filter's sheet offers: [value, label]
        static final String[][] YEARS = {{"", "Any"}, {"2020", "2020s"}, {"2010", "2010s"}, {"2000", "2000s"},
            {"1990", "1990s"}, {"1980", "1980s"}, {"older", "Older"}};
        static final String[] COUNTRIES = {"", "US", "GB", "FR", "DE", "IT", "ES", "SE", "DK", "KR", "JP", "IN", "RU"};
        static final String[][] SCORES = {{"", "Any"}, {"60", "60+"}, {"70", "70+"}, {"80", "80+"}};
        static final String[][] SORTS = {{"best", "Best rated"}, {"popular", "Popular"}, {"new", "Newest"}};

        String url(String genre, int page) {
            return Home.worker + "/discover/" + genre.toLowerCase(Locale.ROOT).replace(' ', '-') + "?type="
                + (series ? "tv" : "movie") + "&years=" + years + "&country=" + country + "&score=" + score + "&sort="
                + sort + "&page=" + page;
        }

        // What the page shows, in words: "Films from the 2010s scored 70 and up, best first, without what you watched".
        String line(boolean hideWatched) {
            String when = years.isEmpty() ? "" : years.equals("older") ? " before 1980" : " from the " + years + "s";
            String order = switch (sort) {
                case "popular" -> "most popular first";
                case "new" -> "newest first";
                default -> "best first";
            };
            return (series ? "Series" : "Films") + (country.isEmpty() ? "" : " made in " + country(country)) + when
                + (score.isEmpty() ? "" : " scored " + score + " and up") + ", " + order
                + (hideWatched ? ", without what you watched" : "");
        }

        static String label(String[][] choices, String value) {
            for (String[] c : choices) if (c[0].equals(value)) return c[1];
            return value;
        }

        // "GB" → "United Kingdom", "" → "Any"
        static String country(String code) {
            return code.isEmpty() ? "Any" : new Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.ENGLISH);
        }
    }

    /** One /discover page: its cards and how many pages there are. */
    record Found(List<Home.Card> cards, int pages) {
        static Found parse(String json) throws JSONException {
            JSONObject o = new JSONObject(json);
            JSONArray a = o.getJSONArray("cards");
            List<Home.Card> cards = new ArrayList<>(a.length());
            for (int i = 0; i < a.length(); i++) cards.add(Home.card(a.getJSONArray(i)));
            return new Found(cards, o.getInt("pages"));
        }
    }

    // Rows as Home's: Best rated, Films, Series (the worker leaves out an empty one).
    static Person parse(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        return new Person(o.getString("line"), o.getString("about"), Home.rows(o));
    }
}
