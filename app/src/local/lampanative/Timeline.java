package local.lampanative;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Resume points, keyed and counted as Lampa does: the worker's /timeline shares them with Lampa (the torbox-en
 * plugin syncs Lampa's timeline with it). No android.* here: tested on the Mac (TimelineTest).
 */
final class Timeline {
    private Timeline() {}

    // Lampa's key: Utils.hash of the original title for a film; of season, ':' after season 10, episode and the
    // original name for an episode. Utils.hash is Java's String.hashCode as a positive decimal.
    static String key(String original, int season, int episode) {
        String s = season == 0 ? original : season + (season > 10 ? ":" : "") + episode + original;
        return Long.toString(Math.abs((long) s.hashCode()));
    }

    // A film's or an episode's key: Lampa takes the original title (TMDB's original_title / original_name); the name
    // when there is none.
    static String key(Film f, int season, int episode) {
        return key(original(f), season, episode);
    }

    static String original(Film f) {
        return f.original().isEmpty() ? f.name() : f.original();
    }

    // A series' episode keys, comma-separated: the worker's /timeline?hash= for its marks. The first 1000 (~11 KB:
    // Cloudflare takes 16 KB of URL). A film: its own key.
    // ponytail: past 1000 episodes (One Piece) the later ones get no marks; POST the keys if one is watched here
    static String keys(Film f) {
        if (f.episodes().isEmpty()) return key(f, 0, 0);
        StringJoiner all = new StringJoiner(",");
        int n = 0;
        for (Film.Season s : f.episodes()) {
            for (int e = 1; e <= s.episodes() && n++ < 1000; e++) all.add(key(f, s.number(), e));
        }
        return all.toString();
    }

    /** A resume point as Lampa keeps it: seconds, seconds (0: Lampa didn't say), percent. */
    record Point(int time, int duration, int percent) {}

    enum State { NONE, WATCHING, WATCHED }

    // Watched from 90 % (Lampa stops resuming there); being watched past the first 10 s (Lampa starts those over).
    static State state(Point p) {
        if (p == null) return State.NONE;
        if (p.percent() >= 90) return State.WATCHED;
        return p.time() > 10 ? State.WATCHING : State.NONE;
    }

    // The worker's /timeline answer: hash → its point.
    static Map<String, Point> marks(String json) throws JSONException {
        JSONObject all = new JSONObject(json).getJSONObject("timelines");
        Map<String, Point> m = new HashMap<>();
        for (Iterator<String> i = all.keys(); i.hasNext(); ) {
            String hash = i.next();
            JSONObject t = all.getJSONObject(hash);
            m.put(hash, new Point(t.optInt("time"), t.optInt("duration"), t.optInt("percent"))); // Lampa's may lack one
        }
        return m;
    }

    // A started film's poster: "1h 7m left", "25m left" (the worker's "left", seconds; rounded, never 0); a series':
    // "26 ep left" (the worker's "26 ep": aired after your furthest watched episode); null: none.
    static String posterLeft(String seconds) {
        if (seconds != null && seconds.matches("\\d+ ep")) return seconds + " left";
        int s;
        try {
            s = Integer.parseInt(seconds);
        } catch (NumberFormatException e) {
            return null;
        }
        if (s <= 0) return null;
        int m = Math.max(1, (s + 30) / 60);
        return (m < 60 ? m + "m" : m / 60 + "h" + (m % 60 > 0 ? " " + m % 60 + "m" : "")) + " left";
    }

    // An episode's mark: "Watched", or the minutes left while being watched; null point: never played.
    static String episodeMark(Point p) {
        return switch (state(p)) {
            case NONE -> "";
            case WATCHED -> "Watched";
            case WATCHING -> p.duration() > p.time()
                ? "Watching · " + Math.max(1, (p.duration() - p.time() + 30) / 60) + " min left" : "Watching";
        };
    }

    // A season's line: its episodes, how many watched, the last one being watched. mark: an episode's point.
    static String seasonLine(int episodes, IntFunction<Point> mark) {
        int watched = 0, watching = 0;
        for (int e = 1; e <= episodes; e++) {
            State st = state(mark.apply(e));
            if (st == State.WATCHED) watched++;
            else if (st == State.WATCHING) watching = e;
        }
        String done = watched == 0 ? "" : watched == episodes ? " · all watched" : " · " + watched + " watched";
        return episodes + " episodes" + done + (watching > 0 ? " · watching E" + watching : "");
    }

    // Where a series goes on, {season, episode}: after the furthest episode with a point, that one while it's being
    // watched, else the next (into the next season); null: nothing played, or the last one watched.
    // ponytail: the furthest, not the latest played: a series watched again from the start resumes at its end
    static int[] resume(List<Film.Season> seasons, BiFunction<Integer, Integer, Point> mark) {
        int[] at = null;
        State last = State.NONE;
        for (Film.Season s : seasons) {
            for (int e = 1; e <= s.episodes(); e++) {
                State st = state(mark.apply(s.number(), e));
                if (st == State.NONE) continue;
                at = new int[] {s.number(), e};
                last = st;
            }
        }
        if (at == null || last == State.WATCHING) return at;
        for (Film.Season s : seasons) { // the next episode
            if (s.number() == at[0] && at[1] < s.episodes()) return new int[] {at[0], at[1] + 1};
            if (s.number() > at[0] && s.episodes() > 0) return new int[] {s.number(), 1};
        }
        return null;
    }

