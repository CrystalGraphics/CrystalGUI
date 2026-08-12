import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * A file for RUNNING, not for reading — every section prints what it did.
 *
 * <p>Open it in the editor and press <b>Shift+F10</b>. Each section logs a line, so the console is a
 * transcript of which language features executed and what they produced. If a section is missing from
 * the output, that is where it stopped.</p>
 *
 * <p>It is an ordinary compilation unit with a {@code static void main}, not a script body, so it takes
 * the compile-as-is path rather than being wrapped in a prelude — which is the distinction M7a's
 * {@code ScriptPrelude.declaresType} exists to make.</p>
 *
 * <p><b>Targets Java 17</b>, which is what the harness JVM allows. Records, sealed types, text blocks,
 * switch expressions and {@code instanceof} patterns are all in; string templates and switch patterns
 * are not, because they are newer than the band the harness runs on.</p>
 */
public class RunTest {

    // ── The logger ──────────────────────────────────────────────────────────────────────────────

    private static final AtomicInteger SECTION = new AtomicInteger();
    private static final long STARTED = System.nanoTime();

    private static void section(String title) {
        System.out.printf("%n=== %02d  %s %s%n", SECTION.incrementAndGet(), title,
                "=".repeat(Math.max(0, 56 - title.length())));
    }

    private static void log(String label, Object value) {
        System.out.printf("   %-28s %s%n", label, value);
    }

    private static void log(String message) {
        System.out.printf("   %s%n", message);
    }

    /** Runs a section and reports a throw rather than ending the whole file. */
    private static void attempt(String title, Runnable body) {
        section(title);
        try {
            body.run();
        } catch (Throwable failed) {
            log("!! threw", failed);
        }
    }

    // ── Static and instance initialisation, in the order they actually run ──────────────────────

    private static final String STATIC_FINAL = "initialised in a static field";
    private static String staticFromBlock;

    static {
        staticFromBlock = "initialised in a static block";
    }

    private final String instanceField = "initialised in an instance field";
    private String instanceFromBlock;

    {
        instanceFromBlock = "initialised in an instance block";
    }

    private RunTest() {
        log("constructor ran", instanceField + " / " + instanceFromBlock);
    }

    // ── Types the later sections use ────────────────────────────────────────────────────────────

    /** A record: components, a compact constructor, a derived accessor. */
    record Point(int x, int y) {
        Point {
            if (x < 0 || y < 0) throw new IllegalArgumentException("negative: " + x + "," + y);
        }

        public double distance() {
            return Math.sqrt(x * x + y * y);
        }
    }

    /** A sealed hierarchy — the compiler knows the permitted set is exhaustive. */
    sealed interface Shape permits Circle, Rect {
    }

    record Circle(double radius) implements Shape {
    }

    record Rect(double width, double height) implements Shape {
    }

    /** An enum carrying state and behaviour, not just names. */
    enum Level {
        LOW(1, "quiet"),
        MEDIUM(5, "normal"),
        HIGH(9, "loud");

        private final int weight;
        private final String description;

        Level(int weight, String description) {
            this.weight = weight;
            this.description = description;
        }

        int weight() {
            return weight;
        }

        boolean louderThan(Level other) {
            return weight > other.weight;
        }

        @Override
        public String toString() {
            return name() + "(" + weight + "," + description + ")";
        }
    }

    /** An interface with a default and a static method. */
    interface Greeter {
        String name();

        default String greet() {
            return "hello, " + name();
        }

        static Greeter of(String name) {
            return () -> name;
        }
    }

    /** An abstract base, to show dispatch and `super`. */
    abstract static class Animal {
        private final String name;

        Animal(String name) {
            this.name = name;
        }

        String name() {
            return name;
        }

        abstract String sound();

        String describe() {
            return name + " says " + sound();
        }
    }

    static final class Dog extends Animal {
        Dog(String name) {
            super(name);
        }

        @Override
        String sound() {
            return "woof";
        }

        @Override
        String describe() {
            return "[dog] " + super.describe();
        }
    }

    static final class Cat extends Animal {
        Cat(String name) {
            super(name);
        }

        @Override
        String sound() {
            return "meow";
        }
    }

    /** A generic container with a bounded parameter. */
    static final class Box<T extends Comparable<T>> {
        private final List<T> items = new ArrayList<>();

        Box<T> add(T item) {
            items.add(item);
            return this;
        }

