package com.crystalgui.language.js.rhino;

import com.crystalgui.language.engine.bridge.JsExecutor;

/**
 * The two class loaders this engine reaches classes through — <b>said once</b>.
 *
 * <h3>Why the host's loader, and why it cannot simply be ours</h3>
 *
 * <p>A class the script names through {@code Java.type} must resolve to the <b>host's</b> definition, or a
 * binding the host handed over and a class the script looked up are two different types with one name. The
 * classes in this package are defined by the band loader, which is child-first so that it can see Rhino —
 * so <em>its</em> answer is the engine's view of the world rather than the application's. The host's loader
 * is found from the {@link JsExecutor} interface, which is parent-first by construction and therefore
 * defined by whoever the host is; in a test that puts Rhino on the plain classpath the two are one and the
 * same, which is also right.</p>
 *
 * <h3>Rhino needs one more thing than the host has</h3>
 *
 * <p>{@code Context.setApplicationClassLoader} refuses a loader that cannot resolve Rhino's own classes
 * ({@code "Loader can not resolve Rhino classes"}), and the host by design cannot. So {@link #APPLICATION}
 * is the host's loader with exactly one addition: {@code org.mozilla.*} from the band, reached only for
 * what the host lacks — a script must not be able to name a class that exists solely on the engine's side
 * of the bridge.</p>
 *
 * <h3>One definition, because two of them can disagree about a class</h3>
 *
 * <p>The executor and the interop resolver each had their own, spelled differently. The editor's "does this
 * class exist" and the runtime's "load this class" are then two questions with two answers, and a name that
 * resolves in one and not the other is reported as neither missing nor present — it simply behaves
 * differently in the popup and at run time.</p>
 */
final class JsLoaders {

    private JsLoaders() {
    }

    /** Whose classes a script sees: the application's. */
    static final ClassLoader HOST = JsExecutor.class.getClassLoader();

    /** The host's, plus Rhino's own package. @see the class note */
    static final ClassLoader APPLICATION = new ClassLoader(HOST) {
        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (name.startsWith("org.mozilla.")) return JsLoaders.class.getClassLoader().loadClass(name);
            throw new ClassNotFoundException(name);
        }
    };

    /**
     * A class by binary name, or null — <b>never initialised</b>, and retried in the nested spelling.
     *
     * <p>Resolving a name in an editor must not run a static initialiser: that is somebody's code, executed
     * because the caret moved.</p>
     *
     * <p>And {@code java.util.Map.Entry} is what a script writes and what the Java engine's probe resolves,
     * while the JVM knows it only as {@code java.util.Map$Entry} — so the spelling that reads correctly in
     * the editor threw "no such class" the moment it ran. Each dot is tried as a {@code $} from the right,
     * which is Nashorn's own rule for {@code Java.type}.</p>
     */
    static Class<?> load(String binaryName) {
        if (binaryName == null || binaryName.isEmpty()) return null;
        Class<?> direct = loadExactly(binaryName);
        if (direct != null) return direct;
        for (int dot = binaryName.lastIndexOf('.'); dot > 0; dot = binaryName.lastIndexOf('.', dot - 1)) {
            Class<?> nested = loadExactly(binaryName.substring(0, dot) + '$' + binaryName.substring(dot + 1));
            if (nested != null) return nested;
        }
        return null;
    }

    private static Class<?> loadExactly(String binaryName) {
        try {
            return Class.forName(binaryName, false, HOST);
        } catch (ClassNotFoundException | LinkageError absent) {
            return null;
        }
    }
}
