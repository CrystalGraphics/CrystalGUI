package com.crystalgui.language.java.fix.ast;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ArrayCreation;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;

/**
 * <b>What is lost by deleting an expression, and what is added by evaluating it twice</b> — the two
 * questions every correction that moves or removes one has to ask.
 *
 * <h3>They are not the same question, which is why the two copies differed</h3>
 *
 * <p>Two families each had a private {@code hasCall}, and they disagreed by exactly one node type:
 * {@code ArrayCreation}. That reads like drift and is not. Deleting {@code new int[n]} is unobservable —
 * nothing sees the array that was never used — while evaluating it twice produces <b>two arrays</b>, and
 * anything that was holding one identity now holds two. Same node, opposite answers, because the callers
 * were asking about deletion and duplication respectively.</p>
 *
 * <p>So this offers both, named for what they are for. A constructor is on both lists: it can do anything,
 * including the thing the caller was about to delete.</p>
 */
final class SideEffects {

    private SideEffects() {
    }

    /** Whether removing this expression could change what the program does. */
    static boolean lostByDeleting(Expression expression) {
        return contains(expression, false);
    }

    /** Whether evaluating this expression twice could differ from evaluating it once. */
    static boolean addedByRepeating(Expression expression) {
        return contains(expression, true);
    }

    private static boolean contains(Expression expression, boolean allocationCounts) {
        if (expression == null) return false;
        boolean[] found = {false};
        expression.accept(new ASTVisitor() {
            @Override public void preVisit(ASTNode node) {
                if (node instanceof MethodInvocation || node instanceof ClassInstanceCreation
                        || node instanceof SuperMethodInvocation
                        || (allocationCounts && node instanceof ArrayCreation)) {
                    found[0] = true;
                }
            }
        });
        return found[0];
    }
}
