package com.crystalgui.style.property.visual.texture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.google.gson.JsonElement;

import com.crystalgui.serialization.JsonOps;
import com.crystalgui.serialization.style.DrawableParts;
import com.crystalgui.serialization.style.StyleParts;

import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.render.texture.CgUiGradient;
import com.crystalgui.render.texture.CgUiGrid;
import com.crystalgui.render.texture.CgUiLayers;
import com.crystalgui.render.texture.CgUiQuad;
import com.crystalgui.render.texture.CgUiShape;
import com.crystalgui.style.property.StylePropertyRegistry;

/**
 * <b>A background can be several drawables, comma-separated, as CSS writes one.</b>
 *
 * <p>It held exactly one before, which is why {@code overlay} exists at all — a second property for
 * what CSS says with a comma. The split is paren-aware, so the interesting case is not the stack but
 * the value that merely LOOKS like one: {@code linear-gradient(to bottom, a, b)} has two commas in it
 * and is one layer.</p>
 */
public class CgUiLayersTest {

    private static CgUiDrawable parse(String css) {
        return new TextureValue(css).compute();
    }

    /** Three layers, three drawables, in the order they were written. */
    @Test
    public void aCommaSeparatedBackgroundIsAStack() {
        CgUiDrawable value = parse("grid(16, #6EDCD024), shape(\"checkmark\"),"
                + " linear-gradient(to bottom, #3574F0FF, #2E436EFF)");
        assertNotNull(value);
        assertTrue("expected a stack, got " + value.getClass().getSimpleName(),
                value instanceof CgUiLayers);

        CgUiLayers layers = (CgUiLayers) value;
        assertEquals(3, layers.layers().size());
        assertTrue(layers.layers().get(0) instanceof CgUiGrid);
        assertTrue(layers.layers().get(1) instanceof CgUiShape);
        assertTrue(layers.layers().get(2) instanceof CgUiGradient);
    }

    /**
     * <b>The first layer is the one on top</b>, which is CSS's order and reads backwards until you know
     * it. The list holds them in that order and {@code draw} walks it from the end.
     */
    @Test
    public void theFirstLayerWrittenIsTheTopOne() {
        CgUiLayers layers = (CgUiLayers) parse("#FF0000FF, #00FF00FF");
        assertNotNull(layers);
        assertEquals(0xFFFF0000, ((CgUiQuad) layers.layers().get(0)).getColorArgb());
        assertEquals(0xFF00FF00, ((CgUiQuad) layers.layers().get(1)).getColorArgb());
    }

    /** A function's own commas are not layer separators. */
    @Test
    public void aValueThatMerelyLooksLikeAStackIsOneLayer() {
        assertTrue(parse("linear-gradient(to bottom, #3574F0FF, #2E436EFF)") instanceof CgUiGradient);
        assertTrue(parse("grid(16 24, #6EDCD024, 2)") instanceof CgUiGrid);
        assertTrue(parse("rgba(0, 0, 0, 0.5)") instanceof CgUiQuad);
    }

    /**
     * One unreadable layer fails the whole declaration.
     *
     * <p>Dropping it would render a stack the author did not write, and a background that is quietly
     * missing a layer is the kind of wrong picture nobody looks for in the CSS.</p>
     */
    @Test
    public void oneBadLayerFailsTheDeclaration() {
        assertNull(parse("grid(16, #6EDCD024), sepia(0.4)"));
        assertNull(parse("nonsense(1), #FF0000FF"));
    }

    /** {@code none} is a layer like any other — CSS allows one in a list. */
    @Test
    public void noneIsAllowedAsALayer() {
        CgUiDrawable value = parse("none, #FF0000FF");
        assertTrue(value instanceof CgUiLayers);
        assertEquals(CgUiDrawable.EMPTY, ((CgUiLayers) value).layers().get(0));
    }

