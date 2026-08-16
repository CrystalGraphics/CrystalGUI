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
     * NOT OFFERED. The same TypeMismatch id, and a cast here is IllegalCast -- one error traded for
     * another. This is the whole safety argument for the family, so it is the case worth trying by hand.
     */
    void unrelated(String s) {
        Integer n = s;
        System.out.println(n);
    }

    /**
     * NOT OFFERED, and for a different reason: an argument mismatch is ParameterMismatch reported on the
     * METHOD NAME, not TypeMismatch on the argument. Working out which argument is wrong means redoing
     * overload resolution, so it is a separate row that is not written yet.
     */
    void take(Dog d) { }

    void argument(Animal a) {
        take(a);
    }
}
