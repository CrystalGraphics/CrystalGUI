package com.crystalgui.ui.input;

import com.crystalgraphics.platform.input.CgModifiers;

/**
 * The arithmetic of dragging a number: pointer movement in, a new value out.
 *
 * <h3>What this is</h3>
 * <p>Press a numeric field's label and slide to change its value — right/up increases, left/down
 * decreases. There is no web equivalent (CSS never enumerated the gesture, so every browser app
 * hand-rolls it), which is why every vendor names it differently: Unity drags a field's label, Blender
 * drags a number button, Photoshop calls them <i>scrubby sliders</i>, and Dear ImGui calls it
 * {@code DragFloat}. <b>ImGui is the port source</b> — it is MIT, per this project's licence table, and
 * Unity's editor source is not. Its speed model and its modifier split are what was taken.</p>
 *
 * <h3>Why it is a pure function and not part of the widget</h3>
 * <p>All of the behaviour worth pinning is arithmetic: the sign per axis, the sensitivity curve, the
 * modifier multipliers, rounding, clamping. Holding that in {@code NumberControl} would mean every test
 * of it needed a window, a font stack and an input handler — the same argument that moved
 * {@code MoveOperations} and friends out of {@code TextEditor}, and it paid off there within minutes.</p>
 *
 * <h3>A pixel is worth a share of the field's range</h3>
 * <p>ImGui's default: a field with a range moves a hundredth of it per pixel, and one without moves one
 * unit per pixel. So 0..1 moves 0.01, 0..100 moves 1, and an unbounded coordinate moves 1 — the same
 * hand movement crosses the same share of every bounded field. Shift is ten times that, Ctrl a tenth.</p>
 *
 * <pre>{@code
 * DragScrub.unitsPerPixel(Spec.FLOAT.withRange(0, 1), 0);     // 0.01
 * DragScrub.unitsPerPixel(Spec.FLOAT, CgModifiers.SHIFT);      // 10
 * DragScrub.unitsPerPixel(Spec.FLOAT.withRate(0.25), 0);      // 0.25, declared
 * }</pre>
 *
 * <h3>Everything is measured from the ANCHOR, never from the live value</h3>
 * <p>The value is the one the drag began on plus the whole travel. Accumulating onto the live value
 * <b>compounds</b> — {@code UIResizer} documents the same trap in its own {@code startWidth} fields — and
 * worse here, because the live value is <em>clamped</em>: at a range limit it absorbs the overshoot, so
 * coming back off the limit lags by however far you pushed past it.</p>
 * <p>Because the result depends only on (anchor, delta), it is safe for a caller to recompute it every
 * frame from an unchanged delta — which {@code UIDragController} will do, since it ticks its listener
 * unconditionally rather than only on movement.</p>
 */
public final class DragScrub {

    /** The share of a field's range one pixel is worth, before modifiers — ImGui's {@code DragSpeedDefaultRatio}. */
    public static final double RANGE_FRACTION = 0.01;

    /** Units per pixel for a field with no range, before modifiers — ImGui's default {@code v_speed}. */
    public static final double UNBOUNDED_RATE = 1.0;

    /** Shift: bigger steps. */
    public static final double COARSE_MULTIPLIER = 10.0;

    /** Ctrl: finer steps. Deliberately not Alt — Alt is the pan/menu modifier elsewhere in the engine. */
    public static final double FINE_MULTIPLIER = 0.1;

    /**
     * Movement before a scrub starts, in <b>physical</b> pixels.
     *
     * <p>Separate from {@link UIDragController#DEFAULT_THRESHOLD_PX} and larger on purpose. That one keeps
     * a click from becoming a drag-and-drop; this one keeps a click from becoming a <em>value change</em>,
     * and an accidental one-unit edit to a shader parameter is both easier to cause and harder to notice
     * than an accidental drag. The label still has to be clickable to focus the field beside it.</p>
     */
    public static final float DEFAULT_THRESHOLD_PX = 5f;

    private DragScrub() {
    }

    /**
     * What a scrub is allowed to produce: whole numbers or not, and within what range.
     *
     * <p>Mirrors what {@code NumberControl} already knows about itself, so the gesture never grows a
     * second opinion about a field's shape.</p>
     */
    public record Spec(boolean integral, double min, double max, double unitsPerPixel) {

        /** Unbounded, fractional — the common case. */
        public static final Spec FLOAT = new Spec(false, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);

        /** Unbounded, whole numbers. */
        public static final Spec INTEGRAL = new Spec(true, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);

        public Spec {
            if (min > max) throw new IllegalArgumentException("min " + min + " exceeds max " + max);
        }

        /** No declared rate: the range decides it. @see DragScrub#unitsPerPixel(Spec, int) */
        public Spec(boolean integral, double min, double max) {
            this(integral, min, max, Double.NaN);
        }

        public Spec withRange(double newMin, double newMax) {
            return new Spec(integral, newMin, newMax, unitsPerPixel);
        }

        /**
         * States what one pixel of hand movement is worth, for a field that knows its own scale.
         *
         * <pre>{@code
         * Spec.FLOAT.withRate(0.1d);   // a fine unbounded quantity: a tenth per pixel
         * }</pre>
         */
        public Spec withRate(double perPixel) {
            return new Spec(integral, min, max, perPixel);
        }
    }

