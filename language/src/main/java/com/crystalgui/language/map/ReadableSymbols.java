package com.crystalgui.language.map;

import com.crystalgui.text.lang.DeclarationSite;
import com.crystalgui.text.lang.Signature;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.text.syntax.SyntaxToken;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows a resolved symbol in the <b>readable</b> namespace, whatever the author spelled.
 *
 * <h3>Why an engine's own answer needs correcting at all</h3>
 *
 * <p>The compile view declares every mapped member twice — once under the name the mapping shows and once
 * under the name the runtime uses — so a legacy script written against SRG names builds. The cost is that
 * a script naming {@code func_71203_ab} resolves to the <em>alias</em> declaration, and an engine quotes
 * what it resolved: the popup read {@code public ServerConfigurationManager func_71203_ab()} where the
 * whole point was to read it as {@code getConfigurationManager}.</p>
 *
 * <p>The alias cannot carry the other name itself. One declaration has one name, and the name is what
 * makes it findable — so the correction belongs where the answer is presented rather than where it is
 * produced. This is the same thing {@code InteropResolver.asReadable} does for JavaScript, one layer up
 * and for every engine at once: which namespace a platform's members are SHOWN in is a fact about the
 * runtime, not about the language asking, so a third engine gets it without knowing this exists.</p>
 *
 * <p>Free for anything already readable. {@link MappingSet#readableMethod} answers with its input when
 * nothing maps the name, so a JavaScript symbol — already renamed on its way out of the interop resolver —
 * and every JDK member pass through untouched.</p>
 */
public final class ReadableSymbols {

    private ReadableSymbols() {
    }

    /**
     * The same symbol under the name the mapping shows, or the symbol unchanged.
     *
     * @param symbol an engine's answer, or null — null is an ordinary answer and passes straight through
     */
    @Nullable
    public static SymbolInfo of(@Nullable SymbolInfo symbol) {
        return in(PlatformMappings.current(), symbol);
    }

    /**
     * The same, against a mapping named outright.
     *
     * <p>Split from {@link #of} so the rule is a FUNCTION of a mapping and a symbol rather than of a
     * process-wide static — which is what lets it be asked a question without standing a platform up
     * first, and is the difference between covering this and covering the registry around it.</p>
     */
    @Nullable
    static SymbolInfo in(MappingSet mappings, @Nullable SymbolInfo symbol) {
        if (symbol == null || symbol.name() == null || symbol.name().isEmpty()) return symbol;
        if (mappings == null || mappings.isIdentity()) return symbol;
        // THE DECLARING CLASS, not the receiver: a mapping names the type that declares a member, which is
        // what `container()` reports. Same rule `InteropResolver.asReadable` follows, and it is what makes
        // an inherited member resolve rather than being looked for on the subclass that has none.
        String container = symbol.container();
        if (container == null || container.isEmpty()) return symbol;
        String internal = container.replace('.', '/');

        // OWNER-KEYED FIRST, THEN THE UNQUALIFIED TIER -- and the second half is not optional.
        //
        // MCP's CSVs carry no owner at all, because an SRG name is globally unique, so essentially every
        // real mapping lives in the unqualified tier. An owner-keyed-only lookup therefore renames
        // NOTHING on a Minecraft host: `func_71276_C` stayed itself, and the popup and the Ctrl+B jump
        // both went back to runtime names. That was this method's own regression, introduced fixing the
        // case below.
        //
        // WHAT THE GUARD HAD TO BE INSTEAD is a statement about the OWNER rather than about the tier. The
        // case that went wrong was a JavaScript chain ending in `list.get(0)`, which infers
        // java.lang.Object: `field_71075_bZ` was renamed there too and hovered as a member called
        // `capabilities` belonging to Object, which declares nothing of the sort. The name was right and
        // the type it was attributed to was invented. A JDK type never declares an SRG member, so
        // refusing the unqualified tier for one costs nothing and removes exactly that answer.
        //
        // Method first, then field. Asked this way rather than off SymbolKind because a kind vocabulary is
        // a thing to keep in step with two engines, and a name is a method's or a field's -- never both.
        String readable = mappings.readableMethodOfOwner(internal, symbol.name());
        if (readable.equals(symbol.name())) {
            readable = mappings.readableFieldOfOwner(internal, symbol.name());
        }
        if (readable.equals(symbol.name()) && !declaresNoMappedMembers(container)) {
            readable = mappings.readableMethod(internal, symbol.name());
            if (readable.equals(symbol.name())) readable = mappings.readableField(internal, symbol.name());
        }
        if (readable.equals(symbol.name())) return symbol;

        SymbolInfo shown = symbol.withName(readable)
                .withSignature(renamed(symbol.signature(), symbol.name(), readable));
        // AND WHAT GO-TO-DEFINITION LOOKS FOR. A sourceless classpath member has no position until its
        // class is decompiled, so the site names the MEMBER and the provider that produced the text is
        // asked where it is -- by offering each occurrence to `resolveAt` and taking the first that
        // resolves to a member of that name. The decompiled text is in the READABLE namespace, so a site
        // still naming `func_71203_ab` matches nothing in it: Ctrl+B opened the class and landed at the
        // top of the file, which reads as the jump being unimplemented for library types.
        DeclarationSite site = shown.declaration();
        if (site == null || site.member() == null || !site.member().equals(symbol.name())) return shown;
        return shown.withDeclaration(new DeclarationSite(site.resource(), site.start(), site.end(),
                readable));
    }

    /**
     * Whether {@code container} is a type no mapping could ever name a member of.
     *
     * <p>The platform's own classes only. A JDK type cannot declare {@code func_71276_C}, so an
     * unqualified entry matching one is a name that has escaped its namespace — which is what happens
     * when a receiver's type was INFERRED rather than declared and the inference landed on
     * {@code java.lang.Object}.</p>
     */
    private static boolean declaresNoMappedMembers(String container) {
        return container.startsWith("java.") || container.startsWith("javax.")
                || container.startsWith("jdk.") || container.startsWith("sun.");
    }

    /**
     * The quoted declaration with one word replaced, and every capture after it moved.
     *
     * <p>A {@link Signature} is text plus tokens INTO that text, so replacing a word of different length
     * without moving what follows leaves every colour after it landing on the neighbouring word — the
     * failure the builder exists to prevent when one is written in the first place.</p>
     *
     * <p><b>Anchored on a token, not on {@code indexOf}.</b> The name is being looked for in a string that
     * also holds the return type, the parameter types and the owner, and a search would take whichever
     * came first. A token that spans exactly the word is the engine's own statement of where the name is.
     * {@code indexOf} remains as the fallback for a signature carrying no captures at all, which is what a
     * assembled one is.</p>
     */
    @Nullable
    static Signature renamed(@Nullable Signature signature, String from, String to) {
        if (signature == null || signature.isEmpty()) return signature;
        String text = signature.text();
        int at = anchor(signature, text, from);
        if (at < 0) return signature;

        int after = at + from.length();
        int delta = to.length() - from.length();
        String updated = text.substring(0, at) + to + text.substring(after);

        List<SyntaxToken> moved = new ArrayList<>(signature.tokens().size());
        for (SyntaxToken token : signature.tokens()) {
            if (token.end() <= at) {
                moved.add(token);
            } else if (token.start() == at && token.end() == after) {
                // The name itself: it keeps its capture and takes the new word's width.
                moved.add(new SyntaxToken(at, at + to.length(), token.name()));
            } else if (token.start() >= after) {
                moved.add(new SyntaxToken(token.start() + delta, token.end() + delta, token.name()));
            }
            // Anything straddling the replaced word describes text that no longer exists. Dropping it
            // costs one word's colour; shifting it would put a capture over a boundary and can go
            // negative, which SyntaxToken refuses outright.
        }
        return new Signature(updated, moved);
    }

    /** Where the name is: the token that spells it, else the first occurrence. */
    private static int anchor(Signature signature, String text, String from) {
        for (SyntaxToken token : signature.tokens()) {
            if (token.end() > text.length()) continue;
            if (token.end() - token.start() != from.length()) continue;
            if (text.startsWith(from, token.start())) return token.start();
        }
        return text.indexOf(from);
    }
}
