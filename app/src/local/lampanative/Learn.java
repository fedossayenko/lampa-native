package local.lampanative;

import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The film page's Learn button: a film's status from the worker's /ext/learn extension (queued, building, ready) and
 * the extension's estimate of how comfortably you would watch the film: a band (Hard, Weak, Good, Comfortable, by the
 * share of its words you know) and how many more words lead to the next band. Tested on the Mac (LearnTest).
 */
final class Learn {
    private Learn() {}

    /** What the extension worked out for a film: the band, and the words to learn to reach the next (0: none). */
    record Estimate(String band, int toNext) {}

    private static final List<String> BANDS = List.of("Hard", "Weak", "Good", "Comfortable");

    /** The extension's answer: none, queued, processing, ready or failed, a short message ("" none), the estimate. */
    record State(String status, String message, Estimate estimate) {
        State(String status, String message) {
            this(status, message, null);
        }

        // the answer to a later request (a queue answer carries no estimate) keeps the estimate already known
        State keeping(State before) {
            return estimate == null && before != null ? new State(status, message, before.estimate) : this;
        }

        // none and failed: the button asks for it (again); the others only look again
        boolean askable() {
            return status.equals("none") || status.equals("failed");
        }
    }

    // {"status": "queued", "message": ""}; null when it isn't one (an error page, an unknown status).
    static State parse(String json) {
        try {
            JSONObject o = new JSONObject(json);
            String s = o.optString("status");
            return switch (s) {
                case "none", "queued", "processing", "ready", "failed" ->
                    new State(s, o.optString("message"), estimate(o.optJSONObject("estimate")));
                default -> null;
            };
        } catch (JSONException e) {
            return null;
        }
    }

    // {"band": "Good", "to_next": 175}: null when missing, an unknown band or a negative count.
    private static Estimate estimate(JSONObject e) {
        if (e == null) return null;
        String band = e.optString("band");
        int toNext = e.optInt("to_next", -1);
        return BANDS.contains(band) && toNext >= 0 ? new Estimate(band, toNext) : null;
    }

    // The band after this one; null for Comfortable and for a name that is no band.
    private static String nextBand(String band) {
        int i = BANDS.indexOf(band);
        return i >= 0 && i < BANDS.size() - 1 ? BANDS.get(i + 1) : null;
    }

    // The queue request's body: the film as TMDB and IMDb know it.
    static String queueBody(String imdb, int tmdb, String title) {
        try {
            return new JSONObject().put("imdb", imdb).put("tmdb", tmdb).put("title", title).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    // "Good · 175 words to Comfortable"; just the band when there is nothing further. Words only, from the film's
    // English subtitle (Hard < 90% of its words known, Weak 90-95, Good 95-98, Comfortable 98+).
    static String estimateText(Estimate e) {
        String next = nextBand(e.band());
        if (next == null || e.toNext() == 0) return e.band();
        return e.band() + " · " + e.toNext() + (e.toNext() == 1 ? " word to " : " words to ") + next;
    }

    // Beside a status the text is short: the button shares a row with Play, Sources and Trailer.
    static String shortEstimate(Estimate e) {
        return e.toNext() == 0 ? e.band() : e.band() + " · " + e.toNext();
    }

    // The button's text: the estimate where the request is none (the click learns it); the status first once it is
    // queued, building or ready, with the short estimate after; failed says so alone (the longest text otherwise).
    static String label(State s) {
        if (s == null) return "Learn";
        Estimate e = s.estimate();
        String also = e == null ? "" : " · " + shortEstimate(e);
        return switch (s.status()) {
            case "queued" -> "Learn · queued" + also;
            case "processing" -> "Learn · building" + also;
            case "ready" -> "Learn · ready" + also;
            case "failed" -> "Learn · failed, try again";
            default -> e == null ? "Learn" : estimateText(e);
        };
    }
}
