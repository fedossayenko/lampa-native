package local.lampanative;

import static local.lampanative.Net.*;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.util.Log;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The pictures: the posters, photos and stills that load as they come near the viewport and drop when they leave it, the
 * gray stand-in and the rounded Pic view, and the decoding of a bitmap (GPU or software). Their lists (posters, pictures)
 * stay in Main, shared with the levels.
 */
final class Posters extends Main.Part {
    Posters(Main m) {
        super(m);
    }

    final Set<ImageView> loading = new HashSet<>();    // Dropped posters are garbage that the tiny Java heap (~5 MB) rarely collects, and until then their pixels
    // (native) and GPU textures stay. One concurrent GC a second after scrolling stops (~33 ms on a pool thread,
    // 0.1 ms pause) frees them, and new posters reuse the textures. Measured by hand: native 37 → 24 MB.
    final Runnable collect = () -> m.pool.execute(Runtime.getRuntime()::gc);    static final int DIM = 0xff606060;    // A Pic with its corners' radius and how far in.
    Pic pic(float radius, int percent) {
        return new Pic(radius, percent);
    }

    // A picture of the page's level (corners radius, < 0: a circle): the poster window loads and drops it like Home's
    // posters (updateWindow).
    ImageView picture(String url, List<ImageView> pics, float radius) {
        ImageView v = new Pic(radius, 0);
        v.setTag(url);
        pics.add(v);
        return v;
    }

    // A picture with round corners (radius px; < 0: a circle) and, on a Continue tile, how far in (percent, a bar along
    // its bottom). Software drawing paints both into the bitmap once when it loads (bake(), on the pool); the GPU clips
    // to the outline and draws the bar's two rects. Not loaded: a gray stand-in in the same shape.
    // Over a film's backdrop (shaded) corners painted the ground's color would show as dark squares: software drawing
    // fills the round shape with the picture instead (a bitmap shader: about a plain draw's cost, no new memory).
    final class Pic extends ImageView {
        final float radius;
        int percent; // an episode's still: set again when its mark changes (episodeRow)
        boolean shaded; // software drawing, over a backdrop: the shape is filled with the picture each frame
        boolean onRow; // in a row that turns white on focus (an episode's still): corners never painted, shaded too
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        Bitmap filled; // the bitmap fill's shader is of

        Pic(float radius, int percent) {
            super(m);
            this.radius = radius;
            this.percent = percent;
            setScaleType(ScaleType.CENTER_CROP);
            setImageDrawable(m.empty);
            if (!m.soft) {
                setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View v, Outline o) {
                        if (radius < 0) o.setOval(0, 0, v.getWidth(), v.getHeight());
                        else o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
                    }
                });
                setClipToOutline(true);
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            boolean blank = getDrawable() == m.empty;
            float r = radius < 0 ? getWidth() / 2f : radius;
            if (blank) {
                c.drawRoundRect(0, 0, getWidth(), getHeight(), r, r, m.gray);
            } else if (m.soft && shaded && getDrawable() instanceof BitmapDrawable d && d.getBitmap() != null) {
                if (filled != d.getBitmap()) { // the picture at its shown size (fit()): no matrix
                    filled = d.getBitmap();
                    fill.setShader(new BitmapShader(filled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                }
                c.drawRoundRect(0, 0, getWidth(), getHeight(), r, r, fill);
            } else {
                super.onDraw(c);
            }
            if (blank || !m.soft) bar(c, getWidth(), getHeight(), percent, m.dp); // software: in the bitmap
        }

        // Software drawing: the bar, then (not over a backdrop) the corners painted the ground's color, into the
        // picture (made its shown size by fit(), so mutable) once.
        void bake(Bitmap b, boolean corners) {
            Canvas c = new Canvas(b);
            bar(c, b.getWidth(), b.getHeight(), percent, m.dp);
            if (!corners) return;
            Path p = new Path();
            if (radius < 0) p.addOval(0, 0, b.getWidth(), b.getHeight(), Path.Direction.CW);
            else p.addRoundRect(0, 0, b.getWidth(), b.getHeight(), radius, radius, Path.Direction.CW);
            p.setFillType(Path.FillType.INVERSE_WINDING);
            Paint ground = new Paint(Paint.ANTI_ALIAS_FLAG);
            ground.setColor(Main.SURFACE);
            c.drawPath(p, ground);
        }
    }

    // How far in: two rects along the bottom, a dark track and the white part watched.
    // The bar's paint, one a thread (the UI thread draws with it, two pool threads may bake at once): a tile with a
    // bar made a Paint on every frame it was drawn.
    private static final ThreadLocal<Paint> BAR = ThreadLocal.withInitial(Paint::new);

    static void bar(Canvas c, int w, int h, int percent, float dp) {
        if (percent <= 0) return;
        Paint p = BAR.get();
        p.setColor(0x80000000);
        c.drawRect(0, h - 4 * dp, w, h, p);
        p.setColor(Color.WHITE);
        c.drawRect(0, h - 4 * dp, w * Math.min(percent, 100) / 100f, h, p);
    }

    // Load the pictures of the level shown near the viewport (Layout.near), drop the rest: Home's posters, or the
    // page's top level's. 160 checks per scroll frame cost well under 1 ms. Only the level shown: views not shown
    // report (0, 0) on screen, so all 160 of Home's would load under a film page (+42 MB).
    void updateWindow() {
        if (m.stopped) return;
        boolean dropped = false;
        for (ImageView p : m.page == null ? m.posters : m.pictures) {
            boolean near = near(p);
            if (near && p.getDrawable() == m.empty && p.getTag() != null && !loading.contains(p)) loadPicture(p);
            else if (!near && p.getDrawable() != m.empty) {
                p.setImageDrawable(m.empty);
                dropped = true;
            }
        }
        if (dropped) collectSoon();
    }

