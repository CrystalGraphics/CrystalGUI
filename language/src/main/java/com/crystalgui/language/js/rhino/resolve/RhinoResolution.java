package com.crystalgui.language.js.rhino.resolve;

import com.crystalgui.language.engine.bridge.LiveScopeSnapshot;
import com.crystalgui.language.js.rhino.exec.RhinoGlobals;
import com.crystalgui.language.js.rhino.RhinoScopes;
import com.crystalgui.text.lang.DeclarationSite;
import com.crystalgui.text.lang.Signature;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.text.lang.SymbolModifier;
import com.crystalgui.text.lang.TypeRef;

import org.mozilla.javascript.ast.AstNode;
import org.mozilla.javascript.ast.AstRoot;
import org.mozilla.javascript.ast.FunctionCall;
import org.mozilla.javascript.ast.FunctionNode;
import org.mozilla.javascript.ast.Name;
import org.mozilla.javascript.ast.NewExpression;
import org.mozilla.javascript.ast.PropertyGet;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a name at an offset is — the four tiers, asked in order.
 *
 * <h3>The order is the order of certainty</h3>
 *
 * <table>
 *   <tr><th>Tier</th><th>Answers when</th><th>Shown as</th></tr>
 *   <tr><td>live scope</td><td>a run has completed and left this name a global</td><td>from last run</td></tr>
 *   <tr><td>JSDoc</td><td>the declaration carries {@code @type}/{@code @returns}/{@code @param}</td>
 *       <td>from JSDoc</td></tr>
 *   <tr><td>inference</td><td>the initializer says — a literal, {@code new X()}, {@code Java.type}</td>
 *       <td>nothing; it is the ordinary answer</td></tr>
 *   <tr><td>declaration</td><td>the name is declared and nothing above answered</td><td>nothing</td></tr>
 * </table>
 *
 * <p>What a value <em>is</em> outranks what the author <em>said</em>, which outranks what the syntax
 * <em>suggests</em>. That is also the order a REPL user expects: having just run the file, the thing on
 * screen should describe the objects that exist, not the guesses that were made before they did.</p>
 *
 * <h3>Why this lives beside the tree rather than above the bridge</h3>
 *
 * <p>The sketch put the tiers host-side over a {@code JsAstView}. Three of the four need the tree —
 * inference reads initializers, JSDoc reads the comment above a declaration, the declaration tier reads
 * the scopes — so that shape means a bridge crossing per node walked, on every hover and every
 * keystroke of a completion. The Java engine answers {@code resolveAt} on its own side and sends one
 * {@link SymbolInfo} across, which is what the bridge is <em>for</em>: the answer crosses, never the
 * tree.</p>
 */
public final class RhinoResolution {

    /** What is written into the owner band when an answer's provenance is worth stating. */
    private static final String FROM_LAST_RUN = "from last run";
    private static final String FROM_JSDOC = "from JSDoc";
    private static final String FROM_HOST = "host binding";

    private final AstRoot root;
    private final RhinoScopes scopes;
    private final String source;
    private final LineIndex lines;
    private final LiveScopeSnapshot live;
    @Nullable private final InteropResolver interop;
    private final String sourceName;
    private final List<String> keywords;

    /** What the host put in scope, by name and declared Java type. @see JsSourceAnalyzer#useHostBindings */
    private final Map<String, String> hostBindings;

    /** Simple names an {@code import} statement bound — a subset of {@link #hostBindings}'s keys. */
    private final Set<String> imported;

    public RhinoResolution(@Nullable AstRoot root, RhinoScopes scopes, String source, LineIndex lines,
                    LiveScopeSnapshot live, @Nullable InteropResolver interop, String sourceName,
                    List<String> keywords, Map<String, String> hostBindings) {
        this(root, scopes, source, lines, live, interop, sourceName, keywords, hostBindings, Set.of());
    }

    /**
     * @param imported the subset of {@code hostBindings} that an {@code import} statement bound, which
     *                 are CLASS objects rather than instances the host handed over. An overload rather
     *                 than a tenth parameter on the only constructor, so a caller with no imports — every
     *                 test, and any host that does not scan for them — is untouched.
     */
    public RhinoResolution(@Nullable AstRoot root, RhinoScopes scopes, String source, LineIndex lines,
                    LiveScopeSnapshot live, @Nullable InteropResolver interop, String sourceName,
                    List<String> keywords, Map<String, String> hostBindings, Set<String> imported) {
        this.imported = imported == null ? Set.of() : imported;
        this.keywords = keywords == null ? List.of() : keywords;
        this.hostBindings = hostBindings == null ? Map.of() : hostBindings;
        this.root = root;
        this.scopes = scopes;
        this.source = source;
        this.lines = lines;
        this.live = live == null ? LiveScopeSnapshot.EMPTY : live;
        this.interop = interop;
        this.sourceName = sourceName;
    }

    // ── resolveAt ───────────────────────────────────────────────────────────────────────────────

    public @Nullable
    SymbolInfo resolveAt(int offset) {
        Name name = nameAt(offset);
        if (name != null) {
            PropertyGet member = memberAccessOf(name);
            return member != null ? resolveMember(member, name) : resolveName(name, offset);
        }
        return resolveExpression(offset);
    }

