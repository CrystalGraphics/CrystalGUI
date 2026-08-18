package com.crystalgui.language.js.rhino.fix;

import com.crystalgui.language.js.rhino.JsKeywords;
import com.crystalgui.language.js.rhino.RhinoScopes;
import com.crystalgui.language.js.rhino.RhinoTokens;
import com.crystalgui.text.DerivedNames;
import com.crystalgui.text.lang.CodeAction;

import org.mozilla.javascript.ast.AstNode;
import org.mozilla.javascript.ast.AstRoot;
import org.mozilla.javascript.ast.Block;
import org.mozilla.javascript.ast.BreakStatement;
import org.mozilla.javascript.ast.ElementGet;
import org.mozilla.javascript.ast.ExpressionStatement;
import org.mozilla.javascript.ast.ForInLoop;
import org.mozilla.javascript.ast.ForLoop;
import org.mozilla.javascript.ast.FunctionCall;
import org.mozilla.javascript.ast.FunctionNode;
import org.mozilla.javascript.ast.IfStatement;
import org.mozilla.javascript.ast.InfixExpression;
import org.mozilla.javascript.ast.KeywordLiteral;
import org.mozilla.javascript.ast.Name;
import org.mozilla.javascript.ast.NewExpression;
import org.mozilla.javascript.ast.NodeVisitor;
import org.mozilla.javascript.ast.NumberLiteral;
import org.mozilla.javascript.ast.ObjectLiteral;
import org.mozilla.javascript.ast.ParenthesizedExpression;
import org.mozilla.javascript.ast.PropertyGet;
import org.mozilla.javascript.ast.ReturnStatement;
import org.mozilla.javascript.ast.Scope;
import org.mozilla.javascript.ast.StringLiteral;
import org.mozilla.javascript.ast.VariableDeclaration;
import org.mozilla.javascript.ast.VariableInitializer;
import org.mozilla.javascript.ast.WhileLoop;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The four intentions that <b>rebuild a construct</b>, rather than replacing a token inside one.
 *
 * <h3>Why they are not in {@code JsQuickFixes} with the others</h3>
 *
 * <p>Everything in that class edits a span the caret is already on: three characters of a {@code var}, an
 * operator between two operands, a qualified name for another spelling of itself. Its longest condition is
 * one question about one node. These four are a different shape and were deferred as a group for the same
 * reason — <b>each has to prove something about how a name is used across a whole body before it may fire
 * at all</b>, and the proof is the work. Whether {@code this} appears anywhere under a function; whether an
 * index is ever used for anything but indexing; whether every arm of a chain tests one variable. Get the
 * proof wrong and the edit is not merely unhelpful, it silently changes what the program does.</p>
 *
 * <h3>The band question is asked three times and answered by measurement</h3>
 *
 * <p>Arrows and {@code for…of} are syntax a 1.7.10 host's Rhino may simply refuse, so both are gated on
 * {@link JsKeywords}' probes rather than on a version number. A fix that produced source the engine will
 * not parse is worse than no fix: the author accepts it, the file breaks, and nothing said it would.</p>
 *
 * <h3>What each one refuses, and why refusing is the feature</h3>
 *
 * <ul>
 *   <li><b>Arrow</b> — a body naming {@code this} or {@code arguments}. An arrow binds both lexically, so
 *       the converted function reads two different values under the same names.</li>
 *   <li><b>{@code for…of}</b> — an index used for anything but a fetch, and a sequence that is not a plain
 *       name. {@code for (i = 0; i &lt; list().length; i++)} calls {@code list()} every iteration and the
 *       {@code of} form calls it once.</li>
 *   <li><b>{@code switch}</b> — an arm whose body already contains a {@code break}. It breaks the
 *       enclosing loop today and would break the new {@code switch} tomorrow, which is a different
 *       program that still compiles.</li>
 *   <li><b>Extract</b> — an expression in a loop header. Hoisting it above the loop evaluates it once
 *       instead of every iteration.</li>
 * </ul>
 */
final class JsIntentions {

    private final AstRoot root;
    private final RhinoScopes scopes;
    private final JsRewrites edits;
    private final Predicate<String> bandAccepts;
    private final List<String> keywords;

