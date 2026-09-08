package com.crystalgui.style.property.visual.texture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.render.texture.CgUiQuad;
import com.crystalgui.serialization.style.DrawableParts;
import com.crystalgui.serialization.style.StyleParts;

/**
 * <b>One list of drawable functions, and everything reads it.</b>
 *
 * <p>The parser knew the nine functions and so did the code that files a copied drawable by which one
 * made it. That second copy is the kind that goes quietly wrong: a function added to the parser and not
 * to the other list still renders perfectly and simply stops being recognised when copied. These hold
 * the two to the same registry.</p>
 */
public class DrawableKindsTest {

    /** Every kind can be offered as a part — no kind exists that the grouping has not heard of. */
    @Test
    public void everyRegisteredKindIsOfferedAsAPart() {
        List<String> partIds = new ArrayList<>();
        for (StyleParts.Part part : DrawableParts.INSTANCE.parts()) partIds.add(part.id());

        List<String> missing = new ArrayList<>();
        for (DrawableKinds.Kind kind : DrawableKinds.all()) {
            if (!partIds.contains(kind.id())) missing.add(kind.id());
        }
        assertTrue("registered kinds nothing would file a copy under: " + missing, missing.isEmpty());
    }

    /** And nothing is offered that cannot be parsed — the list is the registry, not a superset of it. */
    @Test
    public void everyPartIsARegisteredKind() {
        List<String> kindIds = new ArrayList<>();
        for (DrawableKinds.Kind kind : DrawableKinds.all()) kindIds.add(kind.id());

        for (StyleParts.Part part : DrawableParts.INSTANCE.parts()) {
            assertTrue("a part with no kind behind it: " + part.id(), kindIds.contains(part.id()));
        }
    }

    /**
     * <b>The guarantee, stated as the thing a third party does.</b>
     *
     * <p>Registering a kind makes it parse AND makes it file itself, with nothing else touched. If these
     * two ever come apart again, this is what says so.</p>
     */
    @Test
    public void registeringAKindGivesItBothParsingAndASectionOfItsOwn() {
        DrawableKinds.register(DrawableKinds.Kind.function(
                "test-swatch", "Test Swatch", "test-swatch", args -> new CgUiQuad(0xFF123456)));
        try {
            CgUiDrawable parsed = DrawableKinds.parse("test-swatch(anything)");
            assertNotNull("a registered kind parses", parsed);

            DrawableKinds.Kind matched = DrawableKinds.matching("test-swatch(anything)");
            assertNotNull(matched);
            assertEquals("Test Swatch", matched.label());

            List<String> partIds = new ArrayList<>();
            for (StyleParts.Part part : DrawableParts.INSTANCE.parts()) partIds.add(part.id());
            assertTrue("and it is filed under a section of its own with no further edit: " + partIds,
                    partIds.contains("test-swatch"));
        } finally {
            // The registry is process-wide; leaving a fixture in it would be visible to every other test.
            DrawableKinds.unregister("test-swatch");
        }
    }

    /** {@code none} is an absence rather than a way of drawing, so no kind claims it. */
    @Test
    public void noneIsNotAKind() {
        assertNull(DrawableKinds.matching("none"));
        assertNull(DrawableKinds.matching("empty"));
    }

    /** Case picks the function; the original text is what gets parsed, since paths are case-sensitive. */
    @Test
    public void theFunctionNameIsCaseInsensitiveAndItsArgumentsAreNot() {
        assertEquals("gradient", DrawableKinds.matching("LINEAR-GRADIENT(#000000FF, #FFFFFFFF)").id());
        assertEquals("colour", DrawableKinds.matching("#AABBCCDD").id());
    }
}
