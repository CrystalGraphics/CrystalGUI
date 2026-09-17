package com.crystalgui.app.uibuilder.style;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.ConfigControl;
import com.crystalgui.widget.config.Configurator;
import com.crystalgui.widget.config.ValueControl;

/**
 * <b>The transform lab edits the picked op with the gesture its kind wants, in the unit the file spells.</b>
 *
 * <p>Both halves were wrong: every op got the same pad and dial, so a scale or a shear had no control that would
 * write it, and the dial read an angle's number without its unit — the engine writes {@code rotate(0.78rad)}, which
 * the dial showed as 0.78 degrees and could only write back as degrees.</p>
 */
public class TransformLabTest extends UiDocumentTestBase {

    private Property<String> open(String css) {
        return open(css, null, null);
    }

    private Property<String> open(String css, UIElement node, StyleFields fields) {
        Property<String> value = Property.of(css);
        UIElement anchor = new UIElement().layout(l -> l.width(40).height(16));
        document.append(anchor);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        frame();
        TransformLab.open(anchor, StylePropertyRegistry.TRANSFORM, value, true, node, fields);
        for (int i = 0; i < 4; i++) frame();
        return value;
    }

    @Test
    public void anAngleReadsAsDegreesWhateverItIsSpelledIn() {
        open("rotate(0.7853982rad)");
        assertEquals("a quarter turn is 45 degrees", 45d, (Double) read("lab.rotate"), 0.05d);
    }

    @Test
    public void theDialWritesDegrees() {
        Property<String> css = open("rotate(0deg)");
        write("lab.rotate", 90d);
        frame();
        assertEquals("rotate(90deg)", css.get());
    }

    @Test
    public void aScaleIsEditedByItsOwnRows() {
        Property<String> css = open("scale(1)");
        assertFalse("the pad edits a translate, so a scale is not offered one", shown("lab.translate"));
        assertTrue("and has factors instead", shown("lab.scale"));

        write("lab.scale", 2d);
        frame();
        assertEquals("both axes, in the one spelling CSS has for it", "scale(2)", css.get());
    }

    @Test
    public void anUnlinkedScaleWritesEachAxis() {
        Property<String> css = open("scale(1, 2)");
        assertFalse("a scale whose axes differ opens per axis", shown("lab.scale"));
        assertTrue(shown("lab.scale.x"));

        write("lab.scale.x", 3d);
        frame();
        assertEquals("scale(3, 2)", css.get());
    }

    @Test
    public void aTranslateIsEditedOnThePad() {
        Property<String> css = open("translate(4px, 2px)");
        assertTrue(shown("lab.translate"));
        assertFalse("and is not offered the factors", shown("lab.scale"));
        assertArrayEquals(new double[] {4d, 2d}, (double[]) read("lab.translate"), 0.01d);

        write("lab.translate", new double[] {10d, -6d});
        frame();
        assertEquals("translate(10px, -6px)", css.get());
    }

    @Test
    public void aShearIsAnAngleToo() {
        Property<String> css = open("skew(0.5rad, 0rad)");
        assertEquals(28.65d, (Double) read("lab.skew.x"), 0.05d);

        write("lab.skew.x", 10d);
        frame();
        assertEquals("skew(10deg, 0deg)", css.get());
    }

    /** A value with no gesture is still the file's, so it is edited as the text it is rather than left alone. */
    @Test
    public void anOpWithNoGestureOfItsOwnIsEditedAsText() {
        open("matrix(1, 0, 0, 1, 0, 0)");
        assertTrue("the function's own text", shown("lab.op"));
        assertFalse(shown("lab.translate"));
        assertFalse(shown("lab.rotate"));
    }

    /**
     * <b>The pivot is edited here too</b>: it is a declaration of its own, and the point every op turns about is not
     * a thing anybody pictures from {@code transform-origin-x: 50%}. It is a mark ON the specimen, and a percentage
     * of the box when written -- which is what the canvas's own free transform writes.
     */
    @Test
    public void thePivotIsAMarkOnTheSpecimenAndAPercentageOfTheBox() {
        UIElement node = new UIElement().layout(l -> l.width(80).height(40));
        document.append(node);
        LiveEdits.setInline(node, StylePropertyRegistry.TRANSFORM_ORIGIN_X, "25%");
        StyleFields fields = StyleFields.on(null, StyleTarget.inline(), node);
        open("rotate(0deg)", node, fields);

        assertNotNull("the pivot is drawn on the specimen", pivot());
        assertEquals("a quarter along", 25d, (Double) read("lab.origin.x"), 0.05d);
        assertEquals("and halfway down by default", 50d, (Double) read("lab.origin.y"), 0.05d);

        write("lab.origin.y", 100d);
        frame();
        // AS A READER SEES IT: what lands is the property's own spelling of the percentage, `100.0%`.
        assertEquals("100%", CssValues.readable(fields.valueOf("transform-origin-y")));
        assertEquals("the other axis is left alone", "25%",
                CssValues.readable(fields.valueOf("transform-origin-x")));
    }

    private UIElement pivot() {
        for (UIElement each : document.composedSubtree()) {
            if (each.hasClass(TransformLab.PIVOT_CLASS)) return each;
        }
        return null;
    }

    /** An origin row is the transform lab's row, so the two are never edited two different ways. */
    @Test
    public void anOriginRowOpensTheTransformLab() {
        StyleLabs.register();
        for (StyleProperty<?> origin : new StyleProperty<?>[] {StylePropertyRegistry.TRANSFORM_ORIGIN_X,
                StylePropertyRegistry.TRANSFORM_ORIGIN_Y}) {
            DeclarationEditors.Field field =
                    DeclarationEditors.of(origin, "style." + origin.name, origin.name, Property.of("50%"));
            assertTrue(origin.name + " is a lab row", field.control() instanceof StyleChip);
        }
    }

    private ConfigControl control(String id) {
        for (UIElement each : document.composedSubtree()) {
            if (each instanceof ConfigControl found && id.equals(found.descriptor().id())) return found;
        }
        return null;
    }

    private Object read(String id) {
        ConfigControl control = control(id);
        assertNotNull("the lab has a " + id + " row", control);
        return ((ValueControl<?>) control).getValueObject();
    }

    @SuppressWarnings("unchecked")
    private void write(String id, Object value) {
        ConfigControl control = control(id);
        assertNotNull("the lab has a " + id + " row", control);
        ((ValueControl<Object>) control).setValue(value);
    }

    /** Whether the row holding {@code id} is shown, which is how the lab offers one kind's controls and not another's. */
    private boolean shown(String id) {
        ConfigControl control = control(id);
        assertNotNull("the lab has a " + id + " row", control);
        for (UIElement at = (UIElement) control; at != null; at = at.parentElement()) {
            if (at instanceof Configurator row) return !Boolean.TRUE.equals(row.get(Attribute.HIDDEN));
        }
        throw new AssertionError(id + " is not in a row");
    }
}
