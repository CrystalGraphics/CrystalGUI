package com.crystalgui.mc;

import com.crystalgui.ui.UIContainer;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import org.lwjgl.input.Keyboard;

/**
 * Forge event handler that wires a CrystalGUI {@link UIContainer} into
 * Minecraft 1.7.10's input and render pipeline via Forge event bus.
 *
 * <p>Register an instance on {@code MinecraftForge.EVENT_BUS} and
 * {@code FMLCommonHandler.instance().bus()} to receive input and render
 * events. Call {@link #attach(UIContainer)} to start forwarding events
 * to a container, {@link #detach()} to stop.</p>
 *
 * <h3>Input ingress</h3>
 * <p>Subscribes to {@code InputEvent.MouseInputEvent} and
 * {@code InputEvent.KeyInputEvent}, which Forge fires <em>inside</em>
 * the {@code Mouse.next()} / {@code Keyboard.next()} while-loops in
 * {@code Minecraft.runTick()}. This means the current LWJGL event data
 * is still available via {@code Mouse.getEventX()},
 * {@code Keyboard.getEventKey()}, etc. The handler calls
 * {@link CgUiInputAdapter#forwardCurrentMouseEvent} and
 * {@link CgUiInputAdapter#forwardCurrentKeyEvent} to translate and
 * dispatch each event to the container's input system.</p>
 *
 * <h3>Render hook</h3>
 * <p>Subscribes to {@code RenderGameOverlayEvent.Post} with
 * {@code ElementType.ALL} to render the UI after all vanilla HUD
 * elements. Layout and rendering are delegated to
 * {@link CgUiRenderAdapter}.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * CgUiForgeEventHandler handler = new CgUiForgeEventHandler();
 * MinecraftForge.EVENT_BUS.register(handler);
 * FMLCommonHandler.instance().bus().register(handler);
 *
 * handler.attach(myContainer);
 * // ... later ...
 * handler.detach();
 * }</pre>
 */
public class CgUiForgeEventHandler {

    @Getter
    private UIContainer container;
    private final CgUiRenderAdapter renderAdapter = new CgUiRenderAdapter();
    /**
     * -- SETTER --
     *  Sets an optional key filter (e.g. ESC to close).
     */
    @Setter
    private CgUiInputAdapter.KeyFilter keyFilter;
    private boolean repeatEventsEnabled = false;

    /**
     * Attaches a container. Input and render events will be forwarded to it.
     */
    public void attach(UIContainer container) {
        this.container = container;
        Keyboard.enableRepeatEvents(true);
        repeatEventsEnabled = true;
    }

    /**
     * Detaches the current container. Events are no longer forwarded.
     */
    public void detach() {
        this.container = null;
        if (repeatEventsEnabled) {
            Keyboard.enableRepeatEvents(false);
            repeatEventsEnabled = false;
        }
    }

    public boolean isAttached() {
        return container != null;
    }

    // ── Forge input events (fired per-event inside Mouse.next()/Keyboard.next() loops) ──

    @SubscribeEvent
    public void onMouseInput(TickEvent.ClientTickEvent event) {
        if (container == null || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        ScaledResolution sr = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        final int scale = sr.getScaleFactor();
        final int displayHeight = mc.displayHeight;

        CgUiInputAdapter.drainAllEvents(container, (rawX, rawY) -> new float[]{
                        (float) rawX / scale,
                        (float) (displayHeight - rawY) / scale
                },keyFilter);
               
    }
    
    // ── Forge render event ──

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (container == null) return;
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) return;

        ScaledResolution sr = event.resolution;
        renderAdapter.renderContainer(container,
                sr.getScaledWidth(), sr.getScaledHeight());
    }
}
