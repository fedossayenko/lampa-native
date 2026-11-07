package local.lampanative;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Debug;
import android.os.Handler;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.view.FrameMetrics;
import android.view.View;
import android.view.Window;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;

/**
 * Settings → Stats overlay: this app's CPU and frames every 2 s, its memory every 30 s (getMemoryInfo walks
 * /proc/self/smaps: tens of ms), read on the executor given, with their last samples as lines. Off, it doesn't
 * exist.
 */
final class MeterView extends View {
    private static final long EVERY = 2000;
    private static final int KEEP = 30, MEMORY_EVERY = 15; // CPU: a minute; memory: 15 minutes
    private final ArrayDeque<Integer> pss = new ArrayDeque<>(), cpu = new ArrayDeque<>();
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Executor pool;
    private final Handler ui;
    private final Window window;
    private final boolean gpu; // frame times come from the GPU renderer only
    private final long hz = Os.sysconf(OsConstants._SC_CLK_TCK);
    private final float dp;
    private final ActivityManager am;
    private String memory = "Measuring…", work = "";
    // UI thread: frames since the last sample, and when that was; which sample this is
    private int frames, samples;
    private long slowestNs, framesAt;
    // the executor's one thread at a time: the last CPU read, Android's last GPU figure
    private long ticks = -1, cpuAt, graphicsAt = -1;
    private int graphicsKb;
    private boolean running;
    private final Window.OnFrameMetricsAvailableListener onFrame = (w, m, dropped) -> {
        frames++;
        slowestNs = Math.max(slowestNs, m.getMetric(FrameMetrics.TOTAL_DURATION));
    };
    private final Runnable tick = this::sample;

    private record Memory(String line, int mb) {}

    MeterView(Context context, Window window, boolean gpu, Executor pool, Handler ui) {
        super(context);
        this.window = window;
        this.gpu = gpu;
        this.pool = pool;
        this.ui = ui;
        dp = context.getResources().getDisplayMetrics().density;
        am = context.getSystemService(ActivityManager.class);
        text.setColor(0xffffffff);
        text.setTextSize(13 * dp);
        line.setStrokeWidth(2 * dp);
    }

    void start() {
        running = true;
        if (gpu) window.addOnFrameMetricsAvailableListener(onFrame, ui);
        framesAt = SystemClock.uptimeMillis();
        ui.post(tick);
    }

    void stop() {
        running = false;
        ui.removeCallbacks(tick);
        if (gpu) window.removeOnFrameMetricsAvailableListener(onFrame);
    }

    private void sample() {
        if (!running) return;
        long now = SystemClock.uptimeMillis(), span = now - framesAt; // the next sample waits for this one
        int f = frames;
        long slow = slowestNs;
        frames = 0;
        slowestNs = 0;
        framesAt = now;
        boolean readMemory = Meter.due(samples++, MEMORY_EVERY);
        pool.execute(() -> {
            long at = SystemClock.elapsedRealtime(), t; // not the wall clock: it jumps when the stick sets its time
            try {
                t = Meter.ticks(new String(Files.readAllBytes(Paths.get("/proc/self/stat")), StandardCharsets.UTF_8));
            } catch (IOException e) {
                t = ticks;
            }
            int c = Meter.cpu(ticks, t, at - cpuAt, hz);
            ticks = t;
            cpuAt = at;
            Memory m = null;
            if (readMemory) {
                Debug.MemoryInfo mi = new Debug.MemoryInfo();
                Debug.getMemoryInfo(mi); // fresh, without the GPU: Fire OS doesn't let an app read its own
                if (Meter.stale(at, graphicsAt, 300_000)) { // Android measures it at most every 5 minutes
                    graphicsKb = Meter.kb(am.getProcessMemoryInfo(new int[] {Process.myPid()})[0]
                        .getMemoryStat("summary.graphics"));
                    graphicsAt = at;
                }
                m = new Memory(Meter.memory(mi.getTotalPss(), Meter.kb(mi.getMemoryStat("summary.java-heap")),
                    Meter.kb(mi.getMemoryStat("summary.native-heap")), graphicsKb),
                    Meter.total(mi.getTotalPss(), graphicsKb));
            }
            String w = Meter.work(c, f, slow, span, gpu);
            Memory read = m;
            ui.post(() -> {
                if (!running) return;
                if (read != null) {
                    memory = read.line();
                    Meter.add(pss, read.mb(), KEEP);
                }
                Meter.add(cpu, c, KEEP);
                work = w;
                invalidate();
                ui.postDelayed(tick, EVERY);
            });
        });
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(0xc0000000);
        float pad = 8 * dp, y = pad + 13 * dp;
        c.drawText(memory, pad, y, text);
        c.drawText(work, pad, y + 18 * dp, text);
        y += 36 * dp;
        float half = (getWidth() - 3 * pad) / 2, h = getHeight() - y - pad;
        graph(c, pss, "PSS, 15 min, max " + Meter.max(pss) + " MB", 0xff4fc3f7, pad, y, half, h);
        graph(c, cpu, "CPU, 1 min, max " + Meter.max(cpu) + " %", 0xffffb74d, 2 * pad + half, y, half, h);
    }

    // A series in a box, scaled to its biggest sample.
    private void graph(Canvas c, ArrayDeque<Integer> s, String label, int color, float left, float top, float w,
                       float h) {
        line.setColor(color);
        text.setColor(color);
        c.drawText(label, left, top + 11 * dp, text);
        text.setColor(0xffffffff);
        float base = top + h, room = h - 16 * dp, step = w / (KEEP - 1), px = 0, py = 0;
        int max = Math.max(Meter.max(s), 1), i = 0;
        for (int x : s) {
            float nx = left + i * step, ny = base - room * x / max;
            if (i++ > 0) c.drawLine(px, py, nx, ny, line);
            px = nx;
            py = ny;
        }
    }
}
