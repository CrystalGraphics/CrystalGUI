package com.crystalgui.mc.client;

import com.crystalgraphics.api.render.CgRenderPipeline;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.mc.platform.service.content.Mc1710Content;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.Ui;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.slot.FluidSlot;
import com.crystalgui.ui.elements.slot.ItemSlot;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * <b>A full-screen test GUI, and the only thing that runs the 1.7.10 item and fluid renderer.</b>
 *
 * <p>Opened with <b>F8</b>, or with {@code -PcgSlotProbe} which arms that key. Nothing else in the
 * shipped UI constructs a slot, so {@code Mc1710NativeContentService} compiled and had <b>never
 * run</b>.</p>
 *
 * <h3>Its own screen, deliberately not a window on the editor's desktop</h3>
 *
 * <p>A test surface must not share state with the thing it is testing. On the desktop it would inherit
 * a persisted window arrangement, a z-order it does not control, and a compositor whose own behaviour
 * is under active development — and the first attempt proved the point: opened during the desktop
 * build it landed <em>underneath</em> the editor, which is most of the screen, and looked exactly like
 * the flag having done nothing. Here there is one root, one window and nothing else on screen, so
 * anything wrong is the slot's.</p>
 *
 * <h3>What it puts up, and why each one</h3>
 *
 * <ul>
 *   <li><b>A block</b> — the case the {@code MODEL} profile exists for. A block item is real 3D
 *       geometry and the only content that can show a depth fault; a flat sprite renders identically
 *       with a broken depth buffer and proves nothing.</li>
 *   <li><b>A flat sprite</b> — the control, and the common case.</li>
 *   <li><b>A damaged tool</b> and <b>a stack of 64</b> — {@code renderItemOverlayIntoGUI}, the
 *       durability bar and count the host draws and we deliberately do not reproduce.</li>
 *   <li><b>Water at three fills</b> — the {@code FLAT} profile, the block atlas, and tiling.</li>
 *   <li><b>An empty slot</b> — the well with nothing in it, which must stay distinguishable from the
 *       {@code __unsupported__} face.</li>
 * </ul>
 *
 * <p>Stacks resolve from the registry by name and a miss degrades to an empty slot and a log line, so
 * one moved registry name cannot take the screen down.</p>
 */
public final class CgUiSlotScreen extends GuiScreen implements NativeTooltipHost {

    /** @see CgUiSlotScreen */
    public static final boolean ENABLED = Boolean.getBoolean("crystalgui.slot.probe");

    private static final String ROOT_CLASS = "crystalgui-slot-probe";

    /**
     * The host sizes the root and nothing else will — {@code UIWindow.init} only resets caches, so
     * without this the root sizes to content and every percentage inside resolves against zero. Same
     * rule and same reason as {@code CgUiScreen.HOST_STYLES}.
     */
    private static final String HOST_STYLES =
            "." + ROOT_CLASS + " { width: 100%; height: 100%; padding-all: 16px; gap-all: 10px;"
            + " background-color: #E0101014; }"
            + ".slot-row { flex-direction: row; gap-all: 4px; }";

    private UIWindow uiWindow;
    private boolean closeRequested;

    /** Shows the screen. Ignores the request when the probe is not armed. */
    public static void open() {
        if (!ENABLED) return;
        Minecraft.getMinecraft().displayGuiScreen(new CgUiSlotScreen());
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        if (uiWindow != null) return;

        UIElement root = new UIElement()
                .layout(l -> l.flexDirection(FlexDirection.COLUMN));
        root.addClass(ROOT_CLASS);
        build(root);

        uiWindow = new UIWindow(Ui.of(root));
        uiWindow.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        // ORE, because the user-agent sheet gives a slot GEOMETRY and deliberately no colour -- so an
        // unthemed slot is an invisible box, and a probe showing invisible boxes cannot tell a missing
        // well from a missing item. This also puts the ore.css rules themselves under test, which
        // nothing else does in a client.
        uiWindow.getStyleEngine().addStylesheet(
                com.crystalgui.style.sheet.StyleSheetRegistry.of("crystalgui:ore"));
        uiWindow.getStyleEngine().addStylesheet(StyleSheet.parse(HOST_STYLES));
        CrystalGuiCore.LOGGER.info("[slot-probe] screen open; the BLOCK in row 1 is the depth case -- "
                + "a flat sprite renders the same whether or not depth works");
    }

