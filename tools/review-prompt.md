Review the git diff on stdin for lampa-native: a native Android TV app for a Fire TV Stick 4K (Android 11 /
API 30, 1.7 GB RAM, 4 slow cores, PowerVR GE9215), its Cloudflare worker routes and its scripts. Read PLAN.md
first: its budgets and design rules are requirements. Open any file in the repository you need for context.

The app's whole point is to use as little CPU and memory as possible. Review in two parts.

## Problems (each as `path:line — what goes wrong, and when`)
- bugs: wrong behavior, crashes, races between the UI thread and the loader threads, leaked views, bitmaps,
  threads or connections;
- CPU or memory regressions: a new library or framework, animations, blur, bitmaps kept off-screen or in the
  background, work on the UI thread, background timers or services, anything that breaks a PLAN.md budget;
- API level: anything that does not exist on Android 11 (API 30);
- security: secrets in code, logs or the APK; API keys leaving the worker;
- TDD: new or changed logic without a test in app/test (view glue in Main.java is exempt).

## Efficiency: can the changed code be cheaper? (each as `path:line — cheaper way, expected gain`)
Look hard at the hot paths (key presses, scrolling, drawing, poster loading, parsing) and at what stays in memory:
allocations per frame or per item, repeated layout or measurement, views that could be fewer, images bigger than
they are drawn, decoding or downloading more than once, work the worker could do instead of the stick, threads
that could be fewer. Suggest only changes with a real gain on this stick, and say how to measure it
(`tools/measure.sh`, `dumpsys meminfo`). Write "none" when you find nothing worth doing.

## Simpler? (each as `path:line — what to delete or replace, with what`)
Code that could be shorter with the same behavior and cost: dead code, needless abstraction, hand-written code
the platform or Java 21 already provides (records, switch patterns, java.util), duplicated logic. If the change
would be markedly simpler or cheaper with a different approach altogether, say so in two lines. Write "none"
when there is nothing.

No style comments, no praise, no summary of the change.

End with exactly one line: `VERDICT: PASS`, or `VERDICT: BLOCK` if at least one item under Problems is a
definite bug, CPU or memory regression, API-level problem, security problem or untested logic. Efficiency and
simplification suggestions alone never block.
