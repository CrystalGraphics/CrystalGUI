package com.crystalgui.headless;

import com.crystalgui.text.Change;
import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.Rope;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

/**
 * P6.1.6 — described edits, position mapping, inversion and composition.
 *
 * <h3>Everything here has an oracle</h3>
 * <p>A change set is defined entirely by what it does to documents and positions, so it can be tested
 * against those definitions rather than against examples:</p>
 * <ul>
 *   <li>{@code compose(a, b)} must do to a document exactly what applying {@code a} then {@code b} does.</li>
 *   <li>{@code invert} applied to the result must give the original document back.</li>
 *   <li>Applying a change set must produce a document of the length it claims.</li>
 * </ul>
 * <p>Those hold for <em>every</em> input, so the tests generate random ones. Composition is where a
 * hand-picked case is least useful: the failure mode is two edits in two different coordinate systems,
 * which needs at least two changes in each set before it can appear at all.</p>
 */
public class ChangeSetTest {

    private static final String DOC = "the quick brown fox\njumps over\nthe lazy dog\nand keeps going";

    // ── Applying ────────────────────────────────────────────────────────────────────────────────

    @Test
    public void appliesASingleReplacement() {
        Rope doc = Rope.of("hello world");
        ChangeSet set = ChangeSet.replace(doc.length(), 6, 11, "there");
        assertEquals("hello there", set.apply(doc).toString());
        assertEquals(11, set.lengthBefore());
        assertEquals(11, set.lengthAfter());
    }

    /**
     * <b>Several edits at once are in the coordinates of the same original document.</b> That is what
     * makes them a set rather than a sequence — the second offset is not shifted by the first, and a
     * caller who wants that wants {@code compose}. Applying back to front is how the implementation keeps
     * that promise, and this is the test that would fail if it ever applied forwards.
     */
    @Test
    public void severalEditsShareTheOriginalCoordinateSystem() {
        Rope doc = Rope.of("aaa bbb ccc");
        ChangeSet set = ChangeSet.of(doc.length(), List.of(
                new Change(0, 3, "XXXXXX"),
                new Change(8, 11, "Z")));
        assertEquals("XXXXXX bbb Z", set.apply(doc).toString());
    }

