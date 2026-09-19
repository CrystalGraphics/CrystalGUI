package com.crystalgui.app.uibuilder;

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;

import org.joml.Vector3f;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgui.app.uibuilder.canvas.ResizeHandles.Spot;
import com.crystalgui.app.uibuilder.canvas.UIBuilderView;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.style.property.visual.transform.Transform;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;

/**
 * <b>A transformed element is selected and resized where it is painted</b>, as in Figma: the handles sit on its drawn
 * corners, and a drag is read along its own axes, in its own units.
 */
public class HandlesFollowTheTransformTest extends UiDocumentTestBase {

    private UiBuilderDocument model;
    private UIBuilderView editor;
    private UIElement node;
    private Disposable commands;

    @Before
    public void aScaledElementSelected() {
        UIElementRegistry.bootstrap();
        commands = BuilderCommands.register();
        model = new UiBuilderDocument(UiBuilderDocument.EMPTY.getBytes(StandardCharsets.UTF_8), "probe:h.cgui");
        node = new UIElement().layout(l -> l.width(100).height(50));
        // TWICE AS WIDE AS IT MEASURES, about its left edge, so the right edge follows the hand.
        node.generalStyle(g -> g.transform(Transform.scale(2f, 1f))
                .transformOrigin(LengthPercent.ZERO, LengthPercent.ZERO));
        model.root().append(node);
        editor = new UIBuilderView(model);
        UIElement host = new UIElement().layout(l -> l.width(600).height(400));
        host.append(editor.view());
        document.append(host);
        document.update(W, H);
        editor.selection().selectOnly(node);
        for (int i = 0; i < 4; i++) frame();
    }

    @After
    public void releaseCommands() {
        if (commands != null) commands.dispose();
    }

    private Box drawnBox() {
        return editor.shownTree().shown(node).box();
    }

    private UIElement handle(Spot spot) {
        return editor.handles().handles().get(spot.ordinal());
    }

    @Test
    public void aHandleSitsOnThePaintedEdge() {
        Box h = handle(Spot.RIGHT).box();
        Vector3f centre = h.localToWorld().transformPosition(new Vector3f(h.width() / 2f, h.height() / 2f, 0f));
        Vector3f edge = drawnBox().localToWorld().transformPosition(new Vector3f(100f, 25f, 0f));
        assertEquals("on the right edge as drawn, at twice the element's own width", edge.x, centre.x, 0.5f);
        assertEquals(edge.y, centre.y, 0.5f);
    }

    @Test
    public void aDragIsReadInTheElementsOwnUnits() {
        int[] from = centreOf(handle(Spot.RIGHT));
        float perLocalPx = drawnBox().localToWorld().m00();
        press(from[0], from[1]);
        move(from[0] + 20, from[1]);
        move(from[0] + 40, from[1]);
        frame();
        release(from[0] + 40, from[1]);
        frame();

        assertEquals("40 pointer pixels over a box drawn twice as wide is half as much width",
                100f + 40f / perLocalPx, drawnBox().width(), 1f);
    }
}
