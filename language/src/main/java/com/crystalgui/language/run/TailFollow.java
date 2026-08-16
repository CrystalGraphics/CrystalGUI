package com.crystalgui.language.run;

/**
 * Whether new output drags the view down with it — the console's scroll lock, as pure arithmetic.
 *
 * <h3>Why this is a class and not four lines in {@code RunConsoleView}</h3>
 *
 * <p>It was four lines there, and they were wrong twice, in two different ways, neither of which any test
 * in this repository could have caught: the logic needed a laid-out {@code TextEditor} to exercise, and
 * {@code language/src/test} has no Taffy on its classpath, so a {@code UIElement} cannot even be
 * constructed. Two floats and a boolean can be, which is the whole argument for the seam.</p>
 *
 * <h3>The question is NOT "is the tail visible"</h3>
 *
 * <p>That is a fact about geometry, and geometry diverges from the reader's intent constantly — a
 * document that just grew leaves a perfectly still viewport no longer at the tail, which is
 * indistinguishable from somebody having scrolled up. Inferring from it disarms the lock the instant
 * anything is printed.</p>
 *
 * <p>The question is <b>"has the reader moved this"</b>, and answering it needs one more fact than the
 * position: what the console itself last wrote. Three states have to stay separate, and collapsing any
 * two of them is what broke it:</p>
 *
 * <table>
 *   <tr><th>State</th><th>What it looks like</th><th>What it must do</th></tr>
 *   <tr><td>Not measured yet</td><td>{@code max <= 0} or non-finite</td><td>nothing — no vote</td></tr>
 *   <tr><td>The document grew</td><td>position unchanged, {@code max} larger</td><td>stay armed</td></tr>
 *   <tr><td>The reader scrolled</td><td>position differs from what we wrote</td><td>arm iff at the bottom</td></tr>
 * </table>
 *
 * <p>The first two are the ones that were conflated. An unmeasured viewport reports {@code max == 0}, so
 * a freshly opened panel "scrolls to the tail" at offset zero — and from the next line onward the reader
 * is genuinely not at the tail, so a position-only rule disarms permanently on the one console nobody has
 * touched. That was the reported bug: the Run panel opened at the top and stayed there.</p>
 */
final class TailFollow {

    /** Armed by default: a console nobody has scrolled follows its output. */
    private boolean following = true;

    /**
     * Where the console last placed the view, or NaN before it ever has.
     *
     * <p>NaN is load-bearing rather than an initial-value formality. Until the lock has been enforced
     * once there is nothing of ours to compare against, and comparing against zero instead would read the
     * untouched opening position as a deliberate scroll to the top — which is the second version of this
     * bug, arrived at from the other side.</p>
     */
    private float appliedTop = Float.NaN;

    boolean isFollowing() {
        return following;
    }

    /**
     * Re-reads the lock from where the view now is.
     *
     * <p>Called once per frame <b>before</b> anything grows the document — afterwards the question can no
     * longer be answered, because the position that mattered is the one the reader left.</p>
     *
     * @param top the current offset; a non-finite value is {@code TextEditor}'s documented NaN and is
     *            never voted on, since NaN loses every comparison and would disarm the lock for a reason
     *            the reader never caused
     * @param max the largest legal offset; zero means an unmeasured viewport or a document shorter than
     *            one screen, and in both "at the tail" and "at the top" are the same place
     */
    void sample(float top, float max) {
        if (!Float.isFinite(top)) return;
        boolean measured = Float.isFinite(max) && max > 0f;

        if (!following) {
            // NOT ARMED: read the position straight. Nothing of ours is on screen to protect, so there is
            // no reason to compare against what we last wrote -- and comparing anyway leaves a hole, since
            // a reader returning to exactly the offset the lock last set would look like no movement at
            // all and never re-arm. Reachable whenever the document has stopped growing, which is
            // precisely when somebody scrolls back down to wait for the next run.
            if (measured) following = atBottom(top, max);
            return;
        }

        // ARMED: only a divergence from what the console itself last wrote counts as the reader moving
        // away. Everything else is the document growing under a still viewport.
        if (Float.isNaN(appliedTop) || Math.abs(top - appliedTop) <= EPSILON) return;
        following = measured && atBottom(top, max);
    }

    /** Records where the console just put the view — the baseline {@link #sample} measures against. */
    void applied(float top) {
        appliedTop = top;
    }

    /**
     * Re-arms explicitly — the Scroll to End button.
     *
     * <p>Reaching the bottom by dragging re-arms too, through {@link #sample}. This exists because a
     * reader far up a long transcript otherwise has no gesture meaning "resume" short of travelling the
     * whole way back. The baseline is cleared so the next frame cannot read the position being left as a
     * reader gesture.</p>
     */
    /**
     * Stops following, deliberately — the counterpart to {@link #rearm()}.
     *
     * <p>Scrolling away releases the lock on its own, and that is the only way it happened until a tab
     * could <b>restore</b> a position: putting a reader back where they were half way up a transcript has
     * to leave the lock where it was too, or the next line of output drags them to the bottom of the very
     * transcript they had scrolled up in.</p>
     */
    void release() {
        following = false;
        appliedTop = Float.NaN;
    }

    void rearm() {
        following = true;
        appliedTop = Float.NaN;
    }

    private static boolean atBottom(float top, float max) {
        return top >= max - AT_BOTTOM_SLACK;
    }

    /** A pixel of slack: a fractional offset must still count as the bottom. */
    private static final float AT_BOTTOM_SLACK = 1f;

    /** Below this, a difference is rounding rather than a gesture. */
    private static final float EPSILON = 0.5f;
}
