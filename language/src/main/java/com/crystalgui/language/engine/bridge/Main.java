package com.crystalgui.language.engine.bridge;

import com.crystalgui.render.CgUiPaintContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;


/**
 * A deliberately over-featured file, for looking at colours.
 *
 * <p>This exists to exercise the highlighter rather than to do anything: every construct below is here
 * because some capture name in {@code highlights.scm} is attached to it. If a row of this file is the
 * same colour as the row above it and should not be, that is the bug.</p>
 *
 * <p>Sections, roughly in the order they stress things:</p>
 * <ol>
 *   <li>constants, numeric literal forms, and escapes</li>
 *   <li>enums, records, sealed hierarchies</li>
 *   <li>generics, wildcards, bounded types</li>
 *   <li>lambdas, method references, functional interfaces</li>
 *   <li>control flow, switch expressions, pattern matching</li>
 *   <li>inner, static-nested and anonymous classes</li>
 *   <li>non-ASCII text, which is where offset bugs live</li>
 * </ol>
 *
 * @author nobody
 * @see Stream
 */
public final class Main {

    // ── 1. Constants and literals ───────────────────────────────────────────────────────────────

    /** A plain constant — static final, which IntelliJ colours differently from an instance field. */
    public static final String GREETING = "hello, world";

    private static final int MAX_RETRIES = 5;
    private static final long TIMEOUT_NANOS = 2_500_000_000L;
    private static final double GOLDEN_RATIO = 1.618_033_988_749d;
    private static final float EPSILON = 1e-6f;
    private static final char TAB = '\t';
    private static final boolean VERBOSE = false;
    private static final Object NOTHING = null;

    // Every numeric form the grammar has a rule for.
    private static final int HEX = 0xDEAD_BEEF;
    private static final int OCTAL = 0755;
    private static final int BINARY = 0b1010_1010;
    private static final long BIG = 9_223_372_036_854_775_807L;
    private static final double SCIENTIFIC = 6.022e23;
    private static final double NEGATIVE_EXPONENT = 1.6e-19;

    // Escapes, which get their own capture inside a string.
    private static final String ESCAPES = "tab:\t newline:\\n quote:\" backslash:\\ unicode:\u00e9";
    private static final String PATH = "C:\\Users\\somebody\\Documents\\file.txt";
    private static final String REGEX = "^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.+)$";

    /** A text block, which shapes very differently from an ordinary string literal. */
    private static final String QUERY = """
            SELECT id, name, created_at
              FROM widgets
             WHERE owner = ?
               AND deleted_at IS NULL
             ORDER BY created_at DESC
            """;

    private Main() {
        throw new AssertionError("no instances");
    }

    // ── 2. Enums, records, sealed hierarchies ───────────────────────────────────────────────────

    /** Severity, ordered so {@code compareTo} means what it looks like it means. */
    public enum Severity {
        TRACE("trace", 0),
        DEBUG("debug", 10),
        INFO("info", 20),
        WARNING("warn", 30),
        ERROR("error", 40),
        FATAL("fatal", 50);

        private final String label;
        private final int weight;

        Severity(String label, int weight) {
            this.label = label;
            this.weight = weight;
        }

        public String label() {
            return label;
        }

        public int weight() {
            return weight;
        }

        public boolean atLeast(Severity other) {
            return this.weight >= other.weight;
        }

        public static Optional<Severity> parse(String raw) {
            if (raw == null || raw.isBlank()) return Optional.empty();
            String needle = raw.trim().toLowerCase();
            for (Severity candidate : values()) {
                if (candidate.label.equals(needle)) return Optional.of(candidate);
            }
            return Optional.empty();
        }
    }

    /** A record — components, an accessor per component, and a compact constructor. */
    public record Message(String text, Severity severity, long timestamp) {

        public Message {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(severity, "severity");
            if (timestamp < 0L) {
                throw new IllegalArgumentException("timestamp must not be negative: " + timestamp);
            }
        }

        public static Message of(String text) {
            return new Message(text, Severity.INFO, 0L);
        }

