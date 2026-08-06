package com.crystalgui.ui;

import com.crystalgui.render.texture.asset.FileIconTheme;
import com.crystalgui.render.texture.svg.SvgDocument;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * The shipped file-icon theme, and the resolution rules it is read through.
 *
 * <p>In {@code test} rather than {@code headlessTest} because loading reaches {@code CgIO}, which is
 * CrystalGraphics <b>core</b> and deliberately off that classpath.</p>
 */
public class FileIconThemeTest {

    private static final FileIconTheme THEME = FileIconTheme.getDefault();

    /**
     * <b>Every icon the shipped theme names exists, loads, and draws something.</b>
     *
     * <p>Walks the theme rather than restating it. A hand-written list of "the icons we ship" is two
     * copies of one fact and the copy in the test is the one that goes stale — a theme grows an entry,
     * nothing checks the file is there, and the first anyone hears of it is a blank row in a file tree.</p>
     *
     * <p>"Draws something" is the half that matters. A missing file and a file that parses to nothing both
     * produce an empty icon on screen, and only the second one gets past a null check.</p>
     */
    @Test
    public void everyIconTheThemeNamesLoadsAndDraws() {
        // A FLOOR, not merely non-empty. `file` and `folder` alone make iconNames() non-empty, so an
        // extension map that failed to parse -- or a theme file truncated to its first few keys -- would
        // sail past an isEmpty() check while the file tree quietly showed one glyph for everything.
        //
        // Deliberately well under the number shipped (16 as of the 2023 New UI set) rather than tracking
        // it. The assertion is about the map having PARSED, not about how many icons exist -- pinning it
        // to the exact count means every curation of the icon set edits a test that was not testing that.
        assertTrue("the shipped theme resolved only " + THEME.iconNames().size()
                        + " distinct icons; the extension map did not parse",
                THEME.iconNames().size() >= 10);
        for (String name : THEME.iconNames()) {
            SvgDocument document = SvgDocument.of(FileIconTheme.toResourcePath(name));
            assertNotNull(name + " is named by the theme but has no file", document);
            assertFalse(name + " loaded but draws nothing", document.isEmpty());

            // Inside its own box, so a mishandled transform shows up as a failure rather than as artwork
            // sprayed across the row.
            float w = document.width(), h = document.height();
            for (SvgDocument.DrawOp op : document.ops()) {
                for (int i = 0; i < op.data().length; i += 2) {
                    assertTrue(name + " escaped its viewBox",
                            op.data()[i] >= -1f && op.data()[i] <= w + 1f
                                    && op.data()[i + 1] >= -1f && op.data()[i + 1] <= h + 1f);
                }
            }
        }
    }

    @Test
    public void theShippedThemeResolvesToRealIcons() {
        assertNotNull("Main.java", THEME.drawableFor("Main.java", false, false));
        assertNotNull("a folder", THEME.drawableFor("src", true, false));
        assertNotNull("an unknown extension falls back to the generic file icon",
                THEME.drawableFor("mystery.qqq", false, false));
    }

    /**
     * <b>An exact file name beats an extension.</b>
     *
     * <p>{@code package.json} is a package manifest, not a JSON document, and VS Code orders its lookup
     * this way for exactly that reason. Checking the extension map first would make the rule unstateable
     * — every named file in a theme has an extension too.</p>
     */
    @Test
    public void anExactNameBeatsAnExtension() {
        assertEquals("crystalgui:filetypes/json", THEME.iconFor("package.json", false, false));
        assertEquals("crystalgui:filetypes/json", THEME.iconFor("tsconfig.json", false, false));
    }

    /**
     * <b>The longest matching extension wins.</b>
     *
     * <p>{@code types.d.ts} is a TypeScript declaration file, and "everything after the last dot" cannot
     * express that at all. Asserted on the <em>class</em> rather than the icon, because both extensions
     * resolve to the same {@code code} glyph — so the icon alone passes whichever way the match went, and
     * would be a test that agrees with any implementation.</p>
     */
    @Test
    public void theLongestExtensionMatchWins() {
        assertEquals("filetype-d-ts", THEME.classFor("types.d.ts", false));
        assertEquals("filetype-ts", THEME.classFor("app.ts", false));
        assertEquals("filetype-tar-gz", THEME.classFor("release.tar.gz", false));
        assertEquals("filetype-gz", THEME.classFor("dump.gz", false));
    }

    /**
     * <b>A bare suffix is not an extension.</b>
     *
     * <p>A file literally named {@code ts} is not TypeScript. The leading dot is required, which is why
     * the match walks dots rather than calling {@code endsWith}.</p>
     */
    @Test
    public void aNameWithNoDotMatchesNoExtension() {
        assertEquals("filetype-file", THEME.classFor("ts", false));
        // anyType, not text: the fallback is for a file whose type we could not identify, and IntelliJ's
        // anyType is exactly that icon. A text document is a positive claim about the contents, which is
        // the one thing an unmatched name has not made.
        assertEquals("crystalgui:filetypes/anyType", THEME.iconFor("ts", false, false));
    }

