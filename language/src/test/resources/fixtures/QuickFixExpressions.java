/*
 * QUICK-FIX FIXTURE -- expressions ECJ has proved say nothing, one site per correction.
 *
 * Open this in the GL harness (it is installed into the scratch workspace by
 * `./gradlew :language:installHarnessFixtures`), put the caret on any squiggle and press Alt+Enter.
 * Every `// FIX:` line below states, verbatim, the action that must appear on the line beneath it.
 *
 * THOSE COMMENTS ARE ASSERTIONS, NOT DOCUMENTATION. FixtureFilesTest reads this file, asks the engine
 * for the actions at each annotated line and fails if the named one is not offered.
 *
 * ALL THREE PROBLEMS HERE ARE OFF IN ECJ'S DEFAULTS and are switched on by EcjProblemPolicy, so an empty
 * popup on any of these lines means the option went rather than the correction.
 */
public class QuickFixExpressions {

    String castToWhatItAlreadyIs(String text) {
        // FIX: "Remove unnecessary cast"
        return (String) text;
    }

    /** The shape the parentheses exist for: they go with the cast rather than being left behind. */
    int castThenCall(String text) {
        // FIX: "Remove unnecessary cast"
        return ((String) text).length();
    }

    /** The operand's own parentheses are part of the operand and stay. */
    int castOfASum(int a, int b) {
        // FIX: "Remove unnecessary cast"
        return (int) (a + b);
    }

    void take(Object value) { }

    void take(String value) { }

    /**
     * NOT OFFERED, and deliberately: with two candidates present ECJ does not report the cast at all,
     * because removing it would call take(String) instead. Left here as the case a reader will ask about.
     */
    void castThatSelectsAnOverload(String text) {
        take((Object) text);
    }

    /**
     * A null check rather than `true`: JLS 15.20.2 makes `x instanceof T` false when x is null, so the
     * two are not the same answer for a parameter that can be.
     */
    boolean alreadyAnInstance(String text) {
        // FIX: "Replace 'instanceof' with a null check"
        return text instanceof Object;
    }

    /** A check the flow analysis has proved cannot fail: the `if` is the only thing being said. */
    String checkThatCannotFail() {
        Object made = new Object();
        // FIX: "Remove redundant condition"
        if (made != null) {
            return made.toString();
        }
        return "";
    }

    /**
     * NOT OFFERED: the second operand is a real test, so collapsing the `if` would delete it. The
     * diagnostic still marks the redundant half.
     */
    String compoundCondition() {
        Object made = new Object();
        if (made != null && made.hashCode() > 0) {
            return made.toString();
        }
        return "";
    }

    /**
     * NOT OFFERED, and this is what keeps the diagnostic from being noise: ECJ knows nothing about what a
     * caller passes, so a defensive check on a parameter is never reported.
     */
    int defensiveCheck(String text) {
        if (text != null) {
            return text.length();
        }
        return 0;
    }
}
