package com.crystalgui.language.java;

import com.crystalgui.text.Change;
import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.lang.CodeAction;
import com.crystalgui.text.lang.CodeActionKind;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.ChildListPropertyDescriptor;
import org.eclipse.jdt.core.dom.EmptyStatement;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.StructuralPropertyDescriptor;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.TypeParameter;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Corrections for things the compiler says are declared and never used.
 *
 * <p>A family file: every correction here answers an {@code unused*} problem, and a new one is an entry
 * in this file rather than an edit to anything shared. The family is the unit of grouping because that is
 * how they are read — someone adding "remove unused private method" is looking at the other removals, not
 * at unrelated corrections that happen to have been written the same week.</p>
 */
final class UnusedCorrections {

    static final String REMOVE_IMPORT = "java.unused.removeImport";
    static final String REMOVE_IMPORTS = "java.unused.removeImports";
    static final String REMOVE_LOCAL = "java.unused.removeLocal";
    static final String REMOVE_FIELD = "java.unused.removeField";
    static final String REMOVE_METHOD = "java.unused.removeMethod";
    static final String REMOVE_CONSTRUCTOR = "java.unused.removeConstructor";
    static final String REMOVE_TYPE = "java.unused.removeType";
    static final String REMOVE_SEMICOLON = "java.unused.removeSemicolon";
    static final String REMOVE_THROWS = "java.unused.removeThrows";
    static final String REMOVE_SUPERINTERFACE = "java.unused.removeSuperinterface";
    static final String REMOVE_TYPE_PARAMETER = "java.unused.removeTypeParameter";

    private UnusedCorrections() {
    }

    static List<Correction> all() {
        return List.of(
                new RemoveUnusedImport(),
                new RemoveAllUnusedImports(),
                new RemoveUnusedDeclaration(REMOVE_LOCAL, IProblem.LocalVariableIsNeverUsed,
                        VariableDeclarationStatement.class,
                        VariableDeclarationStatement.FRAGMENTS_PROPERTY, "Remove variable "),
                new RemoveUnusedDeclaration(REMOVE_FIELD, IProblem.UnusedPrivateField,
                        FieldDeclaration.class,
                        FieldDeclaration.FRAGMENTS_PROPERTY, "Remove field "),
                new RemoveUnusedMember(REMOVE_METHOD, IProblem.UnusedPrivateMethod),
                new RemoveUnusedMember(REMOVE_CONSTRUCTOR, IProblem.UnusedPrivateConstructor),
                new RemoveUnusedMember(REMOVE_TYPE, IProblem.UnusedPrivateType),
                new RemoveSuperfluousSemicolon(),
                new RemoveListElement(REMOVE_THROWS,
                        new int[] {IProblem.UnusedMethodDeclaredThrownException,
                                   IProblem.UnusedConstructorDeclaredThrownException},
                        Type.class, "Remove '%s' from throws"),
                new RemoveListElement(REMOVE_SUPERINTERFACE,
                        new int[] {IProblem.RedundantSuperinterface},
                        Type.class, "Remove redundant interface '%s'"),
                new RemoveListElement(REMOVE_TYPE_PARAMETER,
                        new int[] {IProblem.UnusedTypeParameter},
                        TypeParameter.class, "Remove type parameter '%s'"));
    }

    // ── Imports ─────────────────────────────────────────────────────────────────────────────────

    /**
     * "Remove unused import" — the one you are looking at.
     *
     * <p>Whole lines, computed rather than rewritten. {@link ImportRegion} records why the import region
     * is the one part of a file JDT's rewriter is not used on.</p>
     */
    private static final class RemoveUnusedImport implements Correction {

        @Override public String id() {
            return REMOVE_IMPORT;
        }

        @Override public int[] problems() {
            return new int[] {IProblem.UnusedImport};
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            ImportDeclaration declaration = context.enclosing(problem, ImportDeclaration.class);
            if (declaration == null) return;
            out.add(context.preferredFix(REMOVE_IMPORT, "Remove unused import",
                    context.changeSet(ImportRegion.deletion(context.source(), declaration))));
        }
    }

    /**
     * "Remove unused imports" — all of them, and a different intention rather than the same one with a
     * count.
     *
     * <p>You either meant this line or you meant to tidy the file, so IntelliJ offers both and so do we.
     * Deliberately not preferred: a fix that edits lines you were not looking at should be chosen rather
     * than defaulted to. A separate {@link Correction} rather than a second action from the one above,
     * because two intentions with two ids are two corrections — that is what an id names.</p>
     */
    private static final class RemoveAllUnusedImports implements Correction {

        @Override public String id() {
            return REMOVE_IMPORTS;
        }

