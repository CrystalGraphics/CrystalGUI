package com.crystalgui.ui;

import com.crystalgui.render.text.FontFamilyCache;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.testsupport.UiTestBase;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * {@code font-family} is a <b>preference list</b>, and the first entry that loads wins.
 *
 * <h3>Why this is worth a test rather than a comment</h3>
 *
 * <p>{@code FontFamilyCache.build} used to demand {@code paths.get(0)} specifically and throw when that
 * one file was absent, treating everything after it purely as per-glyph fallback. That is half of what
 * {@code font-family} means and it has a sharp consequence: a stack naming a face the build does not ship
 * yet is a hard crash at <em>first paint</em>, in every window at once, with a stack trace pointing at a
 * cache rather than at the stylesheet that named it.</p>
 *
 * <p>It is exactly the situation the default is in today — the mono face is declared ahead of the sans
 * and the file is not in the tree yet — so this is the behaviour the whole roll-out rests on, and the
 * failure it guards is invisible until the moment something renders.</p>
 */
public class FontStackFallbackTest extends UiTestBase {

    private static final String PRESENT = "crystalgraphics:IBMPlexSans-Regular.ttf";
    private static final String ABSENT = "crystalgui:ui/fonts/DefinitelyNotShipped-Regular.ttf";

    /** A missing FIRST entry steps down the list instead of throwing. */
    @Test
    public void aMissingPreferredFaceFallsThroughToTheNextOne() {
        assertNotNull("a stack whose first entry is absent must still resolve",
                FontFamilyCache.resolve(List.of(ABSENT, PRESENT), 16));
    }

    /** A stack where nothing resolves is genuinely broken, and still says so. */
    @Test
    public void aStackWithNothingLoadableStillThrows() {
        try {
            FontFamilyCache.resolve(List.of(ABSENT, ABSENT + ".also-missing"), 16);
            fail("a UI with no font at all is broken and should not resolve quietly");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("no font-family source"));
        }
    }

    /**
     * The shipped default must resolve <b>as declared</b>, whichever of its entries is present.
     *
     * <p>This is the one that would catch a typo in the mono path: the stack would silently keep using
     * the sans for ever, and the only symptom is that a change everybody believes has shipped has not.</p>
     */
    @Test
    public void theDefaultFontStackResolves() {
        List<String> declared = StylePropertyRegistry.FONT_FAMILY.initialValue;
        assertFalse("the default stack must not be empty", declared.isEmpty());
        assertNotNull(FontFamilyCache.resolve(declared, 16));
    }
}
