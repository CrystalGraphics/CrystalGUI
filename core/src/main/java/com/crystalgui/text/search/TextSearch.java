package com.crystalgui.text.search;

import com.crystalgui.core.search.SearchMatcher;
import com.crystalgui.core.search.SearchQuery;
import com.crystalgui.text.Rope;
import com.crystalgui.ui.text.TextRange;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every match of a query in a document — VS Code's {@code TextModelSearch}, IntelliJ's
 * {@code FindManager.findString}.
 *
 * <h3>Why this is not on the widget</h3>
 *
 * <p>It was: {@code TextEditor.find} scanned the buffer itself. Both references put the scan on the
 * <em>model</em> side — Monaco reaches it as {@code ITextModel.findMatches(...)}, IntelliJ as an
 * application service that takes a {@code CharSequence} and knows nothing about editors — and this repo
 * already draws that line for {@code text.cursor}, for the reason its own notes give: the extraction is
 * what makes the algorithm reachable without a {@code UIWindow}, fonts and an input handler.</p>
 *
 * <p>Everything here is decidable on a {@code CharSequence}: where a word boundary is, whether overlapping
 * matches count separately, what a zero-width pattern means. None of it needs a frame to test.</p>
 *
 * <h3>It differs from {@link SearchMatcher}, deliberately</h3>
 *
 * <p>{@code SearchMatcher} answers "how well does this <em>candidate</em> match, and where" — the right
 * question for a list row, and it returns the <b>best</b> match with a score. A document wants <b>every</b>
 * match, unranked, in order. The two share the one thing that must not diverge: what counts as a word
 * boundary, via {@link SearchMatcher#isWholeWordAt}.</p>
 */
public final class TextSearch {

    private TextSearch() {
    }

    /**
     * Every match in a <b>document</b>, one line at a time.
     *
     * <h3>Why not simply hand the {@link Rope} to the method below</h3>
     *
     * <p>{@code Rope} is a {@code CharSequence}, so it compiles — and it is the slower of the two options,
     * not the faster one. {@code Rope.charAt} is a descent of the tree with no cursor cached, so a
     * character-by-character scan pays a branchy {@code O(log n)} per character where a {@code String}
     * pays nothing. "Search the rope, it is a CharSequence" is the obvious move and the wrong one.</p>
     *
     * <p>So this searches <b>per line</b>, which is what VS Code's {@code TextModelSearch} does and what
     * every other read in this widget already does: one row's {@code String} at a time, allocated and
     * discarded, instead of a copy of the whole file. Two copies, in fact — the old path built the
     * document once and then built a lowercased copy of it for a case-insensitive search, on every
     * keystroke in the find box.</p>
     *
     * <p><b>A regex still gets the whole text</b>, and that is not laziness: a pattern may span lines, so
     * feeding it one row at a time would silently stop matching anything that crosses a newline. The
     * common case is literal and the common case is now free of copies.</p>
     */
    public static List<TextRange> findAll(@Nullable Rope document, @Nullable SearchQuery query) {
        if (document == null || query == null || query.isEmpty()) return new ArrayList<>();
        if (query.options().regex()) return findAll(document.toString(), query);

        String needle = query.text();
        // A NEEDLE WITH A NEWLINE IN IT cannot be found a line at a time, and nothing stops a user pasting
        // one into the find box.
        if (needle.indexOf('\n') >= 0) return findAll(document.toString(), query);

        List<TextRange> matches = new ArrayList<>();
        boolean words = query.options().wholeWords();
        boolean matchCase = query.options().matchCase();
        for (int row = 0; row < document.lineCount(); row++) {
            String line = document.line(row);
            int start = document.lineStartOffset(row);
            int at = indexOf(line, needle, 0, matchCase);
            while (at >= 0) {
                // A WORD CANNOT SPAN A NEWLINE, so the line's own edges are the document's for this test.
                if (!words || SearchMatcher.isWholeWordAt(line, at, at + needle.length())) {
                    matches.add(TextRange.of(start + at, start + at + needle.length()));
                }
                at = indexOf(line, needle, at + 1, matchCase);
            }
        }
        return matches;
    }

    /** {@code String.indexOf}, or its case-insensitive twin — without lowercasing anything. */
    private static int indexOf(String haystack, String needle, int from, boolean matchCase) {
        if (matchCase) return haystack.indexOf(needle, from);
        int last = haystack.length() - needle.length();
        for (int at = Math.max(0, from); at <= last; at++) {
            if (haystack.regionMatches(true, at, needle, 0, needle.length())) return at;
        }
        return -1;
    }

    /**
     * Every match, in document order.
     *
     * <p>An empty or {@code null} query finds nothing, and so does a regex that will not compile — it is
     * recompiled on every keystroke while one is being typed, so failing loudly here would take the frame
     * down over a half-written {@code (}.</p>
     */
    public static List<TextRange> findAll(@Nullable CharSequence text, @Nullable SearchQuery query) {
        List<TextRange> matches = new ArrayList<>();
        if (text == null || query == null || query.isEmpty()) return matches;

        String haystack = text.toString();
        boolean words = query.options().wholeWords();
        if (query.options().regex()) {
            Pattern pattern = query.pattern();
            if (pattern == null) return matches;          // invalid: a state, not an exception
            Matcher matcher = pattern.matcher(haystack);
            while (matcher.find()) {
                // ZERO-WIDTH IS SKIPPED, NOT TAKEN, and the scan continues past it. `x*` matches the empty
                // string at every position, so taking those reports the whole document as matched; refusing
                // the pattern outright is the opposite error, since `x*` should still find a real x.
                if (matcher.end() == matcher.start()) continue;
                if (words && !SearchMatcher.isWholeWordAt(haystack, matcher.start(), matcher.end())) continue;
                matches.add(TextRange.of(matcher.start(), matcher.end()));
            }
            return matches;
        }

        String needle = query.text();
        String subject = query.options().matchCase() ? haystack : haystack.toLowerCase(Locale.ROOT);
        int at = subject.indexOf(needle);
        while (at >= 0) {
            if (!words || SearchMatcher.isWholeWordAt(haystack, at, at + needle.length())) {
                matches.add(TextRange.of(at, at + needle.length()));
            }
            // ADVANCE BY ONE, not by the match length: "aa" in "aaa" is two hits, which is what every
            // editor reports.
            at = subject.indexOf(needle, at + 1);
        }
        return matches;
    }

    /**
     * Applies a replacement, matching the case of what it replaced — IntelliJ's <b>Preserve case</b>.
     *
     * <p>Three shapes, which is what both references implement and no more: an all-upper match takes an
     * all-upper replacement, a Capitalised match takes a Capitalised one, and anything else is left alone.
     * A general case-mapper is not attempted — {@code getHTMLElement} has no "case" to preserve, and
     * guessing at one produces a rename nobody asked for.</p>
     */
    public static String preserveCase(String matched, String replacement) {
        if (matched == null || matched.isEmpty() || replacement == null || replacement.isEmpty()) {
            return replacement == null ? "" : replacement;
        }
        if (isAllUpper(matched)) return replacement.toUpperCase(Locale.ROOT);
        if (Character.isUpperCase(matched.charAt(0)) && !isAllUpper(matched)) {
            return Character.toUpperCase(replacement.charAt(0)) + replacement.substring(1);
        }
        return replacement;
    }

    /** True only when there is at least one cased letter and every one of them is upper. */
    private static boolean isAllUpper(String text) {
        boolean any = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isLetter(c)) continue;
            any = true;
            if (!Character.isUpperCase(c)) return false;
        }
        return any;
    }
}
