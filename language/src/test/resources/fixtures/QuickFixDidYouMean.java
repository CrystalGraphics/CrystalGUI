/*
 * QUICK-FIX FIXTURE -- "did you mean", one site per kind of near miss.
 *
 * See QuickFixUnused.java for what the `// FIX:` lines mean: they are assertions read by
 * FixtureFilesTest, not comments. Every name below is a keystroke or two from something real, and the
 * popup should offer the real thing -- ranked closest first, capped at five, never applied for you.
 *
 * In the harness the type candidates come from the real classpath index, so `Strin` will also offer
 * whatever else on your classpath is that close; the annotation names only the one that must be there.
 */
public class QuickFixDidYouMean {

    // FIX: "Change to 'String'"
    Strin misspeltType;

    // FIX: "Change to 'Helper'"
    Helpr declaredInThisFile;

    int total;

    int misspeltMethodOnReceiver(String s) {
        // FIX: "Change to 'length()'"
        return s.lenght();
    }

    void misspeltUnqualifiedMethod() {
        // FIX: "Change to 'helper()'"
        helpr();
    }

    int misspeltLocal() {
        int count = 1;
        // FIX: "Change to 'count'"
        return cont;
    }

    int misspeltField(QuickFixDidYouMean other) {
        // FIX: "Change to 'total'"
        return other.totl;
    }

    void helper() { }
}

class Helper { }