        public boolean isProblem() {
            return severity.atLeast(Severity.WARNING);
        }

        @Override
        public String toString() {
            return "[" + severity.label() + "] " + text;
        }
    }

    /** A sealed hierarchy, which the grammar sees as keywords it has no other use for. */
    public sealed interface Shape permits Circle, Rectangle, Triangle {
        double area();

        default String describe() {
            return getClass().getSimpleName() + " of area " + area();
        }
    }

    public record Circle(double radius) implements Shape {
        @Override
        public double area() {
            return Math.PI * radius * radius;
        }
    }

    public record Rectangle(double width, double height) implements Shape {
        @Override
        public double area() {
            return width * height;
        }

        public boolean isSquare() {
            return Math.abs(width - height) < EPSILON;
        }
    }

    public record Triangle(double base, double height) implements Shape {
        @Override
        public double area() {
            return 0.5d * base * height;
        }
    }

    // ── 3. Generics ─────────────────────────────────────────────────────────────────────────────

    /** A generic container, deliberately with a bounded parameter and a wildcard method. */
    public static final class Box<T extends Comparable<T>> {

        private final List<T> items = new ArrayList<>();

        @SafeVarargs
        public static <E extends Comparable<E>> Box<E> of(E... elements) {
            Box<E> box = new Box<>();
            for (E element : elements) box.add(element);
            return box;
        }

        public Box<T> add(T item) {
            if (item != null) items.add(item);
            return this;
        }

        public Optional<T> largest() {
            return items.stream().max(Comparator.naturalOrder());
        }

        public void addAll(List<? extends T> more) {
            for (T item : more) add(item);
        }

        public void drainInto(List<? super T> sink) {
            sink.addAll(items);
            items.clear();
        }

        public <R> List<R> mapEach(Function<? super T, ? extends R> mapper) {
            List<R> out = new ArrayList<>(items.size());
            for (T item : items) out.add(mapper.apply(item));
            return out;
        }

        public int size() {
            return items.size();
        }

        public boolean isEmpty() {
            return items.isEmpty();
        }
    }

    // ── 4. Lambdas, method references, functional interfaces ────────────────────────────────────

    /** A functional interface of our own, so the SAM is not always something from java.util.function. */
    @FunctionalInterface
    public interface Transformer<A, B> {
        B transform(A input);

        default <C> Transformer<A, C> then(Transformer<? super B, ? extends C> next) {
            return input -> next.transform(this.transform(input));
        }

        static <X> Transformer<X, X> identity() {
            return input -> input;
        }
    }

    public static List<String> formatAll(List<Message> messages) {
        return messages.stream()
                .filter(Objects::nonNull)
                .filter(Message::isProblem)
                .sorted(Comparator.comparingLong(Message::timestamp).reversed())
                .map(message -> String.format("%-8s %s", message.severity().label(), message.text()))
                .collect(Collectors.toList());
    }

    public static Map<Severity, List<Message>> groupBySeverity(List<Message> messages) {
        return messages.stream().collect(Collectors.groupingBy(Message::severity));
    }

    public static Map<Boolean, Long> countProblems(List<Message> messages) {
        return messages.stream()
                .collect(Collectors.partitioningBy(Message::isProblem, Collectors.counting()));
    }

    private static void demonstrateLambdas() {
        Supplier<List<String>> supplier = ArrayList::new;
        Function<String, Integer> length = String::length;
        BiFunction<Integer, Integer, Integer> sum = Integer::sum;
        Predicate<String> nonEmpty = text -> !text.isEmpty();
        Comparator<String> byLengthThenNatural = Comparator
                .comparingInt(String::length)
                .thenComparing(Comparator.naturalOrder());
        Transformer<String, String> trimAndUpper = ((Transformer<String, String>) String::trim)
                .then(String::toUpperCase); 
        List<String> words = supplier.get();
        words.add("  gamma ");
        words.add("alpha");
        words.add("beta");
        words.removeIf(nonEmpty.negate());
        words.sort(byLengthThenNatural);

        int total = words.stream().mapToInt(length::apply).sum();
        int viaMethodRef = sum.apply(total, words.size());

        System.out.println(trimAndUpper.transform(words.get(0)) + " " + viaMethodRef);  
    }

