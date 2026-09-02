package com.crystalgui.widget.dnd;

import com.crystalgui.ui.dom.ResizeHandles;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.visual.Resize;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.service.Drag;
import dev.vfyjxf.taffy.style.TaffyDimension;
import dev.vfyjxf.taffy.style.TaffyPosition;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import javax.annotation.Nullable;

/**
 * One grab handle implementing {@code resize:} — ported from the old engine's {@code UIResizer}.
 *
 * <h3>Built by a widget, where the old engine built them from the cascade</h3>
 *
 * <p>{@code UIElement.rebuildResizers} watched the {@code resize} property and grew a set of internal
 * children whenever it changed. That cannot be done here and should not be: the node tree has no
 * property listeners that mutate structure, and a style change that ADDS eight nodes is a structural
 * change made from inside the style pass — the exact re-entrancy {@code UIDocument} refuses. So a
 * resizable widget calls {@link #install} once, in its constructor, and the handles are ordinary light
 * children it owns. {@code resize:} is still read, per drag, so a sheet can still switch a widget
 * between {@code both}, {@code horizontal}, {@code vertical} and {@code none} — what it can no longer
 * do is bring handles into existence on an arbitrary element.</p>
 *
 * <h3>Eight handles is not a divergence</h3>
 *
 * <p>CSS UI 4 says only that the UA "presents a bidirectional resizing mechanism" — it never
 * prescribes a single bottom-right grabber. Browsers ship one because theirs lives in the scrollbar
 * corner and has nowhere else to go; we draw our own, so all four edges and all four corners are
 * available. LDLib2's {@code WindowDragHelper.ResizeHandle} offers the same eight, which is where the
 * idea came from.</p>
 *
 * <p>Position and appearance are left entirely to the sheet via the per-handle classes — no pixel
 * values here, per the usual rule.</p>
 */
public final class Resizer extends UINode {

    public static final Name NAME = Name.of("resizer");

    /** On every handle, whichever edge it is. */
    public static final String RESIZER_CLASS = "__resizer__";

    /**
     * Which edges a handle moves.
     *
     * <p>{@code dx}/{@code dy} are −1, 0 or +1: the sign says which edge follows the pointer, and a
     * <b>negative</b> one means the opposite edge stays put — so the element has to move as well as
     * resize. That is the entire reason {@link UINode#applyResizeOrigin} exists.</p>
     */
    public enum Handle {
        TOP(0, -1), BOTTOM(0, 1), LEFT(-1, 0), RIGHT(1, 0),
        TOP_LEFT(-1, -1), TOP_RIGHT(1, -1), BOTTOM_LEFT(-1, 1), BOTTOM_RIGHT(1, 1);

        public final int dx, dy;

        Handle(int dx, int dy) {
            this.dx = dx;
            this.dy = dy;
        }

        /** Lower-case, dash-separated: {@code TOP_LEFT} to {@code __resizer-top-left__}. */
        public String styleClass() {
            return "__resizer-" + name().toLowerCase().replace('_', '-') + "__";
        }

        /** Whether dragging this handle has to move the element's origin — a top or left edge. */
        public boolean isLeading() {
            return dx < 0 || dy < 0;
        }

        /**
         * A handle exists if <em>any</em> axis it touches is resizable, and a drag then moves only the
         * axes the mode allows.
         *
         * <p>This used to require EVERY axis, so {@code resize: horizontal} had no corners — a corner
         * would imply a vertical resize the mode forbids. Defensible, and not what a browser does: a
         * {@code textarea} with {@code resize: horizontal} still shows the bottom-right grabber and
         * simply changes width alone. The stricter reading also costs the only grip there is — that
         * corner is the one handle the sheet draws — so a single-axis box ends up resizable with
         * nothing on screen saying so, and moving the grip onto a full-length edge strip paints
         * something that reads as a scrollbar.</p>
         *
         * <p>So the constraint moves from WHICH HANDLES EXIST to WHAT A DRAG DOES, which is where the
         * user can see it. @see Resizer#applyResize</p>
         */
        public boolean appliesTo(Resize mode) {
            if (!mode.isResizable()) return false;
            if (dx != 0 && mode.allowsWidth()) return true;
            return dy != 0 && mode.allowsHeight();
        }
    }