    /**
     * The signed movement a two-axis drag represents, in pixels.
     *
     * <p><b>Dominant axis, not horizontal only.</b> A vector field's components sit in a narrow column,
     * and a horizontal-only scrub in a 40px-wide box is unusable — you run out of box long before you run
     * out of range. Unity's {@code niceMouseDelta} combines both the same way.</p>
     *
     * <p>The {@code -dy} is not a sign error: screen Y grows <em>downward</em>, and dragging up must read
     * as an increase.</p>
     */
    public static float dominantDelta(float dxPixels, float dyPixels) {
        return Math.abs(dxPixels) >= Math.abs(dyPixels) ? dxPixels : -dyPixels;
    }

    /**
     * How much one pixel of movement is worth on a field shaped like {@code spec}.
     *
     * <p>The field's declared rate if it has one; otherwise a hundredth of its range, or one unit per pixel
     * when it has none. <b>Never read off the value</b>: a rate that followed how big the number is made
     * one gesture mean two things at two ends of one field — a percentage at 0 crawled at 0.03%/px and the
     * same field at 100 moved ten times as fast, which reads as the value sticking rather than as a rate.</p>
     *
     * <p>Shift and Ctrl apply on top: they are properties of the hand, not of the value.</p>
     */
    public static double unitsPerPixel(Spec spec, int modifiers) {
        double rate = spec.unitsPerPixel();
        if (!(rate > 0d) || !Double.isFinite(rate)) {
            double span = spec.max() - spec.min();
            rate = span > 0d && Double.isFinite(span) ? span * RANGE_FRACTION : UNBOUNDED_RATE;
        }
        if (CgModifiers.hasShift(modifiers)) rate *= COARSE_MULTIPLIER;
        if (CgModifiers.hasCtrl(modifiers)) rate *= FINE_MULTIPLIER;
        return rate;
    }

    /**
     * The value a drag of {@code (dxPixels, dyPixels)} away from {@code anchorValue} produces.
     *
     * <p>Deltas are in <b>physical pixels</b>, like {@link #DEFAULT_THRESHOLD_PX} and for the same reason:
     * the rate models a property of the hand, so it must not change when {@code uiScale} does — or, in a
     * node graph, when the canvas is zoomed. A caller working in a transformed space is responsible for
     * converting; {@code NumberControl} does it by measuring its own handle.</p>
     */
    public static double value(double anchorValue, float dxPixels, float dyPixels, int modifiers, Spec spec) {
        double perPixel = unitsPerPixel(spec, modifiers);
        double moved = dominantDelta(dxPixels, dyPixels) * perPixel;
        // No movement means the anchor, exactly — never a rounded version of it. Rounding here instead
        // would snap the value the moment the gesture passed its threshold, and would break the
        // out-and-back property below by making the return trip land somewhere the outbound one did not.
        if (moved == 0d) return Math.max(spec.min(), Math.min(spec.max(), anchorValue));

        double next = anchorValue + moved;
        // Rounded BEFORE clamping: rounding a clamped value can push it back outside the range when a
        // bound is itself fractional (max 2.5 would round to 3).
        next = spec.integral() ? Math.rint(next) : round(next, decimalsFor(perPixel));
        return Math.max(spec.min(), Math.min(spec.max(), next));
    }

    /**
     * How many decimal places are worth keeping when one pixel is worth {@code unitsPerPixel}.
     *
     * <h3>The value is never more precise than the gesture producing it</h3>
     * <p>A scrub is a hand moving a mouse. Reporting {@code 0.5004732} from it is not precision, it is
     * noise with a decimal point in front — nobody aimed at the fourth decimal place, and in a 40px port
     * editor the digits that <em>were</em> aimed at get pushed off the end by the ones that were not.</p>
     *
     * <p>Deriving the cut from the rate rather than fixing it at two places is what makes it hold at every
     * magnitude: at a value of 1 a pixel is worth {@code 0.03}, so two places; at 10000 a pixel is worth
     * about 3, so none at all, and a five-digit number does not acquire a meaningless {@code .47}. It also
     * leaves {@code Ctrl} genuinely fine — a tenth of the rate is a tenth of the step, so the extra place
     * it buys is one the user asked for and can see.</p>
     */
    public static int decimalsFor(double unitsPerPixel) {
        if (!(unitsPerPixel > 0d) || !Double.isFinite(unitsPerPixel)) return 6;
        return Math.max(0, Math.min(8, -(int) Math.floor(Math.log10(unitsPerPixel))));
    }

    /**
     * Rounds to {@code decimals} places, via {@code BigDecimal}.
     *
     * <p>Not {@code Math.rint(v * f) / f}, which is the obvious version and reintroduces exactly the tail
     * this is removing: that arithmetic can land on a double which is not the nearest one to the intended
     * result, so {@code 15.69} comes back as {@code 15.690000000000001} and prints that way.
     * {@code BigDecimal.valueOf} goes through {@code Double.toString}, so the result is the nearest double
     * and renders as the short form everywhere — including {@code String.valueOf} on the way into the
     * document.</p>
     */
    private static double round(double value, int decimals) {
        if (!Double.isFinite(value)) return value;
        return java.math.BigDecimal.valueOf(value)
                .setScale(decimals, java.math.RoundingMode.HALF_UP)
                .doubleValue();
    }

    /** Whether {@code (dx, dy)} physical pixels is far enough to start scrubbing. */
    public static boolean passesThreshold(float dxPixels, float dyPixels, float thresholdPx) {
        return dxPixels * dxPixels + dyPixels * dyPixels >= thresholdPx * thresholdPx;
    }
}
