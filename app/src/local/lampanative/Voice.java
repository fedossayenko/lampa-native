package local.lampanative;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import static java.nio.charset.StandardCharsets.US_ASCII;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Voice search's logic (no android.*, VoiceTest): the remote's voice button held → 16 kHz speech → the worker's
 * /voice/hear (text) → Claude Code on a Mac at home (what was meant; worker/home-server/voice-claude.py) → the
 * worker's /voice/find (TMDB's films, each with a confidence). Fire OS gives the button to the app on screen instead
 * of Alexa when its activity has the meta-data can_accept_voice_input (FireTVKeyPolicyManager); the
 * remote's microphone then streams to the input BTLE-In (16 kHz mono) while the button is held.
 */
final class Voice {
    private Voice() {}

    static final int RATE = 16000;
    static final int MIN_BYTES = RATE; // 0.5 s of 16-bit speech: less is a tap, not a request
    static final int MAX_MS = 15000;
    static final int KEYCODE_ALEXA = 322; // Fire OS's own code for the voice button (its key policy's log)

    // What the home server (Claude for voice search and Story so far, plain http on the home network) gets instead of
    // the play token: {X-Home-Time, X-Home-Sig}, the time in ms and HMAC-SHA256(token, "home\n" + time + "\n" + body)
    // in hex. It opens nothing at the worker if read on the way, and the server takes a request within 60 s of its time
    // and once, so a copy of it can't be sent again or with another body.
    static String[] homeAuth(String playToken, byte[] body, long nowMs) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(playToken.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String time = String.valueOf(nowMs);
            mac.update(("home\n" + time + "\n").getBytes(StandardCharsets.UTF_8));
            return new String[] {time, String.format("%064x", new java.math.BigInteger(1, mac.doFinal(body)))};
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e); // HmacSHA256 is always there
        }
    }

    // The voice button: Fire OS's ALEXA, or what an Android remote sends.
    static boolean isKey(int keyCode) {
        return keyCode == KEYCODE_ALEXA || keyCode == 84 /* SEARCH */ || keyCode == 231 /* VOICE_ASSIST */;
    }

    // 16-bit mono PCM at RATE as a WAV file.
    static byte[] wav(byte[] pcm, int length) {
        ByteBuffer b = ByteBuffer.allocate(44 + length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(US_ASCII)).putInt(36 + length)
            .put("WAVEfmt ".getBytes(US_ASCII)).putInt(16).putShort((short) 1)
            .putShort((short) 1).putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16)
            .put("data".getBytes(US_ASCII)).putInt(length).put(pcm, 0, length);
        return b.array();
    }

    static String heard(String json) throws JSONException {
        return new JSONObject(json).optString("text").trim();
    }

    static String understandBody(String text) throws JSONException {
        return new JSONObject().put("text", text).toString();
    }

    // The home server's answer as it came when it is a JSON object, else null: a captive page or a proxy's text on
    // a 200 would make findBody throw, and the worker's own understanding (what null asks for) would never be tried.
    static String understood(String body) {
        try {
            new JSONObject(body); // parses, or throws
            return body;
        } catch (JSONException e) {
            return null;
        }
    }

    // understood: Claude Code's answer as it came, null when it didn't answer (the worker's own model reads text).
    static String findBody(String text, String understood) throws JSONException {
        JSONObject o = new JSONObject().put("text", text);
        if (understood != null) o.put("understood", new JSONObject(understood));
        return o.toString();
    }

    record Found(Home.Card card, int sure) {} // sure: 0-100, -1 = found by a recommendation's filters

    record Answer(String intent, boolean open, String by, List<Found> results) {}

    // {intent, by, open, results: [[id, type, name, year, poster, score, backdrop, sure]]}
    static Answer parse(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        JSONArray a = o.getJSONArray("results");
        List<Found> found = new ArrayList<>(a.length());
        for (int i = 0; i < a.length(); i++) found.add(new Found(Home.card(a.getJSONArray(i)), a.getJSONArray(i).optInt(7, -1)));
        return new Answer(o.optString("intent"), o.optBoolean("open"), o.optString("by"), found);
    }

    // How loud a piece of 16-bit speech is, 0-8 (the meter's bars): its RMS of full scale × 60, so the remote's
    // speech (0.05-0.2 by Alexa's own meter) fills 3-8 bars and its hiss (0.004) none.
    static int level(byte[] pcm, int length) {
        double sum = 0;
        for (int i = 0; i + 1 < length; i += 2) {
            double s = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8)) / 32768.0;
            sum += s * s;
        }
        return length < 2 ? 0 : (int) Math.min(8, Math.sqrt(sum / (length / 2)) * 60);
    }

    static String meter(int level) {
        return "Listening  " + "▮".repeat(level) + "▯".repeat(8 - level);
    }

    // A card's corner: how sure ("97%"), nothing for one the filters found.
    static String badge(int sure) {
        return sure < 0 ? null : sure + "%";
    }
}
