package com.crystalgui.text.lang;

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
                         Set<SymbolModifier> modifiers, @Nullable DeclarationSite declaration) {

    public SymbolInfo {
        if (name == null) name = "";
        if (kind == null) kind = SymbolKind.UNKNOWN;
        modifiers = modifiers == null || modifiers.isEmpty() ? Set.of() : Set.copyOf(modifiers);
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
