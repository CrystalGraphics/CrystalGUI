package com.crystalgui.language.java;

import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.lang.CodeAction;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ConditionalExpression;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.StructuralPropertyDescriptor;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

import java.util.List;

/**
 * "Simplify to 'yes'" and "Remove unreachable branch" — what to do about a branch that can never run.
 *
 * <h3>ECJ points at the consequence; the fix has to act on the cause</h3>
 *
 * <p>The diagnostic lands on the unreachable <em>statement or expression</em>, so the obvious reading is
 * "delete this" — and deleting it is wrong in every shape it appears in. {@code if (false) { … }} does not
 * want its block removed and its {@code if} left behind; {@code 5 > 3 ? "yes" : "no"} cannot have
 * {@code "no"} removed at all, because a conditional expression with two branches is the only kind there
 * is. What is actually being reported is <b>a condition that is constant</b>, and the repair is always to
 * collapse the construct to the branch that survives.</p>
 *
 * <h3>The three shapes, and why none of them unwraps a block into its parent</h3>
 *
 * <p>A conditional expression is replaced by its surviving <em>expression</em>, which is always legal
 * where the whole was. An {@code if} whose <em>else</em> is dead loses the else clause and keeps its
 * {@code then}. An {@code if} whose <em>then</em> is dead is replaced by its else statement, or removed
 * outright when there is none — and the else statement is usually a {@code Block}, which stays a block:
 * a block is a statement, so it goes where the {@code if} was and keeps its own scope. Lifting the
 * block's <em>contents</em> into the enclosing one is what IntelliJ offers and is a different fix, because
 * it can collide with a name already declared there.</p>
 *
 * <h3>What is deliberately out of scope</h3>
 *
 * <p><b>The {@code if (DEBUG)} idiom is not reported at all</b>, so nothing here has to be careful of it:
 * ECJ exempts a condition that is a {@code static final boolean} flag, which is the carve-out JLS 14.21
 * makes to allow conditional compilation. What is left is a condition written as a literal constant —
 * {@code if (false)}, {@code if (true)}, {@code 5 > 3} — which is scaffolding rather than a technique.</p>
 */
final class DeadCodeCorrections {

    static final String SIMPLIFY_CONDITIONAL = "java.deadCode.simplifyConditional";
    static final String REMOVE_BRANCH = "java.deadCode.removeBranch";

    /** Long enough to recognise the branch, short enough for a menu row. */
    private static final int TITLE_LIMIT = 30;

    private DeadCodeCorrections() {
    }

    static List<Correction> all() {
        return List.of(new SimplifyConstantCondition());
    }

    /**
     * Collapses an {@code if} whose condition is known to be {@code value} — the one definition of which
     * branch survives, shared by everything that can prove a condition constant.
     *
     * <p>Two callers reach this from opposite ends and must not answer differently: dead code knows which
     * branch <em>cannot run</em>, and a redundant null check knows what the condition <em>evaluates to</em>.
     * The same three shapes fall out either way — an always-true {@code if} keeps its {@code then} and
     * drops any {@code else}; an always-false one becomes its {@code else}, or goes entirely when there is
     * none.</p>
     *
     * <p><b>Nothing lifts a block's contents into the enclosing one.</b> A block is a statement, so it goes
     * where the {@code if} was and keeps its own scope; unwrapping it is IntelliJ's separate offer and can
     * collide with a name already declared outside.</p>
     */
    static void collapseIf(FixContext context, IfStatement statement, boolean value,
                           String id, List<CodeAction> out) {
        ASTRewrite rewrite = context.rewrite();
        String title;
        if (value) {
            Statement dead = statement.getElseStatement();
            if (dead != null) {
                rewrite.remove(dead, null);
                title = "Remove unreachable 'else'";
            } else {
                // Nothing is unreachable — the test simply always passes, so the `if` itself is the noise.
                rewrite.replace(statement, rewrite.createMoveTarget(statement.getThenStatement()), null);
                title = "Remove redundant condition";
            }
        } else {
            Statement survivor = statement.getElseStatement();
            if (survivor == null) {
                rewrite.remove(statement, null);
                title = "Remove unreachable 'if'";
            } else {
                rewrite.replace(statement, rewrite.createMoveTarget(survivor), null);
                title = "Replace 'if' with its 'else'";
            }
        }
        ChangeSet edit = context.changesFrom(rewrite);
        if (edit == null) return;
        out.add(context.preferredFix(id, title, edit));
    }

    private static final class SimplifyConstantCondition implements Correction {

        @Override public String id() {
            // Reported under two ids because the two answers read differently in a menu; the correction is
            // one piece of logic, so contribute() picks which it is offering.
            return SIMPLIFY_CONDITIONAL;
        }

        @Override public int[] problems() {
            return new int[] {IProblem.DeadCode};
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            ASTNode dead = context.enclosing(problem, ASTNode.class);
            if (dead == null) return;
            // A BRACELESS BRANCH REPORTS THE EXPRESSION, NOT THE STATEMENT. For `if (c) doThing();` ECJ's
            // span stops before the semicolon, so the node covering it exactly is the MethodInvocation and
            // its parent is the ExpressionStatement rather than the `if` -- and the fix declined, silently,
            // for every branch somebody had not wrapped in braces. A braced branch works because the span
            // is the Block, which is already a statement.
            //
            // Climbing only while the parent STARTS AT THE SAME OFFSET is what keeps this from walking out
            // of a block: a statement that is dead for another reason (the one after a `return`) is not the
            // head of its enclosing block, so the walk stops rather than blaming an outer `if`.
            while (!(dead instanceof Statement) && dead.getParent() != null
                    && dead.getParent().getStartPosition() == dead.getStartPosition()) {
                dead = dead.getParent();
            }
            StructuralPropertyDescriptor slot = dead.getLocationInParent();
            ASTNode parent = dead.getParent();

            if (parent instanceof ConditionalExpression) {
                ConditionalExpression conditional = (ConditionalExpression) parent;
                Expression survivor = slot == ConditionalExpression.ELSE_EXPRESSION_PROPERTY
                        ? conditional.getThenExpression() : conditional.getElseExpression();
                if (survivor == null) return;
                ASTRewrite rewrite = context.rewrite();
                rewrite.replace(conditional, rewrite.createMoveTarget(survivor), null);
                ChangeSet edit = context.changesFrom(rewrite);
                if (edit == null) return;
                out.add(context.preferredFix(SIMPLIFY_CONDITIONAL,
                        "Simplify to " + quoted(context, survivor), edit));
                return;
            }

            if (!(parent instanceof IfStatement)) return;
            // Which branch is dead IS what the condition evaluates to: a dead `else` means it is always
            // true, a dead `then` means always false.
            collapseIf(context, (IfStatement) parent,
                    slot == IfStatement.ELSE_STATEMENT_PROPERTY, REMOVE_BRANCH, out);
        }

        /** The branch's own source, quoted and elided — a title should name what it will leave behind. */
        private static String quoted(FixContext context, Expression survivor) {
            int start = survivor.getStartPosition();
            String text = context.source()
                    .substring(start, Math.min(context.source().length(), start + survivor.getLength()))
                    .replaceAll("\\s+", " ").trim();
            if (text.length() > TITLE_LIMIT) text = text.substring(0, TITLE_LIMIT - 1) + "…";
            return "'" + text + "'";
        }
    }
}
