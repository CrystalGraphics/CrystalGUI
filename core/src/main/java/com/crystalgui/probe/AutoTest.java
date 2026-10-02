package com.crystalgui.probe;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nullable;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.desktop.host.HostSession;
import com.crystalgui.core.notify.Notification;
import com.crystalgui.core.notify.NotificationEvent;
import com.crystalgui.core.notify.Notifications;

/**
 * <b>The unattended run: what it is asked for, and where its captures go.</b>
 *
 * <p>A host drives its own loop — counting, loading a world, taking a screenshot — because every one of
 * those is a different call per game, and on 1.20.x the screenshot is asynchronous. What a host must not
 * decide for itself is what this class holds: the properties it is configured through, which file each
 * capture lands in, and what {@code *} means. Every one of those was stated twice, and two had already
 * drifted.</p>
 *
 * <pre>{@code
 * if (!AutoTest.ENABLED) return;
 * // ...once the game is up and settled:
 * openDesktop();
 * // ...and once per unit the host settles in -- a painted frame, a tick:
 * AutoTest.settled(host, count);      // runs onFrame steps, takes the captures, quits
 * }</pre>
 *
 * <p><b>A capture is not a paint.</b> It proves a frame was read back, not that this engine drew it: with
 * no live GL context a screen's render returns at once, and outside a level the game never clears the
 * colour buffer, so the frame still holds the previous screen. Log
 * {@link com.crystalgui.desktop.host.HostSession#hasPainted()} beside every capture — a photograph of a
 * main menu once passed every other check.</p>
 */
public final class AutoTest {

    /** {@code -Dcrystalgui.autotest=true}. */
    public static final boolean ENABLED = Boolean.getBoolean("crystalgui.autotest");

    /**
     * How long to wait before opening the desktop.
     *
     * <p>Never zero: the first client tick fires while resources are still reloading, so opening then
     * measures a half-initialised game rather than the desktop.</p>
     */
    public static final String OPEN_AFTER = "crystalgui.autotest.openTick";

    /**
     * When to take the first capture.
     *
     * <p>Layout settles over several passes — {@code UIText} re-measures and pushes its height back
     * until it stops changing — so capturing at once shows a tree mid-settle and reads as a layout bug
     * that is really an early screenshot.</p>
     *
     * <p><b>The unit and the default are the host's, and the two hosts disagree:</b> 1.7.10 counts
     * <em>painted frames</em> from 10, 1.20.x counts <em>ticks</em> from 20. The name lives here so it
     * cannot drift; the number does not, because changing it moves the timing of the only check that can
     * see a packaging defect — and that check is what would have to validate the change.</p>
     */
    public static final String CAPTURE_AT = "crystalgui.autotest.frame";

    /**
     * A SECOND capture, much later. Zero or less means none. Same unit as {@link #CAPTURE_AT}.
     *
     * <p>The first capture proves the desktop renders; it cannot prove it KEEPS rendering. A resource
     * freed or a pooled buffer recycled after a few hundred frames looks identical to "it never worked"
     * to somebody watching, and identical to "it works" to one early screenshot.</p>
     */
    public static final String LATE_CAPTURE_AT = "crystalgui.autotest.lateFrame";

    private static final String OUTPUT =
            System.getProperty("crystalgui.autotest.out", "crystalgui-autotest.png");

    private static final String WORLD = emptyToNull(System.getProperty("crystalgui.autotest.world"));

    /**
     * Extra work another jar wants run on a painted frame, keyed by frame number.
     *
     * <p><b>The seam the language mod's own probes arrive through</b>, and the reason this class names
     * nothing in {@code language/}: since J8 the scripting half ships in a second jar, so its steps
     * cannot be called from here by name.</p>
     */
    private static final Map<Integer, List<Runnable>> FRAME_STEPS = new TreeMap<>();

    private AutoTest() {
    }

    /**
     * Where the first capture goes.
     *
     * <p>Always suffixed, including when no late capture was asked for. 1.7.10 wrote the unsuffixed path
     * in that case and 1.20.x did not, so a caller reading one name found a file on one loader and
     * nothing on the other. {@code prodSmoke} always asks for a late capture, which is the only reason
     * that never showed.</p>
     */
    public static String earlyCapture() {
        return OUTPUT.replace(".png", "-early.png");
    }

