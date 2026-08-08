package com.crystalgui.headless;

import com.crystalgui.text.TextPoint;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSet;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The diagnostic model — headless, because none of it needs a window and a server that never renders
 * anything still has to be able to hold the problems it found.
 */
public class DiagnosticSetTest {

    private static Diagnostic at(int row, int column, DiagnosticSeverity severity, String message) {
        return new Diagnostic(new TextPoint(row, column), new TextPoint(row, column + 1),
                severity, message, null, null);
    }

    private static Diagnostic error(int row) {
        return at(row, 0, DiagnosticSeverity.ERROR, "boom " + row);
    }

    // ── Ordering ────────────────────────────────────────────────────────────────────────────────

    /** Document order, whatever order they arrived in — a Problems panel and next/previous navigation
     * both read the list directly, so sorting at write time is what makes both correct for free. */
    @Test
    public void contentsAreKeptInDocumentOrder() {
        DiagnosticSet set = new DiagnosticSet();
        set.setAll(List.of(error(9), error(2), error(5)));

        assertEquals(List.of(2, 5, 9), set.all().stream().map(d -> d.start().row()).toList());
    }

    /** Two problems at one spot show the worse first. */
    @Test
    public void severityBreaksAPositionTie() {
        DiagnosticSet set = new DiagnosticSet();
        set.setAll(List.of(at(3, 0, DiagnosticSeverity.WARNING, "w"),
                at(3, 0, DiagnosticSeverity.ERROR, "e")));

        assertEquals(DiagnosticSeverity.ERROR, set.all().get(0).severity());
    }

    /**
     * A backwards range is normalised rather than rejected.
     *
     * <p>A producer that reports {@code end < start} has a bug, but dropping the diagnostic would hide the
     * problem it was trying to report — the failure would be an error that silently never appears.</p>
     */
    @Test
    public void aBackwardsRangeIsNormalisedNotDropped() {
        Diagnostic backwards = new Diagnostic(new TextPoint(4, 9), new TextPoint(4, 2),
                DiagnosticSeverity.ERROR, "reversed", null, null);

        assertEquals(new TextPoint(4, 2), backwards.start());
        assertEquals(new TextPoint(4, 9), backwards.end());
    }

    // ── Change notification ─────────────────────────────────────────────────────────────────────

    /**
     * <b>An unchanged recompile is silent.</b>
     *
     * <p>Producers replace the whole set on every compile, and a file that is still broken in the same way
     * produces an equal list. Without the equality guard every recompile would repaint every squiggle in
     * the file — the same bargain {@code replaceOrPutCandidate} makes to stop widget geometry oscillating.</p>
     */
    @Test
    public void replacingWithAnEqualSetEmitsNoChange() {
        DiagnosticSet set = new DiagnosticSet();
        AtomicInteger changes = new AtomicInteger();
        set.onChanged.connect(changes::incrementAndGet);

        set.setAll(List.of(error(1), error(4)));
        assertEquals(1, changes.get());

        set.setAll(List.of(error(1), error(4)));
        assertEquals("an identical recompile must not repaint", 1, changes.get());

        // ...and the guard must not be so eager that a real change is swallowed.
        set.setAll(List.of(error(1), error(5)));
        assertEquals(2, changes.get());
    }

    /** Arrival order must not count as a change either, since the set is sorted on the way in. */
    @Test
    public void reorderedButEqualContentsEmitNoChange() {
        DiagnosticSet set = new DiagnosticSet();
        set.setAll(List.of(error(1), error(4)));
        AtomicInteger changes = new AtomicInteger();
        set.onChanged.connect(changes::incrementAndGet);

        set.setAll(List.of(error(4), error(1)));

        assertEquals(0, changes.get());
    }

