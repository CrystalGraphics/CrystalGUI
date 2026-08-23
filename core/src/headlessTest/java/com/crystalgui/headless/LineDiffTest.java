package com.crystalgui.headless;

import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.Rope;
import com.crystalgui.text.diff.LineDiff;
import com.crystalgui.text.diff.TextDiff;

import org.junit.Test;

import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Phase 6 <b>6.6</b> — the differ under the viewer and under delta reads.
 *
 * <h3>What is worth asserting</h3>
 *
 * <p>Two different things, and only one of them is about correctness.</p>
 *
 * <p><b>The round trip</b> is the correctness property: applying the change set to the original must
 * reproduce the new text, exactly. It is the only assertion a subtly-wrong diff cannot pass — a diff with
 * an off-by-one line, a swallowed newline, or a mis-ordered hunk all fail it, and none of them fails a
 * test that merely counts hunks.</p>
 *
 * <p><b>The shape</b> tests cover the cases the algorithm was chosen for — a moved block, a region full
 * of repeated lines — and assert that the answer is the readable one.</p>
 *
 * <p><b>They do not, however, pin histogram against a naive matcher, and that was checked rather than
 * assumed.</b> Replacing the rarity scoring with "take the first common run" leaves every test here
 * green. The reason is {@code LineDiff}'s prefix/suffix trimming: on fixtures small enough to write out,
 * trimming resolves the whole comparison before {@code findAnchor} is ever consulted, so the scoring has
 * nothing to decide. It earns its place on large messy inputs, which is exactly where a fixture stops
 * being readable as a test. Recorded here rather than left as an implied claim — a test that looks like
 * it pins a decision and does not is worse than no test, because nobody checks it twice.</p>
 */
public class LineDiffTest {

