package local.lampanative;

import static local.lampanative.Net.*;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import org.json.JSONObject;

/**
 * The source list of a film or an episode: the rows from the worker's /sources with their audio chips, TorBox's cached marks
 * and the language filter, and OK on a row (Playback takes it from there). SourceList is the level's tag that
 * the film page saves and restores.
 */
final class SourcesScreen extends Main.Part {
    SourcesScreen(Main m) {
        super(m);
    }

    volatile String addon; // its address (the worker's /addon), asked the first time it refuses

    // TorBox sources, biggest first, in the chosen audio languages: the first 6 (5 rows fit on the screen), then the
    // rest is asked for at once and built as focus nears it (addRows).
    // prefer: the release to focus (Continue: the one last played), null: the first. autoplay (Continue): when that
    // release has this episode too (a season's torrent), it plays at once, no OK.
    void showSources(Film f, Home.Card c, int season, int episode, String prefer, boolean autoplay) {
        long t = System.currentTimeMillis();
        String title = f.heading(season, episode);
        // The head: "Sources", what for and how many under it, the audio languages on a
        // button at the right (Up from the first source)
        LinearLayout list = m.pushList("Sources");
        TextView head = (TextView) list.getChildAt(0);
        list.removeView(head);
        head.setPadding(0, 0, 0, 0);
        LinearLayout top = new LinearLayout(m);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout words = new LinearLayout(m);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(head);
        TextView sub = m.ux.text(title, 16, 0xff9a9a9a);
        sub.setPadding(0, m.dp(2), 0, 0);
        words.addView(sub);
        top.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        TextView audio = m.ux.button("Audio: " + m.shownLangs().replace(",", ""), null);
        audio.setTextSize(18);
        audio.setMinHeight(m.dp(44));
        audio.setPadding(m.dp(20), 0, m.dp(20), 0);
        top.addView(audio);
        LinearLayout.LayoutParams under = new LinearLayout.LayoutParams(-1, -2);
        under.bottomMargin = m.dp(18);
        list.addView(top, 0, under);
        TextView status = m.ux.text("Loading sources…", 16, 0xff9a9a9a);
        list.addView(status);
        // A fast Watch waits for the preload instead of asking TorBox twice.
        // ponytail: after a collection film and Back, it waits for that film's preload (≤ 0.8 s); key it by level
        // if that shows up
        Future<?> before = m.preload;
        m.preload = null;
        SourceList req = new SourceList(f, c, season, episode, title, m.langs(), list, status, sub, audio,
            new ArrayDeque<>(), t, prefer, autoplay, new int[] {AUDIO_READS});
        Ui.levelOf(list).setTag(req); // onSaveInstanceState: the list to open again after process death
        loadSources(req, 0, before);
    }

    // One source list being filled: what it lists, where, the rows not built yet, and when Watch was pressed.
    // audioReads[0]: how many more rows may ask the worker for their audio (rows are built on the UI thread).
    record SourceList(Film f, Home.Card c, int season, int episode, String title, String langs, LinearLayout list,
                      TextView status, TextView sub, TextView audio, ArrayDeque<Sources.Source> pending, long t,
                      String prefer, boolean autoplay, int[] audioReads) {
        View level() {
            return Ui.levelOf(list);
        }
    }