    // A level going under (Home under a film page too) keeps only its pictures on the screen (all: none).
    void trim(List<ImageView> pics, boolean all) {
        var screen = m.getResources().getDisplayMetrics();
        for (ImageView p : pics) {
            p.getLocationOnScreen(m.at);
            if (all || !Layout.onScreen(m.at[0], m.at[1], p.getWidth(), p.getHeight(), screen.widthPixels,
                screen.heightPixels)) p.setImageDrawable(m.empty);
        }
        collectSoon();
    }

    // The dropped posters' memory goes a second after the last drop, not at some later GC (collect).
    void collectSoon() {
        m.ui.removeCallbacks(collect);
        m.ui.postDelayed(collect, 1000);
    }

    boolean near(ImageView p) {
        p.getLocationOnScreen(m.at);
        var screen = m.getResources().getDisplayMetrics();
        return Layout.near(m.at[0], m.at[1], p.getWidth(), p.getHeight(), screen.widthPixels, screen.heightPixels);
    }

    void loadPicture(ImageView p) {
        loading.add(p);
        String url = (String) p.getTag();
        int level = m.shown, w = m.soft ? p.getWidth() : 0, h = m.soft ? p.getHeight() : 0;
        boolean shaded = m.page != null && !m.pageBackdropPath.isEmpty() // a film's pictures, over its backdrop
            || p instanceof Pic pic && pic.onRow;
        m.async(() -> {
            Bitmap b = null;
            try {
                if (level == m.shown) { // else its level went away
                    b = fit(decode(read(url), !m.soft, url.startsWith(Main.TILE) ? 2 : 1), w, h, false);
                    if (b != null && m.soft && p instanceof Pic pic) pic.bake(b, !shaded);
                }
            } catch (IOException e) {
                if (e instanceof Status st && (st.code == 404 || st.code == 410)) { // gone: gray, never asked again
                    Log.w(Main.TAG, "picture " + url + " is gone");
                    m.ui.post(() -> {
                        loading.remove(p);
                        p.setTag(null);
                    });
                    return;
                }
                Log.w(Main.TAG, "picture " + url, e); // stays gray until it's near again
            }
            Bitmap done = b;
            m.ui.post(() -> {
                loading.remove(p);
                // A view not shown (Home under a page, a level under another) can't tell if it's near; showing it
                // again reloads what is.
                if (m.stopped || !p.isAttachedToWindow() || !near(p)) {
                    if (done != null) done.recycle(); // never shown: its pixels (native) go now, not at some later GC
                    return;
                }
                if (done == null) {
                    if (level != m.shown) loadPicture(p); // skipped, but its level is back: Back came before this ran
                    return;
                }
                if (m.startedAt > 0) {
                    Log.i(Main.TAG, "first poster " + (System.currentTimeMillis() - m.startedAt) + " ms after start");
                    m.startedAt = 0;
                }
                if (p instanceof Pic pic) pic.shaded = shaded;
                p.setImageBitmap(done);
            });
        });
    }

    // Software drawing scales a picture on every frame that draws it, and dims the backdrop on every frame (the GPU
    // does both for free): there each picture is made the size it is shown, once, on the pool. 0 × 0: as it is.
    // dim: a film's backdrop, also faded to the ground: on the left under the info (to 72 % of the width) and at the
    // bottom (to 46 % of the height), as the design's two gradients.
    static Bitmap fit(Bitmap b, int w, int h, boolean dim) {
        if (b == null || w <= 0 || h <= 0 || !dim && b.getWidth() == w && b.getHeight() == h) return b;
        float[] crop = Layout.crop(b.getWidth(), b.getHeight(), w, h);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(out);
        c.save();
        c.translate(crop[1], crop[2]);
        c.scale(crop[0], crop[0]);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        if (dim) p.setColorFilter(new PorterDuffColorFilter(DIM, PorterDuff.Mode.MULTIPLY));
        c.drawBitmap(b, 0, 0, p);
        c.restore();
        b.recycle(); // never shown
        if (dim) {
            int clear = Main.SURFACE & 0xffffff;
            // dithered: into RGB_565 a fade steps a level at once (a hard edge ~40 dp in, where the picture's few %
            // first round up)
            Paint fade = new Paint(Paint.DITHER_FLAG);
            fade.setShader(new LinearGradient(0, 0, w * 0.72f, 0, new int[] {Main.SURFACE, 0xe0000000 | clear, clear},
                new float[] {0, 0.32f / 0.72f, 1}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w * 0.72f, h, fade);
            fade.setShader(new LinearGradient(0, h, 0, h * 0.54f, Main.SURFACE, clear, Shader.TileMode.CLAMP));
            c.drawRect(0, h * 0.54f, w, h, fade);
        }
        return out;
    }

    // GPU drawing: HARDWARE, the picture only in the GPU (the decoder uploads it off the UI thread); RGB_565 kept a
    // CPU copy too (measured with PowerVR's own count, /proc/pvr/memtrack_stats: Home 70-73 → 55-57 MB, the film
    // page 100-102 → 58-61, same CPU and frames). Software drawing can't draw those: RGB_565, half of ARGB_8888
    // (posters and backdrops have no transparency), made their shown size by fit().
    // sample: 2 decodes at half the size (a Continue tile's w780 backdrop). Software: mutable, so Pic.bake() can
    // paint into a picture already its shown size (fit() returns it as it is).
    static Bitmap decode(byte[] data, boolean gpu, int sample) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = gpu ? Bitmap.Config.HARDWARE : Bitmap.Config.RGB_565;
        o.inMutable = !gpu;
        o.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }
}
