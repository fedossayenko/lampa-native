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

    // A server that sends a little, then stalls: the result is measured over the test's time, not the stall's.
    static HttpServer stalling(ExecutorService threads) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", x -> {
            x.sendResponseHeaders(200, 1 << 20);
            OutputStream o = x.getResponseBody();
            o.write(new byte[64 << 10]);
            o.flush();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            x.close();
        });
        s.setExecutor(threads);
        s.start();
        return s;
    }

    void testStalledReadEndsSoonAfterTheTest() throws Exception {
        ExecutorService threads = Executors.newCachedThreadPool();
        HttpServer s = stalling(threads);
        try {
            long start = System.nanoTime();
            double mbps = Net.mbps("http://127.0.0.1:" + s.getAddress().getPort() + "/", 2, 300);
            long took = (System.nanoTime() - start) / 1_000_000;
            assert took < 3500 : took;
            // 2 × 64 KB over the 300 ms, not over the 2 s read timeout too
            assert mbps > 2 : mbps;
        } finally {
            s.stop(0);
            threads.shutdownNow();
        }
    }

    // Main.onStop: nothing downloads behind DDD, and a test cut short shows no result.
    void testStopEndsTheTest() throws Exception {
        ExecutorService threads = Executors.newCachedThreadPool();
        HttpServer s = stalling(threads);
        try {
            new Thread(() -> {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                Net.stopSpeed();
            }).start();
            long start = System.nanoTime();
            double mbps = Net.mbps("http://127.0.0.1:" + s.getAddress().getPort() + "/", 2, 60_000);
            long took = (System.nanoTime() - start) / 1_000_000;
            assert took < 3500 : took;
            assert mbps < 0 : mbps;
        } finally {
            s.stop(0);
            threads.shutdownNow();
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
