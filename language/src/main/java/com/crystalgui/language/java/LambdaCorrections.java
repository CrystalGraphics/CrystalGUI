package com.crystalgui.language.java;

import com.crystalgui.text.Change;
import com.crystalgui.text.lang.CodeAction;
import com.crystalgui.text.lang.CodeActionKind;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.AnnotatableType;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.StructuralPropertyDescriptor;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.ThisExpression;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.VariableDeclarationExpression;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "Replace with lambda" — an anonymous class that is really a function, written as one.
 *
 * <h3>An intention, because no compiler reports this</h3>
 *
 * <p>Measured before it was designed: a convertible anonymous class produces <b>no diagnostic at all</b>,
 * not a warning and not an info. "Anonymous can be replaced with lambda" is a JDT <em>UI</em> clean-up and
 * an IntelliJ inspection, and neither is something ECJ emits. So this keys on no problem and is asked once
 * per request about the caret range — the second consumer of that hook after {@code Organize imports}.</p>
 *
 * <h3>What may not be converted</h3>
 *
 * <p>The list is IntelliJ's and Eclipse's, cross-checked against each other and then against a real parse.
 * IntelliJ Community is Apache 2.0 and portable with notice; Eclipse JDT is EPL-2.0 and was read for its
 * decision list only. Two of the entries below are in neither list and came out of writing the converted
 * form by hand and asking whether it still compiles:</p>
 *
 * <ul>
 *   <li><b>Shadowing is fatal, and not only for parameters.</b> A lambda's parameters <em>and its body's
 *       locals</em> live in the enclosing scope, where an anonymous class opened a new one — so a name
 *       that was legal becomes <i>"cannot redeclare another local variable defined in an enclosing
 *       scope"</i>. The anonymous form compiles either way, which makes this a defect the conversion
 *       would introduce rather than one it reveals. Repaired by renaming, not refused.</li>
 *   <li><b>Ambiguity is fatal, and only for same-arity overloads.</b> {@code take(Comparator)} beside
 *       {@code take(Runnable)} is decided by arity and converts cleanly; two interfaces of the same shape
 *       leave the call ambiguous, because the anonymous form named its type and a lambda does not.
 *       Repaired by casting, not refused.</li>
 * </ul>
 *
 * <p>Qualified {@code Outer.this} converts fine and is deliberately allowed — it is only the
 * <em>unqualified</em> form that means something different inside a lambda.</p>
 */
final class LambdaCorrections {

    static final String FROM_ANONYMOUS = "java.lambda.fromAnonymous";

    private LambdaCorrections() {
    }

    static List<Correction> all() {
        return List.of(new ReplaceAnonymousWithLambda());
    }

    private static final class ReplaceAnonymousWithLambda implements Correction {

        @Override public String id() {
            return FROM_ANONYMOUS;
        }

        /** Empty: nothing reports this, so it is asked about the range rather than about a problem. */
        @Override public int[] problems() {
            return new int[0];
        }

        @Override public void contribute(FixContext context, IProblem none, List<CodeAction> out) {
            ClassInstanceCreation creation = creationAt(context);
            if (creation == null) return;
            AnonymousClassDeclaration anonymous = creation.getAnonymousClassDeclaration();
            MethodDeclaration method = convertibleMethod(creation, anonymous, context.source());
            if (method == null) return;
            if (!hasTargetType(creation)) return;
            if (usesTheAnonymousInstance(method, anonymous)) return;

            // ── THREE RANGES, AND DELIBERATELY NOT ASTRewrite ────────────────────────────────────
            //
            // The catalogue's §4 says to prefer the rewriter, and this is the one correction where it
            // fights back. The body has to be carried across UNCHANGED except for a rename or two inside
            // it, and a `createMoveTarget` will not accept nested edits: renaming `left` to `left1`
            // produced `1left` at every use, the moved text and the nested edit disagreeing about whose
            // coordinates an offset was in. Copying the subtree instead would work and would silently
            // drop every comment in the body, which is worse than the problem.
            //
            // Written as text ranges the whole thing is three edits on the ORIGINAL document, which is
            // what ChangeSet wants anyway: the header up to the body's first character, the renames
            // inside it, and whatever trails the body. The author's own indentation and comments survive
            // because nothing regenerates them.
            String source = context.source();
            Set<String> taken = namesInScopeAt(creation);
            List<String> parameters = new ArrayList<>();
            List<Change> edits = new ArrayList<>();
            renameClashes(method, taken, parameters, edits);

            ASTNode body = lambdaBody(method);
            StringBuilder head = new StringBuilder();
            if (needsDisambiguatingCast(creation)) {
                // THE TYPE THE `new` ALREADY WROTE. It is spelled correctly for this file by
                // construction — imported if it needed importing — so there is nothing to work out.
                Type written = creation.getType();
                head.append('(').append(source, written.getStartPosition(),
                        written.getStartPosition() + written.getLength()).append(") ");
            }
            head.append('(').append(String.join(", ", parameters)).append(") -> ");

            edits.add(0, new Change(creation.getStartPosition(), body.getStartPosition(), head.toString()));
            edits.add(new Change(body.getStartPosition() + body.getLength(),
                    creation.getStartPosition() + creation.getLength(), ""));

            out.add(context.action(FROM_ANONYMOUS, "Replace with lambda", CodeActionKind.REFACTOR,
                    context.changeSet(edits)));
        }
    }

