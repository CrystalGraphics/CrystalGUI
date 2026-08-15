/*
 * QUICK-FIX FIXTURE -- unhandled checked exceptions, one site per shape.
 *
 * See QuickFixUnused.java for what the `// FIX:` lines mean: they are assertions read by
 * FixtureFilesTest, not comments. Every unhandled exception gets the same pair -- add it to `throws`, or
 * surround the statement -- and this file has one site per shape the surround handles differently.
 */
import java.io.FileReader;

public class QuickFixExceptions {

    void plainStatement() {
        // FIX: "Add 'FileNotFoundException' to throws"
        // FIX: "Surround with try/catch"
        new FileReader("x");
    }

    /** The variable is read below, so surrounding SPLITS the declaration rather than wrapping it. */
    int declarationUsedBelow() {
        // FIX: "Surround with try/catch"
        FileReader reader = new FileReader("x");
        return reader.hashCode();
    }

    /** Two exceptions, one a subtype of the other -- one catch, of the supertype. */
    void reducedToTheSupertype() {
        // FIX: "Surround with try/catch"
        new FileReader("x").read();
    }

    /** Three unrelated exceptions -- one multi-catch. */
    void unrelatedExceptions() {
        // FIX: "Add 'ClassNotFoundException', 'InstantiationException', 'IllegalAccessException' to throws"
        Class.forName("x").newInstance();
    }

    /** Inside a lambda `throws` is refused (it would go on the wrong callable); surround still works. */
    void insideALambda() {
        Runnable r = () -> {
            // FIX: "Surround with try/catch"
            new FileReader("x");
        };
    }
}
