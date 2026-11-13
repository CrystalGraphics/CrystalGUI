package com.crystalgui.viewport;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.core.async.UiSequence;
import com.crystalgui.core.async.UiThread;
import com.crystalgui.ui.dom.UIDocument;

import javax.annotation.Nullable;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Where a viewport's document runs, and so how each of the viewport's entries reaches it: one strategy per
 * {@link CgUiViewport.Threading}, and the document's {@link UIDocument.Owner}. The viewport never asks which.
 */
abstract class DocumentThread implements UIDocument.Owner {

    final CgUiViewport viewport;
    private final String name;

    DocumentThread(CgUiViewport viewport, String name) {
        this.viewport = viewport;
        this.name = name;
    }

    static DocumentThread of(CgUiViewport viewport, CgUiViewport.Threading threading, String name) {
        return switch (threading) {
            case INLINE -> new Inline(viewport, name);
            case LOCKSTEP -> new Lockstep(viewport, name);
            case SEQUENCED -> new Sequenced(viewport, name);
        };
    }

    @Override
    public String name() {
        return name;
    }

    /** Runs {@code work} on the document and returns once it has run. */
    abstract void run(Runnable work);

    /** {@link #run}, answering what {@code work} answered. */
    abstract <T> T ask(Supplier<T> work);

    /** Hands the document one dispatch: its own answer where it is waited for, true where it is posted. */
    abstract boolean offer(BooleanSupplier dispatch);

    /** {@link #offer} for a key; a posted key the dispatch leaves goes to {@code unhandled}. */
    boolean offerKey(CgSystemInput.Keyboard.Event key, BooleanSupplier dispatch,
                     Queue<CgSystemInput.Keyboard.Event> unhandled) {
        return offer(dispatch);
    }

    /** {@link #offer} for a pointer event. */
    boolean offerMouse(CgSystemInput.Mouse.Event event, BooleanSupplier dispatch) {
        return offer(dispatch);
    }

    /**
     * One host frame: records a commit where this thread records, and shows one. {@code held} is
     * {@code Input.platformHeld()}'s reading, {@code delivery} the extracts' readings, both taken on the render thread.
     *
     * @return whether anything was shown
     */
    abstract boolean frame(@Nullable CgStageFrame stage, float deltaSeconds, int width, int height,
                           CgUiViewport.Content content, int held, @Nullable Runnable delivery);

    /** The newest commit, shown or waiting to be; null before the first. */
    @Nullable
    abstract UiCommit newest();

    /** The index of the commit on screen; 0 before the first. Render thread. */
    abstract long shownIndex();

    /** Whether the document's {@code Input} answers held keys from the events offered rather than the platform. */
    boolean answersFromEvents() {
        return false;
    }

    /** The sequence this document runs on, or null where it runs on the host's thread (which cannot be abandoned). */
    @Nullable
    UiSequence sequence() {
        return null;
    }

    /**
     * Runs what was sent to the document, off any frame: where the document runs on the host's thread, work sent
     * from another waits for the host to come by, and the host comes by here every frame whatever it draws. Host thread.
     */
    void drain() {
    }

    /** Hears each exception work sent to the document lets escape, on the document's thread. */
    void onFault(Consumer<Throwable> listener) {
        UiSequence sequence = sequence();
        if (sequence != null) sequence.onFault(listener);
    }

    /** Stops the thread, after the work queued on it. Render thread. */
    void close() {
    }

    /** How long closing waits on a running turn at each rung before it climbs. */
    private static final long CLOSE_GRACE_NANOS = 250_000_000L;

    /**
     * Ends {@code sequence} without ever waiting on it for good: what is queued runs (an application's dispose among it),
     * but a turn that does not return is aborted at its next checkpoint, then abandoned, so the closer is held for at most
     * two graces. The shell closes a document on its own thread, which a document stuck in a loop must not freeze.
     */
    static void closeSequence(UiSequence sequence) {
        if (!sequence.runNow(() -> { }, CLOSE_GRACE_NANOS)) {
            sequence.abort();
            if (!sequence.runNow(() -> { }, CLOSE_GRACE_NANOS)) {
                sequence.interrupt();
                sequence.abandon();
            }
        }
        sequence.close();
    }

    // ── The strategies ──────────────────────────────────────────────────────────────────────────

