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
        // The user-agent sheet is NOT installed for you, and it is where every rule under test lives.
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
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

    /**
     * Both panels have a real header, and the Blackboard's is the TALLER of the two.
     *
     * <p>They shared one geometry rule until the subtitle moved onto its own line; what they still share
     * is the palette, in {@code graph.css}, which is the half that has to match. So this no longer
     * asserts equal heights — it asserts the two-line head is genuinely taller, which is the thing that
     * would silently stop being true if the stack collapsed back to one row.</p>
     */
    @Test
    public void bothPanelsHaveARealHeader() {
        mount();
        UIElement boardHead = childWithClass(board, BlackboardPanel.HEAD_CLASS);
        UIElement previewHead = childWithClass(preview, MainPreviewPanel.HEAD_CLASS);
        assertNotNull("the board must have a head", boardHead);
        assertNotNull(previewHead);
        assertTrue("neither may be zero-height", h(previewHead) > 4f && h(boardHead) > 4f);
        assertTrue("two stacked lines must be taller than one: " + h(boardHead) + " vs " + h(previewHead),
                h(boardHead) > h(previewHead));
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
     * <b>The subtitle sits UNDER the title, not beside it.</b>
     *
     * <p>Unity stacks the graph's name over its asset path so the pair reads as one identity; side by
     * side they read as two unrelated labels. Asserted as geometry — the subtitle's top must be below the
     * title's — because "is it stacked" is not answerable from structure alone once both are in a head.</p>
     */
    @Test
    public void theSubtitleIsBelowTheTitle() {
        mount();
        UIElement head = childWithClass(board, BlackboardPanel.HEAD_CLASS);
        assertNotNull(head);
        UIElement titles = childWithClass(head, BlackboardPanel.TITLES_CLASS);
        assertNotNull("the head must hold a stacked title column", titles);

        UIElement title = childWithClass(titles, BlackboardPanel.TITLE_CLASS);
        UIElement subtitle = childWithClass(titles, BlackboardPanel.SUBTITLE_CLASS);
        assertNotNull(title);
        assertNotNull(subtitle);
        assertTrue("the subtitle must start below the title, not beside it",
                subtitle.getRuntimeCache().getY() >= title.getRuntimeCache().getY()
                        + title.getRuntimeCache().getHeight() - 0.5f);
    }

    /**
     * <b>Selection rings the capsule, never the whole row.</b>
     *
     * <p>The row is the full width of the panel, so highlighting it reads as a selected table row while
     * the thing actually selected is the chip. Asserted by outline width, since that is what draws.</p>
     */
    @Test
    public void selectionRingsTheCapsuleAndNotTheRow() {
        mount();
        com.crystalgui.graph.GraphProperty added = board.addProperty("Vector 2");
        for (int pass = 0; pass < 4; pass++) window.updateWithoutPainting();

        PropertyPill pill = board.pillFor(added.id());
        assertNotNull(pill);
        assertTrue("the pill must be selected after being added", pill.isSelected());

        float rowOutline = outlineWidthOf(pill);
        float capsuleOutline = outlineWidthOf(pill.capsule());

        assertTrue("the capsule must carry the ring, but had " + capsuleOutline, capsuleOutline > 0f);
        assertEquals("and the row must not, but had " + rowOutline, 0f, rowOutline, 0.01f);
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
