package com.crystalgui.language.run;

import com.crystalgui.fs.Resource;
import com.crystalgui.text.TextBuffer;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * M9.5 §9.5.2 — the transcript as a document.
 *
 * <p>Headless, and it can be: the console writes into a {@link TextBuffer}, which needs no window, no
 * font and no GL. That is the same property that made the list version's model testable and it survives
 * the change of shape — what is being pinned here is the thread rule, the bound, and the level map,
 * none of which are about drawing.</p>
 */
public class RunConsoleTest {

    private static RunMessage out(String script, String text) {
        return new RunMessage(script, null, null, 0, RunLevel.OUT, text);
    }

    private static RunConsole attached(TextBuffer buffer) {
        return new RunConsole().attach(buffer);
    }

    /**
     * <b>Appending writes nothing; draining does.</b>
     *
     * <p>Not an optimisation here but a correctness rule: output arrives on a script's own thread or the
     * game's, and a {@code TextBuffer} may only be mutated on the thread that draws it. A console that
     * wrote on append would be mutating a document from a script thread, which is the kind of fault that
     * shows up as a corrupted rope rather than as an exception.</p>
     */
    @Test
    public void appendingIsQueuedAndDrainingWrites() {
        TextBuffer buffer = new TextBuffer();
        RunConsole console = attached(buffer);

        for (int i = 0; i < 100; i++) console.append(out("Main.java", "line " + i));
        assertEquals("nothing should have been written yet", 0, buffer.length());

        assertTrue(console.drain());
        assertTrue(buffer.toString().startsWith("line 0\n"));
        assertEquals(100, console.lineCount());
    }

    /** Draining with nothing pending is not a change, so a view can skip the work one would cause. */
    @Test
    public void anEmptyDrainReportsNoChange() {
        RunConsole console = attached(new TextBuffer());
        assertFalse(console.drain());
        console.append(out("Main.java", "something"));
        assertTrue(console.drain());
        assertFalse(console.drain());
    }

    /**
     * <b>Every distinct line is its own line.</b>
     *
     * <p>The list version folded consecutive output by call site, which deleted text: a script printing
     * through a helper gave every line the same origin, so thirteen distinct results became one row
     * reading {@code ×13}. Folding is gone with the list — IntelliJ does not collapse, and a text area
     * has nowhere to put a badge — and this pins that it stays gone.</p>
     */
    @Test
    public void distinctLinesFromOneHelperAreAllKept() {
        TextBuffer buffer = new TextBuffer();
        RunConsole console = attached(buffer);
        for (int i = 1; i <= 13; i++) {
            console.append(new RunMessage("RunTest.java", "RunTest.java:50", null, 0,
                    RunLevel.OUT, "result " + i));
        }
        console.drain();

        assertEquals(13, console.lineCount());
        assertTrue(buffer.toString().contains("result 1\n"));
        assertTrue(buffer.toString().contains("result 13\n"));
    }

    /** Even an identical line repeated is repeated — a console shows what happened, in order. */
    @Test
    public void anIdenticalLineIsNotFolded() {
        RunConsole console = attached(new TextBuffer());
        for (int i = 0; i < 5; i++) console.append(out("Main.java", "same"));
        console.drain();
        assertEquals(5, console.lineCount());
    }

    /** A level is remembered per line — the map the tokenizer colours from. */
    @Test
    public void everyLineRemembersItsLevel() {
        RunConsole console = attached(new TextBuffer());
        console.append(new RunMessage("Main.java", null, null, 0, RunLevel.OUT, "fine"));
        console.append(new RunMessage("Main.java", null, null, 0, RunLevel.ERROR, "broke"));
        console.startRun("Main.java");
        console.drain();

        assertEquals(RunLevel.OUT, console.lineAt(0).level());
        assertEquals(RunLevel.ERROR, console.lineAt(1).level());
        assertTrue("a run boundary is not output", console.lineAt(2).isDivider());
        assertNull("past the end is null, not an exception", console.lineAt(99));
    }

