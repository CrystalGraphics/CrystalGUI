package com.crystalgui.ui.dom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Where a host's light children appear inside its shadow tree.
 *
 * <p>A light child asks for a slot by its {@link Attribute#SLOT} attribute; the empty name is the
 * default slot, which takes every child that asked for nothing. A slot nothing is assigned to shows
 * its own light children as <b>fallback</b>. {@code Tab.content()}, {@code WindowFrame.content()},
 * {@code ScrollerView}'s viewport and {@code Dialog}'s body are all slots once ported (audit §12.1) —
 * and moving content between two windows becomes moving a node between two slots, with no flag to
 * remember and put back.</p>
 */
public class UISlot extends UINode {

    /** Where a host's light children appear inside its shadow tree. */
    public static final Name NAME = Name.of("slot");

    private String slotName;
    private final List<UINode> assigned = new ArrayList<>();
    private final List<UINode> assignedView = Collections.unmodifiableList(assigned);

    /** The default slot. */
    public UISlot() {
        this("");
    }

    public UISlot(String slotName) {
        super(NAME);
        this.slotName = slotName == null ? "" : slotName;
    }

    public final String slotName() {
        return slotName;
    }

    public UISlot setSlotName(String name) {
        String value = name == null ? "" : name;
        if (value.equals(slotName)) return this;
        slotName = value;
        ShadowRoot root = containingShadowRoot();
        if (root != null) root.markSlotsDirty();
        return this;
    }

    /** The light children of the host assigned here, in the host's order; empty when showing fallback. */
    public final List<UINode> assignedNodes() {
        ShadowRoot root = containingShadowRoot();
        if (root != null) root.ensureAssigned();
        return assignedView;
    }

    /** Assigned nodes, or this slot's own children as fallback. */
    @Override
    public List<UINode> composedChildren() {
        List<UINode> nodes = assignedNodes();
        return nodes.isEmpty() ? children() : nodes;
    }

    /** Runs after an assignment change, after the mutation that caused it. */
    protected void slotChanged() {
    }

    void beginAssignment() {
        assigned.clear();
    }

    void assign(UINode node) {
        assigned.add(node);
    }

    List<UINode> assignedSnapshot() {
        return new ArrayList<>(assigned);
    }

    @Override
    public String toString() {
        return "<slot" + (slotName.isEmpty() ? "" : " name=" + slotName) + ">";
    }

    /** The slot {@code node} is assigned to, or null — a static so a caller need not know the host. */
    @Nullable
    public static UISlot of(UINode node) {
        return node.assignedSlot();
    }
}
