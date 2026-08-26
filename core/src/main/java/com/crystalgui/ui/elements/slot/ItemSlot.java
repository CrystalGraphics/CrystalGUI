package com.crystalgui.ui.elements.slot;

/**
 * <b>A slot showing an item, drawn by the host.</b>
 *
 * <p>Everything structural is {@link NativeContentSlot}'s; this adds a tag for the cascade and a name
 * for the thing. The item itself — model, enchantment glint, stack count, durability bar, cooldown
 * overlay, and whatever other mods have hooked into item rendering — is drawn by
 * {@link NativeContentService}, because reproducing any of it here would be a worse copy that drifts
 * every time the host or another mod changes.</p>
 *
 * <h3>Bound, not filled</h3>
 *
 * <pre>{@code
 * ItemSlot slot = new ItemSlot();
 * slot.bind(service.resolve("slot:12"));   // a live inventory slot; contents follow the container
 * }</pre>
 *
 * <p>A slot is a <em>view</em>. It never holds an item, so it cannot disagree with the container it is
 * showing, and a container UI needs no synchronisation of its own — the host already has one. See
 * {@link NativeContent}.</p>
 *
 * <h3>Sizing</h3>
 *
 * <p>Geometry is the stylesheet's, and the user-agent sheet gives the conventional 18&#215;18 with 1px of
 * padding, so the content box is the 16&#215;16 an item is drawn at. Nothing here writes a pixel value —
 * a slot in a compact inventory and one in a recipe viewer differ by a CSS rule, not by a constructor
 * argument.</p>
 */
public class ItemSlot extends NativeContentSlot {

    /** Styling hook, alongside the shared {@link NativeContentSlot#SLOT_CLASS}. */
    public static final String ITEM_SLOT_CLASS = "__item-slot__";

    public ItemSlot() {
        addClass(ITEM_SLOT_CLASS);
    }

    /**
     * As {@link NativeContentSlot#bind}, narrowed so a fluent chain keeps this type.
     *
     * <p>No type check on the handle: what an {@link ItemSlot} can show is the platform's judgement, not
     * this class's. A handle that turns out to be a fluid draws as a fluid, which is a surprising UI and
     * not a crash — and refusing it here would mean {@code core} deciding what an item is, which is the
     * one thing this whole seam exists to avoid.</p>
     */
    @Override
    public ItemSlot bind(NativeContent content) {
        super.bind(content);
        return this;
    }
}
