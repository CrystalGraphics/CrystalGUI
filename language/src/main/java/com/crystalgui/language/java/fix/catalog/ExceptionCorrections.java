package com.crystalgui.language.java.fix.catalog;

import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.lang.CodeAction;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.ChildListPropertyDescriptor;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.ThrowStatement;
import org.eclipse.jdt.core.dom.TryStatement;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.UnionType;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.crystalgui.language.java.fix.Correction;
import com.crystalgui.language.java.fix.FixContext;
import com.crystalgui.language.java.fix.ast.Scopes;
import com.crystalgui.language.java.fix.edit.ImportPlan;
import com.crystalgui.language.java.fix.edit.Names;

/**
 * "Add 'IOException' to throws" and "Surround with try/catch" — the pair every unhandled checked
 * exception gets, and the first corrections that <em>generate</em> code rather than delete it.
 *
 * <h3>One statement, one pair — however many exceptions it throws</h3>
 *
 * <p>ECJ reports one problem per exception type, so {@code Class.forName(n).newInstance()} arrives as
 * three. Answering each would put three identical "Surround with try/catch" rows in the popup, so both
 * corrections gather <b>every</b> unhandled type in the enclosing statement and answer once per statement,
 * whichever of its problems the caret touched. The gathered set is also reduced by subtyping — {@code new FileReader(f).read()}
 * throws {@code FileNotFoundException} and {@code IOException}, and a multi-catch naming both is a
 * compile error, so only the supertype is caught.</p>
 *
 * <h3>What the generated code looks like, and why</h3>
 *
 * <p>{@code throw new RuntimeException(e);} in the catch, which is IntelliJ's default template: it does not
 * swallow the exception, and it does not pretend to know what recovery looks like. A declaration whose
 * variable is used afterwards is <b>split</b> — {@code FileReader r;} then {@code try { r = …; }} — because
 * wrapping the whole declaration would take the variable out of scope for everything below it, and the
 * split keeps definite assignment satisfied since the catch completes abruptly. That is what IntelliJ does
 * too, and it is what makes the fix usable on the single most common shape it is offered for.</p>
 *
 * <h3>Refusals</h3>
 *
 * <p>{@code throws} is refused inside a lambda body and inside an initialiser: the enclosing method is not
 * the callable that throws, and a {@code throws} added there compiles and lies. Try/catch is refused where
 * there is no statement to wrap — a field initialiser — and for a {@code var} declaration used later,
 * which cannot be split because {@code var} needs its initialiser.</p>
 */
@SuppressWarnings("unchecked")   // JDT's DOM lists are raw; every add below is to a list of the declared node type
public final class ExceptionCorrections {

    static final String ADD_THROWS = "java.exceptions.addThrows";
    static final String SURROUND_TRY_CATCH = "java.exceptions.surroundWithTryCatch";

    private ExceptionCorrections() {
    }

    public static List<Correction> all() {
        return List.of(new AddThrows(), new SurroundWithTryCatch());
    }

    // ── What is unhandled here ──────────────────────────────────────────────────────────────────

    /** The unhandled exception types of one statement, reduced, in the order ECJ reported them. */
    private record Unhandled(Statement statement, List<String> qualifiedNames) {
    }

    /**
     * The statement enclosing {@code problem} and every unhandled type in it — or null when this
     * statement has already answered {@code correction} in this request, so a statement answers once
     * however many of its exceptions the caret happens to touch. @see FixContext#claim
     */
    private static Unhandled gather(FixContext context, IProblem problem, String correction) {
        Statement statement = context.enclosing(problem, Statement.class);
        if (statement == null) return null;
        int start = statement.getStartPosition();
        int end = start + statement.getLength();
        if (!context.claim(correction + "@" + start)) return null;

        // The names, deduped, in the order reported.
        Set<String> names = new LinkedHashSet<>();
        for (IProblem each : context.unit().getProblems()) {
            if (each.getID() != IProblem.UnhandledException) continue;
            if (each.getSourceStart() < start || each.getSourceEnd() >= end) continue;
            String name = context.reportedName(each);
            if (name != null && !name.isEmpty()) names.add(name);
        }
        if (names.isEmpty()) return null;
        return new Unhandled(statement, reduceBySubtyping(statement, new ArrayList<>(names)));
    }

