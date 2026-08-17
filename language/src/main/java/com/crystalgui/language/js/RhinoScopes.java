package com.crystalgui.language.js;

import com.crystalgui.text.lang.SymbolKind;

import org.mozilla.javascript.Token;
import org.mozilla.javascript.ast.Assignment;
import org.mozilla.javascript.ast.AstNode;
import org.mozilla.javascript.ast.AstRoot;
import org.mozilla.javascript.ast.FunctionNode;
import org.mozilla.javascript.ast.Name;
import org.mozilla.javascript.ast.NodeVisitor;
import org.mozilla.javascript.ast.ObjectProperty;
import org.mozilla.javascript.ast.PropertyGet;
import org.mozilla.javascript.ast.Scope;
import org.mozilla.javascript.ast.UnaryExpression;
import org.mozilla.javascript.ast.VariableDeclaration;
import org.mozilla.javascript.ast.VariableInitializer;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Who declared what, and who used it — read out of Rhino's own parse.
 *
 * <h3>Scope resolution is the parser's, and that is why this is not a second analyser</h3>
 *
 * <p>Rhino's parser resolves every identifier as it goes: {@link Name#getDefiningScope()} answers which
 * {@link Scope} introduced the name, or null when nothing did. That is the expensive half of scope
 * analysis, already done, on the tree the diagnostics came from — so reading it is not "building a
 * second JavaScript analyser on Rhino's AST", which the plan warned against. It is asking the parse a
 * question it already knows the answer to.</p>
 *
 * <p>The alternative was tree-sitter plus a {@code locals.scm} query, and it would have been a
 * <em>third</em> view of the same file — grammar tokens, Rhino diagnostics, tree-sitter scopes — that
 * disagrees with the engine exactly where it matters. A query would happily scope a {@code class} body
 * this engine refuses to run. {@code locals.scm} keeps its place for the languages with no engine.</p>
 *
 * <h3>Declarations come from the AST, not from the symbol table</h3>
 *
 * <p>The obvious route is {@link Scope#getSymbolTable()}, and it is a trap. The table <em>is</em>
 * populated — {@code {top=Symbol (VAR) name=top}} — but every {@code Symbol.getNode()} is <b>null</b> in
 * IDE mode, so there is no position to colour or to point a warning at. It cost a round to find,
 * because the table looks like exactly the right answer right up until you ask it where anything is.</p>
 *
 * <p>So a declaration is taken from the node that declares it, which is where the position lives
 * anyway: a {@link VariableDeclaration} carries its own {@code VAR}/{@code LET}/{@code CONST} token, and
 * a {@link FunctionNode} carries its name and its parameters. Keying on
 * {@code (defining scope, identifier)} joins the two halves — the AST for where, the parser's own
 * resolution for what refers to it.</p>
 *
 * <h3>What one walk produces</h3>
 *
 * <p>Every declaration with its kind, every reference to it, and the two facts that need a whole-file
 * view rather than a local one: whether a name is <b>reassigned</b> after its declaration, and whether
 * it is <b>captured</b> by a nested function. Both are invisible to a grammar — nothing in the shape of
 * {@code total} says either — and both are what makes engine colouring worth having.</p>
 */
final class RhinoScopes {

    /** One declared name: where, what kind, and what is true of it across the whole file. */
    static final class Declaration {

        final String name;
        final SymbolKind kind;
        final int offset;
        final int length;
        /** The function the declaration is inside, or null at the top level. */
        @Nullable final FunctionNode owner;

        /**
         * The name node itself — where the JSDoc above it is looked for.
         *
         * <p>Held rather than re-found from the offset because the tree is already in hand and a second
         * walk to recover a node we had would be the shape this whole class exists to avoid.</p>
         */
        @Nullable final AstNode declaringNode;

        /**
         * What it was declared equal to — the {@code InferenceTier}'s entire input.
         *
         * <p>{@code var x = new java.util.ArrayList()} keeps the {@code new} expression; a function
         * declaration keeps the {@link FunctionNode}; {@code var x;} keeps null, which is the honest
         * answer for a declaration that says nothing about its value.</p>
         */
        @Nullable final AstNode initializer;

        boolean reassigned;
        boolean captured;
        final List<int[]> references = new ArrayList<>();

        Declaration(String name, SymbolKind kind, int offset, int length,
                    @Nullable FunctionNode owner, @Nullable AstNode declaringNode,
                    @Nullable AstNode initializer) {
            this.name = name;
            this.kind = kind;
            this.offset = offset;
            this.length = length;
            this.owner = owner;
            this.declaringNode = declaringNode;
            this.initializer = initializer;
        }

        /** Declared and never mentioned again — what the unused-name warning is built from. */
        boolean isUnused() {
            return references.isEmpty();
        }
    }

    /**
     * A declaration's identity: the scope that introduced the name, and the name.
     *
     * <p>Scope identity is <b>reference</b> equality, which is what makes two different functions'
     * {@code i} two declarations rather than one. Rhino offers no id to compare instead, and keying on
     * the name alone would merge every loop counter in the file into one.</p>
     */
    private static final class Key {

        private final Scope scope;
        private final String name;

        Key(@Nullable Scope scope, String name) {
            this.scope = scope;
            this.name = name;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key that = (Key) other;
            return this.scope == that.scope && this.name.equals(that.name);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(scope) * 31 + name.hashCode();
        }
    }

    private final Map<Key, Declaration> byKey = new LinkedHashMap<>();
    private final List<Declaration> inOrder = new ArrayList<>();
    /** Every name node that resolved to nothing — a free name, which may still be a global. */
    private final List<Name> freeNames = new ArrayList<>();

    private RhinoScopes() {
    }

    /**
     * Walks the tree twice and answers everything below.
     *
     * <p>Twice rather than once, because a reference can precede its declaration — a function is
     * hoisted, and so is {@code var} — so a single pass would have to defer half its decisions anyway.
     * Two passes over a tree already in memory is the cheaper and far clearer arrangement.</p>
     */
    static RhinoScopes of(@Nullable AstRoot root) {
        RhinoScopes scopes = new RhinoScopes();
        if (root == null) return scopes;
        scopes.collectDeclarations(root);
        scopes.collectReferences(root);
        return scopes;
    }

    List<Declaration> declarations() {
        return inOrder;
    }

    List<Name> freeNames() {
        return freeNames;
    }

    /** The declaration a name resolves to, or null when it is free. */
    @Nullable
    Declaration declarationOf(Name name) {
        String identifier = name.getIdentifier();
        if (identifier == null) return null;
        Scope defining = name.getDefiningScope();
        if (defining != null) {
            Declaration found = byKey.get(new Key(defining, identifier));
            if (found != null) return found;
        }
        // THE PARSER DID NOT RESOLVE IT, so try the scopes this name is lexically inside. A `var` is
        // function-scoped and hoisted, and a name used before its declaration in a block the parser
        // recovered through can arrive here with no defining scope while the declaration plainly
        // exists -- reporting that as a free name would mark a declared local unresolved.
        for (AstNode at = name; at != null; at = at.getParent()) {
            if (!(at instanceof Scope)) continue;
            Declaration found = byKey.get(new Key((Scope) at, identifier));
            if (found != null) return found;
        }
        return null;
    }

    // ── Pass one: the nodes that declare something ─────────────────────────────────────────────

    private void collectDeclarations(AstRoot root) {
        root.visit(node -> {
            if (node instanceof VariableDeclaration) {
                // THE DECLARATION CARRIES ITS OWN KEYWORD -- `var`, `let` or `const` -- which is the one
                // thing the symbol table would have told us and the AST tells us better, because here it
                // comes with a position attached.
                // `isConst()`, NOT `getType() == Token.CONST`. The node's type field is what the
                // parser happened to set and is `Token.VAR` for a `const` on both bands -- so the
                // obvious comparison compiles, runs, and quietly colours every constant as a local.
                // Rhino ships the predicate for exactly this reason; use it.
                SymbolKind kind = ((VariableDeclaration) node).isConst()
                        ? SymbolKind.CONSTANT : SymbolKind.LOCAL_VARIABLE;
                for (VariableInitializer initializer : ((VariableDeclaration) node).getVariables()) {
                    declare(initializer.getTarget(), kind, enclosingFunction(node),
                            initializer.getInitializer());
                }
            } else if (node instanceof FunctionNode) {
                FunctionNode function = (FunctionNode) node;
                // A FUNCTION'S NAME BELONGS TO THE SCOPE AROUND IT, NOT TO ITSELF -- so the owner is the
                // function ENCLOSING this one. Reading it from the name node instead walks up into the
                // function being declared and reports every top-level function as owned, which made the
                // unused-name rule warn about `main` in the fixture this milestone is traced in.
                declare(function.getFunctionName(), SymbolKind.FUNCTION,
                        enclosingFunction(function.getParent()), function);
                // A PARAMETER, by contrast, genuinely belongs to its function -- so the name node's own
                // enclosing function is the right answer, and is what `declare` would have found anyway.
                for (AstNode parameter : function.getParams()) {
                    declare(parameter, SymbolKind.PARAMETER, function, null);
                }
            }
            return true;
        });
    }

    /**
     * Records one declared name.
     *
     * <p>Anything that is not a plain {@link Name} is skipped — a destructuring target is a pattern
     * rather than a name, and pulling its bindings out is a separate piece of work that a colour does
     * not need in order to be right about everything else.</p>
     */
    private void declare(@Nullable AstNode target, SymbolKind kind,
                         @Nullable FunctionNode owner, @Nullable AstNode initializer) {
        if (!(target instanceof Name)) return;
        Name name = (Name) target;
        String identifier = name.getIdentifier();
        if (identifier == null || identifier.isEmpty()) return;

        // THE DEFINING SCOPE AS THE PARSER SEES IT, falling back to the nearest enclosing one. A
        // declaration's own Name normally answers `getDefiningScope`, and a parameter of a function the
        // parser gave up on may not -- a declaration with no scope must still be coloured.
        Scope defining = name.getDefiningScope();
        if (defining == null) defining = enclosingScope(name);

        Key key = new Key(defining, identifier);
        if (byKey.containsKey(key)) return;
        Declaration declared = new Declaration(identifier, kind, name.getAbsolutePosition(),
                Math.max(1, name.getLength()), owner, name, initializer);
        byKey.put(key, declared);
        inOrder.add(declared);
    }

    // ── What the resolver asks ──────────────────────────────────────────────────────────────────

    /**
     * The declarations visible at {@code offset}, <b>nearest first</b>.
     *
     * <p>Nearest-first is the order completion wants and the order a reader expects: a local shadows a
     * global, and the thing you just declared is the thing you are most likely about to type. Ordered by
     * how deeply the declaration's owning function is nested rather than by textual distance, because
     * that is what scoping actually means — a name declared at the top of a long function is nearer than
     * one declared on the line above it at file scope.</p>
     *
     * <p>Hoisting is not modelled: every declaration in an enclosing function is visible, including ones
     * written below the offset. That is right for {@code var} and for a function declaration, and wrong
     * only for the temporal dead zone of a {@code let} — which is a runtime error rather than a
     * resolution question, and which no completion list has ever bothered to model.</p>
     */
    List<Declaration> visibleAt(int offset) {
        List<Declaration> visible = new ArrayList<>();
        for (Declaration declared : inOrder) {
            if (declared.owner == null || containsOffset(declared.owner, offset)) visible.add(declared);
        }
        visible.sort((a, b) -> Integer.compare(depthOf(b.owner), depthOf(a.owner)));
        return visible;
    }

    /** The declaration of {@code name} visible at {@code offset}, or null. */
    @Nullable
    Declaration visibleDeclaration(String name, int offset) {
        Declaration best = null;
        int bestDepth = -1;
        for (Declaration declared : inOrder) {
            if (!declared.name.equals(name)) continue;
            if (declared.owner != null && !containsOffset(declared.owner, offset)) continue;
            int depth = depthOf(declared.owner);
            if (depth > bestDepth) {
                best = declared;
                bestDepth = depth;
            }
        }
        return best;
    }

    /** Whether any declaration anywhere in the file carries this name — the package-root shadow test. */
    boolean declaresAnywhere(String name) {
        for (Declaration declared : inOrder) {
            if (declared.name.equals(name)) return true;
        }
        return false;
    }

    private static boolean containsOffset(@Nullable FunctionNode function, int offset) {
        if (function == null) return true;
        int start = function.getAbsolutePosition();
        return offset >= start && offset <= start + function.getLength();
    }

    private static int depthOf(@Nullable FunctionNode function) {
        int depth = 0;
        for (AstNode at = function; at != null; at = at.getParent()) {
            if (at instanceof FunctionNode) depth++;
        }
        return depth;
    }

    @Nullable
    private static Scope enclosingScope(AstNode node) {
        for (AstNode at = node; at != null; at = at.getParent()) {
            if (at instanceof Scope) return (Scope) at;
        }
        return null;
    }

    @Nullable
    private static FunctionNode enclosingFunction(@Nullable AstNode node) {
        for (AstNode at = node; at != null; at = at.getParent()) {
            if (at instanceof FunctionNode) return (FunctionNode) at;
        }
        return null;
    }

    // ── Pass two: every use, and the two whole-file facts ───────────────────────────────────────

    private void collectReferences(AstRoot root) {
        root.visit(new NodeVisitor() {
            @Override
            public boolean visit(AstNode node) {
                if (!(node instanceof Name)) return true;
                Name name = (Name) node;
                // A PROPERTY IS NOT A REFERENCE TO A LOCAL. `o.total` has a `Name` for `total` whose
                // defining scope is null, and treating it as a free name would report every property
                // access in the file as an unresolved global.
                if (isPropertyName(name)) return true;

                Declaration declared = declarationOf(name);
                if (declared == null) {
                    freeNames.add(name);
                    return true;
                }
                int at = name.getAbsolutePosition();
                // THE DECLARATION ITSELF IS NOT A USE. Counting it would make every declared name
                // referenced, which is exactly the question the unused warning asks.
                if (at == declared.offset) return true;

                declared.references.add(new int[]{at, Math.max(1, name.getLength())});
                if (isAssignmentTarget(name)) declared.reassigned = true;
                // CAPTURED: used from inside a function other than the one that declared it. That is
                // what a closure IS, and it is invisible to anything that has not resolved the scopes.
                FunctionNode using = enclosingFunction(name);
                if (using != declared.owner && isInside(using, declared.owner)) declared.captured = true;
                return true;
            }
        });
    }

    /**
     * Whether {@code name} is the property half of {@code a.b} rather than a variable reference.
     *
     * <p>Rhino models both as {@link Name}, so this is the one structural check the walk needs. Getting
     * it wrong is not subtle: every {@code .length} in the file becomes an unresolved global.</p>
     */
    private static boolean isPropertyName(Name name) {
        AstNode parent = name.getParent();
        if (parent instanceof PropertyGet) {
            // The right-hand half only. `a` in `a.b` is a real reference and must not be skipped, which
            // asking the node rather than comparing offsets makes obvious.
            return ((PropertyGet) parent).getProperty() == name;
        }
        // AN OBJECT LITERAL'S KEYS ARE NAMES TOO, and they declare nothing.
        //
        // BY POSITION, and NOT by `getLeft()` — which is the one place these two Rhinos are not source
        // compatible. On band 8 `ObjectProperty extends InfixExpression` and has `getLeft`/`getRight`;
        // on 1.9.1 it extends `AbstractObjectProperty` and has `getKey`/`getValue` instead. Compiling
        // against the oldest band proves the method EXISTS there and says nothing about whether the
        // call resolves on a newer one, so the obvious spelling compiled cleanly and died at runtime
        // with `NoSuchMethodError: ObjectProperty.getLeft()` on bands 11 and 17 only.
        //
        // A property begins at its key, so comparing positions asks the same question with nothing to
        // diverge. `RhinoCapabilityProbeTest` pins the rest of this surface per band and asserts this
        // divergence outright; that test is what this comment exists to point at.
        return parent instanceof ObjectProperty
                && name.getAbsolutePosition() == parent.getAbsolutePosition();
    }

    /** Whether this name is being written to — the left of an {@code =}, or a {@code ++}/{@code --}. */
    private static boolean isAssignmentTarget(Name name) {
        AstNode parent = name.getParent();
        // NOT `getOperator() == Token.INC`. Token constants are inlined at compile time and the bands
        // renumbered them, so that comparison quietly stopped recognising `count++` on every band but
        // the one this module compiles against -- and a reassignment that is not noticed is a colour
        // that is silently wrong. Found while typing a number literal as a boolean. @see RhinoTokens
        if (RhinoTokens.isIncrementOrDecrementOf(parent, name.getIdentifier())) return true;
        // THE LEFT SIDE ONLY: `a = b` reassigns `a` and merely reads `b`. Asking the Assignment node
        // for its left half says that; comparing offsets only happens to say it.
        return parent instanceof Assignment && ((Assignment) parent).getLeft() == name;
    }

    /** Whether {@code inner} is {@code outer} or nested inside it — {@code null} being the file. */
    private static boolean isInside(@Nullable FunctionNode inner, @Nullable FunctionNode outer) {
        if (outer == null) return inner != null;
        for (AstNode at = inner; at != null; at = at.getParent()) {
            if (at == outer) return true;
        }
        return false;
    }
}
