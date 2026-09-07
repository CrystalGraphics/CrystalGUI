package com.crystalgui.workbench;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.render.texture.CgUiQuad;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.control.Checkbox;
import com.crystalgui.widget.overlay.Dialog;

/**
 * <b>A workbench control is an outline — and an outline still has to answer the pointer.</b>
 *
 * <p>{@code workbench button.__labelled__} strips the engine's fill so a toolbar reads as chrome. It is
 * a compound of two types and a class, so it outweighs {@code button:hover} — which means the rule that
 * removed the fill also removed every hover in the shell, in dialogs as much as in toolbars.</p>
 */
public class WorkbenchControlHoverTest extends UiDocumentTestBase {

    private Button control;

    @Before
    public void aLabelledButtonInTheShell() {
        UIElementRegistry.bootstrap();
        withDefaultStyles();
        UIElement shell = new UIElement(Workbench.NAME).layout(l -> l.width(200f).height(60f));
        control = new Button("Apply");
        shell.append(control);
        document.append(shell);
        document.update(W, H);
    }

    /** The outline treatment itself: no fill while the pointer is elsewhere. */
    @Test
    public void aRestingControlIsAnOutline() {
        assertEquals("a workbench control sits on the panel's own surface", 0, alpha(backgroundOf(control)));
    }

    /**
     * The defect, and it reaches every labelled button in the shell — a toolbar's, a dialog's Apply and
     * Cancel. Hovering one has to change something, or the row is a picture.
     */
    @Test
    public void aHoveredControlLightsUp() {
        int resting = backgroundOf(control);
        control.setHovered(true);
        document.update(W, H);

        int hovered = backgroundOf(control);
        assertNotEquals("hovering has to show", resting, hovered);
        assertTrue("and it has to be visible, not a transparent nudge", alpha(hovered) > 0x40);
    }

    /** A press is a further step, so it must not land on the same colour as the hover it came from. */
    @Test
    public void aPressedControlGoesFurtherThanTheHover() {
        control.setHovered(true);
        document.update(W, H);
        int hovered = backgroundOf(control);

        control.setPressed(true);
        document.update(W, H);
        assertNotEquals("a press has to read as one", hovered, backgroundOf(control));
    }

    /**
     * <b>A dialog is dragged, so it floats free of whatever raised it — by joining the top layer, not by
     * being re-parented.</b>
     *
     * <p>The distinction is the whole design. Promotion moves the containing block to the document, so the
     * dialog clamps to the screen the way a {@code WindowFrame} does; the LIGHT tree is untouched, so the
     * cascade still sees it inside the shell and the shell's own control styling reaches its buttons.</p>
     */
    @Test
    public void aDialogFloatsFreeOfThePanelThatRaisedIt() {
        UIElement shell = new UIElement(Workbench.NAME).layout(l -> l.width(600f).height(400f));
        UIElement pane = new UIElement().layout(l -> l.width(200f).height(100f));
        shell.append(pane);
        document.append(shell);
        document.update(W, H);

        Dialog dialog = new Dialog("Paste Attributes");
        Button ok = new Button("Apply");
        dialog.getContent().append(ok);
        document.addOverlay(dialog, pane);
        dialog.show();
        document.update(W, H);

        assertTrue("it has to leave the pane's containing block to escape it",
                document.isPromoted(dialog));
        assertEquals("and it is still a descendant of the shell, so the shell's sheet reaches it",
                0, alpha(backgroundOf(ok)));

        dialog.moveTo(320f, 240f);
        document.update(W, H);
        assertTrue("the pane is 200x100, so this is only reachable if the pane is not the limit",
                dialog.box().x() > 200f);

        // CLOSING DOES NOT DEMOTE, deliberately: the dialog has to stay hosted where it is for the
        // length of its fade, or it drops back into the flow of the panel that raised it on the very
        // frame the close begins and is clipped away — which reads as vanishing rather than fading.
        // A closed dialog costs nothing there (`display: none`, so no box), and the detach path is what
        // demotes for real. @see Dialog#close
        dialog.close();
        document.update(W, H);
        assertTrue("it stays hosted while it fades", document.isPromoted(dialog));

        dialog.removeSelf();
        document.update(W, H);
        assertTrue("and leaving the tree is what gives the layer back",
                !document.isPromoted(dialog));
    }

