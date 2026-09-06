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
public class UISlot extends UIElement {

    /** Where a host's light children appear inside its shadow tree. */
    public static final Name NAME = Name.of("slot");

    /**
     * {@code <slot name="content">} — the slot's own name, as an attribute so it travels.
     *
     * <p>The field alone was not enough once a shadow tree could be <b>described</b>: a template writing
     * a named slot decoded to a default one, and every slotted child landed in the wrong place or
     * nowhere. Set it either way — {@link #setSlotName} writes the attribute and the attribute writes
     * the field.</p>
     */
    public static final Attribute<String> SLOT_NAME = Attribute.of("name", String.class, "");

    private String slotName;
    private final List<UIElement> assigned = new ArrayList<>();
    private final List<UIElement> assignedView = Collections.unmodifiableList(assigned);

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
        set(SLOT_NAME, value);
        ShadowRoot root = containingShadowRoot();
        if (root != null) root.markSlotsDirty();
        return this;
    }

    /** Keeps the field and the attribute one answer, whichever way the name arrived. */
    @Override
    public <T> UIElement set(Attribute<T> key, T value) {
        UIElement self = super.set(key, value);
        if (key == SLOT_NAME) setSlotName((String) value);
        return self;
    }

    /** The light children of the host assigned here, in the host's order; empty when showing fallback. */
    public final List<UIElement> assignedNodes() {
        ShadowRoot root = containingShadowRoot();
        if (root != null) root.ensureAssigned();
        return assignedView;
    }

    /** Assigned nodes, or this slot's own children as fallback. */
    @Override
    public List<UIElement> composedChildren() {
        List<UIElement> nodes = assignedNodes();
        return nodes.isEmpty() ? children() : nodes;
    }

    /** Runs after an assignment change, after the mutation that caused it. */
    protected void slotChanged() {
    }

    void beginAssignment() {
        assigned.clear();
    }

    void assign(UIElement node) {
        assigned.add(node);
    }

    List<UIElement> assignedSnapshot() {
        return new ArrayList<>(assigned);
    }

    @Override
    public String toString() {
        return "<slot" + (slotName.isEmpty() ? "" : " name=" + slotName) + ">";
    }

    /** The slot {@code node} is assigned to, or null — a static so a caller need not know the host. */
    @Nullable
    public static UISlot of(UIElement node) {
        return node.assignedSlot();
    }
}
