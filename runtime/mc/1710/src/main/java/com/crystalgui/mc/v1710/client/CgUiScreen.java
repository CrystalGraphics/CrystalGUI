package com.crystalgui.mc.v1710.client;

import javax.annotation.Nullable;

import com.crystalgraphics.api.render.CgRenderPipeline;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgui.app.crystaleditor.CrystalEditor;
import com.crystalgui.core.window.DesktopPresentation;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.desktop.host.HostSession;
import com.crystalgui.probe.ConnectionProbe;
import com.crystalgui.ui.dom.UIDocument;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

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
 * @see CgUiInput for why input does not arrive through this class at all
 */
public final class CgUiScreen extends GuiScreen {

    /** Set by the pump when an Escape reached the window and nothing consumed it. @see CgUiInput */
    private boolean closeRequested;

    private int framesPainted;

    /**
     * Declares this client's host and what its desktop opens with. Once, from client init.
     *
     * <p>Naming the application here is the whole of what a loader decides about it — which is
     * configuration, not logic. @see HostSession#install</p>
     */
    public static void install() {
        HostSession.install(new Host1710(), CrystalEditor.KIND);
    }

    // ── Opening ─────────────────────────────────────────────────────────────────────────────────

    /**
     * F6 — the desktop, with the editor <b>brought forward</b> whatever state it was left in.
     *
     * <p>Restores it from minimised, un-hides it if it was closed, raises it and gives it the keyboard.
     * "Open the editor" has to mean that or the key is unreliable.</p>
     */
    public static void openEditor() {
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
        open();
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

    static float frameDelta() {
        return HostSession.session().frameDelta();
    }

    /** Whether the desktop has been built — read by the pump before it touches anything. */
    static boolean isReady() {
        return HostSession.isInstalled() && HostSession.session().isBuilt();
    }

    void requestClose() {
        closeRequested = true;
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

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        HostSession session = HostSession.session();
        float delta = session.frameDelta();

        if (closeRequested) {
            closeRequested = false;
            mc.displayGuiScreen(null);
            // AND THE GAME TAKES THE MOUSE BACK. displayGuiScreen(null) calls setIngameFocus itself, but
            // only down a branch that also depends on the player being alive and a world being loaded --
            // and closing from here happens mid-frame, inside the drawScreen of the screen being closed.
            // Asking directly is idempotent when it already happened and is the difference between the
            // world coming back and the cursor still floating over an unresponsive game.
            if (mc.theWorld != null && mc.thePlayer != null) mc.setIngameFocus();

            // AND THE LAST FRAME OF THE FLICKER GOES HERE. Minecraft renders the overlay BEFORE it draws
            // the current screen, so on the frame this method closes itself the overlay hook has already
            // run and stood down -- returning here leaves that frame painted by nobody at all. By this
            // point displayGuiScreen has run onGuiClosed and nulled the current screen, so the
            // presentation is already HUD and the pinned windows can simply be painted now.
            session.paint(DesktopPresentation.HUD, delta, CgUiHud.HOST);
            return;
        }

        if (!session.isBuilt()) return;

        // THE CLOCK EVERY NODE PREVIEW READS. CgPreviewRenderer reuses the shared CgRenderPipeline
        // singleton's one CgFrameData rather than owning its own, which is what lets a Time node's
        // thumbnail animate off whatever clock the application already drives. Nothing else here drives
        // it, so without this line CG_TIME is permanently zero -- and a Multiply of Colour x SineTime
        // renders BLACK whatever colour you pick, which reads as "the preview does not recompile".
        CgRenderPipeline.getInstance().getFrameData().timeSecs =
                (float) (System.nanoTime() / 1_000_000_000.0);

        // INPUT, DRAINED PER FRAME RATHER THAN PER TICK.
        //
        // Minecraft delivers input to a screen from GuiScreen.handleInput(), called from runTick(),
        // which is driven by `new Timer(20.0F)`. So a GuiScreen's input is pumped at 20 Hz while
        // drawScreen renders at 60+. For clicks and typing that is invisible; for anything CONTINUOUS it
        // is not -- a resize handle or a split divider redraws three times a second per twelve frames of
        // motion, which reads as the UI being slow to paint rather than as input being sampled coarsely.
        //
        // Draining here costs nothing extra: it is the same loop handleInput runs, moved to the frame
        // clock. MC's own tick-rate call then finds an empty queue and does nothing.
        pumpInput();

        // ONE HOST TICK, before anything reads the workspace: the client is repaired if the wire moved,
        // and the mount is re-asked.
        session.frame(delta);

        // NAMED AS AN ARM, not painted straight at the compositor. It resolves to DESKTOP for as long as
        // this screen is the current one, so nothing about what is drawn changes -- what changes is that
        // no caller decides for itself whether it is its turn. @see DesktopPresentation
        session.paint(DesktopPresentation.DESKTOP, delta, PAINT_HOST);

        framesPainted++;
        // THE SETTLING CLOCK ON THIS ERA. Painted frames, not ticks: layout is what settles. The
        // captures hang off this, and so do the language jar's own steps -- since J8 the scripting
        // probes ship in a second jar and register themselves, so this host runs them without being
        // able to name them. @see CgUiAutoTest#onPainted
        CgUiAutoTest.onPainted(framesPainted);
    }

    /**
     * The screen's own bracket. Differs from the HUD's in what it hands back on the way out.
     *
     * <p><b>Minecraft writes GL state behind CrystalGraphics' back, so the shadow is dropped on the way
     * in.</b> {@code CgGlStateManager} keeps a CPU-side shadow and elides redundant calls, which is only
     * sound while every write goes through {@code CgGL} — and Minecraft sets blend, depth, texture and
     * program state straight through {@code GL11}/{@code OpenGlHelper} every frame. Getting it wrong
     * produces a MISSING GL call: wrong rendering, no exception. @see CrystalGraphics/AGENTS.md</p>
     *
     * <p><b>And on the way out Minecraft gets back the state its own renderer assumes.</b> It presents by
     * drawing one fixed-function quad — {@code Framebuffer.framebufferRender}: {@code glEnable(TEXTURE_2D)},
     * {@code bindFramebufferTexture()}, a Tessellator quad, and no {@code glUseProgram(0)} anywhere.
     * Fixed-function samples unit 0, but {@code bindFramebufferTexture} binds to whatever unit is
     * <em>currently</em> active — so if the UI leaves the active unit elsewhere, Minecraft binds its
     * screen texture where nothing reads it and the quad falls back to its vertex colour: a pure WHITE
     * window. The editor is sitting correctly in Minecraft's framebuffer the whole time, which is why a
     * {@code glReadPixels} capture shows a perfect UI while the screen shows nothing — the drawing was
     * never the broken part, the presenting was.</p>
     */
    private static final HostSession.PaintHost PAINT_HOST = new HostSession.PaintHost() {

        @Override
        public boolean ownScreenUp() {
            return Minecraft.getMinecraft().currentScreen instanceof CgUiScreen;
        }

        @Override
        public boolean anyScreenUp() {
            return Minecraft.getMinecraft().currentScreen != null;
        }

        @Override
        public void enter() {
            CgGlState.invalidateAllIfPresent();
        }

        @Override
        public void leave() {
            org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
            org.lwjgl.opengl.GL20.glUseProgram(0);
            org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_TEXTURE_2D);
            // The shadow cannot see the three lines above either, so tell it so rather than leaving it
            // describing a context that no longer exists.
            CgGlState.invalidateAllIfPresent();
        }
    };

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

