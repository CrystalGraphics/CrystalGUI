/*
 * QUICK-FIX FIXTURE -- "Replace with lambda", and the far longer list of shapes that must be refused.
 *
 * Open this in the GL harness (it is installed into the scratch workspace by
 * `./gradlew :language:installHarnessFixtures`), put the caret on a `new ...()` HEADER and press
 * Alt+Enter. Every `// FIX:` line below states, verbatim, the action that must appear on the line
 * beneath it.
 *
 * NOTHING IN THIS FILE IS WRONG, and it still has six findings. No compiler reports a convertible
 * anonymous class -- this engine does, from the same judgement the fix uses, because a refactor nobody
 * can see is a refactor nobody applies. They are drawn FADED rather than underlined, which is the
 * treatment the unused family gets and IntelliJ's own for this inspection.
 *
 * That shared judgement is why the refusals below matter more than the conversions: they are what stops
 * a mark promising a conversion the popup then declines to make.
 *
 * The refused cases carry no `// FIX:` line, so the checker asserts nothing about them; they are here
 * for a person to try, and each says what would go wrong.
 */
import java.util.Comparator;

public class QuickFixLambda {

    /** Two statements, so the braces stay -- and the author's own indentation with them. */
    Comparator<String> multiStatement() {
        // FIX: "Replace with lambda"
        return new Comparator<String>() {
            @Override
            public int compare(String left, String right) {
                int byLength = Integer.compare(left.length(), right.length());
                return byLength != 0 ? byLength : left.compareTo(right);
            }
        };
    }

    /** One `return`, so it collapses to the expression form. */
    Comparator<String> singleReturn() {
        // FIX: "Replace with lambda"
        return new Comparator<String>() {
            @Override
            public int compare(String left, String right) {
                return left.compareTo(right);
            }
        };
    }

    /** A void interface collapses the same way, through its single expression statement. */
    Runnable singleStatement() {
        // FIX: "Replace with lambda"
        return new Runnable() {
            @Override
            public void run() {
                System.out.println("go");
            }
        };
    }

    /** The parameter would shadow `left`, so it is renamed rather than refused. */
    Comparator<String> shadowsAParameter() {
        String left = "outer";
        System.out.println(left);
        // FIX: "Replace with lambda"
        return new Comparator<String>() {
            @Override
            public int compare(String left, String right) {
                return left.compareTo(right);
            }
        };
    }

    /** So is a body LOCAL that would shadow -- the same failure, and neither reference documents it. */
    Comparator<String> shadowsALocal() {
        int tally = 1;
        System.out.println(tally);
        // FIX: "Replace with lambda"
        return new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                int tally = 2;
                return tally;
            }
        };
    }

    /** A qualified `QuickFixLambda.this` already meant the enclosing instance, so it converts. */
    int rank = 1;

    Comparator<String> qualifiedThis() {
        // FIX: "Replace with lambda"
        return new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return QuickFixLambda.this.rank;
            }
        };
    }

    // ── REFUSED, each for its own reason ────────────────────────────────────────────────────────

    interface Two {
        void a();

        void b();
    }

    /** Not a functional interface: two abstract methods, so there is nothing for a lambda to be. */
    Two twoAbstractMethods() {
        return new Two() {
            public void a() { }

            public void b() { }
        };
    }

    interface Maker {
        <T> T make(Class<T> type);
    }

    /** "Illegal lambda expression: Method make ... is generic" -- a lambda declares no type parameters. */
    Maker genericMethod() {
        return new Maker() {
            public <T> T make(Class<T> type) {
                return null;
            }
        };
    }

    /** A lambda has nowhere to keep state. */
    Comparator<String> hasAField() {
        return new Comparator<String>() {
            private int calls;

            @Override
            public int compare(String a, String b) {
                return ++calls;
            }
        };
    }

    /** Unqualified `this` would stop meaning the anonymous object and start meaning the enclosing one. */
    Comparator<String> unqualifiedThis() {
        return new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return this.hashCode();
            }
        };
    }

    /** A lambda has no name, so it cannot call itself. */
    Comparator<String> callsItself() {
        return new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return a.isEmpty() ? 0 : compare(b, a);
            }
        };
    }

    /** The doc would be dropped, and a lambda has nowhere to put one. */
    Comparator<String> documented() {
        return new Comparator<String>() {
            /** Orders by natural order. */
            @Override
            public int compare(String a, String b) {
                return a.compareTo(b);
            }
        };
    }

    /** `synchronized` cannot be expressed on a lambda. */
    Comparator<String> synchronizedMethod() {
        return new Comparator<String>() {
            @Override
            public synchronized int compare(String a, String b) {
                return 0;
            }
        };
    }

    /** `@Deprecated` survives compilation; only `@Override` is safe to drop. */
    Comparator<String> retainedAnnotation() {
        return new Comparator<String>() {
            @Deprecated
            @Override
            public int compare(String a, String b) {
                return 0;
            }
        };
    }

    /** A receiver has no target type, and a lambda is a poly expression that needs one. */
    void receiverPosition() {
        new Runnable() {
            @Override
            public void run() { }
        }.run();
    }
}