    // ── 5. Control flow, switch expressions, pattern matching ───────────────────────────────────

    public static String classify(Object value) {
        // Pattern matching for instanceof — the binding is scoped to the true branch only.
        if (value instanceof String text && !text.isBlank()) {
            return "non-blank string of " + text.length();
        }
        if (value instanceof Integer number) {
            return number % 2 == 0 ? "even" : "odd";
        }
        if (value instanceof List<?> list) {
            return "list of " + list.size();
        }
        return "unknown";
    }

    public static double areaOf(Shape shape) {
        // A switch EXPRESSION over a sealed hierarchy, with no default because it is exhaustive.
        return switch (shape) {
            case Circle circle -> circle.area();
            case Rectangle rectangle -> rectangle.area();
            case Triangle triangle -> triangle.area();
        };
    }

    public static String describeSeverity(Severity severity) {
        return switch (severity) {
            case TRACE, DEBUG -> "noise";
            case INFO -> "ordinary";
            case WARNING -> "worth a look";
            case ERROR, FATAL -> {
                String prefix = severity == Severity.FATAL ? "very " : "";
                yield prefix + "bad";
            }
        };
    }

    @SuppressWarnings("unused")
    public static int retryLoop(Supplier<Boolean> attempt) {
        int attempts = 0;
        outer:
        while (attempts < MAX_RETRIES) {
            attempts++;
            for (int inner = 0; inner < 3; inner++) {
                try {
                    if (Boolean.TRUE.equals(attempt.get())) {
                        break outer;
                    }
                } catch (IllegalStateException retryable) {
                    continue;
                } catch (RuntimeException fatal) {
                    throw new IllegalStateException("gave up after " + attempts, fatal);
                } finally {
                    if (VERBOSE) System.out.println("attempt " + attempts + "." + inner);
                }
            }
        }
        return attempts;
    }

    public static void iterationForms(int[] numbers, List<String> names) {
        for (int i = 0; i < numbers.length; i++) {
            numbers[i] = numbers[i] * 2 + 1;
        }
        for (String name : names) {
            if (name == null) continue;
            System.out.println(name.strip());
        }
        int countdown = 3;
        do {
            countdown--;
        } while (countdown > 0);

        IntStream.rangeClosed(1, 10)
                .filter(n -> n % 3 != 0)
                .mapToObj(Integer::toString)
                .forEach(System.out::println);
    }

    // ── 6. Nested, inner and anonymous classes ──────────────────────────────────────────────────

    /** A static nested class — no outer instance. */
    public static final class Counter {
        private int value;

        public synchronized int increment() {
            return ++value;
        }

        public int value() {
            return value;
        }
    }

    /** An inner class, which does capture the outer instance. */
    public final class Inner {
        public String describe() {
            return "inner of " + Main.this.getClass().getSimpleName();
        }
    }

    private static Comparator<Message> anonymousComparator() {
        return new Comparator<Message>() {
            @Override
            public int compare(Message left, Message right) {
                int bySeverity = Integer.compare(right.severity().weight(), left.severity().weight());
                return bySeverity != 0 ? bySeverity : left.text().compareTo(right.text());
            }
        };
    }

    // ── 7. Non-ASCII, where offset bugs live ────────────────────────────────────────────────────

    /**
     * Accented Latin is two bytes, CJK is three, and an emoji is four — and a surrogate PAIR in UTF-16,
     * so it differs in both directions at once. Anything after these is where a miscount shows.
     */
    private static final String ACCENTED = "café, naïve, Ünicode, jamón";
    private static final String CJK = "日本語のテキスト、中文文本、한국어";
    private static final String EMOJI = "🎉 🚀 ✨ 🔥 — and text after the emoji";
    private static final String MIXED = "before ☕ middle 日本 after 🎯 end";