    JsIntentions(AstRoot root, RhinoScopes scopes, JsRewrites edits, List<String> keywords) {
        this.root = root;
        this.scopes = scopes;
        this.edits = edits;
        this.keywords = keywords;
        this.bandAccepts = keywords::contains;
    }

    /**
     * Everything this half offers at {@code caret}.
     *
     * @param covering the innermost node covering the caret — passed in rather than re-derived, because
     *                 {@code JsQuickFixes} already caches it and a second walk per Alt+Enter is a whole
     *                 tree traversal for an answer that is sitting in a field
     */
    void contribute(List<CodeAction> actions, int caret, @Nullable AstNode covering,
                    ActionFactory factory) {
        if (root == null || covering == null) return;
        toArrowFunction(actions, covering, factory);
        toForOf(actions, covering, factory);
        toSwitch(actions, covering, factory);
        extractToLocal(actions, covering, factory);
    }

    /** How this half builds an action — {@code JsQuickFixes} owns the id prefix and the kind. */
    interface ActionFactory {
        CodeAction refactor(String id, String title, com.crystalgui.text.ChangeSet edit);
    }

    // ── function expression → arrow ─────────────────────────────────────────────────────────────

    /**
     * {@code function (a) { return a + 1; }} → {@code (a) => a + 1}.
     *
     * <p>The JavaScript twin of {@code LambdaCorrections}, and it turns on one question the Java version
     * never has to ask: <b>an arrow does not bind {@code this} or {@code arguments}</b>, it inherits them
     * from where it was written. So a body naming either means something different after the conversion —
     * and it still runs, which is what makes it worth refusing rather than warning about.</p>
     *
     * <p>Three further refusals, each for a shape that survives conversion and misbehaves:</p>
     *
     * <ul>
     *   <li><b>A function <em>declaration</em> is not an expression.</b> {@code function f() {}} at
     *       statement level is hoisted; {@code var f = () => {}} is not, so anything calling {@code f}
     *       above its own line stops working. Asked <b>structurally</b> — whether the node's parent is a
     *       statement container — and never through {@code getFunctionType()}, which is a
     *       {@code static final int} javac inlines and the bands renumber.</li>
     *   <li><b>A named function expression that calls itself.</b> The name is the only handle the body has
     *       on itself, and the arrow form has nowhere to put it.</li>
     *   <li><b>The callee of a {@code new}.</b> Arrows are not constructors; this one fails loudly at run
     *       time rather than quietly, but it fails.</li>
     * </ul>
     */
    private void toArrowFunction(List<CodeAction> actions, AstNode covering, ActionFactory factory) {
        if (!bandAccepts.test("arrow")) return;
        FunctionNode function = enclosing(covering, FunctionNode.class);
        if (function == null || !isExpression(function)) return;
        if (function.getParent() instanceof NewExpression) return;

        AstNode body = function.getBody();
        if (body == null) return;
        if (namesAnyOf(body, Set.of("this", "arguments"))) return;

        String selfName = function.getName();
        if (selfName != null && !selfName.isEmpty() && namesAnyOf(body, Set.of(selfName))) return;

        StringBuilder out = new StringBuilder();
        out.append('(');
        List<AstNode> params = function.getParams();
        if (params != null) {
            for (int i = 0; i < params.size(); i++) {
                if (i > 0) out.append(", ");
                out.append(edits.textOf(params.get(i)));
            }
        }
        out.append(") => ");

        AstNode onlyReturned = soleReturnedExpression(body);
        if (onlyReturned != null) {
            // AN OBJECT LITERAL NEEDS ITS OWN BRACKETS. `() => {a: 1}` parses as a body containing a
            // label, so the function returns undefined -- legal, silent, and not what was written.
            boolean isObject = unwrapped(onlyReturned) instanceof ObjectLiteral;
            out.append(isObject ? "(" : "").append(edits.textOf(onlyReturned)).append(isObject ? ")" : "");
        } else {
            out.append(edits.textOf(body));
        }
        actions.add(factory.refactor("to-arrow", "Convert to an arrow function",
                edits.replaceNode(function, out.toString())));
    }

