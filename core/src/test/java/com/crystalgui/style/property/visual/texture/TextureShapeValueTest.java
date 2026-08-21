package com.crystalgui.style.property.visual.texture;

import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.render.texture.CgUiShape;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * {@code background/overlay: shape("name")} — a vector mark drawn directly, no texture asset.
 *
 * <p>Only construction is exercised here, never {@link CgUiShape#draw}: building a {@code CgUiShape}
 * touches nothing in CrystalGraphics (it is a bare {@code Kind} holder), but {@code draw()} reaches
 * {@code CgVectorRenderer}, whose static instance buffer allocates against a live GL context — see
 * {@code CgCurveSplitter}'s own doc for the exact {@code ExceptionInInitializerError} this avoids.</p>
 */
public class TextureShapeValueTest {

    @Test
    public void everyCatalogNameParses() {
        String[] names = {
                "chevron-up", "chevron-down", "chevron-left", "chevron-right",
                "triangle-up", "triangle-down", "triangle-left", "triangle-right",
                "checkmark", "cross", "plus", "minus",
                "arrow-up", "arrow-down", "arrow-left", "arrow-right",
        };
        for (String name : names) {
            CgUiDrawable d = TextureValue.parseDrawable("shape(\"" + name + "\")");
            assertNotNull("shape(\"" + name + "\") failed to parse", d);
            assertTrue(d instanceof CgUiShape);
            assertEquals(name, CgUiShape.parseKind(name), ((CgUiShape) d).kind());
        }
    }

    @Test
    public void singleQuotesAreAcceptedTheSameAsDouble() {
        CgUiDrawable d = TextureValue.parseDrawable("shape('checkmark')");
        assertNotNull(d);
        assertEquals(CgUiShape.Kind.CHECKMARK, ((CgUiShape) d).kind());
    }

    /** An unrecognised name is a parse FAILURE (null), not a silent no-op drawable — same convention
     * {@code asset(...)}/{@code sprite(...)} already follow for a bad path. */
    @Test
    public void anUnrecognisedNameFailsToParse() {
        assertNull(TextureValue.parseDrawable("shape(\"not-a-real-shape\")"));
    }

    @Test
    public void wrongArgumentCountFailsToParse() {
        assertNull(TextureValue.parseDrawable("shape()"));
        assertNull(TextureValue.parseDrawable("shape(\"checkmark\", \"extra\")"));
    }

    /** Every enum value must have a name that round-trips back to itself. */
    @Test
    public void everyKindHasAParsableName() {
        for (CgUiShape.Kind kind : CgUiShape.Kind.values()) {
            boolean found = false;
            // Reverse lookup: parseKind must map SOME name to this kind, or the catalog and the
            // enum have silently drifted apart.
            String[] allNames = {
                    "chevron-up", "chevron-down", "chevron-left", "chevron-right",
                    "triangle-up", "triangle-down", "triangle-left", "triangle-right",
                    "checkmark", "cross", "plus", "minus",
                    "arrow-up", "arrow-down", "arrow-left", "arrow-right",
            };
            for (String name : allNames) {
                if (CgUiShape.parseKind(name) == kind) { found = true; break; }
            }
            assertTrue(kind + " has no parsable CSS name", found);
        }
    }
}