    /** @see #earlyCapture() */
    public static String lateCapture() {
        return OUTPUT.replace(".png", "-late.png");
    }

    /** Whether a world was asked for at all, before any disk is read. */
    public static boolean wantsWorld() {
        return WORLD != null;
    }

    /**
     * The save to load, or null when none was asked for or none is on disk.
     *
     * <p>{@code *} means "whichever one is there". <b>Because a folder name cannot always be passed:</b>
     * an installed client takes these through PrismLauncher's {@code JvmArgs}, which splits on spaces
     * and strips quotes — so {@code -Dcrystalgui.autotest.world=New World} arrives as two arguments and
     * the JVM dies with {@code Could not find or load main class World...}. Every world made through the
     * vanilla UI is called "New World". The sentinel sidesteps the quoting problem, and "the first save"
     * is what somebody driving this unattended means anyway.</p>
     *
     * <p>A host that can <em>create</em> a world may treat null as "make one"; one that cannot stays
     * where it is. That difference is real and is the host's to make.</p>
     *
     * @param savesDir the game's {@code saves} directory
     */
    @Nullable
    public static String resolveWorld(File savesDir) {
        if (WORLD == null) return null;
        if (!"*".equals(WORLD)) return WORLD;
        File[] saves = savesDir.listFiles();
        if (saves == null) return null;
        for (File save : saves) {
            if (new File(save, "level.dat").isFile()) return save.getName();
        }
        return null;
    }

    /**
     * What only the running game can answer, for {@link #tick} and {@link #settled}.
     *
     * <p><b>The thresholds are the host's, units and all</b>, and that is deliberate: 1.7.10 counts
     * <em>painted frames</em> between opening and capturing, 1.20.x counts <em>ticks</em>. Frames are
     * the better proxy — layout is what settles — but unifying them would retime {@code prodSmoke},
     * which is the only check that can see a packaging defect and therefore the one that would have to
     * validate the change. The sequence is shared; the clock is not. @see #CAPTURE_AT</p>
     */
    public interface Host {

        /** Whether the game is up enough to be driven at all — past a splash, past a loading overlay. */
        boolean readyToDrive();

        /** Whether a world is loaded. */
        boolean inWorld();

        /** Gets into a world. Asked until it answers true, then never again. */
        boolean enterWorld();

        /** Opens this host's desktop with its primary application brought forward. */
        void openDesktop();

        /** Photographs what is on screen into {@code path}. */
        void capture(String path);

        /** Ends the run. */
        void quit();

        /** How long to wait before opening, and again after a world loads. In this host's units. */
        int openAfter();

        /** When to take the first capture, counted from the open. In this host's units. */
        int captureAt();

        /** When to take the second, or zero for none. In this host's units. */
        int lateCaptureAt();
    }

    private static int waited;
    private static int inWorldWaited;
    private static boolean worldRequested;
    private static boolean opened;
    private static boolean capturedEarly;
    /** The settle the desktop was first ready on (application up, a frame painted), or -1. */
    private static int readySince = -1;
    /** The settle the first capture was taken on. */
    private static int earlyAt;
    /** When the first capture fell due, so its wait for a painted frame is bounded. */
    private static long captureDueNanos;
    /** How long the first capture waits past its due point for the desktop to be ready. */
    private static final long PAINT_GRACE_NANOS = 15_000_000_000L;
    private static boolean capturedLate;
    /** Calls to {@link #settled} since the desktop opened: the clock {@link #onFrame} counts on. */
    private static int settledSinceOpen;

    /**
     * The phases up to opening the desktop, on the host's own tick.
     *
     * <p>Stops driving once the desktop is open — {@link #settled} takes it from there, on whatever the
     * host counts settling in.</p>
     */
    public static void tick(Host host) {
        if (!ENABLED || opened) return;
        if (!host.readyToDrive()) return;

        if (wantsWorld()) {
            // IN-WORLD, and it is not the same test as opening over a menu: in a world the renderer has
            // run a full pipeline pass before the GUI draws, and at a main menu that never happens. If
            // the desktop draws in one and not the other, that difference IS the bug.
            if (!host.inWorld()) {
                if (++waited < host.openAfter()) return;
                if (!worldRequested) worldRequested = host.enterWorld();
                return;
            }
            // Let it settle, so the pipeline has really run some frames.
            if (++inWorldWaited < host.openAfter()) return;
        } else {
            if (++waited < host.openAfter()) return;
        }

        opened = true;
        CrystalGuiCore.LOGGER.info("CGUI AUTOTEST opening the desktop{}",
                wantsWorld() ? " (in world)" : "");
        // A refusal a person would read in a balloon, into the log a run is judged by.
        Notifications.onDidChange.connect(event -> {
            if (event.kind() != NotificationEvent.Kind.ADDED || event.notification() == null) return;
            Notification added = event.notification();
            CrystalGuiCore.LOGGER.info("CGUI AUTOTEST notification: {}{}", added,
                    added.getDetail().isEmpty() ? "" : " -- " + added.getDetail().replace((char) 10, ' '));
        });
        // A mod's renderer on CrystalGUI's stages, which every capture states the draws of. @see StageProbe
        StageProbe.start();
        host.openDesktop();
    }

