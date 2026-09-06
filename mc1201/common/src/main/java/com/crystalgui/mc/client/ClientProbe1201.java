package com.crystalgui.mc.client;

import java.util.List;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.window.WindowState;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.window.SystemMenu;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.ui.dom.UIElement;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

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
public final class ClientProbe1201 {

    /** {@code -Dcrystalgui.clientProbe=true}. */
    public static final boolean ENABLED = Boolean.getBoolean("crystalgui.clientProbe");

    private ClientProbe1201() {}

    /** Ticks, at 20/s. Steps wait in these rather than in frames, so a slow host still settles. */
    private static final int SETTLE = 40;

    private static final int BUDGET_TICKS = 2400;

    private enum Step {
        WAIT_WORLD, OPEN_EDITOR, WAIT_EDITOR, SHOOT_EDITOR,
        MINIMISE, SHOOT_MINIMISE_MID, SHOOT_MINIMISED,
        RESTORE, SHOOT_RESTORE_MID, SHOOT_RESTORED,
        JUMP_LIST, SHOOT_JUMP_LIST,
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
                CgUiScreen1201.openEditor();
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
                Desktop desktop = CgUiScreen1201.desktop();
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
                Desktop desktop = CgUiScreen1201.desktop();
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
                step = Step.QUIT;
                waitTicks = SETTLE;
                break;
            case QUIT:
                quit();
                break;
            default:
                break;
        }
    }

    /** The first window with a taskbar entry -- the editor, on every routine this drives. */
    private static WindowFrame mainWindow() {
        Desktop desktop = CgUiScreen1201.desktop();
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

    private static void say(String what) {
        CrystalGuiCore.LOGGER.info("[cgui-probe] {}", what);
    }
}
