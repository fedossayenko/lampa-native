package local.lampanative;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The CUB account Lampa uses (its bookmarks and history), signed in the way Lampa signs in a device
 * (src/core/account/device.js): device/code gives a code, the user confirms it at cub.red/activate on a phone, and
 * device/token then answers the account's token. The app keeps the token and the profile in its private files
 * (files/cub) and sends them to the worker, which reads CUB with them. No android.* here: tested on the Mac (CubTest).
 */
final class Cub {
    private Cub() {}

    static final String API = "https://cub.best/api/"; // Lampa's first mirror (its Manifest.cub_mirrors)

    /** A sign-in code: what the user types, what the app asks with, where to type it, seconds it lives and between asks. */
    record Code(String user, String device, String site, int expires, int interval) {}

    // device/code's answer
    static Code code(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        return new Code(o.getString("user_code"), o.getString("device_code"),
            o.optString("verification_uri", "https://cub.red/activate").replaceFirst("^https?://", ""),
            o.optInt("expires_in", 600), Math.max(2, o.optInt("interval", 5)));
    }

    // device/token's answer (HTTP 200): the token once the code is confirmed, null while it waits. Its refusals come
    // as HTTP 500 with a code (failed()).
    static String token(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        return "approved".equals(o.optString("status")) ? o.getString("token") : null;
    }

    // A refusal of device/token: 803 the user refused it on the site, 804 the code expired. Either way Lampa shows a
    // new code; anything else (the network) is asked again.
    static boolean newCode(String json) {
        try {
            int code = new JSONObject(json).optInt("code");
            return code == 803 || code == 804;
        } catch (JSONException e) {
            return false;
        }
    }

    record Profile(int id, String name, boolean main) {}

    // profiles/all: Lampa signs in to the main one (profile.js) and the user may switch; grades are kept per profile.
    static List<Profile> profiles(String json) throws JSONException {
        JSONArray a = new JSONObject(json).getJSONArray("profiles");
        List<Profile> out = new ArrayList<>(a.length());
        for (int i = 0; i < a.length(); i++) {
            JSONObject p = a.getJSONObject(i);
            out.add(new Profile(p.getInt("id"), p.optString("name"), p.optInt("main") == 1 || p.optBoolean("main")));
        }
        return out;
    }

    // The marks the film page offers, as Lampa's (the worker's /mine/<mark>): [mark, label, what it does].
    static final String[][] MARKS = {
        {"scheduled", "Finish later", "Out of Continue browsing, into its own row"},
        {"thrown", "Not interested", "Out of Continue browsing"},
        {"viewed", "Watched it", "Out of Continue browsing"},
    };

    // /mine's marks: "tv_93740" → "scheduled", one a film (Lampa's).
    static Map<String, String> marks(String json) {
        return strings(json, "marks");
    }

    // /mine's grades: "movie_603" → "2" (a GRADES' first item).
    static Map<String, String> grades(String json) {
        return strings(json, "grades");
    }

    // /mine's new episodes: "tv_93740" → "S3 E5", out with the app's audio languages since you watched the one before.
    static Map<String, String> fresh(String json) {
        return strings(json, "new");
    }

    // /mine's "left": a started film's seconds left, "movie_603" → "4000" (its poster says it).
    static Map<String, String> left(String json) {
        return strings(json, "left");
    }

    // /mine's "percent": how far in a started film is, "movie_603" → "43" (its Continue tile's bar).
    static Map<String, String> percent(String json) {
        return strings(json, "percent");
    }

    // /mine's "learned": the percent of a film understood, "movie_603" → "93" (its page's Learn button says it).
    static Map<String, String> learned(String json) {
        return strings(json, "learned");
    }

    // Whether /mine says the learn extension is there (it sends "learned", even empty): only then the film page asks it.
    static boolean learnBound(String json) {
        try {
            return new JSONObject(json).optJSONObject("learned") != null;
        } catch (JSONException e) {
            return false;
        }
    }

    // /mine's "next": a series' episode to go on with, "tv_1399" → "S3 E5 · up next" (its Continue tile's line).
    static Map<String, String> next(String json) {
        return strings(json, "next");
    }

