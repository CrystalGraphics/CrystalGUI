/*
 * QUICK-FIX FIXTURE -- unresolved references, one site per correction.
 *
 * See QuickFixUnused.java for what the `// FIX:` lines mean: they are assertions read by
 * FixtureFilesTest, not comments.
 *
 * `List` is deliberately NOT imported. It resolves to more than one candidate on a normal classpath
 * (java.util.List and java.awt.List), which is the case the "More actions..." list exists for and the
 * reason no import candidate is ever marked preferred -- defaulting to whichever the index happened to
 * return first is a coin toss that edits your file.
 */
public class QuickFixImports {

    // FIX: "Import 'java.util.List'"
    List<String> names;

    /** A second use of the same unresolved name, so the fix is offered wherever it is reported. */
    List<String> alsoNames() {
        return names;
    }
}