    // ── Owners ──────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>An owner replaces only itself.</b>
     *
     * <p>The reason the set is keyed at all — VS Code's {@code (owner, resource)}, IntelliJ's per-inspection
     * highlights. A flat list means the last writer wins, which is the failure {@code Workbench.onStatus}
     * had before the status bar was keyed, arriving a second time in a different package.</p>
     *
     * <p>It was already binding: {@code ShaderGraphEditor} has four independent producers — the emitter, the
     * GLSL driver, the preview and graph-level warnings — and had to merge all four by hand on every
     * compile, because any of them writing alone would have erased the other three.</p>
     */
    @Test
    public void anOwnerReplacesOnlyItsOwnFindings() {
        DiagnosticSet set = new DiagnosticSet();
        set.changeOne("emitter", List.of(error(2)));
        set.changeOne("driver", List.of(error(7)));

        assertEquals(2, set.size());

        set.changeOne("emitter", List.of(error(3), error(4)));
        assertEquals("the driver's finding was erased", 3, set.size());
        assertEquals(List.of(3, 4, 7), set.all().stream().map(d -> d.start().row()).toList());
        assertEquals(List.of(7), set.read("driver").stream().map(d -> d.start().row()).toList());
    }

    /** An owner reporting nothing is an owner with nothing to say, not an owner that never spoke. */
    @Test
    public void anOwnerThatFindsNothingClearsItself() {
        DiagnosticSet set = new DiagnosticSet();
        set.changeOne("emitter", List.of(error(2)));
        set.changeOne("driver", List.of(error(7)));

        set.changeOne("driver", List.of());
        assertEquals(1, set.size());
        assertTrue(set.read("driver").isEmpty());
        assertFalse("and it stops being listed", set.owners().contains("driver"));

        set.remove("emitter");
        assertTrue(set.isEmpty());
    }

    /**
     * <b>{@code changeAll} announces once, and drops an owner it does not mention.</b>
     *
     * <p>The shader graph writes four owners on every compile. One announcement per owner would rebuild a
     * bound Problems panel four times for one compile — and an owner left behind because this run had
     * nothing to say about it would keep showing last compile's errors beside this compile's.</p>
     */
    @Test
    public void changeAllReplacesEveryOwnerAndAnnouncesOnce() {
        DiagnosticSet set = new DiagnosticSet();
        set.changeOne("stale", List.of(error(1)));

        AtomicInteger changes = new AtomicInteger();
        set.onChanged.connect(changes::incrementAndGet);

        set.changeAll(Map.of("emitter", List.of(error(2)), "driver", List.of(error(3))));

        assertEquals("one compile, one repaint", 1, changes.get());
        assertEquals(2, set.size());
        assertFalse("an owner it did not mention survived", set.owners().contains("stale"));
    }

    /** The default owner is what the single-producer spelling writes to — one owner, not a special case. */
    @Test
    public void setAllIsTheDefaultOwner() {
        DiagnosticSet set = new DiagnosticSet();
        set.setAll(List.of(error(4)));

        assertEquals(List.of(DiagnosticSet.DEFAULT_OWNER), List.copyOf(set.owners()));
        assertEquals(1, set.read(DiagnosticSet.DEFAULT_OWNER).size());

        set.changeOne("driver", List.of(error(9)));
        assertEquals("a second owner does not disturb the first", 2, set.size());
    }

    // ── Queries ─────────────────────────────────────────────────────────────────────────────────

    @Test
    public void aMultiRowDiagnosticTouchesEveryRowItSpans() {
        Diagnostic spanning = new Diagnostic(new TextPoint(2, 4), new TextPoint(5, 1),
                DiagnosticSeverity.ERROR, "block", null, null);

        assertFalse(spanning.touchesRow(1));
        assertTrue(spanning.touchesRow(2));
        assertTrue(spanning.touchesRow(4));
        assertTrue(spanning.touchesRow(5));
        assertFalse(spanning.touchesRow(6));
    }

    @Test
    public void worstOnRowPicksTheMostSevereOfSeveral() {
        DiagnosticSet set = new DiagnosticSet();
        set.setAll(List.of(at(7, 0, DiagnosticSeverity.HINT, "h"),
                at(7, 5, DiagnosticSeverity.ERROR, "e"),
                at(7, 9, DiagnosticSeverity.WARNING, "w")));

        assertEquals(DiagnosticSeverity.ERROR, set.worstOnRow(7));
        assertNull("a clean row reports nothing rather than a default severity", set.worstOnRow(8));
    }

