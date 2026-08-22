package com.crystalgui.text.diff;

/**
 * Whether a diff algorithm still has time to keep going.
 *
 * <p>Ported from {@code ITimeout} / {@code InfiniteTimeout} / {@code DateTimeout} in
 * <a href="https://github.com/microsoft/vscode">microsoft/vscode</a>, MIT.</p>
 *
 * <p>Myers is quadratic in space and can be badly behaved on two large unrelated files — exactly the case a
 * user hits by opening the wrong pair. Upstream's answer is not to cap the input size, which would refuse
 * work it could often do, but to give the algorithm a deadline and let it <b>degrade to "everything
 * changed"</b>, which is true and cheap. The caller then knows the answer is approximate rather than being
 * left to guess from a spinner.</p>
 */
public interface DiffTimeout {

    /** Never expires — for tests, and for inputs already known to be small. */
    DiffTimeout INFINITE = () -> true;

    boolean isValid();

    /**
     * A wall-clock deadline.
     *
     * <p>Reads {@code System.nanoTime()} once at construction and compares against it. Note the engine's
     * own rule about that clock: its origin is arbitrary and may be negative, so the deadline is stored as
     * an absolute instant and compared by subtraction, never derived from a sentinel.</p>
     */
    static DiffTimeout after(long millis) {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        return () -> deadline - System.nanoTime() > 0;
    }
}
