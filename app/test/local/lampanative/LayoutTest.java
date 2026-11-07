package local.lampanative;

class LayoutTest {
    // The stick: 1920×1080, cards 300 px wide with a 32 px gap, rows 1840 px wide (80 px left padding).
    static final int STRIDE = 332, CARD = 300, ROW = 1840, SW = 1920, SH = 1080;

    static int scrollFor(int scrollX, int index) {
        int left = index * STRIDE;
        return Layout.alignedScroll(scrollX, ROW, left, left + CARD, STRIDE);
    }

    void testVisibleCardDoesNotScroll() {
        assert scrollFor(0, 0) == 0;
        assert scrollFor(0, 4) == 0 : scrollFor(0, 4); // card 4 ends at 1628 < 1840
    }

    void testRevealingCardOnTheRightScrollsByWholeCards() {
        assert scrollFor(0, 5) == STRIDE : scrollFor(0, 5); // card 5 ends at 1960: one card is enough
        assert scrollFor(0, 9) == 5 * STRIDE : scrollFor(0, 9);
    }

    void testRevealingCardOnTheLeftPutsItFirst() {
        assert scrollFor(3 * STRIDE, 1) == STRIDE : scrollFor(3 * STRIDE, 1);
    }

    // The bug this replaced: Android's own scroll stopped mid-card and cut the first card in half.
    void testScrollAlwaysLandsOnACardEdge() {
        for (int from = 0; from <= 10 * STRIDE; from += STRIDE) {
            for (int i = 0; i < 20; i++) assert scrollFor(from, i) % STRIDE == 0 : "from " + from + " to card " + i;
        }
    }

    void testPosterNearTheScreenIsLoaded() {
        assert Layout.near(100, 100, CARD, 450, SW, SH);       // on screen
        assert Layout.near(SW + 400, 100, CARD, 450, SW, SH);  // next card off the right edge
        assert Layout.near(100, SH + 200, CARD, 450, SW, SH);  // next row below
        assert Layout.near(-CARD - 400, 100, CARD, 450, SW, SH); // just scrolled off the left
    }

    // A Home row is ~650 px (title, poster, name, year). One DOWN brings on screen the row that was two below: its
    // poster starts at 2 rows + the title (~1390 px), so it must be loaded already, or it shows gray and pops in
    // (the "blinking" moving down). One UP brings the row above to the top: its poster at ~-560 px.
    void testTheRowsOneMovesIntoAreLoadedAlready() {
        assert Layout.near(100, 2 * 650 + 92, CARD, 450, SW, SH);
        assert Layout.near(100, -650 + 92, CARD, 450, SW, SH);
        assert !Layout.near(100, 3 * 650 + 92, CARD, 450, SW, SH) : "no further than needed";
    }

    // What a film page leaves loaded underneath: only the posters actually on the screen, so Back shows them at once.
    void testOnScreen() {
        assert Layout.onScreen(100, 100, CARD, 450, SW, SH);
        assert Layout.onScreen(SW - 10, SH - 10, CARD, 450, SW, SH) : "partly on screen counts";
        assert !Layout.onScreen(SW + 1, 100, CARD, 450, SW, SH) && !Layout.onScreen(100, SH, CARD, 450, SW, SH);
        assert !Layout.onScreen(-CARD, 100, CARD, 450, SW, SH) && !Layout.onScreen(0, 0, 0, 0, SW, SH);
    }

    void testPosterFarFromTheScreenIsDropped() {
        assert !Layout.near(SW * 2, 100, CARD, 450, SW, SH);
        assert !Layout.near(100, SH * 2, CARD, 450, SW, SH);
        assert !Layout.near(-SW, 100, CARD, 450, SW, SH);
        assert !Layout.near(100, -SH, CARD, 450, SW, SH);
    }

    void testViewNotLaidOutIsNotNear() {
        assert !Layout.near(0, 0, 0, 0, SW, SH);
    }

    // Software drawing: a picture made the size it is shown, cropped like CENTER_CROP: {scale, dx, dy}.
    void testCrop() {
        float[] backdrop = Layout.crop(1280, 720, 1920, 1080);
        assert backdrop[0] == 1.5f && backdrop[1] == 0 && backdrop[2] == 0 : "same shape: scaled only";
        float[] photo = Layout.crop(185, 278, 200, 300); // the cast's w185 into its 200×300 tile
        assert photo[0] == 200f / 185 : "a touch narrower than its tile: the width decides";
        assert Math.abs(photo[1]) < 1e-4 && Math.abs(photo[2] - (300 - 278 * photo[0]) / 2) < 1e-4 : "top and bottom cut";
        float[] wide = Layout.crop(400, 100, 200, 200);
        assert wide[0] == 2 && wide[1] == -300 && wide[2] == 0;
    }
}
