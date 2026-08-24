package com.crystalgui.language.engine.bridge;

import java.util.List;
import java.util.Set;
import java.util.Map;

/**
 * The one thing the host asks an engine to do, across the classloader boundary.
 *
 * <h3>This package is the seam, and its rules are not negotiable</h3>
 *
 * <p>{@code com.crystalgui.language.engine.bridge} is the <b>only</b> package
 * {@code EngineClassLoader} delegates to its parent. That makes it the one place where a type has the
 * same identity on both sides — the host loads this interface, the child loads the implementation, and
 * a cast across the gap works. Everything in here must therefore:</p>
 *
 * <ul>
 *   <li><b>Name no engine type.</b> Not ECJ, not Rhino, not even in a signature's generics. A bridge
 *       type that mentioned {@code ITypeBinding} would need ECJ on the parent's classpath, which is the
 *       whole thing being avoided.</li>
 *   <li><b>Name no type the host cannot already load</b> — the JDK and this package, nothing else.</li>
 *   <li><b>Stay small.</b> Everything added here stops being isolated, because both sides now agree on
 *       it and neither can change it alone.</li>
 * </ul>
 *
 * <h3>Why bytes rather than a loaded class</h3>
 *
 * <p>{@link Result#classes()} hands back raw class files, not {@code Class} objects, because the engine
 * must not decide what loads them. A script's classes belong in a loader the <em>host</em> owns and can
 * drop — that is what makes a re-run replace the previous instance and leave nothing pinned, which is
 * M7's disposal story. An engine that returned live classes would have loaded them into its own loader
 * and tied every script's lifetime to the engine's.</p>
 */
public interface ScriptCompiler {

    /**
     * Compiles one source file.
     *
     * @param className     the fully qualified name the source declares
     * @param source        the source text
     * @param classpath     what the script compiles against — the host's own live classpath, plus
     *                      whatever overlay it wants visible
     * @param releaseLevel  the Java level to compile for. <b>The caller picks this, not the engine</b>:
     *                      bytecode has to load on the host, and only the host knows what it is
     */
    Result compile(String className, String source, List<String> classpath, int releaseLevel);

    /**
     * Types the classpath cannot supply — the live runtime, and the reflective floor under it.
     *
     * <p>On the compiler rather than passed per compile, for the reason {@code ScriptRuntime.restrictTo}
     * gives about the sandbox: this is a property of the <b>deployment</b>, and every compile in it
     * resolves the same way. Passing it per call would make it possible for two compiles in one process
     * to disagree about what exists.</p>
     *
     * <p>A default of {@link TypeBytes#NONE} is the harness's posture and a test's — resolution then goes
     * entirely through the classpath, exactly as it did before this existed. An engine with nothing to
     * resolve beyond files ignores it.</p>
     *
     * @return this, so a host can install it where it obtains the compiler
     */
    default ScriptCompiler resolveAgainst(TypeBytes types) {
        return this;
    }

    /**
     * What came back.
     *
     * <p><b>One thing to watch if this module is ever desugared toward Java 8 bytecode</b>, which
     * {@code core/} is set up for and this module is not: a record needs class-file major 60, so a
     * bridge type spelled as one cannot be loaded by a Java 8 host. It is fine today because
     * {@code language/} compiles to Java 21 throughout and therefore does not run on Java 8 at all —
     * band 8 exists for the JVM the *scripts* run on, not for one this module runs on. The day that
     * changes, this is one of the types that has to change with it.</p>
     */
    record Result(boolean successful, Map<String, byte[]> classes, List<String> messages,
                  Set<String> projectSources) {

        /** Three-argument form for a compile that reached no workspace at all. */
        public Result(boolean successful, Map<String, byte[]> classes, List<String> messages) {
            this(successful, classes, messages, Set.of());
        }

        /**
         * Every PROJECT type this compile pulled in from the workspace, by qualified name.
         *
         * <p>What a cache has to know and cannot work out. A compiled script is keyed on the text of the
         * file the author ran; a sibling it resolved through is invisible to that key, so editing the
         * sibling would leave the cache serving bytes compiled against the old one — the run would not
         * change, which reads as the edit not having been picked up rather than as a caching decision.</p>
         *
         * <p><b>Names, not texts.</b> The host hashes the current source itself, at both ends of the
         * comparison, so the two are always measured the same way — and nothing here retains a copy of
         * every file a script happened to touch.</p>
         *
         * <p>Empty for a compile with no workspace behind it, which is every test, the harness's own
         * fixtures and a plain JVM. Such a script is cached on its own text alone, exactly as before.</p>
         */
        @Override
        public Set<String> projectSources() {
            return projectSources;
        }

        /** Whether bytecode was produced. False means {@link #messages()} says why. */
        @Override
        public boolean successful() {
            return successful;
        }

        /**
         * Every class file produced, by binary name.
         *
         * <p>More than one, routinely: a nested class, an anonymous class, a lambda's synthetic
         * carrier. A loader that took only the named one would fail on the first inner class with a
         * {@code NoClassDefFoundError} pointing at a name the author never wrote.</p>
         */
        @Override
        public Map<String, byte[]> classes() {
            return classes;
        }

        /**
         * Compiler output, one entry per line.
         *
         * <p>Deliberately unparsed. Turning these into {@code Diagnostic}s with real ranges is M6's
         * job and needs the structured compiler API rather than this one's text — promising structure
         * here would mean parsing prose, which is exactly the shape that breaks on the next ECJ.</p>
         */
        @Override
        public List<String> messages() {
            return messages;
        }
    }
}
