package com.crystalgui.language.java;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.SimpleName;

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
final class ProblemSpans {

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
     * The span this problem should <b>mark</b>, which is ECJ's own except for three.
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
     * <p>Decided here rather than in the editor because this is the only side that knows what an id means —
     * the widget is language-agnostic by design. And decided on the DIAGNOSTIC rather than on the fade
     * alone, so the Problems row navigates to the name too, which is where every IDE puts these.</p>
     */
    static int[] marked(CompilationUnit unit, IProblem problem) {
        int[] reported = reported(problem);
        if (problem.getID() == IProblem.ParameterMismatch) {
            int[] argument = CastCorrections.mismatchedArgumentSpan(unit, reported);
            return argument == null ? reported : argument;
        }
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

    /** Whether a request over {@code [from, to)} should be answered for this problem. */
    static boolean reaches(CompilationUnit unit, IProblem problem, int from, int to) {
        return overlaps(reported(problem), from, to) || overlaps(marked(unit, problem), from, to);
    }

    private static boolean overlaps(int[] span, int from, int to) {
        if (span[0] < 0 || span[1] < span[0]) return false;
        return from <= span[1] && span[0] <= to;
    }
}
