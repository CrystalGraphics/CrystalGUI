package com.crystalgui.language.run;

import com.crystalgui.text.TextBuffer;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * M9.5 §9.5.3 — showing one script's output.
 *
 * <p>The half of the rail that is a model question. What is pinned here is the consequence of §9.5.2:
 * once the transcript is a <b>document</b> rather than a list of rows, filtering is no longer a row-source
 * swap — the document has to be re-derived, and the ring, the filter and the line map all have to come
 * out of one pass or they disagree about what the reader is looking at.</p>
 */
public class ConsoleFilteringTest {

    private static RunMessage out(String script, String text) {
        return new RunMessage(script, null, null, 0, RunLevel.OUT, text);
    }

    private static RunConsole console() {
        return new RunConsole().attach(new TextBuffer());
    }

    private static RunConsole withTwoScripts(TextBuffer buffer) {
        RunConsole console = new RunConsole().attach(buffer);
        console.append(out("A.java", "a one"));
        console.append(out("B.java", "b one"));
        console.append(out("A.java", "a two"));
        console.append(out("B.java", "b two"));
        console.drain();
        return console;
    }

    /** Unfiltered, the document is the transcript. */
    @Test
    public void everythingIsShownByDefault() {
        TextBuffer buffer = new TextBuffer();
        RunConsole console = withTwoScripts(buffer);

        assertNull(console.filter());
        assertEquals(4, console.lineCount());
        assertEquals(4, console.transcriptSize());
    }

    /** Filtering re-derives the document from the transcript, keeping only one script's rows. */
    @Test
    public void filteringShowsOneScript() {
        TextBuffer buffer = new TextBuffer();
        RunConsole console = withTwoScripts(buffer);

        console.setFilter("A.java");
        assertTrue(console.drain());

        assertEquals("A.java", console.filter());
        assertEquals(2, console.lineCount());
        assertEquals("the transcript is untouched", 4, console.transcriptSize());
        assertEquals("a one\na two\n", buffer.toString());
    }

    /** And clearing the filter brings the rest back — the transcript was never thrown away. */
    @Test
    public void clearingTheFilterRestoresEverything() {
        TextBuffer buffer = new TextBuffer();
        RunConsole console = withTwoScripts(buffer);
        console.setFilter("A.java");
        console.drain();

        console.setFilter(null);
        assertTrue(console.drain());

        assertEquals(4, console.lineCount());
        assertEquals("a one\nb one\na two\nb two\n", buffer.toString());
    }

    /**
     * <b>The setter writes nothing; the drain does.</b>
     *
     * <p>The same rule {@link RunConsole#clear()} follows, and for the same reason: a rail row or a menu
     * item may be handled anywhere, and a document may only be mutated on the thread that draws it.</p>
     */
    @Test
    public void settingTheFilterIsQueuedAndTheDrainApplesIt() {
        TextBuffer buffer = new TextBuffer();
        RunConsole console = withTwoScripts(buffer);

        console.setFilter("A.java");
        assertNull("nothing may be applied before the drain", console.filter());
        assertEquals("and the document is untouched", 4, buffer.lineCount() - 1);

        console.drain();
        assertEquals("A.java", console.filter());
    }

    /** Output arriving while a filter is on is filtered as it lands, not on the next rebuild. */
    @Test
    public void newOutputRespectsTheActiveFilter() {
        TextBuffer buffer = new TextBuffer();
        RunConsole console = withTwoScripts(buffer);
        console.setFilter("A.java");
        console.drain();

        console.append(out("B.java", "b three"));
        console.append(out("A.java", "a three"));
        console.drain();

        assertEquals(3, console.lineCount());
        assertEquals(6, console.transcriptSize());
        assertTrue(buffer.toString().endsWith("a three\n"));
        assertFalse(buffer.toString().contains("b three"));
    }

    /**
     * <b>A burst filtered down to nothing is not a change.</b>
     *
     * <p>Reporting one would re-measure every realised row for output the reader cannot see — twenty times
     * a second under a tick script belonging to some other file.</p>
     */
    @Test
    public void aBurstThatIsEntirelyFilteredOutReportsNoChange() {
        RunConsole console = console();
        console.append(out("A.java", "a one"));
        console.drain();
        console.setFilter("A.java");
        console.drain();

        for (int i = 0; i < 20; i++) console.append(out("B.java", "b " + i));
        assertFalse("nothing visible changed", console.drain());
    }

