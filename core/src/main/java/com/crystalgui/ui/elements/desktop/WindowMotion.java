package com.crystalgui.ui.elements.desktop;

import com.crystalgui.ui.UIFrameTicker;

/**
 * One running window animation, of either kind.
 *
 * <p>There are two, and the split is not an implementation detail — it is about whether the window's
 * CONTENT changes shape. {@link WindowAnimation} moves a window without reflowing it (open, close,
 * minimise) and so animates a transform, which is what a compositor does and costs no layout.
 * {@link WindowGeometryAnimation} changes its size (maximise, restore-down) and so animates the layout,
 * because a transform would show the destination's layout at the source's geometry.</p>
 *
 * <p>What they share is only what {@link WindowAnimator} needs of them: they tick, and they can be
 * cancelled by whatever gesture interrupts them.</p>
 */
interface WindowMotion extends UIFrameTicker {

    /** Ends this animation early, without running its completion callback. */
    void cancel();
}
