package com.crystalgui.language.js;

import com.crystalgui.language.run.ConsoleFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Links the {@code Main.js:12} inside a Rhino script frame — and the {@code (Main.js#12)} at the end of
 * a Rhino message.
 *
 * <p>Two shapes, both fixed by Rhino rather than guessed at. A script frame in a printed trace is
 * {@code \tat Main.js:12 (functionName)} — {@code ScriptStackElement.renderJavaStyle} — with no
 * parentheses around the file, which is what keeps this from ever matching a JVM frame's
 * {@code (Foo.java:12)}: that one is {@code JavaStackFrameFilter}'s, and the console's chain is additive,
 * so a filter that matched both would put two links on one span. And every {@code RhinoException.getMessage()}
 * ends in {@code (sourceName#line)}, which is the first line of the trace and the one the author reads,
 * so it links too.</p>
 *
 * <p>Restricted to JavaScript's own extensions. Not because a frame naming {@code lib.txt:3} could not
 * exist, but because this filter is <em>the JavaScript runtime's</em>: it says what its own frames look
 * like, and leaves every other file to whichever runtime produces frames about it.</p>
 *
 * <p>Host-side, and names nothing of Rhino's — it matches text, and text is what the console holds. The
 * name records whose format it is.</p>
 */
public final class RhinoStackFrameFilter implements ConsoleFilter {

    /** {@code at Main.js:12} — the file token stops at whitespace and parentheses. */
    private static final Pattern FRAME =
            Pattern.compile("\\bat ([^\\s()]+\\.(?:js|mjs|cjs)):(\\d+)\\b");

    /** {@code (Main.js#12)} — the origin suffix Rhino appends to its messages. */
    private static final Pattern ORIGIN =
            Pattern.compile("\\(([^\\s()]+\\.(?:js|mjs|cjs))#(\\d+)\\)");

    @Override
    public List<Link> apply(String text) {
        if (text == null || text.length() < 6) return List.of();
        // CHEAPEST REJECTION FIRST: this runs over every realised row and almost none are frames.
        if (text.indexOf(".js") < 0 && text.indexOf(".mjs") < 0 && text.indexOf(".cjs") < 0) {
            return List.of();
        }
        List<Link> links = new ArrayList<>(2);
        collect(FRAME.matcher(text), links);
        collect(ORIGIN.matcher(text), links);
        return links;
    }

    private static void collect(Matcher matcher, List<Link> links) {
        while (matcher.find()) {
            int line = parseLine(matcher.group(2));
            if (line <= 0) continue;
            links.add(new Link(matcher.start(1), matcher.end(2), matcher.group(1), line));
        }
    }

    private static int parseLine(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException tooLong) {
            return -1;
        }
    }
}
