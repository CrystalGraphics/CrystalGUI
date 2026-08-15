package com.crystalgui.language.run;

import com.crystalgui.fs.Resource;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * M9.5 §9.5.4 — the console's two rules, both of which fail silently when wrong.
 *
 * <p>Headless on purpose: nothing here needs a window, a font or a GL context, and the rules being
 * pinned are about a data structure rather than about how it draws. A panel test could not tell a
 * collapse that never fired from one that fired and produced the same picture.</p>
 */
public class RunConsoleTest {

    private static RunMessage out(String script, String origin, String text) {
        return new RunMessage(script, origin, null, 0, RunLevel.OUT, text);
    }

    /**
     * <b>The same message repeating folds into one row with a count.</b>
     *
     * <p>Unity's rule and the reason Collapse exists: a null reference thrown on every frame update is
     * one fact, however many times it is reported.</p>
     */
    @Test
    public void aRepeatedMessageFoldsIntoOneCountedRow() {
        RunConsole console = new RunConsole();
        for (int tick = 1; tick <= 300; tick++) {
            console.append(out("foo.js", "foo.js:12", "cannot read property of null"));
        }

        List<RunConsole.Entry> entries = console.entries();
        assertEquals(1, entries.size());
        assertEquals(300, entries.get(0).count());
    }

    /**
     * <b>Differing text NEVER folds, even from one call site — and this is the bug that shipped.</b>
     *
     * <p>The key was the origin alone, on the argument that a counter printing {@code tick 1},
     * {@code tick 2} would otherwise never fold. The premise was right and the conclusion was not: those
     * are not one message repeating, they are three messages, and a row showing only the newest does not
     * compress the transcript, it deletes two thirds of it.</p>
     *
     * <p>It surfaced on the first real script. {@code RunTest.java} prints everything through a helper,
     * so every line in the file shared that helper's origin, and thirteen distinct results collapsed into
     * one row reading {@code ×13} — twelve of them simply gone. <b>A console that loses output is worse
     * than a console that scrolls</b>, which is what the ring is for.</p>
     */
    @Test
    public void differentMessagesFromOneHelperNeverFold() {
        RunConsole console = new RunConsole();
        // ONE ORIGIN, as a shared print helper produces -- the exact shape that lost output.
        for (int i = 1; i <= 13; i++) {
            console.append(out("RunTest.java", "RunTest.java:50", "result " + i));
        }

        List<RunConsole.Entry> entries = console.entries();
        assertEquals("thirteen distinct lines are thirteen rows", 13, entries.size());
        assertEquals("result 1", entries.get(0).text());
        assertEquals("result 13", entries.get(12).text());
    }

    /**
     * Different call sites are different rows, however alike the text.
     *
     * <p>One more separation than Unity offers, and worth keeping: the same string printed from two
     * places is two facts, and merging them would hide that one of the two ever ran.</p>
     */
    @Test
    public void twoCallSitesDoNotFold() {
        RunConsole console = new RunConsole();
        console.append(out("foo.js", "foo.js:12", "same"));
        console.append(out("foo.js", "foo.js:40", "same"));
        assertEquals(2, console.entries().size());
    }

    /**
     * <b>An unattributed line never folds.</b>
     *
     * <p>Two lines with no origin share "nowhere", not a call site. Folding on that would merge messages
     * that have nothing to do with each other — and would do it most eagerly exactly where the producer
     * knew least, which is the worst possible place to be confident.</p>
     */
    @Test
    public void messagesWithNoOriginNeverFold() {
        RunConsole console = new RunConsole();
        console.append(out("foo.js", null, "repeated"));
        console.append(out("foo.js", null, "repeated"));
        assertEquals(2, console.entries().size());
    }

    /**
     * <b>Only CONSECUTIVE messages fold</b>, or the transcript silently reorders.
     *
     * <p>Two scripts interleaving is the ordinary case, not a corner: a tick handler and a one-shot run
     * at the same time. If a line could reach back past another script's output to join its own, the
     * console would be showing an order that never happened.</p>
     */
    @Test
    public void foldingDoesNotReachBackPastAnotherScript() {
        RunConsole console = new RunConsole();
        // IDENTICAL TEXT AND ORIGIN on the outer pair, so they WOULD fold if folding could reach back.
        console.append(out("a.js", "a.js:1", "same line"));
        console.append(out("b.js", "b.js:1", "interleaved"));
        console.append(out("a.js", "a.js:1", "same line"));

        List<RunConsole.Entry> entries = console.entries();
        assertEquals(3, entries.size());
        assertEquals(1, entries.get(2).count());
    }

