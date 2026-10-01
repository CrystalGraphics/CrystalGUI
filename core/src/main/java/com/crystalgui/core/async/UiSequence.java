package com.crystalgui.core.async;

import com.crystalgui.core.CrystalGuiCore;

import javax.annotation.Nullable;
import java.util.Queue;
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
 * document.runOn(sequence);                                   // the document now belongs to it
 * sequence.execute(() -> document.frame(delta, w, h));        // from the render thread
 * }</pre>
 *
 * <p>Each sequence has its own {@link JobScheduler}: {@link JobScheduler#shared()} answers it inside a task, so a
 * job's {@code onDone} comes back to the sequence that posted the job, in that sequence's next drain.</p>
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

    private final String name;
    private final Executor pool;
    private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private volatile boolean closed;
    /** The thread running this sequence's work now, or null between turns. */
    @Nullable
    private volatile Thread running;
    private volatile JobScheduler jobs;

    private UiSequence(String name, Executor pool) {
        this.name = name;
        this.pool = pool;
    }

    /** A sequence on the shared UI pool. */
    public static UiSequence create(String name) {
        return new UiSequence(name, Pool.EXECUTOR);
    }

    /** A sequence on {@code pool}: a test's own executor, or a dedicated thread an application asked for. */
    public static UiSequence create(String name, Executor pool) {
        return new UiSequence(name, pool);
    }

    /** The sequence running the calling task, or null on any thread outside one. */
    @Nullable
    public static UiSequence current() {
        return CURRENT.get();
    }

    public boolean isCurrent() {
        return CURRENT.get() == this;
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
        if (isCurrent()) {
            task.run();
            return;
        }
        // CLAIMED THE WAY A TURN CLAIMS IT, so a turn and this can never overlap.
        while (!scheduled.compareAndSet(false, true)) LockSupport.parkNanos(20_000L);
        UiSequence outer = CURRENT.get();
        CURRENT.set(this);
        running = Thread.currentThread();
        try {
            for (Runnable queued; !closed && (queued = tasks.poll()) != null; ) {
                try {
                    queued.run();
                } catch (RuntimeException | Error failed) {
                    CrystalGuiCore.LOGGER.error("[cgui] a task on sequence '{}' threw", name, failed);
                }
            }
            task.run();
        } finally {
            running = null;
            CURRENT.set(outer);
            scheduled.set(false);
        }
        if (!tasks.isEmpty() && !closed) schedule();
    }

    /** The thread running this sequence's work at this moment, or null when it is idle: what a hang report names. */
    @Nullable
    public Thread runningThread() {
        return running;
    }

    /** Drops what is queued and refuses what comes after. */
    public void close() {
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
        try {
            for (int i = 0; i < TASKS_PER_TURN && !closed; i++) {
                Runnable task = tasks.poll();
                if (task == null) break;
                try {
                    task.run();
                } catch (RuntimeException | Error failed) {
                    CrystalGuiCore.LOGGER.error("[cgui] a task on sequence '{}' threw", name, failed);
                }
            }
        } finally {
            running = null;
            CURRENT.set(outer);
            scheduled.set(false);
        }
        // Posted while this turn ran, or left over from it: another turn, which may land on another thread.
        if (!tasks.isEmpty() && !closed) schedule();
    }

    @Override
    public String toString() {
        return "UiSequence(" + name + ")";
    }

    /** The UI pool: daemon threads, at the render thread's priority, since a document's frame is a frame. */
    private static final class Pool {
        static final ExecutorService EXECUTOR = create();

        private static ExecutorService create() {
            AtomicInteger counter = new AtomicInteger();
            ThreadFactory factory = runnable -> {
                Thread thread = new Thread(runnable, "cgui-ui-" + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            };
            return Executors.newCachedThreadPool(factory);
        }
    }
}
