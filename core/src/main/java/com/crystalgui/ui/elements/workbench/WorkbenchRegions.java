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

    /** Either split, when one is mounted at all. @see #single */
    public static final String SPLIT_CLASS = "__workbench-split__";

    private final SplitView frame = new SplitView();
    private final SplitView centre = new SplitView();

    private final Map<DockRegion, RegionHost> hosts = new EnumMap<>(DockRegion.class);

    /** Region -> its share of the frame. Persisted; see {@link #weightOf}. */
    private final Map<DockRegion, Float> weights = new EnumMap<>(DockRegion.class);

    /** The EDITOR region's content — the dock. Always present, never hidden. */
    private final UIElement editor;

    public WorkbenchRegions(UIElement editor) {
        this.editor = editor;

        // The BOX carries the frame class -- it is what the workbench mounts. The splits inside it are
        // ordinary column children, like every other pane child.
        rootBox.addClass(FRAME_CLASS);
        frame.addClass(SPLIT_CLASS);
        centre.addClass(SPLIT_CLASS);
        // THE OUTER SPLIT IS VERTICAL, which is IntelliJ's arrangement and not VS Code's: the bottom
        // panel spans the sidebar AND the auxiliary bar, so opening it takes height from both and closing
        // it gives that height back to both. Nesting it under the editor alone -- what this did first --
        // makes the bottom strip stop at the editor's edge, which is visibly not the reference.
        frame.setOrientation(SplitView.Orientation.VERTICAL);
        centre.addClass(CENTRE_CLASS);
        centre.setOrientation(SplitView.Orientation.HORIZONTAL);

        // THE FLOORS ARE THE STYLESHEET'S, so the percentage ones have to get out of the way.
        //
        // SplitView defaults to LDLib2's 5..95, and `boundsFor` folds that together with each pane's CSS
        // minimum by taking whichever is LARGER -- which is right, since a pane must satisfy both. The
        // consequence is that a percentage floor silently wins on any large window: 5% of a 1560px pair is
        // 78px, so lowering `min-width` below that in `ua/workbench.css` would have changed nothing on
        // screen and read as the rule not being applied at all.
        //
        // A percentage is also the wrong unit for this. "At least 5% of the axis" means the sidebar's
        // smallest width depends on how wide the game window is, so the same drag bottoms out somewhere
        // different on a 4K monitor than on a 1080p one. A region's floor is about whether its content is
        // still worth showing, which is a number of pixels; every pane in these two splits now states one.
        frame.setLimits(0f, 100f);
        centre.setLimits(0f, 100f);

        for (DockRegion region : DockRegion.values()) {
            if (region == DockRegion.EDITOR) continue;
            hosts.put(region, new RegionHost(region));
        }

        // A DRAG MUST REACH THE MODEL. The split owns its own pane weights, and nothing was reading them
        // back -- so dragging a divider moved the picture and changed nothing that survives, and the very
        // next sync() (any region opening or closing) restored the authored defaults over the top. The
        // session persisted those defaults too, so a restart came back at 20/30/22 however it was left.
        frame.onPercentageChanged.connect(ignored -> captureWeights());
        centre.onPercentageChanged.connect(ignored -> captureWeights());

        weights.put(DockRegion.SIDEBAR, 0.20f);
        weights.put(DockRegion.PANEL, 0.30f);
        weights.put(DockRegion.AUXILIARY, 0.22f);

        sync();
    }

    /**
     * The element to put in the workbench — a plain box, not the split.
     *
     * <p>Because a {@link SplitView} <b>cannot go below two panes</b>: {@code removePane} refuses, and a
     * surplus pane cannot be made to occupy nothing either — {@code applySplit} writes {@code flex-grow},
     * which only divides <em>free</em> space, and {@code setPaneSizeLimits} clamps <em>dragging</em>
     * rather than layout. So a hidden region kept its band on screen, empty, whichever of those was
     * tried.</p>
     *
     * <p>With a box in front, one part means <b>no split at all</b> — the part is simply the box's child,
     * there is no leftover pane and no divider to drag it back out with. Which is also the honest shape: a
     * split of one thing is not a split.</p>
     */
    public UIElement root() {
        return rootBox;
    }

    private final UIElement rootBox = new UIElement();

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

    /** How a region's two halves divide it — see {@link RegionHost#sideWeight()}. */
    public float sideWeightOf(DockRegion region) {
        RegionHost host = hosts.get(region);
        return host == null ? RegionHost.DEFAULT_SIDE_WEIGHT : host.sideWeight();
    }

    /** @see #sideWeightOf */
    public void setSideWeight(DockRegion region, float weight) {
        RegionHost host = hosts.get(region);
        if (host != null) host.setSideWeight(weight);
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
        if (capturing) return;
        // THE ROW: sidebar | editor | auxiliary.
        List<UIElement> rowParts = new ArrayList<>();
        List<Float> rowWeights = new ArrayList<>();
        if (isVisible(DockRegion.SIDEBAR)) {
            rowParts.add(hosts.get(DockRegion.SIDEBAR));
            rowWeights.add(weightOf(DockRegion.SIDEBAR));
        }
        rowParts.add(editor);
        rowWeights.add(editorShareOf(DockRegion.SIDEBAR, DockRegion.AUXILIARY));
        if (isVisible(DockRegion.AUXILIARY)) {
            rowParts.add(hosts.get(DockRegion.AUXILIARY));
            rowWeights.add(weightOf(DockRegion.AUXILIARY));
        }
        UIElement rowContent = SplitFill.mount(centre, rowParts, rowWeights);

        // THE COLUMN: that whole row, over the bottom panel.
        List<UIElement> columnParts = new ArrayList<>();
        List<Float> columnWeights = new ArrayList<>();
        columnParts.add(rowContent);
        columnWeights.add(editorShareOf(DockRegion.PANEL));
        if (isVisible(DockRegion.PANEL)) {
            columnParts.add(hosts.get(DockRegion.PANEL));
            columnWeights.add(weightOf(DockRegion.PANEL));
        }
        rootBox.setOnlyChild(SplitFill.mount(frame, columnParts, columnWeights));
    }

    /**
     * What the editor keeps on one axis: the whole of it, less the regions <b>actually on screen</b>.
     *
     * <p><b>Only the visible ones, and that word is the entire bug this replaced.</b> Subtracting a
     * hidden region's share as well left the axis summing to less than one — 0.78 with the auxiliary bar
     * closed, since its 0.22 was still being taken off — and {@link SplitFill} then normalises, because a
     * flex row whose weights total below one leaves the remainder blank rather than distributing it. So
     * every visible pane was silently scaled up by 1/0.78, and the <em>sidebar's rendered width depended
     * on whether an unrelated region was open</em>: 256px of 1000 with the auxiliary bar shut, 200px with
     * it open. Opening anything from a stripe button resized the file tree, which is how it was found.</p>
     *
     * <p>Counting only what is mounted makes the total exactly one again, so the normalisation is a
     * no-op in the steady state and a region renders at the share it was given. It also keeps
     * {@link #captureWeights} honest for free: it reads the live weights straight back into the model, so
     * the two only agree while the model's scale and the split's are the same one.</p>
     */
    private float editorShareOf(DockRegion... regions) {
        float taken = 0f;
        for (DockRegion region : regions) {
            if (isVisible(region)) taken += weightOf(region);
        }
        return 1f - taken;
    }

    /**
     * Reads the live pane weights back into the model, after a divider drag.
     *
     * <p>Mirrors {@link #sync()}'s own ordering, because that is what decided which pane a region got. The
     * editor's own share is deliberately <b>not</b> stored: it is whatever the named regions leave, which
     * is how {@link #sync} computes it and keeps the two from drifting.</p>
     */
    private void captureWeights() {
        if (capturing) return;
        // sync() writes weights, which emits, which would land back here mid-write.
        capturing = true;
        try {
            float[] row = centre.getWeights();
            int at = 0;
            if (isVisible(DockRegion.SIDEBAR) && at < row.length) weights.put(DockRegion.SIDEBAR, row[at++]);
            at++; // the editor
            if (isVisible(DockRegion.AUXILIARY) && at < row.length) {
                weights.put(DockRegion.AUXILIARY, row[at]);
            }
            float[] column = frame.getWeights();
            if (isVisible(DockRegion.PANEL) && column.length > 1) {
                weights.put(DockRegion.PANEL, column[1]);
            }
        } finally {
            capturing = false;
        }
    }

    private boolean capturing;
}
