package com.crystalgui.ui.text;

import com.crystalgui.text.Rope;
import com.crystalgui.text.syntax.DocComments;
import com.crystalgui.text.syntax.Language;
import com.crystalgui.text.syntax.LanguageRegistry;
import com.crystalgui.text.syntax.SyntaxToken;
import com.crystalgui.text.syntax.SyntaxTokenizer;
import com.crystalgui.ui.elements.UIText;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>Syntax tokens onto text runs</b> — the operation every consumer of a colour scheme performs.
 *
 * <h3>Why this is a class and not three copies of a loop</h3>
 *
 * <p>Colouring code outside the editor turned out to be three separate things doing the same work:
 * {@code DocumentationPopup} colours a declaration across however many lines the engine broke it into,
 * {@link com.crystalgui.ui.elements.MarkupView} colours a {@code <pre>} sample inside a rendered doc
 * comment, and {@code TextEditor} does it per realised row. The editor's version is genuinely different
 * — it is incremental, cached per row and merged with semantic tokens — but the other two are the same
 * twenty lines, and the second one was about to be written by copying the first.</p>
 *
 * <p><b>The rebasing is the part worth having once.</b> Tokens index the whole source; a run holds a
 * slice of it. A range handed to a run by its absolute offset lands wherever that many characters is on
 * <em>that</em> run — which is a colour on unrelated text rather than an error, so nothing reports it and
 * it looks like a scheme bug. The clipping matters as much: {@code SyntaxTokenizer} explicitly allows a
 * token to extend past the range it was asked about (a block comment spanning the viewport is one token),
 * so every consumer has to cope and exactly one should have to say how.</p>
 *
 * <h3>These runs are OWNED</h3>
 *
 * <p>Every method here clears a run's highlights before writing. That is not tidiness: a band left from a
 * previous document is a set of offsets into a string that no longer exists, so it lands on whatever
 * characters have since moved into them — not a stale colour, a colour over the wrong text entirely.
 * {@code UIText} records the same failure, and {@code DocumentationPopup}'s pooled definition lines are
 * exactly the shape that suffers it.</p>
 *
 * <p>The consequence is that a run passed here must be a run whose highlights are <em>only</em> syntax.
 * A row that also carries a search-match band has to merge the two itself, which is what the editor
 * does.</p>
 */
public final class SyntaxHighlighting {

    private SyntaxHighlighting() {
    }

    /**
     * Every token in {@code source}, read in {@code language} — or empty when nothing claims it.
     *
     * <p><b>A fresh tokenizer, used once, closed.</b> The long-lived kind an editor holds is stateful: it
     * keeps a parse tree for <em>one</em> document and only reparses when that document is marked stale,
     * so asking the same instance about a second string answers about the first. Sharing one is the
     * cross-contamination {@code Workbench} already refuses when it builds one per open file.</p>
     *
     * <p>Closing it is not optional either. A tree-sitter tokenizer holds a native parse tree, and one
     * that is never closed survives until the process ends — which is how every parse tree in the
     * application came to leak once already.</p>
     *
     * <p>The first {@code tokenize} on a fresh tokenizer is <b>synchronous and complete</b>: the
     * incremental backend only defers when it already has a tree to answer from, so a one-shot caller
     * never sees the half-parsed state an editor is built to tolerate. That is what makes this usable
     * from a widget that has one frame to draw in and nowhere to put a callback.</p>
     */
    public static List<SyntaxToken> tokenize(String source, @Nullable Language language) {
        if (source == null || source.isEmpty() || language == null) return List.of();
        // REFINED, exactly as the editor's is: a `<pre>` sample containing a doc comment should read
        // the same in a popup as it does in the file it came from, and the alternative is a second
        // vocabulary that drifts.
        SyntaxTokenizer tokenizer =
                DocComments.refining(LanguageRegistry.forLanguage(language).newTokenizer());
        try {
            return tokenizer.tokenize(Rope.of(source), 0, source.length());
        } finally {
            tokenizer.close();
        }
    }

