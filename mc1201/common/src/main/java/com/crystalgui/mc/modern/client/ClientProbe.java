package com.crystalgui.mc.modern.client;

import java.util.List;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.window.WindowState;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.window.SystemMenu;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.fs.Resource;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.workbench.Workbench;

import com.crystalgui.mc.modern.platform.LifecycleCrystalGUI;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ChatScreen;

import org.lwjgl.glfw.GLFW;

/**
 * A scripted client run: join a world, drive a fixed routine, photograph each step, quit.
 *
 * <pre>{@code
 * ./gradlew :mc1201:forge:runClient -Dcrystalgui.clientProbe=true
 * ./gradlew :mc1201:forge:runClient -Dcrystalgui.clientProbe=true -PcgWorld="Some World"
 * }</pre>
 *
 * <p>Screenshots land in {@code runs/client/screenshots} as {@code cgui-NN-step.png}. Add
 * {@code -Dcrystalgui.layer.probe=true} for GL readbacks alongside them.</p>
 *
 * <p>What {@code serverSmoke} is to the dedicated server, this is to the client — for faults that have
 * to be looked at, and that a headless test and the harness cannot reach because neither has a world
 * behind the UI. It quits on {@link #BUDGET_TICKS} even when a step never becomes ready, so a stalled
 * run ends rather than stranding a Gradle task.</p>
 *
 * <p>Add a step by extending {@link Step} and giving it a case in {@code advance()}; each sets the next
 * step and optionally {@code waitTicks}.</p>
 */
public final class ClientProbe {

    /** {@code -Dcrystalgui.clientProbe=true}. */
    public static final boolean ENABLED = Boolean.getBoolean("crystalgui.clientProbe");

    private ClientProbe() {}

    /** Ticks, at 20/s. Steps wait in these rather than in frames, so a slow host still settles. */
    private static final int SETTLE = 40;

    private static final int BUDGET_TICKS = 2400;

    private enum Step {
        WAIT_WORLD, OPEN_EDITOR, WAIT_EDITOR, SHOOT_EDITOR,
        MINIMISE, SHOOT_MINIMISE_MID, SHOOT_MINIMISED,
        RESTORE, SHOOT_RESTORE_MID, SHOOT_RESTORED,
        JUMP_LIST, SHOOT_JUMP_LIST,
        OPEN_JAVA, SHOOT_JAVA,
        PIN, OPEN_CHAT, OVERLAY_CLICK, SHOOT_OVERLAY,
        HUD_GRABBED_CLICK,
        QUIT, DONE
    }

    private static Step step = Step.WAIT_WORLD;
    private static int waitTicks;
    private static int totalTicks;
    private static int shot;
    private static int editorWaited;

    /** Ticks to wait for the editor window before photographing whatever is there. */
    private static final int EDITOR_DEADLINE = 400;

    /** The class a taskbar entry wears; the anchor a real right-click would present from. */
    private static final String ENTRY_CLASS = "__entry__";

    /** The sample workspace's Java file -- what makes the language stack say anything at all. */
    private static final String JAVA_PROBE_FILE = "workspace:src/main/java/com/example/Main.java";

    /** Called once per client tick. Cheap when off: one static boolean read. */
    public static void tick() {
        if (!ENABLED || step == Step.DONE) return;
        if (++totalTicks > BUDGET_TICKS) {
            CrystalGuiCore.LOGGER.warn("[cgui-probe] budget of {} ticks spent at step {}; quitting",
                    BUDGET_TICKS, step);
            quit();
            return;
        }
        if (waitTicks > 0) {
            waitTicks--;
            return;
        }
        try {
            advance();
        } catch (RuntimeException | LinkageError failed) {
            // A probe that dies mid-routine must still end the run, or the task hangs on a client
            // nobody is driving.
            CrystalGuiCore.LOGGER.error("[cgui-probe] step {} failed; quitting", step, failed);
            quit();
        }
    }

