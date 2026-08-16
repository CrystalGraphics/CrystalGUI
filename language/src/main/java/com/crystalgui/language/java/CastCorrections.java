package com.crystalgui.language.java;

import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.lang.CodeAction;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConditionalExpression;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.InstanceofExpression;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
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
 * <h3>Two problems, because an argument is not an assignment</h3>
 *
 * <p>{@code take(a)} is not a {@code TypeMismatch} at all — it is {@code ParameterMismatch}, reported on
 * the <em>method name</em>, so neither the id nor the range says which argument is wrong. That is a second
 * correction here rather than a fourth shape of the first: it has to find the method and compare each
 * argument, where the assignment shapes only have to read one binding.</p>
 *
 * <p>What stays out is the <b>constructor</b> argument, which is a third id again
 * ({@code UndefinedConstructor}).</p>
 */
final class CastCorrections {

    static final String ADD_CAST = "java.cast.toExpectedType";
    static final String CAST_ARGUMENT = "java.cast.argument";
    static final String CHANGE_TYPE = "java.cast.changeVariableType";

    private CastCorrections() {
    }

    static List<Correction> all() {
        return List.of(new CastToExpectedType(), new CastArgument(), new ChangeVariableType());
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
            String written = TypeNames.writtenName(expected, imports, expression);
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

    // ── The argument shape, which is a different problem entirely ───────────────────────────────

    /**
     * "Cast argument to 'Dog'" — the same repair, reached from the other direction.
     *
     * <p><b>Not a {@code TypeMismatch}.</b> {@code take(a)} reports {@code ParameterMismatch} on the
     * <em>method name</em>, and its message names types the way a human reads them — so neither the id nor
     * the text says which argument is wrong. That has to be worked out: find the one method of that name
     * and arity, compare each argument's binding against its parameter, and offer a cast for whichever
     * disagrees.</p>
     *
     * <p><b>One candidate, or nothing.</b> With two same-arity overloads there is no way to know which was
     * meant — ECJ names one in its message, but that is its guess rendered for a person rather than an
     * answer we can read. Casting to the wrong one compiles and calls the wrong method, which is worse
     * than offering nothing.</p>
     *
     * <p>A constructor argument is a third id again ({@code UndefinedConstructor}) and is not handled
     * here.</p>
     */
    private static final class CastArgument implements Correction {

        @Override public String id() {
            return CAST_ARGUMENT;
        }

        @Override public int[] problems() {
            return new int[] {IProblem.ParameterMismatch};
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            MethodInvocation call = context.enclosing(problem, MethodInvocation.class);
            if (call == null) return;
            ITypeBinding receiver = receiverOf(call);
            if (receiver == null) return;

            IMethodBinding only = soleCandidate(receiver, call.getName().getIdentifier(),
                    call.arguments().size());
            if (only == null) return;

            ITypeBinding[] wanted = only.getParameterTypes();
            for (int i = 0; i < wanted.length; i++) {
                Expression argument = (Expression) call.arguments().get(i);
                ITypeBinding actual = argument.resolveTypeBinding();
                if (actual == null || wanted[i] == null) continue;
                if (actual.isAssignmentCompatible(wanted[i])) continue;
                // THE SAME GUARD as the assignment shape, and for the same reason: `take(aString)` against
                // `take(Integer)` reports this very id, and a cast there is IllegalCast rather than help.
                if (!actual.isCastCompatible(wanted[i])) continue;

                ImportPlan imports = context.importPlan();
                String written = TypeNames.writtenName(wanted[i], imports, argument);
                if (written == null) continue;
                ChangeSet edit = castInPlace(context, imports, argument, written);
                if (edit == null) continue;
                out.add(context.preferredFix(CAST_ARGUMENT, "Cast argument to '" + written + "'", edit));
            }
        }
    }

    /**
     * The span of the argument a {@code ParameterMismatch} is really about, or null.
     *
     * <p>ECJ marks the <b>method name</b>, which reads as "this method is the problem" and points the eye
     * away from the only thing anyone can change. This is the same walk the correction does — one method
     * of that name and arity, then the first argument that does not fit — so the underline and the cast can
     * never disagree about which argument they mean.</p>
     */
    static int[] mismatchedArgumentSpan(CompilationUnit unit, int[] reported) {
        if (reported[0] < 0 || reported[1] <= reported[0]) return null;
        ASTNode node = NodeFinder.perform(unit, reported[0], reported[1] - reported[0]);
        while (node != null && !(node instanceof MethodInvocation)) node = node.getParent();
        if (node == null) return null;
        MethodInvocation call = (MethodInvocation) node;
        ITypeBinding receiver = receiverOf(call);
        if (receiver == null) return null;
        IMethodBinding only = soleCandidate(receiver, call.getName().getIdentifier(),
                call.arguments().size());
        if (only == null) return null;

        ITypeBinding[] wanted = only.getParameterTypes();
        for (int i = 0; i < wanted.length; i++) {
            Expression argument = (Expression) call.arguments().get(i);
            ITypeBinding actual = argument.resolveTypeBinding();
            if (actual == null || wanted[i] == null) continue;
            if (actual.isAssignmentCompatible(wanted[i])) continue;
            if (argument.getStartPosition() < 0 || argument.getLength() <= 0) return null;
            return new int[] {argument.getStartPosition(),
                    argument.getStartPosition() + argument.getLength()};
        }
        return null;
    }

    /** What the call is being made on — the written receiver, or the type the call sits inside. */
    private static ITypeBinding receiverOf(MethodInvocation call) {
        if (call.getExpression() != null) return call.getExpression().resolveTypeBinding();
        for (ASTNode walk = call; walk != null; walk = walk.getParent()) {
            if (walk instanceof AbstractTypeDeclaration) {
                return ((AbstractTypeDeclaration) walk).resolveBinding();
            }
        }
        return null;
    }

    /**
     * The one method of this name and arity, or null when there are none or several.
     *
     * <p>Walks the superclass chain, because a script calling an inherited method is ordinary. Interfaces
     * are deliberately not walked: a default method reachable only through one would be a candidate this
     * misses, which costs an offer rather than producing a wrong one.</p>
     */
    private static IMethodBinding soleCandidate(ITypeBinding type, String name, int arity) {
        IMethodBinding found = null;
        for (ITypeBinding walk = type; walk != null; walk = walk.getSuperclass()) {
            for (IMethodBinding each : walk.getDeclaredMethods()) {
                if (!each.getName().equals(name)) continue;
                if (each.getParameterTypes().length != arity) continue;
                if (found != null) return null;
                found = each;
            }
        }
        return found;
    }

    /** {@code (Type) expr} in place, wrapped where the operand binds looser than a cast. */
    private static ChangeSet castInPlace(FixContext context, ImportPlan imports, Expression expression,
                                         String written) {
        ASTRewrite rewrite = context.rewrite();
        AST ast = context.unit().getAST();
        CastExpression cast = ast.newCastExpression();
        cast.setType((org.eclipse.jdt.core.dom.Type)
                rewrite.createStringPlaceholder(written, ASTNode.SIMPLE_TYPE));
        Expression operand = (Expression) rewrite.createMoveTarget(expression);
        if (bindsLooserThanACast(expression)) {
            ParenthesizedExpression wrapped = ast.newParenthesizedExpression();
            wrapped.setExpression(operand);
            operand = wrapped;
        }
        cast.setExpression(operand);
        rewrite.replace(expression, cast, null);
        return context.changesFrom(rewrite, imports);
    }
    // ── When a cast is impossible, the declaration is what is wrong ─────────────────────────────

    /**
     * "Change variable 'n' type to 'String'" — the offer for the case a cast cannot answer.
     *
     * <p>{@code Integer n = s;} on a {@code String} reports the same {@code TypeMismatch} as a downcast
     * does, and a cast there is {@code IllegalCast} — so {@link CastToExpectedType} correctly refuses and
     * the popup was left with a message and nothing to do. The repair that does exist is the other end:
     * the value is what it is, so the <em>declaration</em> is the part that is wrong.</p>
     *
     * <h3>The variable, and deliberately not the parameter</h3>
     *
     * <p>IntelliJ offers both, and puts "Change parameter 's' type to 'Integer'" first. That one edits a
     * method SIGNATURE, so every caller of it has to still compile afterwards — this engine cannot see the
     * callers of a script's method, and a fix whose damage is out of frame is not a fix. Changing the local
     * is confined to the statement it is offered on and needs nobody's agreement.</p>
     *
     * <p>Offered only where the cast is <b>not</b>, so the two never appear together: a downcast that is
     * genuinely right does not want its variable re-typed to the supertype it already had.</p>
     */
    private static final class ChangeVariableType implements Correction {

        @Override public String id() {
            return CHANGE_TYPE;
        }

        @Override public int[] problems() {
            return new int[] {IProblem.TypeMismatch};
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            Expression expression = context.enclosing(problem, Expression.class);
            if (expression == null) return;
            if (!(expression.getParent() instanceof VariableDeclarationFragment)) return;
            VariableDeclarationFragment fragment = (VariableDeclarationFragment) expression.getParent();
            if (!(fragment.getParent() instanceof VariableDeclarationStatement)) return;
            VariableDeclarationStatement declaration = (VariableDeclarationStatement) fragment.getParent();
            // ONE FRAGMENT ONLY. `int a = 1, b = x;` shares a single type node, so re-typing it for `b`
            // silently re-types `a` as well — a fix that edits a declaration the caret was never on.
            if (declaration.fragments().size() != 1) return;

            ITypeBinding actual = expression.resolveTypeBinding();
            ITypeBinding declared = fragment.resolveBinding() == null
                    ? null : fragment.resolveBinding().getType();
            if (actual == null || declared == null || actual.isEqualTo(declared)) return;
            // NOT WHERE A CAST WOULD DO. A downcast that is genuinely right does not want its variable
            // widened back to the type it already had, and offering both would put two answers to one
            // question in a popup that shows one inline.
            if (actual.isCastCompatible(declared)) return;

            ImportPlan imports = context.importPlan();
            String written = TypeNames.writtenName(actual, imports, expression);
            if (written == null) return;

            ASTRewrite rewrite = context.rewrite();
            rewrite.replace(declaration.getType(),
                    rewrite.createStringPlaceholder(written, ASTNode.SIMPLE_TYPE), null);
            ChangeSet edit = context.changesFrom(rewrite, imports);
            if (edit == null) return;
            out.add(context.preferredFix(CHANGE_TYPE,
                    "Change variable '" + fragment.getName().getIdentifier() + "' type to '" + written + "'",
                    edit));
        }
    }
}
