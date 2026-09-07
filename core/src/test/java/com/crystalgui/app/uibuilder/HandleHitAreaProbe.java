package com.crystalgui.app.uibuilder;

import java.nio.charset.StandardCharsets;

import org.joml.Vector3f;
import org.junit.Test;

import com.crystalgraphics.platform.input.CgCursor;

import com.crystalgui.app.uibuilder.canvas.BuilderEditor;
import com.crystalgui.app.uibuilder.canvas.ResizeHandles;
import com.crystalgui.app.uibuilder.canvas.ResizeHandles.Spot;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;

/**
 * <b>DIAGNOSTIC — how big is a resize handle's hover area, really.</b>
 *
 * <p>Asserts nothing. The cursor over a handle comes from the CSS {@code cursor} on the handle element,
 * resolved against whatever the box tree says is under the pointer — so "the shape only changes on one
 * pixel" is a claim about a box, and this prints the box.</p>
 */
public class HandleHitAreaProbe extends UiDocumentTestBase {

    @Test
    public void printTheHandleBoxes() {
        UIElementRegistry.bootstrap();
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        UiBuilderDocument model = new UiBuilderDocument(
                UiBuilderDocument.EMPTY.getBytes(StandardCharsets.UTF_8), "probe:h.cgui");
        UIElement node = new UIElement().layout(l -> l.width(120).height(60));
        model.root().append(node);

        BuilderEditor editor = new BuilderEditor(model);
        UIElement host = new UIElement().layout(l -> l.width(500).height(400));
        host.append(editor.view());
        document.append(host);
        document.update(W, H);
        editor.selection().selectOnly(node);
        for (int i = 0; i < 4; i++) document.frame(0.016f, W, H);

        ResizeHandles handles = editor.handles();
        System.out.println("[handle-area] displayed=" + handles.isDisplayed()
                + " target=" + handles.target());
        for (int i = 0; i < handles.handles().size(); i++) {
            UIElement handle = handles.handles().get(i);
            Box box = handle.box();
            CgCursor cursor = handle.computedStyle().get(StylePropertyRegistry.CURSOR);
            if (box == null) {
                System.out.println("[handle-area] " + Spot.values()[i] + " NO BOX cursor=" + cursor);
                continue;
            }
            Vector3f origin = box.localToWorld().transformPosition(new Vector3f(0f, 0f, 0f));
            Vector3f far = box.localToWorld()
                    .transformPosition(new Vector3f(box.width(), box.height(), 0f));
            System.out.println("[handle-area] " + Spot.values()[i]
                    + " layout=" + box.width() + "x" + box.height()
                    + " world=(" + origin.x + "," + origin.y + ")-(" + far.x + "," + far.y + ")"
                    + " cursor=" + cursor
                    + " picked=" + pickedAt(origin.x + 0.5f, origin.y + 0.5f, handle)
                    + " pickedCentre=" + pickedAt((origin.x + far.x) / 2f, (origin.y + far.y) / 2f,
                            handle));
        }
    }

    private String pickedAt(float x, float y, UIElement expected) {
        Box hit = document.boxes().pick(x, y, ignored -> false);
        if (hit == null) return "nothing";
        return hit.node() == expected ? "SELF" : String.valueOf(hit.node());
    }
}
