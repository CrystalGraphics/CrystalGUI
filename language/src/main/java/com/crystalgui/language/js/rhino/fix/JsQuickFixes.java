package com.crystalgui.language.js.rhino.fix;

import com.crystalgui.language.js.rhino.exec.RhinoGlobals;
import com.crystalgui.language.js.rhino.RhinoScopes;
import com.crystalgui.language.js.rhino.RhinoTokens;
import com.crystalgui.language.js.rhino.resolve.RhinoInference;
import com.crystalgui.language.js.rhino.resolve.RhinoResolution;
import com.crystalgui.text.SimilarNames;
import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.lang.CodeAction;
import com.crystalgui.text.lang.CodeActionKind;

import org.mozilla.javascript.ast.AstNode;
import org.mozilla.javascript.ast.AstRoot;
import org.mozilla.javascript.ast.Block;
import org.mozilla.javascript.ast.FunctionNode;
import org.mozilla.javascript.ast.InfixExpression;
import org.mozilla.javascript.ast.Name;
import org.mozilla.javascript.ast.PropertyGet;
import org.mozilla.javascript.ast.Scope;
import org.mozilla.javascript.ast.StringLiteral;
import org.mozilla.javascript.ast.VariableDeclaration;
import org.mozilla.javascript.ast.VariableInitializer;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The JavaScript fix catalog — what Alt+Enter offers, and why each entry is worth offering.
 *
 * <h3>Smaller than Java's, and not for want of trying</h3>
 *
 * <p>Java's catalog is sixteen families over JDT's problem ids: a compiler that resolves types reports a
 * great many things that have exactly one sensible repair. Rhino reports syntax and little else, and there
 * is no static type to be wrong about — so half of Java's list has no JavaScript counterpart at all. What
 * is left divides into two groups, and the division is not cosmetic:</p>
 *
 * <ul>
 *   <li><b>Corrections</b> answer a problem the engine reported. There is one such problem in JavaScript
 *       worth repairing — an unused declaration — and one shape the engine flags that must <em>not</em> be
 *       offered a fix.</li>
 *   <li><b>Intentions</b> answer the caret. This is where a dynamic language is nearly as rich as a typed
 *       one, because they are AST-driven and the AST is just as good.</li>
 * </ul>
 *
 * <h3>What is deliberately not here</h3>
 *
 * <p><b>No fix for a refused keyword.</b> {@code class} cannot be rewritten as a function honestly — a
 * prototype-based translation changes what the code means — and the diagnostic already says which band
 * accepts it. Offering a repair that silently altered semantics is worse than offering none.</p>
 *
 * <p><b>No "add a semicolon" or "remove a trailing comma".</b> Both are Rhino strict-mode warnings, and
 * this analyser deliberately does not enable strict mode ({@code RhinoSourceAnalyzer} says why): which
 * style opinions are worth showing is a policy decision, and a fix for a warning nobody emits is dead
 * code that reads as a feature.</p>
 */
public final class JsQuickFixes {

    /** Every action's id is prefixed, so a keymap or a test can name one unambiguously. */
    private static final String ID = "js.fix.";

    private final AstRoot root;
    private final RhinoScopes scopes;
    private final JsRewrites edits;
    private final RhinoResolution resolution;

    public JsQuickFixes(@Nullable AstRoot root, RhinoScopes scopes, JsRewrites edits,
                 RhinoResolution resolution) {
        this.root = root;
        this.scopes = scopes;
        this.edits = edits;
        this.resolution = resolution;
    }

    /**
     * Everything offered for the range {@code [from, to]}.
     *
     * <p>Ordered by {@link CodeAction#ORDER} at the consumer, so this appends in whatever order is
     * clearest to read rather than trying to rank as it goes.</p>
     */
    public List<CodeAction> actionsIn(int from, int to) {
        if (root == null) return List.of();
        List<CodeAction> actions = new ArrayList<>();
        int caret = Math.max(0, from);

        RhinoScopes.Declaration unused = unusedDeclarationAt(caret, to);
        if (unused != null) removeUnused(actions, unused);

        Name free = freeNameAt(caret);
        if (free != null) {
            suggestSimilarNames(actions, free);
            declareAsLocal(actions, free);
        }

        varToLetOrConst(actions, caret);
        tightenEquality(actions, caret);
        switchJavaTypeSpelling(actions, caret);
        concatenationToTemplate(actions, caret);
        wrapInTryCatch(actions, caret);
        return actions;
    }

