package com.crystalgui.language.java;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.ConditionalExpression;
import org.eclipse.jdt.core.dom.DoStatement;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.WhileStatement;

/**
 * <b>What type is wanted where this expression stands</b> — read from the tree, never from a message.
 *
 * <h3>From the tree, and that is the whole point of the class</h3>
 *
 * <p>ECJ says <i>"cannot convert from RunTest.Animal to RunTest.Dog"</i>, and those two names are a
 * <b>display form</b>: a nested type rendered for a human, not source anyone can paste. Every correction
 * that needs the wanted type therefore asks the parent node instead, and three of them had grown their own
 * walk for it.</p>
 *
 * <h3>Null is an answer, and a common one</h3>
 *
 * <p>An expression in a position this does not recognise has no expected type <em>that can be relied
 * on</em>, and guessing produces a fix that compiles into the wrong thing. A {@code return} inside a lambda
 * is the sharpest case: the lambda's return type is inferred from its target, so the enclosing method's is
 * simply not the answer, and walking past one would re-type a method from a value that was never its.</p>
 */
final class Expected {

    private Expected() {
    }

    /**
     * The type {@code expression} is expected to have, or null.
     *
     * <p>Six positions, and they are the ones the corrections reading this actually meet: an initialiser,
     * an assignment's right-hand side, a {@code return}, the operand of a cast, and the condition of an
     * {@code if} / {@code while} / {@code do} / ternary / {@code !}, which is always {@code boolean}.</p>
     *
     * <p>An <b>argument</b> is deliberately not among them, and it is the one that looks like it should be:
     * the parameter it lands on is only knowable once the overload is, which is a different walk with a
     * different failure mode. {@code CastCorrections.mismatchedArguments} owns that.</p>
     */
    static ITypeBinding typeOf(Expression expression) {
        ASTNode parent = expression.getParent();
        if (parent instanceof VariableDeclarationFragment) {
            VariableDeclarationFragment fragment = (VariableDeclarationFragment) parent;
            return fragment.resolveBinding() == null ? null : fragment.resolveBinding().getType();
        }
        if (parent instanceof Assignment) {
            Assignment assignment = (Assignment) parent;
            return assignment.getRightHandSide() == expression
                    ? assignment.getLeftHandSide().resolveTypeBinding() : null;
        }
        if (parent instanceof ReturnStatement) return returnTypeAt(parent);
        if (parent instanceof CastExpression) {
            return ((CastExpression) parent).getType().resolveBinding();
        }
        if (isCondition(expression)) {
            return expression.getAST().resolveWellKnownType("boolean");
        }
        return null;
    }

    /**
     * Whether this expression is <b>the thing being tested</b> — so its type is {@code boolean}.
     *
     * <p>Package-private and separate from {@link #typeOf} because one caller wants the answer as a
     * {@code Type} node rather than a binding, and had grown its own copy of this list to get it.</p>
     */
    static boolean isCondition(Expression expression) {
        ASTNode parent = expression.getParent();
        if (parent instanceof IfStatement) return ((IfStatement) parent).getExpression() == expression;
        if (parent instanceof WhileStatement) return ((WhileStatement) parent).getExpression() == expression;
        if (parent instanceof DoStatement) return ((DoStatement) parent).getExpression() == expression;
        if (parent instanceof ConditionalExpression) {
            return ((ConditionalExpression) parent).getExpression() == expression;
        }
        return parent instanceof PrefixExpression
                && ((PrefixExpression) parent).getOperator() == PrefixExpression.Operator.NOT;
    }

    /**
     * The enclosing method's return type — <b>null inside a lambda</b>.
     *
     * <p>A lambda body's {@code return} is the lambda's, and its type comes from whatever the lambda is
     * being passed to. Walking past one would answer with the surrounding method's return type, which is a
     * type the value was never required to have.</p>
     */
    private static ITypeBinding returnTypeAt(ASTNode returnStatement) {
        MethodDeclaration method = Scopes.enclosingMethod(returnStatement, Scopes.Stop.LAMBDA);
        if (method == null || method.resolveBinding() == null) return null;
        return method.resolveBinding().getReturnType();
    }
}
