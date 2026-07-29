package com.crystalgui.ui;

import com.crystalgraphics.api.PoseStack;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.StyleOrigin;
import com.crystalgui.style.property.layout.LayoutProperties;
import dev.vfyjxf.taffy.style.TaffyPosition;
import dev.vfyjxf.taffy.tree.NodeId;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The <b>top layer</b> — CSS Position 4 §top-layer, the mechanism behind {@code <dialog>} and the
 * Popover API. Owned 1:1 by a {@link UIWindow}, which is this engine's {@code Document}; the naming
 * follows Blink's own {@code Document::AddToTopLayer} / {@code RemoveFromTopLayer} /
 * {@code Element::IsInTopLayer}.
 *
 * <p>Contents paint after the whole main tree, so they cannot be clipped by an ancestor's
 * {@code overflow}, dimmed by its {@code opacity}, or moved by its {@code transform}. That is the
 * only way to draw a tooltip out of a scroller, and it is the entire reason this exists.</p>
 *
 * <h3>Order is the whole stacking model</h3>
 * <p>Per spec, "the last element in the top layer is rendered on top of everything else", and
 * {@code z-index} is <em>irrelevant</em> between two promoted elements — they stack purely by
 * position in this list. So it is a plain insertion-ordered list, painted front-to-back, and
 * {@code RuntimeCache.sortedChildren} is deliberately never consulted here.</p>
 *
 * <h3>What promotion actually changes</h3>
 * <p>A promoted element keeps its DOM parent, because the cascade must not change — inheritance and
 * selector matching are both by tree position. Four <em>positional</em> relationships diverge
 * instead, and they are separate code paths that can silently disagree with each other. Three live
 * here; the fourth two are in {@link UIElement.RuntimeCache}. See {@link UIElement#isInTopLayer()}
 * for the full list, which is the single place it is written down.</p>
 */
public final class TopLayer {

    private final UIWindow window;
    private final List<UIElement> elements = new ArrayList<>();

    /** Reused across frames so a window with a live tooltip doesn't allocate every paint. Grown on
     * demand, never shrunk — the high-water mark of concurrently promoted elements is tiny. */
    private UIElement[] iterationBuffer = new UIElement[8];

    TopLayer(UIWindow window) {
        this.window = window;
    }

    /** Bottom-most first. Read-only; mutate via {@link #add}. */
    public List<UIElement> elements() {
        return Collections.unmodifiableList(elements);
    }

    public boolean isEmpty() {
        return elements.isEmpty();
    }

    /**
     * Promotes {@code element}, or <b>raises</b> it if already promoted.
     *
     * <p><b>Promotion is imperative on purpose.</b> The web promotes with {@code el.showPopover()}
     * and {@code dialog.showModal()} — DOM methods, not a style declaration. (CSS's {@code overlay}
     * property is set by the UA as a side effect so transitions can observe promotion; it is not the
     * trigger.) Mirroring that also avoids colliding with this engine's existing, unrelated
     * {@code overlay} drawable property.</p>
     *
     * <p>Re-adding removes and re-appends, matching the spec's own add algorithm — so "raise this
     * popup" is one idempotent call rather than a remove/add dance the caller has to get right.</p>
     */
    public void add(UIElement element) {
        Objects.requireNonNull(element, "element");
        if (element == window.ui.rootElement)
            throw new IllegalArgumentException("The root element cannot be promoted to the top layer");
        if (element.getAttachedWindow() != window)
            throw new IllegalStateException("Element is not attached to this window; add it to the tree first");

        boolean alreadyPromoted = elements.remove(element); // re-add == raise, per spec
        elements.add(element);
        if (alreadyPromoted) return; // order changed, nothing structural to redo

        element.inTopLayer = true;
        reparentTaffyNodeToRoot(element);

        // The containing block becomes the initial containing block, so the element must be
        // out-of-flow — otherwise promoting a node makes it a flex item of the root and shoves the
        // real content aside. The web does this from its UA sheet (`[popover] { position: fixed }`);
        // there is no selector for "is promoted" here, so it goes in at IMPORTANT origin instead.
        // ANIMATION still outranks it, so a transition can drive position.
        StyleGroup.importantPipeline(element.getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE));

        invalidatePromotedGeometry(element);
    }

    /** Demotes, restoring normal in-tree layout, painting and hit-testing. No-op if never promoted. */
    public void remove(UIElement element) {
        if (element == null || !elements.remove(element)) return;

        element.inTopLayer = false;
        restoreTaffyNodeToDomParent(element);

        // Drop only OUR forced position, by origin, so an author's own `position:` at any other
        // origin survives and the cascade decides again. (A caller's own `!important` position would
        // also be dropped — it lands at the same origin, and there is nothing to tell them apart.)
        element.getStyle().removeCandidates(LayoutProperties.POSITION,
                slot -> slot.origin() == StyleOrigin.IMPORTANT);

        invalidatePromotedGeometry(element);
    }

    // ── Paint ───────────────────────────────────────────────────────────────

    /**
     * The second paint pass — CSS Position 4's "for each element el in doc's top layer: paint a
     * stacking context given el".
     *
     * <p>Runs <em>after</em> the main tree's pose has been popped, so each promoted element starts
     * from the bare root transform and inherits no ancestor transform, scroll offset, scissor or FBO
     * layer. That isolation is the feature.</p>
     *
     * <p>The scissor stack is deliberately <b>asserted</b> empty rather than reset.
     * {@code CgUiPaintContext.beginFrame} already resets it once per frame, so anything left behind
     * is an unbalanced push/pop in the main tree — which should surface here, loudly, rather than be
     * papered over on the way to a subtly wrong clip.</p>
     */
    void paint(CgUiPaintContext ctx, PoseStack pose, Matrix4f rootTransform) {
        if (elements.isEmpty()) return;

        if (ctx.getScissorDepth() != 0) {
            throw new IllegalStateException(
                    "Unbalanced scissor stack after the main paint pass: depth "
                            + ctx.getScissorDepth() + ", expected 0");
        }

        // Snapshot: painting a promoted element can itself promote or demote (a tooltip closing on
        // hover-out), and mutating the list mid-iteration would drop or repeat an element.
        int count = snapshot();
        for (int i = 0; i < count; i++) {
            UIElement element = iterationBuffer[i];
            if (element.getAttachedWindow() != window) continue; // detached mid-frame
            pose.pushPose();
            pose.mulPoseMatrix(rootTransform);
            try {
                element.drawSubtree(ctx);
            } finally {
                pose.popPose();
            }
        }
    }

    // ── Hit testing ─────────────────────────────────────────────────────────

    /**
     * Topmost promoted element under the pointer, or {@code null} to fall through to the main tree.
     *
     * <p>Blink's rule is "hit testing is done in paint-order" — it reuses the paint walk to record
     * hit-test data rather than keeping a second, drift-prone traversal. Same rule here: the top
     * layer paints last so it is tested first, and <b>backwards</b>, because the last-painted element
     * is the visually topmost one.</p>
     */
    UIElement hitTest(float mouseX, float mouseY) {
        for (int i = elements.size() - 1; i >= 0; i--) {
            UIElement promoted = elements.get(i);
            if (promoted.getAttachedWindow() != window) continue;
            UIElement hit = window.elementHitTest(promoted, mouseX, mouseY);
            if (hit != null) return hit;
        }
        return null;
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private int snapshot() {
        int count = elements.size();
        if (iterationBuffer.length < count) iterationBuffer = new UIElement[Math.max(count, iterationBuffer.length * 2)];
        for (int i = 0; i < count; i++) iterationBuffer[i] = elements.get(i);
        return count;
    }

    /** Moves the Taffy node from the DOM parent to the root, making the root box the containing
     * block — the spec's "its containing block is the initial containing block". */
    private void reparentTaffyNodeToRoot(UIElement element) {
        NodeId rootNodeId = window.getRootNodeId();
        if (element.taffyNodeId == null || rootNodeId == null) return;
        var taffyTree = window.getTaffyTree();
        UIElement domParent = element.getParent();
        if (domParent != null && domParent.taffyNodeId != null
                && taffyTree.containsNode(domParent.taffyNodeId)) {
            taffyTree.removeChild(domParent.taffyNodeId, element.taffyNodeId);
        }
        taffyTree.addChild(rootNodeId, element.taffyNodeId);
    }

    /** Inverse of {@link #reparentTaffyNodeToRoot}. */
    private void restoreTaffyNodeToDomParent(UIElement element) {
        if (element.taffyNodeId == null) return;
        var taffyTree = window.getTaffyTree();
        NodeId rootNodeId = window.getRootNodeId();
        if (rootNodeId != null && taffyTree.containsNode(rootNodeId)) {
            taffyTree.removeChild(rootNodeId, element.taffyNodeId);
        }
        UIElement domParent = element.getParent();
        if (domParent == null || domParent.taffyNodeId == null
                || !taffyTree.containsNode(domParent.taffyNodeId)) {
            return;
        }
        // Demotion also runs on the way out of the tree, via unregisterElement. By then
        // removeChildInternal has already dropped this element from its parent's child list, so
        // there is no slot to restore it to — and the node is about to be removed entirely anyway.
        // Reinsert only when it really is still a child.
        int siblingIndex = domParent.getChildren().indexOf(element);
        if (siblingIndex < 0) return;
        taffyTree.insertChildAtIndex(domParent.taffyNodeId, siblingIndex, element.taffyNodeId);
    }

    /** Both position-accumulation paths cache against the DOM parent, and promotion changes the
     * answer on both — so both are dropped, for the whole subtree, on every promotion change. */
    private void invalidatePromotedGeometry(UIElement element) {
        element.clearLayoutCache();
        element.invalidatePoseCachesRecursively();
        element.markTreeDirty();
        // The root gained or lost a child, so its own layout is stale too — marking only the moved
        // node would leave the root's child list resolved from the previous frame.
        window.ui.rootElement.markTreeDirty();
    }
}
