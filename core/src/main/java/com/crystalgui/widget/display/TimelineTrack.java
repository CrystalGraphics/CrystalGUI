package com.crystalgui.widget.display;

import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgraphics.text.render.CgTextRenderer;
import com.crystalgui.render.text.FontFamilyCache;
import com.crystalgui.render.text.TextShadowStyle;
import com.crystalgui.render.text.TextStrokeStyle;
import com.crystalgui.style.GeneralGroup;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.box.Measurable;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.event.MouseEvent;

import javax.annotation.Nullable;

/**
 * One row on a time axis — the primitive every band of a profiler window is drawn with.
 *
 * <p>Subclass it and paint; this owns the axis, the gestures and the way back from a pixel to a
 * nanosecond. {@link SpanTrack} draws zones, {@link FrameStripTrack} draws frame bars, and a counter
 * row is the same class with a different {@link #paintTrack}.</p>
 *
 * <pre>{@code
 * TimelineAxis axis = new TimelineAxis();
 * SpanTrack track = new SpanTrack(axis);
 * track.setRows(rows);
 * }</pre>
 *
 * <h3>Spans are not elements</h3>
 *
 * <p>A frame records hundreds of zones and a chart may show thousands. As {@code UIElement}s that
 * would be thousands of Taffy nodes rebuilt on every selection change — <b>inside the tool that exists
 * to catch exactly that</b>. So a track is one leaf that paints its own content, and hit-testing is
 * arithmetic against the axis rather than a tree walk.</p>
 *
 * <h3>Gestures</h3>
 *
 * <ul>
 *   <li><b>Wheel</b> zooms about the pointer, so what you are looking at stays under it.</li>
 *   <li><b>Drag</b> pans, in the direction the content moves rather than the direction the window
 *       does — the convention of every map and every timeline.</li>
 *   <li><b>Click</b> is handed to {@link #onPicked} as a time, for the subclass to resolve.</li>
 * </ul>
 *
 * <p>Zoom and pan are only offered when {@link #setNavigable} is on. The frame strip turns it off: it
 * shows a fixed ring and a drag there means a RANGE SELECTION, which is a different gesture on the
 * same button and cannot share it.</p>
 */
public abstract class TimelineTrack extends UIElement implements Measurable {

    /** How much one wheel notch zooms. Gentle enough to land on a target rather than past it. */
    private static final double ZOOM_PER_NOTCH = 1.25d;

    private final TimelineAxis axis;

    private boolean navigable = true;
    private boolean dragging;
    private float dragLastX;

    /** Where the pointer is, in track pixels, or NaN when it is elsewhere. */
    private float pointerX = Float.NaN;
    private float pointerY = Float.NaN;

