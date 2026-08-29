package com.crystalgui.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgui.render.text.FontFamilyCache;
import com.crystalgui.testsupport.UiTestBase;
import dev.vfyjxf.taffy.geometry.FloatSize;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.FlexWrap;
import dev.vfyjxf.taffy.util.MeasureFunc;
import org.junit.Test;

/**
 * <b>Spike S1's end-to-end proof</b> — the engine half of {@code taffy/MODIFICATIONS.md} #1.
 *
 * <p>The vendored fix is asserted in {@code TaffyWrapMeasureTest} against a synthetic measure function.
 * This is the same claim through the whole stack it actually has to hold in: a real {@link MeasureFunc}
 * installed by {@link UIElement#measureFunc()}, resolving a real {@link CgFontFamily} and shaping real
 * glyphs, inside a live {@link UIWindow} whose ancestor row carries {@code flex-wrap: wrap}.</p>
 *
 * <p><b>Why this is worth its own test.</b> {@code UIText} does not use a measure function — it lays out
 * as a plain leaf and re-wraps afterwards in {@code onLayoutChanged()}, and its javadoc names this exact
 * Taffy defect as the reason ("Not something fixable without forking a third-party Maven dependency").
 * That fork now exists, so what this pins is the claim that the workaround was standing on: <b>an
 * ordinary measured leaf is now correct under a wrapping ancestor.</b> Retiring the post-layout
 * recompute is not part of M0 — it is a real engine change and belongs with the box tree — but nothing
 * can retire it while this test would fail.</p>
 *
 * <p>Deliberately <em>not</em> a {@code UIText} subclass: {@code ensureShaped} and {@code resolveFamily}
 * are private, and a test that reached through them would be pinning that class's internals rather than
 * the seam. {@link MeasuredLabel} below is what any future measured leaf looks like.</p>
 */
public class MeasureFuncUnderFlexWrapTest extends UiTestBase {

    /** Long enough to need several lines at half the row's width, and to differ if measured at the full width. */
    private static final String PARAGRAPH =
            "a measured leaf must be told its own resolved width and never its container's";

    private static final float ROW_WIDTH = 300f;

    /**
     * An ordinary element that sizes itself from shaped text through a real Taffy {@link MeasureFunc} —
     * the thing Taffy's measure protocol is for, and the thing that did not work under {@code wrap}.
     */
    private static final class MeasuredLabel extends UIElement {

        private final String text;
        /** Every width the measure function was handed, in call order. The input is the real evidence. */
        private final List<Float> measuredAt = new ArrayList<>();

        private MeasuredLabel(String text) {
            this.text = text;
        }

        @Override
        protected MeasureFunc measureFunc() {
            return (known, available) -> {
                float width = known.width;
                if (Float.isNaN(width)) {
                    width = available.width.isDefinite() ? available.width.getValue() : 0f;
                }
                measuredAt.add(width);

                CgFontFamily family = FontFamilyCache.resolve(
                        getStyle().getGeneralGroup().fontFamily(),
                        Math.round(getStyle().getGeneralGroup().fontSize()));
                // 0f is CrystalGraphics' "unbounded" convention, which is what an unconstrained
                // measure means here -- one line, however long.
                CgTextLayout laid = CgTextLayout.of(text, family).shape().layout(width, 0f);
                return new FloatSize(laid.totalWidth(), laid.totalHeight());
            };
        }
    }

    // ---------------------------------------------------------------- fixture

    /** Two measured labels sharing a fixed-width row, so each resolves to exactly half of it. */
    private MeasuredLabel[] layOutRow(FlexWrap wrap) {
        MeasuredLabel first = new MeasuredLabel(PARAGRAPH);
        MeasuredLabel second = new MeasuredLabel(PARAGRAPH);
        for (MeasuredLabel label : new MeasuredLabel[] { first, second }) {
            // flex-basis 0 + grow 1 splits the row evenly; min-width 0 removes CSS's automatic
            // minimum, which would otherwise floor each item at its min-content width and leave
            // flex nothing to distribute.
            label.layout(l -> l.flexGrow(1f).flexBasis(0f).minWidth(0f));
        }

        UIElement row = new UIElement();
        row.layout(l -> l.width(ROW_WIDTH).flexDirection(FlexDirection.ROW).flexWrap(wrap));
        row.addChildren(first, second);

        UIWindow window = new UIWindow(Ui.of(row));
        window.init(800, 800);
        window.getStyleEngine().calculateStyle(0.016f);
        window.calculateLayout();
        window.getStyleEngine().calculateStyle(0.016f);
        window.calculateLayout();
        return new MeasuredLabel[] { first, second };
    }

    // ---------------------------------------------------------------- the proof

    @Test
    public void aMeasuredLeafIsToldItsOwnWidthUnderAWrappingAncestor() {
        MeasuredLabel[] nowrap = layOutRow(FlexWrap.NO_WRAP);
        MeasuredLabel[] wrapped = layOutRow(FlexWrap.WRAP);

        float half = ROW_WIDTH / 2f;

        // The input. This is the assertion that names the defect: under `wrap`, upstream handed the
        // measure function the CONTAINER's 300 instead of this leaf's own 150.
        for (float width : wrapped[0].measuredAt) {
            assertTrue("a wrapping ancestor must not widen what a leaf is measured at -- was " + width,
                    width <= half + 0.5f);
        }
        assertTrue("the measure function must actually have run", !wrapped[0].measuredAt.isEmpty());

        // The output. Wrapping is allowed but nothing here wraps, so the two rows must be identical.
        assertEquals("a leaf's width must not depend on whether its row may wrap",
                nowrap[0].getRuntimeCache().getWidth(), wrapped[0].getRuntimeCache().getWidth(), 0.5f);
        assertEquals("nor its height -- this is the one that silently clipped the last line",
                nowrap[0].getRuntimeCache().getHeight(), wrapped[0].getRuntimeCache().getHeight(), 0.5f);
    }

    @Test
    public void aMeasuredLeafIsTallEnoughForTheTextItHolds() {
        MeasuredLabel[] wrapped = layOutRow(FlexWrap.WRAP);

        // What the defect actually cost: the box was sized for text wrapped at 300 while the box was
        // 150 wide, so the real text needed more lines than there was room for. Re-shape at the width
        // the box ended up with and require the box to be at least that tall.
        float boxWidth = wrapped[0].getRuntimeCache().getWidth();
        CgFontFamily family = FontFamilyCache.resolve(
                wrapped[0].getStyle().getGeneralGroup().fontFamily(),
                Math.round(wrapped[0].getStyle().getGeneralGroup().fontSize()));
        float needed = CgTextLayout.of(PARAGRAPH, family).shape().layout(boxWidth, 0f).totalHeight();

        assertTrue("the text needs " + needed + "px at " + boxWidth + "px wide but the box is only "
                        + wrapped[0].getRuntimeCache().getHeight() + "px tall",
                wrapped[0].getRuntimeCache().getHeight() >= needed - 0.5f);
    }
}
