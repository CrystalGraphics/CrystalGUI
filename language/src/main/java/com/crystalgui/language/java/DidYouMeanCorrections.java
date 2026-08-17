package com.crystalgui.language.java;

import com.crystalgui.text.SimilarNames;

import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.lang.CodeAction;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.Initializer;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.VariableDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Change to 'String'" — the corrections for a name that resolves to nothing but is a keystroke or two
 * from something that does.
 *
 * <h3>Where the candidates come from is the same split Import X drew</h3>
 *
 * <p>The tree knows what is in <em>scope</em>: the locals and parameters above the use, the fields and
 * methods on the receiver's type and its supertypes, the types declared in this file. Only the host knows
 * the classpath, so a misspelt type also asks {@code CodeActionContext.similarTypeNames}, and a
 * candidate that comes from there is written through an {@link ImportPlan} — renaming {@code Lst} to
 * {@code List} without importing it would trade one unresolved name for another.</p>
 *
 * <h3>Ranked by distance, capped, and offered rather than applied</h3>
 *
 * <p>{@link SimilarNames} decides "near". Nothing here is preferred: with two candidates at distance one
 * the popup must show both, and defaulting to the first would be a coin toss that edits the file — the
 * same rule Import X follows for the same reason. A method candidate that has an overload with the
 * call's arity ranks ahead of one that does not, because {@code s.lenght()} means {@code length()} and not
 * {@code lastIndexOf(int)}, whatever the distances say.</p>
 */
final class DidYouMeanCorrections {

    static final String CHANGE_TYPE = "java.didYouMean.type";
    static final String CHANGE_METHOD = "java.didYouMean.method";
    static final String CHANGE_NAME = "java.didYouMean.name";

    private DidYouMeanCorrections() {
    }

    static List<Correction> all() {
        return List.of(new ChangeToType(), new ChangeToMethod(), new ChangeToName());
    }

    // ── Types ───────────────────────────────────────────────────────────────────────────────────

    private static final class ChangeToType implements Correction {

        @Override public String id() {
            return CHANGE_TYPE;
        }

        @Override public int[] problems() {
            return new int[] {IProblem.UndefinedType};
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            Name node = context.enclosing(problem, Name.class);
            if (node == null) return;
            String typed = node.isSimpleName()
                    ? ((SimpleName) node).getIdentifier()
                    : ((QualifiedName) node).getName().getIdentifier();

            // simple name -> the qualified names that spell it. Insertion-ordered so the host's ranking
            // among packages survives; the file's own types go first because they need no import.
            Map<String, List<String>> bySimpleName = new LinkedHashMap<>();
            for (String declared : declaredTypeNames(context)) {
                bySimpleName.computeIfAbsent(declared, any -> new ArrayList<>()).add(declared);
            }
            for (String qualified : context.host().similarTypeNames(typed)) {
                String simple = qualified.substring(qualified.lastIndexOf('.') + 1);
                List<String> spellings = bySimpleName.computeIfAbsent(simple, any -> new ArrayList<>());
                if (!spellings.contains(qualified)) spellings.add(qualified);
            }

            for (String simple : SimilarNames.rank(typed, bySimpleName.keySet())) {
                List<String> spellings = bySimpleName.get(simple);
                for (String qualified : spellings) {
                    ImportPlan imports = context.importPlan();
                    String written = imports.nameFor(qualified);
                    ASTRewrite rewrite = context.rewrite();
                    AST ast = context.unit().getAST();
                    rewrite.replace(node, ast.newName(written), null);
                    ChangeSet edit = context.changesFrom(rewrite, imports);
                    if (edit == null) continue;
                    // Disambiguate only when there is something to disambiguate: two packages offering
                    // the same simple name each say which they are, one says nothing.
                    String title = spellings.size() > 1 && qualified.contains(".")
                            ? "Change to '" + simple + "' (" + qualified.substring(0, qualified.lastIndexOf('.')) + ")"
                            : "Change to '" + simple + "'";
                    out.add(context.fix(CHANGE_TYPE, title, edit));
                }
            }
        }

