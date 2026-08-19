/*
 * QUICK-FIX FIXTURE -- a concrete type that has not implemented everything it inherited.
 *
 * Open this in the GL harness (it is installed into the scratch workspace by
 * `./gradlew :language:installHarnessFixtures`), put the caret on a red squiggle and press Alt+Enter.
 * Every `// FIX:` line below states, verbatim, the action that must appear on the line beneath it.
 *
 * THIS FILE DOES NOT COMPILE, and cannot: every site is an ERROR reported with no configuration.
 *
 * THE ONE MEASURED GAP THAT GREW WHEN THE CLASSPATH RESOLVED. The coverage probe found this unanswered
 * at 28 occurrences over 8 files with an empty classpath and at 32 over 11 with the repository's own
 * classes on it. Every other uncovered row shrank -- most of them were "refers to the missing type X",
 * which is the shape of an unresolvable classpath rather than a gap. Going UP is what separates the two.
 *
 * ECJ REPORTS THIS ONCE PER MISSING METHOD, all of them on the type's name. So a class missing two
 * methods is two problems and must still be ONE row in the popup, offering to write both.
 */
public class QuickFixImplement {

    interface Greeter {
        String greet(String name);

        int count();
    }

    /**
     * Two missing methods, one action. The generated stubs keep the interface's own PARAMETER NAMES,
     * which a binding does not carry -- they are not in bytecode unless the class was compiled with
     * -parameters -- but which are sitting in the tree whenever the interface is declared beside the
     * class, as a script's interfaces overwhelmingly are.
     */
    // FIX: "Implement 2 methods"
    static class Silent implements Greeter {
    }

    /** Only what is actually missing: `count` is written, so only `greet` is generated. */
    // FIX: "Implement method 'greet'"
    static class Counts implements Greeter {
        @Override
        public int count() {
            return 1;
        }
    }

    interface Box<T> {
        T get();
    }

    /**
     * A generic SUPERTYPE is not a problem -- `Box<String>` hands over a method binding whose types are
     * already substituted, so `String get()` is perfectly spellable.
     */
    // FIX: "Implement method 'get'"
    static class Strings implements Box<String> {
    }

    interface Factory {
        <T> T make();

        int size();
    }

    /**
     * NOT OFFERED, AND NOT PARTLY OFFERED. `<T> T make()` declares the type variable itself and has no
     * source form to write. Implementing `size()` and stopping would leave a class that still does not
     * compile and now looks finished, which is worse than an offer that never appeared.
     */
    static class Refused implements Factory {
    }

    interface Loader {
        NoSuchTypeAnywhere load();
    }

    /*
     * NOT OFFERED EITHER, and this one the corpus found rather than anybody's imagination. A RECOVERED
     * binding is JDT's stand-in for a name it could not resolve, and it still answers getQualifiedName()
     * -- so a stub built from one is written against a type that does not exist, trading "must implement"
     * for one unresolvable reference per parameter. Five real files gained between six and sixteen errors
     * each from a version of this fix that was correct in every fixture, because a fixture is written by
     * somebody who knows what the types are.
     */
    static class Loads implements Loader {
    }
}
