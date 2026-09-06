package com.crystalgui.language.java;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;

/**
 * <b>A file that does not compile reports an error.</b>
 *
 * <p>Reported from the desktop scene: a {@code Main.java} with a mangled declaration and a misspelled
 * method showed <em>0 errors, 2 warnings</em>, and the two warnings were about a version of the file
 * that no longer existed - one of them said an import was never used while the line using it was on
 * screen. Something between ECJ and the Problems panel was serving a stale answer.</p>
 *
 * <p>This is the half of that question the engine can answer on its own: given the broken text, does the
 * analyser produce errors at all? If it does, nothing here is the engine's fault and the fault is in
 * delivery - which is a different file and a different test.</p>
 */
public class BrokenSourceIsReportedTest extends FixFixture {

    /** The reported file, reduced to the two things wrong with it, under the fixture's own class name. */
    private static final String BROKEN = """
            public class Script {
                public static void main(String[] args) {
                    String REN//DER = "x";
                    System.out.prisdsntln("hello");
                }
            }
            """;

    /**
     * The declaration on line 3 is cut in half by the comment, so the statement never terminates.
     *
     * <p>Asserted on the SEVERITY rather than on a problem id: which id ECJ picks for an unterminated
     * declaration is its business and has changed between releases, while "the author is told this does
     * not compile" is the contract.</p>
     */
    @Test
    public void anUnterminatedDeclarationIsAnError() {
        List<Diagnostic> found = diagnosticsOf(BROKEN);
        assertTrue("a file that cannot parse reported no error at all -- reported: " + found,
                found.stream().anyMatch(d -> d.severity() == DiagnosticSeverity.ERROR));
    }

    /**
     * The counter-control: the same shape, spelled correctly, reports none.
     *
     * <p>Without it an analyser that answered "error" for every input would satisfy the case above, and
     * the whole test would be measuring nothing.
     */
    @Test
    public void theSameFileWrittenCorrectlyReportsNoError() {
        List<Diagnostic> found = diagnosticsOf("""
                public class Script {
                    public static void main(String[] args) {
                        String render = "x";
                        System.out.println(render);
                    }
                }
                """);
        assertFalse("a file that compiles was reported as broken -- reported: " + found,
                found.stream().anyMatch(d -> d.severity() == DiagnosticSeverity.ERROR));
    }
}
