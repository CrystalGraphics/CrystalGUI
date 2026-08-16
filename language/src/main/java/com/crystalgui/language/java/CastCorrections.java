package com.crystalgui.language.java;

import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.lang.CodeAction;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.ConditionalExpression;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.InstanceofExpression;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

import java.util.List;

/**
 * "Cast expression to 'Dog'" — the value is the right thing and the compiler cannot prove it.
 *
 * <h3>The expected type comes from the TREE, never from the message</h3>
 *
 * <p>ECJ says <i>"cannot convert from RunTest.Animal to RunTest.Dog"</i>, and those two names are a
 * <b>display form</b>: a nested type rendered for a human, not source anyone can paste. Reading
 * {@code getArguments()} would produce a cast that does not compile the moment a type is nested,
 * generic or shadowed. So the target is resolved from the binding of whatever the expression is being
 * assigned <em>to</em> — a variable's declared type, an assignment's left side, the enclosing method's
 * return type — and written through {@link ImportPlan}, which is the one thing that knows what this file
 * may say short.</p>
 *
 * <h3>The compatibility guard is not optional</h3>
 *
 * <p>{@code TypeMismatch} is also what ECJ reports for {@code String s; Integer n = s;}, where a cast is
 * not a fix but a different error — measured, same id. Without {@link ITypeBinding#isCastCompatible} this
 * correction would trade {@code TypeMismatch} for {@code IllegalCast} and call it help. IntelliJ refuses
 * there too.</p>
 *
 * <h3>What is deliberately not here</h3>
 *
 * <p><b>The argument shape.</b> {@code take(a)} where {@code take} wants a {@code Dog} is not a
 * {@code TypeMismatch} at all — it is {@code ParameterMismatch}, reported on the <em>method name</em>
 * rather than on the argument, and working out which argument is wrong and what the parameter type is
 * means redoing overload resolution rather than reading a binding. A separate row, and it stays in the
 * catalogue.</p>
 */
final class CastCorrections {

    static final String ADD_CAST = "java.cast.toExpectedType";

    private CastCorrections() {
    }

    static List<Correction> all() {
        return List.of(new CastToExpectedType());
    }

    private static final class CastToExpectedType implements Correction {

        @Override public String id() {
            return ADD_CAST;
        }

        @Override public int[] problems() {
            return new int[] {IProblem.TypeMismatch, IProblem.ReturnTypeMismatch};
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            Expression expression = context.enclosing(problem, Expression.class);
            if (expression == null) return;
            ITypeBinding actual = expression.resolveTypeBinding();
            ITypeBinding expected = expectedTypeOf(expression);
            if (actual == null || expected == null) return;
            if (actual.isEqualTo(expected)) return;
            // THE GUARD, and the whole safety argument: the same problem id covers `Integer n = aString`,
            // where a cast is not a repair but a second error wearing the first one's clothes.
            if (!actual.isCastCompatible(expected)) return;

            ImportPlan imports = context.importPlan();
            String written = writtenName(expected, imports, expression);
            if (written == null) return;

            ASTRewrite rewrite = context.rewrite();
            AST ast = context.unit().getAST();
            CastExpression cast = ast.newCastExpression();
            cast.setType((org.eclipse.jdt.core.dom.Type)
                    rewrite.createStringPlaceholder(written, ASTNode.SIMPLE_TYPE));
            // PARENTHESES WHERE THE OPERAND BINDS LOOSER, because a cast is a unary operator and ASTRewrite
            // adds none of its own: `(Dog) a + b` casts `a` and leaves the sum alone, which compiles about
            // half the time and means something else every time.
            Expression operand = (Expression) rewrite.createMoveTarget(expression);
            if (bindsLooserThanACast(expression)) {
                ParenthesizedExpression wrapped = ast.newParenthesizedExpression();
                wrapped.setExpression(operand);
                operand = wrapped;
            }
            cast.setExpression(operand);
            rewrite.replace(expression, cast, null);

            ChangeSet edit = context.changesFrom(rewrite, imports);
            if (edit == null) return;
            out.add(context.preferredFix(ADD_CAST, "Cast expression to '" + written + "'", edit));
        }
    }

