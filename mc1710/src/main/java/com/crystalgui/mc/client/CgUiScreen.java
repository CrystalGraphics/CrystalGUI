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
import com.crystalgui.ui.elements.desktop.WindowFrame;
import com.crystalgui.ui.elements.desktop.WindowPolicy;
import com.crystalgui.ui.elements.desktop.WindowState;
import com.crystalgui.mc.net.CgUiConnections;
import com.crystalgui.net.protocol.ProtocolConnection;

import javax.annotation.Nullable;

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
    /** @see #HOST_STYLES */
    private static final String EDITOR_CLASS = "crystalgui-editor";

    private static final String HOST_STYLES =
            "." + ROOT_CLASS + " { width: 100%; height: 100%; }"
            // AND THE EDITOR FILLS ITS WINDOW. It used to BE the root and carry the rule above; since
            // W7 it is content inside a WindowFrame, so what it has to fill is the frame's content slot.
            // Same rule, one level down, and for the same reason: without it the editor sizes to content
            // and every percentage inside resolves against zero.
            + "." + EDITOR_CLASS + " { width: 100%; height: 100%; }";

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
    /** The window the editor lives in. @see #initGui */
    private static WindowFrame editorWindow;

    /**
     * Which desktop's record this is.
     *
     * <p>Per <em>installation</em> rather than per world, deliberately. A window arrangement is about the
     * shape of the screen and the way somebody likes to work; keying it to a save would mean starting from
     * defaults in every new world, which is the opposite of what persistence is for. The workspace session
     * beside it IS per project, because what a window CONTAINS is genuinely a fact about that project.</p>
     */
    private static final String DESKTOP_ID = "client";

    /**
     * How much of the screen a first-run editor takes.
     *
     * <p>Large enough to work in and small enough that the desktop is visibly underneath it, which is
     * the entire job of this number: a first run has to show that there IS a desktop. Persistence takes
     * over from the second run, so it is a first impression rather than a preference.</p>
     */
    private static final float FIRST_RUN_FRACTION = 0.86f;

    /** Run and Stop for the active file, or null where no engine band opened. @see #initGui */
    private static ScriptWorkbench scripting;

    /**
     * The connection the project list was last asked on, or {@code null} for never.
     *
     * <p>Was a {@code boolean}, and the editor is <b>static and outlives every screen</b> —
     * {@code disposeAll} says so itself: <i>"frees the editor at game shutdown, not called on close"</i>.
     * So a one-shot flag meant the project list was asked for <b>at most once per game session</b>,
     * however many worlds were joined afterwards. Leave a world, join another, press F6: an empty Project
     * panel, no root, New File and New Folder greyed because there is nowhere to create INTO — and
     * nothing in the log, because the ask never happened rather than failing.</p>
     *
     * <p>Keyed on the connection because that is what actually changes. Re-opening the editor on the same
     * wire must not re-ask (the tree already has its roots and a second listing would be pure churn), and
     * a new wire must.</p>
     */
    @Nullable
    private static ProtocolConnection<Object> projectsAskedOn;

    private long lastFrameNanos = System.nanoTime();

    /** Set by the pump when an Escape reached the window and nothing consumed it. @see CgUiInput */
    private boolean closeRequested;

    /**
     * F6 — the desktop, with the <b>editor brought forward</b> whatever state it was left in.
     *
     * <p>Restores it from minimised, un-hides it if it was closed, raises it and gives it the keyboard.
     * "Open the editor" has to mean that or the key is unreliable: a window that is merely retained is
     * still a window nobody can see.</p>
     */
    public static void openEditor() {
        open(true);
    }

    /**
     * F7 — the desktop, and <b>nothing else touched</b>.
     *
     * <p>Whatever is on it is what comes back, minimised windows included. That is the difference from
     * F6 and the whole reason there are two keys: without this there is no way to reach the desktop
     * except by putting an application in front of it, which is not a desktop, it is a wallpaper behind
     * a window.</p>
     */
    public static void openDesktop() {
        open(false);
    }

    private static void open(boolean bringEditorForward) {
        showEditorOnOpen = bringEditorForward;
        Minecraft.getMinecraft().displayGuiScreen(new CgUiScreen());
    }

    /** Which key opened this screen. @see #openEditor() */
    private static boolean showEditorOnOpen = true;

    /** Whether the editor has been built — read by the pump before it touches anything. */
    static boolean isReady() {
        return uiWindow != null;
    }

    /**
     * The one {@code UIWindow} on the client, or null before the screen has ever been opened.
     *
     * <p><b>Public because there is exactly one, and that is the point.</b> Anything with a UI to show
     * opens a {@code WindowFrame} on <em>this</em> desktop rather than standing up a second
     * {@code GuiScreen} — a second screen is a second claim on the input pump, the GL handoff, the
     * desktop's own persistence and the modal stack, and only one of them can be in front. The editor
     * is a window here; so is the worked example's panel. @see com.crystalgui.mc.example.MachineExampleClient
     */
    public static UIWindow window() {
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

        if (uiWindow != null) {
            // Reopening: the desktop comes back exactly as it was left. Everything below built it once.
            uiWindow.resumeDesktop();
            bringEditorForward();
            return;
        }

        buildDesktop();
        bringEditorForward();
    }

    /**
     * Builds the compositor — <b>and nothing that needs a workspace</b>.
     *
     * <h3>The desktop does not depend on the editor, and it used to</h3>
     *
     * <p>Everything here was once one method that ended by constructing a {@link CrystalEditor}. That
     * made the desktop unreachable without a server connection, and worse than unreachable: the editor's
     * constructor throws for a null client, so opening this screen from the main menu <b>crashed the
     * game</b> — past a log line saying it should be impossible. It was possible the moment a second key
     * could open the screen, and it was already possible for the unattended capture, which opens from
     * the main menu by design.</p>
     *
     * <p>So the split is not tidying. A desktop is a place; the editor is one application on it, built
     * when something asks for it and refused with a reason when it cannot be.</p>
     */
    private void buildDesktop() {
        trace("begin");
        File dataDir = mc.mcDataDir;
        // NO ROOT. The files live on the SERVER now (Phase 4 B2) -- in single-player that is the
        // integrated server, which is why there is one code path rather than a local special case.
        workspace = new Mc1710Workspace();
        trace("workspace + language registration");

        // BESIDE the workspace, not inside it: a session record is private and must not become part of
        // a project a resource pack could ship. ONE storage, shared: the editor's preferences and session
        // records and the desktop's window arrangement all live in the same private directory, and
        // building two would be two answers to the question of where that is.
        config = new LocalConfigStorage(new File(dataDir, "config/crystalgui").toPath());

        // A BARE ROOT, and the editor becomes a WINDOW on the desktop the UIWindow already owns.
        UIElement root = new UIElement();
        root.addClass(ROOT_CLASS);
        uiWindow = new UIWindow(Ui.of(root));
        // NOT INSTALLED FOR YOU. Without this the window matches no selector at all and the editor
        // renders as an unstyled column of boxes.
        uiWindow.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        uiWindow.getStyleEngine().addStylesheet(StyleSheet.parse(HOST_STYLES));

        // WHERE THE ARRANGEMENT LIVES, and nothing else -- CrystalOS W12. The compositor owns reading it,
        // applying it to windows as they open, and writing it again when the screen closes; a host has no
        // business holding a second copy of that policy.
        uiWindow.desktop().persistTo(config, DESKTOP_ID);

        // ATTACHED HERE, not left to the first frame -- a window opened before the tree HAS a UIWindow
        // gets no animation at all.
        //
        // WindowAnimator.start() begins `UIWindow window = frame.getAttachedWindow(); if (window == null)
        // return;`, which is right (an animation writes styles, and invalidateStyleMatch early-returns on
        // a detached element) and is silent. initGui builds the desktop and opens the editor immediately,
        // while the init below used to happen only in drawScreen -- so the FIRST window of every session
        // opened with no timeline, and every later one animated correctly. Every launch is a fresh
        // client, so the first open is exactly the one anybody testing this looks at: reported as the
        // open animation being broken when there was no open animation to be broken.
        //
        // Calling it twice is free -- drawScreen calls it every frame anyway, which is how a resize is
        // picked up -- and the display size is just as knowable here as it is there.
        //
        // Same trap AGENTS.md already records for a session restore that runs above uiWindow.init in the
        // same method. It is the ATTACH that is the precondition, not the paint.
        uiWindow.init(mc.displayWidth, mc.displayHeight);
        trace("UIWindow + stylesheets");
    }

    /**
     * Builds the editor window on first demand, or explains why it cannot.
     *
     * @return whether there is an editor window to bring forward
     */
    private boolean ensureEditorWindow() {
        if (editorWindow != null) return true;
        if (!workspace.isConnected()) {
            // THE FILES LIVE ON THE SERVER (Phase 4 B2) -- in single-player that is the integrated
            // server -- so there is genuinely nothing for a workbench to show without one. Named rather
            // than left to throw out of a constructor, because "the editor needs a world" and "the editor
            // is broken" look identical from the outside, and the second is what a crash report says.
            CrystalGuiCore.LOGGER.warn("The editor needs a world: CgUiConnections.client() is null, so the "
                    + "join event has not fired. The desktop is open and the editor is not on it.");
            return false;
        }
        File dataDir = mc.mcDataDir;
        editor = new CrystalEditor(workspace.client());
        trace("CrystalEditor construction");
        // THE SAME storage the desktop's arrangement went into. Two would be two answers to the question
        // of where a private directory is.
        editor.useConfig(config);

        editor.addClass(EDITOR_CLASS);

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
        // HIDE_ON_CLOSE, because a workbench is not a dialog: closing it keeps every document, the dock
        // arrangement and the undo history, and its taskbar entry is how it comes back. That is the same
        // promise the static fields above already made, now made by the lifecycle instead of by keeping
        // a reference nobody could see.
        //
        // Escape then falls out end to end with nothing here to arrange it: the cascade inside the
        // window runs first (a dropdown, then a modal), the window is its own last close watcher so its
        // policy minimises it, and only an Escape that nothing wanted reaches handleKeyboardInput below
        // and closes the screen.
        editorWindow = uiWindow.openWindow(new WindowFrame("Crystal Editor"));
        editorWindow.setPolicy(WindowPolicy.HIDE_ON_CLOSE).setKey("editor:main");
        editorWindow.setIcon("crystalgui:code");
        // setContent, not content().addChild -- it is what ADOPTS the editor's menu bar into the
        // caption, so the window has one header rather than two stacked on each other.
        editorWindow.setContent(editor);
        // A WINDOW ON A DESKTOP, not a full-screen editor — and only as a DEFAULT: the record below
        // overrides it the moment there is one, so this is what a first run looks like and nothing else.
        //
        // It used to maximise here, which was right while the desktop was a migration nobody was meant
        // to notice: a maximised frame IS the editor that was there before W7. Once the compositor is
        // the point, that default hides everything it was built for — the taskbar, the caption, snapping,
        // the fact that there is a desktop at all — behind an application that happens to fill the screen.
        //
        // Sized in LOGICAL pixels off the display and DEFAULT_UI_SCALE, which is the scale this host
        // deliberately never changes. A percentage in the sheet would be tempting and would lose to the
        // desktop's own cascade, which writes left/top at a higher origin for any window nobody placed.
        float logicalWidth = mc.displayWidth / UIWindow.DEFAULT_UI_SCALE;
        float logicalHeight = mc.displayHeight / UIWindow.DEFAULT_UI_SCALE;
        editorWindow.resizeTo(Math.round(logicalWidth * FIRST_RUN_FRACTION),
                Math.round(logicalHeight * FIRST_RUN_FRACTION));
        editorWindow.moveTo(Math.round(logicalWidth * (1f - FIRST_RUN_FRACTION) / 2f),
                Math.round(logicalHeight * (1f - FIRST_RUN_FRACTION) / 2f));

        // WHERE THE ARRANGEMENT LIVES, and nothing else -- CrystalOS W12. The compositor owns reading it,
        // applying it to windows as they open, and writing it again when the screen closes; a host has no
        // business holding a second copy of that policy. AFTER the editor window is open, so the record
        // is applied over the defaults above rather than under them.
        trace("editor window");
        return true;
    }

    /** Where every private record goes — the desktop's arrangement and the editor's session alike. */
    private static LocalConfigStorage config;

    /**
     * Brings the editor window forward — what F6 means and F7 deliberately does not.
     *
     * <p>Three states to cover and they are not the same: HIDDEN (minimised, or closed under
     * {@code HIDE_ON_CLOSE}) needs showing, VISIBLE-but-behind needs raising, and already-in-front needs
     * only the keyboard. {@code show} handles the first two and {@code activate} the last, so asking for
     * both unconditionally is correct rather than lazy.</p>
     */
    private void bringEditorForward() {
        // THE FLAG IS CHECKED HERE, not at each call site: both paths into the screen end up wanting the
        // same question asked, and a guard duplicated at two call sites is a guard one of them loses.
        //
        // F7 therefore never BUILDS the editor either, which is the half that matters for a first press:
        // asking for the desktop must not spend three seconds constructing an application to hide.
        // A DESKTOP WITH NOTHING ON IT IS A BLANK SCREEN, not a desktop. The compositor deliberately
        // takes up no space at all until a window exists -- an unused one must not swallow clicks meant
        // for whatever is behind it -- so an empty desktop has no taskbar either, and F7 pressed before
        // anything was ever opened would show the game and nothing else. The first open therefore builds
        // the editor whichever key asked for it; from then on the two keys differ as they should.
        boolean nothingOpen = uiWindow.desktop().registry().size() == 0;
        if (!showEditorOnOpen && !nothingOpen) return;
        if (!ensureEditorWindow()) return;
        // BUILT, and only RAISED for F6. Opening a window already shows it, so an empty desktop now has
        // something on it and a taskbar to reach it by; what F7 must not do is put it in front.
        if (!showEditorOnOpen) return;
        // CONSUMED, because it is a REQUEST and not a mode. It is static (a Minecraft GuiScreen is
        // constructed fresh on every display, so nothing about the request can live on the instance),
        // and left latched it stays true for the rest of the session -- so any later initGui re-raises
        // a window the user has just put away. Minecraft calls initGui from setWorldAndResolution, which
        // runs on every display resize, so "minimise the editor and nudge the game window" would bring
        // it straight back with nothing on screen to explain why.
        showEditorOnOpen = false;
        if (editorWindow.state() == WindowState.HIDDEN) editorWindow.show(true);
        uiWindow.desktop().activate(editorWindow);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        long now = System.nanoTime();
        float delta = (float) ((now - lastFrameNanos) / 1_000_000_000.0);
        lastFrameNanos = now;

        if (closeRequested) {
            closeRequested = false;
            mc.displayGuiScreen(null);
            // AND THE GAME TAKES THE MOUSE BACK. displayGuiScreen(null) calls setIngameFocus itself, but
            // only down a branch that also depends on the player being alive and a world being loaded --
            // and closing from here happens mid-frame, inside the drawScreen of the screen being closed.
            // Asking directly is idempotent when it already happened and is the difference between the
            // world coming back and the cursor still floating over an unresponsive game.
            if (mc.theWorld != null && mc.thePlayer != null) mc.setIngameFocus();
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
        ProtocolConnection<Object> live = CgUiConnections.client();
        // WHERE A SERVER'S WINDOWS GO. Re-asked per frame for the same reason the workspace client is:
        // free when the wire has not moved, and a rebind nothing re-asks for can never fire. Windows
        // that arrived before this point were queued by ClientWindows and land on the next tick.
        CgUiWindowMount.bind(live);
        if (live != null && live != projectsAskedOn) {
            projectsAskedOn = live;
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

        if (editor != null) editor.giveInitialFocus();

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
        // THE SENSE WAS INVERTED, and both halves of the symptom followed from it.
        //
        // consumeKeyboardEvent returns TRUE when the UI CONSUMED the key -- that return exists precisely
        // so a host can act on what is LEFT OVER -- and this read it as "unconsumed". So the screen
        // closed on an Escape the window had already dealt with, and stayed open on one nobody wanted:
        // Escape on bare desktop did nothing at all, while Escape in the editor closed a popup AND the
        // whole screen. Reported exactly that way -- "it only closes when I escape on the editor".
        boolean consumed = CgUiInput.pumpKeyboard(uiWindow);
        if (!consumed && Keyboard.getEventKeyState() && Keyboard.getEventKey() == Keyboard.KEY_ESCAPE) {
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
        // THE COMPOSITOR GOES OFF SCREEN, and every window is retained exactly as it is -- which of them
        // were open, where they were, which was in front. Detaching is also what drops the input state:
        // the hover, the press target and any live drag would otherwise still describe a screen that is
        // no longer up. Pinned windows are the exception W14 adds here.
        // AND EVERYTHING WRITES ITSELF. Suspending detaches the compositor, which detaches every
        // window on it and the editor inside one -- so the desktop records its arrangement and the editor
        // records its session and preferences, each because it went off screen and each knowing what it
        // is responsible for. A host says where the config directory is; it does not say what to put in
        // it. @see Desktop#persistTo and CrystalEditor#saveState
        if (uiWindow != null) uiWindow.suspendDesktop();
    }

    /**
     * <b>Does NOT pause single-player</b> — the game runs underneath the desktop.
     *
     * <p>This returned true, on the reasoning that it is "the conservative answer and what every
     * editor-like GUI in 1.7.10 does", and that returning false "invites a class of 'the world ticked
     * while a modal was open' questions". Both halves were about an editor. A DESKTOP is not an editor
     * and not a modal: the whole claim it makes is that it sits over the machine while the machine keeps
     * working, and one that stops the world is a full-screen application wearing a taskbar.</p>
     *
     * <p>It also read as a fault rather than a policy — reported as the desktop <em>freezing</em> the
     * game, which is exactly what a paused world looks like from inside it. And it is the direction W14
     * is already going: a pinned HUD over the running game is unbuildable if opening the compositor
     * stops the game.</p>
     *
     * <p>The player still cannot move, and that is unrelated to this: {@code GuiScreen.allowUserInput}
     * is false, so game input is gated out while any screen is up. What changes here is only whether the
     * WORLD ticks.</p>
     */
    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    /** Frees the editor at game shutdown. Not called on close — see the {@code editor} field. */
    public static void disposeAll() {
        if (editor != null) Disposer.dispose(editor);
        editor = null;
        editorWindow = null;
        uiWindow = null;
        workspace = null;
        projectsAskedOn = null;
    }
}