    /**
     * A function is an expression when a statement container is <em>not</em> its parent.
     *
     * <p>The same structural question {@code JsQuickFixes.enclosingStatement} asks, and for the same
     * reason: the alternative is {@code getFunctionType() == FunctionNode.FUNCTION_EXPRESSION}, and those
     * constants are inlined at compile time against one band's jar. {@code RhinoTokens} records what that
     * costs — a comparison that silently answers about something else on the band most users run.</p>
     */
    private static boolean isExpression(FunctionNode function) {
        AstNode parent = function.getParent();
        if (parent == null) return false;
        return !(parent instanceof Block || parent instanceof Scope || parent instanceof AstRoot);
    }

    /** The expression of a body that is exactly one {@code return}, or null. */
    @Nullable
    private AstNode soleReturnedExpression(AstNode body) {
        List<AstNode> statements = statementsOf(body);
        if (statements.size() != 1) return null;
        AstNode only = statements.get(0);
        if (!(only instanceof ReturnStatement)) return null;
        return ((ReturnStatement) only).getReturnValue();
    }

    // ── index for → for…of ──────────────────────────────────────────────────────────────────────

    /**
     * {@code for (var i = 0; i < xs.length; i++)} → {@code for (var x of xs)}.
     *
     * <p>{@code LoopIntentions}' rule, ported, and the rule is <b>not</b> "this is a counted loop" — it is
     * "the index is invisible afterwards". Every use of {@code i} in the body must be {@code xs[i]}. One
     * use for anything else and the {@code of} form cannot express the loop at all, because it has no
     * index to offer; without that check the conversion produces code that does not run and looks like it
     * should.</p>
     *
     * <p>And the sequence has to be a plain name. {@code xs.length} on a call is re-evaluated every
     * iteration in the index form and once in the {@code of} form — usually what the author wanted, and
     * not the same program. The Java version says the same thing about a method call in its condition.</p>
     */
    private void toForOf(List<CodeAction> actions, AstNode covering, ActionFactory factory) {
        if (!bandAccepts.test("of")) return;
        ForLoop loop = enclosing(covering, ForLoop.class);
        if (loop == null) return;

        String index = declaredIndexOf(loop);
        if (index == null) return;
        String sequence = countedSequenceOf(loop, index);
        if (sequence == null) return;
        if (!"++".equals(incrementSpellingOf(loop, index))) return;

        AstNode body = loop.getBody();
        if (body == null) return;
        List<Name> uses = namedUsesIn(body, index);
        if (uses.isEmpty()) return;
        List<AstNode> fetches = new ArrayList<>(uses.size());
        for (Name use : uses) {
            AstNode fetch = fetchThrough(use, sequence);
            if (fetch == null) return;
            fetches.add(fetch);
        }
        // THE SEQUENCE MUST NOT BE WRITTEN IN THE BODY EITHER. `xs = other;` mid-loop re-reads
        // `xs.length` on the next iteration in the index form, and iterates the original in the `of`
        // form -- two different programs with no visible difference at the edit site.
        for (Name use : namedUsesIn(body, sequence)) {
            if (isWrittenTo(use)) return;
        }

        String element = elementNameFor(sequence, loop);
        List<com.crystalgui.text.Change> changes = new ArrayList<>(fetches.size() + 1);
        int headerFrom = loop.getAbsolutePosition();
        int headerTo = body.getAbsolutePosition();
        String header = "for (var " + element + " of " + sequence + ") ";
        changes.add(new com.crystalgui.text.Change(headerFrom, headerTo, header));
        for (AstNode fetch : fetches) {
            int from = fetch.getAbsolutePosition();
            changes.add(new com.crystalgui.text.Change(from, from + fetch.getLength(), element));
        }
        actions.add(factory.refactor("to-for-of", "Convert to 'for…of'",
                com.crystalgui.text.ChangeSet.of(edits.length(), changes)));
    }

