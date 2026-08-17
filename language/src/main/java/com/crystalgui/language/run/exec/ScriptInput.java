package com.crystalgui.language.run.exec;

import com.crystalgui.language.run.ScriptRef;
import com.crystalgui.language.run.console.RunConsole;

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

        /**
         * The line last handed to <em>one</em> thread, still being read out a byte at a time.
         *
         * <p><b>Per thread, exactly as {@link ScriptOutput}'s buffer is</b>, and for a sharper reason:
         * these were plain fields, so the remainder of a line one script had not finished reading was
         * handed to whatever read next. A script that consumed one character and returned left the rest
         * of its line — and its newline — sitting in the stream, and the <em>next</em> script's first
         * {@code read()} answered it without ever showing the input row. That is a script silently
         * receiving another script's input, which is worse than a hang because it looks like it worked.</p>
         */
        private static final class Line {
            private byte[] bytes = NOTHING;
            private int position;

            boolean hasMore() {
                return position < bytes.length;
            }
        }

        private final InputStream passthrough;
        private final RunConsole console;
        private final ThreadLocal<Line> reading = ThreadLocal.withInitial(Line::new);

        Routed(InputStream passthrough, RunConsole console) {
            this.passthrough = passthrough;
            this.console = console;
        }

        private boolean insideScript() {
            return ScriptOutput.current() != null;
        }

        /** @return false at end of input — the script was stopped while waiting */
        private boolean ensureBuffered(Line line) {
            if (line.hasMore()) return true;
            // THE PROMPT COMES OUT FIRST. `print("Name? ")` has no newline, so the transcript is still
            // holding it -- and this thread is about to block, which means nothing will finish that line
            // until an answer arrives. Without the flush the input row appears under a console that
            // never asked anything, and the script reads as hung rather than as waiting.
            ScriptOutput.flushPartial();
            String typed = console.awaitInput(scriptName());
            if (typed == null) return false;
            // THE NEWLINE IS PART OF THE LINE. A reader asked for a line and a line ends; without it
            // `Scanner.nextLine()` blocks for a terminator that is never coming.
            line.bytes = (typed + "\n").getBytes(StandardCharsets.UTF_8);
            line.position = 0;
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
            Line line = reading.get();
            if (!ensureBuffered(line)) return -1;
            return line.bytes[line.position++] & 0xFF;
        }

        @Override
        public int read(byte[] destination, int offset, int length) throws IOException {
            if (!insideScript()) return passthrough.read(destination, offset, length);
            if (destination == null) throw new NullPointerException("destination");
            if (offset < 0 || length < 0 || length > destination.length - offset) {
                throw new IndexOutOfBoundsException();
            }
            if (length == 0) return 0;
            Line line = reading.get();
            if (!ensureBuffered(line)) return -1;

            // A SHORT READ, deliberately. See the class note: filling the array would block past the end
            // of a line the user has already finished typing.
            int count = Math.min(length, line.bytes.length - line.position);
            System.arraycopy(line.bytes, line.position, destination, offset, count);
            line.position += count;
            return count;
        }

        @Override
        public int available() throws IOException {
            if (!insideScript()) return passthrough.available();
            Line line = reading.get();
            return line.bytes.length - line.position;
        }

        /**
         * <b>Never closes the passthrough.</b> It is the process's real {@code System.in}, which this
         * borrows rather than owns — a script calling {@code System.in.close()} would otherwise take the
         * game's standard input with it, permanently and for everyone.
         *
         * <p>Clears only the calling thread's remainder, for the same reason the buffer is per thread:
         * one script closing the stream must not discard a line another is midway through reading.</p>
         */
        @Override
        public void close() {
            Line line = reading.get();
            line.bytes = NOTHING;
            line.position = 0;
        }
    }
}
