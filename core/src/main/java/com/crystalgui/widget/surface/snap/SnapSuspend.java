package com.crystalgui.widget.surface.snap;

import com.crystalgraphics.platform.input.CgModifiers;

/**
 * <b>The key that turns snapping off for one gesture</b>, in one place so every gesture spends the same
 * one.
 *
 * <p>Ctrl, which is Blender's and Figma's. It <em>suspends</em> rather than toggles: the preference is
 * the default and the modifier is the exception, because the thing you cannot do with snapping on is put
 * something deliberately NEAR an edge, and that is a per-gesture want rather than a setting.</p>
 *
 * <p>Alt is honoured too, and only for the move gesture's sake — it is what that gesture has always
 * spent on this. It cannot be the general answer because a resize already spends Alt on "about the
 * centre", which is why the shared key had to be a third one.</p>
 */
public final class SnapSuspend {

    private SnapSuspend() {
    }

    /** Whether {@code modifiers} says to leave the proposal alone. */
    public static boolean isSuspended(int modifiers) {
        return CgModifiers.hasCtrl(modifiers);
    }
}
