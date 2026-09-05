package com.crystalgui.mc.client;

import com.crystalgui.desktop.host.DesktopHost;
import com.crystalgui.desktop.host.HostServices;
import java.nio.file.Path;
import com.crystalgraphics.api.render.CgRenderPipeline;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.app.crystaleditor.CrystalEditor;
import com.crystalgui.workbench.app.WorkbenchApplication;
import com.crystalgui.core.storage.LocalConfigStorage;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.core.window.WindowState;
import com.crystalgui.mc.net.CgUiConnections;
import com.crystalgui.net.protocol.ProtocolConnection;

import com.crystalgui.fs.client.Workspace;
import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.File;

/**
 * <b>The Minecraft screen CrystalGUI draws into</b> - a {@code GuiScreen} wrapped around a
 * {@link DesktopHost}.
 *
 * <p>Deliberately small. It answers the host's three questions, forwards Minecraft's screen lifecycle
 * ({@code initGui} to {@code shown}, {@code drawScreen} to {@code frame}, {@code onGuiClosed} to
 * {@code hidden}), and does nothing else. Which applications exist, what the layout is, which commands
 * answer to which keys and where focus starts are all decided above it.</p>
 *
 * <h3>Closing this screen does not close anything</h3>
 *
 * <p>The desktop, its windows and every running application outlive it - the screen is a viewport onto
 * them, so pressing Escape and reopening gets the same unsaved documents back. Only game shutdown
 * disposes the host.</p>
 *
 * <h3>Real device pixels, never {@code ScaledResolution}</h3>
 *
 * <p>{@code drawScreen}'s {@code mouseX}/{@code mouseY} arrive already divided by Minecraft's GUI scale,
 * and {@code GuiScreen.width}/{@code height} are the scaled size. <b>None of them are used here.</b> The
 * engine takes raw pixels and applies its own scale on the box tree's root transform, which is the
 * single definition of what {@code uiScale} means - feeding it pre-scaled numbers creates a second one,
 * and the two disagree by exactly the scale factor. The symptom is the nasty one: everything draws
 * correctly and every click lands somewhere else.</p>
 *
 * <h3>It must not pause the game</h3>
 *
 * <p>{@code doesGuiPauseGame()} is false, and that is load-bearing rather than a preference: pausing
 * stops the integrated server ticking, so the connection is never pumped and every request this screen
 * makes dies at its timeout. A workspace that appears empty in single-player and works in multiplayer is
 * this line.</p>
 *
 * @see CgUiInput for why input does not arrive through this class at all
 */
public final class CgUiScreen extends GuiScreen {

    /** @see #HOST_STYLES */
    private static final String ROOT_CLASS = "crystalgui-screen";

    /**
     * <b>The host sizes the root, and nothing else will.</b>
     *
     * <p>{@code UIDocument.init(w, h)} looks like it does this and does not: all it calls is
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
    private static WorkbenchApplication editor;
    /**
     * The scale this host draws at.
     *
     * <p>Still a HOST choice — the box tree takes whatever a host sets and this one could answer
     * something else. It defers to the seam's default so the two loaders cannot drift apart on a number
     * every shipped stylesheet was measured against.</p>
     */
    public static final float DEFAULT_UI_SCALE = HostServices.DEFAULT_UI_SCALE;

    /** When the last frame was presented, for the delta the engine's motion runs on. */
    private static long lastFrameNanos;

    /**
     * Seconds of RENDERED time since the previous frame.
     *
     * <p>Rendered, not wall: the first windows of a session open into the worst stall there is --
     * measured at 398ms and 282ms between consecutive frames -- so wall time would charge a 150ms
     * gesture its whole duration for frames nobody saw. The engine's own animation service clamps a
     * long gap; this only has to report one honestly. Zero on the first frame, which holds every
     * animation at its start value rather than completing it before anything was drawn.</p>
     */
    static float frameDelta() {
        long now = System.nanoTime();
        long previous = lastFrameNanos;
        lastFrameNanos = now;
        if (previous == 0L) return 0f;
        return (now - previous) / 1_000_000_000f;
    }

    /**
     * The surface, the compositor, the workspace and the window mount — everything a host is handed.
     *
     * <p><b>Static, and that is the point:</b> Minecraft builds a fresh {@code GuiScreen} every time the
     * screen is displayed, so a per-instance host would mean a new desktop — and a new set of windows and
     * unsaved documents — on every open. This one outlives every screen and is disposed at game
     * shutdown.</p>
     */
    private static DesktopHost host;

