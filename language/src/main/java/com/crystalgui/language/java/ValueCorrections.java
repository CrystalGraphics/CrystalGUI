package com.crystalgui.language.java;

import com.crystalgui.text.Change;
import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.lang.CodeAction;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * <b>Something has no value, or has no declaration</b> — and the repair is to write the missing one.
 *
 * <h3>Why these four are one family</h3>
 *
 * <p>They are the errors you make while <em>writing</em> rather than while reviewing: a method whose last
 * branch forgets to return, a local declared and used before it is given anything, a name assigned before
 * it is declared. Two of them need a type they can infer and two need a value of a type they already know,
 * and both halves are {@link TypeNames}.</p>
 *
 * <p><b>The coverage probe cannot see any of them, and that is the point.</b> It walks this repository,
 * which contains only code somebody finished — a file that compiles has no missing return by definition.
 * Their absence from that histogram is a fact about the corpus, not about how often anyone hits them, and
 * mistaking the first for the second is exactly how a catalogue comes to cover what is easy to measure.</p>
 *
 * <h3>Where the line is drawn</h3>
 *
 * <p>Create-local and create-field fire on an <b>assignment</b> — {@code total = 5;} — and not on a bare
 * use. An assignment carries the type on its right-hand side, so the declaration written is the one the
 * author was in the middle of writing. A bare {@code println(total)} carries nothing: the type would have
 * to come from the parameter it is passed to, which is the same inference {@code CreateCorrections} refuses
 * for a lambda argument and for the same reason — a signature that looks finished and still does not fit is
 * worse than no offer.</p>
 */
final class ValueCorrections {

    static final String ADD_RETURN = "java.value.addReturn";
    static final String INITIALISE = "java.value.initialise";
    static final String CREATE_LOCAL = "java.value.createLocal";
    static final String CREATE_FIELD = "java.value.createField";
    static final String INITIALISE_FIELD = "java.value.initialiseField";

    private ValueCorrections() {
    }

    static List<Correction> all() {
        return List.of(new AddReturnStatement(), new InitialiseVariable(),
                new CreateLocalVariable(), new CreateField(), new InitialiseBlankFinalField());
    }

    // ── A value for a type that is known ────────────────────────────────────────────────────────

    /**
     * "Add return statement" — a method that can reach its end without returning.
     *
     * <p>ECJ reports this on the method's <b>name and parameter list</b>, which is where the promise was
     * made rather than where it was broken, and is right: there is no single statement at fault. The
     * statement goes at the end of the body, which is the only place that is correct for every shape of
     * missing return — appending after whatever branches exist cannot make an existing path unreachable,
     * while inserting into a branch would be guessing which one the author meant.</p>
     */
    private static final class AddReturnStatement implements Correction {

        @Override public String id() {
            return ADD_RETURN;
        }

        @Override public int[] problems() {
            return new int[] {IProblem.ShouldReturnValue};
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            MethodDeclaration method = context.enclosing(problem, MethodDeclaration.class);
            if (method == null || method.getBody() == null || method.getReturnType2() == null) return;
            ITypeBinding returns = method.getReturnType2().resolveBinding();
            String value = TypeNames.defaultValue(returns);
            if (value == null) return;

            ASTRewrite rewrite = context.rewrite();
            ListRewrite statements = rewrite.getListRewrite(method.getBody(), Block.STATEMENTS_PROPERTY);
            statements.insertLast(
                    rewrite.createStringPlaceholder("return " + value + ";", ASTNode.RETURN_STATEMENT), null);
            ChangeSet edit = context.changesFrom(rewrite);
            if (edit == null) return;
            out.add(context.preferredFix(ADD_RETURN, "Add return statement", edit));
        }
    }

    /**
     * "Initialize variable 'a'" — a local read before anything was put in it.
     *
     * <p>Reported at the <b>use</b>, not the declaration, so the declaration is found through the binding
     * rather than by position. That matters: the read that ECJ picks is whichever one it reached first, and
     * on a variable used five times it is not the one nearest the declaration.</p>
     *
     * <p>The value is {@link TypeNames#defaultValue}, which is what the JVM would have used for a field of
     * that type — the only defensible default, and both references write the same one.</p>
     */
    private static final class InitialiseVariable implements Correction {