    /** Case is not meaningful in a file name here, and disagreeing file systems are why. */
    @Test
    public void lookupIsCaseInsensitive() {
        assertEquals("crystalgui:filetypes/java", THEME.iconFor("MAIN.JAVA", false, false));
        assertEquals("filetype-java", THEME.classFor("Main.Java", false));
        assertEquals("crystalgui:filetypes/json", THEME.iconFor("Package.JSON", false, false));
    }

    /**
     * <b>An expanded folder falls back to the plain folder icon.</b>
     *
     * <p>A theme that draws one folder icon is the common case; requiring both would make it state the
     * same value twice, and a theme that stated only {@code folder} would show nothing when open.</p>
     */
    @Test
    public void anExpandedFolderFallsBackToTheFolderIcon() {
        assertEquals(THEME.iconFor("src", true, false), THEME.iconFor("src", true, true));
        assertEquals("filetype-folder", THEME.classFor("src", true));
    }

    /** A missing theme resolves nothing rather than throwing — a broken pack must not take the UI down. */
    @Test
    public void aMissingThemeIsEmptyRatherThanFatal() {
        FileIconTheme missing = FileIconTheme.of("crystalgui:no-such-theme");
        assertNotNull(missing);
        assertNull(missing.iconFor("Main.java", false, false));
        assertNull(missing.drawableFor("Main.java", false, false));
        assertEquals("filetype-file", missing.classFor("Main.java", false));
    }

    /** The one definition of where an icon lives — shared with {@code icon()} so they cannot diverge. */
    @Test
    public void anIconNameResolvesToItsAssetPath() {
        assertEquals("crystalgui:ui/icons/folder.svg", FileIconTheme.toResourcePath("crystalgui:folder"));
        assertEquals("crystalgui:ui/icons/folder.svg", FileIconTheme.toResourcePath("folder"));
        assertEquals("mymod:ui/icons/deep/thing.svg", FileIconTheme.toResourcePath("mymod:deep/thing"));
    }

    /**
     * <b>A comma-grouped key registers every name in it, and each still matches on its own.</b>
     *
     * <p>The half that is easy to get wrong is the <em>class</em>: it keys on the extension that matched,
     * not on the group or the icon, so grouping nine image formats onto one line must still leave
     * {@code .filetype-png} distinct from {@code .filetype-svg}. If it did not, grouping would silently
     * cost every grouped type its own colour — a cascade change disguised as a formatting one.</p>
     */
    @Test
    public void aCommaGroupedKeyRegistersEveryNameInIt() {
        assertEquals("crystalgui:filetypes/image", THEME.iconFor("a.png", false, false));
        assertEquals("crystalgui:filetypes/image", THEME.iconFor("a.jpeg", false, false));
        assertEquals("crystalgui:filetypes/image", THEME.iconFor("a.tga", false, false));

        assertEquals("filetype-png", THEME.classFor("a.png", false));
        assertEquals("filetype-tga", THEME.classFor("a.tga", false));

        // Whitespace around a member is trimmed rather than becoming part of the key -- otherwise only the
        // first name on each line would ever match and the rest would silently do nothing.
        assertEquals("crystalgui:filetypes/javaScript", THEME.iconFor("a.jsx", false, false));

        // Multi-dot members survive grouping, so longest-first extension matching still works. Asserted
        // as "d.ts agrees with ts, and is still its own class" rather than by naming the icon: which
        // glyph TypeScript uses is the theme's business and changes whenever one is dropped in, and a
        // test that restates it fails for a reason that has nothing to do with grouping.
        assertEquals(THEME.iconFor("a.ts", false, false), THEME.iconFor("types.d.ts", false, false));
        assertEquals("filetype-d-ts", THEME.classFor("types.d.ts", false));
        assertEquals("filetype-ts", THEME.classFor("a.ts", false));
    }

    /**
     * <b>The dark variant is taken when it exists and skipped when it does not.</b>
     *
     * <p>The fallback is the half worth pinning. Not every icon needs two drawings — a shape that reads on
     * either background ships once — so a missing {@code _dark} file has to mean "variant-neutral" rather
     * than resolving to a path with nothing behind it, which would blank the row instead of drawing the
     * icon that does exist.</p>
     */
    @Test
    public void theDarkVariantIsUsedOnlyWhereThereIsOne() {
        FileIconTheme.Variant original = FileIconTheme.getVariant();
        try {
            FileIconTheme.setVariant(FileIconTheme.Variant.LIGHT);
            assertEquals("crystalgui:filetypes/java",
                    FileIconTheme.withVariant("crystalgui:filetypes/java"));

            FileIconTheme.setVariant(FileIconTheme.Variant.DARK);
            assertEquals("crystalgui:filetypes/java_dark",
                    FileIconTheme.withVariant("crystalgui:filetypes/java"));
            // The Feather chrome marks are stroke="currentColor", so they theme from the cascade and ship
            // exactly one drawing -- the fallback case, using a real asset rather than an invented one.
            assertEquals("crystalgui:folder", FileIconTheme.withVariant("crystalgui:folder"));
            assertNull(FileIconTheme.withVariant(null));

            // The drawable follows the variant without any caller passing it, which is what keeps the
            // switch out of ProjectFileTree.
            assertNotNull(THEME.drawableFor("Main.java", false, false));
        } finally {
            FileIconTheme.setVariant(original);
        }
    }
}
