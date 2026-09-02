package com.crystalgui.app.shadergraph.blackboard;

import com.crystalgui.app.shadergraph.ShaderGraphEditor;
import com.crystalgui.app.shadergraph.preview.MainPreviewPanel;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.graph.GraphDocument;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.dom.UIDocument;
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
public class BlackboardStyleTest extends UiDocumentTestBase {

    private BlackboardPanel board;
    private MainPreviewPanel preview;

    private void mount() {
        GraphDocument graphDocument = new GraphDocument();
        board = new BlackboardPanel(graphDocument, "test", null);
        preview = new MainPreviewPanel(graphDocument,
                com.crystalgraphics.shadergraph.CgShaderNodeRegistry.builtins(),
                new com.crystalgraphics.shadergraph.CgMasterNode());

        UINode root = new UINode().layout(l -> l.width(900).height(700));
        root.append(board);
        root.append(preview);

        document.append(root);
        // BOTH sheets, because the panel really has both: default.css gives it geometry and graph.css
        // gives it palette AND the outline widths for hover/selected. Installing only the user-agent
        // sheet made the ring test read 0 and blame the widget for a rule it was never given -- a test
        // must stand the widget up the way its host does, or it measures a thing that does not exist.
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        document.styleEngine().addStylesheet(
                com.crystalgui.style.sheet.StyleSheetRegistry.of("crystalgui:graph"));
        for (int pass = 0; pass < 8; pass++) frame();
    }

    private static float w(UINode e) {
        return e.box().width();
    }

    private static float h(UINode e) {
        return e.box().height();
    }

    private static UINode childWithClass(UINode parent, String css) {
        for (UINode child : parent.children()) {
            if (child.hasClass(css)) return child;
        }
        return null;
    }

    // ── No scrollbars ───────────────────────────────────────────────────────

    /**
     * <b>The real editor builds exactly one board, with exactly one placeholder.</b>
     *
     * <p>Through {@code ShaderGraphEditor}'s own constructor and {@code addStarterGraph}, because that is
     * the sequence the harness runs: the panel refreshes once on construction and again on the graphDocument
     * change the starter graph causes. A panel built in isolation and never told anything changed cannot
     * show the bug.</p>
     *
     * <p>Deliberately NOT mounted in a document. Laying the editor out attaches the preview renderer, which
     * needs a GL context — so a test that mounted it would fail for an unrelated reason and say nothing
     * about placeholders. The construction path is what is under test here, not the layout.</p>
     */
    @Test
    public void theAssembledEditorShowsOnePlaceholder() {
        ShaderGraphEditor editor = new ShaderGraphEditor().addStarterGraph();
        BlackboardPanel board = editor.blackboard();

        UINode body = childWithClass(board, BlackboardPanel.BODY_CLASS);
        assertNotNull("the board must have a body", body);

        int placeholders = 0;
        for (UINode child : body.children()) {
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
    private static float outlineWidthOf(UINode element) {
        com.crystalgui.style.property.visual.border.LengthPercent width =
                element.getStyle().getComputed(
                        com.crystalgui.style.property.StylePropertyRegistry.OUTLINE_WIDTH);
        return width == null ? 0f : width.value;
    }

    /** Top-left corner radius in pixels — one corner is enough to tell applied from dropped. */
    private static float radiusOf(UINode element) {
        com.crystalgui.style.property.visual.border.LengthPercent r = element.getStyle().getComputed(
                com.crystalgui.style.property.visual.border.BorderRadiusProperties.TOP_LEFT_X);
        return r == null ? 0f : r.value;
    }

    private static org.joml.Vector2f screenCentreOf(UINode element) {
        var cache = element.box();
        return com.crystalgui.core.data.Transform2D.apply(cache.localToWorld(),
                cache.width() / 2f, cache.height() / 2f);
    }

    /** A press at a point, through real hit testing. */
    private void clickAt(float x, float y) {
        document.input().consumeMouseEvent(
                new com.crystalgraphics.platform.input.CgSystemInput.Mouse.Event(
                        Math.round(x), Math.round(y), 0, 0, 0, true, 0f, 1L));
        // The full frame pair, as ScrubUndoTest does: hover is synthesized between beginFrame and
        // endFrame, and hit testing reads that cache.
        frame();
        frame();
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
        UINode body = childWithClass(board, BlackboardPanel.BODY_CLASS);
        assertNotNull("the board must have a body", body);

        for (UINode child : body.children()) {
            boolean bar = child.hasClass("__v-scroller__") || child.hasClass("__h-scroller__")
                    || child.hasClass("__corner__");
            if (!bar) continue;
            assertEquals("a scrollbar is still taking space: " + child.tagName(),
                    0f, w(child) * h(child), 0.01f);
        }
    }
}