    /**
     * <b>Each layer is offered on its own</b>, filed under its own kind.
     *
     * <p>The whole point of the stack for Copy Attributes: a background of three drawables is three
     * rows, under Grid, Shape and Gradient, and ticking one pastes that layer alone.</p>
     */
    @Test
    public void everyLayerIsItsOwnPart() {
        CgUiDrawable stack = parse("grid(16, #6EDCD024), shape(\"checkmark\"),"
                + " linear-gradient(to bottom, #3574F0FF, #2E436EFF)");
        assertNotNull(stack);
        assertTrue("a stack divides into its layers", DrawableParts.INSTANCE.divides(stack));

        List<StyleParts.Part> parts = DrawableParts.INSTANCE.parts(stack);
        assertEquals(3, parts.size());
        assertEquals(List.of("Grid", "Shape", "Gradient"),
                parts.stream().map(StyleParts.Part::group).toList());

        for (StyleParts.Part part : parts) {
            assertNotNull("layer " + part.id() + " has nothing to encode",
                    DrawableParts.INSTANCE.encodePart(JsonOps.INSTANCE, stack, part.id()));
        }
    }

    /**
     * Pasting one layer keeps the target's others, at the depth it had in the source.
     *
     * <p>A shorter target grows rather than shifting: the third layer of one element must not land as
     * the first layer of an element that only had one.</p>
     */
    @Test
    public void pastingOneLayerLeavesTheTargetsOthersAlone() {
        CgUiDrawable source = parse("grid(16, #6EDCD024), shape(\"checkmark\"), #3574F0FF");
        CgUiDrawable target = parse("#FF0000FF, #00FF00FF");
        assertNotNull(source);
        assertNotNull(target);

        JsonElement layer = DrawableParts.INSTANCE.encodePart(JsonOps.INSTANCE, source, "layer-0");
        assertNotNull(layer);
        CgUiDrawable merged = DrawableParts.INSTANCE.mergePart(JsonOps.INSTANCE, target, "layer-0", layer);

        assertTrue(merged instanceof CgUiLayers);
        List<CgUiDrawable> layers = ((CgUiLayers) merged).layers();
        assertEquals(2, layers.size());
        assertTrue("the pasted layer replaced the top one", layers.get(0) instanceof CgUiGrid);
        assertEquals("…and the target's second layer is untouched",
                0xFF00FF00, ((CgUiQuad) layers.get(1)).getColorArgb());
    }

    /**
     * <b>A stack assembled by a paste can still be written.</b>
     *
     * <p>{@code DrawableParts.mergePart} builds a new stack in Java, and {@code StyleAttributes}
     * re-encodes the merged value on the spot — so a stack that could only be written from a remembered
     * source threw the moment one layer was pasted onto another element, taking the whole harness with
     * it. A stack spells itself out of its layers instead.</p>
     */
    @Test
    public void aStackBuiltByAPasteCanWriteItself() {
        CgUiDrawable source = parse("grid(16, #6EDCD024), #3574F0FF");
        CgUiDrawable target = parse("#FF0000FF");
        assertNotNull(source);

        JsonElement layer = DrawableParts.INSTANCE.encodePart(JsonOps.INSTANCE, source, "layer-1");
        assertNotNull(layer);
        CgUiDrawable merged = DrawableParts.INSTANCE.mergePart(JsonOps.INSTANCE, target, "layer-1", layer);

        String written = StylePropertyRegistry.BACKGROUND.write(merged);
        assertEquals("#FF0000FF, #3574F0FF", written);
        assertTrue(new TextureValue(written).compute() instanceof CgUiLayers);
    }

    /** And a stack survives being written back out and read in again. */
    @Test
    public void aStackRoundTrips() {
        String css = "grid(16, #6EDCD024), shape(\"checkmark\"), #3574F0FF";
        CgUiDrawable value = parse(css);
        assertNotNull(value);

        String written = StylePropertyRegistry.BACKGROUND.write(value);
        CgUiDrawable back = new TextureValue(written).compute();
        assertTrue("wrote '" + written + "', which read back as "
                + (back == null ? "nothing" : back.getClass().getSimpleName()),
                back instanceof CgUiLayers);
        assertEquals(3, ((CgUiLayers) back).layers().size());
    }
}
