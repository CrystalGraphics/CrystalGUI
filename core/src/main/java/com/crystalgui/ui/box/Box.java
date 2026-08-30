package com.crystalgui.ui.box;

import com.crystalgui.style.ComputedStyle;
import com.crystalgui.style.TaffyBridge;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.Overflow;
import com.crystalgui.ui.UITransform;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.UINode;
import dev.vfyjxf.taffy.geometry.FloatRect;
import dev.vfyjxf.taffy.tree.NodeId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * One node's place in the layout: its geometry, its world transform, its scroll offset, and the
 * box it is HOSTED under — which is its composed parent's box unless something said otherwise.
 *
 * <p>Everything that made the old engine's "promoted element diverges from its DOM parent in FOUR
 * places" row true is one field here: {@link #setHost}. A popup, a window's owned dialog and a
 * torn-out thumbnail all say where they are laid out, painted and hit-tested by naming a host, and
 * the containing block, the paint order and the hit-test entry follow from that one fact because
 * they are all read off the hosting tree. The node tree is not told; a host is not a parent.</p>
 *
 * <p>Geometry is relative to the host's border-box origin, before the host's scroll offset is
 * applied — what the layout engine reports. {@link #localToWorld()} is the composed answer: the
 * host's world matrix, this box's offset, the host's scroll, and this box's {@code transform}
 * about its {@code transform-origin}. Hit-testing inverts exactly that matrix, so a click lands on
 * what was drawn: there is one definition of where a box is.</p>
 *
 * <p>A box is created and destroyed by its {@link BoxTree}; nothing else constructs one.</p>
 */
public final class Box {

    final BoxTree tree;
    final UINode node;
    final boolean mirror;
    final TaffyBridge bridge;
    NodeId taffyId;

    /** The composed parent's box, as of the last sync. Null for the root. */
    @Nullable Box naturalHost;
    /** A host something chose instead of the natural one. */
    @Nullable Box hostOverride;
    /** Order in which overrides were declared, so two popups hosted on the root stack in the order they opened. */
    int hostedSequence;
    /** The boxes hosted here, in insertion order: natural children first, then overrides by sequence. */
    final List<Box> hosted = new ArrayList<>();
    private @Nullable List<Box> paintOrder;

    @Nullable ComputedStyle appliedStyle;

    // Geometry, as the layout engine reported it.
    float x, y, width, height, contentWidth, contentHeight;
    FloatRect border = new FloatRect(0f, 0f, 0f, 0f);
    FloatRect padding = new FloatRect(0f, 0f, 0f, 0f);

    // Per-box state that is not style: what the compositor is animating. The SCROLL is the node's,
    // so it survives a freeze with nothing captured and a mirror shows the same offset.
    private @Nullable Integer zIndexOverride;
    private @Nullable Float opacityOverride;
    private @Nullable UITransform transformOverride;

    final Matrix4f localToWorld = new Matrix4f();
    final Matrix4f worldToLocal = new Matrix4f();

    Box(BoxTree tree, UINode node, boolean mirror) {
        this.tree = tree;
        this.node = node;
        this.mirror = mirror;
        this.bridge = new TaffyBridge(node.getStyle());
    }

    // ── Identity ─────────────────────────────────────────────────────────────

    public UINode node() {
        return node;
    }

    public BoxTree tree() {
        return tree;
    }

    /** A second box for a node that already has one — a thumbnail's copy. Never the node's own. */
    public boolean isMirror() {
        return mirror;
    }

    // ── Hosting ──────────────────────────────────────────────────────────────

    /** The box this one is laid out under: the override if one was set, else the composed parent's. */
    public @Nullable Box host() {
        return hostOverride != null ? hostOverride : naturalHost;
    }

    /**
     * Lays this box out under {@code host} instead of under its composed parent's box: the
     * containing block, the paint order and the hit-test entry all move with it, and the node tree
     * is untouched. {@code null} returns it to its natural host.
     *
     * <p>Hosting is a fact about the BOX tree; a host must belong to the same tree.</p>
     */
    public void setHost(@Nullable Box host) {
        if (host != null && host.tree != tree) throw new IllegalArgumentException("host belongs to another box tree");
        if (host == this) throw new IllegalArgumentException("a box cannot host itself");
        if (hostOverride == host) return;
        hostOverride = host;
        hostedSequence = host == null ? 0 : tree.nextHostedSequence();
        tree.structureChanged();
    }

    /** The boxes hosted here in PAINT order: z-index ascending, ties in insertion order. */
    public List<Box> children() {
        List<Box> order = paintOrder;
        if (order == null) {
            order = new ArrayList<>(hosted);
            order.sort(Comparator.comparingInt(Box::zIndex));
            paintOrder = order = Collections.unmodifiableList(order);
        }
        return order;
    }

    void invalidatePaintOrder() {
        paintOrder = null;
    }

    // ── Geometry ─────────────────────────────────────────────────────────────

    /** Offset from the host's border-box origin, before the host's scroll. */
    public float x() {
        return x;
    }

    public float y() {
        return y;
    }

    public float width() {
        return width;
    }

    public float height() {
        return height;
    }

    public FloatRect border() {
        return border;
    }

    public FloatRect padding() {
        return padding;
    }

    /** The extent of what is laid out inside — what a scroll offset can reach. */
    public float contentWidth() {
        return contentWidth;
    }

    public float contentHeight() {
        return contentHeight;
    }

    /** Where this box's origin lands in world space, transforms and scrolls applied. */
    public float worldX() {
        return localToWorld.m30();
    }

    public float worldY() {
        return localToWorld.m31();
    }

    /** The one matrix both painting and hit-testing use. Read-only. */
    public Matrix4f localToWorld() {
        return localToWorld;
    }

    public Matrix4f worldToLocal() {
        return worldToLocal;
    }

    // ── Per-box state ────────────────────────────────────────────────────────

    public float scrollLeft() {
        return node.scrollLeft();
    }

    public float scrollTop() {
        return node.scrollTop();
    }

    /** Scrolls what this box hosts. Clamped to the content on the next layout read. */
    public void setScroll(float left, float top) {
        left = clamp(left, 0f, Math.max(0f, contentWidth - width));
        top = clamp(top, 0f, Math.max(0f, contentHeight - height));
        if (left == node.scrollLeft() && top == node.scrollTop()) return;
        node.setScrollOffsets(left, top);
        tree.transformsChanged();
    }

    public int zIndex() {
        if (zIndexOverride != null) return zIndexOverride;
        return node.computedStyle().get(StylePropertyRegistry.Z_INDEX);
    }

    /** A compositor's z, above the cascade's; {@code null} withdraws it. */
    public void setZIndex(@Nullable Integer zIndex) {
        zIndexOverride = zIndex;
        Box host = host();
        if (host != null) host.invalidatePaintOrder();
    }

    public float opacity() {
        if (opacityOverride != null) return opacityOverride;
        return node.computedStyle().get(StylePropertyRegistry.OPACITY);
    }

    public void setOpacity(@Nullable Float opacity) {
        opacityOverride = opacity;
    }

    public UITransform transform() {
        if (transformOverride != null) return transformOverride;
        UITransform t = node.computedStyle().get(StylePropertyRegistry.TRANSFORM);
        return t == null ? UITransform.IDENTITY : t;
    }

    /** A compositor's transform, above the cascade's; {@code null} withdraws it. Layout-free. */
    public void setTransform(@Nullable UITransform transform) {
        transformOverride = transform;
        tree.transformsChanged();
    }

    /** The node's {@code hit-test} attribute — off means this box AND everything it hosts is passed over. */
    public boolean hitTestable() {
        return node.get(Attribute.HIT_TEST);
    }

    /** Whether the box clips what it hosts to its own border box. */
    public boolean clips() {
        Overflow overflow = node.computedStyle().get(StylePropertyRegistry.OVERFLOW);
        return overflow != null && overflow != Overflow.VISIBLE;
    }

    /**
     * Scrolls every clipping ancestor just far enough to reveal this box.
     *
     * <p>Instant, never eased: this is what a Tab press and a programmatic focus do, and easing it
     * would leave focus somewhere the user cannot see for the length of the animation.</p>
     *
     * <p>Walks innermost-outward tracking what it has already moved: scrolling an ancestor moves
     * this box relative to EVERY ancestor above it by the same amount, and none of them move, so one
     * running offset is exact and no re-composition is needed part way.</p>
     */
    public void scrollIntoView() {
        float shiftX = 0f, shiftY = 0f;
        for (Box ancestor = host(); ancestor != null; ancestor = ancestor.host()) {
            if (!ancestor.clips()) continue;
            FloatRect b = ancestor.border();
            float viewLeft = ancestor.worldX() + b.left;
            float viewTop = ancestor.worldY() + b.top;
            float viewRight = viewLeft + Math.max(0f, ancestor.width() - b.left - b.right);
            float viewBottom = viewTop + Math.max(0f, ancestor.height() - b.top - b.bottom);

            float left = worldX() + shiftX;
            float top = worldY() + shiftY;
            float right = left + width;
            float bottom = top + height;

            float dx = 0f, dy = 0f;
            if (right > viewRight) dx = right - viewRight;
            if (left - dx < viewLeft) dx = left - viewLeft;    // a box taller than the view aligns to its start
            if (bottom > viewBottom) dy = bottom - viewBottom;
            if (top - dy < viewTop) dy = top - viewTop;
            if (dx == 0f && dy == 0f) continue;

            float beforeX = ancestor.scrollLeft(), beforeY = ancestor.scrollTop();
            ancestor.setScroll(beforeX + dx, beforeY + dy);
            shiftX -= ancestor.scrollLeft() - beforeX;
            shiftY -= ancestor.scrollTop() - beforeY;
        }
    }

    /** Says the layout under this box must be recomputed. The node's style calls it; so may a skin. */
    public void markLayoutDirty() {
        tree.markDirty(this);
    }

    // ── Hit-testing ──────────────────────────────────────────────────────────

    /**
     * The topmost hit-testable box at a world point, searching what this box hosts in reverse
     * paint order — so the last-painted box is found first, which is the engine's own rule that
     * paint order and hit order must agree. Walks the same matrices painting uses; needs no paint
     * to have happened.
     */
    public @Nullable Box hitTest(float worldX, float worldY) {
        return hitTest(worldX, worldY, box -> false);
    }

    /**
     * As {@link #hitTest(float, float)}, passing over every box {@code skip} admits — how INERTNESS
     * reaches hit-testing without the box tree having to know what inert means. An inert subtree
     * falls THROUGH to what is behind it: {@code pointer-events: none} passes the pointer over a
     * node, it does not punch a hole in the document.
     */
    public @Nullable Box hitTest(float worldX, float worldY, Predicate<Box> skip) {
        if (!hitTestable()) return null;
        Vector4f p = new Vector4f(worldX, worldY, 0f, 1f);
        worldToLocal.transform(p);
        boolean inside = p.x >= 0f && p.y >= 0f && p.x < width && p.y < height;
        if (!inside && clips()) return null;
        List<Box> order = children();
        for (int i = order.size() - 1; i >= 0; i--) {
            Box hit = order.get(i).hitTest(worldX, worldY, skip);
            if (hit != null) return hit;
        }
        // SKIPPED means "not the answer", never "nor anything inside me". An inert node's children
        // are inert too when the reason is the ATTRIBUTE, so a subtree still falls through whole --
        // but when the reason is a MODAL, the one box the pointer may still reach is inside the box
        // that is blocked, and skipping wholesale would put the modal out of reach as well.
        // `hit-test` is the property that IS subtree-wide, and it is checked above.
        return inside && !skip.test(this) ? this : null;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    @Override
    public String toString() {
        return (mirror ? "Box(mirror " : "Box(") + node + " " + x + "," + y + " " + width + "x" + height + ")";
    }
}
