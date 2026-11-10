package local.lampanative;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The film page's Learn button: a film's status from the worker's /ext/learn extension (queued, building, ready) and
 * the learned percent /mine brings. Tested on the Mac (LearnTest).
 */
final class Learn {
    private Learn() {}

    /** The extension's answer: none, queued, processing, ready or failed, and a short message ("" none). */
    record State(String status, String message) {
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
                case "none", "queued", "processing", "ready", "failed" -> new State(s, o.optString("message"));
                default -> null;
            };
        } catch (JSONException e) {
            return null;
        }
    }

    // The queue request's body: the film as TMDB and IMDb know it.
    static String queueBody(String imdb, int tmdb, String title) {
        try {
            return new JSONObject().put("imdb", imdb).put("tmdb", tmdb).put("title", title).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    // The button's text: the learned percent once there is one (/mine's "learned"), else where the request is.
    static String label(State s, String percent) {
        if (percent != null && !percent.isEmpty() && (s == null || s.status().equals("ready") || s.askable())) {
            return "Learned " + percent + "%";
        }
        if (s == null) return "Learn";
        return switch (s.status()) {
            case "queued" -> "Learn · queued";
            case "processing" -> "Learn · building";
            case "ready" -> "Learn · ready";
            case "failed" -> "Learn · failed, try again";
            default -> "Learn";
        };
    }
}
