package com.crystalgui.graph.shader;

import com.crystalgui.graph.GraphDocument;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.Ui;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * P6.3.14 — <b>the Blackboard is styled, and styled the same as the Main Preview.</b>
 *
 * <h3>Why this measures instead of describing</h3>
 * <p>Three rounds of "it still looks nothing like it" went by while every structural test passed, because
 * a structural test cannot see whether a rule <em>matched</em>. This asserts computed geometry against
 * the real user-agent sheet, which is the only thing that can.</p>
 */
public class BlackboardStyleTest extends UiTestBase {

    private UIWindow window;
    private BlackboardPanel board;
    private MainPreviewPanel preview;

    private void mount() {
        GraphDocument document = new GraphDocument();
        board = new BlackboardPanel(document, "test", null);
        preview = new MainPreviewPanel(document,
                com.crystalgraphics.shadergraph.CgShaderNodeRegistry.builtins(),
                new com.crystalgraphics.shadergraph.CgMasterNode());

        UIElement root = new UIElement().layout(l -> l.width(900).height(700));
        root.addChild(board);
        root.addChild(preview);

        window = new UIWindow(Ui.of(root));
        // BOTH sheets, because the panel really has both: default.css gives it geometry and graph.css
        // gives it palette AND the outline widths for hover/selected. Installing only the user-agent
        // sheet made the ring test read 0 and blame the widget for a rule it was never given -- a test
        // must stand the widget up the way its host does, or it measures a thing that does not exist.
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.getStyleEngine().addStylesheet(
                com.crystalgui.style.sheet.StyleSheetRegistry.of("crystalgui:graph"));
        window.init(900, 700);
        for (int pass = 0; pass < 8; pass++) window.updateWithoutPainting();
    }

    private static float w(UIElement e) {
        return e.getRuntimeCache().getWidth();
    }

    private static float h(UIElement e) {
        return e.getRuntimeCache().getHeight();
    }

    private static UIElement childWithClass(UIElement parent, String css) {
        for (UIElement child : parent.getChildren()) {
            if (child.hasClass(css)) return child;
        }
        return null;
    }

    // ── The frame ───────────────────────────────────────────────────────────

    /**
     * <b>The two panels are the same size, because they share one rule.</b>
     *
     * <p>If this fails at some large number, the Blackboard's rules are not matching at all and it is
     * being sized by its content — which is exactly what "looks nothing like it" looked like.</p>
     */
    @Test
    public void theBlackboardIsTheSameSizeAsTheMainPreview() {
        mount();
        assertEquals("width — if this is not 220 the shared rule is not matching",
                w(preview), w(board), 0.5f);
        assertEquals("height", h(preview), h(board), 0.5f);
        assertEquals(220f, w(board), 0.5f);
        assertEquals(236f, h(board), 0.5f);
    }

    // ── No scrollbars ───────────────────────────────────────────────────────

    /**
     * <b>The real editor builds exactly one board, with exactly one placeholder.</b>
     *
     * <p>Through {@code ShaderGraphEditor}'s own constructor and {@code addStarterGraph}, because that is
     * the sequence the harness runs: the panel refreshes once on construction and again on the document
     * change the starter graph causes. A panel built in isolation and never told anything changed cannot
     * show the bug.</p>
     *
     * <p>Deliberately NOT mounted in a window. Laying the editor out attaches the preview renderer, which
     * needs a GL context — so a test that mounted it would fail for an unrelated reason and say nothing
     * about placeholders. The construction path is what is under test here, not the layout.</p>
     */
    @Test
    public void theAssembledEditorShowsOnePlaceholder() {
        ShaderGraphEditor editor = new ShaderGraphEditor().addStarterGraph();
        BlackboardPanel board = editor.blackboard();

        UIElement body = childWithClass(board, BlackboardPanel.BODY_CLASS);
        assertNotNull("the board must have a body", body);

        int placeholders = 0;
        for (UIElement child : body.getChildren()) {
            if (child.hasClass("__empty__")) placeholders++;
        }
        assertEquals("one placeholder, not one per refresh", 1, placeholders);
    }

    /**
     * The computed outline width in pixels, which is what actually draws a ring.
     *
     * <p>{@code OUTLINE_WIDTH} is a {@code LengthPercent}, not a number — reading it as one silently
     * yields zero for every element and makes this test pass or fail for the wrong reason.</p>
     */
    private static float outlineWidthOf(UIElement element) {
        com.crystalgui.style.property.visual.border.LengthPercent width =
                element.getStyle().getComputed(
                        com.crystalgui.style.property.StylePropertyRegistry.OUTLINE_WIDTH);
        return width == null ? 0f : width.value;
    }

    /** Top-left corner radius in pixels — one corner is enough to tell applied from dropped. */
    private static float radiusOf(UIElement element) {
        com.crystalgui.style.property.visual.border.LengthPercent r = element.getStyle().getComputed(
                com.crystalgui.style.property.visual.border.BorderRadiusProperties.TOP_LEFT_X);
        return r == null ? 0f : r.value;
    }

    private static org.joml.Vector2f screenCentreOf(UIElement element) {
        var cache = element.getRuntimeCache();
        return com.crystalgui.core.data.Transform2D.apply(cache.localToWorld.get(),
                cache.getX() + cache.getWidth() / 2f, cache.getY() + cache.getHeight() / 2f);
    }

    /** A press at a point, through real hit testing. */
    private void clickAt(float x, float y) {
        window.getInputHandler().consumeMouseEvent(
                new com.crystalgraphics.platform.input.CgSystemInput.Mouse.Event(
                        Math.round(x), Math.round(y), 0, 0, 0, true, 0f, 1L));
        // The full frame pair, as ScrubUndoTest does: hover is synthesized between beginFrame and
        // endFrame, and hit testing reads that cache.
        window.updateWithoutPainting();
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
    }

    /**
     * <b>No scrollbars.</b>
     *
     * <p>Asserted as zero WIDTH rather than as a display value, because that is what the user sees. A bar
     * that is present but hidden and a bar that is absent are the same thing on screen; a bar that takes
     * 6px is not, whatever the rule says.</p>
     */
    @Test
    public void theListShowsNoScrollbars() {
        mount();
        UIElement body = childWithClass(board, BlackboardPanel.BODY_CLASS);
        assertNotNull("the board must have a body", body);

        for (UIElement child : body.getChildren()) {
            boolean bar = child.hasClass("__v-scroller__") || child.hasClass("__h-scroller__")
                    || child.hasClass("__corner__");
            if (!bar) continue;
            assertEquals("a scrollbar is still taking space: " + child.tagName(),
                    0f, w(child) * h(child), 0.01f);
        }
    }
}
