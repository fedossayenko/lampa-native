package local.lampanative;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/** The app's HTTP: reads through the platform's disk cache, requests never cached, the worker's signed-in POSTs. */
final class Net {
    private Net() {}

    static volatile String token; // the play token (Main.server): goes with every request to the worker

    // A request's status and the body of its answer, an error's too.
    record Answer(int code, String body) {}

    // A small file's text, "" when there is none.
    static String readFile(File f) {
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
    // The worker's routes that read CUB take the app's sign-in (it checks it with CUB and keeps nothing).
    // A redirect would send the headers on to wherever it points (the platform keeps a request's headers when it
    // follows one): no route of ours redirects, so the calls that carry a token never follow one.
    static HttpURLConnection withCub(HttpURLConnection c, Cub.Account a) {
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("X-Cub-Token", a.token());
        c.setRequestProperty("X-Cub-Profile", String.valueOf(a.profile()));
        return c;
    }
    // CUB's API: the sign-in's form posts (as Lampa sends them), or a read with the account's token.
    static Answer cub(String path, String form, String token) throws IOException {
        HttpURLConnection c = open(Cub.API + path, 20000);
        if (token != null) {
            c.setRequestProperty("token", token);
            c.setInstanceFollowRedirects(false); // see withCub
        }
        return send(c, form == null ? null : form.getBytes(StandardCharsets.UTF_8), "application/x-www-form-urlencoded");
    }
    // A signed-in POST to the worker: its status, 0 when it didn't answer, or when nobody is signed in (a button drawn
    // while signed in can be pressed after signing out from the Settings sheet; a null account would end the pool
    // thread's task with a NullPointerException, which ends the app).
    static int post(String path, Cub.Account a, byte[] body, String type) {
        if (a == null) return 0;
        try {
            return send(withCub(open(Home.worker + path, 20000), a), body, type).code();
        } catch (IOException e) {
            return 0;
        }
    }
    // A request never cached, with an optional body: its status and the body of the answer, an error's too.
    static Answer send(HttpURLConnection c, byte[] body, String type) throws IOException {
        c.setUseCaches(false);
        try {
            if (body != null) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(body.length); // streamed, not buffered once more
                c.setRequestProperty("Content-Type", type);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body);
                }
            }
            int code = c.getResponseCode();
            try (InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream()) {
                return new Answer(code, in == null ? "{}" : new String(Body.read(in, c.getContentLength()),
                    StandardCharsets.UTF_8));
            }
        } finally {
            c.disconnect();
        }
    }
    // Read the whole body: the HTTP cache only keeps a response that was read to the end. An HTTP error: Status.
    static byte[] read(String url) throws IOException {
        return read(url, false);
    }
    // stale: a copy in the disk cache up to a day past its max-age will do.
    static byte[] read(String url, boolean stale) throws IOException {
        HttpURLConnection c = open(url, 20000);
        if (stale) c.setRequestProperty("Cache-Control", "max-stale=86400");
        try {
            int code = c.getResponseCode();
            if (code >= 400) throw new Status(code);
            try (InputStream in = c.getInputStream()) {
                return Body.read(in, c.getContentLength());
            }
        } finally {
            c.disconnect();
        }
    }
    static final class Status extends IOException {
        static final long serialVersionUID = 1;
        final int code;

        Status(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }
    // A preload: the body only goes into the disk cache, through one 16 KB buffer instead of piling up in the heap.
    static void cache(String url) throws IOException {
        HttpURLConnection c = open(url, 20000);
        try (InputStream in = c.getInputStream()) {
            byte[] buf = new byte[16384];
            while (in.read(buf) > 0) {
                // only into the cache
            }
        } finally {
            c.disconnect();
        }
    }

    // Settings → Speed test: Cloudflare's test file (it refuses more than 50 MB an answer), asked again and again.
    static final String SPEED = "https://speed.cloudflare.com/__down?bytes=25000000";

    // The running test's deadline (System.nanoTime), 0 once stopSpeed() ended it: its connections go on while it is
    // still theirs. One test at a time (Main's row).
    static volatile long speedEnd;

    // Main.onStop: no download behind DDD. Each connection sees it at its next read (a stalled one within 2 s).
    static void stopSpeed() {
        speedEnd = 0;
    }

    // The test ending at end goes on: before it, not stopped, no newer one.
    static boolean running(long end) {
        return System.nanoTime() < end && speedEnd == end;
    }

    // The end of a test starting now, set on the caller's thread (the UI's), so a stopSpeed() after it ends that test
    // even before the test's own thread runs
    static long armSpeed(long ms) {
        long end = System.nanoTime() + ms * 1_000_000;
        speedEnd = end;
        return end;
    }

    // streams connections read url over and over until end (armSpeed's), this thread one of them: the Mbit/s they
    // got together over that time, 0 when nothing came, -1 when stopSpeed() ended it.
    static double mbpsUntil(String url, int streams, long end) {
        AtomicLong bytes = new AtomicLong();
        long start = System.nanoTime();
        Runnable read = () -> {
            byte[] buf = new byte[16384];
            while (running(end)) {
                try {
                    HttpURLConnection c = open(url, 2000); // a stalled read gives up 2 s on, not 10
                    c.setConnectTimeout(2000); // and so does a stalled connect
                    c.setUseCaches(false); // 25 MB answers kept out of the 50 MB disk cache
                    c.setRequestProperty("Accept-Encoding", "identity"); // bytes as sent: gzip would count unpacked
                    try (InputStream in = c.getInputStream()) {
                        for (int n; running(end) && (n = in.read(buf)) > 0; ) bytes.addAndGet(n);
                    } finally {
                        c.disconnect();
                    }
                } catch (IOException e) {
                    return; // this connection's done; the others go on
                }
            }
        };
        Thread[] others = new Thread[streams - 1];
        for (int i = 0; i < others.length; i++) {
            others[i] = new Thread(read);
            others[i].setDaemon(true);
            others[i].start();
        }
        read.run();
        long until = end + 2_500_000_000L; // past it a stalled one ends alone at its read timeout
        try {
            for (Thread t : others) {
                long left = (until - System.nanoTime()) / 1_000_000;
                if (left > 0) t.join(left);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
        if (speedEnd == 0) return -1;
        return bytes.get() * 8e3 / (Math.min(System.nanoTime(), end) - start); // bits per ns × 1000 = Mbit/s
    }

    static String speedLine(double mbps) {
        return mbps <= 0 ? "Not reachable" : mbps < 10 ? String.format(Locale.ROOT, "%.1f Mbps", mbps)
            : Math.round(mbps) + " Mbps";
    }

    static HttpURLConnection open(String url, int readTimeout) throws IOException {
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            throw new FileNotFoundException(Home.worker.isEmpty() ? "No worker set" : "Not an http(s) address: " + url);
        }
        HttpURLConnection c;
        try {
            c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        } catch (IllegalArgumentException e) { // a space or a bar in a language code: an IOException like the rest
            throw new MalformedURLException("Not an address: " + url);
        }
        c.setConnectTimeout(10000);
        c.setReadTimeout(readTimeout);
        String t = token;
        if (t != null && !Home.worker.isEmpty() && url.startsWith(Home.worker + "/")) {
            c.setRequestProperty("X-Play-Token", t);
            c.setInstanceFollowRedirects(false); // see withCub
        }
        return c;
    }
}
