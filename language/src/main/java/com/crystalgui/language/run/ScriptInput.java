package com.crystalgui.language.run;

import javax.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Routes a running script's {@code System.in} to the console, and everybody else's straight through.
 *
 * <h3>The mirror of {@link ScriptOutput}, and it has to be</h3>
 *
 * <p>Same problem, same shape: there is no process boundary to read from, so the boundary is
 * reconstructed with the thread-local marker {@code ScriptHost} already sets around every invocation.
 * On a thread that is currently inside a script, a read waits for a line typed into the panel; on any
 * other thread it goes to the real {@code System.in}, untouched.</p>
 *
 * <p><b>The passthrough half is the one that matters here.</b> Redirecting {@code System.in} wholesale
 * would hand the game's own reads — and every other mod's — to a text field in a UI panel that may not
 * even be open, and they would block forever with nothing on screen to say why.</p>
 *
 * <h3>Reads return what is buffered, never a full buffer</h3>
 *
 * <p>{@code InputStream}'s default {@code read(byte[], int, int)} keeps calling {@code read()} until the
 * array is full. Left inherited, a {@code Scanner} wrapping this would block after a complete line
 * waiting for enough bytes to fill an 8KB decoder buffer — the line typed, Enter pressed, and nothing
 * happening. So the array form is overridden to return a short read, which is what any interactive
 * stream does and what every reader is built to expect.</p>
 */
public final class ScriptInput {

    private ScriptInput() {
    }

    @Nullable private static InputStream original;

    /**
     * Replaces {@code System.in} with a routing version.
     *
     * <p>Idempotent, and it remembers the stream it displaced so a second install cannot chain one router
     * onto another — two routers would each consult the marker and the inner one would never be reached
     * on a script thread, which is a hang rather than a wrong answer.</p>
     */
    public static synchronized void install(RunConsole target) {
        if (target == null) return;
        if (original == null) original = System.in;
        System.setIn(routed(original, target));
    }

    /** Restores whatever was there before. For a host that is shutting down, and for tests. */
    public static synchronized void uninstall() {
        if (original != null) {
            System.setIn(original);
            original = null;
        }
    }

    /**
     * A stream that reads from {@code target} on a script thread and from {@code passthrough} elsewhere.
     *
     * <p>Public so a test can exercise the routing without touching the process's real streams — the same
     * carve-out {@code ScriptOutput.routed} makes, and for the same reason.</p>
     */
    public static InputStream routed(InputStream passthrough, RunConsole target) {
        return new Routed(passthrough, target);
    }

    private static final class Routed extends InputStream {

        private static final byte[] NOTHING = new byte[0];

        private final InputStream passthrough;
        private final RunConsole console;

        /** The line last handed over, still being read out a byte at a time. */
        private byte[] buffered = NOTHING;
        private int position;

        Routed(InputStream passthrough, RunConsole console) {
            this.passthrough = passthrough;
            this.console = console;
        }

        private boolean insideScript() {
            return ScriptOutput.current() != null;
        }

        /** @return false at end of input — the script was stopped while waiting */
        private boolean ensureBuffered() {
            if (position < buffered.length) return true;
            String line = console.awaitInput(scriptName());
            if (line == null) return false;
            // THE NEWLINE IS PART OF THE LINE. A reader asked for a line and a line ends; without it
            // `Scanner.nextLine()` blocks on for a terminator that is never coming.
            buffered = (line + "\n").getBytes(StandardCharsets.UTF_8);
            position = 0;
            return true;
        }

        @Nullable
        private String scriptName() {
            ScriptRef current = ScriptOutput.current();
            return current == null ? null : current.name();
        }

        @Override
        public int read() throws IOException {
            if (!insideScript()) return passthrough.read();
            if (!ensureBuffered()) return -1;
            return buffered[position++] & 0xFF;
        }

        @Override
        public int read(byte[] destination, int offset, int length) throws IOException {
            if (!insideScript()) return passthrough.read(destination, offset, length);
            if (destination == null) throw new NullPointerException("destination");
            if (offset < 0 || length < 0 || length > destination.length - offset) {
                throw new IndexOutOfBoundsException();
            }
            if (length == 0) return 0;
            if (!ensureBuffered()) return -1;

            // A SHORT READ, deliberately. See the class note: filling the array would block past the end
            // of a line the user has already finished typing.
            int count = Math.min(length, buffered.length - position);
            System.arraycopy(buffered, position, destination, offset, count);
            position += count;
            return count;
        }

        @Override
        public int available() throws IOException {
            if (!insideScript()) return passthrough.available();
            return buffered.length - position;
        }

        /**
         * <b>Never closes the passthrough.</b> It is the process's real {@code System.in}, which this
         * borrows rather than owns — a script calling {@code System.in.close()} would otherwise take the
         * game's standard input with it, permanently and for everyone.
         */
        @Override
        public void close() {
            buffered = NOTHING;
            position = 0;
        }
    }
}
