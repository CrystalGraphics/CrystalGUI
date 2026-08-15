package com.crystalgui.language.java;

import com.crystalgui.text.Change;
import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.lang.CodeAction;
import com.crystalgui.text.lang.CodeActionKind;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ChildListPropertyDescriptor;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.ImportDeclaration;
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
                        FieldDeclaration.FRAGMENTS_PROPERTY, "Remove field "));
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
}