    // ── Corrections ─────────────────────────────────────────────────────────────────────────────

    /**
     * "Remove 'x'" — for a declaration the analyser has already warned about.
     *
     * <p>The whole statement goes, with its line, rather than just the name: deleting {@code unused} out of
     * {@code var unused = 1;} leaves {@code var  = 1;}, which is a syntax error where a warning used to be.
     * A {@code var} that declares several names loses only its own initializer.</p>
     */
    private void removeUnused(List<CodeAction> actions, RhinoScopes.Declaration declared) {
        AstNode statement = enclosingDeclaration(declared.declaringNode);
        if (statement == null) return;
        int from = statement.getAbsolutePosition();
        int to = from + statement.getLength();

        if (statement instanceof VariableDeclaration) {
            List<VariableInitializer> variables = ((VariableDeclaration) statement).getVariables();
            if (variables != null && variables.size() > 1) {
                // ONE OF SEVERAL. `var a = 1, unused = 2;` keeps the statement and loses one initializer,
                // and the comma before it -- taking the statement would delete a name that IS used.
                VariableInitializer mine = initializerFor(variables, declared.offset);
                if (mine == null) return;
                int start = mine.getAbsolutePosition();
                int end = start + mine.getLength();
                // THE COMMA GOES WITH IT, and it must be one of THIS statement's. Searching the whole text
                // before the initializer found the last comma ANYWHERE ABOVE -- an argument list, an array,
                // a previous statement -- so removing the FIRST of several names deleted everything from
                // that comma down. The statement's own start is the bound; and when this is the first name
                // there is no comma before it, so the one AFTER it is what separates it from the next.
                if (mine == variables.get(0)) {
                    int next = variables.get(1).getAbsolutePosition();
                    int comma = edits.textIn(end, next).indexOf(',');
                    if (comma >= 0) end = next;
                } else {
                    int comma = edits.textIn(from, start).lastIndexOf(',');
                    if (comma >= 0) start = from + comma;
                }
                actions.add(fix("remove-unused", "Remove '" + declared.name + "'",
                        edits.replace(start, end, "")));
                return;
            }
            // A `var` STATEMENT'S OWN LENGTH EXCLUDES ITS SEMICOLON in Rhino's tree, so deleting the node
            // alone leaves a bare `;` behind. Taken here rather than in `deleteStatement`, which is also
            // used for shapes that have none.
            if (to < length() && ';' == charAt(to)) to++;
        }
        actions.add(fix("remove-unused", "Remove '" + declared.name + "'",
                edits.deleteStatement(from, to)));
    }

    /**
     * "Did you mean 'x'" — over what is actually in scope here.
     *
     * <p>{@code SimilarNames.rank} is the <b>Java catalog's own ranking</b>, reused rather than copied: it
     * takes strings and returns strings, so nothing about it was ever Java's. Two implementations of "how
     * close is close enough" would drift, and the first divergence shows up as one engine suggesting a
     * name the other would not — which reads as one of them being broken.</p>
     *
     * <p>The candidates are what a completion list would offer at the same place, which is the honest set:
     * a suggestion the author cannot then use is worse than no suggestion.</p>
     */
    private void suggestSimilarNames(List<CodeAction> actions, Name free) {
        String typed = free.getIdentifier();
        if (typed == null || typed.isEmpty()) return;
        Set<String> candidates = new LinkedHashSet<>();
        for (RhinoScopes.Declaration declared : scopes.visibleAt(free.getAbsolutePosition())) {
            candidates.add(declared.name);
        }
        candidates.addAll(resolution.liveNames());
        // AND THE ENGINE'S OWN NAMES. `consle.log` is the commonest typo in the language and was offered
        // nothing at all, because the candidates were declarations and live globals only -- so the one
        // suggestion a JavaScript author most needs was the one case this could not make.
        candidates.addAll(RhinoGlobals.names());
        for (String similar : SimilarNames.rank(typed, candidates)) {
            actions.add(new CodeAction(ID + "rename-to-" + similar, "Change to '" + similar + "'",
                    CodeActionKind.QUICK_FIX, edits.replaceNode(free, similar), null,
                    // PREFERRED, because a near-miss on an existing name is overwhelmingly the cause of a
                    // free name in a file somebody is editing -- far more often than a name they meant to
                    // declare and have not yet.
                    true, edits.version()));
        }
    }