    /**
     * Tokenizes and colours in one step — what a widget with a short code sample wants.
     *
     * <p><b>A no-op when no language was named</b>, rather than a colouring with nothing in it. The two
     * are different states and only one of them should mark the run: {@link #colour} is called with
     * tokens the caller already has, so an empty list there means "lexed, found nothing" and the run is
     * still code. Here an absent language means nobody ever said what it was, and claiming
     * {@link UIText#SYNTAX_CLASS} anyway would pull every element-level scheme rule onto a run that is
     * not being coloured — a restyle with no colouring to show for it.</p>
     */
    public static void highlight(UIText run, String source, @Nullable Language language) {
        if (language == null || source == null || source.isEmpty()) return;
        colour(run, tokenize(source, language), 0, source.length());
    }

    /** Colours a run holding the whole tokenized text. */
    public static void colour(UIText run, List<SyntaxToken> tokens, int length) {
        colour(run, tokens, 0, length);
    }

    /**
     * Colours a run holding {@code [from, to)} of the tokenized text, rebasing the ranges onto it.
     *
     * <p>Marks the run with {@link UIText#SYNTAX_CLASS}, because a band is only half of a colour — the
     * scheme's rules are all scoped to it, so a run carrying ranges and not the class resolves every one
     * of them to nothing and draws as plain text with the highlighting apparently broken.</p>
     */
    public static void colour(UIText run, List<SyntaxToken> tokens, int from, int to) {
        run.addClass(UIText.SYNTAX_CLASS);
        run.highlights().clear();
        for (Map.Entry<String, List<TextRange>> band : bandsFor(tokens, from, to).entrySet()) {
            run.highlights().set(band.getKey(), band.getValue());
        }
    }

    /**
     * Colours one run per line of {@code text}.
     *
     * <p>Extra runs beyond the line count are left alone — a caller pooling its line elements has already
     * hidden them, and clearing one here would be this class reaching past what it was given.</p>
     */
    public static void colourLines(List<UIText> runs, String text, List<SyntaxToken> tokens) {
        String[] lines = text.split("\n", -1);
        int start = 0;
        for (int i = 0; i < lines.length && i < runs.size(); i++) {
            colour(runs.get(i), tokens, start, start + lines[i].length());
            start += lines[i].length() + 1;
        }
    }

    /**
     * The ranges each capture name covers within {@code [from, to)}, rebased to it.
     *
     * <h3>Both names, general first</h3>
     *
     * <p>A dotted capture is published under its stem as well as itself, so a scheme that has not styled
     * {@code function.call} still colours it as {@code function}. That is a <b>fallback</b>, and the order
     * is the whole of its meaning: these end up in one insertion-ordered map and a character belongs to
     * whichever name was written last, so publishing the specific name first inverts it — every
     * specialisation is overwritten by its own stem, and the distinction a query was adjusted to make
     * disappears before it reaches the screen.</p>
     *
     * <p>Insertion-ordered for exactly that reason. A map that reordered per run would make this a
     * heisenbug rather than a decision.</p>
     */
    public static Map<String, List<TextRange>> bandsFor(List<SyntaxToken> tokens, int from, int to) {
        if (tokens.isEmpty() || to <= from) return Collections.emptyMap();
        Map<String, List<TextRange>> bands = new LinkedHashMap<>();
        for (SyntaxToken token : tokens) {
            int start = Math.max(token.start(), from);
            int end = Math.min(token.end(), to);
            if (end <= start) continue;
            TextRange range = TextRange.of(start - from, end - from);
            String general = token.generalName();
            if (general != null) addRange(bands, general, range);
            addRange(bands, token.name(), range);
        }
        return bands;
    }

    /**
     * Records {@code range} under {@code name}, <b>unless something already there overlaps it</b>.
     *
     * <p>Not an optimisation and not tidiness: {@code HighlightRegistry} <em>refuses</em> two overlapping
     * ranges under one name, because a character can only belong to one — so without this the first doc
     * comment containing a sample tree-sitter matched twice threw
     * {@code "Ranges within one highlight must not overlap"} out of a paint tick. A query legitimately
     * matches one node from two patterns, and the general-form fallback above adds a second reason for
     * the same span to arrive twice.</p>
     *
     * <p><b>First wins</b>, because the tokenizer has already sorted by pattern precedence — so the
     * earlier range is the more specific answer and a later overlap is the thing being fallen back
     * from.</p>
     */
    public static void addRange(Map<String, List<TextRange>> byName, String name, TextRange range) {
        List<TextRange> ranges = byName.computeIfAbsent(name, key -> new ArrayList<>());
        for (TextRange existing : ranges) {
            if (range.start() < existing.end() && existing.start() < range.end()) return;
        }
        ranges.add(range);
    }
}
