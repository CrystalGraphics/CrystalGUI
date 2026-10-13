package com.crystalgui.core.async;

import com.crystalgraphics.job.CgJob;
import com.crystalgraphics.job.CgJobKey;
import com.crystalgraphics.job.CgJobLane;
import com.crystalgraphics.job.CgJobLoop;
import com.crystalgraphics.job.progress.CgProgress;
import com.crystalgraphics.job.progress.CgProgressState;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.notify.Notification;
import com.crystalgui.core.notify.Notifications;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * A UI's jobs: CrystalGraphics' {@link CgJob}s on a loop the UI drains, plus the rows the chrome draws for the long
 * ones and a notification when an announced one fails.
 *
 * <pre>{@code
 * JobScheduler.shared()
 *         .job(CgJobKey.of(document, "reparse"), CgJobLane.INTERACTIVE, ctx -> parse(text, ctx))
 *         .debounce(300)
 *         .onDone(document::setTree)
 *         .submit();
 *
 * JobScheduler.shared().cancelAll(document);   // closing it
 * }</pre>
 *
 * <p>Keys, debounce, lanes and supersession are {@link CgJob}'s. A test hands its own executor and clock:</p>
 *
 * <pre>{@code
 * JobScheduler jobs = new JobScheduler(Runnable::run, clock::get);
 * jobs.job(key, CgJobLane.INTERACTIVE, ctx -> 42).onDone(seen::add).submit();
 * jobs.drain();   // delivers on this thread
 * }</pre>
 *
 * <ul>
 *   <li>{@link #drain()} once a frame or tick, on the thread that owns it: it is where results land.</li>
 *   <li>A superseded job's {@code onDone} never runs, and its reply never settles.</li>
 *   <li>A job appears in {@link #active()} only once its work calls {@link CgProgress#begin}.</li>
 * </ul>
 */
public final class JobScheduler implements Disposable {

    private final CgJobLoop loop;
    /** Each key's latest job, until it has ended: what {@link #active()} reads. */
    private final Map<CgJobKey, CgJob<?>> live = new LinkedHashMap<>();
    private boolean disposed;

    /** A scheduler on the engine's job pool. */
    public JobScheduler() {
        this.loop = CgJobLoop.create("cgui");
    }

    /**
     * @param executor    where work runs. A same-thread executor makes every test deterministic
     * @param clockMillis what debounce and the chrome's delays are measured on
     */
    public JobScheduler(Executor executor, LongSupplier clockMillis) {
        this.loop = CgJobLoop.create("cgui", executor, clockMillis);
    }

    /**
     * The calling sequence's scheduler, or on the render thread the application-wide one, created on first use.
     * Guarded by {@link #hasShared()} at the drain site, so asking whether there is work never creates one.
     */
    public static JobScheduler shared() {
        // A document on its own sequence drains its own, and an answer must come back to the sequence that asked.
        UiSequence sequence = UiSequence.current();
        if (sequence != null) return sequence.jobs();
        return processWide();
    }

    /** Whether {@link #shared()} has been created — checked before draining, so asking never constructs. */
    public static boolean hasShared() {
        UiSequence sequence = UiSequence.current();
        if (sequence != null) return sequence.hasJobs();
        return shared != null;
    }

    private static synchronized JobScheduler processWide() {
        if (shared == null) shared = new JobScheduler();
        return shared;
    }

    @Nullable
    private static volatile JobScheduler shared;
    private static volatile boolean undrainedReported;
    private static final AtomicInteger asynchronousDocuments = new AtomicInteger();

    /**
     * A document now records on its own sequence, so nothing drains the process-wide scheduler for it: work posted
     * there is reported. Paired with {@link #asynchronousDocumentClosed()}; the document's driver calls both.
     */
    public static void asynchronousDocumentOpened() {
        asynchronousDocuments.incrementAndGet();
    }

    public static void asynchronousDocumentClosed() {
        asynchronousDocuments.decrementAndGet();
    }

    /**
     * Where a call on this scheduler belongs: a document's sequence when the process-wide one is asked from inside
     * it. A service that kept {@link #shared()} from registration then still answers on the sequence that drains
     * it, not on a scheduler that sequence never drains.
     */
    private JobScheduler route() {
        if (this != shared) return this;
        UiSequence sequence = UiSequence.current();
        return sequence == null ? this : sequence.jobs();
    }

    /** The loop its jobs report to; a sequence enters it while it runs. */
    public CgJobLoop loop() {
        return loop;
    }

    // ── Submitting ──────────────────────────────────────────────────────────────────────────────

    /**
     * Describes a job. Nothing runs until {@link Job#submit()}.
     *
     * @param key  the single-flight identity: a newer submission replaces a waiting job and supersedes a running one
     * @param lane how urgent it is
     * @param work run on a worker, handed a context to poll and report progress on
     */
    public <T> Job<T> job(CgJobKey key, CgJobLane lane, CgJob.Work<? extends T> work) {
        return new Job<>(route(), key, lane, work);
    }

    /** A job being described. See {@link JobScheduler#job}. */
    public static final class Job<T> {
        private final JobScheduler scheduler;
        private final CgJobKey key;
        private final CgJobLane lane;
        private final CgJob.Work<? extends T> work;
        private long debounceMillis;
        @Nullable
        private Consumer<? super T> onDone;

        private Job(JobScheduler scheduler, CgJobKey key, CgJobLane lane, CgJob.Work<? extends T> work) {
            this.scheduler = scheduler;
            this.key = key;
            this.lane = lane;
            this.work = work;
        }

        /** Waits this long after the last submission for its key before starting. */
        public Job<T> debounce(long millis) {
            this.debounceMillis = Math.max(0, millis);
            return this;
        }

        /** Handed the result during {@link JobScheduler#drain()}, before the reply's listeners. */
        public Job<T> onDone(@Nullable Consumer<? super T> consumer) {
            this.onDone = consumer;
            return this;
        }

        /** Starts it. Cancelling the returned job is {@link JobScheduler#cancel(CgJobKey)}. */
        public CgJob<T> submit() {
            return scheduler.start(this);
        }
    }

    private <T> CgJob<T> start(Job<T> description) {
        if (this == shared && asynchronousDocuments.get() > 0 && !undrainedReported) {
            undrainedReported = true;
            CrystalGuiCore.LOGGER.warn("[cgui] job {} was posted to the process-wide scheduler from {}, which no "
                    + "asynchronous document drains: post it from the document's sequence", description.key,
                    Thread.currentThread().getName());
        }
        CgJob<T> job = CgJob.on(loop).keyed(description.key).lane(description.lane)
                .debounce(description.debounceMillis).run(description.work);
        if (description.onDone != null) job.then(description.onDone);
        job.failed(thrown -> reportFailure(job, thrown));
        if (!disposed) live.put(description.key, job);
        return job;
    }

    /**
     * Logs it, and raises a notification for a job that announced itself with {@link CgProgress#begin}: an
     * unannounced one runs per keystroke, and a broken one would notify per character.
     */
    private void reportFailure(CgJob<?> job, Throwable failure) {
        CrystalGuiCore.LOGGER.warn("job {} failed", job, failure);
        CgProgressState announced = job.progress();
        if (announced == null) return;
        String reason = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        Notifications.show(Notification.error(announced.what() + " failed: " + reason));
    }

    /** Drops a waiting job for this key and asks a running one to stop; nothing of it lands. */
    public void cancel(CgJobKey key) {
        JobScheduler owner = route();
        if (owner != this) {
            owner.cancel(key);
            return;
        }
        // Marked, not removed: the work runs until it polls, and a row gone on the click would say it had stopped.
        Tracked shown = tracked.get(key);
        if (shown != null) shown.cancelRequested = true;
        loop.cancel(key.owner(), key.kind());
    }

    /** {@link #cancel} for every job about {@code owner} — what closing a document calls. */
    public void cancelAll(Object owner) {
        JobScheduler routed = route();
        if (routed != this) {
            routed.cancelAll(owner);
            return;
        }
        for (Map.Entry<CgJobKey, Tracked> entry : tracked.entrySet()) {
            if (entry.getKey().owner() == owner) entry.getValue().cancelRequested = true;
        }
        loop.cancelAll(owner);
    }

    // ── The heartbeat ───────────────────────────────────────────────────────────────────────────

    /**
     * Delivers finished results and starts due work. <b>Call once per frame or tick, on the thread that owns this
     * scheduler</b> — the UI thread for {@link #shared()}, a server's for its own.
     *
     * @return whether anything remains outstanding, a row on screen included
     */
    public boolean drain() {
        loop.drain();
        updateTracked(loop.nowMillis());
        return loop.pendingCount() > 0 || !tracked.isEmpty();
    }

    /** Cancels everything and refuses new work. Does not touch the executor it was handed. */
    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        loop.close();
        live.clear();
        tracked.clear();
    }

    /** Jobs submitted and not yet ended: waiting, running, or finished and not yet drained. */
    public int pendingCount() {
        return loop.pendingCount();
    }

    // ── Progress, and what is on screen ─────────────────────────────────────────────────────────

    /** How long a job must have been running before it earns a place in the chrome. @see #active() */
    public static final long DEFAULT_SHOW_AFTER_MILLIS = 400L;

    /** How long it stays once it has appeared, however fast it then finishes. @see #active() */
    public static final long DEFAULT_MINIMUM_VISIBLE_MILLIS = 500L;

    private long showAfterMillis = DEFAULT_SHOW_AFTER_MILLIS;
    private long minimumVisibleMillis = DEFAULT_MINIMUM_VISIBLE_MILLIS;

    private final Map<CgJobKey, Tracked> tracked = new LinkedHashMap<>();

    public JobScheduler setShowAfterMillis(long millis) {
        this.showAfterMillis = Math.max(0, millis);
        return this;
    }

    public JobScheduler setMinimumVisibleMillis(long millis) {
        this.minimumVisibleMillis = Math.max(0, millis);
        return this;
    }

    /**
     * What the chrome should be drawing, most recently begun first.
     *
     * <pre>{@code
     * for (ActiveJob row : JobScheduler.shared().active()) {
     *     bar.show(row.state().summary(), row.state().fraction());
     *     cancelButton.onClick(() -> JobScheduler.shared().cancel(row.key()));
     * }
     * }</pre>
     *
     * <ul>
     *   <li>A job appears once it has called {@link CgProgress#begin} and run for {@link #DEFAULT_SHOW_AFTER_MILLIS},
     *       so per-keystroke work never strobes the chrome.</li>
     *   <li>Once shown it stays {@link #DEFAULT_MINIMUM_VISIBLE_MILLIS}, however soon it finishes.</li>
     *   <li>Ordered by when each began, never by progress: rows with buttons must not move under the cursor.</li>
     * </ul>
     */
    public List<ActiveJob> active() {
        JobScheduler owner = route();
        if (owner != this) return owner.active();
        if (tracked.isEmpty()) return List.of();
        List<ActiveJob> shown = new ArrayList<>(tracked.size());
        for (Map.Entry<CgJobKey, Tracked> entry : tracked.entrySet()) {
            Tracked value = entry.getValue();
            if (value.shownAtMillis == 0L || value.state == null) continue;
            shown.add(new ActiveJob(entry.getKey(), value.state, value.cancelRequested));
        }
        shown.sort(Comparator.comparingLong((ActiveJob job) -> job.state().begunAtMillis()).reversed());
        return List.copyOf(shown);
    }

    /** Folds the running jobs into what is on screen. A shown row outlives its job by the minimum. */
    private void updateTracked(long now) {
        for (Iterator<Map.Entry<CgJobKey, CgJob<?>>> it = live.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<CgJobKey, CgJob<?>> entry = it.next();
            CgJob<?> job = entry.getValue();
            boolean running = job.isRunning();
            if (!running && job.isDone()) {
                it.remove();
                continue;
            }
            CgProgressState state = running ? job.progress() : null;
            if (state == null) continue;
            Tracked value = tracked.computeIfAbsent(entry.getKey(), key -> new Tracked());
            value.state = state;
            if (value.shownAtMillis == 0L && now - state.begunAtMillis() >= showAfterMillis) value.shownAtMillis = now;
        }
        tracked.entrySet().removeIf(entry -> {
            CgJob<?> job = live.get(entry.getKey());
            if (job != null && job.isRunning()) return false;
            Tracked value = entry.getValue();
            return value.shownAtMillis == 0L || now - value.shownAtMillis >= minimumVisibleMillis;
        });
    }

    /** Owned by the draining thread and never handed out — {@link ActiveJob} is what escapes. */
    private static final class Tracked {
        private CgProgressState state;
        private long shownAtMillis;
        private boolean cancelRequested;
    }
}