    // ── Finding it ──────────────────────────────────────────────────────────────────────────────

    /**
     * The anonymous creation whose <b>header</b> the request covers — {@code new} to the opening brace.
     *
     * <p>Not the whole body, which is what IntelliJ highlights and is practical rather than cosmetic: an
     * intention offered anywhere inside a forty-line anonymous class is in every popup that class
     * contains, competing with the fixes for the real problems on those lines.</p>
     */
    private static ClassInstanceCreation creationAt(FixContext context) {
        int from = context.from();
        int to = context.to();
        ASTNode node = NodeFinder.perform(context.unit(), from, Math.max(0, to - from));
        if (node == null) return null;

        // UP FIRST: a caret inside the header lands on the type or the `new`, whose creation is an
        // ancestor.
        for (ASTNode walk = node; walk != null; walk = walk.getParent()) {
            if (walk instanceof ClassInstanceCreation && headerOverlaps((ClassInstanceCreation) walk, from, to)) {
                return (ClassInstanceCreation) walk;
            }
        }

        // AND THEN DOWN, which is not symmetry for its own sake. A request is a RANGE, and the moment it
        // is wider than the header — a selected line, `return new Comparator<String>() {` — the node
        // covering it is the statement and the creation is a CHILD of it, so walking outward passes it by
        // forever. Every fixture line failed on exactly this while every caret-driven test passed.
        ClassInstanceCreation[] found = {null};
        node.accept(new ASTVisitor() {
            @Override public boolean visit(ClassInstanceCreation candidate) {
                if (found[0] == null && headerOverlaps(candidate, from, to)) found[0] = candidate;
                return found[0] == null;
            }
        });
        return found[0];
    }

    /** Whether the request touches {@code new …()} — up to the opening brace, never the body. */
    private static boolean headerOverlaps(ClassInstanceCreation creation, int from, int to) {
        AnonymousClassDeclaration anonymous = creation.getAnonymousClassDeclaration();
        if (anonymous == null) return false;
        return from <= anonymous.getStartPosition() && to >= creation.getStartPosition();
    }

    // ── Whether it may be converted ─────────────────────────────────────────────────────────────

