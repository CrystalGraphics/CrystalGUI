package com.crystalgui.mc.client;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.desktop.DesktopPresentation;
import com.crystalgui.ui.elements.desktop.ScreenOverlay;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * Drains LWJGL for a foreign screen, giving pinned windows first refusal — M16 §26.5/§26.6.
 *
 * <p>Called only from {@code MixinGuiScreen}, which cancels {@code GuiScreen.handleInput} when this
 * wants the events. <b>This is a courier and not a decision-maker</b>: every question about who gets an
 * event is answered by {@link ScreenOverlay} in {@code core/}, so that the arbitration has one
 * implementation rather than one per Minecraft version. What lives here is the part that genuinely is
 * per-version — how to read an event out of LWJGL2 and how to hand back the ones we declined.</p>
 *
 * <h3>On the render tick, not the game tick</h3>
 *
 * <p>{@code GuiScreen.handleInput} runs from {@code Minecraft.runTick} at <b>20 Hz</b> while the game
 * renders at 60+, so this also runs once per FRAME — the same move {@code CgUiScreen.pumpInput} makes
 * for our own screen and for the same reason: a drag sampled at tick rate reads as the UI being slow to
 * paint rather than as input being coarse.</p>
 *
 * <p><b>It is called from two places and that is deliberate</b> — here on the render tick for
 * responsiveness, and from {@code MixinGuiScreen} so that {@code runTick}'s own in-world drain (the one
 * guarded by {@code allowUserInput}, which the inventory sets) finds the empty queue vanilla would have
 * left it. Idempotent by construction: a drain of an empty queue is a loop that does not run.</p>
 *
 * <p><b>The foreign screen gets the improvement too</b>, because it is handed what the desktop declined
 * from this same loop — so nothing about it is degraded by being intercepted. And this runs BEFORE the
 * frame is drawn rather than from inside a draw, which matters: a screen's own handler can reach
 * {@code displayGuiScreen} through a button, and swapping the current screen from inside its own render
 * is a hazard we would be introducing on somebody else's behalf.</p>
 *
 * <h3>Draining is the forwarding</h3>
 *
 * <p>The loop below is {@code GuiScreen.handleInput}'s own, with an arbitration in the middle. That
 * shape is forced rather than chosen: {@code Mouse.getEventX()} and friends describe <b>the current
 * event</b>, the one the last {@code Mouse.next()} produced — so the screen's own handler can only see
 * an event while we are still standing on it. Collecting events to replay later would hand them back
 * with the wrong coordinates, which is the kind of bug that looks like a bad conversion.</p>
 */
@SideOnly(Side.CLIENT)
public final class CgUiOverlayInput {

    /**
     * LWJGL reports a notch as ±120 and measures it the other way up; the engine wants ±1, positive
     * for a wheel rolled DOWN.
     *
     * <p><b>The sign is the whole of it and it was dropped once.</b> This was written as {@code 1/120f},
     * copying the magnitude from {@code CgUiInput} and not the {@code NORMALIZE_TOP_LEFT_ORIGIN} factor
     * beside it — so scrolling in an overlay window ran backwards. The engine's convention is stated on
     * {@code ScrollerView}, which is the only place it is written down: a positive notch means down.</p>
     */
    private static final float MOUSE_SCROLL_NORMALIZE = -1f / 120f;

    /** @see #drainInto */
    private static boolean announced;

    private CgUiOverlayInput() {
    }

    /**
     * Whether pinned windows should be offered this screen's input at all.
     *
     * <p><b>One boolean read on every frame of every session where nothing is pinned</b>, which is the
     * budget a mixin on a method this hot deserves. Everything expensive is behind it.</p>
     */
    public static boolean wants() {
        return CgUiHud.presentation() == DesktopPresentation.OVERLAY;
    }

    /**
     * Drains the queue, offering each event to the desktop and giving {@code screen} what it declined.
     *
     * <p>Failure here drops back to Minecraft's own handling for the rest of the frame rather than
     * propagating: this runs inside the game's input path, and an exception would leave the screen
     * unable to receive anything at all — including whatever the player would press to escape it.</p>
     */
    public static void drainInto(GuiScreen screen) {
        UIWindow window = CgUiScreen.window();
        if (window == null || screen == null) return;
        if (!announced) {
            announced = true;
            // "LIVE" AND "INERT" LOOK IDENTICAL from outside, and a mixin that silently fails to apply
            // looks exactly like a feature that was never wired up. One line, once, so a log can answer
            // which it was.
            CrystalGuiCore.LOGGER.info("[cgui] overlay input is live: pinned windows are taking events "
                    + "from {}", screen.getClass().getName());
        }
        ScreenOverlay overlay = window.screenOverlay();
        Minecraft mc = Minecraft.getMinecraft();

        try {
            while (Mouse.next()) {
                int button = Mouse.getEventButton();
                float wheel = Mouse.getEventDWheel() * MOUSE_SCROLL_NORMALIZE;
                boolean consumed = overlay.offerMouse(
                        Mouse.getEventX(),
                        // BOTTOM-UP TO TOP-DOWN. LWJGL measures from the bottom of the display and the
                        // engine from the top -- the same conversion CgUiInput.pumpMouse does, and
                        // getting it wrong places everything neatly somewhere wrong.
                        mc.displayHeight - Mouse.getEventY(),
                        button,
                        Mouse.getEventButtonState(),
                        wheel);
                if (!consumed) screen.handleMouseInput();
            }
            while (Keyboard.next()) {
                boolean consumed = overlay.offerKey(
                        Keyboard.getEventKey(), Keyboard.getEventCharacter(), Keyboard.getEventKeyState());
                if (!consumed) screen.handleKeyboardInput();
            }
        } catch (RuntimeException | LinkageError e) {
            CrystalGuiCore.LOGGER.error("[cgui] overlay input failed; the screen keeps its own input", e);
        }
    }
}
