package com.crystalgui.document;

import com.crystalgui.fs.CgPath;

import javax.annotation.Nullable;

/**
 * Where a <b>New ▸</b> would land, which is the whole of what a {@link NewDocumentKind} is asked about.
 *
 * <pre>{@code
 * .where(at -> at.sourceRootHolds("java"))      // a Java class, only in the java tree
 * .where(NewDocumentContext::outsideSourceRoots) // a shader graph, only where sources are not
 * }</pre>
 *
 * <p>Resolved once per click and handed to every registered kind. It carries no workbench, no tree and
 * no selection — only the answer they produced — so a kind declared in a jar that has never heard of the
 * explorer can still say where it belongs.</p>
 *
 * @param directory  the directory the new thing would be created in
 * @param sourceRoot the declared source root containing {@code directory}, or null outside every one.
 *                   The root itself counts as being in it — see {@code SourceRoots.rootOf}
 */
public record NewDocumentContext(CgPath directory, @Nullable String sourceRoot) {

    /** Whether this is a source root, or a package inside one. */
    public boolean inSourceRoot() {
        return sourceRoot != null;
    }

    /** The inverse, named so a {@code where} can read as a method reference. */
    public boolean outsideSourceRoots() {
        return sourceRoot == null;
    }

    /**
     * Whether the source root here is the one for {@code language} — {@code "java"}, {@code "js"}.
     *
     * <p>Read off the root's own last segment, because that is all a root is: {@code SourceRoots.CONVENTION}
     * is literally {@code src/main/java} and {@code src/main/js}, and nothing carries a language beside
     * them. A root named after neither answers no to both, which is what leaves a project's own
     * {@code src/main/resources} with the plain catalogue rather than a guess.</p>
     */
    public boolean sourceRootHolds(String language) {
        if (sourceRoot == null || language == null) return false;
        int lastSlash = sourceRoot.lastIndexOf('/');
        return (lastSlash < 0 ? sourceRoot : sourceRoot.substring(lastSlash + 1)).equals(language);
    }
}