    private static void build(UIElement root) {
        root.addChild(new UIText("CrystalGUI slot probe -- Escape to close"));

        root.addChild(new UIText("Items: block, sprite, damaged tool, stack of 64, empty"));
        UIElement items = row();
        items.addChild(itemSlot(stack("minecraft:stone", 1, 0)));
        items.addChild(itemSlot(stack("minecraft:stick", 1, 0)));
        items.addChild(itemSlot(damagedSword()));
        items.addChild(itemSlot(stack("minecraft:cobblestone", 64, 0)));
        items.addChild(new ItemSlot());
        root.addChild(items);

        root.addChild(new UIText("Fluid: water 25%, 50%, 100%   then lava 100% -- compare against a real slot"));
        UIElement fluids = row();
        fluids.addChild(fluidSlot("water", 0.25f));
        fluids.addChild(fluidSlot("water", 0.5f));
        fluids.addChild(fluidSlot("water", 1f));
        // LAVA, full, beside the water. It is the one fluid whose correct appearance everybody already
        // knows by heart, so it is the cheapest possible check on the atlas and the tiling: if this does
        // not look like the lava in an inventory slot, the fluid path is wrong regardless of what water
        // happens to look like.
        fluids.addChild(fluidSlot("lava", 1f));
        root.addChild(fluids);

        root.addChild(new UIText("Hover any slot for the host's own tooltip"));
    }

    private static UIElement row() {
        UIElement row = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW));
        row.addClass("slot-row");
        return row;
    }

    private static ItemSlot itemSlot(ItemStack stack) {
        ItemSlot slot = new ItemSlot();
        if (stack != null) slot.bind(new Mc1710Content.DisplayItem(stack));
        return slot;
    }

    private static FluidSlot fluidSlot(String name, float fill) {
        FluidSlot slot = new FluidSlot();
        net.minecraftforge.fluids.Fluid fluid = FluidRegistry.getFluid(name);
        if (fluid == null) {
            CrystalGuiCore.LOGGER.warn("[slot-probe] no such fluid: {}", name);
            return slot;
        }
        int capacity = 1000;
        slot.bind(new Mc1710Content.DisplayFluid(
                new FluidStack(fluid, Math.round(capacity * fill)), capacity));
        return slot;
    }

    /** A stack by registry name, or null — a miss must not take the screen down mid-construction. */
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

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        if (closeRequested) {
            closeRequested = false;
            mc.displayGuiScreen(null);
            if (mc.theWorld != null && mc.thePlayer != null) mc.setIngameFocus();
            return;
        }
        if (uiWindow == null) return;

        CgRenderPipeline.getInstance().getFrameData().timeSecs =
                (float) (System.nanoTime() / 1_000_000_000.0);
        pumpInput();

        // RAW DEVICE PIXELS, never ScaledResolution. UIWindow applies its own uiScale through
        // getRootTransform(), which is the single definition of what that means -- feeding it
        // pre-scaled numbers draws correctly and lands every click somewhere else.
        uiWindow.init(mc.displayWidth, mc.displayHeight);

        // Minecraft writes GL behind CrystalGraphics' back, so the shadow must be dropped before and
        // after. Getting this wrong produces a MISSING GL CALL: wrong rendering, no exception.
        CgGlState.invalidateAllIfPresent();
        uiWindow.paintFrame();

        // Hand the context back the way Minecraft expects to find it. It presents with one
        // fixed-function quad and binds its framebuffer texture to whatever unit is CURRENTLY active,
        // so leaving the active unit elsewhere yields a pure white window.
        org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
        org.lwjgl.opengl.GL20.glUseProgram(0);
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_TEXTURE_2D);
        CgGlState.invalidateAllIfPresent();
    }

    private void pumpInput() {
        if (Mouse.isCreated()) {
            while (Mouse.next()) handleMouseInput();
        }
        if (Keyboard.isCreated()) {
            while (Keyboard.next()) handleKeyboardInput();
        }
    }

    @Override
    public void handleMouseInput() {
        if (uiWindow == null) return;
        CgUiInput.pumpMouse(uiWindow, mc.displayHeight);
    }

    @Override
    public void handleKeyboardInput() {
        if (uiWindow == null) return;
        boolean consumed = CgUiInput.pumpKeyboard(uiWindow);
        if (!consumed && Keyboard.getEventKeyState() && Keyboard.getEventKey() == Keyboard.KEY_ESCAPE) {
            closeRequested = true;
        }
    }

    /** Deliberately empty — {@link #handleKeyboardInput} owns Escape, and {@code GuiScreen}'s own
     * handler would close the screen out from under a widget that wanted the key. */
    @Override
    protected void keyTyped(char typedChar, int keyCode) {
    }

    @Override
    public void drawNativeItemTooltip(ItemStack stack, int scaledX, int scaledY) {
        renderToolTip(stack, scaledX, scaledY);
    }

    /** The world keeps ticking. A probe is something to look at beside a running game, not a pause. */
    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
