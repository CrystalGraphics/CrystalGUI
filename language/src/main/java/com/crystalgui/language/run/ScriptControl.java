package com.crystalgui.language.run;

/**
 * What an injected safepoint calls — the whole runtime half of the kill switch.
 *
 * <h3>Java has no safe preemption, so the script has to cooperate</h3>
 *
 * <p>{@code Thread.stop} is broken by design and gone from modern JVMs; interrupting a thread does
 * nothing to a loop that never blocks. So an infinite loop in a script freezes the game, and the only
 * way out is for the loop itself to check something. {@link Safepoints} puts that check at every method
 * entry and every backward branch, which is what makes {@code while (true) {}} interruptible.</p>
 *
 * <h3>Why the flag is the thread's own interrupt status</h3>
 *
 * <p>A per-script static would be marginally cheaper to read and would need the host to find and set it
 * on every class of every running script. The thread's interrupt flag is already there, already
 * volatile, already an intrinsic that HotSpot compiles to a field read — and, decisively, <b>it is the
 * flag the JDK itself uses</b>. A script blocked in {@code sleep}, {@code wait}, or a channel read
 * throws {@code InterruptedException} from the same {@code interrupt()} call that trips this. One
 * mechanism covers both halves; a private flag would cover only the busy half and would need the
 * blocking half bolted on beside it.</p>
 *
 * <h3>The honest limit</h3>
 *
 * <p>A script that catches {@link Throwable} inside its loop and continues cannot be stopped this way,
 * and nothing cooperative can stop it. That is not a gap in the implementation — it is the shape of the
 * problem, and §19.1's trust model is the answer: scripts are author-trusted content, the same class as
 * a mod jar. Stated here rather than discovered by someone testing it.</p>
 */
public final class ScriptControl {

    private ScriptControl() {
    }

    /**
     * The injected call. Throws if this thread has been asked to stop.
     *
     * <p><b>Called once per method entry and once per backward branch</b>, so it has to be the cheapest
     * thing that can possibly work: no allocation, no synchronisation, no lookup. The exception is
     * constructed only on the way out.</p>
     *
     * <p>Deliberately does <b>not</b> clear the interrupt. A stop is for the whole run, not for the
     * first checkpoint that notices it, and clearing would let the next loop iteration continue.</p>
     */
    public static void checkpoint() {
        if (Thread.currentThread().isInterrupted()) {
            throw new ScriptStoppedException();
        }
    }

    /** The internal name the injected instruction refers to. Derived, so a rename cannot desync it. */
    static final String INTERNAL_NAME = ScriptControl.class.getName().replace('.', '/');

    static final String CHECKPOINT = "checkpoint";
    static final String CHECKPOINT_DESCRIPTOR = "()V";
}
