package com.crystalgui.language.java;

import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.lang.CodeAction;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
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
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

import java.util.ArrayList;
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
    static final String CHANGE_RETURN_TYPE = "java.cast.changeReturnType";
    static final String DROP_RETURNED_VALUE = "java.cast.dropReturnedValue";

    private CastCorrections() {
    }

    static List<Correction> all() {
        return List.of(new CastToExpectedType(), new CastArgument(), new ChangeVariableType(),
                new ChangeReturnType(), new DropReturnedValue());
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
            ITypeBinding expected = Expected.typeOf(expression);
            if (actual == null || expected == null) return;
            if (actual.isEqualTo(expected)) return;
            // THE GUARD, and the whole safety argument: the same problem id covers `Integer n = aString`,
            // where a cast is not a repair but a second error wearing the first one's clothes.
            if (!actual.isCastCompatible(expected)) return;

            ImportPlan imports = context.importPlan();
            String written = TypeNames.writtenName(expected, imports, expression);
            if (written == null) return;

            ChangeSet edit = castInPlace(context, imports, expression, written);
            if (edit == null) return;
            out.add(context.preferredFix(ADD_CAST, "Cast expression to '" + written + "'", edit));
        }
    }

    /** Operators that bind looser than a cast, so the whole of one has to be wrapped before it is cast. */
    private static boolean bindsLooserThanACast(Expression expression) {
        return Precedence.needsParenthesesWhenWrapped(expression);
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

            for (Mismatch mismatch : mismatchedArguments(call)) {
                // THE SAME GUARD as the assignment shape, and for the same reason: `take(aString)` against
                // `take(Integer)` reports this very id, and a cast there is IllegalCast rather than help.
                if (!mismatch.actual.isCastCompatible(mismatch.wanted)) continue;

                ImportPlan imports = context.importPlan();
                String written = TypeNames.writtenName(mismatch.wanted, imports, mismatch.argument);
                if (written == null) continue;
                ChangeSet edit = castInPlace(context, imports, mismatch.argument, written);
                if (edit == null) continue;
                out.add(context.preferredFix(CAST_ARGUMENT, "Cast argument to '" + written + "'", edit));
            }
        }
    }

    /** One argument that does not fit the parameter it is being passed to. */
    private static final class Mismatch {
        final Expression argument;
        final ITypeBinding actual;
        final ITypeBinding wanted;

        Mismatch(Expression argument, ITypeBinding actual, ITypeBinding wanted) {
            this.argument = argument;
            this.actual = actual;
            this.wanted = wanted;
        }
    }

    /**
     * Every argument of {@code call} that does not fit its parameter, in order.
     *
     * <p>The <b>one</b> walk: find the single method of that name and arity, then compare each argument's
     * binding against the parameter it lands on. It was written twice — once to offer the casts and once
     * to decide what to underline — and those two must not be able to disagree about which argument is
     * wrong, or the squiggle points at one thing and the fix repairs another.</p>
     *
     * <p>Castability is deliberately <em>not</em> filtered here: an argument that cannot be cast is still
     * the argument that is wrong, and still the one to underline. Only the fix cares whether a cast is the
     * answer.</p>
     */
    private static List<Mismatch> mismatchedArguments(MethodInvocation call) {
        ITypeBinding receiver = receiverOf(call);
        if (receiver == null) return List.of();
        IMethodBinding only = soleCandidate(receiver, call.getName().getIdentifier(),
                call.arguments().size());
        if (only == null) return List.of();

        List<Mismatch> found = new ArrayList<>();
        ITypeBinding[] wanted = only.getParameterTypes();
        for (int i = 0; i < wanted.length; i++) {
            Expression argument = (Expression) call.arguments().get(i);
            ITypeBinding actual = argument.resolveTypeBinding();
            if (actual == null || wanted[i] == null) continue;
            if (actual.isAssignmentCompatible(wanted[i])) continue;
            found.add(new Mismatch(argument, actual, wanted[i]));
        }
        return found;
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

        for (Mismatch mismatch : mismatchedArguments((MethodInvocation) node)) {
            Expression argument = mismatch.argument;
            if (argument.getStartPosition() < 0 || argument.getLength() <= 0) return null;
            return new int[] {argument.getStartPosition(),
                    argument.getStartPosition() + argument.getLength()};
        }
        return null;
    }

    /** What the call is being made on — the written receiver, or the type the call sits inside. */
    private static ITypeBinding receiverOf(MethodInvocation call) {
        return call.getExpression() != null
                ? call.getExpression().resolveTypeBinding() : Scopes.enclosingTypeBinding(call);
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

    /**
     * {@code (Type) expr} in place, wrapped where the operand binds looser than a cast.
     *
     * <p>PARENTHESES WHERE THE OPERAND BINDS LOOSER, because a cast is a unary operator and
     * {@code ASTRewrite} adds none of its own: {@code (Dog) a + b} casts {@code a} and leaves the sum
     * alone, which compiles about half the time and means something else every time. Both cast
     * corrections come through here, having previously agreed about that by copy.</p>
     */
    private static ChangeSet castInPlace(FixContext context, ImportPlan imports, Expression expression,
                                         String written) {
        ASTRewrite rewrite = context.rewrite();
        AST ast = context.unit().getAST();
        CastExpression cast = ast.newCastExpression();
        cast.setType((Type) rewrite.createStringPlaceholder(written, ASTNode.SIMPLE_TYPE));
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

    /**
     * "Change return type to 'int'" — a {@code void} method that returns a value.
     *
     * <h3>Why a RETURN type may be changed where a PARAMETER type may not</h3>
     *
     * <p>{@link ChangeVariableType} refuses the parameter direction outright: editing a signature means
     * every caller has to still compile, and this engine cannot see a script's callers. A return type looks
     * like the same objection and is not — widening {@code void} to anything is <b>source-compatible for
     * every existing call</b>, because a call whose result is discarded is a legal statement whatever the
     * method returns. Nothing that compiled stops compiling. That asymmetry is the entire reason one of
     * these is offered and the other is not, and it is worth stating because "it edits a signature" reads
     * as the same objection in both cases.</p>
     *
     * <p>Preferred over dropping the value: {@code return 5;} was written by somebody who meant to return
     * something.</p>
     */
    private static final class ChangeReturnType implements Correction {

        @Override public String id() {
            return CHANGE_RETURN_TYPE;
        }

        @Override public int[] problems() {
            return new int[] {IProblem.VoidMethodReturnsValue};
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            ReturnStatement returned = context.enclosing(problem, ReturnStatement.class);
            if (returned == null || returned.getExpression() == null) return;
            MethodDeclaration method = enclosingMethod(returned);
            if (method == null || method.isConstructor() || method.getReturnType2() == null) return;

            ITypeBinding value = returned.getExpression().resolveTypeBinding();
            ImportPlan imports = context.importPlan();
            String written = TypeNames.writtenName(value, imports, returned);
            if (written == null) return;

            ASTRewrite rewrite = context.rewrite();
            rewrite.replace(method.getReturnType2(),
                    rewrite.createStringPlaceholder(written, ASTNode.SIMPLE_TYPE), null);
            ChangeSet edit = context.changesFrom(rewrite, imports);
            if (edit == null) return;
            out.add(context.preferredFix(CHANGE_RETURN_TYPE,
                    "Change return type to '" + written + "'", edit));
        }
    }

    /**
     * "Remove returned value" — the other answer, because the code cannot say which was meant.
     *
     * <p>Both are ordinary: "I meant this method to return something" and "I left that expression behind"
     * happen about equally often, so offering one and hiding the other is a guess dressed as a fix.</p>
     */
    private static final class DropReturnedValue implements Correction {

        @Override public String id() {
            return DROP_RETURNED_VALUE;
        }

        @Override public int[] problems() {
            return new int[] {IProblem.VoidMethodReturnsValue};
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            ReturnStatement returned = context.enclosing(problem, ReturnStatement.class);
            if (returned == null || returned.getExpression() == null) return;
            if (enclosingMethod(returned) == null) return;
            // A CALL IS NOT A VALUE TO THROW AWAY. `return compute();` discards the RESULT and keeps the
            // work; `return count;` discards nothing at all. Deleting an invocation deletes its side
            // effect, which is the same rule the unused-assignment fix is refused under.
            if (SideEffects.lostByDeleting(returned.getExpression())) return;

            ASTRewrite rewrite = context.rewrite();
            rewrite.remove(returned.getExpression(), null);
            ChangeSet edit = context.changesFrom(rewrite);
            if (edit == null) return;
            out.add(context.fix(DROP_RETURNED_VALUE, "Remove returned value", edit));
        }

    }

    /**
     * The method a {@code return} belongs to, or null.
     *
     * <p>Stops at a {@code LambdaExpression}: a lambda body's {@code return} is the lambda's, not the
     * method's, so walking past one would re-type an enclosing method from a value that was never its own.
     * ECJ does not report this problem inside a lambda, so the guard is for the shape rather than for a
     * case seen — which is the kind that survives a refactor and then does not.</p>
     */
    private static MethodDeclaration enclosingMethod(ASTNode at) {
        return Scopes.enclosingMethod(at, Scopes.Stop.LAMBDA);
    }
}
