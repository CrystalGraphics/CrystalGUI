package com.crystalgui.language.java.ecj;

import com.crystalgui.language.java.fix.JavaQuickFixes;
import com.crystalgui.language.java.fix.catalog.AnnotationCorrections;
import com.crystalgui.language.java.fix.catalog.CastCorrections;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Statement;

/**
 * <b>Where a problem is</b> — the one answer the underline and the quick-fix router both read.
 *
 * <h3>Why this is a class and not two private methods</h3>
 *
 * <p>ECJ's span and the span worth <em>marking</em> are not always the same, and the moment they differ
 * two independent questions start being asked about one problem: the analyzer asks "what do I draw over"
 * and {@link JavaQuickFixes} asks "is the caret near enough to offer this". Answering them from separate
 * code is how a squiggle comes to sit somewhere its own fix cannot be reached from — which is exactly what
 * happened: {@code take(a)} had its underline moved onto the argument while the router went on matching
 * the caret against ECJ's range on {@code take}, so the popup opened on the squiggle showing the message
 * and nothing to do. Both halves were individually correct and tested.</p>
 *
 * <h3>Reported, marked, and why reachability is the union</h3>
 *
 * <p>{@link #marked} is what the user sees. {@link #reaches} deliberately accepts a caret in <em>either</em>
 * range, because the mark is only ever moved to a part of, or a sibling within, the construct ECJ reported
 * — so the union is precisely "the thing that is wrong", and narrowing to the mark alone would take the fix
 * away from a caret sitting somewhere perfectly reasonable. Being generous about where a fix can be asked
 * for costs a user nothing; being wrong about it costs them the fix.</p>
 */
public final class ProblemSpans {

    private ProblemSpans() {
    }

    /** ECJ's own range, converted to the half-open form every range in this codebase uses. */
    static int[] reported(IProblem problem) {
        // getSourceEnd is INCLUSIVE in JDT and exclusive everywhere here, so the +1 is a real conversion
        // rather than an off-by-one waiting to happen: without it a one-character problem produces a
        // zero-width squiggle, which paints as nothing at all.
        return new int[] {problem.getSourceStart(), problem.getSourceEnd() + 1};
    }

    /**
     * The span this problem should <b>mark</b>, which is ECJ's own except for five families.
     *
     * <p>The first is {@link #reachedAcrossSpan} and is the only one that moves a mark to a different
     * LINE — an omission ECJ reports against the token that revealed it, on the line below the one the
     * omission is on. The other four move it within the construct ECJ already named.</p>
     *
     * <p>Every other {@code unused} problem is already reported on the name alone — the field, the nested
     * type, the local, the import, the type parameter. {@code UnusedPrivateMethod} and
     * {@code UnusedPrivateConstructor} report the name <em>and the parameter list</em>, which was invisible
     * while the mark was an underline and is not once it is a fade: a whole signature went grey, so
     * {@code int unusedParameter} read as unused code in its own right when it is simply part of the thing
     * that is unused.</p>
     *
     * <p>{@code ParameterMismatch} is reported on the METHOD NAME, and the method is not what is wrong —
     * one of its arguments is. {@code take(a)} underlined {@code take}, which reads as "this method is the
     * problem" and points the eye away from the only thing anyone can change. IntelliJ marks the argument,
     * and so does the fix that answers it: the same call finds both, so the underline and the cast can
     * never disagree about which argument they mean.</p>
     *
     * <p>The {@code @Override} and {@code @SafeVarargs} families are the same misdirection a third time,
     * and {@link AnnotationCorrections#markedSpan} owns the detail: ECJ marks the method name, while the
     * annotation above it is the only part anybody can act on and the only part the fix will touch. javac
     * and IntelliJ both point at the annotation.</p>
     *
     * <p>Decided here rather than in the editor because this is the only side that knows what an id means —
     * the widget is language-agnostic by design. And decided on the DIAGNOSTIC rather than on the fade
     * alone, so the Problems row navigates to the name too, which is where every IDE puts these.</p>
     */
    static int[] marked(CompilationUnit unit, String source, IProblem problem) {
        int[] reported = reported(problem);
        int[] reached = reachedAcrossSpan(unit, source, reported, problem);
        if (reached != null) return reached;
        if (problem.getID() == IProblem.ParameterMismatch) {
            int[] argument = CastCorrections.mismatchedArgumentSpan(unit, reported);
            return argument == null ? reported : argument;
        }
        int[] annotation = AnnotationCorrections.markedSpan(unit, problem, reported);
        if (annotation != null) return annotation;
        if (problem.getID() != IProblem.UnusedPrivateMethod
                && problem.getID() != IProblem.UnusedPrivateConstructor) {
            return reported;
        }
        if (reported[0] < 0 || reported[1] <= reported[0]) return reported;
        ASTNode node = NodeFinder.perform(unit, reported[0], reported[1] - reported[0]);
        while (node != null && !(node instanceof MethodDeclaration)) node = node.getParent();
        if (node == null) return reported;
        SimpleName name = ((MethodDeclaration) node).getName();
        if (name.getStartPosition() < 0 || name.getLength() <= 0) return reported;
        return new int[] {name.getStartPosition(), name.getStartPosition() + name.getLength()};
    }