    /**
     * The workspace this screen's editor is using, or null before there is one.
     *
     * <p>For {@code CgUiEditorOpenProbe}, which has to work through the SAME workspace the editor is
     * using rather than opening one of its own — a second one would answer over the same wire and
     * prove nothing about whether the screen stopped the server from ticking.</p>
     */
    @Nullable
    public static Workspace workspaceForProbe() {
        return host == null ? null : host.workspace();
    }
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
        return host != null;
    }

    /**
     * The one {@code UIDocument} on the client, or null before the screen has ever been opened.
     *
     * <p><b>Public because there is exactly one, and that is the point.</b> Anything with a UI to show
     * opens a {@code WindowFrame} on <em>this</em> desktop rather than standing up a second
     * {@code GuiScreen} — a second screen is a second claim on the input pump, the GL handoff, the
     * desktop's own persistence and the modal stack, and only one of them can be in front. The editor
     * is a window here; so is the worked example's panel. @see com.crystalgui.mc.example.MachineExampleClient
     */
    public static UIDocument window() {
        return host == null ? null : host.document();
    }

    /**
     * This client's compositor, or null before the screen has ever been opened.
     *
     * <p>The host seam moved off the document at 6.9a and onto the compositor, which is where it
     * belongs: the engine may not name a desktop, so {@code Desktop.of} names the document and not
     * the reverse. Everything a loader used to ask a {@code UIWindow} -- suspend, resume, what to
     * present, whether anything is pinned, the overlay -- it asks here.</p>
     */
    public static Desktop desktop() {
        return host == null ? null : host.desktop();
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

        if (host != null) {
            // Reopening: the desktop comes back exactly as it was left. Everything below built it once.
            host.shown();
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
        host = DesktopHost.create(new Mc1710Host())
                // WHAT A SERVER'S WINDOW IS OFFERED FIRST. The workbench honours an editor-tab or
                // tool-window hint and hands everything else back to the desktop, so a client with no
                // editor open still gets every window -- which is the hint working rather than failing.
                // A supplier because the editor is built later than this and on demand.
                .setWindowMount(() -> editor == null
                        ? null : editor.workbench().windowMount(host.windowMount()));
        // NO SEPARATE ROOT: the DOCUMENT is the root on this engine, so the class the host sheet keys
        // on goes on it directly.
        host.document().addClass(ROOT_CLASS);
        host.document().styles().addStylesheet(StyleSheet.parse(HOST_STYLES));
        trace("DesktopHost + host stylesheet");
    }

    /**
     * The three things only this platform can answer. @see HostServices
     *
     * <p>Where private files go, how big a pixel is, and whether there is a server. Everything the
     * screen used to decide for itself — the window's title, its key, its icon, its close policy, its
     * first-run geometry, when to ask for the project list — was the same answer on every host and is
     * not asked here.</p>
     */
    private final class Mc1710Host implements HostServices {

        @Override
        public Path configDirectory() {
            // BESIDE the workspace, never inside it: a session record is private and must not become
            // part of a project a resource pack could ship.
            return new File(mc.mcDataDir, "config/crystalgui").toPath();
        }

        @Override
        public float uiScale() {
            return DEFAULT_UI_SCALE;
        }

        @Override
        public String desktopId() {
            return DESKTOP_ID;
        }

        @Override
        @Nullable
        public ProtocolConnection<Object> connection() {
            return CgUiConnections.client();
        }
    }

    /**
     * Builds the editor window on first demand, or explains why it cannot.
     *
     * @return whether there is an editor window to bring forward
     */
    private boolean ensureEditorWindow() {
        if (editorWindow != null) return true;
        // ONE CALL, AND EVERY REFUSAL IS THE REGISTRY'S. "The editor needs a world" used to be a log
        // line written here, beside the storage wiring, the class registration, the window's title, key,
        // policy and icon, the first-run geometry, the project ask and the session restore -- fourteen
        // decisions, none of which is about Minecraft, all of which are the same answer on every host.
        // What is left below is the two that genuinely are: how big a first-run window is on THIS
        // display, and that the arrangement record is applied over it rather than under it.
        com.crystalgui.desktop.app.Application launched = host.desktop().applications()
                .launch(CrystalEditor.KIND, host.workspace(), host.config());
        if (!(launched instanceof WorkbenchApplication)) return false;
        editor = (WorkbenchApplication) launched;
        trace("CrystalEditor launch");
        editor.addClass(EDITOR_CLASS);
        editorWindow = editor.mainWindow();

        // A WINDOW ON A DESKTOP, not a full-screen editor -- and only as a DEFAULT: the arrangement
        // record overrides it the moment there is one, so this is what a first run looks like and
        // nothing else.
        //
        // Sized in LOGICAL pixels off the display and DEFAULT_UI_SCALE, which is the scale this host
        // deliberately never changes. A percentage in the sheet would be tempting and would lose to the
        // desktop's own cascade, which writes left/top at a higher origin for any window nobody placed.
        float logicalWidth = mc.displayWidth / DEFAULT_UI_SCALE;
        float logicalHeight = mc.displayHeight / DEFAULT_UI_SCALE;
        editorWindow.resizeTo(Math.round(logicalWidth * FIRST_RUN_FRACTION),
                Math.round(logicalHeight * FIRST_RUN_FRACTION));
        editorWindow.moveTo(Math.round(logicalWidth * (1f - FIRST_RUN_FRACTION) / 2f),
                Math.round(logicalHeight * (1f - FIRST_RUN_FRACTION) / 2f));
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
        boolean nothingOpen = desktop().registry().size() == 0;
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
        desktop().activate(editorWindow);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        float delta = frameDelta();

        if (closeRequested) {
            closeRequested = false;
            mc.displayGuiScreen(null);
            // AND THE GAME TAKES THE MOUSE BACK. displayGuiScreen(null) calls setIngameFocus itself, but
            // only down a branch that also depends on the player being alive and a world being loaded --
            // and closing from here happens mid-frame, inside the drawScreen of the screen being closed.
            // Asking directly is idempotent when it already happened and is the difference between the
            // world coming back and the cursor still floating over an unresponsive game.
            if (mc.theWorld != null && mc.thePlayer != null) mc.setIngameFocus();

            // AND THE LAST FRAME OF THE FLICKER GOES HERE. Moving the decision into
            // DesktopPresentation removed the disagreement between the two paint hooks, but not this:
            // Minecraft renders the overlay BEFORE it draws the current screen, so on the frame this
            // method closes itself the overlay hook has already run and stood down -- and returning here
            // leaves that frame painted by nobody at all.
            //
            // By this point displayGuiScreen has run onGuiClosed (which entered HUD mode) and nulled the
            // current screen, so the presentation is already HUD and the pinned windows can simply be
            // painted now, in the frame that would otherwise have dropped them.
            desktop().paint(CgUiHud.presentation(), delta, mc.displayWidth, mc.displayHeight);
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

        // ONE HOST TICK, before anything reads the workspace: the client is repaired if the wire
        // moved, and the mount is re-asked. Both were written out here; both are the same on any host.
        host.frame(delta);
        // THE PROJECT ASK AND THE SESSION RESTORE WERE HERE, keyed on a static "which wire did I ask
        // on". Both are the application's: it hangs them off the greeting and the project listing, which
        // fire again on a reconnect -- so rejoining a different world reads THAT world's record, which
        // a per-process latch could never do. @see WorkbenchApplication


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

        // THROUGH THE PRESENTATION, not straight at paintFrame. It resolves to DESKTOP for as long as
        // this screen is the current one, so nothing about what is drawn changes -- what changes is that
        // no caller decides for itself whether it is its turn. That is the whole of the close flicker:
        // this method closes the screen from inside itself, and the overlay hook for the same frame had
        // already run and stood down, so the frame was painted by nobody. @see DesktopPresentation
        desktop().paint(CgUiHud.presentation(), delta, mc.displayWidth, mc.displayHeight);
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

        framesPainted++;
        // UNATTENDED SCRIPT RUN, off unless asked for. On the CLIENT THREAD, which is where the Run
        // command runs too -- a probe on a worker would prove nothing about a failure that reaches the
        // game loop. @see CgUiAutoTest#runScriptOnce
        if (framesPainted == CgUiAutoTest.RUN_SCRIPT_ON_FRAME) {
            CgUiAutoTest.runScriptOnce();
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
        if (host == null) return;
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
        UIDocument window = window();
        if (window == null) return;
        // THE SENSE WAS INVERTED, and both halves of the symptom followed from it.
        //
        // consumeKeyboardEvent returns TRUE when the UI CONSUMED the key -- that return exists precisely
        // so a host can act on what is LEFT OVER -- and this read it as "unconsumed". So the screen
        // closed on an Escape the window had already dealt with, and stayed open on one nobody wanted:
        // Escape on bare desktop did nothing at all, while Escape in the editor closed a popup AND the
        // whole screen. Reported exactly that way -- "it only closes when I escape on the editor".
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
        if (host != null) host.hidden();
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

    /** Quits the editor at game shutdown. Not called on close — see the {@code editor} field. */
    public static void disposeAll() {
        if (editor != null) editor.dispose();
        if (host != null) host.dispose();
        editor = null;
        editorWindow = null;
        host = null;
    }
}
