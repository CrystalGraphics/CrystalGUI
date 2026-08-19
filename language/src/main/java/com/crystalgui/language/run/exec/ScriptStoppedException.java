package com.crystalgui.language.run.exec;

/**
 * Thrown out of a script that was asked to stop.
 *
 * <h3>An Error, not an Exception, and that is the load-bearing choice</h3>
 *
 * <p>Scripts contain {@code try/catch} blocks, and a great many of them are written as
 * {@code catch (Exception e)} — around exactly the loop a runaway script is stuck in. If a stop
 * signal were an {@code Exception} the script would swallow its own kill and keep going, and the host
 * would have no way to tell that from a script that is merely slow.</p>
 *
 * <p>{@code Error} is the JVM's category for "not something application code should be catching", and
 * the one place the convention is genuinely enforced by how people write code. It is the same reasoning
 * that makes {@code ThreadDeath} an {@code Error}.</p>
 *
 * <p><b>It is still catchable</b> — {@code catch (Throwable)} exists and some scripts will have it.
 * Nothing cooperative can beat that, and §19.1's trust model is the answer rather than a cleverer
 * exception type.</p>
 */
public final class ScriptStoppedException extends Error {

    private static final long serialVersionUID = 1L;

    public ScriptStoppedException() {
        // NO STACK TRACE. Filling one in costs more than every safepoint check that led here, and it
        // would describe the arbitrary loop iteration where the stop happened to land -- which tells
        // nobody anything. A stop is not a fault.
        super("script stopped", null, false, false);
    }
}