    /** The name a {@code for}'s initializer declares and sets to zero, or null. */
    @Nullable
    private String declaredIndexOf(ForLoop loop) {
        AstNode initializer = loop.getInitializer();
        if (!(initializer instanceof VariableDeclaration)) return null;
        List<VariableInitializer> variables = ((VariableDeclaration) initializer).getVariables();
        if (variables == null || variables.size() != 1) return null;
        VariableInitializer only = variables.get(0);
        if (!(only.getTarget() instanceof Name)) return null;
        AstNode from = only.getInitializer();
        if (!(from instanceof NumberLiteral) || !"0".equals(edits.textOf(from).trim())) return null;
        return ((Name) only.getTarget()).getIdentifier();
    }

    /** The sequence in {@code index < NAME.length}, or null when the condition is any other shape. */
    @Nullable
    private String countedSequenceOf(ForLoop loop, String index) {
        AstNode condition = loop.getCondition();
        if (!(condition instanceof InfixExpression)) return null;
        InfixExpression test = (InfixExpression) condition;
        if (!"<".equals(operatorTextOf(test))) return null;
        if (!(test.getLeft() instanceof Name)
                || !index.equals(((Name) test.getLeft()).getIdentifier())) {
            return null;
        }
        if (!(test.getRight() instanceof PropertyGet)) return null;
        PropertyGet length = (PropertyGet) test.getRight();
        if (length.getProperty() == null || !"length".equals(length.getProperty().getIdentifier())) {
            return null;
        }
        // A PLAIN NAME AND NOTHING ELSE. `a.b.length` would be repeatable too, but `a().b.length` is not
        // and the two are one node type apart -- so the rule is the narrow one it can be sure of.
        if (!(length.getTarget() instanceof Name)) return null;
        return ((Name) length.getTarget()).getIdentifier();
    }

    /** {@code "++"} for {@code i++} or {@code ++i}, else whatever was written. */
    private String incrementSpellingOf(ForLoop loop, String index) {
        AstNode increment = loop.getIncrement();
        if (increment == null) return "";
        String written = edits.textOf(increment).trim();
        if ((index + "++").equals(written) || ("++" + index).equals(written)) return "++";
        return written;
    }

    /** The {@code xs[i]} a use of the index sits inside, or null when it is used for anything else. */
    @Nullable
    private AstNode fetchThrough(Name use, String sequence) {
        AstNode parent = use.getParent();
        if (!(parent instanceof ElementGet)) return null;
        ElementGet fetch = (ElementGet) parent;
        if (fetch.getElement() != use) return null;
        if (!(fetch.getTarget() instanceof Name)) return null;
        if (!sequence.equals(((Name) fetch.getTarget()).getIdentifier())) return null;
        // AND `xs[i] = v` IS NOT A FETCH. It compiles after the conversion and writes to a local copy
        // instead of the array, which is the one shape here that produces a working, wrong program.
        return isWrittenTo(fetch) ? null : fetch;
    }

    /**
     * A name for the element, singularised from the sequence.
     *
     * <p>The one place this diverges from {@code LoopIntentions}, which derives from the element's
     * <em>type</em> because it has a binding to ask. There is no type here, so the collection's name is
     * the only evidence available — and it is usually good evidence, because a collection is nearly always
     * named for what it holds.</p>
     */
    private String elementNameFor(String sequence, ForLoop loop) {
        String stem = sequence;
        if (stem.endsWith("ies") && stem.length() > 3) {
            stem = stem.substring(0, stem.length() - 3) + "y";
        } else if (stem.endsWith("sses") || stem.endsWith("shes") || stem.endsWith("ches")) {
            stem = stem.substring(0, stem.length() - 2);
        } else if (stem.endsWith("s") && !stem.endsWith("ss") && stem.length() > 1) {
            stem = stem.substring(0, stem.length() - 1);
        } else {
            stem = "element";
        }
        return DerivedNames.derive(stem, takenAround(loop), reservedWords());
    }

    // ── if-chain → switch ───────────────────────────────────────────────────────────────────────

