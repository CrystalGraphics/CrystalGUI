package com.crystalgui.app.uibuilder.style;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import dev.vfyjxf.taffy.style.TaffyPosition;

import com.crystalgraphics.platform.input.CgMouseCodes;

import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.service.AnchoredPlacement;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.canvas.CanvasView;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.widget.overlay.Dropdown;
import com.crystalgui.widget.overlay.MenuItem;
import com.crystalgui.widget.overlay.Dialog;

/**
 * <b>A lab takes clicks.</b>
 *
 * <p>Every gizmo in a lab is a widget in a popover, which is the whole point of a popover — so this asserts
 * the boring thing: a press where a control is drawn reaches that control.</p>
 */
public class StyleLabClicksTest extends UiDocumentTestBase {

    private Property<String> css;
    private UIElement anchor;

    @Before
    public void openALab() {
        css = Property.of("linear-gradient(180deg, #6AA9FF, #8A8AFF 50%, #C86AFF)");
        anchor = new UIElement().layout(l -> l.width(40).height(16));
        document.append(anchor);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        document.update(W, H);
        frame();

        GradientLab.open(anchor, StylePropertyRegistry.BACKGROUND, css);
        for (int i = 0; i < 6; i++) frame();
    }

    /** The button has a box where it is drawn, and a press there is a press on it. */
    @Test
    public void aPressOnAKeywordReachesIt() {
        Button keyword = keyword("Remove stop");
        assertNotNull("the lab built its keyword buttons", keyword);

        Box box = keyword.box();
        assertNotNull("and they are laid out", box);
        assertTrue("with a size", box.width() > 0f && box.height() > 0f);

        float[] centre = worldCentre(box);
        click(centre[0], centre[1]);
        frame();

        assertEquals("the press changed the value", "linear-gradient(180deg, #8A8AFF 50%, #C86AFF)", css.get());
    }

    /**
     * <b>Choosing from a dropdown leaves the lab open.</b>
     *
     * <p>A pick that ADDS a declaration moves the sheet's brace and semicolon count, which is exactly what
     * the Styles tab watches to decide its row list must be rebuilt — and the row it rebuilds is the one
     * the lab is anchored to. A slider never trips it: changing a value moves no punctuation.</p>
     */
    @Test
    public void choosingFromADropdownLeavesTheLabOpen() {
        UIElement node = new UIElement();
        node.addClass("card");
        document.append(node);
        TextBuffer sheet = new TextBuffer("""
                .card {
                    opacity: 0.5;
                }
                """);
        SheetDocuments sheets = SheetFixture.install(document, sheet, "menu.css");
        document.update(W, H);
        frame();

        StyleTarget rule = null;
        for (StyleTarget target : StyleTargets.of(node, sheets).targets()) {
            if (!target.isInline()) rule = target;
        }
        assertNotNull("the sheet's rule is a target", rule);

        StyleFields fields = StyleFields.on(null, rule, node);
        TypographyLab.open(node, fields, StylePropertyRegistry.FONT_WEIGHT, fields.value("font-weight"), node);
        for (int i = 0; i < 6; i++) frame();

        Dialog lab = labTitled("Type");
        assertNotNull("the lab is up", lab);
        assertTrue(lab.isOpen());

        Dropdown dropdown = first(lab, Dropdown.class);
        assertNotNull("the lab built its dropdowns", dropdown);
        clickCentre(dropdown.box());
        for (int i = 0; i < 4; i++) frame();
        assertTrue("opening the menu left the lab alone", lab.isOpen());

        MenuItem item = first(document, MenuItem.class);
        assertNotNull("the menu is open", item);
        clickCentre(item.box());
        for (int i = 0; i < 6; i++) frame();

        assertTrue("the lab is still open", lab.isOpen());
        assertTrue("and the lab is still in the tree", lab.isConnected());
    }