    // "S3 E5 · up next" → {3, 5} (≡ on a series opens its episodes there); null: none.
    static int[] episode(String next) {
        java.util.regex.Matcher m = next == null ? null : java.util.regex.Pattern.compile("^S(\\d+) E(\\d+)").matcher(next);
        return m != null && m.find() ? new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))} : null;
    }

    /** Coming up's line: a series' next episode or a film from My list; date "" = later or no date yet; state: out,
     * waiting (aired, not in RU/UA yet), airs, film; a series' episode (0, 0: a film, or a /mine from before it). */
    record Coming(String date, Home.Card card, String what, String state, int season, int episode) {}

    // /mine's "coming", by date.
    static List<Coming> coming(String json) {
        List<Coming> out = new ArrayList<>();
        try {
            JSONArray a = new JSONObject(json).optJSONArray("coming");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONArray c = a.getJSONArray(i);
                JSONArray e = c.optJSONArray(4);
                out.add(new Coming(c.getString(0), Home.card(c.getJSONArray(1)), c.getString(2), c.getString(3),
                    e == null ? 0 : e.getInt(0), e == null ? 0 : e.getInt(1)));
            }
        } catch (JSONException e) {
            // none
        }
        return out;
    }

    // A Coming up date as its group's two lines: ["Today", "Mon 2 Nov"], ["Tomorrow", "Tue 3 Nov"], ["Thursday",
    // "5 Nov"], ["Later", "no date yet"] ("": no date, or past four weeks).
    static String[] day(String date, LocalDate today) {
        if (date.isEmpty()) return new String[] {"Later", "no date yet"};
        LocalDate d = LocalDate.parse(date);
        String named = d.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH));
        String weekday = d.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        if (!d.isAfter(today)) return new String[] {"Today", weekday.substring(0, 3) + " " + named};
        if (d.equals(today.plusDays(1))) return new String[] {"Tomorrow", weekday.substring(0, 3) + " " + named};
        return new String[] {weekday, named};
    }

    /** A History row (/mine/history): when (ms), the title, a series' episodes ("S1 E6 and E7"; "" a film, or none
     * known), seconds left (0 watched, -1 not known), whether Lampa played it, Lampa's keys of its points. */
    record Played(long t, Home.Card card, String what, int left, boolean lampa, List<String> hashes) {}

    static List<Played> history(String json) throws JSONException {
        JSONArray a = new JSONObject(json).getJSONArray("items");
        List<Played> out = new ArrayList<>(a.length());
        for (int i = 0; i < a.length(); i++) {
            JSONArray p = a.getJSONArray(i);
            JSONArray h = p.getJSONArray(5);
            List<String> hashes = new ArrayList<>(h.length());
            for (int j = 0; j < h.length(); j++) hashes.add(h.getString(j));
            out.add(new Played(p.getLong(0), Home.card(p.getJSONArray(1)), p.getString(2), p.getInt(3), p.getInt(4) == 1,
                hashes));
        }
        return out;
    }

    // A History row's group and its label at the row's end: ["Today", "21:40"], ["Yesterday", "23:10"], ["This week",
    // "Saturday"] (the five days before), then the month, ["October", "12 Oct"] ("May 2025" in another year).
    static String[] played(LocalDateTime at, LocalDate today) {
        LocalDate d = at.toLocalDate();
        String time = at.format(DateTimeFormatter.ofPattern("HH:mm"));
        if (!d.isBefore(today)) return new String[] {"Today", time};
        if (d.equals(today.minusDays(1))) return new String[] {"Yesterday", time};
        if (d.isAfter(today.minusDays(7))) return new String[] {"This week", d.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH)};
        String month = d.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        return new String[] {d.getYear() == today.getYear() ? month : month + " " + d.getYear(),
            d.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))};
    }

    /** What's new (/mine's "news", newest first): when (ms), the film or series it opens, its head ("Foundation · S3 E6",
     * a person's name) and line ("Out in Russian", "New film: Dune: Part Three · 2026"). */
    record News(long t, Home.Card card, String head, String line) {}

    static List<News> news(String json) {
        List<News> out = new ArrayList<>();
        try {
            JSONArray a = new JSONObject(json).optJSONArray("news");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONArray n = a.getJSONArray(i);
                out.add(new News(n.getLong(0), Home.card(n.getJSONArray(1)), n.getString(2), n.getString(3)));
            }
        } catch (JSONException e) {
            // none
        }
        return out;
    }

    // When a What's new item came: "18:02" today, "Yesterday", "Friday" (the five days before), else "12 Oct".
    static String ago(LocalDateTime at, LocalDate today) {
        LocalDate d = at.toLocalDate();
        if (!d.isBefore(today)) return at.format(DateTimeFormatter.ofPattern("HH:mm"));
        if (d.equals(today.minusDays(1))) return "Yesterday";
        if (d.isAfter(today.minusDays(7))) return d.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        return d.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH));
    }

    // The grade a poster shows, as Lampa tags its cards: GRADES' item; none for "not mine" or no grade.
    static String[] posterGrade(String g) {
        return "skip".equals(g) ? null : grade(g);
    }

    // /mine's "follows": the people you follow, TMDB's person ids (their pages say Following).
    static Set<Integer> follows(String json) {
        Set<Integer> out = new HashSet<>();
        try {
            JSONArray a = new JSONObject(json).optJSONArray("follows");
            for (int i = 0; a != null && i < a.length(); i++) out.add(a.getInt(i));
        } catch (JSONException e) {
            // none
        }
        return out;
    }

    // /mine's "mylist": what My list holds (Lampa's Bookmarks and Later), "movie_603" each.
    static Set<String> listed(String json) {
        return keys(json, "mylist");
    }

    // /mine's "seen": the films in the history played to 90 %, graded or not.
    static Set<String> seen(String json) {
        return keys(json, "seen");
    }

    // A genre page hides what you watched: graded (any grade, "not mine" too), marked Watched it or Not interested,
    // or a film played to 90 %.
    static boolean watched(String card, Map<String, String> grades, Map<String, String> marks, Set<String> seen) {
        String m = marks.get(card);
        return grades.containsKey(card) || "viewed".equals(m) || "thrown".equals(m) || seen.contains(card);
    }

    private static Set<String> keys(String json, String name) {
        Set<String> out = new HashSet<>();
        try {
            JSONArray a = new JSONObject(json).optJSONArray(name);
            for (int i = 0; a != null && i < a.length(); i++) out.add(a.getString(i));
        } catch (JSONException e) {
            // none
        }
        return out;
    }

    private static Map<String, String> strings(String json, String key) {
        Map<String, String> m = new HashMap<>();
        try {
            JSONObject o = new JSONObject(json).optJSONObject(key);
            if (o != null) for (Iterator<String> i = o.keys(); i.hasNext(); ) {
                String k = i.next();
                m.put(k, String.valueOf(o.get(k)));
            }
        } catch (JSONException e) {
            // none
        }
        return m;
    }

    // Lampa's grades (the torbox-en plugin's grades-patch.js; the worker's /grades shares them per account and
    // profile): [g, label, color, what it does].
    static final String[][] GRADES = {
        {"2", "Loved", "#21d07a", "Films like it first in For you"},
        {"1", "Good", "#c8d531", "Films like it in For you"},
        {"0", "OK", "#f0a030", "Watched, no effect on suggestions"},
        {"-1", "Bad", "#e8455a", "Fewer films like it"},
        {"skip", "Didn't watch / not mine", "#9a9a9a", "Off Rate what you watched, no effect on suggestions"},
    };

    // A grade's item in GRADES; null: none.
    static String[] grade(String g) {
        for (String[] x : GRADES) if (x[0].equals(g)) return x;
        return null;
    }

    // POST /grades with one film's grade at time t, as Lampa's plugin keeps it: {g, t, card} with a card of Lampa's
    // fields (its pages draw a graded film that isn't in the history from it); g null: removed (no card), so the
    // removal reaches Lampa too. original: the film page's original name ("" when it is the name).
    static String gradeBody(Home.Card c, String original, String g, long t) {
        try {
            JSONObject v = new JSONObject().put("t", t);
            if (g == null) v.put("g", JSONObject.NULL);
            else v.put("g", g.equals("skip") ? g : Integer.valueOf(g)).put("card", lampaCard(c, original));
            return new JSONObject().put("grades", new JSONObject().put(c.type() + "_" + c.id(), v)).toString();
        } catch (JSONException e) {
            throw new AssertionError(e); // only strings and numbers
        }
    }

    private static JSONObject lampaCard(Home.Card c, String original) throws JSONException {
        boolean tv = c.type().equals("tv");
        JSONObject o = new JSONObject().put("id", c.id()).put(tv ? "name" : "title", c.name())
            .put(tv ? "original_name" : "original_title", original.isEmpty() ? c.name() : original).put("source", "tmdb");
        if (!c.year().isEmpty()) o.put(tv ? "first_air_date" : "release_date", c.year());
        if (!c.poster().isEmpty()) o.put("poster_path", c.poster());
        if (!c.backdrop().isEmpty()) o.put("backdrop_path", c.backdrop());
        if (c.score() > 0) o.put("vote_average", c.score() / 10.0);
        return o;
    }

    // A mark's name in the list the film page's ⋯ opens; null: none.
    static String markName(String mark) {
        for (String[] m : MARKS) if (m[0].equals(mark)) return m[1];
        return null;
    }

    /** The account signed in: its token, the profile, the profile's name (Settings shows it). */
    record Account(String token, int profile, String name) {
        String save() {
            try {
                return new JSONObject().put("token", token).put("profile", profile).put("name", name).toString();
            } catch (JSONException e) {
                throw new AssertionError(e); // only strings and an int
            }
        }

        // The file's contents; null when there is none or it can't be read (signed out).
        static Account of(String json) {
            try {
                JSONObject o = new JSONObject(json);
                String token = o.getString("token");
                return token.isEmpty() ? null : new Account(token, o.getInt("profile"), o.optString("name"));
            } catch (JSONException | NullPointerException e) {
                return null;
            }
        }
    }
}