    /**
     * {@code if (x === 'a') … else if (x === 'b') … else …} → a {@code switch}.
     *
     * <p>{@code SwitchIntentions}' rule, ported: every arm must test <b>one</b> variable against a
     * literal, and the literals must differ. Two arms minimum, because a single {@code if} rewritten as a
     * {@code switch} is longer and says less.</p>
     *
     * <h3>Two things that convert cleanly and mean something else afterwards</h3>
     *
     * <p><b>A {@code break} inside an arm.</b> Written inside an {@code if} in a loop it leaves the loop;
     * the same statement inside a {@code switch} leaves the switch. Nothing fails, the loop simply stops
     * stopping. Refused rather than rewritten, because rewriting it means inventing a label the author
     * never wrote.</p>
     *
     * <p><b>Two arms declaring the same name.</b> The arms were separate blocks and the cases are one, so
     * two {@code let x} in a converted chain is a syntax error and two {@code var x} silently share a
     * binding. Each case therefore keeps its own braces — which is what a careful hand-conversion does
     * anyway, and costs one line per arm.</p>
     */
    private void toSwitch(List<CodeAction> actions, AstNode covering, ActionFactory factory) {
        IfStatement chain = outermostIfAt(covering);
        if (chain == null) return;

        String subject = null;
        List<String> literals = new ArrayList<>();
        List<AstNode> bodies = new ArrayList<>();
        AstNode fallback = null;

        for (IfStatement arm = chain; arm != null; ) {
            InfixExpression test = equalityIn(arm.getCondition());
            if (test == null) return;
            String tested = testedNameIn(test);
            String literal = testedLiteralIn(test);
            if (tested == null || literal == null) return;
            if (subject == null) subject = tested;
            else if (!subject.equals(tested)) return;
            if (literals.contains(literal)) return;
            if (arm.getThenPart() == null || containsLooseBreak(arm.getThenPart())) return;
            literals.add(literal);
            bodies.add(arm.getThenPart());

            AstNode otherwise = arm.getElsePart();
            if (otherwise instanceof IfStatement) {
                arm = (IfStatement) otherwise;
            } else {
                if (otherwise != null && containsLooseBreak(otherwise)) return;
                fallback = otherwise;
                arm = null;
            }
        }
        if (literals.size() < 2) return;

        String indent = edits.indentAt(chain.getAbsolutePosition());
        StringBuilder out = new StringBuilder("switch (").append(subject).append(") {\n");
        for (int i = 0; i < literals.size(); i++) {
            out.append(indent).append("    case ").append(literals.get(i)).append(": {\n");
            appendReindented(out, bodies.get(i), indent + "        ");
            out.append(indent).append("        break;\n").append(indent).append("    }\n");
        }
        if (fallback != null) {
            out.append(indent).append("    default: {\n");
            appendReindented(out, fallback, indent + "        ");
            out.append(indent).append("    }\n");
        }
        out.append(indent).append('}');
        actions.add(factory.refactor("to-switch", "Convert to 'switch'",
                edits.replaceNode(chain, out.toString())));
    }

    /** The outermost {@code if} of the chain the caret is in, or null. */
    @Nullable
    private IfStatement outermostIfAt(AstNode covering) {
        IfStatement found = null;
        for (AstNode at = covering; at != null; at = at.getParent()) {
            if (at instanceof IfStatement) found = (IfStatement) at;
            if (at instanceof FunctionNode) break;
        }
        // AND THE CHAIN STARTS WHERE NOTHING ELSE'S `else` IS. An `else if` is an IfStatement whose
        // parent is the IfStatement above it, so walking up alone stops one arm short of the top.
        while (found != null && found.getParent() instanceof IfStatement
                && ((IfStatement) found.getParent()).getElsePart() == found) {
            found = (IfStatement) found.getParent();
        }
        return found;
    }

    @Nullable
    private InfixExpression equalityIn(@Nullable AstNode condition) {
        AstNode node = unwrapped(condition);
        if (!(node instanceof InfixExpression)) return null;
        String operator = operatorTextOf((InfixExpression) node);
        return "===".equals(operator) || "==".equals(operator) ? (InfixExpression) node : null;
    }

    /** The name side of {@code x === 'a'}, whichever side it was written on. */
    @Nullable
    private String testedNameIn(InfixExpression test) {
        if (test.getLeft() instanceof Name && isLiteral(test.getRight())) {
            return ((Name) test.getLeft()).getIdentifier();
        }
        if (test.getRight() instanceof Name && isLiteral(test.getLeft())) {
            return ((Name) test.getRight()).getIdentifier();
        }
        return null;
    }

