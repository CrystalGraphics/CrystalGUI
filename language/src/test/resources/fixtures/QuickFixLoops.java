/*
 * QUICK-FIX FIXTURE -- the last three conversions: enhanced for, if-chain to switch, lambda to anonymous.
 *
 * Open this in the GL harness (it is installed into the scratch workspace by
 * `./gradlew :language:installHarnessFixtures`), put the caret on the line beneath a `// FIX:` comment
 * and press Alt+Enter. Every `// FIX:` line states, verbatim, the action that must appear there.
 *
 * THIS FILE COMPILES. All three are intentions and answer no diagnostic.
 *
 * ALL THREE CONVERT BETWEEN TWO FORMS OF THE SAME THING, so everything interesting here is the condition
 * under which the two forms are NOT the same thing.
 */

import java.util.List;

public class QuickFixLoops {

    // ── Enhanced for ────────────────────────────────────────────────────────────────────────────

    void overAnArray(String[] names) {
        // FIX: "Convert to enhanced for"
        for (int i = 0; i < names.length; i++) {
            System.out.println(names[i]);
        }
    }

    void overAList(List<String> names) {
        // FIX: "Convert to enhanced for"
        for (int i = 0; i < names.size(); i++) {
            System.out.println(names.get(i));
        }
    }

    /**
     * NOT OFFERED. The whole safety condition: one use of the index for anything but fetching, and the
     * enhanced form cannot express the loop, because it has no index to give back.
     */
    void indexUsedForItself(String[] names) {
        for (int i = 0; i < names.length; i++) {
            System.out.println(i + ": " + names[i]);
        }
    }

    /** NOT OFFERED. Starting at one is a different loop -- it skips the first element. */
    void skipsTheFirst(String[] names) {
        for (int i = 1; i < names.length; i++) {
            System.out.println(names[i]);
        }
    }

    List<String> computed() {
        return null;
    }

    /**
     * NOT OFFERED. `computed()` is called every iteration and the enhanced form calls it once. That is
     * usually what the author wanted and it is not the same program, so the sequence must be a plain name.
     */
    void recomputedEveryTime() {
        for (int i = 0; i < computed().size(); i++) {
            System.out.println(computed().get(i));
        }
    }

    // ── If chain to switch ──────────────────────────────────────────────────────────────────────

    void chain(int n) {
        // FIX: "Replace if chain with switch"
        if (n == 1) {
            System.out.println("one");
        } else if (n == 2) {
            System.out.println("two");
        } else if (n == 3) {
            System.out.println("three");
        } else {
            System.out.println("many");
        }
    }

    /**
     * FALL-THROUGH IS THE ONE DIFFERENCE BETWEEN THE TWO FORMS AND IT IS SILENT. A branch that already
     * returns, throws, breaks or continues gets no `break`; every other branch gets one. Miss it and a
     * chain that ran one branch becomes a switch that runs the rest of them.
     */
    String strings(String s) {
        // FIX: "Replace if chain with switch"
        if (s.equals("a")) {
            return "first";
        } else if (s.equals("b")) {
            return "second";
        } else if (s.equals("c")) {
            return "third";
        }
        return "none";
    }

    enum Colour { RED, GREEN, BLUE }

    /**
     * NOT OFFERED, and this is the case that looks most obviously in. A switch needs the label UNQUALIFIED
     * -- `case RED`, never `case Colour.RED` -- while the `if` it came from almost always writes the
     * qualified form. Stripping a qualifier this cannot always identify would be wrong on a static import
     * or on a constant that merely looks like one. Right for two shapes beats usually-right for three.
     */
    void colours(Colour c) {
        if (c == Colour.RED) {
            System.out.println(1);
        } else if (c == Colour.GREEN) {
            System.out.println(2);
        } else if (c == Colour.BLUE) {
            System.out.println(3);
        }
    }

    /** NOT OFFERED. Two branches make a switch longer than the chain it replaces. */
    void tooShort(int n) {
        if (n == 1) {
            System.out.println(1);
        } else if (n == 2) {
            System.out.println(2);
        }
    }

    // ── Lambda to anonymous class ───────────────────────────────────────────────────────────────

    void toAnonymous() {
        // FIX: "Replace with anonymous class"
        Runnable r = () -> System.out.println(1);
        r.run();
    }

    /**
     * NOT OFFERED. An unqualified `this` means the enclosing instance in a lambda and would mean the
     * anonymous one inside a class body. The forward conversion refuses on exactly this, from the other
     * direction -- which is the whole reason the two are not symmetric.
     */
    void usesThis() {
        Runnable r = () -> System.out.println(this);
        r.run();
    }
}
