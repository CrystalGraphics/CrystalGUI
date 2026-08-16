/*
 * QUICK-FIX FIXTURE -- intentions: nothing is wrong, and something could be different.
 *
 * Open this in the GL harness (it is installed into the scratch workspace by
 * `./gradlew :language:installHarnessFixtures`), put the caret on the line beneath a `// FIX:` comment
 * and press Alt+Enter. Every `// FIX:` line states, verbatim, the action that must appear there.
 *
 * THIS FILE COMPILES, AND IT IS THE ONLY QUICK-FIX FIXTURE THAT DOES. Every other one is a collection of
 * deliberate errors, because every other family answers a diagnostic. An intention answers none -- that
 * is what makes it an intention -- so there is nothing here to be wrong. It also means no coverage probe
 * over reported problems can ever say one of these is missing, however many are.
 *
 * ALL FOUR ARE PAIRS. Split <-> join, add braces <-> remove braces: you reach for one having just used
 * the other. Writing them as pairs is what forces the refusals to be stated, because the dangerous half
 * of a reversible edit is always the same one -- the direction that REMOVES structure, where Java quietly
 * changes meaning underneath.
 */
public class QuickFixIntentions {

    // ── Declaration and assignment ──────────────────────────────────────────────────────────────

    void split() {
        // FIX: "Split into declaration and assignment"
        int count = 1;
        System.out.println(count);
    }

    /** The initialiser is never touched by the edit, so a comment inside it survives verbatim. */
    void splitKeepsTheValueExactly() {
        // FIX: "Split into declaration and assignment"
        String joined = "a" + /* keep me */ "b";
        System.out.println(joined);
    }

    void join() {
        // FIX: "Join declaration and assignment"
        int count;
        count = 1;
        System.out.println(count);
    }

    /**
     * NOT OFFERED. The assignment must be the VERY NEXT statement -- anything in between may read the
     * variable, and moving the initialiser up past a read changes what the program does. Invisibly, since
     * the result still compiles.
     */
    void notAdjacent() {
        int count;
        System.out.println("something happens first");
        count = 1;
        System.out.println(count);
    }

    /** NOT OFFERED. One type node serves both fragments, so either direction rewrites both. */
    void twoAtOnce() {
        int first = 1, second = 2;
        System.out.println(first + second);
    }

    // ── Braces ──────────────────────────────────────────────────────────────────────────────────

    void addToIf(boolean flag) {
        // FIX: "Add braces"
        if (flag) System.out.println(1);
    }

    void addToLoop(int n) {
        // FIX: "Add braces"
        while (n > 0) n--;
    }

    void removeFromIf(boolean flag) {
        // FIX: "Remove braces"
        if (flag) {
            System.out.println(1);
        }
    }

    /**
     * NOT OFFERED. `if (x) int a = 1;` is not legal Java -- a declaration is not a statement an unbraced
     * body may be, so removing these braces breaks the file outright.
     */
    void bracesAroundADeclaration(boolean flag) {
        if (flag) {
            int held = 1;
            System.out.println(held);
        }
    }

    /**
     * NOT OFFERED, and this is the one that compiles either way. An inner `if` with no `else`, inside the
     * braced then-branch of an `if` that HAS one: take the braces off and the `else` re-binds to the inner
     * `if`. The dangling-else problem -- it compiles, and it means something else.
     */
    void danglingElse(boolean a, boolean b) {
        if (a) {
            if (b) System.out.println(1);
        } else {
            System.out.println(2);
        }
    }

    /**
     * NOT OFFERED. `else if` is not an unbraced else. Bracing it gives `else { if (…) … }`, which is legal,
     * identical in meaning, and not what anybody writing a chain wants.
     */
    void chain(int n) {
        if (n == 1) {
            System.out.println(1);
        } else if (n == 2) {
            System.out.println(2);
        }
    }
}
