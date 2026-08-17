package com.crystalgui.text.diagnostic;

import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.TextPoint;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * <b>M11 §24.6</b> — a producer that is not a language engine, filed through the machinery every other
 * producer already uses.
 *
 * <p>The exit criterion is "with no new machinery named in the diff", so what is asserted here is that a
 * checker's answer reaches a {@link DiagnosticSet} the same way an engine's does — and the one thing that
 * is genuinely this class's own: an answer describing a document the buffer has moved on from is dropped
 * rather than drawn at coordinates that now mean something else.</p>
 */
public class CheckedDocumentTest {

    /** A checker that complains about whatever line it is told to. */
    private static SourceChecker complainingAtRow(int row, String message) {
        return (name, source) -> List.of(new Diagnostic(new TextPoint(row, 0),
                new TextPoint(row, Integer.MAX_VALUE), DiagnosticSeverity.ERROR, message, "shader", null));
    }

    @Test
    public void aCheckersAnswerReachesTheDocumentsDiagnostics() {
        TextBuffer buffer = new TextBuffer("void main() {\n}\n");
        DiagnosticSet diagnostics = new DiagnosticSet();
        try (CheckedDocument checked = new CheckedDocument("shader", "a.shader", buffer, diagnostics,
                complainingAtRow(1, "no Pass blocks found"), null).start()) {
            List<Diagnostic> filed = diagnostics.read("shader");
            assertEquals("the checker's answer never reached the document", 1, filed.size());
            assertEquals("no Pass blocks found", filed.get(0).message());
            assertEquals(1, filed.get(0).start().row());
        }
    }

    /** And a second producer does not disturb the first, which is what filing per owner is for. */
    @Test
    public void anEnginesProblemsAreUntouched() {
        TextBuffer buffer = new TextBuffer("void main() {\n}\n");
        DiagnosticSet diagnostics = new DiagnosticSet();
        diagnostics.changeOne("glsl-engine", List.of(new Diagnostic(new TextPoint(0, 0),
                new TextPoint(0, 4), DiagnosticSeverity.WARNING, "unused", "glsl-engine", null)));

        try (CheckedDocument checked = new CheckedDocument("shader", "a.shader", buffer, diagnostics,
                complainingAtRow(1, "no Pass blocks found"), null).start()) {
            assertEquals("the other producer's problems were replaced", 1,
                    diagnostics.read("glsl-engine").size());
            assertEquals(1, diagnostics.read("shader").size());
        }
    }

    /** Closing withdraws what it filed, and only what it filed. */
    @Test
    public void closingWithdrawsItsOwnProblems() {
        TextBuffer buffer = new TextBuffer("x\n");
        DiagnosticSet diagnostics = new DiagnosticSet();
        diagnostics.changeOne("glsl-engine", List.of(new Diagnostic(new TextPoint(0, 0),
                new TextPoint(0, 1), DiagnosticSeverity.WARNING, "kept", "glsl-engine", null)));

        CheckedDocument checked = new CheckedDocument("shader", "a.shader", buffer, diagnostics,
                complainingAtRow(0, "boom"), null).start();
        assertEquals(1, diagnostics.read("shader").size());
        checked.close();
        assertTrue("the checker's problems outlived it", diagnostics.read("shader").isEmpty());
        assertEquals("closing one producer took another's with it", 1,
                diagnostics.read("glsl-engine").size());
    }

    /** Re-checked on every edit, so a fixed file stops complaining. */
    @Test
    public void anEditRunsItAgain() {
        TextBuffer buffer = new TextBuffer("bad\n");
        DiagnosticSet diagnostics = new DiagnosticSet();
        List<String> seen = new ArrayList<>();
        SourceChecker checker = (name, source) -> {
            seen.add(source);
            return source.startsWith("bad")
                    ? List.of(new Diagnostic(new TextPoint(0, 0), new TextPoint(0, 3),
                            DiagnosticSeverity.ERROR, "bad", "shader", null))
                    : List.of();
        };

        try (CheckedDocument checked = new CheckedDocument("shader", "a.shader", buffer, diagnostics,
                checker, null).start()) {
            assertEquals(1, diagnostics.read("shader").size());
            buffer.replace(0, 3, "good");
            assertEquals("the file was not re-checked after an edit", 2, seen.size());
            assertTrue("a fixed file kept its problem", diagnostics.read("shader").isEmpty());
        }
    }
}
