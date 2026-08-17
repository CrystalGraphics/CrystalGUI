package com.crystalgui.language.java.fix.ast;

import org.eclipse.jdt.core.dom.ArrayAccess;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.ConditionalExpression;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.InstanceofExpression;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.ThisExpression;

import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.CharacterLiteral;
import org.eclipse.jdt.core.dom.NullLiteral;
import org.eclipse.jdt.core.dom.NumberLiteral;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.TypeLiteral;

/**
 * <b>Does this expression need brackets around it</b> — asked whenever a correction moves an expression
 * into a place that binds differently from the one it was written in.
 *
 * <h3>Getting it wrong compiles</h3>
 *
 * <p>Which is what makes it worth one file. {@code int sum = a + b;} inlined into {@code sum * 2} gives
 * {@code a + b * 2} — a different number, no diagnostic, nothing to notice. Four families each carried a
 * list, and the lists were not the same list.</p>
 *
 * <h3>Two questions, not one, and the difference is a decrement</h3>
 *
 * <p>Two of those lists looked like drift and were not. "May {@code !} go in front of this bare?" admits a
 * unary expression — {@code !!flag} is fine. "May this expression's own brackets be dropped, wherever it
 * is?" does not: {@code -(-a)} without them is {@code --a}, which is a different operator. So the shared
 * list is the JLS's <b>primary</b> expressions, and the unary question adds to it rather than the
 * paren-dropping question subtracting from it.</p>
 */
public final class Precedence {

    private Precedence() {
    }

    /**
     * Whether this needs wrapping when something tighter is put around it — a cast, a {@code !}, a
     * substituted initialiser.
     *
     * <p>The list is the operators that bind looser than a unary: an infix, a ternary, an assignment, an
     * {@code instanceof} and a lambda. Wrapping anything not on it is redundant rather than wrong, which is
     * the right way round for an edit nobody re-reads.</p>
     */
    public static boolean needsParenthesesWhenWrapped(Expression expression) {
        return expression instanceof InfixExpression
                || expression instanceof ConditionalExpression
                || expression instanceof Assignment
                || expression instanceof InstanceofExpression
                || expression instanceof LambdaExpression;
    }

    /**
     * A <b>primary</b> expression — one that binds tighter than every operator, so it may be dropped
     * anywhere without brackets and never needs them.
     *
     * <p>The JLS calls this set {@code PrimaryNoNewArray}, and the exclusion in that name is the whole
     * reason it has one: {@code new int[3].length} is a <b>syntax error</b>, so an array creation is not
     * something a selector may follow. It is reachable here — {@code ((int[]) new int[0]).length} is an
     * unnecessary cast, and dropping its brackets would leave a file that does not parse.</p>
     */
    public static boolean isPrimary(Expression expression) {
        return expression instanceof Name
                || expression instanceof ThisExpression
                || expression instanceof FieldAccess
                || expression instanceof SuperFieldAccess
                || expression instanceof ArrayAccess
                || expression instanceof ParenthesizedExpression
                || expression instanceof MethodInvocation
                || expression instanceof SuperMethodInvocation
                || expression instanceof ClassInstanceCreation
                || isLiteral(expression);
    }

    /**
     * The same, plus a unary — what may follow a {@code !} without brackets.
     *
     * <p>{@code !!flag} and {@code !-count} both parse and both mean what they say. This is deliberately
     * <em>not</em> the same set as {@link #isPrimary}: see the class note on {@code -(-a)}.</p>
     */
    public static boolean bindsTighterThanUnary(Expression expression) {
        return isPrimary(expression) || expression instanceof PrefixExpression;
    }

    private static boolean isLiteral(Expression expression) {
        return expression instanceof StringLiteral
                || expression instanceof NumberLiteral
                || expression instanceof BooleanLiteral
                || expression instanceof CharacterLiteral
                || expression instanceof NullLiteral
                || expression instanceof TypeLiteral;
    }
}
