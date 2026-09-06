package com.crystalgui.widget.surface;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import java.util.List;

import org.joml.Vector2f;
import org.junit.Before;
import org.junit.Test;

import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.data.Transform2D;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.surface.mode.SelectExtension;

import dev.vfyjxf.taffy.style.TaffyPosition;

/**
 * <b>A surface under an application-wide overlay still takes its presses.</b>
 *
 * <p>An {@code InputMode} decides whether a press is the surface's by asking what is under the pointer,
 * and {@code Picking} resolves that with a <b>pick</b> — which reaches through {@code hit-test: false} on
 * purpose, because a design surface must see into subtrees it has deliberately made quiescent.</p>
 *
 * <p>So an unhittable layer covering the application answers that question everywhere. The workbench
 * keeps exactly one — {@code RegionDropOverlay}, which paints nothing and spans the whole window — and
 * with it above the canvas the surface declined every press: no selection, no resize, no move, while
 * wheel-zoom and middle-drag went on working because those are ordinary dispatch and dispatch honours
 * the attribute. {@code HIT_TRANSPARENT} is what such a layer says instead.</p>
 */
public class SurfaceUnderAnOverlayTest extends UiDocumentTestBase {

    private SurfaceEditor surface;
    private UIElement item;
    private UIElement sheet;

    @Before
    public void openASurfaceUnderASheet() {
        surface = new SurfaceEditor(TestSurface.policy(), List.of(SelectExtension.ID));
        UIElement root = new UIElement().layout(l -> l.width(600).height(400));
        root.append(surface);
        document.append(root);

        item = new UIElement().layout(l -> l.width(80).height(40));
        surface.surface().place(item, 20f, 20f);

        // The workbench's drop overlay, in the shape that matters: over everything, painting nothing.
        sheet = new UIElement().layout(l -> l.positionType(TaffyPosition.ABSOLUTE)
                .left(0f).top(0f).width(W).height(H));
        sheet.setHitTest(false);
        sheet.set(Attribute.HIT_TRANSPARENT, true);
        document.append(sheet);

        document.update(W, H);
        frame();
    }

    /** The press reaches the surface's tool and selects, with the sheet over the top. */
    @Test
    public void aPressUnderTheSheetStillSelects() {
        press(centre(item));
        frame();

        assertSame(item, surface.selection().items().isEmpty() ? null
                : surface.selection().items().get(0));
    }

    /**
     * And the pick — the question the mode actually asks — never answers with the sheet.
     *
     * <p>This is the assertion that fails without {@code HIT_TRANSPARENT}: {@code hit-test} alone keeps
     * the sheet out of dispatch and not out of a pick.</p>
     */
    @Test
    public void aPickUnderTheSheetAnswersWhatIsBelowIt() {
        Vector2f at = centre(item);
        UIElement found = surface.picking().elementAt(at.x(), at.y());

        assertNotNull(found);
        assertSame("the pick answered the sheet, so the surface declines every press", item, found);
    }

    private void press(Vector2f at) {
        document.input().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(at.x()), Math.round(at.y()), 0, 0, CgMouseCodes.LEFT_BUTTON, true, 0f, 1L));
    }

    private Vector2f centre(UIElement element) {
        var box = element.box();
        return Transform2D.apply(box.localToWorld(), box.width() * 0.5f, box.height() * 0.5f);
    }
}
