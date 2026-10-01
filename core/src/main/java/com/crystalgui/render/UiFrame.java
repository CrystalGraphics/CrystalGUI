package com.crystalgui.render;

import com.crystalgraphics.render.graph.CgFrame;
import com.crystalgraphics.render.graph.CgFrameBuilder;

/**
 * One UI frame, recorded and built: what {@link UiGpu#present} executes. It refers to no recorder state — the
 * recorder records its next frame at once — so it may be built on one thread and presented on the render thread.
 *
 * <pre>{@code
 * recorder.recordFrame(width, height);       // the document's thread; no GL
 * document.paint(recorder);
 * UiFrame frame = recorder.seal();           // ordered, batched and packed here
 *
 * UiGpu.present(frame);                      // render thread, inside the host's frame
 * }</pre>
 *
 * <ul>
 *   <li>Present each frame once: presenting hands its buffers back to the recorder's builder.</li>
 *   <li>Two frames of one recorder may be in flight at once; a recorder whose frames are never presented keeps
 *       building new buffers.</li>
 * </ul>
 */
public final class UiFrame {

    final CgFrame frame;
    final CgFrame present;
    final CgFrameBuilder builder;
    private final int width, height;

    UiFrame(CgFrame frame, CgFrame present, CgFrameBuilder builder, int width, int height) {
        this.frame = frame;
        this.present = present;
        this.builder = builder;
        this.width = width;
        this.height = height;
    }

    /** The surface size it was recorded at, in pixels. */
    public int width() {
        return width;
    }

    /** @see #width() */
    public int height() {
        return height;
    }
}
