package local.lampanative;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

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
    static HttpURLConnection withCub(HttpURLConnection c, Cub.Account a) {
        c.setRequestProperty("X-Cub-Token", a.token());
        c.setRequestProperty("X-Cub-Profile", String.valueOf(a.profile()));
        return c;
    }
    // CUB's API: the sign-in's form posts (as Lampa sends them), or a read with the account's token.
    static Answer cub(String path, String form, String token) throws IOException {
        HttpURLConnection c = open(Cub.API + path, 20000);
        if (token != null) c.setRequestProperty("token", token);
        return send(c, form == null ? null : form.getBytes(StandardCharsets.UTF_8), "application/x-www-form-urlencoded");
    }
    // A signed-in POST to the worker: its status, 0 when it didn't answer.
    static int post(String path, Cub.Account a, byte[] body, String type) {
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
    static HttpURLConnection open(String url, int readTimeout) throws IOException {
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            throw new FileNotFoundException(Home.worker.isEmpty() ? "No worker set" : "Not an http(s) address: " + url);
        }
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(readTimeout);
        String t = token;
        if (t != null && !Home.worker.isEmpty() && url.startsWith(Home.worker + "/")) c.setRequestProperty("X-Play-Token", t);
        return c;
    }
}
