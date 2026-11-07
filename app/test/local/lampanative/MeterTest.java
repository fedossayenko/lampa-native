package local.lampanative;

class MeterTest {
    // /proc/self/stat: utime and stime are fields 14 and 15; the name in parentheses may hold spaces and ")".
    void testTicks() {
        String stat = "4321 (local.lampa native)) S 600 600 0 0 -1 1077952832 9000 0 3 0 1500 250 0 0 20 0 30";
        assert Meter.ticks(stat) == 1750 : Meter.ticks(stat);
    }

    // % of one core, as top shows it (400 % = the stick's 4 cores).
    void testCpu() {
        assert Meter.cpu(1000, 1050, 2000, 100) == 25 : "50 ticks of 10 ms in 2 s";
        assert Meter.cpu(1000, 1000, 2000, 100) == 0;
        assert Meter.cpu(1000, 1800, 2000, 100) == 400 : "all four cores";
        assert Meter.cpu(1000, 1050, 0, 100) == 0 : "no time between reads";
        assert Meter.cpu(-1, 1050, 2000, 100) == 0 : "the first read has nothing before it";
    }

    // The last minute of samples, oldest first; the graph's top is the biggest one seen in it.
    void testSamples() {
        java.util.ArrayDeque<Integer> s = new java.util.ArrayDeque<>();
        for (int x : new int[] {5, 9, 1, 2}) Meter.add(s, x, 3);
        assert s.size() == 3 && s.peekFirst() == 9 && s.peekLast() == 2 : "the oldest went";
        assert Meter.max(s) == 9;
        Meter.add(s, 3, 3);
        assert Meter.max(s) == 3 : "9 has left the window";
        assert Meter.max(new java.util.ArrayDeque<>()) == 0;
    }

    // getMemoryStat's kB; a stat the platform lacks reads as 0.
    void testKb() {
        assert Meter.kb("20480") == 20480 && Meter.kb(null) == 0;
    }

    // The overlay's second line: frames per second over the sample and the slowest frame; software has neither.
    void testWorkLine() {
        String gpu = Meter.work(4, 24, 9_400_000L, 2000, true);
        assert gpu.equals("CPU 4 % · 12.0 frames/s, slowest 9 ms") : gpu;
        assert Meter.work(4, 30, 0, 2500, true).contains("12.0 frames/s") : "over the time that really passed";
        String still = Meter.work(4, 1, 5_000_000L, 2000, true);
        assert still.equals("CPU 4 % · 0.5 frames/s, slowest 5 ms") : "a still screen: the overlay's own redraw";
        assert Meter.work(4, 0, 0, 2000, false).equals("CPU 4 % · software drawing: no frame times");
    }

    // The first line: PSS with the GPU that Android measures apart (Fire OS hides it from the app), all in MB.
    void testMemoryLine() {
        String m = Meter.memory(45_000, 3_100, 20_500, 17_408);
        assert m.equals("PSS 60 MB · Java 3 · Native 20 · GPU 17 (5 min)") : m;
        assert Meter.total(45_000, 17_408) == 60 : "the graph's point";
    }

    // When the slow reads happen: the memory walk on every 15th sample (30 s) from the first; Android's GPU figure
    // only once its 5 minutes are up.
    void testWhenToRead() {
        assert Meter.due(0, 15) && !Meter.due(1, 15) && !Meter.due(14, 15) && Meter.due(15, 15);
        assert Meter.stale(1000, -1, 300_000) : "never read";
        assert !Meter.stale(300_999, 1000, 300_000) && Meter.stale(301_000, 1000, 300_000);
    }
}
