package com.crystalgui.language.java;

import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.lang.CodeAction;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ArrayAccess;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.InstanceofExpression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.NullLiteral;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.ThisExpression;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

import java.util.List;

/**
 * Expressions ECJ has proved say nothing — a cast that casts to what it already is, an {@code instanceof}
 * that is decided by the declared type, a null check that cannot fail.
 *
 * <h3>These three arrive together, and they are one option apart</h3>
 *
 * <p>All three are {@code ignore} in ECJ's defaults, and the first two share a single switch
 * ({@code unnecessaryTypeCheck}) — so they are enabled as a set whether or not that was intended, which
 * is why they are written as a set. See {@link EcjProblemPolicy} for what was measured before turning
 * either on.</p>
 *
 * <h3>The {@code instanceof} fix is a null check, not {@code true} — and that is a correction</h3>
 *
 * <p>The catalogue's row says to replace an always-true {@code instanceof} with {@code true}, on the
 * reading that ECJ only reports the always-true case. It does, and the replacement is still <b>wrong</b>:
 * JLS 15.20.2 makes {@code x instanceof T} false when {@code x} is null, whatever the declared types say,
 * so {@code s instanceof Object} on a possibly-null {@code String} is exactly {@code s != null} and not
 * {@code true}. Measured rather than reasoned about — ECJ reports it on a plain parameter, which is the
 * case where the operand can obviously be null.</p>
 *
 * <p>So the fix writes the null check. Where the operand really is non-null, the {@code redundantNullCheck}
 * pass then reports the result and the second step is offered by the correction below — which is the two
 * fixes composing rather than either of them guessing.</p>
 */
final class ExpressionCorrections {

    static final String REMOVE_CAST = "java.expression.removeCast";
    static final String REPLACE_INSTANCEOF = "java.expression.replaceInstanceof";
    static final String SIMPLIFY_NULL_CHECK = "java.expression.simplifyNullCheck";

    private ExpressionCorrections() {
    }

    static List<Correction> all() {
        return List.of(new RemoveUnnecessaryCast(), new ReplaceRedundantInstanceof(),
                new SimplifyRedundantNullCheck());
    }

    // ── Remove unnecessary cast ─────────────────────────────────────────────────────────────────

    /**
     * <b>Precedence takes care of itself, so this does not reason about it.</b> A cast binds as a unary
     * operator and its operand is already a unary expression, so replacing the whole {@code CastExpression}
     * with that operand can never bind more loosely than what it replaced — {@code (int) (a + b)} keeps the
     * parentheses it already had because they are part of the operand.
     *
     * <p>The one cosmetic case worth handling is the shape the parentheses exist FOR:
     * {@code ((String) s).length()}. Left alone that becomes {@code (s).length()}, which is correct and
     * looks like the fix was half-applied — so when the cast is the whole of a parenthesised expression
     * and the operand needs no parentheses of its own, the parentheses go too.</p>
     */
    private static final class RemoveUnnecessaryCast implements Correction {

        @Override public String id() {
            return REMOVE_CAST;
        }

        @Override public int[] problems() {
            return new int[] {IProblem.UnnecessaryCast};
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            // DOWN, not only up. For `((String) s).length()` ECJ reports the PARENTHESISED range rather
            // than the cast's, so the node at the problem's start is the parent and walking outward from
            // it never meets a CastExpression. Measured; the plain `(String) s` shape reports the cast
            // itself, so both directions have to be handled and neither alone is enough.
            Expression node = context.enclosing(problem, Expression.class);
            while (node instanceof ParenthesizedExpression) {
                node = ((ParenthesizedExpression) node).getExpression();
            }
            if (!(node instanceof CastExpression)) return;
            CastExpression cast = (CastExpression) node;
            Expression operand = cast.getExpression();
            if (operand == null) return;

            ASTNode replaced = cast;
            if (cast.getParent() instanceof ParenthesizedExpression && needsNoParentheses(operand)) {
                replaced = cast.getParent();
            }

            ASTRewrite rewrite = context.rewrite();
            rewrite.replace(replaced, rewrite.createMoveTarget(operand), null);
            ChangeSet edit = context.changesFrom(rewrite);
            if (edit == null) return;
            out.add(context.preferredFix(REMOVE_CAST, "Remove unnecessary cast", edit));
        }

        /** Expressions that already bind tighter than anything they can be dropped into. */
        private static boolean needsNoParentheses(Expression operand) {
            return operand instanceof Name
                    || operand instanceof ThisExpression
                    || operand instanceof FieldAccess
                    || operand instanceof ArrayAccess
                    || operand instanceof ParenthesizedExpression
                    || operand instanceof MethodInvocation
                    || operand instanceof ClassInstanceCreation
                    || operand instanceof StringLiteral;
        }
    }

