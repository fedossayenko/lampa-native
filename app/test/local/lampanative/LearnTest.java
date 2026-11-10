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
        assert Learn.label(null).equals("Learn") && Learn.label(none).equals("Learn");
        assert Learn.label(queued).equals("Learn · queued") && Learn.label(building).equals("Learn · building");
        assert Learn.label(ready).equals("Learn · ready");
        assert Learn.label(new Learn.State("failed", "x")).equals("Learn · failed, try again");
    }

    void testEstimate() {
        Learn.State s = Learn.parse("{\"status\":\"none\",\"message\":\"\",\"estimate\":{\"band\":\"Good\",\"to_next\":175,\"updated\":5}}");
        assert s.status().equals("none") && s.estimate().equals(new Learn.Estimate("Good", 175));
        assert Learn.parse("{\"status\":\"none\"}").estimate() == null;
        for (String bad : new String[] {"{\"band\":\"Fine\",\"to_next\":1}", "{\"band\":\"Good\",\"to_next\":-1}", "{\"band\":\"Good\"}", "{\"to_next\":3}", "[]"}) {
            assert Learn.parse("{\"status\":\"none\",\"estimate\":" + bad + "}").estimate() == null : bad;
        }
        assert Learn.parse("{\"status\":\"none\",\"estimate\":{\"band\":\"Comfortable\",\"to_next\":0}}").estimate().equals(new Learn.Estimate("Comfortable", 0));
    }

    void testEstimateInTheLabel() {
        Learn.Estimate e = new Learn.Estimate("Good", 175);
        Learn.State none = new Learn.State("none", "", e), building = new Learn.State("processing", "", e);
        assert Learn.estimateText(e).equals("Good · 175 words to Comfortable");
        assert Learn.estimateText(new Learn.Estimate("Weak", 6)).equals("Weak · 6 words to Good");
        assert Learn.estimateText(new Learn.Estimate("Hard", 1)).equals("Hard · 1 word to Weak");
        assert Learn.estimateText(new Learn.Estimate("Comfortable", 0)).equals("Comfortable");
        assert Learn.label(none).equals("Learn") : "the estimate is on the info line";
        assert Learn.label(building).equals("Learn · building");
        assert Learn.label(new Learn.State("failed", "x", e)).equals("Learn · failed, try again") : "kept short";
    }

    void testKeepingTheEstimateAcrossAnswers() {
        Learn.State known = new Learn.State("none", "", new Learn.Estimate("Good", 175)), queued = new Learn.State("queued", "");
        assert queued.keeping(known).equals(new Learn.State("queued", "", new Learn.Estimate("Good", 175)));
        assert queued.keeping(null).equals(queued) && queued.keeping(queued).equals(queued);
        Learn.State newer = new Learn.State("ready", "", new Learn.Estimate("Comfortable", 0));
        assert newer.keeping(known).estimate().band().equals("Comfortable") : "a fresher estimate wins";
    }
}
