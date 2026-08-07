package com.crystalgui.ui.elements.workbench;

import com.crystalgui.ui.elements.dock.DockRegion;
import com.crystalgui.ui.elements.dock.RegionSide;

import java.util.Objects;

/**
 * Everything known about one tool window, whether or not it is currently on screen — a port of IntelliJ's
 * {@code WindowInfoImpl}.
 *
 * <h3>The idea being ported</h3>
 *
 * <p>Both IntelliJ and VS Code keep a tool window's placement as <b>its own record, keyed by id, living
 * beside the layout</b> — never inside it. IntelliJ persists one {@code WindowInfoImpl} per tool window
 * into {@code workspace.xml} ({@code visible}, {@code anchor}, {@code weight}, {@code sideWeight},
 * {@code order}, {@code type}, …); VS Code stores {@code workbench.activity.pinnedViewlets2} and
 * {@code workbench.views.state} for the same purpose. In both, the splittable tree covers the
 * <em>editor area only</em>.</p>
 *
 * <p>That is why reopening is exact for them and was not for us. Placement was being <em>derived</em> from
 * the tree at close time, and a position inside a tree cannot be recovered from it once the branch holding
 * it has collapsed. The record makes placement a thing that is <b>stored</b>, so closing stops being
 * destructive.</p>
 *
 * <h3>The anchor is gone, and the region is a real field now</h3>
 *
 * <p>{@link #region()} used to be <em>derived</em> from a {@code DockDropZone} anchor — "the sidebar" said
 * as "against the left wall" — with a javadoc promising the real field would arrive <i>"when a region
 * becomes a real element"</i>. It is one: {@link WorkbenchRegions} holds four, {@link RegionHost} is keyed
 * by them, and nothing about a tool window's placement is expressed as a wall any more.</p>
 *
 * <p>Keeping the derivation while adding {@link #side()} would have left two placement facts where there is
 * only one truth, and the stored one would win in some paths and the derived one in others. That is the
 * failure this whole record exists to stop.</p>
 *
 * <h3>{@code sideWeight} — refused once, ported now</h3>
 *
 * <p>{@link #side()} is IntelliJ's {@code isSplit}. §23.5 named it as deliberately not ported; plan.md §24.5
 * reverses that, and the reversal is the honest part — the reason given was that stacking two tool windows
 * on one wall belongs to a tool-window host rather than to a dock tree, which stopped being an argument the
 * moment we started building a tool-window host. {@code type} (docked/floating/windowed) and {@code autoHide}
 * are still absent: floating tool windows do not exist here. Named so their absence reads as a decision.</p>
 *
 * <p>Immutable, with withers — so a placement can be captured and handed around without any chance of a
 * caller mutating the record that another is about to persist.</p>
 */
public final class ToolWindowState {

    /** What a tool window nobody has ever opened is worth: enough to place it, nothing remembered. */
    public static ToolWindowState initial(String typeId, DockRegion region, int order) {
        return new ToolWindowState(typeId, false, region, RegionSide.PRIMARY, DEFAULT_WEIGHT, order,
                true, true);
    }

    /** The share of its axis a tool window takes when nothing has ever sized it. */
    public static final float DEFAULT_WEIGHT = 0.20f;

    private final String typeId;
    private final boolean visible;
    private final DockRegion region;
    private final RegionSide side;
    private final float weight;
    private final int order;
    private final boolean active;
    private final boolean showStripeButton;

    private ToolWindowState(String typeId, boolean visible, DockRegion region, RegionSide side, float weight,
                            int order, boolean active, boolean showStripeButton) {
        this.typeId = Objects.requireNonNull(typeId, "typeId");
        this.visible = visible;
        this.region = Objects.requireNonNull(region, "region");
        this.side = Objects.requireNonNull(side, "side");
        this.weight = weight;
        this.order = order;
        this.active = active;
        this.showStripeButton = showStripeButton;
    }

    public String typeId() {
        return typeId;
    }

    /** Whether it was on screen. What a restore reopens, and what the stripe button lights for. */
    public boolean visible() {
        return visible;
    }

    /**
     * The region this tool window belongs to — see {@link DockRegion}.
     *
     * <p>Stored, not derived; see the class note. A region survives the tree changing, which is the whole
     * point of the Parts model.</p>
     */
    public DockRegion region() {
        return region;
    }

    /**
     * Which half of that region — see {@link RegionSide}.
     *
     * <p>Together with the region this is the <b>whole</b> of where a tool window lives, and it is what
     * {@link StripeRail} derives the rail and the group from.</p>
     */
    public RegionSide side() {
        return side;
    }

    /** Its share of the axis it divides — {@code DockNode.size()}. */
    public float weight() {
        return weight;
    }

    /** Its position on the stripe. */
    public int order() {
        return order;
    }

    /** Whether it was the selected tab in its strip. A restored tool window that is not comes back hidden. */
    public boolean active() {
        return active;
    }

    /** Whether it appears on a stripe at all — IntelliJ's {@code show_stripe_button}. */
    public boolean showStripeButton() {
        return showStripeButton;
    }

    public ToolWindowState withVisible(boolean nowVisible) {
        return new ToolWindowState(typeId, nowVisible, region, side, weight, order, active, showStripeButton);
    }

    public ToolWindowState withRegion(DockRegion nowRegion) {
        return new ToolWindowState(typeId, visible, nowRegion, side, weight, order, active, showStripeButton);
    }

    public ToolWindowState withSide(RegionSide nowSide) {
        return new ToolWindowState(typeId, visible, region, nowSide, weight, order, active, showStripeButton);
    }

    public ToolWindowState withWeight(float nowWeight) {
        return new ToolWindowState(typeId, visible, region, side, nowWeight, order, active, showStripeButton);
    }

    public ToolWindowState withOrder(int nowOrder) {
        return new ToolWindowState(typeId, visible, region, side, weight, nowOrder, active, showStripeButton);
    }

    public ToolWindowState withActive(boolean nowActive) {
        return new ToolWindowState(typeId, visible, region, side, weight, order, nowActive, showStripeButton);
    }

    public ToolWindowState withShowStripeButton(boolean shown) {
        return new ToolWindowState(typeId, visible, region, side, weight, order, active, shown);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ToolWindowState other)) return false;
        return visible == other.visible && Float.compare(weight, other.weight) == 0
                && order == other.order
                && active == other.active && showStripeButton == other.showStripeButton
                && typeId.equals(other.typeId) && region == other.region && side == other.side;
    }

    @Override
    public int hashCode() {
        return Objects.hash(typeId, visible, region, side, weight, order, active, showStripeButton);
    }

    @Override
    public String toString() {
        return "ToolWindowState[" + typeId + (visible ? " visible" : " hidden") + " " + region + "/" + side
                + " w=" + weight + "]";
    }
}
