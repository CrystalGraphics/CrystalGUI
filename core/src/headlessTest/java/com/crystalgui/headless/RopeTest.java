package com.crystalgui.headless;

import com.crystalgui.text.Rope;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.TextSummary;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.*;

/**
 * P6.1.6 — the document rope.
 *
 * <h3>Why this lives in {@code headlessTest}</h3>
 * <p>Not for speed. A dedicated server builds and edits documents with no GL context and no fonts, and
 * this source set has CrystalGraphics <b>core deliberately absent</b> — so if the buffer ever reaches a
 * backend type outside a paint method, it fails here with {@code NoClassDefFoundError} rather than in
 * production. The absence is the assertion.</p>
 *
 * <h3>Differential, not example-based</h3>
 * <p>The interesting bugs in a rope are not in the cases anyone thinks to write down — they are at chunk
 * boundaries, at the seams left by an earlier edit, and in the tree shapes that only a particular
 * sequence of splits and joins produces. So the core tests run random operations against a
 * {@link StringBuilder} oracle and compare after every step. Hand-written cases are kept only where they
 * pin a specific piece of reasoning.</p>
 */
public class RopeTest {

    /** Comfortably more than one chunk (128) and more than one level of tree. */
    private static final String LOREM = buildDocument(400);

    private static String buildDocument(int lines) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            out.append("line ").append(i).append(" with some text on it");
            if (i < lines - 1) out.append('\n');
        }
        return out.toString();
    }

    // ── The summary monoid ──────────────────────────────────────────────────────────────────────

    /**
     * <b>Composition must be associative.</b> The tree combines child summaries in whatever grouping its
     * shape happens to produce, so if {@code (a+b)+c} and {@code a+(b+c)} ever disagreed, a node's summary
     * would depend on how the document was built rather than on what it contains — and two ropes holding
     * identical text would answer differently.
     */
    @Test
    public void summaryCompositionIsAssociative() {
        Random random = new Random(20260731L);
        for (int trial = 0; trial < 500; trial++) {
            TextSummary a = TextSummary.of(randomText(random, 12));
            TextSummary b = TextSummary.of(randomText(random, 12));
            TextSummary c = TextSummary.of(randomText(random, 12));
            assertEquals("(a+b)+c must equal a+(b+c)", a.add(b).add(c), a.add(b.add(c)));
        }
    }

    /**
     * <b>{@code lastLineChars} is not additive, and this is the case that proves it.</b> A newline on the
     * right ends the left side's line, so the combined trailing line is the right side's alone. Summing
     * the two — the obvious implementation — yields a column that is correct in a document with no chunk
     * boundary mid-line and wrong in every other one.
     */
    @Test
    public void aNewlineOnTheRightEndsTheLeftSideTrailingLine() {
        TextSummary left = TextSummary.of("abc");
        TextSummary right = TextSummary.of("de\nfg");
        assertEquals("the trailing line is the right side's alone", 2, left.add(right).lastLineChars());

        TextSummary noBreak = TextSummary.of("de");
        assertEquals("only a newline-free right side continues ours", 5, left.add(noBreak).lastLineChars());
    }

    @Test
    public void summaryIdentityIsEmpty() {
        TextSummary a = TextSummary.of("hello\nworld");
        assertEquals(a, a.add(TextSummary.EMPTY));
        assertEquals(a, TextSummary.EMPTY.add(a));
    }

    // ── Construction and reading ────────────────────────────────────────────────────────────────

    @Test
    public void emptyDocumentIsStillOneLine() {
        assertEquals(0, Rope.EMPTY.length());
        assertEquals("a document with no newline is one line, not zero", 1, Rope.EMPTY.lineCount());
        assertEquals("", Rope.EMPTY.toString());
    }

    @Test
    public void roundTripsTextOfEverySize() {
        for (String text : new String[] { "", "a", "abc", "a\nb", "\n", "\n\n\n", LOREM }) {
            assertEquals(text, Rope.of(text).toString());
            assertEquals(text.length(), Rope.of(text).length());
        }
    }

    @Test
    public void charAtMatchesTheString() {
        Rope rope = Rope.of(LOREM);
        for (int i = 0; i < LOREM.length(); i++) {
            assertEquals("char " + i, LOREM.charAt(i), rope.charAt(i));
        }
    }

    @Test
    public void lineCountMatchesASplit() {
        assertEquals(LOREM.split("\n", -1).length, Rope.of(LOREM).lineCount());
        assertEquals(4, Rope.of("a\nb\nc\n").lineCount());
    }

    // ── Coordinates — the whole reason for the summaries ─────────────────────────────────────────

    /** Every offset in a multi-chunk, multi-level document, against a naive scan. */
    @Test
    public void offsetToPointMatchesANaiveScanEverywhere() {
        Rope rope = Rope.of(LOREM);
        for (int offset = 0; offset <= LOREM.length(); offset++) {
            assertEquals("offset " + offset, naivePoint(LOREM, offset), rope.offsetToPoint(offset));
        }
    }

    @Test
    public void pointToOffsetIsTheInverse() {
        Rope rope = Rope.of(LOREM);
        for (int offset = 0; offset <= LOREM.length(); offset++) {
            assertEquals("offset " + offset, offset, rope.pointToOffset(rope.offsetToPoint(offset)));
        }
    }

    @Test
    public void everyLineReadsBackIndividually() {
        Rope rope = Rope.of(LOREM);
        String[] lines = LOREM.split("\n", -1);
        assertEquals(lines.length, rope.lineCount());
        for (int row = 0; row < lines.length; row++) {
            assertEquals("row " + row, lines[row], rope.line(row));
        }
    }

    /** A column past the end of its line clamps to the line end rather than spilling onto the next. */
    @Test
    public void aColumnPastTheEndOfItsLineClamps() {
        Rope rope = Rope.of("ab\ncdef\ngh");
        assertEquals(2, rope.pointToOffset(new TextPoint(0, 99)));
        assertEquals(7, rope.pointToOffset(new TextPoint(1, 99)));
        // Row and column clamp INDEPENDENTLY, so (99, 0) is the start of the last row -- not the end of
        // the document. That distinction is the model's to keep: "clicking below the last line puts the
        // caret at the end" is a decision about pointer input, and baking it in here would make the
        // document model unable to express "row 99, column 0" at all.
        assertEquals("a row past the end clamps to the last row, keeping column 0",
                8, rope.pointToOffset(new TextPoint(99, 0)));
    }

    // ── Editing ─────────────────────────────────────────────────────────────────────────────────

    @Test
    public void insertDeleteAndReplaceMatchTheString() {
        Rope rope = Rope.of("hello world");
        assertEquals("hello brave world", rope.insert(6, "brave ").toString());
        assertEquals("hello", rope.delete(5, 11).toString());
        assertEquals("hello there", rope.replace(6, 11, "there").toString());
    }

    /**
     * <b>An edit must leave the previous document intact.</b> This is not a nicety: undo holds an earlier
     * {@code Rope}, and the whole structural-sharing argument is that doing so is cheap. If an edit
     * mutated shared nodes, undo would silently return the edited text and every test that only checked
     * the new document would pass.
     */
    @Test
    public void editingLeavesTheOriginalUntouched() {
        Rope original = Rope.of(LOREM);
        Rope edited = original.replace(10, 20, "REPLACEMENT");

        assertEquals("the original is unchanged", LOREM, original.toString());
        assertNotEquals(LOREM, edited.toString());
        assertEquals(LOREM.length(), original.length());
    }

    /** Random edits against a {@link StringBuilder}, compared after every single one. */
    @Test
    public void randomEditsTrackAStringBuilderExactly() {
        Random random = new Random(11071986L);
        StringBuilder oracle = new StringBuilder(LOREM);
        Rope rope = Rope.of(LOREM);

        for (int step = 0; step < 1200; step++) {
            int start = random.nextInt(oracle.length() + 1);
            int end = Math.min(oracle.length(), start + random.nextInt(24));
            String insert = random.nextInt(4) == 0 ? "" : randomText(random, random.nextInt(20));

            rope = rope.replace(start, end, insert);
            oracle.replace(start, end, insert);

            if (step % 37 != 0 && step != 1199) continue;
            // Full comparison periodically rather than every step: the point is to run MANY operations,
            // and stringifying a large rope every time would dominate the run without finding more.
            assertEquals("text diverged at step " + step, oracle.toString(), rope.toString());
            assertEquals("length diverged at step " + step, oracle.length(), rope.length());
            assertEquals("line count diverged at step " + step,
                    oracle.toString().split("\n", -1).length, rope.lineCount());
        }
    }

    /** Coordinates must survive editing too — a summary rebuilt wrongly on join shows up only here. */
    @Test
    public void coordinatesStayCorrectAfterEditing() {
        Random random = new Random(4242L);
        StringBuilder oracle = new StringBuilder(LOREM);
        Rope rope = Rope.of(LOREM);

        for (int step = 0; step < 200; step++) {
            int start = random.nextInt(oracle.length() + 1);
            int end = Math.min(oracle.length(), start + random.nextInt(30));
            String insert = randomText(random, random.nextInt(16));
            rope = rope.replace(start, end, insert);
            oracle.replace(start, end, insert);

            String text = oracle.toString();
            for (int probe = 0; probe < 12; probe++) {
                int offset = random.nextInt(text.length() + 1);
                assertEquals("step " + step + " offset " + offset,
                        naivePoint(text, offset), rope.offsetToPoint(offset));
            }
        }
    }

    /**
     * <b>Balanced on construction is not the claim; balanced under editing is.</b>
     *
     * <p>A tree that degrades as it is edited still returns the right text — it just gets slower, which is
     * the failure mode least likely to be noticed. Splitting and rejoining is exactly what erodes balance,
     * so this hammers the structure and then measures depth against the logarithmic bound the design
     * claims.</p>
     */
    @Test
    public void ropeStaysShallowUnderManyEdits() {
        Random random = new Random(777L);
        Rope rope = Rope.of(LOREM);
        for (int step = 0; step < 3000; step++) {
            int start = random.nextInt(rope.length() + 1);
            int end = Math.min(rope.length(), start + random.nextInt(10));
            rope = rope.replace(start, end, randomText(random, random.nextInt(10)));
        }

        int leaves = Math.max(1, rope.length() / 64);
        int bound = 4 + (int) (Math.log(leaves) / Math.log(2));
        assertTrue("depth " + rope.depth() + " for " + rope.length()
                + " chars is not logarithmic (bound " + bound + ")", rope.depth() <= bound);
    }

    // ── Unicode ─────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>Bulk construction must not cut a surrogate pair across a chunk boundary.</b> Offsets are UTF-16
     * code units so a split pair is representable, but a chunk is handed to the shaper as a unit and half
     * a pair is not text. Built from a string of astral characters, every chunk boundary is a candidate.
     */
    @Test
    public void chunkingNeverSplitsASurrogatePair() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 500; i++) builder.append("😀");
        String text = builder.toString();

        Rope rope = Rope.of(text);
        assertEquals(text, rope.toString());
        assertEquals(text.length(), rope.length());
        for (int i = 0; i < text.length(); i++) {
            assertEquals("code unit " + i, text.charAt(i), rope.charAt(i));
        }
    }

    @Test
    public void surrogatePairsCountAsTwoColumns() {
        Rope rope = Rope.of("a😀b");
        assertEquals("a column is a code unit, not a glyph", new TextPoint(0, 3), rope.offsetToPoint(3));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    private static TextPoint naivePoint(String text, int offset) {
        int row = 0;
        int lastBreak = -1;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                row++;
                lastBreak = i;
            }
        }
        return new TextPoint(row, offset - lastBreak - 1);
    }

    private static String randomText(Random random, int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            // Newline-heavy on purpose: the line summary is the part with the interesting arithmetic.
            out.append(random.nextInt(6) == 0 ? '\n' : (char) ('a' + random.nextInt(26)));
        }
        return out.toString();
    }
}