    // The worker's answer into the marks, never over a key DDD answered about this session (played): DDD's POST may
    // land after the answer left (always so after a restore).
    static void merge(Map<String, Point> marks, Map<String, Point> answer, java.util.Set<String> played) {
        answer.forEach((k, v) -> {
            if (!played.contains(k)) marks.put(k, v);
        });
    }

    // Points taken back to the start (History > Mark unwatched): the marks kept here, and the keys DDD answered about
    // this session (which merge() never lets the worker overwrite), forget them: else the episodes would show Watched
    // until the app is closed.
    static void forget(Map<String, Point> marks, java.util.Set<String> played, List<String> keys) {
        marks.keySet().removeAll(keys);
        played.removeAll(keys);
    }

    // Continue's {season, episode}: a series' resume(); a film {0, 0} while it's being watched and its source is
    // kept here (else Watch does the same); null: no Continue. mark: a key's point.
    static int[] continueTo(boolean series, Film f, Function<String, Point> mark, Last last) {
        if (series) return resume(f.episodes(), (s, e) -> mark.apply(key(f, s, e)));
        return last != null && state(mark.apply(key(f, 0, 0))) == State.WATCHING ? new int[] {0, 0} : null;
    }

    // Continue plays the kept source again when it is that episode (or the film); else it opens that episode's list.
    static boolean replays(Last last, int[] to) {
        return last != null && last.season() == to[0] && last.episode() == to[1];
    }

    /** The source last played of a film or series (Continue plays it again), kept on the stick. */
    record Last(int season, int episode, String hash, int fileIdx, String name) {
        String save() {
            return season + "\n" + episode + "\n" + hash + "\n" + fileIdx + "\n" + name;
        }

