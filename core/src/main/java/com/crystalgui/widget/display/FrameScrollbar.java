package com.crystalgui.widget.display;

import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.box.Measurable;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.event.MouseEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * The scrollbar under a frame strip: where its view is in the whole ring, and the handle that moves it.
 *
 * <pre>{@code
 * FrameStripTrack strip = new FrameStripTrack();
 * FrameScrollbar bar = new FrameScrollbar(strip);   // below it; reads and writes strip's view
 * bar.onGestureStart(() -> model.setFollowing(false));
 * }</pre>
 *
 * <h3>Gestures</h3>
 *
 * <ul>
 *   <li><b>Drag the thumb</b> to scrub along the ring.</li>
 *   <li><b>Drag either end of the thumb</b> to zoom — the thumb is the view, so a narrower thumb is fewer
 *       frames across the strip. Both ends grab even while the whole ring is showing, which is how a
 *       zoom starts without the wheel.</li>
 *   <li><b>Press the track</b> to centre the view there, and keep dragging to scrub from it.</li>
 *   <li><b>Wheel</b> scrolls along the ring.</li>
 * </ul>
 *
 * <p>Perfetto's and Chrome DevTools' overview do the same with a brush over a miniature. The strip IS the
 * miniature here, so the bar is only the brush.</p>
 */
public class FrameScrollbar extends UIElement implements Measurable {

    public static final Name NAME = Name.of("framescrollbar");

    private enum Grab { NONE, BODY, START, END }

    /** How far inside each end of the thumb a press takes that end rather than the body, in pixels. */
    private static final float GRIP = 8f;
    /** The narrowest a thumb is drawn, so a deep zoom still leaves something to take hold of. */
    private static final float MIN_THUMB = 2f * GRIP + 4f;
    private static final float HEIGHT = 12f;

    private final FrameSeriesTrack target;
    private final List<Runnable> gestureListeners = new ArrayList<>(2);

    private Grab grab = Grab.NONE;
    private Grab hover = Grab.NONE;
    private float pressX;
    private double fromAtPress;
    private double spanAtPress;

    public FrameScrollbar(FrameSeriesTrack target) {
        super(NAME);
        this.target = target;
        refusePublicChildren();
        target.onViewChanged(this::repaint);

        onMouseDown.attachListener((element, event) -> {
            if (event.getButtonId() != CgMouseCodes.LEFT_BUTTON || target.frames() == 0) return;
            UIDocument document = document();
            if (document != null) document.input().setPointerCapture(this);
            for (Runnable listener : gestureListeners) listener.run();
            float x = localX(event);
            grab = grabAt(x);
            if (grab == Grab.NONE) {
                // THE TRACK: centre the view under the press, then scrub on from there.
                double span = target.visible();
                target.setView(framesAt(x) - span * 0.5d, span);
                grab = Grab.BODY;
            }
            pressX = x;
            fromAtPress = target.viewFrom();
            spanAtPress = target.visible();
            event.stopPropagation();
        }, false, true);
        onMouseMove.attachListener((element, event) -> {
            float x = localX(event);
            if (grab == Grab.NONE) {
                Grab over = grabAt(x);
                if (over != hover) {
                    hover = over;
                    repaint();
                }
                return;
            }
            double moved = (x - pressX) / Math.max(1f, width()) * target.frames();
            double end = fromAtPress + spanAtPress;
            switch (grab) {
                case BODY -> target.setView(fromAtPress + moved, spanAtPress);
                case START -> {
                    double from = Math.min(end - 1d, fromAtPress + moved);
                    target.setView(from, end - from);
                }
                case END -> target.setView(fromAtPress, Math.max(1d, spanAtPress + moved));
                default -> {
                }
            }
        }, false, true);
        onMouseUp.attachListener((element, event) -> {
            if (grab == Grab.NONE || event.getButtonId() != CgMouseCodes.LEFT_BUTTON) return;
            grab = Grab.NONE;
            UIDocument document = document();
            if (document != null) document.input().releasePointerCapture();
            repaint();
        }, false, true);
        onMouseLeave.attachListener((element, event) -> {
            if (grab != Grab.NONE || hover == Grab.NONE) return;
            hover = Grab.NONE;
            repaint();
        }, false, true);
        onMouseScroll.attachListener((element, event) -> {
            if (!target.isZoomed() || event.getScroll() == 0f) return;
            // A POSITIVE notch is the wheel rolled DOWN, which reads as LATER along a horizontal bar.
            target.panBy(event.getScroll() * target.visible() * 0.1d);
            event.stopPropagation();
        }, false, true);
    }