    /**
     * What the expression at {@code offset} evaluates to, when the offset is not on a name.
     *
     * <p>The case that matters is a <b>call</b>: {@code Files.emptyList().} puts a {@code )} immediately
     * before the dot, so completion resolves at a character no identifier covers and gets nothing — an
     * empty popup on a chain, which is one of the two most common shapes in Java interop code. Hovering
     * the {@code )} answering with the call's type is right for the same reason.</p>
     *
     * <p>Named for the expression rather than for a symbol, because there is no declaration to point at:
     * what {@code emptyList()} <em>is</em> is a value of its return type, and that is all this can say.</p>
     */
    @Nullable
    private SymbolInfo resolveExpression(int offset) {
        // ANY EXPRESSION, not only a call. A dot after `'text'`, `[1, 2]`, `(x)` or a parenthesised chain
        // is the same question with the same answer available, and answering only for calls meant those
        // fell through to the live-names sample -- a popup listing the run's globals as members of a
        // string literal.
        // STRICTLY CONTAINING, which for an expression is a different question from the one `nameAt` asks.
        // The offset here is the character before the dot — the `)` of `append('b')` — and an inclusive
        // test also matches every node that merely ENDS there, so the innermost match was the string
        // argument `'b'` and the chain resolved to `string`. This is JDT's zero-length `NodeFinder` trap
        // in Rhino's spelling: ask about the character itself, not the boundary beside it.
        AstNode expression = nodeAt(offset, AstNode.class, true);
        if (expression == null) return null;
        TypeRef type = typeOf(expression, offset);
        if (type == null) return null;
        // NO NAME, because a call has none — it is a value, not a declaration. The type is the whole
        // answer, and it is what a member lookup and a hover each need.
        return new SymbolInfo("", SymbolKind.UNKNOWN, type, null, null, Set.of(), null);
    }

    /** {@code receiver.name} — the {@code PropertyGet} this name is the property of, or null. */
    @Nullable
    private static PropertyGet memberAccessOf(Name name) {
        AstNode parent = name.getParent();
        if (!(parent instanceof PropertyGet)) return null;
        return ((PropertyGet) parent).getProperty() == name ? (PropertyGet) parent : null;
    }

    /**
     * The capture a member access deserves, or null to leave it to the grammar — M10's deferred row.
     *
     * <h3>Why this was deferred, and what changed</h3>
     *
     * <p>§12a recorded it as "not done, deliberately": marking a resolved Java member needs a per-node
     * interop lookup during the token walk, priced as "a bridge crossing per member access, on every
     * keystroke". <b>The price was over-estimated.</b> Semantic tokens are built lazily, once per
     * analysis rather than per keystroke, and {@code InteropResolver} caches a class's member list — so
     * a file mentioning one Java type asks once and reads the answer for every access in it.</p>
     *
     * <p>What it buys is the thing a grammar cannot see. {@code CgTextRenderer.TEXT_MATERIAL} is a
     * {@code static final} field, which every scheme draws differently from an ordinary property —
     * italic, and in the constant colour. The Java engine has always drawn it that way
     * ({@code EcjSourceAnalyzer}: static and final together make a {@code CONSTANT}), so the same member
     * read from a {@code .js} file rendered as a plain property beside a {@code .java} file rendering it
     * as a constant. <b>Two editors, one member, two answers</b> — which is the failure this whole
     * interop tier exists to close.</p>
     *
     * <p>Null rather than a default, so a member the resolver cannot type keeps whatever the grammar
     * guessed. A worse answer than the grammar's is the one outcome not worth having.</p>
     */
    public String memberCaptureAt(PropertyGet access) {
        Name property = access == null ? null : access.getProperty();
        if (property == null) return null;
        // ONLY WHERE THE RECEIVER IS A JAVA TYPE, asked before anything is resolved.
        //
        // Two shapes fall out of this and both were wrong without it. A property on a plain object
        // literal is already coloured correctly by the grammar, and re-stating it was a second opinion
        // for no gain -- testing the member's container instead let it through, because an object's
        // inferred type is a container too. And the last segment of a package chain
        // (`java.util.ArrayList`) is a TYPE that `markJavaChains` has already marked, so resolving it
        // here put a second token on the same range under a different name -- the exact defect that
        // pass's own comment records being added to prevent.
        TypeRef receiver = typeOf(access.getTarget(), access.getAbsolutePosition());
        if (receiver == null || JsTypeRef.javaNameOf(receiver) == null) return null;

        SymbolInfo member = resolveMember(access, property);
        if (member == null || member.kind() == null) return null;
        return member.kind().captureName();
    }

    /** A property read: ask the receiver's type what it has by that name. */
    @Nullable
    private SymbolInfo resolveMember(PropertyGet access, Name property) {
        String identifier = property.getIdentifier();
        if (identifier == null || identifier.isEmpty()) return null;

        // THE TYPE AT THE END OF A PACKAGE CHAIN IS THE TYPE, not a property of a package. Hovering the
        // `ArrayList` of `new java.util.ArrayList()` asked what `java.util` has by that name, and a
        // package has no type, so the receiver came back null and the answer was a bare `PropertyGet`
        // property: the popup drew the word `ArrayList` with no owner, no signature and no icon, where
        // the same hover in a .java file names the class and quotes its declaration. The chain is already
        // recognised for COLOURING -- markJavaChains draws exactly these segments as module/module/type --
        // so resolution disagreeing with the colours was the engine contradicting itself on one line.
        if (interop != null) {
            String typeName = RhinoInference.javaNameOf(access, scopes::declaresAnywhere);
            if (typeName != null && typeName.endsWith("." + identifier)) {
                SymbolInfo type = interop.describe(typeName, false);
                if (type != null) return type;
            }
        }

        TypeRef receiver = typeOf(access.getTarget(), access.getAbsolutePosition());
        String javaName = receiver == null ? null : JsTypeRef.javaNameOf(receiver);
        if (javaName != null && interop != null) {
            boolean staticSide = receiver instanceof JsTypeRef && ((JsTypeRef) receiver).isStaticSide();
            for (SymbolInfo candidate : interop.membersOf(javaName, staticSide)) {
                // THE JAVA ENGINE'S OWN ANSWER, handed back unchanged. Rewriting it here would be a
                // second opinion about a Java member, which is exactly what asking the Java engine was
                // meant to avoid -- generic substitution and deprecation both travel with it.
                if (!identifier.equals(candidate.name())) continue;
                // AND ITS SIGNATURE, WHICH `membersOf` DOES NOT CARRY, asked of the Java engine for this
                // one member -- so a hover over `list.add` quotes `src.zip` exactly as it does in a .java
                // file. Null when there is no source beside the class, and then the signature is assembled
                // from what the member already reported. @see InteropResolver#describeMember
                SymbolInfo described = interop.describeMember(javaName, candidate, staticSide);
                Signature quoted = described == null ? null : described.signature();
                // WHAT KIND OF TYPE OWNS IT, asked of the Java engine rather than guessed. The popup's
                // own inference has only the member's kind to reason from and cannot tell a class from an
                // interface, so every Java member hovered from JavaScript drew a class glyph -- while the
                // same member hovered in a .java file drew the interface one, from the same widget, in
                // the same session. The engine already knows; nothing was asking it.
                SymbolInfo owned = candidate.withContainerKind(javaTypeKind(javaName));
                if (quoted == null) {
                    return owned.withSignature(JsSignatures.of(owned, List.of()));
                }
                // THE SIGNATURE AND THE DECLARATION SITE ONLY -- never the whole description. The probe
                // resolves against the GENERIC declaration, so it reports the container as
                // `java.util.ArrayList<E>` where `membersOf` says `java.util.ArrayList`; returning it
                // wholesale made one member describe itself two different ways depending on whether a
                // hover or a completion had asked. The member's identity stays the list's.
                // AND THE OWNER AS THE PROBE RESOLVED IT -- `java.util.ArrayList<E>`, not the raw
                // `java.util.ArrayList` the member list carries. The probe resolves against the GENERIC
                // declaration, which is the same thing the Java engine's own hover reports, so taking it
                // here is what makes the two editors agree. It deliberately does NOT travel back into
                // `membersOf`: a completion row names the receiver the user typed, and only the hover is
                // describing the declaration.
                String owner = described.container();
                SymbolInfo signed = (owner == null ? owned : owned.withContainer(owner))
                        .withSignature(quoted);
                return described.declaration() == null ? signed
                        : signed.withDeclaration(described.declaration());
            }
        }
        // A PROPERTY WE CANNOT TYPE IS STILL A PROPERTY. Answering null would make a hover over an
        // ordinary object member report nothing at all, which reads as the engine being absent.
        return new SymbolInfo(identifier, SymbolKind.PROPERTY, null,
                receiver == null ? null : receiver.displayName(), null, Set.of(), null);
    }