        @Override public String id() {
            return INITIALISE;
        }

        @Override public int[] problems() {
            return new int[] {IProblem.UninitializedLocalVariable};
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            SimpleName use = context.enclosing(problem, SimpleName.class);
            if (use == null || !(use.resolveBinding() instanceof IVariableBinding)) return;
            IVariableBinding variable = (IVariableBinding) use.resolveBinding();
            VariableDeclarationFragment fragment = declarationOf(context, variable);
            if (fragment == null || fragment.getInitializer() != null) return;
            String value = TypeNames.defaultValue(variable.getType());
            if (value == null) return;

            // ONE ACTION PER VARIABLE. A local read three times before it is assigned is three problems and
            // one declaration, and three identical rows in the popup would be three ways to do the same
            // edit — which is what `claim` exists for.
            if (!context.claim(INITIALISE + "@" + fragment.getStartPosition())) return;

            ASTRewrite rewrite = context.rewrite();
            rewrite.set(fragment, VariableDeclarationFragment.INITIALIZER_PROPERTY,
                    rewrite.createStringPlaceholder(value, ASTNode.SIMPLE_NAME), null);
            ChangeSet edit = context.changesFrom(rewrite);
            if (edit == null) return;
            out.add(context.preferredFix(INITIALISE,
                    "Initialize variable '" + variable.getName() + "'", edit));
        }

        /** The fragment declaring {@code variable}, found by binding rather than by position. */
        private static VariableDeclarationFragment declarationOf(FixContext context,
                                                                 IVariableBinding variable) {
            VariableDeclarationFragment[] found = {null};
            context.unit().accept(new ASTVisitor() {
                @Override public boolean visit(VariableDeclarationFragment candidate) {
                    IVariableBinding declared = candidate.resolveBinding();
                    if (found[0] == null && declared != null && declared.isEqualTo(variable)) {
                        found[0] = candidate;
                    }
                    return found[0] == null;
                }
            });
            return found[0];
        }
    }

    // ── A declaration for a name that has none ──────────────────────────────────────────────────

    /**
     * "Create local variable 'total'" — {@code total = 5;} becomes {@code int total = 5;}.
     *
     * <p><b>One inserted word, and deliberately not a rewrite.</b> The declaration wanted is the assignment
     * that is already written with a type in front of it, so the edit is an insertion at the name and the
     * initialiser is never touched — which keeps its formatting, its comments and its line breaks exactly as
     * typed. Expressing the same thing through {@code ASTRewrite} would regenerate the statement.</p>
     */
    private static final class CreateLocalVariable implements Correction {

        @Override public String id() {
            return CREATE_LOCAL;
        }

        @Override public int[] problems() {
            return new int[] {IProblem.UnresolvedVariable, IProblem.UndefinedName};
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            Assignment assignment = assignmentTo(context, problem);
            if (assignment == null) return;
            SimpleName name = (SimpleName) assignment.getLeftHandSide();
            ITypeBinding type = assignment.getRightHandSide().resolveTypeBinding();
            ImportPlan imports = context.importPlan();
            String written = TypeNames.writtenName(type, imports, assignment);
            if (written == null) return;
            if (!context.claim(CREATE_LOCAL + "@" + name.getStartPosition())) return;

            List<Change> changes = new ArrayList<>();
            changes.add(new Change(name.getStartPosition(), name.getStartPosition(), written + " "));
            ChangeSet edit = context.changeSet(changes, imports);
            if (edit == null) return;
            out.add(context.preferredFix(CREATE_LOCAL,
                    "Create local variable '" + name.getIdentifier() + "'", edit));
        }
    }

