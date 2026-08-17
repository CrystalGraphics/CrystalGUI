package com.crystalgui.language.run;

import com.crystalgui.fs.Resource;
import com.crystalgui.language.run.console.*;
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

    /**
     * <b>And the bound holds while nothing is draining</b> — which is the state a closed panel is in.
     *
     * <p>The ring above only trims inside {@code drain}, and {@code drain} is called once a frame <em>by
     * the panel</em>. A closed panel is a detached one, so its ticker unregisters and nothing drains at
     * all — and every line a chatty script printed piled up in the queue instead, for as long as the
     * console stayed shut. "The transcript is bounded" was true of everything the reader could see and
     * false of everything else, which is why no test and no eviction notice ever showed it.</p>
     *
     * <p>Note there is no {@code drain} in the loop. That absence <em>is</em> the test.</p>
     */
    @Test
    public void theQueueIsBoundedEvenWhenNothingDrainsIt() {
        TextBuffer buffer = new TextBuffer();
        RunConsole console = attached(buffer).setBudgetKb(1);

        for (int i = 0; i < 5000; i++) {
            console.append(out("Main.java", "a line of some length " + i));
        }

        console.drain();
        assertTrue("nothing was dropped, so the queue grew to hold all 5000",
                console.dropped() > 0);
        assertTrue("the transcript outgrew its budget while the panel was shut",
                buffer.length() <= 4 * 1024);
        assertTrue("the newest line must always survive",
                buffer.toString().endsWith("a line of some length 4999\n"));
    }

    /**
     * <b>A drop that happened in the queue is still reported.</b>
     *
     * <p>The count is the whole reason eviction is acceptable: a transcript that quietly begins in the
     * middle reads as the console having missed something. Lines dropped before they were ever shown are
     * no different — arguably they matter more, because the reader was not there to see them go.</p>
     */
    @Test
    public void linesDroppedFromTheQueueAreCounted() {
        RunConsole console = attached(new TextBuffer()).setBudgetKb(1);
        for (int i = 0; i < 2000; i++) console.append(out("Main.java", "line " + i));

        assertEquals("nothing has been drained, so nothing is in the document yet", 0,
                console.lineCount());
        console.drain();
        assertTrue("the queue dropped lines and said nothing about it", console.dropped() > 0);
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

    /**
     * <b>A closing boundary belongs to the script it describes, not to its own text.</b>
     *
     * <p>{@code startRun} takes one string because the opening divider's text <em>is</em> the script's
     * name, so the same value serves as label and owner. A closing line reads {@code Main.java finished
     * in 1.2 sec} and still has to be filed under {@code Main.java} — attributing it to its own text
     * would give the summary a filter bucket of its own, so it would show under All output and be missing
     * from the one script whose run it is about.</p>
     */
    @Test
    public void aClosingBoundaryIsFiledUnderItsScript() {
        RunConsole console = attached(new TextBuffer());
        console.startRun("Main.java");
        console.append(out("Main.java", "working"));
        console.endRun("Main.java", "Main.java finished in 2 sec");
        console.drain();

        // heading, break, "working", break, footnote — the breaks are `needsBreakBetween`'s.
        assertEquals(5, console.lineCount());
        assertTrue("the closing line is a boundary, not output", console.lineAt(4).isDivider());
        assertEquals("Main.java finished in 2 sec", console.lineAt(4).text());
        assertEquals("the summary was filed under its own text", "Main.java", console.lineAt(4).script());

        console.setFilter("Main.java");
        console.drain();
        assertEquals("filtering to the script dropped its own closing line", 5, console.lineCount());
    }

    /** A run with no ending to report writes nothing rather than an empty boundary. */
    @Test
    public void anAbsentSummaryIsNotABlankLine() {
        RunConsole console = attached(new TextBuffer());
        console.endRun("Main.java", null);
        console.endRun("Main.java", "");
        console.drain();
        assertEquals(0, console.lineCount());
    }

    /**
     * <b>A second run of one script is findable without reading the text.</b>
     *
     * <p>Retaining history is the decision this console made — our transcript is one filtered surface per
     * workspace rather than IntelliJ's console per run configuration, so clearing on each run would take
     * other scripts' output with it. The cost of that decision is exactly this: two runs of one script
     * print the same thing twice, and the seam between them is the only thing saying where the last one
     * ended. A blank line puts it there, an ordinal names it, and a capture of its own colours it.</p>
     */
    @Test
    public void aSecondRunOpensWithABreakAndAnOrdinal() {
        RunConsole console = attached(new TextBuffer());
        console.startRun("Ask.java");
        console.append(out("Ask.java", "hello"));
        console.endRun("Ask.java", "Ask.java finished in 1 sec");
        console.startRun("Ask.java");
        console.drain();

        // A BOUNDARY IS SET APART ON BOTH SIDES:
        //   0 Ask.java          the heading
        //   1                   break, so it is not jammed against the output it labels
        //   2 hello             the run's output
        //   3                   break, so the footnote is not jammed against it
        //   4 Ask.java finished in 1 sec
        //   5                   break, before the next run begins
        //   6 Ask.java (run 2)
        assertEquals(7, console.lineCount());
        assertEquals("the first run should not be pushed down by a break above it",
                "Ask.java", console.lineAt(0).text());
        assertTrue("the opening line is a heading, not a footnote", console.lineAt(0).isRunStart());

        assertEquals("the heading is jammed against the output it labels", "", console.lineAt(1).text());
        assertEquals("hello", console.lineAt(2).text());
        assertEquals("the closing line is jammed against the output above it",
                "", console.lineAt(3).text());
        assertFalse("the closing line must stay a footnote", console.lineAt(4).isRunStart());
        assertTrue("and still a boundary", console.lineAt(4).isDivider());

        assertEquals("nothing separates the runs", "", console.lineAt(5).text());
        assertEquals("the second run is not named as one", "Ask.java (run 2)",
                console.lineAt(6).text());
        assertTrue(console.lineAt(6).isRunStart());
    }

    /**
     * <b>The stamp is on the document, and the model keeps the script's own text.</b>
     *
     * <p>Which is what lets the style change without the transcript being re-timed, and what keeps a
     * filter — and every {@code ConsoleFilter} — working on what the script actually said.</p>
     */
    @Test
    public void aStampedLineDecoratesTheDocumentAndNotTheModel() {
        TextBuffer buffer = new TextBuffer();
        RunConsole console = attached(buffer);
        console.setPrefixStyle(ConsolePrefix.Style.TIME);
        console.append(out("Main.java", "hello"));
        console.drain();

        assertEquals("the model should hold what the script printed", "hello", console.lineAt(0).text());
        String row = buffer.line(0);
        assertTrue("the document row is not stamped: " + row, row.endsWith("hello"));
        assertTrue("the stamp is not a clock: " + row, row.startsWith("["));
        assertEquals("[HH:mm:ss] ".length() + "hello".length(), row.length());
    }

    /** A boundary is never stamped — it is the console talking about itself, not output. */
    @Test
    public void boundariesAreNotStamped() {
        TextBuffer buffer = new TextBuffer();
        RunConsole console = attached(buffer);
        console.setPrefixStyle(ConsolePrefix.Style.FULL);
        console.startRun("Main.java");
        console.append(out("Main.java", "hello"));
        console.drain();

        assertEquals("a heading was given a timestamp", "Main.java", buffer.line(0));
        assertTrue("the output line lost its stamp", buffer.line(2).startsWith("["));
    }

    /**
     * <b>A link's columns are shifted past the stamp.</b>
     *
     * <p>A {@code ConsoleFilter} is handed the script's own text — that is what keeps it testable — but
     * the row on screen begins with the prefix, and both the underline and the click test are computed in
     * ROW columns. Unshifted, every link on a stamped line is underlined a dozen characters to the left
     * of the text it names and a click in the middle of it opens nothing. Nothing throws; the underline
     * simply sits on the wrong words.</p>
     */
    @Test
    public void aLinksColumnsFollowTheTextOnScreen() {
        TextBuffer buffer = new TextBuffer();
        RunConsole console = attached(buffer).addFilter(new JavaStackFrameFilter());
        console.setPrefixStyle(ConsolePrefix.Style.FULL);
        console.append(new RunMessage("Main.java", null, null, 0, RunLevel.ERROR,
                "\tat X.y(Main.java:12)"));
        console.drain();

        java.util.List<ConsoleFilter.Link> links = console.linksAt(0);
        assertEquals(1, links.size());
        ConsoleFilter.Link link = links.get(0);
        String row = buffer.line(0);
        assertEquals("the underline is not over the text it names",
                "Main.java:12", row.substring(link.start(), link.end()));
    }

    /**
     * <b>The stamp is measurable, because three things have to start after it.</b>
     *
     * <p>A link's columns have to move right past it, and a line's colour has to <em>begin</em> after it.
     * The second was missed: an echoed input line painted its own timestamp green and italic along with
     * the words, and an error line would have painted one red — which reads as the stamp belonging to the
     * line rather than to the console.</p>
     */
    @Test
    public void theStampIsMeasuredApartFromTheLine() {
        TextBuffer buffer = new TextBuffer();
        RunConsole console = attached(buffer);
        console.setPrefixStyle(ConsolePrefix.Style.TIME);
        console.startRun("Main.java");
        console.append(out("Main.java", "hello"));
        console.drain();

        // 0 heading, 1 break, 2 output
        assertEquals("a boundary is never stamped, so nothing of it belongs to the console",
                0, console.stampWidth(0));
        assertEquals("[HH:mm:ss] ".length(), console.stampWidth(2));
        assertEquals("the measured stamp is not the one that was written",
                "hello", buffer.line(2).substring(console.stampWidth(2)));

        console.setPrefixStyle(ConsolePrefix.Style.NONE);
        console.drain();
        assertEquals("nothing is stamped, so nothing is measured", 0, console.stampWidth(2));
    }

    /** And a link's shift is that same measurement, so the two cannot drift apart. */
    @Test
    public void aLinksShiftIsTheStampsWidth() {
        TextBuffer buffer = new TextBuffer();
        RunConsole console = attached(buffer).addFilter(new JavaStackFrameFilter());
        console.setPrefixStyle(ConsolePrefix.Style.FULL);
        console.append(new RunMessage("Main.java", null, null, 0, RunLevel.ERROR,
                "at X.y(Main.java:12)"));
        console.drain();

        ConsoleFilter.Link link = console.linksAt(0).get(0);
        assertEquals("the underline starts somewhere other than where the text does",
                "Main.java:12",
                buffer.line(0).substring(link.start(), link.end()));
        assertTrue("a link cannot begin inside the stamp",
                link.start() >= console.stampWidth(0));
    }

    /** Changing the style rewrites every row — half a stamped transcript reads as a stopped clock. */
    @Test
    public void aStyleChangeRestampsWhatIsAlreadyThere() {
        TextBuffer buffer = new TextBuffer();
        RunConsole console = attached(buffer);
        console.append(out("Main.java", "hello"));
        console.drain();
        assertEquals("hello", buffer.line(0));

        console.setPrefixStyle(ConsolePrefix.Style.TIME);
        console.drain();
        assertTrue("the line already there was left unstamped", buffer.line(0).endsWith("hello"));
        assertTrue(buffer.line(0).startsWith("["));

        console.setPrefixStyle(ConsolePrefix.Style.NONE);
        console.drain();
        assertEquals("turning it off left the stamp behind", "hello", buffer.line(0));
    }

    /** Never two breaks in a row, and never one above the first line. */
    @Test
    public void breaksAreNotDoubledOrLeading() {
        RunConsole console = attached(new TextBuffer());
        console.startRun("Quiet.java");
        // A run that printed nothing: heading, one break, footnote -- not two breaks between them.
        console.endRun("Quiet.java", "Quiet.java finished in 1 sec");
        console.drain();

        assertEquals(3, console.lineCount());
        assertEquals("Quiet.java", console.lineAt(0).text());
        assertEquals("", console.lineAt(1).text());
        assertEquals("Quiet.java finished in 1 sec", console.lineAt(2).text());
    }

    /** A blank line the script printed itself satisfies the rule — the gap is what matters, not who made it. */
    @Test
    public void aScriptsOwnBlankLineIsNotDoubled() {
        RunConsole console = attached(new TextBuffer());
        console.startRun("A.java");
        console.append(out("A.java", "working"));
        console.append(out("A.java", ""));
        console.endRun("A.java", "A.java finished in 1 sec");
        console.drain();

        // heading, break, working, "", footnote -- the script's own blank does the separating.
        assertEquals(5, console.lineCount());
        assertEquals("", console.lineAt(3).text());
        assertEquals("A.java finished in 1 sec", console.lineAt(4).text());
    }

    /**
     * <b>The ordinal counts runs, not surviving lines.</b>
     *
     * <p>Derived from the transcript it would <em>fall</em> as the ring evicted old runs, so "run 3" would
     * become "run 2" while the reader watched. A clear is the one thing that genuinely starts again.</p>
     */
    @Test
    public void theOrdinalSurvivesEvictionAndResetsOnClear() {
        RunConsole console = attached(new TextBuffer()).setBudgetKb(1);
        for (int i = 0; i < 3; i++) {
            console.startRun("Loud.java");
            for (int line = 0; line < 40; line++) {
                console.append(out("Loud.java", "a line of some length " + line));
            }
            console.drain();
        }
        assertTrue("the ring should have evicted the earliest runs", console.dropped() > 0);

        console.startRun("Loud.java");
        console.drain();
        String header = console.lineAt(console.lineCount() - 1).text();
        assertEquals("the ordinal fell as old runs aged out", "Loud.java (run 4)", header);

        console.clear();
        console.startRun("Loud.java");
        console.drain();
        assertEquals("a clear is a fresh start and the count should say so",
                "Loud.java", console.lineAt(0).text());
    }

    /**
     * <b>Forgetting one script takes its lines and leaves everyone else's.</b>
     *
     * <p>A clear is the blunt answer: the complaint is a console filling up with runs you have finished
     * reading, and taking everything is not what was asked. The rail's Remove is the selective one.</p>
     */
    @Test
    public void forgettingOneScriptLeavesTheOthers() {
        RunConsole console = attached(new TextBuffer());
        console.append(out("A.java", "from A"));
        console.append(out("B.java", "from B"));
        console.append(out("A.java", "from A again"));
        console.drain();
        assertEquals(3, console.lineCount());

        console.forget("A.java");
        console.drain();

        assertEquals("only B's line should be left", 1, console.lineCount());
        assertEquals("from B", console.lineAt(0).text());
        assertEquals("and A should be gone from the picker too",
                java.util.List.of("B.java"), console.scripts());
    }

    /**
     * <b>The ring's budget is re-measured, not adjusted.</b>
     *
     * <p>The running total is what the ring trims against. Left describing lines that are gone, it would
     * evict from a transcript that had just shrunk — the console would keep dropping its oldest surviving
     * output for as long as the phantom characters were counted, and report drops nobody caused.</p>
     */
    @Test
    public void forgettingGivesTheBudgetBack() {
        TextBuffer buffer = new TextBuffer();
        RunConsole console = attached(buffer).setBudgetKb(1);
        for (int i = 0; i < 60; i++) console.append(out("noisy.java", "a line of some length " + i));
        console.append(out("quiet.java", "the only line that matters"));
        console.drain();

        console.forget("noisy.java");
        console.drain();
        long droppedAfterForget = console.dropped();

        // Nothing new is written, so nothing may be evicted: a stale total would trim the survivor.
        for (int i = 0; i < 5; i++) console.drain();
        assertEquals("the ring kept trimming against characters that were gone",
                droppedAfterForget, console.dropped());
        assertEquals(1, console.lineCount());
        assertEquals("the only line that matters", console.lineAt(0).text());
    }

    /**
     * <b>A filter naming the forgotten script is dropped with it.</b>
     *
     * <p>Otherwise the console is narrowed to something that no longer exists: an empty document with no
     * row selected to explain it, which reads as the transcript having been cleared.</p>
     */
    @Test
    public void forgettingTheFilteredScriptRestoresAllOutput() {
        RunConsole console = attached(new TextBuffer());
        console.append(out("A.java", "from A"));
        console.append(out("B.java", "from B"));
        console.setFilter("A.java");
        console.drain();
        assertEquals(1, console.lineCount());

        console.forget("A.java");
        console.drain();

        assertNull("the filter outlived its subject", console.filter());
        assertEquals("everything else should be showing again", 1, console.lineCount());
        assertEquals("from B", console.lineAt(0).text());
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
