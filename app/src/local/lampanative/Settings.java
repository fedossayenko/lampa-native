package local.lampanative;

import static local.lampanative.Net.*;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.icu.util.ULocale;
import android.net.Uri;
import android.os.Process;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.json.JSONObject;

/**
 * Settings: the tab's rows and what each one does (drawing, stats, the CUB account and profile, TorBox, the audio languages, the
 * play pick), the sheets they open, and the stats meter. The small readers every screen uses (account(), langs(), software(),
 * settings()) stay in Main.
 */
final class Settings extends Main.Part {
    Settings(Main m) {
        super(m);
    }

    String settingsAt; // the row showSettings focuses (back from its sub-sheet), else its first
    MeterView meter; // Settings → Stats overlay, while shown
    LinearLayout settingsList; // the Settings tab's rows: drawn again when a sheet or a level over them goes
    TextView settingsHelp; // beside them: what the focused one does
    String torboxLine = "…"; // Settings' TorBox line as last read: drawn again with the rows, asked again after a minute
    String speedLine = ""; // Settings → Speed test: the last result, "Testing…" while one runs (no second at once)
    long torboxAt;

    // ≡ or Home's Settings: a side sheet over whatever is shown: each setting's name and
    // value, what the focused one does at the sheet's foot. OK flips or picks. Drawing restarts the app: a window's
    // GPU is chosen before it is shown, and the GPU's context and driver pools stay with the process once made.
    void showSettings() {
        showSettings(null);
    }

    // ≡ on the remote: the sheet, except on the Settings tab itself (its rows are there).
    void menu() {
        if (settingsList == null || !settingsList.isAttachedToWindow()) showSettings();
    }

    void showSettings(String at) {
        settingsAt = at;
        LinearLayout list = m.ux.sheet("Settings", null, null);
        settingsRows(list, m.ux.footer(list), true);
    }