    @Test
    public void overlappingChangesAreRefusedRatherThanNormalised() {
        try {
            ChangeSet.of(20, List.of(new Change(0, 10, "x"), new Change(5, 15, "y")));
            fail("overlapping changes have no defined combined meaning and must be refused");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("compose"));
        }
    }

    @Test
    public void applyingAlwaysProducesTheClaimedLength() {
        Random random = new Random(31337L);
        Rope doc = Rope.of(DOC);
        for (int trial = 0; trial < 300; trial++) {
            ChangeSet set = randomSet(random, doc.length());
            assertEquals("trial " + trial, set.lengthAfter(), set.apply(doc).length());
        }
    }

    // ── Inversion — the whole of undo ────────────────────────────────────────────────────────────

    /**
     * <b>Undo is not a separate mechanism, and this is the property that makes that true.</b> If it ever
     * failed, an undo stack built on it would return a document subtly unlike the one the user had, which
     * is worse than an undo that visibly does nothing.
     */
    @Test
    public void invertingAnEditRestoresTheDocumentExactly() {
        Random random = new Random(90210L);
        Rope doc = Rope.of(DOC);
        for (int trial = 0; trial < 400; trial++) {
            ChangeSet set = randomSet(random, doc.length());
            Rope edited = set.apply(doc);
            Rope restored = set.invert(doc).apply(edited);
            assertEquals("trial " + trial, doc.toString(), restored.toString());
        }
    }

    @Test
    public void anInvertedEditAppliesToTheDocumentTheOriginalProduced() {
        Rope doc = Rope.of("hello world");
        ChangeSet set = ChangeSet.replace(doc.length(), 0, 5, "goodbye");
        ChangeSet undo = set.invert(doc);
        assertEquals("goodbye world".length(), undo.lengthBefore());
        assertEquals(doc.length(), undo.lengthAfter());
    }

    // ── Composition ─────────────────────────────────────────────────────────────────────────────

    /**
     * <b>The defining property of composition</b>, over random pairs of multi-change sets. One change per
     * set cannot exercise it: the bug composition invites is mixing up which document an offset belongs
     * to, and with a single change the two coordinate systems coincide often enough to hide it.
     */
    @Test
    public void composingTwoEditsEqualsApplyingThemInTurn() {
        Random random = new Random(1234567L);
        Rope doc = Rope.of(DOC);
        for (int trial = 0; trial < 600; trial++) {
            ChangeSet first = randomSet(random, doc.length());
            Rope middle = first.apply(doc);
            ChangeSet second = randomSet(random, middle.length());

            String sequential = second.apply(middle).toString();
            String composed = first.compose(second).apply(doc).toString();
            assertEquals("trial " + trial, sequential, composed);
        }
    }

    @Test
    public void composingAcrossAMismatchedDocumentIsRefused() {
        ChangeSet first = ChangeSet.replace(10, 0, 5, "");
        try {
            first.compose(ChangeSet.replace(10, 0, 1, "x"));
            fail("the second set applies to a document the first does not produce");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("length"));
        }
    }

    @Test
    public void composingWithNothingChangesNothing() {
        Rope doc = Rope.of(DOC);
        ChangeSet set = ChangeSet.replace(doc.length(), 4, 9, "slow");
        assertEquals(set.apply(doc).toString(),
                set.compose(ChangeSet.empty(set.lengthAfter())).apply(doc).toString());
        assertEquals(set.apply(doc).toString(),
                ChangeSet.empty(doc.length()).compose(set).apply(doc).toString());
    }

    /** A run of single keystrokes composing into one change set — undo coalescing, structurally. */
    @Test
    public void aRunOfKeystrokesComposesIntoOneChange() {
        Rope doc = Rope.of("fn ");
        ChangeSet typed = ChangeSet.empty(doc.length());
        Rope current = doc;
        for (char c : "main".toCharArray()) {
            ChangeSet stroke = ChangeSet.of(current.length(), Change.insert(current.length(), String.valueOf(c)));
            current = stroke.apply(current);
            typed = typed.compose(stroke);
        }

        assertEquals("fn main", current.toString());
        assertEquals("four keystrokes are one undo step", 1, typed.changes().size());
        assertEquals("fn main", typed.apply(doc).toString());
        assertEquals("and one undo returns the lot", "fn ", typed.invert(doc).apply(current).toString());
    }

    // ── Mapping positions ───────────────────────────────────────────────────────────────────────

    @Test
    public void positionsBeforeAnEditDoNotMove() {
        ChangeSet set = ChangeSet.replace(20, 10, 15, "xyz");
        assertEquals(0, set.mapPos(0, -1));
        assertEquals(5, set.mapPos(5, -1));
    }

    @Test
    public void positionsAfterAnEditShiftByItsDelta() {
        ChangeSet set = ChangeSet.replace(20, 10, 15, "xyz");
        assertEquals("5 removed, 3 inserted", 18, set.mapPos(20, 1));
    }

    /**
     * <b>{@code assoc} is the tie-break a position alone cannot express.</b> Text inserted exactly at a
     * position could land before it or after it, and which is right depends on what the position means —
     * a caret that just typed wants to stay after its own text, while the start of a selection wants to
     * stay put. A global policy makes one of those two wrong, which is why this is a parameter.
     */
    @Test
    public void assocDecidesWhichSideOfAnInsertionAPositionLandsOn() {
        ChangeSet set = ChangeSet.of(10, Change.insert(5, "abc"));
        assertEquals("biased before, it stays put", 5, set.mapPos(5, -1));
        assertEquals("biased after, it follows the insertion", 8, set.mapPos(5, 1));
    }

    @Test
    public void aPositionInsideDeletedTextCollapsesToTheEditPoint() {
        ChangeSet set = ChangeSet.of(20, Change.delete(5, 15));
        assertEquals(5, set.mapPos(10, -1));
        assertEquals(5, set.mapPos(10, 1));
    }

    /**
     * Anything that should disappear along with its text asks for {@link ChangeSet.MapMode#TRACK_DELETION}
     * — a search highlight over deleted words should vanish, not pile up at the deletion point.
     */
    @Test
    public void trackDeletionReportsAPositionWhoseTextIsGone() {
        ChangeSet set = ChangeSet.of(20, Change.delete(5, 15));
        assertEquals(ChangeSet.DELETED, set.mapPos(10, 1, ChangeSet.MapMode.TRACK_DELETION));
        assertEquals("an untouched position is still fine",
                4, set.mapPos(4, 1, ChangeSet.MapMode.TRACK_DELETION));
    }

    /** A range keeps covering what it covered, which means its ends bias outward. */
    @Test
    public void mappingARangeBiasesItsEndsOutward() {
        ChangeSet set = ChangeSet.of(20, Change.insert(10, "XXX"));
        int[] range = set.mapRange(10, 15, ChangeSet.MapMode.SIMPLE);
        assertNotNull(range);
        assertEquals("the start does not get pushed past the insertion", 10, range[0]);
        assertEquals(18, range[1]);
    }

    /**
     * <b>Composition preserves the document exactly, and coarsens position mapping. Both are pinned.</b>
     *
     * <p>The tempting property — that mapping through {@code compose(a, b)} equals mapping through {@code a}
     * then {@code b} — <b>is not a theorem</b>, and asserting it was the first thing tried here. When two
     * replaced regions end up adjacent with no surviving original text between them, composition merges
     * them, because in the original document's coordinates they are one contiguous span. A position on the
     * former boundary is then <em>interior</em> to a deletion rather than on its edge, and collapses to the
     * start instead of landing between the two insertions.</p>
     *
     * <p>The boundary could be kept by tagging every insertion with the original range it replaced. It is
     * deliberately not: the divergence only moves a position that sits in text which is deleted either way,
     * and the composed edit still produces a byte-identical document — which is the property undo actually
     * rests on, and is tested over 600 random pairs above.</p>
     */
    @Test
    public void composingCoarsensMappingWhereItMergesAdjacentEdits() {
        ChangeSet first = ChangeSet.replace(20, 0, 6, "abc");
        ChangeSet second = ChangeSet.replace(first.lengthAfter(), 3, 9, "XY");
        ChangeSet composed = first.compose(second);

        assertEquals("the two edits are one contiguous span of the original", 1, composed.changes().size());
        assertEquals("stepwise keeps the boundary", 3, second.mapPos(first.mapPos(6, -1), -1));
        assertEquals("composed sees position 6 as interior, and collapses it", 0, composed.mapPos(6, -1));
    }

    /**
     * <b>What is guaranteed:</b> a position untouched by either edit maps the same way regardless of
     * whether the edits were composed first. That covers every anchor outside the edited region, which is
     * nearly all of them in a real document.
     */
    @Test
    public void positionsBeforeEveryEditMapIdenticallyThroughACompose() {
        Random random = new Random(24680L);
        Rope doc = Rope.of(DOC);
        for (int trial = 0; trial < 400; trial++) {
            ChangeSet first = randomSet(random, doc.length());
            ChangeSet second = randomSet(random, first.lengthAfter());
            ChangeSet composed = first.compose(second);

            int firstTouched = earliestTouchedOffset(first, second, composed);
            for (int position = 0; position < firstTouched; position++) {
                assertEquals("trial " + trial + " position " + position,
                        second.mapPos(first.mapPos(position, -1), -1), composed.mapPos(position, -1));
            }
        }
    }

    /**
     * <b>And the case coalescing actually depends on:</b> a run of keystrokes at one point composes into a
     * single change through which the caret maps exactly as it did stroke by stroke. This is the merge that
     * an undo stack performs constantly, so it is the one that has to be exact rather than merely safe.
     */
    @Test
    public void mappingAcrossACoalescedRunOfKeystrokesIsExact() {
        Rope doc = Rope.of("fn ");
        int caret = 3;
        int stepwiseCaret = caret;
        ChangeSet coalesced = ChangeSet.empty(doc.length());
        Rope current = doc;

        for (char c : "main()".toCharArray()) {
            ChangeSet stroke = ChangeSet.of(current.length(), Change.insert(stepwiseCaret, String.valueOf(c)));
            stepwiseCaret = stroke.mapPos(stepwiseCaret, 1);
            current = stroke.apply(current);
            coalesced = coalesced.compose(stroke);
        }

        assertEquals("fn main()", current.toString());
        assertEquals("the caret follows its own typing", 9, stepwiseCaret);
        assertEquals("and lands identically through the coalesced step",
                stepwiseCaret, coalesced.mapPos(caret, 1));
    }

    /** A mapped position is always inside the document it maps into — no mode, no input, ever escapes. */
    @Test
    public void aMappedPositionIsAlwaysInsideTheResultingDocument() {
        Random random = new Random(5150L);
        Rope doc = Rope.of(DOC);
        for (int trial = 0; trial < 300; trial++) {
            ChangeSet set = randomSet(random, doc.length());
            for (int position = 0; position <= doc.length(); position++) {
                for (int assoc : new int[] { -1, 1 }) {
                    int mapped = set.mapPos(position, assoc);
                    assertTrue("trial " + trial + " position " + position + " -> " + mapped,
                            mapped >= 0 && mapped <= set.lengthAfter());
                }
            }
        }
    }

    /** The lowest offset any of the three sets touches. */
    private static int earliestTouchedOffset(ChangeSet first, ChangeSet second, ChangeSet composed) {
        int earliest = Integer.MAX_VALUE;
        for (ChangeSet set : new ChangeSet[] { first, second, composed }) {
            if (!set.changes().isEmpty()) earliest = Math.min(earliest, set.changes().get(0).from());
        }
        return earliest == Integer.MAX_VALUE ? 0 : earliest;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    /** Up to three sorted, non-overlapping changes over a document of the given length. */
    private static ChangeSet randomSet(Random random, int documentLength) {
        List<Change> changes = new ArrayList<>();
        int cursor = 0;
        int count = random.nextInt(4);
        for (int i = 0; i < count && cursor < documentLength; i++) {
            int from = cursor + random.nextInt(Math.max(1, (documentLength - cursor) / 2 + 1));
            int to = Math.min(documentLength, from + random.nextInt(8));
            String insert = random.nextInt(3) == 0 ? "" : randomText(random, random.nextInt(6));
            changes.add(new Change(from, to, insert));
            cursor = to + 1;
        }
        return ChangeSet.of(documentLength, changes);
    }

    private static String randomText(Random random, int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(random.nextInt(7) == 0 ? '\n' : (char) ('A' + random.nextInt(26)));
        }
        return out.toString();
    }
}
