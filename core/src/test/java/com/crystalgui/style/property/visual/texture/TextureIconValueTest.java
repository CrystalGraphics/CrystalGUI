package com.crystalgui.style.property.visual.texture;

import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.render.texture.CgUiSvg;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The {@code icon("ns:name")} form of {@code background}/{@code overlay}.
 *
 * <p>In {@code test} rather than {@code headlessTest} because resolving an icon reads the file through
 * {@code CgIO}. Parsing is deliberately the part that touches the disk — a stylesheet naming a missing icon
 * should fail where the sheet is read, not silently draw nothing at paint time.</p>
 */
public class TextureIconValueTest {

    @Test
    public void anIconNameResolvesToAVectorDrawable() {
        CgUiDrawable drawable = TextureValue.parseDrawable("icon(\"crystalgui:folder\")");
        assertNotNull("the shipped folder icon did not resolve", drawable);
        assertTrue("expected a vector drawable, got " + drawable.getClass().getSimpleName(),
                drawable instanceof CgUiSvg);

        // The viewBox, read 1:1 as logical pixels -- what overlay-fit resolves against.
        assertEquals(24f, drawable.intrinsicWidth(), 0.01f);
        assertEquals(24f, drawable.intrinsicHeight(), 0.01f);
    }

    /** The namespace is optional and defaults to {@code crystalgui}, matching {@code asset(...)}. */
    @Test
    public void theNamespaceIsOptional() {
        assertTrue(TextureValue.parseDrawable("icon(\"folder\")") instanceof CgUiSvg);
    }

    /**
     * <b>A missing icon is a parse failure, not an empty drawable.</b>
     *
     * <p>Null is how this class reports "that is not valid CSS", and {@code none} already spells a
     * deliberate absence. Collapsing the two would make a typo indistinguishable from an intention — the
     * same reasoning {@code parseDrawable} already applies to {@code none}/{@code empty}.</p>
     */
    @Test
    public void aMissingIconIsAParseFailure() {
        assertNull(TextureValue.parseDrawable("icon(\"crystalgui:no-such-icon\")"));
        assertNull(TextureValue.parseDrawable("icon()"));
        assertNull(TextureValue.parseDrawable("icon(\"\")"));
    }

    /** Trailing args are type-sniffed and order-independent, exactly as {@code image(...)}'s are. */
    @Test
    public void trailingArgumentsAreAccepted() {
        assertTrue(TextureValue.parseDrawable("icon(\"crystalgui:folder\", #FF0000)") instanceof CgUiSvg);
        assertTrue(TextureValue.parseDrawable("icon(\"crystalgui:folder\", monochrome)") instanceof CgUiSvg);
        assertTrue(TextureValue.parseDrawable("icon(\"crystalgui:folder\", monochrome, #FF0000)")
                instanceof CgUiSvg);
        assertNull("an unrecognised trailing arg must fail rather than be ignored",
                TextureValue.parseDrawable("icon(\"crystalgui:folder\", wibble)"));
    }

    /**
     * <b>Every icon named by the shipped file-type theme parses as CSS too.</b>
     *
     * <p>The theme and the {@code icon()} keyword resolve a name through the same one method, and this is
     * what pins that they agree — a theme entry pointing at an icon a stylesheet cannot name would be a
     * silent gap between two files nobody reads together.</p>
     */
    @Test
    public void everyThemeIconIsAlsoNameableFromCss() {
        for (String name : new String[]{"crystalgui:folder", "crystalgui:file-text", "crystalgui:image",
                "crystalgui:code", "crystalgui:package"}) {
            assertTrue(name + " is in the theme but does not parse as CSS",
                    TextureValue.parseDrawable("icon(\"" + name + "\")") instanceof CgUiSvg);
        }
    }
}
