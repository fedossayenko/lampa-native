package local.lampanative;

import static local.lampanative.Net.*;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.CharacterStyle;
import android.text.style.ReplacementSpan;
import android.util.StateSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.function.Supplier;

/**
 * The app's widgets, made in code: texts, buttons, list rows, side sheets, poster tiles and the spans of a source's chips.
 * Not a screen: every screen asks it, so it holds no state of its own (the screen's metrics are Main's).
 */
final class Ui {
    final Main m;

    Ui(Main m) {
        this.m = m;
    }

    // The card a Home picture is on: picture → its frame (with the badges) → the halo → the card.
    static View cardOf(ImageView picture) {
        return (View) picture.getParent().getParent().getParent();
    }

    static boolean inside(View v, ViewGroup group) {
        for (View p = v; p != null; p = p.getParent() instanceof View parent ? parent : null) if (p == group) return true;
        return false;
    }

    // A row title and a strip under it that scrolls sideways by whole cards; returns the strip's line for the cards.
    // The strip starts a halo left of the title (its parent's padding is a halo less), so the pictures line up with
    // the title and the first card's halo isn't cut by the parent's padding.
    LinearLayout strip(LinearLayout parent, TextView title, int stride) {
        title.setPadding(m.halo, m.dp(16), 0, m.dp(8) - m.halo); // the halo's room above the pictures
        title.setTypeface(m.medium);
        parent.addView(title);
        HorizontalScrollView strip = new HorizontalScrollView(m) {
            // Every scroll the row makes for focus comes through here, so it lands on whole cards (LayoutTest).
            // A scroll in the focus listener instead would get overridden by this one.
            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) {
                return Layout.alignedScroll(getScrollX(), getWidth(), r.left, r.right, stride) - getScrollX();
            }
        };
        strip.setHorizontalScrollBarEnabled(false);
        strip.setSmoothScrollingEnabled(false); // jump: one redraw per press instead of a 15-frame animation
        LinearLayout line = line();
        strip.addView(line);
        parent.addView(strip);
        return line;
    }

    // A horizontal line of focusables (a row's cards, a film's buttons) that Left and Right never leave: at its ends
    // they stop, instead of jumping to the nearest card further left or right in another row, on the screen or off
    // it in a row scrolled sideways (the row "goes on" in the next one).
    LinearLayout line() {
        return new LinearLayout(m) {
            @Override
            public View focusSearch(View focused, int direction) {
                View next = super.focusSearch(focused, direction);
                boolean sideways = direction == FOCUS_LEFT || direction == FOCUS_RIGHT;
                return sideways && (next == null || !inside(next, this)) ? focused : next;
            }
        };
    }

    // A poster's badge: the text on its round background, padded.
    TextView chip(TextView t, int background) {
        t.setBackground(shape(6 * m.dp, background, 0));
        t.setPadding(m.dp(6), m.dp(2), m.dp(6), m.dp(2));
        return t;
    }

    // Where a badge sits: in a corner, 6 dp in (clear of the picture's round corner).
    FrameLayout.LayoutParams badge(int gravity) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, gravity);
        int in = m.dp(6);
        lp.setMargins(in, in, in, in);
        return lp;
    }

    // A card: a picture (w × h, corners radius; < 0: a circle) in its focus halo, a name and a gray line under it, up
    // to two lines each. The halo is a ring drawn by the card only while focused (the old frame sat inside the
    // picture, over the badges); the card reaches a halo past the picture on each side, inside the gap.
    View tile(View picture, int w, int h, float radius, String name, String sub) {
        LinearLayout card = new LinearLayout(m);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setFocusable(true);
        FrameLayout ring = new FrameLayout(m);
        GradientDrawable on = shape(radius < 0 ? 0 : radius + m.halo, 0, Color.WHITE);
        on.setStroke(m.dp(3), Color.WHITE);
        if (radius < 0) on.setShape(GradientDrawable.OVAL);
        StateListDrawable states = new StateListDrawable();
        states.addState(Main.FOCUSED, on);
        // Over the picture, not under it: software drawing paints a picture's corners the ground's color, and a corner
        // square reaches past a 10-12 dp curve into the ring (dark notches in it). Over it, the ring
        // hides them; in the 3 dp gap they are the ground's color anyway. A margin, not padding: a FrameLayout's
        // foreground is drawn inside its padding.
        ring.setForeground(states);
        ring.setDuplicateParentStateEnabled(true);
        FrameLayout.LayoutParams in = new FrameLayout.LayoutParams(w, h);
        in.setMargins(m.halo, m.halo, m.halo, m.halo);
        ring.addView(picture, in);
        card.addView(ring, w + 2 * m.halo, h + 2 * m.halo);
        TextView n = text(name, 15, Color.WHITE);
        n.setTextColor(new ColorStateList(new int[][] {Main.FOCUSED, {}}, new int[] {Color.WHITE, 0xffdddddd}));
        n.setDuplicateParentStateEnabled(true);
        n.setMaxLines(2);
        n.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams under = new LinearLayout.LayoutParams(w, -2);
        under.setMargins(m.halo, m.dp(4), m.halo, 0); // 10 dp under the picture
        card.addView(n, under);
        TextView s = text(sub, 13, 0xff9a9a9a);
        s.setMaxLines(2);
        s.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams sub2 = new LinearLayout.LayoutParams(w, -2);
        sub2.setMargins(m.halo, 0, m.halo, 0);
        card.addView(s, sub2);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w + 2 * m.halo, -2);
        lp.setMargins(0, 0, m.gap - 2 * m.halo, 0); // the same stride as before: picture + gap
        card.setLayoutParams(lp);
        return card;
    }

    // The level a content column is on, which is what alive() and the loaders take: the scroll view that pushList,
    // showFilm, rowsPage and tabPage put it in. The one place that knows that.
    static View levelOf(View content) {
        return (View) content.getParent();
    }

    // A one-character button (★, ⋯): a 48 dp circle.
    TextView round(TextView b) {
        b.setSingleLine(); // sets its minimum to one line: the 48 dp after it
        b.setMinHeight(m.dp(48));
        b.setTextSize(22);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(m.dp(48));
        return b;
    }

    LinearLayout vertical() {
        LinearLayout l = new LinearLayout(m);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(0, 0, 0, m.dp(36));
        return l;
    }

    // Rows built as focus nears the end of the list: count now, 5 more when one of the last 3 gets focus, until next
    // gives null. A list that isn't scrolled builds nothing more (a row is ~1.5-2 ms on the stick; 89 at once froze
    // a frame for 121 ms).
    void addRows(LinearLayout list, Supplier<View> next, int count) {
        for (int i = 0; i < count; i++) {
            View r = next.get();
            if (r == null) return;
            View.OnFocusChangeListener own = r.getOnFocusChangeListener(); // the row's own (a genre's: scroll)
            r.setOnFocusChangeListener((v, focused) -> {
                if (own != null) own.onFocusChange(v, focused);
                if (focused && list.indexOfChild(v) >= list.getChildCount() - 3) addRows(list, next, 5);
            });
            list.addView(r);
        }
    }

    static final String DETAIL = "detail";

    void chip(SpannableStringBuilder line, String b) {
        int start = line.length();
        line.append(b);
        line.setSpan(new Chip(m.dp, b, Sources.badgeColor(b)), start, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        line.append("  ");
    }

    // A text color that follows its view's focus (a list row turns white under focus): a span can't take a
    // ColorStateList, but the paint it gets carries the view's state.
    static final class Tint extends CharacterStyle {
        final int color, focused;

        Tint(int color, int focused) {
            this.color = color;
            this.focused = focused;
        }

        @Override
        public void updateDrawState(TextPaint tp) {
            tp.setColor(StateSet.stateSetMatches(Main.FOCUSED, tp.drawableState) ? focused : color);
        }
    }

    // A source's badge (4K, DV, REMUX…): white text on a 6 dp round chip of its color. It draws its own label: in a
    // line cut with "…" the text it is handed has its letters swapped for U+FFFC (drawn as "OBJ").
    static final class Chip extends ReplacementSpan {
        final float dp; // the screen's density
        final String label;
        final int color;

        Chip(float dp, String label, int color) {
            this.dp = dp;
            this.label = label;
            this.color = color;
        }

        @Override
        public int getSize(Paint p, CharSequence t, int start, int end, Paint.FontMetricsInt fm) {
            return Math.round(p.measureText(label) + 14 * dp);
        }

        @Override
        public void draw(Canvas c, CharSequence t, int start, int end, float x, int top, int y, int bottom, Paint p) {
            int was = p.getColor();
            p.setColor(color);
            c.drawRoundRect(x, top, x + p.measureText(label) + 14 * dp, bottom, 6 * dp, 6 * dp, p);
            p.setColor(Color.WHITE);
            c.drawText(label, x + 7 * dp, y, p);
            p.setColor(was);
        }
    }

    // A section's title on a page (Search's): 22 sp medium, 12 dp over what it heads.
    TextView heading(String title, int top) {
        TextView t = text(title, 22, Color.WHITE);
        t.setTypeface(m.medium);
        t.setPadding(0, top, 0, m.dp(12));
        return t;
    }

    // A keyboard key, `wide` keys wide.
    TextView key(String label, int wide, View.OnClickListener action) {
        TextView k = button(label, action);
        int size = m.dp(52), high = m.dp(46), space = m.dp(4); // 46: the tab bar over them
        k.setBackground(glass(12 * m.dp, 0x22ffffff, 0)); // a rounded square, no edge
        k.setTypeface(Typeface.DEFAULT);
        k.setMinHeight(0);
        k.setPadding(0, 0, 0, 0);
        k.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size * wide + (wide - 1) * space, high);
        lp.setMargins(0, 0, space, space);
        k.setLayoutParams(lp);
        return k;
    }

    // A group the arrows never leave (a side sheet, the grade bar's pills): at its edges they stop.
    LinearLayout trapped() {
        return new LinearLayout(m) {
            @Override
            public View focusSearch(View focused, int direction) {
                View next = super.focusSearch(focused, direction);
                return next != null && inside(next, this) ? next : focused;
            }
        };
    }

    // A side sheet (⋯, Settings, Audio languages): 392 dp on the right, its title, an optional gray line, then its
    // rows (sheetRow, section, divider). closed: runs when it goes.
    LinearLayout sheet(String title, String sub, Runnable closed) {
        LinearLayout panel = trapped();
        panel.setOrientation(LinearLayout.VERTICAL);
        int p12 = m.dp(12);
        panel.setPadding(p12, m.dp(24), p12, p12);
        panel.setBackground(shape(24 * m.dp, 0xff2a2d30, 0x26ffffff));
        LinearLayout list = new LinearLayout(m);
        list.setOrientation(LinearLayout.VERTICAL);
        TextView head = text(title, 24, Color.WHITE);
        head.setTypeface(m.medium);
        head.setPadding(p12, 0, p12, sub == null ? p12 : 0);
        list.addView(head);
        if (sub != null) {
            TextView s = text(sub, 15, 0xff9a9a9a);
            s.setPadding(p12, m.dp(2), p12, p12);
            list.addView(s);
        }
        ScrollView sv = new ScrollView(m);
        sv.setSmoothScrollingEnabled(false);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(list);
        panel.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        int margin = m.dp(16);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(m.dp(392), -1, Gravity.END);
        lp.setMargins(margin, margin, margin, margin);
        m.showOverlay(panel, lp, closed);
        return list;
    }

    // A sheet's line at its foot, over a rule (Settings: what the focused row does).
    TextView footer(LinearLayout list) {
        LinearLayout panel = (LinearLayout) list.getParent().getParent();
        View rule = new View(m);
        rule.setBackgroundColor(0x1affffff);
        panel.addView(rule, -1, Math.max(1, (int) m.dp));
        TextView t = text("", 15, 0xffdddddd);
        t.setPadding(m.dp(12), m.dp(12), m.dp(12), m.dp(4));
        panel.addView(t);
        return t;
    }

    // A sheet's row: a list row, tighter, its detail up to 3 lines (the sheet is narrow).
    LinearLayout sheetRow(CharSequence title, CharSequence detail, CharSequence end,
                                  View.OnClickListener action) {
        LinearLayout r = row(title, detail, end, action);
        int p12 = m.dp(12), p8 = m.dp(8);
        r.setPadding(p12, p8, p12, p8);
        ((LinearLayout.LayoutParams) r.getLayoutParams()).bottomMargin = m.dp(4);
        if (detail != null) {
            TextView d = (TextView) ((LinearLayout) (end != null ? r.getChildAt(0) : r)).getChildAt(1);
            d.setSingleLine(false);
            d.setMaxLines(3);
            d.setPadding(0, m.dp(2), 0, 0);
        }
        return r;
    }

    // A sheet's group heading (Settings' Display, Account, Playback).
    TextView section(String title) {
        TextView t = text(title, 15, 0xff9a9a9a);
        t.setTypeface(m.medium);
        t.setPadding(m.dp(12), m.dp(16), m.dp(12), m.dp(4));
        return t;
    }

    View divider() {
        View d = new View(m);
        d.setBackgroundColor(0x1affffff);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, (int) m.dp));
        lp.setMargins(m.dp(12), m.dp(4), m.dp(12), m.dp(8));
        d.setLayoutParams(lp);
        return d;
    }

    // The ✓ at a sheet row's end: the current mark, a chosen language ("" none); green, darker on a focused row.
    static CharSequence check(boolean on) {
        if (!on) return "";
        SpannableStringBuilder s = new SpannableStringBuilder("✓");
        s.setSpan(new Tint(Home.GREEN, 0xff0a7d3e), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(-2, -2);
    }

    // A button after another in a row: a gap before it.
    LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginStart(m.dp(12));
        return lp;
    }

    // A focusable text button (Watch, Search, Settings, the keyboard's keys): glass, a flat round shape, white at 13 %
    // with a 1 dp edge; white with dark text under focus, so it can't be missed (a lighter gray was).
    TextView button(String label, View.OnClickListener action) {
        TextView b = text(label, 20, Color.WHITE);
        b.setTypeface(m.medium);
        b.setPadding(m.dp(24), 0, m.dp(24), 0);
        b.setMinHeight(m.dp(48));
        b.setGravity(Gravity.CENTER);
        b.setBackground(glass(m.pill, 0x22ffffff, 0x26ffffff));
        b.setTextColor(new ColorStateList(new int[][] {Main.FOCUSED, {}}, new int[] {0xff111111, Color.WHITE}));
        b.setFocusable(true);
        b.setOnClickListener(action);
        return b;
    }

    // One of the tabs: the screen shown lit (here), the others plain; white under focus.
    TextView tab(String label, boolean here, View.OnClickListener action) {
        TextView t = text(label, 18, Color.WHITE);
        if (here) t.setTypeface(m.medium);
        t.setPadding(m.dp(22), 0, m.dp(22), 0);
        t.setMinHeight(m.dp(38));
        t.setGravity(Gravity.CENTER);
        t.setBackground(glass(m.pill, here ? 0x2effffff : 0, 0));
        t.setTextColor(new ColorStateList(new int[][] {Main.FOCUSED, {}}, new int[] {0xff111111, here ? Color.WHITE : 0xffdddddd}));
        t.setFocusable(true);
        t.setOnClickListener(action);
        return t;
    }

    // A focusable list row: a title line, an optional detail line and an optional label at its end (a source's
    // ⚡ Cached); OK runs the action. Resting it draws nothing; focused, a white round row with dark text.
    LinearLayout row(CharSequence title, CharSequence detail, View.OnClickListener action) {
        return row(title, detail, null, action);
    }

    LinearLayout row(CharSequence title, CharSequence detail, CharSequence end, View.OnClickListener action) {
        LinearLayout r = new LinearLayout(m);
        r.setFocusable(true);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(Main.FOCUSED, shape(14 * m.dp, Color.WHITE, 0));
        bg.addState(new int[] {android.R.attr.state_selected}, shape(14 * m.dp, 0x22ffffff, 0)); // a season shown
        r.setBackground(bg);
        r.setPadding(m.dp(18), m.dp(10), m.dp(18), m.dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = m.dp(6);
        r.setLayoutParams(lp);
        LinearLayout lines = r; // the title and detail: the row itself, or its first column when there's an end label
        if (end != null) {
            r.setGravity(Gravity.CENTER_VERTICAL);
            lines = new LinearLayout(m);
            lines.setDuplicateParentStateEnabled(true);
            r.addView(lines, new LinearLayout.LayoutParams(0, -2, 1));
        }
        lines.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(title, 18, Color.WHITE);
        t.setTextColor(new ColorStateList(new int[][] {Main.FOCUSED, {}}, new int[] {0xff111111, Color.WHITE}));
        t.setDuplicateParentStateEnabled(true);
        t.setSingleLine();
        t.setEllipsize(TextUtils.TruncateAt.END);
        lines.addView(t);
        if (detail != null) {
            TextView d = text(detail, 15, 0xffb0b0b0);
            d.setTextColor(new ColorStateList(new int[][] {Main.FOCUSED, {}}, new int[] {0xff4a4a4a, 0xffb0b0b0}));
            d.setDuplicateParentStateEnabled(true);
            d.setSingleLine();
            d.setEllipsize(TextUtils.TruncateAt.END);
            d.setPadding(0, m.dp(6), 0, 0);
            d.setTag(DETAIL); // a source row's, set again when its audio is read
            lines.addView(d);
        }
        if (end != null) {
            TextView e = text(end, 18, Color.WHITE);
            e.setTextColor(new ColorStateList(new int[][] {Main.FOCUSED, {}}, new int[] {0xff111111, 0xffdddddd}));
            e.setDuplicateParentStateEnabled(true);
            e.setPadding(m.dp(16), 0, 0, 0);
            r.addView(e);
        }
        r.setOnClickListener(action);
        return r;
    }

    // A flat round shape: fill, and a 1 dp edge when edge isn't 0.
    GradientDrawable shape(float radius, int fill, int edge) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(radius);
        d.setColor(fill);
        if (edge != 0) d.setStroke(Math.max(1, (int) m.dp), edge);
        return d;
    }

    // A button's background: the shape in glass, white under focus.
    Drawable glass(float radius, int fill, int edge) {
        StateListDrawable bg = new StateListDrawable();
        bg.addState(Main.FOCUSED, shape(radius, Color.WHITE, 0));
        bg.addState(new int[0], shape(radius, fill, edge));
        return bg;
    }

    TextView text(CharSequence s, int sp, int color) {
        TextView t = new TextView(m);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }
}
