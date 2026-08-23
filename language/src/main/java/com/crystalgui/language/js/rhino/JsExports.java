package com.crystalgui.language.js.rhino;

import com.crystalgui.language.js.rhino.resolve.RhinoInference;

import org.mozilla.javascript.Parser;
import org.mozilla.javascript.ast.Assignment;
import org.mozilla.javascript.ast.AstNode;
import org.mozilla.javascript.ast.AstRoot;
import org.mozilla.javascript.ast.Name;
import org.mozilla.javascript.ast.NodeVisitor;
import org.mozilla.javascript.ast.PropertyGet;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What a project script exports, read statically — M15 S6's editor half.
 *
 * <h3>Why this is an approximation, and why it is still worth having</h3>
 *
 * <p>{@code require()} returns whatever a script assigned to {@code exports}, which is a value rather
 * than a declaration — §24.6 lists resolving it as the largest unknown in M15. There is no way to be
 * complete: a module may build its exports in a loop, behind a condition, or from a name computed at run
 * time, and no static reading can follow that.</p>
 *
 * <p>What it can read is the three shapes people actually write, and reading those is the difference
 * between an import that offers its members and one that offers nothing:</p>
 *
 * <ul>
 *   <li>{@code exports.name = …}</li>
 *   <li>{@code module.exports.name = …}</li>
 *   <li>{@code module.exports = { a: …, b: … }}</li>
 * </ul>
 *
 * <p>Anything else answers with what it found, which may be nothing. <b>Under-reporting is the safe
 * direction</b>: a missing row costs the author a completion they can still type, while inventing one
 * would offer a name that does not exist at run time.</p>
 *
 * <h3>Whole-tree, not top-level</h3>
 *
 * <p>Assignments are collected wherever they appear rather than only at the top of the file, because
 * {@code if (supported) { exports.fast = … }} is ordinary and its export is real. The cost is that a
 * conditional export is reported as though it were unconditional — which is the same over-reporting a
 * reader does by eye, and much less wrong than dropping it.</p>
 */
public final class JsExports {

    private JsExports() {
    }

    /**
     * The names {@code source} appears to export, in source order.
     *
     * <p>Empty for a module that exports nothing, that could not be parsed, or whose exports are not
     * written in a readable shape. The caller cannot tell those apart and does not need to: all three
     * mean "there is nothing to offer behind the dot".</p>
     */
    public static List<String> namesIn(@Nullable String source) {
        if (source == null || source.isEmpty()) return List.of();
        AstRoot root = parse(source);
        if (root == null) return List.of();

        Set<String> found = new LinkedHashSet<>();
        root.visit(new NodeVisitor() {
            @Override
            public boolean visit(AstNode node) {
                if (node instanceof Assignment) collect((Assignment) node, found);
                return true;
            }
        });
        return new ArrayList<>(found);
    }

    private static void collect(Assignment assignment, Set<String> found) {
        AstNode target = assignment.getLeft();
        if (!(target instanceof PropertyGet)) return;
        PropertyGet get = (PropertyGet) target;

        // `module.exports = { … }` -- the whole object at once. Its keys are read through the ONE
        // band-safe reader, because `ObjectProperty.getLeft()` is declared on a different supertype in
        // the two Rhino versions we ship and `getFirstChild()` answers null on the band we run.
        if (isModuleExports(get)) {
            found.addAll(RhinoInference.keysOf(assignment.getRight()));
            return;
        }

        // `exports.name = …` or `module.exports.name = …` -- one name, whatever it is assigned.
        String name = nameOf(get.getProperty());
        if (name == null) return;
        AstNode owner = get.getTarget();
        if (isExports(owner) || (owner instanceof PropertyGet && isModuleExports((PropertyGet) owner))) {
            found.add(name);
        }
    }

    /** Whether this is the expression {@code module.exports}. */
    private static boolean isModuleExports(PropertyGet get) {
        return "exports".equals(nameOf(get.getProperty())) && isNamed(get.getTarget(), "module");
    }

    /** Whether this is the bare identifier {@code exports}. */
    private static boolean isExports(@Nullable AstNode node) {
        return isNamed(node, "exports");
    }

    private static boolean isNamed(@Nullable AstNode node, String identifier) {
        return node instanceof Name && identifier.equals(((Name) node).getIdentifier());
    }

    @Nullable
    private static String nameOf(@Nullable AstNode node) {
        return node instanceof Name ? ((Name) node).getIdentifier() : null;
    }

    /**
     * Parses a module, sharing the editor's own parser settings.
     *
     * <p>Errors are swallowed rather than reported: this is being asked about a file the author is not
     * looking at, and a diagnostic on <em>their</em> file about a mistake in <em>another</em> one belongs
     * to that file's own analysis, which will report it when it is opened.</p>
     */
    @Nullable
    private static AstRoot parse(String source) {
        try {
            // BLANKED FIRST, exactly as the executor and the analyser blank it. `import a.b.C;` is not
            // JavaScript, and a parser that saw it would fail on line 1 of every module that has one.
            return new Parser(RhinoSourceAnalyzer.environs(), new ErrorSink())
                    .parse(JsImports.blank(source), "module.js", 1);
        } catch (RuntimeException | StackOverflowError unparseable) {
            return null;
        }
    }

    /** Discards what it is told. @see #parse */
    private static final class ErrorSink implements org.mozilla.javascript.ErrorReporter {
        @Override
        public void warning(String message, String name, int line, String source, int offset) {
        }

        @Override
        public void error(String message, String name, int line, String source, int offset) {
        }

        @Override
        public org.mozilla.javascript.EvaluatorException runtimeError(
                String message, String name, int line, String source, int offset) {
            return new org.mozilla.javascript.EvaluatorException(message, name, line, source, offset);
        }
    }
}