    // A comment with the same problem: café 日本語 🎉 — the declaration below must still colour correctly.
    private static final int AFTER_NON_ASCII = 42;

    public static Map<String, Integer> lengthsOfEach() {
        Map<String, Integer> lengths = new HashMap<>();
        lengths.put("accented", ACCENTED.length());
        lengths.put("cjk", CJK.length());
        lengths.put("emoji", EMOJI.length());
        lengths.put("mixed", MIXED.length());
        lengths.put("codePoints", (int) EMOJI.codePoints().count());
        return lengths;
    }

    // ── 8. Something that ties it together ──────────────────────────────────────────────────────

    public static List<Message> sampleMessages() {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("started", Severity.INFO, 1L));
        messages.add(new Message("cache miss", Severity.DEBUG, 2L));
        messages.add(new Message("slow query took 1.2s", Severity.WARNING, 3L));
        messages.add(new Message("connection refused", Severity.ERROR, 4L));
        messages.add(new Message("out of memory", Severity.FATAL, 5L));
        messages.add(new Message("café ☕ ready", Severity.INFO, 6L));
        return messages;
    }

    public static String report(List<Message> messages) {
        StringBuilder out = new StringBuilder(256);
        out.append(GREETING).append(System.lineSeparator());

        Map<Severity, List<Message>> grouped = groupBySeverity(messages);
        Stream.of(Severity.values())
                .filter(grouped::containsKey)
                .sorted(Comparator.comparingInt(Severity::weight).reversed())
                .forEach(severity -> {
                    List<Message> group = grouped.get(severity);
                    out.append(String.format("%-8s (%d)%n", severity.label(), group.size()));
                    for (Message message : group) {
                        out.append("    ").append(message).append(System.lineSeparator());
                    }
                });

        Map<Boolean, Long> counts = countProblems(messages);
        out.append("problems: ").append(counts.getOrDefault(true, 0L))
                .append(", ordinary: ").append(counts.getOrDefault(false, 0L));
        return out.toString();
    }

    public static void main(String[] args) {
        List<Message> messages = sampleMessages();
        messages.sort(anonymousComparator());

        System.out.println(report(messages));
        System.out.println(formatAll(messages));

        Box<String> box = Box.of("delta", "alpha", "charlie", "bravo");
        System.out.println(box.largest().orElse("<empty>"));
        System.out.println(box.mapEach(String::toUpperCase));

        List<Shape> shapes = List.of(
                new Circle(1.5d),
                new Rectangle(3.0d, 4.0d),
                new Triangle(6.0d, 2.0d));
        double total = shapes.stream().mapToDouble(Main::areaOf).sum();
        System.out.printf("total area %.3f%n", total);

        for (Shape shape : shapes) {
            System.out.println(shape.describe());
        }

        Counter counter = new Counter();
        for (int i = 0; i < MAX_RETRIES; i++) counter.increment();
        System.out.println("counted to " + counter.value());

        System.out.println(classify("text"));
        System.out.println(classify(7));
        System.out.println(classify(List.of(1, 2, 3)));
        System.out.println(describeSeverity(Severity.FATAL));

        System.out.println(lengthsOfEach());
        System.out.println(ACCENTED + " / " + CJK + " / " + EMOJI);
        System.out.println("after non-ascii: " + AFTER_NON_ASCII);

        demonstrateLambdas();
        iterationForms(new int[]{1, 2, 3, 4, 5}, new ArrayList<>(List.of("one", "two")));

        System.out.println(QUERY);
        System.out.println(ESCAPES);
        System.out.println(REGEX);

        int attempts = retryLoop(() -> true);
        System.out.println("attempts: " + attempts + ", timeout " + TIMEOUT_NANOS + "ns");
        System.out.println("phi=" + GOLDEN_RATIO + " hex=" + HEX + " bin=" + BINARY + " oct=" + OCTAL);
        System.out.println("big=" + BIG + " sci=" + SCIENTIFIC + " tiny=" + NEGATIVE_EXPONENT);
        System.out.println("tab[" + TAB + "] nothing=" + NOTHING + " path=" + PATH);
    }
}