    private static void advance() {
        Minecraft mc = Minecraft.getInstance();
        switch (step) {
            case WAIT_WORLD:
                if (mc.level == null || mc.player == null) return;
                say("in a world");
                step = Step.OPEN_EDITOR;
                waitTicks = SETTLE;
                break;
            case OPEN_EDITOR:
                CgUiScreen.openEditor();
                step = Step.WAIT_EDITOR;
                editorWaited = 0;
                break;
            case WAIT_EDITOR:
                // Polled rather than waited out: the editor needs a workspace, which needs a connection,
                // so the window appears an unknown number of ticks after the ask.
                if (mainWindow() != null) {
                    say("editor window is up after " + editorWaited + " ticks");
                    step = Step.SHOOT_EDITOR;
                    // Settle: the dock builds its groups a frame later and a file has to be read.
                    waitTicks = SETTLE * 2;
                    return;
                }
                if (++editorWaited > EDITOR_DEADLINE) {
                    say("no editor window after " + EDITOR_DEADLINE + " ticks; shooting anyway");
                    step = Step.SHOOT_EDITOR;
                }
                break;
            case SHOOT_EDITOR:
                say("fps on the desktop = " + mc.getFps());
                shoot("editor-open");
                step = Step.MINIMISE;
                break;
            case MINIMISE: {
                WindowFrame frame = mainWindow();
                if (frame == null) {
                    say("no window to minimise");
                    step = Step.JUMP_LIST;
                    return;
                }
                frame.minimize();
                step = Step.SHOOT_MINIMISE_MID;
                // MID-ANIMATION on purpose: the window animations are the reported fault, and the
                // settled state is exactly the one frame that cannot show it.
                waitTicks = 3;
                break;
            }
            case SHOOT_MINIMISE_MID:
                shoot("minimise-mid");
                step = Step.SHOOT_MINIMISED;
                waitTicks = SETTLE;
                break;
            case SHOOT_MINIMISED:
                shoot("minimised");
                step = Step.RESTORE;
                break;
            case RESTORE: {
                WindowFrame frame = mainWindow();
                if (frame == null) {
                    step = Step.JUMP_LIST;
                    return;
                }
                if (frame.state() == WindowState.HIDDEN) frame.show(true);
                Desktop desktop = CgUiScreen.desktop();
                if (desktop != null) desktop.activate(frame);
                step = Step.SHOOT_RESTORE_MID;
                waitTicks = 3;
                break;
            }
            case SHOOT_RESTORE_MID:
                shoot("restore-mid");
                step = Step.SHOOT_RESTORED;
                waitTicks = SETTLE;
                break;
            case SHOOT_RESTORED:
                shoot("restored");
                step = Step.JUMP_LIST;
                break;
            case JUMP_LIST: {
                WindowFrame frame = mainWindow();
                Desktop desktop = CgUiScreen.desktop();
                if (frame == null || desktop == null) {
                    step = Step.QUIT;
                    return;
                }
                // Anchored on the taskbar entry: the anchor decides placement, and anchored to the
                // desktop the menu lands somewhere the screenshot does not cover.
                UIElement anchor = desktop.querySelector("." + ENTRY_CLASS);
                say("jump list anchored on " + (anchor == null ? "the desktop (no entry found)" : "an entry"));
                SystemMenu.showJumpList(frame, anchor != null ? anchor : desktop);
                step = Step.SHOOT_JUMP_LIST;
                waitTicks = 10;
                break;
            }
            case SHOOT_JUMP_LIST:
                shoot("jump-list");
                step = Step.OPEN_JAVA;
                waitTicks = SETTLE;
                break;
            case OPEN_JAVA: {
                // The language stack says nothing until a Java file is analysed, and the restored session
                // opens whatever was last open -- which is not this on a fresh workspace.
                if (CgUiScreen.editorWorkbench() == null) {
                    say("no workbench; cannot open a java file");
                    step = Step.PIN;
                    return;
                }
                say("opening " + JAVA_PROBE_FILE);
                CgUiScreen.editorWorkbench().editors().open(Resource.parse(JAVA_PROBE_FILE));
                step = Step.SHOOT_JAVA;
                // The open is a round trip to the workspace, and the first analysis has to open an
                // engine band before it can answer anything.
                waitTicks = SETTLE * 4;
                break;
            }
            case SHOOT_JAVA:
                shoot("java-open");
                reportJavaDiagnostics();
                step = Step.PIN;
                waitTicks = SETTLE;
                break;
            case PIN: {
                WindowFrame frame = mainWindow();
                if (frame == null) {
                    say("no window to pin");
                    step = Step.QUIT;
                    return;
                }
                frame.setPinned(true);
                // The jump list from the previous step is still promoted over the middle of the window,
                // and a press there hits the menu rather than the editor under it.
                UIDocument doc = CgUiScreen.window();
                if (doc != null) doc.dismiss().lightDismiss(null);
                // The pointer, parked over the window's middle: overlayHitTest reads the REAL cursor,
                // so a synthesised press with the cursor elsewhere would answer about somewhere else.
                parkPointerOver(frame);
                say("pinned, pointer parked");
                step = Step.OPEN_CHAT;
                waitTicks = SETTLE;
                break;
            }
            case OPEN_CHAT:
                // A foreign screen, which is what turns the presentation into OVERLAY.
                mc.setScreen(new ChatScreen(""));
                step = Step.OVERLAY_CLICK;
                waitTicks = SETTLE;
                break;
            case OVERLAY_CLICK:
                // The loader's own entry points, so this exercises the chain a real click takes from
                // ScreenEvent inward -- the hit test, the keyboard handover, the dispatch.
                say("offering a press, a release and a key");
                LifecycleCrystalGUI.offerMouse(0, true, 0f);
                LifecycleCrystalGUI.offerMouse(0, false, 0f);
                LifecycleCrystalGUI.offerKey(GLFW.GLFW_KEY_Z, (char) 0, true);
                // A visible character: whether it lands in the buffer is the whole question, and the
                // screenshot is the only place the answer shows.
                LifecycleCrystalGUI.offerKey(0, 'Z', true);
                LifecycleCrystalGUI.offerKey(GLFW.GLFW_KEY_Z, (char) 0, false);
                step = Step.SHOOT_OVERLAY;
                waitTicks = SETTLE;
                break;
            case SHOOT_OVERLAY:
                say("fps in " + CgUiHud.presentation() + " = " + mc.getFps());
                shoot("overlay");
                step = Step.HUD_GRABBED_CLICK;
                waitTicks = SETTLE;
                break;
            case HUD_GRABBED_CLICK: {
                // Back to playing: no screen, mouse grabbed. A press here is an attack, and the
                // compositor must not see it however the camera happens to be pointing.
                mc.setScreen(null);
                if (mc.mouseHandler != null) mc.mouseHandler.grabMouse();
                boolean consumed = LifecycleCrystalGUI.offerMouse(0, true, 0f);
                say("grabbed=" + (mc.mouseHandler != null && mc.mouseHandler.isMouseGrabbed())
                        + " presentation=" + CgUiHud.presentation()
                        + " press consumed=" + consumed + " (expected false)");
                step = Step.QUIT;
                waitTicks = SETTLE;
                break;
            }
            case QUIT:
                quit();
                break;
            default:
                break;
        }
    }

