package com.crystalgui.ui.elements.workbench;

import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.SplitView;
import com.crystalgui.ui.elements.dock.DockRegion;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The fixed frame the workbench is arranged in — VS Code's part grid, IntelliJ's anchors around
 * {@code EditorsSplitters}.
 *
 * <pre>
 *   SplitView (horizontal)
 *     |-- RegionHost SIDEBAR
 *     |-- SplitView (vertical)
 *     |     |-- the EDITOR region  (DockArea)
 *     |     '-- RegionHost PANEL
 *     '-- RegionHost AUXILIARY
 * </pre>
 *
 * <h3>A {@link SplitView}, deliberately not a {@code DockLayout}</h3>
 *
 * <p>The dock tree is right for documents and wrong for regions, and the difference is exactly the one
 * §23 was about: a dock leaf's identity is its <em>position</em>, so closing one collapses the branch that
 * held it and the position has to be reconstructed. A region is a fixed slot — hiding it destroys nothing,
 * because "the sidebar" is not a position.</p>
 *
 * <p>It also already does what a region needs and a leaf cannot: {@code setPaneSizeLimits} gives a real
 * minimum in pixels, which is what "the sidebar is at least 150px wide" means and what a flex weight is
 * structurally unable to say.</p>
 *
 * <h3>The panel spans the editor, not the whole width</h3>
 *
 * <p>Hence the vertical split <em>inside</em> the horizontal one. That is both references' default —
 * VS Code's panel sits under the editor with the auxiliary bar beside both, and IntelliJ's bottom tool
 * windows stop at the right stripe — and it falls out of the nesting rather than needing to be arranged.</p>
 *
 * <h3>A hidden region leaves the split; it does not linger at zero</h3>
 *
 * <p>A pane sized to nothing still has a divider, and a divider you can drag to reveal a region that is
 * supposed to be hidden is a second, contradictory way to toggle it. So {@link #sync()} rebuilds the panes
 * from the regions that currently have something to show — while the {@link RegionHost}s themselves
 * survive, because a region that vanished when empty could never be reopened.</p>
 */
public final class WorkbenchRegions {

    /** The frame itself, so a sheet can reach the whole arrangement. */
    public static final String FRAME_CLASS = "__workbench-regions__";

    /** The column holding the editor above the bottom panel. */
    public static final String CENTRE_CLASS = "__workbench-centre__";

    private final SplitView frame = new SplitView();
    private final SplitView centre = new SplitView();

    private final Map<DockRegion, RegionHost> hosts = new EnumMap<>(DockRegion.class);

    /** Region -> its share of the frame. Persisted; see {@link #weightOf}. */
    private final Map<DockRegion, Float> weights = new EnumMap<>(DockRegion.class);

    /** The EDITOR region's content — the dock. Always present, never hidden. */
    private final UIElement editor;

    public WorkbenchRegions(UIElement editor) {
        this.editor = editor;

        frame.addClass(FRAME_CLASS);
        frame.setOrientation(SplitView.Orientation.HORIZONTAL);
        centre.addClass(CENTRE_CLASS);
        centre.setOrientation(SplitView.Orientation.VERTICAL);

        for (DockRegion region : DockRegion.values()) {
            if (region == DockRegion.EDITOR) continue;
            hosts.put(region, new RegionHost(region));
        }

        weights.put(DockRegion.SIDEBAR, 0.20f);
        weights.put(DockRegion.PANEL, 0.30f);
        weights.put(DockRegion.AUXILIARY, 0.22f);

        sync();
    }

    /** The element to put in the workbench. */
    public UIElement root() {
        return frame;
    }

    public RegionHost host(DockRegion region) {
        return hosts.get(region);
    }

    /** Whether {@code region} currently has anything in it. EDITOR is always true. */
    public boolean isVisible(DockRegion region) {
        if (region == DockRegion.EDITOR) return true;
        RegionHost host = hosts.get(region);
        return host != null && !host.isEmpty();
    }

    /**
     * This region's share of its axis.
     *
     * <p><b>Kept here rather than read back out of the split</b>, which is the same lesson
     * {@code ToolWindowState} records: a size derived from the layout is a size that hiding destroys, and
     * the whole reason placement moved out of the tree was that it could not survive being collapsed.</p>
     */
    public float weightOf(DockRegion region) {
        Float weight = weights.get(region);
        return weight == null ? 0.25f : weight;
    }

    /** @see #weightOf */
    public void setWeight(DockRegion region, float weight) {
        if (region == DockRegion.EDITOR || weight <= 0f) return;
        weights.put(region, weight);
        sync();
    }

    /**
     * Rebuilds the panes from the regions that have something to show.
     *
     * <p>Called whenever a region gains or loses its content. Cheap, and rare — a region's occupancy
     * changes when somebody presses a stripe button, not per frame.</p>
     */
    public void sync() {
        List<UIElement> centreParts = new ArrayList<>();
        List<Float> centreWeights = new ArrayList<>();
        centreParts.add(editor);
        centreWeights.add(1f - weightOf(DockRegion.PANEL));
        if (isVisible(DockRegion.PANEL)) {
            centreParts.add(hosts.get(DockRegion.PANEL));
            centreWeights.add(weightOf(DockRegion.PANEL));
        }
        fill(centre, centreParts, centreWeights);

        List<UIElement> frameParts = new ArrayList<>();
        List<Float> frameWeights = new ArrayList<>();
        if (isVisible(DockRegion.SIDEBAR)) {
            frameParts.add(hosts.get(DockRegion.SIDEBAR));
            frameWeights.add(weightOf(DockRegion.SIDEBAR));
        }
        frameParts.add(centre);
        frameWeights.add(1f - weightOf(DockRegion.SIDEBAR) - weightOf(DockRegion.AUXILIARY));
        if (isVisible(DockRegion.AUXILIARY)) {
            frameParts.add(hosts.get(DockRegion.AUXILIARY));
            frameWeights.add(weightOf(DockRegion.AUXILIARY));
        }
        fill(frame, frameParts, frameWeights);
    }

    /**
     * Brings a split's panes into line with {@code parts}, adding and removing as needed.
     *
     * <h3>A {@link SplitView} never drops below two panes, and this must not loop on that</h3>
     *
     * <p>{@code removePane} refuses when two are left — <em>"a split view with one pane is a container with
     * a divider in it, and every caller would have to check for that shape"</em> — and returns
     * {@code false} rather than throwing. Looping while {@code paneCount() > parts.size()} therefore
     * <b>never terminates</b> in the ordinary case where only the centre is visible, which is the state the
     * workbench starts in. It killed the test worker outright.</p>
     *
     * <p>So the floor is respected, and a surplus pane is emptied at near-zero weight instead. Which is
     * also why {@link RegionHost} is not marked internal: {@code paneContent} clears through
     * {@code clearAllChildren}, and that deliberately skips internal children — a host marked internal
     * would never leave the pane it was first put in, and content would accumulate behind it.</p>
     */
    private static void fill(SplitView split, List<UIElement> parts, List<Float> weights) {
        int wanted = Math.max(2, parts.size());
        while (split.paneCount() > wanted) {
            if (!split.removePane(split.paneCount() - 1)) break;
        }
        while (split.paneCount() < parts.size()) split.addPane();

        for (int i = 0; i < parts.size(); i++) {
            split.paneContent(i, parts.get(i));
        }
        // The floor's leftovers: emptied, and given as close to nothing as a weight can be.
        for (int i = parts.size(); i < split.paneCount(); i++) {
            split.paneContent(i, new UIElement());
        }

        float[] resolved = new float[split.paneCount()];
        for (int i = 0; i < resolved.length; i++) {
            resolved[i] = i < weights.size() ? Math.max(0.02f, weights.get(i)) : 0.0001f;
        }
        if (resolved.length > 0) split.setWeights(resolved);
    }
}
