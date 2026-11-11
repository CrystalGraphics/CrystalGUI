package com.crystalgui.gauntlet;

import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgraphics.gauntlet.CgGauntletController;
import com.crystalgraphics.gauntlet.CgGauntletState;
import com.crystalgraphics.job.CgHostThread;
import com.crystalgraphics.lifecycle.CgEngineLifecycle;
import com.crystalgraphics.platform.input.CgInput;
import com.crystalgraphics.platform.input.CgInputStage;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.platform.service.CgGameSession;
import com.crystalgraphics.platform.service.CgScreens;
import com.crystalgui.core.window.WindowState;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.app.Application;
import com.crystalgui.desktop.host.DesktopHost;
import com.crystalgui.desktop.host.HostSession;
import com.crystalgui.desktop.window.SystemMenu;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.fs.Resource;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.workbench.Workbench;
import com.crystalgui.workbench.app.WorkbenchApplication;

/**
 * {@code crystalgui:desktop-script}: a scripted client run. It enters a world, drives a fixed routine through the
 * desktop (minimise, restore mid-animation, the jump list, a Java file, pin, a click through an overlay, a click while
 * the mouse is grabbed), photographs each step and ends.
 *
 * <pre>{@code
 * -Dcrystalgraphics.gauntlet=crystalgui:desktop-script
 * }</pre>
 *
 * <p>For faults that have to be looked at, which neither a headless test nor the harness reaches because neither has
 * a world behind the UI. Photographs land in the run's folder as {@code crystalgui-desktop-script-NN-step.png}.</p>
 *
 * <ul>
 *   <li>Clicks and keys go into CrystalGraphics' {@code SCREEN} input stage, where a host's own events arrive; the
 *       game's screens are {@code CgScreens}', the pointer {@code CgInput}'s.</li>
 *   <li>Steps wait in client ticks (20 a second), so a slow host still settles.</li>
 *   <li>It ends after {@link #BUDGET_TICKS} even when a step never becomes ready, failing its budget check.</li>
 *   <li>A step is a {@link Step} constant and a case in {@code advance}, which sets the next step and a wait.</li>
 * </ul>
 */
public final class DesktopScriptTest extends CgGauntletController {

    public static final String ID = "crystalgui:desktop-script";

    /** Ticks, at 20/s. Steps wait in these rather than in frames, so a slow host still settles. */
    private static final int SETTLE = 40;

    private static final int BUDGET_TICKS = 2400;

    /** Ticks to wait for the desktop's window before photographing whatever is there. */
    private static final int WINDOW_DEADLINE = 400;

    /** The class a taskbar entry wears; the anchor a real right-click would present from. */
    private static final String ENTRY_CLASS = "__entry__";

    /** The sample workspace's Java file -- what makes the language stack say anything at all. */
    private static final String JAVA_PROBE_FILE = "workspace:src/main/java/com/example/Main.java";

    private enum Step {
        WAIT_WORLD, GAME_THREADS, WAIT_GAME_THREADS, OPEN_DESKTOP, WAIT_WINDOW, SHOOT_DESKTOP,
        MINIMISE, SHOOT_MINIMISE_MID, SHOOT_MINIMISED,
        RESTORE, SHOOT_RESTORE_MID, SHOOT_RESTORED,
        JUMP_LIST, SHOOT_JUMP_LIST,
        OPEN_JAVA, SHOOT_JAVA,
        PIN, OPEN_FOREIGN_SCREEN, OVERLAY_CLICK, SHOOT_OVERLAY,
        HUD_GRABBED_CLICK,
        FINISH, DONE
    }

    /** The test playing; the client tick reaches it here, since a tick subscription lasts the process. */
    private static volatile DesktopScriptTest active;
    private static boolean subscribed;

    private Step step = Step.WAIT_WORLD;
    private int waitTicks, totalTicks, shot, windowWaited, threadsWaited;
    private volatile String clientAnswer, serverAnswer;
    /** Where the pointer was last put, which a click is offered at. */
    private int pointerX, pointerY;
    /** Frames since the last frame-rate reading, and when it was. */
    private int framesSince;
    private long framesSinceNanos = System.nanoTime();

    @Override
    protected void onInit() {
        active = this;
        synchronized (DesktopScriptTest.class) {
            if (subscribed) return;
            subscribed = true;
        }
        CgEngineLifecycle.onClientTick(() -> {
            DesktopScriptTest test = active;
            if (test != null) test.tick();
        });
    }

    @Override
    protected void onStateChange(CgGauntletState from, CgGauntletState to) {
        if (to.equals(CgGauntletState.TITLE)) enterWorld();
    }

    @Override
    protected void onTick(float seconds) {
        framesSince++;
    }

    @Override
    protected void onEnd() {
        active = null;
    }

