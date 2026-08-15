package com.crystalgui.language.run;

import javax.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Routes a running script's output to the console, and everybody else's straight through.
 *
 * <h3>The problem this exists to solve</h3>
 *
 * <p>IntelliJ can capture a run's output for free because a run is a separate process and stdout belongs
 * to it alone. Scripts here run <em>inside</em> the game's JVM, so there is no process boundary to
 * capture at: redirecting {@code System.out} wholesale would swallow Minecraft's logging and every other
 * mod's, and a script's line would be indistinguishable from the game's.</p>
 *
 * <p><b>So the boundary is reconstructed with a thread-local marker.</b> Whatever invokes script code
 * sets it around the call; the replacement streams consult it. On a thread that is currently inside a
 * script, output goes to the console; on any other, it goes where it always went, untouched.</p>
 *
 * <p>That single move is what makes {@code System.out.println} work <b>both</b> in a one-shot script on
 * its own thread <b>and</b> in an event handler running on the game thread. A per-thread rule alone
 * covers only the first — a handler is on the game's thread and is otherwise indistinguishable from the
 * game. And requiring authors to use a special {@code print} binding instead is worse than it sounds:
 * the first thing anybody writes is {@code System.out.println}, and it would vanish with no
 * explanation.</p>
 *
 * <h3>The marker belongs to the CALLER, not to this class</h3>
 *
 * <p>{@link #enter}/{@link #exit} are public because more than one thing will invoke script code. Today
 * it is {@code ScriptHost.run}; when event handlers land, the dispatcher must bracket every invocation
 * the same way, and a handler firing outside the bracket is output that silently reaches the game's log
 * instead of the panel.</p>
 */
public final class ScriptOutput {

    private ScriptOutput() {
    }

    /**
     * The script this thread is currently executing, or null.
     *
     * <p>Inheritable would be wrong: a thread a script spawns is not the script, it outlives the
     * invocation that made it, and its output would keep arriving under an owner that had finished.</p>
     */
    private static final ThreadLocal<ScriptRef> CURRENT = new ThreadLocal<>();

    private static volatile RunConsole console;
    @Nullable private static PrintStream originalOut;
    @Nullable private static PrintStream originalErr;

    /**
     * Replaces {@code System.out} and {@code System.err} with routing versions.
     *
     * <p>A global side effect, so it is explicit and belongs to the application rather than to the model
     * — a test that wants to exercise routing builds a stream with {@link #routed} instead and leaves the
     * JVM's streams alone.</p>
     *
     * <p><b>Always wraps the ORIGINAL streams, never the current ones.</b> Installing twice — a second
     * workspace, a console rebuilt after a reload — would otherwise wrap a wrapper, so every line would
     * be considered twice and a script's output would arrive in the older console as well as the newer.
     * Keeping the originals makes re-installation a replacement rather than a stack.</p>
     */
    public static synchronized void install(RunConsole target) {
        console = target;
        if (originalOut == null) {
            originalOut = System.out;
            originalErr = System.err;
        }
        System.setOut(new PrintStream(routed(originalOut, RunLevel.OUT, target), true,
                StandardCharsets.UTF_8));
        System.setErr(new PrintStream(routed(originalErr, RunLevel.ERROR, target), true,
                StandardCharsets.UTF_8));
    }

    /** Marks this thread as running {@code script}, and answers what it was running before. */
    @Nullable
    public static ScriptRef enter(ScriptRef script) {
        ScriptRef previous = CURRENT.get();
        CURRENT.set(script);
        return previous;
    }

    /**
     * Restores what {@link #enter} answered.
     *
     * <p>Restoring rather than clearing, because a script may call another script's function and the
     * outer one is still running when the inner returns. Clearing would send the rest of the outer
     * script's output to the game log.</p>
     */
    public static void exit(@Nullable ScriptRef previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    @Nullable
    public static ScriptRef current() {
        return CURRENT.get();
    }

    /**
     * Writes one line as the script currently on this thread, or does nothing if there is none.
     *
     * <p>The entry point for a language's own logging binding — {@code console.log}, {@code print} —
     * which knows its level and does not need it inferred from which stream it went to.</p>
     */
    public static void write(RunLevel level, String text) {
        ScriptRef script = CURRENT.get();
        RunConsole target = console;
        if (script == null || target == null) return;
        target.append(message(script, level, text));
    }

    // ── Where a line came from ──────────────────────────────────────────────────────────────────

    /**
     * Builds a message, attributing it to <b>the script's own deepest frame</b>.
     *
     * <p>Not the frame that called {@code println}: a script calling a helper that prints should be told
     * which of <em>its</em> lines caused the output, not which line of the helper emitted it. Walking
     * down to the first frame the script owns gives that, and it is what makes the collapse key stable
     * for a helper called from two places — they are genuinely two origins.</p>
     *
     * <p>{@code StackWalker} rather than {@code new Throwable().getStackTrace()}, which materialises the
     * whole trace to read one frame of it. This walk stops at the first match.</p>
     */
    static RunMessage message(ScriptRef script, RunLevel level, String text) {
        Optional<StackWalker.StackFrame> frame = StackWalker.getInstance().walk(
                frames -> frames.filter(f -> script.owns(f.getClassName())).findFirst());
        if (frame.isEmpty()) {
            // Output from a script that is running but not on the stack -- a callback the engine
            // invoked, say. Real, attributable to the script, and with no line to name; it simply does
            // not collapse. @see RunMessage#collapseKey
            return RunMessage.of(script.name(), level, text);
        }
        int line = frame.get().getLineNumber();
        return line > 0
                ? RunMessage.at(script.name(), script.file(), line, level, text)
                : RunMessage.of(script.name(), level, text);
    }

    // ── The streams ─────────────────────────────────────────────────────────────────────────────

    /**
     * A stream that routes to {@code target} while a script is running on the calling thread, and to
     * {@code passthrough} otherwise.
     *
     * <p>The primitive {@link #install} is built from, and public because a host that manages its own
     * streams wants it directly rather than having the JVM's replaced underneath it — the harness, which
     * already owns an output pane, is the obvious case. It is also what a test uses to exercise routing
     * without touching {@code System.out}.</p>
     */
    public static OutputStream routed(OutputStream passthrough, RunLevel level, RunConsole target) {
        return new Routed(passthrough, level, target);
    }

    /**
     * Splits the byte stream into lines, because a console row is a line and {@code PrintStream} deals
     * in bytes.
     *
     * <p>Buffered <b>per thread</b>: two scripts printing at once would otherwise interleave halves of
     * each other's lines into one buffer and emit rows belonging to neither.</p>
     */
    private static final class Routed extends OutputStream {

        private final OutputStream passthrough;
        private final RunLevel level;
        private final RunConsole target;
        private final ThreadLocal<ByteArrayOutputStream> pending =
                ThreadLocal.withInitial(ByteArrayOutputStream::new);

        Routed(OutputStream passthrough, RunLevel level, RunConsole target) {
            this.passthrough = passthrough;
            this.level = level;
            this.target = target;
        }

        @Override
        public void write(int b) throws java.io.IOException {
            ScriptRef script = CURRENT.get();
            if (script == null) {
                passthrough.write(b);
                return;
            }
            accept(script, (byte) b);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws java.io.IOException {
            ScriptRef script = CURRENT.get();
            if (script == null) {
                passthrough.write(bytes, offset, length);
                return;
            }
            for (int i = 0; i < length; i++) accept(script, bytes[offset + i]);
        }

        private void accept(ScriptRef script, byte b) {
            if (b == '\n') {
                emit(script);
                return;
            }
            // DROPPED, not buffered. A CR belongs to the line ending on Windows and would otherwise
            // survive into the row's text as an invisible trailing character -- which then makes two
            // otherwise identical rows differ, for a reason nobody can see.
            if (b == '\r') return;
            pending.get().write(b);
        }

        private void emit(ScriptRef script) {
            ByteArrayOutputStream buffer = pending.get();
            String text = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            buffer.reset();
            target.append(message(script, level, text));
        }

        /**
         * Flushing does <b>not</b> emit a partial line.
         *
         * <p>{@code PrintStream} in autoflush mode flushes after every {@code println} — and also after
         * a bare {@code print}, which has produced no line yet. Emitting on flush would break
         * {@code print("a"); print("b"); println("c")} into three rows instead of one.</p>
         */
        @Override
        public void flush() throws java.io.IOException {
            if (CURRENT.get() == null) passthrough.flush();
        }
    }
}
