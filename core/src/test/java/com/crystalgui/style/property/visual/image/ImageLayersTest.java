package com.crystalgui.style.property.visual.image;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.crystalgraphics.serialization.format.CgJsonOps;
import com.crystalgui.render.image.CgUiImage;
import com.crystalgui.serialization.style.ImageParts;
import com.crystalgui.serialization.style.StyleParts;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.google.gson.JsonElement;

/** A comma list is a stack of images, first on top; the builder copies and pastes it a layer at a time. */
public class ImageLayersTest {

    private static final String THREE = "grid(16, #6EDCD024), shape(\"checkmark\"),"
            + " linear-gradient(to bottom, #3574F0FF, #2E436EFF)";

    private static List<CgUiImage> layers(CgUiImage value) {
        assertTrue("expected a stack, got " + value, value instanceof CgUiImage.Layers);
        return ((CgUiImage.Layers) value).layers();
    }

    @Test
    public void aCommaListIsAStackFirstOnTop() {
        List<CgUiImage> layers = layers(ImageValue.parse(THREE));
        assertEquals(3, layers.size());
        assertTrue(layers.get(0) instanceof CgUiImage.Grid);
        assertTrue(layers.get(1) instanceof CgUiImage.Mark);
        assertTrue(layers.get(2) instanceof CgUiImage.Gradient);
        assertEquals(List.of(CgUiImage.color(0xFFFF0000), CgUiImage.color(0xFF00FF00)),
                layers(ImageValue.parse("#FF0000FF, #00FF00FF")));
    }

    @Test
    public void commasInsideAFunctionAreOneLayer() {
        assertTrue(ImageValue.parse("linear-gradient(to bottom, #3574F0FF, #2E436EFF)") instanceof CgUiImage.Gradient);
        assertTrue(ImageValue.parse("grid(16 24, #6EDCD024, 2)") instanceof CgUiImage.Grid);
        assertTrue(ImageValue.parse("rgba(0, 0, 0, 0.5)") instanceof CgUiImage.Color);
    }

    @Test
    public void oneBadLayerFailsTheDeclaration() {
        assertNull(ImageValue.parse("grid(16, #6EDCD024), sepia(0.4)"));
        assertNull(ImageValue.parse("nonsense(1), #FF0000FF"));
    }

    @Test
    public void noneIsALayer() {
        assertEquals(CgUiImage.NONE, layers(ImageValue.parse("none, #FF0000FF")).get(0));
    }

    @Test
    public void everyLayerIsItsOwnPart() {
        CgUiImage stack = ImageValue.parse(THREE);
        assertTrue(ImageParts.INSTANCE.divides(stack));
        List<StyleParts.Part> parts = ImageParts.INSTANCE.parts(stack);
        assertEquals(List.of("Grid", "Shape", "Gradient"), parts.stream().map(StyleParts.Part::group).toList());
        for (StyleParts.Part part : parts) {
            assertNotNull(part.id(), ImageParts.INSTANCE.encodePart(CgJsonOps.INSTANCE, stack, part.id()));
        }
    }

    @Test
    public void pastingOneLayerKeepsTheTargetsOthers() {
        CgUiImage source = ImageValue.parse("grid(16, #6EDCD024), shape(\"checkmark\"), #3574F0FF");
        JsonElement layer = ImageParts.INSTANCE.encodePart(CgJsonOps.INSTANCE, source, "layer-0");
        CgUiImage merged = ImageParts.INSTANCE.mergePart(CgJsonOps.INSTANCE, ImageValue.parse("#FF0000FF, #00FF00FF"),
                "layer-0", layer);
        List<CgUiImage> layers = layers(merged);
        assertEquals(2, layers.size());
        assertTrue(layers.get(0) instanceof CgUiImage.Grid);
        assertEquals(CgUiImage.color(0xFF00FF00), layers.get(1));
    }

    @Test
    public void aPastedStackWritesItselfAndReadsBack() {
        JsonElement layer = ImageParts.INSTANCE.encodePart(CgJsonOps.INSTANCE,
                ImageValue.parse("grid(16, #6EDCD024), #3574F0FF"), "layer-1");
        CgUiImage merged = ImageParts.INSTANCE.mergePart(CgJsonOps.INSTANCE, ImageValue.parse("#FF0000FF"), "layer-1",
                layer);
        String written = StylePropertyRegistry.BACKGROUND_IMAGE.write(merged);
        assertEquals("#ff0000, #3574f0", written);
        assertEquals(merged, ImageValue.parse(written));
    }

    @Test
    public void aPainterSetFromJavaDoesNotDivide() {
        CgUiImage painted = CgUiImage.painted((canvas, w, h, color) -> { });
        assertTrue("copied whole, under Appearance", !ImageParts.INSTANCE.divides(painted));
        assertTrue(!ImageParts.INSTANCE.divides(CgUiImage.layers(List.of(painted, CgUiImage.color(0xFF000000)))));
    }
}