    /**
     * What a declaration's initializer makes it — the <b>syntactic</b> answer, then the resolved one.
     *
     * <p>The syntactic tier reads shapes it can settle alone: {@code new java.util.ArrayList()},
     * {@code Java.type("a.b.C")}, a bare package chain. It cannot read a <em>member</em>, so
     * {@code var text = CgTextRenderer.TEXT_MATERIAL} typed to nothing and the hover said {@code var
     * text} with no type at all — beside {@code var list: java.util.ArrayList} two lines up, which is
     * what made it look arbitrary rather than absent.</p>
     *
     * <p>{@link #typeOf} has known how to answer this all along: a {@code PropertyGet}'s type is the
     * type of the member it reads. It was simply never asked here — the declaration path stopped at the
     * syntactic tier, which is the cheap one and was never meant to be the only one.</p>
     *
     * <h3>Re-entrancy, because a declaration can name itself</h3>
     *
     * <p>{@code var a = a.b;} is legal to write and types {@code a} from an expression that types from
     * {@code a}. The syntactic tier could not recurse because it never resolved a name; this one does,
     * so the cycle has to be cut. A declaration already being typed answers null rather than descending
     * — which is the honest answer for a definition that depends on itself.</p>
     */
    @Nullable
    private TypeRef initializerType(RhinoScopes.Declaration declared) {
        TypeRef syntactic = inferredType(declared.initializer);
        if (syntactic != null || declared.initializer == null) return syntactic;
        if (!typingDeclarations.add(declared.offset)) return null;
        try {
            return typeOf(declared.initializer, declared.offset);
        } finally {
            typingDeclarations.remove(declared.offset);
        }
    }

    /** Declaration offsets currently being typed. @see #initializerType */
    private final java.util.Set<Integer> typingDeclarations = new java.util.HashSet<>();

    /**
     * The type of any expression — what a receiver's members are looked up on.
     *
     * <p>A bare {@link Name} is not a syntactic question, so it goes back through the tiers: what
     * {@code list} is depends on what it was declared equal to, what JSDoc said, and what the last run
     * left it as. Everything else the syntax can answer on its own.</p>
     */
    @Nullable
    private TypeRef typeOf(@Nullable AstNode expression, int offset) {
        if (expression instanceof Name) {
            SymbolInfo resolved = resolveName((Name) expression, offset);
            return resolved == null ? null : resolved.type();
        }
        // THE SYNTACTIC ANSWER FIRST, because `Java.type("a.b.C")` and a bare `java.util.List` are both
        // shapes inference reads directly and neither needs a member lookup.
        TypeRef syntactic = inferredType(expression);
        if (syntactic != null) return syntactic;

        if (expression instanceof PropertyGet) {
            // `a.b` as a receiver is the member b, and its type is what b holds.
            PropertyGet get = (PropertyGet) expression;
            SymbolInfo member = get.getProperty() == null ? null : resolveMember(get, get.getProperty());
            return member == null ? null : member.type();
        }
        if (expression instanceof FunctionCall) {
            // A CALL'S TYPE IS ITS CALLEE'S. A method's `type` is its RETURN type -- that is what a
            // SymbolInfo means for anything invocable, in both engines -- so resolving the thing being
            // called and taking its type is the whole of it, and it composes to any depth of chain.
            return typeOf(((FunctionCall) expression).getTarget(), offset);
        }
        return null;
    }

