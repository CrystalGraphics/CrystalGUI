package com.crystalgui.language.run;

import com.crystalgui.language.run.console.*;
import com.crystalgui.text.TextBuffer;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * M9.5 §9.5.6 — which spans of console output are navigable.
 *
 * <p>Headless, and it can be: a {@link ConsoleFilter} is a pure function of a line's text, which is the
 * property the whole design rests on. What cannot be tested here is the click itself — that needs a window
 * — so what is pinned is the part where a mistake is silent: which characters are the link, and that the
 * answer survives the ring evicting from the front.</p>
 */
public class ConsoleLinksTest {

    private final ConsoleFilter filter = new JavaStackFrameFilter();

    private static RunMessage out(String text) {
        return new RunMessage("Main.java", null, null, 0, RunLevel.OUT, text);
    }

    /**
     * <b>The span is the file and line, not the whole frame.</b>
     *
     * <p>IntelliJ underlines exactly {@code Foo.java:42}. Underlining the qualified method name in front
     * of it would promise navigation from text that does not navigate, and turn a trace into a wall of
     * underline — which is what the mark is meant to stand out from.</p>
     */
    @Test
    public void aFrameLinksTheFileAndLineOnly() {
        String text = "\tat com.example.Thing.method(Thing.java:42)";
        List<ConsoleFilter.Link> links = filter.apply(text);

        assertEquals(1, links.size());
        ConsoleFilter.Link link = links.get(0);
        assertEquals("Thing.java:42", text.substring(link.start(), link.end()));
        assertEquals("Thing.java", link.fileName());
        assertEquals(42, link.line());
    }

    /** Every frame in a trace is its own link — a trace is many destinations, not one. */
    @Test
    public void everyFrameOnALineIsItsOwnLink() {
        List<ConsoleFilter.Link> links =
                filter.apply("at A.a(A.java:1) at B.b(B.java:22)");
        assertEquals(2, links.size());
        assertEquals("A.java", links.get(0).fileName());
        assertEquals(22, links.get(1).line());
    }

    /**
     * <b>A frame with no source is not a link.</b>
     *
     * <p>Guessing produces an underline that opens nothing, which is worse than a plain frame: it teaches
     * the reader that the underlines are unreliable, and then the real ones stop being trusted.</p>
     */
    @Test
    public void framesWithNoSourceAreNotLinked() {
        assertTrue(filter.apply("\tat java.lang.Thread.run(Native Method)").isEmpty());
        assertTrue(filter.apply("\tat Foo.bar(Unknown Source)").isEmpty());
        assertTrue(filter.apply("plain output with no frame at all").isEmpty());
        assertTrue(filter.apply("").isEmpty());
    }

    /** A line number too large to be one is refused rather than thrown out of a paint pass. */
    @Test
    public void anUnparseableLineNumberIsRefusedNotThrown() {
        assertTrue(filter.apply("at X.y(X.java:99999999999999)").isEmpty());
    }

    /** A console with no filters answers empty rather than null — nothing is navigable, and that is fine. */
    @Test
    public void aConsoleWithNoFiltersHasNoLinks() {
        RunConsole console = new RunConsole().attach(new TextBuffer());
        console.append(out("at A.a(A.java:1)"));
        console.drain();
        assertTrue(console.linksAt(0).isEmpty());
    }

    /** With one registered, the console answers per row. */
    @Test
    public void aRegisteredFilterAnswersPerRow() {
        RunConsole console = new RunConsole().attach(new TextBuffer()).addFilter(filter);
        console.append(out("ordinary output"));
        console.append(out("\tat A.a(A.java:7)"));
        console.drain();

        assertTrue(console.linksAt(0).isEmpty());
        assertEquals(7, console.linksAt(1).get(0).line());
        assertTrue("past the end is empty, not an exception", console.linksAt(99).isEmpty());
    }

    /**
     * <b>A run boundary is never scanned.</b>
     *
     * <p>A divider is ours rather than the script's, so a match on one can only be a false positive — and
     * a rule of dashes is exactly the sort of text a loose pattern finds something in.</p>
     */
    @Test
    public void aDividerIsNeverScanned() {
        RunConsole console = new RunConsole().attach(new TextBuffer()).addFilter(text -> {
            // A filter that claims everything, to prove the divider is excluded by the console and not by
            // the filter happening not to match.
            return text.isEmpty() ? List.of() : List.of(new ConsoleFilter.Link(0, 1, "X.java", 1));
        });
        console.startRun("Main.java");
        console.drain();

        assertTrue(console.lineAt(0).isDivider());
        assertTrue(console.linksAt(0).isEmpty());
    }

