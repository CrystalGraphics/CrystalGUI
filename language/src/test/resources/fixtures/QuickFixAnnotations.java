/*
 * QUICK-FIX FIXTURE -- annotations that are on a declaration and should not be.
 *
 * Open this in the GL harness (it is installed into the scratch workspace by
 * `./gradlew :language:installHarnessFixtures`), put the caret on a red squiggle and press Alt+Enter.
 * Every `// FIX:` line below states, verbatim, the action that must appear on the line beneath it.
 *
 * THIS FILE DOES NOT COMPILE, and cannot: every site is an ERROR reported with no configuration, which
 * is also why there is no severity table to keep in step for this family.
 *
 * REMOVAL ONLY, AND DELIBERATELY. The catalogue pairs each of these with its opposite -- add @Override,
 * add @Deprecated -- and neither insertion has a diagnostic to hang off: MissingOverrideAnnotation is
 * switched off on purpose (an override is a relationship, not a defect) and the @Deprecated family fires
 * only on a Javadoc mismatch. So there is nothing here to add, only things to take away.
 */
public class QuickFixAnnotations {

    interface Greeter {
        String greet(String name);
    }

    /** The common one: the annotation is right and the signature drifted out from under it. */
    static class Drifted implements Greeter {

        @Override
        public String greet(String name) {
            return "hello " + name;
        }

        // FIX: "Remove '@Override'"
        @Override
        public String greet(String first, String last) {
            return "hello " + first + " " + last;
        }
    }

    /** No supertype at all, which is the same problem with a different cause and the same repair. */
    static class Standalone {
        // FIX: "Remove '@Override'"
        @Override
        void alone() { }
    }

    /**
     * A QUALIFIED annotation is the same annotation. Legal, rare, and exactly the spelling a simple-name
     * comparison misses -- so the match is on the last segment of the type name.
     */
    static class Qualified {
        // FIX: "Remove '@Override'"
        @java.lang.Override
        void qualified() { }
    }

    /**
     * @SafeVarargs on a method with no varargs parameter. Read the constant names carefully: both
     * SafeVarargs problems fire where the annotation is WRONGLY APPLIED, never where it is missing.
     */
    static class FixedArity {
        // FIX: "Remove '@SafeVarargs'"
        @SafeVarargs
        static void notVarargs(String only) {
            System.out.println(only);
        }
    }

    /**
     * And on a non-final instance method: it can be overridden by one that is not safe, so the promise
     * is not this author's to make.
     */
    static class Overridable {
        // FIX: "Remove '@SafeVarargs'"
        @SafeVarargs
        void promise(String... items) {
            System.out.println(items.length);
        }
    }

    /**
     * NOT OFFERED. An @Override that genuinely overrides is not a problem, so nothing is reported and
     * there is nothing to take away. Present because a removal fix that fires on a correct annotation is
     * the failure mode with no diagnostic to notice it.
     */
    static class Correct implements Greeter {
        @Override
        public String greet(String name) {
            return name;
        }
    }
}
