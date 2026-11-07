package local.lampanative;

import java.util.ArrayDeque;
import java.util.Locale;

/** The stats overlay's numbers (Settings → Stats overlay). No android.*: tested on the Mac (MeterTest). */
final class Meter {
    private Meter() {}

    // CPU time used so far (clock ticks): utime + stime, fields 14 and 15 of /proc/self/stat, counted after the
    // last ")" because the name in parentheses may hold spaces.
    static long ticks(String stat) {
        String[] f = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
        return Long.parseLong(f[11]) + Long.parseLong(f[12]);
    }

    // % of one core between two reads, as top shows it (400 % = the stick's 4 cores); the first read (before
    // = -1) has nothing to compare with.
    static int cpu(long ticksBefore, long ticksNow, long ms, long hz) {
        return ticksBefore < 0 || ms <= 0 ? 0 : (int) Math.round((ticksNow - ticksBefore) * 100_000.0 / hz / ms);
    }

    // A slow read on every so many samples, from the first.
    static boolean due(int sample, int every) {
        return sample % every == 0;
    }

    // A figure read at last (-1: never) is stale after ms.
    static boolean stale(long now, long last, long ms) {
        return last < 0 || now - last >= ms;
    }

    // getMemoryStat's kB; null (a stat this Android lacks) as 0.
    static int kb(String kb) {
        return kb == null ? 0 : Integer.parseInt(kb);
    }

    // PSS with the GPU, which Android measures apart (Fire OS hides an app's own from it), in MB.
    static int total(int pssKb, int graphicsKb) {
        return (pssKb + graphicsKb) / 1024;
    }

    // The overlay's first line, in MB.
    static String memory(int pssKb, int javaKb, int nativeKb, int graphicsKb) {
        return "PSS " + total(pssKb, graphicsKb) + " MB · Java " + javaKb / 1024 + " · Native " + nativeKb / 1024
            + " · GPU " + graphicsKb / 1024 + " (5 min)";
    }

    // The second line: CPU, then frames per second over the time that passed and the slowest one (GPU only).
    static String work(int cpu, int frames, long slowestNs, long ms, boolean gpu) {
        return "CPU " + cpu + " % · " + (gpu ? String.format(Locale.ROOT, "%.1f", frames * 1000.0 / Math.max(ms, 1))
            + " frames/s, slowest " + slowestNs / 1_000_000 + " ms" : "software drawing: no frame times");
    }

    // A graph's samples: the newest at the end, keep at most.
    static void add(ArrayDeque<Integer> samples, int x, int keep) {
        if (samples.size() == keep) samples.removeFirst();
        samples.addLast(x);
    }

    static int max(ArrayDeque<Integer> samples) {
        int m = 0;
        for (int x : samples) m = Math.max(m, x);
        return m;
    }
}
