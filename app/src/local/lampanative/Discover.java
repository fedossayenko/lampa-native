package local.lampanative;

import static local.lampanative.Net.*;

import android.graphics.Color;
import android.graphics.Typeface;
import android.util.Log;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

// A genre with filters: its name, the filters as pills (OK: a sheet of choices, or a
// switch), what they show in words, then the posters 5 a line, 20 more as focus nears the end (the worker's
// /discover; what you watched hidden here). Surprise me opens one shown, at random.
final class Discover extends Main.Part {
    final String genre;
    Person.Filters f = Person.Filters.START;
    boolean hide = true;
    final List<ImageView> pics = new ArrayList<>();
    final List<Home.Card> shown = new ArrayList<>();
    final LinearLayout grid = new LinearLayout(m), pills = m.line();
    final TextView words = m.text("", 15, 0xff9a9a9a);
    TextView type, years, country, score, sort, watched;
    View level;
    LinearLayout last; // the grid's line being filled
    int page, pages, asked; // pages read and how many there are; asked: an answer for older filters is dropped
    boolean loading;

    Discover(Main m, String genre) {
        super(m);
        this.genre = genre;
        if (genre.equals("Kids")) f = new Person.Filters(true, "", "", "", "best"); // TMDB's Kids: series only
    }

    void show() {
        ScrollView sv = new ScrollView(m);
        sv.setSmoothScrollingEnabled(false);
        sv.setTag("genre " + genre); // backTo
        LinearLayout column = new LinearLayout(m);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = m.dp(60);
        column.setPadding(pad - m.halo, m.dp(36), pad, pad); // the posters' halos (strip())
        TextView head = m.text(genre, 36, Color.WHITE);
        head.setTypeface(m.medium);
        head.setPadding(m.halo, 0, 0, 0);
        column.addView(head);
        pills.setPadding(m.halo, m.dp(14), 0, 0);
        type = pill("", v -> set(new Person.Filters(!f.series(), f.years(), f.country(), f.score(), f.sort())));
        years = pill("", v -> choose("Years", Person.Filters.YEARS, f.years(),
            x -> set(new Person.Filters(f.series(), x, f.country(), f.score(), f.sort()))));
        String[][] countries = new String[Person.Filters.COUNTRIES.length][];
        for (int i = 0; i < countries.length; i++) {
            String code = Person.Filters.COUNTRIES[i];
            countries[i] = new String[] {code, Person.Filters.country(code)};
        }
        country = pill("", v -> choose("Country", countries, f.country(),
            x -> set(new Person.Filters(f.series(), f.years(), x, f.score(), f.sort()))));
        score = pill("", v -> choose("Score", Person.Filters.SCORES, f.score(),
            x -> set(new Person.Filters(f.series(), f.years(), f.country(), x, f.sort()))));
        sort = pill("", v -> choose("Sort", Person.Filters.SORTS, f.sort(),
            x -> set(new Person.Filters(f.series(), f.years(), f.country(), f.score(), x))));
        watched = pill("", v -> {
            hide = !hide;
            set(f);
        });
        pill("Surprise me", v -> {
            if (!shown.isEmpty()) m.showFilm(shown.get((int) (Math.random() * shown.size())), null);
        });
        HorizontalScrollView bar = new HorizontalScrollView(m); // seven pills are wider than the screen
        bar.setHorizontalScrollBarEnabled(false);
        bar.setSmoothScrollingEnabled(false);
        bar.addView(pills);
        column.addView(bar);
        words.setPadding(m.halo + m.dp(4), m.dp(10), 0, m.dp(6));
        column.addView(words);
        grid.setOrientation(LinearLayout.VERTICAL);
        column.addView(grid);
        sv.addView(column);
        level = sv;
        m.pushLevel(sv, "", pics, false);
        set(f);
        pills.getChildAt(0).requestFocus();
    }

    private TextView pill(String label, View.OnClickListener action) {
        TextView p = m.button(label, action);
        p.setTextSize(16);
        p.setTypeface(Typeface.DEFAULT);
        p.setSingleLine(true);
        p.setMinHeight(m.dp(40));
        p.setPadding(m.dp(16), 0, m.dp(16), 0);
        LinearLayout.LayoutParams lp = Main.wrap();
        if (pills.getChildCount() > 0) lp.setMarginStart(m.dp(8));
        pills.addView(p, lp);
        return p;
    }

    // A filter's choices in a sheet, the one set ✓.
    private void choose(String title, String[][] choices, String now, Consumer<String> picked) {
        LinearLayout list = m.sheet(title, null, null);
        for (String[] c : choices) {
            list.addView(m.sheetRow(c[1], null, Main.check(c[0].equals(now)), v -> {
                m.closeOverlay();
                picked.accept(c[0]);
            }));
        }
    }

    // New filters: the pills say them, the posters again from page 1.
    void set(Person.Filters to) {
        f = to;
        type.setText(f.series() ? "Series" : "Films");
        years.setText("Years: " + Person.Filters.label(Person.Filters.YEARS, f.years()));
        country.setText("Country: " + Person.Filters.country(f.country()));
        score.setText("Score: " + Person.Filters.label(Person.Filters.SCORES, f.score()));
        sort.setText("Sort: " + Person.Filters.label(Person.Filters.SORTS, f.sort()));
        watched.setText("Watched: " + (hide ? "Hidden" : "Shown"));
        words.setText(f.line(hide));
        asked++;
        page = 0;
        pages = 1;
        loading = false;
        shown.clear();
        grid.removeAllViews();
        pics.clear();
        last = null;
        m.collectSoon();
        more();
    }

    // The next page of posters, unless one is coming.
    void more() {
        if (loading || page >= pages) return;
        loading = true;
        int ask = asked, n = page + 1;
        String url = f.url(genre, n);
        m.async(() -> {
            try {
                Person.Found found = Person.Found.parse(new String(read(url), StandardCharsets.UTF_8));
                m.ui.post(() -> {
                    if (ask != asked || !m.alive(level)) return;
                    loading = false;
                    page = n;
                    pages = found.pages();
                    add(found.cards());
                    if (grid.getChildCount() < 3) more(); // what you watched hid most: a screenful more
                    if (shown.isEmpty() && page >= pages) words.setText(f.line(hide) + ". Nothing found.");
                });
            } catch (Exception e) {
                Log.w(Main.TAG, "discover " + url, e);
                boolean none = e instanceof Status st && st.code == 404; // no such genre for films (Kids)
                m.ui.post(() -> {
                    if (ask != asked) return;
                    loading = false;
                    if (shown.isEmpty()) words.setText(none ? "TMDB has no " + (f.series() ? "series" : "films")
                        + " in " + genre + "." : "TMDB didn't answer. Change a filter to try again.");
                });
            }
        });
    }

    void add(List<Home.Card> cards) {
        for (Home.Card c : cards) {
            if (hide && m.cub.watched(Timeline.card(c))) continue;
            if (last == null || last.getChildCount() == 5) {
                last = m.line();
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.topMargin = m.dp(8);
                grid.addView(last, lp);
            }
            View card = m.filmCard(c, pics);
            LinearLayout in = last;
            card.setOnFocusChangeListener((v, focused) -> { // near the end: the next page
                if (focused && grid.indexOfChild(in) >= grid.getChildCount() - 2) more();
            });
            last.addView(card);
            shown.add(c);
        }
    }
}