    /**
     * <b>A checkbox mark is the same control at 12px</b> — an empty face with a border, ticked or not.
     *
     * <p>The state rides on the TICK, not on the face. Filling the mark green turns a column of rows
     * into a column of status lights; a green checkmark in the same box the unticked ones have says
     * only the thing that changed.</p>
     */
    @Test
    public void tickingACheckboxColoursTheMarkAndNotTheBox() {
        Checkbox box = checkboxInAShell();
        UIElement mark = markOf(box);
        assertEquals("unticked, it is an outline like the buttons beside it", 0, alpha(backgroundOf(mark)));

        box.setChecked(true);
        document.update(W, H);

        assertEquals("the face does not fill", 0, alpha(backgroundOf(markOf(box))));
        assertTrue("the tick does, and shape() takes the element's own colour",
                isGreen(markOf(box).getStyle().getGeneralGroup().color()));
    }

    /**
     * <b>The ordering claim, which is the part that breaks silently.</b>
     *
     * <p>{@code :checked} and {@code :hover} are the same weight, so the later rule wins the tie. The
     * checked rule has to come FIRST — it only has to beat the base sheet's green fill — or a ticked box
     * would stop lighting up under the pointer, and only while the pointer is on it.</p>
     */
    @Test
    public void aTickedCheckboxStillLightsUpUnderThePointer() {
        Checkbox box = checkboxInAShell();
        box.setChecked(true);
        document.update(W, H);

        box.setHovered(true);
        document.update(W, H);

        assertTrue("the hover fill still reaches a ticked box",
                alpha(backgroundOf(markOf(box))) > 0x40);
        assertTrue("and the tick is still green under it",
                isGreen(markOf(box).getStyle().getGeneralGroup().color()));
    }

    private Checkbox checkboxInAShell() {
        UIElement shell = new UIElement(Workbench.NAME).layout(l -> l.width(200f).height(60f));
        Checkbox box = new Checkbox("Wrap");
        shell.append(box);
        document.append(shell);
        document.update(W, H);
        return box;
    }

    /** Green dominant — what separates the tick from any neutral colour, at any palette. */
    private static boolean isGreen(int argb) {
        int red = (argb >> 16) & 0xFF, green = (argb >> 8) & 0xFF, blue = argb & 0xFF;
        return green > red + 0x20 && green > blue + 0x20;
    }

    /**
     * <b>A detach is not a close</b> — minimising a window with a dialog open, then restoring it.
     *
     * <p>{@code hide()} removes the frame from the tree and the dialog goes with it. {@code UINode}
     * demotes anything leaving, because it cannot tell a hide from a close; the dialog can, so it
     * re-promotes on the way back. Without that it returned in ordinary flow — confined to the panel it
     * was raised from, which is the whole thing promotion exists to prevent — and with its clamp hook
     * dropped by {@code Animation.forget}, so nothing re-placed it either.</p>
     */
    @Test
    public void aDialogSurvivesItsWindowBeingHiddenAndShownAgain() {
        UIElement layer = new UIElement().layout(l -> l.width(600f).height(400f));
        UIElement frame = new UIElement().layout(l -> l.width(300f).height(200f));
        layer.append(frame);
        document.append(layer);
        document.update(W, H);

        Dialog dialog = new Dialog("Paste Attributes");
        dialog.layout(l -> l.width(120f).height(80f));
        dialog.getTitleBar().layout(l -> l.height(16f));
        document.addOverlay(dialog, frame);
        dialog.show();
        document.update(W, H);
        assertTrue(document.isPromoted(dialog));

        // What minimise does: the frame leaves the tree, carrying the dialog with it.
        layer.remove(frame);
        document.update(W, H);
        assertTrue("nothing detached stays in the top layer", !document.isPromoted(dialog));

        layer.append(frame);
        document.update(W, H);

        assertTrue("and coming back restores it", document.isPromoted(dialog));
        dialog.moveTo(400f, 300f);
        frame();
        assertTrue("so it is free of the 300x200 frame again, not confined to it",
                dialog.box().x() > 300f);
    }

    /** The mark is a shadow part, so a light-tree query cannot reach it. */
    private static UIElement markOf(Checkbox box) {
        for (UINode node : box.composedSubtree()) {
            if (node instanceof UIElement element
                    && Checkbox.MARK_PART.equals(element.get(Attribute.PART))) {
                return element;
            }
        }
        throw new AssertionError("no mark part on " + box);
    }

    private static int alpha(int argb) {
        return (argb >>> 24) & 0xFF;
    }

    private static int backgroundOf(UIElement node) {
        CgUiDrawable drawable = node.getStyle().getGeneralGroup().background();
        assertTrue("expected a flat fill, got " + drawable, drawable instanceof CgUiQuad);
        return ((CgUiQuad) drawable).getColorArgb();
    }
}
