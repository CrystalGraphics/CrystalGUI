package com.crystalgui.headless;

import com.crystalgui.net.mirror.UIElementMirror;
import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.serialization.JsonOps;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.control.TextField;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * {@code line-height}, {@code caret-width} and {@code selection-color} — three values that used to be
 * private constants in {@code TextField}, so no theme could touch the caret or the selection
 * highlight. The green {@code 0x803C8527} in particular was CrystalGUI's own accent leaking into
 * every theme that used a text field.
 *
 * <p>This half covers what a <b>server</b> can do with them: read defaults, and serialize authored
 * values to a client. The CSS-facing half lives in {@code TextStylePropertiesCssTest} in the normal
 * test set, because {@code StyleSheet} cannot be class-loaded without CrystalGraphics — its
 * {@code DEFAULT} field reads {@code default.css} at class-init. That split is the documented
 * boundary, not an accident.</p>
 */
public class TextStylePropertiesTest {

    /** The guard for the whole change: defaults must equal the constants they replaced. */
    @Test
    public void defaultsMatchTheConstantsTheyReplaced() {
        var style = new TextField().getStyle().getGeneralGroup();
        // `normal`, asserted as the sentinel rather than a number precisely because this set has no
        // CrystalGraphics: `normal` means "ask the font", and that reading it here does NOT try to is
        // the property under test. The sentinel becomes pixels only in TextField.paintOverlay.
        assertTrue("line-height defaults to `normal`", Float.isNaN(style.lineHeight()));
        assertEquals("was CARET_WIDTH", 1f, style.caretWidth(), 0.0001f);
        assertEquals("was SELECTION_ARGB", 0x803C8527, style.selectionColor());
    }

    @Test
    public void allThreeAreMarkedInheritable() {
        assertTrue(StylePropertyRegistry.LINE_HEIGHT.isInheritable());
        assertTrue(StylePropertyRegistry.CARET_WIDTH.isInheritable());
        assertTrue(StylePropertyRegistry.SELECTION_COLOR.isInheritable());
    }

    /** Inheritance itself, without going near a stylesheet. */
    @Test
    public void aChildSeesAnAncestorsValues() {
        UIElement root = new UIElement();
        TextField field = new TextField();
        root.append(field);

        root.getStyle().getGeneralGroup().lineHeight(2f).caretWidth(3f).selectionColor(0xFF00FF00);

        var child = field.getStyle().getGeneralGroup();
        assertEquals(2f, child.lineHeight(), 0.0001f);
        assertEquals(3f, child.caretWidth(), 0.0001f);
        assertEquals(0xFF00FF00, child.selectionColor());
    }

    /** An element's own value wins over an inherited one. */
    @Test
    public void anOwnValueBeatsAnInheritedOne() {
        UIElement root = new UIElement();
        TextField field = new TextField();
        root.append(field);

        root.getStyle().getGeneralGroup().caretWidth(3f);
        field.getStyle().getGeneralGroup().caretWidth(7f);

        assertEquals(7f, field.getStyle().getGeneralGroup().caretWidth(), 0.0001f);
    }

    // ── Transitions come free from the property types ───────────────────────

    /** {@code FloatProperty}/{@code ColorProperty} set an interpolator in their constructors, so
     * {@code transition: selection-color 200ms} works with no extra wiring. */
    @Test
    public void allThreeCanAnimate() {
        assertTrue(StylePropertyRegistry.LINE_HEIGHT.isAllowTransition());
        assertTrue(StylePropertyRegistry.CARET_WIDTH.isAllowTransition());
        assertTrue("a colour that snapped instead of blending would be a mis-declared property — the "
                        + "create(String, int) overload would have made this an IntProperty",
                StylePropertyRegistry.SELECTION_COLOR.isAllowTransition());
        assertEquals("and it must blend channel-wise, not numerically", 0xFF808080,
                (int) StylePropertyRegistry.SELECTION_COLOR.getInterpolator()
                        .interpolate(0xFF000000, 0xFFFFFFFF, 0.5f));
    }

    // ── Serialization ───────────────────────────────────────────────────────

    /** Float- and Integer-valued properties are already in StyleValueCodecs, so these travel free. */
    @Test
    public void allThreeRoundTripToAClient() {
        UIElement element = new UIElement();
        element.getStyle().getGeneralGroup()
                .lineHeight(1.75f)
                .caretWidth(4f)
                .selectionColor(0x40FF00FF);

        for (DynamicOps<?> ops : new DynamicOps<?>[]{JsonOps.INSTANCE, PlainOps.INSTANCE}) {
            var style = roundTrip(element, ops).getStyle().getGeneralGroup();
            assertEquals(1.75f, style.lineHeight(), 0.0001f);
            assertEquals(4f, style.caretWidth(), 0.0001f);
            assertEquals(0x40FF00FF, style.selectionColor());
        }
    }

    private static <T> UIElement roundTrip(UIElement source, DynamicOps<T> ops) {
        return new UIElementMirror<>(ops).decode(new UIElementMirror<>(ops).describe(source));
    }

    /** A widget that never had them set must not carry them over the wire. */
    @Test
    public void defaultsAreNotSent() {
        JsonObject encoded = new UIElementMirror<>(JsonOps.INSTANCE)
                .describe(new TextField()).getAsJsonObject();
        assertFalse("an unstyled field should carry no style block at all", encoded.has("style"));
    }
}
