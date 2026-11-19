package com.crystalgui.render;

import com.crystalgraphics.api.canvas.CgPhotograph;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.trace.CgFrameImages;
import com.crystalgraphics.trace.CgTrace;

/**
 * The trace's photograph of a UI frame ({@link CgFrameImages}): a {@link CgPhotograph} of the frame target, recorded
 * with the frame's present when the trace frame is due. Never waits; the picture lands frames later.
 *
 * <pre>{@code
 * UiPhotograph.recordIfDue(present, frameTarget);   // after the frame's composite is recorded
 * }</pre>
 */
final class UiPhotograph {

    private UiPhotograph() {}

    /** Records the photograph of {@code frame} into {@code recording} when the trace frame is due. Any thread. */
    static void recordIfDue(CgRecording recording, CgGraphTexture frame) {
        long index = CgTrace.currentFrameIndex();
        if (!CgFrameImages.isDue(index)) return;
        CgPhotograph.take(recording, frame, frame.getWidth(), frame.getHeight(), CgFrameImages.width(),
                (w, h, rgb) -> CgFrameImages.put(index, w, h, rgb));
    }
}