    /**
     * What the expression is expected to be, from whatever it is being handed to.
     *
     * <p>Three shapes, and they are the three ECJ reports these two problems on: an initialiser, an
     * assignment's right-hand side, and a {@code return}. Anything else answers null rather than guessing —
     * an argument is the case that looks like a fourth and is not, and it is a different problem id
     * entirely.</p>
     */
    private static ITypeBinding expectedTypeOf(Expression expression) {
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
        if (parent instanceof ReturnStatement) {
            for (ASTNode walk = parent; walk != null; walk = walk.getParent()) {
                if (walk instanceof LambdaExpression) return null;   // a lambda's return is inferred
                if (walk instanceof MethodDeclaration) {
                    MethodDeclaration method = (MethodDeclaration) walk;
                    return method.resolveBinding() == null ? null : method.resolveBinding().getReturnType();
                }
            }
        }
        return null;
    }

    /** Operators that bind looser than a cast, so the whole of one has to be wrapped before it is cast. */
    private static boolean bindsLooserThanACast(Expression expression) {
        return expression instanceof InfixExpression
                || expression instanceof ConditionalExpression
                || expression instanceof Assignment
                || expression instanceof InstanceofExpression
                || expression instanceof LambdaExpression;
    }

    /**
     * The type as this file may write it, or null when it cannot be written at all.
     *
     * <p>Built up rather than taken from {@code getQualifiedName()}, which renders a parameterized type as
     * {@code java.util.List<java.lang.String>} — every name in it fully qualified, which compiles and is
     * nobody's idea of a cast. Each part goes through {@link ImportPlan} separately so the result is what
     * the file would have written by hand.</p>
     *
     * <p><b>Null for anything not denotable.</b> A capture, a wildcard or an intersection has no source
     * form to cast to; offering one would produce a fix that cannot compile, which is the failure the
     * compatibility guard above exists to avoid in the other direction.</p>
     */
    private static String writtenName(ITypeBinding type, ImportPlan imports, ASTNode at) {
        if (type.isPrimitive()) return type.getName();
        if (type.isArray()) {
            String component = writtenName(type.getComponentType(), imports, at);
            return component == null ? null : component + "[]";
        }
        if (type.isCapture() || type.isWildcardType() || type.isIntersectionType()
                || type.isAnonymous() || type.isNullType()) {
            return null;
        }
        if (type.isTypeVariable()) return type.getName();
        if (type.isParameterizedType()) {
            String raw = imports.nameFor(type.getErasure().getQualifiedName());
            StringBuilder built = new StringBuilder(raw).append('<');
            ITypeBinding[] arguments = type.getTypeArguments();
            for (int i = 0; i < arguments.length; i++) {
                String argument = writtenName(arguments[i], imports, at);
                if (argument == null) return null;
                if (i > 0) built.append(", ");
                built.append(argument);
            }
            return built.append('>').toString();
        }
        if (inScopeUnqualified(type, at)) return type.getName();
        String qualified = type.getQualifiedName();
        return qualified.isEmpty() ? null : imports.nameFor(qualified);
    }

    /**
     * Whether a NESTED type's simple name resolves where the cast is being written.
     *
     * <p>{@link ImportPlan} shortens by package, which is the right rule for a type somewhere else and no
     * rule at all for one declared in this file: {@code Script.Dog} has no package to strip, so it came out
     * qualified — correct, compiling, and not what anyone writing by hand would put. The scope that makes
     * the short form legal is lexical, so it is answered lexically: the simple name resolves when the cast
     * sits inside the type that declares it, or inside the type itself.</p>
     *
     * <p>A sibling top-level class in the same file is deliberately <em>not</em> in that scope and keeps the
     * qualified form, which is what makes this a check rather than an assumption that a file has one type.</p>
     */
    private static boolean inScopeUnqualified(ITypeBinding type, ASTNode at) {
        ITypeBinding declaring = type.getDeclaringClass();
        if (declaring == null) return false;
        for (ASTNode walk = at; walk != null; walk = walk.getParent()) {
            if (!(walk instanceof AbstractTypeDeclaration)) continue;
            ITypeBinding enclosing = ((AbstractTypeDeclaration) walk).resolveBinding();
            if (enclosing != null && (enclosing.isEqualTo(declaring) || enclosing.isEqualTo(type))) {
                return true;
            }
        }
        return false;
    }
}
