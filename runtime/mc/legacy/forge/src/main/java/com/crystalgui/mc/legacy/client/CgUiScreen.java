package com.crystalgui.mc.legacy.client;

import com.crystalgraphics.platform.world.CgPlayers;
import com.crystalgraphics.world.CgWorldQueries;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.platform.input.CgKeyCodes;
import javax.annotation.Nullable;

import com.crystalgui.app.crystaleditor.CrystalEditor;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.desktop.host.HostSession;
import com.crystalgui.mc.legacy.probe.CgUiAutoTest;
import com.crystalgui.probe.ConnectionProbe;
import com.crystalgui.ui.dom.UIDocument;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.input.Keyboard;

/**
 * <b>The Minecraft screen CrystalGUI draws into</b> — a {@code GuiScreen} over a {@link HostSession}.
 *
 * <p>Minecraft's screen lifecycle mapped onto the session's, plus the input pump and the GL handoff
 * this version of the game needs. Which applications exist, what opens, how big a first-run window is
 * and when one is raised are all the session's, and the same answer on every loader.</p>
 *
 * <h3>Closing this screen does not close anything</h3>
 *
 * <p>The desktop, its windows and every running application outlive it — the screen is a viewport onto
 * them, so pressing Escape and reopening gets the same unsaved documents back. Only game shutdown
 * disposes the session.</p>
 *
 * <p>Its input reaches the desktop through CrystalGraphics' {@code SCREEN} input stage.</p>
 */
public final class CgUiScreen extends GuiScreen {

    /** Set when an Escape reached the desktop and nothing consumed it. @see #keyTyped */
    private boolean closeRequested;

    private int framesPainted;

    /**
     * Declares this client's host and what its desktop opens with. Once, from client init.
     *
     * <p>Naming the application here is the whole of what a loader decides about it — which is
     * configuration, not logic. @see HostSession#install</p>
     */
    public static void install() {
        HostSession.install(new HostLegacy(), CrystalEditor.KIND);
    }

    // ── Opening ─────────────────────────────────────────────────────────────────────────────────

    /**
     * F6 — the desktop, with the editor <b>brought forward</b> whatever state it was left in.
     *
     * <p>Restores it from minimised, un-hides it if it was closed, raises it and gives it the keyboard.
     * "Open the editor" has to mean that or the key is unreliable.</p>
     */
    public static void openEditor() {
        if (!HostSession.mayOpen()) return;
        HostSession.session().requestApplication();
        open();
    }

    /**
     * F7 — the desktop, and <b>nothing else touched</b>.
     *
     * <p>Whatever is on it is what comes back, minimised windows included. Without this there is no way
     * to reach the desktop except by putting an application in front of it, which is not a desktop.</p>
     */
    public static void openDesktop() {
        if (HostSession.mayOpen()) open();
    }

    private static void open() {
        // THE PROBE OWNS THE DESKTOP WHILE IT RUNS. Opening it claims ui/openWindow for the client
        // window host, which the probe's own session is holding. Every caller comes through here --
        // the keybind, the worked example, and the probe itself. @see ConnectionProbe#isDrivingDesktop
        if (ConnectionProbe.enabled() && !ConnectionProbe.isDrivingDesktop()) {
            CrystalGuiCore.LOGGER.info("[cgui] not opening the desktop: the connection probe is driving");
            return;
        }
        Minecraft.getMinecraft().displayGuiScreen(new CgUiScreen());
    }

    // ── What the rest of this loader reads ──────────────────────────────────────────────────────

    /**
     * The one {@code UIDocument} on the client, or null before the screen has ever been opened.
     *
     * <p><b>There is exactly one, and that is the point.</b> Anything with a UI to show opens a window
     * on <em>this</em> desktop rather than standing up a second {@code GuiScreen} — a second screen is a
     * second claim on the input pump, the GL handoff, the desktop's persistence and the modal stack, and
     * only one of them can be in front.</p>
     */
    @Nullable
    public static UIDocument window() {
        return HostSession.isInstalled() ? HostSession.session().document() : null;
    }

