package com.crystalgui.language.js;

import com.crystalgui.language.engine.bridge.LiveScopeSnapshot;
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
import java.util.LinkedHashSet;
import java.util.List;
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

    private final AstRoot root;
    private final RhinoScopes scopes;
    private final String source;
    private final LineIndex lines;
    private final LiveScopeSnapshot live;
    @Nullable private final InteropResolver interop;
    private final String sourceName;
    private final List<String> keywords;

    RhinoResolution(@Nullable AstRoot root, RhinoScopes scopes, String source, LineIndex lines,
                    LiveScopeSnapshot live, @Nullable InteropResolver interop, String sourceName,
                    List<String> keywords) {
        this.keywords = keywords == null ? List.<String>of() : keywords;
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
        AstNode call = nodeAt(offset, FunctionCall.class);
        if (call == null) return null;
        TypeRef type = typeOf(call, offset);
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
                    return candidate.withSignature(JsSignatures.of(candidate, List.<String>of()));
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

        RhinoScopes.Declaration declared = scopes.visibleDeclaration(identifier, offset);
        if (declared != null) return fromDeclaration(declared, identifier);

        // NOT DECLARED HERE. A run may have made it a global, or it may be a Java package root, or it
        // may genuinely be nothing -- and those are three different things to say.
        SymbolInfo fromRun = fromLiveScope(identifier);
        if (fromRun != null) return fromRun;

        String javaName = RhinoInference.javaNameOf(name.getParent(), scopes::declaresAnywhere);
        if (javaName != null && interop != null && interop.exists(javaName)) {
            return interop.describe(javaName, true);
        }
        return null;
    }

    /**
     * A declared name, typed by whichever tier can.
     *
     * <p>The live scope is asked first even for a declared name, and that is deliberate: a top-level
     * {@code var} <em>is</em> a global once the file has run, so after a run the editor knows what it
     * actually became — which is more than its initializer said, and is the entire reason the tier
     * exists.</p>
     */
    private SymbolInfo fromDeclaration(RhinoScopes.Declaration declared, String identifier) {
        DeclarationSite site = DeclarationSite.here(lines.pointAt(declared.offset),
                lines.pointAt(declared.offset + declared.length));
        String container = containerOf(declared);

        SymbolInfo fromRun = declared.owner == null ? fromLiveScope(identifier) : null;
        if (fromRun != null) {
            return signed(new SymbolInfo(identifier, declared.kind, fromRun.type(),
                    suffixed(container, FROM_LAST_RUN), null, modifiersOf(declared, false), site,
                    fromRun.parameters()), declared);
        }

        RhinoJsDoc doc = RhinoJsDoc.forDeclaration(root, declared.declaringNode, declared.offset, source);
        String declaredType = doc.declaredType();
        if (declaredType != null) {
            return signed(new SymbolInfo(identifier, declared.kind, typeNamed(declaredType),
                    suffixed(container, FROM_JSDOC), emptyToNull(doc.description()),
                    modifiersOf(declared, doc.isDeprecated()), site, parametersOf(declared, doc)),
                    declared);
        }

        // A FUNCTION DECLARATION'S TYPE IS WHAT IT RETURNS, never "function". That is what a `type` means
        // for anything invocable -- Java's METHOD symbols carry their return type -- and it is what makes
        // `add(1).` resolvable at all: a call's type is its callee's. Unknown unless JSDoc said, which is
        // the honest answer for a language with no declared return types. A VARIABLE holding a function is
        // the other case and keeps `function`, because there the value really is one.
        TypeRef inferred = declared.kind == SymbolKind.FUNCTION
                ? null : RhinoInference.typeOf(declared.initializer, scopes::declaresAnywhere);
        return signed(new SymbolInfo(identifier, declared.kind, inferred, container,
                emptyToNull(doc.description()), modifiersOf(declared, doc.isDeprecated()), site,
                parametersOf(declared, doc)), declared);
    }

    /**
     * The symbol with its declaration rendered onto it.
     *
     * <p>Here rather than at each of the three returns above, because a symbol without a signature draws an
     * empty box in the popup and forgetting one is invisible until somebody hovers exactly that shape.</p>
     */
    private static SymbolInfo signed(SymbolInfo symbol, RhinoScopes.Declaration declared) {
        return symbol.withSignature(JsSignatures.of(symbol, parameterNamesOf(declared)));
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
        return global.withSignature(JsSignatures.of(global, List.<String>of()));
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
            case OBJECT: return JsTypeRef.js(JsTypeRef.OBJECT);
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
        if (javaName == null || interop == null) return List.of();
        boolean staticSide = type instanceof JsTypeRef && ((JsTypeRef) type).isStaticSide();
        return interop.membersOf(javaName, staticSide);
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
        if (!(target instanceof Name)) return null;
        RhinoScopes.Declaration callee =
                scopes.visibleDeclaration(((Name) target).getIdentifier(), target.getAbsolutePosition());
        if (callee == null || !(callee.initializer instanceof FunctionNode)) return null;
        List<AstNode> parameters = ((FunctionNode) callee.initializer).getParams();
        if (parameters == null || index >= parameters.size()) return null;
        AstNode parameter = parameters.get(index);
        if (!(parameter instanceof Name)) return null;
        RhinoJsDoc doc = RhinoJsDoc.forDeclaration(root, callee.declaringNode, callee.offset, source);
        String declaredType = doc.paramType(((Name) parameter).getIdentifier());
        return declaredType == null ? null : typeNamed(declaredType);
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
    private AstNode nodeAt(int offset, Class<? extends AstNode> kind) {
        if (root == null) return null;
        AstNode[] best = new AstNode[1];
        root.visit(node -> {
            int start = node.getAbsolutePosition();
            int end = start + node.getLength();
            if (offset < start || offset > end) {
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
