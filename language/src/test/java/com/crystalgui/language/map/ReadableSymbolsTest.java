package com.crystalgui.language.map;

import com.crystalgui.text.lang.DeclarationSite;
import com.crystalgui.text.lang.Signature;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.text.syntax.SyntaxToken;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * A quoted declaration renamed, with its colours still over the right words.
 *
 * <h3>Why the rewrite is the part worth testing</h3>
 *
 * <p>Swapping {@code SymbolInfo.name()} is one call. A {@link Signature} is text <b>plus offsets into that
 * text</b>, and the two names are never the same length — {@code func_71203_ab} is thirteen characters and
 * {@code getConfigurationManager} is twenty-three — so every capture after the name has to move by exactly
 * the difference. Get it wrong and the popup still says the right words while colouring the neighbouring
 * one, which is the class of error the {@code Signature.Builder} exists to keep out of engines and which
 * looks like a theming bug rather than arithmetic.</p>
 */
public class ReadableSymbolsTest {

    /** {@code public ServerConfigurationManager func_71203_ab()}, captured the way an engine captures it. */
    private static Signature quoted() {
        return new Signature("public ServerConfigurationManager func_71203_ab()", List.of(
                new SyntaxToken(0, 6, "keyword"),
                new SyntaxToken(7, 33, "type"),
                new SyntaxToken(34, 47, "function"),
                new SyntaxToken(47, 49, "punctuation.bracket")));
    }

    /**
     * <b>The word changes and everything after it moves with it.</b>
     *
     * <p>Asserted on the text a capture actually covers rather than on its offsets, because that is the
     * question — a token is only right if it still spans the word it was put on.</p>
     */
    @Test
    public void aRenamedSignatureKeepsItsColoursOverTheSameWords() {
        Signature renamed = ReadableSymbols.renamed(quoted(), "func_71203_ab", "getConfigurationManager");

        assertEquals("public ServerConfigurationManager getConfigurationManager()", renamed.text());
        assertEquals(List.of("public", "ServerConfigurationManager", "getConfigurationManager", "()"),
                spelled(renamed));
        assertEquals(List.of("keyword", "type", "function", "punctuation.bracket"), names(renamed));
    }

    /**
     * <b>The SHORTER direction too</b>, which moves everything the other way and is where a fix written as
     * "add the difference" without a sign would still pass the test above.
     */
    @Test
    public void aRenameToAShorterNameMovesTheColoursBack() {
        Signature renamed = ReadableSymbols.renamed(
                new Signature("public boolean isRemote()", List.of(
                        new SyntaxToken(0, 6, "keyword"),
                        new SyntaxToken(7, 14, "type"),
                        new SyntaxToken(15, 23, "function"),
                        new SyntaxToken(23, 25, "punctuation.bracket"))),
                "isRemote", "f_1");

        assertEquals("public boolean f_1()", renamed.text());
        assertEquals(List.of("public", "boolean", "f_1", "()"), spelled(renamed));
    }

    /**
     * <b>Anchored on the token that spells the name, not on the first occurrence of it.</b>
     *
     * <p>The name is looked for in a string that also carries the return type, the parameters and
     * sometimes the owner. Here the return type CONTAINS the member's name as a prefix, so a plain
     * {@code indexOf} renames the type and leaves the method alone — a signature that reads
     * {@code public getBlockState getBlock()} coming back as {@code public xState getBlock()}.</p>
     */
    @Test
    public void theNameIsFoundWhereTheEngineSaidItWas() {
        Signature renamed = ReadableSymbols.renamed(
                new Signature("public getBlockState getBlock()", List.of(
                        new SyntaxToken(0, 6, "keyword"),
                        new SyntaxToken(7, 20, "type"),
                        new SyntaxToken(21, 29, "function"),
                        new SyntaxToken(29, 31, "punctuation.bracket"))),
                "getBlock", "func_1");

        assertEquals("public getBlockState func_1()", renamed.text());
        assertEquals(List.of("public", "getBlockState", "func_1", "()"), spelled(renamed));
    }

    /** A signature with nothing to rename is handed back as it stands, not rebuilt. */
    @Test
    public void aSignatureWithoutTheNameIsUntouched() {
        Signature original = quoted();
        assertSame(original, ReadableSymbols.renamed(original, "notInThere", "other"));
    }

    /**
     * <b>Go-to-definition looks for the name the DECOMPILED text actually has.</b>
     *
     * <p>A sourceless classpath member has no position until its class is decompiled, so the site names
     * the member and whoever produced the text is asked where it is — by offering each occurrence to
     * {@code resolveAt} and taking the first that resolves to a member of that name. That text is in the
     * readable namespace, so a site still naming {@code func_71203_ab} matches nothing in it: Ctrl+B
     * opened the class and landed at the top of the file, which reads as the jump being unimplemented for
     * library types rather than as a name in the wrong namespace.</p>
     *
     * <p>The rest of the site is carried through unchanged — it is the same declaration, and only what to
     * look for has been restated.</p>
     */
    @Test
    public void theDeclarationSiteLooksForTheReadableName() {
        String owner = "net/minecraft/server/MinecraftServer";
        MappingSet mappings = MappingSet.builder()
                .method(owner, "func_71203_ab", "getConfigurationManager")
                .build();

        SymbolInfo resolved = SymbolInfo.of("func_71203_ab", SymbolKind.METHOD)
                .withContainer("net.minecraft.server.MinecraftServer")
                .withDeclaration(DeclarationSite.inLibraryMember(
                        "net.minecraft.server.MinecraftServer", "func_71203_ab"));

        SymbolInfo shown = ReadableSymbols.in(mappings, resolved);
        assertEquals("getConfigurationManager", shown.name());
        assertEquals("the jump still hunts for a name the decompiled text does not contain",
                "getConfigurationManager", shown.declaration().member());
    }

    /** An unmapped member keeps the site it was given, object and all. */
    @Test
    public void anUnmappedMemberIsHandedBackUntouched() {
        MappingSet mappings = MappingSet.builder()
                .method("net/minecraft/server/MinecraftServer", "func_71203_ab", "getConfigurationManager")
                .build();
        SymbolInfo resolved = SymbolInfo.of("size", SymbolKind.METHOD)
                .withContainer("java.util.List");
        assertSame(resolved, ReadableSymbols.in(mappings, resolved));
    }

    /** What each capture actually covers, which is the only thing that decides whether it is right. */
    private static List<String> spelled(Signature signature) {
        return signature.tokens().stream()
                .map(token -> signature.text().substring(token.start(), token.end()))
                .collect(java.util.stream.Collectors.toList());
    }

    private static List<String> names(Signature signature) {
        return signature.tokens().stream().map(SyntaxToken::name)
                .collect(java.util.stream.Collectors.toList());
    }
}
