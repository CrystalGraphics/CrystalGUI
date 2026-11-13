package com.crystalgui.core.async;

import com.crystalgraphics.job.CgHostThread;
import com.crystalgraphics.platform.service.CgGameThreads;
import com.crystalgraphics.job.CgJobLoop;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.variable.CgVar;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.trace.UiTrace;

import javax.annotation.Nullable;
import java.util.Queue;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Tasks run in the order posted, never two at once, on whichever pool thread is free: Chromium's sequence, and what
 * a document runs on when it is off the render thread (plan engine-threaded-ui §2.1).
 *
 * <pre>{@code
 * UiSequence sequence = UiSequence.create("desktop");
 * sequence.execute(() -> document.frame(delta, w, h));        // from the render thread
 * // A document's driver owns it on its sequence: UIDocument.own, with an Owner over isCurrent and execute.
 * }</pre>
 *
 * <p>Each sequence has its own {@link JobScheduler}: {@link JobScheduler#shared()} answers it inside a task, and its
 * loop is {@link CgJobLoop#current()} there, so a job's {@code onDone} — or a bare {@code CgJob.run}'s {@code then} —
 * comes back to the sequence that started it, in that sequence's next drain.</p>
 *
 * <ul>
 *   <li>A task that throws is logged and the sequence carries on.</li>
 *   <li>{@link #close()} drops what is queued and refuses what is posted after; a task already running finishes.</li>
 *   <li>Forty idle sequences cost forty queues: the threads are a shared pool.</li>
 * </ul>
 */
public final class UiSequence implements Executor {

    /** How many tasks one turn runs before giving its pool thread back, so one busy sequence cannot starve the rest. */
    private static final int TASKS_PER_TURN = 64;

    private static final ThreadLocal<UiSequence> CURRENT = new ThreadLocal<>();

    /** Off by default. Read once: which sequences exist is decided at startup. */
    private static final CgVar.Bool SINGLE_SEQUENCE = CgVar.bool("crystalgui.ui.singleSequence", false,
            CgVar.DEV | CgVar.RESTART,
            "Runs every document and the services on one sequence. A bug that goes away with it is a threading bug.");

    static {
        // A CgHostThread answer goes back to the sequence that asked, so its then() may touch the tree.
        CgHostThread.answerThrough(() -> {
            UiSequence sequence = CURRENT.get();
            return sequence == null ? null : sequence.asBinding();
        });
    }

    private final String name;
    /** The zone a turn is recorded under, so the profiler draws this sequence a lane of its own. */
    private final String zone;
    private final Executor pool;
    /** The services sequence, or the single sequence of the switch: outlives every document, so close leaves it. */
    private final boolean shared;
    private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private volatile boolean closed;
    /** Set by {@link #abort}: the next checkpoint on this sequence throws. */
    private volatile boolean aborting;
    /** Set by {@link #abandon}: a thread may still hold the turn, so nothing waits for it. */
    private volatile boolean abandoned;
    /** The thread running this sequence's work now, or null between turns. */
    @Nullable
    private volatile Thread running;
    private volatile JobScheduler jobs;

    private UiSequence(String name, Executor pool, boolean shared) {
        this.name = name;
        this.zone = ZONE_PREFIX + name;
        this.pool = pool;
        this.shared = shared;
    }

    /** What a turn's zone starts with: how the profiler knows a sequence's lane. */
    public static final String ZONE_PREFIX = "sequence:";

    /** A sequence on the shared UI pool. */
    public static UiSequence create(String name) {
        return new UiSequence(name, Pool.EXECUTOR, false);
    }

    /** A sequence on {@code pool}: a test's own executor, or a dedicated thread an application asked for. */
    public static UiSequence create(String name, Executor pool) {
        return new UiSequence(name, pool, false);
    }

    /**
     * A document's sequence: one of its own, or with {@code -Dcrystalgui.ui.singleSequence=true} the one sequence
     * every document and the services share.
     *
     * <pre>{@code
     * UiSequence sequence = UiSequence.forDocument("crystalgui:editor");
     * sequence.close();      // when the document closes; the shared one keeps running
     * }</pre>
     */
    public static UiSequence forDocument(String name) {
        return SINGLE_SEQUENCE.get() ? Shared.SINGLE : create(name);
    }

    /**
     * The services sequence: where owned services live (the workspace, notifications, the clipboard), and where a
     * command invoked with no document runs. It never lays out or paints, so a document's work never holds it up.
     *
     * <pre>{@code
     * UiSequence.services().execute(() -> notifications.add(posted));
     * }</pre>
     */
    public static UiSequence services() {
        return SINGLE_SEQUENCE.get() ? Shared.SINGLE : Shared.SERVICES;
    }

    /** Built on first use: a dedicated server that never asks makes neither. */
    private static final class Shared {
        static final UiSequence SERVICES = new UiSequence("services", Pool.EXECUTOR, true);
        static final UiSequence SINGLE = new UiSequence("single", Pool.EXECUTOR, true);
    }

    /** The sequence running the calling task, or null on any thread outside one. */
    @Nullable
    public static UiSequence current() {
        return CURRENT.get();
    }

    public boolean isCurrent() {
        return CURRENT.get() == this;
    }

    private CgGameThreads.Binding asBinding() {
        return new CgGameThreads.Binding() {
            @Override
            public void execute(Runnable work) {
                UiSequence.this.execute(work);
            }

            @Override
            public boolean isCurrent() {
                return UiSequence.this.isCurrent();
            }
        };
    }

    public String name() {
        return name;
    }

    /** Posts {@code task}, to run after everything posted before it. */
    @Override
    public void execute(Runnable task) {
        if (closed) return;
        tasks.add(task);
        schedule();
    }

    /**
     * Runs {@code task} on the calling thread <b>as this sequence</b>, after everything posted before it, and returns
     * when it has run. Waits for a turn already running on the pool; none starts until this returns.
     *
     * <pre>{@code
     * sequence.runNow(() -> document.frame(delta, w, h));   // the render thread, in lockstep with the sequence
     * }</pre>
     *
     * <p>What a host does while recording may not leave the render thread yet: ownership is the sequence's, so the tree
     * refuses every other caller, while the work itself stays where the GL context is. Called from inside one of this
     * sequence's tasks it simply runs {@code task}.</p>
     */
    public void runNow(Runnable task) {
        runNow(task, Long.MAX_VALUE);
    }

    /**
     * {@link #runNow(Runnable)} that gives up waiting for a running turn after {@code timeoutNanos}: answers whether
     * {@code task} ran (also true when the sequence was abandoned, which nothing waits for). What closing a document
     * uses, so a document stuck in a loop cannot hold its closer.
     */
    public boolean runNow(Runnable task, long timeoutNanos) {
        if (isCurrent()) {
            task.run();
            return true;
        }
        if (abandoned) return true;
        // CLAIMED THE WAY A TURN CLAIMS IT, so a turn and this can never overlap.
        long started = System.nanoTime();
        while (!scheduled.compareAndSet(false, true)) {
            if (abandoned) return true;
            if (System.nanoTime() - started >= timeoutNanos) return false;
            LockSupport.parkNanos(20_000L);
        }
        UiSequence outer = CURRENT.get();
        CURRENT.set(this);
        running = Thread.currentThread();
        try (CgJobLoop.Scope ignored = jobs().loop().enter()) {
            for (Runnable queued; !closed && !endedByAbort() && (queued = tasks.poll()) != null; ) {
                try {
                    queued.run();
                } catch (ApplicationAborted unwound) {
                    unwind();
                } catch (RuntimeException | Error failed) {
                    CrystalGuiCore.LOGGER.error("[cgui] a task on sequence '{}' threw", name, failed);
                    faulted(failed);
                }
            }
            try {
                task.run();
            } catch (ApplicationAborted unwound) {
                unwind();
            }
        } finally {
            running = null;
            CURRENT.set(outer);
            scheduled.set(false);
        }
        if (!tasks.isEmpty() && !closed) schedule();
        return true;
    }

    /** The thread running this sequence's work at this moment, or null when it is idle: what a hang report names. */
    @Nullable
    public Thread runningThread() {
        return running;
    }

    /**
     * Rung 1 of closing a document: its next {@linkplain #checkpoint checkpoint} throws {@link ApplicationAborted}, the
     * turn unwinds, and the sequence closes. A no-op on the shared sequences.
     */
    public void abort() {
        if (!shared) aborting = true;
    }

    /** Whether {@link #abort} was called. */
    public boolean aborting() {
        return aborting;
    }

    /**
     * The engine's checkpoint: throws {@link ApplicationAborted} when the calling thread is running a sequence that was
     * {@linkplain #abort aborted}. One thread-local read and one volatile read, so it is cheap enough per listener and
     * per node.
     *
     * <pre>{@code
     * for (Listener each : listeners) {
     *     UiSequence.checkpoint();
     *     each.run();
     * }
     * }</pre>
     */
    public static void checkpoint() {
        UiSequence sequence = CURRENT.get();
        if (sequence != null && sequence.aborting) throw new ApplicationAborted(sequence.name);
    }

    /** Rung 2: interrupts the thread running this sequence, which ends a wait, a sleep or an interruptible lock. */
    public void interrupt() {
        Thread thread = running;
        if (thread != null && !shared) thread.interrupt();
    }

    /**
     * Rung 3: gives the sequence up. Its queue is dropped and nothing more is accepted; the thread stuck in it is
     * interrupted and left at minimum priority, and the pool serves the next turn on another thread.
     */
    public void abandon() {
        if (shared) return;
        closed = true;
        abandoned = true;
        tasks.clear();
        Thread thread = running;
        if (thread != null) {
            thread.setPriority(Thread.MIN_PRIORITY);
            thread.interrupt();
        }
    }

    /** Drops what is queued and refuses what comes after. The services sequence and the single sequence stay open. */
    public void close() {
        if (shared) return;
        closed = true;
        tasks.clear();
    }

    public boolean isClosed() {
        return closed;
    }

    /** This sequence's scheduler, built on first use. What {@link JobScheduler#shared()} answers inside a task. */
    public JobScheduler jobs() {
        JobScheduler mine = jobs;
        if (mine == null) {
            synchronized (this) {
                if (jobs == null) jobs = new JobScheduler();
                mine = jobs;
            }
        }
        return mine;
    }

    /** Whether {@link #jobs()} has been built, without building it. */
    boolean hasJobs() {
        return jobs != null;
    }

    private void schedule() {
        if (scheduled.compareAndSet(false, true)) pool.execute(this::runTurn);
    }

    private void runTurn() {
        UiSequence outer = CURRENT.get();
        CURRENT.set(this);
        running = Thread.currentThread();
        long turn = CgTrace.stamp(UiTrace.FRAME);
        try (CgJobLoop.Scope ignored = jobs().loop().enter()) {
            for (int i = 0; i < TASKS_PER_TURN && !closed && !endedByAbort(); i++) {
                Runnable task = tasks.poll();
                if (task == null) break;
                try {
                    task.run();
                } catch (ApplicationAborted unwound) {
                    unwind();
                } catch (RuntimeException | Error failed) {
                    CrystalGuiCore.LOGGER.error("[cgui] a task on sequence '{}' threw", name, failed);
                    faulted(failed);
                }
            }
        } finally {
            CgTrace.zoneDone(UiTrace.FRAME, zone, turn);
            running = null;
            CURRENT.set(outer);
            scheduled.set(false);
        }
        // Posted while this turn ran, or left over from it: another turn, which may land on another thread.
        if (!tasks.isEmpty() && !closed) schedule();
    }

    /** Whether an abort has ended the sequence between tasks, which closes it as a checkpoint would. */
    private boolean endedByAbort() {
        if (!aborting) return false;
        unwind();
        return true;
    }

    /** An aborted task reached its checkpoint: the sequence is over. */
    private volatile Consumer<Throwable> faults;

    /**
     * Hears every exception a task of this sequence lets escape, on the sequence's thread, after it is logged: the
     * owner of the document reports it. A listener that throws is ignored.
     *
     * <pre>{@code
     * sequence.onFault(cause -> shell.report(cause));
     * }</pre>
     */
    public void onFault(Consumer<Throwable> listener) {
        this.faults = listener;
    }

    private void faulted(Throwable failed) {
        Consumer<Throwable> listener = faults;
        if (listener == null) return;
        try {
            listener.accept(failed);
        } catch (RuntimeException ignored) {
            // a fault report must not fault the sequence again
        }
    }

    private void unwind() {
        closed = true;
        tasks.clear();
        CrystalGuiCore.LOGGER.info("[cgui] sequence '{}' unwound at a checkpoint and closed", name);
    }

    @Override
    public String toString() {
        return "UiSequence(" + name + ")";
    }

    /**
     * The UI pool: daemon threads at normal priority, set explicitly because a thread otherwise inherits its creator's,
     * which would make a pool thread's priority depend on which sequence first needed it.
     */
    private static final class Pool {
        static final ExecutorService EXECUTOR = create();

        private static ExecutorService create() {
            AtomicInteger counter = new AtomicInteger();
            ThreadFactory factory = runnable -> {
                Thread thread = new Thread(runnable, "cgui-ui-" + counter.incrementAndGet());
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY);
                return thread;
            };
            return Executors.newCachedThreadPool(factory);
        }
    }
}