    /** A plain name: the tiers, in order. */
    @Nullable
    private SymbolInfo resolveName(Name name, int offset) {
        String identifier = name.getIdentifier();
        if (identifier == null || identifier.isEmpty()) return null;

        // THE PARSER'S OWN ANSWER FIRST. It resolved this exact node while parsing, so it already knows
        // which of two same-named declarations this use refers to -- where the offset search below can
        // only pick the deepest scope covering the caret, and picked the wrong one for anything shadowed
        // in a sibling block. Asked only when there is a node; the search stays for the callers that have
        // an offset and nothing else.
        RhinoScopes.Declaration declared = scopes.declarationOf(name);
        if (declared == null) declared = scopes.visibleDeclaration(identifier, offset);
        if (declared != null) return fromDeclaration(declared, identifier);

        // NOT DECLARED HERE. A run may have made it a global, the host may have bound it, it may be a
        // Java package root, or it may genuinely be nothing -- and those are four different things to say.
        SymbolInfo fromRun = fromLiveScope(identifier);
        if (fromRun != null) return fromRun;

        SymbolInfo bound = fromHostBinding(identifier);
        if (bound != null) return bound;

        String javaName = RhinoInference.javaNameOf(name.getParent(), scopes::declaresAnywhere);
        if (javaName != null && interop != null && interop.exists(javaName)) {
            return interop.describe(javaName, true);
        }
        return null;
    }

    /**
     * A declared name, typed by whichever tier can.
     *
     * <h3>The live tier contributes a TYPE; it never replaces the declaration</h3>
     *
     * <p>It used to: a top-level name found in the live scope was rebuilt from the live entry alone, so
     * after any run a documented {@code function join(name, count)} hovered with <b>no description, no
     * parameter types and type {@code function}</b> — and because a call's type is its callee's,
     * {@code join('a', 1).} stopped completing entirely. The tier order (§5.1) is about which tier knows
     * the <em>type</em>; the description, the parameter list and the kind are the file's, and no run
     * improves on them.</p>
     *
     * <p>So the file is read first and the run is asked only for a type — and a <b>declared function</b>
     * is never typed by it, because a live function's type is always the string {@code function} while
     * {@code SymbolInfo.type()} for anything invocable means its <em>return</em> type. A variable that
     * became a Java object still types from the run, which is the case the tier was added for.</p>
     */
    private SymbolInfo fromDeclaration(RhinoScopes.Declaration declared, String identifier) {
        DeclarationSite site = DeclarationSite.here(lines.pointAt(declared.offset),
                lines.pointAt(declared.offset + declared.length));
        String container = containerOf(declared);
        RhinoJsDoc doc = docFor(declared);

        // WHAT THE FILE SAYS. A FUNCTION DECLARATION'S TYPE IS WHAT IT RETURNS, never "function" -- that
        // is what a `type` means for anything invocable, in both engines -- so it is unknown unless JSDoc
        // said. A VARIABLE holding a function is the other case and keeps `function`, because there the
        // value really is one.
        String declaredType = doc.declaredType();
        TypeRef stated = declaredType != null ? typeNamed(declaredType)
                : declared.kind == SymbolKind.FUNCTION ? null
                : initializerType(declared);

        TypeRef live = liveTypeFor(declared, identifier);
        TypeRef type = live != null ? live : stated;
        String tier = live != null ? FROM_LAST_RUN : declaredType != null ? FROM_JSDOC : null;

        SymbolInfo symbol = new SymbolInfo(identifier, declared.kind, type,
                tier == null ? container : suffixed(container, tier),
                emptyToNull(doc.description()), modifiersOf(declared, doc.isDeprecated()), site,
                parametersOf(declared, doc));
        return signed(symbol.withContainerKind(containerKindOf(declared)), declared);
    }

    /** What the last run made of a declared name, when that is more than the file could say. */
    @Nullable
    private TypeRef liveTypeFor(RhinoScopes.Declaration declared, String identifier) {
        // ONLY A TOP-LEVEL DECLARATION IS A GLOBAL; a local of the same name is a different binding, and
        // typing it from the run would describe somebody else's value.
        if (declared.owner != null) return null;
        SymbolInfo global = fromLiveScope(identifier);
        TypeRef type = global == null ? null : global.type();
        if (type == null) return null;
        // @see the class note on this method -- a declared function is never typed by the run.
        if (declared.kind == SymbolKind.FUNCTION && !JsTypeRef.isJava(type)) return null;
        return type;
    }

    /**
     * The doc comment for a declaration, read once.
     *
     * <p>{@code symbolsInScope} builds a symbol for every visible declaration on every keystroke, and
     * finding a doc comment is a scan of the file's whole comment list — so the popup's own filter was
     * paying for a scan per declaration per character typed, for descriptions it then threw away.</p>
     */
    private RhinoJsDoc docFor(RhinoScopes.Declaration declared) {
        RhinoJsDoc cached = docs.get(declared);
        if (cached != null) return cached;
        RhinoJsDoc read = RhinoJsDoc.forDeclaration(root, declared.declaringNode, declared.offset, source);
        docs.put(declared, read);
        return read;
    }

    /** Identity-keyed: a {@code Declaration} is one object per parse and has no value equality. */
    private final Map<RhinoScopes.Declaration, RhinoJsDoc> docs = new IdentityHashMap<>();

    /**
     * The symbol with its declaration rendered onto it.
     *
     * <p>Here rather than at each of the three returns above, because a symbol without a signature draws an
     * empty box in the popup and forgetting one is invisible until somebody hovers exactly that shape.</p>
     */
    private static SymbolInfo signed(SymbolInfo symbol, RhinoScopes.Declaration declared) {
        // WITH THE KEYWORD THAT WAS ACTUALLY WRITTEN. `let` and `var` are one SymbolKind because nothing
        // above the engine needs to tell them apart, so the distinction has to travel beside the symbol
        // rather than inside it -- and without it every `let` in the file rendered as `var`, which is a
        // claim about scoping rather than a cosmetic slip.
        return symbol.withSignature(JsSignatures.of(symbol, parameterNamesOf(declared),
                declared.isLet ? "let" : null));
    }

    /**
     * A function's parameter names, in order — which JavaScript always has and Java's compiled path never
     * does.
     *
     * <p>Read from the declaration rather than carried on {@code SymbolInfo}: core's seam holds parameter
     * TYPES and deliberately not names, because JDT reports {@code arg0} for a classpath member and a field
     * populated with a placeholder by the engine that has most members is worse than no field.</p>
     */
    private static List<String> parameterNamesOf(RhinoScopes.Declaration declared) {
        if (!(declared.initializer instanceof FunctionNode)) return List.of();
        List<AstNode> parameters = ((FunctionNode) declared.initializer).getParams();
        if (parameters == null || parameters.isEmpty()) return List.of();
        List<String> names = new ArrayList<>(parameters.size());
        for (AstNode parameter : parameters) {
            names.add(parameter instanceof Name ? ((Name) parameter).getIdentifier() : "");
        }
        return names;
    }

