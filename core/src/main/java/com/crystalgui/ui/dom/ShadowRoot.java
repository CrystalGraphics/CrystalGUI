package com.crystalgui.ui.dom;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * A composite's own tree: its parts, hung off a {@linkplain #host() host} rather than under it.
 *
 * <p>Never a light child (its {@code parent()} is null; {@link #host()} is the way up), never
 * described by the codec, never observed by the mirror, never reached by a selector from outside —
 * a rule outside matches nothing in here, and a part is exposed for theming by name through
 * {@code ::part()} (spike S2). Inherited style still crosses the boundary, as it does on the web.
 * Content the host is given as light children appears inside this tree wherever a {@link Slot} takes
 * it; a light child no slot takes is not rendered at all.</p>
 *
 * <p>Slot assignment is recomputed at the end of any mutation that could have moved it — a change to
 * the host's light children, a slot appearing, disappearing or being renamed in here, a child's
 * {@code slot} attribute — and a slot whose assigned nodes changed hears {@code slotChanged()} with the
 * other lifecycle callbacks, after the mutation. A detached tree assigns lazily on the first read.</p>
 *
 * <p>Focus: a shadow tree is its own navigation scope, and {@link #delegatesFocus()} says whether
 * focusing the host focuses the first focusable thing inside instead — the composite's answer to
 * "a focusable container is a wall".</p>
 */
public final class ShadowRoot extends Node {

    private final Node host;
    private final boolean delegatesFocus;

    private boolean slotsDirty = true;
    private List<Slot> slots = List.of();

    ShadowRoot(Node host, boolean delegatesFocus) {
        super(Name.SHADOW_ROOT);
        this.host = host;
        this.delegatesFocus = delegatesFocus;
        this.inShadow = true;
    }

    public Node host() {
        return host;
    }

    public boolean delegatesFocus() {
        return delegatesFocus;
    }

    /** The shadow root is transparent in the flat tree; its children's composed parent is the host. */
    @Override
    @Nullable
    public Node composedParent() {
        return host;
    }

    /** The slots in this tree, in tree order, as of the last assignment. */
    public List<Slot> slots() {
        ensureAssigned();
        return slots;
    }

    /** The slot named {@code name} ({@code ""} for the default slot), or null. */
    @Nullable
    public Slot slot(String name) {
        for (Slot slot : slots()) {
            if (slot.slotName().equals(name)) return slot;
        }
        return null;
    }

    void markSlotsDirty() {
        slotsDirty = true;
        Document doc = document;
        if (doc != null) doc.slotsDirty(this);
    }

    void ensureAssigned() {
        if (slotsDirty) assign();
    }

    private void assign() {
        slotsDirty = false;
        List<Slot> found = new ArrayList<>();
        collectSlots(this, found);

        Map<Slot, List<Node>> before = new HashMap<>();
        for (Slot slot : slots) before.put(slot, slot.assignedSnapshot());
        for (Slot slot : found) slot.beginAssignment();

        for (Node child : host.children()) {
            String wanted = child.get(Attribute.SLOT);
            Slot target = null;
            for (Slot slot : found) {
                if (slot.slotName().equals(wanted)) {
                    target = slot;
                    break;
                }
            }
            child.assignedSlot = target;
            if (target != null) target.assign(child);
        }
        slots = found;

        for (Slot slot : found) {
            List<Node> was = before.getOrDefault(slot, List.of());
            if (!was.equals(slot.assignedSnapshot())) {
                Document doc = document;
                if (doc != null) doc.queue(slot::slotChanged);
                else slot.slotChanged();
            }
        }
    }

    /** Slots in THIS tree: light descendants, never inside a nested host's own shadow tree. */
    private static void collectSlots(Node at, List<Slot> into) {
        for (Node child : at.children()) {
            if (child instanceof Slot) into.add((Slot) child);
            collectSlots(child, into);
        }
    }

    @Override
    public String toString() {
        return "<shadow-root of " + host + ">";
    }
}
