package com.crystalgui.language.js.rhino;

import java.util.function.Supplier;

/**
 * <b>Every entry into Rhino runs with the engine loader on the thread.</b> One rule, one place.
 *
 * <h3>What goes wrong without it, and why it is so hard to see</h3>
 *
 * <p>Rhino 1.8+ resolves its regular-expression engine through {@code ServiceLoader} on
 * {@code org.mozilla.javascript.RegExpLoader}. The no-argument {@code ServiceLoader.load} reads the
 * <b>thread's</b> context classloader rather than the caller's — and for a child-first engine loader
 * that is the host's, which cannot see the service file inside the band jar. So the lookup finds
 * nothing.</p>
 *
 * <p><b>And the answer is cached at class initialisation</b>, which is what makes this a rule about
 * <em>every</em> entry point rather than about the one that evaluates a regex. Whichever call touches a
 * Rhino class first decides the answer for the life of the loader; every later call inherits it, however
 * carefully that later call sets the loader. The capability probe hit this twice — once by swapping the
 * loader inside its own {@code enter()} (too late, a static field had been read a line earlier), and
 * once when the <em>analyser</em> initialised {@code Context} before the <em>executor</em> ever ran.
 * Two different classes, one shared loader, and the second one paid for the first one's ordering.</p>
 *
 * <p>The symptom is not a load error and does not mention classloaders. It is
 * {@code "Regular expressions are not available."} thrown from the first regular expression a script
 * evaluates — on bands 11 and 17 only, because band 8's older Rhino predates the lookup and works
 * either way. A developer on Java 8 would never reproduce it.</p>
 *
 * <h3>Why this class's own loader is the right one</h3>
 *
 * <p>This class is defined <em>by</em> the {@code EngineClassLoader} — that is what it means for the
 * adapters to live on the far side of the bridge — so {@code RhinoThread.class.getClassLoader()} is the
 * engine loader by construction. Naming it that way rather than accepting one as a parameter means no
 * caller can pass the wrong loader, and there is nothing to keep in step.</p>
 *
 * <h3>The restore matters as much as the install</h3>
 *
 * <p>These run on threads the host owns — the UI thread during an analysis, a script thread during a
 * run. Leaving an engine loader on a borrowed thread makes every later {@code ServiceLoader} in the
 * process resolve against the wrong classpath, which is the same class of bug pointing the other way.</p>
 */
final class RhinoThread {

    private RhinoThread() {
    }

    /** Runs {@code body} with the engine loader installed, and puts back what was there. */
    static <T> T with(Supplier<T> body) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(RhinoThread.class.getClassLoader());
        try {
            return body.get();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /** A body that may throw anything — a script run, whose failure is the script's to report. */
    interface ThrowingSupplier<T> {
        T get() throws Throwable;
    }

    /** {@link #with}, for a body whose exceptions must reach the caller as themselves. */
    static <T> T withThrowing(ThrowingSupplier<T> body) throws Throwable {
        return withLoader(RhinoThread.class.getClassLoader(), body);
    }

    /**
     * Runs {@code body} with {@code loader} on the thread, and puts back what was there.
     *
     * <p>Exists to run a script's <b>own execution</b> under the loader the host had, rather than under
     * ours. Everything above is about getting the engine loader on the thread for calls <em>into</em>
     * Rhino; this is the other direction, and it matters as much: a script calls back out — into a mod's
     * Java, into CrystalGUI — and any {@code ServiceLoader} or context-loader lookup that runs there would
     * otherwise resolve against a child-first loader that defines its own copy of every host class.</p>
     *
     * <p>Safe because the one thing that must be resolved under the engine loader is resolved before this:
     * Rhino caches its regular-expression engine at class initialisation, and {@code initStandardObjects}
     * — which installs {@code RegExp} — has already run inside the outer scope. @see the class note</p>
     */
    static <T> T withLoader(ClassLoader loader, ThrowingSupplier<T> body) throws Throwable {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return body.get();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}