    /**
     * <b>"Survives the script stopping" is a promise about lifetime, not about volume.</b>
     *
     * <p>Without the ring a script printing without pause grows the document until the game dies. With
     * it the oldest lines go — and the count of what went is kept, because a transcript that quietly
     * begins in the middle reads as the console having missed something.</p>
     */
    @Test
    public void theRingDropsTheOldestAndSaysHowMany() {
        TextBuffer buffer = new TextBuffer();
        RunConsole console = attached(buffer).setBudgetKb(1);
        for (int i = 0; i < 500; i++) {
            console.append(out("Main.java", "a line of some length " + i));
            console.drain();
        }

        assertTrue("the ring should have evicted something", console.dropped() > 0);
        assertTrue("and the document should be bounded", buffer.length() <= 2 * 1024);
        assertTrue("the newest line survives", buffer.toString().endsWith("a line of some length 499\n"));
    }

    /** The level map stays aligned to the document after a trim, or every colour lands on the wrong line. */
    @Test
    public void theLevelMapStaysAlignedAfterTrimming() {
        TextBuffer buffer = new TextBuffer();
        RunConsole console = attached(buffer).setBudgetKb(1);
        for (int i = 0; i < 500; i++) {
            console.append(new RunMessage("Main.java", null, null, 0,
                    i % 2 == 0 ? RunLevel.OUT : RunLevel.ERROR, "line " + i));
            console.drain();
        }

        assertEquals("one entry per row, exactly", buffer.lineCount() - 1, console.lineCount());
        for (int row = 0; row < console.lineCount(); row++) {
            String text = buffer.line(row);
            RunConsole.Line line = console.lineAt(row);
            assertNotNull("no level for row " + row, line);
            assertEquals("row " + row + " is out of step with the document", text, line.text());
        }
    }

    /** Clearing is queued, so it cannot land between two lines of a burst that preceded it. */
    @Test
    public void clearingEmptiesEverything() {
        TextBuffer buffer = new TextBuffer();
        RunConsole console = attached(buffer).setBudgetKb(1);
        for (int i = 0; i < 300; i++) console.append(out("Main.java", "a line of some length " + i));
        console.drain();
        assertTrue(console.dropped() > 0);

        console.clear();
        console.drain();
        assertEquals(0, buffer.length());
        assertEquals(0, console.lineCount());
        assertEquals("the eviction notice goes with a fresh start", 0, console.dropped());
    }

    /**
     * <b>Written from many threads, drained from one.</b>
     *
     * <p>The shape production is actually in: a one-shot on its own thread and a handler on the game's,
     * both printing, while the frame loop drains.</p>
     */
    @Test
    public void appendingFromManyThreadsLosesNothing() throws Exception {
        RunConsole console = attached(new TextBuffer());
        int threads = 4;
        int each = 250;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int id = t;
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < each; i++) console.append(out("s" + id, "line " + i));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }

        start.countDown();
        assertTrue("threads did not finish", done.await(20, TimeUnit.SECONDS));
        console.drain();
        assertEquals(threads * each, console.lineCount());
    }

    /** Every enqueue is announced, because the view has to know to drain. */
    @Test
    public void everyChangeIsSignalled() {
        RunConsole console = attached(new TextBuffer());
        AtomicInteger changes = new AtomicInteger();
        console.onDidChange.connect(changes::incrementAndGet);

        console.append(out("Main.java", "one"));
        console.startRun("Main.java");
        console.clear();

        assertEquals(3, changes.get());
    }

    /** A console with nothing attached swallows output rather than throwing — the headless-host case. */
    @Test
    public void anUnattachedConsoleIsInert() {
        RunConsole console = new RunConsole();
        console.append(out("Main.java", "nowhere to go"));
        assertFalse(console.drain());
        assertEquals(0, console.lineCount());
    }

    /** A navigable line keeps what a click needs; an unattributed one says it has none. */
    @Test
    public void navigabilityIsCarriedPerLine() {
        RunConsole console = attached(new TextBuffer());
        Resource file = Resource.of(Resource.SCHEME_PROJECT, "src/Main.java");
        console.append(RunMessage.at("Main.java", file, 42, RunLevel.ERROR, "boom"));
        console.append(RunMessage.of("Main.java", RunLevel.OUT, "plain"));
        console.drain();

        assertTrue(console.lineAt(0).isNavigable());
        assertEquals(42, console.lineAt(0).line());
        assertFalse(console.lineAt(1).isNavigable());
    }
}
