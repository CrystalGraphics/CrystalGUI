package com.crystalgui.headless;

import com.crystalgui.text.syntax.Language;
import com.crystalgui.text.syntax.LanguageRegistry;
import com.crystalgui.text.syntax.SyntaxTokenizer;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Which language a file name resolves to. Headless — a server classifies documents it never renders.
 */
public class LanguageRegistryTest {

    @Test
    public void javaFilesResolveToTheJavaLanguage() {
        assertEquals("java", LanguageRegistry.forFileName("Main.java").language().name());
    }

    /**
     * Every extension GLSL is written under, not just {@code .glsl}.
     *
     * <p>The stage suffixes are what a driver's toolchain uses, and CrystalGraphics' own shipped assets
     * are {@code .shader}, {@code .vert} and {@code .frag} — so omitting them would mean the engine's own
     * sources opened as plain text.</p>
     */
    @Test
    public void everyGlslExtensionResolvesToGlsl() {
        for (String name : new String[]{"a.glsl", "a.vert", "a.frag", "a.geom",
                "a.tesc", "a.tese", "a.comp", "gui_quad.shader"}) {
            assertEquals(name, "glsl", LanguageRegistry.forFileName(name).language().name());
        }
    }

    @Test
    public void extensionsAreCaseInsensitive() {
        assertEquals("java", LanguageRegistry.forFileName("Main.JAVA").language().name());
        assertEquals("glsl", LanguageRegistry.forFileName("Main.Frag").language().name());
    }

    /** The LAST dot decides, so a compound name still classifies. */
    @Test
    public void theLastDotDecidesTheExtension() {
        assertEquals("java", LanguageRegistry.forFileName("util.test.java").language().name());
    }

    /**
     * A dot in a directory name is not the file's extension.
     *
     * <p>{@code my.project/README} has no extension at all — reading backwards from the last dot without
     * first cutting at the separator would call it a {@code project} file.</p>
     */
    @Test
    public void aDotInADirectoryNameIsNotAnExtension() {
        assertSame(LanguageRegistry.PLAIN, LanguageRegistry.forFileName("my.project/README"));
        assertSame(LanguageRegistry.PLAIN, LanguageRegistry.forFileName("my.project\\README"));
        assertEquals("java", LanguageRegistry.forFileName("my.project/src/Main.java").language().name());
    }

    /** An unknown or absent extension is plain — the honest answer, not a guess. The file still opens. */
    @Test
    public void anUnknownExtensionIsPlainRatherThanAGuess() {
        assertSame(LanguageRegistry.PLAIN, LanguageRegistry.forFileName("notes.txt"));
        assertSame(LanguageRegistry.PLAIN, LanguageRegistry.forFileName("README"));
        assertSame(LanguageRegistry.PLAIN, LanguageRegistry.forFileName("trailing."));
        assertSame(LanguageRegistry.PLAIN, LanguageRegistry.forFileName(null));
        assertSame(Language.PLAIN, LanguageRegistry.PLAIN.language());
        assertSame(SyntaxTokenizer.NONE, LanguageRegistry.PLAIN.newTokenizer());
    }

    @Test
    public void isKnownReportsWhetherTheExtensionWasRegistered() {
        assertTrue(LanguageRegistry.isKnown("Main.java"));
        assertTrue(LanguageRegistry.isKnown("shade.frag"));
        assertFalse(LanguageRegistry.isKnown("notes.txt"));
        assertFalse(LanguageRegistry.isKnown(null));
    }

    /**
     * <b>Each document gets its own tokenizer instance.</b>
     *
     * <p>{@code KeywordTokenizer} is stateless and would survive sharing, but the interface exists for
     * implementations that are not: {@code SyntaxTokenizer.edited} is there so a tree-sitter backend can
     * hold a parse tree per document. A shared instance works today and silently applies one file's edits
     * to another file's tree the moment such a backend is registered.</p>
     */
    @Test
    public void eachCallProducesAFreshTokenizer() {
        LanguageRegistry.Entry entry = LanguageRegistry.forFileName("Main.java");
        assertNotSame(entry.newTokenizer(), entry.newTokenizer());
    }

    @Test
    public void aRegisteredEntryIsFoundUnderEveryExtensionItClaimed() {
        assertTrue(LanguageRegistry.extensions().contains("java"));
        assertTrue(LanguageRegistry.extensions().contains("shader"));
    }
}
