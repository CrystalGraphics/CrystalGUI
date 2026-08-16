/*
 * QUICK-FIX FIXTURE -- the condition pair and the variable pair.
 *
 * Open this in the GL harness (it is installed into the scratch workspace by
 * `./gradlew :language:installHarnessFixtures`), put the caret on the line beneath a `// FIX:` comment
 * and press Alt+Enter. Every `// FIX:` line states, verbatim, the action that must appear there.
 *
 * THIS FILE COMPILES, like QuickFixIntentions.java and for the same reason: an intention answers no
 * diagnostic, so there is nothing here to be wrong.
 *
 * THE REMOVING HALF IS ALWAYS THE DANGEROUS ONE, which by now is the pattern rather than a coincidence.
 * Flipping is safe because it does two opposite things at once; inlining is not, because it can duplicate
 * work and rebind operators, and neither shows up in a result that compiles.
 */
public class QuickFixRefactor {

    // ── The condition ───────────────────────────────────────────────────────────────────────────

    void flip(int n) {
        // FIX: "Flip if/else"
        if (n == 0) {
            System.out.println("zero");
        } else {
            System.out.println("other");
        }
    }

    /** `!ready` negates to `ready`, not to `!!ready`. */
    void flipNegated(boolean ready) {
        // FIX: "Flip if/else"
        if (!ready) {
            System.out.println(1);
        } else {
            System.out.println(2);
        }
    }

    /**
     * NOT OFFERED. The else branch of a chain is another `if`, so swapping would hoist a whole tail into
     * the then-position and leave the chain meaning something else.
     */
    void chain(int n) {
        if (n == 1) {
            System.out.println(1);
        } else if (n == 2) {
            System.out.println(2);
        }
    }

    /** NOT OFFERED. With no else there is nothing to swap, and negating alone changes what runs. */
    void noElse(int n) {
        if (n == 1) {
            System.out.println(1);
        }
    }

    /**
     * The one intention in this engine that CHANGES WHAT THE CODE DOES, which is why it is restricted to a
     * comparison: a flipped `<` says one thing, the reader can see both halves, and there is no version of
     * it that quietly does something else. Wrapping an arbitrary condition in `!` would not be.
     */
    boolean negate(int n) {
        // FIX: "Negate comparison"
        return n < 10;
    }

    // ── The variable ────────────────────────────────────────────────────────────────────────────

    void introduce(String s) {
        // FIX: "Introduce variable 'trim'"
        System.out.println(s.trim());
    }

    void inline(String s) {
        // FIX: "Inline variable 'trimmed'"
        String trimmed = s.trim();
        System.out.println(trimmed);
    }

    /**
     * The value is PARENTHESISED on the way in. `int sum = a + b;` inlined into `sum * 2` gives
     * `a + b * 2` -- which compiles and is a different number.
     */
    void inlineOperator(int a, int b) {
        // FIX: "Inline variable 'sum'"
        int sum = a + b;
        System.out.println(sum * 2);
    }

    static int compute() {
        return 1;
    }

    /**
     * NOT OFFERED. One call would become two, which is a behaviour change the moment it does anything.
     * IntelliJ warns and lets you continue; a popup with no dialog cannot ask, so it refuses.
     */
    void usedTwice() {
        int n = compute();
        System.out.println(n + n);
    }

    /** NOT OFFERED. An assigned variable's initialiser stopped being its value at the next line. */
    void reassigned(int a) {
        int n = a;
        n = a + 1;
        System.out.println(n);
    }
}
