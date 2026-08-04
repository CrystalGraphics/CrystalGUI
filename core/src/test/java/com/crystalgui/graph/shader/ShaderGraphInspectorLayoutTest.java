package com.crystalgui.graph.shader;

import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.Ui;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.graph.GraphNode;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * P6.3.13 — <b>the inspector has to have a size.</b>
 *
 * <h3>What this pins, and why it needed its own file</h3>
 * <p>{@code ShaderNodeInspectorTest} asserts the panel builds the right rows, and it passed while the
 * inspector rendered as an empty rectangle in the dock. Both were true at once: the rows existed, were
 * bound, and were laid out at <b>zero height</b>.</p>
 *
 * <p>The cause is the load-bearing rule that a widget's cascade identity is its <b>tag</b>, never its
 * Java supertype. {@code default.css} sizes the inspector with a tag rule, and a new element's tag is its
 * lowercased class name — so {@code ShaderGraphInspector} and {@code ShaderGraphSettingsPanel} matched
 * nothing at all and inherited no size. It is the same way {@code Dropdown extends Button} laid out at
 * zero height until {@code default.css} named it.</p>
 *
 * <p>A test that only asserts on structure cannot see this, which is exactly why it is worth having a
 * separate one that asserts on geometry.</p>
 */
public class ShaderGraphInspectorLayoutTest extends UiTestBase {

    private static final float PANE_W = 320f;
    private static final float PANE_H = 520f;

    private ShaderGraphEditor editor;
    private ShaderGraphInspector inspector;
    private UIWindow window;

    /** The inspector inside a fixed box, which is what a dock pane is. */
    private void mount() {
        editor = new ShaderGraphEditor().addStarterGraph();
        inspector = new ShaderGraphInspector(editor);

        UIElement pane = new UIElement().layout(l -> l.width(PANE_W).height(PANE_H));
        pane.addChild(inspector);

        UIElement root = new UIElement().layout(l -> l.width(900).height(600));
        root.addChild(pane);

        window = new UIWindow(Ui.of(root));
        // The user-agent sheet is NOT installed for you, and it is where every size in this test comes
        // from. Without it this passes for the wrong reason by exercising no CSS at all.
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(900, 600);
        settle();
    }

    private void settle() {
        for (int pass = 0; pass < 8; pass++) window.updateWithoutPainting();
    }

    private static float height(UIElement element) {
        return element.getRuntimeCache().getHeight();
    }

    private static float width(UIElement element) {
        return element.getRuntimeCache().getWidth();
    }

    // ── The frame ───────────────────────────────────────────────────────────

    /** The inspector fills the pane it was put in. */
    @Test
    public void theInspectorFillsItsPane() {
        mount();
        assertEquals("width", PANE_W, width(inspector), 0.5f);
        assertEquals("height", PANE_H, height(inspector), 0.5f);
    }

    /** The tab strip is not the whole panel — the panes take the rest. */
    @Test
    public void theTabsFillTheInspector() {
        mount();
        assertEquals(PANE_H, height(inspector.tabs()), 0.5f);
        assertTrue("the strip must not eat the panel", height(inspector.tabs().strip()) < PANE_H / 2f);
    }

    // ── The tabs' contents ──────────────────────────────────────────────────

    /**
     * <b>The node tab has real height.</b>
     *
     * <p>The failure this exists for: every row present and correct, and none of it on screen.</p>
     */
    @Test
    public void theNodeTabHasHeight() {
        mount();
        assertTrue("the node inspector collapsed to " + height(inspector.nodeInspector()),
                height(inspector.nodeInspector()) > 100f);
        assertTrue(width(inspector.nodeInspector()) > 100f);
    }

    /** And so does the graph tab, once it is the selected one. */
    @Test
    public void theGraphTabHasHeight() {
        mount();
        inspector.tabs().selectIndex(1);
        settle();
        assertTrue("the settings panel collapsed to " + height(inspector.graphSettings()),
                height(inspector.graphSettings()) > 100f);
        assertTrue(width(inspector.graphSettings()) > 100f);
    }

    // ── The rows ────────────────────────────────────────────────────────────

    /**
     * A selected node's rows are laid out with real height, not merely constructed.
     *
     * <p>Asserts on the row rather than the control, because a zero-height ROW is the shape of this bug —
     * the control inside it can have its own height and still never be seen.</p>
     */
    @Test
    public void aSelectedNodesRowsAreLaidOut() {
        mount();
        GraphNode multiply = editor.graph().nodes().stream()
                .filter(node -> node.getNodeId() != null
                        && "cg:Math/Basic/multiply".equals(
                                editor.graph().getDocument().node(node.getNodeId()).typeId()))
                .findFirst().orElse(null);
        assertNotNull("the starter graph must contain a multiply", multiply);

        editor.graph().getSelection().selectOnly(multiply);
        settle();

        UIElement panel = inspector.nodeInspector();
        assertTrue("the node tab must have put rows on screen", panel.getChildren().size() > 0);

        float tallest = 0f;
        for (UIElement row : panel.getChildren()) tallest = Math.max(tallest, height(row));
        assertTrue("every row laid out at zero height", tallest > 4f);
    }

    /** The graph tab's generated setting rows likewise. */
    @Test
    public void theGeneratedSettingRowsAreLaidOut() {
        mount();
        inspector.tabs().selectIndex(1);
        settle();

        UIElement panel = inspector.graphSettings();
        assertTrue("the graph tab must have rows", panel.getChildren().size() > 0);

        float tallest = 0f;
        for (UIElement row : panel.getChildren()) tallest = Math.max(tallest, height(row));
        assertTrue("every generated row laid out at zero height", tallest > 4f);
    }
}
