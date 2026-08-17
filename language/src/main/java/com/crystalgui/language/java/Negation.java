package com.crystalgui.language.java;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;

/**
 * <b>The opposite of a condition, written the way a person would write it.</b>
 *
 * <p>Shared by "Flip if/else", which negates as half of a meaning-preserving swap, and by "Negate
 * comparison", which negates as the whole edit. One definition because the interesting part is the same
 * for both and is entirely about <em>not</em> reaching for {@code "!(" + source + ")"} every time.</p>
 *
 * <h3>Four rewrites, then the fallback</h3>
 *
 * <p>{@code !x} unwraps, a comparison flips its operator, a boolean literal becomes the other one, and
 * parentheses are looked through. Everything else is wrapped. The point is that the three common shapes —
 * {@code !ready}, {@code n == 0}, {@code true} — come out as {@code ready}, {@code n != 0}, {@code false}
 * rather than as {@code !!ready}, {@code !(n == 0)} and {@code !true}, all of which are correct and none
 * of which anybody would leave in a file.</p>
 *
 * <h3>De Morgan is deliberately NOT applied</h3>
 *
 * <p>{@code a && b} becomes {@code !(a && b)} and not {@code !a || !b}. Both are correct; the second is a
 * different <em>reading</em> of the same condition, and which is clearer is the author's call rather than
 * a fix's. IntelliJ ships that as its own separate intention for the same reason.</p>
 *
 * <p>Text rather than a rewritten node, so an operand's own formatting and any comment inside it survive
 * — the finding {@code IntentionCorrections} records for the whole family.</p>
 */
final class Negation {

    private Negation() {
    }

    /** {@code condition}'s opposite, as source. Never null. */
    static String of(Expression condition, String source) {
        if (condition instanceof ParenthesizedExpression) {
            // LOOKED THROUGH, NOT UNWRAPPED. `(a && b)` negates to `!(a && b)` -- dropping the parentheses
            // here and re-adding them below would be the same string by luck rather than by rule, and for
            // `(x)` it would produce `!x`, which is right, and for `(a) + (b)` it would not be.
            return of(((ParenthesizedExpression) condition).getExpression(), source);
        }
        if (condition instanceof PrefixExpression
                && ((PrefixExpression) condition).getOperator() == PrefixExpression.Operator.NOT) {
            return FixContext.text(((PrefixExpression) condition).getOperand(), source);
        }
        if (condition instanceof BooleanLiteral) {
            return ((BooleanLiteral) condition).booleanValue() ? "false" : "true";
        }
        if (condition instanceof InfixExpression) {
            InfixExpression infix = (InfixExpression) condition;
            InfixExpression.Operator flipped = opposite(infix.getOperator());
            // ONLY A TWO-OPERAND COMPARISON. `a < b < c` does not parse, but `a + b + c` gives one infix
            // node with an extended operand list, and flipping an operator there would rewrite a chain.
            if (flipped != null && !infix.hasExtendedOperands()) {
                return FixContext.text(infix.getLeftOperand(), source) + " " + flipped.toString() + " "
                        + FixContext.text(infix.getRightOperand(), source);
            }
        }
        return "!" + parenthesised(condition, source);
    }

    /** The comparison that is this one's opposite, or null when the operator is not a comparison. */
    private static InfixExpression.Operator opposite(InfixExpression.Operator operator) {
        if (operator == InfixExpression.Operator.EQUALS) return InfixExpression.Operator.NOT_EQUALS;
        if (operator == InfixExpression.Operator.NOT_EQUALS) return InfixExpression.Operator.EQUALS;
        if (operator == InfixExpression.Operator.LESS) return InfixExpression.Operator.GREATER_EQUALS;
        if (operator == InfixExpression.Operator.GREATER_EQUALS) return InfixExpression.Operator.LESS;
        if (operator == InfixExpression.Operator.GREATER) return InfixExpression.Operator.LESS_EQUALS;
        if (operator == InfixExpression.Operator.LESS_EQUALS) return InfixExpression.Operator.GREATER;
        return null;
    }

    /**
     * The expression as written, wrapped when {@code !} would otherwise bind tighter than it does.
     *
     * <p>{@code !} is a unary operator, so {@code !a && b} negates {@code a} alone. Anything that is not a
     * single term has to be parenthesised or the result means something else — and it compiles, which is
     * what makes getting this wrong expensive.</p>
     */
    private static String parenthesised(Expression expression, String source) {
        String text = FixContext.text(expression, source);
        return Precedence.bindsTighterThanUnary(expression) ? text : "(" + text + ")";
    }


}