    @Nullable
    private String testedLiteralIn(InfixExpression test) {
        if (isLiteral(test.getRight())) return edits.textOf(test.getRight()).trim();
        if (isLiteral(test.getLeft())) return edits.textOf(test.getLeft()).trim();
        return null;
    }

    private static boolean isLiteral(@Nullable AstNode node) {
        return node instanceof StringLiteral || node instanceof NumberLiteral;
    }

    /**
     * A {@code break} that would change what it leaves.
     *
     * <p>Only a bare one — a {@code break} inside a nested loop or switch belongs to that, and a labelled
     * one names its target explicitly and is unaffected either way.</p>
     */
    private boolean containsLooseBreak(AstNode body) {
        boolean[] found = {false};
        body.visit(node -> {
            if (found[0]) return false;
            if (node != body && (node instanceof ForLoop || node instanceof ForInLoop
                    || node instanceof WhileLoop || node instanceof FunctionNode)) {
                return false;
            }
            if (node instanceof BreakStatement) found[0] = true;
            return !found[0];
        });
        return found[0];
    }

    /** A body's statements, written out one per line at {@code indent}. */
    private void appendReindented(StringBuilder out, AstNode body, String indent) {
        for (AstNode statement : statementsOf(body)) {
            out.append(indent).append(edits.textOf(statement).trim());
            if (!edits.textOf(statement).trim().endsWith(";")
                    && !edits.textOf(statement).trim().endsWith("}")) {
                out.append(';');
            }
            out.append('\n');
        }
    }

    // ── extract to local ────────────────────────────────────────────────────────────────────────

    /**
     * The expression under the caret, hoisted into a {@code var} above its statement.
     *
     * <p>{@code Names}' deriving half is what this was waiting on and the reason it was deferred: that
     * half took a JDT binding, so there was nothing for JavaScript to reuse. It is now
     * {@link DerivedNames} in {@code core}, split at the line where the rule stops needing a type.</p>
     *
     * <p><b>Never in a loop header.</b> Hoisting {@code xs.length} out of {@code while (i < xs.length)}
     * evaluates it once and the loop stops terminating — a hang rather than an error, from an edit the
     * author accepted without reading. That is the whole guard: the enclosing statement being a loop means
     * the expression is in its header, since a body is a {@code Block} and would have answered with the
     * statement inside it.</p>
     *
     * <p>Declared with {@code var} rather than {@code let}, because {@code var} is the one keyword every
     * band takes and "Change 'var' to 'let'" is already the next entry in this catalog.</p>
     */
    private void extractToLocal(List<CodeAction> actions, AstNode covering, ActionFactory factory) {
        AstNode expression = extractableAt(covering);
        if (expression == null) return;
        AstNode statement = JsQuickFixes.enclosingStatementOf(expression);
        if (statement == null || statement == expression) return;
        if (statement instanceof ForLoop || statement instanceof ForInLoop
                || statement instanceof WhileLoop) {
            return;
        }

        String name = DerivedNames.derive(stemFor(expression), takenAround(statement), reservedWords());
        String indent = edits.indentAt(statement.getAbsolutePosition());
        int lineStart = edits.lineStartAt(statement.getAbsolutePosition());
        int from = expression.getAbsolutePosition();

        List<com.crystalgui.text.Change> changes = new ArrayList<>(2);
        changes.add(new com.crystalgui.text.Change(lineStart, lineStart,
                indent + "var " + name + " = " + edits.textOf(expression) + ";\n"));
        changes.add(new com.crystalgui.text.Change(from, from + expression.getLength(), name));
        actions.add(factory.refactor("extract-local", "Introduce variable '" + name + "'",
                com.crystalgui.text.ChangeSet.of(edits.length(), changes)));
    }