    /**
     * <b>Choosing another layer leaves the lab open.</b>
     *
     * <p>The stack rebuilds its rows when one is selected, so the row that was pressed is detached while
     * the press is still being delivered. Light dismiss then asked a node with no parent which popover it
     * was inside, got "none", and shut the lab as though the press had landed somewhere else.</p>
     */
    @Test
    public void choosingAnotherLayerLeavesTheLabOpen() {
        Property<String> shadows = Property.of("#000000FF 0px 1px 2px, #FFFFFFFF 0px 2px 4px");
        ShadowLab.open(anchor, StylePropertyRegistry.TEXT_SHADOW, shadows);
        for (int i = 0; i < 6; i++) frame();

        Dialog lab = labTitled("Shadow");
        assertNotNull("the shadow lab is up", lab);
        assertTrue(lab.isOpen());

        List<UIElement> rows = new ArrayList<>();
        for (UIElement each : lab.composedSubtree()) {
            if (each.hasClass(LayerStack.ROW_CLASS) && each.box() != null) rows.add(each);
        }
        assertEquals("one row per shadow", 2, rows.size());

        clickCentre(rows.get(1).box());
        for (int i = 0; i < 6; i++) frame();
        assertTrue("the lab is still open", lab.isOpen());
    }

    /**
     * <b>The preview is one canvas, and both things it offers do something.</b>
     *
     * <p>A ground pick and a right-click reset are each one listener, which is the shape of thing in this
     * panel that has repeatedly been built, looked right and done nothing.</p>
     */
    @Test
    public void theStagePicksItsGroundAndRightClickSendsItHome() {
        Dialog lab = labTitled("Gradient");
        assertNotNull("the gradient lab is up", lab);

        CanvasView stage = first(lab, CanvasView.class);
        assertNotNull("the preview is a canvas", stage);
        assertTrue("which opens on the dark plate", stage.hasClass(StyleLab.DARK_CLASS));

        UIElement light = null;
        for (UIElement each : lab.composedSubtree()) {
            if (each.hasClass(StyleLab.PICK_CLASS) && each.hasClass(StyleLab.LIGHT_CLASS)) light = each;
        }
        assertNotNull("with a pick per plate", light);
        clickCentre(light.box());
        for (int i = 0; i < 4; i++) frame();
        assertTrue("the pick changed the ground", stage.hasClass(StyleLab.LIGHT_CLASS));
        assertFalse("and took the other one off", stage.hasClass(StyleLab.DARK_CLASS));

        stage.setZoom(4f).setPan(30f, 40f);
        frame();
        float[] at = worldCentre(stage.box());
        press(at[0], at[1], CgMouseCodes.RIGHT_BUTTON);
        frame();
        release(at[0], at[1], CgMouseCodes.RIGHT_BUTTON);
        for (int i = 0; i < 4; i++) frame();
        assertEquals("right-click is home", 1f, stage.getZoom(), 1e-6);
        assertEquals(0f, stage.getPanX(), 1e-6);
        assertEquals(0f, stage.getPanY(), 1e-6);
    }

    /**
     * <b>A layer can be taken off its own row.</b>
     *
     * <p>The reorder and remove controls were built all along and drawn at {@code opacity: 0}: the rule
     * that reveals them names an ancestor carrying both {@code __configurator__} and {@code __style-row__},
     * which is an inspector declaration row and not a lab's layer row. They held their space and could not
     * be seen, so a stack of five shadows had no way to become four.</p>
     */
    @Test
    public void aLayerCanBeRemovedFromItsRow() {
        Property<String> shadows = Property.of(
                "#000000FF 0px 1px 2px, #FFFFFFFF 0px 2px 4px, #FF0000FF 0px 3px 6px");
        ShadowLab.open(anchor, StylePropertyRegistry.TEXT_SHADOW, shadows);
        for (int i = 0; i < 6; i++) frame();

        Dialog lab = labTitled("Shadow");
        assertNotNull(lab);
        List<UIElement> rows = new ArrayList<>();
        for (UIElement each : lab.composedSubtree()) {
            if (each.hasClass(LayerStack.ROW_CLASS) && each.box() != null) rows.add(each);
        }
        assertEquals("one row per shadow", 3, rows.size());

        Button remove = null;
        for (UIElement each : rows.get(1).composedSubtree()) {
            if (each instanceof Button button && "\u00d7".equals(button.getText())) remove = button;
        }
        assertNotNull("the row carries a remove control", remove);
        assertNotNull("with a box to press", remove.box());

        clickCentre(remove.box());
        for (int i = 0; i < 6; i++) frame();

        assertEquals("the layer is gone", 2, CssValues.layers(shadows.get()).size());
        assertFalse("and it is the one that was asked for", shadows.get().contains("#FFFFFF"));
    }