        @Override public int[] problems() {
            return new int[] {IProblem.UnusedImport};
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            List<ImportDeclaration> unused = new ArrayList<>();
            for (IProblem each : context.unit().getProblems()) {
                if (each.getID() != IProblem.UnusedImport) continue;
                ImportDeclaration declaration = context.enclosing(each, ImportDeclaration.class);
                if (declaration != null && !unused.contains(declaration)) unused.add(declaration);
            }
            if (unused.size() < 2) return;

            List<Change> changes = new ArrayList<>(unused.size());
            for (ImportDeclaration each : unused) changes.add(ImportRegion.deletion(context.source(), each));
            // SORTED, because ChangeSet.of REQUIRES it rather than normalising -- two overlapping changes
            // have no defined combined meaning, so it refuses them instead of letting iteration order
            // decide. Tree order is document order here, but that is a property of this loop rather than
            // a guarantee, and the sort costs nothing.
            changes.sort(Comparator.comparingInt(Change::from));
            out.add(context.action(REMOVE_IMPORTS, "Remove unused imports",
                    CodeActionKind.SOURCE, context.changeSet(changes)));
        }
    }

    // ── Locals and fields ───────────────────────────────────────────────────────────────────────

    /**
     * "Remove variable 's'" / "Remove field 'x'" — one class, two registrations.
     *
     * <p>The two differ only in which problem they answer, which node encloses the name and what the
     * title calls it. Parameterising is what keeps the pair from drifting: the multi-name handling below
     * was written once and both get it.</p>
     *
     * <p><b>A declaration that declares several names loses only the unused one.</b>
     * {@code int a = 1, b = 2;} becomes {@code int a = 1;} — the comma goes with it and nothing here says
     * where the comma was. This was refused for as long as a fix was a computed range, on the correct
     * reasoning that deleting the statement would take {@code a} with it; what changed is not the
     * reasoning but what the substrate can express.</p>
     *
     * <p><b>The name comes from the declaration, never from the problem's arguments.</b>
     * {@code UnusedPrivateField} leads with the declaring <em>type</em>, so reading argument zero titled
     * this "Remove field 'Script'" for a field named {@code count}. The fragment has to be found anyway
     * for the list case, and its own name node cannot be anything but the name being removed.</p>
     */
    private static final class RemoveUnusedDeclaration implements Correction {

        private final String id;
        private final int problem;
        private final Class<? extends ASTNode> declarationType;
        private final ChildListPropertyDescriptor fragments;
        private final String titlePrefix;

        RemoveUnusedDeclaration(String id, int problem, Class<? extends ASTNode> declarationType,
                                ChildListPropertyDescriptor fragments, String titlePrefix) {
            this.id = id;
            this.problem = problem;
            this.declarationType = declarationType;
            this.fragments = fragments;
            this.titlePrefix = titlePrefix;
        }

        @Override public String id() {
            return id;
        }

        @Override public int[] problems() {
            return new int[] {problem};
        }

        @Override public void contribute(FixContext context, IProblem reported, List<CodeAction> out) {
            ASTNode declaration = context.enclosing(reported, declarationType);
            if (declaration == null) return;
            VariableDeclarationFragment fragment =
                    context.enclosing(reported, VariableDeclarationFragment.class);
            if (fragment == null) return;

            ASTRewrite rewrite = context.rewrite();
            List<?> declared = (List<?>) declaration.getStructuralProperty(fragments);
            if (declared != null && declared.size() > 1) {
                rewrite.getListRewrite(declaration, fragments).remove(fragment, null);
            } else {
                rewrite.remove(declaration, null);
            }

            ChangeSet edit = context.changesFrom(rewrite);
            if (edit == null) return;
            out.add(context.preferredFix(id,
                    titlePrefix + "'" + fragment.getName().getIdentifier() + "'", edit));
        }
    }

    // ── Whole members ───────────────────────────────────────────────────────────────────────────

    /**
     * "Remove method 'x'" / "Remove constructor 'X'" / "Remove class 'X'".
     *
     * <p>A private member nothing in the file refers to. One class for three registrations, because the
     * only thing that differs is the noun — and the noun is <b>read from the declaration</b> rather than
     * carried as a parameter, so a private {@code interface} says "interface" and an {@code enum} says
     * "enum" without three more entries. Getting that from a field would be three chances to write
     * "class" and mean any of them.</p>
     *
     * <p>Simpler than its variable counterpart above and for a structural reason: a method or a type is
     * one declaration of one thing, so there is no list to remove an element from. The whole node goes,
     * and JDT takes its line with it.</p>
     *
     * <p><b>ECJ reports these for private members only</b>, which is what makes the deletion safe to
     * offer: nothing outside the file can be referring to one. A package-private method with no callers
     * is not reported at all, and should not be — the compiler cannot see who else is on the classpath.</p>
     */
    private static final class RemoveUnusedMember implements Correction {

        private final String id;
        private final int problem;

        RemoveUnusedMember(String id, int problem) {
            this.id = id;
            this.problem = problem;
        }

        @Override public String id() {
            return id;
        }

        @Override public int[] problems() {
            return new int[] {problem};
        }

