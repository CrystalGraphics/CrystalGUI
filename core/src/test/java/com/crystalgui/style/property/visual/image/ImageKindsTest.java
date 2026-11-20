package com.crystalgui.style.property.visual.image;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.crystalgui.render.image.CgUiImage;
import com.crystalgui.render.image.CgUiMark;
import com.crystalgui.serialization.style.ImageParts;
import com.crystalgui.serialization.style.StyleParts;
import com.crystalgui.style.property.StylePropertyRegistry;

/** The CSS functions an image is written with: each a section of the builder's copy, extensible by registering. */
public class ImageKindsTest {

    private static List<String> partIds() {
        List<String> ids = new ArrayList<>();
        for (StyleParts.Part part : ImageParts.INSTANCE.parts()) ids.add(part.id());
        return ids;
    }

    @Test
    public void everyKindIsAPartAndEveryPartAKind() {
        List<String> kinds = new ArrayList<>();
        for (ImageKinds.Kind kind : ImageKinds.all()) kinds.add(kind.id());
        assertEquals(kinds, partIds());
    }

    @Test
    public void aRegisteredKindParsesAndHasItsOwnSection() {
        ImageKinds.register(ImageKinds.Kind.function("test-swatch", "Test Swatch", "test-swatch",
                args -> CgUiImage.color(0xFF123456)));
        try {
            assertEquals(CgUiImage.color(0xFF123456), ImageKinds.parse("test-swatch(anything)"));
            assertEquals("Test Swatch", ImageKinds.matching("test-swatch(anything)").label());
            assertTrue(partIds().contains("test-swatch"));
        } finally {
            // Process-wide: a fixture left in would show in every other test
            ImageKinds.unregister("test-swatch");
        }
    }

    @Test
    public void noneIsNotAKind() {
        assertNull(ImageKinds.matching("none"));
        assertNull(ImageKinds.matching("empty"));
    }

    @Test
    public void theFunctionNameIsCaseInsensitive() {
        assertEquals("gradient", ImageKinds.matching("LINEAR-GRADIENT(#000000FF, #FFFFFFFF)").id());
        assertEquals("color", ImageKinds.matching("#AABBCCDD").id());
    }

    @Test
    public void everyMarkBuiltInJavaWritesWhatReadsBack() {
        assertEquals("shape(\"checkmark\")", StylePropertyRegistry.MASK.write(CgUiImage.mark(CgUiMark.CHECKMARK)));
        for (CgUiMark mark : CgUiMark.values()) {
            String written = StylePropertyRegistry.MASK.write(CgUiImage.mark(mark));
            CgUiImage back = ImageValue.parse(written);
            assertNotNull(mark + " wrote '" + written + "'", back);
            assertEquals(CgUiImage.mark(mark), back);
        }
    }
}
