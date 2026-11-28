package local.lampanative;

import static local.lampanative.Net.*;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Voice search: the remote's voice button held records what is said (the microphone on its own thread), the worker hears it, the
 * home server's Claude Code understands it (or the worker's own model does) and TMDB finds it. The screen it fills is
 * SearchScreen; whether it records stays in Main (the key handler and onStop read it).
 */
final class VoiceSearch extends Main.Part {
    VoiceSearch(Main m) {
        super(m);
    }

    // Voice search (Voice): the remote's voice button held opens Search on top (or uses the open one) and records
    // until it is let go (Voice.MAX_MS at most). The microphone: adb's pm grant RECORD_AUDIO, or Fire TV's
    // Settings. Android's own question can't be asked from here: Fire OS closes it at once when the voice button
    // asks (seen: six presses, each closed in 30 ms, the button "not working").
    void listen() {
        if (m.recording || m.asking || m.restoring) return;
        m.closeOverlay(); // Search goes over the screen, not under a sheet
        SearchScreen s = m.pageTop != null && m.pageTop.getTag() instanceof SearchScreen o ? o : null;
        if (s == null) {
            View focused = m.scroll.findFocus();
            if (m.page == null) m.openPage(focused != null ? focused : m.settingsButton);
            s = new SearchScreen(m);
            s.show();
        }
        if (m.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            s.say("No microphone");
            s.clear();
            s.results.addView(m.ux.text("This app may not use the microphone. Allow it in Fire TV's Settings → Applications"
                + " → Manage Installed Applications → Lampa Native.", 18, 0xff9a9a9a));
            return;
        }
        int min = AudioRecord.getMinBufferSize(Voice.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, Voice.RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, Voice.RATE * 2));
        if (r.getState() != AudioRecord.STATE_INITIALIZED) {
            r.release();
            s.say("No microphone");
            return;
        }
        int ask = s.listening();
        m.recording = true;
        SearchScreen on = s;
        new Thread(() -> {
            ByteArrayOutputStream said = new ByteArrayOutputStream();
            byte[] b = new byte[3200]; // 0.1 s at most a read
            long end = System.nanoTime() + Voice.MAX_MS * 1_000_000L;
            int shownLevel = -1;
            try { // the microphone let go and the next press possible whatever a read throws
                r.startRecording();
                // not waiting for a full piece: the remote stops streaming as the button is let go, and a read waiting
                // for one would never return (seen: stuck until the next press, which it then took too)
                while (m.recording && System.nanoTime() < end) {
                    int n = r.read(b, 0, b.length, AudioRecord.READ_NON_BLOCKING);
                    if (n < 0) break;
                    if (n == 0) {
                        SystemClock.sleep(20);
                        continue;
                    }
                    said.write(b, 0, n);
                    int level = Voice.level(b, n);
                    if (level != shownLevel) {
                        shownLevel = level;
                        m.ui.post(() -> on.level(ask, level));
                    }
                }
            } catch (RuntimeException e) {
                Log.w(Main.TAG, "voice: recording", e);
            } finally {
                r.release(); // stops it too
                m.recording = false;
            }
            m.ui.post(() -> heard(on, ask, said.toByteArray()));
        }, "voice").start();
    }

    // What was said: heard by the worker, understood by Claude Code on the home server (or the worker's own model),
    // found on TMDB. One sure title opens at once; Back shows the others. ask: this press's number, an older
    // press's answers are dropped.
    void heard(SearchScreen s, int ask, byte[] pcm) {
        if (m.pageTop != s.screen || ask != s.asks) return; // Back, or pressed again, meanwhile
        if (pcm.length < Voice.MIN_BYTES) {
            s.say("Hold the button while you speak");
            s.clear();
            return;
        }
        s.thinking("Hearing…");
        Future<String> taste = taste(s); // beside the hearing
        m.async(() -> {
            String text = null;
            Voice.Answer found = null;
            try {
                Answer h = m.ask("/voice/hear", Voice.wav(pcm, pcm.length), "audio/wav");
                if (h.code() != 200) throw new IOException("voice/hear: " + h.code());
                text = Voice.heard(h.body());
                if (!text.isEmpty()) {
                    String t = text;
                    m.ui.post(() -> s.heard(ask, t));
                    found = find(text, taste);
                }
            } catch (Exception e) {
                Log.w(Main.TAG, "voice", e);
            }
            String t = text;
            Voice.Answer a = found;
            m.ui.post(() -> s.found(ask, t, a, null, null));
        });
    }

    // A theme on Search (Search.THEMES) or its Something different: asked as if said, shown as its label.
    void ask(SearchScreen s, Search.Theme theme, String text) {
        if (m.recording) return;
        int ask = ++s.asks;
        s.keys.requestFocus(); // the key pressed goes with the old results: the keyboard meanwhile, the first film after
        s.say(theme.label()); // gray, not a query: a key typed starts from nothing
        s.thinking("Finding…");
        Future<String> taste = taste(s);
        m.async(() -> {
            Voice.Answer found = null;
            try {
                found = find(text, taste);
            } catch (Exception e) {
                Log.w(Main.TAG, "theme", e);
            }
            Voice.Answer a = found;
            m.ui.post(() -> s.found(ask, theme.label(), a, theme, text));
        });
    }

    // Claude Code's understanding (or the worker's model's), found on TMDB; null when the worker didn't answer.
    Voice.Answer find(String text, Future<String> tasteAsked) throws IOException, JSONException {
        String taste = null;
        try {
            taste = tasteAsked.get(4, TimeUnit.SECONDS); // beside the hearing, or once a screen: rarely waited for
        } catch (Exception e) {
            Log.w(Main.TAG, "voice: no taste", e);
        }
        if (taste == null) taste = ""; // failed: asked without
        Answer f = m.ask("/voice/find", Voice.findBody(text, understand(text, taste), taste).getBytes(StandardCharsets.UTF_8));
        return f.code() == 200 ? Voice.parse(f.body()) : null;
    }

    // What a recommendation knows of the viewer (the worker's /voice/taste: grades and the country's Home rows), asked on
    // its own thread once while Search is open (again after a failure: null); "" when nobody is signed in.
    // Sent with title searches too (~2-4 KB): Claude can't know the intent before reading the request.
    Future<String> taste; // the last taste asked, for TASTE_MS: a voice press from Home opens a new Search each time
    long tasteAt;
    static final long TASTE_MS = 10 * 60_000;

    Future<String> taste(SearchScreen s) {
        if (Voice.reuse(taste) && System.currentTimeMillis() - tasteAt < TASTE_MS) return taste;
        Cub.Account a = m.account();
        String country = m.country();
        if (a == null) return CompletableFuture.completedFuture(""); // nobody signed in: nothing to ask
        FutureTask<String> t = new FutureTask<>(() -> {
            try {
                m.token(); // read again after a refusal, as m.ask does (open() sends it)
                Answer r = send(withCub(open(Home.worker + "/voice/taste?country=" + URLEncoder.encode(country, "UTF-8"), 5000), a),
                    null, null);
                if (r.code() == 200) return new JSONObject(r.body()).optString("taste");
                Log.w(Main.TAG, "voice: taste " + r.code());
                if (r.code() == 401 || r.code() == 403) { // refused: nothing to know, not asked again for TASTE_MS
                    if (r.code() == 403) Net.token = null;
                    return "";
                }
            } catch (IOException | JSONException e) { // m.token(): none set up, too
                Log.w(Main.TAG, "voice: taste", e);
            }
            return null;
        });
        Main.thread("taste", t);
        tasteAt = System.currentTimeMillis();
        return taste = t;
    }

    String understand(String text, String taste) {
        if (m.homeServer.isEmpty()) return null;
        try {
            HttpURLConnection c = open("http://" + m.homeServer + "/understand", 25000);
            // the home network: no answer in 3 s = not there (a computer just woken or busy took over 1.5 s); the
            // worker's own understanding meanwhile
            c.setConnectTimeout(3000);
            byte[] body = Voice.understandBody(text, taste).getBytes(StandardCharsets.UTF_8);
            String[] auth = Voice.homeAuth(m.token(), body, System.currentTimeMillis());
            c.setRequestProperty("X-Home-Time", auth[0]);
            c.setRequestProperty("X-Home-Sig", auth[1]);
            Answer a = send(c, body, "application/json");
            if (a.code() == 200) return Voice.understood(a.body());
            Log.w(Main.TAG, "voice: Claude Code answered " + a.code());
        } catch (IOException | JSONException e) {
            Log.w(Main.TAG, "voice: no Claude Code", e);
        }
        return null;
    }
}