    /**
     * <b>The answer survives eviction, which is the reason spans are never stored.</b>
     *
     * <p>The ring deletes from the front of the document — an edit. Held offsets would begin describing the
     * wrong text from the first eviction, silently: the transcript goes on working and only the
     * destinations are wrong. Recomputing from the row's own text cannot desync, and this is the test that
     * would fail if somebody cached them.</p>
     */
    @Test
    public void linksAreStillRightAfterTheRingHasEvicted() {
        RunConsole console = new RunConsole().attach(new TextBuffer()).addFilter(filter).setBudgetKb(1);
        for (int i = 0; i < 400; i++) {
            console.append(out("\tat com.example.Thing.method(Thing.java:" + (i + 1) + ")"));
            console.drain();
        }
        assertTrue("the ring should have evicted", console.dropped() > 0);

        for (int row = 0; row < console.lineCount(); row++) {
            List<ConsoleFilter.Link> links = console.linksAt(row);
            assertEquals("row " + row + " should carry exactly one link", 1, links.size());
            String text = console.lineAt(row).text();
            ConsoleFilter.Link link = links.get(0);
            assertEquals("Thing.java:" + link.line(), text.substring(link.start(), link.end()));
        }
    }

    /** Two filters answering one line come back in document order, because the tokenizer walks them. */
    @Test
    public void linksFromSeveralFiltersComeBackSorted() {
        RunConsole console = new RunConsole().attach(new TextBuffer())
                .addFilter(text -> text.length() > 20 ? List.of(new ConsoleFilter.Link(15, 20, "B.java", 2)) : List.of())
                .addFilter(text -> text.length() > 20 ? List.of(new ConsoleFilter.Link(2, 6, "A.java", 1)) : List.of());
        console.append(out("a line long enough to carry two spans"));
        console.drain();

        List<ConsoleFilter.Link> links = console.linksAt(0);
        assertEquals(2, links.size());
        assertEquals("A.java", links.get(0).fileName());
        assertEquals("B.java", links.get(1).fileName());
    }

    /**
     * <b>A trace the JVM actually wrote, not one this test made up.</b>
     *
     * <p>Every other case here hands the filter a string shaped the way {@link StackTraceElement} is
     * documented to print. That proves the regex against the spec and not against reality — a tab that is
     * really a space, a frame format that differs under a lambda or a synthetic method, and the pattern
     * would still pass while matching nothing in the panel. This throws for real, captures what
     * {@code printStackTrace} emits, and pushes it through the console exactly as
     * {@code ScriptWorkbench.report} does.</p>
     */
    @Test
    public void aRealStackTraceProducesLinks() {
        StringWriter trace = new StringWriter();
        try {
            throw new IllegalStateException("deliberate");
        } catch (IllegalStateException thrown) {
            thrown.printStackTrace(new PrintWriter(trace));
        }

        RunConsole console = new RunConsole().attach(new TextBuffer()).addFilter(filter);
        for (String line : trace.toString().split("\\R")) {
            if (!line.isBlank()) console.append(new RunMessage("Main.java", null, null, 0,
                    RunLevel.ERROR, line));
        }
        console.drain();

        int linked = 0;
        boolean sawThisFile = false;
        for (int row = 0; row < console.lineCount(); row++) {
            for (ConsoleFilter.Link link : console.linksAt(row)) {
                linked++;
                String text = console.lineAt(row).text();
                // The span must name the file and line and nothing else -- not the qualified method in
                // front of it, and not the parentheses around it.
                assertEquals(link.fileName() + ":" + link.line(),
                        text.substring(link.start(), link.end()));
                if ("ConsoleLinksTest.java".equals(link.fileName())) sawThisFile = true;
            }
        }
        assertTrue("a real trace should have produced links", linked > 0);
        assertTrue("the frame for this very test should be one of them", sawThisFile);
        // The first line is the exception's own message, which names no source and must not be linked.
        assertTrue("the header line is not a frame", console.linksAt(0).isEmpty());
    }

    /** An empty or reversed span is a programming error and says so rather than painting nothing. */
    @Test
    public void anEmptySpanIsRefused() {
        try {
            new ConsoleFilter.Link(4, 4, "A.java", 1);
            fail("an empty span should be refused");
        } catch (IllegalArgumentException expected) {
            // the point
        }
    }
}
