package com.crystalgui.language.run.console;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Links the {@code Foo.java:42} inside a Java stack frame.
 *
 * <p>The shape is fixed by {@link StackTraceElement#toString()} — {@code at pkg.Type.method(File.java:12)}
 * — so this matches what the JDK writes rather than a guess about what a trace looks like.</p>
 *
 * <h3>The span is the file and line, not the whole frame</h3>
 *
 * <p>IntelliJ underlines exactly {@code Foo.java:42} and leaves {@code at com.example.Type.method} plain.
 * That is not decoration: the underlined text is a promise that clicking it goes somewhere, and the
 * package-qualified method name in front of it does not go anywhere on its own. Underlining the whole
 * frame would also make a trace a wall of underline, which is what the mark is trying to stand out
 * from.</p>
 *
 * <h3>What it deliberately does not match</h3>
 *
 * <p>Frames with no source — {@code (Native Method)}, {@code (Unknown Source)}, a synthetic
 * {@code (<generated>)} — carry no file and no line, so there is nothing to point at and the regex simply
 * fails them. A filter that guessed would produce a link that opens nothing, which is worse than a plain
 * frame: an underline that does not navigate teaches people the underlines are unreliable.</p>
 */
public final class JavaStackFrameFilter implements ConsoleFilter {

    /**
     * {@code (Name.java:123)} — the file name is captured without its directory because a trace never has
     * one, and the line must be all digits so that a lambda's {@code $$Lambda$14/0x…} cannot half-match.
     *
     * <p>The extension is not restricted to {@code .java}: Kotlin, Groovy and our own future dialects all
     * write frames the same way, and a file name is a file name. Resolution is what decides whether the
     * name means anything, and it happens somewhere that can actually tell.</p>
     */
    private static final Pattern FRAME =
            Pattern.compile("\\(([A-Za-z_$][A-Za-z0-9_$]*\\.[A-Za-z0-9]+):(\\d+)\\)");

    @Override
    public List<Link> apply(String text) {
        if (text == null || text.length() < 7) return List.of();
        // CHEAPEST POSSIBLE REJECTION FIRST. This runs over every realised row, and the overwhelming
        // majority of console output is not a stack frame -- one indexOf beats starting a matcher.
        if (text.indexOf(".java:") < 0 && text.indexOf(':') < 0) return List.of();

        Matcher matcher = FRAME.matcher(text);
        List<Link> links = null;
        while (matcher.find()) {
            int line = parseLine(matcher.group(2));
            // A LINE NUMBER THAT DOES NOT FIT IN AN INT IS NOT A LINE NUMBER. `(A.java:99999999999)` is
            // well-formed to the regex and would throw out of a paint pass, which is the one place an
            // exception is least recoverable.
            if (line <= 0) continue;
            if (links == null) links = new ArrayList<>(2);
            // Group 1's start through group 2's end: `Foo.java:42`, with the parentheses left outside.
            links.add(new Link(matcher.start(1), matcher.end(2), matcher.group(1), line));
        }
        return links == null ? List.of() : links;
    }

    private static int parseLine(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException tooLong) {
            return -1;
        }
    }
}
