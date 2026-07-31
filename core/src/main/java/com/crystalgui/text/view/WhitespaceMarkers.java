package com.crystalgui.text.view;

/**
 * Which characters of a line get a visible whitespace marker.
 *
 * <p><b>Ported from VS Code's {@code _applyRenderWhitespace}</b> —
 * {@code src/vs/editor/common/viewLayout/viewLineRenderer.ts}, microsoft/vscode, MIT licence. The
 * predicate in {@link #shouldMark} is that function's, minus the parts that exist to keep the browser's
 * bidi layout intact when a token is split into DOM spans, which has no analogue here.</p>
 *
 * <p>A {@code boolean[]} rather than ranges, because the caller draws one marker per character and ranges
 * would only be unpacked again. The array is over the row's <b>character</b> indices, so a tab is one
 * entry however many columns it covers — the marker is drawn once, at the tab's start, which is where
 * every editor puts the arrow.</p>
 */
public final class WhitespaceMarkers {

    /**
     * The middle dot every editor uses for a space, and the rightwards arrow for a tab.
     *
     * <p>Written as literals, which is only safe because {@code core/build.gradle.kts} now pins
     * {@code options.encoding = "UTF-8"}. Left to the platform default these decode to different
     * characters under windows-1252 and draw the wrong glyph — a failure no test asserting on offsets or
     * counts would notice, and invisible on a JDK 18+ toolchain that already defaults to UTF-8.</p>
     */
    public static final char SPACE_MARKER = '·';
    public static final char TAB_MARKER = '→';

    /**
     * ASCII stand-ins for a font that cannot draw the real markers.
     *
     * <p>Not hypothetical: the bundled {@code MinecraftRegular.otf} has no U+2026, which is why
     * {@code UIText} carries the same fallback for its ellipsis. A marker the font cannot draw renders as
     * a blank advance and is indistinguishable from the feature being off.</p>
     */
    public static final char SPACE_MARKER_ASCII = '.';
    public static final char TAB_MARKER_ASCII = '>';

    private WhitespaceMarkers() {
    }

    /**
     * Which characters of {@code line} to mark.
     *
     * @param continuesWithWrappedLine whether a soft wrap carries this row onto another visual row.
     *                                 {@link RenderWhitespace#TRAILING} draws nothing when it does —
     *                                 the "trailing" whitespace of a wrapped segment is in the middle of
     *                                 the line, and marking it would report every wrap as a lint error.
     */
    public static boolean[] shouldMark(String line, RenderWhitespace mode, int tabSize,
                                       boolean continuesWithWrappedLine) {
        int length = line.length();
        boolean[] marked = new boolean[length];
        if (mode == RenderWhitespace.NONE || length == 0) return marked;
        if (mode == RenderWhitespace.TRAILING && continuesWithWrappedLine) return marked;

        boolean onlyBoundary = mode == RenderWhitespace.BOUNDARY;
        boolean onlyTrailing = mode == RenderWhitespace.TRAILING;

        int firstNonBlank = firstNonBlankIndex(line);
        boolean blankLine = firstNonBlank < 0;
        int lastNonBlank;
        if (blankLine) {
            firstNonBlank = length;
            lastNonBlank = length;
        } else {
            lastNonBlank = lastNonBlankIndex(line);
        }

        boolean wasInWhitespace = false;
        for (int i = 0; i < length; i++) {
            char c = line.charAt(i);
            boolean inWhitespace;

            if (i < firstNonBlank || i > lastNonBlank) {
                // Leading or trailing whitespace is always marked -- that is the point of the feature.
                inWhitespace = true;
            } else if (c == '\t') {
                // A tab is marked in every mode, boundary included. It is invisible AND ambiguous in a
                // way a single space is not: how far it moves depends on where it starts.
                inWhitespace = true;
            } else if (c == ' ') {
                if (onlyBoundary) {
                    // Inside the text, a lone space between two words is left alone. A space is marked
                    // only where it is part of a RUN -- either following one, or followed by one.
                    if (wasInWhitespace) {
                        inWhitespace = true;
                    } else {
                        char next = i + 1 < length ? line.charAt(i + 1) : '\0';
                        inWhitespace = next == ' ' || next == '\t';
                    }
                } else {
                    inWhitespace = true;
                }
            } else {
                inWhitespace = false;
            }

            if (inWhitespace && onlyTrailing) {
                inWhitespace = blankLine || i > lastNonBlank;
            }

            marked[i] = inWhitespace;
            wasInWhitespace = inWhitespace;
        }
        return marked;
    }

    /** The marker glyph for a character, or {@code 0} when it is not whitespace. */
    public static char markerFor(char c) {
        if (c == ' ') return SPACE_MARKER;
        if (c == '\t') return TAB_MARKER;
        return '\0';
    }

    private static int firstNonBlankIndex(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c != ' ' && c != '\t') return i;
        }
        return -1;
    }

    private static int lastNonBlankIndex(String line) {
        for (int i = line.length() - 1; i >= 0; i--) {
            char c = line.charAt(i);
            if (c != ' ' && c != '\t') return i;
        }
        return -1;
    }
}
