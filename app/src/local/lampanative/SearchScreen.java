package local.lampanative;

import static local.lampanative.Net.*;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class SearchScreen extends Main.Part {
    SearchScreen(Main m) {
        super(m);
    }

    final LinearLayout screen = new LinearLayout(m) {
        // Up from the keyboard's top row: the tabs (the genres beside it start a little higher, so Android's
        // nearest one up is a genre)
        @Override
        public View focusSearch(View focused, int direction) {
            View next = super.focusSearch(focused, direction);
            if (direction == FOCUS_UP && Main.inside(focused, SearchScreen.this.keys) && next != null
                && Main.inside(next, SearchScreen.this.results)) return (View) tabs.getChildAt(0).getTag();
            return next;
        }
    }, keys = new LinearLayout(m), results = new LinearLayout(m);
    FrameLayout tabs; // the tab bar over it
    final TextView query = m.text("", 26, Color.WHITE);
    final List<ImageView> pics = new ArrayList<>();
    final Map<String, String> sure = new HashMap<>(); // voice results: how sure each is ("97%"), by Timeline.card
    int asks; // voice presses here: only the latest one's answers are shown
    final Runnable soon = this::find; // 0.3 s after the last key
    String q = "", shownFor; // the query typed; the one the results are for (null: none yet)
    String inFlight; // the query asked and not answered yet: "dark" → "dark " meanwhile asks nothing more
    boolean ru = m.settings().getBoolean("search_ru", false);

    void show() {
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(0, m.dp(24), 0, 0); // Home's bar's place
        screen.addView(tabs = m.tabBar("Search"));
        LinearLayout body = new LinearLayout(m);
        body.setPadding(m.dp(60), m.dp(16), 0, 0);
        screen.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout left = new LinearLayout(m);
        left.setOrientation(LinearLayout.VERTICAL);
        query.setBackground(m.shape(m.pill, 0x14ffffff, 0x26ffffff));
        query.setPadding(m.dp(22), 0, m.dp(22), 0);
        query.setGravity(Gravity.CENTER_VERTICAL);
        query.setSingleLine();
        query.setEllipsize(TextUtils.TruncateAt.START); // a long query keeps its end in view
        left.addView(query, m.dp(336), m.dp(56)); // the keyboard's width: the results don't move as it grows
        TextView hint = m.text("Hold the voice button and say a title", 15, 0xff9a9a9a);
        hint.setPadding(m.dp(22), m.dp(8), 0, m.dp(12));
        left.addView(hint);
        keys.setOrientation(LinearLayout.VERTICAL);
        left.addView(keys);
        layout();
        LinearLayout actions = new LinearLayout(m);
        actions.addView(m.key("Space", 2, v -> set(Search.type(q, " "))));
        actions.addView(m.key("⌫", 1, v -> set(Search.erase(q))));
        actions.addView(m.key(ru ? "EN" : "RU", 1, v -> {
            ru = !ru;
            m.settings().edit().putBoolean("search_ru", ru).apply();
            ((TextView) v).setText(ru ? "EN" : "RU");
            layout();
        }));
        actions.addView(m.key("Clear", 2, v -> set("")));
        left.addView(actions);
        body.addView(left);
        ScrollView sv = new ScrollView(m);
        sv.setSmoothScrollingEnabled(false);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setPadding(m.gap, 0, m.dp(40), m.dp(36)); // 3 cards (with their gaps): 498 of 508 dp
        sv.addView(results);
        body.addView(sv, new LinearLayout.LayoutParams(0, -1, 1));
        screen.setTag(this); // the voice button finds the open search by it
        m.pushLevel(screen, "", pics, false);
        keys.post(() -> ((LinearLayout) keys.getChildAt(0)).getChildAt(0).requestFocus());
        set("");
    }

    // 6 keys a row of the layout chosen.
    void layout() {
        keys.removeAllViews();
        String all = ru ? Search.RU : Search.EN;
        LinearLayout row = null;
        for (int i = 0; i < all.length(); i++) {
            if (i % 6 == 0) keys.addView(row = new LinearLayout(m));
            String k = all.substring(i, i + 1);
            row.addView(m.key(k, 1, v -> set(Search.type(q, k)))); // lower case, as typed
        }
    }

    // The query changed: shown at once, searched 0.3 s after the last key (at once when cleared).
    void set(String typed) {
        q = typed;
        sure.clear();
        query.setText(q.isEmpty() ? "Type a title" : q + "▏");
        query.setTextColor(q.isEmpty() ? 0xff9a9a9a : Color.WHITE);
        m.ui.removeCallbacks(soon);
        m.ui.postDelayed(soon, q.isEmpty() ? 0 : 300);
    }

    void find() {
        String asked = q.trim();
        // not on top (left, or a film or Settings over it) when the pause ended; or the same search ("dark" →
        // "dark "): the results stay
        // ponytail: ≡ in the 0.3 s pause leaves the older results until the next key; ask again on Back if seen
        if (m.pageTop != screen || !Search.asks(asked, shownFor, inFlight)) return;
        if (asked.isEmpty()) {
            show(asked, null);
            return;
        }
        inFlight = asked;
        m.pool.execute(() -> {
            List<Home.Card> found;
            try {
                found = Search.parse(new String(read(Search.url(asked)), StandardCharsets.UTF_8));
            } catch (Exception e) {
                Log.w(Main.TAG, "search", e);
                found = null;
            }
            List<Home.Card> f = found;
            m.ui.post(() -> {
                if (asked.equals(inFlight)) inFlight = null;
                if (m.pageTop != screen || !asked.equals(q.trim())) return; // not on top, or typed on meanwhile
                if (f != null) {
                    show(asked, f);
                } else {
                    clear();
                    results.addView(m.text("Can't search now. Try again.", 18, 0xff9a9a9a));
                }
            });
        });
    }

    // The old results go; their posters still queued are skipped (shown).
    void clear() {
        results.removeAllViews();
        pics.clear(); // garbage now, collected below
        m.shown++;
        shownFor = null;
        m.collectSoon();
    }

    // The results for `asked`, 3 cards a row; null: nothing typed, the last searches. Focus that was on the old
    // ones goes to the first new one (or the keyboard).
    void show(String asked, List<Home.Card> found) {
        boolean focused = results.findFocus() != null;
        clear();
        shownFor = asked;
        if (found == null) {
            List<String> recent = Search.recent(m.settings().getString("searches", ""));
            if (!recent.isEmpty()) results.addView(m.heading("Recent searches", 0));
            for (String r : recent) results.addView(m.row(r, null, v -> set(r)));
            results.addView(m.heading("Genres", recent.isEmpty() ? 0 : m.dp(24)));
            LinearLayout line = null;
            for (int i = 0; i < Search.GENRES.size(); i++) { // 3 a row: 3 keys 3 wide fill the 508 dp
                if (i % 3 == 0) results.addView(line = new LinearLayout(m));
                String g = Search.GENRES.get(i);
                line.addView(m.key(g, 3, v -> m.showGenre(g)));
            }
        } else if (found.isEmpty()) {
            results.addView(m.text("Nothing found", 18, 0xff9a9a9a));
        } else {
            results.addView(m.heading("Top results", 0));
        }
        LinearLayout row = null;
        for (int i = 0; found != null && i < found.size(); i++) {
            if (i % 3 == 0) {
                results.addView(row = new LinearLayout(m));
                row.setPadding(0, 0, 0, m.gap);
            }
            Home.Card c = found.get(i);
            View card = m.cardView(c, m.picture(Main.POSTER + c.poster(), pics, 10 * m.dp), sure.get(Timeline.card(c)));
            card.setOnFocusChangeListener((v, on) -> m.prefetch(c, on)); // OK shows it all at once
            card.setOnClickListener(v -> {
                m.ui.removeCallbacks(soon); // a search still pending would rebuild these under the film
                m.settings().edit().putString("searches",
                    Search.remember(m.settings().getString("searches", ""), asked)).apply(); // what was found
                m.showFilm(c, null);
            });
            row.addView(card);
        }
        if (focused && !results.requestFocus()) keys.requestFocus(); // the first card; none: the first key
    }

    // Voice: what is going on, gray in the query's place (a key typed then starts from nothing).
    void say(String what) {
        m.ui.removeCallbacks(soon);
        q = "";
        query.setText(what);
        query.setTextColor(0xff9a9a9a);
    }

    // A press: its number (answers to an older one are dropped), the meter at 0 until speech comes.
    int listening() {
        say(Voice.meter(0));
        clear();
        results.addView(m.text("Speak while you hold the button: a title, what happens in it, or what you would"
            + " like to watch. English, Russian or Ukrainian.", 18, 0xff9a9a9a));
        return ++asks;
    }

    // The microphone's level while the button is held: it hears you.
    void level(int ask, int level) {
        if (ask == asks && m.recording) query.setText(Voice.meter(level));
    }

    // Waiting for the worker or Claude Code: the system's small spinner and what is going on.
    void thinking(String what) {
        clear();
        LinearLayout row = new LinearLayout(m);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ProgressBar spin = new ProgressBar(m); // indeterminate: turns while shown, gone with the row
        spin.setIndeterminateTintList(ColorStateList.valueOf(0xff21d07a));
        row.addView(spin, m.dp(36), m.dp(36));
        TextView t = m.text(what, 20, 0xff9a9a9a);
        t.setPadding(m.dp(14), 0, 0, 0);
        row.addView(t);
        results.addView(row);
    }

    // The request as heard: the query (typing goes on from it), then finding.
    void heard(int ask, String text) {
        if (m.pageTop != screen || ask != asks) return;
        q = text;
        query.setText(text);
        query.setTextColor(Color.WHITE);
        thinking("Finding…");
    }

    // The films found, each with how sure it is the one; a recommendation leaves out what was graded or marked
    // watched. text null: not heard (no network, the worker down); "" nothing said; a null: not searched.
    void found(int ask, String text, Voice.Answer a) {
        if (m.pageTop != screen || ask != asks) return;
        if (text == null || text.isEmpty()) {
            say(text == null ? "Can't hear now. Try again." : "Didn't catch that. Hold the button and speak.");
            clear();
            return;
        }
        if (a == null) {
            clear();
            results.addView(m.text("Can't search now. Try again.", 18, 0xff9a9a9a));
            return;
        }
        Log.i(Main.TAG, "voice: " + a.intent() + " by " + a.by() + ", " + a.results().size() + " found");
        sure.clear();
        List<Home.Card> cards = new ArrayList<>();
        for (Voice.Found f : a.results()) {
            String key = Timeline.card(f.card());
            if (a.intent().equals("recommend") && (m.cubGrades.containsKey(key)
                || "viewed".equals(m.cubMarks.get(key)) || "thrown".equals(m.cubMarks.get(key)))) continue;
            String badge = Voice.badge(f.sure());
            if (badge != null) sure.put(key, badge);
            cards.add(f.card());
        }
        show(text, cards);
        results.requestFocus(); // the first film (none: stays where it was)
        if (a.open() && !cards.isEmpty()) m.showFilm(cards.get(0), null);
    }
}
