package com.crystalgui.language.js.rhino.resolve;

import com.crystalgui.language.engine.bridge.LiveScopeSnapshot;
import com.crystalgui.language.js.RhinoGlobals;
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
final class RhinoResolution {

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

    RhinoResolution(@Nullable AstRoot root, RhinoScopes scopes, String source, LineIndex lines,
                    LiveScopeSnapshot live, @Nullable InteropResolver interop, String sourceName,
                    List<String> keywords, Map<String, String> hostBindings) {
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

    @Nullable
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

    /** A property read: ask the receiver's type what it has by that name. */
    @Nullable
    private SymbolInfo resolveMember(PropertyGet access, Name property) {
        String identifier = property.getIdentifier();
        if (identifier == null || identifier.isEmpty()) return null;

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
                if (quoted == null) {
                    return candidate.withSignature(JsSignatures.of(candidate, List.of()));
                }
                // THE SIGNATURE AND THE DECLARATION SITE ONLY -- never the whole description. The probe
                // resolves against the GENERIC declaration, so it reports the container as
                // `java.util.ArrayList<E>` where `membersOf` says `java.util.ArrayList`; returning it
                // wholesale made one member describe itself two different ways depending on whether a
                // hover or a completion had asked. The member's identity stays the list's.
                SymbolInfo signed = candidate.withSignature(quoted);
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
        TypeRef syntactic = RhinoInference.typeOf(expression, scopes::declaresAnywhere);
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
                : RhinoInference.typeOf(declared.initializer, scopes::declaresAnywhere);

        TypeRef live = liveTypeFor(declared, identifier);
        TypeRef type = live != null ? live : stated;
        String tier = live != null ? FROM_LAST_RUN : declaredType != null ? FROM_JSDOC : null;

        return signed(new SymbolInfo(identifier, declared.kind, type,
                tier == null ? container : suffixed(container, tier),
                emptyToNull(doc.description()), modifiersOf(declared, doc.isDeprecated()), site,
                parametersOf(declared, doc)), declared);
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
        TypeRef type = typeName.indexOf('.') > 0 ? JsTypeRef.javaInstance(typeName)
                : JsTypeRef.js(typeName);
        SymbolInfo binding = new SymbolInfo(identifier, SymbolKind.PROPERTY, type, FROM_HOST, null,
                Set.of(), null);
        return binding.withSignature(JsSignatures.of(binding, List.of()));
    }

    /** Whether the host bound this name — what stops a fix catalog treating it as a mistake. */
    boolean isHostBinding(@Nullable String identifier) {
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
        return global.withSignature(JsSignatures.of(global, List.of()));
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
    Set<String> liveNames() {
        return live.names();
    }

    /** What this band's parser accepts, measured. @see JsKeywords */
    List<String> supportedKeywords() {
        return keywords;
    }

    // ── membersOf ───────────────────────────────────────────────────────────────────────────────

    List<SymbolInfo> membersOf(@Nullable TypeRef type, int contextOffset) {
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
    List<SymbolInfo> symbolsInScope(int offset) {
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
    @Nullable
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
    @Nullable
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

    /** {@code summarise — from JSDoc}: the provenance, in the band that already names the owner. */
    private static String suffixed(@Nullable String container, String tier) {
        return container == null || container.isEmpty() ? tier : container + " — " + tier;
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
}