    /** Once per client tick. */
    private void tick() {
        if (step == Step.DONE) return;
        if (++totalTicks > BUDGET_TICKS) {
            check("desktop-script.budget", false, "budget of " + BUDGET_TICKS + " ticks spent at step " + step);
            finish();
            return;
        }
        if (waitTicks > 0) {
            waitTicks--;
            return;
        }
        try {
            // ON THE DOCUMENT'S SEQUENCE when it has one: a step reads and writes the tree.
            if (HostSession.isInstalled()) HostSession.session().inDocument(this::advance);
            else advance();
        } catch (RuntimeException | LinkageError failed) {
            CrystalGuiCore.LOGGER.error("[{}] step {} failed; ending", ID, step, failed);
            check("desktop-script.step", false, "step " + step + " threw " + failed);
            finish();
        }
    }

    private void advance() {
        switch (step) {
            case WAIT_WORLD:
                if (!CgGameSession.inWorld()) return;
                say("in a world");
                step = Step.GAME_THREADS;
                waitTicks = SETTLE;
                break;
            case GAME_THREADS:
                // FROM UI CODE, as a mod would: each game thread runs the work and answers here.
                CgHostThread.CLIENT.call(() -> Thread.currentThread().getName()).then(name -> clientAnswer = name);
                if (CgHostThread.SERVER.isAvailable()) {
                    CgHostThread.SERVER.call(() -> Thread.currentThread().getName()).then(name -> serverAnswer = name);
                } else {
                    serverAnswer = "(no integrated server)";
                }
                threadsWaited = 0;
                step = Step.WAIT_GAME_THREADS;
                break;
            case WAIT_GAME_THREADS:
                if ((clientAnswer == null || serverAnswer == null) && ++threadsWaited < WINDOW_DEADLINE) return;
                say("game threads: client work ran on '" + clientAnswer + "', server work on '" + serverAnswer + "'");
                if (clientAnswer == null || serverAnswer == null) {
                    CrystalGuiCore.LOGGER.error("[" + ID + "] a game thread never answered: client {}, server {}",
                            clientAnswer, serverAnswer);
                }
                step = Step.OPEN_DESKTOP;
                break;
            case OPEN_DESKTOP:
                HostSession.session().openApplication();
                step = Step.WAIT_WINDOW;
                windowWaited = 0;
                break;
            case WAIT_WINDOW:
                // Polled rather than waited out: the application needs a workspace, which needs a
                // connection, so the window appears an unknown number of ticks after the ask.
                if (mainWindow() != null) {
                    say("window is up after " + windowWaited + " ticks");
                    step = Step.SHOOT_DESKTOP;
                    // Settle: the dock builds its groups a frame later and a file has to be read.
                    waitTicks = SETTLE * 2;
                    return;
                }
                if (++windowWaited > WINDOW_DEADLINE) {
                    say("no window after " + WINDOW_DEADLINE + " ticks; shooting anyway");
                    step = Step.SHOOT_DESKTOP;
                }
                break;
            case SHOOT_DESKTOP:
                say("fps on the desktop = " + fps());
                shoot("desktop-open");
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
                Desktop desktop = desktop();
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
                Desktop desktop = desktop();
                if (frame == null || desktop == null) {
                    step = Step.FINISH;
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
                // The language stack says nothing until a Java file is analysed, and the restored
                // session opens whatever was last open -- which is not this on a fresh workspace.
                Workbench workbench = workbench();
                if (workbench == null) {
                    say("no workbench; cannot open a java file");
                    step = Step.PIN;
                    return;
                }
                say("opening " + JAVA_PROBE_FILE);
                workbench.editors().open(Resource.parse(JAVA_PROBE_FILE));
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
                    step = Step.FINISH;
                    return;
                }
                frame.setPinned(true);
                // The jump list from the previous step is still promoted over the middle of the window,
                // and a press there hits the menu rather than the editor under it.
                UIDocument doc = document();
                if (doc != null) doc.dismiss().lightDismiss(null);
                // The pointer, parked over the window's middle: the overlay hit test reads the REAL
                // cursor, so a synthesised press with the cursor elsewhere asks about somewhere else.
                parkPointerOver(frame);
                say("pinned, pointer parked");
                step = Step.OPEN_FOREIGN_SCREEN;
                waitTicks = SETTLE;
                break;
            }
            case OPEN_FOREIGN_SCREEN:
                // A screen this desktop does not own, which is what turns the presentation into OVERLAY.
                CgScreens.openChat();
                step = Step.OVERLAY_CLICK;
                waitTicks = SETTLE;
                break;
            case OVERLAY_CLICK:
                // The SCREEN stage a host's own events arrive at, so this exercises the chain a real click
                // takes from there inward -- the hit test, the keyboard handover, the dispatch.
                say("offering a press, a release and a key");
                offerMouse(0, true);
                offerMouse(0, false);
                offerKey(CgKeyCodes.KEY_Z, true);
                // A visible character: whether it lands in the buffer is the whole question, and the
                // screenshot is the only place the answer shows.
                offerCharacter('Z');
                offerKey(CgKeyCodes.KEY_Z, false);
                step = Step.SHOOT_OVERLAY;
                waitTicks = SETTLE;
                break;
            case SHOOT_OVERLAY:
                say("fps in " + HostSession.session().presentation() + " = " + fps());
                shoot("overlay");
                step = Step.HUD_GRABBED_CLICK;
                waitTicks = SETTLE;
                break;
            case HUD_GRABBED_CLICK: {
                // Back to playing: no screen, mouse grabbed. A press here is an attack, and the
                // compositor must not see it however the camera happens to be pointing.
                CgScreens.closeFront();
                CgInput.grabPointer();
                boolean consumed = offerMouse(0, true);
                say("grabbed=" + CgInput.pointerGrabbed() + " presentation=" + HostSession.session().presentation());
                check("desktop-script.grabbed-press", !consumed, "a press while playing is the game's, not the desktop's");
                step = Step.FINISH;
                waitTicks = SETTLE;
                break;
            }
            case FINISH:
                check("desktop-script.routine", true, "every step ran");
                finish();
                break;
            default:
                break;
        }
    }

    /** Puts the OS cursor over the middle of {@code frame}, in surface pixels. */
    private void parkPointerOver(WindowFrame frame) {
        if (frame.box() == null || !HostSession.isInstalled()) return;
        float scale = DesktopHost.UI_SCALE;
        pointerX = Math.round((frame.box().x() + frame.box().width() / 2f) * scale);
        pointerY = Math.round((frame.box().y() + frame.box().height() / 2f) * scale);
        CgInput.movePointer(pointerX, pointerY);
        say("pointer at " + pointerX + "," + pointerY);
    }

    /** A button at the parked pointer, into the SCREEN stage; whether something consumed it. */
    private boolean offerMouse(int button, boolean pressed) {
        return CgInputStage.SCREEN.mouseButton(new CgSystemInput.Mouse.Event(pointerX, pointerY, 0, 0, button, pressed,
                0f, System.currentTimeMillis()));
    }

    private static void offerKey(int key, boolean pressed) {
        CgInputStage.SCREEN.key(new CgSystemInput.Keyboard.Event((char) 0, key, pressed, false, System.currentTimeMillis()));
    }

    private static void offerCharacter(char typed) {
        CgInputStage.SCREEN.character(new CgSystemInput.Keyboard.Event(typed, 0, true, false, System.currentTimeMillis()));
    }

    /** Frames a second since the last reading, for the lines that say what the desktop costs. */
    private int fps() {
        long now = System.nanoTime();
        int fps = (int) Math.round(framesSince / ((now - framesSinceNanos) / 1e9));
        framesSince = 0;
        framesSinceNanos = now;
        return fps;
    }

    /** The first window with a taskbar entry -- the primary application, on every routine this drives. */
    @Nullable
    private static WindowFrame mainWindow() {
        Desktop desktop = desktop();
        if (desktop == null) return null;
        List<WindowFrame> windows = desktop.registry().taskbarOrder();
        return windows.isEmpty() ? null : windows.get(0);
    }

    @Nullable
    private static Desktop desktop() {
        return HostSession.isInstalled() ? HostSession.session().desktop() : null;
    }

    @Nullable
    private static UIDocument document() {
        return HostSession.isInstalled() ? HostSession.session().document() : null;
    }

    @Nullable
    private static Workbench workbench() {
        Application app = HostSession.isInstalled() ? HostSession.session().application() : null;
        return app instanceof WorkbenchApplication ? ((WorkbenchApplication) app).workbench() : null;
    }

    /** Photographs what is on screen as the next numbered step. */
    private void shoot(String name) {
        String file = String.format("%02d-%s", ++shot, name);
        capture(file);
        say("shot " + file);
    }

    private void finish() {
        step = Step.DONE;
        say("routine complete");
        endTest();
    }

    /**
     * The analysed file's diagnostics, as text: a count is legible in a photograph and the messages are not, and
     * sixteen errors from a broken classpath photograph the same as sixteen from a typo.
     */
    private static void reportJavaDiagnostics() {
        Workbench workbench = workbench();
        if (workbench == null) return;
        List<Diagnostic> problems = workbench.markers().read(Resource.parse(JAVA_PROBE_FILE));
        say(problems.size() + " diagnostic(s) on " + JAVA_PROBE_FILE);
        for (Diagnostic problem : problems) {
            say("  " + problem.severity() + " " + problem.start().row() + ":"
                    + problem.start().column() + " " + problem.message());
        }
    }

    private static void say(String what) {
        CrystalGuiCore.LOGGER.info("[{}] {}", ID, what);
    }
}
