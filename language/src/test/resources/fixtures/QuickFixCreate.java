/*
 * QUICK-FIX FIXTURE -- create from usage, one site per inference.
 *
 * See QuickFixUnused.java for what the `// FIX:` lines mean: they are assertions read by
 * FixtureFilesTest, not comments. Each call below names a method that does not exist, and Alt+Enter must
 * offer to create it -- typed from the arguments, returning what the use site expects, into the type the
 * receiver names (only ever a type declared in THIS file: anything else would be a second file).
 */
import java.util.List;

public class QuickFixCreate {

    void bareCall(String label) {
        // FIX: "Create method 'helper(int, String)'"
        helper(1, label);
    }

    int intoAnotherTypeInThisFile(QuickFixCreateHelper h, int seed) {
        // FIX: "Create method 'compute(int)'"
        return h.compute(seed);
    }

    void staticCall() {
        // FIX: "Create method 'make()'"
        QuickFixCreateHelper.make();
    }

    void underACondition() {
        // FIX: "Create method 'ready()'"
        if (ready()) { }
    }

    void genericParameter(List<String> names) {
        // FIX: "Create method 'take(List)'"
        take(names);
    }
}

class QuickFixCreateHelper {
}
