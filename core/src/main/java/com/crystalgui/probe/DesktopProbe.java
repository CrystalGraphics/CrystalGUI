package com.crystalgui.probe;

import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.window.DesktopPresentation;
import com.crystalgui.core.window.WindowState;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.app.Application;
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
 * <b>A scripted client run</b>: join a world, drive a fixed routine, photograph each step, quit.
 *
 * <pre>{@code
 * // once per client tick
 * DesktopProbe.tick(myHost);
 * }</pre>
 *
 * <p>What {@code ServerSmoke} is to the dedicated server, this is to the desktop — for faults that have
 * to be <em>looked at</em>, and that neither a headless test nor the GL harness can reach because
 * neither has a world behind the UI. Captures land beside the game as {@code cgui-NN-step.png}.</p>
 *
 * <h3>Why the routine is here and not in a loader</h3>
 *
 * <p>Minimise, restore mid-animation, the jump list, pin, click through an overlay, click while the
 * mouse is grabbed — not one of those is a fact about a Minecraft version. They were 1.20.x's alone and
 * 1.7.10 had no equivalent at all, which is the same asymmetry the wire probes had: the era with the
 * routine was the only era it ever ran on.</p>
 *
 * <p>It quits on {@link #BUDGET_TICKS} even when a step never becomes ready, so a stalled run ends
 * rather than stranding a build task.</p>
 *
 * <p>Add a step by giving {@link Step} a constant and {@code advance} a case; each sets the next step
 * and optionally a wait.</p>
 */
public final class DesktopProbe {

    /** {@code -Dcrystalgui.clientProbe=true}. */
    public static final boolean ENABLED = Boolean.getBoolean("crystalgui.clientProbe");

    /** What only the running game can answer. Thirteen one-liners, and every one is per-game. */
    public interface Host {

        /** Whether the client is in a world yet. */
        boolean inWorld();

        /** Opens this host's desktop with its primary application brought forward. */
        void openDesktop();

        /** Frames per second, for the line that says what the desktop costs. */
        int fps();

        /** Opens a screen the desktop does NOT own — what turns the presentation into OVERLAY. */
        void openForeignScreen();

        /** Closes whatever screen is up, so the player is back in the world. */
        void closeScreen();

        /** Takes the mouse, as playing does. */
        void grabMouse();

        boolean mouseGrabbed();

        /** Puts the OS cursor at a point in <b>surface</b> pixels. */
        void movePointerTo(int surfaceX, int surfaceY);

        /** Photographs what is on screen, into {@code fileName} beside the game. */
        void shoot(String fileName);

        /** Ends the run. */
        void quit();

        /** Offers a button through the loader's own entry point. @return whether it was consumed */
        boolean offerMouse(int button, boolean pressed);

        /** Offers a key through the loader's own entry point. */
        void offerKey(int platformKey, char typed, boolean pressed);

        /** This platform's code for {@code Z} — LWJGL2 and GLFW do not agree. */
        int keyZ();

        /** What the compositor says it is showing. */
        DesktopPresentation presentation();
    }

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
        WAIT_WORLD, OPEN_DESKTOP, WAIT_WINDOW, SHOOT_DESKTOP,
        MINIMISE, SHOOT_MINIMISE_MID, SHOOT_MINIMISED,
        RESTORE, SHOOT_RESTORE_MID, SHOOT_RESTORED,
        JUMP_LIST, SHOOT_JUMP_LIST,
        OPEN_JAVA, SHOOT_JAVA,
        PIN, OPEN_FOREIGN_SCREEN, OVERLAY_CLICK, SHOOT_OVERLAY,
        HUD_GRABBED_CLICK,
        QUIT, DONE
    }

    private static Step step = Step.WAIT_WORLD;
    private static int waitTicks;
    private static int totalTicks;
    private static int shot;
    private static int windowWaited;

    private DesktopProbe() {
    }

    /** Called once per client tick. Cheap when off: one static boolean read. */
    public static void tick(Host host) {
        if (!ENABLED || step == Step.DONE) return;
        if (++totalTicks > BUDGET_TICKS) {
            CrystalGuiCore.LOGGER.warn("[cgui-probe] budget of {} ticks spent at step {}; quitting",
                    BUDGET_TICKS, step);
            quit(host);
            return;
        }
        if (waitTicks > 0) {
            waitTicks--;
            return;
        }
        try {
            advance(host);
        } catch (RuntimeException | LinkageError failed) {
            // A probe that dies mid-routine must still end the run, or the task hangs on a client
            // nobody is driving.
            CrystalGuiCore.LOGGER.error("[cgui-probe] step {} failed; quitting", step, failed);
            quit(host);
        }
    }

    private static void advance(Host host) {
        switch (step) {
            case WAIT_WORLD:
                if (!host.inWorld()) return;
                say("in a world");
                step = Step.OPEN_DESKTOP;
                waitTicks = SETTLE;
                break;
            case OPEN_DESKTOP:
                host.openDesktop();
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
                say("fps on the desktop = " + host.fps());
                shoot(host, "desktop-open");
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
                shoot(host, "minimise-mid");
                step = Step.SHOOT_MINIMISED;
                waitTicks = SETTLE;
                break;
            case SHOOT_MINIMISED:
                shoot(host, "minimised");
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
                shoot(host, "restore-mid");
                step = Step.SHOOT_RESTORED;
                waitTicks = SETTLE;
                break;
            case SHOOT_RESTORED:
                shoot(host, "restored");
                step = Step.JUMP_LIST;
                break;
            case JUMP_LIST: {
                WindowFrame frame = mainWindow();
                Desktop desktop = desktop();
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
                shoot(host, "jump-list");
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
                shoot(host, "java-open");
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
                UIDocument doc = document();
                if (doc != null) doc.dismiss().lightDismiss(null);
                // The pointer, parked over the window's middle: the overlay hit test reads the REAL
                // cursor, so a synthesised press with the cursor elsewhere asks about somewhere else.
                parkPointerOver(host, frame);
                say("pinned, pointer parked");
                step = Step.OPEN_FOREIGN_SCREEN;
                waitTicks = SETTLE;
                break;
            }
            case OPEN_FOREIGN_SCREEN:
                // A screen this desktop does not own, which is what turns the presentation into OVERLAY.
                host.openForeignScreen();
                step = Step.OVERLAY_CLICK;
                waitTicks = SETTLE;
                break;
            case OVERLAY_CLICK:
                // The loader's own entry points, so this exercises the chain a real click takes from the
                // screen event inward -- the hit test, the keyboard handover, the dispatch.
                say("offering a press, a release and a key");
                host.offerMouse(0, true);
                host.offerMouse(0, false);
                host.offerKey(host.keyZ(), (char) 0, true);
                // A visible character: whether it lands in the buffer is the whole question, and the
                // screenshot is the only place the answer shows.
                host.offerKey(0, 'Z', true);
                host.offerKey(host.keyZ(), (char) 0, false);
                step = Step.SHOOT_OVERLAY;
                waitTicks = SETTLE;
                break;
            case SHOOT_OVERLAY:
                say("fps in " + host.presentation() + " = " + host.fps());
                shoot(host, "overlay");
                step = Step.HUD_GRABBED_CLICK;
                waitTicks = SETTLE;
                break;
            case HUD_GRABBED_CLICK: {
                // Back to playing: no screen, mouse grabbed. A press here is an attack, and the
                // compositor must not see it however the camera happens to be pointing.
                host.closeScreen();
                host.grabMouse();
                boolean consumed = host.offerMouse(0, true);
                say("grabbed=" + host.mouseGrabbed()
                        + " presentation=" + host.presentation()
                        + " press consumed=" + consumed + " (expected false)");
                step = Step.QUIT;
                waitTicks = SETTLE;
                break;
            }
            case QUIT:
                quit(host);
                break;
            default:
                break;
        }
    }

    /** Puts the OS cursor over the middle of {@code frame}, in surface pixels. */
    private static void parkPointerOver(Host host, WindowFrame frame) {
        if (frame.box() == null || !HostSession.isInstalled()) return;
        float scale = HostSession.session().services().uiScale();
        int x = Math.round((frame.box().x() + frame.box().width() / 2f) * scale);
        int y = Math.round((frame.box().y() + frame.box().height() / 2f) * scale);
        host.movePointerTo(x, y);
        say("pointer at " + x + "," + y);
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

    /**
     * Photographs what is on screen.
     *
     * <p>Deliberately not a readback of one of our own render targets: the question these answer is what
     * ended up ON SCREEN, and the whole class of fault being chased is one where an intermediate target
     * is correct and the picture is not.</p>
     */
    private static void shoot(Host host, String name) {
        String file = String.format("cgui-%02d-%s.png", ++shot, name);
        host.shoot(file);
        say("shot " + file);
    }

    private static void quit(Host host) {
        step = Step.DONE;
        say("routine complete; stopping the client");
        host.quit();
    }

    /**
     * The analysed file's diagnostics, as text.
     *
     * <p>A count is legible in a screenshot and the messages are not, and the difference decides what to
     * look at: sixteen errors from a broken classpath and sixteen from a typo photograph the same. This
     * layer degrades silently by design, so the run has to say what it concluded.</p>
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
        CrystalGuiCore.LOGGER.info("[cgui-probe] {}", what);
    }
}
