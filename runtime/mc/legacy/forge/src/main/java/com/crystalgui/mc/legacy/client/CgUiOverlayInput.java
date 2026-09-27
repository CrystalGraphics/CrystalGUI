package com.crystalgui.mc.legacy.client;

import java.io.IOException;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.window.DesktopPresentation;
import com.crystalgui.desktop.host.HostSession;
import com.crystalgui.desktop.host.ScreenOverlay;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * Offers a foreign screen's input to pinned windows first. A courier: who gets an event is decided in
 * {@code core/}, by {@link HostSession#offerMouse} and then {@link ScreenOverlay}.
 *
 * <p>Forge 1.8–1.12.2 posts {@code GuiScreenEvent.MouseInputEvent.Pre} and {@code KeyboardInputEvent.Pre}
 * once per queued event from inside {@code GuiScreen.handleInput}, standing on that event, and skips the
 * screen's own handler when one is cancelled. {@link CgUiHud} cancels exactly the events the desktop takes.
 * No mixin: 1.7.10 needed one only because its Forge had neither event.</p>
 *
 * <ul>
 *   <li>{@code handleInput} runs from {@code runTick} at 20 Hz, so {@link #drain} also calls it on the
 *       render tick: both UIs get per-frame input, and the tick's own call finds an empty queue.</li>
 *   <li>Offer only while an event is current: LWJGL's {@code getEvent*} describe the event the last
 *       {@code next()} produced.</li>
 * </ul>
 */
@SideOnly(Side.CLIENT)
public final class CgUiOverlayInput {

    /** LWJGL reports a notch as ±120 the other way up; the engine wants ±1, positive rolled DOWN. */
    private static final float MOUSE_SCROLL_NORMALIZE = -1f / 120f;

    private static boolean announced;

    private CgUiOverlayInput() {
    }

    /** Whether pinned windows are offered this screen's input. One read per frame when nothing is pinned. */
    public static boolean wants() {
        return CgUiHud.presentation() == DesktopPresentation.OVERLAY;
    }

    /** Offers the mouse event LWJGL is standing on; true when the desktop took it. */
    public static boolean offerCurrentMouse(GuiScreen screen) {
        announce(screen);
        return HostSession.isInstalled() && HostSession.session().offerMouse(CgUiHud.HOST, Mouse.isGrabbed(),
                Mouse.getEventX(),
                // LWJGL measures from the bottom of the display, the engine from the top.
                Minecraft.getMinecraft().displayHeight - Mouse.getEventY(),
                Mouse.getEventButton(),
                Mouse.getEventButtonState(),
                Mouse.getEventDWheel() * MOUSE_SCROLL_NORMALIZE);
    }

    /** Offers the keyboard event LWJGL is standing on; true when the desktop took it. */
    public static boolean offerCurrentKey(GuiScreen screen) {
        announce(screen);
        return HostSession.isInstalled() && HostSession.session().offerKey(
                Keyboard.getEventKey(), Keyboard.getEventCharacter(), Keyboard.getEventKeyState());
    }

    /**
     * Drains {@code screen}'s input on the render tick, through the same Pre events as the game tick.
     * A failure is logged and the frame goes on: this is inside the game's input path.
     */
    public static void drain(GuiScreen screen) {
        if (screen == null) return;
        try {
            screen.handleInput();
        } catch (IOException | RuntimeException | LinkageError e) {
            CrystalGuiCore.LOGGER.error("[cgui] overlay input failed; the screen keeps its own input", e);
        }
    }

    /** Once: a hook that never fires and a feature that was never wired look the same from outside. */
    private static void announce(GuiScreen screen) {
        if (announced || screen == null) return;
        announced = true;
        CrystalGuiCore.LOGGER.info("[cgui] overlay input is live: pinned windows are taking events from {}",
                screen.getClass().getName());
    }
}
