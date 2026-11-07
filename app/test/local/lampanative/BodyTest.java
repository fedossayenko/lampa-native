package local.lampanative;

import java.io.ByteArrayInputStream;
import java.util.Arrays;

class BodyTest {
    private static byte[] bytes(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) i;
        return b;
    }

    // The size the server said (the cache knows it), shorter, longer or unknown: always the whole body.
    void testWholeBody() throws Exception {
        for (int said : new int[] {-1, 0, 1000, 999_999, 300_000}) {
            for (int real : new int[] {0, 1, 1000, 16_384, 300_000}) {
                byte[] got = Body.read(new ByteArrayInputStream(bytes(real)), said);
                assert Arrays.equals(got, bytes(real)) : "said " + said + ", real " + real + ": " + got.length;
            }
        }
    }


    // A wrong, huge Content-Length (an error page, a broken server) allocates nothing big before a byte comes.
    void testHugeSizeSaidIsNotAllocated() throws Exception {
        assert Body.read(new ByteArrayInputStream(bytes(10)), Integer.MAX_VALUE).length == 10;
    }
}