    /**
     * "Create field 'total'" — the same assignment, answered in the enclosing type instead.
     *
     * <p>Offered beside the local and never instead of it, because which one was meant is genuinely the
     * author's call and neither reference guesses: IntelliJ lists both and puts the local first, which is
     * what {@code preferredFix} on the other one does here.</p>
     *
     * <p><b>Placed after the last existing field</b>, or first in the body when there are none. Not at the
     * very top: a type that opens with its constants and then grows a generated field above them reads as
     * having been reorganised, which is a bigger edit than the one that was asked for.</p>
     */
    private static final class CreateField implements Correction {

        @Override public String id() {
            return CREATE_FIELD;
        }

        @Override public int[] problems() {
            return new int[] {IProblem.UnresolvedVariable, IProblem.UndefinedName,
                    IProblem.UndefinedField};
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            Assignment assignment = assignmentTo(context, problem);
            if (assignment == null) return;
            SimpleName name = (SimpleName) assignment.getLeftHandSide();
            AbstractTypeDeclaration owner = Scopes.enclosingTypeDeclaration(assignment);
            if (!(owner instanceof TypeDeclaration)) return;

            ITypeBinding type = assignment.getRightHandSide().resolveTypeBinding();
            ImportPlan imports = context.importPlan();
            String written = TypeNames.writtenName(type, imports, assignment);
            if (written == null) return;
            if (!context.claim(CREATE_FIELD + "@" + name.getStartPosition())) return;

            // STATIC IF THE SITE IS. A field generated from an assignment inside a static method must be
            // static too, or the fix trades "cannot be resolved" for "cannot make a static reference to a
            // non-static field" — a different error in the same place, which reads as the fix not working.
            boolean isStatic = Scopes.isStaticContext(assignment);
            String declaration = "private " + (isStatic ? "static " : "")
                    + written + " " + name.getIdentifier() + ";";

            ASTRewrite rewrite = context.rewrite();
            ListRewrite body = rewrite.getListRewrite(owner, TypeDeclaration.BODY_DECLARATIONS_PROPERTY);
            ASTNode placeholder = rewrite.createStringPlaceholder(declaration, ASTNode.FIELD_DECLARATION);
            BodyDeclaration lastField = lastFieldOf((TypeDeclaration) owner);
            if (lastField == null) {
                body.insertFirst(placeholder, null);
            } else {
                body.insertAfter(placeholder, lastField, null);
            }
            ChangeSet edit = context.changesFrom(rewrite, imports);
            if (edit == null) return;
            out.add(context.fix(CREATE_FIELD, "Create field '" + name.getIdentifier() + "'", edit));
        }

        private static FieldDeclaration lastFieldOf(TypeDeclaration owner) {
            FieldDeclaration last = null;
            for (Object each : owner.bodyDeclarations()) {
                if (each instanceof FieldDeclaration) last = (FieldDeclaration) each;
            }
            return last;
        }
    }

    // ── Shared ──────────────────────────────────────────────────────────────────────────────────

    /**
     * The plain {@code name = value} the problem is about, or null for every other shape.
     *
     * <p>Three refusals, and each is a case where a declaration cannot be written from what is there.
     * A compound assignment ({@code total += 1}) reads the variable before it writes it, so declaring it
     * here would still not compile. A {@code void} right-hand side has no type to declare. And a bare use
     * has no right-hand side at all.</p>
     */
    private static Assignment assignmentTo(FixContext context, IProblem problem) {
        SimpleName name = context.enclosing(problem, SimpleName.class);
        if (name == null || !(name.getParent() instanceof Assignment)) return null;
        Assignment assignment = (Assignment) name.getParent();
        if (assignment.getLeftHandSide() != name) return null;
        if (assignment.getOperator() != Assignment.Operator.ASSIGN) return null;
        if (!(assignment.getParent() instanceof ExpressionStatement)) return null;
        ITypeBinding type = assignment.getRightHandSide().resolveTypeBinding();
        return type == null || "void".equals(type.getName()) || type.isNullType() ? null : assignment;
    }


