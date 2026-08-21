package com.crystalgui.mc.client;

import com.crystalgraphics.api.render.CgRenderPipeline;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgui.core.dispose.Disposer;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.editor.CrystalEditor;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.fs.LocalConfigStorage;
import com.crystalgui.language.run.view.RunPanels;
import com.crystalgui.language.run.view.ScriptWorkbench;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.Ui;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.File;

/**
 * The Minecraft host for {@link CrystalEditor}.
 *
 * <p>{@code CrystalEditor}'s own javadoc states the contract this implements: <i>"A host supplies a
 * {@link com.crystalgui.fs.WorkspaceClient} and a window; everything else is decided here."</i> So this
 * class is deliberately small — it owns a {@link UIWindow}, drives one frame, and saves on close.
 * Which panels exist, what the layout is, which commands answer to which keys and where focus starts
 * are all the editor's, and none of them appear below.</p>
 *
 * <p>The reference host is the harness's {@code CgUiDockScene}; this is the same sequence with
 * Minecraft's clock and screen size substituted.</p>
 *
 * <h3>Real device pixels, never {@code ScaledResolution}</h3>
 *
 * <p>{@link #drawScreen}'s {@code mouseX}/{@code mouseY} arrive already divided by Minecraft's GUI
 * scale, and {@code GuiScreen.width}/{@code height} are the scaled size. <b>None of them are
 * used here.</b> {@code UIWindow} takes raw pixels and applies its own scale through
 * {@code getRootTransform()}, which {@code AGENTS.md} names as <i>the single definition of what
 * uiScale means</i> — feeding it pre-scaled numbers creates a second definition, and the two disagree
 * by exactly the scale factor. The symptom is the nasty one: everything draws correctly and every
 * click lands somewhere else.</p>
 *
 * @see CgUiInput for why input does not arrive through this class at all
 */
public final class CgUiScreen extends GuiScreen {

    /** @see #HOST_STYLES */
    private static final String ROOT_CLASS = "crystalgui-screen";

    /**
     * <b>The host sizes the root, and nothing else will.</b>
     *
     * <p>{@code UIWindow.init(w, h)} looks like it does this and does not: all it calls is
     * {@code UIElement.initScreen}, whose entire body is {@code runtimeCache.resetCache()} plus the
     * same call on each child. The root therefore has <em>no</em> width or height of its own, and with
     * Taffy's defaults here ({@code flex-direction: COLUMN}, {@code min-size: 0}, no explicit size) it
     * sizes to content — so every percentage and every {@code flex-grow} inside it resolves against
     * zero and the whole editor collapses.</p>
     *
     * <p>The symptom is not an empty window, which is what makes it slow to place: the root still
     * paints its own themed background over the full screen, so the game vanishes behind a flat
     * charcoal rectangle with nothing in it, and it reads as "the editor rendered but has no content"
     * rather than "the editor is zero by zero".</p>
     *
     * <p>{@code CgUiDockScene} does the same thing under the name {@code .demo-root} and it is not
     * demo scaffolding — the {@code padding-all} beside it is, and is left out here because a
     * full-screen editor wants no margin.</p>
     */
    private static final String HOST_STYLES =
            "." + ROOT_CLASS + " { width: 100%; height: 100%; }";

    /**
     * Kept across opens.
     *
     * <p>Rebuilding the editor on every open would discard every document, the dock arrangement and the
     * undo history with it — a workbench is not a dialog. Minecraft constructs a fresh
     * {@code GuiScreen} each time one is shown, so the state has to live somewhere that is not the
     * screen.</p>
     */
    private static CrystalEditor editor;
    private static UIWindow uiWindow;
    private static Mc1710Workspace workspace;

    /** Run and Stop for the active file, or null where no engine band opened. @see #initGui */
    private static ScriptWorkbench scripting;

    /** Whether the project list has been asked for. @see Mc1710Workspace#isConnected */
    private static boolean projectsAsked;

    private long lastFrameNanos = System.nanoTime();

    /** Set by the pump when an Escape reached the window and nothing consumed it. @see CgUiInput */
    private boolean closeRequested;

    public static void open() {
        Minecraft.getMinecraft().displayGuiScreen(new CgUiScreen());
    }

    /** Whether the editor has been built — read by the pump before it touches anything. */
    static boolean isReady() {
        return uiWindow != null;
    }

    static UIWindow window() {
        return uiWindow;
    }

    void requestClose() {
        closeRequested = true;
    }