    /** "Declare 'x' as a local" — a {@code var} on its own line, above the statement using it. */
    private void declareAsLocal(List<CodeAction> actions, Name free) {
        String name = free.getIdentifier();
        if (name == null || name.isEmpty()) return;
        AstNode statement = enclosingStatement(free);
        if (statement == null) return;
        int at = edits.lineStartAt(statement.getAbsolutePosition());
        String indent = edits.indentAt(statement.getAbsolutePosition());
        actions.add(fix("declare-local", "Declare '" + name + "' as a local",
                edits.insertAt(at, indent + "var " + name + ";\n")));
    }

    // ── Intentions ──────────────────────────────────────────────────────────────────────────────

    /**
     * {@code var} → {@code let}, and → {@code const} when the name is never reassigned.
     *
     * <p>The JavaScript twin of {@code ModifierCorrections}' {@code final}, and it rests on the same fact:
     * the scopes already know whether anything writes to the name, so {@code const} is offered only when it
     * would still run. Offered only when the band accepts the keyword — {@code JsKeywords} measures that,
     * and a fix that produced source the engine refuses would be worse than no fix.</p>
     */
    private void varToLetOrConst(List<CodeAction> actions, int caret) {
        VariableDeclaration declaration = enclosingVar(caret);
        if (declaration == null || declaration.isConst()) return;
        String written = edits.textIn(declaration.getAbsolutePosition(),
                declaration.getAbsolutePosition() + 3);
        if (!"var".equals(written)) return;

        int from = declaration.getAbsolutePosition();
        if (keywordAllowed("let")) {
            actions.add(refactor("var-to-let", "Change 'var' to 'let'",
                    edits.replace(from, from + 3, "let")));
        }
        if (keywordAllowed("const") && neverReassigned(declaration)) {
            actions.add(refactor("var-to-const", "Change 'var' to 'const'",
                    edits.replace(from, from + 3, "const")));
        }
    }

    /**
     * {@code ==} → {@code ===}, and {@code !=} → {@code !==}.
     *
     * <p>The classic, and the one exception is the reason it needs the AST rather than a text search:
     * {@code x == null} is <b>idiomatic</b> — it catches {@code undefined} too, which is usually what was
     * meant — so tightening it changes behaviour. Every style guide that mandates {@code ===} carves out
     * exactly this case.</p>
     */
    private void tightenEquality(List<CodeAction> actions, int caret) {
        InfixExpression comparison = enclosingLooseEquality(caret);
        if (comparison == null) return;
        String operator = edits.textOf(comparison);
        int at = operatorOffsetIn(comparison, operator);
        if (at < 0) return;
        if (isNullLiteral(comparison.getLeft()) || isNullLiteral(comparison.getRight())) return;

        boolean negated = operator.charAt(at - comparison.getAbsolutePosition()) == '!';
        String tightened = negated ? "!==" : "===";
        actions.add(refactor("tighten-equality", "Change to '" + tightened + "'",
                edits.replace(at, at + 2, tightened)));
    }

    /**
     * {@code Packages.a.b.C} ↔ {@code Java.type("a.b.C")} — either spelling, into the other.
     *
     * <p>Both resolve, so this is a preference rather than a repair; it is here because the two forms are
     * genuinely used and converting by hand means retyping a qualified name, which is where a typo comes
     * from.</p>
     */
    private void switchJavaTypeSpelling(List<CodeAction> actions, int caret) {
        AstNode node = nodeCovering(caret);
        for (AstNode at = node; at != null; at = at.getParent()) {
            String called = RhinoInference.javaTypeCall(at);
            if (called != null) {
                actions.add(refactor("to-packages", "Change to 'Packages." + called + "'",
                        edits.replaceNode(at, "Packages." + called)));
                return;
            }
            if (!(at instanceof PropertyGet)) continue;
            String chain = RhinoInference.javaNameOf(at, scopes::declaresAnywhere);
            if (chain == null) continue;
            actions.add(refactor("to-java-type", "Change to 'Java.type(\"" + chain + "\")'",
                    edits.replaceNode(at, "Java.type(\"" + chain + "\")")));
            return;
        }
    }

