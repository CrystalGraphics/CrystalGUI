package com.crystalgui.harness.scene;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.ui.service.Animation;
import com.crystalgui.app.crystaleditor.CrystalEditor;
import com.crystalgui.workbench.app.WorkbenchApplication;
import com.crystalgui.core.window.WindowPolicy;
import com.crystalgui.core.window.WindowState;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.DesktopCommands;
import com.crystalgraphics.trace.CgFrameImages;
import com.crystalgraphics.trace.CgFrameRecord;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceSnapshot;
import com.crystalgui.core.trace.UiTrace;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.crystalgui.widget.display.SpanTrack;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.app.frameprofiler.FrameProfiler;
import com.crystalgui.app.frameprofiler.ChainsTab;
import com.crystalgui.app.frameprofiler.FrameImageView;
import com.crystalgui.app.frameprofiler.FrameProfilerPanel;
import com.crystalgui.app.frameprofiler.HintsTab;
import com.crystalgui.app.frameprofiler.ProfilerModel;
import com.crystalgui.app.frameprofiler.ProfilerSettings;
import com.crystalgui.core.settings.Setting;
import com.crystalgui.core.settings.SettingsLayer;
import com.crystalgui.desktop.taskbar.TaskbarDesigner;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.render.UiFrame;
import com.crystalgui.render.UiGpu;
import com.crystalgui.ui.dom.UIElement;
import dev.vfyjxf.taffy.style.FlexDirection;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.DocumentDriver;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.input.keymap.KeyChord;
import com.crystalgui.ui.input.keymap.Keymap;
import com.crystalgui.widget.canvas.CanvasView;
import com.crystalgui.widget.graph.GraphNode;
import com.crystalgui.widget.graph.GraphView;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.control.Checkbox;
import com.crystalgui.widget.display.FrameStripTrack;
import com.crystalgui.widget.collection.tree.TreeView;
import com.crystalgui.core.collection.tree.TreeRow;
import com.crystalgui.app.frameprofiler.CallTreeTab;
import com.crystalgui.widget.layout.SplitView;
import com.crystalgui.widget.config.control.MaskControl;
import com.crystalgui.widget.display.FrameStatsOverlay;
import com.crystalgui.widget.overlay.Dialog;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.workbench.Workbench;
import com.crystalgui.workbench.toolwindow.ToolWindowManager;
import com.crystalgui.workbench.toolwindow.ToolWindowType;

import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgui.fs.CgPath;
import com.crystalgui.app.shadergraph.ShaderGraphDocument;
import com.crystalgui.document.Document;
import com.crystalgui.graph.NodeData;
import com.crystalgui.harness.HoverSweepProbe;
import com.crystalgui.harness.ShaderGraphCostProbe;
import com.crystalgui.harness.TraceCostProbe;

import javax.annotation.Nullable;

import com.crystalgui.core.data.Transform2D;
import org.joml.Vector2f;


/**
 * CrystalOS, hands-on — the compositor scene, and the one place any of it can be driven by hand.
 *
 * <h3>This is the {@code windowing} scene, on the M6 engine</h3>
 *
 * <p>A PORT of that file rather than a fresh scene, and deliberately: it is the compositor's manual,
 * written a W at a time as {@code plan/shell-windowing.md} shipped, and everything it tells you to try is a
 * thing somebody built the demonstration for on the day. Rewriting it would have thrown that away and
 * quietly replaced it with whatever this engine happened to make easy — the difference between a scene
 * that can find a regression and one that can only show what already works.</p>
 *
 * <p>So far: W1 geometry and chrome, W2 stacking and activation, W3 the lifecycle, W4 the taskbar,
 * W5 window-scoped modality, W6 maximise and restore, W7 the editor as a window, W8 tool-window
 * tear-out, W10 the switcher, W12 persistence and the no-steal rule, W13a the window command set and
 * its system menu, W13b Alt-drag, fullscreen and snap, W14 game mode.</p>
 *
 * <h3>What to do in here</h3>
 * <ul>
 *   <li><b>Drag a title bar.</b> The clamp is Windows', not a panel's: a window may hang off the sides
 *       and the bottom, and its caption can never leave the top. Push one into a corner to see which
 *       edges give. <i>(W1)</i></li>
 *   <li><b>Drag any edge or corner.</b> All eight handles come from {@code resize: both} in
 *       {@code ua/desktop.css} — nothing in {@code WindowFrame} builds them. <i>(W1)</i></li>
 *   <li><b>Overlap them and click the one behind.</b> It comes forward <em>and the click still
 *       lands</em> — press a button in a background window and watch it both raise the window and
 *       press. The caption of the active one is coloured; the rest go quiet. <i>(W2)</i></li>
 *   <li><b>Click a button in one window, switch to another, and click back.</b> Focus returns to the
 *       button you left, not to the first thing in the window — and clicking a title bar to drag does
 *       not count as leaving it. <i>(W2)</i></li>
 *   <li><b>Click bare desktop.</b> No window is active, which is a legal state and what every desktop
 *       does when you click its background. <i>(W2)</i></li>
 *   <li><b>Resize the harness window.</b> Every frame re-clamps against the new work area, and windows
 *       pushed in by a shrinking desktop go back to where you put them when the room returns.
 *       <i>(W1)</i></li>
 *   <li><b>F2 opens another window</b>, placed by the Win32 cascade — one caption height further along
 *       each time, wrapping back to the origin when the next step would put it off the work area.
 *       <i>(W1)</i></li>
 *   <li><b>Minimise a window and watch its tick counter stop.</b> That counter is the whole point of
 *       the freeze: a hidden window is <em>detached</em>, so it lays out nothing, paints nothing, holds
 *       no input state and runs nothing either. Its taskbar entry brings it back and the counter
 *       <em>resumes from where it stopped</em>, which is retention working rather than a window being
 *       rebuilt. <i>(W3)</i></li>
 *   <li><b>The strip along the bottom.</b> One entry per window that exists — a minimised one is
 *       dimmed, never dropped, because the entry IS the way back. Click an entry to bring its window
 *       forward; click the entry of the window you are already in and it minimises, which is the toggle
 *       that makes a taskbar a taskbar. The entries never move: open order, whatever you activate.
 *       <i>(W4)</i></li>
 *   <li><b>Press "Modal" and the dialog blocks ONE window.</b> The window it belongs to stops
 *       answering clicks and dims behind a backdrop; every other window, and the taskbar, stays
 *       completely live — drag them, click their buttons, minimise them. Escape closes the dialog
 *       first and the window second, which is the cascade in one gesture. <i>(W5)</i></li>
 *   <li><b>Maximise, or double-click a caption.</b> A maximised window fills the WORK AREA — the
 *       taskbar's row is not part of it, because the strip is laid out rather than overlaid — its
 *       resize handles go away, and its glyph turns into "restore". Then <b>drag its title bar</b>: it
 *       comes loose under the pointer, keeping the same fraction across the caption. A press alone does
 *       nothing, or a double-click could never work. <i>(W6)</i></li>
 *   <li><b>The Crystal Editor is a window here.</b> The whole workbench — file tree, tabs, dock,
 *       panels — inside a {@code WindowFrame}, over the harness's own workspace, sharing the desktop
 *       with everything else. <b>Its menu lives in the window's caption</b> — client-side decorations,
 *       so there is one header rather than two stacked on each other. <i>(W7)</i></li>
 *   <li><b>The close button</b> destroys by default, and the window behind it takes over. The Welcome
 *       window is deliberately {@code HIDE_ON_CLOSE} instead — close it and it minimises. <i>(W3)</i></li>
 *   <li><b>F4 opens a window WITHOUT taking focus</b> — the no-steal rule. Type in the editor while you
 *       press it: the caret never moves, the editor keeps its lit caption, and the new window goes in
 *       <em>behind</em> it. Its taskbar entry is highlighted until you click it, carries a <b>badge</b>,
 *       and <b>fills with progress</b> for four seconds before both go away. <b>Middle-click any
 *       entry</b> to close its window through its own policy. <i>(W12)</i></li>
 *   <li><b>Move the windows, quit, and run it again.</b> They come back where you left them, in the
 *       order you left them, with the minimised ones still minimised. <i>(W12)</i></li>
 *   <li><b>Right-click a title bar. Right-click a taskbar entry. Press {@code Alt+-}.</b> The same
 *       four rows in three places, because they are one command set with three renderers — Restore,
 *       Minimize, Maximize, Close, each greyed exactly when it does not apply. <b>Restore and Maximize
 *       are two rows</b>, as Win32's are, rather than one whose label changes under you. <i>(W13a)</i></li>
 *   <li><b>Right-click the entry of a window that is NOT in front, and choose Minimize.</b> It
 *       minimises <em>that</em> window, not the one you were looking at. A taskbar entry is not inside
 *       the window it stands for, so it has to say which window it means. <i>(W13a)</i></li>
 *   <li><b>Hold Alt and drag anywhere inside a window</b> — over its content, over a button, over the
 *       editor's text. The Linux WM staple, and the answer for a window whose title bar is tiny or
 *       covered by adopted chrome. It takes the press in the CAPTURE phase, which is the whole of what
 *       makes "anywhere" true. <i>(W13b)</i></li>
 *   <li><b>Drag a window's caption to the left or right edge.</b> A translucent rectangle shows where
 *       it will land — <em>behind</em> the window you are dragging, so it reads as a hole in the desktop
 *       rather than a sheet over your hand — and releasing snaps it to that half. <b>Drag to the top</b>
 *       to maximise. Press Escape mid-drag and the preview goes with it. <i>(W13b)</i></li>
 *   <li><b>F11 fullscreens</b> the active window: it fills the desktop and the taskbar goes with it.
 *       Nothing special had to be built — the strip is <em>laid out</em>, so hiding it re-flows the work
 *       area and the maximised window follows. <i>(W13b)</i></li>
 *   <li><b>Open the system menu and choose Move, then use the arrows.</b> Enter keeps the result,
 *       Escape puts the window back <em>exactly</em> where it was, Shift nudges finely, and any other
 *       key ends the mode and still does whatever it was going to do. This is the only way to move a
 *       window whose title bar has ended up off the work area. <i>(W13c)</i></li>
 *   <li><b>Right-click the taskbar itself</b> — the strip, not an entry — for <b>Show Desktop</b>.
 *       Everything minimises; run it again and exactly what it took comes back. Two menus on one strip,
 *       and the distinction is what they are about: an entry's names a window, the strip's names the
 *       desktop. <i>(W13c)</i></li>
 *   <li><b>Look for a chord beside Close. There isn't one.</b> {@code Ctrl+W} closes an editor tab —
 *       frequent, cheap, undoable — and a window takes its content with it, so the destructive one is
 *       deliberately not a keystroke away from the common one. <i>(W13a)</i></li>
 * </ul>
 *
 * <p>The readout window is deliberately a <em>live</em> one rather than text painted over the scene: it
 * is the only way to watch the clamp arithmetic while dragging, and it doubles as content that proves a
 * window clips what it holds.</p>
 *
 * <h3>What the port changed, and none of it is a choice</h3>
 * <ul>
 *   <li><b>The tick counter is an OWNED hook.</b> The old one was a {@code UIFrameTicker} that had to
 *       return false once its element left the tree, and re-register itself from {@code onShown}
 *       because registration was one-way — the widget's half of the freeze, and the half that is
 *       invisible when it is missing. {@code Animation.every(owner, hook)} is dropped when the owner
 *       is frozen, so the counter is only a counter now. The demonstration is unchanged; the contract
 *       it was demonstrating is gone.</li>
 *   <li><b>The modal is {@code attachOwned}</b> where the old scene called
 *       {@code uiWindow.addOverlay(dialog, owner.content())}. Same surface, named by the frame.</li>
 *   <li><b>Every size here is in LOGICAL pixels</b>, which is half the surface at this scene's 2x. The
 *       old scene ran at 1x and its numbers are its own — a 560x380 editor there is the whole desktop
 *       here, which is exactly what the first run of this port did.</li>
 * </ul>
 */
