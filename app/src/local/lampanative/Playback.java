package local.lampanative;

import static local.lampanative.Net.*;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;
import android.view.View;
import android.widget.Toast;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Playback: OK on a source asks the worker for TorBox's link (with the skip times and the season's stills), hands it to DDD, and
 * takes DDD's answer back (position, tracks, speed) into the marks and the worker's timeline. What is playing and whether a
 * link is being asked stay in Main (the key handler, onCreate and Progress read them).
 */
final class Playback extends Main.Part {
    Playback(Main m) {
        super(m);
    }

    static final int PLAY = 1;

    static final String DDD = "top.rootu.dddplayer";

    // OK on a source: TorBox's link from the worker (~1 s) with the resume point (Lampa's key: the worker shares it
    // with Lampa), then DDD from there. DDD hands back where it stopped: onActivityResult.
    // level: the source list (or the film page, Continue) it was asked from: gone meanwhile, no DDD.
    void play(Film f, Home.Card c, int season, int episode, String hash, int fileIdx, String name,
                      View level) {
        if (m.asking) { // a second OK while the link comes: one DDD, one playback
            Toast.makeText(m, "Still getting the link…", Toast.LENGTH_SHORT).show();
            return;
        }
        m.asking = true;
        String key = Timeline.key(f, season, episode);
        String path = Timeline.playPath(hash, fileIdx, name, key, season > 0 ? Timeline.original(f) : null);
        Toast.makeText(m, "Getting the link…", Toast.LENGTH_SHORT).show();
        // Our DDD fork's skip times (intro, recap, credits) from the worker, asked alongside the link
        String skipPath = Timeline.segmentsPath(c, f.imdb(), season, episode, hash, fileIdx, name, Timeline.original(f));
        FutureTask<Answer> skipsAsked = skipPath == null ? null : new FutureTask<>(() -> m.ask(skipPath, null));
        // The season's stills and overviews for our DDD fork's next episode card and paused screen (the page has
        // read it: the HTTP cache's)
        FutureTask<List<Film.Episode>> stillsAsked = season < 1 ? null : new FutureTask<>(() -> Film.season(
            new String(read(Film.seasonUrl(c, season)), StandardCharsets.UTF_8)));
        // their own threads: the link's task below waits for them, and the pool's three may all be busy
        if (skipsAsked != null) Main.thread("segments", skipsAsked);
        if (stillsAsked != null) Main.thread("stills", stillsAsked);
        m.async(() -> {
            String message;
            try {
                Answer a = m.ask(path, null);
                Timeline.Link l = Timeline.link(a.code(), a.body());
                if (l.url() != null) {
                    Map<String, String> skips = Map.of();
                    try { // a second more at most: no skip button beats a late film
                        if (skipsAsked != null) {
                            Answer s = skipsAsked.get(1, TimeUnit.SECONDS);
                            skips = Timeline.segments(s.code(), s.body());
                        }
                    } catch (Exception e) {
                        Log.w(Main.TAG, "segments " + skipPath, e);
                    }
                    Map<String, String> skip = skips;
                    Map<Integer, Film.Episode> of = new HashMap<>(); // this season's episodes by number
                    try {
                        if (stillsAsked != null) {
                            for (Film.Episode e : stillsAsked.get(1, TimeUnit.SECONDS)) of.put(e.number(), e);
                        }
                    } catch (Exception e) {
                        Log.w(Main.TAG, "stills " + c.id() + "/" + season, e);
                    }
                    String[] stills = l.playlist().stream().map(e -> e.season() == season && of.containsKey(e.episode())
                        && !of.get(e.episode()).still().isEmpty() ? Main.STILL + of.get(e.episode()).still() : "")
                        .toArray(String[]::new);
                    String[] overviews = l.playlist().stream().map(e -> season < 1 ? f.overview()
                        : e.season() == season && of.containsKey(e.episode()) ? of.get(e.episode()).overview() : "")
                        .toArray(String[]::new);
                    m.ui.post(() -> {
                        m.asking = false;
                        // Back, Home or onDestroy meanwhile: no DDD (level null: from Home, ≡ on a poster)
                        if (m.stopped || level != null && !m.alive(level)) return;
                        // Continue plays it again (Last)
                        // ponytail: ~150 B a film played, in their own file (settings stays small); trim past ~1000
                        m.getSharedPreferences("last", Context.MODE_PRIVATE).edit().putString(Timeline.card(c),
                            new Timeline.Last(season, episode, hash, fileIdx, name).save()).apply();
                        m.addHistory(c);
                        String[] titles = l.playlist().stream().map(e -> f.heading(e.season(), e.episode()))
                            .toArray(String[]::new);
                        startPlayer(l.url(), f.heading(season, episode), Sources.audio(m.langs()),
                            new Timeline.Playing(key, Timeline.card(c), season, episode, l.startMs(), l.playlist()),
                            titles, stills, overviews, skip);
                    });
                    return;
                }
                message = l.message();
            } catch (Exception e) {
                Log.w(Main.TAG, "play " + hash, e);
                message = e instanceof FileNotFoundException ? e.getMessage() : "Can't get the link. Try again.";
            }
            String told = message;
            m.ui.post(() -> {
                m.asking = false;
                Toast.makeText(m, told, Toast.LENGTH_LONG).show();
            });
        });
    }