    @Test
    public void countsAreBySeverity() {
        DiagnosticSet set = new DiagnosticSet();
        set.setAll(List.of(error(1), error(2), at(3, 0, DiagnosticSeverity.WARNING, "w")));

        assertEquals(2, set.count(DiagnosticSeverity.ERROR));
        assertEquals(1, set.count(DiagnosticSeverity.WARNING));
        assertEquals(0, set.count(DiagnosticSeverity.HINT));
        assertEquals(DiagnosticSeverity.ERROR, set.worst());
    }

    // ── Navigation ──────────────────────────────────────────────────────────────────────────────

    /** Navigation is a cycle: the last problem is not a dead end, so repeatedly pressing "next" walks
     * every one and comes back. IntelliJ's F2 and VS Code's F8. */
    @Test
    public void nextAndPreviousWrapAround() {
        DiagnosticSet set = new DiagnosticSet();
        Diagnostic first = error(1);
        Diagnostic last = error(9);
        set.setAll(List.of(first, error(5), last));

        assertSame(first, set.nextFrom(TextPoint.ZERO));
        assertSame("past the last one, wrap to the first", first, set.nextFrom(new TextPoint(20, 0)));
        assertSame(last, set.previousFrom(new TextPoint(20, 0)));
        assertSame("before the first one, wrap to the last", last, set.previousFrom(TextPoint.ZERO));
    }

    /** Strictly after, so pressing "next" while sitting on a problem moves off it rather than
     * re-reporting the one already under the caret. */
    @Test
    public void nextFromAPositionOnAProblemMovesPastIt() {
        DiagnosticSet set = new DiagnosticSet();
        set.setAll(List.of(error(3), error(8)));

        assertEquals(8, set.nextFrom(new TextPoint(3, 0)).start().row());
    }

    @Test
    public void navigatingAnEmptySetReportsNothingRatherThanThrowing() {
        DiagnosticSet set = new DiagnosticSet();
        assertNull(set.nextFrom(TextPoint.ZERO));
        assertNull(set.previousFrom(TextPoint.ZERO));
        assertNull(set.worst());
    }

    /** A whole-row diagnostic ends past any real column, so it clamps to the line length at render time —
     * the length belongs to the buffer, and the model deliberately does not know it. */
    @Test
    public void aWholeRowDiagnosticSpansThatRowOnly() {
        Diagnostic row = Diagnostic.onRow(6, DiagnosticSeverity.ERROR, "line 6 is bad");

        assertTrue(row.isSingleRow());
        assertTrue(row.touchesRow(6));
        assertEquals(0, row.start().column());
        assertEquals(Integer.MAX_VALUE, row.end().column());
    }

    /**
     * <b>A diagnostic's message is one line, whatever the producer sent.</b>
     *
     * <p>A GLSL driver's info log is newline-<em>terminated</em>, so the message reached the Problems panel
     * as {@code …undefined variable "cg_Normal"
} and shaped as <b>two lines</b> in a sixteen-pixel row.
     * {@code white-space: nowrap} does not prevent that — it stops text wrapping and says nothing about an
     * explicit break — so the box came out twice as tall as its row, was centred, and overhung it by half a
     * line each way. A {@code UIText} draws from its box top, so the words landed a few pixels above the
     * icon beside them and it read as the <em>row</em> being misaligned.</p>
     *
     * <p>Measured, not guessed: the row's parts all shared its centre line exactly, and the message box was
     * 26px tall against a 16px row. Every consumer draws a diagnostic on one line, so the message is
     * flattened once here rather than defended against in each of them.</p>
     */
    @Test
    public void aMessageIsFlattenedToOneLine() {
        Diagnostic fromDriver = new Diagnostic(TextPoint.ZERO, TextPoint.ZERO, DiagnosticSeverity.ERROR,
                "Vertex shader compile failed: 0(437) : error C1503: undefined variable \"cg_Normal\"\n",
                "glsl", null);

        assertFalse("a newline survived into the message", fromDriver.message().contains("\n"));
        assertTrue(fromDriver.message().endsWith("\"cg_Normal\""));

        assertEquals("interior breaks collapse to a single space, keeping the words apart",
                "first second third",
                new Diagnostic(TextPoint.ZERO, TextPoint.ZERO, DiagnosticSeverity.WARNING,
                        "first\n  second\tthird", null, null).message());
    }
}