    /**
     * A name the host put in scope — typed by its declared Java class, before anything has run.
     *
     * <p>The one tier that does not need the file or a run: the host says {@code world : net.minecraft…},
     * so {@code world.} can list the Java engine's own members for that class from the first keystroke.
     * Without it a binding was a free name — unresolved colouring, a "did you mean", an offer to declare
     * it as a local, and nothing at all behind the dot.</p>
     */
    @Nullable
    private SymbolInfo fromHostBinding(String identifier) {
        String typeName = hostBindings.get(identifier);
        if (typeName == null || typeName.isEmpty()) return null;
        // AN IMPORTED NAME IS THE CLASS ITSELF, and is described by the engine that knows it. `import
        // a.b.C` binds the CLASS OBJECT -- its members are the statics, exactly as `Java.type('a.b.C')`
        // binds -- where an ordinary host binding is an INSTANCE the host handed over. Reported through
        // `interop.describe` so an imported name hovers identically to the fully qualified spelling it
        // replaced: same kind, same quoted declaration, same owner. Anything else would make the
        // shorthand read as a different thing from the name it stands for.
        if (imported.contains(identifier) && interop != null) {
            SymbolInfo described = interop.describe(typeName, true);
            if (described != null) return described;
        }
        TypeRef type = typeName.indexOf('.') > 0 ? JsTypeRef.javaInstance(typeName)
                : JsTypeRef.js(typeName);
        // MODULE, so the owner band draws a module glyph rather than a class one. The band's own
        // inference reasons from the SYMBOL's kind -- a member lives in a type -- which is right for Java
        // and wrong for every owner JavaScript has: a host, a file, or the last run's global scope.
        SymbolInfo binding = new SymbolInfo(identifier, SymbolKind.PROPERTY, type, FROM_HOST, null,
                Set.of(), null);
        return binding.withContainerKind(SymbolKind.MODULE)
                .withSignature(JsSignatures.of(binding, List.of()));
    }

    /** Whether the host bound this name — what stops a fix catalog treating it as a mistake. */
    public boolean isHostBinding(@Nullable String identifier) {
        return identifier != null && hostBindings.containsKey(identifier);
    }

    /** A global the last run left behind. */
    @Nullable
    private SymbolInfo fromLiveScope(String identifier) {
        LiveScopeSnapshot.Entry entry = live.get(identifier);
        if (entry == null) return null;
        List<TypeRef> parameters = List.of();
        if (entry.arity() > 0) {
            List<TypeRef> unknown = new ArrayList<>(entry.arity());
            for (int i = 0; i < entry.arity(); i++) unknown.add(TypeRef.of("?"));
            parameters = unknown;
        }
        SymbolInfo global = new SymbolInfo(identifier, kindOf(entry), typeOfLive(entry),
                FROM_LAST_RUN, null, Set.of(), null, parameters);
        // MODULE for the same reason as the host binding above: the global scope a run left behind is a
        // place, not a class.
        return global.withContainerKind(SymbolKind.MODULE)
                .withSignature(JsSignatures.of(global, List.of()));
    }

    private static SymbolKind kindOf(LiveScopeSnapshot.Entry entry) {
        switch (entry.kind()) {
            case FUNCTION:
                return SymbolKind.FUNCTION;
            case JAVA_CLASS:
                return SymbolKind.CLASS;
            default:
                // A GLOBAL IS A PROPERTY OF THE GLOBAL OBJECT -- that is not an analogy, it is how the
                // language defines one, and it is the kind that makes a completion list draw it the same
                // way it draws every other member of an object.
                return SymbolKind.PROPERTY;
        }
    }

    @Nullable
    private static TypeRef typeOfLive(LiveScopeSnapshot.Entry entry) {
        if (entry.isJava()) {
            return entry.kind() == LiveScopeSnapshot.Kind.JAVA_CLASS
                    ? JsTypeRef.javaClass(entry.javaClassName())
                    : JsTypeRef.javaInstance(entry.javaClassName());
        }
        switch (entry.kind()) {
            case FUNCTION: return JsTypeRef.js(JsTypeRef.FUNCTION);
            case ARRAY: return JsTypeRef.js(JsTypeRef.ARRAY);
            // WITH THE PROPERTIES THE RUN SAW ON IT, so `membersOf` answers a live object exactly as it
            // answers an object literal -- one path rather than a second one in the completion provider.
            case OBJECT: return JsTypeRef.object(entry.ownIds());
            case STRING: return JsTypeRef.js(JsTypeRef.STRING);
            case NUMBER: return JsTypeRef.js(JsTypeRef.NUMBER);
            case BOOLEAN: return JsTypeRef.js(JsTypeRef.BOOLEAN);
            case REGEXP: return JsTypeRef.js(JsTypeRef.REGEXP);
            case UNDEFINED: return JsTypeRef.js(JsTypeRef.UNDEFINED);
            case NULL: return JsTypeRef.js(JsTypeRef.NULL);
            default: return null;
        }
    }

    /** The names the last run left behind — what a "did you mean" must not offer to rename. */
    public Set<String> liveNames() {
        return live.names();
    }

    /** What this band's parser accepts, measured. @see JsKeywords */
    public List<String> supportedKeywords() {
        return keywords;
    }

    // ── membersOf ───────────────────────────────────────────────────────────────────────────────