    /** Unity's Collapse is a toggle, and off means off. */
    @Test
    public void collapsingCanBeTurnedOff() {
        RunConsole console = new RunConsole().setCollapsing(false);
        for (int i = 0; i < 5; i++) console.append(out("foo.js", "foo.js:12", "line"));
        assertEquals(5, console.entries().size());
    }

    /**
     * <b>"Survives the script stopping" is a promise about lifetime, not about volume.</b>
     *
     * <p>Without the ring a script printing without pause grows this until the game dies. With it, the
     * oldest rows go — and the count of what went is kept, because a transcript that quietly begins in
     * the middle reads as the console having missed something rather than as the bound being reached.</p>
     */
    @Test
    public void theRingDropsTheOldestAndSaysHowMany() {
        RunConsole console = new RunConsole().setBudgetKb(1);
        for (int i = 0; i < 400; i++) {
            console.append(out("foo.js", "foo.js:" + i, "a line of some length " + i));
        }

        assertTrue("the ring should have evicted something", console.dropped() > 0);
        assertTrue("and it should still be bounded", console.size() < 400);
        List<RunConsole.Entry> entries = console.entries();
        assertEquals("the newest line is the one kept",
                "a line of some length 399", entries.get(entries.size() - 1).text());
    }

    /**
     * A budget smaller than one message must not empty the console on every append.
     *
     * <p>It would report a drop each time and show nothing, which reads as the console being broken
     * rather than as the setting being absurd.</p>
     */
    @Test
    public void anAbsurdBudgetStillKeepsTheNewestRow() {
        RunConsole console = new RunConsole().setBudgetKb(1);
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 5000; i++) huge.append('x');
        console.append(out("foo.js", "foo.js:1", huge.toString()));
        console.append(out("foo.js", "foo.js:2", huge.toString()));

        assertEquals(1, console.size());
        assertEquals("foo.js:2", console.entries().get(0).origin());
    }

    /** Clearing is a fresh start, so the eviction notice goes with it. */
    @Test
    public void clearingAlsoClearsTheDropCount() {
        RunConsole console = new RunConsole().setBudgetKb(1);
        for (int i = 0; i < 400; i++) console.append(out("foo.js", "foo.js:" + i, "text " + i));
        assertTrue(console.dropped() > 0);

        console.clear();
        assertEquals(0, console.size());
        assertEquals(0, console.dropped());
    }

    /**
     * <b>Written from a script's thread, read from the UI's.</b>
     *
     * <p>Unlike {@code Markers}, which lives on one thread, output arrives on whatever thread the script
     * runs on — its own for a one-shot, the game's for a tick handler. A snapshot rather than a live view
     * is what stops a panel iterating rows while one appears underneath it.</p>
     */
    @Test
    public void appendingFromManyThreadsLosesNothing() throws Exception {
        RunConsole console = new RunConsole();
        int threads = 4;
        int each = 250;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int id = t;
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < each; i++) {
                        // A DISTINCT ORIGIN PER LINE, so nothing folds and the count is exact -- this
                        // test is about not losing writes, not about collapsing.
                        console.append(out("s" + id, "s" + id + ":" + i, "line"));
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }

        start.countDown();
        assertTrue("threads did not finish", done.await(20, TimeUnit.SECONDS));
        assertEquals(threads * each, console.size());
    }

    /** A reader iterating the snapshot cannot be surprised by a row arriving. */
    @Test
    public void entriesIsASnapshotAndNotAView() {
        RunConsole console = new RunConsole();
        console.append(out("foo.js", "foo.js:1", "first"));
        List<RunConsole.Entry> snapshot = console.entries();
        console.append(out("foo.js", "foo.js:2", "second"));
        assertEquals(1, snapshot.size());
        assertEquals(2, console.entries().size());
    }

    /** Every append is announced, because a panel has to know to redraw. */
    @Test
    public void everyChangeIsSignalled() {
        RunConsole console = new RunConsole();
        AtomicInteger changes = new AtomicInteger();
        console.onDidChange.connect(c -> changes.incrementAndGet());

        console.append(out("foo.js", "foo.js:1", "one"));
        console.append(out("foo.js", "foo.js:1", "two"));   // folds, and is still a change
        console.clear();

        assertEquals(3, changes.get());
    }

    /** A navigable line knows where to send a double-click; an unattributed one says so. */
    @Test
    public void navigabilityIsCarriedPerLine() {
        Resource file = Resource.of(Resource.SCHEME_PROJECT, "src/Main.java");
        RunMessage located = RunMessage.at("Main.java", file, 42, RunLevel.ERROR, "boom");
        assertTrue(located.isNavigable());
        assertEquals("Main.java:42", located.origin());
        assertFalse(RunMessage.of("Main.java", RunLevel.OUT, "plain").isNavigable());
    }
}
