package local.lampanative;

import static local.lampanative.Net.*;

import android.graphics.Color;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import org.json.JSONException;

// CUB's device sign-in, as Lampa's: a code to confirm on a phone at the site shown, asked about every few seconds
// until it is. A refused or expired code is replaced. Back stops it (each step checks its level is still there).
final class SignIn extends Main.Part {
    final Runnable done;
    final TextView site = m.text("", 30, Color.WHITE), code = m.text("", 64, Color.WHITE),
        status = m.text("Getting a code…", 18, 0xff9a9a9a);
    LinearLayout list;
    View level;
    Cub.Code c;
    long until;

    SignIn(Main m, Runnable done) {
        super(m);
        this.done = done;
    }

    void show() {
        list = m.pushList("Sign in to CUB");
        level = m.pageTop;
        list.addView(m.text("On your phone, open", 22, 0xffdddddd));
        list.addView(site);
        code.setTypeface(m.medium);
        code.setLetterSpacing(0.1f);
        code.setBackground(m.shape(16 * m.dp, 0x14ffffff, 0x26ffffff));
        code.setPadding(m.dp(32), m.dp(4), m.dp(32), m.dp(4));
        LinearLayout.LayoutParams lp = Main.wrap();
        lp.setMargins(0, m.dp(16), 0, m.dp(16));
        list.addView(code, lp); // the code on a glass tile, as the query
        list.addView(status);
        newCode();
    }

    void newCode() {
        m.pool.execute(() -> {
            try {
                Answer a = cub("device/code", "client=lampa", null);
                if (a.code() != 200) throw new IOException("HTTP " + a.code());
                Cub.Code got = Cub.code(a.body());
                m.ui.post(() -> {
                    if (!m.alive(level)) return;
                    c = got;
                    until = System.currentTimeMillis() + got.expires() * 1000L;
                    site.setText(got.site());
                    code.setText(got.user());
                    status.setText("and enter this code. Waiting for it…");
                    later(got.interval());
                });
            } catch (IOException | JSONException e) {
                Log.w(Main.TAG, "CUB code", e);
                m.ui.post(() -> {
                    if (!m.alive(level)) return;
                    status.setText("CUB doesn't answer. Trying again…");
                    m.ui.postDelayed(this::newCode, 10000);
                });
            }
        });
    }

    void later(int seconds) {
        m.ui.postDelayed(this::ask, seconds * 1000L);
    }

    void ask() {
        if (!m.alive(level)) return;
        if (System.currentTimeMillis() > until) {
            newCode();
            return;
        }
        Cub.Code asked = c;
        m.pool.execute(() -> {
            try {
                Answer a = cub("device/token", "device_code=" + URLEncoder.encode(asked.device(), "UTF-8"), null);
                String token = a.code() == 200 ? Cub.token(a.body()) : null;
                if (token != null) {
                    List<Cub.Profile> all = Cub.profiles(cub("profiles/all", null, token).body());
                    m.ui.post(() -> pick(token, all));
                } else if (a.code() != 200 && Cub.newCode(a.body())) {
                    m.ui.post(() -> {
                        if (m.alive(level)) newCode();
                    });
                } else {
                    m.ui.post(() -> {
                        if (m.alive(level)) later(asked.interval());
                    });
                }
            } catch (IOException | JSONException e) {
                Log.w(Main.TAG, "CUB token", e);
                m.ui.post(() -> {
                    if (m.alive(level)) later(asked.interval());
                });
            }
        });
    }

    // Signed in: the profile Lampa uses is the main one unless switched, and grades are kept per profile, so
    // with more than one the user picks.
    void pick(String token, List<Cub.Profile> all) {
        if (!m.alive(level)) return;
        if (all.size() <= 1) { // none: the account itself, as before profiles
            save(token, all.isEmpty() ? new Cub.Profile(0, "", true) : all.get(0));
            return;
        }
        list.removeViews(1, list.getChildCount() - 1);
        list.addView(m.text("Signed in. Which profile does Lampa use?", 22, 0xffdddddd));
        for (Cub.Profile p : all) list.addView(m.row(p.name(), p.main() ? "Main profile" : null, v -> save(token, p)));
        list.getChildAt(2).requestFocus();
    }

    void save(String token, Cub.Profile p) {
        Cub.Account a = new Cub.Account(token, p.id(), p.name());
        try {
            Files.write(m.cubFile().toPath(), a.save().getBytes(StandardCharsets.UTF_8)); // private to the app
        } catch (IOException e) {
            Log.w(Main.TAG, "CUB account not saved", e);
            status.setText("Couldn't save the sign-in: " + e.getMessage());
            return;
        }
        m.cubAccount = Optional.of(a);
        if (m.pageTop == level) m.back(); // the level below, or Home when it was opened over Home
        done.run();
    }
}
