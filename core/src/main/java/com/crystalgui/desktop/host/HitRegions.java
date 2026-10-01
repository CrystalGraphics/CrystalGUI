package com.crystalgui.desktop.host;

import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.box.HitShape;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Which points of the surface the desktop takes while another UI's screen is up, frozen at the end of a frame: what
 * the router answers a host from without reading the document (plan engine-threaded-ui §5.1, step 1).
 *
 * <pre>{@code
 * HitRegions regions = HitRegions.capture(document);   // the document's thread, after its frame
 * boolean ours = regions.takes(x, y);                   // the host's thread, until the next capture
 * }</pre>
 *
 * <p>The answer is {@link ScreenOverlay#overlayHitTest}'s, flattened: every box the hit test would try, in its order,
 * each marked with whether a hit there is the desktop's. A window, or something promoted into the top layer, is one
 * region; boxes over the windows that are not theirs (the taskbar) are regions that give the point back to the game.
 * Inert boxes are left out, as the hit test passes over them.</p>
 *
 * <p>Two differences from the live test, both at a window's edge: a window's content that overflows the window is not
 * in its region, and a hit-test change made between the capture and the event is not seen. A box is a copy; nothing
 * here reads the tree after {@link #capture}.</p>
 */
public final class HitRegions {

    /** Takes nothing: before the first capture. */
    public static final HitRegions NONE = new HitRegions(new Region[0], false);

    private final Region[] regions;
    private final boolean pointerCaptured;

    private HitRegions(Region[] regions, boolean pointerCaptured) {
        this.regions = regions;
        this.pointerCaptured = pointerCaptured;
    }

    /** Freezes {@code document}'s regions. On its thread, after layout. */
    public static HitRegions capture(UIDocument document) {
        // A scroll since the last compose moved boxes without composing them; the live test composes first too.
        document.boxes().composeIfDirty();
        Box root = document.boxes().root();
        if (root == null) return NONE;
        Desktop desktop = Desktop.ifPresent(document);
        UIElement layer = desktop == null ? null : desktop.windowLayer();
        List<Region> out = new ArrayList<>();
        // A window or a promoted subtree is ONE region unless it is inert: a modal elsewhere can block it, and the hit
        // test then passes over it to whatever of its content is not blocked, or to what is behind.
        root.visitInHitOrder(
                box -> !(ours(document, layer, box.node()) && !inert(document, box)),
                box -> {
                    if (box.isStackingOnly() || inert(document, box)) return;
                    Region region = Region.of(box, ours(document, layer, box.node()));
                    if (region != null) out.add(region);
                });
        // What lies under every region of ours can only answer "the game's", which is also the answer for no region.
        int last = out.size() - 1;
        while (last >= 0 && !out.get(last).ours) last--;
        return new HitRegions(out.subList(0, last + 1).toArray(new Region[0]),
                document.input().pointerCaptureTarget() != null);
    }

    /** Whether a press at this surface point is the desktop's. */
    public boolean takes(float x, float y) {
        for (Region region : regions) {
            if (region.contains(x, y)) return region.ours;
        }
        return false;
    }

    /** Whether a drag inside the desktop held the pointer when this was captured. */
    public boolean pointerCaptured() {
        return pointerCaptured;
    }

    /** How many regions the answer reads; the trailing ones that can only give a point back are dropped. */
    public int size() {
        return regions.length;
    }

    /** {@link ScreenOverlay#overlayHitTest}'s rule for a box the hit test answered with. */
    private static boolean ours(UIDocument document, @Nullable UIElement layer, UIElement node) {
        for (UIElement walk = node; walk != null; walk = walk.composedParent()) {
            if (document.isPromoted(walk)) return true;
        }
        for (UIElement walk = node; walk != null; walk = walk.composedParent()) {
            if (walk instanceof WindowFrame) return true;
            if (walk == layer) return false;
        }
        return false;
    }

    private static boolean inert(UIDocument document, Box box) {
        return document.focus().isInert(box.node());
    }

    /** One box, with every clipping box it is reached through. */
    private static final class Region {
        final HitShape shape;
        final HitShape[] clips;
        final boolean ours;

        private Region(HitShape shape, HitShape[] clips, boolean ours) {
            this.shape = shape;
            this.clips = clips;
            this.ours = ours;
        }

        /** Null when hit-testing is off on the box or above it: the test never reaches it. */
        @Nullable
        static Region of(Box box, boolean ours) {
            if (!box.hitTestable()) return null;
            List<HitShape> clips = new ArrayList<>();
            for (Box between = box.host(); between != null; between = between.host()) {
                if (!between.hitTestable()) return null;
                if (between.clips()) clips.add(between.hitShape());
            }
            return new Region(box.hitShape(), clips.toArray(new HitShape[0]), ours);
        }

        boolean contains(float x, float y) {
            if (!shape.contains(x, y)) return false;
            for (HitShape clip : clips) {
                if (!clip.contains(x, y)) return false;
            }
            return true;
        }
    }
}
