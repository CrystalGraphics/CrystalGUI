package com.crystalgui.ui;

import com.crystalgraphics.shadergraph.CgMasterNode;
import com.crystalgraphics.shadergraph.CgPreviewMesh;
import com.crystalgraphics.shadergraph.CgShaderNodeRegistry;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.graph.GraphDocument;
import com.crystalgui.graph.shader.MainPreviewPanel;
import com.crystalgui.graph.GraphCodecs;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.testsupport.UiTestBase;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * P6.3.12 — the Main Preview panel's <b>view state</b>.
 *
 * <h3>What this can and cannot cover</h3>
 * <p>The picture needs a GL context, so nothing here draws. What it does cover is the boundary that
 * actually matters and that a screenshot would never reveal: the chosen mesh, the orbit and the zoom are
 * <b>view</b> state. They must not reach the document and must not reach an {@code UndoStack}, or Ctrl+Z
 * starts rotating a sphere instead of undoing an edit — the same rule this project already applies to
 * scroll position and selection.</p>
 *
 * <p>Constructing the panel is itself part of the assertion: {@code CgMainPreviewRenderer} allocates no
 * GL object until its first {@code render}, so a panel can exist in a headless test at all.</p>
 */
public class MainPreviewPanelTest extends UiTestBase {

    private GraphDocument document;
    private UndoStack undo;
    private MainPreviewPanel panel;

    @Before
    public void setUp() {
        document = new GraphDocument();
        undo = new UndoStack();
        panel = new MainPreviewPanel(document, CgShaderNodeRegistry.builtins(), new CgMasterNode());
    }

    // ── Defaults ────────────────────────────────────────────────────────────

    @Test
    public void itOpensOnASphereFacingTheCamera() {
        assertEquals(CgPreviewMesh.SPHERE, panel.mesh());
        assertEquals(0f, panel.yaw(), 0f);
        assertEquals(0f, panel.pitch(), 0f);
        assertEquals(1f, panel.zoom(), 0f);
    }

    @Test
    public void theMeshCanBeChosen() {
        panel.setMesh(CgPreviewMesh.CAPSULE);
        assertEquals(CgPreviewMesh.CAPSULE, panel.mesh());
    }

    /** A null choice falls back rather than leaving the renderer with nothing to draw. */
    @Test
    public void aNullMeshFallsBackToTheDefault() {
        panel.setMesh(CgPreviewMesh.CUBE);
        panel.setMesh(null);
        assertEquals(CgPreviewMesh.SPHERE, panel.mesh());
    }

    @Test
    public void resetViewPutsTheCameraBack() {
        panel.setMesh(CgPreviewMesh.CYLINDER);
        panel.resetView();
        assertEquals(0f, panel.yaw(), 0f);
        assertEquals(0f, panel.pitch(), 0f);
        assertEquals(1f, panel.zoom(), 0f);
        // The SHAPE is not part of the camera — resetting the view should not also change what is being
        // looked at, which would be a second surprise hidden inside a "reset" nobody asked twice for.
        assertEquals(CgPreviewMesh.CYLINDER, panel.mesh());
    }

    // ── The boundary ────────────────────────────────────────────────────────

    /**
     * <b>Choosing a shape is not an edit.</b>
     *
     * <p>The document is compared before and after as encoded bytes rather than by identity: the point is
     * not that nothing was replaced, it is that nothing a save would carry has changed.</p>
     */
    @Test
    public void choosingAMeshChangesNeitherTheDocumentNorTheHistory() {
        String before = encoded();

        panel.setMesh(CgPreviewMesh.CUBE);
        panel.resetView();

        assertEquals("the document must be untouched", before, encoded());
        assertEquals("and nothing may reach the undo stack", 0, undo.undoDepth());
        assertFalse(undo.canUndo());
    }

    // ── The header, which is copied from the configurator rather than derived ──

    /**
     * The title sits <b>inside</b> the header's padding, and the header is at least a config row tall.
     *
     * <p>Both halves were bugs. The strip was originally a bare {@code UIText}, which draws its glyphs
     * from its own box top — so there was no flex item for {@code align-items} to centre and the
     * ascenders clipped against the panel edge, and no height or padding value could have fixed it. The
     * shape had to change: a row container with the title as a child, which is exactly what
     * {@code .__configurator-group__ > .__head__} already is.</p>
     *
     * <p>Asserted as <em>measured layout</em> rather than by reading the stylesheet back, because the
     * question is whether the rule reaches this element at all — a selector that silently matches nothing
     * reads identically to one that matches and is overridden.</p>
     */
    @Test
    public void theHeaderInsetsItsTitleAndIsAConfigRowTall() {
        UIElement root = new UIElement().layout(l -> l.width(600).height(600));
        root.addChild(panel);
        UIWindow window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(com.crystalgui.style.sheet.StyleSheet.DEFAULT);
        window.init(600, 600);
        // Twice: UIText settles its measured height on the pass after the box it wraps against is known.
        window.updateWithoutPainting();
        window.updateWithoutPainting();

        UIElement head = null;
        for (UIElement child : panel.getChildren()) {
            if (child.hasClass(MainPreviewPanel.HEAD_CLASS)) head = child;
        }
        assertNotNull("the header must be a container, not the label itself", head);
        assertEquals("a header row is at least as tall as a configurator row", 20f,
                head.getRuntimeCache().getHeight(), 0.5f);

        assertFalse("the header must contain the title", head.getChildren().isEmpty());
        UIElement title = head.getChildren().get(0);
        float inset = head.getRuntimeCache().getWidth() - title.getRuntimeCache().getWidth();
        assertTrue("the title must be inset by the header's padding on both sides, was " + inset,
                inset >= 8f);
    }

    private String encoded() {
        return String.valueOf(GraphCodecs.DOCUMENT.encode(PlainOps.INSTANCE, document));
    }
}