    /**
     * Drops any type that is a subtype of another in the set — a multi-catch naming both is an error.
     *
     * <p>Bindings are found by matching the reported names against the exception types of every call
     * inside the statement; a name that finds no binding is kept as it is, which is the conservative
     * answer.</p>
     */
    private static List<String> reduceBySubtyping(Statement statement, List<String> names) {
        Map<String, ITypeBinding> bindings = new LinkedHashMap<>();
        statement.accept(new ASTVisitor() {
            @Override public boolean visit(MethodInvocation node) {
                record(node.resolveMethodBinding());
                return true;
            }
            @Override public boolean visit(SuperMethodInvocation node) {
                record(node.resolveMethodBinding());
                return true;
            }
            @Override public boolean visit(ClassInstanceCreation node) {
                record(node.resolveConstructorBinding());
                return true;
            }
            @Override public boolean visit(ConstructorInvocation node) {
                record(node.resolveConstructorBinding());
                return true;
            }
            @Override public boolean visit(SuperConstructorInvocation node) {
                record(node.resolveConstructorBinding());
                return true;
            }
            @Override public boolean visit(ThrowStatement node) {
                ITypeBinding thrown = node.getExpression().resolveTypeBinding();
                if (thrown != null) bindings.putIfAbsent(thrown.getErasure().getQualifiedName(), thrown);
                return true;
            }
            private void record(IMethodBinding method) {
                if (method == null) return;
                for (ITypeBinding each : method.getExceptionTypes()) {
                    bindings.putIfAbsent(each.getErasure().getQualifiedName(), each);
                }
            }
        });
        List<String> kept = new ArrayList<>();
        for (String name : names) {
            ITypeBinding self = bindings.get(name);
            boolean subsumed = false;
            for (String other : names) {
                if (other.equals(name)) continue;
                ITypeBinding parent = bindings.get(other);
                if (self != null && parent != null && self.isSubTypeCompatible(parent)) {
                    subsumed = true;
                    break;
                }
            }
            if (!subsumed) kept.add(name);
        }
        return kept;
    }

    // ── throws ──────────────────────────────────────────────────────────────────────────────────

    private static final class AddThrows implements Correction {

        @Override public String id() {
            return ADD_THROWS;
        }

        @Override public int[] problems() {
            return new int[] {IProblem.UnhandledException};
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            Unhandled unhandled = gather(context, problem, ADD_THROWS);
            if (unhandled == null) return;
            MethodDeclaration method = enclosingMethod(unhandled.statement());
            if (method == null) return;

            ImportPlan imports = context.importPlan();
            ASTRewrite rewrite = context.rewrite();
            AST ast = context.unit().getAST();
            ListRewrite thrown = rewrite.getListRewrite(method, MethodDeclaration.THROWN_EXCEPTION_TYPES_PROPERTY);
            List<String> shown = new ArrayList<>();
            for (String qualified : unhandled.qualifiedNames()) {
                String written = imports.nameFor(qualified);
                thrown.insertLast(ast.newSimpleType(ast.newName(written)), null);
                shown.add("'" + written + "'");
            }
            ChangeSet edit = context.changesFrom(rewrite, imports);
            if (edit == null) return;
            out.add(context.fix(ADD_THROWS, "Add " + String.join(", ", shown) + " to throws", edit));
        }

        /**
         * The method or constructor whose signature may take the {@code throws} — or null when the
         * statement is inside a lambda or an initialiser, where the enclosing method is not the callable
         * that throws.
         */
        private static MethodDeclaration enclosingMethod(Statement statement) {
            return Scopes.enclosingMethod(statement,
                    Scopes.Stop.LAMBDA, Scopes.Stop.INITIALIZER, Scopes.Stop.ANONYMOUS);
        }
    }

    // ── try/catch ───────────────────────────────────────────────────────────────────────────────

    private static final class SurroundWithTryCatch implements Correction {

        @Override public String id() {
            return SURROUND_TRY_CATCH;
        }

