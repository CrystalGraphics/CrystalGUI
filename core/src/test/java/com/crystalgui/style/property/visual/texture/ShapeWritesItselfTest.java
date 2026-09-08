package com.crystalgui.style.property.visual.texture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.render.texture.CgUiShape;
import com.crystalgui.style.property.StylePropertyRegistry;

/**
 * <b>A vector mark has a CSS spelling of its own, and does not depend on being remembered.</b>
 *
 * <p>{@link TextureValue} keeps the text a drawable was parsed from in a weak map keyed by equality,
 * which is exactly right for the drawables that cannot describe themselves — a {@code CgUiSvg} holds a
 * parsed document, a {@code CgUiSprite} resolved UVs — and exactly wrong for one whose equality is
 * value-based. {@code CgUiShape} is a record, so two equal shapes share one entry keyed on whichever was
 * seen first; the codec's own write-then-read check makes a temporary, and when that temporary is
 * collected the entry goes with it. The live shape, unchanged and still on screen, then has no CSS:</p>
 *
 * <pre>
 * IllegalStateException: A CgUiShape for 'mask' was built in Java rather than parsed from CSS
 *     at TextureProperty.write · StyleValueCodecs · InlineStyleCodec.encode
 * </pre>
 *
 * <p>That reaches a person as Copy Attributes throwing on an element whose mask has been drawing
 * correctly since it was opened, and it would reach them as a document that cannot be saved.</p>
 */
public class ShapeWritesItselfTest {

    /** A shape the map has never heard of still writes — which is the reported crash, exactly. */
    @Test
    public void aShapeWithNoRememberedSourceStillWrites() {
        CgUiDrawable built = new CgUiShape(CgUiShape.Kind.CHECKMARK);
        assertEquals("shape(\"checkmark\")", StylePropertyRegistry.MASK.write(built));
    }

    /** And what it writes is what the parser reads, which is the whole of a round trip. */
    @Test
    public void andWhatItWritesParsesBackToTheSameShape() {
        for (CgUiShape.Kind kind : CgUiShape.Kind.values()) {
            String written = StylePropertyRegistry.MASK.write(new CgUiShape(kind));
            CgUiDrawable back = StylePropertyRegistry.MASK.valueParser.parse(written).compute();
            assertNotNull(kind + " wrote '" + written + "', which its own parser could not read", back);
            assertEquals(new CgUiShape(kind), back);
        }
    }

    /**
     * Every kind's name is one the catalog answers to.
     *
     * <p>{@code cssName} derives the name and {@code parseKind} switches on it, so a kind added to the
     * enum has a spelling immediately — but only this holds the derivation and the switch together.</p>
     */
    @Test
    public void everyKindsNameIsOneTheCatalogKnows() {
        for (CgUiShape.Kind kind : CgUiShape.Kind.values()) {
            assertEquals("shape(\"" + CgUiShape.cssName(kind) + "\") names no kind",
                    kind, CgUiShape.parseKind(CgUiShape.cssName(kind)));
        }
    }
}
