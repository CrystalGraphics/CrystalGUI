package com.crystalgui.core.async;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.dispose.Disposable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Runs work off the UI thread and hands the answers back on it.
 *
 * <p>The engine had no such thing: until this class, the only executor anywhere in {@code core/} was
 * SVG preloading, and every other slow thing ran on the frame. Compiling a script, scanning a classpath
 * and reparsing a file are all far past a frame budget, so all three needed the same four properties —
 * and three separate ad-hoc answers to that is how a codebase ends up with three thread pools that
 * cannot see each other and starve one another on a four-core machine.</p>
 *
 * <h3>The frame tick is the heartbeat</h3>
 * <p><b>Every decision this class makes happens on the UI thread, inside {@link #drain()}.</b> Debounce
 * windows are evaluated there against the injected clock; jobs are promoted into the executor there;
 * results are delivered there. Only the work itself runs elsewhere.</p>
 *
 * <p>That is what makes the whole thing testable — there are no timer threads, no scheduled futures and
 * no sleeping, so a test with a same-thread executor and a hand-cranked clock observes the identical
 * code path the real editor does. It is also what keeps the concurrency surface to exactly one object:
 * the completion queue. Nothing else here is touched by two threads, so nothing else needs a lock, and a
 * widget never sees a background thread at all.</p>
 *
 * <p>The cost is that a job starts on a frame boundary — up to ~16ms of latency at 60fps. That is inside
 * every budget in the plan (the tightest is 100ms, for completion), and it buys determinism, which is
 * the trade every editor makes here.</p>
 *
 * <h3>Single-flight, keyed</h3>
 * <p>Submitting a {@link JobKey} that is already waiting <b>replaces</b> it; submitting one that is
 * already running <b>supersedes</b> it — the runner is asked to cancel, its result is dropped when it
 * arrives, and the replacement is queued. So a burst of keystrokes leaves exactly one live job per key
 * instead of one per keystroke, and convergence is structural rather than something each caller has to
 * arrange: the last submission always wins.</p>
 *
 * <p><b>A superseded job's result is discarded even if it never polled for cancellation.</b> That is
 * deliberate — it means correctness does not depend on well-behaved job bodies, only responsiveness
 * does.</p>
 *
 * <h3>What this class does NOT know about</h3>
 * <p>Document versions. Staleness against a document is the consumer's policy — some results are
 * discarded, some are kept and adjusted, some are kept per-line — and a scheduler that tried to decide
 * that centrally would have to understand every consumer. It guarantees only that a superseded job's
 * result never lands; comparing a version stamp is the caller's job, and the caller is the only one who
 * knows which of the three answers is right for it.</p>
 */
public final class JobScheduler implements Disposable {

    /** How long a job may wait before it is promoted regardless of lane. See {@link JobLane#BACKGROUND}. */
    public static final long DEFAULT_STARVATION_GUARD_MILLIS = 2_000L;

    private final Executor executor;
    private final LongSupplier clockMillis;
    private final int maxConcurrent;
    private long starvationGuardMillis = DEFAULT_STARVATION_GUARD_MILLIS;

    /**
     * Waiting jobs, at most one per key. Insertion-ordered so equal-priority ties break FIFO — without
     * that, two jobs that became due on the same frame would run in hash order, which is stable enough to
     * look deliberate and arbitrary enough to be wrong.
     */
    private final Map<JobKey, Waiting<?>> waiting = new LinkedHashMap<>();

    /** In-flight jobs, at most one per key. */
    private final Map<JobKey, Running> running = new HashMap<>();

    /**
     * Bumped on every submission for a key. A completion carrying an older number has been superseded
     * and is dropped — which is what makes supersession work for jobs that never poll.
     */
    private final Map<JobKey, Integer> generations = new HashMap<>();

    /** The one thing here touched by two threads. Workers append; {@link #drain()} consumes. */
    private final Queue<Completion<?>> completed = new ConcurrentLinkedQueue<>();

    private boolean disposed;

    /** A scheduler on a small shared daemon pool and the system clock — what the application uses. */
    public JobScheduler() {
        this(defaultExecutor(), System::currentTimeMillis, defaultConcurrency());
    }

    /**
     * The application-wide scheduler, created on first use.
     *
     * <p>One pool, not one per feature — three pools compete for the same cores and none of them knows
     * it. Tests construct their own instead, which is what the injecting constructor is for; this is the
     * wiring for everything that just wants the shared one.</p>
     *
     * <p>Guarded by {@link #hasShared()} at the drain site for the same reason {@code CgUiPaintContext}
     * has {@code hasInstance()}: merely asking whether there is work to do must not be what spawns a
     * thread pool. A headless process that never schedules anything never creates one.</p>
     */
    public static JobScheduler shared() {
        if (shared == null) shared = new JobScheduler();
        return shared;
    }

    /** Whether {@link #shared()} has been created — checked before draining, so asking never constructs. */
    public static boolean hasShared() {
        return shared != null;
    }

    private static JobScheduler shared;

    /**
     * @param executor      where work runs. A same-thread executor makes every test deterministic
     * @param clockMillis   the time source debounce is measured against — an input, never read directly,
     *                      for the reason {@code TextBuffer} already records about {@code TransitionEngine}
     * @param maxConcurrent how many jobs may be in flight at once
     */
    public JobScheduler(Executor executor, LongSupplier clockMillis, int maxConcurrent) {
        this.executor = executor;
        this.clockMillis = clockMillis;
        this.maxConcurrent = Math.max(1, maxConcurrent);
    }

    /** Leaves at least two cores for the frame, and never fewer than one worker. */
    private static int defaultConcurrency() {
        return Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
    }

    private static Executor defaultExecutor() {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "cgui-job-" + counter.incrementAndGet());
            // Daemon: a pending classpath scan must never be the reason the game will not exit.
            thread.setDaemon(true);
            // Below the render thread on purpose -- this pool exists to use the cores the frame is not.
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        };
        return Executors.newFixedThreadPool(defaultConcurrency(), factory);
    }

    public JobScheduler setStarvationGuardMillis(long millis) {
        this.starvationGuardMillis = Math.max(0, millis);
        return this;
    }

    // ── Submitting ──────────────────────────────────────────────────────────────────────────────

    /**
     * Describes a job. Nothing is scheduled until {@link Job#submit()}.
     *
     * @param key  what this work is about and what it is — the single-flight identity
     * @param lane how urgent it is
     * @param work the actual computation, run off the UI thread, handed a {@link JobContext} to poll
     */
    public <T> Job<T> job(JobKey key, JobLane lane, Function<JobContext, T> work) {
        return new Job<>(this, key, lane, work);
    }

    /** A job being described. See {@link JobScheduler#job}. */
    public static final class Job<T> {
        private final JobScheduler scheduler;
        private final JobKey key;
        private final JobLane lane;
        private final Function<JobContext, T> work;
        private long debounceMillis;
        private Consumer<T> onDone = result -> { };

        private Job(JobScheduler scheduler, JobKey key, JobLane lane, Function<JobContext, T> work) {
            this.scheduler = scheduler;
            this.key = key;
            this.lane = lane;
            this.work = work;
        }

        /**
         * Wait this long after the <em>last</em> submission before starting.
         *
         * <p>Re-submitting inside the window pushes the deadline out, so a run of keystrokes produces one
         * job at the end rather than one per key. A property of the kind of work, not of the caller: a
         * reparse wants 0 (just get off the frame), diagnostics want ~300ms, an index wants to run once.</p>
         */
        public Job<T> debounce(long millis) {
            this.debounceMillis = Math.max(0, millis);
            return this;
        }

        /** Handed the result on the UI thread, during {@link JobScheduler#drain()}. Never called if superseded. */
        public Job<T> onDone(Consumer<T> consumer) {
            this.onDone = consumer == null ? result -> { } : consumer;
            return this;
        }

        /** Queues it. Replaces any waiting job with the same key and supersedes any running one. */
        public void submit() {
            scheduler.enqueue(key, lane, debounceMillis, work, onDone);
        }
    }

    private <T> void enqueue(JobKey key, JobLane lane, long debounceMillis,
                             Function<JobContext, T> work, Consumer<T> onDone) {
        if (disposed) return;

        int generation = generations.merge(key, 1, Integer::sum);

        // Supersede the in-flight one. It is asked to stop, but its result is dropped on generation
        // regardless of whether it ever asks -- see the class note.
        Running inFlight = running.get(key);
        if (inFlight != null) inFlight.context.cancel();

        long now = clockMillis.getAsLong();
        // put() replaces, which IS the debounce reset: an earlier deadline is discarded with the entry
        // that carried it.
        waiting.put(key, new Waiting<>(key, lane, generation, now, now + debounceMillis, work, onDone));
    }

    /**
     * Drops any waiting job for this key and asks a running one to stop.
     *
     * <p>Bumps the generation, so a result already in flight is discarded when it lands.</p>
     */
    public void cancel(JobKey key) {
        // MARKED, not removed. Cancellation is cooperative, so there is a real gap between asking and the
        // worker noticing -- and a row that vanished on the click would claim the work had stopped when it
        // had not. @see ActiveJob#cancelRequested()
        Tracked shown = tracked.get(key);
        if (shown != null) shown.cancelRequested = true;
        generations.merge(key, 1, Integer::sum);
        waiting.remove(key);
        Running inFlight = running.get(key);
        if (inFlight != null) inFlight.context.cancel();
    }

    /**
     * {@link #cancel} for every job about {@code owner} — what closing a document calls.
     *
     * <p>Without it, closing a file leaves its reparse and its diagnostics running against a buffer
     * nobody is looking at, and their results arrive for an editor that no longer exists.</p>
     */
    public void cancelAll(Object owner) {
        List<JobKey> victims = new ArrayList<>();
        for (JobKey key : waiting.keySet()) if (key.owner() == owner) victims.add(key);
        for (JobKey key : running.keySet()) if (key.owner() == owner) victims.add(key);
        for (JobKey key : victims) cancel(key);
    }

    // ── The heartbeat ───────────────────────────────────────────────────────────────────────────

    /**
     * Delivers finished results and starts due work. <b>Call once per frame, on the UI thread.</b>
     *
     * <p><b>Deliver, promote, deliver.</b> The first pass hands back what finished since the last frame.
     * The second exists because a job may complete <em>during</em> promotion — always, on a same-thread
     * executor; occasionally, on a real pool when the work is short — and without it such a result would
     * sit in the queue for a whole extra frame despite being ready before {@code drain} returned. One
     * extra poll of an empty queue is the entire cost.</p>
     *
     * <p>It is deliberately two passes and not a loop to quiescence: a completion handler that re-submits
     * an undebounced job is an ordinary shape ("recompute after applying"), and a loop would let that
     * spin the frame forever rather than settling on the next one.</p>
     *
     * @return whether anything remains outstanding, so a caller may stop ticking when idle
     */
    public boolean drain() {
        deliverCompleted();
        promoteDue();
        deliverCompleted();
        // LAST, and on this thread. Every visibility decision -- has it earned a place on screen, has it
        // been there long enough to leave -- is made here, where the rest of this class's decisions are
        // made, so none of it can be reached from a worker. @see #active()
        updateTracked(clockMillis.getAsLong());
        return !waiting.isEmpty() || !running.isEmpty() || !tracked.isEmpty();
    }

    private void deliverCompleted() {
        Completion<?> completion;
        while ((completion = completed.poll()) != null) {
            running.remove(completion.key);
            // The generation check is the whole of supersession. A result whose key has been re-submitted
            // describes a question nobody is asking any more.
            Integer current = generations.get(completion.key);
            if (current == null || current != completion.generation) continue;
            if (completion.failure != null) {
                CrystalGuiCore.LOGGER.warn("job {} failed", completion.key, completion.failure);
                continue;
            }
            try {
                completion.deliver();
            } catch (RuntimeException failed) {
                // A throwing consumer must not take the frame down with it, and must not stop the rest of
                // this tick's results being delivered.
                CrystalGuiCore.LOGGER.warn("job {} completion handler failed", completion.key, failed);
            }
        }
    }

    private void promoteDue() {
        if (waiting.isEmpty() || running.size() >= maxConcurrent) return;

        long now = clockMillis.getAsLong();
        List<Waiting<?>> due = new ArrayList<>();
        for (Waiting<?> candidate : waiting.values()) {
            // Never two in flight for one key: the running one is already superseded and about to be
            // dropped, and starting its replacement alongside it would double the work for no gain.
            if (now >= candidate.dueAt && !running.containsKey(candidate.key)) due.add(candidate);
        }
        if (due.isEmpty()) return;

        due.sort(startOrder(now));

        Iterator<Waiting<?>> iterator = due.iterator();
        while (iterator.hasNext() && running.size() < maxConcurrent) {
            Waiting<?> next = iterator.next();
            if (!hasSlotFor(next.lane)) continue;
            waiting.remove(next.key);
            start(next);
        }
    }

    /**
     * <b>{@link JobLane#BACKGROUND} may not take the last slot.</b>
     *
     * <p>{@code maxConcurrent} is scheduler-wide, so a long job holds a slot for its whole life — and
     * background work is precisely the long kind. A 16 MB download and a classpath index are minutes
     * between them, and an analysis wants a slot on the next keystroke.</p>
     *
     * <p>The starvation guard does not help here and it is worth saying why, because it looks as though it
     * should: it promotes a job that has been <em>waiting</em> too long, and cannot evict one that is
     * <em>running</em>. A queue of interactive work behind two downloads is not starved by ordering, it is
     * starved by occupancy.</p>
     *
     * <p>So background is capped one below the pool. With a pool of one it may still run — a machine that
     * can only do one thing at a time should do the thing that was asked for rather than nothing.</p>
     */
    private boolean hasSlotFor(JobLane lane) {
        if (lane != JobLane.BACKGROUND || maxConcurrent <= 1) return true;
        int backgroundRunning = 0;
        for (Running inFlight : running.values()) {
            if (inFlight.lane() == JobLane.BACKGROUND) backgroundRunning++;
        }
        return backgroundRunning < maxConcurrent - 1;
    }

    /**
     * Starved first, then by lane, then FIFO.
     *
     * <p>The guard is what stops {@link JobLane#BACKGROUND} being theoretical: a document being typed in
     * produces a steady stream of higher-lane work, so a strict ordering alone means an index queued
     * behind it is never built — and the symptom is not a hang but completion quietly never learning
     * about unimported types, which reads as a missing feature rather than a scheduling bug.</p>
     */
    private Comparator<Waiting<?>> startOrder(long now) {
        return Comparator
                .comparing((Waiting<?> job) -> !isStarved(job, now))
                .thenComparingInt(job -> job.lane.ordinal())
                .thenComparingLong(job -> job.submittedAt);
    }

    private boolean isStarved(Waiting<?> job, long now) {
        return now - job.submittedAt >= starvationGuardMillis;
    }

    private <T> void start(Waiting<T> job) {
        JobContext context = new JobContext(clockMillis);
        running.put(job.key, new Running(context, job.lane));
        executor.execute(() -> {
            T result = null;
            Throwable failure = null;
            try {
                result = job.work.apply(context);
            } catch (JobContext.JobCancelledException cancelled) {
                // Expected control flow, not an error: the job noticed it was superseded and stopped. The
                // generation check would have dropped the result anyway; this just saved the rest of the work.
                failure = null;
            } catch (Throwable thrown) {
                failure = thrown;
            }
            completed.add(new Completion<>(job.key, job.generation, result, failure, job.onDone));
        });
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────────────────────────

    /**
     * Stops accepting work and asks everything in flight to stop.
     *
     * <p>Does <b>not</b> shut the executor down — it may be shared or injected, and a scheduler does not
     * own what it was handed. Whoever supplied it owns it; the default pool is daemon-threaded so it
     * cannot keep the process alive either way.</p>
     */
    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        waiting.clear();
        for (Running inFlight : running.values()) inFlight.context.cancel();
        running.clear();
        generations.clear();
        completed.clear();
    }

    // ── Test and diagnostic surface ─────────────────────────────────────────────────────────────

    /** Jobs described but not yet started. */
    public int waitingCount() {
        return waiting.size();
    }

    /** Jobs in flight. */
    public int runningCount() {
        return running.size();
    }

    // ── Progress, and what is on screen ─────────────────────────────────────────────────────────

    /** How long a job must have been running before it earns a place in the chrome. @see #active() */
    public static final long DEFAULT_SHOW_AFTER_MILLIS = 400L;

    /** How long it stays once it has appeared, however fast it then finishes. @see #active() */
    public static final long DEFAULT_MINIMUM_VISIBLE_MILLIS = 500L;

    private long showAfterMillis = DEFAULT_SHOW_AFTER_MILLIS;
    private long minimumVisibleMillis = DEFAULT_MINIMUM_VISIBLE_MILLIS;

    private final Map<JobKey, Tracked> tracked = new LinkedHashMap<>();

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
     * <h3>Two policies, and both exist to stop the status bar flickering</h3>
     *
     * <p><b>A job appears only once it has called {@link Progress#begin}</b>, and only after
     * {@link #DEFAULT_SHOW_AFTER_MILLIS}. An analysis runs on every keystroke; if everything appeared the
     * chrome would strobe continuously, and most work finishes before anyone could read its name.</p>
     *
     * <p><b>And once it has appeared it stays</b> for {@link #DEFAULT_MINIMUM_VISIBLE_MILLIS}, so a job
     * that finishes just after crossing the delay does not flash in and out.</p>
     *
     * <p>Most recently begun first, not by how far along: ordering by progress reorders rows under the
     * cursor, which is the one thing a list with buttons in it must not do.</p>
     *
     * <p>Safe to call every frame — it allocates one list and reads volatile references. The decisions
     * were all made in {@link #drain()}.</p>
     */
    public List<ActiveJob> active() {
        if (tracked.isEmpty()) return List.of();
        List<ActiveJob> shown = new ArrayList<>(tracked.size());
        for (Map.Entry<JobKey, Tracked> entry : tracked.entrySet()) {
            Tracked value = entry.getValue();
            if (value.shownAtMillis == 0L || value.state == null) continue;
            shown.add(new ActiveJob(entry.getKey(), value.state, value.cancelRequested));
        }
        shown.sort(Comparator.comparingLong((ActiveJob job) -> job.state().begunAtMillis()).reversed());
        return List.copyOf(shown);
    }

    /**
     * Folds this frame's running jobs into what is on screen.
     *
     * <p>A tracked entry outlives its job deliberately: {@link #DEFAULT_MINIMUM_VISIBLE_MILLIS} is
     * measured from when the row appeared, so the record has to survive the job it describes. One that was
     * never shown leaves immediately — there is nothing for a minimum to protect.</p>
     */
    private void updateTracked(long now) {
        for (Map.Entry<JobKey, Running> entry : running.entrySet()) {
            ProgressState state = entry.getValue().context().progressState();
            if (state == null) continue;
            Tracked value = tracked.computeIfAbsent(entry.getKey(), key -> new Tracked());
            value.state = state;
            value.finishedAtMillis = 0L;
            if (value.shownAtMillis == 0L && now - state.begunAtMillis() >= showAfterMillis) {
                value.shownAtMillis = now;
            }
        }
        tracked.entrySet().removeIf(entry -> {
            Tracked value = entry.getValue();
            if (running.containsKey(entry.getKey())) return false;
            if (value.shownAtMillis == 0L) return true;
            if (value.finishedAtMillis == 0L) value.finishedAtMillis = now;
            return now - value.shownAtMillis >= minimumVisibleMillis;
        });
    }

    /** Mutable, owned by the UI thread, and never handed out — {@link ActiveJob} is what escapes. */
    private static final class Tracked {
        private ProgressState state;
        private long shownAtMillis;
        private long finishedAtMillis;
        private boolean cancelRequested;
    }

    // ── Internals ───────────────────────────────────────────────────────────────────────────────

    private record Waiting<T>(JobKey key, JobLane lane, int generation, long submittedAt, long dueAt,
                              Function<JobContext, T> work, Consumer<T> onDone) {
    }

    /** The lane is carried so {@link #hasSlotFor} can count occupancy without a second map. */
    private record Running(JobContext context, JobLane lane) {
    }

    private record Completion<T>(JobKey key, int generation, T result, Throwable failure,
                                 Consumer<T> onDone) {
        void deliver() {
            onDone.accept(result);
        }
    }
}
