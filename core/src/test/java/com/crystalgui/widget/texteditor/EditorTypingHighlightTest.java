package com.crystalgui.widget.texteditor;

import com.crystalgui.ui.dom.UINode;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.crystalgui.text.Rope;
import com.crystalgui.text.syntax.SyntaxToken;
import com.crystalgui.text.syntax.SyntaxTokenizer;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.widget.texteditor.TextEditor;
import com.crystalgui.ui.text.TextRange;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * §Nothing is re-highlighted while you are typing.
 *
 * <h3>What IntelliJ does, and why copying the mechanism matters more than copying the trigger</h3>
 *
 * <p>IntelliJ runs two highlighting tiers and defers one. Its <b>lexer</b> re-runs synchronously and
 * incrementally on every keystroke — keywords, strings and comments never go plain. Everything needing an
 * identifier to <em>resolve</em> belongs to the <b>daemon</b>, which every keystroke <em>cancels</em> and
 * restarts after 300ms ({@code DaemonCodeAnalyzerSettings.getAutoReparseDelay()}).</p>
 *
 * <p>This was first built with a trigger on the word boundary — recompute when a space is typed — which is
 * what the behaviour <em>looks</em> like from outside. It is the wrong rule, and the reason is the bug that
 * followed it: a parse landing mid-edit is a <b>correct parse of incomplete text</b>, tree-sitter recovers
 * around the half-written token, and the recovery re-classifies every line below. A boundary trigger adopts
 * exactly that. Only the delay is safe, so only the delay is implemented.</p>
 */
public class EditorTypingHighlightTest extends EditorTestBase {

    /**
     * Stands in for a grammar: counts what it is asked, and captures every run of letters.
     *
     * <p><b>{@link #recovering} is what makes any of this testable.</b> A line-local fake answers the same
     * thing for an untouched row however often it is asked, so it cannot show the defect at all — the
     * first version of these tests passed with the fix removed, twice. A real parser <em>recovers</em>
     * around a half-written token and re-classifies whole regions, so the fixture has to be able to change
     * its mind about text nobody edited. That is the entire bug.</p>
     */
    private static final class CountingTokenizer implements SyntaxTokenizer {
        int queries;
        InvalidationListener listener;
        boolean recovering;

        /** The half-open offset span the parser had to recover around, or {@code null} for none. */
        int[] recovered;

        @Override
        public boolean recoveredAround(int fromOffset, int toOffset) {
            return recovered != null && fromOffset < recovered[1] && recovered[0] < toOffset;
        }

        @Override
        public List<SyntaxToken> tokenize(Rope document, int from, int to) {
            queries++;
            if (recovering) return List.of();
            List<SyntaxToken> tokens = new ArrayList<>();
            String text = document.toString();
            int limit = Math.min(to, text.length());
            for (int i = Math.max(0, from); i < limit; i++) {
                if (!Character.isLetter(text.charAt(i))) continue;
                int start = i;
                while (i < limit && Character.isLetter(text.charAt(i))) i++;
                tokens.add(new SyntaxToken(start, i, "keyword"));
            }
            return tokens;
        }

        @Override
        public void setInvalidationListener(InvalidationListener listener) {
            this.listener = listener;
        }
    }

    private static final String SOURCE = "class Thing {\n    int value;\n    int other;\n}\n";

    private CountingTokenizer tokenizer;

    private void buildWithGrammar() {
        build(SOURCE);
        tokenizer = new CountingTokenizer();
        editor.setTokenizer(tokenizer);
        showEditor();
    }

    /** Puts the caret at the end of {@code value} on row 1. */
    private void caretAfterValue() {
        editor.setCaret(SOURCE.indexOf("    int value;") + "    int value".length());
        showEditor();
    }

    /** Every highlighted range on {@code row}, in that row's own coordinates. */
    private List<TextRange> highlightedOn(int row) {
        List<TextRange> out = new ArrayList<>();
        String wanted = editor.buffer().document().line(row);
        for (UINode line : linesOf()) {
            UIText text = (UIText) line.children().get(0);
            if (!text.getText().equals(wanted)) continue;
            for (String name : text.highlights().names()) out.addAll(text.highlights().get(name));
            return out;
        }
        return out;
    }

    private boolean covered(int row, int column) {
        for (TextRange range : highlightedOn(row)) {
            if (column >= range.start() && column < range.end()) return true;
        }
        return false;
    }