    /** Hears a press, before anything moves — what lets an owner that follows the newest frame stop. */
    public void onGestureStart(Runnable listener) {
        if (listener != null) gestureListeners.add(listener);
    }

    public FrameSeriesTrack target() {
        return target;
    }

    /** Where the thumb is, in this bar's pixels: {@code [left, width]}. */
    public float[] thumb() {
        float width = Math.max(1f, width());
        int frames = Math.max(1, target.frames());
        float left = (float) (target.viewFrom() / frames * width);
        float span = (float) Math.max(MIN_THUMB, target.visible() / frames * width);
        return new float[]{Math.min(left, width - span), span};
    }

    private Grab grabAt(float x) {
        float[] thumb = thumb();
        float left = thumb[0];
        float right = left + thumb[1];
        if (x < left || x > right) return Grab.NONE;
        if (x <= left + GRIP) return Grab.START;
        if (x >= right - GRIP) return Grab.END;
        return Grab.BODY;
    }

    private double framesAt(float x) {
        return x / Math.max(1f, width()) * target.frames();
    }

    private float width() {
        Box box = box();
        return box == null ? 1f : box.width();
    }

    private float localX(MouseEvent event) {
        return toLocal(event.getPosition().x(), event.getPosition().y()).x;
    }

    @Override
    public Size measure(Constraints constraints) {
        float width = constraints.hasKnownWidth() ? constraints.knownWidth()
                : constraints.hasAvailableWidth() ? constraints.availableWidth() : 256f;
        return new Size(width, HEIGHT);
    }

    /**
     * The thumb in {@code color}, its two grips in {@code border-color}. Square: a rounded rect takes the
     * SDF material, which does not keep painter's order with the batched plain rects around it.
     */
    @Override
    public void paintContent(CgUiPaintContext ctx, Box box) {
        if (target.frames() == 0) return;
        int thumbColor = computedStyle().get(StylePropertyRegistry.COLOR);
        int accent = computedStyle().get(StylePropertyRegistry.BORDER_COLOR);
        float[] thumb = thumb();
        float height = box.height();
        boolean active = grab != Grab.NONE || hover != Grab.NONE;
        int body = active ? (thumbColor & 0x00FFFFFF) | 0xE0000000 : (thumbColor & 0x00FFFFFF) | 0xA0000000;
        ctx.rect().at(thumb[0], 2f).size(thumb[1], height - 4f).fillColor(body).submit();
        // THE GRIPS say the ends can be taken hold of; lit in the accent while one is under the pointer.
        paintGrip(ctx, thumb[0] + 3f, height, hover == Grab.START || grab == Grab.START ? accent : 0xFF000000);
        paintGrip(ctx, thumb[0] + thumb[1] - 5f, height, hover == Grab.END || grab == Grab.END ? accent : 0xFF000000);
        ctx.flush();
    }

    private static void paintGrip(CgUiPaintContext ctx, float x, float height, int argb) {
        int color = (argb & 0x00FFFFFF) | 0x90000000;
        ctx.rect().at(x, 4f).size(1f, height - 8f).fillColor(color).submit();
        ctx.rect().at(x + 2f, 4f).size(1f, height - 8f).fillColor(color).submit();
    }
}