    /**
     * The expression worth extracting at the caret, or null.
     *
     * <p>A bare {@code Name} is refused: {@code var y = x;} is not a refactoring, it is a second name for
     * the same thing. So is a whole expression statement, which is already as extracted as it gets.</p>
     */
    @Nullable
    private AstNode extractableAt(AstNode covering) {
        for (AstNode at = covering; at != null; at = at.getParent()) {
            if (at instanceof Name || at instanceof AstRoot) continue;
            // A CALLEE IS NOT AN EXPRESSION TO EXTRACT. The caret on `getName` in
            // `player.getName()` sits on a PropertyGet, and hoisting THAT gives
            // `var getName = player.getName; getName();` -- which loses the receiver, so the method runs
            // with the wrong `this`. It still parses and usually still returns something. Walk on to the
            // call, which is the thing the author was pointing at anyway.
            if (at.getParent() instanceof FunctionCall
                    && ((FunctionCall) at.getParent()).getTarget() == at) {
                continue;
            }
            if (at instanceof FunctionCall || at instanceof PropertyGet || at instanceof ElementGet
                    || at instanceof InfixExpression || at instanceof StringLiteral
                    || at instanceof NumberLiteral || at instanceof ObjectLiteral) {
                // NOT WHEN IT IS THE STATEMENT ITSELF. `foo();` on its own line has nothing to introduce
                // a variable for -- the value is already being discarded on purpose.
                if (at.getParent() instanceof ExpressionStatement) return null;
                return at;
            }
            if (at instanceof Block || at instanceof Scope) return null;
        }
        return null;
    }

    /** What to call the extracted value, read off what it is. */
    private String stemFor(AstNode expression) {
        AstNode node = unwrapped(expression);
        if (node instanceof NewExpression) {
            AstNode target = ((NewExpression) node).getTarget();
            return target instanceof Name
                    ? DerivedNames.lower(((Name) target).getIdentifier()) : "value";
        }
        if (node instanceof FunctionCall) {
            AstNode target = ((FunctionCall) node).getTarget();
            if (target instanceof PropertyGet) {
                Name called = ((PropertyGet) target).getProperty();
                if (called != null) return DerivedNames.fromAccessor(called.getIdentifier());
            }
            if (target instanceof Name) {
                return DerivedNames.fromAccessor(((Name) target).getIdentifier());
            }
        }
        if (node instanceof PropertyGet) {
            Name property = ((PropertyGet) node).getProperty();
            if (property != null) return property.getIdentifier();
        }
        if (node instanceof StringLiteral) return "text";
        return "value";
    }

    // ── Shared walking ──────────────────────────────────────────────────────────────────────────

    /**
     * Whether {@code body} names any of {@code wanted}, <b>not descending into a nested function</b>.
     *
     * <p>The exclusion is the point for the arrow conversion: a nested {@code function} rebinds
     * {@code this} and {@code arguments}, so one that uses them says nothing about the function being
     * converted. Including them would refuse the commonest shape there is — a callback inside a callback.</p>
     *
     * <p>Walked with {@code visit} rather than by reading children, because that is the one reading true
     * on both bands: a node's parts are fields on the band we run against and entries in the generic child
     * list on the band we compile against, so {@code getFirstChild()} answers null for half the tree.</p>
     *
     * <p><b>And {@code this} is recognised through {@link RhinoTokens}, never through the source it
     * covers.</b> Measured: the {@code KeywordLiteral} in {@code this.x} reports its length as <em>five</em>
     * characters, so reading the span gives {@code "this."} — dot included — and a comparison against
     * {@code "this"} quietly never matches. The arrow conversion was therefore offered on every function
     * that used {@code this}, which is the exact case it exists to refuse. {@code toSource()} renders the
     * node instead of measuring it, which is the whole reason that class exists.</p>
     */
    private boolean namesAnyOf(AstNode body, Set<String> wanted) {
        boolean[] found = {false};
        body.visit(node -> {
            if (found[0]) return false;
            if (node != body && node instanceof FunctionNode) return false;
            if (node instanceof Name && wanted.contains(((Name) node).getIdentifier())) {
                // A PROPERTY IS NOT A REFERENCE. `o.arguments` names a member of `o`, and reading it as a
                // use of the function's own `arguments` refuses conversions that were always safe.
                if (!isPropertyName((Name) node)) found[0] = true;
            } else if (node instanceof KeywordLiteral
                    && wanted.contains(RhinoTokens.keywordOf(node))) {
                found[0] = true;
            }
            return !found[0];
        });
        return found[0];
    }