    public List<SymbolInfo> membersOf(@Nullable TypeRef type, int contextOffset) {
        String javaName = type == null ? null : JsTypeRef.javaNameOf(type);
        if (javaName != null && interop != null) {
            boolean staticSide = type instanceof JsTypeRef && ((JsTypeRef) type).isStaticSide();
            return interop.membersOf(javaName, staticSide);
        }
        List<SymbolInfo> members = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // AN OBJECT'S OWN PROPERTIES: an object literal's, written down in the file, or a live object's,
        // seen by the last run. Without this, `var o = { a: 1 }; o.` had nothing to offer until the file
        // had been run once -- and it is the commonest object in a script.
        if (type instanceof JsTypeRef) {
            for (String key : ((JsTypeRef) type).keys()) {
                if (seen.add(key)) {
                    members.add(new SymbolInfo(key, SymbolKind.PROPERTY, null, type.displayName(), null,
                            Set.of(), null));
                }
            }
        }

        // AND WHAT ITS PROTOTYPE GIVES IT, read from the engine. `'abc'.`, `[1, 2].` and any typed
        // JavaScript receiver resolved perfectly well and then offered NOTHING, because this only ever
        // knew how to ask the Java engine -- and an empty answer is worse than none, since it sent the
        // completion provider to its "I cannot type this" fallback, which offers the run's globals as
        // though they were members of a string.
        String prototype = type == null ? null : type.qualifiedName();
        for (String id : RhinoGlobals.membersOfPrototype(prototype == null ? "" : prototype)) {
            if (!seen.add(id)) continue;
            members.add(new SymbolInfo(id, SymbolKind.PROPERTY, null, prototype + ".prototype", null,
                    Set.of(), null));
        }
        return members;
    }

    // ── symbolsInScope ──────────────────────────────────────────────────────────────────────────

    /**
     * What is nameable at {@code offset}, nearest first.
     *
     * <p>Declarations, then whatever the last run left in the global scope, then the Java package roots.
     * A name that is both declared and live appears once, as its declaration — the file is what the
     * author is editing, and reporting a duplicate would put the same identifier in a completion list
     * twice.</p>
     */
    public List<SymbolInfo> symbolsInScope(int offset) {
        List<SymbolInfo> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (RhinoScopes.Declaration declared : scopes.visibleAt(offset)) {
            if (!seen.add(declared.name)) continue;
            out.add(fromDeclaration(declared, declared.name));
        }
        for (String name : live.names()) {
            if (!seen.add(name)) continue;
            SymbolInfo global = fromLiveScope(name);
            if (global != null) out.add(global);
        }
        // AND WHAT THE HOST BOUND, which is in scope before anything is declared or run and is the whole
        // reason a scripting host is worth having.
        for (String name : hostBindings.keySet()) {
            if (!seen.add(name)) continue;
            SymbolInfo bound = fromHostBinding(name);
            if (bound != null) out.add(bound);
        }
        return out;
    }

    // ── expectedTypeAt ──────────────────────────────────────────────────────────────────────────

    /**
     * What type belongs at {@code offset}, when a JSDoc {@code @param} on the callee says so.
     *
     * <p>The only case JavaScript can answer without executing: the author wrote the parameter's type
     * down. A Java callee's parameter type is knowable too and arrives with the completion work, where
     * it has a consumer; answering null until then is the honest state rather than a guess.</p>
     */
    public @Nullable
    TypeRef expectedTypeAt(int offset) {
        FunctionCall call = enclosingCall(offset);
        if (call == null) return null;
        int index = argumentIndexAt(call, offset);
        if (index < 0) return null;
        AstNode target = call.getTarget();

        // A JAVA CALLEE KNOWS ITS PARAMETER TYPES, and it is the case that pays: `list.add(|)` in a
        // script reaching Java is where an expected type is actually useful, and the tier that could
        // answer it was left unbuilt with a note saying it would land "with 10.7". It did not.
        TypeRef fromJava = javaParameterTypeAt(target, index);
        if (fromJava != null) return fromJava;

        if (!(target instanceof Name)) return null;
        RhinoScopes.Declaration callee =
                scopes.visibleDeclaration(((Name) target).getIdentifier(), target.getAbsolutePosition());
        if (callee == null || !(callee.initializer instanceof FunctionNode)) return null;
        List<AstNode> parameters = ((FunctionNode) callee.initializer).getParams();
        if (parameters == null || index >= parameters.size()) return null;
        AstNode parameter = parameters.get(index);
        if (!(parameter instanceof Name)) return null;
        String declaredType = docFor(callee).paramType(((Name) parameter).getIdentifier());
        return declaredType == null ? null : typeNamed(declaredType);
    }

    /**
     * The declared type of a Java method's {@code index}-th parameter, when the callee is one.
     *
     * <p>Through the same member list the completion popup and the hover read, so the answer cannot
     * disagree with what either of them showed. An overload set is resolved the only way it can be
     * without argument types: the first member of that name with enough parameters — which is exactly
     * right for the overwhelmingly common case of one overload, and a defensible guess otherwise.</p>
     */
    @Nullable
    private TypeRef javaParameterTypeAt(@Nullable AstNode target, int index) {
        if (!(target instanceof PropertyGet) || interop == null) return null;
        PropertyGet access = (PropertyGet) target;
        Name method = access.getProperty();
        if (method == null || method.getIdentifier() == null) return null;

        TypeRef receiver = typeOf(access.getTarget(), access.getAbsolutePosition());
        String javaName = receiver == null ? null : JsTypeRef.javaNameOf(receiver);
        if (javaName == null) return null;
        boolean staticSide = receiver instanceof JsTypeRef && ((JsTypeRef) receiver).isStaticSide();
        for (SymbolInfo member : interop.membersOf(javaName, staticSide)) {
            if (!method.getIdentifier().equals(member.name())) continue;
            if (index < member.parameters().size()) return member.parameters().get(index);
        }
        return null;
    }

    @Nullable
    private FunctionCall enclosingCall(int offset) {
        AstNode innermost = nodeAt(offset, FunctionCall.class);
        return innermost instanceof FunctionCall ? (FunctionCall) innermost : null;
    }

    private static int argumentIndexAt(FunctionCall call, int offset) {
        List<AstNode> arguments = call.getArguments();
        if (arguments == null || arguments.isEmpty()) return -1;
        for (int i = 0; i < arguments.size(); i++) {
            AstNode argument = arguments.get(i);
            int start = argument.getAbsolutePosition();
            if (offset >= start && offset <= start + argument.getLength()) return i;
        }
        return -1;
    }