    // titles: the playlist's (an episode and the ones after it in its torrent): DDD goes on to the next by itself.
    // skips: the worker's skip times by "season:episode" ("0:0": a film).
    void startPlayer(String url, String title, String audio, Timeline.Playing p, String[] titles,
                             String[] stills, String[] overviews, Map<String, String> skips) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.parse(url), "video/*");
        i.setPackage(DDD);
        i.putExtra("title", title);
        if (titles.length > 1) { // position (below) is for the item at url, the first
            i.putExtra("video_list", p.playlist().stream().map(e -> Uri.parse(e.url())).toArray(Uri[]::new));
            i.putExtra("video_list.name", titles);
            i.putExtra("video_list.thumbnail", stills); // our DDD fork's next episode card ("" for none)
            i.putExtra("video_list.overview", overviews); // and its paused screen
            // Our DDD fork: a button to skip each one's intro, recap or credits ("" for none)
            i.putExtra("video_list.segments", p.playlist().stream()
                .map(e -> skips.getOrDefault(e.season() + ":" + e.episode(), "")).toArray(String[]::new));
        } else if (skips.containsKey(p.season() + ":" + p.episode())) {
            i.putExtra("segments", skips.get(p.season() + ":" + p.episode()));
        }
        if (titles.length <= 1 && overviews.length > 0 && !overviews[0].isEmpty()) i.putExtra("overview", overviews[0]);
        i.putExtra("position", p.startMs()); // ms; 0: from the start
        if (!audio.isEmpty()) i.putExtra("audio_lang", audio); // our DDD fork: this film's audio, "ru,uk"
        // Our DDD fork: the film's or series' tracks and speed as DDD last answered them (onActivityResult). The audio
        // key goes even empty: DDD then carries a track picked now on to the playlist's next episodes.
        SharedPreferences tracks = m.getSharedPreferences("tracks", Context.MODE_PRIVATE);
        i.putExtra("audio_track", tracks.getString(p.card() + " audio", ""));
        String sub = tracks.getString(p.card() + " sub", null);
        if (sub != null) i.putExtra("subtitle_track", sub);
        if (tracks.contains(p.card() + " speed")) i.putExtra("speed", tracks.getFloat(p.card() + " speed", 1f));
        i.putExtra("return_result", true); // else DDD sends nothing back
        i.putExtra("return_progress", true); // our DDD fork: the position while it plays too (Progress)
        m.playing = p;
        m.getSharedPreferences("playing", Context.MODE_PRIVATE).edit()
            .putString("now", p.toJson()).putString("url", url).apply();
        try {
            m.startActivityForResult(i, PLAY);
        } catch (ActivityNotFoundException e) {
            m.playing = null;
            m.getSharedPreferences("playing", Context.MODE_PRIVATE).edit().clear().apply(); // nothing is playing
            Toast.makeText(m, "DDD player isn't installed.", Toast.LENGTH_LONG).show();
        }
    }

    // DDD's answer: position and duration (ms), end_by "completion" or "user", and the address it stopped on (a
    // playlist: a later episode; the ones before it ended). Saved by the worker (stamped there: newest wins), where
    // Lampa picks it up; the worker also marks an episode watched when DDD goes on from it (/stream), in case DDD
    // never answers.
    void result(int request, int result, Intent data) {
        Timeline.Playing started = m.playing;
        m.playing = null;
        // Its answer carries the final position: the reports of Progress end with the playback (else its address
        // would stay good for any later broadcast that knew it)
        if (request == PLAY) m.getSharedPreferences("playing", Context.MODE_PRIVATE).edit().clear().apply();
        if (request != PLAY || started == null || result != Activity.RESULT_OK || data == null) return;
        String at = data.getData() == null ? null : data.getData().toString();
        Timeline.Playing p = started.at(at);
        for (Timeline.Episode e : started.before(at)) {
            m.marks.put(e.key(), new Timeline.Point(0, 0, 100));
            m.played.add(e.key());
        }
        if (p != started) { // Continue goes on from the episode DDD stopped on, the same release
            SharedPreferences last = m.getSharedPreferences("last", Context.MODE_PRIVATE);
            Timeline.Last was = Timeline.Last.of(last.getString(p.card(), null));
            Timeline.Episode e = started.playlist().stream().filter(x -> x.key().equals(p.key())).findFirst().orElseThrow();
            if (was != null) last.edit().putString(p.card(), new Timeline.Last(e.season(), e.episode(), was.hash(), -1,
                e.name()).save()).apply();
        }
        // The tracks and speed it played with, for the next time (startPlayer). No speed: DDD's default.
        SharedPreferences.Editor tracks = m.getSharedPreferences("tracks", Context.MODE_PRIVATE).edit();
        String audio = data.getStringExtra("audio_track"), sub = data.getStringExtra("subtitle_track");
        if (audio != null) tracks.putString(p.card() + " audio", audio);
        if (sub != null) tracks.putString(p.card() + " sub", sub);
        if (data.hasExtra("speed")) tracks.putFloat(p.card() + " speed", data.getFloatExtra("speed", 1f));
        else tracks.remove(p.card() + " speed");
        tracks.apply();
        // DDD puts Kotlin Longs (checked: 4030 s of The Dark Knight came back)
        long[] t = p.saved(data.getLongExtra("position", 0), data.getLongExtra("duration", 0),
            "completion".equals(data.getStringExtra("end_by")));
        if (t == null) return;
        m.marks.put(p.key(), new Timeline.Point((int) t[0], (int) t[1], (int) t[2]));
        m.played.add(p.key());
        m.remark(); // the episode's row under the sources
        if (p.season() == 0 && t[2] >= 90 && !m.cub.grades().containsKey(p.card()) && m.account() != null) {
            m.askGradeFor = p.card();
            m.askGrade();
        }
        String json = p.body(t);
        m.load(null, "timeline", () -> m.ask("/timeline", json.getBytes(StandardCharsets.UTF_8)).code(), code -> {
            if (code != 204) Log.w(Main.TAG, "timeline: HTTP " + code);
            m.loadMine(); // the film just played, in Continue browsing
        }, () -> { }); // ponytail: lost; keep it on the stick and send it later if this shows up
    }
}