    /**
     * Appends the handle set {@code target} can use, and returns it.
     *
     * <p>The five leading handles are omitted outright when {@link UINode#canMoveResizeOrigin} is
     * false, which leaves bottom, right and the corner — CSS's own default grabber. Everything else is
     * decided per drag from the live {@code resize} value, so a sheet can still narrow the set.</p>
     */
    public static List<Resizer> install(UINode target, Resize mode) {
        List<Resizer> handles = new ArrayList<>(Handle.values().length);
        boolean leading = target.canMoveResizeOrigin();
        for (Handle handle : Handle.values()) {
            if (handle.isLeading() && !leading) continue;
            // AND THE MODE DECIDES THE REST, which it did not until now: every non-leading handle was
            // built whatever the sheet said, so `resize: horizontal` grew a bottom-right CORNER --
            // the one handle that draws the grip. `applyResize` then refused it, correctly, on the
            // rule this class already states: a handle exists only if EVERY axis it touches is
            // resizable. So the only visible grabber on a horizontal-only box was one that could
            // never move it, and the box read as not resizable at all.
            if (!handle.appliesTo(mode)) continue;
            Resizer resizer = new Resizer(handle, target);
            ResizeHandles.attach(target, resizer);
            handles.add(resizer);
        }
        return handles;
    }



    private final Handle handle;
    private final @Nullable UINode target;

    /**
     * Box at the moment the drag began.
     *
     * <p>Accumulated from here rather than from the live box: the box changes as we resize it, so
     * reading it each frame would compound the delta and the element would race away from the
     * cursor.</p>
     */
    private float startWidth, startHeight, startLeft, startTop;

    /**
     * An UNBOUND handle — what a description decodes into, and what nothing drags.
     *
     * <p>A handle is built by {@link #install} for a resizable node and is never described over a
     * wire, so this exists for the registry rather than for a caller: every concrete node needs a kind
     * and every kind needs a factory. {@code beginResize} returns on a null target, so an unbound one
     * is decoration.</p>
     */
    public Resizer() {
        this(Handle.BOTTOM_RIGHT, null);
    }

    private Resizer(Handle handle, @Nullable UINode target) {
        super(NAME);
        this.handle = handle;
        this.target = target;
        addClass(RESIZER_CLASS);
        addClass(handle.styleClass());

        // Out of flow: handles overlay their parent's edges and must not consume a slot in its layout,
        // or a resizable element would visibly reflow its content.
        StyleGroup.defaultPipeline(getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE));

        // AND EXEMPT FROM THE SCROLL OFFSET. A handle is pinned to its parent's VISIBLE edge -- that is
        // what "resize this element" means -- but a scroll offset is applied to every non-exempt child,
        // so on a scrollable element the handles slid away with the content: scrolling down by one line
        // carried the bottom-right grabber up out of the corner, and the corner stopped responding.
        setScrollExempt(true);

