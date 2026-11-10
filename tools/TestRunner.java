import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Runs every test* method of every *Test class under the given class directory; exits 1 on any failure. */
public class TestRunner {
    public static void main(String[] args) throws Exception {
        boolean assertsOn = false;
        assert assertsOn = true;
        if (!assertsOn) throw new IllegalStateException("run with java -ea, or every assert passes silently");

        Path root = Path.of(args[0]);
        List<Path> classes;
        try (Stream<Path> files = Files.walk(root)) {
            classes = files.filter(f -> f.toString().endsWith("Test.class")).sorted().toList();
        }
        int run = 0, failed = 0;
        for (Path p : classes) {
            Class<?> c = Class.forName(root.relativize(p).toString().replace('/', '.').replaceAll("\\.class$", ""));
            var make = c.getDeclaredConstructor();
            make.setAccessible(true);
            Method[] methods = c.getDeclaredMethods();
            Arrays.sort(methods, Comparator.comparing(Method::getName)); // getDeclaredMethods' order is unspecified
            for (Method m : methods) {
                if (!m.getName().startsWith("test")) continue;
                run++;
                m.setAccessible(true);
                try {
                    m.invoke(make.newInstance());
                } catch (InvocationTargetException e) {
                    failed++;
                    StackTraceElement[] at = e.getCause().getStackTrace(); // empty for an exception made without one
                    System.out.println("FAIL " + c.getSimpleName() + "." + m.getName() + ": " + e.getCause()
                        + (at.length > 0 ? " at " + at[0] : ""));
                }
            }
        }
        System.out.println(run + " tests, " + failed + " failed");
        if (failed > 0 || run == 0) System.exit(1);
    }
}