    protected TimelineTrack(Name name, TimelineAxis axis) {
        super(name);
        this.axis = axis;
        refusePublicChildren();
        // The axis is shared, so its own change is this track's repaint: one scroll moves every row.
        axis.onChanged(this::repaint);

        onMouseScroll.attachListener((element, event) -> {
            if (!navigable) return;
            float local = localX(event);
            // A POSITIVE notch is the wheel rolled DOWN (HostPointer.scroll), which zooms OUT.
            double factor = event.getScroll() > 0f ? 1d / ZOOM_PER_NOTCH : ZOOM_PER_NOTCH;
            axis.setPixels(width());
            axis.zoomAt(local, factor);
            event.stopPropagation();
        }, false, true);
        onMouseDown.attachListener((element, event) -> {
            // THE MIDDLE BUTTON PANS, on every row, whatever the left button means there -- the
            // convention of every map and every node editor, and the one gesture a strip whose left drag
            // is a range selection has for moving.
            if (event.getButtonId() == CgMouseCodes.MIDDLE_BUTTON) {
                UIDocument document = document();
                if (document != null) document.input().setPointerCapture(this);
                panning = true;
                dragLastX = localX(event);
                event.stopPropagation();
                return;
            }
            // ONLY THE LEFT BUTTON picks and selects. The right is left for a menu.
            if (event.getButtonId() != CgMouseCodes.LEFT_BUTTON) return;
            // CAPTURED, so the gesture survives the pointer leaving the track. A flame row is a few
            // pixels tall and a pan drifts vertically; a strip range is released wherever the hand
            // stops. Without capture the first ended the pan halfway and the second never heard the
            // release, leaving a range half-drawn for the next hover to extend.
            UIDocument document = document();
            if (document != null) document.input().setPointerCapture(this);
            pressed = true;
            travelled = false;
            pointerX = localX(event);
            pressX = pointerX;
            if (navigable) {
                dragging = true;
                dragLastX = pointerX;
            }
            onPressed(pointerX, localY(event), axis.timeAt(pointerX));
        }, false, true);
        onMouseMove.attachListener((element, event) -> {
            float local = localX(event);
            if (pressed && Math.abs(local - pressX) > CLICK_SLOP) travelled = true;
            if (dragging || panning) {
                // NEGATED: dragging right moves the content right, which means looking EARLIER.
                panPixels(dragLastX - local);
                dragLastX = local;
            } else if (local != pointerX) {
                pointerX = local;
                pointerY = localY(event);
                onHovered(local, pointerY, axis.timeAt(local));
                repaint();
            }
        }, false, true);
        onMouseUp.attachListener((element, event) -> {
            if (panning && event.getButtonId() == CgMouseCodes.MIDDLE_BUTTON) {
                panning = false;
                UIDocument document = document();
                if (document != null) document.input().releasePointerCapture();
                return;
            }
            if (!pressed || event.getButtonId() != CgMouseCodes.LEFT_BUTTON) return;
            pressed = false;
            dragging = false;
            UIDocument document = document();
            if (document != null) document.input().releasePointerCapture();
            float local = localX(event);
            // A PAN IS NOT A CLICK. Releasing a drag over a span must not select it -- the gesture was
            // about where to look, not what to pick. A track whose drag MEANS something (the strip's
            // range) is not navigable and always hears the release.
            if (navigable && travelled) return;
            onPicked(local, localY(event), axis.timeAt(local));
        }, false, true);
        onMouseLeave.attachListener((element, event) -> {
            if (pressed || panning) return;       // captured: the gesture is still ours
            pointerX = Float.NaN;
            onHovered(Float.NaN, Float.NaN, 0L);
            repaint();
        }, false, true);
    }

    /** How far a press may wander and still count as a click, in track pixels. */
    private static final float CLICK_SLOP = 3f;

    private boolean pressed;
    private boolean travelled;
    /** A middle-button drag in progress. */
    private boolean panning;

    /**
     * Moves the view by {@code pixels}; positive looks later. The time axis by default — a row on another
     * axis (the frame strip) moves its own.
     */
    protected void panPixels(float pixels) {
        axis.setPixels(width());
        axis.panPixels(pixels);
    }
    private float pressX;

    public TimelineAxis axis() {
        return axis;
    }

    /** Whether the wheel zooms and a drag pans. Off for a track whose drag means something else. */
    public TimelineTrack setNavigable(boolean value) {
        navigable = value;
        return this;
    }

    public boolean isNavigable() {
        return navigable;
    }

    /** Where the pointer is in track pixels, or {@code NaN}. */
    protected float pointerX() {
        return pointerX;
    }

    protected float pointerY() {
        return pointerY;
    }

    protected float width() {
        Box box = box();
        return box == null ? 1f : box.width();
    }

    /**
     * The pointer in this track's own pixels — the space the axis is sized in.
     *
     * <p>Through {@link #toLocal}, never by subtracting {@code box().worldX()}. World space is the
     * SURFACE: the UI scale is baked into every box's {@code localToWorld}, and a pointer arrives in
     * surface pixels too. Subtracting the world origin therefore leaves a surface-pixel offset, which
     * the axis — sized from the box's logical width — read as twice as far along at a scale of 2. Every
     * click on the strip and the flame chart landed at double its distance from the left edge.</p>
     */
    private float localX(MouseEvent event) {
        return toLocal(event.getPosition().x(), event.getPosition().y()).x;
    }

    /** As {@link #localX}, for the row a pointer is over. */
    protected float localY(MouseEvent event) {
        return toLocal(event.getPosition().x(), event.getPosition().y()).y;
    }

    // ── What a subclass answers ─────────────────────────────────────────────────────────────

    /** Paint the row, in box-local pixels. The axis has already been given this track's width. */
    protected abstract void paintTrack(CgUiPaintContext ctx, Box box);

    /** How tall this row wants to be. */
    protected abstract float trackHeight();

    /**
     * A press landed.
     *
     * <p>{@code x} and {@code y} are track pixels and {@code nanos} is the time under {@code x}. The
     * ROW comes with them because a time alone cannot say which one was clicked, and a track that had
     * to be told separately would be told late.</p>
     */
    protected void onPressed(float x, float y, long nanos) {
    }