    /**
     * Drains both input queues into the window, once per rendered frame.
     *
     * <p>The same body as {@code GuiScreen.handleInput}, on the frame clock instead of the tick clock.
     * Guarded on {@code isCreated()} exactly as the original is — a headless or shutting-down client has
     * neither device.</p>
     */
    private void pumpInput() {
        if (!isReady()) return;
        if (Mouse.isCreated()) {
            while (Mouse.next()) handleMouseInput();
        }
        if (Keyboard.isCreated()) {
            while (Keyboard.next()) handleKeyboardInput();
        }
    }

    /**
     * <b>The keyboard pump.</b> Minecraft calls this once per event from {@code GuiScreen.handleInput}
     * with that event current — presses <em>and releases</em>, since that loop is unconditional.
     *
     * <p>No {@code super}: it forwards to {@link #keyTyped}, whose vanilla body closes the screen on
     * Escape unconditionally. Escape is a cascade here — a live drag eats it, then the topmost popover,
     * then a modal — so the window gets first refusal and the screen closes only on what is left over.</p>
     */
    @Override
    public void handleKeyboardInput() {
        UIDocument window = window();
        if (window == null) return;
        // consumeKeyboardEvent returns TRUE when the UI CONSUMED the key -- that return exists precisely
        // so a host can act on what is LEFT OVER. Read as "unconsumed" it closed the screen on an Escape
        // the window had already dealt with and stayed open on one nobody wanted.
        boolean consumed = CgUiInput.pumpKeyboard(window);
        if (!consumed && Keyboard.getEventKeyState() && Keyboard.getEventKey() == Keyboard.KEY_ESCAPE) {
            closeRequested = true;
        }
    }

    /** <b>The mouse pump.</b> Once per event, same contract as above. */
    @Override
    public void handleMouseInput() {
        UIDocument window = window();
        if (window == null) return;
        // Raw device height, never GuiScreen.height -- see CgUiInput.pumpMouse.
        CgUiInput.pumpMouse(window, mc.displayHeight);
    }

    /**
     * <b>Deliberately empty — do not restore {@code super}.</b>
     *
     * <p>{@link GuiScreen#keyTyped} closes the screen on Escape unconditionally
     * ({@code if (keyCode == 1) this.mc.displayGuiScreen(null)}), which would sit above CrystalGUI's own
     * close-watcher cascade and shut the whole editor on the first Escape inside an open dropdown.
     * Escape reaches the window through the pump like every other key.</p>
     */
    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        // Intentionally empty. @see #handleKeyboardInput
    }
}
