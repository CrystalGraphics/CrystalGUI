package com.crystalgui.workbench.region;

import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.widget.layout.SplitView;
import com.crystalgui.workbench.region.DockRegion;
import com.crystalgui.workbench.region.RegionSide;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One {@link DockRegion}, as an element — VS Code's `SIDEBAR_PART` / `PANEL_PART` / `AUXILIARYBAR_PART`.
 *
 * <h3>Why a region is not a dock leaf</h3>
 *
 * <p>A leaf's identity is its position in a tree, and closing one collapses the branch that held it —
 * which is the whole reason {@code ToolWindowManager} needed a four-tier heuristic to put a hidden tool
 * window back. A region is a <b>fixed slot</b>: hiding it does not destroy anything, because "the sidebar"
 * is not a position and cannot be collapsed away.</p>
 *
 * <p>So a region is not something the layout can lose, and that is the point. You cannot drag the sidebar
 * into the panel; you drag a <em>container</em> between them, which is a one-field write.</p>
 *
 * <h3>Two halves, split along the region's CROSS axis</h3>
 *
 * <p>This is IntelliJ's {@code isSplit}, and the axis is what makes one concept cover both shapes: the
 * sidebar and the auxiliary bar are columns, so their halves stack <em>vertically</em> — Project above
 * Structure — while the bottom strip runs across, so its halves sit <em>side by side</em>: Problems beside
 * Services. {@link RegionSide} says which half; the divider between them is {@link #sideWeight()}.</p>
 *
 * <p><b>At most two</b>, which is IntelliJ's own limit and what keeps a region a region rather than a
 * second dock tree. A third container in a half replaces the one there, exactly as a single-slot region
 * always did.</p>
 *
 * <h3>Empty is a real state, not an absent one</h3>
 *
 * <p>A region with nothing showing still exists — it is simply not in the frame's split. That is the same
 * rule the uncloseable central leaf already states: a region that vanished when empty could never be
 * reopened, because there would be nothing left for {@code Ctrl+B} to toggle. The same goes for a half.</p>
 */
public class RegionHost extends UINode {
    /** One region, as a node. */
    public static final Name NAME = Name.of("regionhost");


    /** Every region host, for a theme that frames them alike. */
    public static final String HOST_CLASS = "__region-host__";

    /** The split between a region's two halves, when it has two. */
    public static final String SPLIT_CLASS = "__region-split__";

    /**
     * One half's holder — a plain box between the split and the container.
     *
     * <p><b>Not an optimisation, and not skippable.</b> A {@code ViewContainer} is
     * {@code markAsInternal()}, and {@code SplitView.paneContent} empties a pane with
     * {@code clearAllChildren}, which <em>deliberately refuses</em> internal children. Put a container in a
     * pane directly and the clear silently does nothing, so the next mount throws "cannot add the same
     * child twice". The holder is ordinary, so the pane can clear it; the container goes inside it through
     * {@code setOnlyChild}, which handles the internal case.</p>
     *
     * <p>This is the same reason {@link RegionHost} itself is not internal — see the constructor. One layer
     * down, one more time.</p>
     */
    public static final String HALF_CLASS = "__region-half__";

    /** Half and half, until a divider is dragged. IntelliJ's {@code sideWeight} default. */
    public static final float DEFAULT_SIDE_WEIGHT = 0.5f;

    private final DockRegion region;
    private final SplitView split = new SplitView();

    /** Which type is showing in each half. Absent means that half is empty. */
    private final Map<RegionSide, String> showing = new EnumMap<>(RegionSide.class);

    /** One holder per half. Built once and reused — see {@link #HALF_CLASS}. */
    private final Map<RegionSide, UINode> halves = new EnumMap<>(RegionSide.class);

    private float sideWeight = DEFAULT_SIDE_WEIGHT;
    private boolean capturing;

    public RegionHost(DockRegion region) {
        super(NAME);
        this.region = region;
        addClass(HOST_CLASS);
        // Per-region class so the sheet can size the sidebar differently from the panel without the
        // Java side naming a pixel -- the widget rule.
        addClass("__region-" + region.name().toLowerCase(Locale.ROOT) + "__");
        // NOT markAsInternal(). SplitView.paneContent clears a pane with clearAllChildren(), which
        // deliberately skips internal children -- so an internal host would never leave the pane it was
        // first placed in, and every later sync would stack another one behind it.

        split.addClass(SPLIT_CLASS);
        // THE CROSS AXIS of the region, which is the whole of what `side` means here. A column's halves
        // stack; a strip's sit side by side.
        split.setOrientation(region == DockRegion.PANEL
                ? SplitView.Orientation.HORIZONTAL
                : SplitView.Orientation.VERTICAL);
        // A DRAG MUST REACH THE MODEL, or the divider moves the picture and changes nothing that survives
        // -- and the very next sync restores the authored default over the top. The same omission cost the
        // frame's own weights a session; see WorkbenchRegions.captureWeights.
        split.onPercentageChanged.connect(ignored -> captureSideWeight());

        for (RegionSide side : RegionSide.values()) {
            UINode half = new UINode();
            half.addClass(HALF_CLASS);
            half.addClass(side == RegionSide.PRIMARY ? "__region-primary__" : "__region-secondary__");
            halves.put(side, half);
        }
    }

    public DockRegion region() {
        return region;
    }

    /** What is showing in {@code side}, or null when that half is empty. */
    @Nullable
    public String showing(RegionSide side) {
        return showing.get(side);
    }

    /**
     * What is showing in the region's first occupied half.
     *
     * <p>For callers that only need "is anything here" — a session capture, a diagnostic. Anything that
     * acts on a particular tool window should ask {@link #showing(RegionSide)} with that window's side,
     * or it will act on its neighbour whenever the region is split.</p>
     */
    @Nullable
    public String showing() {
        String primary = showing.get(RegionSide.PRIMARY);
        return primary != null ? primary : showing.get(RegionSide.SECONDARY);
    }

    public boolean isEmpty() {
        return showing.isEmpty();
    }

    /** The share of the region's cross axis its {@link RegionSide#PRIMARY} half takes. */
    public float sideWeight() {
        return sideWeight;
    }

    /** @see #sideWeight() */
    public void setSideWeight(float value) {
        if (value <= 0f || value >= 1f) return;
        this.sideWeight = value;
        sync();
    }

    /** Shows {@code content} in one half, replacing whatever was there. */
    public void show(RegionSide side, String typeId, UINode element) {
        showing.put(side, typeId);
        halves.get(side).setOnlyChild(element);
        sync();
    }

    /** Empties one half. The host stays; see the class note on why. */
    public void clear(RegionSide side) {
        showing.remove(side);
        halves.get(side).setOnlyChild(null);
        sync();
    }

    /** Empties both halves. */
    public void clear() {
        showing.clear();
        for (UINode half : halves.values()) half.setOnlyChild(null);
        sync();
    }

    /**
     * Mounts whichever halves have something in them.
     *
     * <p>One half means <b>no split</b> — see {@link SplitFill} for why a {@code SplitView} cannot be asked
     * to hold a single thing, and why a leftover pane is worse than none.</p>
     */
    private void sync() {
        if (capturing) return;
        List<UINode> parts = new ArrayList<>(2);
        List<Float> weights = new ArrayList<>(2);
        if (showing.containsKey(RegionSide.PRIMARY)) {
            parts.add(halves.get(RegionSide.PRIMARY));
            weights.add(sideWeight);
        }
        if (showing.containsKey(RegionSide.SECONDARY)) {
            parts.add(halves.get(RegionSide.SECONDARY));
            weights.add(1f - sideWeight);
        }
        setOnlyChild(SplitFill.mount(split, parts, weights));
    }

    /**
     * Reads the divider back into the model after a drag.
     *
     * <p>Only while both halves are mounted: with one half the split is not on screen at all, and whatever
     * weights it still holds describe an arrangement that is not being looked at.</p>
     */
    private void captureSideWeight() {
        if (capturing || showing.size() < 2) return;
        capturing = true;
        try {
            float[] shares = split.getWeights();
            if (shares.length >= 1 && shares[0] > 0f && shares[0] < 1f) sideWeight = shares[0];
        } finally {
            capturing = false;
        }
    }

    // acceptsPublicChildren stays TRUE, unlike most composites here. A region host is a holder -- the
    // same shape as Tab.content() or a SplitView pane -- and setOnlyChild adds through the public API,
    // so refusing would make the one method this class exists for throw.
}