    /** The single method to become the lambda, or null when any condition refuses. */
    private static MethodDeclaration convertibleMethod(ClassInstanceCreation creation,
                                                       AnonymousClassDeclaration anonymous, String source) {
        // `new @Foo Runnable() {}` -- the annotation has nowhere to go on a lambda.
        if (creation.getType() instanceof AnnotatableType
                && !((AnnotatableType) creation.getType()).annotations().isEmpty()) {
            return null;
        }
        ITypeBinding created = creation.getType().resolveBinding();
        if (created == null) return null;
        // ONE CALL FOR TWO CONDITIONS: null unless the type is an interface with exactly one abstract
        // method, inherited ones counted and Object's public methods discounted -- which is the whole of
        // "is it functional", done by the compiler that decides it rather than by a walk of our own.
        IMethodBinding functional = created.getFunctionalInterfaceMethod();
        if (functional == null) return null;
        // A lambda cannot declare type parameters: "Illegal lambda expression: Method make ... is generic".
        if (functional.getTypeParameters().length > 0) return null;

        List<?> members = anonymous.bodyDeclarations();
        if (members.size() != 1 || !(members.get(0) instanceof MethodDeclaration)) return null;
        MethodDeclaration method = (MethodDeclaration) members.get(0);
        if (method.isConstructor() || method.getBody() == null) return null;
        if (!method.typeParameters().isEmpty()) return null;
        // The doc would be dropped on the floor, and a lambda has nowhere to put one.
        //
        // NOT getJavadoc() ALONE, which answers null here: a doc comment inside an anonymous class body
        // does not arrive on the node — measured, a documented method converted happily. The parse's own
        // comment list does not depend on that attachment, so the question is asked of it instead.
        if (method.getJavadoc() != null || documentedBefore(method, anonymous, source)) return null;
        int modifiers = method.getModifiers();
        if (Modifier.isSynchronized(modifiers) || Modifier.isStrictfp(modifiers)) return null;
        for (Object each : method.modifiers()) {
            // @Override is source-retained and inherited, so dropping it loses nothing. Anything else is
            // kept by the class file or read reflectively, and a lambda cannot carry it.
            if (each instanceof Annotation && !isOverride((Annotation) each)) return null;
        }
        return method;
    }

    /**
     * A doc comment sitting between the anonymous body's opening brace and this method.
     *
     * <p><b>Asked of the source text, and that is the third attempt.</b> {@code getJavadoc()} answers
     * null — a doc comment inside an anonymous class body is not attached to the declaration below it —
     * and walking the unit's comment list for one that {@code isDocComment()} finds nothing either. Both
     * spellings of the question let a documented method convert happily. What the comment <em>is</em> is
     * decided by the three characters it opens with, and those are in the file whatever the DOM made of
     * them.</p>
     */
    private static boolean documentedBefore(MethodDeclaration method, AnonymousClassDeclaration anonymous,
                                            String source) {
        int from = Math.max(0, anonymous.getStartPosition());
        int to = Math.min(source.length(), method.getName().getStartPosition());
        return from < to && source.substring(from, to).contains("/**");
    }

    private static boolean isOverride(Annotation annotation) {
        ITypeBinding binding = annotation.resolveTypeBinding();
        return binding != null && "java.lang.Override".equals(binding.getQualifiedName());
    }

    /**
     * Whether the body reaches the anonymous object itself — {@code this}, {@code super}, or a call to the
     * very method being converted.
     *
     * <p>All three mean something different afterwards. An unqualified {@code this} becomes the
     * <em>enclosing</em> instance; {@code super} has no meaning at all; and a lambda cannot call itself,
     * because it has no name to call. A <b>qualified</b> {@code Outer.this} already meant the enclosing
     * instance and is left alone — measured, it converts and compiles.</p>
     */
    private static boolean usesTheAnonymousInstance(MethodDeclaration method,
                                                    AnonymousClassDeclaration anonymous) {
        IMethodBinding declared = method.resolveBinding();
        final boolean[] found = {false};
        method.getBody().accept(new ASTVisitor() {
            @Override public boolean visit(ThisExpression node) {
                if (node.getQualifier() == null) found[0] = true;
                return true;
            }

            @Override public boolean visit(SuperMethodInvocation node) {
                found[0] = true;
                return true;
            }

            @Override public boolean visit(SuperFieldAccess node) {
                found[0] = true;
                return true;
            }

            @Override public boolean visit(MethodInvocation node) {
                if (node.getExpression() != null || declared == null) return true;
                IMethodBinding called = node.resolveMethodBinding();
                if (called != null && called.isEqualTo(declared)) found[0] = true;
                return true;
            }

            /** A nested anonymous class has its own `this`; its body is not ours to judge. */
            @Override public boolean visit(AnonymousClassDeclaration node) {
                return node == anonymous;
            }
        });
        return found[0];
    }

