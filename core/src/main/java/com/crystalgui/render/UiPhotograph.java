package com.crystalgui.render;

import com.crystalgraphics.gl.buffer.CgReadback;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgTextureDesc;
import com.crystalgraphics.trace.CgFrameImages;
import com.crystalgraphics.trace.CgTrace;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The trace's photograph of a UI frame ({@link CgFrameImages}): the frame target shrunk on the GPU and read back,
 * recorded with the frame's present when the trace frame is due. Never waits; the picture lands frames later.
 *
 * <pre>{@code
 * UiPhotograph.recordIfDue(present, frameTarget);   // after the frame's composite is recorded
 * }</pre>
 *
 * <p>Halved with linear filtering until within twice the photograph's width, then copied to size: each halving is an
 * exact two-by-two box, where one linear copy from full size reads four texels of every sixteen and turns text into
 * noise.</p>
 */
final class UiPhotograph {

    /** Photographs on their way at once: three covers a GPU two frames behind. */
    private static final int SLOTS = 3;
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();

    private UiPhotograph() {}

    /** Records the photograph of {@code frame} into {@code recording} when the trace frame is due. Any thread. */
    static void recordIfDue(CgRecording recording, CgGraphTexture frame) {
        long index = CgTrace.currentFrameIndex();
        if (!CgFrameImages.isDue(index) || IN_FLIGHT.get() >= SLOTS) return;
        int fromW = frame.getWidth(), fromH = frame.getHeight();
        int w = Math.min(CgFrameImages.width(), fromW);
        int h = Math.max(1, Math.round((float) fromH * w / fromW));
        CgGraphTexture from = frame;
        while (fromW / 2 >= w * 2) {
            int stepW = fromW / 2, stepH = Math.max(1, fromH / 2);
            CgGraphTexture step = shrunk(stepW, stepH);
            recording.copy(from, 0, 0, fromW, fromH, step, 0, 0, stepW, stepH, true);
            from = step;
            fromW = stepW;
            fromH = stepH;
        }
        CgGraphTexture photo = shrunk(w, h);
        recording.copy(from, 0, 0, fromW, fromH, photo, 0, 0, w, h, true);
        IN_FLIGHT.incrementAndGet();
        recording.readback(photo, 0, 0, 0, w, h, new CgReadback.Sink() {
            @Override
            public void accept(ByteBuffer data) {
                IN_FLIGHT.decrementAndGet();
                CgFrameImages.put(index, w, h, rgb(data, w, h));
            }

            @Override
            public void failed(String reason) {
                IN_FLIGHT.decrementAndGet();
            }
        });
    }

    private static CgGraphTexture shrunk(int w, int h) {
        return CgGraphTexture.transientTexture("cgui_photograph", new CgTextureDesc(w, h, CgUiPaintContext.LAYER_FORMAT));
    }

    /** RGBA rows bottom first, as a readback answers, into RGB rows top first, as {@link CgFrameImages} keeps them. */
    private static byte[] rgb(ByteBuffer data, int w, int h) {
        byte[] rgba = new byte[w * h * 4];
        data.get(rgba);
        byte[] rgb = new byte[w * h * 3];
        for (int y = 0; y < h; y++) {
            int src = (h - 1 - y) * w * 4, dst = y * w * 3;
            for (int x = 0; x < w; x++, src += 4, dst += 3) {
                rgb[dst] = rgba[src];
                rgb[dst + 1] = rgba[src + 1];
                rgb[dst + 2] = rgba[src + 2];
            }
        }
        return rgb;
    }
}
