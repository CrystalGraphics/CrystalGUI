package com.crystalgui.app.uibuilder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.joml.Vector2f;
import org.junit.Before;
import org.junit.Test;

import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.app.uibuilder.canvas.BuilderEditor;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.core.data.DataContext;
import com.crystalgui.core.data.Transform2D;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;

/**
 * <b>L4.4 — design mode, and a click that selects.</b>
 *
 * <p>The whole design/preview switch is one attribute. {@code hit-test: false} on the artboard makes
 * every widget in the document quiescent — the engine never looks inside — while the builder still picks
 * through it, because {@code Picking} resolves with a pick rather than a hit test. Preview clears the
 * attribute and pops the mode, and the same tree is simply used.</p>
 */
public class DesignModeAndSelectionTest extends UiDocumentTestBase {

    private static final String SOURCE = "{\n"
            + "  \"cgui\": 1,\n"
            + "  \"root\": { \"kind\": \"element\", \"id\": \"root\",\n"
            + "    \"children\": [\n"
            + "      { \"kind\": \"text\", \"id\": \"first\", \"state\": { \"text\": \"one\" } },\n"
            + "      { \"kind\": \"text\", \"id\": \"second\", \"state\": { \"text\": \"two\" } }\n"
            + "    ] }\n"
            + "}\n";

    private BuilderEditor editor;
    private UIElement first;
    private UIElement second;

    @Before
    public void openTheDocument() {
        UIElementRegistry.bootstrap();
        editor = new BuilderEditor(new UiBuilderDocument(
                SOURCE.getBytes(StandardCharsets.UTF_8), "test:page"));
        UIElement root = new UIElement().layout(l -> l.width(800).height(500));
        root.append(editor.view());
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        document.update(W, H);
        frame();

        first = editor.document().root().children().get(0);
        second = editor.document().root().children().get(1);
    }

    /** A builder opens in design mode, and design mode IS the attribute. */
    @Test
    public void aBuilderOpensWithTheDocumentQuiescent() {
        assertTrue(editor.surface().isDesignMode());
        assertTrue("the artboard is unhittable, so nothing under it sees input",
                editor.artboard().isDesignMode());
    }

    /** <b>The point of L4.4.</b> A press on the canvas selects the node under it. */
    @Test
    public void aClickOnTheCanvasSelectsTheNodeUnderIt() {
        clickOn(second);

        assertEquals(1, editor.selection().size());
        assertSame(second, editor.selection().node());
    }

    /** And the inspector reads it, which is the only reason a selection is worth having. */
    @Test
    public void theInspectorSeesWhatTheCanvasSelected() {
        clickOn(first);

        BuilderSelection seen =
                DataContext.from(editor.view()).get(BuilderEditor.BUILDER_SELECTION);
        assertSame(first, seen.node());
    }

    /**
     * <b>Both directions.</b> The hierarchy writes the builder's selection and the canvas must follow.
     *
     * <p>Two selections exist because they answer different questions — the engine moves a set of items
     * and knows nothing about rules or tokens — and they are one selection to everybody who reads them.
     * </p>
     */
    @Test
    public void selectingThroughTheBuilderReachesTheEngine() {
        editor.selection().selectOnly(second);

        assertTrue("the engine's item set followed", editor.surface().selection().contains(second));
        assertEquals(1, editor.surface().selection().size());
    }

    /** And a canvas click reaches the builder's, without the two answering each other forever. */
    @Test
    public void theBridgeSettlesRatherThanEchoing() {
        editor.surface().selection().selectOnly(first);

        assertSame(first, editor.selection().node());
        assertEquals(1, editor.surface().selection().size());
        assertEquals(1, editor.selection().size());
    }

    /** Preview hands the document back its input. */
    @Test
    public void previewMakesTheDocumentLiveAgain() {
        editor.surface().setDesignMode(false);

        assertFalse(editor.artboard().isDesignMode());
        assertFalse("and the surface's mode is off the stack, or it would swallow every press",
                document.input().modes().stream()
                        .anyMatch(mode -> "surface".equals(mode.name())));
    }

    /** Switching back re-arms both halves. */
    @Test
    public void designModeComesBack() {
        editor.surface().setDesignMode(false);
        editor.surface().setDesignMode(true);

        assertTrue(editor.artboard().isDesignMode());
        assertTrue(document.input().modes().stream()
                .anyMatch(mode -> "surface".equals(mode.name())));
    }

    /**
     * <b>Selecting twice.</b> The regression that made the canvas unusable after one click.
     *
     * <p>The handle layer appears as soon as anything is selected. Full-size and hittable, it became the
     * answer to every hit test that landed on background — so the first click selected and no click
     * after it did anything at all. It is zero-sized now, and its handles sit outside it.</p>
     */
    @Test
    public void aSecondClickSelectsSomethingElse() {
        clickOn(first);
        assertSame(first, editor.selection().node());

        clickOn(second);
        assertSame("the handles must not stand between the pointer and the canvas",
                second, editor.selection().node());
    }

