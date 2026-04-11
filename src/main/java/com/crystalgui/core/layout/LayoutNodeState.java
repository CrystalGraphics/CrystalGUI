package com.crystalgui.core.layout;

import dev.vfyjxf.taffy.tree.NodeId;
import com.crystalgui.core.geometry.UiRect;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nullable;

@Getter
public final class LayoutNodeState {

    @Nullable
    private NodeId nodeId;
    @Setter
    private UiRect layoutBox = UiRect.ZERO;
    private boolean layoutDirty = true;

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

    public void markLayoutDirty() {
        this.layoutDirty = true;
    }

    public void clearLayoutDirty() {
        this.layoutDirty = false;
    }
}
