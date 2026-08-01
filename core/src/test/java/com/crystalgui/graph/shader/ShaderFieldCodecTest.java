package com.crystalgui.graph.shader;

import com.crystalgui.graph.NodeField;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.Ui;
import com.crystalgui.ui.elements.config.control.ColorControl;
import com.crystalgui.ui.elements.config.control.VectorControl;
import com.crystalgui.ui.elements.graph.NodeFieldWidgets;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * <b>The GLSL codec, exercised end to end.</b>
 *
 * <p>Since P6.1.8 step 7, {@code NodeFieldWidgets} no longer has any built-in notion of what a colour
 * or a vector literal looks like — {@link ShaderColorFieldWidget} and {@link ShaderVectorFieldWidget}
 * carry that alone, and a codec that silently mis-parses is worse than one that is missing: the field
 * looks editable, and every value it writes back is wrong. Asserted here rather than trusted, because
 * neither had a test before this class existed — the only prior coverage was
 * {@code NodeControlKitTest}'s height check, which never looked at what a control actually reported.</p>
 */
public class ShaderFieldCodecTest extends UiTestBase {

    @Test
    public void colorRoundTripsThroughVec4() {
        int argb = 0xFF3C8CFF;
        String literal = ShaderColorFieldWidget.formatVec4(argb);
        assertEquals(argb, ShaderColorFieldWidget.parseVec4(literal));
    }

    @Test
    public void colorParsesAOneArgumentBroadcastLikeGlslDoes() {
        // vec4(1.0) is opaque white in GLSL, not opaque red — every component takes the one value given.
        assertEquals(0xFFFFFFFF, ShaderColorFieldWidget.parseVec4("vec4(1.0)"));
    }

    @Test
    public void colorFallsBackToOpaqueWhiteOnGarbage() {
        assertEquals("a malformed literal must not throw, and must not open on black",
                0xFFFFFFFF, ShaderColorFieldWidget.parseVec4("not a vec4"));
    }

    @Test
    public void vectorRoundTripsThroughVecN() {
        double[] values = { 0.25, -1.5 };
        String literal = ShaderVectorFieldWidget.format(values);
        assertArrayEquals(values, ShaderVectorFieldWidget.parse(literal), 0.001);
    }

    @Test
    public void vectorArityComesFromTheLiteralItself() {
        // NodeField carries no arity of its own — see ShaderVectorFieldWidget's class javadoc — so a
        // vec3 literal must still parse to three components rather than being clipped to two.
        assertArrayEquals(new double[] { 1, 2, 3 }, ShaderVectorFieldWidget.parse("vec3(1, 2, 3)"), 0.001);
    }

    @Test
    public void vectorFallsBackToATwoComponentZeroOnGarbage() {
        assertArrayEquals(new double[] { 0, 0 }, ShaderVectorFieldWidget.parse("garbage"), 0.001);
    }

    /**
     * The field the whole codec exists for: a user drags/types into the control and the STRING
     * {@code NodeFieldBinder} would write is the correct GLSL literal — not merely that parsing in
     * isolation works.
     */
    @Test
    public void editingTheColorControlWritesBackTheGlslLiteral() {
        // Explicit, rather than relying on another test class having already called it — the registry
        // is a shared static map, and a test that depended on execution order would pass or fail by
        // luck rather than by what it actually asserts.
        ShaderColorFieldWidget.install();

        UIElement root = new UIElement();
        UIWindow window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(400, 300);

        NodeField field = NodeField.color("tint", "Tint", "vec4(1.000, 1.000, 1.000, 1.000)");
        String[] written = { null };
        UIElement widget = NodeFieldWidgets.create(field, field.defaultValue(), v -> written[0] = v);
        assertTrue(widget instanceof ColorControl);
        root.addChild(widget);
        window.updateWithoutPainting();

        ((ColorControl) widget).picker().onColorChanged.emit(0xFF00FF00);
        assertEquals("vec4(0.000, 1.000, 0.000, 1.000)", written[0]);
    }

    @Test
    public void editingTheVectorControlWritesBackTheGlslLiteral() {
        ShaderVectorFieldWidget.install();

        UIElement root = new UIElement();
        UIWindow window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(400, 300);

        NodeField field = new NodeField("uv", "UV", NodeField.Kind.VECTOR,
                java.util.List.of(), "vec2(0.000, 0.000)", null);
        String[] written = { null };
        UIElement widget = NodeFieldWidgets.create(field, field.defaultValue(), v -> written[0] = v);
        assertTrue(widget instanceof VectorControl);
        root.addChild(widget);
        window.updateWithoutPainting();

        ((VectorControl) widget).components().get(0).field().setText("2");
        assertEquals("vec2(2.000, 0.000)", written[0]);
    }
}