    /**
     * {@code 'a' + x + 'b'} → {@code `a${x}b`}.
     *
     * <p>Offered only when the band accepts template literals and only when the chain contains a string
     * literal — {@code a + b} on two numbers is arithmetic, and turning it into a template would change
     * what the program computes.</p>
     */
    private void concatenationToTemplate(List<CodeAction> actions, int caret) {
        if (!keywordAllowed("template")) return;
        InfixExpression chain = enclosingConcatenation(caret);
        if (chain == null) return;
        List<AstNode> parts = new ArrayList<>();
        if (!flattenConcatenation(chain, parts) || parts.size() < 2) return;

        // THE FIRST `+` HAS TO BE CONCATENATION ALREADY, which means one of the two leftmost parts is a
        // string. "the chain contains a string somewhere" is NOT the rule and changes what the program
        // computes: `1 + 2 + 'a'` is "3a", because the leftmost `+` is arithmetic -- and as a template it
        // becomes "12a". Every later operand is concatenated once the running value is a string, so only
        // the leftmost pair decides.
        if (!(parts.get(0) instanceof StringLiteral) && !(parts.get(1) instanceof StringLiteral)) return;

        StringBuilder out = new StringBuilder("`");
        for (AstNode part : parts) {
            if (part instanceof StringLiteral) {
                out.append(escapeForTemplate(((StringLiteral) part).getValue()));
            } else {
                out.append("${").append(edits.textOf(part)).append('}');
            }
        }
        out.append('`');
        actions.add(refactor("to-template", "Change to a template literal",
                edits.replaceNode(chain, out.toString())));
    }

    /** "Surround with try/catch" — the twin of {@code ExceptionCorrections}' second half. */
    private void wrapInTryCatch(List<CodeAction> actions, int caret) {
        AstNode statement = enclosingStatement(nodeCovering(caret));
        if (statement == null) return;
        int from = statement.getAbsolutePosition();
        int end = from + statement.getLength();
        // AND THERE HAS TO BE SOMETHING TO WRAP. A blank range is not a statement however the tree
        // reported it, and wrapping one produces an empty try block nobody asked for.
        if (edits.textIn(from, end).isBlank()) return;
        if (end < length() && ';' == charAt(end)) end++;
        String indent = edits.indentAt(from);
        // THE STATEMENT IS RE-INDENTED, because a body that keeps its old column reads as being outside
        // the block it is now inside -- which is the one thing a wrap must not leave ambiguous.
        String body = edits.textIn(from, end);
        String replacement = "try {\n" + indent + "    " + body + "\n"
                + indent + "} catch (e) {\n"
                + indent + "    console.error(e);\n"
                + indent + "}";
        actions.add(refactor("wrap-try-catch", "Surround with try/catch",
                edits.replace(from, end, replacement)));
    }

    // ── Finding what the caret is on ────────────────────────────────────────────────────────────

    @Nullable
    private RhinoScopes.Declaration unusedDeclarationAt(int from, int to) {
        for (RhinoScopes.Declaration declared : scopes.declarations()) {
            // THE ANALYSER'S OWN PREDICATE, not a second copy of it: a fix offered on a name carrying no
            // warning -- or missing on one that does -- is what two spellings of this rule produce the
            // first time either changes. @see RhinoScopes.Declaration#isReportableUnused
            if (!declared.isReportableUnused()) continue;
            int end = declared.offset + declared.length;
            if (from <= end && to >= declared.offset) return declared;
        }
        return null;
    }

    @Nullable
    private Name freeNameAt(int caret) {
        for (Name free : scopes.freeNames()) {
            int start = free.getAbsolutePosition();
            if (caret < start || caret > start + free.getLength()) continue;
            String name = free.getIdentifier();
            if (name == null || name.isEmpty()) continue;
            // A GLOBAL IS NOT A MISTAKE. `Math`, `console`, a name the last run left behind, and a Java
            // package root all resolve to nothing in the scopes and to something at run time -- offering
            // to rename or declare one would be offering to break working code.
            if (RhinoGlobals.isBuiltin(name) || resolution.liveNames().contains(name)) continue;
            // NOR IS A HOST BINDING. `world` is put in scope by the application; offering to declare it
            // as a local shadows the thing the script exists to reach.
            if (resolution.isHostBinding(name)) continue;
            if (RhinoInference.javaNameOf(free.getParent(), scopes::declaresAnywhere) != null) continue;
            return free;
        }
        return null;
    }

