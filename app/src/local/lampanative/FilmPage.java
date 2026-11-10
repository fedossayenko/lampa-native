package local.lampanative;

import static local.lampanative.Net.*;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The film page and the pages like it: a film over its backdrop with its info, cast and collection, an actor's or a genre's
 * page, the Watch, Continue, mark and grade buttons, and the marks (/mine's points, DDD's answers) and grades they show and
 * send. The marks themselves, and the levels and the overlay they are drawn into, stay in Main: every screen reads them.
 */
final class FilmPage extends Main.Part {
    FilmPage(Main m) {
        super(m);
    }

    static final String PHOTO = "https://image.tmdb.org/t/p/w185"; // the cast's 185×278 px photos, cut to a circle

    // The film page; sources: after process death, the source list {season, episode} to open again over it.
    void openFilm(Home.Card c, View card, int[] sources) {
        m.openPage(card);
        m.prefetched = Film.url(c); // in the disk cache now: resting on this card again after Back reads nothing
        showFilm(c, sources);
    }

    // A film over its backdrop (no poster, the backdrop shows through on the right), the
    // info, and under it (the page scrolls) the cast and the film's collection. Drawn at once from the card, again
    // from the worker's /card. A film of the collection goes on top as one more level; Back comes back here.
    void showFilm(Home.Card c, int[] sources) {
        long t = System.currentTimeMillis();
        ScrollView sv = new ScrollView(m);
        sv.setSmoothScrollingEnabled(false);
        sv.setTag(c); // the film level of this film (backTo, where())
        LinearLayout column = new LinearLayout(m);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = m.dp(60);
        column.setPadding(pad - m.halo, m.dp(84), pad, pad); // the info takes the halo back (strip())
        sv.addView(column);
        List<ImageView> pics = new ArrayList<>();
        LinearLayout info = new LinearLayout(m);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(m.halo, 0, 0, 0);
        column.addView(info);
        fill(column, info, Film.of(c), c, pics, false);
        m.pushLevel(sv, c.backdrop(), pics, sources == null);
        m.restoring = sources != null;

        m.load(null, "film " + c.id(), () -> Film.parse(new String(read(Film.url(c)), StandardCharsets.UTF_8)), f -> {
            m.restoring = false;
            if (!m.alive(sv)) return;
            // A film's first sources into the disk cache while the page is read, so Watch shows them at
            // once (a series waits for its episode). Before the page's photos and posters on the pool.
            if (c.type().equals("movie") && !f.imdb().isEmpty() && m.pageTop == sv
                && !Sources.refusedLately(m.refusedAt, System.currentTimeMillis())) { // else a 502 for nothing
                String url = Sources.url(f.imdb(), 0, 0, m.langs(), 0);
                m.preload = m.pool.submit(() -> {
                    cache(url);
                    Log.i(Main.TAG, "sources preloaded " + (System.currentTimeMillis() - t) + " ms after OK");
                    return null;
                });
            }
            fill(column, info, f, c, pics, true);
            loadMarks(f);
            Log.i(Main.TAG, "film details " + (System.currentTimeMillis() - t) + " ms after OK");
            if (sources != null && m.pageTop == sv) reopen(f, c, sources[0], sources[1], null, false);
        }, () -> m.restoring = false); // the page keeps what the card gave it
        // ponytail: a restore whose /card fails stays without its backdrop; Back and in again shows it
    }

    // The top level's backdrop: from the network the first time, from the disk cache when Back returns to it.
    void showBackdrop(long t) {
        View top = m.pageTop;
        String path = m.pageBackdropPath;
        if (path.isEmpty()) return;
        int sw = m.getResources().getDisplayMetrics().widthPixels, sh = m.getResources().getDisplayMetrics().heightPixels;
        m.load(null, "backdrop " + path, () -> {
            // Dimmed, and faded to the ground on the left and at the bottom, in one pass when it loads (a few
            // ms on the pool; per frame: nothing). Software: at the screen's size; GPU: at its own (the GPU
            // scales it for free), then into the GPU only.
            Bitmap raw = Posters.decode(read(Main.BACKDROP + path), false, 1);
            if (raw == null) throw new IOException("not a picture");
            Bitmap b = Posters.fit(raw, m.soft ? sw : raw.getWidth(), m.soft ? sh : raw.getHeight(), true);
            if (!m.soft) {
                Bitmap g = b.copy(Bitmap.Config.HARDWARE, false);
                b.recycle();
                if (g == null) throw new IOException("no GPU copy"); // out of graphics memory: logged by load()
                b = g;
            }
            return b;
        }, done -> {
            if (m.stopped || m.pageTop != top) { // left meanwhile
                done.recycle(); // 4 MB of pixels (native) that no one will see
                return;
            }
            m.pageBackdrop.setImageBitmap(done);
            Log.i(Main.TAG, "film backdrop " + (System.currentTimeMillis() - t) + " ms after OK");
        }, () -> { });
    }

