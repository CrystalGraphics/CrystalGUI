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

    /** Registered patterns are reported in registration ORDER -- {@code Set.copyOf} used to be
     * returned from a method whose javadoc promised order, and it does not preserve any. */
    @Test
    public void aRegisteredEntryIsFoundUnderEveryExtensionItClaimed() {
        assertTrue(LanguageRegistry.rules().contains("EXTENSION:java"));
        assertTrue(LanguageRegistry.rules().contains("EXTENSION:shader"));
        assertTrue("java was registered before glsl and must still be reported first",
                LanguageRegistry.rules().indexOf("EXTENSION:java")
                        < LanguageRegistry.rules().indexOf("EXTENSION:shader"));
    }

    /**
     * <b>An exact file name beats an extension.</b>
     *
     * <p>The registry matched extensions and nothing else, which cannot express most of what a real
     * project contains: {@code Dockerfile}, {@code Makefile} and {@code CMakeLists.txt} have no useful
     * extension, and {@code .gitignore}'s whole name looks like one. Every editor that does this properly
     * matches on a pattern — VS Code takes {@code extensions}, {@code filenames} and
     * {@code filenamePatterns} together.</p>
     *
     * <p>Order is the load-bearing part: name, then extension, then glob. Reversed, {@code CMakeLists.txt}
     * would be whatever claimed {@code .txt}.</p>
     */
    @Test
    public void anExactNameWinsOverTheExtension() {
        LanguageRegistry.Entry cmake = new LanguageRegistry.Entry(
                Language.cFamily("cmake"), () -> SyntaxTokenizer.NONE);
        LanguageRegistry.Entry text = new LanguageRegistry.Entry(
                Language.cFamily("plaintext"), () -> SyntaxTokenizer.NONE);
        LanguageRegistry.registerExtensions(text, "zzz");
        LanguageRegistry.registerNames(cmake, "CMakeLists.zzz");

        assertEquals("cmake", LanguageRegistry.forFileName("CMakeLists.zzz").language().name());
        assertEquals("plaintext", LanguageRegistry.forFileName("notes.zzz").language().name());
        assertEquals("the match must not care about case", "cmake",
                LanguageRegistry.forFileName("cmakelists.ZZZ").language().name());
    }

    /** A dotfile is a NAME, not an extension — {@code .gitignore} is not a "gitignore file". */
    @Test
    public void aLeadingDotIsPartOfTheNameRatherThanAnExtension() {
        LanguageRegistry.Entry ignore = new LanguageRegistry.Entry(
                Language.cFamily("ignore"), () -> SyntaxTokenizer.NONE);
        LanguageRegistry.registerNames(ignore, ".gitignore");

        assertEquals("ignore", LanguageRegistry.forFileName(".gitignore").language().name());
        // And nothing claimed an extension called "gitignore", so a file that really had one is plain.
        assertSame(LanguageRegistry.PLAIN, LanguageRegistry.forFileName("rules.gitignore"));
    }

    /**
     * Globs match the whole name, and {@code .} is a literal.
     *
     * <p>Written as a loop rather than a regex for exactly this: one unescaped dot would make
     * {@code *.js} also claim {@code axjs}, which is the classic way a pattern matcher backed by a regex
     * surprises whoever wrote the pattern.</p>
     */
    @Test
    public void globsMatchTheWholeNameAndTreatDotsLiterally() {
        LanguageRegistry.Entry spec = new LanguageRegistry.Entry(
                Language.cFamily("spec"), () -> SyntaxTokenizer.NONE);
        LanguageRegistry.registerGlobs(spec, "*.test.js");

        assertEquals("spec", LanguageRegistry.forFileName("thing.test.js").language().name());
        assertEquals("spec", LanguageRegistry.forFileName("src/deep/thing.test.js").language().name());
        assertSame("a dot must not behave as 'any character'",
                LanguageRegistry.PLAIN, LanguageRegistry.forFileName("thingXtestXjs"));
        assertSame(LanguageRegistry.PLAIN, LanguageRegistry.forFileName("thing.test.json"));
    }

    /**
     * An extension still beats a glob, so a broad pattern cannot shadow a rule that named a suffix.
     *
     * <p>Deliberately NOT registered as a bare {@code *}. The registry is static, so a catch-all written
     * here is a catch-all for every other test in the JVM — which is exactly what happened when this was
     * first written, and it broke two unrelated assertions about unknown names being plain. The narrow
     * pattern proves the same precedence without the collateral.</p>
     */
    @Test
    public void anExtensionWinsOverAGlob() {
        LanguageRegistry.Entry shadow = new LanguageRegistry.Entry(
                Language.cFamily("shadow"), () -> SyntaxTokenizer.NONE);
        LanguageRegistry.registerGlobs(shadow, "*.java");

        assertEquals("the glob shadowed a registered extension", "java",
                LanguageRegistry.forFileName("Main.java").language().name());
    }
}
