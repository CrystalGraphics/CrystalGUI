package com.crystalgui.app.uibuilder.canvas;

import javax.annotation.Nullable;

import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.dnd.SortPlacement;

import dev.vfyjxf.taffy.style.TaffyPosition;

/**
 * <b>Where a drop would land</b>, drawn over the canvas: the container outlined, and a line on the side of
 * the child it lands against.
 *
 * <pre>{@code
 * DropResolver.Drop drop = new DropResolver(root, ctx.dropIndicator()).resolve(sources, rawX, rawY);
 * ctx.dropIndicator().show(drop);   // each update; null shows nothing
 * ctx.dropIndicator().clear();      // when the gesture ends
 * }</pre>
 *
 * <p>Unity UI Builder's picture and GrapesJS's placeholder: the line runs across the target's flow — a
 * horizontal line between two children of a column, a vertical one in a row — with a short cap at each end
 * so it reads as a place rather than a border. An empty container gets its line along the top of its box.
 * One pixel of outline and two of line at any zoom.</p>
 *
 * <ul>
 *   <li>It is also the SPACE a drop is resolved in, so the rects it draws are the rects that decided.</li>
 *   <li>Clear it when the gesture ends; nothing else takes it down.</li>
 * </ul>
 */
public final class DropIndicator extends UIElement {

    public static final Name NAME = Name.of("dropindicator");

    public static final String LAYER_CLASS = "__drop-indicator__";

    /** The target container's outline. */
    private static final float OUTLINE = 1f;

    /** The landing line, across the flow. */
    private static final float LINE = 2f;

    /** Each cap's length, along the flow. */
    private static final float CAP = 6f;

    /** How far inside an empty container its line sits. */
    private static final float EMPTY_INSET = 4f;

    @Nullable
    private DropResolver.Drop drop;

    public DropIndicator() {
        super(NAME);
        addClass(LAYER_CLASS);
        set(Attribute.HIT_TEST, false);
        set(Attribute.HIT_TRANSPARENT, true);
        StyleGroup.defaultPipeline(getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE).left(0f).top(0f)
                        .widthPercent(100f).heightPercent(100f));
    }

    /** Shows {@code drop}, replacing what was shown; null shows nothing. */
    public void show(@Nullable DropResolver.Drop drop) {
        this.drop = drop;
        repaint();
    }

    public void clear() {
        show(null);
    }

    /** What is shown now. For a test. */
    @Nullable
    public DropResolver.Drop drop() {
        return drop;
    }

    /**
     * The landing line as {x, y, width, height} in this layer, or null for none.
     *
     * <p>Separate from the painting so it can be asserted without a GL context.</p>
     */
    @Nullable
    public static float[] lineOf(@Nullable DropResolver.Drop drop) {
        if (drop == null) return null;
        DropResolver.Against against = drop.against();
        if (against == null) {
            float[] t = drop.targetRect();
            return new float[] {t[0] + EMPTY_INSET, t[1] + EMPTY_INSET - LINE / 2f,
                    Math.max(0f, t[2] - EMPTY_INSET * 2f), LINE};
        }
        float[] r = against.rect();
        boolean after = against.side() == SortPlacement.Side.AFTER;
        if (drop.column()) {
            float y = after ? r[1] + r[3] : r[1];
            return new float[] {r[0], y - LINE / 2f, r[2], LINE};
        }
        float x = after ? r[0] + r[2] : r[0];
        return new float[] {x - LINE / 2f, r[1], LINE, r[3]};
    }

    @Override
    public void paintContent(CgUiPaintContext paint, Box box) {
        DropResolver.Drop shown = drop;
        float[] line = lineOf(shown);
        if (box == null || shown == null || line == null) return;
        int accent = getStyle().computed().get(StylePropertyRegistry.COLOR);
        CanvasRects.outline(paint, shown.targetRect(), OUTLINE, accent);
        paint.fillRect(line[0], line[1], line[2], line[3], accent);
        // THE CAPS, across the line at each end. From the drop, not the line's proportions, which a tiny
        // container can invert.
        if (shown.against() == null || shown.column()) {
            float y = line[1] + line[3] / 2f - CAP / 2f;
            paint.fillRect(line[0], y, LINE, CAP, accent);
            paint.fillRect(line[0] + line[2] - LINE, y, LINE, CAP, accent);
        } else {
            float x = line[0] + line[2] / 2f - CAP / 2f;
            paint.fillRect(x, line[1], CAP, LINE, accent);
            paint.fillRect(x, line[1] + line[3] - LINE, CAP, LINE, accent);
        }
    }
}
