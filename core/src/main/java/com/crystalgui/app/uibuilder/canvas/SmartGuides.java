package com.crystalgui.app.uibuilder.canvas;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.transform.Transform;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.surface.snap.SnapAxis;
import com.crystalgui.widget.surface.snap.SnapIndicator;
import com.crystalgui.widget.surface.snap.SnapIndicators;
import com.crystalgui.widget.text.UIText;

import dev.vfyjxf.taffy.style.TaffyPosition;

/**
 * <b>What the live gesture snapped to</b>, drawn over the canvas — one layer for every gesture that snaps.
 *
 * <pre>{@code
 * ctx.smartGuides().show(node.parentElement(), snap.indicators());   // each update
 * ctx.smartGuides().clear();                                          // when the gesture ends
 * }</pre>
 *
 * <p>tldraw's split: a tool solves and hands over what it found, and one overlay draws it. A line through
 * every aligned point with a × at each, and a bar with its length in each matched gap, in the guides' red
 * and one pixel at any zoom.</p>
 *
 * <ul>
 *   <li>{@code space} is the element whose LAYOUT box the indicators are measured from — the moving
 *       node's parent.</li>
 *   <li><b>Clear it when the gesture ends.</b> Nothing else takes it down.</li>
 * </ul>
 */
public final class SmartGuides extends UIElement {

    public static final Name NAME = Name.of("smartguides");

    public static final String LAYER_CLASS = "__smart-guides__";

    /** A matched gap's length, beside its bar. */
    public static final String GAP_LABEL_CLASS = "__snap-gap-label__";

    /** Matched to {@code SelectionOutline}'s: the two mark the same edge and must read as one line. */
    private static final float GUIDE_THICKNESS = 1f;

    /** Between a gap's bar and its label, in screen pixels. */
    private static final float LABEL_GAP = 3f;

    @Nullable
    private UIElement space;

    private List<SnapIndicator> indicators = List.of();

    /** The gaps being labelled, in label order. @see #placeLabels */
    private List<SnapIndicator.Gap> labelled = List.of();

    /** Grown on demand and never shrunk; the ones not needed are hidden. */
    private final List<UIElement> labels = new ArrayList<>();

    private final List<UIText> labelTexts = new ArrayList<>();

    public SmartGuides() {
        super(NAME);
        addClass(LAYER_CLASS);
        set(Attribute.HIT_TEST, false);
        set(Attribute.HIT_TRANSPARENT, true);
        StyleGroup.defaultPipeline(getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE).left(0f).top(0f)
                        .widthPercent(100f).heightPercent(100f));
    }

    /** Shows {@code found}, measured from {@code space}'s layout box, replacing whatever was shown. */
    public void show(@Nullable UIElement space, List<SnapIndicator> found) {
        this.space = found.isEmpty() ? null : space;
        // ONE OUTLINE PER OWNER: a box with a point on both axes' guides is named by both.
        List<SnapIndicator> kept = new ArrayList<>(found.size());
        for (SnapIndicator indicator : found) {
            if (indicator instanceof SnapIndicator.Owner owner && outlines(kept, owner)) continue;
            kept.add(indicator);
        }
        indicators = List.copyOf(kept);
        List<SnapIndicator.Gap> gaps = new ArrayList<>();
        for (SnapIndicator indicator : indicators) {
            if (indicator instanceof SnapIndicator.Gap gap) gaps.add(gap);
        }
        while (labels.size() < gaps.size()) addLabel();
        for (int i = 0; i < labels.size(); i++) {
            boolean shown = i < gaps.size();
            if (shown) labelTexts.get(i).setText(Integer.toString(Math.round(gaps.get(i).length())));
            if (labels.get(i).isDisplayed() != shown) labels.get(i).setDisplayed(shown);
        }
        labelled = gaps;
    }

    public void clear() {
        show(null, List.of());
    }

    private static boolean outlines(List<SnapIndicator> kept, SnapIndicator.Owner owner) {
        for (SnapIndicator indicator : kept) {
            if (indicator instanceof SnapIndicator.Owner other && other.xs() == owner.xs()) return true;
        }
        return false;
    }

    /** What is shown now. For a test, and for anyone reading the overlay. */
    public List<SnapIndicator> indicators() {
        return indicators;
    }

    private void addLabel() {
        UIElement label = new UIElement();
        label.addClass(GAP_LABEL_CLASS);
        label.setHitTest(false);
        label.setDisplayed(false);
        StyleGroup.defaultPipeline(label.getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE).left(0f).top(0f));
        UIText text = new UIText();
        label.append(text);
        append(label);
        labels.add(label);
        labelTexts.add(text);
    }

    /**
     * The space's box as drawn in this layer: the solver's (0, 0) and extent on screen. Drawn rather than
     * laid out, because the space's children are placed inside its own transform.
     */
    @Nullable
    private float[] area() {
        return space == null ? null : CanvasRects.of(space, this);
    }

    /** Solver units to pixels here. @see CanvasRects#scaleOf */
    private float scaleOf(float[] area) {
        return CanvasRects.scaleOf(area, space == null ? null : space.box());
    }

    @Override
    public void paintContent(CgUiPaintContext paint, Box box) {
        if (box == null || indicators.isEmpty()) return;
        float[] area = area();
        if (area == null) return;
        int colour = getStyle().computed().get(StylePropertyRegistry.COLOR);
        // The owners' outlines take the layer's border colour, which the sheet sets fainter.
        int ownerColour = getStyle().computed().get(StylePropertyRegistry.BORDER_COLOR);
        // Half the stroke, so the coordinate stays the line's centre and it lies on the selection
        // outline rather than beside it.
        SnapIndicators.paint(paint, indicators, area[0], area[1], scaleOf(area), colour, ownerColour,
                GUIDE_THICKNESS * 0.5f);
    }

    /** Beside each labelled bar: above a horizontal one, right of a vertical one. Post-layout, sized. */
    private void placeLabels() {
        if (labelled.isEmpty()) return;
        float[] area = area();
        if (area == null) return;
        float scale = scaleOf(area);
        for (int i = 0; i < labelled.size() && i < labels.size(); i++) {
            Box label = labels.get(i).box();
            if (label == null) continue;
            SnapIndicator.Gap gap = labelled.get(i);
            float middle = (gap.from() + gap.to()) * 0.5f * scale;
            float cross = gap.cross() * scale;
            boolean horizontal = gap.axis() == SnapAxis.HORIZONTAL;
            float x = horizontal ? area[0] + middle - label.width() * 0.5f : area[0] + cross + LABEL_GAP;
            float y = horizontal ? area[1] + cross - label.height() - LABEL_GAP
                    : area[1] + middle - label.height() * 0.5f;
            label.setTransform(Transform.translate(x, y));
        }
    }

    @Override
    protected void connected() {
        super.connected();
        document().animation().afterLayout(this, delta -> {
            placeLabels();
            return true;
        });
    }
}
