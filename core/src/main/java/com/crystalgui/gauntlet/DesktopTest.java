package com.crystalgui.gauntlet;

import com.crystalgui.screen.CgUiScreen;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.crystalgraphics.gauntlet.CgGauntlet;
import com.crystalgraphics.gauntlet.CgGauntletCapture;
import com.crystalgraphics.render.canvas.CgCanvasMaterials;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.variable.CgVar;
import com.crystalgui.core.CrystalGuiCore;

/**
 * {@code crystalgui:desktop}: the desktop opens in a world, paints, brings its application forward, and draws over a
 * mod's renderer on CrystalGraphics' GUI stages; then it is photographed as {@code crystalgui-desktop-desktop.png}.
 * The check that runs on a shipped jar ({@code prodSmoke}).
 *
 * <pre>{@code
 * -Dcrystalgraphics.gauntlet=crystalgui:desktop
 * -Dcrystalgui.desktop.bare=true          // the desktop alone, no application brought forward
 * -Dcrystalgui.desktop.lateFrame=600      // and a second photograph that many frames after the open
 * }</pre>
 *
 * <p>Another mod's checks ride on it: a step on a frame counted from the open, and a check into its report.</p>
 *
 * <pre>{@code
 * if (DesktopTest.plays()) DesktopTest.onFrame(5, MyProbe::runOnce);    // a client listener's onPreInit
 * DesktopTest.report("my.thing", ok, detail);                         // from the step
 * }</pre>
 *
 * <ul>
 *   <li>A photograph proves a frame was read back, not that the desktop drew it: the report states whether it painted
 *       and which stage drew beside every photograph.</li>
 *   <li>It ends once its photographs are written and the last step's frame has passed.</li>
 * </ul>
 */
public final class DesktopTest extends DesktopRun {

    public static final String ID = "crystalgui:desktop";

    private static final boolean BARE = CgVar.bool("crystalgui.desktop.bare", false, CgVar.DEV | CgVar.RESTART,
            "crystalgui:desktop opens the bare desktop, no application brought forward.").get();
    private static final int LATE_FRAME = CgVar.integer("crystalgui.desktop.lateFrame", 0, 0, 100_000,
            CgVar.DEV | CgVar.RESTART, "Frames after the open crystalgui:desktop takes a second photograph at; 0 for none.").get();
    /** Seconds after the desktop is ready (painted, its application up) before it is photographed. */
    private static final double READY_SETTLE = 2;
    /** Seconds past the open the photograph waits for ready before it is taken anyway, and the checks say why. */
    private static final double READY_GRACE = 20;
    /** Frames each photograph waits past the last event it follows (the open, ready, the first photograph). */
    private static final int SETTLE_FRAMES = 20;

    private static final Map<Integer, List<Runnable>> STEPS = new TreeMap<>();
    private static volatile DesktopTest active;

    private long openedNanos, readyNanos;
    private int readyFrame, earlyFrame;
    private CgGauntletCapture.Shot early, late;
    private final StageMark mark = new StageMark();

    public DesktopTest() {
        super(BARE);
    }

    /** Whether this process's run plays the desktop test: what a mod asks before registering steps. */
    public static boolean plays() {
        return CgGauntlet.plays(ID);
    }

    /**
     * Runs {@code step} on the {@code frame}-th frame after the desktop opened. Several share a frame in registration
     * order; one that throws is logged and the rest run. Before the run's start, a client listener's {@code onPreInit}.
     */
    public static synchronized void onFrame(int frame, Runnable step) {
        STEPS.computeIfAbsent(frame, unused -> new ArrayList<>()).add(step);
    }

    /** States a check into the running desktop test's report; logged only outside one. Any thread. */
    public static void report(String name, boolean ok, String detail) {
        DesktopTest test = active;
        if (test != null) test.check(name, ok, detail);
        else CrystalGuiCore.LOGGER.info("{} {}: {} ({})", ID, name, ok, detail);
    }

    @Override
    protected void onInit() {
        active = this;
    }

    @Override
    protected void onOpened() {
        openedNanos = System.nanoTime();
        mark.start();
    }

    @Override
    protected void onEnd() {
        active = null;
        mark.stop();
    }

    @Override
    protected void onDesktopFrame(int frame) {
        runSteps(frame);
        long now = System.nanoTime();
        boolean ready = CgUiScreen.hasPainted() && (BARE || CgUiScreen.application() != null);
        if (ready && readyNanos == 0) {
            readyNanos = now;
            readyFrame = frame;
        }
        if (early == null) {
            boolean settled = readyNanos != 0 && frame >= readyFrame + SETTLE_FRAMES
                    && (now - readyNanos) / 1e9 >= READY_SETTLE;
            if (frame >= SETTLE_FRAMES && (settled || (now - openedNanos) / 1e9 >= READY_GRACE)) {
                photographed();
                early = capture("desktop");
                earlyFrame = frame;
            }
            return;
        }
        if (LATE_FRAME > 0 && late == null && frame >= LATE_FRAME && frame >= earlyFrame + SETTLE_FRAMES) {
            late = capture("late");
        }
        boolean shotsDone = early.done() && (LATE_FRAME <= 0 || late != null && late.done());
        if (!shotsDone || frame <= lastStep()) return;
        check("desktop.capture", early.written(), early.written() ? early.file().toString() : early.failure());
        if (late != null) check("desktop.late-capture", late.written(), late.written() ? late.file().toString() : late.failure());
        endTest();
    }

    /** What the photograph shows, stated beside it. */
    private void photographed() {
        check("desktop.painted", CgUiScreen.hasPainted(), "the desktop presented a frame");
        if (BARE) skip("desktop.application", "the bare desktop");
        else check("desktop.application", CgUiScreen.application() != null, "the primary application is up");
        check("desktop.stage-screen", mark.screen > 0, "a mod's renderer on the SCREEN stage drew " + mark.screen
                + " frames, on HUD " + mark.hud);
    }

    private static synchronized int lastStep() {
        return STEPS.isEmpty() ? 0 : ((TreeMap<Integer, List<Runnable>>) STEPS).lastKey();
    }

    private void runSteps(int frame) {
        List<Runnable> steps;
        synchronized (DesktopTest.class) {
            steps = STEPS.get(frame);
        }
        if (steps == null) return;
        for (Runnable step : steps) {
            try {
                step.run();
            } catch (RuntimeException | LinkageError failed) {
                CrystalGuiCore.LOGGER.error("{} frame {} step failed", ID, frame, failed);
                check("desktop.step-" + frame, false, failed.toString());
            }
        }
    }

    /** A mod's renderer on CrystalGUI's GUI stages: a mark over the windows, top left, and a count of each stage's draws. */
    private static final class StageMark {
        /** Over CrystalGUI's windows. */
        private static final int ORDER = CgUiScreen.ORDER + 1000;
        private static final int MARK = 0xFFFF00FF;
        volatile int screen, hud;
        private CgRenderStage.Registration onScreen, onHud;

        void start() {
            onScreen = CgRenderStage.SCREEN.register(ORDER, frame -> {
                draw(frame);
                screen++;
            });
            onHud = CgRenderStage.HUD.register(ORDER, frame -> {
                draw(frame);
                hud++;
            });
        }

        void stop() {
            if (onScreen != null) onScreen.close();
            if (onHud != null) onHud.close();
        }

        private void draw(CgStageFrame frame) {
            // The canvas's image with no texture: its colour alone, the least a mod draws at a stage with.
            frame.canvas().quad(CgCanvasMaterials.SCREEN.image()).at(4f, 4f).size(12f, 12f).color(MARK).submit();
        }
    }
}
