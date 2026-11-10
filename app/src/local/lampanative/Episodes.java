package local.lampanative;

import static local.lampanative.Net.*;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.PrimitiveIterator;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.json.JSONException;

// Watch on a series: seasons and episodes on one screen. The seasons on the left with
// what of each is watched; the focused one's episodes on the right, each with its mark after its number (one
// line, so a mark coming later moves nothing). Opens on the episode to go on with (to; null: the first).
// ponytail: no episode titles or stills (the board's switch): the worker would have to send each season's
final class Episodes extends Main.Part {
    final Film f;
    final Home.Card c;
    final int[] to;
    final LinearLayout seasons = m.vertical(), episodes = m.vertical();
    final ScrollView right = new ScrollView(m);
    final TextView sub = m.text("", 16, 0xff9a9a9a);
    View level, chosen; // the season row whose episodes show
    Film.Season shown;
    List<Film.Episode> about = List.of(); // the shown season's names, runtimes and stills (/season), once in
    final List<ImageView> stills = new ArrayList<>(); // its rows' (the picture window's of this level)
    Supplier<View> next; // its episodes' rows not built yet

    Episodes(Main m, Film f, Home.Card c, int[] to) {
        super(m);
        this.f = f;
        this.c = c;
        this.to = to;
    }

    void show() {
        LinearLayout screen = new LinearLayout(m) {
            // Left from an episode: its season, not the one beside it. Right from a season: its episode to go
            // on with, else its first.
            @Override
            public View focusSearch(View focused, int direction) {
                if (direction == FOCUS_LEFT && Main.inside(focused, episodes)) return chosen;
                if (direction == FOCUS_RIGHT && Main.inside(focused, seasons)) {
                    focus(to != null && to[0] == shown.number() ? to[1] - 1 : 0, next);
                    return focused; // focus() moves it, now or once the row is built
                }
                return super.focusSearch(focused, direction);
            }
        };
        level = screen;
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(m.dp(60), m.dp(36), m.dp(60), 0);
        TextView head = m.text(f.name(), 24, Color.WHITE);
        head.setTypeface(m.medium);
        screen.addView(head);
        sub.setPadding(0, m.dp(2), 0, 0);
        sub.setTag((Runnable) () -> sub.setText("Season " + shown.number() + " · " + line(shown))); // remark()
        screen.addView(sub);
        LinearLayout panes = new LinearLayout(m);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
        lp.topMargin = m.dp(18);
        screen.addView(panes, lp);
        ScrollView left = new ScrollView(m);
        left.setSmoothScrollingEnabled(false);
        left.setVerticalScrollBarEnabled(false);
        left.addView(seasons);
        panes.addView(left, new LinearLayout.LayoutParams(m.dp(216), -1));
        right.setSmoothScrollingEnabled(false);
        right.setVerticalScrollBarEnabled(false);
        right.addView(episodes);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -1, 1);
        rp.setMarginStart(m.dp(24));
        panes.addView(right, rp);
        Film.Season first = f.episodes().get(0);
        View firstRow = null;
        for (Film.Season s : f.episodes()) {
            LinearLayout r = m.row("Season " + s.number(), "", v -> focus(0, next)); // OK: its first episode
            TextView d = (TextView) r.getChildAt(1);
            d.setSingleLine(false); // "13 episodes · 7 watched · watching E8" in 216 dp
            d.setMaxLines(2);
            Main.marked(r, 1, () -> line(s));
            r.setOnFocusChangeListener((v, focused) -> {
                if (focused) episodesOf(s, r);
            });
            seasons.addView(r);
            if (firstRow == null || to != null && to[0] == s.number()) {
                first = s;
                firstRow = r;
            }
        }
        m.pushLevel(screen, "", stills, false);
        episodesOf(first, firstRow);
        Supplier<View> built = next;
        screen.post(() -> focus(to != null ? to[1] - 1 : 0, built));
    }

    private String line(Film.Season s) {
        return Timeline.seasonLine(s.episodes(), e -> m.marks.get(Timeline.key(f, s.number(), e)));
    }

    // A season's episodes on the right: 10 rows (the ~9 the screen shows), the rest as focus nears them.
    void episodesOf(Film.Season s, View row) {
        if (s == shown) return;
        if (chosen != null) chosen.setSelected(false);
        chosen = row;
        row.setSelected(true); // lit while focus is in its episodes
        shown = s;
        Main.remark(sub);
        m.trim(stills, true);
        stills.clear();
        about = List.of();
        episodes.removeAllViews();
        right.scrollTo(0, 0);
        m.async(() -> { // names, runtimes, stills: "Episode 5" rows until then (or for good, offline)
            try {
                List<Film.Episode> got = Film.season(new String(read(Film.seasonUrl(c, s.number())),
                    StandardCharsets.UTF_8));
                m.ui.post(() -> {
                    if (shown != s || !m.alive(level)) return;
                    about = got;
                    Main.remark(episodes);
                    m.updateWindow();
                });
            } catch (IOException | JSONException e) {
                Log.w(Main.TAG, "season " + c.id() + "/" + s.number(), e);
            }
        });
        PrimitiveIterator.OfInt e = IntStream.rangeClosed(1, s.episodes()).iterator();
        next = () -> e.hasNext() ? episodeRow(s, e.nextInt()) : null;
        m.addRows(episodes, next, 10);
    }

    // The design's episode row: its still, "8 · Better Call Saul", "47m · Watching · 19 min left", a line of its
    // overview.
    private LinearLayout episodeRow(Film.Season s, int episode) {
        LinearLayout r = m.row("", "", "", v -> m.showSources(f, c, s.number(), episode, null, false));
        r.removeViewAt(1); // no end label: the still goes first instead
        Main.Pic still = (Main.Pic) m.picture(null, stills, 10 * m.dp);
        still.onRow = true; // dark corners on the white focused row else (software drawing)
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(m.dp(150), m.dp(84));
        sp.setMarginEnd(m.dp(16));
        r.addView(still, 0, sp);
        LinearLayout lines = (LinearLayout) r.getChildAt(1);
        TextView name = (TextView) lines.getChildAt(0), line = (TextView) lines.getChildAt(1);
        line.setPadding(0, m.dp(2), 0, 0);
        TextView plot = m.text("", 15, 0xff9a9a9a);
        plot.setTextColor(new ColorStateList(new int[][] {Main.FOCUSED, {}}, new int[] {0xff4a4a4a, 0xff9a9a9a}));
        plot.setDuplicateParentStateEnabled(true);
        plot.setSingleLine();
        plot.setEllipsize(TextUtils.TruncateAt.END);
        plot.setPadding(0, m.dp(4), 0, 0);
        lines.addView(plot);
        Runnable set = () -> { // now, and again by remark(): /season came, or the marks
            Film.Episode a = about.stream().filter(x -> x.number() == episode).findFirst().orElse(null);
            name.setText(Film.title(episode, a));
            Timeline.Point p = m.marks.get(Timeline.key(f, s.number(), episode));
            SpannableStringBuilder d = new SpannableStringBuilder(a != null && a.runtime() > 0 ? a.runtime() + "m" : "");
            String airs = a == null ? "" : Film.airs(a.airDate(), LocalDate.now()); // not out yet: when it is
            if (!airs.isEmpty()) d.append(d.length() > 0 ? " · " : "").append(airs);
            String mark = Timeline.episodeMark(p);
            if (!mark.isEmpty()) {
                if (d.length() > 0) d.append(" · ");
                int start = d.length();
                d.append(mark);
                boolean watched = Timeline.state(p) == Timeline.State.WATCHED;
                d.setSpan(watched ? new Main.Tint(Home.GREEN, 0xff0a7d3e) : new Main.Tint(0xffb0b0b0, 0xff4a4a4a), start,
                    d.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            line.setText(d);
            plot.setText(a == null ? "" : a.overview());
            if (a != null && !a.still().isEmpty() && still.getTag() == null) still.setTag(Main.STILL + a.still());
            int in = Timeline.state(p) == Timeline.State.WATCHING ? p.percent() : 0; // the design's bar on the still
            if (in != still.percent) {
                still.percent = in;
                // software drawing baked the old bar into the picture: load it again (a 300 px still)
                if (m.soft && still.getDrawable() != m.empty) still.setImageDrawable(m.empty);
                still.invalidate();
                if (m.soft) m.ui.post(m::updateWindow);
            }
        };
        set.run();
        r.setTag(set);
        return r;
    }

    // Focus on an episode's row: rows built 5 a frame up to it (a season resumed deep: 64 at once froze a frame).
    // from: the season's rows it was asked for; another season shown meanwhile, it stops.
    void focus(int index, Supplier<View> from) {
        if (!m.alive(level) || from != next) return; // Back, or another season, meanwhile
        if (index < episodes.getChildCount()) {
            episodes.getChildAt(index).requestFocus();
            return;
        }
        int had = episodes.getChildCount();
        m.addRows(episodes, from, 5);
        if (episodes.getChildCount() > had) episodes.post(() -> focus(index, from));
    }
}