public class CgUiDesktopScene
        implements InteractiveSceneLifecycle, CgSystemInput.Keyboard, CgSystemInput.Mouse {

    /** The harness runs at 2x, like every other CrystalGUI scene, so text is legible in a screenshot. */
    private static final float SCALE = 2f;

    private UIDocument document;
    private Desktop desktop;

    /** The live geometry readout, refreshed per frame. */
    private UIText readout;

    /**
     * W12 is <em>seen</em> here rather than reasoned about — move the windows, close the harness, run it
     * again and they are where you left them. Its own id, so an M6 arrangement and the old scene's
     * cannot overwrite each other while both engines still run.
     */
    private static final String DESKTOP_ID = "harness-m6";

    /** Numbers the cascade, so a new window is identifiable once there are six of them. */
    private int spawned;

    /** The host's own root, which is all a host provides: the desktop fills whatever it is given. */
    private static final String STYLES = """
            .demo-root { width: 100%; height: 100%; }
            .desktop-editor { width: 100%; height: 100%; }
            """;

    /** Both halves of a real workspace, in this process — the same fixture the dock scene runs on. */
    private final HarnessWorkspace workspace = new HarnessWorkspace();

    private WorkbenchApplication editor;
    private boolean projectsAsked;
    private boolean focusGiven;
    private int backgroundWindows;

    /**
     * Runs the document: on this thread, or on a sequence with {@code -Dcrystalgui.ui.async=true} or
     * {@code -Dcrystalgui.ui.sequence=true}. Everything the scene does to the tree goes through it.
     */
    private DocumentDriver<Void> driver;
    private DocumentDriver.Painter<Void> painter;

    @Override
    public void init(HarnessContext ctx) {
        document = new UIDocument();
        driver = DocumentDriver.attach(document);
        painter = DocumentDriver.whole(document, () -> {
            try (CgTrace.Zone ignored = CgTrace.zone(UiTrace.FRAME, "scene:readout")) {
                refreshReadout();
            }
        }, null);
        driver.run(() -> build(ctx));
    }

    private void build(HarnessContext ctx) {
        if (driver.sequence() == null) document.markFrameThread();
        document.styles().addStylesheet(StyleSheet.DEFAULT);
        document.styles().addStylesheet(StyleSheet.parse(STYLES));
        document.boxes().setUiScale(SCALE);

        // ALL A HOST PROVIDES IS A ROOT WITH A SIZE. Nobody constructs a desktop -- `Desktop.of` finds
        // or builds the document's one, because the engine may not name a compositor, so the compositor
        // names the document.
        UIElement root = new UIElement();
        root.addClass("demo-root");
        document.append(root);
        desktop = Desktop.of(document);

        // THE HUD IS THE ENGINE'S, not the harness's -- the same class a Minecraft host attaches, so a
        // number read here means what it means in game. Over everything, hit-tests nothing, F7 to hide.
        FrameStatsOverlay.attach(document);

        WindowFrame welcome = desktop.addWindow(new WindowFrame("Welcome"));
        // HIDE_ON_CLOSE, so one window in the scene demonstrates the other half of the policy: its
        // close button minimises rather than destroys.
        welcome.setPolicy(WindowPolicy.HIDE_ON_CLOSE).setKey("desktop:welcome");
        welcome.setIcon("crystalgui:package");
        welcome.moveTo(24, 24).resizeTo(250, 210);
        welcome.content().append(paragraph(
                "CrystalOS — geometry, chrome, stacking.",
                "",
                "Drag the title bar to move me.",
                "Drag any edge or corner to resize me.",
                "My caption cannot leave the top of the",
                "desktop, but my body may hang off the",
                "sides and the bottom.",
                "",
                "F2 opens a window; the strip below",
                "brings one back. Minimise me and my",
                "counter stops.",
                "",
                "F3 floats the editor's Project panel.",
                "F4 opens one WITHOUT taking focus.",
                "F5 opens the Taskbar Designer.",
                "F6 is GAME MODE: only PINNED windows",
                "paint, and they keep ticking.",
                "",
                // READ FROM THE KEYMAP, never spelled. The chord is a command's declared default and any
                // keymap above overrides it, so a literal here is a promise the scene cannot keep -- the
                // same rule every tooltip and menu accelerator in the engine follows.
                switcherHint()));
        installTickCounter(welcome);
        // TWO BUTTONS PER WINDOW, so focus memory has somewhere to be remembered: focus one, click the
        // other window, click back, and the same button should still hold it. One button per window
        // would pass by coincidence -- the focus delegate is the first focusable, which with one
        // control IS the remembered one.
        welcome.content().append(focusRow("Alpha", "Beta"));
        welcome.content().append(modalButton(welcome));

        // OVERLAPPING ON PURPOSE, and added second so it is the one on top: equal z-index sorts
        // later-inserted-first, and paint walks that list in reverse. Two windows that never touch
        // demonstrate nothing about stacking.
        WindowFrame inspector = desktop.addWindow(new WindowFrame("Geometry"));
        // A KEY IS WHAT A RECORD CAN NAME A WINDOW BY; without one a window is anonymous and comes back
        // at its default place every run.
        inspector.setKey("desktop:geometry");
        inspector.setIcon("crystalgui:code");
        inspector.moveTo(150, 250).resizeTo(300, 150);
        readout = new UIText("");
        inspector.content().append(readout);
        inspector.content().append(focusRow("Gamma", "Delta"));
        inspector.content().append(modalButton(inspector));

        // WHERE THIS HARNESS WRITES, asked of the harness rather than decided here -- and before
        // anything launches onto the desktop, since that is what an application is given.
        desktop.useStorage(ctx.installation());

        openEditorWindow();

        // WHERE THE ARRANGEMENT LIVES -- W12 -- and LAST, so it is applied over whatever the scene above
        // set up rather than under it. Every window this scene opens carries a key, which is what a
        // record can name one by; a key from an older version of this scene simply matches nothing.
        // With no record at all it does nothing, so a first run looks exactly as it always has.
        desktop.persistAs(DESKTOP_ID);
    }

    /**
     * "Ctrl+Tab cycles windows", with the chord <b>resolved</b> rather than written down.
     *
     * <p>Null scope on purpose: nothing is focused when the scene is built, and {@code acceleratorFor}
     * falls through to the command's own declared default — which is what a keymap-less harness has.</p>
     */
    private static String switcherHint() {
        KeyChord chord = Keymap.acceleratorFor(null, DesktopCommands.SWITCH_WINDOW);
        return chord == null ? "The window switcher is unbound." : chord + " cycles windows (MRU).";
    }

    /**
     * The whole editor, as one window on the desktop — W7's shape, run in the harness.
     *
     * <p>Opened <b>floating</b> here and maximised in game, and the difference is the point rather than
     * an inconsistency: in game there is nothing else on the desktop yet, so a maximised frame is the
     * full-screen editor that was there before W7 and nobody notices the migration. Here the whole
     * exercise is seeing it share a desktop, so it starts at a size that leaves the other windows
     * visible. Double-click its caption for the in-game default.</p>
     *
     * <p>{@code ScriptWorkbench} IS installed here now. It used to be {@code cgui-dock}'s, on the
     * division that this scene is about the compositor and that one about the editor with everything
     * on — sound while both could run the editor, and gone the moment `ScriptWorkbench` moved to the
     * new engine: {@code cgui-dock} is the old engine's and is deleted at 6.9b, so without this there
     * would be no scene that runs the Run panel at all.</p>
     */
    private void openEditorWindow() {
        // ONE CALL, AND NO ASSEMBLY. The title, the key, the policy, the icon, the storage, the
        // extensions, the project ask, the session restore and the initial focus were all written here
        // and in the 1.7.10 screen, twice, and they are the same answer on both -- so they are the
        // manifest's and the engine's. What is left is what this SCENE decides: where the window sits,
        // and a class for the readout to style it by.
        //
        // Beside the scratch workspace, never inside it: a session record is private and must not become
        // part of a project a resource pack ships. The dock scene keeps its own for the same reason.
        editor = (WorkbenchApplication) desktop.applications().launch(CrystalEditor.KIND,
                workspace.workspace());
        if (editor == null) return;
        editor.addClass("desktop-editor");
        if (GRAPH_COST) graphCost = graphCostProbe();
        if (HOVER_SWEEP) hoverSweep = hoverSweepProbe();
        // The Frame Profiler open for the whole run, live: what its own refresh costs a frame being profiled.
        if (GRAPH_COST && Boolean.getBoolean("crystalgui.harness.desktop.graphCost.viewer")) FrameProfiler.openOn(desktop);
        // A SIZE THIS SCENE CHOOSES, over whatever the arrangement record says: the whole exercise here
        // is watching the editor share a desktop, so it starts small enough to see the other windows.
        editor.mainWindow().moveTo(300, 55).resizeTo(600, 400);
    }

    private UIElement paragraph(String... lines) {
        UIElement box = new UIElement();
        for (String line : lines) box.append(new UIText(line));
        return box;
    }

    /**
     * A per-window counter that ticks while the window is on the desktop and <b>stops dead while it is
     * hidden</b> — the freeze, made watchable.
     *
     * <p><b>An OWNED hook, where the old scene needed the widget's own half of the contract.</b> That
     * version was a {@code UIFrameTicker} which had to return false once its element left the tree, and
     * re-register itself from {@code onShown} because registration was one-way. Both are gone:
     * {@code Animation.every} is dropped when its owner is frozen or disconnected, and the
     * {@code onShown} line below asks for it back. Restoring resumes the count rather than resetting
     * it, which is the difference between retention and a window that was rebuilt.</p>
     */
    private void installTickCounter(WindowFrame frame) {
        UIText label = new UIText("ticks 0");
        frame.content().append(label);
        int[] ticks = {0};
        Animation.Hook tick = delta -> {
            label.setText("ticks " + (++ticks[0]));
            return true;
        };
        // IF ABSENT: a hidden window's hook is frozen, not dropped, so a show would otherwise add a second.
        frame.onShown.connect(persisted -> document.animation().everyIfAbsent(frame, tick));
        document.animation().everyIfAbsent(frame, tick);
    }

    /**
     * Opens a modal <b>owned by this window</b> — the W5 demonstration.
     *
     * <p>{@code attachOwned} parents it on the window's own overlay slot rather than in the global top
     * layer, which is what scopes the modality. Everything outside the window carries on working, which
     * is the entire point.</p>
     */
    private UIElement modalButton(WindowFrame owner) {
        Button open = new Button("Modal");
        open.onPressed.connect(() -> {
            Dialog dialog = new Dialog("Owned by " + owner.getTitle());
            owner.attachOwned(dialog);
            dialog.append(new UIText("This blocks " + owner.getTitle() + "."));
            dialog.append(new UIText("Everything else still works."));
            dialog.showModal();
        });
        UIElement row = new UIElement();
        row.layout(l -> l.flexDirection(FlexDirection.ROW).paddingTop(4));
        row.append(open);
        return row;
    }

    /** Two focusable controls that do nothing but hold focus — which is the whole point of them. */
    private UIElement focusRow(String first, String second) {
        UIElement row = new UIElement();
        row.layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(4).paddingTop(4));
        row.append(new Button(first));
        row.append(new Button(second));
        return row;
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        long workStart = System.nanoTime();

        // ONE NETWORK TICK, before anything reads the workspace.
        float delta = frame.getDeltaTime();
        driver.post(() -> {
            try (CgTrace.Zone ignored = CgTrace.zone(UiTrace.FRAME, "scene:workspacePump")) {
                workspace.pump(delta);
            }
        });
        // THE PROJECT ASK AND THE SESSION RESTORE WERE HERE, behind a "have I asked yet" flag this scene
        // kept for itself and the 1.7.10 screen kept for itself. Both are the application's now: it
        // hangs them off the greeting and the project listing, so the ordering is stated once and a
        // reconnect re-asks. @see WorkbenchApplication

        int w = ctx.getScreenWidth();
        int h = ctx.getScreenHeight();
        // Nothing here reinjects a key the document left: the harness has no game to give it to.
        while (driver.pollUnhandledKey() != null) { }

        if (NODES_SHOT && driveNodesShot(ctx, frame.getFrameNumber(), w, h)) return;
        if (PRESENT_AGAIN && frame.getFrameNumber() > 60 && frame.getFrameNumber() % 2 == 1) {
            driver.run(() -> {
                document.frame(delta, w / SCALE, h / SCALE);
                refreshReadout();
            });
            UiGpu.presentAgain(w, h);
            if (frame.getFrameNumber() == 101) ctx.getArtifactService().requestCapture("present-again");
            return;
        }
        if (PRESENT_AGAIN && frame.getFrameNumber() == 100) ctx.getArtifactService().requestCapture("presented");
        // SIMULATED GAME MODE -- W14, and the closest a GL harness gets to Minecraft without being it.
        // F6 flips the scene to input-off and leaves ONLY pinned frames on screen. What it is really
        // testing is the thing no unit test can reach: that a pinned window keeps its hooks and its
        // transitions while every unpinned window is frozen, and that nothing runs the hover pipeline
        // meanwhile.
        //
        // ONE PAINT ENTRY, where the old scene had two. The old `UIWindow` took a
        // `DesktopPresentation` and painted a different subtree for the HUD; the new `enterHudMode`
        // HIDES the unpinned windows instead, so what is left on screen is already exactly the HUD and
        // an ordinary paint draws it. `DesktopPresentation` still exists -- it is one of the three
        // types in `core.window` both engines name -- and on this engine nothing reads it yet.
        driver.frame(delta, w, h, painter);
        // The probes read and drive the tree, so they run on the document -- waited for, which is why they are off unless asked.
        if (probing()) driver.run(() -> afterFrame(ctx, frame, workStart));
        // Late enough that the first window's placement, the entry animations and the editor's own
        // deferred rebuilds have all settled -- a capture at frame 5 photographs a desktop that is
        // still assembling itself and every diff against it is noise. Counted in frames that showed the
        // document: one recording on its own presents nothing until its first frame is done.
        if (driver.presentedFrames() == 40) ctx.getArtifactService().requestCapture("startup");
    }

    private boolean probing() {
        return traceCost != null || hoverSweep != null || closeWhenClean || graphCost != null || PROFILER_SHOT
                || MINIMISE_SHOT;
    }

    private void afterFrame(HarnessContext ctx, FrameInfo frame, long workStart) {
        if (traceCost != null && traceCost.frame(System.nanoTime() - workStart)) traceCostDone = true;
        if (hoverSweep != null && hoverSweep.frame(System.nanoTime() - workStart)) hoverSweepDone = true;
        if (closeWhenClean) {
            Document graph = graphDocument();
            if (graph == null || !graph.isDirty()) {
                closeWhenClean = false;
                if (editor != null) editor.workbench().dock().closePanel(editor.workbench().refFor(GRAPH_COST_FILE));
            }
        }
        String graphShot = graphCost == null ? null : graphCost.captureNow();
        if (graphShot != null) ctx.getArtifactService().requestCapture(graphShot);
        if (graphCost != null && graphCost.frame()) graphCostDone = true;
        if (PROFILER_SHOT) driveProfilerShot(ctx, frame.getFrameNumber());
        if (MINIMISE_SHOT) driveMinimiseShot(ctx, frame.getFrameNumber());
    }

    /**
     * {@code -Dcrystalgui.harness.desktop.presentAgain=true}: from frame 61 every odd frame paints nothing and shows the
     * last frame again ({@link UiGpu#presentAgain}). {@code present-again} (frame 101) must match {@code presented}.
     */
    private static final boolean PRESENT_AGAIN = Boolean.getBoolean("crystalgui.harness.desktop.presentAgain");

    // ── -Dcrystalgui.harness.desktop.nodes=true: a scroll and a window move as property values ──

    /**
     * At frame 91, moves a window and scrolls a box by writing the presented frame's values and executing it again
     * ({@link UiGpu#redraw}), with nothing recorded or built: {@code nodes-redraw}. At frame 92, makes the same changes
     * in the document and photographs the frame it records: {@code nodes-recorded}. The two must match but for the
     * band the scroll revealed, which the first recording never held. Prints {@code [nodes-probe]} lines. Inline only:
     * run with {@code -Dcrystalgui.ui.async=false}.
     */
    private static final boolean NODES_SHOT = Boolean.getBoolean("crystalgui.harness.desktop.nodes");
    private static final float NODES_MOVE_X = 40f, NODES_MOVE_Y = 30f, NODES_SCROLL = 24f;

    private WindowFrame nodesWindow;
    private Box nodesScroller;

    /** @return whether this frame was the redraw, which presents in place of the document's frame */
    private boolean driveNodesShot(HarnessContext ctx, long frameNumber, int w, int h) {
        // The window to move goes on top first: one behind the maximised editor would move unseen.
        if (frameNumber == 30) {
            driver.run(() -> {
                for (WindowFrame window : desktop.windows()) {
                    if (!window.isMaximized() && window.box() != null) {
                        desktop.activate(window);
                        break;
                    }
                }
            });
        }
        if (frameNumber == 90) ctx.getArtifactService().requestCapture("nodes-before");
        if (frameNumber == 91) {
            UiFrame shown = UiGpu.presented();
            if (shown == null || driver.isAsync()) {
                System.out.println("[nodes-probe] SKIPPED: needs a presented frame, inline (-Dcrystalgui.ui.async=false)");
                return false;
            }
            long id = shown.frameId();
            for (WindowFrame window : desktop.windows()) {
                Box box = window.box();
                if (box != null && box.movedNode(id) != 0 && !window.isMaximized()) {
                    nodesWindow = window;
                    break;
                }
            }
            Box root = document.boxes().root();
            nodesScroller = root == null ? null : scrolledBox(root, id);
            if (nodesWindow == null || nodesScroller == null) {
                System.out.println("[nodes-probe] SKIPPED: window " + nodesWindow + ", scroller " + nodesScroller);
                return false;
            }
            int moved = nodesWindow.box().movedNode(id), scrolled = nodesScroller.scrolledNode(id);
            shown.values().translate(moved, NODES_MOVE_X * SCALE, NODES_MOVE_Y * SCALE);
            shown.values().translate(scrolled, 0f, -NODES_SCROLL * SCALE);
            UiGpu.redraw(w, h);
            ctx.getArtifactService().requestCapture("nodes-redraw");
            System.out.println("[nodes-probe] frame " + id + " redrawn: window node " + moved + " ('" + nodesWindow.getTitle()
                    + "' at " + nodesWindow.left() + "," + nodesWindow.top() + "), scroll node " + scrolled + " ("
                    + nodesScroller.node().tagName() + " scrolled " + nodesScroller.scrollTop() + " of "
                    + nodesScroller.maxScrollTop() + ", inside the window " + isInside(nodesScroller, nodesWindow.box())
                    + ")");
            return true;
        }
        if (frameNumber == 92 && nodesWindow != null && nodesScroller != null) {
            long before = UiGpu.presented() == null ? -1 : UiGpu.presented().frameId();
            driver.run(() -> {
                nodesWindow.moveTo(nodesWindow.left() + NODES_MOVE_X, nodesWindow.top() + NODES_MOVE_Y);
                nodesScroller.setScroll(nodesScroller.scrollLeft(), nodesScroller.scrollTop() + NODES_SCROLL);
            });
            ctx.getArtifactService().requestCapture("nodes-recorded");
            System.out.println("[nodes-probe] frame " + before + " was presented last; recording the same changes: window at "
                    + nodesWindow.left() + "," + nodesWindow.top() + ", scrolled " + nodesScroller.scrollTop());
        }
        return false;
    }

    /** The first box whose content frame {@code frame} scrolled under a node, with room to scroll on, and not virtualised. */
    private static Box scrolledBox(Box box, long frame) {
        if (box.scrolledNode(frame) != 0 && box.node().scrollExtent(false) < 0f
                && box.maxScrollTop() - box.scrollTop() >= NODES_SCROLL) return box;
        for (Box child : box.children()) {
            Box found = scrolledBox(child, frame);
            if (found != null) return found;
        }
        return null;
    }

    private static boolean isInside(Box box, Box ancestor) {
        for (Box at = box; at != null; at = at.host()) if (at == ancestor) return true;
        return false;
    }

    // ── -Dcrystalgui.harness.desktop.minimise=true: a window's snapshot, in flight ──

    /**
     * Minimises the active window -- the editor -- and restores it, photographing each flight halfway: what flies is the window's
     * snapshot, so {@code minimise-mid} and {@code restore-mid} show whether it was drawn, and {@code restored}
     * against {@code minimise-before} whether the window came back as it left. ~4s, with {@code --seconds=5}.
     */
    private static final boolean MINIMISE_SHOT = Boolean.getBoolean("crystalgui.harness.desktop.minimise");

    private WindowFrame minimiseTarget;

    private void driveMinimiseShot(HarnessContext ctx, long frameNumber) {
        switch ((int) frameNumber) {
            case 99 -> {
                minimiseTarget = desktop.activeWindow();
                ctx.getArtifactService().requestCapture("minimise-before");
            }
            case 100 -> {
                if (minimiseTarget != null) minimiseTarget.minimize();
            }
            case 106 -> ctx.getArtifactService().requestCapture("minimise-mid");
            case 150 -> ctx.getArtifactService().requestCapture("minimised");
            case 160 -> {
                if (minimiseTarget != null) minimiseTarget.show(true);
            }
            case 166 -> ctx.getArtifactService().requestCapture("restore-mid");
            case 230 -> ctx.getArtifactService().requestCapture("restored");
            default -> { }
        }
    }

    /**
     * -Dcrystalgui.harness.desktop.traceCost=true: what the trace engine costs a frame with every channel
     * off, measured against every channel on. Prints {@code [trace-cost]} lines and exits. @see TraceCostProbe
     */
    private static final boolean TRACE_COST = Boolean.getBoolean("crystalgui.harness.desktop.traceCost");

    private final TraceCostProbe traceCost = TRACE_COST ? new TraceCostProbe(240) : null;
    private boolean traceCostDone;

    /**
     * -Dcrystalgui.harness.desktop.hoverSweep=true: what moving the pointer across the editor costs a frame, against
     * the pointer parked. Prints {@code [hover-sweep]} lines and exits. @see HoverSweepProbe
     */
    private static final boolean HOVER_SWEEP = Boolean.getBoolean("crystalgui.harness.desktop.hoverSweep");

    @Nullable
    private HoverSweepProbe hoverSweep;
    private boolean hoverSweepDone;

    private HoverSweepProbe hoverSweepProbe() {
        return new HoverSweepProbe(GRAPH_COST_WARMUP, new HoverSweepProbe.Target() {
            @Override
            public void moveTo(float x, float y) {
                document.input().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                        (int) x, (int) y, 0, 0, CgMouseCodes.NONE, false, 0f, -1L));
            }

            @Override
            public float[] region() {
                UIElement window = editor == null ? null : editor.mainWindow();
                float[] corner = at(window, 0f, 0f);
                float[] far = at(window, 1f, 1f);
                return new float[]{corner[0], corner[1], far[0] - corner[0], far[1] - corner[1]};
            }
        });
    }

    /**
     * -Dcrystalgui.harness.desktop.graphCost=true: what the scratch shader graph costs a frame while open and
     * untouched, against the same desktop without it. Prints {@code [graph-cost]} lines and exits.
     * @see ShaderGraphCostProbe
     */
    private static final boolean GRAPH_COST = Boolean.getBoolean("crystalgui.harness.desktop.graphCost");

    /** The desktop runs the language stack and generates glyphs for seconds after it opens. */
    private static final int GRAPH_COST_WARMUP = Integer.getInteger("crystalgui.harness.desktop.graphCost.warmup", 900);

    private static final CgPath GRAPH_COST_FILE = CgPath.of(HarnessWorkspace.PROJECT_ID, "src/other/new.shadergraph");

    @Nullable
    private ShaderGraphCostProbe graphCost;
    private boolean graphCostDone;

    private ShaderGraphCostProbe graphCostProbe() {
        return new ShaderGraphCostProbe(GRAPH_COST_WARMUP, new ShaderGraphCostProbe.Target() {
            @Override
            public boolean isOpen() {
                return editor != null && editor.workbench().openPaths().contains(GRAPH_COST_FILE);
            }

            @Override
            public void open() {
                if (editor != null) editor.workbench().openFile(GRAPH_COST_FILE);
            }

            /**
             * Closes the graph, discarding the probe's edits first: a dirty document asks before it closes, and the
             * question would sit on screen through the block meant to measure the desktop without it.
             */
            @Override
            public void close() {
                if (editor == null) return;
                Document graph = graphDocument();
                if (graph != null && graph.isDirty()) {
                    editor.workbench().documents().revert(graph);
                    closeWhenClean = true;
                    return;
                }
                editor.workbench().dock().closePanel(editor.workbench().refFor(GRAPH_COST_FILE));
            }

            @Override
            public int hooks() {
                return document.animation().hookCount();
            }

            @Override
            public int afterLayoutHooks() {
                return document.animation().afterLayoutCount();
            }

            /** An unwired input's constant, the way a port editor writes it, and the recompile its binder asks for. */
            @Override
            public void edit(int n) {
                Document open = graphDocument();
                if (open == null || !(open.model() instanceof ShaderGraphDocument graph)) return;
                NodeData node = graph.graph().node(GRAPH_COST_NODE);
                if (node == null) return;
                graph.graph().replaceNode(node.withProperty("B", String.valueOf(0.3f + 0.1f * n)));
                graph.shader().requestRecompile();
            }

            /** The probe's node, moved as one frame of a drag moves it: its widget and the document together. */
            @Override
            public void moveNode(float dx, float dy) {
                for (UIElement each : document.composedSubtree()) {
                    if (!(each instanceof GraphView graph)) continue;
                    GraphNode node = graph.widgetFor(GRAPH_COST_NODE);
                    NodeData data = graph.getDocument().node(GRAPH_COST_NODE);
                    if (node != null && data != null) graph.moveNode(node, data.x() + dx, data.y() + dy);
                    return;
                }
            }

            /** The open graph's canvas, panned through the same setView a drag goes through. */
            @Override
            public void pan(float dx, float dy) {
                for (UIElement each : document.composedSubtree()) {
                    if (!(each instanceof GraphView graph)) continue;
                    for (UIElement inner : graph.composedSubtree()) {
                        if (inner instanceof CanvasView canvas) {
                            canvas.panBy(dx, dy);
                            return;
                        }
                    }
                }
            }
        });
    }

    /** The open shader graph's document, or null. */
    @Nullable
    private Document graphDocument() {
        if (editor == null) return null;
        for (Document open : editor.workbench().documents().all()) {
            if (open.model() instanceof ShaderGraphDocument) return open;
        }
        return null;
    }

    /** Set by the probe's close while a revert is in flight; the panel closes once the document is clean. */
    private boolean closeWhenClean;

    /** A Multiply in the scratch graph whose B is unwired, with four previews and the output downstream of it. */
    private static final String GRAPH_COST_NODE = "htsi617tga";

    // ── -Dcrystalgui.harness.desktop.profiler=true: open the profiler, drive it, photograph it ──

    /**
     * A scripted run for iterating on the Frame Profiler without a hand on the mouse.
     *
     * <p>Opens it maximised, presses Record, lets the ring fill, then drives every gesture the window
     * has through the REAL input path -- a click on the strip, on a zone, a wheel zoom, a pan, a range
     * drag, the tabs, a counter row, Live -- photographing after each and printing what the model says
     * happened. The printout is half the point: a photograph of a highlighted bar cannot say whether the
     * click selected the frame under it or one twice as far along.</p>
     */
    private static final boolean PROFILER_SHOT = Boolean.getBoolean("crystalgui.harness.desktop.profiler");

    /** When the script starts driving -- enough frames for the 4Hz refresh to have run several times. */
    private static final int PROFILER_SHOT_AT = Integer.getInteger("crystalgui.harness.desktop.profiler.at", 180);

    private FrameProfilerPanel profilerPanel;
    private boolean profilerShotDone;
    private HarnessContext shotContext;

    private void driveProfilerShot(HarnessContext ctx, long frameNumber) {
        shotContext = ctx;
        if (frameNumber == 50) {
            // THE SCENE'S OWN READOUT OFF: it sits over the window's caption buttons, and every
            // photograph of the profiler would be a photograph of the readout's corner too.
            FrameStatsOverlay readout = FrameStatsOverlay.of(document);
            if (readout != null && readout.isShowing()) FrameStatsOverlay.toggleOn(document);
            // RECORD FROM LAUNCH, checked before the window exists: what is already in the ring was
            // recorded by the autostart alone.
            log("before opening: recording " + CgTrace.isRecording() + ", frames already recorded "
                    + CgTrace.frameCount() + ", keeps first " + CgTrace.firstFrames() + " + newest " + CgTrace.newestFrames()
                    + ", record-at-launch " + ProfilerSettings.get(ProfilerSettings.RECORD_AT_LAUNCH));
            WindowFrame window = FrameProfiler.openOn(desktop);
            profilerWindow = window;
            window.maximize();
            // THE COMPOSED TREE, not content(): the window hosts what it is given in a slot of its
            // own, so the panel is a descendant rather than the content element itself.
            for (UIElement each : window.composedSubtree()) {
                if (each instanceof FrameProfilerPanel panel) {
                    profilerPanel = panel;
                    break;
                }
            }
            if (profilerPanel != null && !profilerPanel.model().isCapturing()) {
                profilerPanel.model().toggleRecording();
            }
            log("opened; panel found: " + (profilerPanel != null) + "; enabled: " + CgTrace.enabledNames());
            return;
        }
        if (profilerPanel == null) {
            if (frameNumber > 60) profilerShotDone = true;
            return;
        }
        FrameProfilerPanel panel = profilerPanel;
        ProfilerModel model = panel.model();
        long step = frameNumber - PROFILER_SHOT_AT;
        if (step < 0) return;

        switch ((int) step) {
            case 0 -> {
                logGc(model);
                log("live: frames " + model.frameCount() + ", following " + model.isFollowing()
                        + ", selected " + model.selectedIndex() + ", zones " + model.zonesOfSelection().size()
                        + ", tracks " + panel.chart().tracks().size() + ", counters " + model.counterSeries().size());
                shot("01-live");
                FrameStripTrack strip = panel.strip();
                float[] thumb = panel.scrollbar().thumb();
                log("opening view: " + (int) strip.visible() + " of " + strip.frames() + " frames across, from "
                        + (int) strip.viewFrom() + ", thumb " + (int) thumb[1] + " of "
                        + (int) panel.scrollbar().box().width() + " px");
            }

            // A CLICK ON THE STRIP, 70% along: it must select the frame under the pointer and pause.
            case 4 -> {
                Box box = panel.strip().box();
                float[] p = at(panel.strip(), 0.70f, 0.6f);
                log("strip box: worldX " + box.worldX() + " worldY " + box.worldY() + " w " + box.width()
                        + " h " + box.height() + " uiScale " + document.boxes().uiScale()
                        + " -> sent (" + p[0] + ", " + p[1] + ") -> toLocal " + panel.strip().toLocal(p[0], p[1]));
                hover(panel.strip(), 0.70f, 0.6f);
            }
            case 6 -> press(panel.strip(), 0.70f, 0.6f, true);
            case 7 -> press(panel.strip(), 0.70f, 0.6f, false);
            case 10 -> {
                int expected = (int) (panel.strip().viewFrom() + 0.70f * panel.strip().visible());
                log("strip click: selected " + model.selectedIndex() + " (pointer over ~" + expected
                        + "), following " + model.isFollowing());
                shot("02-strip-click");
            }
            // A CLICK ON A ZONE in the frame thread's top row.
            case 14 -> hover(firstTrack(panel), 0.30f, 9f / trackHeight(panel));
            case 16 -> press(firstTrack(panel), 0.30f, 9f / trackHeight(panel), true);
            case 17 -> press(firstTrack(panel), 0.30f, 9f / trackHeight(panel), false);
            case 20 -> {
                log("zone click: selected zone " + model.selectedZone() + ", following " + model.isFollowing());
                shot("03-zone-click");
            }
            // A WHEEL ZOOM, three notches UP about the middle -- a NEGATIVE scroll (HostPointer.scroll),
            // which must zoom IN: the span must shrink.
            case 24 -> {
                hover(firstTrack(panel), 0.5f, 0.3f);
                spanBefore = panel.chart().axis().spanNanos();
            }
            case 25, 26, 27 -> wheel(firstTrack(panel), 0.5f, 0.3f, -1f);
            case 30 -> {
                log("wheel up: span " + spanBefore / 1000 + "us -> " + panel.chart().axis().spanNanos() / 1000
                        + "us (must shrink)");
                log("zoom: axis span " + panel.chart().axis().spanNanos() / 1000 + "us of "
                        + (panel.chart().axis().extentTo() - panel.chart().axis().extentFrom()) / 1000 + "us");
                shot("04-zoom");
            }
            // A PAN, dragged right-to-left and released over a zone: must pan, and must NOT select.
            case 34 -> press(firstTrack(panel), 0.60f, 0.3f, true);
            case 35 -> hover(firstTrack(panel), 0.50f, 0.3f);
            case 36 -> hover(firstTrack(panel), 0.40f, 2.5f);
            case 37 -> press(firstTrack(panel), 0.40f, 2.5f, false);
            case 40 -> {
                log("pan: axis from +" + (panel.chart().axis().from() - panel.chart().axis().extentFrom()) / 1000
                        + "us, selected zone still " + model.selectedZone());
                shot("05-pan");
            }
            // A RANGE DRAG across the strip, released below it: capture must carry the release home.
            case 44 -> press(panel.strip(), 0.20f, 0.5f, true);
            case 45 -> hover(panel.strip(), 0.26f, 0.5f);
            case 46 -> hover(panel.strip(), 0.32f, 1.8f);
            case 47 -> press(panel.strip(), 0.32f, 1.8f, false);
            case 50 -> {
                log("range: " + model.hasRange() + " " + model.rangeFrom() + ".." + model.rangeTo()
                        + ", zones " + model.zonesOfSelection().size());
                shot("06-range");
            }
            case 54 -> click(panel.countersTab());
            // A WHEEL OVER A COUNTER ROW scrolls the tab and leaves the frame axis alone: the rows fill
            // the tab, so a row that zoomed on the wheel left nothing to scroll with.
            case 56 -> {
                countersSpan = panel.strip().visible();
                if (!panel.counters().rows().isEmpty()) wheel(panel.counters().rows().get(0), 0.5f, 0.6f, 3f);
            }
            case 58 -> {
                Box tabBox = panel.counters().scroller().box();
                log("counters wheel: scrollTop " + panel.counters().scroller().scrollTop() + " (must be > 0), strip span "
                        + (int) countersSpan + " -> " + (int) panel.strip().visible() + " (must not move); tab "
                        + (tabBox == null ? "no box" : "h " + tabBox.height() + " client " + tabBox.clientHeight()
                        + " content " + tabBox.contentHeight()) + ", rows " + panel.counters().rows().size());
                shot("07-counters");
            }
            case 60 -> {
                if (!panel.counters().rows().isEmpty()) hover(panel.counters().rows().get(1), 0.5f, 0.5f);
            }
            case 61 -> {
                if (!panel.counters().rows().isEmpty()) press(panel.counters().rows().get(1), 0.5f, 0.5f, true);
            }
            case 62 -> {
                if (!panel.counters().rows().isEmpty()) press(panel.counters().rows().get(1), 0.5f, 0.5f, false);
            }
            case 65 -> {
                log("counter click: selected " + model.selectedIndex() + " (pointer over ~"
                        + (int) (panel.strip().viewFrom() + 0.5f * panel.strip().visible()) + "), range " + model.hasRange());
                shot("08-counter-click");
            }
            // A PRESS ON A COUNTER'S LABEL is not a pick: the selection must not move.
            case 66 -> {
                labelBefore = model.selectedIndex();
                if (!panel.counters().rows().isEmpty()) press(panel.counters().rows().get(1), 0.2f, 0.15f, true);
            }
            case 67 -> {
                if (!panel.counters().rows().isEmpty()) press(panel.counters().rows().get(1), 0.2f, 0.15f, false);
            }
            case 68 -> {
                log("counter label press: selected " + labelBefore + " -> " + model.selectedIndex() + " (must not move)");
                click(panel.callTreeTab());
            }
            case 71 -> {
                TreeView<CallTreeTab.CallNode> tree = panel.callTree().calleeTree();
                log("call tree: " + tree.visibleRows().size() + " rows, open " + tree.expandedItems());
                shot("09-calltree");
            }
            // A CLICK ON A CALLEE ROW selects that zone everywhere, and the callers side answers for it.
            case 72 -> click(calleeRow(panel, 1));
            case 74 -> {
                TreeView<CallTreeTab.CallNode> tree = panel.callTree().calleeTree();
                TreeRow<CallTreeTab.CallNode> row = tree.rowAt(1);
                TreeView<CallTreeTab.CallNode> up = panel.callTree().callerTree();
                log("callee click: row 1 is " + (row == null ? null : row.item().name()) + ", selected zone "
                        + model.selectedZone() + ", callers rows " + up.visibleRows().size()
                        + (up.visibleRows().isEmpty() ? "" : " rooted at " + up.visibleRows().get(0).item().name()));
                shot("09b-callee-selected");
            }
            // THE TWISTY of the first row folds it, and only it: the selection must not move.
            case 75 -> {
                UIElement row = calleeRow(panel, 0);
                twistyWasOpen = panel.callTree().calleeTree().rowAt(0).expanded();
                twistyRows = panel.callTree().calleeTree().visibleRows().size();
                if (row != null) click(row.children().get(0));
            }
            case 77 -> {
                TreeView<CallTreeTab.CallNode> tree = panel.callTree().calleeTree();
                log("twisty: row 0 open " + twistyWasOpen + " -> " + tree.rowAt(0).expanded() + ", rows "
                        + twistyRows + " -> " + tree.visibleRows().size() + ", selected zone still "
                        + model.selectedZone());
            }
            case 78 -> click(panel.zonesTab());
            case 79 -> shot("10-zones");
            case 80 -> click(panel.liveButton());
            case 88 -> {
                log("live button: following " + model.isFollowing() + ", selected " + model.selectedIndex()
                        + " of " + model.frameCount());
                shot("11-live-again");
            }
            // A RANGE DRAGGED WHILE LIVE: the press must pause the window before the refresh can move
            // the frames under the pointer.
            case 92 -> press(panel.strip(), 0.55f, 0.5f, true);
            case 93 -> hover(panel.strip(), 0.62f, 0.5f);
            case 94 -> hover(panel.strip(), 0.70f, 1.6f);
            case 95 -> press(panel.strip(), 0.70f, 1.6f, false);
            case 98 -> {
                int n = model.frameCount();
                log("live range: " + model.hasRange() + " " + model.rangeFrom() + ".." + model.rangeTo()
                        + " (pointer over ~" + (int) (0.55f * n) + ".." + (int) (0.70f * n) + "), following "
                        + model.isFollowing());
                shot("12-live-range");
            }
            case 99 -> click(panel.callTreeTab());
            case 101 -> shot("12b-range-calltree");
            // KEYBOARD: a click on the strip, then three Right arrows -- one frame per press.
            case 102 -> click(panel.strip());
            case 105 -> {
                keyStart = model.selectedIndex();
                key(CgKeyCodes.KEY_RIGHT);
            }
            case 106 -> key(CgKeyCodes.KEY_RIGHT);
            case 107 -> key(CgKeyCodes.KEY_RIGHT);
            case 110 -> {
                log("keyboard: " + keyStart + " -> " + model.selectedIndex() + " after 3 x Right (focus on "
                        + (document.focus().focused() == null ? "nothing" : document.focus().focused().name()) + ")");
                shot("13-keyboard");
            }
            case 112 -> click(worstButton(panel));
            case 115 -> {
                long worst = 0L;
                int worstAt = -1;
                for (int i = 0; i < model.frameCount(); i++) {
                    long wall = model.frames().get(i).wallNanos();
                    if (wall > worst) {
                        worst = wall;
                        worstAt = i;
                    }
                }
                firstWorst = model.selectedIndex();
                log("worst frame: selected " + model.selectedIndex() + " at "
                        + model.frames().get(model.selectedIndex()).wallNanos() / 1_000_000 + " ms (slowest overall is "
                        + worstAt + ", " + worst / 1_000_000 + " ms)");
                shot("14-worst");
            }
            case 116 -> click(worstButton(panel));
            case 117 -> {
                int second = model.selectedIndex();
                log("worst again: " + firstWorst + " -> " + second + " at "
                        + model.frames().get(second).wallNanos() / 1_000_000 + " ms (must be a different, "
                        + "no-slower frame)");
            }
            case 118 -> click(panel.recordButton());
            case 121 -> {
                log("record off: capturing " + model.isCapturing() + ", frozen " + model.isFrozen()
                        + ", enabled " + CgTrace.enabledNames());
                shot("15-record-off");
            }
            case 123 -> click(panel.recordButton());
            case 126 -> log("record on: capturing " + model.isCapturing() + ", enabled " + CgTrace.enabledNames());
            case 128 -> click(channelsToggle(panel));
            // PAST THE FADE: a popover opens over 120 ms, and a photograph inside that reads as a
            // translucent menu.
            case 140 -> shot("16-channels-open");
            case 142 -> click(channelBox(panel, "crystalgui.blame"));
            case 150 -> {
                log("tick blame: enabled " + CgTrace.enabledNames() + ", menu still open "
                        + channelsOpen(panel));
                shot("17-channels-ticked");
            }
            case 152 -> click(channelBox(panel, "crystalgui.blame"));
            case 156 -> log("untick blame: enabled " + CgTrace.enabledNames());
            // THE DIVIDER, dragged down 60 px: the split must follow the pointer.
            case 162 -> {
                UIElement divider = splitDivider(panel);
                splitBefore = splitOf(panel);
                if (divider != null) press(divider, 0.5f, 0.5f, true);
            }
            case 163 -> {
                UIElement divider = splitDivider(panel);
                if (divider != null) {
                    float[] p = at(divider, 0.5f, 0.5f);
                    document.input().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                            (int) p[0], (int) p[1] + 60, 0, 60, CgMouseCodes.NONE, false, 0f, -1L));
                }
            }
            case 164 -> {
                UIElement divider = splitDivider(panel);
                if (divider != null) {
                    float[] p = at(divider, 0.5f, 0.5f);
                    document.input().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                            (int) p[0], (int) p[1], 0, 0, CgMouseCodes.LEFT_BUTTON, false, 0f, System.currentTimeMillis()));
                }
            }
            case 167 -> {
                log("divider drag: split " + splitBefore + "% -> " + splitOf(panel) + "%");
                shot("18-split-dragged");
            }
            case 168 -> {
                int at = -1;
                for (int i = model.frameCount() - 1; i >= 0; i--) {
                    if (model.frames().get(i).hadGc()) {
                        at = i;
                        break;
                    }
                }
                if (at >= 0) {
                    model.setFollowing(false);
                    model.selectFrame(at);
                }
                CgFrameRecord frame = model.selectedFrame();
                log("gc frame: " + at + (frame == null ? "" : " -> " + frame.gcSummary() + " of "
                        + frame.wallNanos() / 1_000_000 + " ms"));
            }
            case 169 -> {
                logGc(model);
                shot("19-gc-frame");
            }
            // THE GEAR in the caption opens the settings page in place of the bands.
            case 172 -> click(gear());
            case 176 -> {
                log("gear: settings open " + panel.isSettingsOpen() + ", gear lit "
                        + (gear() != null && gear().hasClass(WindowFrame.ACTION_ON_CLASS))
                        + ", summary: " + panel.settingsPage().summaryText());
                shot("20-settings");
            }
            // WHAT THE PAGE'S ROWS WRITE: the first 300 frames and no newest -- keep the start, then stop.
            case 178 -> {
                ProfilerSettings.set(ProfilerSettings.FIRST_FRAMES, 300);
                ProfilerSettings.set(ProfilerSettings.FRAMES, 0);
            }
            case 181 -> {
                log("settings changed: keeps first " + CgTrace.firstFrames() + " + newest " + CgTrace.newestFrames()
                        + ", summary: " + panel.settingsPage().summaryText());
                shot("21-settings-changed");
            }
            case 183 -> click(panel.settingsPage().doneButton());
            case 186 -> {
                log("done: settings open " + panel.isSettingsOpen() + ", capturing " + model.isCapturing());
                keepFirstWaiting = true;
            }
            default -> driveAfterSettings(panel, model, step);
        }
    }

    private static Button worstButton(FrameProfilerPanel panel) {
        for (UIElement each : panel.composedSubtree()) {
            if (each instanceof Button button && "Worst frame".equals(button.getText())) return button;
        }
        return null;
    }

    private static UIElement channelsToggle(FrameProfilerPanel panel) {
        for (UIElement each : panel.composedSubtree()) {
            if (each instanceof MaskControl mask) return mask.toggle();
        }
        return null;
    }

    private int firstWorst;
    private WindowFrame profilerWindow;
    private boolean keepFirstWaiting;
    private long fullAt = -1L;
    private double viewBefore;

    private Button gear() {
        if (profilerWindow == null) return null;
        for (UIElement each : profilerWindow.composedSubtree()) {
            if (each instanceof Button button && button.hasClass(WindowFrame.SETTINGS_ACTION_CLASS)) return button;
        }
        return null;
    }

    /** From the ring filling onwards: keep-first, the strip zoom, Home, and putting everything back. */
    private void driveAfterSettings(FrameProfilerPanel panel, ProfilerModel model, long step) {
        if (keepFirstWaiting) {
            if (CgTrace.isFull()) {
                keepFirstWaiting = false;
                fullAt = step;
            } else if (step > 186 + 900) {
                log("keep-first: the ring never filled");
                keepFirstWaiting = false;
                fullAt = step;
            }
            return;
        }
        if (fullAt < 0L) return;
        long at = step - fullAt;
        FrameStripTrack strip = panel.strip();
        if (at == 2) {
            model.refresh();
        } else if (at == 4) {
            List<CgFrameRecord> frames = model.frames();
            log("keep-first: full " + CgTrace.isFull() + ", recording " + CgTrace.isRecording()
                    + ", frames " + frames.size() + " from #" + (frames.isEmpty() ? -1 : frames.get(0).index())
                    + ", stop reason: " + model.stopReason() + ", record button '" + panel.recordButton().getText() + "'");
            shot("22-kept-first");
        } else if (at == 6) {
            hover(strip, 0.1f, 0.5f);
            viewBefore = strip.visible();
        } else if (at >= 7 && at <= 12) {
            wheel(strip, 0.1f, 0.5f, -1f);
        } else if (at == 14) {
            log("strip wheel up x6: " + viewBefore + " -> " + strip.visible() + " frames across, from "
                    + strip.viewFrom() + ", zoomed " + strip.isZoomed() + ", counters follow "
                    + (panel.counters().rows().isEmpty() ? "n/a" : panel.counters().rows().get(0).visible()));
            shot("23-strip-zoomed");
        } else if (at == 15) {
            // SCRUB TO THE ORIGIN: select the last frame, then Home -- frame 0 must be selected AND in view.
            model.selectFrame(model.frameCount() - 1);
        } else if (at == 17) {
            click(strip);
        } else if (at == 19) {
            model.selectFrame(model.frameCount() - 1);
        } else if (at == 21) {
            key(CgKeyCodes.KEY_HOME);
        } else if (at == 24) {
            CgFrameRecord first = model.selectedFrame();
            log("home: selected " + model.selectedIndex() + " (#" + (first == null ? -1 : first.index())
                    + "), strip view from " + strip.viewFrom() + " across " + strip.visible());
            shot("24-origin");
        } else if (at == 25) {
            key(CgKeyCodes.KEY_A);
        } else if (at == 27) {
            log("A: zoomed " + strip.isZoomed());
        // THE SCROLLBAR: the thumb's right end dragged to the middle zooms to half the ring.
        } else if (at == 28) {
            hover(panel.scrollbar(), 0.997f, 0.5f);
        } else if (at == 29) {
            press(panel.scrollbar(), 0.997f, 0.5f, true);
        } else if (at == 30) {
            hover(panel.scrollbar(), 0.5f, 0.5f);
        } else if (at == 31) {
            press(panel.scrollbar(), 0.5f, 0.5f, false);
        } else if (at == 33) {
            log("scrollbar end dragged to half: " + strip.visible() + " frames across of " + strip.frames()
                    + ", from " + strip.viewFrom());
            viewBefore = strip.viewFrom();
        // ...and its body dragged 30% along scrubs by 30% of the ring.
        } else if (at == 34) {
            float[] thumb = panel.scrollbar().thumb();
            float mid = (thumb[0] + thumb[1] * 0.5f) / panel.scrollbar().box().width();
            barMid = mid;
            hover(panel.scrollbar(), mid, 0.5f);
            press(panel.scrollbar(), mid, 0.5f, true);
        } else if (at == 35) {
            hover(panel.scrollbar(), barMid + 0.3f, 0.5f);
        } else if (at == 36) {
            press(panel.scrollbar(), barMid + 0.3f, 0.5f, false);
        } else if (at == 38) {
            log("scrollbar body dragged 30%: from " + viewBefore + " -> " + strip.viewFrom()
                    + " (must move ~" + (int) (0.3f * strip.frames()) + " frames)");
            shot("25-scrollbar");
            viewBefore = strip.viewFrom();
        // A MIDDLE-BUTTON DRAG on the strip pans; it must not make a range.
        } else if (at == 39) {
            hover(strip, 0.6f, 0.5f);
            middle(strip, 0.6f, 0.5f, true);
        } else if (at == 40) {
            hover(strip, 0.4f, 0.5f);
        } else if (at == 41) {
            middle(strip, 0.4f, 0.5f, false);
        } else if (at == 43) {
            log("middle drag: from " + viewBefore + " -> " + strip.viewFrom() + " (must be later), range "
                    + model.hasRange());
        } else if (at == 48) {
            click(gear());
        } else if (at == 51) {
            for (UIElement each : panel.settingsPage().composedSubtree()) {
                if (each instanceof Button button && "Restore defaults".equals(button.getText())) click(button);
            }
        } else if (at == 54) {
            log("restore defaults: keeps first " + CgTrace.firstFrames() + " + newest " + CgTrace.newestFrames()
                    + ", summary: " + panel.settingsPage().summaryText());
            click(panel.settingsPage().doneButton());
        } else if (at == 56) {
            if ("arm-launch".equals(System.getProperty("crystalgui.harness.desktop.profiler.phase"))) {
                ProfilerSettings.set(ProfilerSettings.RECORD_AT_LAUNCH, true);
                log("armed record-at-launch for the next run");
            } else if (ProfilerSettings.get(ProfilerSettings.RECORD_AT_LAUNCH)) {
                ProfilerSettings.set(ProfilerSettings.RECORD_AT_LAUNCH, false);
                log("record-at-launch switched back off");
            }
        // THE START AND THE NEWEST: 60 kept from the start, 120 newest, then run past both. Frame #0 must
        // still be there, with a gap marked before the newest.
        } else if (at == 58) {
            ProfilerSettings.set(ProfilerSettings.FIRST_FRAMES, 40);
            ProfilerSettings.set(ProfilerSettings.FRAMES, 150);
            if (!model.isCapturing()) model.toggleRecording();
            model.setFollowing(true);
        } else if (at == 330) {
            model.refresh();
        } else if (at == 332) {
            List<CgFrameRecord> frames = model.frames();
            int gap = -1;
            for (int i = 1; i < frames.size(); i++) {
                if (frames.get(i).index() != frames.get(i - 1).index() + 1) {
                    gap = i;
                    break;
                }
            }
            log("start and newest: recorded " + CgTrace.frameCount() + ", kept " + frames.size() + " from #"
                    + frames.get(0).index() + ", gap at " + gap + (gap < 0 ? ""
                    : " (#" + frames.get(gap - 1).index() + " then #" + frames.get(gap).index() + ")")
                    + ", still recording " + model.isCapturing());
            float[] thumb = panel.scrollbar().thumb();
            log("opening view after the clear: " + (int) strip.visible() + " of " + strip.frames()
                    + " across, from " + (int) strip.viewFrom() + ", thumb " + (int) thumb[1] + " of "
                    + (int) panel.scrollbar().box().width() + " px");
            shot("26-start-and-newest");
        // THE THUMB, dragged hard left from the opening view: it must reach frame #0.
        } else if (at == 333) {
            float[] thumb = panel.scrollbar().thumb();
            barMid = (thumb[0] + thumb[1] * 0.5f) / panel.scrollbar().box().width();
            hover(panel.scrollbar(), barMid, 0.5f);
            press(panel.scrollbar(), barMid, 0.5f, true);
        } else if (at == 334) {
            hover(panel.scrollbar(), 0f, 0.5f);
        } else if (at == 335) {
            press(panel.scrollbar(), 0f, 0.5f, false);
        } else if (at == 336) {
            log("thumb dragged hard left: strip from " + (int) strip.viewFrom() + " across "
                    + (int) strip.visible() + ", first shown #" + model.frames().get((int) strip.viewFrom()).index()
                    + ", following " + model.isFollowing());
            shot("26b-thumb-left");
            click(strip);
        } else if (at == 337) {
            key(CgKeyCodes.KEY_HOME);
        } else if (at == 338) {
            CgFrameRecord first = model.selectedFrame();
            log("home after the ring rolled: #" + (first == null ? -1 : first.index()) + ", zones "
                    + model.zonesOfSelection().size());
            shot("27-first-frame");
        // ZOOMED ACROSS THE GAP, on a fractional view, with a frame selected just after it: the marks must
        // sit exactly on their bars, and the break must read.
        } else if (at == 340) {
            gapFraction = 40f / Math.max(1, model.frameCount());
            hover(strip, gapFraction, 0.5f);
        } else if (at >= 341 && at <= 346) {
            wheel(strip, gapFraction, 0.5f, -1f);
        } else if (at == 347) {
            panel.strip().panBy(0.37d);
            model.selectFrame(41);
        } else if (at == 350) {
            log("zoomed on the gap: from " + strip.viewFrom() + " across " + strip.visible());
            shot("28-gap-zoomed");
        // HINTS: the tab, its rows, and the first row's link followed.
        } else if (at == 351) {
            // THE WHOLE RING, so every rule has its chance; one frame may simply be innocent.
            model.setFollowing(false);
            model.selectRange(0, model.frameCount() - 1);
        } else if (at == 353) {
            click(panel.hintsTab());
        } else if (at == 356) {
            log("hints: tab '" + panel.hintsTab().getText() + "', " + panel.hints().rows().size() + " rows: "
                    + model.hintsOfSelection().stream().map(row -> row.hint().code()).toList());
            shot("29-hints");
            hintLink = null;
            for (UIElement row : panel.hints().rows()) {
                for (UIElement each : row.composedSubtree()) {
                    if (each instanceof Button button && button.hasClass(HintsTab.LINK_CLASS)) {
                        hintLink = button;
                        break;
                    }
                }
                if (hintLink != null) break;
            }
            if (hintLink != null) click(hintLink);
        } else if (at == 359) {
            log("hint link '" + (hintLink == null ? "none" : hintLink.getText()) + "': zone " + model.selectedZone()
                    + ", tab " + (panel.tabs().getSelectedTab() == null ? "?" : panel.tabs().getSelectedTab().getText()));
        // COMPARE: the first twenty frames pinned as A, the last twenty as B, through the tab's buttons.
        } else if (at == 360) {
            click(panel.compareTab());
            model.setFollowing(false);
            model.selectRange(0, 19);
        } else if (at == 362) {
            click(panel.compare().pinAButton());
            model.selectRange(model.frameCount() - 20, model.frameCount() - 1);
        } else if (at == 364) {
            click(panel.compare().pinBButton());
        } else if (at == 367) {
            log("compare: A " + model.sideA() + ", B " + model.sideB() + ", " + model.compare().size()
                    + " zones; " + panel.compare().summaryText());
            shot("30-compare");
        // EXPORT, and the file read straight back as B.
        } else if (at == 368) {
            click(panel.exportButton());
            panel.compare().loadDropdown().select(0);
        } else if (at == 369) {
            log("export: " + panel.exportNote().getText());
            log("loaded as B: A " + model.sideA() + ", B " + model.sideB() + " (" + model.framesOf(model.sideB()).size()
                    + " frames, " + String.format("%.2f", model.meanFrameMillis(model.sideB())) + " ms), "
                    + model.compare().size() + " zones");
        // THE FOOTER, and the viewer's own work shown on request.
        } else if (at == 370) {
            log("footer: " + panel.footerText().getText());
            click(panel.viewerToggle());
        } else if (at == 373) {
            boolean own = model.zonesOfSelection().stream()
                    .anyMatch(zone -> ProfilerModel.VIEWER.name().equals(zone.channel()));
            log("viewer shown: " + model.isShowingViewer() + ", its zones in the selection " + own
                    + ", toggle now '" + panel.viewerToggle().getText() + "'");
            click(panel.viewerToggle());
        // CHAINS
        } else if (at == 375) {
            click(panel.chainsTab());
        } else if (at == 378) {
            List<ChainsTab.Step> chains = panel.chains().chains();
            log("chains: " + chains.size() + (chains.isEmpty() ? "" : ", newest " + chains.get(0).name()
                    + " " + chains.get(0).durationNanos() / 1000 + "us with " + chains.get(0).children().size()
                    + " steps"));
            shot("31-chains");
        // THE READOUT'S SPARKLINE opens the frame under the press.
        } else if (at == 380) {
            FrameStatsOverlay.toggleOn(document);
        } else if (at == 392) {
            FrameStatsOverlay readout = FrameStatsOverlay.of(document);
            sparkRow = null;
            if (readout != null) {
                for (UIText row : readout.rows()) {
                    if (row.hasClass(FrameStatsOverlay.SPARK_CLASS)) sparkRow = row;
                }
            }
            if (sparkRow != null) {
                float[] p = at(sparkRow, 0.5f, 0.5f);
                int columns = sparkRow.getText().length();
                int column = Math.max(0, Math.min(columns - 1, sparkRow.offsetAtScreen(p[0], p[1])));
                sparkExpected = readout.barFrame(column);
                click(sparkRow);
            }
        } else if (at == 395) {
            CgFrameRecord shown = model.selectedFrame();
            log("readout sparkline click: selected #" + (shown == null ? -1 : shown.index()) + ", the bar was #"
                    + sparkExpected + ", following " + model.isFollowing() + ", spark row " + (sparkRow != null));
            shot("32-from-readout");
            FrameStatsOverlay.toggleOn(document);
        // F9 IS profiler.open: pressed with the profiler in front it closes it; again, it comes back.
        } else if (at == 397) {
            desktop.raise(profilerWindow);
        } else if (at == 399) {
            log("F9 with the profiler in front (active " + (desktop.activeWindow() == profilerWindow) + ")");
            key(CgKeyCodes.KEY_F9);
        } else if (at == 420) {
            // A CLOSE ANIMATES before the window hides: read it well after the press.
            log("F9: showing " + (profilerWindow.state() != WindowState.HIDDEN));
            key(CgKeyCodes.KEY_F9);
        } else if (at == 440) {
            log("F9 again: showing " + (profilerWindow.state() != WindowState.HIDDEN));
        } else if (at == 442) {
            // BACK TO THE DEFAULTS, through the store: the page is closed, so its button cannot be pressed.
            for (Setting<?> setting : ProfilerSettings.all()) {
                ProfilerSettings.store().reset(SettingsLayer.USER, setting);
            }
        } else if (at == 444) {
            log("defaults again: keeps first " + CgTrace.firstFrames() + " + newest " + CgTrace.newestFrames());
        // FRAME IMAGES (T8): tick the channel, let it photograph, hover the strip, open the Screen tab --
        // and the gate, read off the trace: what glend:image cost a frame, averaged.
        } else if (at == 446) {
            Set<String> channels = new HashSet<>(model.enabledChannels());
            channels.add(CgFrameImages.IMAGES.name());
            model.setEnabledChannels(channels);
            model.setFollowing(true);
            imagesFrom = CgTrace.currentFrameIndex();
        } else if (at == 646) {
            long frames = 0L;
            long nanos = 0L;
            long worst = 0L;
            StringBuilder captures = new StringBuilder();
            for (CgFrameRecord record : CgTrace.frames()) {
                if (record.index() <= imagesFrom) continue;
                frames++;
                long spent = 0L;
                for (CgTraceSnapshot.ZoneView zone : CgTrace.zonesIn(record)) {
                    if (zone.name().startsWith("glend:image")) {
                        spent += zone.durationNanos();
                        captures.append(String.format(" %s=%.3f", zone.name().substring(12), zone.durationNanos() / 1e6));
                    }
                }
                nanos += spent;
                worst = Math.max(worst, spent);
            }
            log("images: each frame's glend:image, ms:" + captures);
            log(String.format("images: %d held, %d frames since the channel went on; capture %.4f ms a frame "
                            + "averaged (gate < 0.2), worst frame %.3f ms", CgFrameImages.count(), frames,
                    frames == 0 ? 0d : nanos / 1e6 / frames, worst / 1e6));
            hover(panel.strip(), 0.85f, 0.5f);
        } else if (at == 650) {
            log("preview: showing " + panel.preview().isDisplayed() + ", caption \"" + panel.previewCaptionText() + "\"");
            shot("33-frame-preview");
        } else if (at == 652) {
            click(panel.screenTab());
        } else if (at == 658) {
            FrameImageView view = panel.screen().view();
            log("screen: \"" + panel.screen().captionText() + "\", image "
                    + (view.shown() == null ? "none" : view.shown().width() + "x" + view.shown().height()
                    + " of #" + view.shown().frameIndex()));
            shot("34-screen");
        // PAUSED PAST THE RING: the store drops a picture once the live ring has rolled past its frame,
        // and a paused window still shows that frame -- so its picture must come from the model.
        } else if (at == 1210) {
            model.setFollowing(false);
            CgFrameRecord held = model.selectedFrame();
            pausedOn = held == null ? -1L : held.index();
            CgFrameImages.Image image = held == null ? null : model.imageAtOrBefore(held.index());
            pausedImage = image == null ? -1L : image.frameIndex();
            log("paused on #" + pausedOn + " showing image of #" + pausedImage);
        } else if (at == 1400 || at == 1700) {
            CgFrameRecord now = model.selectedFrame();
            log("paused? following " + model.isFollowing() + ", selected #" + (now == null ? -1 : now.index())
                    + ", snapshot #" + model.snapshot().frames().get(0).index() + "..#"
                    + model.snapshot().frames().get(model.snapshot().frames().size() - 1).index());
        } else if (at == 1950) {
            CgFrameImages.Image store = CgFrameImages.atOrBefore(pausedOn);
            CgFrameImages.Image held = model.imageAtOrBefore(pausedOn);
            log("740 frames later: the store has #" + (store == null ? -1 : store.frameIndex())
                    + " for it, the window #" + (held == null ? -1 : held.frameIndex())
                    + " (must be #" + pausedImage + "), screen \"" + panel.screen().captionText() + "\"");
            hover(panel.strip(), 0.5f, 0.5f);
        } else if (at == 1954) {
            log("paused preview: showing " + panel.preview().isDisplayed() + ", caption \""
                    + panel.previewCaptionText() + "\"");
            shot("35-paused-images");
            profilerShotDone = true;
        }
    }
    private int labelBefore;
    private long imagesFrom;
    private long pausedOn;
    private long pausedImage;
    private double countersSpan;
    private boolean twistyWasOpen;
    private int twistyRows;

    private static UIElement calleeRow(FrameProfilerPanel panel, int index) {
        return panel.callTree().calleeTree().realisedRows().get(index);
    }
    private long spanBefore;

    private static void logGc(ProfilerModel model) {
        StringBuilder line = new StringBuilder("gc: ");
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            line.append(collector.getName()).append(" x").append(collector.getCollectionCount())
                    .append(' ').append(collector.getCollectionTime()).append("ms; ");
        }
        int frames = 0;
        long total = 0L;
        for (CgFrameRecord frame : model.frames()) {
            if (frame.gcMillis() > 0L) frames++;
            total += frame.gcMillis();
        }
        log(line + "ring: " + frames + " of " + model.frameCount() + " frames carry GC, " + total + "ms");
    }
    private float splitBefore;

    private static SplitView splitOf0(FrameProfilerPanel panel) {
        for (UIElement each : panel.composedSubtree()) {
            if (each instanceof SplitView split) return split;
        }
        return null;
    }

    private static float splitOf(FrameProfilerPanel panel) {
        SplitView split = splitOf0(panel);
        return split == null ? -1f : split.getPercentage();
    }

    private static UIElement splitDivider(FrameProfilerPanel panel) {
        SplitView split = splitOf0(panel);
        if (split == null) return null;
        for (UIElement each : split.composedChildren()) {
            if (each.hasClass("__divider__")) return each;
        }
        return null;
    }

    private static boolean channelsOpen(FrameProfilerPanel panel) {
        for (UIElement each : panel.composedSubtree()) {
            if (each instanceof MaskControl mask) return mask.panel().isOpen();
        }
        return false;
    }

    /** The checkbox for {@code channel} in the channel menu's popover, which lives in the top layer. */
    private UIElement channelBox(FrameProfilerPanel panel, String channel) {
        for (UIElement each : panel.composedSubtree()) {
            if (each instanceof MaskControl mask) {
                for (UIElement row : mask.panel().composedSubtree()) {
                    if (row instanceof Checkbox box && channel.equals(box.getLabel())) return box;
                }
            }
        }
        log("no checkbox for " + channel);
        return null;
    }

    private SpanTrack firstTrack(FrameProfilerPanel panel) {
        List<SpanTrack> tracks = panel.chart().tracks();
        return tracks.isEmpty() ? null : tracks.get(0);
    }

    private float trackHeight(FrameProfilerPanel panel) {
        SpanTrack track = firstTrack(panel);
        return track == null || track.box() == null ? 18f : Math.max(1f, track.box().height());
    }

    /**
     * A point on {@code element} as a fraction of its box, in SURFACE pixels -- what Input receives.
     *
     * <p>Through the box's own {@code localToWorld}, NOT {@code uiScale()}: this scene scales through
     * the root transform, so {@code uiScale()} answers 1 while every matrix carries 2. Multiplying by
     * the former sent every scripted click to half the distance it was aimed at.</p>
     */
    private float[] at(UIElement element, float fx, float fy) {
        Box box = element == null ? null : element.box();
        if (box == null) return new float[]{-1f, -1f};
        Vector2f surface = Transform2D.apply(box.localToWorld(), fx * box.width(), fy * box.height());
        return new float[]{surface.x, surface.y};
    }

    private void hover(UIElement element, float fx, float fy) {
        float[] p = at(element, fx, fy);
        document.input().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                (int) p[0], (int) p[1], 0, 0, CgMouseCodes.NONE, false, 0f, -1L));
    }

    private float barMid;
    private Button hintLink;
    private UIText sparkRow;
    private long sparkExpected;
    private float gapFraction;

    private void middle(UIElement element, float fx, float fy, boolean down) {
        float[] p = at(element, fx, fy);
        document.input().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                (int) p[0], (int) p[1], 0, 0, CgMouseCodes.MIDDLE_BUTTON, down, 0f, System.currentTimeMillis()));
    }

    private void press(UIElement element, float fx, float fy, boolean down) {
        float[] p = at(element, fx, fy);
        document.input().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                (int) p[0], (int) p[1], 0, 0, CgMouseCodes.LEFT_BUTTON, down, 0f, System.currentTimeMillis()));
    }

    private void wheel(UIElement element, float fx, float fy, float notches) {
        float[] p = at(element, fx, fy);
        document.input().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                (int) p[0], (int) p[1], 0, 0, CgMouseCodes.NONE, false, notches, -1L));
    }

    /** Hover, press and release in one call -- for buttons and tabs, where a same-frame click is fine. */
    private void click(UIElement element) {
        hover(element, 0.5f, 0.5f);
        press(element, 0.5f, 0.5f, true);
        press(element, 0.5f, 0.5f, false);
    }

    private int keyStart;

    /** One key down and up, through the real keyboard path. */
    private void key(int code) {
        document.input().consumeKeyboardEvent(new CgSystemInput.Keyboard.Event((char) 0, code, true, false, System.currentTimeMillis()));
        document.input().consumeKeyboardEvent(new CgSystemInput.Keyboard.Event((char) 0, code, false, false, System.currentTimeMillis()));
    }

    private void shot(String name) {
        shotContext.getArtifactService().requestCapture("profiler-" + name);
    }

    private static void log(String line) {
        System.out.println("[profiler-shot] " + line);
    }

    /**
     * Written before the paint so the numbers are this frame's, not last frame's — the same ordering
     * reason the dock scene defers its own dump until after a paint: input is consumed before layout
     * runs, so anything read on the way in describes boxes that have not been measured yet.
     */
    private void refreshReadout() {
        UIElement area = desktop.windowLayer();
        WindowFrame active = desktop.activeWindow();
        Box areaBox = area == null ? null : area.box();
        StringBuilder text = new StringBuilder();
        text.append("work area  ")
                .append(areaBox == null ? 0 : Math.round(areaBox.width())).append(" x ")
                .append(areaBox == null ? 0 : Math.round(areaBox.height()))
                .append("      active: ").append(active == null ? "(none)" : active.getTitle())
                .append('\n');
        // THE REGISTRY, not the layer -- so hidden windows are listed too. A minimised window that
        // appears nowhere is a minimised window with no way back, which is the failure W4's taskbar
        // exists to prevent.
        for (WindowFrame window : desktop.windows()) {
            boolean hidden = window.state() == WindowState.HIDDEN;
            Box box = window.box();
            text.append(window == active ? "> " : "  ")
                    .append(window.getTitle()).append("  ")
                    .append(hidden ? "hidden" : Math.round(window.left()) + ", " + Math.round(window.top()))
                    .append("   ")
                    .append(box == null ? 0 : Math.round(box.width())).append(" x ")
                    .append(box == null ? 0 : Math.round(box.height()))
                    // THE DEPTH, because raise is the one W2 operation with no visible effect of its
                    // own when the windows happen not to overlap.
                    .append("   z ").append(window.getStyle().getGeneralGroup().zIndex())
                    .append('\n');
        }
        readout.setText(text.toString());
    }

    @Override
    public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
        return driver.offerKey(event, () -> handleKey(event));
    }

    private boolean handleKey(CgSystemInput.Keyboard.Event event) {
        // THE MODE OWNS THE KEYBOARD except for its own way out. In game the keyboard is the game's;
        // here the scene has to stand in for that, and a mode nobody can leave is worse than no mode.
        if (event.pressed() && event.key() == CgKeyCodes.KEY_F6) {
            if (desktop.isHudMode()) desktop.exitHudMode(); else desktop.enterHudMode();
            return true;
        }
        if (desktop.isHudMode()) {
            if (event.pressed()) windowKey(event.key());
            return true;
        }
        // THE APPLICATION FIRST, and the scene's keys only on what it leaves -- as a real host acts only on
        // an unconsumed key. Taken first, they were dead to every binding of their own in the application:
        // the builder's and the explorer's F2 rename, the editor's F3 Find Next, the explorer's F5 refresh.
        if (document.input().consumeKeyboardEvent(event)) return true;
        if (!event.pressed()) return false;
        if (windowKey(event.key())) return true;
        // F5 IS A CONVENIENCE, NOT THE AFFORDANCE. The designer is a registered command with its own
        // chord and a taskbar context-menu entry, so it is reachable identically here and in game --
        // this key exists only because a harness scene is where it gets opened forty times an hour.
        // F7 AND F8 ARE NOT HANDLED HERE, and used to be. They are `desktop.frameStats` and
        // `desktop.frameStatsDetail` now -- registered by DesktopCommands, so the editor and a Minecraft
        // screen answer the same two keys. The document is offered every key above this line, so a
        // handler for them here could never run; leaving one would read as the live path.
        if (event.key() == CgKeyCodes.KEY_F5) {
            WindowFrame existing = desktop.registry().byKey("taskbar-designer");
            if (existing != null) existing.requestClose();
            else TaskbarDesigner.open(document);
            return true;
        }
        // F9 IS NOT HANDLED HERE: it is `profiler.open`, the command every surface with a desktop has,
        // so the scene's key and the game's are the same binding.
        return false;
    }

    /** F2 opens a window, F3 floats the Project panel, F4 opens a window without taking focus. */
    private boolean windowKey(int key) {
        switch (key) {
            case CgKeyCodes.KEY_F2 -> spawnCascadedWindow();
            case CgKeyCodes.KEY_F3 -> toggleFloatingProjectPanel();
            case CgKeyCodes.KEY_F4 -> spawnBackgroundWindow();
            default -> {
                return false;
            }
        }
        return true;
    }

    /**
     * W12's no-steal rule and the three things a taskbar entry can say, in one keystroke.
     *
     * <p>What to look at, and most of it is what does <b>not</b> happen. The window appears and
     * <em>nothing else moves</em>: the window you were in keeps its caption lit, keeps the keyboard, and
     * is not covered — the new one goes in behind it. That is Win32's foreground lock, X11's urgency
     * hint and macOS's bouncing dock icon, all of which exist because the alternative is a server
     * pushing a UI that eats the next thing you type.</p>
     *
     * <p>Its taskbar entry is what says it arrived: highlighted until you click it, and never again
     * after — activation is the only thing that clears it, because a flash on a timer is a notification
     * you can miss by looking away. The entry also carries a <b>badge</b> and fills with
     * <b>progress</b>, which is the same entry answering three separate questions at once and the
     * reason they were built together.</p>
     */
    private void spawnBackgroundWindow() {
        WindowFrame frame = new WindowFrame("Server push " + (++backgroundWindows));
        frame.setBadge(String.valueOf(backgroundWindows));
        UIText note = new UIText("Opened without focus.\nThe editor kept the keyboard.");
        note.generalStyle(g -> g.color(0xFFB4B8BF));
        frame.content().append(note);
        frame.resizeTo(220f, 110f).moveTo(40f + backgroundWindows * 16f, 340f);
        // FALSE is the no-steal rule -- the second argument is whether to activate.
        desktop.addWindow(frame, false);
        // A REAL DURATION, ticked rather than set once: progress that is only ever written at the moment
        // the window opens draws a bar nobody sees move, which demonstrates the slot and not the feature.
        frame.setProgress(0f);
        document.animation().every(frame, delta -> {
            if (frame.state() == WindowState.DESTROYED) return false;
            float next = frame.progress() + delta * 0.25f;
            frame.setProgress(next);
            if (next < 1f) return true;
            // Done: the fill goes away and the badge with it, which is the half that rots when nobody
            // writes it -- a badge only ever added says "3 errors" for the rest of the session.
            frame.setProgress(-1f);
            frame.setBadge(null);
            return false;
        });
    }

    /**
     * W8 in one keystroke: the Project panel out of its region and into an owned window, and back.
     *
     * <p>The gesture a user actually performs is dragging a rail button into the editor area — a drop
     * that resolves to no region, which is IntelliJ's own tear-out zone. That is hard to do
     * <em>reliably</em> from a scene, and impossible to do at all before the first frame, so the key is
     * here as well: it drives exactly the same two calls the drop does.</p>
     *
     * <p>What to look at, because most of W8 is only visible in what does <b>not</b> happen. The float
     * is a real {@code WindowFrame} — drag its caption, resize its edges — and it is <b>owned</b>: move
     * the editor window and the float travels with it, minimise the editor and the float goes too, and
     * it never appears in the taskbar, because it is not independently reachable. The panel keeps its
     * expansion and scroll across the trip, because the same container is moved rather than rebuilt. And
     * there is <b>one header</b>: the panel's own is adopted into the frame's caption.</p>
     */
    private void toggleFloatingProjectPanel() {
        if (editor == null) return;
        ToolWindowManager manager = editor.workbench().toolWindowManager();
        if (manager.typeOf(Workbench.PROJECT_TYPE) == ToolWindowType.FLOATING) {
            manager.dockPanel(Workbench.PROJECT_TYPE);
            return;
        }
        // Offset from the editor window's own corner, which is the space a float's insets are measured
        // in -- it is owned by that window, so (0,0) is the editor's top-left and not the desktop's.
        manager.floatPanel(Workbench.PROJECT_TYPE, 100f, 70f);
    }

    /**
     * A window with <b>no position at all</b> — which is the case the cascade exists for, and the only
     * way to exercise it. Placement then happens on this frame's layout pass, once the caption and the
     * work area have been measured; there is nothing to place against before that.
     */
    private void spawnCascadedWindow() {
        spawned++;
        WindowFrame window = desktop.addWindow(new WindowFrame("Window " + spawned));
        window.setIcon("crystalgui:file-text");
        window.resizeTo(200, 120);
        window.content().append(new UIText("Cascaded, not placed."));
        window.content().append(focusRow("One", "Two"));
    }

    @Override
    public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
        // DISPLAY-ONLY IS THE RULE THAT MAKES THE HUD SOUND. In game the cursor is grabbed, so its
        // reported position is wherever the player last had a menu open -- delivering a move against it
        // would enter and leave elements under a pointer that is not there. Refusing here is the
        // harness standing in for a grab it has no way to perform.
        return driver.offerMouse(event, () -> desktop.isHudMode() || document.input().consumeMouseEvent(event));
    }

    @Override
    public void dispose() {
        if (driver == null) return;
        driver.run(() -> {
            // ASKED FOR EXPLICITLY, because this scene tears down without ever detaching anything -- and
            // detaching is the moment each of these would otherwise write itself. WHAT to write is still
            // theirs; this only says when.
            if (desktop != null) desktop.savePersistedState();
            // QUITTING IT, which writes its state on the way out. Closing the window would not: a
            // workbench under HIDE_ON_CLOSE is still running with everything in it.
            if (editor != null) editor.dispose();
        });
        driver.close();
        driver = null;
        editor = null;
        document = null;
        desktop = null;
        readout = null;
    }

    @Override
    public boolean isRunning() {
        return !profilerShotDone && !traceCostDone && !graphCostDone && !hoverSweepDone;
    }

    @Override
    public boolean uses3DCamera() {
        return false;
    }

    @Override
    public boolean shouldShutdownOnComplete() {
        return PROFILER_SHOT || TRACE_COST;
    }
}
