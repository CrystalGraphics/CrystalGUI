package com.crystalgui.core.window;

/**
 * How far a movable window may travel off the edge of what contains it — <b>a caption's worth stays</b>.
 *
 * <pre>{@code
 * float left = WindowClamp.left(wantedLeft, frameWidth, areaWidth, captionHeight);
 * float top  = WindowClamp.top(wantedTop, areaHeight, captionHeight, moving);
 * }</pre>
 *
 * <h3>Why not simply inside</h3>
 *
 * <p>Clamping a window fully inside its container is the obvious rule and the wrong one: it makes the
 * edges of a large window unreachable, since the only way to see its right-hand side on a narrow screen
 * is to push its left off. Every desktop allows the overhang. What none of them allow is losing the
 * window — so the bound is stated in CAPTION HEIGHTS, one square of title bar, which is both the thing
 * you grab to bring it back and a size that is visible at a glance.</p>
 *
 * <p>The top is the asymmetry, and it is deliberate: at rest a caption may not go above the edge at all,
 * because a title bar off the top is a window that cannot be dragged back down. It is allowed one
 * caption of headroom <em>while moving</em>, which is what lets a pointer riding inside the caption
 * reach the top edge — Windows behaves the same way, and whatever does not snap is brought back when the
 * drag ends.</p>
 *
 * <p>Floats, and nothing else. Both a compositor's frames and a widget layer's dialogs obey this rule and
 * the two may not name each other, so it is stated once here rather than twice in parallel — the caption
 * arithmetic was wrong in three different ways while it was being arrived at, and every version of it
 * still put a window somewhere plausible.</p>
 */
public final class WindowClamp {

    private WindowClamp() {
    }

    /**
     * The horizontal position, symmetric: a caption's width may remain at either edge.
     *
     * @param captionHeight a square of it, so a window is as findable off the left as off the right
     */
    public static float left(float wanted, float frameWidth, float areaWidth, float captionHeight) {
        return clamp(wanted, captionHeight - frameWidth, areaWidth - captionHeight);
    }

    /**
     * The vertical position, asymmetric.
     *
     * @param moving whether a drag is live — the only time the caption may rise above the top
     */
    public static float top(float wanted, float areaHeight, float captionHeight, boolean moving) {
        return clamp(wanted, moving ? -captionHeight : 0f, areaHeight - captionHeight);
    }

    /**
     * {@code lo} is allowed to exceed {@code hi} — a window narrower than its own caption — and the
     * upper bound wins, which keeps the title bar on screen rather than the body.
     */
    private static float clamp(float value, float lo, float hi) {
        return Math.max(Math.min(lo, hi), Math.min(value, hi));
    }
}
