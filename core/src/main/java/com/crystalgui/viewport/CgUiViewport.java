package com.crystalgui.viewport;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.async.ApplicationAborted;
import com.crystalgui.core.async.UiSequence;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.render.CgUiRenderer;
import com.crystalgui.render.UiFrame;
import com.crystalgui.render.UiPresenter;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.service.Input;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One document on screen: it owns the document's thread, feeds it input, frames it at a GUI stage and shows what it
 * recorded. A host wires its frame, its input and anything it does to the tree through this, and the same code runs
 * whichever {@link Threading} the document runs on.
 *
 * <pre>{@code
 * // once, after the tree is built on the host's thread
 * CgUiViewport viewport = CgUiViewport.open(document, CgUiViewport.Threading.fromFlags(), "editor");
 * CgUiViewport.Content content = CgUiViewport.Content.whole(document);
 *
 * // every host frame: a renderer at a GUI stage, which gives it the size, the target and the clock
 * CgRenderStage.SCREEN.register(CgRenderStage.VIEWPORT_WIDGETS, stage -> viewport.frame(stage, content));
 *
 * // input, and anything else that touches the tree
 * viewport.consumeMouseEvent(event);
 * viewport.execute(() -> workspace.pump(delta));      // on the document, without waiting
 * float[] caret = viewport.readout(CgUiViewport.TEXT_INPUT_AREA);   // of the frame on screen
 *
 * viewport.close();                                   // render thread, with the document
 * }</pre>
 *
 * <p>A host that paints more than the document, or reads its own facts about a frame, writes a {@link Content}:</p>
 *
 * <pre>{@code
 * static final Readout<Boolean> PINNED = Readout.named("pinned");
 * CgUiViewport.Content content = new CgUiViewport.Content() {
 *     public boolean frame(float delta, int w, int h) { return desktop.prepare(delta, w, h); }
 *     public void paint(CgUiPaintContext ctx) { desktop.paintInto(ctx); }
 *     public void readouts(Readouts out) { out.put(PINNED, desktop.hasPinnedWindows()); }
 * };
 * }</pre>
 *
 * <ul>
 *   <li>Open after building the tree: from then on only the viewport's own entries may touch it, or
 *       {@code document.execute}.</li>
 *   <li>{@link Threading#SEQUENCED} answers an input event "taken" before it is dispatched; a key the document then
 *       leaves comes back from {@link #pollUnhandledKey} a frame later.</li>
 *   <li>{@link #run} and {@link #ask} wait for the document, and so for a frame being recorded: for a probe or a
 *       script, never per frame.</li>
 *   <li>Every threading commits: a readout reads the frame on screen in each.</li>
 *   <li>{@link #close} frees what the document's frames drew with; close the viewport with its document.</li>
 * </ul>
 */
public final class CgUiViewport implements CgSystemInput.Mouse, CgSystemInput.Keyboard {

    /** Records on its own sequence while the render thread presents: {@code true} unless set. */
    public static final String SEQUENCED_FLAG = "crystalgui.ui.async";

    /** Runs on a sequence of its own, every entry in lockstep with the host's thread. */
    public static final String LOCKSTEP_FLAG = "crystalgui.ui.sequence";

    /** The longest step a frame takes: a stall reaches the document as one slow frame, never as a jump. */
    public static final float MAX_FRAME_SECONDS = 0.25f;

    /** The focus owner's caret area in surface pixels, {@code [x, y, width, height]}; null when nothing takes text. */
    public static final Readout<float[]> TEXT_INPUT_AREA = Readout.named("text-input-area");

    /** Whether an element holds the pointer captured: a drag or a press in progress. */
    public static final Readout<Boolean> POINTER_CAPTURED = Readout.named("pointer-captured");

    /** Where a document runs. */
    public enum Threading {
        /** On the host's thread. */
        INLINE,
        /** On a sequence of its own, every entry waited for: the tree refuses other threads while the host is in charge. */
        LOCKSTEP,
        /** On a sequence that records frames while the render thread presents the last one. The default. */
        SEQUENCED;

        /**
         * What the system properties ask for: sequenced unless {@code -Dcrystalgui.ui.async=false}, lockstep with
         * {@code -Dcrystalgui.ui.sequence=true} alone.
         */
        public static Threading fromFlags() {
            String sequencedFlag = System.getProperty(SEQUENCED_FLAG);
            boolean lockstep = Boolean.getBoolean(LOCKSTEP_FLAG);
            if (sequencedFlag == null ? !lockstep : Boolean.parseBoolean(sequencedFlag)) return SEQUENCED;
            return lockstep ? LOCKSTEP : INLINE;
        }
    }

    /**
     * What a viewport frames: the document's frame, its paint, and what the host reads back. Every method runs on the
     * document's thread. The viewport opens, seals or aborts the paint context's frame itself.
     *
     * <pre>{@code
     * CgUiViewport.Content.whole(document);
     * CgUiViewport.Content.whole(document, ctx -> ctx.textGamma(gamma), null);       // before the document paints
     * CgUiViewport.Content.whole(document, null, ctx -> ctx.text().at(4, 4).text(fps).submit());   // over it
     * }</pre>
     */
    public interface Content {

        /**
         * The document's frame at a surface of {@code width} by {@code height} pixels. False when nothing paints this
         * frame: no paint context is asked for, and nothing holds a GPU resource for it.
         */
        boolean frame(float deltaSeconds, int width, int height);

        /** Paints into the frame the viewport opened on {@code ctx}. */
        void paint(CgUiPaintContext ctx);

        /** After paint: what the host may read of this frame. The viewport has put its own. */
        default void readouts(Readouts out) {
        }

        /**
         * A frame that threw a {@code RuntimeException} or a {@code LinkageError}: the viewport aborted it, logged it
         * and commits nothing to draw. Recover here; anything else thrown propagates.
         */
        default void failed(Throwable cause) {
        }

        /** The whole document at its box tree's scale: a host with nothing to add. */
        static Content whole(UIDocument document) {
            return whole(document, null, null);
        }

        /**
         * The whole document at its box tree's scale, with {@code beforePaint} run in its frame before the document
         * paints and {@code overlay} after; either null for none.
         */
        static Content whole(UIDocument document, @Nullable Consumer<CgUiPaintContext> beforePaint,
                             @Nullable Consumer<CgUiPaintContext> overlay) {
            return new Content() {
                @Override
                public boolean frame(float deltaSeconds, int width, int height) {
                    float scale = document.boxes().uiScale();
                    document.frame(deltaSeconds, width / scale, height / scale);
                    return true;
                }

                @Override
                public void paint(CgUiPaintContext ctx) {
                    if (beforePaint != null) beforePaint.accept(ctx);
                    document.paint(ctx);
                    if (overlay != null) overlay.accept(ctx);
                }
            };
        }
    }

    private final UIDocument document;
    private final Threading threading;
    private final String name;
    private final DocumentThread thread;
    private final UIDocument.Ownership ownership;
    private final Queue<CgSystemInput.Keyboard.Event> unhandledKeys = new ConcurrentLinkedQueue<>();

    /** Where this document is drawn: what it records with, and what shows it at the stage that frames it. */
    private final CgUiRenderer.View view = CgUiRenderer.openView();
    /** {@link CgFrameClock}'s seconds at this document's last frame; NaN before the first. Render thread. */
    private double lastFrameSeconds = Double.NaN;

    /** Commits built so far. The document's thread. */
    private volatile long commits;
    /** Host frames that showed the document. Render thread. */
    private long presented;
    private boolean closed;

    private CgUiViewport(UIDocument document, Threading threading, String name) {
        this.document = document;
        this.threading = threading;
        this.name = name;
        this.thread = DocumentThread.of(this, threading, name);
        this.ownership = document.own(thread);
        if (thread.answersFromEvents()) document.input().answerFromEvents();
        if (threading != Threading.INLINE) {
            CrystalGuiCore.LOGGER.info("[cgui] document '{}' runs on its own sequence, {}", name,
                    threading == Threading.SEQUENCED ? "recording on its own" : "in lockstep");
        }
    }

    /**
     * Puts {@code document} on screen, run as {@code threading} asks; {@code name} names its sequence. Once per
     * document.
     *
     * @throws IllegalStateException if the document is already owned
     */
    public static CgUiViewport open(UIDocument document, Threading threading, String name) {
        return new CgUiViewport(document, threading, name);
    }

    public UIDocument document() {
        return document;
    }

    public Threading threading() {
        return threading;
    }

    /** Whether the document records off the host's thread, so a host reads it only through readouts. */
    public boolean isSequenced() {
        return threading == Threading.SEQUENCED;
    }

    /** Whether the compositor moves and fades between the document's frames: sequenced, unless switched off. */
    public boolean compositesMotion() {
        return thread.compositesMotion();
    }

    // ── The tree ────────────────────────────────────────────────────────────────────────────────

    /** Runs {@code work} on the document and returns once it has run. */
    public void run(Runnable work) {
        thread.run(work);
    }

    /** {@link #run}, answering what {@code work} answered. */
    public <T> T ask(Supplier<T> work) {
        return thread.ask(work);
    }

    /** Runs {@code work} on the document: at once when already there, else without waiting. */
    public void execute(Runnable work) {
        document.execute(work);
    }

    // ── Input ───────────────────────────────────────────────────────────────────────────────────

    /** Hands the document one dispatch: its own answer where it is waited for, true where it is posted. */
    public boolean offer(BooleanSupplier dispatch) {
        return thread.offer(dispatch);
    }

    /** {@link #offer} for a key; posted, a key the dispatch leaves comes back from {@link #pollUnhandledKey}. */
    public boolean offerKey(CgSystemInput.Keyboard.Event key, BooleanSupplier dispatch) {
        return thread.offerKey(key, dispatch, unhandledKeys);
    }

    /** {@link #offer} for a pointer event, whose position the compositor follows when posted. */
    public boolean offerMouse(CgSystemInput.Mouse.Event event, BooleanSupplier dispatch) {
        return thread.offerMouse(event, dispatch);
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
     * One host frame at a GUI stage, from a renderer's turn: records the document at the stage's size where it records,
     * and shows the newest frame there. Its delta is {@link #advance()}'s.
     *
     * <pre>{@code
     * CgRenderStage.SCREEN.register(CgRenderStage.VIEWPORT_WIDGETS, stage -> viewport.frame(stage, content));
     * }</pre>
     *
     * @return whether anything was shown: sequenced, false until the first frame has been recorded
     */
    public boolean frame(CgStageFrame stage, Content content) {
        return frame(stage, advance(), content);
    }

    /** {@link #frame(CgStageFrame, Content)} with a delta already read from {@link #advance()}, for a host that ticks with it. */
    public boolean frame(CgStageFrame stage, float deltaSeconds, Content content) {
        return frame(stage, deltaSeconds, stage.host().width(), stage.host().height(), content);
    }

    /**
     * Seconds of rendered time since this document last framed, on {@link CgFrameClock}, at most
     * {@link #MAX_FRAME_SECONDS}; zero the first time. Rendered rather than wall: frames nobody saw are not charged to a
     * gesture, and zero first holds every animation at its start rather than finishing it before anything was drawn.
     * Advances the document's clock: once a frame, by whoever frames it. Render thread.
     */
    public float advance() {
        double now = CgFrameClock.seconds(), last = lastFrameSeconds;
        lastFrameSeconds = now;
        if (Double.isNaN(last) || now <= last) return 0f;
        return (float) Math.min(now - last, MAX_FRAME_SECONDS);
    }

    /** {@link #frame(CgStageFrame, float, Content)} at a given size; with no stage it records and shows nothing. Tests. */
    boolean frame(@Nullable CgStageFrame stage, float deltaSeconds, int width, int height, Content content) {
        // Made here, on the render thread, before the document first records: fonts and the text renderer.
        if (stage != null) view.paintContext();
        int held = Input.platformHeld();
        boolean shown = thread.frame(stage, deltaSeconds, width, height, content, held, ownership.readExtracts());
        if (shown) presented++;
        return shown;
    }

    /** What shows this document's frames: the last presented, which a host may move and draw again. Render thread. */
    public UiPresenter presenter() {
        return view.presenter();
    }

    /** Where this document is drawn: its paint context and its presenter, closed with the viewport. */
    public CgUiRenderer.View view() {
        return view;
    }

    /** {@code key}'s value in the newest frame the document committed; null before the first, or when it put none. */
    @Nullable
    public <T> T readout(Readout<T> key) {
        UiCommit newest = thread.newest();
        return newest == null ? null : newest.readouts().get(key);
    }

    /**
     * How many host frames have shown the document: the frame count a capture or a script waits on, since sequenced the
     * first frames show nothing. Render thread.
     */
    public long presentedFrames() {
        return presented;
    }

    /**
     * How many frames the document has committed. Read it on the document's thread: an event dispatched there shows in
     * the first commit numbered above it.
     *
     * <pre>{@code
     * viewport.offerMouse(event, () -> { dispatchedAfter = viewport.committedFrames(); return dispatch(event); });
     * // later, render thread: the event is on screen once presentedCommit() > dispatchedAfter
     * }</pre>
     */
    public long committedFrames() {
        return commits;
    }

    /**
     * The number of the commit on screen, counted as {@link #committedFrames()} counts them; 0 before the first. It
     * moves only when a new frame is shown, so its rate is the document's real frame rate. Render thread.
     */
    public long presentedCommit() {
        return thread.shownIndex();
    }

    /** Whether a frame has been committed: sequenced, whether there is anything to present yet. */
    public boolean hasCommitted() {
        return thread.newest() != null;
    }

    /**
     * Waits for the frame being recorded, stops the document's thread, gives the document back, then closes the view,
     * freeing what its frames drew with: after it returns nothing of the document runs. The document is not touched;
     * dispose it first, through {@link #run}. Render thread; idempotent.
     */
    /**
     * The thread running this document's work now, or null when it is idle or runs on the host's thread: what a hang
     * report takes the stack of. Any thread.
     */
    @Nullable
    public Thread runningThread() {
        UiSequence sequence = thread.sequence();
        return sequence == null ? null : sequence.runningThread();
    }

    /**
     * Rung 1 of ending an unresponsive document: its engine checkpoints throw {@link ApplicationAborted}. Any thread; a
     * no-op for a document on the host's thread.
     */
    public void abort() {
        UiSequence sequence = thread.sequence();
        if (sequence != null) sequence.abort();
    }

    /** Whether the document's sequence has ended: it unwound at a checkpoint after {@link #abort}, or was abandoned. */
    public boolean stopped() {
        UiSequence sequence = thread.sequence();
        return sequence != null && sequence.isClosed();
    }

    /** Rung 2: interrupts the thread running the document, ending a wait or a sleep. Any thread. */
    public void interrupt() {
        UiSequence sequence = thread.sequence();
        if (sequence != null) sequence.interrupt();
    }

    /**
     * Rung 3: gives the document's sequence up, so {@link #close} no longer waits for it. The stuck thread is left at
     * minimum priority. Any thread.
     */
    public void abandon() {
        UiSequence sequence = thread.sequence();
        if (sequence != null) sequence.abandon();
    }

    public void close() {
        if (closed) return;
        closed = true;
        thread.close();
        ownership.release();
        view.close();
    }

    // ── For the threads ─────────────────────────────────────────────────────────────────────────

    /** On the document: the platform's held keys, the extracts' readings, then a commit. */
    UiCommit recordOnDocument(int held, @Nullable Runnable delivery, Content content, float deltaSeconds, int width,
                              int height) {
        document.input().reconcile(held);
        if (delivery != null) delivery.run();
        return commit(content, deltaSeconds, width, height);
    }

    /** Sends the platform's held keys to the document, behind what is queued there. */
    void reconcileHeld(int held) {
        document.execute(() -> document.input().reconcile(held));
    }

    /** On the document: frames, paints and seals one frame with its readouts. A failed frame commits nothing to draw. */
    UiCommit commit(Content content, float deltaSeconds, int width, int height) {
        UiFrame frame = null;
        Readouts out = new Readouts();
        long started = System.nanoTime();
        try {
            if (content.frame(deltaSeconds, width, height)) {
                CgUiPaintContext ctx = view.paintContext();
                ctx.recordFrame(width, height);
                try {
                    content.paint(ctx);
                } catch (RuntimeException | LinkageError failed) {
                    try {
                        ctx.abortFrame();
                    } catch (RuntimeException | LinkageError aborting) {
                        failed.addSuppressed(aborting);   // the paint's own failure is the one to report
                    }
                    throw failed;
                }
                frame = ctx.seal();
            }
            out.put(TEXT_INPUT_AREA, document.input().textInputArea());
            out.put(POINTER_CAPTURED, document.input().pointerCaptureTarget() != null);
            content.readouts(out);
        } catch (RuntimeException | LinkageError failed) {
            CrystalGuiCore.LOGGER.error("[cgui] a frame of '{}' failed", name, failed);
            frame = null;
            content.failed(failed);
        }
        view.frameLog().committed(started, System.nanoTime() - started);
        boolean moves = thread.compositesMotion();
        return new UiCommit(frame, out, ++commits, moves ? CompositorMotion.follow(document, frame) : null,
                moves ? CompositorMotion.motions(document, frame) : List.of());
    }

    /** Presents {@code commit} at {@code stage} at once: where the frame is recorded in the host's frame. */
    void show(@Nullable CgStageFrame stage, UiCommit commit) {
        if (stage != null && commit.frame() != null) view.presenter().present(stage, commit.frame());
    }
}