    /** Past the settle, then frames enough to adopt. */
    private void waitForSettle() {
        try {
            Thread.sleep(400L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        showEditor();
        showEditor();
    }

    // ── The cost ────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>Typing does not re-query the grammar.</b>
     *
     * <p>Measured on a 2,000-line Java file with the production scheduler installed, the grammar tier
     * costs <b>424µs a keystroke</b>, of which 68µs is the per-row query this skips. The rest is
     * {@code edited()} keeping the tree in sync, which cannot be skipped without desynchronising it — so
     * this says how much of the cost it removes rather than implying all of it.</p>
     */
    @Test
    public void typingDoesNotReQueryTheGrammar() {
        buildWithGrammar();
        caretAfterValue();
        tokenizer.queries = 0;

        type("Counter");
        showEditor();

        assertEquals("seven characters must not be seven grammar queries", 0, tokenizer.queries);
    }

    /** ...and stopping does. */
    @Test
    public void settlingReQueriesTheEditedRow() {
        buildWithGrammar();
        caretAfterValue();
        type("Counter");
        showEditor();
        tokenizer.queries = 0;

        waitForSettle();

        assertTrue("the row must colour itself once typing stops", tokenizer.queries > 0);
        assertTrue("and the finished name is coloured", covered(1, "    int ".length()));
    }

    // ── What the screen does ────────────────────────────────────────────────────────────────────

    /**
     * <b>The token being edited goes plain, and nothing else on the row does.</b>
     *
     * <p>Falls out of the mapping rather than needing a rule of its own: a token <em>touching</em> the edit
     * point is dropped, everything else is shifted. Typing at the end of {@code value} extends that token
     * rather than following it, so keeping it would colour a name that no longer exists.</p>
     */
    @Test
    public void theEditedTokenGoesPlainAndTheRestOfTheRowDoesNot() {
        buildWithGrammar();
        caretAfterValue();
        assertTrue("coloured before typing starts", covered(1, "    int ".length()));

        type("Counter");
        showEditor();

        assertFalse("the name being typed must go plain", covered(1, "    int ".length()));
        assertTrue("`int`, which nobody is editing, must keep its colour", covered(1, 4));
    }

    /**
     * <b>The bug this was rewritten for: typing on one row must not recolour any other.</b>
     *
     * <p>A background parse landing mid-edit is refused and remembered rather than applied. Before that,
     * every line below the caret was repainted a beat after each keystroke — from a parse that had
     * recovered around a half-written token — and again a few keystrokes later with a different recovery.
     * The announcement is fired directly here, which is what the tokenizer does when its off-thread parse
     * completes.</p>
     */
    @Test
    public void aBackgroundParseLandingMidEditDoesNotRecolourOtherRows() {
        buildWithGrammar();
        caretAfterValue();
        List<TextRange> before = highlightedOn(2);
        assertFalse("row 2 has colours to lose", before.isEmpty());

        type("Counter");
        showEditor();
        // The off-thread parse finishes and announces, as it does every few keystrokes -- and it has
        // recovered around the half-written name, so it now answers differently for rows nobody touched.
        tokenizer.recovering = true;
        tokenizer.listener.tokensChanged(0, SyntaxTokenizer.InvalidationListener.EVERYTHING);
        showEditor();
        showEditor();

        assertEquals("a row nobody touched must not be repainted mid-edit", before, highlightedOn(2));
    }

    /** ...and once typing settles, that announcement is applied after all. */
    @Test
    public void theRefusedAnnouncementIsAppliedOnSettle() {
        buildWithGrammar();
        caretAfterValue();
        type("Counter");
        showEditor();
        tokenizer.recovering = true;
        tokenizer.listener.tokensChanged(0, SyntaxTokenizer.InvalidationListener.EVERYTHING);
        showEditor();
        assertFalse("refused for now", highlightedOn(2).isEmpty());
        tokenizer.queries = 0;

        waitForSettle();

        assertTrue("a refused announcement must not be forgotten", tokenizer.queries > 0);
        assertTrue("and it is applied once typing stops", highlightedOn(2).isEmpty());
    }

    /**
     * <b>An unfinished statement may not recolour the line below it</b> — and may not hold the rest of the
     * file either.
     *
     * <p>The case behind this: typing {@code Stri} with no semicolon repainted the {@code return} on the
     * next line, and adding the semicolon put it back. The parse is not wrong — that is what the grammar
     * says about incomplete text — it is just less useful than the colours that row already had. A lexer
     * would not do it, which is why IntelliJ does not: a half-written token cannot change how the next
     * line lexes, but it very much changes how the file parses.</p>
     *
     * <p><b>Both halves are the test.</b> Declining the reclassification is easy; declining it only inside
     * the recovery is the part worth pinning, because the obvious rule — "does this file parse" — is false
     * for nearly every file being edited and would freeze the colours of the whole document whenever
     * anything anywhere was unfinished.</p>
     */
    @Test
    public void aRecoveredRegionDoesNotRepaintTheRowsItSwallowsButLeavesTheRestAlone() {
        buildWithGrammar();
        caretAfterValue();
        List<TextRange> swallowed = highlightedOn(2);
        List<TextRange> faraway = highlightedOn(0);
        assertFalse("row 2 has colours to lose", swallowed.isEmpty());
        assertFalse("row 0 has colours to lose", faraway.isEmpty());

        // Typing settles, and the statement is still unfinished: the parser recovers around row 1 and
        // swallows row 2 with it, while row 0 is nowhere near it.
        type("Counter");
        waitForSettle();
        int row2Start = editor.buffer().document().lineStartOffset(2);
        tokenizer.recovered = new int[] {editor.buffer().document().lineStartOffset(1),
                row2Start + editor.buffer().document().line(2).length()};
        tokenizer.recovering = true;
        tokenizer.listener.tokensChanged(0, SyntaxTokenizer.InvalidationListener.EVERYTHING);
        showEditor();
        showEditor();

        assertEquals("the row the recovery swallowed keeps what it had", swallowed, highlightedOn(2));
        assertTrue("a row outside it must still take the new answer", highlightedOn(0).isEmpty());
    }

    /**
     * <b>...and the same is true when the parse lands MID-BURST, which is the case that actually happens.</b>
     *
     * <p>The test above stages its announcement <em>after</em> typing has settled, and that is the rarer
     * half. An analysis is debounced by 300ms and the settle is 300ms, so in ordinary typing the parse
     * lands while {@code editing} is still true — a burst of keystrokes, a pause at the end of a word, the
     * parse arriving in that pause. On that path the rows are recorded in {@code staleRows} rather than
     * dropped, and the settle used to drop <b>every one of them unconditionally</b> without ever asking
     * about the recovery. So the guard was installed on one of the two ways into the cache and missing
     * from the one a keystroke actually takes.</p>
     *
     * <p>Reported as an unfinished line recolouring the line below it, which is the same symptom the test
     * above pins — and it survived that test because the fixture reproduced the sequence nobody types.</p>
     */
    @Test
    public void anAnnouncementLandingMidBurstStillKeepsTheSwallowedRowOnSettle() {
        buildWithGrammar();
        caretAfterValue();
        List<TextRange> swallowed = highlightedOn(2);
        List<TextRange> faraway = highlightedOn(0);
        assertFalse("row 2 has colours to lose", swallowed.isEmpty());
        assertFalse("row 0 has colours to lose", faraway.isEmpty());

        type("Counter");
        showEditor();
        // WHILE STILL EDITING -- no settle between the keystrokes and the parse.
        int row2Start = editor.buffer().document().lineStartOffset(2);
        tokenizer.recovered = new int[] {editor.buffer().document().lineStartOffset(1),
                row2Start + editor.buffer().document().line(2).length()};
        tokenizer.recovering = true;
        tokenizer.listener.tokensChanged(0, SyntaxTokenizer.InvalidationListener.EVERYTHING);
        showEditor();

        waitForSettle();

        assertEquals("the row the recovery swallowed keeps what it had", swallowed, highlightedOn(2));
        assertTrue("a row outside it must still take the new answer", highlightedOn(0).isEmpty());
    }

    /**
     * <b>Pressing Enter keeps the colours of the rows below it.</b>
     *
     * <p>The cache is keyed by row index, so a line-count change makes every row below describe someone
     * else's text. Dropping them all is the easy answer and it repaints the viewport; the keys are moved
     * instead.</p>
     */
    @Test
    public void pressingEnterKeepsTheColoursBelowIt() {
        buildWithGrammar();
        caretAfterValue();
        List<TextRange> otherBefore = highlightedOn(2);
        assertFalse("row 2 has colours to lose", otherBefore.isEmpty());

        key(com.crystalgraphics.platform.input.CgKeyCodes.KEY_RETURN);
        showEditor();

        assertEquals("`int other;` moved down a row and kept its colours",
                otherBefore, highlightedOn(3));
    }

    // ── Starting from nothing ───────────────────────────────────────────────────────────────────

    /** Typing where no token exists is the commonest case, and an earlier draft threw here. */
    @Test
    public void aWordCanBeStartedFromNothing() {
        buildWithGrammar();
        editor.setCaret(SOURCE.indexOf("    int value;") + "    int value;".length());
        showEditor();

        type("S");
        showEditor();

        assertEquals("    int value;S", editor.buffer().document().line(1));
    }

    /** ...including on an otherwise empty row. */
    @Test
    public void aWordCanBeStartedOnAnEmptyRow() {
        build("class Thing {\n\n}\n");
        tokenizer = new CountingTokenizer();
        editor.setTokenizer(tokenizer);
        showEditor();
        editor.setCaret("class Thing {\n".length());
        showEditor();

        type("S");
        showEditor();

        assertEquals("S", editor.buffer().document().line(1));
    }
}
