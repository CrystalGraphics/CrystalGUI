package com.crystalgui.core.layout;

import dev.vfyjxf.taffy.geometry.TaffySize;
import dev.vfyjxf.taffy.style.AvailableSpace;
import dev.vfyjxf.taffy.tree.Layout;
import dev.vfyjxf.taffy.tree.NodeId;
import dev.vfyjxf.taffy.tree.TaffyTree;
import com.crystalgui.core.geometry.UiRect;
import com.crystalgui.ui.UIElement;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

public final class LayoutContext {

    @Getter
    private final TaffyTree taffyTree = new TaffyTree();

    public void attachSubtree(UIElement element) {
        if (element.getLayoutState().isAttachedToLayoutTree()) {
            syncChildren(element);
            return;
        }

        NodeId nodeId = taffyTree.newLeaf(element.getLayoutStyle().copy());
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
            taffyTree.markDirty(nodeId);
        }
    }

    public void computeLayout(UIElement root, float availableWidth, float availableHeight) {
        if (!root.getLayoutState().isAttachedToLayoutTree()) {
            attachSubtree(root);
        }

        refreshSubtree(root);
        taffyTree.computeLayout(
                requireNodeId(root),
                TaffySize.of(AvailableSpace.definite(availableWidth), AvailableSpace.definite(availableHeight))
        );
        updateLayoutBoxes(root, 0.0f, 0.0f);
    }

    private void refreshSubtree(UIElement element) {
        NodeId nodeId = requireNodeId(element);
        taffyTree.setStyle(nodeId, element.getLayoutStyle().copy());

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
