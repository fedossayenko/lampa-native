package local.lampanative;

import static local.lampanative.Net.*;

import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import org.json.JSONArray;

/**
 * History: the tile that ends Continue browsing, the page of what you played here and in Lampa (newest first), and the sheet
 * on a row that marks it unwatched or removes it, with the worker call behind each.
 */
final class History extends Main.Part {
    History(Main m) {
        super(m);
    }

    static final String HISTORY = "history";    View historyTile;

    LinearLayout historyList;    List<Cub.Played> historyShown = List.of();

    // Continue browsing's last tile: History.
    View historyTile(TextView rowTitle) {
        int w = m.dp(195), h = m.dp(110);
        TextView picture = m.ux.text("↺", 40, 0xff9a9a9a);
        picture.setGravity(Gravity.CENTER);
        picture.setBackground(m.ux.shape(12 * m.dp, 0x14ffffff, 0x26ffffff));
        View card = m.ux.tile(picture, w, h, 12 * m.dp, "History", "Played here and in Lampa");
        card.setTag(HISTORY);
        card.setOnFocusChangeListener((v, focused) -> {
            if (focused) m.scroll.scrollTo(0, rowTitle.getTop());
        });
        card.setOnClickListener(this::openHistory);
        return card;
    }

    // History: what was played here and in Lampa, newest first, by day (the worker's
    // /mine/history, asked each time it opens: ~16 KB). OK opens the film; ≡ marks it unwatched or removes it.
    // ponytail: text rows, no posters (Coming up's look); add thumbnails if the rows read poorly
    void openHistory(View from) {
        m.openPage(from);
        historyList = m.pushList("History");
        historyList.addView(m.ux.text("Played here and in Lampa, newest first · ≡ marks one unwatched or removes it", 16,
            0xff9a9a9a));
        fillHistory(historyList, 0);
    }

    // History's rows (again after a change: focus on the row at that place).
    void fillHistory(LinearLayout list, int focus) {
        list.removeViews(2, list.getChildCount() - 2); // the title and its line stay
        Cub.Account a = m.account();
        TextView note = m.ux.text(a == null ? "Sign in to CUB in Settings: History comes from your CUB account." : "Loading…",
            18, 0xffdddddd);
        note.setPadding(0, m.dp(16), 0, 0);
        list.addView(note);
        if (a == null) return;
        View level = Ui.levelOf(list);
        int tz = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000;
        m.load(level, "history", () -> {
            Answer r = send(withCub(open(Home.worker + "/mine/history?tz=" + tz, 20000), a), null, null);
            if (r.code() != 200) throw new Status(r.code());
            return Cub.history(r.body());
        }, items -> showHistory(list, note, items, focus), () -> note.setText("Can't load History. Back, then open it again."));
    }

    void showHistory(LinearLayout list, TextView note, List<Cub.Played> items, int focus) {
        historyShown = items;
        if (items.isEmpty()) {
            note.setText("Nothing played yet.");
            return;
        }
        list.removeView(note);
        LocalDate today = LocalDate.now();
        ZoneId zone = ZoneId.systemDefault();
        LinearLayout day = null;
        String at = null;
        List<View> rows = new ArrayList<>();
        for (Cub.Played p : items) {
            String[] d = Cub.played(LocalDateTime.ofInstant(Instant.ofEpochMilli(p.t()), zone), today);
            if (!d[0].equals(at)) { // a group: its name on the left, its rows on the right (Coming up's layout)
                at = d[0];
                LinearLayout group = new LinearLayout(m);
                TextView name = m.ux.text(d[0], 18, Color.WHITE);
                name.setTypeface(m.medium);
                name.setPadding(0, m.dp(10), 0, 0);
                group.addView(name, m.dp(120), -2);
                day = new LinearLayout(m);
                day.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 1);
                right.setMarginStart(m.dp(20));
                group.addView(day, right);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = m.dp(14);
                list.addView(group, lp);
            }
            SpannableStringBuilder end = new SpannableStringBuilder(d[1]);
            end.setSpan(new Ui.Tint(0xff9a9a9a, 0xff4a4a4a), 0, end.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            LinearLayout r = m.ux.row(p.card().name(), historyLine(p), end, v -> m.filmPage.showFilm(p.card(), null));
            r.setTag(p); // ≡ on it: historySheet
            r.setPadding(m.dp(18), m.dp(8), m.dp(18), m.dp(8));
            ((LinearLayout.LayoutParams) r.getLayoutParams()).bottomMargin = m.dp(4);
            day.addView(r);
            rows.add(r);
        }
        rows.get(Math.min(focus, rows.size() - 1)).requestFocus();
    }

