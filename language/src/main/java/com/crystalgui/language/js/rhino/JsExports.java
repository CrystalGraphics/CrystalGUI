package com.crystalgui.language.js.rhino;

import com.crystalgui.language.js.rhino.resolve.RhinoInference;
import com.crystalgui.text.lang.SymbolKind;

import org.mozilla.javascript.Parser;
import org.mozilla.javascript.ast.Assignment;
import org.mozilla.javascript.ast.AstNode;
import org.mozilla.javascript.ast.AstRoot;
import org.mozilla.javascript.ast.FunctionNode;
import org.mozilla.javascript.ast.Name;
import org.mozilla.javascript.ast.NodeVisitor;
import org.mozilla.javascript.ast.PropertyGet;
import org.mozilla.javascript.ast.VariableDeclaration;
import org.mozilla.javascript.ast.VariableInitializer;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a project script exports, read statically — M15 S6's editor half.
 *
 * <h3>Two ways to export, and the implicit one is the default</h3>
 *
 * <p>A module that says nothing exports <b>every top-level declaration</b> — a function is a function, a
 * {@code var} is a value, and that is all there is to it. A module that assigns to {@code exports} or
 * {@code module.exports} is taken at its word instead, and those assignments are then the whole of what
 * it exports.</p>
 *
 * <p>The implicit form is the default because this language already writes its own {@code import a.b.C;}
 * rather than ES or CommonJS syntax — so requiring {@code exports.name = …} on the other side would leave
 * an author writing a Java-shaped import against a Node-shaped export, which is the worst of both. The
 * explicit form stays because it is the only way to keep something <em>private</em>: with nothing else in
 * the file to go on, "top-level" and "exported" are the same set.</p>
 *
 * <h3>A declaration says far more than an assignment</h3>
 *
 * <p>This is not only about what an author types. {@code function greet(who) { }} is a DECLARATION: it
 * carries a kind, its parameter names and a precise span, so a hover can render {@code function
 * greet(who)} and a jump can land on the word. {@code exports.greet = function (who) { }} is an
 * assignment of an anonymous function to a property — statically it offers a name and little else, which
 * is why a module's member used to hover as a bare word with nothing around it.</p>
 *
 * <p>So the explicit form reads its right-hand side too: assigning a function still yields a function.</p>
 *
 * <h3>It is still an approximation, and under-reporting is the safe direction</h3>
 *
 * <p>{@code require()} returns a value, and §24.6 lists resolving it as M15's largest unknown. A module
 * may build its exports in a loop or from a name computed at run time, and no static reading follows
 * that. A missing row costs the author a completion they can still type; an invented one offers a name
 * that does not exist at run time.</p>
 */
public final class JsExports {

    private JsExports() {
    }

    /**
     * One exported name.
     *
     * @param name       what an importer writes after the dot
     * @param kind       what it is, as far as the declaration says
     * @param offset     where the name is written, or 0 when it has no span of its own — the keys of an
     *                   object assigned wholesale to {@code module.exports} are read through the one
     *                   band-safe reader, which answers with strings rather than nodes
     * @param parameters a function's parameter names, in order; empty for anything else
     */
    public record Export(String name, SymbolKind kind, int offset, List<String> parameters) {
    }

    /** The names {@code source} exports, in source order. */
    public static List<String> namesIn(@Nullable String source) {
        List<String> names = new ArrayList<>();
        for (Export export : exportsIn(source)) names.add(export.name());
        return names;
    }

    /**
     * Everything {@code source} exports, with what each one is.
     *
     * <p>Explicit wins: a file that assigns to {@code exports} has said what it means, and adding its
     * top-level names on top would export the private helpers it was being explicit in order to hide.</p>
     */
    public static List<Export> exportsIn(@Nullable String source) {
        if (source == null || source.isEmpty()) return List.of();
        AstRoot root = parse(source);
        if (root == null) return List.of();

        Map<String, Export> explicit = new LinkedHashMap<>();
        root.visit(node -> {
            if (node instanceof Assignment) collectAssigned((Assignment) node, explicit);
            return true;
        });
        if (!explicit.isEmpty()) return new ArrayList<>(explicit.values());

        Map<String, Export> declared = new LinkedHashMap<>();
        collectDeclared(root, declared);
        return new ArrayList<>(declared.values());
    }

