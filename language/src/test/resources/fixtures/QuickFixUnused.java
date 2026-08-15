/*
 * QUICK-FIX FIXTURE -- the "unused" family, one site per correction.
 *
 * Open this in the GL harness (it is installed into the scratch workspace by
 * `./gradlew :language:installHarnessFixtures`), put the caret on any squiggle and press Alt+Enter.
 * Every `// FIX:` line below states, verbatim, the action that must appear on the line beneath it.
 *
 * THOSE COMMENTS ARE ASSERTIONS, NOT DOCUMENTATION. FixtureFilesTest reads this file, asks the engine
 * for the actions at each annotated line and fails if the named one is not offered -- so a comment here
 * cannot quietly stop being true, which is the whole reason the file is shaped this way rather than
 * written as prose. When a correction is added to a family, its site is added to that family's fixture
 * in the same commit.
 *
 * The file must PARSE. A syntax error makes ECJ skip analyseCode() and with it every optional problem in
 * the file, so a fixture that does not compile reports fewer diagnostics rather than more.
 */

// FIX: "Remove unused import"
// FIX: "Remove unused imports"
import java.util.List;
import java.util.Set;
import java.util.Map;

public class QuickFixUnused {

    // FIX: "Remove field 'unusedField'"
    private int unusedField;

    /** Keeps one import genuinely used, so the unused ones are unused for a real reason. */
    Map<String, String> usesMap() {
        return null;
    }

    // FIX: "Remove method 'neverCalled'"
    private void neverCalled() { }

    // FIX: "Remove constructor 'QuickFixUnused'"
    private QuickFixUnused(int unusedParameter) { }

    /** Keeps the class constructible, so the private one above is unused for a real reason. */
    QuickFixUnused() { }

    // FIX: "Remove interface 'Helper'"
    private interface Helper { }

    void unusedLocal() {
        // FIX: "Remove variable 'greeting'"
        String greeting = "never read";
    }

    void redundantSemicolon() {
        // FIX: "Remove redundant semicolon"
        ;
    }

    // FIX: "Remove 'java.io.IOException' from throws"
    void neverThrowsIt() throws java.io.IOException { }

    // FIX: "Remove type parameter 'U'"
    <T, U> void unusedTypeParameter(T t) { }

    /**
     * The case that was refused until quick fixes moved onto JDT's rewriter: deleting the statement
     * would take `a` with it, so the correction offered nothing at all. Now only `b` goes, comma included.
     */
    int multiNameDeclaration() {
        // FIX: "Remove variable 'b'"
        int a = 1, b = 2;
        return a;
    }
}

/** `Runnable` again, when the superclass already implements it. */
// FIX: "Remove redundant interface 'Runnable'"
class QuickFixRedundantInterface extends QuickFixUnusedBase implements Runnable { }

class QuickFixUnusedBase implements Runnable {
    public void run() { }
}
