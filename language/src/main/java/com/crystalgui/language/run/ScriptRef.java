package com.crystalgui.language.run;

import com.crystalgui.fs.Resource;

/**
 * Which script is running — the file it came from, and the class it compiled to.
 *
 * <h3>Why both, and not either alone</h3>
 *
 * <p>The <b>file</b> is the identity everything the user sees is keyed by: the rail, the filter, and the
 * running indicator, which is a {@code FileDecorationProvider} and can only decorate a resource.</p>
 *
 * <p>The <b>binary name</b> is what {@link ScriptOutput} matches stack frames against to find the line
 * that produced a message. It cannot be derived from the file — the prelude wraps a snippet in a
 * generated type, so the class a script compiles to routinely has neither the file's name nor its
 * package.</p>
 */
public record ScriptRef(Resource file, String binaryName) {

    /** What the console and the rail label it — the file's own name. */
    public String name() {
        return file.name();
    }

    /**
     * Whether {@code className} is this script's own code.
     *
     * <p>Matches nested and synthetic classes too, because a lambda inside a script compiles to
     * {@code Script$$Lambda$14} and a message printed from inside one is still the script's — reporting
     * it as belonging to nobody would put exactly the output people wrap in lambdas outside the
     * collapse rule.</p>
     */
    public boolean owns(String className) {
        if (className == null) return false;
        if (className.equals(binaryName)) return true;
        return className.startsWith(binaryName) && className.length() > binaryName.length()
                && (className.charAt(binaryName.length()) == '$');
    }
}