    @Nullable
    private VariableDeclaration enclosingVar(int caret) {
        for (AstNode at = nodeCovering(caret); at != null; at = at.getParent()) {
            if (at instanceof VariableDeclaration) return (VariableDeclaration) at;
            if (at instanceof FunctionNode) return null;
        }
        return null;
    }

    @Nullable
    private InfixExpression enclosingLooseEquality(int caret) {
        for (AstNode at = nodeCovering(caret); at != null; at = at.getParent()) {
            if (!(at instanceof InfixExpression)) continue;
            String text = edits.textOf(at);
            if (operatorOffsetIn((InfixExpression) at, text) >= 0) return (InfixExpression) at;
        }
        return null;
    }

    /**
     * Where a {@code ==} or {@code !=} sits inside a comparison, or {@code -1}.
     *
     * <p>Found in the text between the two operands rather than by asking for the operator, because a
     * {@code Token} constant cannot be compared across bands — the constants are inlined at compile time
     * and the bands renumbered them. @see RhinoTokens</p>
     */
    private int operatorOffsetIn(InfixExpression comparison, String text) {
        AstNode left = comparison.getLeft();
        AstNode right = comparison.getRight();
        if (left == null || right == null) return -1;
        int gapFrom = left.getAbsolutePosition() + left.getLength();
        int gapTo = right.getAbsolutePosition();
        for (int at = gapFrom; at + 1 < gapTo && at + 1 < length(); at++) {
            char c = charAt(at);
            if (charAt(at + 1) != '=') continue;
            if (c != '=' && c != '!') continue;
            // `===` AND `!==` ARE ALREADY STRICT. Their third character is what tells them apart from the
            // loose forms, and offering to "tighten" one would be an edit that changes nothing.
            if (at + 2 < gapTo && charAt(at + 2) == '=') return -1;
            return at;
        }
        return -1;
    }

    @Nullable
    private InfixExpression enclosingConcatenation(int caret) {
        InfixExpression outermost = null;
        for (AstNode at = nodeCovering(caret); at != null; at = at.getParent()) {
            if (at instanceof InfixExpression && "+".equals(operatorTextOf((InfixExpression) at))) {
                outermost = (InfixExpression) at;
            } else if (outermost != null) {
                break;
            }
        }
        return outermost;
    }

    /** The operator between two operands, as written. */
    private String operatorTextOf(InfixExpression expression) {
        AstNode left = expression.getLeft();
        AstNode right = expression.getRight();
        if (left == null || right == null) return "";
        return edits.textIn(left.getAbsolutePosition() + left.getLength(),
                right.getAbsolutePosition()).trim();
    }

    private boolean flattenConcatenation(AstNode node, List<AstNode> into) {
        if (node instanceof InfixExpression && "+".equals(operatorTextOf((InfixExpression) node))) {
            InfixExpression infix = (InfixExpression) node;
            return flattenConcatenation(infix.getLeft(), into)
                    && flattenConcatenation(infix.getRight(), into);
        }
        if (node == null) return false;
        into.add(node);
        return true;
    }

    /**
     * The statement {@code node} is part of — the node whose parent is a statement <em>container</em>.
     *
     * <p>Asked structurally rather than by listing statement classes, because Rhino has a dozen of them and
     * a list is a thing to forget an entry from. What makes a node a statement is what encloses it: a
     * {@link Block}, a {@link Scope} (which covers a function and the script), or the root.</p>
     *
     * <p><b>{@code Block} is the one that has to be named explicitly</b>, and leaving it out is not a near
     * miss: a function body is a {@code Block}, so without it the walk runs past every statement inside
     * every function and answers the function itself — and "insert above this statement" became "insert at
     * the top of the file". It read as an off-by-one in the offset arithmetic rather than as the wrong node.</p>
     */
    @Nullable
    private AstNode enclosingStatement(@Nullable AstNode node) {
        for (AstNode at = node; at != null; at = at.getParent()) {
            AstNode parent = at.getParent();
            // THE ROOT IS NOT A STATEMENT. Returning it for an empty document made "surround with
            // try/catch" offer to wrap nothing at all, in a file with nothing in it.
            if (parent == null) return at instanceof AstRoot ? null : at;
            if (parent instanceof Block || parent instanceof Scope || parent instanceof AstRoot) {
                return at;
            }
        }
        return null;
    }

