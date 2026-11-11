package com.crystalgui.render;

import com.crystalgraphics.render.canvas.CgCanvasMaterials;
import com.crystalgraphics.render.canvas.CgShapeTable;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgui.core.trace.UiTrace;
import org.jspecify.annotations.Nullable;

/**
 * Shows a document's frames at a GUI stage: executes each {@link UiFrame} at its place in the firing
 * ({@link CgStageFrame#include}) and composites its picture onto the stage's target with the stage's canvas, under the
 * stage's camera. Keeps the last frame, so a host frame with no new one shows it again, moved or as it was. One per
 * {@link CgUiRenderer.View}, beside the paint context whose target it shows.
 *
 * <pre>{@code
 * UiPresenter presenter = view.presenter();
 * CgRenderStage.SCREEN.register(CgRenderStage.VIEWPORT_WIDGETS, stage -> {
 *     UiFrame next = sequence.poll();
 *     if (next != null) presenter.present(stage, next);               // executed, composited, kept
 *     else if (moved) presenter.redraw(stage, true);                   // the kept frame again, under its values now
 *     else presenter.presentAgain(stage);                              // the kept picture, composited again
 * });
 * }</pre>
 *
 * <ul>
 *   <li>Render thread, inside a renderer's turn at a GUI stage, which gives it the host's target and section and times
 *       it under the stage's GPU zone; the frame's own passes as {@code gpu:ui} inside it.</li>
 *   <li>A frame recorded at another size than the stage's still composites at its own; {@link #redraw} and
 *       {@link #presentAgain} show nothing once the stage's size has changed.</li>
 *   <li>Its view's {@link CgUiRenderer.View#close() close} hands the kept frame's buffers back.</li>
 * </ul>
 */
public final class UiPresenter {

    private static final int GPU_UI = CgGpuTrace.name("ui");
    /** The other half of the frame the document opened, once its passes have executed. Held: no lambda a frame. */
    private static final Runnable FRAME_END = UiTrace::frameEnd;

    @Nullable
    private UiFrame shown;

    UiPresenter() {
    }

    /** Executes {@code frame} at this point of {@code stage}'s firing, composites it, and keeps it in place of the last. */
    public void present(CgStageFrame stage, UiFrame frame) {
        UiFrame last = shown;
        shown = frame;
        // Recycled at once: the last frame executed in an earlier firing, and the builder's buffers it holds are free.
        if (last != null) last.builder.recycle(last.frame);
        stage.include(frame.frame).timed(GPU_UI).then(FRAME_END);
        UiPhotograph.recordIfDue(stage.recording(), frame.picture());
        composite(stage, frame);
    }

    /**
     * Executes the kept frame again with its {@link UiFrame#values()} as they stand now, and composites it: a compositor's
     * scroll, move or fade with nothing recorded or built.
     *
     * <pre>{@code
     * presenter.presented().values().translate(listBox.scrolledNode(frameId), 0f, -scrolledPx);
     * presenter.redraw(stage, true);
     * }</pre>
     *
     * <p>What a recording decided in the target's pixels stays as recorded: what was culled, a layer's region, a
     * backdrop's capture. A move far enough to reach those wants a new frame.</p>
     *
     * @param keepRequested skip every pass that writes a requested texture -- a surface, a shader-graph preview --
     *                      leaving it as the frame's first execution did. A move under property values changes none of
     *                      them; false paints them again
     * @return whether it drew: false with nothing kept, or at another size than the kept frame's
     */
    public boolean redraw(CgStageFrame stage, boolean keepRequested) {
        UiFrame frame = shown;
        if (!fits(stage, frame)) return false;
        stage.includeAgain(frame.frame, keepRequested).timed(GPU_UI).then(FRAME_END);
        composite(stage, frame);
        return true;
    }

    /**
     * Composites the kept frame's picture again, executing nothing: a host frame that arrives before a new frame was
     * sealed. The picture is as its last execution left it.
     *
     * @return whether it drew: false with nothing kept, or at another size than the kept frame's
     */
    public boolean presentAgain(CgStageFrame stage) {
        UiFrame frame = shown;
        if (!fits(stage, frame)) return false;
        composite(stage, frame);
        return true;
    }

    /** The frame last presented, whose values {@link #redraw} draws with; null before the first. Render thread. */
    @Nullable
    public UiFrame presented() {
        return shown;
    }

    /** Hands the kept frame's buffers back and forgets it. Render thread. */
    void release() {
        UiFrame last = shown;
        shown = null;
        if (last != null) last.builder.recycle(last.frame);
    }

    private static boolean fits(CgStageFrame stage, @Nullable UiFrame frame) {
        return frame != null && frame.width() == stage.host().width() && frame.height() == stage.host().height();
    }

    /** The picture over the stage's target, one premultiplied quad in the window's pixels; row 0 of a target is its bottom. */
    private static void composite(CgStageFrame stage, UiFrame frame) {
        stage.canvas().quad(CgCanvasMaterials.SCREEN.box()).texture(0, frame.picture())
                .custom2(CgShapeTable.PREMULTIPLIED).at(0f, 0f).size(frame.width(), frame.height()).uv(0f, 1f, 1f, 0f)
                .submit();
    }
}
