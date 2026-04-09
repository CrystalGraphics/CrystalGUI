package com.crystalgui.core.layout;

import dev.vfyjxf.taffy.tree.NodeId;
import com.crystalgui.core.geometry.UiRect;

import javax.annotation.Nullable;

public final class LayoutNodeState {

    @Nullable
    private NodeId nodeId;
    private UiRect layoutBox = UiRect.ZERO;
    private boolean layoutDirty = true;

    @Nullable
    public NodeId getNodeId() {
        return nodeId;
    }

    public boolean isAttachedToLayoutTree() {
        return nodeId != null;
    }

    public void attachNode(NodeId nodeId) {
        this.nodeId = nodeId;
        this.layoutDirty = true;
    }

    public void detachNode() {
        this.nodeId = null;
        this.layoutBox = UiRect.ZERO;
        this.layoutDirty = true;
    }

    public UiRect getLayoutBox() {
        return layoutBox;
    }

    public void setLayoutBox(UiRect layoutBox) {
        this.layoutBox = layoutBox;
    }

    public boolean isLayoutDirty() {
        return layoutDirty;
    }

    public void markLayoutDirty() {
        this.layoutDirty = true;
    }

    public void clearLayoutDirty() {
        this.layoutDirty = false;
    }
}