    /**
     * Whether the expression sits somewhere a lambda's target type can be inferred from.
     *
     * <p>An anonymous class carries its own type and can stand anywhere; a lambda is a poly expression and
     * cannot. {@code new Runnable() {…}.run()} is the shape this refuses — a receiver has no target type,
     * so there is nothing for the lambda to be.</p>
     */
    private static boolean hasTargetType(ClassInstanceCreation creation) {
        StructuralPropertyDescriptor slot = creation.getLocationInParent();
        ASTNode parent = creation.getParent();
        if (parent instanceof VariableDeclarationFragment) {
            return slot == VariableDeclarationFragment.INITIALIZER_PROPERTY;
        }
        if (parent instanceof Assignment) return slot == Assignment.RIGHT_HAND_SIDE_PROPERTY;
        if (parent instanceof ReturnStatement) return true;
        if (parent instanceof CastExpression) return true;
        if (parent instanceof MethodInvocation) return slot == MethodInvocation.ARGUMENTS_PROPERTY;
        if (parent instanceof ClassInstanceCreation) {
            return slot == ClassInstanceCreation.ARGUMENTS_PROPERTY;
        }
        return false;
    }

    // ── The body ────────────────────────────────────────────────────────────────────────────────

    /**
     * The node the lambda's body should be — the block, or the one thing inside it.
     *
     * <p>{@code { return expr; }} collapses to {@code expr} and a void body of one expression statement
     * collapses to that expression; anything longer keeps its braces, which is why a two-statement
     * comparator still reads as a block afterwards.</p>
     */
    private static ASTNode lambdaBody(MethodDeclaration method) {
        Block block = method.getBody();
        if (block.statements().size() != 1) return block;
        Statement only = (Statement) block.statements().get(0);
        if (only instanceof ReturnStatement) {
            Expression value = ((ReturnStatement) only).getExpression();
            return value == null ? block : value;
        }
        if (only instanceof ExpressionStatement) return ((ExpressionStatement) only).getExpression();
        return block;
    }

    // ── Shadowing ───────────────────────────────────────────────────────────────────────────────

    /**
     * Every local and parameter whose scope covers {@code creation}.
     *
     * <p><b>Locals and parameters only.</b> Fields are not in this set and must not be: a lambda may
     * legally shadow a field, exactly as any other code may, and renaming for one would be a change
     * nobody asked for.</p>
     *
     * <p>A declaration <em>after</em> the creation in the same block is not in scope at it, so the
     * position is compared rather than the block merely being walked — over-collecting here does not
     * produce wrong code, it produces renames that were never needed.</p>
     */
    private static Set<String> namesInScopeAt(ASTNode creation) {
        Set<String> names = new HashSet<>();
        int at = creation.getStartPosition();
        for (ASTNode walk = creation.getParent(); walk != null; walk = walk.getParent()) {
            if (walk instanceof MethodDeclaration) {
                for (Object each : ((MethodDeclaration) walk).parameters()) {
                    names.add(((SingleVariableDeclaration) each).getName().getIdentifier());
                }
                break;
            }
            if (walk instanceof Block) {
                for (Object each : ((Block) walk).statements()) {
                    if (!(each instanceof VariableDeclarationStatement)) continue;
                    VariableDeclarationStatement declared = (VariableDeclarationStatement) each;
                    if (declared.getStartPosition() >= at) continue;
                    for (Object fragment : declared.fragments()) {
                        names.add(((VariableDeclarationFragment) fragment).getName().getIdentifier());
                    }
                }
            } else if (walk instanceof ForStatement) {
                for (Object each : ((ForStatement) walk).initializers()) {
                    if (!(each instanceof VariableDeclarationExpression)) continue;
                    for (Object fragment : ((VariableDeclarationExpression) each).fragments()) {
                        names.add(((VariableDeclarationFragment) fragment).getName().getIdentifier());
                    }
                }
            } else if (walk instanceof EnhancedForStatement) {
                names.add(((EnhancedForStatement) walk).getParameter().getName().getIdentifier());
            } else if (walk instanceof CatchClause) {
                names.add(((CatchClause) walk).getException().getName().getIdentifier());
            }
        }
        return names;
    }

    /** {@code name}, or {@code name1}, {@code name2}… — the first spelling nothing in scope has taken. */
    private static String freeName(String name, Set<String> taken) {
        if (!taken.contains(name)) return name;
        for (int suffix = 1; ; suffix++) {
            String candidate = name + suffix;
            if (!taken.contains(candidate)) return candidate;
        }
    }

