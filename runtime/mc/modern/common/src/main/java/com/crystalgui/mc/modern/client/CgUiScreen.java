package com.crystalgui.mc.modern.client;

import com.crystalgraphics.platform.service.CgHostPaint;
import com.crystalgraphics.mc.modern.platform.HostGlHandoff;
import com.crystalgraphics.mc.modern.platform.CgMinecraftClient;
import javax.annotation.Nullable;

import com.crystalgraphics.platform.input.CgInput;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.platform.service.CgHostWindow;
import com.crystalgui.app.crystaleditor.CrystalEditor;
import com.crystalgui.core.window.DesktopPresentation;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.app.Application;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.desktop.host.HostServices;
import com.crystalgui.desktop.host.HostSession;
//? if >=26.1 {
/*import com.crystalgui.lifecycle.CgUiLifecycle;
*///?}
import com.crystalgui.probe.ConnectionProbe;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.workbench.Workbench;
import com.crystalgui.workbench.app.WorkbenchApplication;

import net.minecraft.client.Minecraft;
//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?} elif >=1.20 {
import net.minecraft.client.gui.GuiGraphics;
//?} elif >=1.15 {
/*import com.mojang.blaze3d.vertex.PoseStack;
*///?}
import net.minecraft.client.gui.screens.Screen;
//? if >=1.19 {
import net.minecraft.network.chat.Component;
//?} elif >=1.14 {
/*import net.minecraft.network.chat.TextComponent;
*///?}
//? if >=1.21.9 {
/*import net.minecraft.client.input.KeyEvent;
*///?}
//? if >=26.3 {
/*import java.util.Arrays;
import net.minecraft.client.input.PreeditEvent;
*///?}

/**
 * The CrystalGUI desktop as a 1.20.x {@link Screen} — <b>and nothing more than that</b>.
 *
 * <p>Minecraft's screen lifecycle mapped onto {@link HostSession}'s, plus the GL handoff this version of the
 * game needs. What opens, how big it is and when it is raised are {@code HostSession}'s and are the same answer
 * on every loader. Its input reaches the desktop through CrystalGraphics' {@code SCREEN} input stage.</p>
 */
public final class CgUiScreen extends Screen {

    public CgUiScreen() {
        //? if >=1.19 {
        super(Component.literal("CrystalGUI"));
        //?} elif >=1.14 {
        /*super(new TextComponent("CrystalGUI"));
        *///?} else {
        /*super();
        *///?}
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
        if (!HostSession.mayOpen()) return;
        HostSession.session().requestApplication();
        open();
    }