    /**
     * The host's thread: a plain thread builds the document until the host first comes by ({@link #drain} or a
     * frame), which takes it. A sequence's thread is never the host's, so the shell launching an application from
     * its own sequence posts to it. Work sent from another thread waits for the host's next visit.
     */
    static final class Inline extends DocumentThread {
        private final Queue<Runnable> sent = new ConcurrentLinkedQueue<>();
        @Nullable
        private volatile Thread thread;
        @Nullable
        private volatile UiCommit shown;
        @Nullable
        private volatile Consumer<Throwable> faults;

        Inline(CgUiViewport viewport, String name) {
            super(viewport, name);
        }

        @Override
        public boolean isCurrent() {
            Thread known = thread;
            // Running the document's own work, whatever sequence's turn the host's thread is also lending itself to.
            if (known == Thread.currentThread() && UiThread.isHosting()) return true;
            // A sequence's task is never the host's work, even where it runs on the host's thread (the shell closing an
            // application from the render thread): it is sent, and run in place, as the host's.
            if (UiSequence.current() != null) return false;
            return known == null || known == Thread.currentThread() || !known.isAlive();
        }

        @Override
        void onFault(Consumer<Throwable> listener) {
            faults = listener;
        }

        @Override
        void drain() {
            thread = Thread.currentThread();
            UiThread.enterHost();
            try {
                for (Runnable work; (work = sent.poll()) != null; ) {
                    try {
                        work.run();
                    } catch (RuntimeException | LinkageError failed) {
                        // As a sequence contains a task: the application's fault, never the host frame's.
                        Consumer<Throwable> listener = faults;
                        if (listener == null) {
                            CrystalGuiCore.LOGGER.error("[cgui] work sent to document '{}' failed", name(), failed);
                        } else {
                            listener.accept(failed);
                        }
                    }
                }
            } finally {
                UiThread.exitHost();
            }
        }

        @Override
        public void execute(Runnable work) {
            sent.add(work);
            // On the host's own thread there is no one to wait for: run what was sent now, as the host's.
            if (thread == Thread.currentThread()) drain();
        }

        @Override
        void run(Runnable work) {
            work.run();
        }

        @Override
        <T> T ask(Supplier<T> work) {
            return work.get();
        }

        @Override
        boolean offer(BooleanSupplier dispatch) {
            return dispatch.getAsBoolean();
        }

        @Override
        boolean frame(@Nullable CgStageFrame stage, float deltaSeconds, int width, int height,
                      CgUiViewport.Content content, int held, @Nullable Runnable delivery) {
            drain();
            UiCommit commit;
            UiThread.enterHost();
            try {
                commit = viewport.recordOnDocument(held, delivery, content, deltaSeconds, width, height);
            } finally {
                UiThread.exitHost();
            }
            shown = commit;
            viewport.show(stage, commit);
            return true;
        }

        @Override
        @Nullable
        UiCommit newest() {
            return shown;
        }

        @Override
        long shownIndex() {
            UiCommit commit = shown;
            return commit == null ? 0L : commit.index();
        }
    }

    /** A sequence of its own, every entry waited for: the tree refuses other threads while the host stays in charge. */
    static final class Lockstep extends DocumentThread {
        private final UiSequence sequence;
        @Nullable
        private volatile UiCommit shown;

        Lockstep(CgUiViewport viewport, String name) {
            super(viewport, name);
            sequence = UiSequence.forDocument(name);
        }

        @Override
        public boolean isCurrent() {
            return sequence.isCurrent();
        }

        @Override
        public void execute(Runnable work) {
            sequence.execute(work);
        }

        @Override
        void run(Runnable work) {
            sequence.runNow(work);
        }

        @Override
        <T> T ask(Supplier<T> work) {
            Object[] answer = new Object[1];
            sequence.runNow(() -> answer[0] = work.get());
            @SuppressWarnings("unchecked") T result = (T) answer[0];
            return result;
        }

        @Override
        boolean offer(BooleanSupplier dispatch) {
            boolean[] taken = new boolean[1];
            sequence.runNow(() -> taken[0] = dispatch.getAsBoolean());
            return taken[0];
        }

        @Override
        boolean frame(@Nullable CgStageFrame stage, float deltaSeconds, int width, int height,
                      CgUiViewport.Content content, int held, @Nullable Runnable delivery) {
            UiCommit commit = ask(() -> viewport.recordOnDocument(held, delivery, content, deltaSeconds, width, height));
            shown = commit;
            viewport.show(stage, commit);
            return true;
        }

