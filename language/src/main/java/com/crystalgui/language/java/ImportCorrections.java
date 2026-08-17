package com.crystalgui.language.java;

import com.crystalgui.text.Change;
import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.lang.CodeAction;
import com.crystalgui.text.lang.CodeActionKind;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.Comment;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ImportDeclaration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    static final String ORGANIZE = "java.imports.organize";

    private ImportCorrections() {
    }

    static List<Correction> all() {
        return List.of(new AddImport(), new OrganizeImports());
    }

    /**
     * "Organize imports" — drop the unused ones, sort the rest, group them the way IntelliJ does.
     *
     * <h3>The first intention</h3>
     *
     * <p>Not keyed on a problem: it is offered whenever the caret is <em>in the import region</em>, whether
     * or not anything there is wrong, because "tidy this" is a thing you ask for rather than a thing the
     * compiler complains about. Registered through the registry's one concession to that — an empty
     * {@code problems()} — so it ranks, merges and shows exactly like a fix.</p>
     *
     * <h3>IntelliJ's default layout, ported</h3>
     *
     * <p>Everything else first, then {@code javax.*} and {@code java.*} together, then the static imports;
     * a blank line between groups, alphabetical within one. Not because it is better than Eclipse's or
     * Google's — there is no better — but because a layout has to be <em>some</em> convention and this
     * project's reference for editor behaviour is IntelliJ.</p>
     *
     * <h3>Refused over a comment</h3>
     *
     * <p>The region is rebuilt as text, so a comment sitting between two imports would be deleted. A tidy
     * that removes somebody's note is not a tidy, so if any comment lies inside the region nothing is
     * offered — the file is not organised, and it says so by staying as it is.</p>
     */
    private static final class OrganizeImports implements Correction {

        @Override public String id() {
            return ORGANIZE;
        }

        @Override public int[] problems() {
            return Correction.NONE;
        }

        @Override public void contribute(FixContext context, IProblem none, List<CodeAction> out) {
            CompilationUnit unit = context.unit();
            String source = context.source();
            int[] region = ImportRegion.regionOf(unit, source);
            if (region == null) return;
            if (context.from() > region[1] || context.to() < region[0]) return;
            for (Object each : unit.getCommentList()) {
                int at = ((Comment) each).getStartPosition();
                if (at >= region[0] && at < region[1]) return;
            }

            Set<ImportDeclaration> unused = context.unusedImports();

            List<String> others = new ArrayList<>(), javax = new ArrayList<>(),
                    java = new ArrayList<>(), statics = new ArrayList<>();
            for (Object each : unit.imports()) {
                ImportDeclaration declaration = (ImportDeclaration) each;
                if (unused.contains(declaration)) continue;
                String name = declaration.getName().getFullyQualifiedName();
                String line = "import " + (declaration.isStatic() ? "static " : "") + name
                        + (declaration.isOnDemand() ? ".*" : "") + ";\n";
                if (declaration.isStatic()) statics.add(line);
                else if (name.startsWith("javax.")) javax.add(line);
                else if (name.startsWith("java.")) java.add(line);
                else others.add(line);
            }
            Collections.sort(others);
            Collections.sort(javax);
            Collections.sort(java);
            Collections.sort(statics);

            StringBuilder rebuilt = new StringBuilder();
            appendGroup(rebuilt, others);
            List<String> platform = new ArrayList<>(javax);
            platform.addAll(java);
            appendGroup(rebuilt, platform);
            appendGroup(rebuilt, statics);

            String current = source.substring(region[0], region[1]);
            if (rebuilt.toString().equals(current)) return;

            int end = region[1];
            // Emptied entirely: also take the blank line that separated the imports from what follows,
            // or `package demo;` is left two blank lines from the class.
            if (rebuilt.length() == 0 && end < source.length() && source.charAt(end) == '\n'
                    && region[0] >= 2 && source.startsWith("\n\n", region[0] - 2)) {
                end++;
            }
            out.add(context.action(ORGANIZE, "Organize imports", CodeActionKind.SOURCE,
                    ChangeSet.replace(source.length(), region[0], end, rebuilt.toString())));
        }

        private static void appendGroup(StringBuilder into, List<String> lines) {
            if (lines.isEmpty()) return;
            if (into.length() > 0) into.append('\n');
            for (String line : lines) into.append(line);
        }
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
