/*
 * QUICK-FIX FIXTURE -- something has no value, or has no declaration.
 *
 * Open this in the GL harness (it is installed into the scratch workspace by
 * `./gradlew :language:installHarnessFixtures`), put the caret on a red squiggle and press Alt+Enter.
 * Every `// FIX:` line below states, verbatim, the action that must appear on the line beneath it.
 *
 * THIS FILE DOES NOT COMPILE, and cannot: every site is an ERROR reported with no configuration.
 *
 * THESE ARE THE ERRORS YOU MAKE WHILE WRITING, which is why the coverage probe over this repository
 * never sees one. A file that compiles has no missing return, no uninitialised local and no name used
 * before it is declared -- by definition. Their absence from that histogram is a fact about a corpus of
 * FINISHED code, and reading it as "nobody hits these" is how a catalogue comes to cover only what is
 * convenient to count.
 */
public class QuickFixValues {

    // ── A value, for a type that is already known ───────────────────────────────────────────────

    /**
     * The method promises an int and one path falls off the end. ECJ marks the NAME AND PARAMETER LIST,
     * which is where the promise was made rather than where it was broken -- and is right, because no
     * single statement is at fault.
     */
    // FIX: "Add return statement"
    int total(int a) {
        int b = a + 1;
        System.out.println(b);
    }

    /** A reference type gets `null` by the same rule -- the value the JVM would have used for a field. */
    // FIX: "Add return statement"
    String name(boolean flag) {
        if (flag) {
            return "yes";
        }
    }

    /**
     * A local read before anything was put in it. Reported at the USE, so the declaration is found through
     * the binding rather than by walking backwards -- ECJ picks whichever read it reached first, which on a
     * variable used several times is not the one nearest the declaration.
     */
    void uninitialised() {
        String label;
        System.out.println("something else entirely");
        // FIX: "Initialize variable 'label'"
        System.out.println(label);
    }

    // ── A declaration, for a name that has none ─────────────────────────────────────────────────

    /**
     * An assignment written before the declaration -- the ordinary way of typing. BOTH answers are offered
     * and neither reference guesses between them: IntelliJ lists the local first, which is what being the
     * preferred one means here.
     */
    void assignedFirst() {
        // FIX: "Create local variable 'count'"
        // FIX: "Create field 'count'"
        count = 5;
        System.out.println(count);
    }

    /** The declared type is whatever the right-hand side turned out to be. */
    void inferredFromTheValue() {
        // FIX: "Create local variable 'greeting'"
        greeting = "hello";
        System.out.println(greeting);
    }

    /**
     * A field generated inside a static method must be static too, or the fix trades "cannot be resolved"
     * for "cannot make a static reference to a non-static field" -- a different error in the same place,
     * which reads as the fix not having worked.
     */
    static void fromStatic() {
        // FIX: "Create field 'shared'"
        shared = 7;
        System.out.println(shared);
    }

    // ── Refused, and these matter more than the conversions ─────────────────────────────────────

    /**
     * NOT OFFERED. A compound assignment READS the variable before it writes it, so declaring it here
     * leaves the file exactly as broken -- with a fix applied, which is worse than none.
     */
    void compound() {
        running += 5;
        System.out.println(running);
    }

    /**
     * NOT OFFERED. A bare use carries no type at all. It would have to come from the parameter it is
     * passed to, which is the same inference the create-method correction refuses for a lambda argument
     * and for the same reason: a declaration that looks finished and still does not fit is worse than
     * nothing.
     */
    void bareUse() {
        System.out.println(neverDeclared);
    }

    /** NOT OFFERED. `void` is not a type a variable can have. */
    static void nothing() { }

    void fromVoid() {
        result = nothing();
        System.out.println(result);
    }
}