    /**
     * "Initialize field 'a'" — a blank {@code final} field no constructor assigns.
     *
     * <h3>Reported on the CONSTRUCTOR, and answered at the declaration</h3>
     *
     * <p>ECJ marks the constructor that failed to assign it, which is where the obligation was broken and
     * not where it can be met once: a type with three constructors reports three problems and giving the
     * field a value at its declaration answers all of them. The field is found from the problem's own
     * arguments being useless here — it comes from the binding of what the message names — so it is
     * located by walking the enclosing type for a {@code final} field with no initialiser instead.</p>
     *
     * <h3>Refused when any constructor already assigns it</h3>
     *
     * <p>A {@code final} field may be assigned exactly once. Initialising at the declaration while some
     * other constructor assigns it turns "may not have been initialized" into "may already have been
     * assigned" — a different error, in a place the caret never was, and only in the constructor that was
     * previously correct.</p>
     */
    private static final class InitialiseBlankFinalField implements Correction {

        @Override public String id() {
            return INITIALISE_FIELD;
        }

        @Override public int[] problems() {
            return new int[] {IProblem.UninitializedBlankFinalField};
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            MethodDeclaration constructor = context.enclosing(problem, MethodDeclaration.class);
            if (constructor == null || !constructor.isConstructor()) return;
            AbstractTypeDeclaration owner = Scopes.enclosingTypeDeclaration(constructor);
            if (!(owner instanceof TypeDeclaration)) return;

            for (Object each : ((TypeDeclaration) owner).bodyDeclarations()) {
                if (!(each instanceof FieldDeclaration)) continue;
                FieldDeclaration field = (FieldDeclaration) each;
                if (!Modifier.isFinal(field.getModifiers())) continue;
                if (field.fragments().size() != 1) continue;
                VariableDeclarationFragment fragment =
                        (VariableDeclarationFragment) field.fragments().get(0);
                if (fragment.getInitializer() != null) continue;
                IVariableBinding declared = fragment.resolveBinding();
                if (declared == null || assignedAnywhereIn(owner, declared)) continue;

                String value = TypeNames.defaultValue(declared.getType());
                if (value == null) continue;
                if (!context.claim(INITIALISE_FIELD + "@" + fragment.getStartPosition())) continue;

                ASTRewrite rewrite = context.rewrite();
                rewrite.set(fragment, VariableDeclarationFragment.INITIALIZER_PROPERTY,
                        rewrite.createStringPlaceholder(value, ASTNode.SIMPLE_NAME), null);
                ChangeSet edit = context.changesFrom(rewrite);
                if (edit == null) continue;
                out.add(context.preferredFix(INITIALISE_FIELD,
                        "Initialize field '" + fragment.getName().getIdentifier() + "'", edit));
                return;
            }
        }

        /**
         * Whether any constructor or initialiser in this type assigns <b>this field</b>.
         *
         * <p>By binding, and that is the whole of it: asked by NAME, a local called {@code total} in any
         * method of the type answered yes, so "Initialize field 'total'" was refused for a field nothing
         * had ever assigned. The name is what the two spellings have in common and is exactly what does
         * not identify the field.</p>
         */
        private static boolean assignedAnywhereIn(AbstractTypeDeclaration owner, IVariableBinding field) {
            boolean[] found = {false};
            owner.accept(new ASTVisitor() {
                @Override public boolean visit(Assignment assignment) {
                    if (isTheField(assignment.getLeftHandSide())) found[0] = true;
                    return !found[0];
                }

                private boolean isTheField(Expression target) {
                    IBinding bound = null;
                    if (target instanceof Name) bound = ((Name) target).resolveBinding();
                    if (target instanceof FieldAccess) bound = ((FieldAccess) target).resolveFieldBinding();
                    if (target instanceof SuperFieldAccess) {
                        bound = ((SuperFieldAccess) target).resolveFieldBinding();
                    }
                    return bound instanceof IVariableBinding && ((IVariableBinding) bound).isEqualTo(field);
                }
            });
            return found[0];
        }
    }
}
