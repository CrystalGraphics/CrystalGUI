/*
 * QUICK-FIX FIXTURE -- construct it, re-type it, or initialise it.
 *
 * Open this in the GL harness (it is installed into the scratch workspace by
 * `./gradlew :language:installHarnessFixtures`), put the caret on a red squiggle and press Alt+Enter.
 * Every `// FIX:` line below states, verbatim, the action that must appear on the line beneath it.
 *
 * THIS FILE DOES NOT COMPILE, and cannot: every site is an ERROR reported with no configuration.
 *
 * WHAT THESE ARE REALLY ABOUT IS THREE REFUSALS -- which signature may be edited, which value may be
 * thrown away, and when a final field may be given one. The conversions are the easy half.
 */
public class QuickFixConstruct {

    // ── Create constructor ──────────────────────────────────────────────────────────────────────

    static class Box {
        int size;
    }

    void construct() {
        // FIX: "Create constructor 'Box(int, String)'"
        Box b = new Box(1, "a");
        System.out.println(b);
    }

    /**
     * NOT OFFERED. `String` is not declared in this file, and another file is the one thing a ChangeSet
     * cannot be -- one edit, one document, by construction.
     */
    void elsewhere() {
        String s = new String(1, 2, 3);
        System.out.println(s);
    }

    /**
     * NOT OFFERED. A lambda argument has no type of its own: it takes one from the parameter it is passed
     * to, and that parameter is exactly what does not exist yet. Writing `Object` produces a signature the
     * call still cannot use, while looking finished.
     */
    void lambdaArgument() {
        Box b = new Box(() -> { });
        System.out.println(b);
    }

    // ── The value and the signature ─────────────────────────────────────────────────────────────

    /**
     * BOTH ANSWERS. Changing the return type is preferred -- `return 5;` was written by somebody who meant
     * to return something -- and dropping the value is offered beside it, because "I meant this to return"
     * and "I left that behind" happen about equally often and the code cannot say which.
     *
     * A RETURN type may be changed where a PARAMETER type may not, and the reason is not taste: widening
     * `void` is source-compatible for every existing call, since a call whose result is discarded is a
     * legal statement whatever the method returns. Nothing that compiled stops compiling.
     */
    void returnsAValue() {
        // FIX: "Change return type to 'int'"
        // FIX: "Remove returned value"
        return 5;
    }

    static int compute() {
        return 1;
    }

    /**
     * NOT DROPPED. `return compute();` discards the RESULT and keeps the work -- deleting the invocation
     * deletes the work too. The same rule the unused-assignment fix is refused under. Re-typing is still
     * offered, because that keeps both.
     */
    void returnsACall() {
        // FIX: "Change return type to 'int'"
        return compute();
    }

    // ── A final field with no value ─────────────────────────────────────────────────────────────

    static class Sized {
        final int size;

        // FIX: "Initialize field 'size'"
        Sized() { }
    }

    /**
     * NOT OFFERED. A final field may be assigned exactly once, so initialising at the declaration while
     * another constructor assigns it turns "may not have been initialized" into "may already have been
     * assigned" -- a different error, in the constructor that was previously correct.
     */
    static class PartlySized {
        final int size;

        PartlySized() { }

        PartlySized(int n) {
            size = n;
        }
    }
}