        Optional<T> largest() {
            return items.stream().max(Comparator.naturalOrder());
        }

        int size() {
            return items.size();
        }
    }

    /** A custom checked exception, and a resource for try-with-resources. */
    static final class RunTestException extends Exception {
        RunTestException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static final class Resource implements AutoCloseable {
        private final String name;

        Resource(String name) {
            this.name = name;
            log("opened", name);
        }

        String read() {
            return "contents of " + name;
        }

        @Override
        public void close() {
            log("closed", name);
        }
    }

    /** A custom Iterable, so the enhanced-for section is not only about collections. */
    static final class Countdown implements Iterable<Integer> {
        private final int from;

        Countdown(int from) {
            this.from = from;
        }

        @Override
        public Iterator<Integer> iterator() {
            return new Iterator<>() {
                private int next = from;

                @Override
                public boolean hasNext() {
                    return next > 0;
                }

                @Override
                public Integer next() {
                    return next--;
                }
            };
        }
    }

    // ── main ────────────────────────────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        System.out.println("RunTest starting - args: " + Arrays.toString(args));
        new RunTest();

        attempt("primitives, overflow and bit operations", RunTest::primitives);
        attempt("strings, text blocks and formatting", RunTest::strings);
        attempt("arrays, var and varargs", RunTest::arraysVarAndVarargs);
        attempt("control flow, labels and switch", RunTest::controlFlow);
        attempt("collections and iteration", RunTest::collections);
        attempt("generics and wildcards", RunTest::generics);
        attempt("records", RunTest::records);
        attempt("sealed types and pattern matching", RunTest::sealedTypes);
        attempt("enums", RunTest::enums);
        attempt("inheritance and polymorphism", RunTest::inheritance);
        attempt("interfaces: default, static, functional", RunTest::interfaces);
        attempt("lambdas and method references", RunTest::lambdas);
        attempt("streams", RunTest::streams);
        attempt("optional", RunTest::optionals);
        attempt("nested, anonymous and local classes", RunTest::nestedClasses);
        attempt("exceptions and try-with-resources", RunTest::exceptions);
        attempt("threads", RunTest::threads);
        attempt("reflection", RunTest::reflection);
        attempt("time and math", RunTest::timeAndMath);

        System.out.printf("%nRunTest finished - %d sections in %d ms%n",
                SECTION.get(), Duration.ofNanos(System.nanoTime() - STARTED).toMillis());
    }

    // ── Sections ────────────────────────────────────────────────────────────────────────────────

    private static void primitives() {
        int max = Integer.MAX_VALUE;
        log("Integer.MAX_VALUE", max);
        log("overflow wraps to", max + 1);
        log("long arithmetic", (long) max + 1);
        log("integer division", 7 / 2);
        log("floating division", 7 / 2.0);
        log("modulo of a negative", -7 % 3);
        log("char arithmetic", (char) ('a' + 2));
        log("cast narrows", (byte) 300);
        log("shift left", 1 << 10);
        log("unsigned shift right", -8 >>> 28);
        log("and / or / xor", (12 & 10) + " / " + (12 | 10) + " / " + (12 ^ 10));
        log("bit count of 255", Integer.bitCount(255));
        log("autoboxing equality trap", cachedBoxes() + " vs " + uncachedBoxes());
    }

    private static String cachedBoxes() {
        Integer a = 127;
        Integer b = 127;
        return "127==127 -> " + (a == b);
    }

    private static String uncachedBoxes() {
        Integer a = 128;
        Integer b = 128;
        return "128==128 -> " + (a == b) + " (cache stops at 127)";
    }

    private static void strings() {
        String plain = "the quick brown fox";
        log("length / upper", plain.length() + " / " + plain.toUpperCase());
        log("split", Arrays.toString(plain.split(" ")));
        log("join", String.join("-", "a", "b", "c"));
        log("replace", plain.replace("quick", "slow"));
        log("contains / indexOf", plain.contains("brown") + " / " + plain.indexOf("brown"));
        log("strip and isBlank", "  padded  ".strip() + " / " + "   ".isBlank());
        log("repeat", "ab".repeat(3));
        log("chars", plain.chars().filter(c -> c == 'o').count() + " letter o");
        log("formatted", "%s has %d chars".formatted(plain, plain.length()));

        String block = """
                a text block
                  keeps its indentation
                and its line breaks""";
        log("text block lines", block.lines().count());
        for (String line : block.lines().toList()) log("  | " + line);

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 5; i++) builder.append(i).append(',');
        builder.setLength(builder.length() - 1);
        log("StringBuilder", builder.reverse());

