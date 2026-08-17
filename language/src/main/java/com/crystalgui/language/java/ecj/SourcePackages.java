package com.crystalgui.language.java.ecj;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The compilation unit's real name — the one its own {@code package} declaration implies.
 *
 * <h3>The file is the authority on where it lives</h3>
 *
 * <p>Both the analyser and the compiler have to tell ECJ what unit they are handing it, and the obvious
 * answer — the file's stem, {@code Main} — is wrong for any file with a package. ECJ checks the two
 * against each other and reports <i>"The declared package does not match the expected package"</i> on
 * line 1: a diagnostic about the tool's own bookkeeping, sitting on the author's first line, saying
 * nothing they can act on and nothing they can fix.</p>
 *
 * <p>It is not hypothetical. The harness scratch project has a {@code Main.java} that declares
 * {@code package com.crystalgui.language.grammar}, because it was copied out of this repository — which
 * is exactly what people do with scratch files. Opening it would have shown an error immediately, on a
 * file that compiles perfectly well, and the natural conclusion is that the analyser is broken.</p>
 *
 * <p>Reading the declaration instead makes the two agree by construction, whatever the file says and
 * however the author changes it.</p>
 */
public final class SourcePackages {

    /**
     * A package declaration, anchored to a line start.
     *
     * <p>Anchored so the word inside a comment or a string is not mistaken for one — the same
     * conservatism {@code ScriptPrelude}'s import hoisting uses, and for the same reason: guessing
     * wrong here produces a mismatch error rather than a subtle misbehaviour, but it produces it on
     * every analysis of an innocent file.</p>
     */
    private static final Pattern PACKAGE =
            Pattern.compile("(?m)^[ \\t]*package\\s+([\\w.]+)\\s*;");

    private SourcePackages() {
    }

    /** The package {@code source} declares, or {@code ""} for the default package. */
    static String declaredPackage(String source) {
        if (source == null) return "";
        Matcher matcher = PACKAGE.matcher(stripComments(source));
        return matcher.find() ? matcher.group(1) : "";
    }

    /**
     * The path ECJ should be told this unit lives at — {@code a/b/Main.java}.
     *
     * @param className the caller's name for the unit, qualified or not. Only its <b>simple</b> part is
     *                  used, because the package comes from the source: a caller that derived the name
     *                  from a file path has no idea what package the file declares, and if it did guess
     *                  one, this is where the two would disagree
     */
    static String unitPath(String className, String source) {
        String simple = className == null || className.isEmpty() ? "Script" : className;
        int lastDot = simple.lastIndexOf('.');
        if (lastDot >= 0) simple = simple.substring(lastDot + 1);

        String declared = declaredPackage(source);
        return declared.isEmpty() ? simple + ".java"
                : declared.replace('.', '/') + "/" + simple + ".java";
    }

    /** The binary name a compiled unit will have — what a loader must be asked for. */
    public static String binaryName(String className, String source) {
        String path = unitPath(className, source);
        return path.substring(0, path.length() - ".java".length()).replace('/', '.');
    }

    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }
}
