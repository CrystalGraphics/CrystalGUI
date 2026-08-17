package com.crystalgui.language.java.fix.edit;

import com.crystalgui.text.Change;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ImportDeclaration;

import java.util.List;

/**
 * The import region's own text arithmetic — the one part of a file {@link Rewrites} is not used on.
 *
 * <h3>This class is a boundary, not a leftover</h3>
 *
 * <p>Every other correction describes itself to JDT's rewriter and lets it compute the text. Imports do
 * not, and the reason is JDT's own design: it never intended the general rewriter to be used there. It
 * ships {@code ImportRewrite} for the import region, and that class refuses to work without a Java model
 * this engine deliberately has not got — {@code IllegalArgumentException: AST must have been constructed
 * from a Java element}.</p>
 *
 * <p>Driving the general rewriter there instead is wrong in two independently measured ways, and both
 * land on the same shape — <b>a file with no package declaration</b>, which is what a script normally
 * is:</p>
 *
 * <ul>
 *   <li><b>Removing.</b> A list's elements are removed together with the separators <em>between</em> them,
 *       so emptying a list that nothing precedes strands the final terminator. Removing the only import of
 *       such a file leaves a blank first line. Identical through {@code remove} and {@code ListRewrite},
 *       so it is not the API choice.</li>
 *   <li><b>Inserting.</b> A {@code ListRewrite} on {@code IMPORTS_PROPERTY} places an import correctly
 *       when a package declaration precedes it and produces
 *       {@code import java.util.List;public class Script { }} — no separator, plus two spurious leading
 *       blank lines — when none does.</li>
 * </ul>
 *
 * <p>So the line is <b>the import region versus everything else</b>: one boundary, drawn once from two
 * measurements, rather than a judgement each new correction has to make. It is a class rather than a
 * paragraph so that the boundary is somewhere you can stand, and anything tempted to fold it back into
 * {@code Rewrites} should reproduce those two results first.</p>
 */
final class ImportRegion {

    private ImportRegion() {
    }

    /**
     * A node's extent, widened to <b>whole lines</b> when it has the line to itself.
     *
     * <p>Deleting only the node leaves the indentation before it and the newline after, so removing an
     * import empties its line rather than removing it and the file slowly fills with blanks. Widening only
     * when nothing else shares the line is what stops two declarations on one line losing the survivor.</p>
     */
    static Change deletion(String source, ASTNode node) {
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

    /**
     * Where a new import goes: after the last one, else after the package declaration, else the top.
     *
     * <p><b>Never before the package statement</b>, which would not compile — the one placement that turns
     * a fix into a new error. Three cases from the tree rather than a scan for a blank line, because the
     * tree already knows all three and a text scan would have to cope with the comment forms it does
     * not.</p>
     */
    static int insertOffset(CompilationUnit unit, String source) {
        List<?> imports = unit.imports();
        if (!imports.isEmpty()) {
            ImportDeclaration last = (ImportDeclaration) imports.get(imports.size() - 1);
            return afterLine(source, last.getStartPosition() + last.getLength());
        }
        if (unit.getPackage() != null) {
            return afterLine(source, unit.getPackage().getStartPosition() + unit.getPackage().getLength());
        }
        return 0;
    }

    /**
     * The whole import region as {@code {start, end}} — from the first import's line start to just past
     * the last import's line — or null when the file has no imports.
     *
     * <p>Whole lines at both ends, so replacing the region replaces exactly the import lines and nothing
     * of what surrounds them.</p>
     */
    static int[] regionOf(CompilationUnit unit, String source) {
        List<?> imports = unit.imports();
        if (imports.isEmpty()) return null;
        ImportDeclaration first = (ImportDeclaration) imports.get(0);
        ImportDeclaration last = (ImportDeclaration) imports.get(imports.size() - 1);
        int start = first.getStartPosition();
        while (start > 0 && source.charAt(start - 1) != '\n') start--;
        int end = afterLine(source, last.getStartPosition() + last.getLength());
        return new int[] {start, end};
    }

    /** Whether {@code qualified} is already imported, so a fix is not offered for what is there. */
    static boolean alreadyImported(CompilationUnit unit, String qualified) {
        for (Object each : unit.imports()) {
            if (((ImportDeclaration) each).getName().getFullyQualifiedName().equals(qualified)) return true;
        }
        return false;
    }

    /** The offset just past the line {@code at} sits on, terminator included. */
    private static int afterLine(String source, int at) {
        int i = Math.max(0, Math.min(at, source.length()));
        while (i < source.length() && source.charAt(i) != '\n') i++;
        return Math.min(source.length(), i + 1);
    }

    private static boolean isBlank(String source, int from, int to) {
        for (int i = from; i < to; i++) {
            if (!Character.isWhitespace(source.charAt(i))) return false;
        }
        return true;
    }
}
