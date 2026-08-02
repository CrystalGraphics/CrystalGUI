package com.crystalgui.ui;

import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.ColorSelector;
import com.crystalgui.ui.elements.Dialog;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The picker must lay out identically wherever it is presented.
 *
 * <p>The Color node shows it inside a {@link Dialog} that is promoted to the top layer by hand, which is
 * a different containing block, a different Taffy parent and a different transform chain from the gallery's
 * in-flow dialog. Every one of those is a chance for a percentage to resolve against a different box —
 * and the picker places its ring handle and its SV square with percentages, so a divergence shows up as a
 * wheel whose parts are the wrong size or off centre rather than as anything that fails loudly.</p>
 */
public class PickerInPromotedDialogTest extends UiTestBase {

    private static final float EPS = 0.01f;

    private record Box(float x, float y, float w, float h) {
        @Override public String toString() {
            return String.format("(%.2f, %.2f) %.2fx%.2f", x, y, w, h);
        }
    }

    private static Box boxOf(UIElement e) {
        var c = e.getRuntimeCache();
        return new Box(c.getX(), c.getY(), c.getWidth(), c.getHeight());
    }

    private static Box relative(UIElement child, UIElement parent) {
        Box c = boxOf(child), p = boxOf(parent);
        return new Box(c.x() - p.x(), c.y() - p.y(), c.w(), c.h());
    }

    /** Lays out a picker in a dialog, optionally promoted, and returns the ring and square geometry. */
    private Box[] layout(boolean promote) {
        UIElement root = new UIElement();
        UIWindow window = new UIWindow(Ui.of(root));
        window.init(600, 600);

        Dialog dialog = new Dialog("Color");
        ColorSelector picker = new ColorSelector();
        dialog.getContent().addChild(picker);
        root.addChild(dialog);
        dialog.show();
        if (promote) window.getTopLayer().add(dialog);
        window.updateWithoutPainting();

        UIElement ring = picker.querySelectorAll("." + ColorSelector.RING_CLASS).get(0);
        UIElement square = picker.querySelectorAll("." + ColorSelector.SQUARE_CLASS).get(0);
        return new Box[] { boxOf(picker), relative(ring, picker), relative(square, ring) };
    }

    @Test
    public void promotingTheDialogDoesNotChangeThePickerGeometry() {
        Box[] inFlow = layout(false);
        Box[] promoted = layout(true);

        assertEquals("picker box: " + inFlow[0] + " vs " + promoted[0],
                inFlow[0].w(), promoted[0].w(), EPS);
        assertEquals("ring box: " + inFlow[1] + " vs " + promoted[1],
                inFlow[1].w(), promoted[1].w(), EPS);
        assertEquals("ring height: " + inFlow[1] + " vs " + promoted[1],
                inFlow[1].h(), promoted[1].h(), EPS);
        assertEquals("square within ring: " + inFlow[2] + " vs " + promoted[2],
                inFlow[2].w(), promoted[2].w(), EPS);
        assertEquals("square offset in ring: " + inFlow[2] + " vs " + promoted[2],
                inFlow[2].x(), promoted[2].x(), EPS);
    }

    /**
     * The picker inside a node's control row must size its channel rows exactly as it does anywhere else.
     *
     * <p>Promotion moves an element's Taffy parent and its transform but NOT its DOM parent, so
     * {@code graphnode .__control-row__ textfield { width: 0; flex-grow: 1 }} reaches every field in the
     * picker. It ties with the picker's own rule on specificity, so the WIDTH is won on source order and
     * everything reads correctly — while the grow factor goes uncontested and the fields quietly eat the
     * slack the colour tracks need. Nothing fails; the numbers in the sheet stay right; only the picture
     * is wrong.</p>
     */
    @Test
    public void aPickerInsideANodeControlRowKeepsItsFieldWidths() {
        UIElement root = new UIElement();
        UIWindow window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(com.crystalgui.style.sheet.StyleSheet.DEFAULT);
        window.init(600, 600);

        var node = new com.crystalgui.ui.elements.graph.GraphNode("Color");
        UIElement swatch = new UIElement();
        swatch.addClass(com.crystalgui.ui.elements.graph.GraphNode.FULL_WIDTH_CLASS);
        node.addControl("", swatch);
        root.addChild(node);

        Dialog dialog = new Dialog("Color");
        ColorSelector picker = new ColorSelector();
        dialog.getContent().addChild(picker);
        swatch.addChild(dialog);
        dialog.show();
        window.getTopLayer().add(dialog);
        window.updateWithoutPainting();

        UIElement field = picker.querySelectorAll("." + ColorSelector.CHANNEL_ROW_CLASS + " textfield").get(0);
        UIElement track = picker.querySelectorAll("." + ColorSelector.CHANNEL_ROW_CLASS + " slider").get(0);

        assertEquals("the node's control-row rule must not inflate the picker's value fields",
                22f, boxOf(field).w(), 0.5f);
        assertTrue("the colour track must keep the row's slack, was " + boxOf(track),
                boxOf(track).w() > boxOf(field).w() * 2f);
    }

    /** The ring must be SQUARE, or the shader normalises a non-square box into an ellipse. */
    @Test
    public void theRingStaysSquareWhenPromoted() {
        Box ring = layout(true)[1];
        assertEquals("a non-square ring box draws the hue band as an ellipse: " + ring,
                ring.w(), ring.h(), EPS);
    }
}
