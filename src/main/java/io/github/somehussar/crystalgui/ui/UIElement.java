package io.github.somehussar.crystalgui.ui;

import dev.vfyjxf.taffy.tree.NodeId;
import lombok.Getter;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public class UIElement {

    // Taffy-related stuff ;[
    protected NodeId nodeId;

    // UI Structure
    @Getter
    @Nullable
    private UIContainer container;

    @Nullable
    private UIElement parent;
    private final List<UIElement> children = new ArrayList<>();



}
