package com.crystalgui.language.js.rhino.resolve;

import com.crystalgui.language.js.rhino.JsImports;
import com.crystalgui.language.js.rhino.RhinoSourceAnalyzer;
import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.text.lang.TypeRef;

import org.mozilla.javascript.Parser;
import org.mozilla.javascript.ast.Assignment;
import org.mozilla.javascript.ast.AstNode;
import org.mozilla.javascript.ast.AstRoot;
import org.mozilla.javascript.ast.FunctionNode;
import org.mozilla.javascript.ast.Name;
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
 * an author writing a Java-shaped import against a Node-shaped export. The explicit form stays because it
 * is the only way to keep something <em>private</em>: with nothing else in the file to go on, "top-level"
 * and "exported" are the same set.</p>
 *
 * <h3>A declaration says far more than an assignment, and that is the whole reason this reads them</h3>
 *
 * <p>{@code function greet(who) { }} carries a kind, its parameter names, its doc comment and a precise
 * span. {@code exports.greet = function (who) { }} is an anonymous function assigned to a property —
 * statically a name and little else, which is why a module's member once hovered as a bare word with
 * nothing around it. The explicit form therefore reads its right-hand side too.</p>
 *
 * <h3>Why it lives beside the resolver</h3>
 *
 * <p>Everything an export needs to describe itself is already written here: {@link RhinoInference} types
 * an initializer, {@link RhinoJsDoc} reads the comment above a declaration, and {@link JsTypeRef} is what
 * a type is spelled as. Reading a module anywhere else would mean either a second copy of all three or
 * widening them for one caller.</p>
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
     * One exported name, described as fully as its declaration allows.
     *
     * @param name          what an importer writes after the dot
     * @param kind          what it is, as far as the declaration says
     * @param offset        where the name is written, or 0 when it has no span of its own — the keys of
     *                      an object assigned wholesale to {@code module.exports} are read through the
     *                      one band-safe reader, which answers with strings rather than nodes
     * @param parameters    a function's parameter names, in order; empty for anything else
     * @param type          what the initializer says it is, or null when nothing said
     * @param keyword       {@code var}, {@code let} or {@code const} — what actually introduced it, so a
     *                      signature prints the word the author wrote
     * @param documentation the doc comment above it, or null
     */
    public record Export(String name, SymbolKind kind, int offset, List<String> parameters,
                         @Nullable TypeRef type, @Nullable String keyword,
                         @Nullable String documentation) {
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
            if (node instanceof Assignment) collectAssigned(root, source, (Assignment) node, explicit);
            return true;
        });
        if (!explicit.isEmpty()) return new ArrayList<>(explicit.values());

        Map<String, Export> declared = new LinkedHashMap<>();
        collectDeclared(root, source, declared);
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
    private static void collectDeclared(AstRoot root, String source, Map<String, Export> found) {
        for (Object statement : root) {
            if (!(statement instanceof AstNode node)) continue;
            if (node instanceof FunctionNode function) {
                Name name = function.getFunctionName();
                if (name == null) continue;
                found.putIfAbsent(name.getIdentifier(), new Export(name.getIdentifier(),
                        SymbolKind.FUNCTION, positionOf(name), parameterNamesOf(function),
                        null, null, docFor(root, source, function, positionOf(name))));
            } else if (node instanceof VariableDeclaration declaration) {
                String keyword = declaration.isConst() ? "const" : declaration.isLet() ? "let" : "var";
                // FIELD, not PROPERTY: a top-level name is the file's surface, and the same kind the
                // resolver reports for it where it is DECLARED. An imported member describing itself
                // differently from its own declaration is the drift this whole seam exists to avoid.
                SymbolKind kind = declaration.isConst() ? SymbolKind.CONSTANT : SymbolKind.FIELD;
                for (VariableInitializer initializer : declaration.getVariables()) {
                    AstNode target = initializer.getTarget();
                    if (!(target instanceof Name named)) continue;
                    AstNode value = initializer.getInitializer();
                    int at = positionOf(named);
                    // A FUNCTION ASSIGNED TO A NAME IS STILL A FUNCTION -- `var f = function (a) {}` is
                    // how half of JavaScript declares one, and calling it a value would lose its
                    // parameters and its glyph.
                    found.putIfAbsent(named.getIdentifier(), value instanceof FunctionNode assigned
                            ? new Export(named.getIdentifier(), SymbolKind.FUNCTION, at,
                                    parameterNamesOf(assigned), null, null,
                                    docFor(root, source, declaration, at))
                            : new Export(named.getIdentifier(), kind, at, List.of(),
                                    RhinoInference.typeOf(value, name -> false), keyword,
                                    docFor(root, source, declaration, at)));
                }
            }
        }
    }

    // ── The explicit form ───────────────────────────────────────────────────────────────────────

    private static void collectAssigned(AstRoot root, String source, Assignment assignment,
                                        Map<String, Export> found) {
        AstNode target = assignment.getLeft();
        if (!(target instanceof PropertyGet get)) return;

        // `module.exports = { … }` -- the whole object at once. Its keys go through the ONE band-safe
        // reader, because `ObjectProperty.getLeft()` is declared on a different supertype in the two
        // Rhino versions we ship and `getFirstChild()` answers null on the band we run.
        if (isModuleExports(get)) {
            for (String key : RhinoInference.keysOf(assignment.getRight())) {
                found.putIfAbsent(key,
                        new Export(key, SymbolKind.PROPERTY, 0, List.of(), null, null, null));
            }
            return;
        }

        String name = nameOf(get.getProperty());
        if (name == null) return;
        AstNode owner = get.getTarget();
        if (!isExports(owner) && !(owner instanceof PropertyGet && isModuleExports((PropertyGet) owner))) {
            return;
        }
        int at = positionOf(get.getProperty());
        AstNode value = assignment.getRight();
        String documentation = docFor(root, source, assignment.getParent(), at);
        // FIRST WINS, so a name assigned twice points at where it was introduced.
        found.putIfAbsent(name, value instanceof FunctionNode assigned
                ? new Export(name, SymbolKind.FUNCTION, at, parameterNamesOf(assigned),
                        null, null, documentation)
                : new Export(name, SymbolKind.PROPERTY, at, List.of(),
                        RhinoInference.typeOf(value, id -> false), null, documentation));
    }

    // ── Reading what is around a declaration ────────────────────────────────────────────────────

    /**
     * The doc comment above a declaration, as a renderer wants it.
     *
     * <p>Through the same reader the editor uses on the file the author has open, so a member's comment
     * reads identically whether it is hovered where it is written or where it is imported. Without it an
     * imported member showed its container and its signature with nothing underneath — which reads as the
     * member being undocumented rather than as a field being dropped at the seam, and is the same failure
     * the Java side records for {@code describeMember}.</p>
     */
    @Nullable
    private static String docFor(AstRoot root, String source, @Nullable AstNode node, int offset) {
        RhinoJsDoc doc = RhinoJsDoc.forDeclaration(root, node, offset, source);
        if (doc == null || doc.isEmpty()) return null;
        String markdown = doc.markdown();
        return markdown == null || markdown.isEmpty() ? null : markdown;
    }

    private static int positionOf(AstNode node) {
        return Math.max(0, node.getAbsolutePosition());
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