    /**
     * Where the first open's time actually goes — {@code -Dcrystalgui.startup.trace=true}.
     *
     * <p>Off by default and one block per session when on. It exists because the first F6 was reported as
     * a three-second freeze and the honest answer to "what is slow" was that nobody knew: the two pieces
     * measurable without a client came to about a second between them (the user-agent sheet's nine-part
     * parse, and opening the engine band plus ECJ's first analysis), and the rest is behind GL — fonts,
     * glyph atlases, icon parsing, the first layout of a whole workbench — which no unit test can reach.</p>
     *
     * <p>The same lesson §26.13a records from Phase 3: <b>a client is an environment no test reproduces,
     * and the way to find out what it is doing is to ask it rather than to reason from the source.</b></p>
     */
    private static final boolean TRACE = Boolean.getBoolean("crystalgui.startup.trace");

    private static long traceStart;

    private static boolean tracedFirstPaint;

    private static void trace(String phase) {
        if (!TRACE) return;
        long now = System.nanoTime();
        if (traceStart != 0) {
            CrystalGuiCore.LOGGER.info("[startup] {} — {} ms", phase, (now - traceStart) / 1_000_000);
        }
        traceStart = now;
    }

    @Override
    public void initGui() {
        // Held-key repeat. Without it a held arrow moves the caret exactly once and backspace deletes
        // one character however long it is held, which reads as the editor being unresponsive rather
        // than as a missing flag.
        Keyboard.enableRepeatEvents(true);

        if (uiWindow != null) return;

        trace("begin");
        File dataDir = mc.mcDataDir;
        // NO ROOT. The files live on the SERVER now (Phase 4 B2) -- in single-player that is the
        // integrated server, which is why there is one code path rather than a local special case.
        workspace = new Mc1710Workspace();
        trace("workspace + language registration");

        if (!workspace.isConnected()) {
            // Should be impossible -- this screen opens from inside a world, and the connection is opened
            // on join. Named rather than left to NPE somewhere in the file tree, because "the editor has
            // no workspace" and "the editor is broken" look identical from the outside.
            CrystalGuiCore.LOGGER.error("Opening the editor with no server connection: the file tree will "
                    + "be empty. CgUiConnections.client() is null, which means the join event never fired.");
        }
        editor = new CrystalEditor(workspace.client());
        trace("CrystalEditor construction");
        // BESIDE the workspace, not inside it: a session record is private and must not become part of
        // a project a resource pack could ship. Same reason the trash lives outside.
        editor.useConfig(new LocalConfigStorage(new File(dataDir, "config/crystalgui").toPath()));

        editor.addClass(ROOT_CLASS);

        // RUN AND STOP, for the file in front. Installed here rather than by CrystalEditor because it
        // belongs to the LANGUAGE module: the editor is the shell, and a shell that hard-wired a Run
        // panel would drag ECJ and Rhino into every application built on it. CgUiDockScene installs it
        // the same way and for the same reason.
        //
        // Null when no engine band opened, and the commands are then deliberately NOT registered -- a
        // Run row that cannot run anything teaches people the feature is broken rather than unavailable.
        //
        // The cache root is beside the config rather than inside the workspace: compiled output is
        // derived, private, and must not become part of a project a resource pack could ship.
        scripting = ScriptWorkbench.install(
                CommandRegistry.global(), editor.workbench(),
                new File(dataDir, "config/crystalgui/script-cache").toPath());
        if (scripting != null) editor.workbench().revealPanel(RunPanels.RUN_TYPE);

        trace("scripting install");
        uiWindow = new UIWindow(Ui.of(editor));
        // NOT INSTALLED FOR YOU. Without this the window matches no selector at all and the editor
        // renders as an unstyled column of boxes.
        uiWindow.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        uiWindow.getStyleEngine().addStylesheet(StyleSheet.parse(HOST_STYLES));
        trace("UIWindow + stylesheets");
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        long now = System.nanoTime();
        float delta = (float) ((now - lastFrameNanos) / 1_000_000_000.0);
        lastFrameNanos = now;

        if (closeRequested) {
            closeRequested = false;
            mc.displayGuiScreen(null);
            return;
        }

        // THE CLOCK EVERY NODE PREVIEW READS. CgPreviewRenderer reuses the shared CgRenderPipeline
        // singleton's one CgFrameData rather than owning its own, which is what lets a Time node's
        // thumbnail animate off whatever clock the application already drives. Nothing else here drives
        // it, so without this line CG_TIME is permanently zero -- and a Multiply of Colour x SineTime
        // renders BLACK whatever colour you pick, which reads as "the preview does not recompile" and is
        // really sin(0). CgUiDockScene carries the same line and says "in Minecraft the loader drives
        // it". This is that loader.
        CgRenderPipeline.getInstance().getFrameData().timeSecs =
                (float) (System.nanoTime() / 1_000_000_000.0);

        // INPUT, DRAINED PER FRAME RATHER THAN PER TICK.
        //
        // Minecraft delivers input to a screen from GuiScreen.handleInput(), which it calls from
        // runTick() -- and runTick is driven by `new Timer(20.0F)`:
        //
        //     for (int i = 0; i < this.timer.elapsedTicks; ++i) this.runTick();
        //
        // So a GuiScreen's input is pumped at 20 Hz while drawScreen renders at 60+. For clicks and
        // typing that is invisible; for anything CONTINUOUS it is not. A resize handle or a split
        // divider redraws three times a second per twelve frames of motion, which reads as the UI being
        // slow to paint rather than as input being sampled coarsely -- the drag is smooth, the picture
        // is not.
        //
        // Draining here costs nothing extra: it is the same loop handleInput runs, moved to the frame
        // clock. MC's own tick-rate call then finds an empty queue and does nothing, so this needs no
        // override and stays correct if a frame is ever skipped.
        //
        // The harness has always polled per frame (InteractiveSceneRunner.pollInput), which is why this
        // only appears in game.
        pumpInput();

        // ONE NETWORK TICK, before anything reads the workspace.
        workspace.pump(delta);
        if (!projectsAsked && workspace.isConnected()) {
            projectsAsked = true;
            editor.workbench().fileTree().loadProjects();
            // AFTER loadProjects, never before: the restore parks the folders it wants expanded and
            // retries until the listings that reveal them arrive, so asking first parks everything.
            editor.restoreSession(Mc1710Workspace.PROJECT_ID);
        }

        // Raw pixels. NOT ScaledResolution -- see the class javadoc.
        //
        // And the SCALE stays at UIWindow's own default, which is what CgUiDockScene runs at. Deriving
        // it from ScaledResolution.getScaleFactor() sounds respectful of the player's preference and is
        // wrong for this window: MC's GUI scale is tuned for a hotbar and an inventory and reaches 4 on
        // a large display, so the editor rendered at roughly twice the size of the same panels in the
        // harness. An IDE is not a game HUD. Matching the harness is also what makes a harness capture
        // and an in-game one comparable, which is the whole basis for testing one against the other.
        uiWindow.init(mc.displayWidth, mc.displayHeight);

        // MINECRAFT WRITES GL STATE BEHIND CrystalGraphics' BACK, SO THE SHADOW MUST BE DROPPED HERE.
        //
        // CgGlStateManager keeps a CPU-side shadow and ELIDES redundant GL calls, which is only sound
        // while every write goes through CgGL. Minecraft sets blend, depth, texture and program state
        // directly through GL11/OpenGlHelper on every frame, so by the time a GuiScreen paints, the
        // shadow describes a context that no longer exists — and each disagreement is a call CgGL
        // decides it does not need to make.
        //
        // CrystalGraphics/AGENTS.md states the rule and the consequence: foreign code that writes raw GL
        // "must call CgGlState.invalidateAllIfPresent()", and getting it wrong "produces a MISSING GL
        // CALL — wrong rendering, no exception". The harness never needed it because nothing else in
        // that process touches GL; Minecraft is the first host where something does.
        //
        // Once per frame, immediately before painting: cheap (it drops a shadow, it does not read the
        // driver) and there is no earlier point that stays true, since MC's own GUI pass runs between
        // frames.
        CgGlState.invalidateAllIfPresent();

        uiWindow.paintFrame();
        if (TRACE && !tracedFirstPaint) {
            tracedFirstPaint = true;
            // THE ONE THAT NEEDS A CLIENT. Fonts, glyph atlases, icon SVGs and the first layout of the
            // whole workbench all happen here and nowhere a test can reach.
            trace("first paint");
        }

        // HAND MINECRAFT BACK THE STATE ITS OWN RENDERER ASSUMES.
        //
        // The state-manager design note is explicit that this is the caller's job: "Before calling in,
        // state MC cares about must be set through MC's API" -- two shadows, each blind to the other.
        // CrystalGraphics restores what it saved, but it restores it into ITS shadow's idea of the
        // world, and Minecraft never wrote through CgGL, so the two disagree about the pieces below.
        //
        // What goes wrong without this is remote from the cause. Minecraft presents by drawing ONE
        // fixed-function quad -- Framebuffer.framebufferRender: glEnable(GL_TEXTURE_2D),
        // bindFramebufferTexture(), a Tessellator quad, and no glUseProgram(0) anywhere. Fixed-function
        // samples texture unit 0, but bindFramebufferTexture binds to whatever unit is CURRENTLY active,
        // so if the UI leaves the active unit elsewhere, MC binds its screen texture to a unit nothing
        // reads and the quad falls back to its vertex colour: a pure WHITE window. Meanwhile the editor
        // is sitting correctly in MC's framebuffer, which is why a glReadPixels capture shows a perfect
        // UI while the screen shows nothing -- the drawing was never the broken part, the presenting was.
        org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
        org.lwjgl.opengl.GL20.glUseProgram(0);
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_TEXTURE_2D);
        // The shadow cannot see the three lines above either, so tell it so rather than leaving it
        // describing a context that no longer exists.
        CgGlState.invalidateAllIfPresent();

