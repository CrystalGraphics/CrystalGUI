package com.crystalgui.app.uibuilder;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

import com.crystalgui.app.uibuilder.canvas.Artboard;
import com.crystalgui.app.uibuilder.canvas.TreePolicy;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.widget.control.Checkbox;
import com.crystalgui.widget.control.Slider;
import com.crystalgui.widget.surface.SurfacePolicy;

/**
 * <b>A widget on the canvas answers for its own insides.</b>
 *
 * <p>The policy walked up LIGHT parents, and a widget's parts are a shadow tree whose root has no light
 * parent by design — so the walk from a slider's track reached the shadow root and stopped. The slider
 * was hoverable over its own padding and dead over every part of it that draws; a checkbox answered down
 * its left and right margins only, which reads as hit-testing being off by a few pixels rather than as a
 * walk that cannot leave a shadow tree.</p>
 */
public class HoverReachesInsideAWidgetTest extends UiDocumentTestBase {

    @Test
    public void aPartAnswersAsTheWidgetItBelongsTo() {
        UIElementRegistry.bootstrap();
        UiBuilderDocument model = new UiBuilderDocument(
                UiBuilderDocument.EMPTY.getBytes(StandardCharsets.UTF_8), "probe:x.cgui");
        Artboard board = new Artboard(model);
        TreePolicy policy = new TreePolicy(board);

        Slider slider = new Slider();
        Checkbox checkbox = new Checkbox();
        model.root().append(slider, checkbox);
        document.append(board);
        document.update(W, H);

        assertSame("the widget itself was already right", slider, policy.itemFor(slider));
        assertSame("a part of it must answer as the widget", slider, policy.itemFor(partOf(slider)));
        assertSame("...and so must a checkbox's mark", checkbox, policy.itemFor(partOf(checkbox)));
    }

    /**
     * <b>And the press over that part belongs to the surface.</b>
     *
     * <p>{@code itemFor} and {@code ownerOf} answer about the same hit and have to agree. While only the
     * first crossed the shadow boundary, the outline followed the pointer over a widget and clicking
     * there selected nothing — which is the most broken-looking state the canvas can be in.</p>
     */
    @Test
    public void aPressOverAPartBelongsToTheSurface() {
        UIElementRegistry.bootstrap();
        UiBuilderDocument model = new UiBuilderDocument(
                UiBuilderDocument.EMPTY.getBytes(StandardCharsets.UTF_8), "probe:x.cgui");
        Artboard board = new Artboard(model);
        TreePolicy policy = new TreePolicy(board);

        Slider slider = new Slider();
        model.root().append(slider);
        document.append(board);
        document.update(W, H);

        assertSame("a press on a widget's own box was already the surface's",
                SurfacePolicy.PressOwner.SURFACE, policy.ownerOf(slider));
        assertSame("a press on its track is the surface's too",
                SurfacePolicy.PressOwner.SURFACE, policy.ownerOf(partOf(slider)));
        // BLANK PAGE STAYS THE SURFACE'S: it is how you deselect, and where a marquee starts.
        assertSame(SurfacePolicy.PressOwner.SURFACE, policy.ownerOf(board));
        // And something off the board entirely is still the tree's.
        assertSame(SurfacePolicy.PressOwner.TREE, policy.ownerOf(new UIElement()));
    }

    /** The deepest thing a pointer could land on inside a widget — what the box tree hands back. */
    private static UIElement partOf(UIElement widget) {
        assertNotNull("the widget has no shadow tree to reach into", widget.shadowRoot());
        UIElement at = widget.shadowRoot().children().get(0);
        while (!at.composedChildren().isEmpty()) at = at.composedChildren().get(0);
        return at;
    }
}