    /** A click completed. @see #onPressed */
    protected void onPicked(float x, float y, long nanos) {
    }

    /** The pointer moved, or left — then every argument is {@code NaN}. */
    protected void onHovered(float x, float y, long nanos) {
    }

    // ── Engine hooks ────────────────────────────────────────────────────────────────────────

    @Override
    public Size measure(Constraints constraints) {
        float width = constraints.hasKnownWidth() ? constraints.knownWidth()
                : constraints.hasAvailableWidth() ? constraints.availableWidth() : 256f;
        return new Size(width, trackHeight());
    }

    @Override
    public void paintContent(CgUiPaintContext ctx, Box box) {
        // THE AXIS LEARNS THE WIDTH HERE, which is the only place it is known: layout has run, and a
        // width set at construction is a guess that every later mapping inherits.
        axis.setPixels(box.width());
        paintTrack(ctx, box);
        ctx.flush();
    }

    /**
     * Always true: a track's picture changes without its box moving.
     *
     * <p>Panning, zooming, hovering and selecting all redraw the same geometry, so a retained layer
     * would hold the first frame of a drag for the whole drag.</p>
     */
    @Override
    public boolean paintsDynamically() {
        return true;
    }

    // ── Drawing helpers, shared by every renderer ───────────────────────────────────────────

    /** The face this track's labels take, resolved from its own computed style. */
    @Nullable
    protected CgFontFamily labelFont() {
        GeneralGroup general = getStyle().getGeneralGroup();
        try {
            return FontFamilyCache.resolve(general.fontFamily(), Math.round(general.fontSize()));
        } catch (RuntimeException unavailable) {
            // No font stack in this process — a headless test, or a context with no glyphs. The row
            // still draws its bars; only the labels go.
            return null;
        }
    }

    /**
     * Draws {@code text} vertically centred in the band {@code [top, top + height]}, cut to
     * {@code maxWidth}, or nothing at all if it will not fit.
     *
     * <p>Nothing rather than an ellipsis alone: a column two characters wide showing an ellipsis is
     * noise where a bare bar is a shape, and a flame chart is mostly bars too narrow to name.</p>
     *
     * <p>Centred on the INK, ascender plus descender, not on the line box. {@code at(y)} is the top
     * of the line and the baseline sits an ascender below it, so a label placed at the band's middle
     * minus a guessed constant hangs below its bar at any size the constant was not tuned for.</p>
     */
    protected void label(CgUiPaintContext ctx, @Nullable CgFontFamily font, String text,
                         float x, float top, float height, float maxWidth, int argb) {
        if (font == null || text == null || text.isEmpty() || maxWidth < MIN_LABEL_WIDTH) return;
        float size = getStyle().getGeneralGroup().fontSize();
        float advance = Math.max(1f, size * MONO_ADVANCE_EM);
        int fits = (int) (maxWidth / advance);
        if (fits < 2) return;
        String shown = text.length() <= fits ? text : text.substring(0, Math.max(1, fits - 1)) + "…";

        var metrics = font.getLayoutMetrics();
        float ink = metrics.getAscender() + metrics.getDescender();
        float y = top + (height - ink) * 0.5f;

        CgTextRenderer.Draw draw = ctx.text().draw()
                .text(shown).family(font).at(x, y).color(argb)
                .pose(ctx.getPoseStack());
        // BOTH ARE INHERITABLE, so a declaration anywhere above reaches these glyphs exactly as it
        // reaches a label's. A widget that draws its own text and skips them leaves the properties
        // cascading correctly and drawing nothing, which is indistinguishable from their not existing.
        // currentcolor follows what is ACTUALLY drawn, which here is the caller's argb.
        GeneralGroup general = getStyle().getGeneralGroup();
        TextStrokeStyle.applyTo(draw, font, general, computedStyle(), argb);
        TextShadowStyle.applyTo(draw, general, argb);
        draw.submit();
    }

    /** Below this a label is not worth the draw call. */
    private static final float MIN_LABEL_WIDTH = 14f;

    /**
     * A monospaced face's advance, as a fraction of its size.
     *
     * <p>Judged rather than shaped: shaping every candidate label to discard most of them is the cost
     * this check exists to avoid. The tracks' labels are set in JetBrains Mono, whose advance is 0.6em,
     * and a label that errs short loses a character rather than overrunning its bar.</p>
     */
    private static final float MONO_ADVANCE_EM = 0.6f;
}
