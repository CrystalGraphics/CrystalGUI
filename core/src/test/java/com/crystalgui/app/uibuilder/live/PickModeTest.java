package com.crystalgui.app.uibuilder.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.joml.Vector2f;
import org.junit.Test;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.app.uibuilder.BuilderSelection;
import com.crystalgui.app.uibuilder.canvas.BuilderEditor;
import com.crystalgui.core.data.DataContext;
import com.crystalgui.core.data.Transform2D;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;

import dev.vfyjxf.taffy.style.TaffyPosition;

/**
 * <b>L3.5 — the next click anywhere in the window becomes a selection.</b>
 *
 * <p>Live inspect has no canvas: the thing picked belongs to somebody else's scene, so the pick has to
 * land somewhere every inspector can reach it from — a provider on the DOCUMENT, because a data context
 * walks outward and ends at the window.</p>
 */
public class PickModeTest extends UiDocumentTestBase {

    private UIElement target;

    private UIElement open() {
        target = new UIElement().layout(l -> l.width(80).height(40));
        target.setId("target");
        document.append(target);
        document.update(W, H);
        return target;
    }

    @Test
    public void aClickPicksWhatIsUnderItAndTheWindowAnswersForIt() {
        open();
        PickMode picker = PickMode.start(document);
        assertNotNull(picker);

        clickOn(target);

        BuilderSelection selection = DataContext.from(target).get(BuilderEditor.BUILDER_SELECTION);
        assertNotNull("the window answers for the pick", selection);
        assertSame("and it is what was under the pointer", target, selection.node());
    }

    /** One picker per window, so two invocations do not fight over the same click. */
    @Test
    public void theSubjectIsOnePerDocument() {
        open();
        assertSame(LiveSubject.on(document), LiveSubject.on(document));
    }

    /** The picker takes itself off the stack once it has picked — it is a one-shot. */
    @Test
    public void pickingEndsTheMode() {
        open();
        PickMode picker = PickMode.start(document);
        assertTrue(document.input().hasMode(picker));

        clickOn(target);

        assertFalse("a picker is a one-shot", document.input().hasMode(picker));
    }

    /**
     * <b>Escape ends it, and stops eating the key.</b>
     *
     * <p>Consumed once, while the picker is up — otherwise the same Escape would also dismiss whatever is
     * behind it. What must not happen is the mode staying pushed and quietly swallowing every later
     * Escape in the window, which is why ending is the same action.</p>
     */
    @Test
    public void escapeEndsItWithoutEatingTheKeyAfterwards() {
        open();
        PickMode picker = PickMode.start(document);

        assertTrue("consumed while picking", picker.keyPressed(CgKeyCodes.KEY_ESCAPE, 0, false));
        assertFalse("and the mode is gone", document.input().hasMode(picker));
        assertFalse("so a later Escape is not eaten by it",
                picker.keyPressed(CgKeyCodes.KEY_ESCAPE, 0, false));
    }

    /** Anything but Escape is left to the window. */
    @Test
    public void otherKeysPassThrough() {
        open();
        PickMode picker = PickMode.start(document);
        assertFalse(picker.keyPressed(CgKeyCodes.KEY_A, 0, false));
    }

    @Test
    public void movingTracksWhatIsUnderThePointerWithoutConsuming() {
        open();
        PickMode picker = PickMode.start(document);
        Vector2f at = centre(target);

        assertFalse("a consumed move would freeze every :hover in the window",
                picker.pointerMoved(at.x(), at.y()));
        assertEquals(target, picker.hovered());
    }

    /**
     * <b>An unhittable layer over the whole window is picked through, not picked.</b>
     *
     * <p>The regression, and it made the picker useless rather than merely imprecise: the workbench keeps
     * a full-application {@code RegionDropOverlay} that paints nothing and is {@code hit-test: false}, so
     * a picker reaching through the flag reported that same invisible sheet wherever you clicked. Every
     * click, one answer, and nothing you were pointing at.</p>
     */
    @Test
    public void anUnhittableLayerOverEverythingIsNotWhatGetsPicked() {
        open();
        UIElement sheet = new UIElement().layout(l -> l.positionType(TaffyPosition.ABSOLUTE)
                                                       .left(0f).top(0f).width(W).height(H));
        sheet.setId("sheet");
        sheet.setHitTest(false);
        document.append(sheet);
        document.update(W, H);

        PickMode picker = PickMode.start(document);
        clickOn(target);

        BuilderSelection selection = DataContext.from(target).get(BuilderEditor.BUILDER_SELECTION);
        assertNotNull(selection);
        assertSame("the sheet is a picture, and the pick went through it",
                target, selection.node());
        assertFalse(document.input().hasMode(picker));
    }

    private void clickOn(UIElement element) {
        Vector2f at = centre(element);
        document.input().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(at.x()), Math.round(at.y()), 0, 0, CgMouseCodes.LEFT_BUTTON, true, 0f, 1L));
        frame();
        document.input().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(at.x()), Math.round(at.y()), 0, 0, CgMouseCodes.LEFT_BUTTON, false, 0f, 2L));
        frame();
    }

    private Vector2f centre(UIElement element) {
        var box = element.box();
        return Transform2D.apply(box.localToWorld(), box.width() * 0.5f, box.height() * 0.5f);
    }
}