        @Override
        @Nullable
        UiCommit newest() {
            return shown;
        }

        @Override
        long shownIndex() {
            UiCommit commit = shown;
            return commit == null ? 0L : commit.index();
        }

        @Override
        UiSequence sequence() {
            return sequence;
        }

        @Override
        void close() {
            closeSequence(sequence);
        }
    }

    /**
     * A sequence that records frames while the render thread presents the last one committed. An input event is
     * answered "taken" before it is dispatched; the compositor moves what the commits declared between them.
     */
    static final class Sequenced extends DocumentThread {
        private final UiSequence sequence;
        private final CommitQueue queue;
        private final CompositorMotion motion = new CompositorMotion();

        /** The host's last content and size, for a frame the sequence chains on its own. Written on the render thread. */
        @Nullable
        private volatile CgUiViewport.Content lastContent;
        private volatile int lastWidth, lastHeight;
        /** When the sequence last began recording. Sequence thread. */
        private long lastRecordNanos;

        Sequenced(CgUiViewport viewport, String name) {
            super(viewport, name);
            sequence = UiSequence.forDocument(name);
            queue = new CommitQueue(sequence);
            JobScheduler.asynchronousDocumentOpened();
        }

        @Override
        public boolean isCurrent() {
            return sequence.isCurrent();
        }

        @Override
        public void execute(Runnable work) {
            sequence.execute(work);
        }

        @Override
        public boolean compositesMotion() {
            return CompositorMotion.ENABLED;
        }

        @Override
        boolean answersFromEvents() {
            return true;
        }

        @Override
        void run(Runnable work) {
            sequence.runNow(work);
        }

        @Override
        <T> T ask(Supplier<T> work) {
            Object[] answer = new Object[1];
            sequence.runNow(() -> answer[0] = work.get());
            @SuppressWarnings("unchecked") T result = (T) answer[0];
            return result;
        }

        @Override
        boolean offer(BooleanSupplier dispatch) {
            sequence.execute(dispatch::getAsBoolean);
            return true;
        }

        @Override
        boolean offerKey(CgSystemInput.Keyboard.Event key, BooleanSupplier dispatch,
                         Queue<CgSystemInput.Keyboard.Event> unhandled) {
            sequence.execute(() -> {
                if (!dispatch.getAsBoolean()) unhandled.add(key);
            });
            return true;
        }

        @Override
        boolean offerMouse(CgSystemInput.Mouse.Event event, BooleanSupplier dispatch) {
            motion.pointerAt(event.x(), event.y());
            return offer(dispatch);
        }

        @Override
        boolean frame(@Nullable CgStageFrame stage, float deltaSeconds, int width, int height,
                      CgUiViewport.Content content, int held, @Nullable Runnable delivery) {
            viewport.reconcileHeld(held);
            boolean moves = compositesMotion();
            boolean shown = queue.present(stage, viewport.presenter(), commit -> moves && motion.move(commit)) != null;
            motion.presented(System.nanoTime());
            lastContent = content;
            lastWidth = width;
            lastHeight = height;
            if (queue.accepting()) {
                queue.requestFrame(() -> {
                    if (delivery != null) delivery.run();
                    return viewport.commit(content, recordDelta(), width, height);
                }, this::chained);
            } else if (delivery != null) {
                // THE DOCUMENT IS BUSY, possibly chaining frames of its own: the readings wait behind it.
                viewport.document().execute(delivery);
            }
            return shown;
        }

        /** What a recording chained behind a slow one records: the content and size the host last framed with. */
        @Nullable
        private UiCommit chained() {
            CgUiViewport.Content content = lastContent;
            return content == null ? null : viewport.commit(content, recordDelta(), lastWidth, lastHeight);
        }

        /**
         * Seconds since the last recording began, on the sequence's own clock: a chained frame has no host delta, and one
         * clock for both kinds counts no time twice.
         */
        private float recordDelta() {
            long now = System.nanoTime();
            float delta = lastRecordNanos == 0L ? 0f : (now - lastRecordNanos) / 1_000_000_000f;
            lastRecordNanos = now;
            return delta;
        }

        @Override
        @Nullable
        UiCommit newest() {
            return queue.newest();
        }

        @Override
        long shownIndex() {
            return queue.shownIndex();
        }

        @Override
        UiSequence sequence() {
            return sequence;
        }

        @Override
        void close() {
            closeSequence(sequence);
            JobScheduler.asynchronousDocumentClosed();
        }
    }
}
