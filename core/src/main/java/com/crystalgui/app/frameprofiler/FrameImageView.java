package com.crystalgui.app.frameprofiler;

import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.platform.gl.CgGL;
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
 * <p>Call {@link #show} outside a paint — from a refresh or an event — since it uploads the picture,
 * and an upload while painting would leave the paint context's texture binding stale.</p>
 */
public class FrameImageView extends UIElement {

    public static final Name NAME = Name.of("frameimage");

    @Nullable
    private CgFrameImages.Image shown;
    @Nullable
    private CgTexture2D texture;

    public FrameImageView() {
        super(NAME);
    }

    @Nullable
    public CgFrameImages.Image shown() {
        return shown;
    }

    /** Shows {@code image}, or nothing. Uploads only when the picture changed. */
    public void show(@Nullable CgFrameImages.Image image) {
        if (image == shown) return;
        shown = image;
        if (image != null) upload(image);
        repaint();
    }

    private void upload(CgFrameImages.Image image) {
        // RGBA, not the RGB it is stored as: GL unpacks rows on four-byte boundaries by default, and a
        // row of an odd width in RGB is not one. TOP ROW FIRST, as the picture is stored: the paint
        // context draws in a top-left space, where UV row 0 is the top. Reversed, it drew upside down.
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
        if (texture == null) {
            texture = CgTexture2D.createFromPixels(image.width(), image.height(), rgba, CgTextureSpec.RGBA8_LINEAR);
        } else {
            texture.upload(image.width(), image.height(), rgba, CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE);
        }
    }

    @Override
    public void paintContent(CgUiPaintContext ctx, Box box) {
        CgFrameImages.Image image = shown;
        if (image == null || texture == null || box.width() <= 0f || box.height() <= 0f) return;
        float scale = Math.min(box.width() / image.width(), box.height() / image.height());
        float w = image.width() * scale;
        float h = image.height() * scale;
        ctx.drawImage(texture, (box.width() - w) * 0.5f, (box.height() - h) * 0.5f, w, h, 0f, 0f, 1f, 1f, 0xFFFFFFFF);
    }

    /** The picture is kept and only its texture goes: a paused window re-shows nothing on its own. */
    @Override
    protected void disconnected() {
        super.disconnected();
        if (texture != null) {
            texture.delete();
            texture = null;
        }
    }

    @Override
    protected void connected() {
        super.connected();
        if (shown != null && texture == null) upload(shown);
    }
}