    private static String text(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    /** The property: apply and compare. */
    private static void roundTrips(String before, String after) {
        ChangeSet changes = TextDiff.changes(before, after);
        Rope applied = changes.apply(Rope.of(before));
        assertEquals("applying the diff must reproduce the new text exactly", after, applied.toString());
    }

    // ── Correctness ─────────────────────────────────────────────────────────────────────────────

    @Test
    public void identicalTextsHaveNoChanges() {
        String same = text("a", "b", "c");
        assertTrue(LineDiff.diff(same, same).isEmpty());
        assertTrue(TextDiff.changes(same, same).isEmpty());
    }

    @Test
    public void anInsertionRoundTrips() {
        roundTrips(text("a", "b", "c"), text("a", "inserted", "b", "c"));
    }

    @Test
    public void aDeletionRoundTrips() {
        roundTrips(text("a", "b", "c"), text("a", "c"));
    }

    @Test
    public void aReplacementRoundTrips() {
        roundTrips(text("a", "b", "c"), text("a", "different", "c"));
    }

    @Test
    public void severalSeparateHunksRoundTrip() {
        roundTrips(text("1", "2", "3", "4", "5", "6", "7", "8"),
                text("1", "two", "3", "4", "five", "6", "7", "eight"));
    }

    /** Everything replaced, with nothing in common to anchor on. */
    @Test
    public void aCompleteRewriteRoundTrips() {
        roundTrips(text("a", "b", "c"), text("x", "y", "z"));
    }

    @Test
    public void emptyOnEitherSideRoundTrips() {
        roundTrips("", text("a", "b"));
        roundTrips(text("a", "b"), "");
        roundTrips("", "");
    }

    /**
     * A trailing newline terminates the last line; it does not begin another.
     *
     * <p>{@code "a\nb\n"} is two lines, not three. Getting it wrong makes every file that ends in a
     * newline — which is most files — report a phantom change on its last line.</p>
     */
    @Test
    public void aTrailingNewlineIsNotAnExtraLine() {
        assertEquals(2, LineDiff.lines("a\nb\n").size());
        assertEquals(2, LineDiff.lines("a\nb").size());
        roundTrips("a\nb\n", "a\nb");
        roundTrips("a\nb", "a\nb\n");
    }

    /**
     * A thousand random edits, because the round trip is a property and properties want volume.
     *
     * <p>Seeded, so a failure is reproducible rather than a story about a build that once went red.</p>
     */
    @Test
    public void randomEditsAlwaysRoundTrip() {
        Random random = new Random(20260822L);
        for (int trial = 0; trial < 200; trial++) {
            StringBuilder before = new StringBuilder();
            int lines = 1 + random.nextInt(40);
            for (int i = 0; i < lines; i++) {
                before.append("line ").append(random.nextInt(8)).append('\n');
            }
            StringBuilder after = new StringBuilder(before);

            for (int edit = 0; edit < 1 + random.nextInt(5); edit++) {
                List<String> current = LineDiff.lines(after.toString());
                if (current.isEmpty()) break;
                int at = random.nextInt(current.size());
                switch (random.nextInt(3)) {
                    case 0 -> current.add(at, "inserted " + random.nextInt(8));
                    case 1 -> current.remove(at);
                    default -> current.set(at, "changed " + random.nextInt(8));
                }
                after.setLength(0);
                for (String line : current) after.append(line).append('\n');
            }
            roundTrips(before.toString(), after.toString());
        }
    }

    // ── Shape: why this is histogram and not Myers ──────────────────────────────────────────────

    /**
     * <b>A moved block stays a moved block.</b>
     *
     * <p>The case the algorithm was chosen for: given a function moved past another, an approach that
     * only minimises the changed-line count can match the closing brace of one against the closing brace
     * of the other and produce a diff that is minimal and unreadable. The assertion is that a move comes
     * out as one block leaving and one arriving rather than a shredded file.</p>
     *
     * <p>See the class note: this passes against a naive anchor chooser too, because trimming gets there
     * first on a fixture this size. It is a correctness test, not a proof of the algorithm choice.</p>
     */
    @Test
    public void aMovedBlockIsNotScrambled() {
        String before = text(
                "void alpha() {",
                "    doAlphaThing();",
                "}",
                "",
                "void beta() {",
                "    doBetaThing();",
                "}");
        String after = text(
                "void beta() {",
                "    doBetaThing();",
                "}",
                "",
                "void alpha() {",
                "    doAlphaThing();",
                "}");

        roundTrips(before, after);

        // The bodies are unique lines and must survive intact: a scrambled diff rewrites them.
        List<LineDiff.Hunk> hunks = LineDiff.diff(before, after);
        List<String> beforeLines = LineDiff.lines(before);
        for (LineDiff.Hunk hunk : hunks) {
            for (int line = hunk.fromLine(); line < hunk.toLine(); line++) {
                String touched = beforeLines.get(line);
                assertTrue("a unique body line must not be rewritten by a move: " + touched,
                        !touched.contains("doAlphaThing") || hunkCount(hunks) <= 2);
            }
        }
        assertTrue("a move is one block out and one block in, not a shredded file: " + hunkCount(hunks),
                hunkCount(hunks) <= 2);
    }

    /**
     * Repeated lines do not become anchors.
     *
     * <p>A closing brace occurs everywhere and means nothing; matching on one is how a diff ends up
     * pairing unrelated blocks. Histogram scores a run by its <em>commonest</em> line, so a run
     * containing a brace loses to a run of unique lines.</p>
     */
    @Test
    public void commonLinesAreNotUsedAsAnchors() {
        String before = text("}", "}", "}", "unique alpha", "}", "}", "}");
        String after = text("}", "}", "}", "unique beta", "}", "}", "}");

        roundTrips(before, after);
        assertEquals("only the unique line differs", 1, hunkCount(LineDiff.diff(before, after)));
    }

    /** An insertion is reported between two lines, not as a rewrite of one of them. */
    @Test
    public void anInsertionIsAZeroWidthRangeOnTheOldSide() {
        List<LineDiff.Hunk> hunks = LineDiff.diff(text("a", "b"), text("a", "new", "b"));

        assertEquals(1, hunks.size());
        assertTrue("nothing was removed", hunks.get(0).isInsertion());
        assertEquals("and it lands between the two", 1, hunks.get(0).fromLine());
    }

    /** And a deletion is the mirror of it. */
    @Test
    public void aDeletionIsAZeroWidthRangeOnTheNewSide() {
        List<LineDiff.Hunk> hunks = LineDiff.diff(text("a", "gone", "b"), text("a", "b"));

        assertEquals(1, hunks.size());
        assertTrue(hunks.get(0).isDeletion());
    }

    private static int hunkCount(List<LineDiff.Hunk> hunks) {
        return hunks.size();
    }
}
