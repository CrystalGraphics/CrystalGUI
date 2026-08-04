package com.crystalgui.ui;

import com.crystalgui.graph.shader.ShaderGraphEditor;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The assembled shader graph widget.
 *
 * <p>Every test here carries a <b>timeout</b>, which is unusual and deliberate: the failure this widget
 * actually shipped was an infinite layout pass, and an assertion cannot catch one. The window hung before
 * painting a frame and the only symptom was "Not Responding" — no exception, no log line, nothing for a
 * conventional test to observe. A timeout turns that into a red test instead of a hung suite.</p>
 */
public class ShaderGraphEditorTest extends UiTestBase {

    private UIWindow window;
    private ShaderGraphEditor editor;

    private void build() {
        editor = new ShaderGraphEditor();
        UIElement root = new UIElement().layout(l -> l.width(800).height(500));
        root.addChild(editor);
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(1600, 1000);
    }

    /**
     * <b>Layout settles.</b> The regression test for a real freeze.
     *
     * <p>Preview attachment was done from {@code onLayoutChanged}, which runs <em>inside</em>
     * {@code calculateLayout()}'s {@code while (isLayoutDirty())} loop. Attaching adds elements, so every
     * pass re-dirtied the tree and the loop never terminated — the harness hung on open, before its first
     * frame. Structural changes belong outside the layout pass; this asserts that they are.</p>
     */
    @Test(timeout = 15_000)
    public void framesTerminateRatherThanLoopingForever() {
        build();
        for (int i = 0; i < 8; i++) window.updateWithoutPainting();
        assertNotNull(editor.graph());
    }

    @Test(timeout = 15_000)
    public void anEmptyGraphCompilesToSomethingRatherThanThrowing() {
        build();
        for (int i = 0; i < 3; i++) window.updateWithoutPainting();
        assertNotNull("no compile result at all", editor.lastCompile());
        assertNotNull(editor.source().getText());
    }

    /**
     * The starter graph is opt-in, and compiles.
     *
     * <p><b>No frames are pumped after seeding, deliberately.</b> Attaching the previews starts
     * {@code CgPreviewRenderer}, which allocates an FBO per node and therefore needs a real GL context —
     * so a frame here dies in {@code CgCapabilities.detect()} rather than telling us anything about the
     * graph. What is assertable without pixels is the document and the emit, which is what this checks;
     * the seeded widget under a live context is the harness's job.</p>
     */
    @Test(timeout = 15_000)
    public void theStarterGraphIsOptInAndCompiles() {
        build();
        assertTrue("a new editor must not invent nodes", editor.graph().getDocument().nodeCount() == 0);

        editor.addStarterGraph();

        assertTrue(editor.graph().getDocument().nodeCount() > 0);
        assertNotNull(editor.lastCompile());
        assertTrue("the starter graph does not compile: "
                + String.join("; ", editor.lastCompile().errors()), editor.lastCompile().ok());
    }

    /**
     * <b>The graph's own subtree is not marked internal.</b>
     *
     * <p>The bug that hung both scenes, and the third time this exact trap has been hit in this widget
     * layer. {@code addInternalChild(split)} looks right and {@code markAsInternal()} <b>recurses</b>, so
     * it stamped the split, both panes, the {@code GraphView}, its canvas and everything under them — and
     * {@code removeChild}/{@code clearAllChildren} <b>silently refuse</b> internal children. The previews
     * add and retire a thumbnail per node, so every retirement was declined, the tree grew without bound,
     * and layout took longer every frame until the window stopped responding.</p>
     *
     * <p>The thread dump was pure Taffy, which reads as a layout cycle and was really an unbounded tree —
     * which is why this is asserted structurally rather than by timing. A wrapper marked internal
     * <em>while empty</em> is the fix, the same one {@code QuickPick} and {@code ProblemsPanel} carry.</p>
     */
    @Test(timeout = 15_000)
    public void theGraphSubtreeIsNotStampedInternal() {
        build();
        // removeChild REFUSES an internal child and reports false, so asking the engine to remove the
        // GraphView is the observable form of "was this subtree stamped?" -- and it asks the engine
        // rather than a private flag this test would have to guess the name of.
        UIElement pane = editor.graph().getParent();
        assertNotNull("the graph is not in the tree at all", pane);
        boolean removable = pane.removeChild(editor.graph());
        assertTrue("the GraphView is stamped internal -- removals under it are silently refused, "
                + "which is what made the tree grow without bound", removable);
        pane.addChild(editor.graph());
    }
}