        static Last of(String saved) {
            String[] a = saved == null ? new String[0] : saved.split("\n", 5);
            if (a.length < 5) return null;
            try {
                return new Last(Integer.parseInt(a[0]), Integer.parseInt(a[1]), a[2], Integer.parseInt(a[3]), a[4]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    // Where DDD starts (ms) from a saved point (seconds, percent): Lampa resumes after 10 s and before 90 %.
    static long startMs(long time, int percent) {
        return time > 10 && percent < 90 ? time * 1000 : 0;
    }

    // Lampa's card id in a timeline record (Timeline.link): tv_1399, movie_155.
    static String card(Home.Card c) {
        return c.type() + "_" + c.id();
    }

    // The worker's /play path: one file of a torrent (Torrentio's index and name) and this point's key. series: an
    // episode's original series name (Lampa's keys of the ones after it), the playlist then comes too; null: a film.
    static String playPath(String hash, int fileIdx, String name, String key, String series) {
        try {
            return "/play/" + hash + "?file=" + fileIdx + "&name=" + URLEncoder.encode(name, "UTF-8") + "&tl=" + key
                + (series == null ? "" : "&series=" + URLEncoder.encode(series, "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e); // UTF-8 is always there
        }
    }

    // The worker's /segments path (our DDD fork's skip times): a film's, or an episode's and the 5 after it (DDD's
    // playlist rarely gets further in one sitting; Continue asks again), with the release played (torrent, file, name,
    // the series' original name): the home server finds that release's intros by sound. null without an IMDb id.
    // ponytail: the 5 after it count on in the same season; a torrent going on into the next gets none there
    static String segmentsPath(Home.Card c, String imdb, int season, int episode, String hash, int fileIdx, String name,
                               String original) {
        if (imdb.isEmpty()) return null;
        StringBuilder p = new StringBuilder("/segments/").append(c.type()).append('/').append(c.id()).append('/').append(imdb);
        if (season == 0) return p.toString();
        for (int i = 0; i < 6; i++) p.append(i == 0 ? "?e=" : ",").append(season).append(':').append(episode + i);
        try {
            return p.append("&h=").append(hash).append("&f=").append(fileIdx).append("&n=")
                .append(URLEncoder.encode(name, "UTF-8")).append("&o=").append(URLEncoder.encode(original, "UTF-8")).toString();
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e); // UTF-8 is always there
        }
    }

    // Its answer {"1:9": "intro:0-58867;credits:3395489-"} ("0:0": a film's): the same map, empty on anything else.
    static Map<String, String> segments(int code, String body) {
        Map<String, String> m = new HashMap<>();
        try {
            JSONObject o = new JSONObject(body);
            for (Iterator<String> k = o.keys(); code == 200 && k.hasNext(); ) {
                String key = k.next();
                m.put(key, o.getString(key));
            }
        } catch (JSONException e) { // Cloudflare's HTML page: no skip button, the film plays all the same
            m.clear();
        }
        return m;
    }

    /** The worker's /play answer: TorBox's link and where to start (ms), or what to tell the user. */
    record Link(String url, long startMs, String message, List<Episode> playlist) {
        Link(String url, long startMs, String message) {
            this(url, startMs, message, List.of());
        }
    }

    /** An episode of DDD's playlist: the worker's /stream address of its file in the torrent played. */
    record Episode(int season, int episode, String key, String name, String url) {}

    static Link link(int code, String body) {
        try {
            JSONObject o = new JSONObject(body);
            if (code == 200) {
                JSONArray a = o.optJSONArray("playlist");
                List<Episode> list = new ArrayList<>();
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONArray e = a.getJSONArray(i);
                    list.add(new Episode(e.getInt(0), e.getInt(1), e.getString(2), e.getString(3), e.getString(4)));
                }
                return new Link(o.getString("url"), startMs(o.getLong("time"), o.getInt("percent")), null, list);
            }
            if (code == 202) {
                return new Link(null, 0, "TorBox is downloading it: " + o.getInt("progress") + " %. Try again later.");
            }
            return new Link(null, 0, "Can't get the link: " + o.optString("error", "HTTP " + code));
        } catch (JSONException e) { // Cloudflare's HTML page, or the worker changed its answer
            return new Link(null, 0, "Can't get the link: HTTP " + code);
        }
    }

    /** What DDD is playing and where it started (ms): kept across process death (DDD playing 4K may get us killed). */
    // playlist: DDD's episodes, this one first (empty: one file). DDD answers with the address it stopped on.
    record Playing(String key, String card, int season, int episode, long startMs, List<Episode> playlist) {
        Playing(String key, String card, int season, int episode, long startMs) {
            this(key, card, season, episode, startMs, List.of());
        }

        String[] save() {
            List<String> a = new ArrayList<>(List.of(key, card, String.valueOf(season), String.valueOf(episode),
                String.valueOf(startMs)));
            for (Episode e : playlist) a.addAll(List.of(String.valueOf(e.season()), String.valueOf(e.episode()), e.key(),
                e.name(), e.url()));
            return a.toArray(new String[0]);
        }

        // null: none, or what isn't one (too short, a number that isn't): the receiver runs with the app killed, where
        // an exception is a crash report for a broadcast that only asked to be ignored.
        static Playing of(String[] a) {
            if (a == null || a.length < 5) return null;
            try {
                List<Episode> list = new ArrayList<>();
                for (int i = 5; i + 4 < a.length; i += 5) {
                    list.add(new Episode(Integer.parseInt(a[i]), Integer.parseInt(a[i + 1]), a[i + 2], a[i + 3], a[i + 4]));
                }
                return new Playing(a[0], a[1], Integer.parseInt(a[2]), Integer.parseInt(a[3]), Long.parseLong(a[4]), list);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        // Whether a report of DDD's (the address it plays) is about this playback: the address started, or one of the
        // playlist's. Only the worker's signature makes one, so another app can't send a report that passes.
        boolean reports(String address, String started) {
            return address != null && (address.equals(started) || playlist.stream().anyMatch(e -> e.url().equals(address)));
        }

        // The episode DDD stopped on (its address; null or not in the list: this one), started from its beginning
        // when it is a later one.
        Playing at(String url) {
            for (int i = 1; i < playlist.size(); i++) {
                Episode e = playlist.get(i);
                if (e.url().equals(url)) return new Playing(e.key(), card, e.season(), e.episode(), 0, List.of());
            }
            return this;
        }

        // The episodes DDD went past before the one it stopped on: watched to the end.
        List<Episode> before(String url) {
            for (int i = 1; i < playlist.size(); i++) if (playlist.get(i).url().equals(url)) return playlist.subList(0, i);
            return List.of();
        }

        // DDD's position and duration (ms) as Lampa saves them: {time, duration (s), percent}. null: nothing to save
        // (no duration; or back within Lampa's first 10 s of a resumed film, a dead link too: 0 would win as newest).
        long[] saved(long positionMs, long durationMs, boolean ended) {
            if (durationMs <= 0 || !ended && startMs > 0 && positionMs <= 10_000) return null;
            long position = ended ? durationMs : Math.min(Math.max(positionMs, 0), durationMs);
            return new long[] {Math.round(position / 1000.0), Math.round(durationMs / 1000.0),
                Math.round(position * 100.0 / durationMs)};
        }

        // POST /timeline: where DDD stopped ({time, duration, percent} from saved()); the worker stamps the time.
        String body(long[] t) {
            try {
                JSONObject p = new JSONObject().put("hash", key).put("time", t[0]).put("duration", t[1])
                    .put("percent", t[2]).put("card", card).put("season", season).put("episode", episode);
                return new JSONObject().put("timelines", new JSONArray().put(p)).toString();
            } catch (JSONException e) {
                throw new AssertionError(e); // numbers and strings only
            }
        }
    }
}
