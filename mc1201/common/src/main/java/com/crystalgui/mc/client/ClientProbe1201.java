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
 * <b>A scripted client run, off unless asked for.</b> {@code -Dcrystalgui.clientProbe=true}
 *
 * <p>What {@code serverSmoke} is to the dedicated server, this is to the client: boot, drive a fixed
 * routine, photograph each step, quit. It exists because the rendering faults on this loader are all
 * things somebody has to LOOK at -- a wash that appears only while an animation is playing, a menu that
 * flashes white for a few frames, a surface that is a flat fill rather than a blurred one -- and none
 * of them is reachable from a headless test or from the harness, which has no world behind the UI and
 * therefore never takes the paths that break.
 *
 * <p>The alternative is a person opening the game, performing the routine by hand and describing what
 * they saw, once per candidate fix. That is the slowest instrument available and the least precise: a
 * photograph taken at a named step is comparable across runs, and a sentence is not.
 *
 * <p><b>It quits on its own, and it quits when it goes wrong too</b> -- {@link #BUDGET_TICKS} is a hard
 * ceiling, so a step that never becomes ready ends the run rather than leaving a client up forever with
 * a Gradle task attached to it.
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
                // POLLED, never assumed. The editor needs a workspace and a workspace needs a
                // connection, so the window appears some unknown number of ticks after the ask -- and a
                // fixed wait either photographs an empty desktop or spends time it did not need.
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
                // ANCHORED ON THE TASKBAR ENTRY, not on the desktop. The anchor decides placement, and
                // anchored to the desktop the menu resolves somewhere the screenshot never showed -- so
                // the shot came back looking like the menu had not opened at all, which is a different
                // fault from the one being chased.
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
