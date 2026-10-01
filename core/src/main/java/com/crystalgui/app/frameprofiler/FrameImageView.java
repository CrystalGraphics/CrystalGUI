package com.crystalgui.app.frameprofiler;

import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.trace.CgFrameImages;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * One captured frame image, fitted into this element's box at its own aspect and centred.
 *
 * <pre>{@code
 * FrameImageView view = new FrameImageView();
 * view.show(CgFrameImages.atOrBefore(frame.index()));   // null shows nothing
 * }</pre>
 *
 * <p>{@link #show} only keeps the picture; the next paint records its upload, so neither touches GL.</p>
 */
public class FrameImageView extends UIElement {

    public static final Name NAME = Name.of("frameimage");

    @Nullable
    private CgFrameImages.Image shown;
    /** {@link #shown} as RGBA, until a paint records its upload. */
    @Nullable
    private ByteBuffer pending;
    /** The picture's texture, requested from {@link #owner}, which releases it. */
    @Nullable
    private CgGraphTexture texture;
    @Nullable
    private CgUiPaintContext owner;

    public FrameImageView() {
        super(NAME);
    }

    @Nullable
    public CgFrameImages.Image shown() {
        return shown;
    }

    /** Shows {@code image}, or nothing. The picture is uploaded only when it changed. */
    public void show(@Nullable CgFrameImages.Image image) {
        if (image == shown) return;
        shown = image;
        pending = image == null ? null : rgba(image);
        repaint();
    }

    /**
     * RGBA, not the RGB it is stored as: GL unpacks rows on four-byte boundaries by default, and a row of an odd width
     * in RGB is not one. TOP ROW FIRST, as the picture is stored: the paint context draws in a top-left space, where
     * UV row 0 is the top. Reversed, it drew upside down.
     */
    private static ByteBuffer rgba(CgFrameImages.Image image) {
        int w = image.width();
        int h = image.height();
        ByteBuffer rgba = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        byte[] rgb = image.rgb();
        for (int y = 0; y < h; y++) {
            for (int x = 0, j = y * w * 3; x < w; x++, j += 3) {
                rgba.put(rgb[j]).put(rgb[j + 1]).put(rgb[j + 2]).put((byte) 0xFF);
            }
        }
        rgba.flip();
        return rgba;
    }

    @Override
    public void paintContent(CgUiPaintContext ctx, Box box) {
        CgFrameImages.Image image = shown;
        if (image == null || box.width() <= 0f || box.height() <= 0f) return;
        int iw = image.width(), ih = image.height();
        if (texture == null || owner != ctx || texture.getWidth() != iw || texture.getHeight() != ih) {
            release();
            texture = ctx.requestLayer("cgui_frame_image", iw, ih);
            owner = ctx;
            if (pending == null) pending = rgba(image);
        }
        if (pending != null) {
            ByteBuffer pixels = pending;
            pending = null;
            ctx.upload(texture, target -> ((CgTexture2D) target.getColorTexture(0))
                    .upload(iw, ih, pixels, CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE));
        }
        float scale = Math.min(box.width() / iw, box.height() / ih);
        float w = iw * scale;
        float h = ih * scale;
        ctx.drawImage(texture, (box.width() - w) * 0.5f, (box.height() - h) * 0.5f, w, h, 0f, 0f, 1f, 1f, 0xFFFFFFFF);
    }

    /** The picture is kept and only its texture goes: a paused window re-shows nothing on its own. */
    @Override
    protected void disconnected() {
        super.disconnected();
        release();
    }

    private void release() {
        if (texture != null && owner != null) owner.releaseTexture(texture);
        texture = null;
        owner = null;
    }
}
