package com.crystalgui.mc.client;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.mc.platform.service.content.Mc1710Content;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.slot.FluidSlot;
import com.crystalgui.ui.elements.slot.ItemSlot;
import com.crystalgui.ui.elements.desktop.WindowFrame;
import com.crystalgui.ui.elements.desktop.WindowPolicy;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

/**
 * <b>The only thing that can exercise {@code Mc1710NativeContentService} at all.</b>
 *
 * <p>Off unless {@code -Dcrystalgui.slot.probe=true}; enable with
 * {@code ./gradlew :mc1710:runClient -PcgSlotProbe}.</p>
 *
 * <h3>Why this exists rather than a test</h3>
 *
 * <p>Nothing in the shipped UI constructs a slot, so the 1.7.10 renderer compiled and had <b>never
 * run</b>. Every part of the seam that is engine-side is covered by the {@code cgui-slot} harness scene
 * driven by a stand-in; what no test and no harness can reach is the half that only exists inside a
 * Minecraft client — {@code RenderItem}, the block atlas, {@code RenderHelper}'s GUI lighting, and
 * vanilla's tooltip renderer. This opens a window with those on screen so they can be looked at.</p>
 *
 * <h3>What it puts up, and why each one</h3>
 *
 * <ul>
 *   <li><b>A block</b> — the case the whole {@code MODEL} profile exists for. A block item is real 3D
 *       geometry, so it is the only content that can show a depth fault. A flat sprite renders
 *       identically with a broken depth buffer and proves nothing.</li>
 *   <li><b>A flat sprite item</b> — the control, and the common case.</li>
 *   <li><b>A damaged tool</b> — exercises {@code renderItemOverlayIntoGUI}, the durability bar the host
 *       draws and we deliberately do not reproduce.</li>
 *   <li><b>A stack of 64</b> — the count overlay, same reason.</li>
 *   <li><b>Water at several fills</b> — the {@code FLAT} profile, the block atlas, and tiling.</li>
 *   <li><b>An empty slot</b> — the well with nothing in it, which must not be confused with the
 *       {@code __unsupported__} face.</li>
 * </ul>
 *
 * <p>Every stack is built from the registry by name rather than from {@code Blocks}/{@code Items}
 * constants, so a missing entry degrades to an empty slot and a log line instead of throwing during
 * screen construction.</p>
 */
public final class CgUiSlotProbe {

    /** @see CgUiSlotProbe */
    public static final boolean ENABLED = Boolean.getBoolean("crystalgui.slot.probe");

    private CgUiSlotProbe() {
    }

    /**
     * Opens the probe window, if armed.
     *
     * <p>Called from {@link CgUiScreen}'s desktop build. Returns quietly when off, so the one call site
     * needs no guard of its own.</p>
     */
    public static void contribute(UIWindow window) {
        if (!ENABLED || window == null) return;

        WindowFrame frame = window.openWindow(new WindowFrame("Item & Fluid Slots"));
        frame.setPolicy(WindowPolicy.HIDE_ON_CLOSE).setKey("probe:slots");
        frame.setContent(buildContent());
        CrystalGuiCore.LOGGER.info("[slot-probe] window opened; look for a BLOCK in row 1 -- a flat "
                + "sprite renders the same whether or not depth works");
    }

    private static UIElement buildContent() {
        UIElement root = new UIElement()
                .layout(l -> l.flexDirection(FlexDirection.COLUMN).paddingAll(10).gapAll(8));

        root.addChild(label("Items - block, sprite, damaged tool, stack of 64, empty"));
        UIElement items = row();
        items.addChild(itemSlot(stack("minecraft:stone", 1, 0)));
        items.addChild(itemSlot(stack("minecraft:stick", 1, 0)));
        // Half-damaged, so the durability bar is unmistakably present rather than full or absent.
        items.addChild(itemSlot(damagedSword()));
        items.addChild(itemSlot(stack("minecraft:cobblestone", 64, 0)));
        items.addChild(new ItemSlot());
        root.addChild(items);

        root.addChild(label("Fluid - water at 25%, 50%, 100%"));
        UIElement fluids = row();
        fluids.addChild(fluidSlot(0.25f));
        fluids.addChild(fluidSlot(0.5f));
        fluids.addChild(fluidSlot(1f));
        root.addChild(fluids);

        root.addChild(label("Hover any slot for the host's own tooltip"));
        return root;
    }

    private static UIElement row() {
        return new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(4));
    }

    private static UIElement label(String text) {
        return new UIText(text);
    }

    private static ItemSlot itemSlot(ItemStack stack) {
        ItemSlot slot = new ItemSlot();
        if (stack != null) slot.bind(new Mc1710Content.DisplayItem(stack));
        return slot;
    }

    private static FluidSlot fluidSlot(float fill) {
        FluidSlot slot = new FluidSlot();
        if (FluidRegistry.WATER != null) {
            int capacity = 1000;
            slot.bind(new Mc1710Content.DisplayFluid(
                    new FluidStack(FluidRegistry.WATER, Math.round(capacity * fill)), capacity));
        }
        return slot;
    }

    /**
     * A stack by registry name, or null.
     *
     * <p>Null rather than a throw: this runs during screen construction, and a probe that took the
     * editor down because one registry name moved would be worse than an empty slot and a log line.</p>
     */
    private static ItemStack stack(String name, int count, int damage) {
        Item item = (Item) Item.itemRegistry.getObject(name);
        if (item == null) {
            CrystalGuiCore.LOGGER.warn("[slot-probe] no such item: {}", name);
            return null;
        }
        return new ItemStack(item, count, damage);
    }

    private static ItemStack damagedSword() {
        ItemStack sword = stack("minecraft:iron_sword", 1, 0);
        if (sword == null) return null;
        sword.setItemDamage(sword.getMaxDamage() / 2);
        return sword;
    }
}
