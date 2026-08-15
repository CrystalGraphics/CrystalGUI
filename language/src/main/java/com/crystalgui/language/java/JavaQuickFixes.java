package com.crystalgui.language.java;

import com.crystalgui.text.Change;
import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.lang.CodeAction;
import com.crystalgui.text.lang.CodeActionKind;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The error → fix table, and the only place in this engine that has one.
 *
 * <h3>Keyed on the problem id, which is the only thing that can key it</h3>
 *
 * <p>Eclipse's own {@code IQuickFixProcessor} is a switch on {@code IProblem.getID()}, and the reason is
 * that a problem's <em>identity</em> is the only durable statement of what it means — its message is prose
 * that changes between releases and is localised. {@code EcjSourceAnalyzer} already puts that id into
 * {@code Diagnostic.code}, so nothing new had to be threaded through the analyzer to make this possible.</p>
 *
 * <p><b>Named constants, never numeric literals and never ranges.</b> {@code IProblem} is published API in
 * a jar this module already imports; the ID <em>ranges</em> are internal, which is the distinction
 * {@code optionalProblemsAnalysed} already records for {@code CategorizedProblem}. Java inlines a
 * {@code static final int} at compile time, so these become literals and are safe on the oldest band even
 * though that also means a missing constant could not be detected — which is why the table sticks to
 * corrections that have existed since JDT 3.x.</p>
 *
 * <h3>An unknown id returns nothing, and that is the answer rather than a gap</h3>
 *
 * <p>ECJ reports on the order of a thousand distinct problems. Covering them is not a goal: the popup still
 * shows the message and whatever the shape-derived contributors offer, and treating an empty result as a
 * hole to be filled is precisely how a table of (problems × fixes) gets built by accident.</p>
 */
final class JavaQuickFixes {

    private JavaQuickFixes() {
    }

    /**
     * Everything offered for the problems overlapping {@code [from, to)}.
     *
     * <p>In the unit's own coordinates. The caller stamps the answer with the analysis version and the
     * apply path refuses it if the buffer has moved, so these offsets are either exactly right or unused.</p>
     */
    static List<CodeAction> in(CompilationUnit unit, String source, long version, int from, int to,
                              java.util.function.Function<String, List<String>> importCandidates) {
        if (unit == null || source == null) return List.of();
        List<CodeAction> actions = new ArrayList<>();
        int documentLength = source.length();

        for (IProblem problem : unit.getProblems()) {
            if (!overlaps(problem, from, to)) continue;
            int id = problem.getID();

            if (id == IProblem.UnusedImport) {
                addUnusedImport(actions, unit, source, documentLength, version, problem);
            } else if (id == IProblem.LocalVariableIsNeverUsed) {
                addUnusedDeclaration(actions, unit, source, documentLength, version, problem,
                        VariableDeclarationStatement.class, "Remove variable ");
            } else if (id == IProblem.UnusedPrivateField) {
                addUnusedDeclaration(actions, unit, source, documentLength, version, problem,
                        FieldDeclaration.class, "Remove field ");
            } else if (id == IProblem.UndefinedType || id == IProblem.ImportNotFound) {
                addImports(actions, unit, source, documentLength, version, problem, importCandidates);
            }
        }
        actions.sort(CodeAction.ORDER);
        return actions;
    }

    // ── Unused imports ──────────────────────────────────────────────────────────────────────────

    /**
     * "Remove unused import", plus the batch when the file has more than one.
     *
     * <p>Both, because they are different intentions rather than one with a count: you either meant this
     * line or you meant to tidy the file, and IntelliJ offers exactly this pair. The batch is not
     * preferred — a fix that edits lines you were not looking at should be chosen, not defaulted to.</p>
     */
    private static void addUnusedImport(List<CodeAction> actions, CompilationUnit unit, String source,
                                        int documentLength, long version, IProblem problem) {
        ImportDeclaration declaration = enclosing(unit, problem, ImportDeclaration.class);
        if (declaration == null) return;
        actions.add(CodeAction.preferredFix("Remove unused import",
                ChangeSet.of(documentLength, deletion(source, declaration)), version));

        List<ImportDeclaration> unused = allUnusedImports(unit);
        if (unused.size() < 2) return;
        List<Change> changes = new ArrayList<>(unused.size());
        for (ImportDeclaration each : unused) changes.add(deletion(source, each));
        // SORTED, because ChangeSet.of REQUIRES it rather than normalising -- two overlapping changes have
        // no defined combined meaning, so it refuses them instead of letting iteration order decide.
        changes.sort(Comparator.comparingInt(Change::from));
        actions.add(new CodeAction("Remove unused imports", CodeActionKind.SOURCE,
                ChangeSet.of(documentLength, changes), null, false, version));
    }