    /** And the handle layer itself is never what a pick answers. */
    @Test
    public void theHandleLayerIsNeverTheHitTarget() {
        clickOn(first);
        frame();

        Vector2f at = centre(second);
        assertFalse("a point over the canvas resolved to the handle layer",
                editor.handles().contains(document.input().hoverTarget()));
    }

    /**
     * <b>The outlines are actually up.</b>
     *
     * <p>{@code visibleByDefault()} was a declaration nothing acted on — nobody called
     * {@code OverlayLayer.showDefaults()}, so the overlay was never built and selection happened with
     * nothing on screen to show it. Which is indistinguishable from selection not happening.</p>
     */
    @Test
    public void theCanvasOverlaysAreShowingFromTheStart() {
        assertTrue("hover outline", editor.surface().overlays()
                .isShowing(BuilderOverlaysExtension.HOVER));
        assertTrue("selection outline", editor.surface().overlays()
                .isShowing(BuilderOverlaysExtension.SELECTION));
    }

    /** And preview takes them down, along with the handles — none of it is part of the UI being used. */
    @Test
    public void previewTakesTheDesignChromeDown() {
        clickOn(first);
        assertTrue(editor.handles().isDisplayed());

        editor.surface().setDesignMode(false);

        assertFalse(editor.surface().overlays().isShowing(BuilderOverlaysExtension.HOVER));
        assertFalse(editor.surface().overlays().isShowing(BuilderOverlaysExtension.SELECTION));
        assertFalse("and the handles are not left over a live UI", editor.handles().isDisplayed());
    }

    /**
     * <b>The page is not a node.</b>
     *
     * <p>The artboard is placed on the plane like anything else, so a marquee — which is what a press on
     * empty canvas starts — caught it, and clicking anywhere blank put eight resize handles on the page
     * frame while the inspector described the frame instead of the document. {@code TreePolicy} has always
     * answered null for it; the marquee was reading the plane's children directly.</p>
     */
    @Test
    public void clickingEmptyCanvasNeverSelectsTheArtboard() {
        Box board = editor.artboard().box();
        // Well inside the page and clear of both text nodes, which sit at its top.
        Vector2f blank = Transform2D.apply(board.localToWorld(),
                board.width() * 0.5f, board.height() * 0.8f);

        pressAt(blank);
        frame();
        releaseAt(blank);
        frame();

        assertFalse("the page frame is not something an edit can act on",
                editor.selection().contains(editor.artboard()));
        assertFalse(editor.surface().selection().contains(editor.artboard()));
    }

    /** And a marquee across the whole page catches the document's nodes, not the page. */
    @Test
    public void aMarqueeCatchesNodesAndNotTheArtboard() {
        Box board = editor.artboard().box();
        Vector2f from = Transform2D.apply(board.localToWorld(), 2f, 2f);
        Vector2f to = Transform2D.apply(board.localToWorld(),
                board.width() - 2f, board.height() - 2f);

        pressAt(from);
        frame();
        document.input().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(to.x()), Math.round(to.y()), 0, 0, -1, false, 0f, -1L));
        frame();
        releaseAt(to);
        frame();

        assertFalse("the page was caught by its own marquee",
                editor.surface().selection().contains(editor.artboard()));
    }

    /**
     * <b>A drag never marks a document node.</b>
     *
     * <p>The engine's move gesture marks what it is moving with a class for the duration. A node inside a
     * UI document is placed by its parent, so there is no plane-move for it — but the gesture was
     * engaged anyway and left {@code __moving__} behind, which is a class on a node of the document:
     * encoded into the file, listed in the inspector, and enough to mark the tab dirty.</p>
     */
    @Test
    public void draggingANodeDoesNotWriteADesignClassIntoTheDocument() {
        Vector2f at = centre(first);
        pressAt(at);
        frame();

        // DURING the press, which is when the mark goes on. Asserting after the release passes either
        // way, because the gesture takes its own mark off when it ends -- so the leak is only visible
        // for a drag that never ends cleanly, which is exactly how it was reported.
        assertTrue("the press marked a node the file will be written from: " + first.classes(),
                first.classes().isEmpty());

        releaseAt(at);
        frame();
        assertTrue(first.classes().isEmpty());
        assertFalse("and nothing was recorded to undo", editor.document().history().canUndo());
    }

    private void pressAt(Vector2f at) {
        document.input().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(at.x()), Math.round(at.y()), 0, 0, CgMouseCodes.LEFT_BUTTON, true, 0f, 1L));
    }

    private void releaseAt(Vector2f at) {
        document.input().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(at.x()), Math.round(at.y()), 0, 0, CgMouseCodes.LEFT_BUTTON, false, 0f, 2L));
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
