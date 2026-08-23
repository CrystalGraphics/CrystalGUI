package com.crystalgui.text.lang;

import javax.annotation.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * "What does this project itself declare, and what is its text?" — the seam a compiler resolves
 * cross-file references through.
 *
 * <h3>Why this exists</h3>
 *
 * <p>Every project file is compiled in isolation today: the analyser is handed one source string and a
 * classpath of jars, so {@code Main.java} cannot see a type declared in {@code Viewer.java}. The compiler
 * asks its name environment for types it does not have, and the environment has only ever been able to
 * answer with the classpath. This is the other half of that answer.</p>
 *
 * <h3>JDK types only, and that is a hard rule rather than a preference</h3>
 *
 * <p>The implementation lives in {@code core/} and the caller is <b>inside the engine band</b>, where the
 * class loader is child-first over everything except the JDK, the bridge package and
 * {@code com.crystalgui.text.*} — which is why this interface is in that package tree and speaks in
 * {@code String}. A {@code CgPath} here would be silently <em>redefined</em> inside the band, and the two
 * copies would stop being assignable; §15.5 A shipped exactly that bug, and its symptom was not a crash
 * but a feature that was quietly inert for a release.</p>
 *
 * <h3>Null is "not yet", not "no"</h3>
 *
 * <p>An index is filled in the background — the workspace crawl is asynchronous and so is reading a file
 * that nobody has open. {@link #sourceOf} therefore answers null both for a name the project does not
 * declare and for one it declares but has not read yet, and the caller cannot tell them apart. That is
 * survivable because it is the same shape the rest of this stack already degrades through: the analysis
 * resolves without it, and the next one — after the read lands — resolves with it.</p>
 */
public interface ProjectSources {

    /**
     * The current text of the project file declaring {@code qualifiedName}, or null.
     *
     * <p><b>Current, not saved.</b> An open editor's buffer is the truth about what the code says; a
     * compiler resolving against the file on disk would report errors about text the author has already
     * fixed, and would do it in the one place they are looking.</p>
     *
     * <p>Called from the analysis thread, and must not block: a miss is an answer.</p>
     */
    @Nullable
    String sourceOf(String qualifiedName);

    /**
     * Whether this project declares anything at or under {@code packageName}.
     *
     * <p><b>At or under</b>, and the distinction is the whole reason this is a method rather than a
     * lookup. ECJ asks about <em>each segment of a qualified name before it looks the type up</em> — so
     * resolving {@code com.example.Main} asks about {@code com}, then {@code com.example}. A package that
     * declares no types directly, only sub-packages, must still answer true or the name never resolves.
     * §15.5 records the same trap from the other direction, where delegating {@code isPackage} to a
     * classloader stopped every Minecraft type resolving on an obfuscated host.</p>
     */
    boolean declaresPackage(String packageName);

    /** Every type declared directly in {@code packageName}, simple names only. Never null. */
    List<String> typesIn(String packageName);

    /** A project that declares nothing — what a host with no workspace open has. */
    ProjectSources NONE = new ProjectSources() {
        @Override
        public String sourceOf(String qualifiedName) {
            return null;
        }

        @Override
        public boolean declaresPackage(String packageName) {
            return false;
        }

        @Override
        public List<String> typesIn(String packageName) {
            return Collections.emptyList();
        }
    };
}