    // ── The implicit form ───────────────────────────────────────────────────────────────────────

    /**
     * Every TOP-LEVEL declaration, which is what a module with no {@code exports} of its own offers.
     *
     * <p>Top-level and nothing deeper: a name declared inside a function is that function's, and offering
     * it would name something no importer can reach. That is the whole difference between this and the
     * assignment walk, which deliberately looks everywhere because {@code if (x) { exports.y = … }} is
     * ordinary.</p>
     */
    private static void collectDeclared(AstRoot root, Map<String, Export> found) {
        for (Object statement : root) {
            if (!(statement instanceof AstNode node)) continue;
            if (node instanceof FunctionNode function) {
                Name name = function.getFunctionName();
                if (name == null) continue;
                found.putIfAbsent(name.getIdentifier(), new Export(name.getIdentifier(),
                        SymbolKind.FUNCTION, Math.max(0, name.getAbsolutePosition()),
                        parameterNamesOf(function)));
            } else if (node instanceof VariableDeclaration declaration) {
                SymbolKind kind = declaration.isConst() ? SymbolKind.CONSTANT : SymbolKind.PROPERTY;
                for (VariableInitializer initializer : declaration.getVariables()) {
                    AstNode target = initializer.getTarget();
                    if (!(target instanceof Name named)) continue;
                    // A FUNCTION ASSIGNED TO A NAME IS STILL A FUNCTION -- `var f = function (a) {}` is
                    // how half of JavaScript declares one, and calling it a property would lose its
                    // parameters and its glyph.
                    AstNode value = initializer.getInitializer();
                    found.putIfAbsent(named.getIdentifier(), value instanceof FunctionNode assigned
                            ? new Export(named.getIdentifier(), SymbolKind.FUNCTION,
                                    Math.max(0, named.getAbsolutePosition()),
                                    parameterNamesOf(assigned))
                            : new Export(named.getIdentifier(), kind,
                                    Math.max(0, named.getAbsolutePosition()), List.of()));
                }
            }
        }
    }

    // ── The explicit form ───────────────────────────────────────────────────────────────────────

    private static void collectAssigned(Assignment assignment, Map<String, Export> found) {
        AstNode target = assignment.getLeft();
        if (!(target instanceof PropertyGet get)) return;

        // `module.exports = { … }` -- the whole object at once. Its keys go through the ONE band-safe
        // reader, because `ObjectProperty.getLeft()` is declared on a different supertype in the two
        // Rhino versions we ship and `getFirstChild()` answers null on the band we run.
        if (isModuleExports(get)) {
            for (String key : RhinoInference.keysOf(assignment.getRight())) {
                found.putIfAbsent(key, new Export(key, SymbolKind.PROPERTY, 0, List.of()));
            }
            return;
        }

        String name = nameOf(get.getProperty());
        if (name == null) return;
        AstNode owner = get.getTarget();
        if (!isExports(owner) && !(owner instanceof PropertyGet && isModuleExports((PropertyGet) owner))) {
            return;
        }
        int offset = Math.max(0, get.getProperty().getAbsolutePosition());
        AstNode value = assignment.getRight();
        // FIRST WINS, so a name assigned twice points at where it was introduced.
        found.putIfAbsent(name, value instanceof FunctionNode assigned
                ? new Export(name, SymbolKind.FUNCTION, offset, parameterNamesOf(assigned))
                : new Export(name, SymbolKind.PROPERTY, offset, List.of()));
    }

    private static List<String> parameterNamesOf(FunctionNode function) {
        List<String> names = new ArrayList<>();
        for (AstNode parameter : function.getParams()) {
            if (parameter instanceof Name named) names.add(named.getIdentifier());
        }
        return names;
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
