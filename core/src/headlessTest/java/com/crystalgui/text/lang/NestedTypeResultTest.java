package com.crystalgui.text.lang;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A nested type's two spellings, and the three questions a picker asks about them.
 *
 * <h3>Why the record carries both</h3>
 *
 * <p>{@code qualifiedName} used to be the identity as well as the display name, deliberately — <i>"two
 * spellings of one name is how they come to disagree"</i>. That holds while every indexed type is
 * top-level, and stops the moment a nested one appears: no class file is called
 * {@code WorldSettings.GameType}, so addressing a {@code library:} resource by it opened an empty
 * document. Where the spellings genuinely differ, carrying both is the only way to keep them agreeing.</p>
 */
public class NestedTypeResultTest {

    private static TypeSearch.Result nested() {
        return new TypeSearch.Result("GameType", "net.minecraft.world.WorldSettings", null,
                SymbolKind.ENUM, false, "net.minecraft.world.WorldSettings$GameType");
    }

    private static TypeSearch.Result topLevel() {
        return new TypeSearch.Result("Minecraft", "net.minecraft.client", null,
                SymbolKind.CLASS, false);
    }

    /**
     * <b>The importable name, the file to open, and the package — three different answers.</b>
     *
     * <p>{@code packageName} names the ENCLOSING TYPE for a nested type so {@code qualifiedName} spells
     * something an author can write; a row that says "in Outer of package" needs the package back, which
     * is what {@code packageOnly} is for. Asserting all three together is the point — any two of them
     * agreeing is what made one field look sufficient.</p>
     */
    @Test
    public void aNestedTypeAnswersEachQuestionSeparately() {
        TypeSearch.Result result = nested();

        assertTrue(result.isNested());
        assertEquals("net.minecraft.world.WorldSettings.GameType", result.qualifiedName());
        assertEquals("net.minecraft.world.WorldSettings$GameType", result.binaryName());
        assertEquals("the file to open is the class the member lives in",
                "net.minecraft.world.WorldSettings", result.topLevelName());
        assertEquals("net.minecraft.world", result.packageOnly());
        assertEquals("WorldSettings", result.enclosingName());
    }

    /** A top-level type answers the same thing to all of them, which is why one field served for years. */
    @Test
    public void aTopLevelTypeIsUnchanged() {
        TypeSearch.Result result = topLevel();

        assertFalse(result.isNested());
        assertEquals("net.minecraft.client.Minecraft", result.qualifiedName());
        assertEquals("net.minecraft.client.Minecraft", result.binaryName());
        assertEquals("net.minecraft.client.Minecraft", result.topLevelName());
        assertEquals("net.minecraft.client", result.packageOnly());
        assertEquals("", result.enclosingName());
    }

    /**
     * <b>Two levels deep.</b>
     *
     * <p>The enclosing chain is a chain, not a name: {@code Outer.Inner} rather than {@code Inner}, and
     * the file to open is still the outermost class. A fix that split on the LAST dollar for everything
     * would open {@code Outer$Inner}, which is not a file either.</p>
     */
    @Test
    public void aDoublyNestedTypeNamesItsWholeChain() {
        TypeSearch.Result result = new TypeSearch.Result("Leaf", "pkg.Outer.Inner", null,
                SymbolKind.CLASS, false, "pkg.Outer$Inner$Leaf");

        assertEquals("pkg.Outer", result.topLevelName());
        assertEquals("pkg", result.packageOnly());
        assertEquals("Outer.Inner", result.enclosingName());
        assertEquals("pkg.Outer.Inner.Leaf", result.qualifiedName());
    }

    /** A type in the default package has no package to name, and must not invent one. */
    @Test
    public void aNestedTypeInTheDefaultPackageHasNoPackage() {
        TypeSearch.Result result = new TypeSearch.Result("Inner", "Outer", null,
                SymbolKind.CLASS, false, "Outer$Inner");

        assertEquals("Outer", result.topLevelName());
        assertEquals("", result.packageOnly());
        assertEquals("Outer", result.enclosingName());
    }
}
