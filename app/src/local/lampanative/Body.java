package local.lampanative;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/** A response body read whole (InputStream.readAllBytes is API 33). No android.*: tested on the Mac (BodyTest). */
final class Body {
    private Body() {}

    // Into one buffer the size the server said (the cache knows it): handed back as is, no copy on the ~5 MB heap.
    // Unknown (-1), wrong or over 4 MB (ours are ≤ ~300 KB): the buffer doubles, as ByteArrayOutputStream would.
    static byte[] read(InputStream in, int size) throws IOException {
        byte[] b = new byte[size > 0 && size <= 4 << 20 ? size : 16384];
        int n = 0;
        while (true) {
            if (n == b.length) {
                int next = in.read();
                if (next < 0) return b;
                b = Arrays.copyOf(b, b.length * 2);
                b[n++] = (byte) next;
            }
            int r = in.read(b, n, b.length - n);
            if (r < 0) return n == b.length ? b : Arrays.copyOf(b, n);
            n += r;
        }
    }
}
