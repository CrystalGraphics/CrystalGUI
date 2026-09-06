package com.crystalgui.ui.box;

/**
 * Intrinsic sizing as a layout-phase protocol: the layout engine asks, during layout, what size a
 * leaf wants under the constraints it has been given, and lays it out at the answer.
 *
 * <p>The old engine had zero implementors of the layout engine's measure function (audit §1). Text
 * sized itself <em>after</em> layout instead — re-wrapping against the box it had been given and
 * pushing the result back into the cascade as an {@code IMPORTANT} candidate, which re-dirtied
 * layout, which ran again, until it settled or hit a pass cap. That loop is what the box tree does
 * not have: geometry feedback goes through this, once, inside the pass (plan/engine-core.md 5.3, D5.10).</p>
 *
 * <p>A node's skin implements this; the box tree wires it to the engine's measure function. The
 * types are the protocol's own rather than the layout engine's so a skin does not import Taffy.</p>
 */
public interface Measurable {

    /** How an axis with no definite size is to be read. */
    enum Fit {
        /** As much room as it takes: one line, however long. */
        MAX_CONTENT,
        /** As little room as it can be given: the widest thing that cannot be broken. */
        MIN_CONTENT
    }

    /**
     * What the engine already knows and what room there is. {@code NaN} means "not known" for the
     * known sizes and "no definite size" for the available ones — then the {@link Fit} for that axis
     * says whether the engine is asking for the max-content or the min-content answer.
     */
    record Constraints(float knownWidth, float knownHeight, float availableWidth, float availableHeight,
                       Fit widthFit, Fit heightFit) {
        public boolean hasKnownWidth() {
            return !Float.isNaN(knownWidth);
        }

        public boolean hasKnownHeight() {
            return !Float.isNaN(knownHeight);
        }

        public boolean hasAvailableWidth() {
            return !Float.isNaN(availableWidth);
        }

        public boolean hasAvailableHeight() {
            return !Float.isNaN(availableHeight);
        }

        /** The width to wrap against: known, else available, else unbounded ({@code NaN}). */
        public float wrapWidth() {
            return hasKnownWidth() ? knownWidth : availableWidth;
        }

        /** Whether the width question is the min-content one. */
        public boolean wantsMinContentWidth() {
            return !hasKnownWidth() && !hasAvailableWidth() && widthFit == Fit.MIN_CONTENT;
        }
    }

    record Size(float width, float height) {
        public static final Size ZERO = new Size(0f, 0f);
    }

    Size measure(Constraints constraints);
}
