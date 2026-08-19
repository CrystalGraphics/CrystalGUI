/*
 * QUICK-FIX FIXTURE -- "Cast expression to '...'", and the case that looks identical and must be refused.
 *
 * Open this in the GL harness (it is installed into the scratch workspace by
 * `./gradlew :language:installHarnessFixtures`), put the caret on a red squiggle and press Alt+Enter.
 * Every `// FIX:` line below states, verbatim, the action that must appear on the line beneath it.
 *
 * THIS FILE DOES NOT COMPILE, and cannot: every site here is a type mismatch, which is an ERROR reported
 * with no configuration. That is also why there is no severity table to keep in step for this family.
 *
 * The refused cases carry no `// FIX:` line. They matter more than the conversions do: ECJ reports the
 * SAME problem id for a downcast that is exactly right and for an assignment between unrelated types
 * where a cast is a second error rather than a repair, so one guard is all that separates them.
 */
public class QuickFixCast {

    static class Animal { }

    static final class Dog extends Animal { }

    /** A downcast the author knows is safe and the compiler cannot prove. */
    void initialiser(Animal a) {
        // FIX: "Cast expression to 'Dog'"
        Dog d = a;
        System.out.println(d);
    }

    /** The same, arriving through an assignment rather than a declaration. */
    void assignment(Animal a) {
        Dog d = null;
        // FIX: "Cast expression to 'Dog'"
        d = a;
        System.out.println(d);
    }

    /** A `return` is a second problem id and the same repair. */
    Dog returned(Animal a) {
        // FIX: "Cast expression to 'Dog'"
        return a;
    }

    /**
     * A looser-binding operand is wrapped: a cast is a unary operator, so `(byte) flag ? 1 : 2` would cast
     * the condition rather than the value and mean something else entirely.
     */
    void looserOperand(boolean flag) {
        // FIX: "Cast expression to 'byte'"
        byte b = flag ? 1 : 2;
        System.out.println(b);
    }

    /**
     * NO CAST HERE. Same TypeMismatch id, and a cast would be IllegalCast -- one error traded for another.
     * The value is what it is, so the DECLARATION is the part that is wrong, and that is what is offered.
     *
     * IntelliJ also offers "Change parameter 's' type to 'Integer'". That one edits a method SIGNATURE, so
     * every caller has to still compile afterwards -- which this engine cannot see for a script's method,
     * so it is deliberately not offered.
     */
    void unrelated(String s) {
        // FIX: "Change variable 'n' type to 'String'"
        Integer n = s;
        System.out.println(n);
    }

    /**
     * An argument mismatch is a different problem -- ParameterMismatch, reported on the METHOD NAME, so
     * neither the id nor the range says which argument is wrong. That is worked out from the one method of
     * that name and arity.
     */
    void take(Dog d) { }

    void argument(Animal a) {
        // FIX: "Cast argument to 'Dog'"
        take(a);
    }

    /** Only the one that is wrong, wherever it sits in the list. */
    void takeTwo(int n, Dog d) { }

    void secondArgument(Animal a) {
        // FIX: "Cast argument to 'Dog'"
        takeTwo(1, a);
    }

    /**
     * NOT OFFERED. Two overloads of the same arity, and no way to know which was meant -- ECJ names one in
     * its message, but that is its guess rendered for a person rather than an answer that can be read. A
     * cast to the wrong one compiles and calls the wrong method.
     */
    void ambiguous(Dog d) { }

    void ambiguous(String s) { }

    void overloaded(Animal a) {
        ambiguous(a);
    }
}