        onMouseDown.attachListener((node, event) -> beginResize(event), false, false);
    }

    public Handle handle() {
        return handle;
    }

    private void beginResize(MouseEvent.Down event) {
        if (target == null) return;
        UINode node = target;
        Box box = node.box();
        if (box == null || node.document() == null) return;

        startWidth = box.width();
        startHeight = box.height();
        startLeft = target.resizeOriginLeft();
        startTop = target.resizeOriginTop();

        // A positional drag: no payload, no drop targets, and no threshold -- a resize must track the
        // very first pixel or small adjustments would be impossible.
        Drag.start(this, event.getPosition().x(), event.getPosition().y(),
                (x, y, sx, sy, dx, dy) -> applyResize(dx, dy));
    }

    private void applyResize(float deltaX, float deltaY) {
        if (target == null) return;
        UINode node = target;
        Resize mode = node.computedStyle().get(StylePropertyRegistry.RESIZE);
        if (mode == null || !handle.appliesTo(mode)) return;

        // A trailing edge grows by the drag; a leading edge grows by its negation and moves the origin
        // to match, so the opposite edge stays where it was.
        // PER AXIS, because a handle may touch one the mode forbids -- a bottom-right corner under
        // `resize: horizontal` exists (a browser shows it) and must move width alone. A forbidden axis
        // keeps its starting value rather than being refused, or the whole drag dies with it.
        float width = mode.allowsWidth() ? startWidth + handle.dx * deltaX : startWidth;
        float height = mode.allowsHeight() ? startHeight + handle.dy * deltaY : startHeight;

        // Clamp to the element's OWN min/max first. This is not what constrains the box -- Taffy applies
        // these regardless -- it is what lets the origin below be derived from a size the element will
        // actually settle at. Without it the origin followed the raw pointer delta while the size sat
        // pinned at its minimum, so dragging a top edge downward shrank the window to min-height and then
        // started towing it down the screen, while the mirror-image drag from the bottom correctly just
        // stopped.
        width = clampToStyleRange(node, width, true);
        height = clampToStyleRange(node, height, false);

        // Then keep the box inside its containing block -- for out-of-flow elements only, which is the
        // same set that has leading handles at all. Moving was already clamped this way and sizing was
        // not, so a panel parked in the bottom-right corner could be resized straight out through it.
        UINode container = target.canMoveResizeOrigin() ? target.resizeContainingBlock() : null;
        Box available = container == null ? null : container.box();
        if (available != null) {
            // A trailing edge is bounded by the far side of the container. A leading edge is bounded by
            // its own origin reaching zero, which caps growth at everything between the container's near
            // side and the edge that is staying put.
            if (handle.dx > 0) width = Math.min(width, available.width() - startLeft);
            if (handle.dx < 0) width = Math.min(width, startLeft + startWidth);
            if (handle.dy > 0) height = Math.min(height, available.height() - startTop);
            if (handle.dy < 0) height = Math.min(height, startTop + startHeight);
        }

        final float finalWidth = Math.max(0f, width);
        final float finalHeight = Math.max(0f, height);

        // INLINE origin, NOT IMPORTANT. The spec is explicit that a user resize writes the style
        // attribute "without !important", so an author's !important rule still wins. Everything else
        // that writes geometry from code uses IMPORTANT; this is the deliberate exception.
        StyleGroup.inlinePipeline(node.getStyle().getLayoutGroup(), l -> {
            if (handle.dx != 0) l.width(finalWidth);
            if (handle.dy != 0) l.height(finalHeight);
        });

        // AND SAY SO. Everything else that writes geometry from code writes at a higher origin than
        // this INLINE one, so a widget that sizes itself would overwrite the drag every frame and the
        // handle would appear dead on that axis. @see UINode#markUserSized
        target.markUserSized(handle.dx != 0, handle.dy != 0);

        // The origin follows the size that was ACHIEVED, never the pointer. That is what pins the
        // opposite edge in place, and what makes the element stop moving the instant it stops resizing.
        if (handle.isLeading()) {
            target.applyResizeOrigin(
                    handle.dx < 0 ? startLeft + (startWidth - finalWidth) : target.resizeOriginLeft(),
                    handle.dy < 0 ? startTop + (startHeight - finalHeight) : target.resizeOriginTop());
        }

        // LAST, so an override sees the geometry the drag settled on and its own writes are not then
        // overwritten by the origin above.
        target.onUserResize(handle.dx, handle.dy, finalWidth, finalHeight);
    }

    /**
     * Clamps a desired size into the element's own {@code min-*}/{@code max-*}.
     *
     * <p>Only definite lengths participate: a percentage would have to be resolved against the
     * containing block, and {@code auto} is not a bound at all. Both are left to Taffy — the point is
     * not to constrain the box but to <em>predict</em> the size it will settle at.</p>
     */
    private static float clampToStyleRange(UINode node, float desired, boolean horizontal) {
        TaffyDimension min = node.computedStyle().get(
                horizontal ? LayoutProperties.MIN_WIDTH : LayoutProperties.MIN_HEIGHT);
        TaffyDimension max = node.computedStyle().get(
                horizontal ? LayoutProperties.MAX_WIDTH : LayoutProperties.MAX_HEIGHT);
        if (min != null && min.getType() == TaffyDimension.Type.LENGTH) {
            desired = Math.max(desired, min.getValue());
        }
        if (max != null && max.getType() == TaffyDimension.Type.LENGTH) {
            desired = Math.min(desired, max.getValue());
        }
        return desired;
    }

    // ── Driven by the cascade ───────────────────────────────────────────────

    /**
     * Makes {@code resize} ambient: any node the sheets give a resizable mode grows handles, and any
     * node whose mode goes back to {@code none} loses them.
     *
     * <p>Registered once, from {@code Widgets}. The engine watches the computed value and calls this;
     * it cannot build a handle itself, because {@code ui.dom} is below the widget layer. @see
     * ResizeHandles</p>
     *
     */
    public static void driveFromStyle() {
        ResizeHandles.setInstaller(Resizer::apply);
    }

    /** Idempotent, because a computed value is re-resolved on every match this node takes part in. */
    private static void apply(UINode node, Resize mode) {
        // THE SET, not merely whether there is one. Comparing presence was enough while every
        // resizable node got the same three handles; now that the mode decides which exist, a change
        // from `both` to `horizontal` leaves the count non-zero and would have been skipped -- the
        // stale corner surviving a mode change is the same dead grabber, arriving a different way.
        EnumSet<Handle> want = EnumSet.noneOf(Handle.class);
        if (mode.isResizable()) {
            boolean leading = node.canMoveResizeOrigin();
            for (Handle handle : Handle.values()) {
                if (handle.isLeading() && !leading) continue;
                if (handle.appliesTo(mode)) want.add(handle);
            }
        }
        EnumSet<Handle> have = EnumSet.noneOf(Handle.class);
        for (UINode child : node.children()) {
            if (child instanceof Resizer resizer) have.add(resizer.handle);
        }
        if (want.equals(have)) return;

        for (UINode child : new ArrayList<>(node.children())) {
            if (child instanceof Resizer) node.remove(child);
        }
        if (!want.isEmpty()) install(node, mode);
    }

}
