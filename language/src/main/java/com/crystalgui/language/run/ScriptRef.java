package com.crystalgui.language.run;

import com.crystalgui.fs.Resource;
import com.crystalgui.language.run.exec.ScriptOutput;

import java.util.Optional;

/**
 * Which script is running — the file it came from, and how to tell which of its lines is executing.
 *
 * <h3>Why both, and not either alone</h3>
 *
 * <p>The <b>file</b> is the identity everything the user sees is keyed by: the rail, the filter, and the
 * running indicator, which is a {@code FileDecorationProvider} and can only decorate a resource.</p>
 *
 * <p>The <b>origin</b> is what {@link ScriptOutput} asks to find the line that produced a message. It
 * cannot be derived from the file, and it is not the same question for every runtime: a Java script is
 * a JVM class whose frames carry the answer, so its origin walks the stack for the class the script
 * compiled to — which routinely has neither the file's name nor its package, since the prelude wraps a
 * snippet in a generated type. A JavaScript script under Rhino has no frames of its own to walk; its
 * runtime knows the line another way. So the runtime that made the script supplies the origin, and the
 * console never learns how it was answered.</p>
 */
public record ScriptRef(Resource file, Origin origin) {

    /**
     * How a runtime finds the line of its own script that is executing on the calling thread.
     *
     * <p>Asked at every emitted line, so an implementation stops at the first answer. {@link #NONE} is the
     * honest answer for a runtime that cannot say — the row is still shown, still filtered and still
     * stopped; only the column naming its source is empty.</p>
     */
    @FunctionalInterface
    public interface Origin {

        /** Nothing can be named. Every line lands without a source position. */
        Origin NONE = () -> -1;

        /** The 1-based line of the script's own code currently executing on this thread, or {@code -1}. */
        int currentLine();
    }

    /**
     * The JVM's own answer: the deepest frame the script's class owns that can name a line.
     *
     * <p>Not the frame that called {@code println}: a script calling a helper that prints should be told
     * which of <em>its</em> lines caused the output, not which line of the helper emitted it. Walking down
     * to the first frame the script owns gives that, and it is what makes the collapse key stable for a
     * helper called from two places — they are genuinely two origins.</p>
     *
     * <p>Not "the deepest owned frame" either: a lambda body compiles into the script's own class and is
     * owned, but a synthetic frame can carry no line number at all — and taking it and finding none threw
     * away an enclosing frame that had one. Printing from inside a {@code forEach} is the ordinary way to
     * hit that, and it costs the origin of exactly the lines people wrap in lambdas.</p>
     *
     * <p>{@code StackWalker} rather than {@code new Throwable().getStackTrace()}, which materialises the
     * whole trace to read one frame of it. This walk stops at the first match.</p>
     *
     * <p>Here rather than beside the Java runtime because it is about the <em>JVM</em>, not about Java: any
     * runtime that defines its script as a class — a JS engine in compiled mode, a Kotlin one — answers
     * this way, and the one that does not answers its own way.</p>
     */
    public record ClassOrigin(String binaryName, int prependedRows) implements Origin {

        /** A class compiled from the author's text as written — no wrapper, so no shift. */
        public ClassOrigin(String binaryName) {
            this(binaryName, 0);
        }

        /**
         * Whether {@code className} is this script's own code.
         *
         * <p>Matches nested and synthetic classes too, because a lambda inside a script compiles to
         * {@code Script$$Lambda$14} and a message printed from inside one is still the script's — reporting
         * it as belonging to nobody would put exactly the output people wrap in lambdas outside the
         * collapse rule.</p>
         */
        public boolean owns(String className) {
            if (className == null) return false;
            if (className.equals(binaryName)) return true;
            return className.startsWith(binaryName) && className.length() > binaryName.length()
                    && (className.charAt(binaryName.length()) == '$');
        }

        /**
         * A frame's line, as the AUTHOR's file numbers it.
         *
         * <p>A runtime that wraps a snippet compiles a unit the author never wrote — a class header, a
         * method header, hoisted imports — so a JVM frame names a line in the <em>unit</em> and every
         * console row points somewhere past the end of a short file. Six lines of script reported as line
         * 10, with the link navigating nowhere.</p>
         *
         * <p>{@code prependedRows} is engine-neutral on purpose: how many rows a runtime added before the
         * author's first is a number, not a Java fact, and a Kotlin host or a JS engine in compiled mode
         * has the same one. What the rows CONTAIN stays the runtime's business — {@code ScriptPrelude}
         * owns that and hands over the count.</p>
         *
         * <p><b>Below the author's first line answers -1, never a clamp.</b> A frame inside the prelude is
         * code the author never wrote and cannot navigate to; clamping it to line 1 would blame their
         * first character for it. -1 is already the documented "running, but not on a line I can name",
         * and {@code ScriptOutput.message} degrades it to a row with no link rather than a broken one —
         * the same choice {@code ScriptPrelude.toScriptPoint} makes for diagnostics.</p>
         */
        @Override
        public int currentLine() {
            Optional<StackWalker.StackFrame> frame = StackWalker.getInstance().walk(
                    frames -> frames.filter(f -> owns(f.getClassName()) && f.getLineNumber() > 0)
                            .findFirst());
            int unitLine = frame.map(StackWalker.StackFrame::getLineNumber).orElse(-1);
            if (unitLine < 0) return -1;
            int authorLine = unitLine - prependedRows;
            return authorLine > 0 ? authorLine : -1;
        }
    }

    /** A script that runs as a JVM class — its lines are found on the stack. */
    public static ScriptRef ofClass(Resource file, String binaryName) {
        return ofClass(file, binaryName, 0);
    }

    /**
     * A script that runs as a JVM class compiled from a WRAPPED unit.
     *
     * @param prependedRows rows the runtime added before the author's first line — see
     *                      {@link ClassOrigin#currentLine()}
     */
    public static ScriptRef ofClass(Resource file, String binaryName, int prependedRows) {
        return new ScriptRef(file, new ClassOrigin(binaryName, prependedRows));
    }

    public ScriptRef {
        if (origin == null) origin = Origin.NONE;
    }

    /** What the console and the rail label it — the file's own name. */
    public String name() {
        return file.name();
    }
}
