package com.crystalgui.core.layout;

import dev.vfyjxf.taffy.geometry.FloatSize;
import dev.vfyjxf.taffy.geometry.TaffySize;
import dev.vfyjxf.taffy.style.AvailableSpace;
import dev.vfyjxf.taffy.tree.Layout;
import dev.vfyjxf.taffy.tree.NodeId;
import dev.vfyjxf.taffy.tree.TaffyTree;
import dev.vfyjxf.taffy.util.MeasureFunc;
import com.crystalgui.core.geometry.UiRect;
import com.crystalgui.ui.UIElement;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages the Taffy layout tree for a UI container.
 *
 * <p>Supports measured leaves: elements that provide a {@link MeasureFunc}
 * via {@link UIElement#getMeasureFunc()} are attached with
 * {@code newLeafWithMeasure}, and layout is always computed through
 * {@code computeLayoutWithMeasure} so measured nodes participate correctly.</p>
 */
public final class LayoutContext {

    /** No-op measure returning zero size — used as the default for non-measured leaves. */
    private static final MeasureFunc NOOP_MEASURE = (knownDimensions, availableSpace) -> new FloatSize(0, 0);

    @Getter
    private final TaffyTree taffyTree = new TaffyTree();

    public void attachSubtree(UIElement element) {
        if (element.getLayoutState().isAttachedToLayoutTree()) {
            syncChildren(element);
            return;
        }

        MeasureFunc measureFunc = element.getMeasureFunc();
        NodeId nodeId;
        if (measureFunc != null) {
            nodeId = taffyTree.newLeafWithMeasure(element.getLayoutStyle().copy(), measureFunc);
        } else {
            nodeId = taffyTree.newLeaf(element.getLayoutStyle().copy());
        }
        element.getLayoutState().attachNode(nodeId);

        for (UIElement child : element.getChildren()) {
            attachSubtree(child);
        }

        syncChildren(element);
        markDirty(element);
    }

    public void detachSubtree(UIElement element) {
        for (UIElement child : new ArrayList<UIElement>(element.getChildren())) {
            detachSubtree(child);
        }

        NodeId nodeId = element.getLayoutState().getNodeId();
        if (nodeId != null && taffyTree.containsNode(nodeId)) {
            taffyTree.remove(nodeId);
        }
        element.getLayoutState().detachNode();
        element.clearDirtyFlags();
    }

    public void syncChildren(UIElement element) {
        NodeId parentNode = requireNodeId(element);
        List<NodeId> childNodes = new ArrayList<NodeId>();
        for (UIElement child : element.getChildren()) {
            if (!child.getLayoutState().isAttachedToLayoutTree()) {
                attachSubtree(child);
            }
            childNodes.add(requireNodeId(child));
        }
        taffyTree.setChildren(parentNode, childNodes.toArray(new NodeId[0]));
        taffyTree.markDirty(parentNode);
    }

    public void markDirty(UIElement element) {
        element.getLayoutState().markLayoutDirty();
        NodeId nodeId = element.getLayoutState().getNodeId();
        if (nodeId != null && taffyTree.containsNode(nodeId)) {
            taffyTree.setStyle(nodeId, element.getLayoutStyle().copy());
            // Update measure function if it changed
            MeasureFunc measureFunc = element.getMeasureFunc();
            if (measureFunc != null) {
                taffyTree.setMeasureFunc(nodeId, measureFunc);
            }
            taffyTree.markDirty(nodeId);
        }
    }

    /**
     * Computes the layout for the subtree rooted at the given element.
     *
     * <p>Uses {@code computeLayoutWithMeasure} so that elements with measure
     * functions (like UiLabel) participate in layout correctly.</p>
     */
    public void computeLayout(UIElement root, float availableWidth, float availableHeight) {
        if (!root.getLayoutState().isAttachedToLayoutTree()) {
            attachSubtree(root);
        }

        refreshSubtree(root);
        // Use computeLayoutWithMeasure with NOOP_MEASURE as default
        // so measured leaves registered via newLeafWithMeasure are respected
        taffyTree.computeLayoutWithMeasure(
                requireNodeId(root),
                TaffySize.of(AvailableSpace.definite(availableWidth), AvailableSpace.definite(availableHeight)),
                NOOP_MEASURE
        );
        updateLayoutBoxes(root, 0.0f, 0.0f);
    }

    private void refreshSubtree(UIElement element) {
        NodeId nodeId = requireNodeId(element);
        taffyTree.setStyle(nodeId, element.getLayoutStyle().copy());

        // Update measure function during refresh
        MeasureFunc measureFunc = element.getMeasureFunc();
        if (measureFunc != null) {
            taffyTree.setMeasureFunc(nodeId, measureFunc);
        }

        List<NodeId> childNodes = new ArrayList<NodeId>();
        for (UIElement child : element.getChildren()) {
            if (!child.getLayoutState().isAttachedToLayoutTree()) {
                attachSubtree(child);
            }
            refreshSubtree(child);
            childNodes.add(requireNodeId(child));
        }
        taffyTree.setChildren(nodeId, childNodes.toArray(new NodeId[0]));
        if (element.isLayoutDirty()) {
            taffyTree.markDirty(nodeId);
        }
    }

    private void updateLayoutBoxes(UIElement element, float parentX, float parentY) {
        Layout layout = taffyTree.getLayout(requireNodeId(element));
        float absoluteX = parentX + layout.location().x;
        float absoluteY = parentY + layout.location().y;
        element.getLayoutState().setLayoutBox(new UiRect(absoluteX, absoluteY, layout.size().width, layout.size().height));
        element.clearDirtyFlags();

        for (UIElement child : element.getChildren()) {
            updateLayoutBoxes(child, absoluteX, absoluteY);
        }
    }

    private NodeId requireNodeId(UIElement element) {
        NodeId nodeId = element.getLayoutState().getNodeId();
        if (nodeId == null) {
            throw new IllegalStateException("Element is not attached to the Taffy layout tree");
        }
        return nodeId;
    }
}