    // The Settings tab (a tab, not a sheet over Home): its rows on the left, what the focused one
    // does on the right, as TV settings show it.
    void openSettings(View from) {
        m.openPage(from);
        LinearLayout tab = m.tabPage("Settings");
        LinearLayout cols = new LinearLayout(m);
        settingsList = new LinearLayout(m);
        settingsList.setOrientation(LinearLayout.VERTICAL);
        cols.addView(settingsList, m.dp(560), -2);
        settingsHelp = m.ux.text("", 18, 0xff9a9a9a);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0, -2, 1);
        hp.setMarginStart(m.dp(48));
        hp.topMargin = m.dp(12);
        cols.addView(settingsHelp, hp);
        tab.addView(cols);
        settingsAt = "Drawing"; // its first row, as Search's first key and Coming up's first day
        settingsRows(settingsList, settingsHelp, false);
    }

    // The Settings tab shown again (a sheet over it closed, a level over it went): its values may have changed.
    void settingsAgain() {
        if (settingsList == null || !settingsList.isAttachedToWindow()) return;
        settingsList.removeAllViews();
        settingsRows(settingsList, settingsHelp, false); // focus on the row it had (settingsAt)
    }

    // sheet: ≡'s (Back from its sub-sheets opens it again), else the tab's (shown again by settingsAgain()).
    void settingsRows(LinearLayout list, TextView help, boolean sheet) {
        setting(list, help, "Drawing", drawing(), "GPU: smoothest scrolling. Software: about a third of the memory, "
            + "~30 ms more per scroll step. OK switches and restarts the app.", v -> {
                //noinspection ApplySharedPref: on disk now, this process ends below
                m.settings().edit().putBoolean("software", !m.software()).commit();
                m.startActivity(new Intent(m, Main.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                Process.killProcess(Process.myPid());
            });
        setting(list, help, "Stats overlay", stats(), "Memory, CPU and frames in the top right corner.", v -> {
            boolean on = !m.settings().getBoolean("stats", false);
            m.settings().edit().putBoolean("stats", on).apply();
            ((TextView) ((LinearLayout) v).getChildAt(1)).setText(stats()); // the value, right
            showMeter(on);
        });
        setting(list, help, "Speed test", speedLine, "OK downloads from Cloudflare for 8 seconds over 4 connections: "
            + "the speed this TV gets from the internet, the line and the Wi-Fi together.", v -> {
                if (speedLine.equals("Testing…")) return;
                TextView value = (TextView) ((LinearLayout) v).getChildAt(1);
                speedLine = "Testing…";
                value.setText(speedLine);
                new Thread(() -> { // its own: pool's 3 stay for posters meanwhile
                    double mbps = Net.mbps(Net.SPEED, 4, 8000);
                    String shown = mbps < 0 ? "" : Net.speedLine(mbps); // stopped (onStop): no result
                    m.ui.post(() -> {
                        speedLine = shown;
                        if (value.isAttachedToWindow()) value.setText(shown);
                        else if (m.overlay == null) settingsAgain(); // the tab's rows drawn again meanwhile; a sheet
                        // open over them keeps its focus (they show the result when it closes)
                    });
                }).start();
            });
        list.addView(m.ux.divider());
        Cub.Account a = m.account();
        setting(list, help, "CUB account", a == null ? "Sign in" : "Signed in", "Lampa's account: your history on Home "
            + "and in Lampa, My list, marks and grades. OK " + (a == null ? "signs in with a code." : "signs out."), v -> {
                Runnable done = m::loadMine; // signed in: your rows; signed out: nothing (signOut's showMine)
                if (m.account() != null) {
                    signOut(done);
                    if (sheet) m.overlayBack = () -> showSettings("CUB account");
                    return;
                }
                m.closeOverlay();
                if (m.page == null) m.openPage(m.scroll.findFocus()); // the code needs the screen: a page over Home
                new SignIn(m, done).show();
            });
        if (a != null) {
            setting(list, help, "Profile", a.name().isEmpty() ? "Main" : a.name(), "Whose history, marks and grades: CUB "
                + "keeps them per profile, as Lampa. OK picks another.", v -> {
                    showProfiles();
                    if (sheet) m.overlayBack = () -> showSettings("Profile");
                });
        }
        LinearLayout torbox = setting(list, help, "TorBox", torboxLine, "The TorBox account the worker plays from: its plan "
            + "and the torrents in it.", v -> { });
        setting(list, help, "Worker", Home.worker.isEmpty() ? "Not set" : Uri.parse(Home.worker).getHost(), "The worker "
            + "this app talks to, set once from a computer (README, Setup). To change it: adb shell pm clear "
            + "local.lampanative, then set it up again.", v -> { });
        LinearLayout about = setting(list, help, "About", "", "Lampa Native is unofficial: not made by or affiliated with "
            + "Lampa or CUB. This application uses TMDB and the TMDB APIs but is not endorsed, certified, or otherwise "
            + "approved by TMDB.", v -> { });
        ((TextView) about.getChildAt(1)).setCompoundDrawablesRelativeWithIntrinsicBounds(drawable("tmdb"), null, null, null);
        list.addView(m.ux.divider());
        setting(list, help, "Play picks", m.picks() ? "Best cached" : "Ask each time", "Best cached: a film's ▶ Play "
            + "starts the biggest release TorBox has cached in your audio languages. Ask each time: the sources list.", v -> {
                m.settings().edit().putBoolean("picks", !m.picks()).apply();
                ((TextView) ((LinearLayout) v).getChildAt(1)).setText(m.picks() ? "Best cached" : "Ask each time");
            });
        setting(list, help, "Audio languages", m.shownLangs().replace("all languages", "All"), "Sources in these "
            + "languages first. OK picks them.", v -> {
                // no source list here: the app's default ones and those chosen, no counts
                Set<String> codes = new LinkedHashSet<>(List.of("RU", "UA", "EN"));
                codes.addAll(Sources.codes(m.langs()));
                List<Sources.Language> all = new ArrayList<>();
                for (String code : codes) all.add(new Sources.Language(code, -1));
                showLanguages(all, null);
                if (sheet) m.overlayBack = () -> showSettings("Audio languages");
            });
        long now = SystemClock.elapsedRealtime();
        if (now - torboxAt < 60_000) return; // the tab's rows drawn again after a sub-sheet: no new request, no "…"
        torboxAt = now;
        // TorBox's plan and torrents (~0.5 s)
        Consumer<String> show = line -> {
            torboxLine = line;
            if (torbox.isAttachedToWindow()) ((TextView) torbox.getChildAt(1)).setText(line);
            else settingsAgain(); // the tab's rows drawn again meanwhile
        };
        m.load(null, "torbox", () -> {
            Answer t = m.ask("/torbox", null);
            JSONObject o = new JSONObject(t.body());
            return t.code() == 200 ? o.getString("plan") + " · " + o.getInt("torrents") + " torrents" : "Not reachable";
        }, show, () -> show.accept("Not reachable"));
    }

    @SuppressLint("DiscouragedApi") // by name: build.sh makes no R class
    Drawable drawable(String name) {
        return m.getDrawable(m.getResources().getIdentifier(name, "drawable", m.getPackageName()));
    }

    // A setting's row: its name and value on one line; focused, what it does at the sheet's foot.
    LinearLayout setting(LinearLayout list, TextView help, String title, String value, String does,
                                 View.OnClickListener action) {
        LinearLayout r = m.ux.sheetRow(title, null, value, action);
        r.setPadding(m.dp(12), m.dp(10), m.dp(12), m.dp(10));
        ((LinearLayout.LayoutParams) r.getLayoutParams()).bottomMargin = m.dp(2);
        r.setOnFocusChangeListener((v, focused) -> {
            if (!focused) return;
            help.setText(does);
            settingsAt = title; // the tab's rows drawn again keep it
        });
        list.addView(r);
        if (title.equals(settingsAt)) r.requestFocus();
        return r;
    }

    // Settings → Profile: the account's CUB profiles (Lampa's; history, marks and grades are each one's), the one used
    // ✓. OK switches: Home's rows are that profile's.
    void showProfiles() {
        Cub.Account a = m.account();
        LinearLayout list = m.ux.sheet("Profile", "Lampa uses the main one unless switched there", null);
        TextView wait = m.ux.text("Asking CUB…", 16, 0xff9a9a9a);
        wait.setPadding(m.dp(12), 0, 0, 0);
        list.addView(wait);
        View panel = m.overlay;
        m.load(null, "profiles", () -> Cub.profiles(cub("profiles/all", null, a.token()).body()), all -> {
            if (m.overlay != panel) return;
            list.removeView(wait);
            for (Cub.Profile p : all) {
                list.addView(m.ux.sheetRow(p.name(), p.main() ? "Main profile" : null, Ui.check(p.id() == a.profile()), v -> {
                    try {
                        m.saveAccount(new Cub.Account(a.token(), p.id(), p.name()));
                    } catch (IOException e) {
                        Toast.makeText(m, "Couldn't save it: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        return;
                    }
                    m.closeOverlay();
                    m.loadMine(); // that profile's rows
                }));
            }
            panel.requestFocus();
        }, () -> wait.setText("CUB doesn't answer. Try again later."));
    }

    void signOut(Runnable done) {
        LinearLayout list = m.ux.sheet("CUB account", m.account().name().isEmpty() ? null : m.account().name(), null);
        list.addView(m.ux.sheetRow("Sign out", "History leaves Home; CUB keeps it, and Lampa stays signed in", null, v -> {
            //noinspection ResultOfMethodCallIgnored: gone or never there, signed out either way
            m.cubFile().delete();
            //noinspection ResultOfMethodCallIgnored: the same
            m.mineFile().delete();
            m.cubAccount = Optional.empty();
            m.closeOverlay();
            m.showMine("");
            done.run();
        }));
    }

    String drawing() {
        return m.software() ? "Software" : "GPU";
    }

    String stats() {
        return m.settings().getBoolean("stats", false) ? "On" : "Off";
    }

    // The stats overlay goes on the window's decor, so it stays over Home and pages alike.
    void showMeter(boolean on) {
        if (on == (meter != null)) return;
        ViewGroup decor = (ViewGroup) m.getWindow().getDecorView();
        if (on) {
            meter = new MeterView(m, m.getWindow(), !m.soft, m.background, m.ui); // not the poster threads
            decor.addView(meter, new FrameLayout.LayoutParams(m.dp(330), m.dp(150),
                Gravity.TOP | Gravity.END));
            meter.start();
        } else {
            meter.stop();
            decor.removeView(meter);
            meter = null;
        }
    }

    // A side sheet: OK on a language adds or removes it, All languages takes them all. Saved for next time; when it
    // closes with the choice changed, reload runs (the source list asks again; null from Settings). releases < 0: not
    // counted (from Settings).
    void showLanguages(List<Sources.Language> all, Runnable reload) {
        String before = m.langs();
        LinearLayout list = m.ux.sheet("Audio languages", "OK adds or removes one.", () -> {
            if (reload != null && !m.langs().equals(before)) reload.run();
        });
        list.addView(m.ux.sheetRow("All languages", null, null, v -> {
            m.setLangs("");
            m.closeOverlay();
        }));
        list.addView(m.ux.divider());
        for (Sources.Language l : all) {
            String code = l.code();
            list.addView(m.ux.sheetRow(language(code), l.releases() < 0 ? code : code + " · " + l.releases() + " releases",
                Ui.check(Sources.selected(m.langs(), code)), v -> {
                    m.setLangs(Sources.toggle(m.langs(), code));
                    ((TextView) ((LinearLayout) v).getChildAt(1)).setText(Ui.check(Sources.selected(m.langs(), code)));
                }));
        }
    }

    // A Torrentio flag's language, in English: its country's likely one, from the platform's own data (UA →
    // Ukrainian, BR → Portuguese). EN is the worker's own (🇬🇧, or no flag).
    static String language(String code) {
        if (code.equals("EN")) return "English";
        String name = ULocale.addLikelySubtags(new ULocale("und_" + code)).getDisplayLanguage(ULocale.ENGLISH);
        return name.isEmpty() || name.startsWith("Unknown") || name.equalsIgnoreCase("und") ? code : name;
    }
}
