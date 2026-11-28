package local.lampanative;

import static local.lampanative.Net.*;

import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * Your <year>: the tile after History at the end of Continue browsing (late November to January, Cub.yearShown), and
 * its page: the worker's /mine/year as a few lines (hours, films, episodes, the longest run, the top genre and
 * director) and a row of posters (Loved this year, else the most watched).
 */
final class Year extends Main.Part {
    Year(Main m) {
        super(m);
    }

    static final String YEAR = "year"; // the tile's tag
    View tile;

    View tile(TextView rowTitle, int year) {
        int w = m.dp(195), h = m.dp(110);
        TextView picture = m.ux.text(String.valueOf(year), 40, 0xffdddddd);
        picture.setGravity(Gravity.CENTER);
        picture.setBackground(m.ux.shape(12 * m.dp, 0x14ffffff, 0x26ffffff));
        View card = m.ux.tile(picture, w, h, 12 * m.dp, "Your " + year, "Your year in films and series");
        card.setTag(YEAR);
        card.setOnFocusChangeListener((v, focused) -> {
            if (focused) m.scroll.scrollTo(0, rowTitle.getTop());
        });
        card.setOnClickListener(v -> openYear(v, year));
        tile = card;
        return card;
    }

    // Asked each time it opens (~3 KB; the first time of a year up to ~10 s while the worker asks TMDB for genres).
    void openYear(View from, int year) {
        m.openPage(from);
        ScrollView sv = new ScrollView(m);
        sv.setSmoothScrollingEnabled(false);
        LinearLayout column = new LinearLayout(m);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(m.dp(60) - m.halo, m.dp(36), m.dp(60), m.dp(60)); // the posters' row takes the halo back
        sv.addView(column);
        TextView head = m.ux.text("Your " + year, 24, Color.WHITE);
        head.setTypeface(m.medium);
        head.setPadding(m.halo, 0, 0, m.dp(12));
        column.addView(head);
        Cub.Account a = m.account();
        TextView note = m.ux.text(a == null ? "Sign in to CUB in Settings: your year comes from your CUB account." : "Counting…",
            18, 0xffdddddd);
        note.setPadding(m.halo, m.dp(16), 0, 0);
        column.addView(note);
        List<ImageView> pics = new ArrayList<>();
        m.pushLevel(sv, "", pics, false);
        if (a == null) return;
        int tz = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000;
        m.load(sv, "year", () -> {
            Answer r = send(withCub(open(Home.worker + "/mine/year?y=" + year + "&tz=" + tz, 30000), a), null, null);
            if (r.code() != 200) throw new Status(r.code());
            return Cub.year(r.body());
        }, y -> show(column, note, y, pics), () -> note.setText("Can't count your year. Back, then open it again."));
    }

    void show(LinearLayout column, TextView note, Cub.Year y, List<ImageView> pics) {
        if (y.started() == 0 && y.episodes() == 0) {
            note.setText("Nothing played in " + y.year() + " yet.");
            return;
        }
        column.removeView(note);
        TextView big = m.ux.text(y.hours() + (y.hours() == 1 ? " hour" : " hours") + " · " + y.days()
            + (y.days() == 1 ? " day" : " days"), 36, Color.WHITE);
        big.setTypeface(m.medium);
        big.setPadding(m.halo, m.dp(8), 0, m.dp(8));
        column.addView(big);
        for (String line : Cub.yearLines(y)) {
            TextView t = m.ux.text(line, 20, 0xffdddddd);
            t.setPadding(m.halo, m.dp(4), 0, m.dp(4));
            column.addView(t);
        }
        TextView small = m.ux.text(Cub.yearNote(y), 15, 0xff9a9a9a);
        small.setPadding(m.halo, m.dp(10), 0, 0);
        if (!y.posters().isEmpty()) {
            LinearLayout row = m.filmPage.personRow(column, new Home.Row(y.posterTitle(), y.posters()), pics, false);
            if (row.getChildCount() > 0) row.getChildAt(0).requestFocus();
        }
        column.addView(small);
    }

    // The tile's year today (0: none): Cub.yearShown.
    static int shown() {
        return Cub.yearShown(LocalDate.now());
    }
}