        editor.giveInitialFocus();

        framesPainted++;
        // UNATTENDED SCRIPT RUN, off unless asked for. On the CLIENT THREAD, which is where the Run
        // command runs too -- a probe on a worker would prove nothing about a failure that reaches the
        // game loop. @see CgUiAutoTest#runScriptOnce
        if (framesPainted == CgUiAutoTest.RUN_SCRIPT_ON_FRAME) {
            CgUiAutoTest.runScriptOnce(scripting);
        }
        // The §15.5 A proof, on the same frame budget. @see CgUiAutoTest#probeLiveBytesOnce
        if (framesPainted == 5) CgUiAutoTest.probeLiveBytesOnce();
        // And what the member list actually holds here. @see CgUiAutoTest#probeCompletionOnce
        if (framesPainted == 6) CgUiAutoTest.probeCompletionOnce();
        // ...and asked much later, because the analysis behind each one is debounced onto a worker that
        // drains on THIS thread. @see CgUiAutoTest#reportCompletionProbes
        if (framesPainted == 60) CgUiAutoTest.reportCompletionProbes();
        if (framesPainted == CgUiAutoTest.CAPTURE_ON_FRAME) {
            CgUiAutoTest.captureAndQuit(mc, mc.displayWidth, mc.displayHeight);
        }
        if (framesPainted == CgUiAutoTest.LATE_CAPTURE_ON_FRAME) {
            CgUiAutoTest.captureLateAndQuit(mc, mc.displayWidth, mc.displayHeight);
        }
    }

    /**
     * Drains both input queues into the window, once per rendered frame.
     *
     * <p>The same body as {@code GuiScreen.handleInput}, on the frame clock instead of the tick clock.
     * Guarded on {@code isCreated()} exactly as the original is — a headless or shutting-down client has
     * neither device.</p>
     */
    private void pumpInput() {
        if (uiWindow == null) return;
        if (Mouse.isCreated()) {
            while (Mouse.next()) handleMouseInput();
        }
        if (Keyboard.isCreated()) {
            while (Keyboard.next()) handleKeyboardInput();
        }
    }

    private int framesPainted;

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
        if (uiWindow == null) return;
        boolean unconsumed = CgUiInput.pumpKeyboard(uiWindow);
        if (unconsumed && Keyboard.getEventKeyState() && Keyboard.getEventKey() == Keyboard.KEY_ESCAPE) {
            closeRequested = true;
        }
    }

    /** <b>The mouse pump.</b> Once per event, same contract as above. */
    @Override
    public void handleMouseInput() {
        if (uiWindow == null) return;
        // Raw device height, never GuiScreen.height -- see CgUiInput.pumpMouse.
        CgUiInput.pumpMouse(uiWindow, mc.displayHeight);
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

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        if (editor != null && uiWindow != null) {
            editor.saveSession(Mc1710Workspace.PROJECT_ID,
                    (int) uiWindow.getScreenWidth(), (int) uiWindow.getScreenHeight());
            editor.savePreferences();
        }
    }

    /**
     * Pauses single-player.
     *
     * <p>The conservative answer and what every editor-like GUI in 1.7.10 does. Returning {@code false}
     * invites a class of "the world ticked while a modal was open" questions that Phase 1 has no reason
     * to answer.</p>
     */
    @Override
    public boolean doesGuiPauseGame() {
        return true;
    }

    /** Frees the editor at game shutdown. Not called on close — see the {@code editor} field. */
    public static void disposeAll() {
        if (editor != null) Disposer.dispose(editor);
        editor = null;
        uiWindow = null;
        workspace = null;
        projectsAsked = false;
    }
}
