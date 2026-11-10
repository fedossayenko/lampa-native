package local.lampanative;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

class VoiceTest {
    void testKey() {
        assert Voice.isKey(322) && Voice.isKey(84) && Voice.isKey(231) : "Fire OS's ALEXA, SEARCH, VOICE_ASSIST";
        assert !Voice.isKey(82) && !Voice.isKey(4) : "MENU and BACK stay theirs";
    }

    // A WAV that Nova-3 reads: 16 kHz, mono, 16-bit, the data's own length (the buffer may be bigger).
    void testWav() {
        byte[] pcm = {1, 2, 3, 4, 9, 9};
        byte[] w = Voice.wav(pcm, 4);
        ByteBuffer b = ByteBuffer.wrap(w).order(ByteOrder.LITTLE_ENDIAN);
        assert w.length == 48 : w.length;
        assert new String(w, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF") && b.getInt(4) == 40;
        assert b.getShort(22) == 1 && b.getInt(24) == 16000 && b.getInt(28) == 32000 && b.getShort(34) == 16;
        assert b.getInt(40) == 4 && w[44] == 1 && w[47] == 4;
    }

    // Silence no bars, the remote's hiss none, speech some, a shout all 8.
    void testLevel() {
        assert Voice.level(new byte[3200], 3200) == 0;
        assert Voice.level(tone(130), 3200) == 0 : "hiss: 0.004 of full scale";
        assert Voice.level(tone(3277), 3200) == 6 : "speech at 0.1 of full scale";
        assert Voice.level(tone(820), 3200) == 1 : "quiet speech at 0.025 shows";
        assert Voice.level(tone(32000), 3200) == 8 && Voice.level(new byte[0], 0) == 0;
        assert Voice.meter(3).equals("Listening  ▮▮▮▯▯▯▯▯") : Voice.meter(3);
    }

    // A square wave of this amplitude, little-endian 16-bit: its RMS is the amplitude.
    private static byte[] tone(int amplitude) {
        ByteBuffer b = ByteBuffer.allocate(3200).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 1600; i++) b.putShort((short) (i % 2 == 0 ? amplitude : -amplitude));
        return b.array();
    }

    void testBodies() throws Exception {
        assert Voice.understandBody("фильм \"Дюна\"").equals("{\"text\":\"фильм \\\"Дюна\\\"\"}") : Voice.understandBody("фильм \"Дюна\"");
        assert Voice.findBody("x", null).equals("{\"text\":\"x\"}") : "no answer from Claude: the worker reads it";
        String with = Voice.findBody("x", "{\"intent\":\"title\",\"candidates\":[]}");
        assert with.contains("\"understood\":{") && with.contains("\"intent\":\"title\"") : with;
        assert Voice.heard("{\"text\":\" Дом дракона. \"}").equals("Дом дракона.");
        assert Voice.heard("{}").isEmpty();
    }

    // The worker's answer (2026-10-31): sure in the card's eighth place; -1 for the filters' finds.
    void testParse() throws Exception {
        Voice.Answer a = Voice.parse("{\"intent\":\"title\",\"by\":\"claude\",\"open\":true,\"results\":[[157336,\"movie\","
            + "\"Interstellar\",\"2014\",\"/i.jpg\",84,\"\",97],[2,\"movie\",\"Robo\",\"2025\",\"/r.jpg\",0,\"\",-1]]}");
        assert a.open() && a.intent().equals("title") && a.by().equals("claude") : a;
        assert a.results().equals(List.of(
            new Voice.Found(new Home.Card(157336, "movie", "Interstellar", "2014", "/i.jpg", 84, ""), 97),
            new Voice.Found(new Home.Card(2, "movie", "Robo", "2025", "/r.jpg", 0, ""), -1))) : a.results();
        assert "97%".equals(Voice.badge(97)) && Voice.badge(-1) == null;
    }

    // The same HMAC as the home server's (python: hmac.new(b"abc", b"home\n1760000000000\n" + body, hashlib.sha256).hexdigest()),
    // the time and the body in it: another body or another time is another signature.
    void testHomeAuth() {
        byte[] body = "{\"text\":\"dune\"}".getBytes(StandardCharsets.UTF_8);
        String[] a = Voice.homeAuth("abc", body, 1760000000000L);
        assert a[0].equals("1760000000000") : a[0];
        assert a[1].equals("c194caad455ed94f5e479e53c6f8ea1f3202d408e9fc553d3b5c00133bd2645e") : a[1];
        assert !a[1].equals(Voice.homeAuth("abc", body, 1760000000001L)[1]);
        assert !a[1].equals(Voice.homeAuth("abc", "{\"text\":\"x\"}".getBytes(StandardCharsets.UTF_8), 1760000000000L)[1]);
        assert !a[1].equals(Voice.homeAuth("abd", body, 1760000000000L)[1]);
    }

    // Only a JSON object from the home server goes on to /voice/find; anything else is "didn't answer".
    void testUnderstoodIsAnObject() throws Exception {
        assert Voice.understood("{\"intent\":\"find\"}").equals("{\"intent\":\"find\"}");
        assert Voice.understood("<html>login</html>") == null && Voice.understood("") == null;
        assert Voice.understood("[1]") == null;
        Voice.findBody("dune", Voice.understood("sorry, try later")); // null: no "understood" key, no exception
    }
}