    // A History row's gray line: "S1 E6 and E7 · Watched · ★ Good · in Lampa", "Film · 1h 5m left".
    CharSequence historyLine(Cub.Played p) {
        SpannableStringBuilder s = new SpannableStringBuilder(!p.what().isEmpty() ? p.what()
            : p.card().type().equals("tv") ? "Series" : "Film");
        String left = Timeline.posterLeft(String.valueOf(p.left()));
        if (p.left() == 0) {
            s.append(" · ");
            int from = s.length();
            s.append("Watched");
            s.setSpan(new Ui.Tint(Home.GREEN, 0xff0a7d3e), from, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else if (left != null) {
            s.append(" · ").append(left);
        }
        String[] g = Cub.posterGrade(m.cub.grades().get(Timeline.card(p.card())));
        if (g != null) {
            s.append(" · ");
            int from = s.length();
            s.append("★ ").append(g[1]);
            s.setSpan(new Ui.Tint(Color.parseColor(g[2]), 0xff111111), from, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (p.lampa()) s.append(" · in Lampa");
        return s;
    }

    // ≡ on a History row: its page, Mark unwatched (its points back to the start; Lampa takes them too), Remove (out
    // of CUB's history, so Lampa's too). Both are CUB's: the worker writes them.
    void historySheet(Cub.Played p) {
        boolean series = p.card().type().equals("tv");
        LinearLayout list = m.ux.sheet(p.card().name(), historyLine(p).toString(), null);
        list.addView(m.ux.sheetRow(series ? "Series page" : "Film page", null, null, v -> {
            m.closeOverlay();
            m.filmPage.showFilm(p.card(), null);
        }));
        list.addView(m.ux.divider());
        int at = historyShown.indexOf(p);
        if (!p.hashes().isEmpty()) {
            String body = new JSONArray(p.hashes()).toString();
            list.addView(m.ux.sheetRow("Mark unwatched", (p.what().isEmpty() ? "Back to the start" : p.what() + " back to the "
                + "start") + ", here and in Lampa", null, v -> historyChange("/mine/unwatch",
                ("{\"hashes\":" + body + "}").getBytes(StandardCharsets.UTF_8), at, p.hashes())));
        }
        list.addView(m.ux.sheetRow("Remove from history", "Lampa's history loses it too", null,
            v -> historyChange("/mine/unhistory/" + p.card().type() + "/" + p.card().id(), new byte[0], at, List.of())));
    }

    // A History change sent (204: CUB or the timeline took it), then the page and Home's rows again.
    // unwatched: the keys of the points taken back to the start: the marks kept here (and DDD's answers of this
    // session, which the worker's answers never overwrite) forget them, or the episodes would show Watched until the
    // app is closed.
    void historyChange(String path, byte[] body, int at, List<String> unwatched) {
        Cub.Account a = m.account();
        LinearLayout list = historyList;
        Toast.makeText(m, "Saving…", Toast.LENGTH_SHORT).show();
        m.load(null, "history change", () -> post(path, a, body, "application/json"), done -> {
            if (done != 204) {
                Toast.makeText(m, "CUB didn't take it. Try again.", Toast.LENGTH_LONG).show();
                return;
            }
            m.closeOverlay();
            Timeline.forget(m.marks, m.played, unwatched);
            m.filmPage.remark();
            if (list != null && m.alive(Ui.levelOf(list))) fillHistory(list, Math.max(0, at));
            m.loadMine(); // Continue browsing, the posters' badges
        }, () -> { });
    }
}
