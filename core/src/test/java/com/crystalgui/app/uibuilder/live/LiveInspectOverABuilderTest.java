package com.crystalgui.app.uibuilder.live;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.nio.charset.StandardCharsets;

import org.joml.Vector2f;
import org.junit.Before;
import org.junit.Test;

import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.app.uibuilder.BuilderSelection;
import com.crystalgui.app.uibuilder.canvas.BuilderEditor;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.core.data.DataContext;
import com.crystalgui.core.data.Transform2D;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;

/**
 * <b>Live inspect, with a {@code .cgui} in front.</b>
 *
 * <p>The inspector's source is the ACTIVE EDITOR'S VIEW — {@code InspectorExtension.seed} — which for a
 * {@code .cgui} is the builder's own surface. A {@code DataContext} walks outward and stops at the first
 * non-null answer, so the surface answering an empty {@link BuilderSelection} shadowed the document-level
 * {@link LiveSubject} outright: every pick reported nothing, for exactly the file type live inspect is
 * for. The surface stays silent while it has nothing to say.</p>
 */
public class LiveInspectOverABuilderTest extends UiDocumentTestBase {

    private static final String SOURCE = "{\n"
            + "  \"cgui\": 1,\n"
            + "  \"root\": { \"kind\": \"element\", \"id\": \"root\",\n"
            + "    \"children\": [ { \"kind\": \"text\", \"id\": \"title\","
            + " \"state\": { \"text\": \"bao\" } } ] }\n"
            + "}\n";

    private BuilderEditor editor;
    private UIElement elsewhere;

    @Before
    public void openTheDocument() {
        UIElementRegistry.bootstrap();
        editor = new BuilderEditor(new UiBuilderDocument(
                SOURCE.getBytes(StandardCharsets.UTF_8), "test:page"));
        UIElement root = new UIElement().layout(l -> l.width(800).height(400));
        root.append(editor.view());
        document.append(root);

        // Something outside the builder to pick, as a taskbar entry or a dialog would be.
        elsewhere = new UIElement().layout(l -> l.width(80).height(40));
        elsewhere.setId("elsewhere");
        document.append(elsewhere);
        document.update(W, H);
        frame();
    }

    /** What the inspector reads, asked exactly as the inspector asks it. */
    private BuilderSelection asTheInspectorAsks() {
        return DataContext.from(editor.view()).get(BuilderEditor.BUILDER_SELECTION);
    }

    /** <b>The report.</b> A pick lands where the inspector will look for it. */
    @Test
    public void aPickReachesTheInspectorWhileABuilderIsInFront() {
        // The text INSIDE the document's root, not the root: a pick answers the deepest thing under the
        // pointer, which is the node somebody clicking the word means.
        UIElement target = editor.artboard().children().get(0).children().get(0);

        PickMode.start(document);
        clickOn(target);

        BuilderSelection seen = asTheInspectorAsks();
        assertNotNull("the inspector found no selection at all", seen);
        assertSame("and it is what was picked", target, seen.node());
    }

    /** And so does a pick that lands outside the builder, which is the general live-inspect case. */
    @Test
    public void aPickOutsideTheBuilderReachesItToo() {
        PickMode.start(document);
        clickOn(elsewhere);

        BuilderSelection seen = asTheInspectorAsks();
        assertNotNull(seen);
        assertSame(elsewhere, seen.node());
    }

    /**
     * <b>The builder's own selection still wins once it has one.</b>
     *
     * <p>Silence is "I have nothing to say", not "ask someone else instead" — a surface that has
     * genuinely selected something must not be overridden by a stale live pick.</p>
     */
    @Test
    public void theBuildersOwnSelectionWinsWhenItHasOne() {
        UIElement mine = editor.artboard();
        editor.selection().selectOnly(mine);

        LiveSubject.on(document).pick(elsewhere);

        assertSame("the surface's own answer, not the document's",
                mine, asTheInspectorAsks().node());
    }

    /** With nothing selected anywhere, the question genuinely has no answer. */
    @Test
    public void nothingSelectedAnywhereAnswersNothing() {
        assertNull(asTheInspectorAsks());
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
