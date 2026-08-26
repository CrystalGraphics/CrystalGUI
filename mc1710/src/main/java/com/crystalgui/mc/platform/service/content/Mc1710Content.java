package com.crystalgui.mc.platform.service.content;

import com.crystalgui.ui.elements.slot.NativeContent;
import com.crystalgui.ui.elements.slot.NativeProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

/**
 * The 1.7.10 handles, and the descriptors they are rebuilt from.
 *
 * <h3>Bound handles read through; display handles hold</h3>
 *
 * <p>{@link BoundSlot} is a <em>view</em> onto a vanilla {@link Slot}: it stores the slot, never the
 * stack, and answers from {@code slot.getStack()} every time it is asked. That is what lets a container
 * UI need no synchronisation of its own — the open {@link Container} is already synchronised by the
 * server, and a handle that cached would go stale the first time a hopper moved something.</p>
 *
 * <p>{@link DisplayItem} and {@link DisplayFluid} are the other case — a recipe view, an icon, a
 * palette entry — where there is no container to read through and the value <em>is</em> the content.</p>
 *
 * <h3>Descriptors name a place, not a thing</h3>
 *
 * <p>{@code slot:12} resolves against whatever container the player currently has open, which is the
 * only interpretation that survives being sent by a server: the two ends do not share object identity,
 * but they do share the container the server opened. A descriptor that named an item instead would
 * describe the contents at the moment the description was built and be wrong by the time it arrived.</p>
 */
public final class Mc1710Content {

    private Mc1710Content() {
    }

    static final String SLOT_PREFIX = "slot:";
    static final String ITEM_PREFIX = "item:";
    static final String FLUID_PREFIX = "fluid:";

    /** A live view onto a slot in the player's open container. */
    public static final class BoundSlot implements NativeContent {
        private final int index;

        public BoundSlot(int index) {
            this.index = index;
        }

        /** The slot right now, or null if no container is open or the index no longer exists. */
        ItemStack stack() {
            Slot slot = slotOf(index);
            return slot == null ? null : slot.getStack();
        }

        @Override
        public String descriptor() {
            return SLOT_PREFIX + index;
        }

        @Override
        public NativeProfile profile() {
            // MODEL unconditionally, and NOT decided per stack. Whether a given item is a 3D block model
            // or a flat sprite is the item's own business and can change with a resource pack, so asking
            // would be a guess that is wrong for exactly the case that breaks visibly -- a block drawn
            // with no depth buffer comes out inside-out. Depth costs a clear on a small target.
            return NativeProfile.MODEL;
        }

        @Override
        public boolean isEmpty() {
            return stack() == null;
        }
    }

    /** A standalone stack, for anything that is not showing a container. */
    public static final class DisplayItem implements NativeContent {
        private final ItemStack stack;

        public DisplayItem(ItemStack stack) {
            this.stack = stack;
        }

        ItemStack stack() {
            return stack;
        }

        @Override
        public String descriptor() {
            if (stack == null || stack.getItem() == null) return "";
            // Name, damage and size -- everything needed to rebuild the same stack. NBT is deliberately
            // absent: it has no bounded text form, and a description is content-addressed, so a large
            // tag would be re-hashed on every change of a value nothing here reads.
            return ITEM_PREFIX + net.minecraft.item.Item.itemRegistry.getNameForObject(stack.getItem())
                    + ":" + stack.getItemDamage() + ":" + stack.stackSize;
        }

        @Override
        public NativeProfile profile() {
            return NativeProfile.MODEL;
        }

        @Override
        public boolean isEmpty() {
            return stack == null || stack.getItem() == null || stack.stackSize <= 0;
        }
    }

    /** A fluid and how full its tank is. */
    public static final class DisplayFluid implements NativeContent {
        private final FluidStack fluid;
        private final int capacity;

        public DisplayFluid(FluidStack fluid, int capacity) {
            this.fluid = fluid;
            this.capacity = Math.max(1, capacity);
        }

        FluidStack fluid() {
            return fluid;
        }

        @Override
        public String descriptor() {
            if (fluid == null || fluid.getFluid() == null) return "";
            return FLUID_PREFIX + fluid.getFluid().getName() + ":" + fluid.amount + ":" + capacity;
        }

        @Override
        public NativeProfile profile() {
            // FLAT, and this is the whole reason profiles exist. A fluid is tiled atlas quads with depth
            // testing OFF -- give it MODEL's depth-tested, lit contract and it is not merely wasteful,
            // it is the wrong state for what is being drawn.
            return NativeProfile.FLAT;
        }

        @Override
        public boolean isEmpty() {
            return fluid == null || fluid.getFluid() == null || fluid.amount <= 0;
        }

        @Override
        public float fillFraction() {
            if (isEmpty()) return 0f;
            return Math.min(1f, fluid.amount / (float) capacity);
        }
    }

    /**
     * The slot at {@code index} in the player's open container, or null.
     *
     * <p>Null is ordinary rather than exceptional: a description can name a slot in a container that has
     * since closed, and a UI that outlives its container should draw an empty well rather than throw.</p>
     */
    static Slot slotOf(int index) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer player = mc == null ? null : mc.thePlayer;
        Container container = player == null ? null : player.openContainer;
        if (container == null || container.inventorySlots == null) return null;
        if (index < 0 || index >= container.inventorySlots.size()) return null;
        return (Slot) container.inventorySlots.get(index);
    }
}
