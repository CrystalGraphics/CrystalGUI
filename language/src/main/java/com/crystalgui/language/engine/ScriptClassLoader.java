package com.crystalgui.language.engine;

import java.util.HashMap;
import java.util.Map;

/**
 * One compiled script's classes, in a loader the <b>host</b> owns.
 *
 * <h3>Why the script does not live in the engine's loader</h3>
 *
 * <p>A script is re-run constantly — that is the authoring loop. Each run must replace the last, and
 * the last must become collectable, which means the <em>only</em> thing referencing its classes is a
 * loader nothing else holds. Loading a script into the engine's own loader would tie every version of
 * every script to the engine's lifetime: the engine is expensive to build and is meant to be long-lived,
 * so it is precisely the wrong owner. Dropping this object drops the script.</p>
 *
 * <p>Its parent is the <b>host</b> classloader and deliberately not the engine's: a script calls the
 * host's API, and has no business seeing ECJ.</p>
 *
 * <h3>Parent-first, unlike the engine's loader</h3>
 *
 * <p>The opposite choice to {@link EngineClassLoader}, for the opposite reason. That one is child-first
 * because our pinned engine must beat whatever else is on the classpath. A script must <em>not</em> beat
 * the host — a script class named the same as a host class has to resolve to the host's, or a script
 * could shadow the API it is being given. Parent-first is the default, so this only overrides
 * {@link #findClass}.</p>
 */
public final class ScriptClassLoader extends ClassLoader {

    private final Map<String, byte[]> pending;

    /**
     * @param classes compiled class files by binary name — {@link
     *                com.crystalgui.language.engine.bridge.ScriptCompiler.Result#classes()}
     * @param parent  the host loader, which is what a script is allowed to see
     */
    public ScriptClassLoader(Map<String, byte[]> classes, ClassLoader parent) {
        super(parent);
        this.pending = new HashMap<>(classes);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] bytes = pending.remove(name);
        if (bytes == null) throw new ClassNotFoundException(name);
        // REMOVED, NOT READ. defineClass throws if called twice for one name, and the parent-first walk
        // can reach findClass more than once for the same name under concurrent loads. Handing the
        // bytes over exactly once makes the second attempt a plain ClassNotFoundException, which
        // loadClass then resolves from findLoadedClass.
        return defineClass(name, bytes, 0, bytes.length);
    }

    /** Whether this loader holds a class of that name that has not been defined yet. */
    public boolean has(String binaryName) {
        return pending.containsKey(binaryName);
    }

    static {
        registerAsParallelCapable();
    }
}
