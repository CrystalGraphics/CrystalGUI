package com.crystalgui.render;

import com.crystalgraphics.render.graph.CgFrame;
import com.crystalgraphics.render.graph.CgFrameBuilder;
import com.crystalgraphics.render.property.CgPropertyValues;

/**
 * One UI frame, recorded and built: what {@link UiGpu#present} executes. It refers to no paint-context state — the
 * context records its next frame at once — so it may be built on one thread and presented on the render thread.
 *
 * <pre>{@code
 * ctx.recordFrame(width, height);            // the document's thread; no GL
 * document.paint(ctx);
 * UiFrame frame = ctx.seal();                // ordered, batched and packed here
 *
 * UiGpu.present(frame);                      // render thread, inside the host's frame
 * }</pre>
 *
 * <p>Its spatial and effect nodes are drawn with its {@link #values()}: a compositor moves what the frame recorded by
 * writing them and calling {@link UiGpu#redraw}, with nothing recorded or built again.</p>
 *
 * <pre>{@code
 * int window = windowBox.movedNode(frame.frameId());
 * frame.values().translate(window, dx, dy);
 * UiGpu.redraw(width, height);               // the last presented frame, executed again
 * }</pre>
 *
 * <ul>
 *   <li>Present each frame once: presenting hands its buffers back to the context's builder.</li>
 *   <li>Two frames of one context may be in flight at once; a context whose frames are never presented keeps
 *       building new buffers.</li>
 * </ul>
 */
public final class UiFrame {

    final CgFrame frame;
    final CgFrame present;
    final CgFrameBuilder builder;
    private final CgPropertyValues values;
    private final long frameId;
    private final int width, height;

    UiFrame(CgFrame frame, CgFrame present, CgFrameBuilder builder, CgPropertyValues values, long frameId, int width,
            int height) {
        this.frame = frame;
        this.present = present;
        this.builder = builder;
        this.values = values;
        this.frameId = frameId;
        this.width = width;
        this.height = height;
    }

    /** What its nodes are drawn with: written by a compositor, read when the frame executes. Render thread. */
    public CgPropertyValues values() {
        return values;
    }

    /** The paint context's frame it was recorded as: what a box's node ids are asked by. @see CgUiPaintContext#frameId */
    public long frameId() {
        return frameId;
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