    // ── Reading the tree ────────────────────────────────────────────────────────────────────────

    /** The innermost {@link Name} covering {@code offset}, or null. */
    @Nullable
    Name nameAt(int offset) {
        AstNode found = nodeAt(offset, Name.class);
        return found instanceof Name ? (Name) found : null;
    }

    /**
     * The innermost node of {@code kind} covering {@code offset}.
     *
     * <p>Innermost by construction: the walk keeps the last match, and a visitor descends before it
     * moves on — so a {@code Name} inside a {@code PropertyGet} inside a {@code FunctionCall} answers
     * the {@code Name} when one is asked for. An offset at a node's very end counts as inside it, which
     * is what makes a hover over the last character of an identifier resolve.</p>
     */
    @Nullable
    AstNode nodeAt(int offset, Class<? extends AstNode> kind) {
        return nodeAt(offset, kind, false);
    }

    /**
     * @param strict whether the offset must be a character <em>of</em> the node rather than its end
     *               boundary. False is what a hover wants — a caret just past the last character of an
     *               identifier still means that identifier. True is what a receiver lookup wants, since
     *               there the offset is deliberately the last character of the expression.
     */
    @Nullable
    private AstNode nodeAt(int offset, Class<? extends AstNode> kind, boolean strict) {
        if (root == null) return null;
        AstNode[] best = new AstNode[1];
        root.visit(node -> {
            int start = node.getAbsolutePosition();
            int end = start + node.getLength();
            if (offset < start || (strict ? offset >= end : offset > end)) {
                // NOT A REASON TO STOP DESCENDING. A parent's reported extent does not always cover its
                // children in a recovered tree, and pruning on it loses the node under the caret in
                // exactly the broken files this parser exists to answer for.
                return true;
            }
            if (kind.isInstance(node)) best[0] = node;
            return true;
        });
        return best[0];
    }

    /**
     * The innermost node covering {@code offset} — <b>one definition</b>, shared with the fix catalog.
     *
     * <p>{@code JsQuickFixes} had a second copy of this walk and called it five times per Alt+Enter, so a
     * single gesture walked the whole tree six times over. It holds this object already; there is no
     * reason for it to hold a visitor too.</p>
     */
    public @Nullable
    AstNode nodeCovering(int offset) {
        return nodeAt(offset, AstNode.class);
    }

    // ── Small shared pieces ─────────────────────────────────────────────────────────────────────

    /**
     * A JSDoc type name as a {@link TypeRef}.
     *
     * <p>A dotted name is taken to be a Java class — {@code {java.util.List}} is the documented way to
     * say so and the only qualified thing a script can name. Anything else is a JavaScript type name and
     * is carried as text, which is all JSDoc ever promised.</p>
     */
    private TypeRef typeNamed(String declared) {
        String name = declared.trim();
        if (name.isEmpty()) return null;
        if (name.indexOf('.') > 0 && interop != null && interop.exists(name)) {
            return JsTypeRef.javaInstance(name);
        }
        return JsTypeRef.js(name);
    }

    /** The enclosing function's name, or the file's — what the popup's owner band shows. */
    @Nullable
    private String containerOf(RhinoScopes.Declaration declared) {
        if (declared.owner == null) return sourceName;
        Name name = declared.owner.getFunctionName();
        return name == null || name.getIdentifier() == null ? sourceName : name.getIdentifier();
    }

    /**
     * What KIND of thing that owner is — stated, never inferred.
     *
     * <p>The popup infers an owner's kind from the SYMBOL's kind on Java's rule: a member is declared in
     * a type, a type in a package. That is right for Java and wrong for every owner JavaScript has. A
     * local's owner is a <b>function</b> and a top-level declaration's is the <b>file</b>, and both came
     * out drawn with a class glyph — {@code Ⓒ useJava} beside a local, {@code Ⓒ Probe.js} beside a
     * top-level function, each asserting a class that does not exist.</p>
     *
     * <p>The owner itself is kept rather than dropped to match Java, which reports none for a local. A
     * JavaScript local really is owned by its function, and with the right glyph that reads correctly;
     * the defect was the icon claiming a type, not the fact being offered.</p>
     */
    @Nullable
    private SymbolKind containerKindOf(RhinoScopes.Declaration declared) {
        return declared.owner == null ? SymbolKind.MODULE : SymbolKind.FUNCTION;
    }

    /**
     * {@code summarise — from JSDoc}: the provenance, after the owner it qualifies.
     *
     * <p>The separator is load-bearing rather than decorative — {@code DocumentationPopup} splits on it
     * so the note is drawn as a muted trailing remark instead of being coloured as another segment of the
     * qualified path. Without that the band read {@code applyDiscount — from JSDoc} with `from` and
     * `JSDoc` tinted as though they were package and type names.</p>
     */
    private static String suffixed(@Nullable String container, String tier) {
        return container == null || container.isEmpty() ? tier : container + " — " + tier;
    }

    /**
     * Whether a Java type is a class, an interface or an enum — the owner band's glyph.
     *
     * <p>Asked of the Java engine, which is the only thing that knows: the three are spelled identically
     * at every use site, and {@code membersOf} reports a member without saying what declared it.</p>
     */
    @Nullable
    private SymbolKind javaTypeKind(String binaryName) {
        if (interop == null) return null;
        SymbolInfo type = interop.describe(binaryName, false);
        return type == null ? null : type.kind();
    }

    private static Set<SymbolModifier> modifiersOf(RhinoScopes.Declaration declared, boolean deprecated) {
        Set<SymbolModifier> modifiers = new LinkedHashSet<>();
        // `const` IS `final`, and that is the pair SymbolModifier already documents as making a constant.
        if (declared.kind == SymbolKind.CONSTANT) modifiers.add(SymbolModifier.FINAL);
        if (deprecated) modifiers.add(SymbolModifier.DEPRECATED);
        return modifiers;
    }

