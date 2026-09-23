package com.crystalgui.mc.modern.client;

import javax.annotation.Nullable;

import com.crystalgraphics.api.render.CgRenderPipeline;
import com.crystalgui.app.crystaleditor.CrystalEditor;
import com.crystalgui.core.window.DesktopPresentation;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.app.Application;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.desktop.host.HostSession;
import com.crystalgui.probe.ConnectionProbe;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.workbench.Workbench;
import com.crystalgui.workbench.app.WorkbenchApplication;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The CrystalGUI desktop as a 1.20.x {@link Screen} — <b>and nothing more than that</b>.
 *
 * <p>Minecraft's screen lifecycle mapped onto {@link HostSession}'s, plus the input overrides and the
 * GL handoff this version of the game needs. What opens, how big it is and when it is raised are
 * {@code HostSession}'s and are the same answer on every loader.</p>
 *
 * @see CgUiInput for the event translation
 */
public final class CgUiScreen extends Screen {

    public CgUiScreen() {
        super(Component.literal("CrystalGUI"));
    }

    /**
     * Declares this client's host and what its desktop opens with. Once, from client init.
     *
     * <p>Naming the application here is the whole of what a loader decides about it — which is
     * configuration, not logic. @see HostSession#install</p>
     */
    public static void install() {
        HostSession.install(new HostModern(), CrystalEditor.KIND);
    }

    // ── Opening ─────────────────────────────────────────────────────────────────────────────────

    /** Opens the desktop with the primary application brought forward. */
    public static void openEditor() {
        HostSession.session().requestApplication();
        open();
    }

    /** Opens the desktop and touches no window, so one left minimised comes back that way. */
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
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) mc.setScreen(new CgUiScreen());
    }

    // ── What the rest of this loader reads ──────────────────────────────────────────────────────

    @Nullable
    public static UIDocument window() {
        return HostSession.isInstalled() ? HostSession.session().document() : null;
    }

    @Nullable
    public static Desktop desktop() {
        return HostSession.isInstalled() ? HostSession.session().desktop() : null;
    }

    /** The editor's workbench, or null before one is up. */
    @Nullable
    static Workbench editorWorkbench() {
        Application app = HostSession.isInstalled() ? HostSession.session().application() : null;
        return app instanceof WorkbenchApplication ? ((WorkbenchApplication) app).workbench() : null;
    }

    static float frameDelta() {
        return HostSession.session().frameDelta();
    }

    static float uiScale() {
        return HostSession.session().services().uiScale();
    }

    // ── Minecraft's screen lifecycle ────────────────────────────────────────────────────────────

    @Override
    protected void init() {
        HostSession.session().shown();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        HostSession session = HostSession.session();
        if (!session.isBuilt()) return;
        // The engine initialises on the first WORLD render and this screen also opens over the title
        // screen, where there is none. @see CgUiHostGl#ensureContext
        CgUiHostGl.ensureContext(surfaceWidth(), surfaceHeight());
        if (!CgUiHostGl.contextIsLive()) return;

        float delta = session.frameDelta();

        // The clock every node preview reads. Nothing else drives it here, so without this CG_TIME is
        // permanently zero and a Time node's thumbnail renders black.
        CgRenderPipeline.getInstance().getFrameData().timeSecs =
                (float) (System.nanoTime() / 1_000_000_000.0);

        session.frame(delta);

        // DRAIN MINECRAFT'S OWN BATCH FIRST. GuiGraphics queues its geometry into a BufferSource that is
        // flushed only after render() returns, so anything Minecraft still had pending would composite ON
        // TOP of the immediate-mode GL below rather than under it. The symptom is the whole UI reading one
        // shade darker, with nothing in the UI itself to blame.
        graphics.flush();

        session.paint(DesktopPresentation.DESKTOP, delta, PAINT_HOST);
    }

    /**
     * The screen's own bracket — no {@code beforePaint}, unlike the HUD's.
     *
     * <p>1.20 posts no move event for a HUD, so that arm has to offer the pointer itself every frame.
     * A screen gets {@link #mouseMoved} and would be told twice.</p>
     */
    private static final HostSession.PaintHost PAINT_HOST = new HostSession.PaintHost() {

        @Override
        public boolean ownScreenUp() {
            Minecraft mc = Minecraft.getInstance();
            return mc != null && mc.screen instanceof CgUiScreen;
        }

        @Override
        public boolean anyScreenUp() {
            Minecraft mc = Minecraft.getInstance();
            return mc != null && mc.screen != null;
        }

        @Override
        public void enter() {
            CgUiHostGl.enter();
        }

        @Override
        public void leave() {
            CgUiHostGl.leave();
        }
    };

    @Override
    public void removed() {
        HostSession.session().hidden();
    }

    /**
     * <b>Does not pause single-player.</b> A desktop sits over the machine while the machine keeps
     * working, and pausing also stops {@code MinecraftServer.tick} -- so the integrated server never
     * pumps the connection and every workspace call dies at its timeout, with nothing in the log.
     */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Frees the compositor at game shutdown. Not called on close -- see {@link #removed()}. */
    public static void disposeAll() {
        if (HostSession.isInstalled()) HostSession.session().dispose();
    }

    private static int surfaceWidth() {
        return HostSession.session().services().surfaceWidth();
    }

    private static int surfaceHeight() {
        return HostSession.session().services().surfaceHeight();
    }

    // ── Input ───────────────────────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        UIDocument window = window();
        return window != null && CgUiInput.mouseButton(window, button, true);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        UIDocument window = window();
        return window != null && CgUiInput.mouseButton(window, button, false);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        UIDocument window = window();
        if (window != null) CgUiInput.mouseMoved(window);
    }

    /**
     * A drag is a move: the engine tracks the button itself through pointer capture, and reporting a
     * press here would end the drag on its first pixel.
     */
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        UIDocument window = window();
        if (window == null) return false;
        CgUiInput.mouseMoved(window);
        return true;
    }

    // 1.20.2 added the horizontal axis. Reached only when the loader's scroll event did not consume the
    // scroll -- see CgUiHud -- which is the same on every version.
    @Override
    //? if >=1.20.2 {
    /*public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double delta) {
    *///?} else {
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
    //?}
        UIDocument window = window();
        return window != null && CgUiInput.scrolled(window, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        UIDocument window = window();
        if (window == null) return false;
        if (CgUiInput.key(window, keyCode, true)) return true;

        // Escape is a cascade -- a live drag eats it, then a popover, then a modal -- so the screen
        // closes only on one nothing wanted. shouldCloseOnEsc() is false for the same reason.
        if (keyCode == ESCAPE_KEY) {
            onClose();
            return true;
        }
        return false;
    }

    private static final int ESCAPE_KEY = 256;

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        UIDocument window = window();
        return window != null && CgUiInput.key(window, keyCode, false);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        UIDocument window = window();
        return window != null && CgUiInput.character(window, codePoint);
    }

    /** @see #keyPressed */
    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