    // The film's info; then its cast and its collection (but itself) under it, their pictures into pics.
    // loaded: f is the worker's /card, not the card's name and year alone.
    void fill(LinearLayout column, LinearLayout info, Film f, Home.Card c, List<ImageView> pics,
                      boolean loaded) {
        boolean series = c.type().equals("tv");
        info.removeAllViews();
        int textW = m.dp(540);
        TextView name = m.ux.text(f.name(), 44, Color.WHITE);
        name.setTypeface(m.medium);
        name.setMaxWidth(textW);
        info.addView(name, Ui.wrap()); // a max width holds only for a wrapped one
        if (!f.original().isEmpty()) info.addView(m.ux.text(f.original(), 18, 0xff9a9a9a));
        // a film: when it ends if started now
        String ends = series ? "" : Film.endsAt(f.runtime(), LocalTime.now());
        TextView line = m.ux.text(ends.isEmpty() ? f.line() : f.line() + " · " + ends, 18, 0xffdddddd);
        line.setPadding(0, m.dp(8), 0, 0);
        line.setMaxWidth(textW);
        info.addView(line, Ui.wrap());
        // how comfortably you'd watch it in English (the learn extension's estimate; Learn fills it in)
        TextView english = m.ux.text("", 18, 0xffdddddd);
        english.setPadding(0, m.dp(4), 0, 0);
        english.setVisibility(View.GONE);
        info.addView(english, Ui.wrap());
        for (List<Film.Score> scores : List.of(f.scores(), f.seasons())) {
            if (scores.isEmpty()) continue;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = m.dp(16);
            info.addView(scoreRow(scores), lp);
        }
        LinearLayout buttons = m.ux.line();
        boolean watchable = f.watchable(series); // a series with no episodes yet: none
        // Settings → Play picks: Best cached: a film's ▶ Play starts the release picked
        // for it (pickSource, under the buttons), Sources lists them all
        boolean picks = !series && m.picks();
        TextView plays = m.ux.text("", 15, 0xffdddddd);
        if (watchable) {
            Sources.Source[] best = new Sources.Source[1]; // ▶ Play's release, once the first sources are read
            TextView watch = m.ux.button(picks ? "▶  Play" : "▶  Watch", v -> {
                if (series) new Episodes(m, f, c, Timeline.resume(f.episodes(), (s, e) -> m.marks.get(Timeline.key(f, s, e)))).show();
                else if (best[0] != null) m.playback.play(f, c, 0, 0, best[0].hash(), best[0].fileIdx(), best[0].title(), Ui.levelOf(column));
                else m.sourcesScreen.showSources(f, c, 0, 0, null, false); // none cached, or not read yet
            });
            watch.setOnFocusChangeListener((v, focused) -> { // up from the rows: the whole top again
                if (focused) ((ScrollView) column.getParent()).scrollTo(0, 0);
            });
            View cont = continueButton(f, c, watch, column, picks);
            LinearLayout.LayoutParams before = new LinearLayout.LayoutParams(-2, -2);
            before.setMarginEnd(m.dp(12)); // its own gap: hidden, Watch starts the row
            buttons.addView(cont, before); // first, as the design: the one to press
            buttons.addView(watch);
            if (picks) buttons.addView(m.ux.button("Sources", v -> m.sourcesScreen.showSources(f, c, 0, 0, null, false)), m.ux.spaced());
            (cont.getVisibility() == View.VISIBLE ? cont : watch).requestFocus(); // marks known: Continue
            if (picks && loaded) pickSource(f, best, plays, Ui.levelOf(column));
        }
        // each film's trailer on its page, not a row of trailers
        if (!f.trailer().isEmpty()) {
            buttons.addView(m.ux.button("Trailer", v -> m.playTrailer(f.trailer())),
                buttons.getChildCount() > 0 ? m.ux.spaced() : new LinearLayout.LayoutParams(-2, -2));
        }
        // the page's top (alive() knows it), not column: given column, the status's answer was always dropped
        if (loaded && m.cub.learnBound() && !series && !f.imdb().isEmpty()) {
            learnButton(f, c, buttons, Ui.levelOf(column), english);
        }
        // Story so far: a series with episodes watched before the one Continue goes on with. Its tag shows it
        // once the marks are known (remark), as Continue's
        if (series && loaded && watchable) {
            Supplier<List<int[]>> last = () -> Film.before(f.episodes(),
                Timeline.continueTo(true, f, m.marks::get, last(c)), 3);
            TextView story = m.ux.button("Story so far", v -> {
                List<int[]> now = last.get();
                if (!now.isEmpty()) m.storySoFar(c, now);
            });
            Runnable mark = () -> story.setVisibility(last.get().isEmpty() ? View.GONE : View.VISIBLE);
            story.setTag(mark);
            mark.run();
            buttons.addView(story, buttons.getChildCount() > 0 ? m.ux.spaced() : new LinearLayout.LayoutParams(-2, -2));
        }
        // ⋯ and ★ need only the sign-in: a film with nothing to watch is still marked and graded (e.g.
        // The Miniaturist's "TV Movie" entry on TMDB has no IMDb id, so no sources, and had no ★ either)
        if (loaded && m.account() != null) {
            buttons.addView(gradeButton(c, f), buttons.getChildCount() > 0 ? m.ux.spaced() : new LinearLayout.LayoutParams(-2, -2));
            buttons.addView(markButton(c), m.ux.spaced());
            if (!watchable) buttons.getChildAt(0).requestFocus();
        }
        if (buttons.getChildCount() > 0) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = m.dp(20);
            info.addView(buttons, lp);
        }
        if (picks && watchable && loaded) {
            plays.setPadding(m.dp(4), m.dp(12), 0, 0);
            info.addView(plays);
        }
        if (loaded && !watchable) {
            TextView none = m.ux.text(f.imdb().isEmpty() ? "Nothing to watch: TMDB gives this one no IMDb id, so no sources."
                + (!series ? " Look for it as a series too." : "") : "No episodes out yet.", 16, 0xff9a9a9a);
            none.setPadding(0, m.dp(10), 0, 0);
            info.addView(none);
        }
        TextView overview = m.ux.text(f.overview(), 18, 0xffdddddd);
        overview.setPadding(0, m.dp(20), 0, 0);
        overview.setLineSpacing(0, 1.5f);
        overview.setMaxWidth(m.dp(480));
        overview.setMaxLines(3);
        overview.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(overview, Ui.wrap());
        TextView people = m.ux.text(f.people(), 16, 0xff9a9a9a);
        people.setPadding(0, m.dp(12), 0, 0);
        people.setMaxWidth(textW);
        people.setMaxLines(2);
        people.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(people, Ui.wrap());
        if (!f.cast().isEmpty()) {
            // w185's 185×278 cut to a 184 px circle (92 dp): software drawing makes it that size once (a third less
            // memory a photo), the GPU crops and clips it
            int w = m.dp(92);
            LinearLayout cast = m.ux.strip(column, m.ux.text("Cast", 22, Color.WHITE), w + m.gap);
            // OK: their page (showPerson). A /card from before person ids (0) only scrolls the row.
            // ponytail: a 185×278 photo replacing the 300×450 stand-in costs a layout pass per photo loaded or
            // dropped (≤ 12); a photo-sized stand-in if gfxinfo shows it while scrolling to the Cast row
            Iterator<Film.Actor> members = f.cast().iterator();
            m.ux.addRows(cast, () -> {
                if (!members.hasNext()) return null;
                Film.Actor p = members.next();
                ImageView photo = m.pix.picture(PHOTO + p.photo(), pics, -1);
                View t = m.ux.tile(photo, w, w, -1, p.name(), p.character());
                if (p.id() > 0) t.setOnClickListener(v -> showPerson(p, photo.getDrawable()));
                return t;
            }, 8); // 7.7 fit: built in one frame with the info, so no more than that
        }
        Iterator<Home.Card> parts = f.parts().iterator(); // the others: the worker leaves this one out
        if (parts.hasNext()) {
            m.ux.addRows(m.ux.strip(column, m.ux.text(f.collection(), 22, Color.WHITE), m.cardW + m.gap),
                () -> parts.hasNext() ? filmCard(parts.next(), pics) : null, 7); // 5.5 fit; Bond has 27
        }
        // More like this: TMDB's recommendations of it, without what was graded or watched already
        Iterator<Home.Card> similar = f.similar().stream().filter(x -> !m.cub.grades().containsKey(Timeline.card(x))
            && !m.cub.seen().contains(Timeline.card(x))).iterator();
        if (similar.hasNext()) {
            m.ux.addRows(m.ux.strip(column, m.ux.text("More like this", 22, Color.WHITE), m.cardW + m.gap),
                () -> similar.hasNext() ? filmCard(similar.next(), pics) : null, 7);
        }
    }

    // A film's card on a page's row (a collection, an actor's): OK opens it on top, or goes back to it when it is
    // open below.
    View filmCard(Home.Card c, List<ImageView> pics) {
        View card = m.cardView(c, m.pix.picture(Main.POSTER + c.poster(), pics, 10 * m.dp));
        card.setOnClickListener(v -> {
            if (!backTo(l -> isFilm(l, c))) showFilm(c, null);
        });
        return card;
    }

    // Back to the level already open below (a film: A → B → A; an actor) instead of one more, so the stack is never
    // deeper than the collection. False when it isn't open.
    boolean backTo(Predicate<View> level) {
        if (m.levels.stream().noneMatch(l -> level.test(l.view()))) return false;
        while (!level.test(m.pageTop)) m.popLevel(false);
        showBackdrop(System.currentTimeMillis()); // none for an actor (its backdrop is "")
        return true;
    }

    // An actor's page, one more level (OK on a cast photo): the photo, the name, born and where, what TMDB says of
    // them, then the worker's rows (Best rated, Films, Series) as Home cards. OK on one opens it on top, or goes back
    // to it when it is open below; an actor open below is gone back to the same way (backTo). Drawn at once from
    // the cast photo; the rest when /person answers. Sized so the first row fits under the top: focus on its first
    // card scrolls nothing (36 dp + the ~160 dp info + the row's title and cards ≈ 515 of the stick's 540 dp). The
    // photo is the cast row's w185 at the cast row's size: drawn 1:1, no scaling each frame in software drawing.
    // ponytail: film → actor → film → actor… stacks levels without bound (views only: a level under another keeps
    // no pictures); cap it if a deep chain shows in the heap
    void showPerson(Film.Actor a, Drawable photo) {
        if (backTo(l -> l.getTag() instanceof Film.Actor o && o.id() == a.id())) return;
        rowsPage(a, a.name(), PHOTO + a.photo(), photo, Person.url(a.id()), "OK on the photo");
    }

    // A genre's page (Search before typing: OK on a genre): its name, then the worker's rows (New, Popular, Best
    // rated films, then series) as an actor's.
    void showGenre(String genre) {
        if (backTo(l -> ("genre " + genre).equals(l.getTag()))) return;
        new Discover(m, genre).show();
    }

    // An actor's or a genre's page: tag (backTo finds it by it), the name, a photo (null: none), the worker's rows.
    void rowsPage(Object tag, String title, String photoUrl, Drawable photo, String url, String again) {
        ScrollView sv = new ScrollView(m);
        sv.setSmoothScrollingEnabled(false);
        sv.setTag(tag);
        LinearLayout column = new LinearLayout(m);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = m.dp(60);
        column.setPadding(pad - m.halo, m.dp(36), pad, pad); // the photo and name take the halo back (strip())
        sv.addView(column);
        List<ImageView> pics = new ArrayList<>();
        LinearLayout content = new LinearLayout(m);
        content.setPadding(m.halo, 0, 0, 0);
        if (photoUrl != null) {
            Posters.Pic cover = (Posters.Pic) m.pix.picture(photoUrl, pics, -1);
            cover.shaded = true; // the cast photo's bitmap, from over the film's backdrop: its corners aren't painted
            if (photo != null && photo != m.empty) cover.setImageDrawable(photo.getConstantState().newDrawable(m.getResources()));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(m.dp(92), m.dp(92));
            lp.setMarginEnd(m.dp(40));
            content.addView(cover, lp);
        }
        LinearLayout info = new LinearLayout(m);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView name = m.ux.text(title, 36, Color.WHITE);
        name.setTypeface(m.medium);
        name.setSingleLine(); // one line, the born line one, the biography 3: the info stays ~160 dp
        name.setEllipsize(TextUtils.TruncateAt.END);
        if (tag instanceof Film.Actor a && m.account() != null) { // Follow beside the name: the info stays as high
            LinearLayout top = m.ux.line();
            top.setGravity(Gravity.CENTER_VERTICAL);
            name.setMaxWidth(m.getResources().getDisplayMetrics().widthPixels * 2 / 3 - m.dp(220));
            top.addView(name, Ui.wrap());
            top.addView(followButton(a), m.ux.spaced());
            info.addView(top);
        } else {
            info.addView(name);
        }
        content.addView(info, m.getResources().getDisplayMetrics().widthPixels * 2 / 3, -2);
        column.addView(content);
        m.pushLevel(sv, "", pics, false);
        m.load(sv, "rows " + url, () -> Person.parse(new String(read(url), StandardCharsets.UTF_8)),
            p -> fillPerson(column, info, p, pics),
            () -> info.addView(m.ux.text("TMDB didn't answer. Back, and " + again + " again.", 18, 0xff9a9a9a)));
    }

    // Follow on a person's page: their coming films (directed, or a lead) go to Coming up.
    // The worker keeps it per CUB account and profile (Lampa's own Subscribe is CUB Premium).
    TextView followButton(Film.Actor a) {
        TextView b = m.ux.button("", null);
        b.setMinHeight(m.dp(44));
        b.setTextSize(18);
        Runnable label = () -> b.setText(m.cub.follows().contains(a.id()) ? "✓  Following" : "Follow");
        label.run();
        b.setOnClickListener(v -> {
            boolean on = !m.cub.follows().contains(a.id());
            Cub.Account acc = m.account();
            b.setText(on ? "Following…" : "Unfollowing…");
            m.load(null, "follow", () -> post("/mine/" + (on ? "follow/" : "unfollow/") + a.id(), acc, new byte[0], "text/plain"), done -> {
                if (done == 204) {
                    Set<Integer> f = new HashSet<>(m.cub.follows());
                    if (on) f.add(a.id());
                    else f.remove(a.id());
                    m.cub = m.cub.withFollows(f);
                    m.loadMine(); // Coming up: their films
                } else {
                    Toast.makeText(m, "Didn't save. Try again.", Toast.LENGTH_LONG).show();
                }
                label.run();
            }, () -> { });
        });
        return b;
    }

    // The actor's line and biography (3 lines: more push the first row under the screen), then the rows. A genre
    // has neither.
    void fillPerson(LinearLayout column, LinearLayout info, Person p, List<ImageView> pics) {
        if (!p.line().isEmpty() || !p.about().isEmpty()) {
            TextView line = m.ux.text(p.line(), 18, 0xffdddddd);
            line.setPadding(0, m.dp(8), 0, m.dp(8));
            line.setSingleLine();
            line.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(line);
            TextView about = m.ux.text(p.about(), 18, 0xffbbbbbb);
            about.setMaxLines(3);
            about.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(about);
        }
        fillRows(column, p.rows(), pics, true);
    }

    // A genre's rows without the films you watched (Cub.watched; the worker sends 100 a row, 20 are shown, so a row
    // stays full), or with them: the switch under the name, remembered (hidden by default).
    // An actor's or a genre's rows under the name; focus: its first card (not when the genre's switch has it).
    void fillRows(LinearLayout column, List<Home.Row> rows, List<ImageView> pics, boolean focus) {
        if (rows.isEmpty()) return;
        LinearLayout first = personRow(column, rows.get(0), pics, true);
        if (focus && first.getChildCount() > 0) first.getChildAt(0).requestFocus();
        // the rows under the screen a frame later: 21 cards in one frame would hold it ~35 ms (review); not when the
        // genre's switch drew them again meanwhile
        m.ui.post(() -> {
            if (first.isAttachedToWindow()) for (Home.Row r : rows.subList(1, rows.size())) personRow(column, r, pics, false);
        });
    }

    // Focus on a card puts its row at the top with its title, as Home's; the first row: the page's top, the name too
    // (back up, the row's title and the name stayed above the screen).
    LinearLayout personRow(LinearLayout column, Home.Row row, List<ImageView> pics, boolean first) {
        Iterator<Home.Card> cards = row.cards().iterator();
        TextView title = m.ux.text(row.title(), 22, Color.WHITE);
        LinearLayout line = m.ux.strip(column, title, m.cardW + m.gap);
        m.ux.addRows(line, () -> {
            if (!cards.hasNext()) return null;
            View card = filmCard(cards.next(), pics);
            card.setOnFocusChangeListener((v, on) -> {
                if (on) ((ScrollView) column.getParent()).scrollTo(0, first ? 0 : title.getTop());
            });
            return card;
        }, 7); // 5.5 fit
        return line;
    }

    // Lampa's rate line on one glass pill: each score in its color, its source beside it.
    // As many as fit the page's width (a long series' seasons: the first ones), none cut in half.
    View scoreRow(List<Film.Score> scores) {
        LinearLayout row = new LinearLayout(m);
        row.setBackground(m.ux.shape(m.pill, 0x14ffffff, 0x26ffffff));
        int side = m.dp(18), between = m.dp(20), beside = m.dp(6);
        row.setPadding(side, m.dp(8), side, m.dp(8));
        float room = m.getResources().getDisplayMetrics().widthPixels - 120 * m.dp - 2 * side; // the page's 60 dp sides
        for (Film.Score s : scores) {
            TextView score = m.ux.text(s.text(), 20, Home.scoreColor(s.score()));
            score.setTypeface(m.medium);
            TextView label = m.ux.text(s.label(), 13, 0xffb0b0b0);
            float w = score.getPaint().measureText(s.text()) + beside + label.getPaint().measureText(s.label());
            if (row.getChildCount() > 0) w += between;
            if (w > room) break;
            room -= w;
            LinearLayout.LayoutParams lp = Ui.wrap();
            if (row.getChildCount() > 0) lp.setMarginStart(between);
            row.addView(score, lp);
            LinearLayout.LayoutParams beside2 = Ui.wrap();
            beside2.setMarginStart(beside);
            row.addView(label, beside2);
        }
        return row;
    }

    // ▶ Play's release: the biggest TorBox has cached in your languages (Sources.best), from the first sources the
    // page preloaded (read from the disk: no second ask). None: ▶ Play opens the list. level: the film's.
    void pickSource(Film f, Sources.Source[] best, TextView plays, View level) {
        String url = Sources.url(f.imdb(), 0, 0, m.langs(), 0);
        Future<?> before = m.preload;
        m.load(level, "pick " + f.imdb(), () -> {
            try {
                if (before != null) before.get();
            } catch (Exception e) {
                // read below, from the network
            }
            return Sources.best(Sources.parse(new String(read(url), StandardCharsets.UTF_8)).streams());
        }, got -> {
            best[0] = got;
            plays.setText(got != null ? playsLine(got) : "Nothing cached in " + m.shownLangs() + ": ▶ Play lists the sources");
        }, () -> { // ▶ Play opens the list
            best[0] = null;
            plays.setText("");
        });
    }

    volatile boolean noEstimateRoute; // the extension answered 404/405 to /learn/estimate: ask the status only

    // Learn: asks the worker's /ext/learn extension (a service the user runs beside it) for the film's status once when
    // the page opens, and shows the button only when it answers. OK asks for it, or looks again while it is waiting.
    // english: the line under the film's info line for the estimate ("English: Good · 175 words to Comfortable"),
    // as the button's row has no room for it beside a long Continue
    void learnButton(Film f, Home.Card c, LinearLayout buttons, View level, TextView english) {
        Learn.State[] state = {null};
        boolean[] busy = {false}; // a request is out (disabling the button would drop its focus)
        TextView b = m.ux.button("Learn", null);
        b.setVisibility(View.GONE);
        Runnable draw = () -> {
            b.setText(Learn.label(state[0]));
            Learn.Estimate e = state[0] == null ? null : state[0].estimate();
            if (e != null) english.setText("English: " + Learn.estimateText(e));
            english.setVisibility(e == null ? View.GONE : View.VISIBLE);
        };
        b.setOnClickListener(v -> {
            boolean start = state[0] == null || state[0].askable();
            if (busy[0]) return;
            busy[0] = true;
            b.setText("Learn · …");
            m.pool.execute(() -> {
                Learn.State got = null;
                try {
                    Answer a = start ? m.ask("/ext/learn/queue", Learn.queueBody(f.imdb(), c.id(), f.name())
                        .getBytes(StandardCharsets.UTF_8)) : m.ask("/ext/learn/status?imdb=" + f.imdb(), null);
                    if (a.code() == 200) got = Learn.parse(a.body());
                } catch (IOException e) {
                    Log.w(Main.TAG, "learn", e);
                }
                Learn.State now = got;
                m.ui.post(() -> {
                    busy[0] = false;
                    if (!m.alive(level)) return;
                    if (now != null) state[0] = now.keeping(state[0]);
                    draw.run();
                });
            });
        });
        buttons.addView(b, buttons.getChildCount() > 0 ? m.ux.spaced() : new LinearLayout.LayoutParams(-2, -2));
        m.pool.execute(() -> {
            Learn.State got = null;
            try {
                // the page asks for the film's estimate (once for this page, no polling; OK looks again); it answers
                // like the status. Any other answer (an extension without the route, an error, a timeout): the plain
                // status, so the button never depends on the estimate. The extension never 404s a film, so a 404 or
                // 405 means the route is missing: later pages go straight to the status.
                Answer a = null;
                if (!noEstimateRoute) {
                    try {
                        a = m.ask("/ext/learn/estimate", Learn.queueBody(f.imdb(), c.id(), f.name())
                            .getBytes(StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        Log.w(Main.TAG, "learn estimate", e);
                    }
                }
                if (a != null && (a.code() == 404 || a.code() == 405)) noEstimateRoute = true;
                if (a == null || a.code() != 200) a = m.ask("/ext/learn/status?imdb=" + f.imdb(), null);
                if (a.code() == 200) got = Learn.parse(a.body());
            } catch (IOException e) {
                Log.w(Main.TAG, "learn status", e);
            }
            Learn.State now = got;
            if (now == null) return; // no extension, or it is down: no button
            m.ui.post(() -> {
                if (!m.alive(level)) return;
                state[0] = now.keeping(state[0]);
                draw.run();
                b.setVisibility(View.VISIBLE);
            });
        });
    }

    // "Plays [4K] [DV] [REMUX] · RU · 74.3 GB · ⚡ Cached": the picture's badges as the source rows' chips.
    CharSequence playsLine(Sources.Source s) {
        SpannableStringBuilder line = new SpannableStringBuilder("Plays  ");
        for (String b : Sources.picture(s)) m.ux.chip(line, b);
        line.append(Sources.tracks(s.languages(), m.langs())).append(" · ").append(s.size()).append(" · ");
        int start = line.length();
        line.append(Sources.BOLT + " Cached");
        line.setSpan(new Ui.Tint(Home.GREEN, Home.GREEN), start, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return line;
    }

    // Continue, beside Watch while there is somewhere to go on (marks: the worker's points, DDD's): a series to the
    // episode being watched or the next one, a film being watched. Its source last played again when it is that
    // episode (or the film); else that episode's list, and the same release (a season's torrent) plays from it
    // at once when it has that episode, else it is only focused.
    // hides: watch is ▶ Play, which Continue stands in for while it shows (both resume; a shorter row).
    View continueButton(Film f, Home.Card c, View watch, View column, boolean hides) {
        boolean series = c.type().equals("tv");
        TextView b = m.ux.button("", v -> {
            Timeline.Last last = last(c);
            int[] to = Timeline.continueTo(series, f, m.marks::get, last);
            if (to == null) return; // hidden meanwhile
            if (Timeline.replays(last, to)) {
                m.playback.play(f, c, to[0], to[1], last.hash(), last.fileIdx(), last.name(), Ui.levelOf(column));
            } else {
                reopen(f, c, to[0], to[1], last != null ? last.hash() : null, true);
            }
        });
        Runnable mark = () -> {
            int[] to = Timeline.continueTo(series, f, m.marks::get, last(c));
            boolean was = b.getVisibility() == View.VISIBLE;
            b.setVisibility(to == null ? View.GONE : View.VISIBLE);
            if (hides && to == null) watch.setVisibility(View.VISIBLE);
            if (to == null) return;
            String left = series ? null : Timeline.posterLeft(m.cub.left().get(Timeline.card(c)));
            b.setText(series ? "▶  Continue S" + to[0] + " E" + to[1] : left == null ? "▶  Continue" : "▶  Continue · " + left);
            if (!was && watch.hasFocus()) b.requestFocus(); // shown now: the one to press
            if (hides) watch.setVisibility(View.GONE);
        };
        b.setVisibility(View.GONE);
        b.setTag(mark);
        mark.run();
        return b;
    }

    // ⋯ beside Watch, a side sheet: Add to (Remove from) My list, then marks for leaving Continue browsing (
    // finish later, or not at all): Finish later, Not interested, Watched it, or none; the film's own ✓.
    // One character: the info column is half the screen and a worded button wrapped onto three lines next to
    // "Continue S1 E9". Home's rows follow.
    TextView markButton(Home.Card c) {
        return m.ux.round(m.ux.button("⋯", v -> marksSheet(c, true)));
    }

    // The ⋯ sheet: My list (withList: from the film page; ≡ on a poster has its own row), then the marks.
    void marksSheet(Home.Card c, boolean withList) {
        String now = m.cub.marks().get(Timeline.card(c)), name = Cub.markName(now);
        LinearLayout list = m.ux.sheet(c.name(), name == null ? null : "Marked: " + name, null);
        if (withList) {
            boolean listed = m.cub.list().contains(Timeline.card(c));
            list.addView(m.ux.sheetRow(listed ? "Remove from My list" : "Add to My list", "Lampa's Bookmarks, its Later too",
                null, r -> toggleList(c)));
            list.addView(m.ux.divider());
        }
        for (String[] mark : Cub.MARKS) list.addView(m.ux.sheetRow(mark[1], mark[2], Ui.check(mark[0].equals(now)), r -> setMark(c, mark[0])));
        if (now != null) list.addView(m.ux.sheetRow("No mark", "Back in Continue browsing while it's being watched",
            null, r -> setMark(c, "none")));
    }

    // Add to (Remove from) My list: Lampa's Bookmarks (its Later too when removing).
    void toggleList(Home.Card c) {
        String key = Timeline.card(c);
        boolean listed = m.cub.list().contains(key);
        postMine(c, listed ? "unbook" : "book", () -> {
            Set<String> l = new HashSet<>(m.cub.list());
            if (listed) l.remove(key);
            else l.add(key);
            m.cub = m.cub.withList(l);
        });
    }

    // ★ beside ⋯: the film's grade, Lampa's (shared through the worker's /grades), the star in its color; OK: the
    // grade bar under the buttons. A graded film leaves Home's Rate what you watched.
    TextView gradeButton(Home.Card c, Film f) {
        TextView b = m.ux.button("★", null);
        paintGrade(b, m.cub.grades().get(Timeline.card(c)));
        Consumer<String> under = asked -> {
            View buttons = (View) b.getParent();
            int[] p = new int[2];
            buttons.getLocationInWindow(p); // the window is the screen (fullscreen), and the sheets' parent fills it
            int in = m.dp(16); // the bar's padding: its pills line up with the buttons
            gradeBar(c, f.original(), p[0] - in, p[1] + buttons.getHeight() + in, g -> paintGrade(b, g), null, asked);
        };
        b.setOnClickListener(v -> under.accept(null));
        askGradeKey = Timeline.card(c);
        askGradeRun = () -> {
            if (b.isAttachedToWindow()) under.accept(c.name());
        };
        m.ui.post(this::askGrade); // a page built after DDD answered (the app was killed while it played)
        return m.ux.round(b);
    }

    // How was it?: back from DDD with a film watched to the end and not graded, its page's
    // grade bar opens by itself with Later. The film's ★ sets what to run (its page may be built after DDD answers).
    String askGradeFor; // the film ("movie_603") whose grade to ask, until its page shows
    String askGradeKey; // the film whose ★ is on the page, and what opens its bar
    Runnable askGradeRun;

    void askGrade() {
        if (askGradeFor == null || !askGradeFor.equals(askGradeKey) || askGradeRun == null) return;
        askGradeFor = null;
        m.ui.postDelayed(askGradeRun, 400); // laid out again after DDD
    }

    // OK on a Rate what you watched card: the grade bar under it, without opening the film (no /card, no backdrop).
    // Graded, the film leaves the row: focus goes on to the card after it first, so it stays in the row.
    // rate: from Rate what you watched (else ≡ on a poster: No grade instead of Open film, focus stays).
    void rateBar(Home.Card c, View card, boolean rate) {
        View strip = (View) card.getParent().getParent(); // under the row: a card beside may take two lines
        int[] p = new int[2];
        strip.getLocationInWindow(p);
        gradeBar(c, "", p[0], p[1] + strip.getHeight() + m.dp(12), g -> {
            if (!rate) return;
            ViewGroup line = (ViewGroup) card.getParent();
            int i = line.indexOfChild(card);
            View next = line.getChildAt(i + 1 < line.getChildCount() ? i + 1 : i - 1);
            if (next != null) next.requestFocus();
        }, rate ? () -> openFilm(c, card, null) : null, null);
    }

    // The grade bar: the grades as pills on an opaque bar at x, y, and what the focused
    // one does under them. saved: after a grade is saved (the page's ★ repainted). open: Home's "Open film" (null on
    // the film page, which offers No grade instead). asked: the film's name when it opened by itself after the film
    //: "How was it?" over the pills and Later after them.
    void gradeBar(Home.Card c, String original, int x, int y, Consumer<String> saved, Runnable open,
                          String asked) {
        String now = m.cub.grades().get(Timeline.card(c));
        LinearLayout pills = m.ux.trapped();
        TextView hint = m.ux.text("", 15, 0xffdddddd);
        String after = open != null ? ". Graded, " + c.name() + " leaves this row." : "";
        for (String[] g : Cub.GRADES) {
            SpannableStringBuilder label = new SpannableStringBuilder("★  " + g[1] + (g[0].equals(now) ? "  ✓" : ""));
            label.setSpan(new Ui.Tint(Color.parseColor(g[2]), 0xff111111), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            gradePill(pills, label, g[3] + after, hint, v -> setGrade(c, original, g[0], saved));
        }
        if (open == null && now != null) {
            gradePill(pills, "No grade", "Back in Rate what you watched", hint, v -> setGrade(c, original, null, saved));
        }
        if (open != null) {
            gradePill(pills, "Open film  →", "Its page: watch it, mark it", hint, v -> {
                m.closeOverlay();
                open.run();
            }).setBackground(m.ux.glass(m.pill, 0, 0x26ffffff));
        }
        if (asked != null) {
            gradePill(pills, "Later", "Grade it from ★ on its page any time", hint, v -> m.closeOverlay())
                .setBackground(m.ux.glass(m.pill, 0, 0x26ffffff));
        }
        LinearLayout bar = new LinearLayout(m);
        bar.setOrientation(LinearLayout.VERTICAL);
        int in = m.dp(16);
        bar.setPadding(in, in, in, in);
        bar.setBackground(m.ux.shape(24 * m.dp, 0xff2a2d30, 0x26ffffff));
        if (asked != null) {
            TextView title = m.ux.text("How was " + asked + "?", 22, Color.WHITE);
            title.setPadding(m.dp(4), 0, 0, m.dp(12));
            bar.addView(title);
        }
        bar.addView(pills);
        hint.setPadding(m.dp(4), m.dp(12), 0, 0);
        bar.addView(hint);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2);
        lp.leftMargin = x;
        lp.topMargin = y;
        m.showOverlay(bar, lp, null);
    }

    TextView gradePill(LinearLayout pills, CharSequence label, String does, TextView hint,
                               View.OnClickListener action) {
        TextView p = m.ux.button("", action);
        p.setText(label);
        p.setTextSize(17);
        p.setPadding(m.dp(16), 0, m.dp(16), 0);
        p.setMinHeight(m.dp(44));
        p.setBackground(m.ux.glass(m.pill, 0x22ffffff, 0));
        p.setOnFocusChangeListener((v, focused) -> {
            if (focused) hint.setText(does);
        });
        LinearLayout.LayoutParams lp = Ui.wrap();
        if (pills.getChildCount() > 0) lp.setMarginStart(m.dp(8));
        pills.addView(p, lp);
        return p;
    }

    static void paintGrade(TextView b, String g) {
        String[] x = Cub.grade(g);
        b.setTextColor(new ColorStateList(new int[][] {{android.R.attr.state_focused}, {}},
            new int[] {0xff111111, x == null ? Color.WHITE : Color.parseColor(x[2])}));
    }

    void setGrade(Home.Card c, String original, String g, Consumer<String> saved) {
        Cub.Account a = m.account();
        View panel = m.overlay;
        Toast.makeText(m, "Saving…", Toast.LENGTH_SHORT).show();
        byte[] body = Cub.gradeBody(c, original, g, System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8);
        m.load(null, "grade", () -> post("/grades", a, body, "application/json"), done -> {
            if (done != 200) {
                Toast.makeText(m, "The grade wasn't saved. Try again.", Toast.LENGTH_LONG).show();
                return;
            }
            Map<String, String> graded = new HashMap<>(m.cub.grades());
            if (g == null) graded.remove(Timeline.card(c));
            else graded.put(Timeline.card(c), g);
            m.cub = m.cub.withGrades(graded);
            if (m.overlay == panel) m.closeOverlay(); // not Back meanwhile
            saved.accept(g);
            m.loadMine(); // Home's Rate what you watched
        }, () -> { });
    }

    void setMark(Home.Card c, String mark) {
        postMine(c, mark, () -> {
            Map<String, String> marked = new HashMap<>(m.cub.marks());
            if (mark.equals("none")) marked.remove(Timeline.card(c));
            else marked.put(Timeline.card(c), mark);
            m.cub = m.cub.withMarks(marked);
        });
    }

    // POST /mine/<list>/<type>/<id> (a mark, My list); saved: what the app keeps once CUB took it. Home's rows follow.
    void postMine(Home.Card c, String list, Runnable saved) {
        Cub.Account a = m.account();
        View panel = m.overlay;
        Toast.makeText(m, "Saving…", Toast.LENGTH_SHORT).show();
        m.load(null, "mine post", () -> post("/mine/" + list + "/" + c.type() + "/" + c.id(), a, new byte[0], "text/plain"), done -> {
            if (done != 204) {
                Toast.makeText(m, "CUB didn't take it. Try again.", Toast.LENGTH_LONG).show();
                return;
            }
            saved.run();
            if (m.overlay == panel) m.closeOverlay();
            m.loadMine(); // Home's rows: out of Continue browsing, into Finish later or My list
        }, () -> { });
    }

    // The source last played of this film or series (play() keeps it), or null.
    Timeline.Last last(Home.Card c) {
        return Timeline.Last.of(m.getSharedPreferences("last", Context.MODE_PRIVATE).getString(Timeline.card(c), null));
    }

    // The worker's points for a film or a series' episodes (one D1 read, ~0.2 s, Lampa's too): the marks.
    // ponytail: each remark() hashes every episode's key again (~3000 on a 1000-episode series, a few ms, only when
    // marks come); keep the keys per film if that shows up
    void loadMarks(Film f) {
        m.load(null, "marks", () -> {
            Answer a = m.ask("/timeline?hash=" + Timeline.keys(f), null); // up to 1000 keys: off the UI thread
            if (a.code() != 200) throw new IOException("HTTP " + a.code());
            return Timeline.marks(a.body());
        }, got -> {
            Timeline.merge(m.marks, got, m.played);
            remark();
        }, () -> { }); // no marks
    }

    // A row whose line (its child) shows watch marks: set now, and again by remark().
    static void marked(LinearLayout r, int child, Supplier<CharSequence> text) {
        Runnable set = () -> ((TextView) r.getChildAt(child)).setText(text.get());
        set.run();
        r.setTag(set);
    }

    // The marks again (seasons' and episodes' rows, Continue), on top or under it: the worker's points came, or DDD's.
    void remark() {
        if (m.pageTop != null) remarkView(m.pageTop);
        for (Main.Level l : m.levels) remarkView(l.view());
    }

    static void remarkView(View v) {
        if (v.getTag() instanceof Runnable mark) mark.run();
        if (v instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) remarkView(g.getChildAt(i));
    }

    // A film level or a source list saves its film (and the list's season, episode); other lists save nothing.
    static boolean where(View level, Bundle out) {
        if (level.getTag() instanceof SourcesScreen.SourceList s) {
            out.putStringArray("film", s.c().save());
            out.putIntArray("sources", new int[] {s.season(), s.episode()});
            return true;
        }
        if (level.getTag() instanceof Home.Card c) {
            out.putStringArray("film", c.save());
            return true;
        }
        return false;
    }

    // TMDB's films and series number apart (movie 1399 isn't series 1399): an actor's rows hold both.
    static boolean isFilm(View level, Home.Card film) {
        return level.getTag() instanceof Home.Card c && c.id() == film.id() && c.type().equals(film.type());
    }

    // The source list open before process death, over its film read again: Back goes the same way as before.
    // prefer: Continue's release to focus in the source list; autoplay: Continue's, it plays at once when there.
    void reopen(Film f, Home.Card c, int season, int episode, String prefer, boolean autoplay) {
        if (season > 0) {
            if (f.episodes().stream().noneMatch(x -> x.number() == season)) { // the series changed meanwhile: the
                showBackdrop(System.currentTimeMillis()); // film page, with its backdrop
                return;
            }
            new Episodes(m, f, c, new int[] {season, episode}).show();
        }
        m.sourcesScreen.showSources(f, c, season, episode, prefer, autoplay);
    }
}