    /** Puts the OS cursor over the middle of {@code frame}, in surface pixels. */
    private static void parkPointerOver(WindowFrame frame) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null || frame.box() == null) return;
        float scale = CgUiScreen.uiScale();
        double x = (frame.box().x() + frame.box().width() / 2f) * scale;
        double y = (frame.box().y() + frame.box().height() / 2f) * scale;
        GLFW.glfwSetCursorPos(mc.getWindow().getWindow(), x, y);
        say("pointer at " + Math.round(x) + "," + Math.round(y));
    }

    /** The first window with a taskbar entry -- the editor, on every routine this drives. */
    private static WindowFrame mainWindow() {
        Desktop desktop = CgUiScreen.desktop();
        if (desktop == null) return null;
        List<WindowFrame> windows = desktop.registry().taskbarOrder();
        return windows.isEmpty() ? null : windows.get(0);
    }

    /**
     * Photographs Minecraft's own main target, which is where our composite lands.
     *
     * <p>Deliberately not a readback of one of our render targets: the question these answer is what
     * ended up ON SCREEN, and the whole class of fault being chased is one where an intermediate target
     * is correct and the picture is not.
     */
    private static void shoot(String name) {
        Minecraft mc = Minecraft.getInstance();
        String file = String.format("cgui-%02d-%s.png", ++shot, name);
        Screenshot.grab(mc.gameDirectory, file, mc.getMainRenderTarget(), message -> { });
        say("shot " + file);
    }

    private static void quit() {
        step = Step.DONE;
        say("routine complete; stopping the client");
        Minecraft.getInstance().stop();
    }

    /**
     * The analysed file's diagnostics, as text.
     *
     * <p>A count is legible in a screenshot and the messages are not, and the difference decides what
     * to look at: sixteen errors from a broken classpath and sixteen from a typo photograph the same.
     * This layer degrades silently by design, so the run has to say what it concluded.</p>
     */
    private static void reportJavaDiagnostics() {
        Workbench workbench = CgUiScreen.editorWorkbench();
        if (workbench == null) return;
        List<Diagnostic> problems = workbench.markers().read(Resource.parse(JAVA_PROBE_FILE));
        say(problems.size() + " diagnostic(s) on " + JAVA_PROBE_FILE);
        for (Diagnostic problem : problems) {
            say("  " + problem.severity() + " " + problem.start().row() + ":"
                    + problem.start().column() + " " + problem.message());
        }
    }

    private static void say(String what) {
        CrystalGuiCore.LOGGER.info("[cgui-probe] {}", what);
    }
}
