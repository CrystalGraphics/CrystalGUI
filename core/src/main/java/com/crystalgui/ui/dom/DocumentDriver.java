package com.crystalgui.ui.dom;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.async.HostThread;
import com.crystalgui.core.data.ReadOnlyVec2f;
import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.core.async.UiSequence;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.render.UiFrame;
import com.crystalgui.style.property.visual.transform.Transform;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.service.CompositorAnimation;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Runs a document for its host: on the host's thread, on a sequence of its own in lockstep, or on a sequence that
 * records frames while the render thread presents them (plan engine-threaded-ui). A host wires its frame, its input
 * and anything it does to the tree through this, and runs the same code in every mode.
 *
 * <pre>{@code
 * // once, after the tree is built on the host's thread
 * DocumentDriver<Void> driver = DocumentDriver.attach(document);
 *
 * // every host frame, on the render thread, with the host's target bound
 * driver.frame(delta, width, height, DocumentDriver.whole(document));
 *
 * // input, and anything else that touches the tree
 * driver.consumeMouseEvent(event);
 * driver.post(() -> workspace.pump(delta));
 * }</pre>
 *
 * <p>A host with its own handling around the document's offers it as one dispatch, and a host that reads facts about a
 * frame (whether anything is pinned, where the caret is) answers them from its painter:</p>
 *
 * <pre>{@code
 * boolean ours = driver.offerKey(event, () -> myKeys(event) || document.input().consumeKeyboardEvent(event));
 *
 * Painter<Facts> painter = new Painter<>() {
 *     public void paint(float d, int w, int h) { ... draw onto the bound target ... }
 *     public UiFrame record(float d, int w, int h) { ... the same, recorded ... }
 *     public Facts facts() { return new Facts(desktop.hasPinnedWindows()); }
 * };
 * Facts shown = driver.shownFacts();   // of the frame on screen, null before the first or when not async
 * }</pre>
 *
 * <ul>
 *   <li>The mode comes from {@code -Dcrystalgui.ui.async=true} (records on its own) or {@code -Dcrystalgui.ui.sequence=true}
 *       (lockstep); neither runs it inline.</li>
 *   <li>Attach after building the tree: from then on only the driver's own entries may touch it.</li>
 *   <li>Asynchronously an input event answers "taken" before it is dispatched; a key the document then leaves comes
 *       back from {@link #pollUnhandledKey} a frame later.</li>
 *   <li>Asynchronously the document's {@code PlatformPort} answers key and button state from the events offered here:
 *       a host that hands the document events some other way leaves it a stale picture of the keyboard.</li>
 *   <li>{@link #run} waits for the sequence, and so for a frame being recorded. For a probe or a script, never per
 *       frame.</li>
 * </ul>
 */
public final class DocumentDriver<F> implements CgSystemInput.Mouse, CgSystemInput.Keyboard {

    /** Records on its own sequence while the render thread presents. Implies {@link #SEQUENCED}. */
    public static final String ASYNC = "crystalgui.ui.async";

    /** Runs on a sequence of its own, every entry in lockstep with the host's thread. */
    public static final String SEQUENCED = "crystalgui.ui.sequence";

    public enum Mode {
        /** On the host's thread, as a document with no driver. */
        INLINE,
        /** On a sequence, every entry waited for: the tree refuses other threads while the host's stays in charge. */
        LOCKSTEP,
        /** On a sequence that records frames while the render thread presents the last one. */
        ASYNC;

        /** What the system properties ask for. */
        public static Mode fromFlags() {
            if (Boolean.getBoolean(DocumentDriver.ASYNC)) return ASYNC;
            return Boolean.getBoolean(SEQUENCED) ? LOCKSTEP : INLINE;
        }
    }

    /**
     * One frame of a document, as the host draws it. {@link #paint} runs inline and in lockstep, {@link #record}
     * asynchronously; both on the document.
     */
    public interface Painter<F> {

        /** Frames the document and draws it onto the bound target. */
        void paint(float deltaSeconds, int width, int height);

        /** Frames the document and records it, for the render thread to present; null when nothing paints. */
        @Nullable
        UiFrame record(float deltaSeconds, int width, int height);

        /** What the host reads back about the frame just recorded. Asynchronous only. */
        @Nullable
        default F facts() {
            return null;
        }
    }

    /**
     * The whole document at its box tree's scale: a host with nothing to add to the document's own frame.
     *
     * <pre>{@code
     * DocumentDriver.whole(document);
     * DocumentDriver.whole(document, this::refreshReadout, null);                // after the frame, before paint
     * DocumentDriver.whole(document, null, ctx -> ctx.text().draw()...submit()); // over the document, in its frame
     * }</pre>
     *
     * <p>{@code afterFrame} runs between the document's frame and its paint, and {@code overlay} draws after the
     * document in the same frame; both on the document, null for none.</p>
     */
    public static <F> Painter<F> whole(UIDocument document, @Nullable Runnable afterFrame,
                                       @Nullable Consumer<CgUiPaintContext> overlay) {
        return new Painter<>() {
            @Override
            public void paint(float deltaSeconds, int width, int height) {
                CgUiPaintContext ctx = frame(deltaSeconds, width, height);
                ctx.beginFrame(width, height);
                document.paint(ctx);
                if (overlay != null) overlay.accept(ctx);
                ctx.endFrame();
            }

            @Override
            public UiFrame record(float deltaSeconds, int width, int height) {
                CgUiPaintContext ctx = frame(deltaSeconds, width, height);
                ctx.recordFrame(width, height);
                document.paint(ctx);
                if (overlay != null) overlay.accept(ctx);
                return ctx.seal();
            }

            private CgUiPaintContext frame(float deltaSeconds, int width, int height) {
                float scale = document.boxes().uiScale();
                document.frame(deltaSeconds, width / scale, height / scale);
                if (afterFrame != null) afterFrame.run();
                return document.paintContext();
            }
        };
    }

    public static <F> Painter<F> whole(UIDocument document) {
        return whole(document, null, null);
    }

    private final UIDocument document;
    private final Mode mode;
    @Nullable
    private final UiSequence sequence;
    @Nullable
    private final SurfaceCompositor<F> compositor;
    /** What the document asks of the platform when it records on its own; null otherwise. */
    @Nullable
    private final PostedPlatformPort port;
    private final Queue<CgSystemInput.Keyboard.Event> unhandledKeys = new ConcurrentLinkedQueue<>();

    /** Commits built so far. The sequence's. */
    private long commits;
    /** Host frames that drew the document. Render thread. */
    private long presented;
    private boolean closed;

    private DocumentDriver(UIDocument document, Mode mode, String name) {
        this.document = document;
        this.mode = mode;
        if (mode == Mode.INLINE) {
            sequence = null;
            compositor = null;
            port = null;
            return;
        }
        sequence = UiSequence.create(name);
        document.runOn(sequence);
        compositor = mode == Mode.ASYNC ? new SurfaceCompositor<>(sequence) : null;
        port = mode == Mode.ASYNC ? new PostedPlatformPort(sequence) : null;
        if (port != null) {
            document.usePlatform(port);
            JobScheduler.asynchronousDocumentOpened();
        }
        CrystalGuiCore.LOGGER.info("[cgui] document '{}' runs on its own sequence, {}", name,
                mode == Mode.ASYNC ? "recording on its own" : "in lockstep");
    }

    /** Drives {@code document} in the mode the system properties ask for. */
    public static <F> DocumentDriver<F> attach(UIDocument document) {
        return attach(document, Mode.fromFlags(), "document");
    }

    /**
     * Drives {@code document} in {@code mode}, its sequence named {@code name}. Once per document.
     *
     * @throws IllegalStateException if the document already has a driver
     */
    public static <F> DocumentDriver<F> attach(UIDocument document, Mode mode, String name) {
        if (document.driver() != null) throw new IllegalStateException("The document already has a driver");
        DocumentDriver<F> driver = new DocumentDriver<>(document, mode, name);
        document.useDriver(driver);
        return driver;
    }

    public Mode mode() {
        return mode;
    }

    public boolean isAsync() {
        return mode == Mode.ASYNC;
    }

    /** Whether the compositor moves and fades between the document's frames: async, unless switched off. */
    public boolean compositesMotion() {
        return mode == Mode.ASYNC && COMPOSITOR_MOTION;
    }

    public UIDocument document() {
        return document;
    }

    /** The sequence the document runs on, or null inline. */
    @Nullable
    public UiSequence sequence() {
        return sequence;
    }

    // ── The tree ────────────────────────────────────────────────────────────────────────────────

    /** Runs {@code work} on the document and returns once it has run. */
    public void run(Runnable work) {
        if (sequence == null) work.run();
        else sequence.runNow(work);
    }

    /** {@link #run}, answering what {@code work} answered. */
    public <T> T ask(Supplier<T> work) {
        if (sequence == null) return work.get();
        Object[] answer = new Object[1];
        sequence.runNow(() -> answer[0] = work.get());
        @SuppressWarnings("unchecked") T result = (T) answer[0];
        return result;
    }

    /** Runs {@code work} on the document, without waiting for it when the document records on its own. */
    public void post(Runnable work) {
        if (mode == Mode.ASYNC) sequence.execute(work);
        else run(work);
    }

    // ── Input ───────────────────────────────────────────────────────────────────────────────────

    /**
     * Hands the document one dispatch and answers whether it took the event: the dispatch's own answer inline and in
     * lockstep, and true when posted.
     */
    public boolean offer(BooleanSupplier dispatch) {
        if (sequence == null) return dispatch.getAsBoolean();
        if (mode == Mode.ASYNC) {
            sequence.execute(dispatch::getAsBoolean);
            return true;
        }
        boolean[] taken = new boolean[1];
        sequence.runNow(() -> taken[0] = dispatch.getAsBoolean());
        return taken[0];
    }

    /** {@link #offer} for a key: posted, a key the dispatch leaves is queued for {@link #pollUnhandledKey}. */
    public boolean offerKey(CgSystemInput.Keyboard.Event key, BooleanSupplier dispatch) {
        if (mode != Mode.ASYNC) return offer(dispatch);
        sequence.execute(() -> {
            port.note(key);
            if (!dispatch.getAsBoolean()) unhandledKeys.add(key);
        });
        return true;
    }

    /** {@link #offer} for a pointer event, whose buttons the document's platform answers from when posted. */
    public boolean offerMouse(CgSystemInput.Mouse.Event event, BooleanSupplier dispatch) {
        if (mode != Mode.ASYNC) return offer(dispatch);
        pointerX = event.x();
        pointerY = event.y();
        sequence.execute(() -> {
            port.note(event);
            dispatch.getAsBoolean();
        });
        return true;
    }

    @Override
    public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
        return offerMouse(event, () -> document.input().consumeMouseEvent(event));
    }

    @Override
    public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
        return offerKey(event, () -> document.input().consumeKeyboardEvent(event));
    }

    /** An input method's run in progress; an empty {@code text} ends it. */
    public boolean consumeComposition(String text, int caret) {
        return offer(() -> document.input().consumeComposition(text, caret));
    }

    /** The oldest key the document dispatched and left since it was posted, or null. On the host's thread. */
    @Nullable
    public CgSystemInput.Keyboard.Event pollUnhandledKey() {
        return unhandledKeys.poll();
    }

    // ── The frame ───────────────────────────────────────────────────────────────────────────────

    /**
     * One host frame, on the render thread with the host's target bound: paints the document, or presents what it last
     * recorded and asks for the next frame with at most one in flight.
     *
     * @return whether anything was drawn: asynchronously, false until the first frame has been recorded
     */
    public boolean frame(float deltaSeconds, int width, int height, Painter<F> painter) {
        // This thread is where documents are framed: work and answers queued for it run first.
        HostThread.drainFrames();
        if (compositor == null) {
            Runnable delivery = document.readExtracts();
            run(() -> {
                if (delivery != null) delivery.run();
                painter.paint(deltaSeconds, width, height);
            });
            presented++;
            return true;
        }
        // Made here, on the render thread, before the sequence first records: fonts and the text renderer.
        document.paintContext();
        port.service();
        boolean shown = compositor.present(width, height, commit -> followPointer(commit) | animate(commit)) != null;
        lastPresentNanos = System.nanoTime();
        lastPainter = painter;
        lastWidth = width;
        lastHeight = height;
        Runnable delivery = document.readExtracts();
        if (compositor.accepting()) {
            compositor.requestFrame(() -> {
                if (delivery != null) delivery.run();
                return commit(painter, recordDelta(), width, height);
            }, this::chainedCommit);
        } else if (delivery != null) {
            // THE DOCUMENT IS BUSY, possibly chaining frames of its own: the readings wait in its inbox for the next.
            document.post(delivery);
        }
        if (shown) presented++;
        return shown;
    }

    /** What a recording chained behind a slow one records: the painter and size the host last framed with. */
    private UiCommit<F> chainedCommit() {
        Painter<F> painter = lastPainter;
        return painter == null ? null : commit(painter, recordDelta(), lastWidth, lastHeight);
    }

    /** The host's last painter and size, for a frame the sequence chains on its own. Written on the render thread. */
    @Nullable
    private volatile Painter<F> lastPainter;
    private volatile int lastWidth, lastHeight;
    /** When the sequence last began recording. Sequence thread. */
    private long lastRecordNanos;

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

    private UiCommit<F> commit(Painter<F> painter, float deltaSeconds, int width, int height) {
        UiFrame frame = painter.record(deltaSeconds, width, height);
        return new UiCommit<>(frame, painter.facts(), ++commits, follow(frame), motions(frame));
    }

    /** What the compositor plays in {@code frame}: every animation handed to it, with its box's node if it has one. */
    private List<UiCommit.Motion> motions(@Nullable UiFrame frame) {
        List<CompositorAnimation> playing = document.animation().onCompositor();
        if (frame == null || playing.isEmpty()) return List.of();
        List<UiCommit.Motion> motions = new ArrayList<>(playing.size());
        for (CompositorAnimation animation : playing) {
            Box box = animation.target().box();
            int moved = box == null ? 0 : box.movedNode(frame.frameId());
            if (moved == 0) {
                motions.add(new UiCommit.Motion(animation, 0, 0, null, null, null, 0f, 0f, 0f, 0f, 0f, 0f));
                continue;
            }
            float width = box.width(), height = box.height();
            float originX = animation.originX(width), originY = animation.originY(height);
            Matrix4f recorded = box.transform().applyTo(new Matrix4f(), 0f, 0f, width, height,
                    box.transformOriginX() != null ? box.transformOriginX() : originX,
                    box.transformOriginY() != null ? box.transformOriginY() : originY);
            Matrix4f recordedInverse = recorded.invert(new Matrix4f());
            Matrix4f world = box.movedWorld();
            Matrix4f place = new Matrix4f(world).mul(recordedInverse);
            motions.add(new UiCommit.Motion(animation, moved, box.fadedNode(frame.frameId()), place,
                    place.invert(new Matrix4f()), recordedInverse, width, height, originX, originY,
                    // AT REST: the node is the box's corner without its transform. @see Box#restToWorld
                    Math.round(place.m30()), Math.round(place.m31())));
        }
        return motions;
    }

    /** What follows the pointer in {@code frame}, if anything and it has a node there to move. On the document. */
    @Nullable
    private UiCommit.Follow follow(@Nullable UiFrame frame) {
        UIElement element = frame == null ? null : document.input().pointerFollower();
        Box box = element == null ? null : element.box();
        int node = box == null ? 0 : box.movedNode(frame.frameId());
        if (node == 0) return null;
        ReadOnlyVec2f pointer = document.input().pointer();
        UIElement within = document.input().pointerFollowerWithin();
        Box bounds = within == null ? null : within.box();
        if (bounds == null) {
            float free = UiCommit.Follow.FREE;
            return new UiCommit.Follow(node, pointer.x(), pointer.y(), -free, free, -free, free);
        }
        // World rectangles, axis-aligned: what a clamp on left/top keeps inside its container.
        float x0 = box.worldX(), y0 = box.worldY();
        float x1 = x0 + box.width() * box.localToWorld().m00(), y1 = y0 + box.height() * box.localToWorld().m11();
        float bx0 = bounds.worldX(), by0 = bounds.worldY();
        float bx1 = bx0 + bounds.width() * bounds.localToWorld().m00();
        float by1 = by0 + bounds.height() * bounds.localToWorld().m11();
        float minX = Math.min(0f, bx0 - x0), minY = Math.min(0f, by0 - y0);
        return new UiCommit.Follow(node, pointer.x(), pointer.y(),
                minX, Math.max(minX, bx1 - x1), minY, Math.max(minY, by1 - y1));
    }

    // ── Compositor motion ───────────────────────────────────────────────────────────────────────

    /** {@code -Dcrystalgui.ui.compositorMotion=false}: nothing moves between the document's frames. */
    private static final boolean COMPOSITOR_MOTION =
            !"false".equals(System.getProperty("crystalgui.ui.compositorMotion"));

    /** The pointer as the render thread last saw it, in surface pixels. Render thread. */
    private float pointerX, pointerY;
    /** The commit {@link #followPointer} last moved, and by how much, so an unchanged offset is not drawn again. */
    @Nullable
    private UiCommit<F> moved;
    private int movedX, movedY;

    /**
     * Moves what the commit recorded following the pointer by the pointer's travel since: a window dragged across a busy
     * document stays under the hand. Whole device pixels, as a spatial node is placed. Render thread.
     */
    private boolean followPointer(UiCommit<F> commit) {
        if (!COMPOSITOR_MOTION) return false;
        UiCommit.Follow follow = commit.follow();
        if (follow == null || commit.frame() == null) return false;
        int dx = Math.round(clamp(pointerX - follow.pointerX(), follow.minX(), follow.maxX()));
        int dy = Math.round(clamp(pointerY - follow.pointerY(), follow.minY(), follow.maxY()));
        if (commit == moved && dx == movedX && dy == movedY) return false;
        commit.frame().values().translate(follow.node(), dx, dy);
        moved = commit;
        movedX = dx;
        movedY = dy;
        return true;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /** The commit {@link #animate} last drew finished, so a settled flight is shown again rather than redrawn. */
    @Nullable
    private UiCommit<F> settled;
    /** When the last host frame was presented, and how many slow ones a waiting flight has been held through. */
    private long lastPresentNanos;
    private int heldPresents;
    /** A gap longer than this is a stall, and a flight does not start its clock in one. */
    private static final long STALL_NANOS = 100_000_000L;
    private static final int MAX_HELD_PRESENTS = 60;
    private final Matrix4f motionAt = new Matrix4f(), motionNode = new Matrix4f();

    /**
     * Plays the commit's compositor animations at this host frame: each box's node takes the animation's transform
     * now, and its effect node the opacity. Their clocks start the first time a frame carrying them is presented.
     * Render thread.
     */
    private boolean animate(UiCommit<F> commit) {
        List<UiCommit.Motion> motions = commit.motions();
        if (motions.isEmpty() || commit.frame() == null || commit == settled) return false;
        long now = System.nanoTime();
        // NOT IN A STALL: the first windows open while the editor builds, and a clock started in a 400 ms present has
        // run out before a second frame is drawn. It waits, at its start value, for frames at an ordinary pace.
        boolean ordinary = now - lastPresentNanos <= STALL_NANOS || heldPresents++ >= MAX_HELD_PRESENTS;
        if (ordinary) heldPresents = 0;
        boolean finished = true;
        for (int i = 0; i < motions.size(); i++) {
            UiCommit.Motion motion = motions.get(i);
            CompositorAnimation animation = motion.animation();
            if (ordinary) animation.startAt(now);
            finished &= animation.isFinished(now);
            if (motion.moved() == 0) continue;
            Transform at = animation.transformAt(now);
            at.applyTo(motionAt.identity(), 0f, 0f, motion.width(), motion.height(), motion.originX(), motion.originY());
            motionNode.set(motion.place()).mul(motionAt).mul(motion.recordedInverse()).mul(motion.placeInverse())
                    .translate(motion.cornerX(), motion.cornerY(), 0f);
            commit.frame().values().transform(motion.moved(), motionNode.m00(), motionNode.m01(), motionNode.m10(),
                    motionNode.m11(), motionNode.m30(), motionNode.m31());
            if (motion.faded() != 0) commit.frame().values().opacity(motion.faded(), animation.opacityAt(now));
        }
        if (finished) settled = commit;
        return true;
    }

    /** The facts of the frame on screen, or of the newest one committed; null before the first, and when not async. */
    @Nullable
    public F shownFacts() {
        if (compositor == null) return null;
        UiCommit<F> shown = compositor.active();
        return shown == null ? null : shown.facts();
    }

    /**
     * How many host frames have drawn the document: the frame count a capture or a script waits on, since
     * asynchronously the first frames present nothing. Render thread.
     */
    public long presentedFrames() {
        return presented;
    }

    /** Whether a frame has been committed: asynchronously, whether there is anything to present yet. */
    public boolean hasCommitted() {
        return compositor == null || compositor.active() != null;
    }

    /**
     * Waits for the frame being recorded, then stops the sequence: after it returns nothing of the document runs, so
     * the host may free what its frames use. The document is not touched; dispose it first, through {@link #run}.
     */
    public void close() {
        if (sequence == null || closed) return;
        closed = true;
        sequence.runNow(() -> { });
        sequence.close();
        if (port != null) JobScheduler.asynchronousDocumentClosed();
    }
}
