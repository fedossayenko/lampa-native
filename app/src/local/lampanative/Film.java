package local.lampanative;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The worker's /card answer: the film page, every text ready to draw. Tested on the Mac (FilmTest). */
record Film(String name, String original, String line, String overview, List<Score> scores,
            List<Score> seasons, String imdb, List<Season> episodes, String people, List<Actor> cast,
            String collection, List<Home.Card> parts, String trailer, int runtime, List<Home.Card> similar) {

    // trailer: its YouTube video ("" none); runtime: a film's minutes (0 unknown, a series); similar: More like this
    // (TMDB's recommendations); without them: a page drawn before /card answers, the tests' films.
    Film(String name, String original, String line, String overview, List<Score> scores, List<Score> seasons,
         String imdb, List<Season> episodes, String people, List<Actor> cast, String collection, List<Home.Card> parts) {
        this(name, original, line, overview, scores, seasons, imdb, episodes, people, cast, collection, parts, "");
    }

    Film(String name, String original, String line, String overview, List<Score> scores, List<Score> seasons,
         String imdb, List<Season> episodes, String people, List<Actor> cast, String collection, List<Home.Card> parts,
         String trailer) {
        this(name, original, line, overview, scores, seasons, imdb, episodes, people, cast, collection, parts, trailer, 0);
    }

    Film(String name, String original, String line, String overview, List<Score> scores, List<Score> seasons,
         String imdb, List<Season> episodes, String people, List<Actor> cast, String collection, List<Home.Card> parts,
         String trailer, int runtime) {
        this(name, original, line, overview, scores, seasons, imdb, episodes, people, cast, collection, parts, trailer,
            runtime, List.of());
    }

    // A runtime as the page's line says it: "2h 46m", "58m".
    static String length(int minutes) {
        return (minutes >= 60 ? minutes / 60 + "h " : "") + minutes % 60 + "m";
    }

    // When a film started now ends ("ends at 23:41"); "" without a runtime.
    static String endsAt(int runtime, LocalTime now) {
        return runtime > 0 ? "ends at " + now.plusMinutes(runtime).format(DateTimeFormatter.ofPattern("HH:mm")) : "";
    }

    /** A score as drawn: "RT" / "99%", colored by score (0-100). The verdict comes first. */
    record Score(String label, String text, int score) {}

    /** A season and how many episodes it has (series only, no specials). */
    record Season(int number, int episodes) {}

    /**
     * An episode's row (the worker's /season): its name, runtime in minutes (0: unknown), overview, still path, and
     * the day it airs ("2026-11-10", "" when TMDB has none).
     */
    record Episode(int number, String name, int runtime, String overview, String still, String airDate) {}

    // v: the answer's version (2: air dates), so a day-old cached answer of an older one is never read.
    static String seasonUrl(Home.Card c, int season) {
        return Home.worker + "/season/" + c.id() + "/" + season + "?v=2";
    }

    // lang: an audio language ("RU"), its names and overviews where TMDB has them (Story so far); null: English
    static String seasonUrl(Home.Card c, int season, String lang) {
        return seasonUrl(c, season) + (lang == null ? "" : "&lang=" + lang);
    }

    // What a source list or DDD calls what is played: "Foundation · S3 E5", a film's name alone (season 0).
    String heading(int season, int episode) {
        return season > 0 ? name + " · S" + season + " E" + episode : name;
    }

    // Whether there is something to watch: an IMDb id for the sources, and for a series an episode out (a series with
    // none yet has no seasons to open: Episodes needs the first).
    boolean watchable(boolean series) {
        return !imdb.isEmpty() && (!series || !episodes.isEmpty());
    }

    // A trailer's address; null unless the worker's video is a YouTube id (letters, digits, - and _ only: it is
    // appended to a URL that any app may open).
    static String trailerUrl(String video) {
        return video != null && video.matches("[A-Za-z0-9_-]{6,20}") ? "https://www.youtube.com/watch?v=" + video : null;
    }

    // An episode row's title: "8 · Better Call Saul"; TMDB's stand-in name till it has one ("Episode 9") just once.
    static String title(int number, Episode e) {
        return e == null || e.name().isEmpty() || e.name().equals("Episode " + number) ? "Episode " + number
            : number + " · " + e.name();
    }

    // An episode not out yet: "Airs tomorrow", "Airs Tuesday, 10 Nov"; "" once out or undated.
    static String airs(String date, LocalDate today) {
        LocalDate at = Cub.date(date);
        if (at == null || !at.isAfter(today)) return ""; // none, out already, or a date in a shape we can't read
        String[] d = Cub.day(at, today);
        return d[0].equals("Tomorrow") ? "Airs tomorrow" : "Airs " + d[0] + ", " + d[1];
    }

    // Story so far: up to n episodes before at (Timeline.resume's [season, episode], where Continue goes on),
    // oldest first, back into the seasons before. Empty: nothing watched before it (or at null: watched to the end).
    static List<int[]> before(List<Season> seasons, int[] at, int n) {
        List<int[]> all = new ArrayList<>();
        for (Season s : seasons) for (int e = 1; e <= s.episodes(); e++) all.add(new int[] {s.number(), e});
        for (int i = 0; at != null && i < all.size(); i++) {
            if (all.get(i)[0] == at[0] && all.get(i)[1] == at[1]) return all.subList(Math.max(0, i - n), i);
        }
        return List.of();
    }

    // POST /recap to the home server (voice-claude.py): the series, the last episode watched and the overviews up to
    // it, [label, name, overview] oldest first. key: what it keeps the summary under, written once per episode.
    static String recapBody(Home.Card c, int season, int episode, List<String[]> episodes, String language)
        throws JSONException {
        JSONArray a = new JSONArray();
        for (String[] e : episodes) a.put(new JSONArray().put(e[0]).put(e[1]).put(e[2]));
        return new JSONObject().put("key", c.type() + "_" + c.id() + " S" + season + "E" + episode)
            .put("series", c.name()).put("year", c.year()).put("upto", "season " + season + ", episode " + episode)
            .put("episodes", a).put("language", language).toString();
    }

    static List<Episode> season(String json) throws JSONException {
        JSONArray a = new JSONObject(json).getJSONArray("episodes");
        List<Episode> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONArray e = a.getJSONArray(i);
            out.add(new Episode(e.getInt(0), e.getString(1), e.getInt(2), e.getString(3), e.getString(4),
                e.optString(5, "")));
        }
        return out;
    }

    /** Someone in the cast, with a photo (TMDB profile path), and TMDB's person id for their page (0: none). */
    record Actor(String name, String character, String photo, int id) {}

    // The page from the card alone (name and year), shown until /card answers.
    static Film of(Home.Card c) {
        return new Film(c.name(), "", c.year(), "", List.of(), List.of(), "", List.of(), "", List.of(), "", List.of());
    }

    // v: the answer's version. The disk cache keeps an answer a day (the worker's max-age): bump it when the answer
    // changes, or films opened in the last day show the old one (2: cast and collection; 3: no cast names over
    // the cast's photos; 4: the cast's person ids; 5: the trailer; 6: the runtime; 7: More like this).
    static String url(Home.Card c) {
        return Home.worker + "/card/" + c.type() + "/" + c.id() + "?v=8";
    }

    // imdb, episodes, cast, the collection and the trailer are opt*: a /card cached on the stick from before they existed still
    // opens.
    static Film parse(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        List<Season> episodes = new ArrayList<>();
        JSONArray e = o.optJSONArray("episodes");
        for (int i = 0; e != null && i < e.length(); i++) {
            JSONArray s = e.getJSONArray(i);
            episodes.add(new Season(s.getInt(0), s.getInt(1)));
        }
        List<Actor> cast = new ArrayList<>();
        JSONArray c = o.optJSONArray("cast");
        for (int i = 0; c != null && i < c.length(); i++) {
            JSONArray p = c.getJSONArray(i);
            cast.add(new Actor(p.getString(0), p.getString(1), p.getString(2), p.optInt(3))); // 0: a /card from before ids
        }
        List<Home.Card> parts = new ArrayList<>();
        JSONArray a = o.optJSONArray("parts");
        for (int i = 0; a != null && i < a.length(); i++) parts.add(Home.card(a.getJSONArray(i)));
        List<Home.Card> similar = new ArrayList<>();
        JSONArray s = o.optJSONArray("similar");
        for (int i = 0; s != null && i < s.length(); i++) similar.add(Home.card(s.getJSONArray(i)));
        return new Film(o.getString("name"), o.getString("original"), o.getString("line"), o.getString("overview"),
            scores(o.getJSONArray("scores")), scores(o.getJSONArray("seasons")), o.optString("imdb"), episodes,
            o.getString("people"), cast, o.optString("collection"), parts, o.optString("trailer"), o.optInt("runtime"),
            similar);
    }

    private static List<Score> scores(JSONArray a) throws JSONException {
        List<Score> out = new ArrayList<>(a.length());
        for (int i = 0; i < a.length(); i++) {
            JSONArray s = a.getJSONArray(i);
            out.add(new Score(s.getString(0), s.getString(1), s.getInt(2)));
        }
        return out;
    }
}