    /**
     * A function's parameters, typed by JSDoc where the author said and unknown where they did not.
     *
     * <p>Every parameter is reported even when only some are documented, because the <em>count</em> and
     * the order are facts about the function and a half-length list would misdescribe the call. An
     * undocumented one is {@code ?}, which is what a dynamic language honestly knows.</p>
     */
    private List<TypeRef> parametersOf(RhinoScopes.Declaration declared, RhinoJsDoc doc) {
        if (!(declared.initializer instanceof FunctionNode)) return List.of();
        List<AstNode> declaredParameters = ((FunctionNode) declared.initializer).getParams();
        if (declaredParameters == null || declaredParameters.isEmpty()) return List.of();
        List<TypeRef> types = new ArrayList<>(declaredParameters.size());
        for (AstNode parameter : declaredParameters) {
            String name = parameter instanceof Name ? ((Name) parameter).getIdentifier() : null;
            String declaredType = name == null ? null : doc.paramType(name);
            types.add(declaredType == null ? TypeRef.of("?") : typeNamed(declaredType));
        }
        return types;
    }

    @Nullable
    private static String emptyToNull(@Nullable String text) {
        return text == null || text.isEmpty() ? null : text;
    }
    /**
     * The syntactic tier's answer, <b>minus anything the policy refuses</b>.
     *
     * <p>{@code InteropResolver} gates {@code describe} and {@code membersOf}, so a refused class was
     * absent from the completion list, from the index and from execution. It was <b>not</b> absent from
     * the hover: inference reads {@code Java.type('java.lang.System')} straight off the syntax and never
     * asks anyone, so a variable holding one hovered as {@code s : java.lang.System} under a policy that
     * refuses {@code java.lang} — the editor naming a type whose every use throws.</p>
     *
     * <p>That is the failure the sandbox exists to prevent stated exactly: <em>offered by the editor and
     * refused at run time</em>, which is worse than either restriction alone because the editor is then
     * actively wrong. §21.9 has always claimed hover was covered; nothing asserted it, and this is what
     * writing that assertion found.</p>
     *
     * <p>Filtered on the way <b>out</b> rather than inside {@code RhinoInference}, for the reason the
     * member list already gives: inference is a pure function of the syntax and says the same thing
     * whatever the posture, so the policy belongs at the seam where an answer is handed over.</p>
     */
    @Nullable
    private TypeRef inferredType(@Nullable AstNode expression) {
        TypeRef syntactic = RhinoInference.typeOf(expression, scopes::declaresAnywhere);
        if (syntactic == null) syntactic = fromImportedName(expression);
        TypeRef inferred = qualifyImports(syntactic);
        if (inferred == null || interop == null) return inferred;
        String javaName = JsTypeRef.javaNameOf(inferred);
        return javaName == null || interop.permits(javaName) ? inferred : null;
    }

    /**
     * The type of an expression that names an <b>imported</b> class by its simple name.
     *
     * <p>{@code RhinoInference} answers nothing here, and correctly: it reads Java out of a dotted
     * CHAIN, and {@code ArrayList} on its own is not one — a bare capitalised name is just a name to
     * anything reasoning from syntax alone. What makes it a class is an {@code import} line, which is a
     * fact about this file rather than about the expression, so it is answered here.</p>
     *
     * <p>Without it, writing the import made the file know <em>less</em>: {@code new ArrayList()} typed
     * to nothing, so {@code list.add(…)} fell through to "a property we cannot type is still a
     * property" and the hover showed a bare name where the fully-qualified spelling of the same line
     * quoted {@code public boolean add(E e)} under {@code java.util.ArrayList<E>}.</p>
     *
     * <p>A {@code new} makes an INSTANCE and a bare mention is the CLASS OBJECT — the same distinction
     * {@code JsTypeRef} draws everywhere else, and the reason {@code Collections.EMPTY_LIST} wants the
     * statics while {@code list.add} wants the instance members.</p>
     */
    @Nullable
    private TypeRef fromImportedName(@Nullable AstNode expression) {
        if (expression == null || imported.isEmpty()) return null;
        boolean instance = expression instanceof NewExpression;
        AstNode named = instance ? ((NewExpression) expression).getTarget() : expression;
        if (!(named instanceof Name)) return null;
        String simple = ((Name) named).getIdentifier();
        if (simple == null || !imported.contains(simple)) return null;
        String binary = hostBindings.get(simple);
        if (binary == null || binary.isEmpty()) return null;
        return instance ? JsTypeRef.javaInstance(binary) : JsTypeRef.javaClass(binary);
    }

    /**
     * An imported simple name, expanded to the class it names.
     *
     * <p>The syntactic tier reads {@code new ArrayList()} and can only answer {@code ArrayList} — the
     * package is on an {@code import} line it never saw. Everything downstream is keyed on a BINARY
     * name, so that answer looked like a type and resolved to nothing: no members, so
     * {@code list.add(…)} went unresolved; no owner, so the hover read {@code ArrayList} where the
     * fully-qualified spelling of the same line read {@code java.util.ArrayList<E>} with the method
     * quoted under it. <b>Writing the import made the file know less.</b></p>
     *
     * <p>Expanded here rather than inside {@code RhinoInference} because the imports are a property of
     * this FILE and that class is a pure function of the syntax — the same split the policy filter uses,
     * one seam further in.</p>
     */
    @Nullable
    private TypeRef qualifyImports(@Nullable TypeRef type) {
        if (type == null || !JsTypeRef.isJava(type)) return type;
        String name = type.qualifiedName();
        // ONLY A BARE NAME. Anything already qualified is either a real chain or an expansion that has
        // happened once already, and re-reading it against the imports could only make it wrong.
        if (name == null || name.indexOf('.') >= 0 || !imported.contains(name)) return type;
        String binary = hostBindings.get(name);
        if (binary == null || binary.isEmpty()) return type;
        return type instanceof JsTypeRef && ((JsTypeRef) type).isStaticSide()
                ? JsTypeRef.javaClass(binary) : JsTypeRef.javaInstance(binary);
    }

}
