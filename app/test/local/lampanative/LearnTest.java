package local.lampanative;

class LearnTest {
    void testParse() {
        assert Learn.parse("{\"status\":\"queued\",\"message\":\"\"}").equals(new Learn.State("queued", ""));
        assert Learn.parse("{\"status\":\"none\"}").askable() && Learn.parse("{\"status\":\"failed\",\"message\":\"x\"}").askable();
        assert !Learn.parse("{\"status\":\"processing\"}").askable() && !Learn.parse("{\"status\":\"ready\"}").askable();
        assert Learn.parse("{\"status\":\"weird\"}") == null && Learn.parse("<html>bad gateway") == null && Learn.parse("") == null;
    }

    void testBody() throws Exception {
        org.json.JSONObject o = new org.json.JSONObject(Learn.queueBody("tt0468569", 155, "A \"quoted\" title"));
        assert o.getString("imdb").equals("tt0468569") && o.getInt("tmdb") == 155 && o.getString("title").equals("A \"quoted\" title");
    }

    void testLabel() {
        Learn.State none = new Learn.State("none", ""), queued = new Learn.State("queued", ""),
            building = new Learn.State("processing", ""), ready = new Learn.State("ready", "");
        assert Learn.label(null, null).equals("Learn") && Learn.label(none, null).equals("Learn");
        assert Learn.label(queued, null).equals("Learn · queued") && Learn.label(building, null).equals("Learn · building");
        assert Learn.label(ready, null).equals("Learn · ready") && Learn.label(ready, "").equals("Learn · ready");
        assert Learn.label(ready, "93").equals("Learned 93%") && Learn.label(none, "40").equals("Learned 40%");
        assert Learn.label(building, "93").equals("Learn · building") : "a rebuild in progress shows";
        assert Learn.label(new Learn.State("failed", "x"), null).equals("Learn · failed, try again");
    }
}
