package com.crystalgui.workbench.region;

import com.crystalgui.ui.dom.UINode;
import com.crystalgui.widget.layout.SplitView;

import java.util.List;

/**
 * Puts a list of parts into a {@link SplitView} — or hands back the one part when there is only one.
 *
 * <h3>Why this is not two lines at each call site</h3>
 *
 * <p>A {@code SplitView} <b>cannot go below two panes</b>. {@code removePane} refuses and returns false, so
 * looping on it never terminates — it killed a test worker outright — and a surplus pane cannot be made to
 * occupy nothing either: {@code applySplit} writes {@code flex-grow}, which only divides <em>free</em>
 * space, while {@code setPaneSizeLimits} clamps <em>dragging</em> rather than layout. Whichever of those you
 * reach for, the region you hid keeps its band on screen, empty.</p>
 *
 * <p>So one part means <b>no split at all</b>: the part is mounted directly and the split holds nothing that
 * could keep measuring. Which is also the honest shape — a split of one thing is not a split.</p>
 *
 * <p>{@link WorkbenchRegions} worked this out for the frame and {@link RegionHost} needs exactly the same
 * thing for a region's two halves, one axis down. Second consumer, so it lives once.</p>
 */
final class SplitFill {

    private SplitFill() {
    }

    /** The smallest share a pane may be given, so a collapsed one is still findable by its divider. */
    private static final float MIN_WEIGHT = 0.02f;

    /**
     * @return the element the caller should mount — {@code split} when there are several parts, the part
     *         itself when there is one, and {@code null} when there are none
     */
    static UINode mount(SplitView split, List<UINode> parts, List<Float> weights) {
        if (parts.isEmpty()) return null;
        if (parts.size() == 1) {
            // Taken OUT of the split, so the split holds nothing that could keep measuring.
            parts.get(0).removeSelf();
            return parts.get(0);
        }
        fill(split, parts, weights);
        return split;
    }

    /**
     * Brings a split's panes into line with {@code parts}.
     *
     * <p>Weights are <b>normalised</b>, and that is a flexbox rule rather than anything about panes:
     * {@code applySplit} writes the weight straight into {@code flex-grow}, and when the total across items
     * is less than one, only that fraction of the free space is distributed — the rest is simply left
     * blank. Shares authored as fractions of the whole sum to 1 only while every one of them is present, so
     * it looks correct until something is hidden.</p>
     */
    private static void fill(SplitView split, List<UINode> parts, List<Float> weights) {
        while (split.paneCount() > parts.size()) {
            if (!split.removePane(split.paneCount() - 1)) break;
        }
        while (split.paneCount() < parts.size()) split.addPane();

        for (int i = 0; i < parts.size(); i++) split.paneContent(i, parts.get(i));

        float[] resolved = new float[Math.min(split.paneCount(), weights.size())];
        float total = 0f;
        for (int i = 0; i < resolved.length; i++) {
            resolved[i] = Math.max(MIN_WEIGHT, weights.get(i));
            total += resolved[i];
        }
        if (total > 0f) {
            for (int i = 0; i < resolved.length; i++) resolved[i] /= total;
        }
        if (resolved.length > 0) split.setWeights(resolved);
    }
}
