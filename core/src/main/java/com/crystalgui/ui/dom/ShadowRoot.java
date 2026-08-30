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
 * Content the host is given as light children appears inside this tree wherever a {@link UISlot} takes
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
public final class ShadowRoot extends UINode {

    /** A shadow root: never a light child, never described, never styled from outside. */
    public static final Name NAME = Name.of("shadow-root");

    private final UINode host;
    private final boolean delegatesFocus;

    private boolean slotsDirty = true;
    private List<UISlot> slots = List.of();

    ShadowRoot(UINode host, boolean delegatesFocus) {
        super(NAME);
        this.host = host;
        this.delegatesFocus = delegatesFocus;
        this.inShadow = true;
    }

    public UINode host() {
        return host;
    }

    public boolean delegatesFocus() {
        return delegatesFocus;
    }

    /** The shadow root is transparent in the flat tree; its children's composed parent is the host. */
    @Override
    @Nullable
    public UINode composedParent() {
        return host;
    }

    /** The slots in this tree, in tree order, as of the last assignment. */
    public List<UISlot> slots() {
        ensureAssigned();
        return slots;
    }

    /** The slot named {@code name} ({@code ""} for the default slot), or null. */
    @Nullable
    public UISlot slot(String name) {
        for (UISlot slot : slots()) {
            if (slot.slotName().equals(name)) return slot;
        }
        return null;
    }

    void markSlotsDirty() {
        slotsDirty = true;
        UIDocument doc = document;
        if (doc != null) doc.slotsDirty(this);
    }

    void ensureAssigned() {
        if (slotsDirty) assign();
    }

    private void assign() {
        slotsDirty = false;
        List<UISlot> found = new ArrayList<>();
        collectSlots(this, found);

        Map<UISlot, List<UINode>> before = new HashMap<>();
        for (UISlot slot : slots) before.put(slot, slot.assignedSnapshot());
        for (UISlot slot : found) slot.beginAssignment();

        for (UINode child : host.children()) {
            String wanted = child.get(Attribute.SLOT);
            UISlot target = null;
            for (UISlot slot : found) {
                if (slot.slotName().equals(wanted)) {
                    target = slot;
                    break;
                }
            }
            child.assignedSlot = target;
            if (target != null) target.assign(child);
        }
        slots = found;

        for (UISlot slot : found) {
            List<UINode> was = before.getOrDefault(slot, List.of());
            if (!was.equals(slot.assignedSnapshot())) {
                UIDocument doc = document;
                if (doc != null) doc.queue(slot::slotChanged);
                else slot.slotChanged();
            }
        }
    }

    /** Slots in THIS tree: light descendants, never inside a nested host's own shadow tree. */
    private static void collectSlots(UINode at, List<UISlot> into) {
        for (UINode child : at.children()) {
            if (child instanceof UISlot) into.add((UISlot) child);
            collectSlots(child, into);
        }
    }

    @Override
    public String toString() {
        return "<shadow-root of " + host + ">";
    }
}
