package com.crystalgui.core.async;

import com.crystalgui.core.CrystalGuiCore;

import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Reports work that took a frame's worth of time <b>on the thread that draws frames</b>.
 *
 * <h3>Why a guard rather than an audit</h3>
 *
 * <p>Two operations cost this application its frame rate and neither was visible from its call site.
 * {@code ResourceContentProvider.symbolOf(Resource)} reads exactly like a property getter and ran a
 * 761ms compile the first time it was asked about a class. {@code HostClasspath.detect()} reads like a
 * getter and opened <em>every jar on the classpath</em> — 3-5ms over 23 entries, against an 8.3ms budget
 * at 120Hz, on a presentation provider the dock re-reads on every strip rebuild. Its own javadoc said
 * those archive opens were "paid once per process"; with no cache they were paid once per call.</p>
 *
 * <p>Nobody wrote a bad call site. An audit of callers would have found nothing, because the cost is
 * behind the callee's signature — so the thing worth building is not a one-time sweep but something that
 * <b>names the operation the next time it happens</b>. Reported as "the editor feels slow", a cost like
 * this is nearly unattributable; reported as one line naming the call and its duration, it is a
 * five-minute fix. That is the same reasoning {@code serverSmoke} exists on: a runtime property no test
 * and no import scan can see is worth a mechanism that observes it running.</p>
 *
 * <h3>Always on, and it costs two clock reads</h3>
 *
 * <p>Not behind a system property, because a guard that is off by default catches nothing and this
 * codebase has already paid for that shape — "live and inert look identical, so a capability that can be
 * silently skipped must say it is on". The whole cost off the UI thread is one volatile read and a
 * branch; on it, two calls to {@code nanoTime}.</p>
 *
 * <p><b>Once per operation.</b> A slow call is usually slow every time, and a warning per frame would
 * bury the report it is trying to make — so each distinct description is reported once and the set is
 * capped, since a description carrying a resource name is otherwise unbounded.</p>
 */
public final class UiBudget {

    private UiBudget() {
    }

    /**
     * What a single operation may take on the frame thread before it is worth hearing about.
     *
     * <p>2ms against 8.3ms at 120Hz. Not a quarter of the budget by arithmetic — it is that a frame does
     * many things, so one of them taking a quarter of the whole is already the largest thing in it.</p>
     */
    private static final long BUDGET_NANOS = 2_000_000L;

    /** Above this many distinct reports, stop remembering: the point has been made. */
    private static final int MAX_REPORTS = 200;

    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    /**
     * Starts timing, or returns {@code 0} when there is nothing to measure against.
     *
     * <p>Zero is the "not on the frame thread" sentinel and {@link #end} treats it as such, so a caller
     * needs no branch of its own and background work pays a volatile read.</p>
     */
    public static long begin() {
        return UiThread.isCurrent() ? System.nanoTime() : 0L;
    }

    /**
     * Reports if the operation cost a visible part of a frame.
     *
     * @param started what {@link #begin} returned
     * @param what    the operation, named well enough to act on — include the subject, not just the call
     */
    public static void end(long started, String what) {
        if (started == 0L) return;
        long took = System.nanoTime() - started;
        if (took < BUDGET_NANOS) return;
        if (REPORTED.size() >= MAX_REPORTS || !REPORTED.add(what)) return;
        report(what, took);
    }

    /**
     * Says it, <b>off the thread being measured</b>.
     *
     * <h3>Measured: the first report cost 245ms on the frame thread</h3>
     *
     * <p>Not the formatting — the logging framework initialising on its first use, which in a bare JVM
     * happens to be this call. A guard that stalls a frame by a quarter of a second to complain about a
     * frame being stalled is worse than no guard, and it would have polluted exactly the measurements it
     * exists to inform. In the application the logger is warm long before any frame, so this is invisible
     * there — which is what makes it the kind of thing that ships.</p>
     *
     * <p><b>Its own thread rather than {@link JobScheduler}</b>, on purpose. A report can be raised from
     * inside a {@code drain()} — {@code onDone} runs on the frame thread, and anything it touches may ask
     * a provider — so enqueuing there would mutate the scheduler's own queues while it is walking them.
     * A daemon thread owes nothing to the frame loop and cannot hold the process open.</p>
     */
    private static void report(String what, long took) {
        REPORTS.add(what + " took " + took / 1_000_000L + "ms");
        startReporter();
    }

    private static final BlockingQueue<String> REPORTS = new LinkedBlockingQueue<>();

    private static volatile boolean reporting;

    private static synchronized void startReporter() {
        if (reporting) return;
        reporting = true;
        Thread thread = new Thread(() -> {
            while (true) {
                try {
                    String line = REPORTS.take();
                    CrystalGuiCore.LOGGER.warn(
                            "[ui-budget] {} on the frame thread; work that is a pure function of a "
                                    + "snapshot belongs on JobScheduler. @see UiThread", line);
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "cgui-ui-budget");
        thread.setDaemon(true);
        thread.start();
    }

    /** Forgets what has been reported, so a test can assert on the report happening. */
    public static void forgetForTesting() {
        REPORTED.clear();
    }

    /** Whether this operation has already been reported — what a test asserts on. */
    public static boolean hasReported(String what) {
        return REPORTED.contains(what);
    }
}
