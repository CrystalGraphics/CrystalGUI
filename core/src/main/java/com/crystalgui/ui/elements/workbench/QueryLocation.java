package com.crystalgui.ui.elements.workbench;

import com.crystalgui.text.TextPoint;

import javax.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A trailing {@code :line} on a search query — {@code ArrayList:42}, {@code Foo.java(17,8)}.
 *
 * <h3>Why the separator list is so long</h3>
 *
 * <p>Ported from IntelliJ's {@code AbstractGotoSEContributor}, separators and all, because the list is not
 * whimsy: it is every shape a file-and-line appears in <b>when pasted from somewhere else</b>. A stack
 * trace writes {@code Foo.java:42}. A compiler writes {@code Foo.java(42,8)}. GitHub writes {@code #L42}
 * and {@code ?l=42}. A log line writes {@code Foo at line 42}. The feature is not "we support a colon" —
 * it is "paste the thing you copied and it works", and that only holds if the list is complete.</p>
 *
 * <p>The regex is guarded by a character test so it almost never runs: a query with none of
 * {@code :,;@[( #} in it cannot have a location, which is nearly every query anybody types.</p>
 *
 * <h3>Both parts are optional, and a bare separator is not a location</h3>
 *
 * <p>{@code Foo:} is somebody mid-keystroke, so it strips to {@code Foo} with no point — which is what
 * makes typing the colon before the number not blank the list for a frame. A column with no line is not
 * expressible and is not accepted.</p>
 */
public final class QueryLocation {

    /**
     * Verbatim from {@code AbstractGotoSEContributor.ourPatternToDetectLinesAndColumns}.
     *
     * <p>Kept in its original spelling rather than tidied, so a future reader can diff it against the
     * source it came from. The groups are: name, line, column.</p>
     */
    private static final Pattern LINES_AND_COLUMNS = Pattern.compile(
            "(.+?)"                                                        // name, non-greedy matching
            + "(?::|@|,| |#|#L|\\?l=| on line | at line |:line |:?\\(|:?\\[)" // separator
            + "(\\d+)?(?:\\W(\\d+)?)?"                                     // line + column
            + "[)\\]]?");                                                  // possible closing paren/brace

    /** The cheap pre-test. IntelliJ's own, and for the same reason: the regex is not free per keystroke. */
    private static final String SEPARATOR_CHARS = ":,;@[( #";

    private final String name;
    @Nullable
    private final TextPoint point;

    private QueryLocation(String name, @Nullable TextPoint point) {
        this.name = name;
        this.point = point;
    }

    /** The query with any trailing location removed — what to actually search for. Never null. */
    public String name() {
        return name;
    }

    /** Where to put the caret once it opens, or null when the query named no location. */
    @Nullable
    public TextPoint point() {
        return point;
    }

    public boolean hasPoint() {
        return point != null;
    }

    /**
     * Splits {@code query} into a name and an optional caret position.
     *
     * <p>A query with no location comes back as itself with a null point, so a caller never needs to ask
     * whether to call this.</p>
     */
    public static QueryLocation parse(@Nullable String query) {
        String text = query == null ? "" : query.trim();
        if (text.isEmpty()) return new QueryLocation("", null);
        if (!containsAny(text, SEPARATOR_CHARS)
                && !text.contains(" line ") && !text.contains("?l=")) {
            return new QueryLocation(text, null);
        }

        Matcher matcher = LINES_AND_COLUMNS.matcher(text);
        if (!matcher.matches()) return new QueryLocation(text, null);

        String name = matcher.group(1);
        if (name == null || name.isEmpty()) return new QueryLocation(text, null);

        // ONE-BASED ON THE WAY IN, zero-based within the document -- the number in a stack trace is the
        // one the gutter shows. `EditorCommands.goToLine` makes the same conversion for the same reason.
        int line = parsePositive(matcher.group(2));
        if (line < 0) return new QueryLocation(name, null);
        int column = parsePositive(matcher.group(3));
        return new QueryLocation(name, new TextPoint(line, Math.max(column, 0)));
    }

    /** The group as a zero-based index, or -1 when absent or unparseable. */
    private static int parsePositive(@Nullable String group) {
        if (group == null || group.isEmpty()) return -1;
        try {
            return Math.max(0, Integer.parseInt(group) - 1);
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }

    private static boolean containsAny(String text, String chars) {
        for (int i = 0; i < text.length(); i++) {
            if (chars.indexOf(text.charAt(i)) >= 0) return true;
        }
        return false;
    }
}