        @Override public void contribute(FixContext context, IProblem reported, List<CodeAction> out) {
            BodyDeclaration declaration = context.enclosing(reported, BodyDeclaration.class);
            if (declaration == null) return;
            String noun = nounFor(declaration);
            String name = nameOf(declaration);
            if (noun == null || name == null) return;

            ASTRewrite rewrite = context.rewrite();
            rewrite.remove(declaration, null);
            ChangeSet edit = context.changesFrom(rewrite);
            if (edit == null) return;
            out.add(context.preferredFix(id, "Remove " + noun + " '" + name + "'", edit));
        }

        /** What to call it, from what it is — a constructor is not a method and an enum is not a class. */
        private static String nounFor(BodyDeclaration declaration) {
            if (declaration instanceof MethodDeclaration) {
                return ((MethodDeclaration) declaration).isConstructor() ? "constructor" : "method";
            }
            if (declaration instanceof EnumDeclaration) return "enum";
            if (declaration instanceof AnnotationTypeDeclaration) return "annotation";
            if (declaration instanceof TypeDeclaration) {
                return ((TypeDeclaration) declaration).isInterface() ? "interface" : "class";
            }
            return null;
        }

        private static String nameOf(BodyDeclaration declaration) {
            if (declaration instanceof MethodDeclaration) {
                return ((MethodDeclaration) declaration).getName().getIdentifier();
            }
            if (declaration instanceof AbstractTypeDeclaration) {
                return ((AbstractTypeDeclaration) declaration).getName().getIdentifier();
            }
            return null;
        }
    }

    // ── One element of a list ───────────────────────────────────────────────────────────────────

    /**
     * "Remove 'IOException' from throws" / "Remove redundant interface 'Runnable'" /
     * "Remove type parameter 'U'" — the same operation three times, and the reason it is one class.
     *
     * <p>Each of these is an element of a comma-separated list on a declaration — {@code throws A, B},
     * {@code implements A, B}, {@code <T, U>} — and removing one means absorbing a comma, or removing the
     * keyword when the last one goes. That is what {@code ListRewrite} exists for and what a hand-computed
     * range cannot express, which is why the catalogue listed these behind a "separator helper": the
     * helper turned out to be the substrate, and the correction reduces to <em>find the element, ask which
     * list it is in, remove it from that list</em>. The list is read off the node's own
     * {@code getLocationInParent()}, so this does not even need to know whether it is looking at a method's
     * throws clause or an enum's implements clause.</p>
     */
    private static final class RemoveListElement implements Correction {

        private final String id;
        private final int[] problems;
        private final Class<? extends ASTNode> elementType;
        private final String titleFormat;

        RemoveListElement(String id, int[] problems, Class<? extends ASTNode> elementType,
                          String titleFormat) {
            this.id = id;
            this.problems = problems;
            this.elementType = elementType;
            this.titleFormat = titleFormat;
        }

        @Override public String id() {
            return id;
        }

        @Override public int[] problems() {
            return problems;
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            ASTNode element = context.enclosing(problem, elementType);
            if (element == null) return;
            StructuralPropertyDescriptor location = element.getLocationInParent();
            // Only an element OF A LIST can be removed this way. A `Type` node is also what a field's
            // declared type is, and that one is a single child -- offering to "remove" it would delete
            // the type from a declaration that needs one.
            if (!(location instanceof ChildListPropertyDescriptor)) return;

            ASTRewrite rewrite = context.rewrite();
            rewrite.getListRewrite(element.getParent(), (ChildListPropertyDescriptor) location)
                    .remove(element, null);
            ChangeSet edit = context.changesFrom(rewrite);
            if (edit == null) return;

            String shown = context.text(element).trim();
            out.add(context.preferredFix(id, String.format(titleFormat, shown), edit));
        }
    }

    // ── Nothing at all ──────────────────────────────────────────────────────────────────────────

    /**
     * "Remove redundant semicolon" — a {@code ;} that parses to an empty statement.
     *
     * <p>The smallest correction there is, and the only one whose node carries no name. Reported only
     * because {@code EcjProblemPolicy} switches {@code emptyStatement} on; ECJ leaves it at
     * {@code ignore}, which is the reason a fix for it would otherwise be dead code that looks alive.</p>
     *
     * <p>Removed through the rewriter rather than by deleting the reported range, so a {@code ;} sitting
     * alone on a line takes the line with it while one trailing a statement does not.</p>
     */
    private static final class RemoveSuperfluousSemicolon implements Correction {

        @Override public String id() {
            return REMOVE_SEMICOLON;
        }

        @Override public int[] problems() {
            return new int[] {IProblem.SuperfluousSemicolon};
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            EmptyStatement statement = context.enclosing(problem, EmptyStatement.class);
            if (statement == null) return;
            ASTRewrite rewrite = context.rewrite();
            rewrite.remove(statement, null);
            ChangeSet edit = context.changesFrom(rewrite);
            if (edit == null) return;
            out.add(context.preferredFix(REMOVE_SEMICOLON, "Remove redundant semicolon", edit));
        }
    }
}
