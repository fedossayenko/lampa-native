package local.lampanative;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The worker's /streams answer: one film's (or episode's) TorBox sources. Tested on the Mac (SourcesTest). */
final class Sources {
    static final int VISION = 0xff6a1b9a, HDR = 0xffa86400, REMUX = 0xff2e7d32, AUDIO = 0xff1565c0, PLAIN = 0xff455a64;

    /** cached: 1 TorBox has it (plays at once), 0 it doesn't, -1 unknown. audio: its tracks, null = not read yet. */
    record Source(String hash, int fileIdx, String title, List<String> badges, String size, int seeders, String tracker,
                  List<String> languages, int cached, List<Track> audio) {
        Source withAudio(List<Track> tracks) {
            return new Source(hash, fileIdx, title, badges, size, seeders, tracker, languages, cached, tracks);
        }
    }

    /** An audio track read from the release's file (the worker's /audio): lang the app's code (RU, UA, EN; "" not
     *  said), codec (TrueHD, DTS-HD, DTS, DD+, DD, AAC…), channels, Atmos (TrueHD's 16 channels or E-AC-3's
     *  objects, from its first frame), and its name ("Дубляж Red Head Sound"). */
    record Track(String lang, String codec, int channels, boolean atmos, String name) {}

    record Language(String code, int releases) {}

    /** next: ask from=next for the rest, 0 when there is no more. */
    record Page(List<Source> streams, int next, int total, List<Language> languages) {}

    private Sources() {}

    // season 0 = a film. langs: "RU,UA", "" = every language.
    static String url(String imdb, int season, int episode, String langs, int from) {
        return Home.worker + path(imdb, season, episode, langs, from);
    }

    static String path(String imdb, int season, int episode, String langs, int from) {
        String what = season > 0 ? "series/" + imdb + "/" + season + "/" + episode : "movie/" + imdb;
        return "/streams/" + what + "?lang=" + langs + (from > 0 ? "&from=" + from : "");
    }

    // The row a source list focuses: the release asked for (Continue: the one last played), else the first.
    static int focus(List<Source> streams, String hash) {
        for (int i = 0; hash != null && i < streams.size(); i++) {
            if (streams.get(i).hash().equals(hash)) return i;
        }
        return 0;
    }

    // The worker's 502 is Torrentio refusing it (TorBox failing only marks rows unknown): the stick asks Torrentio.
    static boolean fallsBack(int code) {
        return code == 502;
    }

    // The streams addon refused the worker (its 502) in the last 10 minutes: the stick asks it itself, every page.
    // refusedAt: 0 = never.
    static boolean refusedLately(long refusedAt, long now) {
        return refusedAt > 0 && now - refusedAt < 600_000;
    }

    // The streams addon's own list (addon: its address, the worker's /addon), which the stick posts to the worker when
    // the addon refuses the worker (Torrentio's 429: Cloudflare's shared IPs).
    static String addon(String addon, String imdb, int season, int episode) {
        return addon.replaceAll("/+$", "") + "/stream/" + (season > 0 ? "series/" + imdb + ":" + season + ":" + episode
            : "movie/" + imdb) + ".json";
    }

    static Page parse(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        JSONArray rows = o.getJSONArray("streams"), langs = o.getJSONArray("languages");
        List<Source> streams = new ArrayList<>(rows.length());
        for (int i = 0; i < rows.length(); i++) {
            JSONArray r = rows.getJSONArray(i);
            JSONArray audio = r.optJSONArray(9); // null: not read yet
            streams.add(new Source(r.getString(0), r.getInt(1), r.getString(2), strings(r.getJSONArray(3)),
                r.getString(4), r.getInt(5), r.getString(6), strings(r.getJSONArray(7)), r.getInt(8),
                audio == null ? null : tracks(audio)));
        }
        List<Language> languages = new ArrayList<>(langs.length());
        for (int i = 0; i < langs.length(); i++) {
            languages.add(new Language(langs.getJSONArray(i).getString(0), langs.getJSONArray(i).getInt(1)));
        }
        return new Page(streams, o.getInt("next"), o.getInt("total"), languages);
    }

    // The worker's [[lang, codec, channels, atmos, name], ...] (a /streams row's, or /audio's "tracks").
    static List<Track> tracks(JSONArray a) throws JSONException {
        List<Track> out = new ArrayList<>(a.length());
        for (int i = 0; i < a.length(); i++) {
            JSONArray t = a.getJSONArray(i);
            out.add(new Track(t.getString(0), t.getString(1), t.getInt(2), t.getInt(3) == 1, t.optString(4)));
        }
        return out;
    }

    // GET /audio/<hash>?file=&name= (ask(): the play token): a cached release's tracks, read from its file the first
    // time (~1-3 s, a TorBox add the worker budgets), at once after that. The file Play would pick.
    static String audioPath(Source s) {
        return "/audio/" + s.hash() + "?file=" + s.fileIdx() + "&name=" + URLEncoder.encode(s.title(), StandardCharsets.UTF_8);
    }

    static final List<String> NAMED_AUDIO = List.of("Atmos", "TrueHD", "DTS", "DD+", "DD");

    // A row's chips: the release's own (4K, DV, REMUX, HEVC…), then its audio: read → the best track of each language
    // shown (audioChips), not read → what its name says (Atmos, TrueHD, DTS, DD+, DD: some, for any language).
    static List<String> chips(Source s, String langs) {
        List<String> audio = s.audio() == null ? List.of() : audioChips(s.audio(), langs);
        if (audio.isEmpty()) return s.badges();
        List<String> out = new ArrayList<>();
        for (String b : s.badges()) if (!NAMED_AUDIO.contains(b)) out.add(b);
        out.addAll(audio);
        return out;
    }

    // "RU DD+ 7.1", "EN TrueHD Atmos": per language, the picked ones in their order then English (the original),
    // its best track: Atmos, then more channels, then the better codec. Commentaries left out. langs "" (every
    // language): the release's first language and English.
    static List<String> audioChips(List<Track> audio, String langs) {
        List<String> order = codes(langs);
        if (order.isEmpty()) for (Track t : audio) if (!t.lang().isEmpty() && !commentary(t)) {
            order.add(t.lang());
            break;
        }
        if (!order.contains("EN")) order.add("EN");
        List<String> chips = new ArrayList<>();
        for (String lang : order) {
            Track best = null;
            for (Track t : audio) {
                if (t.lang().equals(lang) && !commentary(t) && (best == null || better(t, best))) best = t;
            }
            if (best != null) chips.add(lang + " " + best.codec() + " " + (best.atmos() ? "Atmos" : layout(best.channels())));
        }
        return chips;
    }

    private static final Pattern COMMENTARY = Pattern.compile("(?iu)comment|коммент");
    // Lossless first, then by how much each carries
    private static final List<String> CODECS = List.of("TrueHD", "DTS-HD", "FLAC", "PCM", "DD+", "DTS", "DD", "AAC",
        "Opus");

    private static boolean commentary(Track t) {
        return COMMENTARY.matcher(t.name()).find();
    }

    private static boolean better(Track a, Track b) {
        if (a.atmos() != b.atmos()) return a.atmos();
        if (a.channels() != b.channels()) return a.channels() > b.channels();
        return rank(a.codec()) < rank(b.codec());
    }

    private static int rank(String codec) {
        int i = CODECS.indexOf(codec);
        return i < 0 ? CODECS.size() : i;
    }

    static String layout(int channels) {
        return switch (channels) {
            case 8 -> "7.1";
            case 7 -> "6.1";
            case 6 -> "5.1";
            case 2 -> "2.0";
            case 1 -> "1.0";
            default -> channels + "ch";
        };
    }

    private static List<String> strings(JSONArray a) throws JSONException {
        List<String> out = new ArrayList<>(a.length());
        for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        return out;
    }

    // Seeders only matter when TorBox has to download it; a cached one streams from TorBox's own copy. The languages
    // only while the audio isn't read (then its chips say them, the line fits).
    static String details(Source s, String shown) {
        String seeders = s.cached() == 1 ? "" : s.seeders() + " seeders · ";
        boolean chips = s.audio() != null && !audioChips(s.audio(), shown).isEmpty();
        String langs = s.languages().isEmpty() || chips ? "" : " · " + String.join(" ", s.languages());
        return s.size() + " · " + seeders + s.tracker() + langs;
    }

    // ⚡ from the symbols font (U+FE0E asks for the plain glyph): the color emoji one costs 18 MB on the stick for the
    // rest of the session (the emoji font read in, an 8 MB color glyph texture; measured).
    static final String BOLT = "⚡\uFE0E";

    static String cachedLabel(int cached) {
        return switch (cached) {
            case 1 -> BOLT + " Cached";
            case 0 -> "Not cached";
            default -> "";
        };
    }

    // ▶ Play's release (Settings → Play picks: Best cached): the first cached one, the list being biggest first; never
    // a whole disc (DDD can't play one). null: none cached.
    static Source best(List<Source> streams) {
        for (Source s : streams) if (s.cached() == 1 && !s.badges().contains("Disc")) return s;
        return null;
    }

    // A release's picture badges (4K, DV, HDR, REMUX): what "Plays …" shows; codecs and sound left out.
    static List<String> picture(Source s) {
        List<String> out = new ArrayList<>();
        for (String b : s.badges()) if (List.of("4K", "1080p", "720p", "DV", "HDR", "HDR10+", "REMUX").contains(b)) out.add(b);
        return out;
    }

    // The same in one line of plain text (≡ on a poster): "4K DV · RU · ⚡ cached, 74.3 GB".
    static String playsText(Source s, String langs) {
        String picture = String.join(" ", picture(s));
        return (picture.isEmpty() ? "" : picture + " · ") + tracks(s.languages(), langs) + " · " + BOLT + " cached, "
            + s.size();
    }

    // A release's audio on one line: all of a few; of many, the picked ones it has (none: its first three) and how
    // many more, "RU UA +25" (a remux can carry 27).
    static String tracks(List<String> all, String langs) {
        if (all.size() <= 4) return String.join(" ", all);
        List<String> shown = new ArrayList<>();
        for (String code : all) if (selected(langs, code)) shown.add(code);
        if (shown.isEmpty()) shown = all.subList(0, 3);
        return String.join(" ", shown) + " +" + (all.size() - shown.size());
    }

    static int badgeColor(String badge) {
        return switch (badge) {
            case "DV" -> VISION;
            case "HDR", "HDR10+" -> HDR;
            case "REMUX" -> REMUX;
            case "Atmos", "TrueHD", "DTS", "DD+", "DD" -> AUDIO;
            default -> badge.contains(" ") ? AUDIO : PLAIN; // a read track: "RU DD+ 7.1"
        };
    }

    // The language setting, "RU,UA" by default: adds the code or takes it out. Nothing left = every language.
    static String toggle(String langs, String code) {
        List<String> list = codes(langs);
        if (!list.remove(code)) list.add(code);
        return String.join(",", list);
    }

    static boolean selected(String langs, String code) {
        return codes(langs).contains(code);
    }

    // DDD's audio_lang for the picked languages: Russian first (then Ukrainian) when picked; "" = DDD's own setting.
    static String audio(String langs) {
        List<String> picked = codes(langs), audio = new ArrayList<>(2);
        if (picked.contains("RU")) audio.add("ru");
        if (picked.contains("UA")) audio.add("uk");
        return String.join(",", audio);
    }

    static List<String> codes(String langs) {
        List<String> list = new ArrayList<>(Arrays.asList(langs.split(",")));
        list.remove(""); // "".split(",") is [""]
        return list;
    }
}