    /**
     * <b>The ring bounds the TRANSCRIPT, not the document.</b>
     *
     * <p>Under a filter the document is a subset, so a document-sized bound would let the retained
     * transcript grow without limit — the memory the bound exists to cap, uncapped in exactly the state
     * somebody turned a filter on to survive.</p>
     */
    @Test
    public void theRingBoundsTheTranscriptEvenWhenTheDocumentIsSmall() {
        TextBuffer buffer = new TextBuffer();
        RunConsole console = new RunConsole().attach(buffer).setBudgetKb(1);
        console.append(out("A.java", "the only visible line"));
        console.drain();
        console.setFilter("A.java");
        console.drain();

        for (int i = 0; i < 400; i++) {
            console.append(out("B.java", "hidden line of some length " + i));
            console.drain();
        }

        assertTrue("the transcript must be bounded", console.transcriptSize() < 400);
        assertTrue("and eviction reported", console.dropped() > 0);
        assertTrue("while the document stayed small", buffer.length() < 512);
    }

    /**
     * <b>Eviction keeps the document and the line map in step.</b>
     *
     * <p>Rows are dropped from the front of the transcript, and only the ones that were <em>shown</em> come
     * off the document. If those two ever disagree, {@code lineAt(row)} starts describing a different row
     * than the one painted — and both the tokenizer's colours and the links read it.</p>
     */
    @Test
    public void theLineMapStaysAlignedThroughEvictionUnderAFilter() {
        TextBuffer buffer = new TextBuffer();
        RunConsole console = new RunConsole().attach(buffer).setBudgetKb(1);
        console.setFilter("A.java");
        console.drain();

        for (int i = 0; i < 300; i++) {
            console.append(out(i % 3 == 0 ? "A.java" : "B.java", "line number " + i));
            console.drain();
        }

        assertTrue(console.dropped() > 0);
        assertEquals("one entry per row, exactly", buffer.lineCount() - 1, console.lineCount());
        for (int row = 0; row < console.lineCount(); row++) {
            RunConsole.Line line = console.lineAt(row);
            assertNotNull("no line for row " + row, line);
            assertEquals("row " + row + " is out of step", buffer.line(row), line.text());
            assertEquals("and belongs to the filtered script", "A.java", line.script());
        }
    }

    /** A run boundary belongs to its script, so filtering hides another script's boundaries too. */
    @Test
    public void aRunBoundaryIsFilteredWithItsScript() {
        TextBuffer buffer = new TextBuffer();
        RunConsole console = console();
        console.startRun("A.java");
        console.append(out("A.java", "a one"));
        console.startRun("B.java");
        console.append(out("B.java", "b one"));
        console.drain();

        console.setFilter("A.java");
        console.drain();

        // A.java's heading, the break under it, and its one line. B.java's boundary and its break belong
        // to B and go with it.
        assertEquals(3, console.lineCount());
        assertTrue(console.lineAt(0).isDivider());
        for (int row = 0; row < console.lineCount(); row++) {
            assertEquals("another script's boundary survived the filter",
                    "A.java", console.lineAt(row).script());
        }
        assertEquals("a one", console.lineAt(2).text());
    }

    /** The picker's row set: distinct, in first-seen order, and derived rather than kept. */
    @Test
    public void scriptsAreListedInFirstSeenOrder() {
        RunConsole console = console();
        console.append(out("B.java", "one"));
        console.append(out("A.java", "two"));
        console.append(out("B.java", "three"));
        console.drain();

        assertEquals(List.of("B.java", "A.java"), console.scripts());
    }

    /** Clearing drops the transcript, the document and the filter's subject alike. */
    @Test
    public void clearingEmptiesTheTranscriptNotJustTheView() {
        TextBuffer buffer = new TextBuffer();
        RunConsole console = withTwoScripts(buffer);
        console.setFilter("A.java");
        console.drain();

        console.clear();
        console.drain();

        assertEquals(0, console.lineCount());
        assertEquals(0, console.transcriptSize());
        assertEquals(0, buffer.length());
        assertEquals("the filter survives a clear", "A.java", console.filter());
    }

    /** Setting the same filter twice is not a change, so it cannot cost a rebuild per frame. */
    @Test
    public void settingTheSameFilterIsNotAChange() {
        RunConsole console = withTwoScripts(new TextBuffer());
        console.setFilter("A.java");
        console.drain();

        console.setFilter("A.java");
        assertFalse(console.drain());
    }
}