    /** This client's compositor, or null before the screen has ever been opened. */
    @Nullable
    public static Desktop desktop() {
        return HostSession.isInstalled() ? HostSession.session().desktop() : null;
    }

    /** Escape: the desktop plays itself off first, and the frame after it has, this closes. @see HostSession#requestHide */
    void requestClose() {
        if (!HostSession.session().requestHide(() -> closeRequested = true)) closeRequested = true;
    }

    /** Quits the editor at game shutdown. Not called on close — see the class note. */
    public static void disposeAll() {
        if (HostSession.isInstalled()) HostSession.session().dispose();
    }

    // ── Minecraft's screen lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void initGui() {
        // Held-key repeat. Without it a held arrow moves the caret exactly once and backspace deletes
        // one character however long it is held, which reads as the editor being unresponsive rather
        // than as a missing flag.
        Keyboard.enableRepeatEvents(true);
        HostSession.session().shown();
    }

    /**
     * Draws nothing: the desktop is CrystalGraphics' {@code SCREEN} stage's, which fires once this has drawn, and runs
     * the host tick. Its input arrived before this, through the {@code SCREEN} input stage.
     */
    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        if (closeRequested) {
            closeRequested = false;
            mc.displayGuiScreen(null);
            // AND THE GAME TAKES THE MOUSE BACK. displayGuiScreen(null) calls setIngameFocus itself, but
            // only down a branch that also depends on the player being alive and a world being loaded --
            // and closing from here happens mid-frame, inside the drawScreen of the screen being closed.
            // Asking directly is idempotent when it already happened and is the difference between the
            // world coming back and the cursor still floating over an unresponsive game.
            if ((CgWorldQueries.clientLevel() != null && CgPlayers.local() != null)) mc.setIngameFocus();
            // The HUD's turn has passed this frame: SCREEN, firing after this, paints the pinned windows instead.
            return;
        }
        if (!HostSession.session().isBuilt()) return;
        framesPainted++;
        // THE SETTLING CLOCK ON THIS ERA. Painted frames, not ticks: layout is what settles. The
        // captures hang off this, and so do the language jar's own steps -- since J8 the scripting
        // probes ship in a second jar and register themselves, so this host runs them without being
        // able to name them. @see CgUiAutoTest#onPainted
        CgUiAutoTest.onPainted(framesPainted);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        // THE COMPOSITOR GOES OFF SCREEN, and every window is retained exactly as it is. Detaching is
        // also what drops the input state: the hover, the press target and any live drag would otherwise
        // still describe a screen that is no longer up. Everything writes itself on the way out — the
        // desktop its arrangement, the editor its session — each because it went off screen.
        HostSession.session().hidden();
    }

    /**
     * <b>Does NOT pause single-player</b> — the game runs underneath the desktop.
     *
     * <p>Pausing stops the integrated server ticking, so the connection is never pumped and every
     * request this screen makes dies at its timeout. A workspace that appears empty in single-player and
     * works in multiplayer is this line. It also read as a fault rather than a policy — reported as the
     * desktop <em>freezing</em> the game, which is what a paused world looks like from inside it.</p>
     *
     * <p>The player still cannot move, and that is unrelated: {@code GuiScreen.allowUserInput} is false,
     * so game input is gated out while any screen is up. What changes here is only whether the WORLD
     * ticks.</p>
     */
    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // ── Input ───────────────────────────────────────────────────────────────────────────────────
    // Every event reaches the desktop through CrystalGraphics' SCREEN input stage before this screen sees it
    // (HostInputListener); what arrives here is what the desktop left.

    /**
     * A key the desktop dispatched on its own thread and left, handed back a frame late: what this screen does with
     * one nothing wanted. @see #keyTyped
     */
    void keyLeftByDesktop(CgSystemInput.Keyboard.Event key) {
        if (key.pressed() && key.key() == CgKeyCodes.KEY_ESCAPE) requestClose();
    }

    /**
     * An Escape the desktop left closes it; nothing else does here. <b>Never {@code super}</b>: vanilla's closes the
     * screen at once on Escape, past the desktop's own play-off.
     */
    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) requestClose();
    }
}
