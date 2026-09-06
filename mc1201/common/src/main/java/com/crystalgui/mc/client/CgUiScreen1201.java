package com.crystalgui.mc.client;

import java.io.File;
import java.nio.file.Path;

import javax.annotation.Nullable;

import com.crystalgraphics.api.render.CgRenderPipeline;
import com.crystalgui.core.window.DesktopPresentation;
import com.crystalgui.core.window.WindowState;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.app.Application;
import com.crystalgui.desktop.host.DesktopHost;
import com.crystalgui.desktop.host.HostServices;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.mc.net.Connections1201;
import com.crystalgui.app.crystaleditor.CrystalEditor;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.workbench.app.WorkbenchApplication;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The CrystalGUI desktop as a 1.20.x {@link Screen}.
 *
 * <p>Minecraft builds a fresh {@code Screen} on every display, so the compositor lives in statics and
 * {@link #init()} either builds it once or reports that it is back on screen. {@link #removed()} is not
 * a teardown: every window and every unsaved document survives a close and comes back as it was.</p>
 */
public final class CgUiScreen1201 extends Screen {

    private static final String ROOT_CLASS = "__mc-host__";
    private static final String EDITOR_CLASS = "__mc-editor__";
    private static final String DESKTOP_ID = "client";

    /** Fraction of the surface a first-run editor window takes. */
    private static final float FIRST_RUN_FRACTION = 0.8f;

    private static DesktopHost host;
    private static WorkbenchApplication editor;
    private static WindowFrame editorWindow;

    /**
     * Consumed by {@link #init()}, never merely read. {@code init()} re-runs on every window resize, so
     * a flag left standing brings the editor forward again on the next resize -- windows the player
     * closed reappear, which is what mc1710 shipped and was reported as uncloseable windows.
     */
    private static boolean showEditorOnOpen;

    /**
     * Set by {@link #openEditor()}, cleared once a window actually exists.
     *
     * <p>The launch can fail for a reason that fixes itself — the editor needs a workspace, which needs a
     * connection, and there may not be one yet. {@code init()} was the only caller and re-runs only on a
     * resize, so the attempt was never made again and the desktop stayed up and empty. Separate from
     * {@link #showEditorOnOpen} because the two are consumed on different events, and driven off a flag
     * rather than "the desktop is empty" so it cannot re-open a window the user just closed.</p>
     */
    private static boolean awaitingEditorLaunch;

    private static long lastFrameNanos;

    public CgUiScreen1201() {
        super(Component.literal("CrystalGUI"));
    }

    /** Opens the desktop with the editor brought forward. */
    public static void openEditor() {
        showEditorOnOpen = true;
        awaitingEditorLaunch = true;
        open();
    }

    /** Opens the desktop and touches no window, so one left minimised comes back that way. */
    public static void openDesktop() {
        open();
    }

    private static void open() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) mc.setScreen(new CgUiScreen1201());
    }

    @Nullable
    public static UIDocument window() {
        return host == null ? null : host.document();
    }

    @Nullable
    public static Desktop desktop() {
        return host == null ? null : host.desktop();
    }

    /** Seconds since the previous frame, clamped so a stall does not complete every animation at once. */
    static float frameDelta() {
        long now = System.nanoTime();
        if (lastFrameNanos == 0L) {
            lastFrameNanos = now;
            return 0f;
        }
        float delta = (now - lastFrameNanos) / 1_000_000_000f;
        lastFrameNanos = now;
        return Math.max(0f, Math.min(delta, 0.25f));
    }

    @Override
    protected void init() {
        if (host == null) buildDesktop();
        else host.shown();
        bringEditorForward();
    }

    private void buildDesktop() {
        host = DesktopHost.create(new Mc1201Host());
        host.document().addClass(ROOT_CLASS);
    }

    /** The three things only this platform can answer. @see HostServices */
    private static final class Mc1201Host implements HostServices {

        @Override
        public Path configDirectory() {
            Minecraft mc = Minecraft.getInstance();
            File root = mc == null ? new File(".") : mc.gameDirectory;
            return new File(root, "config/crystalgui").toPath();
        }

        @Override
        public float uiScale() {
            return CgUiScreen1201.uiScale();
        }

        @Override
        public String desktopId() {
            return DESKTOP_ID;
        }

        @Override
        @Nullable
        public ProtocolConnection<Object> connection() {
            // Re-asked every frame, so a reconnect is a different object carrying the same workspace and
            // DesktopHost rebinds rather than rebuilds. Null means no server right now: supported.
            return Connections1201.client();
        }
    }

    /**
     * Builds the editor window on demand. Returns false when it cannot be built -- with no connection
     * there is no workspace, and the editor refuses one. The desktop does not depend on it.
     */
    private boolean ensureEditorWindow() {
        if (editorWindow != null) return true;
        Application launched;
        try {
            launched = host.desktop().applications()
                    .launch(CrystalEditor.KIND, host.workspace(), host.config());
        } catch (RuntimeException failed) {
            return false;
        }
        if (!(launched instanceof WorkbenchApplication)) return false;

        editor = (WorkbenchApplication) launched;
        editor.addClass(EDITOR_CLASS);
        editorWindow = editor.mainWindow();

        float scale = uiScale();
        float logicalWidth = surfaceWidth() / scale;
        float logicalHeight = surfaceHeight() / scale;
        editorWindow.resizeTo(Math.round(logicalWidth * FIRST_RUN_FRACTION),
                Math.round(logicalHeight * FIRST_RUN_FRACTION));
        editorWindow.moveTo(Math.round(logicalWidth * (1f - FIRST_RUN_FRACTION) / 2f),
                Math.round(logicalHeight * (1f - FIRST_RUN_FRACTION) / 2f));
        return true;
    }

    private void bringEditorForward() {
        boolean nothingOpen = desktop() != null && desktop().registry().size() == 0;
        if (!showEditorOnOpen && !nothingOpen) return;
        if (!ensureEditorWindow()) return;
        // BUILT, which is what this flag is about -- the bring-forward below is a separate question and
        // may legitimately decline.
        awaitingEditorLaunch = false;
        if (!showEditorOnOpen) return;

        showEditorOnOpen = false;
        if (editorWindow.state() == WindowState.HIDDEN) editorWindow.show(true);
        desktop().activate(editorWindow);
    }

    /**
     * Device pixels per logical pixel, and NOT the player's GUI Scale.
     *
     * <p>It was that, and GUI Scale is the wrong input: it sizes 16px widgets and a bitmap font, so a
     * player who wants a readable inventory gets a desktop scaled to match and the two cannot both be
     * right. It was also only ever read once, at {@code DesktopHost} construction, so it never followed
     * a change of the setting either.</p>
     */
    private static float uiScale() {
        return HostServices.DEFAULT_UI_SCALE;
    }

    private static int surfaceWidth() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null || mc.getWindow() == null ? 0 : mc.getWindow().getWidth();
    }

    private static int surfaceHeight() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null || mc.getWindow() == null ? 0 : mc.getWindow().getHeight();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (host == null || !CgUiHostGl1201.contextIsLive()) return;
        // THE RETRY, and it costs a boolean read once a window exists. @see #awaitingEditorLaunch
        if (awaitingEditorLaunch) bringEditorForward();
        float delta = frameDelta();

        // The clock every node preview reads. Nothing else drives it here, so without this CG_TIME is
        // permanently zero and a Time node's thumbnail renders black.
        CgRenderPipeline.getInstance().getFrameData().timeSecs =
                (float) (System.nanoTime() / 1_000_000_000.0);

        host.frame(delta);

        // DRAIN MINECRAFT'S OWN BATCH FIRST. GuiGraphics queues its geometry into a BufferSource that is
        // flushed only after render() returns, so anything Minecraft still had pending would composite ON
        // TOP of the immediate-mode GL below rather than under it. The symptom is the whole UI reading one
        // shade darker, with nothing in the UI itself to blame.
        graphics.flush();

        CgUiHostGl1201.enter();
        try {
            host.desktop().paint(DesktopPresentation.DESKTOP, delta, surfaceWidth(), surfaceHeight());
        } finally {
            CgUiHostGl1201.leave();
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        UIDocument window = window();
        return window != null && CgUiInput1201.mouseButton(window, button, true);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        UIDocument window = window();
        return window != null && CgUiInput1201.mouseButton(window, button, false);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        UIDocument window = window();
        if (window != null) CgUiInput1201.mouseMoved(window);
    }

    /**
     * A drag is a move: the engine tracks the button itself through pointer capture, and reporting a
     * press here would end the drag on its first pixel.
     */
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        UIDocument window = window();
        if (window == null) return false;
        CgUiInput1201.mouseMoved(window);
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        UIDocument window = window();
        return window != null && CgUiInput1201.scrolled(window, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        UIDocument window = window();
        if (window == null) return false;
        if (CgUiInput1201.key(window, keyCode, true)) return true;

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
        return window != null && CgUiInput1201.key(window, keyCode, false);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        UIDocument window = window();
        return window != null && CgUiInput1201.character(window, codePoint);
    }

    /** @see #keyPressed */
    @Override
    public boolean shouldCloseOnEsc() {
        return false;
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

    @Override
    public void removed() {
        // Off screen, not destroyed: the desktop records its arrangement and each application its
        // session, because each went off screen. Only dispose() takes them down.
        //
        // AND THE PENDING LAUNCH IS ABANDONED. Closing the screen withdraws the request; carrying it
        // across would open an editor the next time the desktop is shown for some unrelated reason.
        awaitingEditorLaunch = false;
        if (host != null) host.hidden();
    }

    /** Frees the compositor at game shutdown. Not called on close -- see {@link #removed()}. */
    public static void disposeAll() {
        if (editor != null) editor.dispose();
        if (host != null) host.dispose();
        editor = null;
        editorWindow = null;
        host = null;
    }
}