    // ── Replace a decided instanceof ────────────────────────────────────────────────────────────

    private static final class ReplaceRedundantInstanceof implements Correction {

        @Override public String id() {
            return REPLACE_INSTANCEOF;
        }

        @Override public int[] problems() {
            return new int[] {IProblem.UnnecessaryInstanceof};
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            InstanceofExpression test = context.enclosing(problem, InstanceofExpression.class);
            if (test == null) return;
            Expression operand = test.getLeftOperand();
            if (operand == null) return;

            ASTRewrite rewrite = context.rewrite();
            AST ast = context.unit().getAST();
            InfixExpression nullCheck = ast.newInfixExpression();
            nullCheck.setOperator(InfixExpression.Operator.NOT_EQUALS);
            nullCheck.setLeftOperand((Expression) ASTNode.copySubtree(ast, operand));
            nullCheck.setRightOperand(ast.newNullLiteral());
            rewrite.replace(test, nullCheck, null);

            ChangeSet edit = context.changesFrom(rewrite);
            if (edit == null) return;
            out.add(context.preferredFix(REPLACE_INSTANCEOF, "Replace 'instanceof' with a null check", edit));
        }
    }

    // ── Collapse a null check that cannot fail ──────────────────────────────────────────────────

    /**
     * <b>Only when the comparison is the WHOLE condition of an {@code if}.</b> ECJ reports one identifier,
     * and that identifier can sit anywhere — including inside a compound condition, where
     * {@code if (o != null && o.isEmpty())} has a redundant operand and a perfectly live {@code if}.
     * Collapsing there would delete a real test. Removing just the operand is a second, different fix and
     * is left to the catalogue rather than guessed at here; a compound condition keeps its diagnostic and
     * is simply offered nothing.
     *
     * <p>The collapse itself is {@link DeadCodeCorrections#collapseIf}, shared rather than re-derived. The
     * two triggers reach the same question from opposite ends — dead code knows which branch cannot run,
     * a redundant null check knows what the condition evaluates to — and a second implementation of "which
     * branch survives" is how the two would come to disagree.</p>
     */
    private static final class SimplifyRedundantNullCheck implements Correction {

        @Override public String id() {
            return SIMPLIFY_NULL_CHECK;
        }

        @Override public int[] problems() {
            return new int[] {
                    IProblem.RedundantNullCheckOnNonNullLocalVariable,
                    IProblem.RedundantNullCheckOnNullLocalVariable,
                    IProblem.RedundantNullCheckOnNonNullExpression};
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            InfixExpression comparison = context.enclosing(problem, InfixExpression.class);
            if (comparison == null || !isNullComparison(comparison)) return;
            if (!(comparison.getParent() instanceof IfStatement)) return;
            IfStatement statement = (IfStatement) comparison.getParent();
            if (statement.getExpression() != comparison) return;

            // "cannot be null" makes `!= null` true; "can only be null" makes `== null` true. The id says
            // which side the analysis proved, the operator says which way that answers the test.
            //
            // IT ALWAYS COMES OUT TRUE, and that is a fact about the family rather than an accident: a
            // check ECJ calls REDUNDANT is one that always passes. The always-fails case is reported
            // under different ids entirely (`…ComparisonYieldsFalse`) and comes with a `DeadCode` on the
            // branch that cannot run, so it is already answered by DeadCodeCorrections. The derivation
            // stays rather than being replaced by `true`, because it is what keeps this correct if a
            // fourth id joins the family; the guard below is what stops it acting on a fifth meaning.
            boolean nonNull = problem.getID() != IProblem.RedundantNullCheckOnNullLocalVariable;
            boolean equals = comparison.getOperator() == InfixExpression.Operator.EQUALS;
            if (nonNull == equals) return;

            DeadCodeCorrections.collapseIf(context, statement, true, SIMPLIFY_NULL_CHECK, out);
        }

        private static boolean isNullComparison(InfixExpression comparison) {
            InfixExpression.Operator operator = comparison.getOperator();
            if (operator != InfixExpression.Operator.EQUALS && operator != InfixExpression.Operator.NOT_EQUALS) {
                return false;
            }
            if (!comparison.extendedOperands().isEmpty()) return false;
            return comparison.getLeftOperand() instanceof NullLiteral
                    || comparison.getRightOperand() instanceof NullLiteral;
        }
    }
}