    /**
     * <b>A lab is not dismissed by looking away from it.</b>
     *
     * <p>Which is the whole reason it stopped being a popover: a popover closes on any press outside, and
     * this is a panel you tune a value in while watching the element it changes. No dismissal rule fixes
     * that, because closing on an outside press is what a popover IS.</p>
     */
    @Test
    public void aLabStaysOpenWhenSomethingElseIsPressed() {
        Dialog lab = labTitled("Gradient");
        assertNotNull(lab);
        assertTrue(lab.isOpen());

        click(2f, 2f);
        for (int i = 0; i < 6; i++) frame();
        assertTrue("still open", lab.isOpen());
        assertTrue("and still in the tree", lab.isConnected());
    }

    /** One at a time: nothing dismisses a dialog, so a second row would otherwise stack a second lab. */
    @Test
    public void openingAnotherLabClosesTheOne() {
        Dialog gradient = labTitled("Gradient");
        assertNotNull(gradient);

        ShadowLab.open(anchor, StylePropertyRegistry.TEXT_SHADOW, Property.of("0 1px 2px #000000"));
        for (int i = 0; i < 6; i++) frame();

        assertNotNull("the new lab is up", labTitled("Shadow"));
        // isOpen, not "still in the tree": removeWhenClosed runs after the close animation.
        assertFalse("and the one it replaced is closed", gradient.isOpen());
    }

    /**
     * <b>A lab opened from a row at the edge stays on screen.</b>
     *
     * <p>Placed by hand off the anchor's world matrix it did not: a world coordinate carries
     * {@code uiScale} while {@code left}/{@code top} are logical, and nothing flipped it to the other
     * side or clamped it to the window, so a lab opened from the inspector's own rows went off the
     * bottom-right corner. {@link AnchoredPlacement} is the one definition of both.</p>
     */
    @Test
    public void aLabOpenedFromTheEdgeStaysOnScreen() {
        UIElement edge = new UIElement().layout(l -> l.positionType(TaffyPosition.ABSOLUTE)
                .left(W - 60f).top(H - 60f).width(40).height(16));
        document.append(edge);
        document.update(W, H);
        frame();

        ShadowLab.open(edge, StylePropertyRegistry.TEXT_SHADOW, Property.of("0 1px 2px #000000"));
        for (int i = 0; i < 8; i++) frame();

        Dialog lab = labTitled("Shadow");
        assertNotNull("the lab is up", lab);
        Box box = lab.box();
        assertNotNull("and laid out", box);
        assertTrue("its left edge is on screen", box.x() >= 0f);
        assertTrue("its top edge is on screen", box.y() >= 0f);
        assertTrue("its right edge is too: " + (box.x() + box.width()), box.x() + box.width() <= W + 0.5f);
        assertTrue("and its bottom: " + (box.y() + box.height()), box.y() + box.height() <= H + 0.5f);
    }

    private void clickCentre(Box box) {
        assertNotNull(box);
        float[] at = worldCentre(box);
        click(at[0], at[1]);
    }

    /** By its title: the fixture opens a gradient lab of its own, so "a lab" is ambiguous here. */
    private Dialog labTitled(String title) {
        for (UIElement each : document.composedSubtree()) {
            if (each instanceof Dialog lab && lab.hasClass(StyleLab.LAB_CLASS)
                    && title.equals(lab.getTitle())) {
                return lab;
            }
        }
        return null;
    }

    private static <T extends UIElement> T first(UIElement root, Class<T> kind) {
        for (UIElement each : root.composedSubtree()) {
            if (kind.isInstance(each) && each.box() != null) return kind.cast(each);
        }
        return null;
    }

    private static float[] worldCentre(Box box) {
        return new float[]{box.localToWorld().m30() + box.width() / 2f,
                box.localToWorld().m31() + box.height() / 2f};
    }

    private Button keyword(String text) {
        for (UIElement each : document.composedSubtree()) {
            if (each instanceof Button button && text.equals(button.getText())) return button;
        }
        return null;
    }
}