        @Override public int[] problems() {
            return new int[] {IProblem.UnhandledException};
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            Unhandled unhandled = gather(context, problem, SURROUND_TRY_CATCH);
            if (unhandled == null) return;
            Statement statement = unhandled.statement();

            ImportPlan imports = context.importPlan();
            ASTRewrite rewrite = context.rewrite();
            AST ast = context.unit().getAST();
            String variable = freeExceptionName(statement);

            VariableDeclarationFragment usedLater = declarationUsedBelow(statement);
            if (usedLater != null) {
                if (!(statement.getLocationInParent() instanceof ChildListPropertyDescriptor)) return;
                VariableDeclarationStatement declaration = (VariableDeclarationStatement) statement;
                if (declaration.getType().isVar()) return;           // `var` cannot be split from its initialiser
                // `Type name;` stays where it was; `try { name = init; } catch …` follows it.
                VariableDeclarationFragment bare = ast.newVariableDeclarationFragment();
                bare.setName(ast.newSimpleName(usedLater.getName().getIdentifier()));
                VariableDeclarationStatement declared = ast.newVariableDeclarationStatement(bare);
                declared.setType((Type) rewrite.createCopyTarget(declaration.getType()));
                declared.modifiers().addAll(ast.newModifiers(declaration.getModifiers()));

                Assignment assignment = ast.newAssignment();
                assignment.setLeftHandSide(ast.newSimpleName(usedLater.getName().getIdentifier()));
                assignment.setRightHandSide((Expression) rewrite.createMoveTarget(usedLater.getInitializer()));
                ExpressionStatement assign = ast.newExpressionStatement(assignment);
                TryStatement tryStatement = tryAround(ast, assign, unhandled.qualifiedNames(), imports, variable);

                ListRewrite siblings = rewrite.getListRewrite(statement.getParent(),
                        (ChildListPropertyDescriptor) statement.getLocationInParent());
                siblings.insertAfter(tryStatement, statement, null);
                siblings.replace(statement, declared, null);
            } else {
                Statement moved = (Statement) rewrite.createMoveTarget(statement);
                rewrite.replace(statement, tryAround(ast, moved, unhandled.qualifiedNames(), imports, variable), null);
            }

            ChangeSet edit = context.changesFrom(rewrite, imports);
            if (edit == null) return;
            out.add(context.fix(SURROUND_TRY_CATCH, "Surround with try/catch", edit));
        }

        private static TryStatement tryAround(AST ast, Statement body, List<String> qualifiedNames,
                                              ImportPlan imports, String variable) {
            TryStatement tryStatement = ast.newTryStatement();
            Block block = ast.newBlock();
            block.statements().add(body);
            tryStatement.setBody(block);

            CatchClause clause = ast.newCatchClause();
            SingleVariableDeclaration exception = ast.newSingleVariableDeclaration();
            exception.setName(ast.newSimpleName(variable));
            if (qualifiedNames.size() == 1) {
                exception.setType(ast.newSimpleType(ast.newName(imports.nameFor(qualifiedNames.get(0)))));
            } else {
                UnionType union = ast.newUnionType();
                for (String qualified : qualifiedNames) {
                    union.types().add(ast.newSimpleType(ast.newName(imports.nameFor(qualified))));
                }
                exception.setType(union);
            }
            clause.setException(exception);

            Block handler = ast.newBlock();
            ThrowStatement rethrow = ast.newThrowStatement();
            ClassInstanceCreation wrapped = ast.newClassInstanceCreation();
            wrapped.setType(ast.newSimpleType(ast.newSimpleName("RuntimeException")));
            wrapped.arguments().add(ast.newSimpleName(variable));
            rethrow.setExpression(wrapped);
            handler.statements().add(rethrow);
            clause.setBody(handler);
            tryStatement.catchClauses().add(clause);
            return tryStatement;
        }

        /**
         * The single fragment this statement declares, if a statement below it in the same block reads
         * it — the case that has to be split rather than wrapped. Null otherwise.
         */
        private static VariableDeclarationFragment declarationUsedBelow(Statement statement) {
            if (!(statement instanceof VariableDeclarationStatement)) return null;
            VariableDeclarationStatement declaration = (VariableDeclarationStatement) statement;
            if (declaration.fragments().size() != 1) return null;
            VariableDeclarationFragment fragment = (VariableDeclarationFragment) declaration.fragments().get(0);
            if (fragment.getInitializer() == null) return null;
            IVariableBinding binding = fragment.resolveBinding();
            if (binding == null) return null;
            if (!(statement.getParent() instanceof Block)) return null;

            Block block = (Block) statement.getParent();
            int after = statement.getStartPosition() + statement.getLength();
            boolean[] used = {false};
            block.accept(new ASTVisitor() {
                @Override public boolean visit(SimpleName name) {
                    if (name.getStartPosition() >= after && binding.isEqualTo(name.resolveBinding())) used[0] = true;
                    return true;
                }
            });
            return used[0] ? fragment : null;
        }

        /** {@code e}, unless something in the enclosing method is already called that; then {@code ex}, then {@code e1}… */
        private static String freeExceptionName(Statement statement) {
            ASTNode scope = Scopes.enclosingNameScope(statement);
            return Names.free(scope == null ? Set.of() : Names.declaredIn(scope), "e", "ex");
        }
    }
}