    private static List<ImportDeclaration> allUnusedImports(CompilationUnit unit) {
        List<ImportDeclaration> found = new ArrayList<>();
        for (IProblem problem : unit.getProblems()) {
            if (problem.getID() != IProblem.UnusedImport) continue;
            ImportDeclaration declaration = enclosing(unit, problem, ImportDeclaration.class);
            if (declaration != null && !found.contains(declaration)) found.add(declaration);
        }
        return found;
    }

    // ── An unresolved type ────────────────────────────────────────────

    /**
     * "Import java.util.List" — one action per candidate, which is the point.
     *
     * <p>The first problem here whose answer is <b>several</b> actions rather than one. That is why none
     * of them is preferred: with {@code List} on the classpath four times over, defaulting to whichever
     * the index happened to return first is a coin toss that edits the file. IntelliJ shows the list and
     * auto-applies only when there is exactly one candidate.</p>
     *
     * <p>The <b>name</b> comes from the problem's own arguments and the <b>place</b> from the syntax tree.
     * Neither can come from the other: the compiler knows what did not resolve, and only the tree knows
     * where an import may legally be written.</p>
     */
    private static void addImports(List<CodeAction> actions, CompilationUnit unit, String source,
                                   int documentLength, long version, IProblem problem,
                                   java.util.function.Function<String, List<String>> importCandidates) {
        if (importCandidates == null) return;
        String name = nameOf(problem);
        if (name == null || name.isEmpty()) return;
        // The LAST segment: an unresolved `java.utl.List` reports the whole path, and what has to be
        // imported is a type rather than whatever the author mistyped in front of it.
        int dot = name.lastIndexOf('.');
        if (dot >= 0) name = name.substring(dot + 1);
        List<String> candidates = importCandidates.apply(name);
        if (candidates == null || candidates.isEmpty()) return;

        int at = importInsertOffset(unit, source);
        for (String qualified : candidates) {
            if (alreadyImported(unit, qualified)) continue;
            String text = "import " + qualified + ";\n";
            actions.add(new CodeAction("Import '" + qualified + "'", CodeActionKind.QUICK_FIX,
                    ChangeSet.of(documentLength, Change.insert(at, text)), null, false, version));
        }
    }

    private static boolean alreadyImported(CompilationUnit unit, String qualified) {
        for (Object each : unit.imports()) {
            if (((ImportDeclaration) each).getName().getFullyQualifiedName().equals(qualified)) return true;
        }
        return false;
    }

    /**
     * Where a new import goes: after the last one, else after the package declaration, else the top.
     *
     * <p><b>Never before the package statement</b>, which would not compile — the one placement that turns
     * a fix into a new error. Three cases from the tree rather than a scan for a blank line, because the
     * tree already knows all three and a text scan would have to cope with the comment forms it does
     * not.</p>
     */
    private static int importInsertOffset(CompilationUnit unit, String source) {
        List<?> imports = unit.imports();
        if (!imports.isEmpty()) {
            ImportDeclaration last = (ImportDeclaration) imports.get(imports.size() - 1);
            return afterLine(source, last.getStartPosition() + last.getLength());
        }
        if (unit.getPackage() != null) {
            return afterLine(source,
                    unit.getPackage().getStartPosition() + unit.getPackage().getLength());
        }
        return 0;
    }

    /** The offset just past the line {@code at} sits on, terminator included. */
    private static int afterLine(String source, int at) {
        int i = Math.max(0, Math.min(at, source.length()));
        while (i < source.length() && source.charAt(i) != '\n') i++;
        return Math.min(source.length(), i + 1);
    }

    // ── Unused locals and fields ────────────────────────────────────────────────────────────────