    /** Every reference to {@code identifier} inside {@code body}, property names excluded. */
    private List<Name> namedUsesIn(AstNode body, String identifier) {
        List<Name> uses = new ArrayList<>();
        body.visit(node -> {
            if (node instanceof Name && identifier.equals(((Name) node).getIdentifier())
                    && !isPropertyName((Name) node)) {
                uses.add((Name) node);
            }
            return true;
        });
        return uses;
    }

    private static boolean isPropertyName(Name name) {
        AstNode parent = name.getParent();
        return parent instanceof PropertyGet && ((PropertyGet) parent).getProperty() == name;
    }

    /**
     * Every operator that <b>writes</b> to its left operand, listed rather than derived.
     *
     * <p>The derived version — "ends with {@code =} and does not begin with one" — reads as though it
     * covers this and gets the most important case backwards: plain {@code =} both ends and begins with
     * {@code =}, so it was excluded, and {@code xs[i] = 0} was accepted as a fetch. The {@code for…of}
     * conversion then wrote to a loop variable instead of to the array: a program that still runs, still
     * looks converted, and silently stops storing anything.</p>
     */
    private static final Set<String> WRITES = Set.of(
            "=", "+=", "-=", "*=", "/=", "%=", "**=", "&=", "|=", "^=",
            "<<=", ">>=", ">>>=", "&&=", "||=", "??=");

    /** Whether {@code node} is the left of an assignment — asked of the text, since operators renumber. */
    private boolean isWrittenTo(AstNode node) {
        AstNode parent = node.getParent();
        if (!(parent instanceof InfixExpression)) return false;
        InfixExpression assignment = (InfixExpression) parent;
        if (assignment.getLeft() != node) return false;
        return WRITES.contains(operatorTextOf(assignment));
    }

    /** The operator between two operands, as written. @see JsQuickFixes */
    private String operatorTextOf(InfixExpression expression) {
        AstNode left = expression.getLeft();
        AstNode right = expression.getRight();
        if (left == null || right == null) return "";
        return edits.textIn(left.getAbsolutePosition() + left.getLength(),
                right.getAbsolutePosition()).trim();
    }

    private static AstNode unwrapped(@Nullable AstNode node) {
        AstNode at = node;
        while (at instanceof ParenthesizedExpression) {
            at = ((ParenthesizedExpression) at).getExpression();
        }
        return at;
    }

    /** A body's statements — the block's children, or the one statement a braceless body is. */
    private List<AstNode> statementsOf(AstNode body) {
        List<AstNode> statements = new ArrayList<>();
        if (body instanceof Block || body instanceof Scope) {
            for (Object child : (Iterable<?>) body) {
                if (child instanceof AstNode) statements.add((AstNode) child);
            }
        } else {
            statements.add(body);
        }
        return statements;
    }

    @Nullable
    private static <T extends AstNode> T enclosing(AstNode from, Class<T> type) {
        for (AstNode at = from; at != null; at = at.getParent()) {
            if (type.isInstance(at)) return type.cast(at);
        }
        return null;
    }

    /** Every name in scope where {@code at} is, so a derived one does not shadow a live one. */
    private Set<String> takenAround(AstNode at) {
        Set<String> taken = new LinkedHashSet<>();
        for (RhinoScopes.Declaration declared : scopes.visibleAt(at.getAbsolutePosition())) {
            taken.add(declared.name);
        }
        for (RhinoScopes.Declaration declared : scopes.declarations()) taken.add(declared.name);
        return taken;
    }

    /**
     * This band's reserved words.
     *
     * <p>The measured set minus what is measured but is not a word — {@code template} and {@code arrow}
     * are constructs, and {@code var arrow = …} is perfectly legal. @see JsKeywords#notAKeyword</p>
     */
    private Set<String> reservedWords() {
        Set<String> reserved = new LinkedHashSet<>(keywords);
        reserved.removeAll(JsKeywords.notAKeyword());
        return reserved;
    }
}