        /** Every type declared in this file, by simple name — candidates that need no import. */
        private static Set<String> declaredTypeNames(FixContext context) {
            Set<String> names = new LinkedHashSet<>();
            context.unit().accept(new ASTVisitor() {
                @Override public void preVisit(ASTNode node) {
                    if (node instanceof AbstractTypeDeclaration) {
                        names.add(((AbstractTypeDeclaration) node).getName().getIdentifier());
                    }
                }
            });
            return names;
        }
    }

    // ── Methods ─────────────────────────────────────────────────────────────────────────────────

    private static final class ChangeToMethod implements Correction {

        @Override public String id() {
            return CHANGE_METHOD;
        }

        @Override public int[] problems() {
            return new int[] {IProblem.UndefinedMethod};
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            SimpleName name = context.enclosing(problem, SimpleName.class);
            if (name == null) return;
            ASTNode call = name.getParent();
            ITypeBinding receiver;
            int arity;
            if (call instanceof MethodInvocation && ((MethodInvocation) call).getName() == name) {
                MethodInvocation invocation = (MethodInvocation) call;
                receiver = invocation.getExpression() == null
                        ? Scopes.enclosingTypeBinding(invocation)
                        : invocation.getExpression().resolveTypeBinding();
                arity = invocation.arguments().size();
            } else if (call instanceof SuperMethodInvocation && ((SuperMethodInvocation) call).getName() == name) {
                ITypeBinding here = Scopes.enclosingTypeBinding(call);
                receiver = here == null ? null : here.getSuperclass();
                arity = ((SuperMethodInvocation) call).arguments().size();
            } else {
                return;
            }
            if (receiver == null) return;

            ITypeBinding here = Scopes.enclosingTypeBinding(call);
            Map<String, Boolean> arityMatch = new LinkedHashMap<>();   // name -> has an overload of that arity
            Set<String> visited = new LinkedHashSet<>();
            collectMethods(receiver, here, arity, arityMatch, visited);
            // An interface type has Object's methods too, and no superclass link to reach them by.
            if (receiver.isInterface()) {
                collectMethods(context.unit().getAST().resolveWellKnownType("java.lang.Object"),
                        here, arity, arityMatch, visited);
            }

            List<String> ranked = new ArrayList<>(SimilarNames.rank(name.getIdentifier(), arityMatch.keySet()));
            // Stable: distance decides, then "takes this many arguments" promotes within equal distance.
            ranked.sort((a, b) -> Boolean.compare(!arityMatch.get(a), !arityMatch.get(b)));

            for (String candidate : ranked) {
                ASTRewrite rewrite = context.rewrite();
                rewrite.replace(name, context.unit().getAST().newSimpleName(candidate), null);
                ChangeSet edit = context.changesFrom(rewrite);
                if (edit == null) continue;
                out.add(context.fix(CHANGE_METHOD, "Change to '" + candidate + "()'", edit));
            }
        }

        private static void collectMethods(ITypeBinding type, ITypeBinding here, int arity,
                                           Map<String, Boolean> into, Set<String> visited) {
            if (type == null) return;
            ITypeBinding erasure = type.getErasure() == null ? type : type.getErasure();
            if (!visited.add(erasure.getKey())) return;
            for (IMethodBinding method : erasure.getDeclaredMethods()) {
                if (method.isConstructor() || method.isSynthetic()) continue;
                if (!visibleFrom(method.getModifiers(), method.getDeclaringClass(), here)) continue;
                boolean matches = method.getParameterTypes().length == arity || method.isVarargs();
                into.merge(method.getName(), matches, Boolean::logicalOr);
            }
            collectMethods(erasure.getSuperclass(), here, arity, into, visited);
            for (ITypeBinding each : erasure.getInterfaces()) collectMethods(each, here, arity, into, visited);
        }
    }