        log("equals vs ==", identityVersusEquality());
    }

    private static String identityVersusEquality() {
        String literal = "interned";
        String built = new StringBuilder("intern").append("ed").toString();
        return "equals -> " + literal.equals(built) + ", == -> " + (literal == built);
    }

    private static void arraysVarAndVarargs() {
        var numbers = new int[]{5, 3, 9, 1, 7};
        log("array", Arrays.toString(numbers));
        int[] sorted = numbers.clone();
        Arrays.sort(sorted);
        log("sorted copy", Arrays.toString(sorted));
        log("original untouched", Arrays.toString(numbers));
        log("binarySearch for 7", Arrays.binarySearch(sorted, 7));
        log("fill", Arrays.toString(new int[3]));

        int[][] grid = new int[3][3];
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) grid[row][column] = row * 3 + column;
        }
        log("2-D array", Arrays.deepToString(grid));

        log("varargs of 3", sum(1, 2, 3));
        log("varargs of 0", sum());
        log("varargs from array", sum(numbers));

        var inferred = new LinkedHashMap<String, List<Integer>>();
        inferred.put("odds", List.of(1, 3, 5));
        log("var with generics", inferred);
    }

    private static int sum(int... values) {
        int total = 0;
        for (int value : values) total += value;
        return total;
    }

    private static void controlFlow() {
        StringBuilder trace = new StringBuilder();

        outer:
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                if (j == 2) continue outer;
                if (i == 3) break outer;
                trace.append(i).append(j).append(' ');
            }
        }
        log("labelled break/continue", trace.toString().trim());

        int countdown = 3;
        StringBuilder doWhile = new StringBuilder();
        do {
            doWhile.append(countdown).append(' ');
        } while (--countdown > 0);
        log("do-while", doWhile.toString().trim());

        log("ternary", 5 > 3 ? "yes" : "no");

        for (Level level : Level.values()) {
            String verdict = switch (level) {
                case LOW -> "barely there";
                case MEDIUM -> "audible";
                case HIGH -> {
                    String shouted = level.description.toUpperCase();
                    yield shouted + "!";
                }
            };
            log("switch expression " + level.name(), verdict);
        }

        log("old-style switch fallthrough", oldStyleSwitch(2));
    }

    private static String oldStyleSwitch(int value) {
        StringBuilder hit = new StringBuilder();
        switch (value) {
            case 1:
                hit.append("one ");
            case 2:
                hit.append("two ");
            case 3:
                hit.append("three ");
                break;
            default:
                hit.append("other ");
        }
        return hit.toString().trim();
    }

    private static void collections() {
        List<String> names = new ArrayList<>(List.of("delta", "alpha", "charlie", "bravo"));
        log("list", names);
        names.sort(Comparator.naturalOrder());
        log("sorted", names);
        names.sort(Comparator.comparing(String::length).thenComparing(Comparator.reverseOrder()));
        log("by length then reverse", names);
        names.removeIf(name -> name.startsWith("a"));
        log("removeIf a*", names);

        Map<String, Integer> lengths = new LinkedHashMap<>();
        for (String name : names) lengths.put(name, name.length());
        log("map", lengths);
        lengths.merge("bravo", 100, Integer::sum);
        log("merge", lengths);
        log("getOrDefault", lengths.getOrDefault("missing", -1));
        lengths.computeIfAbsent("echo", key -> key.length());
        log("computeIfAbsent", lengths);

        Set<Integer> unique = new TreeSet<>(List.of(5, 1, 5, 3, 1));
        log("TreeSet dedupes and sorts", unique);

        Map<Boolean, List<Integer>> split = IntStream.rangeClosed(1, 10).boxed()
                .collect(Collectors.partitioningBy(n -> n % 2 == 0));
        log("partitioned", split);

        log("custom Iterable", stream(new Countdown(5)));

        Iterator<String> iterator = names.iterator();
        StringBuilder walked = new StringBuilder();
        while (iterator.hasNext()) walked.append(iterator.next()).append(' ');
        log("explicit iterator", walked.toString().trim());
    }

    private static String stream(Iterable<Integer> iterable) {
        StringBuilder out = new StringBuilder();
        for (int value : iterable) out.append(value).append(' ');
        return out.toString().trim();
    }

    private static void generics() {
        Box<String> words = new Box<String>().add("pear").add("apple").add("quince");
        log("Box<String> size", words.size());
        log("largest", words.largest().orElse("<none>"));

        Box<Integer> numbers = new Box<Integer>().add(3).add(11).add(7);
        log("Box<Integer> largest", numbers.largest().orElse(-1));

        log("generic method", firstOf(List.of("only", "these", "three")));
        log("wildcard total", totalOf(List.of(1, 2.5, 3L)));
        log("bounded pair", pair("key", 42));
    }

    private static <T> T firstOf(List<T> items) {
        return items.isEmpty() ? null : items.get(0);
    }

    private static double totalOf(List<? extends Number> numbers) {
        double total = 0;
        for (Number number : numbers) total += number.doubleValue();
        return total;
    }

    private static <K, V> String pair(K key, V value) {
        return key + "=" + value;
    }

    private static void records() {
        Point origin = new Point(0, 0);
        Point far = new Point(3, 4);
        log("record toString", far);
        log("accessors", far.x() + "," + far.y());
        log("derived method", far.distance());
        log("equals by value", far.equals(new Point(3, 4)));
        log("hashCode by value", far.hashCode() == new Point(3, 4).hashCode());
        log("origin", origin);
        try {
            new Point(-1, 0);
        } catch (IllegalArgumentException refused) {
            log("compact constructor refused", refused.getMessage());
        }
    }

    private static void sealedTypes() {
        List<Shape> shapes = List.of(new Circle(2), new Rect(3, 4), new Circle(0.5));
        for (Shape shape : shapes) {
            // instanceof pattern: the variable is typed and in scope only where it matched.
            if (shape instanceof Circle circle) {
                log("circle r=" + circle.radius(), "area " + round(Math.PI * circle.radius() * circle.radius()));
            } else if (shape instanceof Rect rect) {
                log("rect " + rect.width() + "x" + rect.height(), "area " + round(rect.width() * rect.height()));
            }
        }
        log("total area", round(shapes.stream().mapToDouble(RunTest::areaOf).sum()));
    }

    private static double areaOf(Shape shape) {
        if (shape instanceof Circle circle) return Math.PI * circle.radius() * circle.radius();
        if (shape instanceof Rect rect) return rect.width() * rect.height();
        return 0;
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    private static void enums() {
        for (Level level : Level.values()) {
            log("ordinal " + level.ordinal(), level + " louder than LOW? " + level.louderThan(Level.LOW));
        }
        log("valueOf", Level.valueOf("MEDIUM"));
        log("sorted by weight", Arrays.stream(Level.values())
                .sorted(Comparator.comparingInt(Level::weight).reversed())
                .map(Enum::name).collect(Collectors.joining(" > ")));

        Map<Level, String> byLevel = new java.util.EnumMap<>(Level.class);
        byLevel.put(Level.HIGH, "alarm");
        byLevel.put(Level.LOW, "hum");
        log("EnumMap keeps declaration order", byLevel);
    }

    private static void inheritance() {
        List<Animal> animals = List.of(new Dog("rex"), new Cat("mog"));
        for (Animal animal : animals) log(animal.getClass().getSimpleName(), animal.describe());
        log("polymorphic dispatch", animals.stream().map(Animal::sound).collect(Collectors.joining("/")));
        log("instanceof through the hierarchy", animals.get(0) instanceof Animal);
    }

    private static void interfaces() {
        Greeter named = Greeter.of("world");
        log("static factory + lambda", named.greet());

        Greeter anonymous = new Greeter() {
            @Override
            public String name() {
                return "anonymous class";
            }

            @Override
            public String greet() {
                return "overridden: " + name();
            }
        };
        log("default overridden", anonymous.greet());

        Supplier<String> supplier = () -> "from a Supplier";
        Function<Integer, Integer> doubler = n -> n * 2;
        BiFunction<Integer, Integer, Integer> adder = Integer::sum;
        log("Supplier", supplier.get());
        log("Function composed", doubler.andThen(doubler).apply(5));
        log("BiFunction", adder.apply(20, 22));
    }

    private static void lambdas() {
        List<String> words = new ArrayList<>(List.of("gamma", "alpha", "beta"));
        words.sort((left, right) -> left.compareTo(right));
        log("lambda comparator", words);
        words.forEach(word -> log("  forEach", word));

        Function<String, Integer> length = String::length;
        log("method reference", length.apply("measured"));

        Supplier<List<String>> constructorReference = ArrayList::new;
        log("constructor reference", constructorReference.get().size() + " (empty)");

        int captured = 10;
        Function<Integer, Integer> closesOver = n -> n + captured;
        log("closure over a local", closesOver.apply(5));
    }

    private static void streams() {
        List<String> words = List.of("apple", "banana", "cherry", "date", "elderberry", "fig");

        log("filter + map", words.stream()
                .filter(word -> word.length() > 4)
                .map(String::toUpperCase)
                .collect(Collectors.toList()));
        log("reduce", words.stream().reduce("", (a, b) -> a.isEmpty() ? b : a + "|" + b));
        log("count / anyMatch", words.stream().filter(w -> w.contains("a")).count()
                + " / " + words.stream().anyMatch(w -> w.startsWith("z")));
        log("groupingBy length", words.stream().collect(Collectors.groupingBy(String::length)));
        log("joining", words.stream().limit(3).collect(Collectors.joining(", ", "[", "]")));
        log("IntStream sum", IntStream.rangeClosed(1, 100).sum());
        log("summaryStatistics", words.stream().mapToInt(String::length).summaryStatistics());
        log("flatMap", Stream.of(List.of(1, 2), List.of(3, 4))
                .flatMap(List::stream).collect(Collectors.toList()));
        log("sorted distinct", Stream.of(3, 1, 3, 2, 1).distinct().sorted().collect(Collectors.toList()));
        log("iterate + limit", Stream.iterate(1, n -> n * 2).limit(8).collect(Collectors.toList()));
        log("toMap", words.stream().limit(3)
                .collect(Collectors.toMap(w -> w.charAt(0), Function.identity())));
    }

    private static void optionals() {
        Optional<String> present = Optional.of("value");
        Optional<String> empty = Optional.empty();
        log("isPresent", present.isPresent() + " / " + empty.isPresent());
        log("orElse", empty.orElse("fallback"));
        log("map", present.map(String::toUpperCase).orElse("?"));
        log("filter that fails", present.filter(v -> v.startsWith("x")).orElse("filtered out"));
        log("orElseGet", empty.orElseGet(() -> "computed"));
        present.ifPresent(value -> log("ifPresent", value));
        log("ofNullable(null)", Optional.ofNullable(null).isEmpty());
    }

    private static void nestedClasses() {
        class Local {
            private final String where = "a local class, declared inside a method";

            String describe() {
                return where;
            }
        }
        log("local class", new Local().describe());

        Runnable anonymous = new Runnable() {
            @Override
            public void run() {
                log("anonymous class", "ran");
            }
        };
        anonymous.run();

        RunTest outer = new RunTest();
        Inner inner = outer.new Inner("holds a reference to its outer instance");
        log("inner class", inner.describe());
        log("static nested", new StaticNested().describe());
    }

    /** A non-static inner class — it captures the enclosing instance. */
    final class Inner {
        private final String note;

        Inner(String note) {
            this.note = note;
        }

        String describe() {
            return note + " (outer field: " + instanceField + ")";
        }
    }

    /** A static nested class — no enclosing instance. */
    static final class StaticNested {
        String describe() {
            return "a static nested class, no outer instance";
        }
    }

    private static void exceptions() {
        try {
            throw new IllegalStateException("thrown on purpose");
        } catch (IllegalStateException caught) {
            log("caught", caught.getMessage());
        } finally {
            log("finally", "always runs");
        }

        try {
            Object notAString = 42;
            String bad = (String) notAString;
            log("unreachable", bad);
        } catch (ClassCastException | NullPointerException caught) {
            log("multi-catch", caught.getClass().getSimpleName());
        }

        try (Resource first = new Resource("first.txt");
             Resource second = new Resource("second.txt")) {
            log("read", first.read() + " + " + second.read());
        }

        try {
            wrapAndRethrow();
        } catch (RunTestException wrapped) {
            log("custom exception", wrapped.getMessage());
            log("  cause", wrapped.getCause());
        }

        log("stack depth of a caught throw", depthOf());
    }

    private static void wrapAndRethrow() throws RunTestException {
        try {
            Integer.parseInt("not a number");
        } catch (NumberFormatException cause) {
            throw new RunTestException("could not parse the configured value", cause);
        }
    }

    private static int depthOf() {
        try {
            throw new RuntimeException("measured");
        } catch (RuntimeException caught) {
            return caught.getStackTrace().length;
        }
    }

    private static void threads() {
        AtomicInteger counter = new AtomicInteger();
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            final int id = i;
            Thread worker = new Thread(() -> {
                for (int n = 0; n < 1000; n++) counter.incrementAndGet();
                log("  worker " + id, "done");
            }, "run-test-" + i);
            workers.add(worker);
            worker.start();
        }
        for (Thread worker : workers) {
            try {
                worker.join(5000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                log("interrupted while joining", worker.getName());
            }
        }
        log("atomic total (expect 4000)", counter.get());

        Map<String, Integer> concurrent = new java.util.concurrent.ConcurrentHashMap<>();
        concurrent.put("a", 1);
        concurrent.merge("a", 10, Integer::sum);
        log("ConcurrentHashMap", concurrent);
    }

    private static void reflection() {
        Class<?> type = Point.class;
        log("class", type.getName());
        log("is record", type.isRecord());
        log("record components", Arrays.stream(type.getRecordComponents())
                .map(component -> component.getType().getSimpleName() + " " + component.getName())
                .collect(Collectors.joining(", ")));
        log("declared methods", Arrays.stream(type.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName).sorted().collect(Collectors.joining(", ")));
        try {
            Object instance = type.getDeclaredConstructor(int.class, int.class).newInstance(6, 8);
            log("reflective construction", instance);
            log("reflective call", type.getMethod("distance").invoke(instance));
        } catch (ReflectiveOperationException failed) {
            // "!!" is the fixture's one mark for a defect, shared with attempt() above -- so a section
            // that CATCHES its own failure is still visible to RunTestFixtureTest. Without the shared
            // prefix this line reported a broken reflective lookup under a heading that reads as the
            // engine being broken, and the test saw a section that had not thrown and passed.
            log("!! reflection failed", failed);
        }

        // NOTE FOR MINECRAFT HOSTS: the mapping boundary rewrites symbolic references, not strings, so
        // a reflective lookup BY NAME sees runtime names in production. Fine here (the harness mapping
        // is identity); see plan_syntax.md §15.5 D.1.
        log("loaded by", RunTest.class.getClassLoader().getClass().getSimpleName());
    }

    private static void timeAndMath() {
        LocalDate today = LocalDate.now();
        log("today", today);
        log("plus 90 days", today.plusDays(90));
        log("day of week", today.getDayOfWeek());
        log("leap year", today.isLeapYear());
        log("duration", Duration.ofMinutes(135).toHoursPart() + "h "
                + Duration.ofMinutes(135).toMinutesPart() + "m");

        log("abs / signum", Math.abs(-7) + " / " + Math.signum(-7.0));
        log("floorDiv vs /", Math.floorDiv(-7, 2) + " vs " + (-7 / 2));
        log("floorMod vs %", Math.floorMod(-7, 3) + " vs " + (-7 % 3));
        log("pow / sqrt", Math.pow(2, 10) + " / " + Math.sqrt(144));
        log("round half up", Math.round(2.5) + " and " + Math.round(-2.5));
        log("clamped", Math.min(Math.max(15, 0), 10));
        log("BigInteger", java.math.BigInteger.valueOf(2).pow(100));
        log("BigDecimal exactness", new java.math.BigDecimal("0.1")
                .add(new java.math.BigDecimal("0.2")) + " (double gives " + (0.1 + 0.2) + ")");
    }

    // ── Not called: uncomment the body to test Stop ─────────────────────────────────────────────

    /**
     * A deliberate runaway, for testing the kill switch.
     *
     * <p>Call it from {@link #main} and press Run: the script will spin forever. <b>Mod+F2</b> stops it,
     * which works because the output pass injects a safepoint check at every backward branch — an
     * ordinary {@code Thread.interrupt} does nothing to a loop that never blocks.</p>
     */
    @SuppressWarnings("unused")
    private static void runawayForStopTesting() {
        section("runaway - press Mod+F2 to stop");
        long spins = 0;
        while (true) {
            spins++;
            if (spins % 50_000_000L == 0) log("still spinning", spins);
        }
    }
}