    /**
     * Renames every parameter and body local that would clash once the body moves out of its own scope.
     *
     * <p>One mechanism for both, because both fail the same way: the declaration is resolved to its
     * binding and <em>every</em> {@code SimpleName} in the body that resolves to that binding is rewritten
     * with it. Renaming the declaration alone would compile to something that means something else.</p>
     */
    private static void renameClashes(MethodDeclaration method, Set<String> taken,
                                      List<String> parameters, List<Change> edits) {
        Set<String> used = new HashSet<>(taken);
        List<VariableDeclarationFragment> locals = new ArrayList<>();
        method.getBody().accept(new ASTVisitor() {
            @Override public boolean visit(VariableDeclarationFragment node) {
                locals.add(node);
                return true;
            }
        });

        for (Object each : method.parameters()) {
            SingleVariableDeclaration parameter = (SingleVariableDeclaration) each;
            String was = parameter.getName().getIdentifier();
            String now = freeName(was, used);
            used.add(now);
            parameters.add(now);
            if (!now.equals(was)) renameInBody(method, parameter.resolveBinding(), now, edits);
        }
        for (VariableDeclarationFragment local : locals) {
            String was = local.getName().getIdentifier();
            if (!taken.contains(was)) continue;
            String now = freeName(was, used);
            used.add(now);
            renameInBody(method, local.resolveBinding(), now, edits);
        }
        // ChangeSet.of requires them sorted and non-overlapping, and two bindings renamed in turn produce
        // two interleaved runs. Distinct names never overlap, so ordering is the whole of it.
        edits.sort((left, right) -> Integer.compare(left.from(), right.from()));
    }

    /**
     * <b>The BODY, never the whole method.</b> A parameter's own declaration sits in the part being
     * replaced by the lambda, so rewriting it there puts an edit inside a range that is already being
     * rewritten — and the two come out spliced together: renaming `left` to `left1` produced `1left` at
     * every use, which is an offset collision wearing the costume of a string bug. The lambda builds its
     * parameter list from scratch anyway, so the declaration never needed touching.
     */
    private static void renameInBody(MethodDeclaration method, IVariableBinding binding, String now,
                                     List<Change> edits) {
        if (binding == null) return;
        method.getBody().accept(new ASTVisitor() {
            @Override public boolean visit(SimpleName node) {
                IBinding resolved = node.resolveBinding();
                if (resolved != null && resolved.isEqualTo(binding)) {
                    edits.add(new Change(node.getStartPosition(),
                            node.getStartPosition() + node.getLength(), now));
                }
                return true;
            }
        });
    }

    // ── Ambiguity ───────────────────────────────────────────────────────────────────────────────

    /**
     * Whether the call this sits in needs the lambda's type spelled out.
     *
     * <p>Only an <b>argument</b> can be ambiguous — everywhere else the target type is written down. And
     * only against an overload set: measured, {@code take(Comparator)} beside {@code take(Runnable)}
     * converts cleanly because the arity decides it, while two interfaces of the same shape do not.</p>
     *
     * <p>IntelliJ writes the cast and then removes it when it turns out to have been unnecessary. We
     * cannot re-resolve inside a code-action request, so the test is the cheap one — more than one
     * candidate of that name and arity — which errs towards a cast that was not needed. That is the right
     * direction: an unnecessary cast is ugly and removable by {@code java.expression.removeCast}, while a
     * necessary one that is missing does not compile.</p>
     */
    private static boolean needsDisambiguatingCast(ClassInstanceCreation creation) {
        if (!(creation.getParent() instanceof MethodInvocation)) return false;
        MethodInvocation call = (MethodInvocation) creation.getParent();
        if (creation.getLocationInParent() != MethodInvocation.ARGUMENTS_PROPERTY) return false;
        IMethodBinding invoked = call.resolveMethodBinding();
        if (invoked == null) return false;
        ITypeBinding owner = invoked.getDeclaringClass();
        if (owner == null) return false;

        int candidates = 0;
        for (IMethodBinding each : owner.getDeclaredMethods()) {
            if (each.getName().equals(invoked.getName())
                    && each.getParameterTypes().length == invoked.getParameterTypes().length) {
                candidates++;
            }
        }
        return candidates >= 2;
    }
}
