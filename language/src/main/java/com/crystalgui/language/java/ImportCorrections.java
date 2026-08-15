package com.crystalgui.language.java;

import com.crystalgui.text.Change;
import com.crystalgui.text.lang.CodeAction;
import com.crystalgui.text.lang.CodeActionKind;

import org.eclipse.jdt.core.compiler.IProblem;

import java.util.List;

/**
 * Corrections that add to the import region.
 *
 * <p>Separate from {@link UnusedCorrections} despite both touching imports, because a family is grouped
 * by the <em>problem</em> it answers rather than by the text it happens to edit: this one is about a name
 * that will not resolve, and is where "did you mean" and the rest of the unresolved-reference corrections
 * belong when they land.</p>
 */
final class ImportCorrections {

    static final String ADD_IMPORT = "java.imports.add";

    private ImportCorrections() {
    }

    static List<Correction> all() {
        return List.of(new AddImport());
    }

    /**
     * "Import java.util.List" — one action per candidate, which is the point.
     *
     * <p>The first correction whose answer is <b>several</b> actions rather than one, which is the case
     * the merge and the "More actions…" list were built for. None of them is preferred: with {@code List}
     * on the classpath four times over, defaulting to whichever the index happened to return first is a
     * coin toss that edits the file. IntelliJ shows the list and auto-applies only when there is exactly
     * one candidate.</p>
     *
     * <p>The <b>name</b> comes from the problem's own arguments and the <b>place</b> from the syntax tree.
     * Neither can come from the other: the compiler knows what did not resolve, and only the tree knows
     * where an import may legally be written. The candidates come from the host, which is the only side
     * with an index of the classpath.</p>
     */
    private static final class AddImport implements Correction {

        @Override public String id() {
            return ADD_IMPORT;
        }

        @Override public int[] problems() {
            // A family of two: the same missing type, reported differently depending on whether the name
            // appeared in code or in an import that resolves to nothing.
            return new int[] {IProblem.UndefinedType, IProblem.ImportNotFound};
        }

        @Override public void contribute(FixContext context, IProblem problem, List<CodeAction> out) {
            String name = context.reportedName(problem);
            if (name == null || name.isEmpty()) return;
            // The LAST segment: an unresolved `java.utl.List` reports the whole path, and what has to be
            // imported is a type rather than whatever the author mistyped in front of it.
            int dot = name.lastIndexOf('.');
            if (dot >= 0) name = name.substring(dot + 1);

            List<String> candidates = context.host().importCandidates(name);
            if (candidates == null || candidates.isEmpty()) return;

            int at = ImportRegion.insertOffset(context.unit(), context.source());
            for (String qualified : candidates) {
                if (ImportRegion.alreadyImported(context.unit(), qualified)) continue;
                out.add(context.action(ADD_IMPORT, "Import '" + qualified + "'", CodeActionKind.QUICK_FIX,
                        context.changeSet(Change.insert(at, "import " + qualified + ";\n"))));
            }
        }
    }
}
