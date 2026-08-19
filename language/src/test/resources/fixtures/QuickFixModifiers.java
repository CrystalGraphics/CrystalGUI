/*
 * QUICK-FIX FIXTURE -- one keyword added or taken away, one site per correction.
 *
 * Open this in the GL harness (it is installed into the scratch workspace by
 * `./gradlew :language:installHarnessFixtures`), put the caret on any squiggle and press Alt+Enter.
 * Every `// FIX:` line below states, verbatim, the action that must appear on the line beneath it.
 *
 * THOSE COMMENTS ARE ASSERTIONS, NOT DOCUMENTATION. FixtureFilesTest reads this file, asks the engine
 * for the actions at each annotated line and fails if the named one is not offered.
 *
 * UNLIKE THE OTHER FIXTURES, THIS FILE DOES NOT COMPILE, and it cannot: every problem here is an ERROR,
 * reported with no configuration, on code that is genuinely wrong. That is also why there is no severity
 * table to keep in step for these three.
 *
 * ONE BROKEN TYPE PER PROBLEM, and that is load-bearing rather than tidiness. A type that must be
 * abstract is a type whose declaration ECJ cannot complete, so the members inside it resolve to recovered
 * bindings -- and the `final` fix works by resolving the assigned name back to where it was declared. Put
 * both in one class and the final sites silently offer nothing, which is exactly how this file failed the
 * first time it was written.
 */
public class QuickFixModifiers {

    private final int assignedLater = 1;

    void assignsAFinalField() {
        // FIX: "Remove 'final' modifier"
        assignedLater = 2;
    }

    void assignsAFinalLocal() {
        final int value = 1;
        // FIX: "Remove 'final' modifier"
        value = 2;
        System.out.println(value);
    }

    /** A blank final assigned twice: a third problem id and the same repair. */
    void assignsABlankFinalTwice() {
        final int value;
        value = 1;
        // FIX: "Remove 'final' modifier"
        value = 2;
        System.out.println(value);
    }
}

/**
 * Reported twice -- once on the method below, once on this class's name -- and the same keyword answers
 * both, so the popup must show one row wherever the caret is.
 */
class QuickFixModifiersConcrete {

    // FIX: "Make 'QuickFixModifiersConcrete' abstract"
    abstract void needsAnAbstractClass();
}

/**
 * The other direction: the method has a body, so the keyword is the half that is wrong. Removing the body
 * would compile too and would throw away what was written.
 */
abstract class QuickFixModifiersAbstract {

    // FIX: "Remove 'abstract' modifier"
    abstract void hasABody() { }
}
