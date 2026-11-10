package local.lampanative;

import com.sun.net.httpserver.HttpServer;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Net's rules about where the tokens go, on real sockets (127.0.0.1): the worker's address only, no redirects. */
class NetSafetyTest {
    // A server that records the X-Play-Token of each request it gets and answers with `code`, a Location when given.
    private static HttpServer serve(List<String> tokens, int code, String location, String body) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", x -> {
            tokens.add(String.valueOf(x.getRequestHeaders().getFirst("X-Play-Token")));
            if (location != null) x.getResponseHeaders().add("Location", location);
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
            if (b.length > 0) x.getResponseBody().write(b);
            x.close();
        });
        s.start();
        return s;
    }

    private static String at(HttpServer s) {
        return "http://127.0.0.1:" + s.getAddress().getPort();
    }

    // The play token goes to the worker's own address and to nothing else (another port of the same host included).
    void testTokenOnlyToTheWorker() throws Exception {
        List<String> worker = new ArrayList<>(), other = new ArrayList<>();
        HttpServer w = serve(worker, 200, null, "{}"), o = serve(other, 200, null, "{}");
        String was = Home.worker, wasToken = Net.token;
        try {
            Home.worker = at(w);
            Net.token = "secret-token-0123456789";
            Net.read(at(w) + "/home");
            Net.read(at(o) + "/home");
            assert worker.equals(List.of("secret-token-0123456789")) : worker;
            assert other.equals(List.of("null")) : other;
        } finally {
            Home.worker = was;
            Net.token = wasToken;
            w.stop(0);
            o.stop(0);
        }
    }

    // A redirect from the worker is not followed: the token would go on to wherever it points.
    void testRedirectIsNotFollowed() throws Exception {
        List<String> target = new ArrayList<>(), worker = new ArrayList<>();
        HttpServer t = serve(target, 200, null, "{}");
        HttpServer w = serve(worker, 302, at(t) + "/stolen", "");
        String was = Home.worker, wasToken = Net.token;
        try {
            Home.worker = at(w);
            Net.token = "secret-token-0123456789";
            Net.Answer a = Net.send(Net.open(at(w) + "/x", 5000), null, null);
            assert a.code() == 302 : a.code();
            assert target.isEmpty() : "the redirect was followed: " + target;
        } finally {
            Home.worker = was;
            Net.token = wasToken;
            w.stop(0);
            t.stop(0);
        }
    }

    // Anything but http(s) is refused as a FileNotFoundException; a bad character is an IOException, not a crash.
    void testBadAddressesAreIOExceptions() throws Exception {
        for (String url : new String[] {"file:///etc/passwd", "ftp://example.com/x", "/home", ""}) {
            try {
                Net.open(url, 1000);
                assert false : url;
            } catch (FileNotFoundException expected) {
                // refused
            }
        }
        try {
            Net.open("https://example.com/mine?lang=RU UA", 1000);
            assert false : "a space made a connection";
        } catch (IOException expected) {
            // not an IllegalArgumentException
        }
    }

    // An error's code and body come back (send), or as a Status with its code (read).
    void testErrorsKeepTheirCodeAndBody() throws Exception {
        HttpServer s = serve(new ArrayList<>(), 500, null, "{\"error\":\"boom\"}"), gone = serve(new ArrayList<>(), 404, null, "");
        try {
            HttpURLConnection c = Net.open(at(s) + "/x", 5000);
            Net.Answer a = Net.send(c, null, null);
            assert a.code() == 500 && a.body().equals("{\"error\":\"boom\"}") : a;
            try {
                Net.read(at(gone) + "/x");
                assert false;
            } catch (Net.Status st) {
                assert st.code == 404;
            }
        } finally {
            s.stop(0);
            gone.stop(0);
        }
    }

    // Nobody signed in: a post says 0 (not saved) instead of throwing.
    void testPostWithoutAccount() {
        assert Net.post("/grades", null, new byte[0], "application/json") == 0;
    }
}