    /** Opens the desktop and touches no window, so one left minimised comes back that way. */
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
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) CgMinecraftClient.setScreen(mc, new CgUiScreen());
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

    static float uiScale() {
        return HostServices.DEFAULT_UI_SCALE;
    }

    // ── Minecraft's screen lifecycle ────────────────────────────────────────────────────────────

    // 1.21.6 draws the HUD at the end of the frame, after this screen's immediate-mode paint, so the
    // hotbar and crosshair would land ON the desktop, which used to cover them. Hidden while it is up;
    // the player's own setting is kept once per opening, since init() also runs on every resize. From
    // 26.1 the desktop paints at the frame end, over the HUD, so it stays: and with it the hand and the HUD stage.
    //? if >=1.21.6 <26.1 {
    /*private Boolean hudHiddenBefore;
    *///?}

    @Override
    protected void init() {
        HostSession.session().shown();
        // SDL sends characters only while an owner has started text input, which Minecraft's own text
        // boxes do on focus. The desktop is ours to type into while it is up. Owner-checked on release.
        //? if >=26.3 {
        /*minecraft.onTextInputFocusChange(this, true);
        *///?}
        //? if >=1.21.6 <26.1 {
        /*if (hudHiddenBefore == null) hudHiddenBefore = CgMinecraftClient.hudHidden(minecraft);
        CgMinecraftClient.setHudHidden(minecraft, true);
        *///?}
    }

    // DRAIN MINECRAFT'S OWN BATCH FIRST. GuiGraphics queues its geometry into a BufferSource that is
    // flushed only after render() returns, so anything Minecraft still had pending would composite ON TOP
    // of the immediate-mode GL below rather than under it. The symptom is the whole UI reading one shade
    // darker, with nothing in the UI itself to blame. Before 1.20 the batch is the game's own buffer
    // source, which GuiGraphics.flush wraps. 1.21.6 records GUI draws and renders them at the end of the
    // frame, so there is no batch to drain. 26.1 EXTRACTS the GUI before it draws the level at all, so a
    // paint there lands under the world: the desktop paints at the frame end instead, over everything.
    //? if >=26.1 {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        CgUiLifecycle.atFrameEnd(DesktopPresentation.DESKTOP, CgUiScreen::paintDesktop);
    }
    *///?} elif >=1.21.6 {
    /*@Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        paintDesktop();
    }
    *///?} elif >=1.20 {
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.flush();
        paintDesktop();
    }
    //?} elif >=1.16 {
    /*@Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        Minecraft.getInstance().renderBuffers().bufferSource().endBatch();
        paintDesktop();
    }
    *///?} elif >=1.15 {
    /*@Override
    public void render(int mouseX, int mouseY, float partialTick) {
        Minecraft.getInstance().renderBuffers().bufferSource().endBatch();
        paintDesktop();
    }
    *///?} else {
    /*@Override
    public void render(int mouseX, int mouseY, float partialTick) {
        paintDesktop();
    }
    *///?}

    private static void paintDesktop() {
        HostSession session = HostSession.session();
        if (!session.isBuilt()) return;
        // The engine initialises on the first WORLD render and this screen also opens over the title
        // screen, where there is none. @see HostGlHandoff#ensureContext
        HostGlHandoff.ensureContext(surfaceWidth(), surfaceHeight());
        if (!HostGlHandoff.contextIsLive()) return;

        float delta = session.frameDelta();

        session.frame(delta);
        session.paint(DesktopPresentation.DESKTOP, delta, PAINT_HOST);
        //? if >=26.3 {
        /*placeTextInputArea();
        *///?}
    }

    // An input method's run in progress, and where its candidate list opens: 26.3's SDL reports both and
    // Minecraft hands the first to the screen, drawing nothing itself. The engine's side is CompositionEvent.
    //? if >=26.3 {
    /*@Override
    public boolean preeditUpdated(@Nullable PreeditEvent event) {
        UIDocument window = window();
        // NULL ENDS THE COMPOSITION: Minecraft passes no event rather than an empty one.
        if (window == null) return false;
        return event == null
                ? HostSession.session().input().consumeComposition("", 0)
                : HostSession.session().input().consumeComposition(event.fullText(), event.caretPosition());
    }

    private static int[] placedTextInputArea;

    // The focus owner's caret, in GUI units -- setTextInputArea scales by the GUI scale itself, and takes
    // two CORNERS, as EditBox passes them, not a size. Sent only on a change: SDL passes it to the OS each time.
    private static void placeTextInputArea() {
        UIDocument window = window();
        float[] area = window == null ? null : HostSession.session().textInputArea();
        if (area == null) return;
        Minecraft mc = Minecraft.getInstance();
        float scale = Math.max(1, mc.getWindow().getGuiScale());
        int left = (int) Math.floor(area[0] / scale);
        int top = (int) Math.floor(area[1] / scale);
        int[] corners = { left, top,
                Math.max(left + 1, (int) Math.ceil((area[0] + area[2]) / scale)),
                Math.max(top + 1, (int) Math.ceil((area[1] + area[3]) / scale)) };
        if (Arrays.equals(corners, placedTextInputArea)) return;
        placedTextInputArea = corners;
        mc.textInputManager().setTextInputArea(corners[0], corners[1], corners[2], corners[3]);
    }
    *///?}

    // 1.21.6 draws the background from renderWithTooltip, before render() and deferred to the end of the
    // frame -- so its blur and dim would land OVER the desktop painted above. The desktop is its own.
    //? if >=26.1 {
    /*@Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    }
    *///?} elif >=1.21.6 {
    /*@Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }
    *///?}

    /** The screen's own bracket. */
    private static final HostSession.PaintHost PAINT_HOST = new HostSession.PaintHost() {

        @Override
        public boolean ownScreenUp() {
            Minecraft mc = Minecraft.getInstance();
            return mc != null && CgMinecraftClient.screen(mc) instanceof CgUiScreen;
        }

        @Override
        public boolean anyScreenUp() {
            Minecraft mc = Minecraft.getInstance();
            return mc != null && CgMinecraftClient.screen(mc) != null;
        }

        @Override
        public void enter() {
            CgHostPaint.enter();
        }

        @Override
        public void leave() {
            CgHostPaint.leave();
        }
    };

    @Override
    public void removed() {
        HostSession.session().hidden();
        //? if >=26.3 {
        /*minecraft.onTextInputFocusChange(this, false);
        *///?}
        //? if >=1.21.6 <26.1 {
        /*if (hudHiddenBefore != null) CgMinecraftClient.setHudHidden(minecraft, hudHiddenBefore);
        hudHiddenBefore = null;
        *///?}
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
        return CgHostWindow.width();
    }

    private static int surfaceHeight() {
        return CgHostWindow.height();
    }

    // ── Input ───────────────────────────────────────────────────────────────────────────────────
    // Every event reaches the desktop through CrystalGraphics' SCREEN input stage before this screen sees it
    // (HostInputListener); what arrives here is what the desktop left.

    /**
     * An Escape the desktop left closes it. Escape is a cascade -- a live drag eats it, then a popover, then a
     * modal -- so the screen closes only on one nothing wanted. shouldCloseOnEsc() is false for the same reason.
     */
    @Override
    //? if >=1.21.9 {
    /*public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
    *///?} else {
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
    //?}
        if (CgInput.key(keyCode) != CgKeyCodes.KEY_ESCAPE) return false;
        onClose();
        return true;
    }

    /**
     * A key the desktop dispatched on its own thread and left, handed back a frame late: what this screen does with
     * one nothing wanted. @see #keyPressed
     */
    void keyLeftByDesktop(CgSystemInput.Keyboard.Event key) {
        if (key.pressed() && key.key() == CgKeyCodes.KEY_ESCAPE) onClose();
    }

    /** Set once the desktop has played itself off screen: the next tick closes, outside the frame being drawn. */
    private volatile boolean closeReady;

    /** Escape and the close key: the desktop plays itself off first. @see HostSession#requestHide */
    @Override
    public void onClose() {
        if (!HostSession.session().requestHide(() -> closeReady = true)) super.onClose();
    }

    @Override
    public void tick() {
        super.tick();
        if (!closeReady) return;
        closeReady = false;
        super.onClose();
    }

    /** @see #keyPressed */
    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