    /**
     * "Remove variable 's'" / "Remove field 'x'".
     *
     * <p>Refused when the declaration declares <b>more than one name</b>: {@code int a, b;} with only
     * {@code b} unused would lose {@code a} as well, and a quick fix that silently deletes working code is
     * worse than no quick fix. Eclipse and IntelliJ both narrow to the fragment instead; doing that
     * properly means rewriting the declaration rather than deleting a range, which is a different job from
     * this one and is not worth guessing at.</p>
     */
    private static <T extends ASTNode> void addUnusedDeclaration(
            List<CodeAction> actions, CompilationUnit unit, String source, int documentLength, long version,
            IProblem problem, Class<T> declarationType, String titlePrefix) {
        T declaration = enclosing(unit, problem, declarationType);
        if (declaration == null) return;
        if (fragmentCount(declaration) != 1) return;
        String name = nameOf(problem);
        if (name == null) return;
        actions.add(CodeAction.preferredFix(titlePrefix + "'" + name + "'",
                ChangeSet.of(documentLength, deletion(source, declaration)), version));
    }

    private static int fragmentCount(ASTNode declaration) {
        if (declaration instanceof VariableDeclarationStatement) {
            return ((VariableDeclarationStatement) declaration).fragments().size();
        }
        if (declaration instanceof FieldDeclaration) {
            return ((FieldDeclaration) declaration).fragments().size();
        }
        return 1;
    }

    /**
     * The name the problem is about, from its own arguments.
     *
     * <p>{@code IProblem.getArguments()} is how ECJ hands over the pieces its message was built from, and
     * for every problem here the first argument is the offending name. Read from there rather than sliced
     * out of the source at the problem's range, because the two are not always the same span and the
     * message is the thing the title has to agree with.</p>
     */
    private static String nameOf(IProblem problem) {
        String[] arguments = problem.getArguments();
        return arguments == null || arguments.length == 0 ? null : arguments[0];
    }

    // ── Ranges ──────────────────────────────────────────────────────────────────────────────────

    /**
     * The node's extent, widened to <b>whole lines</b> when it has the line to itself.
     *
     * <p>Deleting only the node leaves the indentation that preceded it and the newline that followed, so
     * removing an import empties the line rather than removing it and the file slowly fills with blanks.
     * Widening only when nothing else shares the line is what keeps {@code int a; int b;} from losing
     * {@code b} along with {@code a}.</p>
     */
    private static Change deletion(String source, ASTNode node) {
        int start = node.getStartPosition();
        int end = Math.min(source.length(), start + node.getLength());
        if (start < 0 || end <= start) return Change.delete(0, 0);

        int lineStart = start;
        while (lineStart > 0 && source.charAt(lineStart - 1) != '\n') lineStart--;
        int lineEnd = end;
        while (lineEnd < source.length() && source.charAt(lineEnd) != '\n') lineEnd++;

        boolean aloneOnItsLine = isBlank(source, lineStart, start) && isBlank(source, end, lineEnd);
        if (!aloneOnItsLine) return Change.delete(start, end);
        // The terminator too, or the line survives as an empty one.
        return Change.delete(lineStart, Math.min(source.length(), lineEnd + 1));
    }

    private static boolean isBlank(String source, int from, int to) {
        for (int i = from; i < to; i++) {
            if (!Character.isWhitespace(source.charAt(i))) return false;
        }
        return true;
    }

    private static boolean overlaps(IProblem problem, int from, int to) {
        int start = problem.getSourceStart();
        // JDT's end is INCLUSIVE, as the diagnostic conversion already records.
        int end = problem.getSourceEnd() + 1;
        if (start < 0 || end < start) return false;
        return from <= end && start <= to;
    }

    /** The nearest enclosing node of {@code type} covering the problem, or null. */
    private static <T extends ASTNode> T enclosing(CompilationUnit unit, IProblem problem, Class<T> type) {
        int start = problem.getSourceStart();
        int length = Math.max(0, problem.getSourceEnd() + 1 - start);
        if (start < 0) return null;
        ASTNode node = NodeFinder.perform(unit, start, length);
        while (node != null && !type.isInstance(node)) {
            // A BodyDeclaration is as far out as any of these fixes reach; going past one would let an
            // unused local walk up to the method that contains it and offer to delete that instead.
            if (node instanceof BodyDeclaration && !type.isInstance(node)) {
                if (type == FieldDeclaration.class && node instanceof FieldDeclaration) break;
                if (!(node instanceof FieldDeclaration)) return null;
            }
            node = node.getParent();
        }
        return type.isInstance(node) ? type.cast(node) : null;
    }
}