    @Nullable
    private AstNode enclosingDeclaration(@Nullable AstNode node) {
        for (AstNode at = node; at != null; at = at.getParent()) {
            if (at instanceof VariableDeclaration || at instanceof FunctionNode) return at;
        }
        return null;
    }

    @Nullable
    private static VariableInitializer initializerFor(List<VariableInitializer> variables, int offset) {
        for (VariableInitializer initializer : variables) {
            AstNode target = initializer.getTarget();
            if (target != null && target.getAbsolutePosition() == offset) return initializer;
        }
        return null;
    }

    private boolean neverReassigned(VariableDeclaration declaration) {
        List<VariableInitializer> variables = declaration.getVariables();
        if (variables == null || variables.isEmpty()) return false;
        for (VariableInitializer initializer : variables) {
            // A `const` MUST BE INITIALISED, so `var x;` can never become one whatever else is true.
            if (initializer.getInitializer() == null) return false;
            AstNode target = initializer.getTarget();
            if (!(target instanceof Name)) return false;
            RhinoScopes.Declaration declared =
                    scopes.declarationOf((Name) target);
            if (declared == null || declared.reassigned) return false;
        }
        return true;
    }

    /**
     * The innermost node covering {@code offset} — the resolver's walk, not a second copy of it.
     *
     * <p>This class held its own visitor and called it five times per Alt+Enter, so one gesture walked the
     * whole tree six times. Cached per invocation because every caller in {@link #actionsIn} asks about the
     * same caret.</p>
     */
    @Nullable
    private AstNode nodeCovering(int offset) {
        if (root == null) return null;
        if (offset != coveringOffset) {
            coveringOffset = offset;
            covering = resolution.nodeCovering(offset);
        }
        return covering;
    }

    @Nullable private AstNode covering;
    private int coveringOffset = Integer.MIN_VALUE;

    private boolean isNullLiteral(@Nullable AstNode node) {
        return "null".equals(RhinoTokens.keywordOf(node));
    }

    /** Whether this band takes a keyword — measured, never assumed. @see JsKeywords */
    private boolean keywordAllowed(String keyword) {
        return resolution.supportedKeywords().contains(keyword);
    }

    private static String escapeForTemplate(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("`", "\\`").replace("${", "\\${");
    }

    private char charAt(int offset) {
        return edits.charAt(offset);
    }

    private int length() {
        return edits.length();
    }

    private CodeAction fix(String id, String title, ChangeSet edit) {
        return CodeAction.fix(ID + id, title, edit, edits.version());
    }

    /**
     * An intention with <b>no description</b> — Alt+Enter and the bulb, never a hover band.
     *
     * <p>Java's {@code FixContext.intention} takes a description and says why: the popup's header band
     * exists to state a diagnostic, an intention has none, and without a line of its own the band draws
     * as a blank grey strip that reads as a message which failed to load. {@code EditorLanguageFeatures}
     * enforces the other half — an action with neither a diagnostic behind it nor a description is left
     * out of the hover entirely.</p>
     *
     * <p>That is the right home for everything here <b>because these apply nearly everywhere</b>. "Change
     * 'var' to 'let'" fires on every {@code var} and "Surround with try/catch" on every statement, so a
     * hover anywhere in a script grew an action bar with nothing above it. IntelliJ keeps this class of
     * intention behind the bulb for the same reason.</p>
     *
     * <p><b>A shape-specific entry should describe itself and say so</b> — one that recognises a
     * particular construct, the way Java's {@code preferredIntention} does, has earned the band. Give it
     * a description and it appears there; that is the whole switch.</p>
     */
    private CodeAction refactor(String id, String title, ChangeSet edit) {
        return new CodeAction(ID + id, title, CodeActionKind.REFACTOR, edit, null, false,
                edits.version());
    }
}