    // Torrentio refusing the worker (its 502: 429 from Cloudflare's shared IPs) sends this and the next 10 minutes'
    // pages through the stick.
    void loadSources(SourceList req, int from, Future<?> before) {
        String imdb = req.f().imdb();
        m.load(req.level(), "sources " + imdb, () -> {
            try {
                if (before != null) before.get();
            } catch (Exception e) {
                // the preload failed: asked again below
            }
            String json = null;
            if (!Sources.refusedLately(m.refusedAt, System.currentTimeMillis())) {
                try {
                    json = new String(read(Sources.url(imdb, req.season(), req.episode(), req.langs(), from)),
                        StandardCharsets.UTF_8);
                } catch (Status e) {
                    if (!Sources.fallsBack(e.code)) throw e;
                    Log.w(Main.TAG, "sources: the addon refused the worker (429), asked from here for 10 minutes");
                    m.refusedAt = System.currentTimeMillis();
                }
            }
            if (json == null) { // the addon answers the stick's own IP: its list (~40-300 KB; its max-age keeps
                // it on the disk for the next page) to the worker to filter and check with TorBox
                if (addon == null) {
                    Answer at = m.ask("/addon", null);
                    if (at.code() != 200) throw new IOException("addon: HTTP " + at.code());
                    addon = at.body().trim();
                }
                // ponytail: each page posts the whole list again (and the preload's 502 isn't counted); have
                // the worker keep a posted list ~10 min if refusals last
                Answer a = m.ask(Sources.path(imdb, req.season(), req.episode(), req.langs(), from),
                    read(Sources.addon(addon, imdb, req.season(), req.episode())));
                if (a.code() != 200) throw new IOException("sources: HTTP " + a.code());
                json = a.body();
            }
            return Sources.parse(json);
        }, p -> { // alive: not left
            for (Sources.Source s : p.streams()) { // each release once in all the pages: it plays once
                if (req.autoplay() && s.hash().equals(req.prefer())) {
                    m.playback.play(req.f(), req.c(), req.season(), req.episode(), s.hash(), s.fileIdx(), s.title(),
                        req.level());
                }
            }
            req.pending().addAll(p.streams());
            Supplier<View> next = () -> req.pending().isEmpty() ? null : sourceRow(req.pending().poll(), req);
            if (from > 0) {
                m.ux.addRows(req.list(), next, 5); // the others as focus gets near them
                return;
            }
            req.list().removeView(req.status());
            String shown = req.langs().isEmpty() ? "all languages" : req.langs().replace(",", ", ");
            req.sub().setText(req.title() + " · " + p.total() + " found, biggest first");
            req.audio().setOnClickListener(v -> m.settingsScreen.showLanguages(p.languages(), () -> {
                if (m.pageTop != req.level()) return; // left meanwhile
                m.popLevel(false); // no backdrop: the new list goes straight on top
                showSources(req.f(), req.c(), req.season(), req.episode(), null, false);
            }));
            if (p.total() == 0) req.list().addView(m.ux.text("Nothing in " + shown + ".", 16, 0xff9a9a9a));
            m.ux.addRows(req.list(), next, p.streams().size());
            // the first source, under the head (Continue: the release last played, when among them); nothing
            // found: OK goes straight to the languages
            int at = 1 + Sources.focus(p.streams(), req.prefer());
            (p.streams().isEmpty() ? req.audio() : req.list().getChildAt(at)).requestFocus();
            Log.i(Main.TAG, "sources " + (System.currentTimeMillis() - req.t()) + " ms after Watch");
            if (p.next() > 0) loadSources(req, p.next(), null);
        }, () -> {
            if (req.status().getParent() == null) req.list().addView(req.status()); // under the first 6
            req.status().setText(from > 0 ? "The rest of the sources didn't load. Press Back and try again."
                : "Can't load the sources. Press Back and try again.");
        });
    }

    // ⚡ Cached, the badges on their colors, then size · seeders · tracker · languages. A cached release whose audio
    // isn't read yet (the first AUDIO_READS of a list) asks the worker for it and gets its line again with the tracks.
    View sourceRow(Sources.Source s, SourceList req) {
        SpannableStringBuilder cached = new SpannableStringBuilder(Sources.cachedLabel(s.cached()));
        cached.setSpan(s.cached() == 1 ? new Ui.Tint(Home.GREEN, 0xff0a7d3e) : new Ui.Tint(0xff8a8a8a, 0xff4a4a4a), 0,
            cached.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        LinearLayout r = m.ux.row(s.title(), sourceLine(s, req.langs()), cached, v -> m.playback.play(req.f(), req.c(), req.season(),
            req.episode(), s.hash(), s.fileIdx(), s.title(), req.level()));
        if (Sources.readsAudio(s, req.autoplay(), req.prefer(), req.audioReads()[0])) {
            req.audioReads()[0]--;
            TextView detail = r.findViewWithTag(Ui.DETAIL);
            View level = req.level();
            m.audioReads.execute(() -> {
                // left before its turn (or covered by a page: read on the next Watch, Back doesn't rebuild the row),
                // or DDD plays: no TorBox add for a row nobody sees (alive() is the UI thread's; a level off screen is
                // detached; read off the UI thread, a stale answer only costs or skips one read). ponytail: one already
                // asking runs on (a socket waiting <= 30 s) and keeps the closed list's few views until it ends: cut
                // off, the worker would stop before taking its add out of the account again.
                if (m.stopped || !level.isAttachedToWindow()) return;
                try {
                    Answer a = m.ask(Sources.audioPath(s), null);
                    if (a.code() != 200) return; // 429: the worker's TorBox budget, next time; 502: not readable
                    Sources.Source read = s.withAudio(Sources.tracks(new JSONObject(a.body()).getJSONArray("tracks")));
                    m.ui.post(() -> {
                        if (m.alive(level)) detail.setText(sourceLine(read, req.langs()));
                    });
                } catch (Exception e) {
                    Log.w(Main.TAG, "audio " + s.hash(), e);
                }
            });
        }
        return r;
    }

    static final int AUDIO_READS = 4; // the first page's cached ones (5 rows fit on the screen)

    SpannableStringBuilder sourceLine(Sources.Source s, String langs) {
        SpannableStringBuilder line = new SpannableStringBuilder();
        List<Sources.Heard> heard = Sources.heard(s, langs);
        for (String b : Sources.badges(s, Sources.fileRead(s))) m.ux.chip(line, b);
        // size and tracker before the audio groups: a one-line row cut at its end keeps what rows are compared by
        line.append(Sources.details(s, heard.isEmpty())).append("  ");
        for (Sources.Heard h : heard) { // its chips, then where they come from in the line's own color
            for (String c : h.chips()) m.ux.chip(line, c);
            line.append(h.from()).append("  ");
        }
        return line;
    }
}
