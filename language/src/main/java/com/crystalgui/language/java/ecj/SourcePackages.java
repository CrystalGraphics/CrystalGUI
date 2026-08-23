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

    /**
     * The package this unit actually belongs to — <b>the path's answer where there is one</b>.
     *
     * <h3>Which of the two authorities wins, and why it depends</h3>
     *
     * <p>There are two claims about a unit's package: the {@code package} line the author wrote, and the
     * directory the file sits in. They are the same claim for a well-formed project file and they differ
     * for two very different reasons.</p>
     *
     * <p>A <b>script</b> has no directory to speak of — it is compiled under a generated class name and
     * whatever it declares is the only fact available, which is what {@code InMemoryUnit} was written to
     * respect. Such a name arrives here UNQUALIFIED, and the declaration is taken.</p>
     *
     * <p>A file under a declared <b>source root</b> arrives QUALIFIED, because {@code JavaLanguage} derived
     * its name from the path. There the path is authoritative: it is what the index used to name the type,
     * so a {@code package} line that disagrees would have the file resolving under one name and compiling
     * under another. Handing ECJ the path's package is what turns that into javac's own diagnostic on the
     * package line — M15 S4's "a package line disagreeing with its directory becomes a diagnostic".</p>
     *
     * <p><b>Both have to be non-empty for the path to win.</b> A qualified name whose source declares no
     * package at all is a decompiled or generated unit rather than a disagreement, and imposing a package
     * on one would report an error against text nobody wrote. The spec sentence is about a package line
     * that disagrees; where there is no line there is nothing to disagree with.</p>
     */
    static String effectivePackage(String className, String source) {
        String declared = declaredPackage(source);
        String fromName = packageOf(className);
        return fromName.isEmpty() || declared.isEmpty() ? declared : fromName;
    }

    /** The package part of a qualified name, or {@code ""} when it carries none. */
    private static String packageOf(String className) {
        if (className == null) return "";
        int lastDot = className.lastIndexOf('.');
        return lastDot < 0 ? "" : className.substring(0, lastDot);
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

        String effective = effectivePackage(className, source);
        return effective.isEmpty() ? simple + ".java"
                : effective.replace('.', '/') + "/" + simple + ".java";
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
