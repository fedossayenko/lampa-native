package local.lampanative;

/** Scroll and visibility math for the rows. No android.* here, so it is tested on the Mac (LayoutTest). */
final class Layout {
    private Layout() {}

    // Where a row scrolls to show the card [left, right): only when it is cut off, and then to a card edge, so no
    // card shows cut in half (Lampa does the same).
    static int alignedScroll(int scrollX, int width, int left, int right, int stride) {
        if (left < scrollX) return left / stride * stride;
        if (right > scrollX + width) return (right - width + stride - 1) / stride * stride;
        return scrollX;
    }

    // Whether a poster at screen position (x, y) is near enough the viewport to hold its bitmap: a quarter screen
    // sideways (the next cards) and up, half a screen down. A row is ~650 px: one DOWN brings in the row that was
    // two below (from ~1390 px), one UP the row above (LayoutTest). Each poster held costs ~270 KB of pixels, and
    // its GPU texture once drawn, until the bitmap is collected. A view not laid out yet (w == 0) is not near.
    static boolean near(int x, int y, int w, int h, int screenW, int screenH) {
        return w > 0 && x > -screenW / 4 - w && x < screenW * 5 / 4 && y > -screenH / 4 - h && y < screenH * 3 / 2;
    }

    // Whether any of it is on the screen: what a film page leaves loaded on Home underneath.
    static boolean onScreen(int x, int y, int w, int h, int screenW, int screenH) {
        return w > 0 && x > -w && x < screenW && y > -h && y < screenH;
    }

    // A picture (pw × ph) made the size it is shown (w × h), cropped like CENTER_CROP: {scale, dx, dy}.
    static float[] crop(int pw, int ph, int w, int h) {
        float scale = Math.max((float) w / pw, (float) h / ph);
        return new float[] {scale, (w - pw * scale) / 2, (h - ph * scale) / 2};
    }
}