    /**
     * Where an omission belongs when the recovery <b>reached across a line break</b> to find it.
     *
     * <h3>The parser needed one more token and took it from the next line</h3>
     *
     * <p>{@code sfafafas} alone on a line is not a statement — it is half a declaration — so ECJ carries on
     * looking for a name, takes {@code System} off the line below, and only then reports
     * <em>"Syntax error on token '.', ';' expected"</em>. The mark lands on the {@code .} of a line the
     * user never touched, so an unfinished line puts a red underline under its innocent neighbour.</p>
     *
     * <p><b>It is exactly the ODD-token case, and that is why it looked intermittent.</b> Write two words
     * ({@code sdasdasdasdas asdasdsa}) and the declaration is complete: ECJ reports
     * {@code ParsingErrorInsertToComplete} at the end of that line, correctly, and nothing below is
     * touched. One word and it reaches. Reported as "when it's just one run without spaces it still breaks
     * the line under it".</p>
     *
     * <h3>The discriminator, and what was measured to trust it</h3>
     *
     * <p>Three conditions together: the id is {@link IProblem#ParsingError}, the enclosing statement
     * carries JDT's {@code RECOVERED} flag, and that statement <em>starts on an earlier line than the
     * mark</em>. A legitimately multi-line statement satisfies the third on its own, so the first two are
     * what make it safe — and they were checked against every cross-line shape that could be constructed:
     * a parenthesised expression, an argument list, an {@code if} condition, an array initialiser and a
     * generic call, each broken mid-way. <b>None of them reports {@code ParsingError} at all</b>; the
     * nearest miss is {@code foo(1,⏎2 3)}, which reports {@code ParsingErrorDeleteToken} and is
     * <em>not</em> flagged {@code RECOVERED}. Nor is the multi-line
     * {@code "a" +⏎"b"} missing its semicolon, which reports {@code InsertToComplete} on the later line
     * where the terminator genuinely belongs.</p>
     *
     * <p>The mark goes on the <b>last real character of the construct's first line</b> — the character the
     * terminator should follow — rather than the empty position after it, for the reason
     * {@link #reported} converts ECJ's inclusive end: a zero-width mark paints as nothing.</p>
     */
    private static int[] reachedAcrossSpan(
            CompilationUnit unit, String source, int[] reported, IProblem problem) {
        if (problem.getID() != IProblem.ParsingError) return null;
        if (unit == null || source == null) return null;
        if (reported[0] <= 0 || reported[0] > source.length()) return null;
        // LENGTH 1 -- a zero-length range is "covered" by any node ENDING at the offset. @see the same
        // note on EcjSourceAnalyzer.expressionAt, which paid for that distinction with a completion bug.
        ASTNode node = NodeFinder.perform(unit, reported[0], 1);
        while (node != null && !(node instanceof Statement) && !(node instanceof BodyDeclaration)) {
            node = node.getParent();
        }
        if (node == null || (node.getFlags() & ASTNode.RECOVERED) == 0) return null;
        int start = node.getStartPosition();
        if (start < 0 || start >= reported[0]) return null;
        // Back from the END OF THE CONSTRUCT'S FIRST LINE, which is where the terminator belongs --
        // everything after it on that construct was taken from the line below.
        int lineEnd = source.indexOf('\n', start);
        if (lineEnd < 0 || lineEnd >= reported[0]) return null;
        return previousRealCharacter(source, start, lineEnd + 1);
    }

    /**
     * The last real character before {@code before}, but <b>only across a line break</b>.
     *
     * <p>The newline is the whole condition. Within one line ECJ's own position is the better of the two —
     * it points at the token a reader can see — and the complaint being answered is only ever about blame
     * landing on a line nobody edited. A mark is never moved sideways, only back onto the line the
     * omission is on.</p>
     */
    private static int[] previousRealCharacter(String source, int floor, int before) {
        int previous = before - 1;
        boolean crossedALine = false;
        while (previous >= floor && Character.isWhitespace(source.charAt(previous))) {
            if (source.charAt(previous) == '\n') crossedALine = true;
            previous--;
        }
        if (previous < floor || !crossedALine) return null;
        return new int[] {previous, previous + 1};
    }

    /** Whether a request over {@code [from, to)} should be answered for this problem. */
    public static boolean reaches(CompilationUnit unit, String source, IProblem problem,
                                  int from, int to) {
        return overlaps(reported(problem), from, to)
                || overlaps(marked(unit, source, problem), from, to);
    }

    private static boolean overlaps(int[] span, int from, int to) {
        if (span[0] < 0 || span[1] < span[0]) return false;
        return from <= span[1] && span[0] <= to;
    }
}
