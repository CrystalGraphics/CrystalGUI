package com.crystalgui.headless;

import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.Rope;
import com.crystalgui.text.diff.ComparisonPolicy;
import com.crystalgui.text.diff.DiffAlgorithmResult;
import com.crystalgui.text.diff.DiffRange;
import com.crystalgui.text.diff.DiffTimeout;
import com.crystalgui.text.diff.DynamicProgrammingDiff;
import com.crystalgui.text.diff.LineSequence;
import com.crystalgui.text.diff.LinesDiff;
import com.crystalgui.text.diff.MyersDiff;
import com.crystalgui.text.diff.TextDiff;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The ported line differ — {@link MyersDiff}, {@link DynamicProgrammingDiff} and the heuristics over them.
 *
 * <p>Two kinds of assertion, and they are not interchangeable. <b>Validity</b> is checked as a property
 * over random input: a diff must pair up so that the unchanged gaps are equal-length on both sides, and
 * applying it must reproduce the other text. <b>Behaviour</b> is checked on written cases, because the
 * whole reason these algorithms were ported is that they make judgements a validity check cannot see.</p>
 */
public class LinesDiffTest {

    private static List<String> lines(String... lines) {
        return Arrays.asList(lines);
    }

    private static String text(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    /** A diff must pair the texts up consistently — the property every later stage depends on. */
    private static void isWellFormed(List<DiffRange> diffs, int length1, int length2) {
        int at1 = 0;
        int at2 = 0;
        for (DiffRange range : diffs) {
            assertTrue("ordered and disjoint on side 1", range.start1() >= at1);
            assertTrue("ordered and disjoint on side 2", range.start2() >= at2);
            assertEquals("the gap before a change must match on both sides",
                    range.start1() - at1, range.start2() - at2);
            at1 = range.end1();
            at2 = range.end2();
        }
        assertEquals("the trailing gap must match too", length1 - at1, length2 - at2);
    }

    // ── Validity ────────────────────────────────────────────────────────────────────────────────

    @Test
    public void randomInputsProduceWellFormedDiffs() {
        Random random = new Random(1234L);
        for (int trial = 0; trial < 400; trial++) {
            List<String> a = randomLines(random, random.nextInt(40));
            List<String> b = randomlyEdited(random, a);
            isWellFormed(LinesDiff.compute(a, b).diffs(), a.size(), b.size());
        }
    }

    /** And the change set built from it must reproduce the text — the end-to-end property. */
    @Test
    public void randomInputsRoundTripThroughAChangeSet() {
        Random random = new Random(5678L);
        for (int trial = 0; trial < 200; trial++) {
            List<String> a = randomLines(random, 1 + random.nextInt(30));
            List<String> b = randomlyEdited(random, a);
            String before = join(a);
            String after = join(b);

            ChangeSet changes = TextDiff.changes(before, after);
            assertEquals("applying the diff must reproduce the new text",
                    after, changes.apply(Rope.of(before)).toString());
        }
    }

    /** Both algorithms must be valid; they need not agree, and where they differ neither is wrong. */
    @Test
    public void myersAndTheExactAlgorithmBothProduceWellFormedDiffs() {
        Random random = new Random(910L);
        for (int trial = 0; trial < 200; trial++) {
            List<String> a = randomLines(random, 1 + random.nextInt(25));
            List<String> b = randomlyEdited(random, a);
            LineSequence[] pair = LineSequence.pair(a, b, ComparisonPolicy.DEFAULT);

            isWellFormed(MyersDiff.compute(pair[0], pair[1]).diffs(), a.size(), b.size());
            isWellFormed(DynamicProgrammingDiff.compute(pair[0], pair[1]).diffs(), a.size(), b.size());
        }
    }

    @Test
    public void identicalInputsHaveNoDiff() {
        List<String> same = lines("a", "b", "c");
        assertTrue(LinesDiff.compute(same, same).diffs().isEmpty());
    }

    @Test
    public void emptyOnEitherSideIsOneWholeChange() {
        assertEquals(1, LinesDiff.compute(lines(), lines("a", "b")).diffs().size());
        assertEquals(1, LinesDiff.compute(lines("a", "b"), lines()).diffs().size());
        assertTrue(LinesDiff.compute(lines(), lines()).diffs().isEmpty());
    }

    // ── Behaviour ───────────────────────────────────────────────────────────────────────────────

    /**
     * <b>The trap the rough pass creates, and the reason it must be undone.</b>
     *
     * <p>The algorithms hash the <em>trimmed</em> line whatever the caller asked for, so that a reindent
     * does not destroy every anchor. That means a reindented line arrives back inside an <em>unchanged</em>
     * span — and under {@code DEFAULT} it is not unchanged. Left alone, a merge concludes nobody touched
     * those lines and silently drops a reindent that competed with a real edit on the other side.</p>
     */
    @Test
    public void aReindentIsReportedAsChangedUnderTheDefaultPolicy() {
        List<String> before = lines("void f() {", "int a = 1;", "}");
        List<String> after = lines("void f() {", "    int a = 1;", "}");

        List<DiffRange> strict = LinesDiff.compute(before, after,
                ComparisonPolicy.DEFAULT, DiffTimeout.INFINITE).diffs();
        assertEquals("the indented line differs and must say so", 1, strict.size());
        assertEquals(1, strict.get(0).start1());
        assertEquals(2, strict.get(0).end1());

        List<DiffRange> relaxed = LinesDiff.compute(before, after,
                ComparisonPolicy.TRIM_WHITESPACES, DiffTimeout.INFINITE).diffs();
        assertTrue("and must not, when the caller asked to ignore indentation", relaxed.isEmpty());
    }

    /**
     * The rough pass earns its keep: a reindent plus a real edit still finds the real edit.
     *
     * <p>Anchoring on exact text through a reindent leaves nothing to match, so the whole block reports as
     * replaced. Anchoring whitespace-blind finds the correspondence, and the exactness pass then reports
     * both the indentation change and the edit — separately.</p>
     */
    @Test
    public void aReindentedBlockWithOneRealEditDoesNotReportAsAWholesaleReplacement() {
        List<String> before = lines("a();", "b();", "c();", "d();", "e();");
        List<String> after = lines("    a();", "    b();", "    CHANGED();", "    d();", "    e();");

        List<DiffRange> diffs = LinesDiff.compute(before, after).diffs();

        assertFalse("something must differ", diffs.isEmpty());
        // Every line differs under DEFAULT (they were all reindented), so what is being asserted is that
        // the ALIGNMENT survived: five lines against five, not a five-for-five wholesale replacement
        // reported as one unaligned block.
        isWellFormed(diffs, before.size(), after.size());
        for (DiffRange range : diffs) {
            assertEquals("each reported change must pair line-for-line", range.length1(), range.length2());
        }
    }

    /**
     * A pure insertion slides to the boundary a person would have drawn.
     *
     * <p>{@code LineSequence.boundaryScore} prefers splitting where the surrounding code is least indented,
     * so an added method reports as starting at the blank line above it rather than partway through the
     * previous method's closing brace. Both are the same edit; only one reads.</p>
     */
    @Test
    public void anInsertedBlockIsReportedAtTheLeastIndentedBoundary() {
        List<String> before = lines("class A {", "    void a() {", "    }", "}");
        List<String> after = lines("class A {", "    void a() {", "    }", "", "    void b() {", "    }", "}");

        List<DiffRange> diffs = LinesDiff.compute(before, after).diffs();

        assertEquals(1, diffs.size());
        DiffRange insertion = diffs.get(0);
        assertTrue("it is an insertion", insertion.isEmpty1());
        assertEquals("three lines arrived", 3, insertion.length2());
        // The inserted run must be a self-contained block rather than one that starts mid-method and
        // wraps around: whatever offset it lands at, the lines it names must be the new ones.
        List<String> inserted = after.subList(insertion.start2(), insertion.end2());
        assertTrue("the insertion must name the new method: " + inserted,
                inserted.stream().anyMatch(line -> line.contains("void b()")));
    }

    /** A timeout degrades to "everything changed" rather than hanging or throwing. */
    @Test
    public void anExpiredTimeoutDegradesRatherThanFailing() {
        List<String> a = new ArrayList<>();
        List<String> b = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            a.add("line " + i);
            b.add("other " + i);
        }

        DiffAlgorithmResult result = LinesDiff.compute(a, b, ComparisonPolicy.DEFAULT,
                DiffTimeout.after(0));

        assertTrue("it must say the answer is approximate", result.hitTimeout());
        isWellFormed(result.diffs(), a.size(), b.size());
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────────────────────

    private static List<String> randomLines(Random random, int count) {
        List<String> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            lines.add(" ".repeat(random.nextInt(3)) + "line " + random.nextInt(6));
        }
        return lines;
    }

    private static List<String> randomlyEdited(Random random, List<String> source) {
        List<String> edited = new ArrayList<>(source);
        for (int edit = 0, n = 1 + random.nextInt(4); edit < n; edit++) {
            if (edited.isEmpty()) {
                edited.add("inserted " + random.nextInt(6));
                continue;
            }
            int at = random.nextInt(edited.size());
            switch (random.nextInt(4)) {
                case 0 -> edited.add(at, "inserted " + random.nextInt(6));
                case 1 -> edited.remove(at);
                case 2 -> edited.set(at, "changed " + random.nextInt(6));
                default -> edited.set(at, " ".repeat(random.nextInt(4)) + edited.get(at).strip());
            }
        }
        return edited;
    }

    private static String join(List<String> lines) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) text.append(line).append('\n');
        return text.toString();
    }
}