    /**
     * The capture phases, counted from the open in whatever unit this host settles in.
     *
     * <p>Called with a monotonic count — painted frames on one era, ticks on another. @see Host</p>
     */
    public static void settled(Host host, int sinceOpen) {
        if (!ENABLED || !opened || capturedLate) return;
        runSteps(++settledSinceOpen);

        // READY IS WHAT THE CAPTURE PHOTOGRAPHS: the application launched and a frame painted. A desktop on its own
        // thread launches the application there and presents a frame later, so its first painted frame can be the
        // bare taskbar; the capture comes a full settling delay after ready. Bounded in WALL time, since the hosts'
        // units differ, so a desktop that never gets there is still photographed and reported.
        boolean due = sinceOpen >= host.captureAt();
        if (due && captureDueNanos == 0L) captureDueNanos = System.nanoTime();
        if (readySince < 0 && HostSession.isInstalled() && HostSession.session().hasPainted()
                && HostSession.session().application() != null) {
            readySince = sinceOpen;
        }
        boolean settledSinceReady = readySince >= 0 && sinceOpen >= readySince + host.captureAt();
        boolean waitedEnough = due && System.nanoTime() - captureDueNanos >= PAINT_GRACE_NANOS;
        if (!capturedEarly && due && (settledSinceReady || waitedEnough)) {
            earlyAt = sinceOpen;
            capturedEarly = true;
            CrystalGuiCore.LOGGER.info("CGUI AUTOTEST {}", StageProbe.report());
            host.capture(earlyCapture());
            // NOTHING LEFT TO TAKE THE SECOND WITH, so the first must not quit when one is wanted.
            if (host.lateCaptureAt() <= 0) {
                capturedLate = true;
                host.quit();
            }
            return;
        }
        if (capturedEarly && host.lateCaptureAt() > 0 && sinceOpen >= host.lateCaptureAt()
                && sinceOpen >= earlyAt + host.captureAt()) {
            capturedLate = true;
            CrystalGuiCore.LOGGER.info("CGUI AUTOTEST {}", StageProbe.report());
            host.capture(lateCapture());
            host.quit();
        }
    }

    /**
     * Runs {@code step} on the {@code frame}-th settle after the desktop opened, on every host — a painted
     * frame on 1.7.10 and legacy Forge, a tick on 1.13+. Only in an unattended run.
     *
     * <pre>{@code
     * AutoTest.onFrame(5, MyProbe::runOnce);   // from mod init, long before anything paints
     * }</pre>
     *
     * <p>Several steps may share a frame and run in registration order; a step that throws is logged and
     * the rest still run. A step registered for a frame that has already passed simply never runs. Before
     * the host's capture frame, a step's effect is in the photograph.</p>
     */
    public static void onFrame(int frame, Runnable step) {
        FRAME_STEPS.computeIfAbsent(frame, unused -> new ArrayList<>()).add(step);
    }

    /** @deprecated {@link #settled} runs the steps; a host calling this as well runs each twice. */
    @Deprecated
    public static void runFrameSteps(int framesPainted) {
        runSteps(framesPainted);
    }

    private static void runSteps(int framesPainted) {
        List<Runnable> steps = FRAME_STEPS.get(framesPainted);
        if (steps == null) return;
        for (Runnable step : steps) {
            try {
                step.run();
            } catch (RuntimeException failed) {
                CrystalGuiCore.LOGGER.error("CGUI AUTOTEST frame {} step failed", framesPainted, failed);
            }
        }
    }

    @Nullable
    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
