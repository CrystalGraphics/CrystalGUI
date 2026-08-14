package com.crystalgui.text.lang;

import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * What an engine knows about one name — the answer to {@link Resolver#resolveAt}.
 *
 * <h3>The field set is what three consumers read, and nothing else</h3>
 *
 * <p>LSP splits this across {@code Hover}, {@code SymbolInformation}, {@code CompletionItem} and
 * {@code Definition}, because a protocol pays for a round trip per request and so bundles per request.
 * There is no wire here, so the split buys nothing and costs an engine four near-identical builders.
 * One record, and its fields are exactly what the three planned consumers read:</p>
 *
 * <ul>
 *   <li><b>Hover</b> — {@link #name}, {@link #kind}, {@link #type}, {@link #container},
 *       {@link #documentation}, and the strike-through from {@link SymbolModifier#DEPRECATED}</li>
 *   <li><b>Go-to-definition</b> — {@link #declaration}</li>
 *   <li><b>Completion</b> — {@link #name}, {@link #kind} for the icon, {@link #type} for the detail
 *       column, {@link SymbolModifier#STATIC} for ranking</li>
 * </ul>
 *
 * <p>Anything an engine knows that no consumer reads stays inside the engine. That is not minimalism for
 * its own sake: a field on a seam is a field every implementation has to populate, and one that is only
 * populated by whoever added it is worse than absent, because a consumer cannot tell the two apart.</p>
 *
 * @param name          the identifier itself, unqualified
 * @param kind          what it is
 * @param type          its type — the return type for a method, null when the notion does not apply
 * @param container     what declares it ({@code java.util.List}, a package, an enclosing function), or null
 * @param documentation rendered docs, or null. <b>Resolved on demand</b>: see {@link CompletionProvider}
 * @param modifiers     never null; empty when nothing applies
 * @param declaration   where it is declared, or null when the engine cannot say — a member of a compiled
 *                      class with no source attached is the ordinary case, not a failure
 */
public record SymbolInfo(String name, SymbolKind kind, @Nullable TypeRef type,
                         @Nullable String container, @Nullable String documentation,
                         Set<SymbolModifier> modifiers, @Nullable DeclarationSite declaration,
                         List<TypeRef> parameters, @Nullable Signature signature,
                         @Nullable SymbolKind containerKind) {

    public SymbolInfo {
        if (name == null) name = "";
        if (kind == null) kind = SymbolKind.UNKNOWN;
        modifiers = modifiers == null || modifiers.isEmpty() ? Set.of() : Set.copyOf(modifiers);
        parameters = parameters == null || parameters.isEmpty() ? List.of() : List.copyOf(parameters);
    }

    /**
     * The nine-component shape, for every construction site that predates {@link #containerKind}.
     *
     * <p>Same reasoning as the two below: what KIND the container is can only be answered by something
     * holding the owner's binding, and the dozen sites that build a symbol from a name and a kind have
     * nothing to offer. {@link #containerKind()} answering null is that statement, made once.</p>
     */
    public SymbolInfo(String name, SymbolKind kind, @Nullable TypeRef type, @Nullable String container,
                      @Nullable String documentation, Set<SymbolModifier> modifiers,
                      @Nullable DeclarationSite declaration, List<TypeRef> parameters,
                      @Nullable Signature signature) {
        this(name, kind, type, container, documentation, modifiers, declaration, parameters,
                signature, null);
    }

    /**
     * The eight-component shape, for every construction site that predates {@link Signature}.
     *
     * <p>Kept as an overload for the reason the seven-component one is: a signature is something an
     * <em>engine with a binding</em> can produce, and the dozen places that build a {@code SymbolInfo}
     * from a name and a kind — tests, the keyword tier, a completion item — have nothing to offer and
     * should not have to say so. {@link #signature()} answering null is that statement, made once.</p>
     */
    public SymbolInfo(String name, SymbolKind kind, @Nullable TypeRef type, @Nullable String container,
                      @Nullable String documentation, Set<SymbolModifier> modifiers,
                      @Nullable DeclarationSite declaration, List<TypeRef> parameters) {
        this(name, kind, type, container, documentation, modifiers, declaration, parameters, null);
    }

    /**
     * The seven-component shape, for everything that is not a method.
     *
     * <p>Kept as an overload rather than making every existing construction site pass {@code List.of()}:
     * a parameter list is a fact about a <em>method</em>, and a field being asked to declare it has none
     * is noise at a dozen call sites. {@link #parameters()} answering empty for a field is the same
     * statement, made once.</p>
     */
    public SymbolInfo(String name, SymbolKind kind, @Nullable TypeRef type, @Nullable String container,
                      @Nullable String documentation, Set<SymbolModifier> modifiers,
                      @Nullable DeclarationSite declaration) {
        this(name, kind, type, container, documentation, modifiers, declaration, List.of());
    }

    /**
     * The declared parameter types, empty for anything that is not a method.
     *
     * <p><b>Structured, not a rendered string</b>, because generic substitution is the engine's answer and
     * nobody else's: ask {@code List<String>} for {@code get} and JDT reports {@code (int)} returning
     * {@code String}, where a name-based render would have said {@code E}. Handing over text would throw
     * that away at the one seam that has it.</p>
     *
     * <p><b>Types, not names.</b> JDT reports real parameter names only when it has source or a
     * {@code -parameters} build; for an ordinary classpath class it answers {@code arg0}. So a label built
     * from this reads {@code getProperty(String, String)} — which is what Eclipse itself shows, and is
     * better than confidently printing {@code arg0}.</p>
     */
    @Override
    public List<TypeRef> parameters() {
        return parameters;
    }

    /** Whether this is something you call — so accepting it should write brackets. */
    public boolean isInvocable() {
        return kind == SymbolKind.METHOD || kind == SymbolKind.CONSTRUCTOR || kind == SymbolKind.FUNCTION;
    }

    /** {@code (String, int)} — or {@code ()} for a method with none, and {@code ""} for a non-method. */
    public String parameterList() {
        if (kind != SymbolKind.METHOD && kind != SymbolKind.CONSTRUCTOR && kind != SymbolKind.FUNCTION) {
            return "";
        }
        StringBuilder rendered = new StringBuilder("(");
        for (int i = 0; i < parameters.size(); i++) {
            if (i > 0) rendered.append(", ");
            TypeRef parameter = parameters.get(i);
            rendered.append(parameter == null ? "?" : parameter.displayName());
        }
        return rendered.append(')').toString();
    }

    public SymbolInfo withParameters(List<TypeRef> newParameters) {
        return new SymbolInfo(name, kind, type, container, documentation, modifiers, declaration,
                newParameters, signature, containerKind);
    }

    /**
     * The declaration as the engine would write it, with the tokens that colour it. @see Signature
     *
     * <p>Null for everything that has no binding behind it, which is most producers — a consumer draws
     * what it can assemble from the other fields instead. That fallback is not a degraded mode: it is
     * what a grammar-only language will always show.</p>
     */
    public SymbolInfo withSignature(@Nullable Signature newSignature) {
        return new SymbolInfo(name, kind, type, container, documentation, modifiers, declaration,
                parameters, newSignature, containerKind);
    }

    /**
     * What the container IS — an interface, an enum, a class.
     *
     * <p>Not derivable from {@link #container()}, which is a display string: {@code java.util.List} says
     * nothing about {@code List} being an interface, and a capitalisation guess is exactly the heuristic
     * the engine exists to replace. The popup reads it twice — for the icon beside the owner, which drew
     * a class mark over every interface, and for the colour of the last path segment, which is the one
     * place the editor and the popup could still disagree about a name.</p>
     */
    public SymbolInfo withContainerKind(@Nullable SymbolKind newContainerKind) {
        return new SymbolInfo(name, kind, type, container, documentation, modifiers, declaration,
                parameters, signature, newContainerKind);
    }

    /** The common shape: a name and what it is. */
    public static SymbolInfo of(String name, SymbolKind kind) {
        return new SymbolInfo(name, kind, null, null, null, Set.of(), null);
    }

    /** A name, what it is, and its type — everything a completion row draws. */
    public static SymbolInfo of(String name, SymbolKind kind, @Nullable TypeRef type) {
        return new SymbolInfo(name, kind, type, null, null, Set.of(), null);
    }

    public SymbolInfo withType(@Nullable TypeRef newType) {
        return new SymbolInfo(name, kind, newType, container, documentation, modifiers, declaration);
    }

    public SymbolInfo withContainer(@Nullable String newContainer) {
        return new SymbolInfo(name, kind, type, newContainer, documentation, modifiers, declaration);
    }

    public SymbolInfo withDocumentation(@Nullable String docs) {
        return new SymbolInfo(name, kind, type, container, docs, modifiers, declaration);
    }

    public SymbolInfo withModifiers(SymbolModifier... added) {
        return new SymbolInfo(name, kind, type, container, documentation,
                added == null ? Set.of() : Set.of(added), declaration);
    }

    public SymbolInfo withDeclaration(@Nullable DeclarationSite site) {
        return new SymbolInfo(name, kind, type, container, documentation, modifiers, site);
    }

    public boolean is(SymbolModifier modifier) {
        return modifiers.contains(modifier);
    }

    /**
     * How this should be coloured — {@link SymbolKind#captureName()}, except that a static final field
     * is a constant however the engine labelled its kind.
     *
     * <p>The carve-out is here rather than in the engine because every engine would need it and they would
     * not agree: ECJ reports a {@code static final} field as a field with two modifiers, which is exactly
     * right as a fact about the language and exactly wrong as a colour. Both reference IDEs draw it as a
     * constant.</p>
     */
    public String captureName() {
        if (kind == SymbolKind.FIELD && is(SymbolModifier.STATIC) && is(SymbolModifier.FINAL)) {
            return SymbolKind.CONSTANT.captureName();
        }
        return kind.captureName();
    }
}