    // ── Names: locals, parameters and fields ────────────────────────────────────────────────────

    private static final class ChangeToName implements Correction {

        @Override public String id() {
            return CHANGE_NAME;
        }

        @Override public int[] problems() {
            return new int[] {IProblem.UnresolvedVariable, IProblem.UndefinedName, IProblem.UndefinedField};
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            SimpleName name = context.enclosing(problem, SimpleName.class);
            if (name == null) return;
            ITypeBinding here = Scopes.enclosingTypeBinding(name);

            Set<String> candidates = new LinkedHashSet<>();
            ASTNode parent = name.getParent();
            if (parent instanceof FieldAccess && ((FieldAccess) parent).getName() == name) {
                collectFields(((FieldAccess) parent).getExpression().resolveTypeBinding(),
                        here, candidates, new LinkedHashSet<>());
            } else if (parent instanceof QualifiedName && ((QualifiedName) parent).getName() == name) {
                collectFields(((QualifiedName) parent).getQualifier().resolveTypeBinding(),
                        here, candidates, new LinkedHashSet<>());
            } else {
                collectLocalsAbove(name, candidates);
                collectFields(here, here, candidates, new LinkedHashSet<>());
            }

            for (String candidate : SimilarNames.rank(name.getIdentifier(), candidates)) {
                ASTRewrite rewrite = context.rewrite();
                rewrite.replace(name, context.unit().getAST().newSimpleName(candidate), null);
                ChangeSet edit = context.changesFrom(rewrite);
                if (edit == null) continue;
                out.add(context.fix(CHANGE_NAME, "Change to '" + candidate + "'", edit));
            }
        }

        /**
         * Every local and parameter declared above {@code use} in the same method, lambda or initialiser.
         *
         * <p>"Above" is by position, not by block — a name declared in an earlier sibling block is offered
         * too and would then fail to resolve. Accepted rather than solved: the true scope walk is what
         * the compiler does, and a candidate that turns out to be out of scope produces one honest error
         * where there was one before, not a silent edit.</p>
         */
        private static void collectLocalsAbove(SimpleName use, Set<String> into) {
            ASTNode scope = Scopes.enclosingNameScope(use);
            if (scope == null) return;
            int before = use.getStartPosition();
            scope.accept(new ASTVisitor() {
                @Override public void preVisit(ASTNode node) {
                    if (node instanceof VariableDeclaration && node.getStartPosition() < before) {
                        into.add(((VariableDeclaration) node).getName().getIdentifier());
                    }
                }
            });
        }

        private static void collectFields(ITypeBinding type, ITypeBinding here, Set<String> into,
                                          Set<String> visited) {
            if (type == null) return;
            ITypeBinding erasure = type.getErasure() == null ? type : type.getErasure();
            if (!visited.add(erasure.getKey())) return;
            for (IVariableBinding field : erasure.getDeclaredFields()) {
                if (field.isSynthetic()) continue;
                if (!visibleFrom(field.getModifiers(), field.getDeclaringClass(), here)) continue;
                into.add(field.getName());
            }
            collectFields(erasure.getSuperclass(), here, into, visited);
            for (ITypeBinding each : erasure.getInterfaces()) collectFields(each, here, into, visited);
        }
    }

    // ── Shared ──────────────────────────────────────────────────────────────────────────────────

    /** Whether a member with these modifiers on {@code owner} may be named from inside {@code here}. */
    private static boolean visibleFrom(int modifiers, ITypeBinding owner, ITypeBinding here) {
        if (!Modifier.isPrivate(modifiers)) return true;
        if (owner == null || here == null) return false;
        // Private members are visible throughout the top-level type that declares them.
        return topLevelOf(owner).getKey().equals(topLevelOf(here).getKey());
    }

    private static ITypeBinding topLevelOf(ITypeBinding type) {
        ITypeBinding at = type;
        while (at.getDeclaringClass() != null) at = at.getDeclaringClass();
        return at;
    }
}
