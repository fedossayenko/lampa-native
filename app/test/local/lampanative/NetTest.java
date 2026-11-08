package local.lampanative;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

class NetTest {
    // Settings → Speed test: every connection's bytes count, and each one asks again when its answer ends.
    void testSpeedCountsEveryConnection() throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] body = new byte[64 << 10];
        int[] asked = {0};
        s.createContext("/", x -> {
            synchronized (asked) {
                asked[0]++;
            }
            x.sendResponseHeaders(200, body.length);
            try (OutputStream o = x.getResponseBody()) {
                o.write(body);
            }
        });
        ExecutorService threads = Executors.newFixedThreadPool(4);
        s.setExecutor(threads);
        s.start();
        try {
            long start = System.nanoTime();
            double mbps = Net.mbps("http://127.0.0.1:" + s.getAddress().getPort() + "/", 2, 300);
            long took = (System.nanoTime() - start) / 1_000_000;
            assert mbps > 1 : mbps;
            assert took >= 300 && took < 3000 : took;
            synchronized (asked) {
                assert asked[0] > 2 : asked[0]; // more than one answer a connection
            }
        } finally {
            s.stop(0);
            threads.shutdown();
        }
    }

    void testSpeedOfNoServerIsZero() throws Exception {
        assert Net.mbps("http://127.0.0.1:1/", 2, 300) == 0;
    }

    void testSpeedLine() {
        assert Net.speedLine(412.4).equals("412 Mbps") : Net.speedLine(412.4);
        assert Net.speedLine(8.46).equals("8.5 Mbps") : Net.speedLine(8.46);
        assert Net.speedLine(0).equals("Not reachable") : Net.speedLine(0);
    }
}
