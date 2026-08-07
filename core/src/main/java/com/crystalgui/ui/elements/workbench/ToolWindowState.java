package com.crystalgui.ui.elements.workbench;

import com.crystalgui.ui.elements.dock.DockDropZone;
import com.crystalgui.ui.elements.dock.DockPanelRef;
import com.crystalgui.ui.elements.dock.DockRegion;
import com.crystalgui.ui.elements.dock.DockPath;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

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
 * <h3>What each field is for, and which are ours rather than IntelliJ's</h3>
 *
 * <p>{@link #anchor}, {@link #weight}, {@link #order}, {@link #visible} and {@link #active} are the direct
 * counterparts. Two are additions this design needs because it keeps tool windows in the same tree as
 * documents — which IntelliJ does not, and which is the trade
 * {@code DockPanelDescriptor} documents:</p>
 *
 * <ul>
 *   <li>{@link #path} — the structural position. IntelliJ needs no equivalent because its tool windows are
 *       never in a tree; an anchor plus a weight fully determines where one goes. Here a panel may sit
 *       <em>inside</em> the tree, and nothing about a wall describes that.</li>
 *   <li>{@link #groupedWith} — the panels sharing its strip. Same reason: IntelliJ's tool windows do not
 *       share a tab strip with an editor, so the situation cannot arise there.</li>
 * </ul>
 *
 * <p>{@code sideWeight}, {@code type} (docked/floating/windowed) and {@code autoHide} are deliberately
 * <b>not</b> ported: floating tool windows do not exist here, and a side weight only means something on a
 * stripe that stacks two tool windows on one wall, which is a feature of IntelliJ's tool-window host
 * rather than of a dock tree. They are named here so their absence reads as a decision.</p>
 *
 * <p>Immutable, with withers — so a placement can be captured and handed around without any chance of a
 * caller mutating the record that another is about to persist.</p>
 */
public final class ToolWindowState {

    /** What a tool window nobody has ever opened is worth: enough to place it, nothing remembered. */
    public static ToolWindowState initial(String typeId, DockDropZone anchor, int order) {
        return new ToolWindowState(typeId, false, anchor, DEFAULT_WEIGHT, order,
                null, -1, List.of(), true, true, null, DockDropZone.SPLIT_LEFT);
    }

    /** The share of its axis a tool window takes when nothing has ever sized it. */
    public static final float DEFAULT_WEIGHT = 0.20f;

    private final String typeId;
    private final boolean visible;
    private final DockDropZone anchor;
    private final float weight;
    private final int order;
    @Nullable
    private final DockPath path;
    private final int indexInParent;
    private final List<DockPanelRef> groupedWith;
    private final boolean active;
    private final boolean showStripeButton;
    @Nullable
    private final DockPanelRef relativeTo;
    private final DockDropZone relativeZone;

    private ToolWindowState(String typeId, boolean visible, DockDropZone anchor, float weight, int order,
                            @Nullable DockPath path, int indexInParent, List<DockPanelRef> groupedWith,
                            boolean active, boolean showStripeButton,
                            @Nullable DockPanelRef relativeTo, DockDropZone relativeZone) {
        this.typeId = Objects.requireNonNull(typeId, "typeId");
        this.visible = visible;
        this.anchor = Objects.requireNonNull(anchor, "anchor");
        this.weight = weight;
        this.order = order;
        this.path = path;
        this.indexInParent = indexInParent;
        this.groupedWith = Collections.unmodifiableList(List.copyOf(groupedWith));
        this.active = active;
        this.showStripeButton = showStripeButton;
        this.relativeTo = relativeTo;
        this.relativeZone = relativeZone == null ? DockDropZone.SPLIT_LEFT : relativeZone;
    }

    public String typeId() {
        return typeId;
    }

    /** Whether it was on screen. What a restore reopens, and what the stripe button lights for. */
    public boolean visible() {
        return visible;
    }

    /** Which wall it opens against when {@link #path} cannot be honoured. */
    /**
     * The region this tool window belongs to — see {@link DockRegion}.
     *
     * <p><b>Derived from {@link #anchor()} rather than stored</b>, deliberately: it is the same fact said
     * the durable way, so deriving it costs no persisted field and therefore no version bump. The field
     * appears — and the bump with it — at plan.md §23 step 7, when a region becomes a real element and an
     * anchor stops being able to express one.</p>
     *
     * <p>Ask this rather than {@link #anchor()} in anything new. An anchor is a wall of the current tree;
     * a region survives the tree changing, which is the whole point of the Parts model.</p>
     */
    public DockRegion region() {
        return DockRegion.ofWall(anchor);
    }

    public DockDropZone anchor() {
        return anchor;
    }

    /** Its share of the axis it divides — {@code DockNode.size()}. */
    public float weight() {
        return weight;
    }

    /** Its position on the activity bar. */
    public int order() {
        return order;
    }

    /** Where its leaf sat in the tree, or null if it has never been placed. */
    @Nullable
    public DockPath path() {
        return path;
    }

    /** Which child of {@link #path} its leaf was; -1 when unknown. */
    public int indexInParent() {
        return indexInParent;
    }

    /** The panels that shared its strip, so it can rejoin them rather than open in a pane of its own. */
    public List<DockPanelRef> groupedWith() {
        return groupedWith;
    }

    /**
     * A panel that was <b>next to</b> this one, and which side of it this one was on.
     *
     * <h3>Why a relative position and not only a path</h3>
     *
     * <p>{@link #path} names a branch, and closing a panel usually <em>destroys</em> that branch: a leaf
     * alone in a two-child branch leaves one sibling behind, and {@code normalise()} correctly dissolves
     * the branch and splices the survivor upward. So the most common arrangement of all — a tool window in
     * a pane of its own — is exactly the one whose path stops resolving the moment it is hidden.</p>
     *
     * <p>A neighbour survives that, because it is still on screen. Stating the position as "to the right
     * of <em>that</em> panel" reproduces the arrangement with the same operation that created it, and it
     * stays meaningful however the tree above it has been rearranged since.</p>
     *
     * <p>Null when the panel had no neighbour — a layout of one pane, which needs no relative anything.</p>
     */
    @Nullable
    public DockPanelRef relativeTo() {
        return relativeTo;
    }

    /** Which side of {@link #relativeTo} this panel was on. */
    public DockDropZone relativeZone() {
        return relativeZone;
    }

    public ToolWindowState withRelativeTo(@Nullable DockPanelRef neighbour, DockDropZone zone) {
        return new ToolWindowState(typeId, visible, anchor, weight, order, path, indexInParent,
                groupedWith, active, showStripeButton, neighbour, zone);
    }

    /** Whether it was the selected tab in its strip. A restored tool window that is not comes back hidden. */
    public boolean active() {
        return active;
    }

    /** Whether it appears on the activity bar at all — IntelliJ's {@code show_stripe_button}. */
    public boolean showStripeButton() {
        return showStripeButton;
    }

    public ToolWindowState withVisible(boolean nowVisible) {
        return new ToolWindowState(typeId, nowVisible, anchor, weight, order, path, indexInParent,
                groupedWith, active, showStripeButton, relativeTo, relativeZone);
    }

    public ToolWindowState withAnchor(DockDropZone nowAnchor) {
        return new ToolWindowState(typeId, visible, nowAnchor, weight, order, path, indexInParent,
                groupedWith, active, showStripeButton, relativeTo, relativeZone);
    }

    public ToolWindowState withWeight(float nowWeight) {
        return new ToolWindowState(typeId, visible, anchor, nowWeight, order, path, indexInParent,
                groupedWith, active, showStripeButton, relativeTo, relativeZone);
    }

    public ToolWindowState withOrder(int nowOrder) {
        return new ToolWindowState(typeId, visible, anchor, weight, nowOrder, path, indexInParent,
                groupedWith, active, showStripeButton, relativeTo, relativeZone);
    }

    public ToolWindowState withPlacement(@Nullable DockPath nowPath, int nowIndex) {
        return new ToolWindowState(typeId, visible, anchor, weight, order, nowPath, nowIndex,
                groupedWith, active, showStripeButton, relativeTo, relativeZone);
    }

    public ToolWindowState withGroupedWith(List<DockPanelRef> nowGrouped) {
        return new ToolWindowState(typeId, visible, anchor, weight, order, path, indexInParent,
                nowGrouped, active, showStripeButton, relativeTo, relativeZone);
    }

    public ToolWindowState withActive(boolean nowActive) {
        return new ToolWindowState(typeId, visible, anchor, weight, order, path, indexInParent,
                groupedWith, nowActive, showStripeButton, relativeTo, relativeZone);
    }

    public ToolWindowState withShowStripeButton(boolean shown) {
        return new ToolWindowState(typeId, visible, anchor, weight, order, path, indexInParent,
                groupedWith, active, shown, relativeTo, relativeZone);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ToolWindowState other)) return false;
        return visible == other.visible && Float.compare(weight, other.weight) == 0
                && order == other.order && indexInParent == other.indexInParent
                && active == other.active && showStripeButton == other.showStripeButton
                && typeId.equals(other.typeId) && anchor == other.anchor
                && Objects.equals(path, other.path) && groupedWith.equals(other.groupedWith)
                && Objects.equals(relativeTo, other.relativeTo) && relativeZone == other.relativeZone;
    }

    @Override
    public int hashCode() {
        return Objects.hash(typeId, visible, anchor, weight, order, path, indexInParent, groupedWith,
                active, showStripeButton, relativeTo, relativeZone);
    }

    @Override
    public String toString() {
        return "ToolWindowState[" + typeId + (visible ? " visible" : " hidden") + " " + anchor
                + " w=" + weight + " at=" + path + ":" + indexInParent + "]";
    }
}
