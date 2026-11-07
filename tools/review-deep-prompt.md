You are reviewing the whole of lampa-native, not a diff: a native Android TV app for a Fire TV Stick 4K (Android 11
/ API 30, 32-bit userland, 1.7 GB RAM, 4 slow cores, PowerVR GE9215) that replaces the Lampa web app for one
household, with a Cloudflare worker doing the heavy lifting. Read PLAN.md first (budgets, design rules, the
measurement log, "Alternatives"), then the code in app/, tools/ and the worker routes it calls.

Question: if we started from scratch today, knowing everything in PLAN.md's measurement log, would a different
approach be lighter on CPU and memory, or markedly simpler, for the same features? Consider at least: the current
plain-View approach, custom-drawn rows (one View per row), androidx Leanback / RecyclerView, Jetpack Compose for
TV, Flutter, a native (NDK) UI, a lighter web UI in a WebView, and moving more work to the worker. Use the
measurements in PLAN.md, not general reputation; say what you would measure on the stick to settle a doubt.

Answer in three parts:
1. Verdict: keep the approach, or switch (to what) — two lines.
2. The two or three biggest remaining costs in the current code (CPU, memory, complexity), each with the
   cheapest fix and how to measure it.
3. What PLAN.md's later steps (film page, TorBox sources, DDD playback, search, CUB sync) should do differently
   from what is planned, if anything.

No praise. End with exactly one line: `VERDICT: PASS` (keep the approach) or `VERDICT: BLOCK` (switch).
