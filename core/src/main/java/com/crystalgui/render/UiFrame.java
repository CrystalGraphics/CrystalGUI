package com.crystalgui.render;

import com.crystalgraphics.render.graph.CgFrame;
import com.crystalgraphics.render.graph.CgFrameBuilder;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.property.CgPropertyValues;

/**
 * One UI frame, recorded and built: the passes that paint the document into its picture, a texture of the paint
 * context's, which a {@link UiPresenter} executes at a GUI stage and composites there. It refers to no paint-context
 * state, so it may be built on the document's thread and presented on the render thread.
 *
 * <pre>{@code
 * ctx.recordFrame(width, height);            // the document's thread; no GPU
 * document.paint(ctx);
 * UiFrame frame = ctx.seal();                // ordered, batched and packed here
 *
 * presenter.present(stage, frame);           // render thread, in a SCREEN or HUD renderer's turn
 * }</pre>
 *
 * <p>Its spatial and effect nodes are drawn with its {@link #values()}: a compositor moves what the frame recorded by
 * writing them and asking the presenter to {@linkplain UiPresenter#redraw redraw}, with nothing recorded or built
 * again.</p>
 *
 * <pre>{@code
 * frame.values().translate(window.movedNode(frame.frameId()), dx, dy);
 * presenter.redraw(stage, true);             // the last presented frame, executed again
 * }</pre>
 *
 * <ul>
 *   <li>Present each frame once: its presenter hands its buffers back to the context's builder when the next replaces it.</li>
 *   <li>Two frames of one context may be in flight at once; a context whose frames are never presented keeps
 *       building new buffers.</li>
 * </ul>
 */
public final class UiFrame {

    final CgFrame frame;
    final CgFrameBuilder builder;
    private final CgGraphTexture picture;
    private final CgPropertyValues values;
    private final long frameId;
    private final int width, height;

    UiFrame(CgFrame frame, CgFrameBuilder builder, CgGraphTexture picture, CgPropertyValues values, long frameId,
            int width, int height) {
        this.frame = frame;
        this.builder = builder;
        this.picture = picture;
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

    /** The texture it paints into, premultiplied, its row 0 at the bottom: what the presenter composites. */
    public CgGraphTexture picture() {
        return picture;
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
