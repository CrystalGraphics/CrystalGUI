package com.crystalgui.rewrite;

import com.crystalgui.rewrite.render.CgUiPaintContext;
import dev.vfyjxf.taffy.geometry.TaffySize;
import dev.vfyjxf.taffy.style.AvailableSpace;
import dev.vfyjxf.taffy.tree.Layout;
import dev.vfyjxf.taffy.tree.NodeId;
import dev.vfyjxf.taffy.tree.TaffyTree;

import java.util.List;

/**
 * Runtime engine. Owns the paint context, the live
 * {@link TaffyTree}, and drives the per-frame layout + paint entry points.
 *
 * <p>Deliberately does NOT implement any platform (LWJGL2/LWJGL3/MC) widget or Screen
 * interface itself. Need MC-Sided adapters.</p>
 *
 * <p><b>Tree building:</b> the Taffy-side tree is (re)built by {@link #rebuildTree()},
 * which walks {@code ui.rootElement} and its children, calling {@code TaffyTree.newLeaf}/
 * {@code newWithChildren} bottom-up and stashing the resulting {@link NodeId} on each
 * {@link UIElement}. Called automatically once from the constructor. See
 * {@link UIElement}'s javadoc for the current limitation around structural changes made
 * after attachment — call {@link #rebuildTree()} again after adding/removing children.</p>
 */
public final class UiRuntime {

    public final Ui ui;
    private static final CgUiPaintContext paintContext = new CgUiPaintContext();

    private final TaffyTree taffyTree = new TaffyTree();
    private NodeId rootNodeId;

    private int screenWidth;
    private int screenHeight;

    public UiRuntime(Ui ui) {
        this.ui = ui;
        rebuildTree();
    }

    public void resize(int screenWidth, int screenHeight) {
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
    }

    /**
     * Discards and rebuilds the entire Taffy-side tree from {@code ui.rootElement}'s
     * current structure. Call after any {@link UIElement#addChild}/{@link UIElement#removeChild}
     * mutation — see the known-limitation note on {@link UIElement}.
     */
    public void rebuildTree() {
        taffyTree.clear();
        rootNodeId = buildNode(ui.rootElement);
    }

    private NodeId buildNode(UIElement element) {
        List<UIElement> children = element.getChildren();
        NodeId id;
        if (children.isEmpty()) {
            id = taffyTree.newLeaf(element.getTaffyStyle());
        } else {
            NodeId[] childIds = new NodeId[children.size()];
            for (int i = 0; i < children.size(); i++) {
                childIds[i] = buildNode(children.get(i));
            }
            id = taffyTree.newWithChildren(element.getTaffyStyle(), childIds);
        }
        element.taffyNodeId = id;
        element.attachedRuntime = this;
        return id;
    }

    /** Marks a Taffy node dirty. Called by {@code UIElement.layout(...)} after a style change. */
    void markDirty(NodeId nodeId) {
        taffyTree.markDirty(nodeId);
    }

    /**
     * Runs Taffy's layout algorithm against the current screen size, then walks the tree
     * writing absolute screen-space bounds back onto every {@link UIElement} via
     * {@link UIElement#setBounds}. Taffy's own {@code Layout.location()} is parent-relative;
     * this walk accumulates the offset so every element ends up in absolute screen coordinates.
     */
    public void layout() {
        taffyTree.computeLayout(rootNodeId,
                TaffySize.of(AvailableSpace.definite(screenWidth), AvailableSpace.definite(screenHeight)));
        applyLayout(ui.rootElement, 0f, 0f);
    }

    private void applyLayout(UIElement element, float parentAbsX, float parentAbsY) {
        Layout layout = taffyTree.getLayout(element.taffyNodeId);
        // NOTE: FloatPoint/FloatSize are plain classes with public fields (x/y, width/height),
        // NOT records — unlike Layout itself, which is a record (hence layout.location()/size()
        // being method calls while .x/.y/.width/.height below are field access).
        float absX = parentAbsX + layout.location().x;
        float absY = parentAbsY + layout.location().y;
        element.setBounds(absX, absY, layout.size().width, layout.size().height);
        for (UIElement child : element.getChildren()) {
            applyLayout(child, absX, absY);
        }
    }

    /**
     * Lays out and paints the whole tree, once, synchronously, right now. Call this from
     * wherever your per-frame render hook lives (harness scene, or later the platform
     * adapter's render callback). No batching, no queued commands — by the time this method
     * returns, every visible element's GPU draw calls have already been issued in painter's
     * order, using bounds computed by this same call.
     */
    public void paintFrame() {
        layout();
        paintContext.beginFrame(screenWidth, screenHeight);
        ui.rootElement.drawSubtree(paintContext);
        paintContext.endFrame();
    }
}
