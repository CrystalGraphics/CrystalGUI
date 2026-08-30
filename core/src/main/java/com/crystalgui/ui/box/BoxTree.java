package com.crystalgui.ui.box;

import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.ComputedStyle;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.ui.UITransform;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UINode;
import dev.vfyjxf.taffy.geometry.FloatSize;
import dev.vfyjxf.taffy.geometry.TaffySize;
import dev.vfyjxf.taffy.style.AvailableSpace;
import dev.vfyjxf.taffy.style.TaffyDimension;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import dev.vfyjxf.taffy.tree.Layout;
import dev.vfyjxf.taffy.tree.NodeId;
import dev.vfyjxf.taffy.tree.TaffyTree;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import org.joml.Matrix4f;

/**
 * The box tree: one layout tree per {@link UIDocument}, derived from the composed node tree, laid out
 * in ONE pass, with world matrices and hit-testing that need no paint to have happened.
 *
 * <p>What it replaces is the old engine's layout being a property of the element (a Taffy node id
 * on every {@code UIElement}, created at attach and reparented by hand), settled by a
 * {@code while (isLayoutDirty())} loop, with geometry cached in the element and refreshed by the
 * paint — so "clicks land where things were drawn" was a promise kept by two caches agreeing, and
 * hit-testing an unpainted tree read stale matrices (plan_engine_core_audit.md §1, §2).</p>
 *
 * <h3>The pass</h3>
 *
 * <ol>
 *   <li><b>Sync.</b> When the node tree's structure changed since the last pass, the composed tree
 *   is walked and boxes are created for nodes that have none and destroyed for nodes that are
 *   gone or are {@code display: none}. Hosting is resolved (natural or overridden) and the layout
 *   engine's child lists are rewritten where they differ.</li>
 *   <li><b>Style.</b> Every box whose node's {@link ComputedStyle} is not the one it last applied
 *   is re-mapped through {@link BoxStyle}; an unchanged snapshot costs one reference compare.</li>
 *   <li><b>Layout.</b> {@code computeLayout} runs once if anything is dirty, with every
 *   {@link Measurable} answered inside it. Nothing feeds back into the cascade.</li>
 *   <li><b>Read.</b> Geometry is copied into the boxes and world matrices composed top-down,
 *   scroll and transform included.</li>
 * </ol>
 *
 * <p>A structure change is REPORTED by the node tree ({@link UIDocument#addStructureListener}), so the
 * walk is skipped on frames where nothing moved, and a mutation nothing reported cannot exist —
 * the node tree owns its mutators.</p>
 *
 * <h3>Mirrors</h3>
 *
 * <p>{@link #mirror(UINode, Box)} lays a subtree out a second time under another host — what a
 * taskbar thumbnail is. The old engine drew a subtree twice and corrupted hit-testing unless the
 * pass said it was a copy, because every element reconciled ONE cached matrix against whatever pose
 * it was last drawn with. A mirror has boxes of its own, so each copy has its own matrices and its
 * own place in the hit order; the node is not told it is drawn twice.</p>
 */
public final class BoxTree {

    private final UIDocument document;
    private final TaffyTree taffy = new TaffyTree();

    /** The node's own box, for every node that has one. */
    private final Map<UINode, Box> boxes = new IdentityHashMap<>();
    private final List<Mirror> mirrors = new ArrayList<>();
    private @Nullable Box root;

    private boolean structureDirty = true;
    private boolean transformsDirty = true;
    private int hostedSequence;
    private int layoutPasses;
    private int syncPasses;
    private float viewportWidth, viewportHeight;
    private final Matrix4f rootTransform = new Matrix4f();

    /** A subtree laid out again under another host. */
    private static final class Mirror {
        final UINode subtree;
        final Box root;
        final Map<UINode, Box> realm = new IdentityHashMap<>();

        Mirror(UINode subtree, Box root) {
            this.subtree = subtree;
            this.root = root;
            realm.put(subtree, root);
        }
    }

    public BoxTree(UIDocument document) {
        this.document = document;
        taffy.disableRounding();
        document.addStructureListener(this::structureChanged);
    }

    // ── Queries ──────────────────────────────────────────────────────────────

    /** The document's box. Null until the first layout. */
    public @Nullable Box root() {
        return root;
    }

    /** The node's own box, or null when it has none — off the tree, or {@code display: none}. */
    public @Nullable Box boxOf(UINode node) {
        return boxes.get(node);
    }

    /** How many times the layout engine has been asked to compute. The one-pass metric. */
    public int layoutPasses() {
        return layoutPasses;
    }

    /** How many times the composed tree has been walked to rebuild boxes. */
    public int syncPasses() {
        return syncPasses;
    }

    /** The topmost hit-testable box at a world point, or null over nothing. */
    public @Nullable Box hitTest(float worldX, float worldY) {
        return root == null ? null : root.hitTest(worldX, worldY);
    }

    /** As above, passing over what {@code skip} admits — inertness, which the focus service answers. */
    public @Nullable Box hitTest(float worldX, float worldY, Predicate<Box> skip) {
        return root == null ? null : root.hitTest(worldX, worldY, skip);
    }

    /**
     * The transform from the document's own space to the SURFACE — the one definition of what
     * {@code uiScale} means here.
     *
     * <p>It seeds {@link Box#localToWorld}, so painting and hit-testing both pick it up by reading
     * the matrix they already read: there is no second place a scale can be applied and no window in
     * which the two can disagree. The old engine kept it on the window and had to invalidate every
     * cached transform when it moved.</p>
     */
    public void setRootTransform(Matrix4f transform) {
        rootTransform.set(transform);
        transformsDirty = true;
    }

    public Matrix4f rootTransform() {
        return new Matrix4f(rootTransform);
    }

    // ── Mirrors ──────────────────────────────────────────────────────────────

    /**
     * Lays {@code subtree} out a second time, hosted under {@code host}. The mirror's root box is
     * returned; position it as any hosted box (a {@code left}/{@code top} in its node's style would
     * move the original too, so a caller that wants the copy elsewhere hosts it under a box that is
     * elsewhere, or sets a transform on the returned box).
     */
    public Box mirror(UINode subtree, Box host) {
        if (host.tree != this) throw new IllegalArgumentException("host belongs to another box tree");
        Box mirrorRoot = new Box(this, subtree, true);
        mirrorRoot.hostOverride = host;
        mirrorRoot.hostedSequence = nextHostedSequence();
        mirrorRoot.taffyId = newLeaf(mirrorRoot);
        mirrors.add(new Mirror(subtree, mirrorRoot));
        structureChanged();
        return mirrorRoot;
    }

    /** Takes a mirror down. A no-op for a box that is not a mirror root. */
    public void unmirror(Box mirrorRoot) {
        for (int i = 0; i < mirrors.size(); i++) {
            Mirror mirror = mirrors.get(i);
            if (mirror.root != mirrorRoot) continue;
            for (Box box : new ArrayList<>(mirror.realm.values())) destroy(box);
            mirrors.remove(i);
            structureChanged();
            return;
        }
    }

    // ── The pass ─────────────────────────────────────────────────────────────

    /**
     * Lays the document out at the viewport size: sync if the structure moved, restyle what changed,
     * compute once if anything is dirty, read the results and compose the world matrices.
     */
    public void layout(float width, float height) {
        document.require("layout");
        if (structureDirty || root == null) {
            sync();
            structureDirty = false;
        }
        Box root = this.root;
        if (root == null) throw new IllegalStateException("the document has no box");
        boolean viewportMoved = width != viewportWidth || height != viewportHeight;
        viewportWidth = width;
        viewportHeight = height;
        refreshStyles(root);
        for (Mirror mirror : mirrors) refreshStyles(mirror.root);
        // The document's box IS the viewport, whatever its style says -- written after the style
        // refresh, which would otherwise hand it back its sheet's `auto` on the next restyle. And it
        // is a BLOCK container unless a sheet says otherwise: CSS's root is one, so children stack
        // vertically at their own heights, where a flex row would stretch each to the whole viewport.
        root.bridge.setWidth(TaffyDimension.length(width));
        root.bridge.setHeight(TaffyDimension.length(height));
        if (root.appliedStyle == null || !root.appliedStyle.isSet(LayoutProperties.DISPLAY)) {
            root.bridge.setDisplay(TaffyDisplay.BLOCK);
        }
        if (viewportMoved) taffy.markDirty(root.taffyId);
        if (taffy.isDirty(root.taffyId)) {
            taffy.computeLayout(root.taffyId,
                    TaffySize.of(AvailableSpace.definite(width), AvailableSpace.definite(height)));
            layoutPasses++;
            read(root);
            transformsDirty = true;
        }
        if (transformsDirty) {
            compose(root, rootTransform, 0f, 0f);
            transformsDirty = false;
        }
    }

    /**
     * Paints the tree through the shared paint context, with whatever pose is on the stack as the
     * surface transform. Layout first -- the painter draws what {@link #layout} composed.
     */
    public void paint(CgUiPaintContext ctx) {
        BoxPainter.paint(this, ctx);
    }

    // ── Sync ─────────────────────────────────────────────────────────────────

    private void sync() {
        syncPasses++;
        Set<Box> live = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Box> inOrder = new ArrayList<>();
        root = syncNode(document, null, boxes, false, live, inOrder);
        for (Mirror mirror : mirrors) {
            live.add(mirror.root);
            inOrder.add(mirror.root);
            for (UINode child : mirror.subtree.composedChildren()) {
                syncNode(child, mirror.root, mirror.realm, true, live, inOrder);
            }
        }
        // Boxes nobody walked to are gone -- and anything hosted on one of them goes home.
        reap(boxes.values(), live);
        for (Mirror mirror : mirrors) reap(mirror.realm.values(), live);
        for (Box box : inOrder) {
            if (box.hostOverride != null && !live.contains(box.hostOverride)) {
                box.hostOverride = null;
                box.hostedSequence = 0;
            }
        }
        // Hosting: natural children first in document order, then overrides in the order declared.
        for (Box box : inOrder) {
            box.hosted.clear();
            box.invalidatePaintOrder();
        }
        for (Box box : inOrder) {
            if (box.hostOverride == null && box.naturalHost != null) box.naturalHost.hosted.add(box);
        }
        List<Box> overridden = new ArrayList<>();
        for (Box box : inOrder) {
            if (box.hostOverride != null) overridden.add(box);
        }
        overridden.sort((a, b) -> Integer.compare(a.hostedSequence, b.hostedSequence));
        for (Box box : overridden) box.hostOverride.hosted.add(box);
        // The layout engine's child lists follow the hosting, rewritten only where they differ.
        for (Box box : inOrder) {
            NodeId[] wanted = new NodeId[box.hosted.size()];
            for (int i = 0; i < wanted.length; i++) wanted[i] = box.hosted.get(i).taffyId;
            if (!sameChildren(box.taffyId, wanted)) taffy.setChildren(box.taffyId, wanted);
        }
    }

    private @Nullable Box syncNode(UINode node, @Nullable Box naturalHost, Map<UINode, Box> realm,
                                   boolean mirror, Set<Box> live, List<Box> inOrder) {
        // No box, and none below it -- reaped with everything else not walked to. A FROZEN subtree is
        // the same answer for a different reason: it is still in the tree and is not live, so it lays
        // out nothing, paints nothing and hit-tests nothing until it is thawed.
        if (node != document && node.computedStyle().get(LayoutProperties.DISPLAY) == TaffyDisplay.NONE) {
            return null;
        }
        if (node.isFrozen()) return null;
        Box box = realm.get(node);
        if (box == null) {
            box = new Box(this, node, mirror);
            box.taffyId = newLeaf(box);
            realm.put(node, box);
            if (!mirror) node.setBox(box);
        }
        box.naturalHost = naturalHost;
        live.add(box);
        inOrder.add(box);
        for (UINode child : node.composedChildren()) syncNode(child, box, realm, mirror, live, inOrder);
        return box;
    }

    private void reap(Iterable<Box> candidates, Set<Box> live) {
        List<Box> dead = new ArrayList<>();
        for (Box box : candidates) if (!live.contains(box)) dead.add(box);
        for (Box box : dead) destroy(box);
    }

    private void destroy(Box box) {
        if (box.mirror) {
            for (Mirror mirror : mirrors) mirror.realm.remove(box.node, box);
        } else {
            boxes.remove(box.node, box);
            if (box.node.box() == box) box.node.setBox(null);
        }
        if (taffy.containsNode(box.taffyId)) taffy.remove(box.taffyId);
        if (box == root) root = null;
    }

    private NodeId newLeaf(Box box) {
        NodeId id = taffy.newLeaf(box.bridge.style);
        if (box.node instanceof Measurable) {
            Measurable measurable = (Measurable) box.node;
            taffy.setMeasureFunc(id, (known, available) -> {
                Measurable.Size size = measurable.measure(new Measurable.Constraints(
                        known.width, known.height,
                        available.width.isDefinite() ? available.width.getValue() : Float.NaN,
                        available.height.isDefinite() ? available.height.getValue() : Float.NaN,
                        fitOf(available.width), fitOf(available.height)));
                return new FloatSize(size.width(), size.height());
            });
        }
        return id;
    }

    private static Measurable.Fit fitOf(AvailableSpace space) {
        return space.isMinContent() ? Measurable.Fit.MIN_CONTENT : Measurable.Fit.MAX_CONTENT;
    }

    private boolean sameChildren(NodeId parent, NodeId[] wanted) {
        List<NodeId> current = taffy.getChildren(parent);
        if (current.size() != wanted.length) return false;
        for (int i = 0; i < wanted.length; i++) {
            if (!current.get(i).equals(wanted[i])) return false;
        }
        return true;
    }

    // ── Style ────────────────────────────────────────────────────────────────

    private void refreshStyles(Box box) {
        ComputedStyle computed = box.node.computedStyle();
        if (computed != box.appliedStyle) {
            int zBefore = box.appliedStyle == null ? 0 : box.appliedStyle.get(StylePropertyRegistry.Z_INDEX);
            BoxStyle.apply(box.bridge, computed);
            box.appliedStyle = computed;
            taffy.markDirty(box.taffyId);
            transformsDirty = true;
            if (computed.get(StylePropertyRegistry.Z_INDEX) != zBefore) {
                Box host = box.host();
                if (host != null) host.invalidatePaintOrder();
            }
        }
        for (Box child : box.hosted) refreshStyles(child);
    }

    // ── Read + compose ───────────────────────────────────────────────────────

    private void read(Box box) {
        Layout layout = taffy.getLayout(box.taffyId);
        box.x = layout.location().x;
        box.y = layout.location().y;
        box.width = layout.size().width;
        box.height = layout.size().height;
        box.contentWidth = layout.contentSize().width;
        box.contentHeight = layout.contentSize().height;
        box.border = layout.border();
        box.padding = layout.padding();
        for (Box child : box.hosted) read(child);
    }

    private void compose(Box box, Matrix4f hostWorld, float hostScrollLeft, float hostScrollTop) {
        box.localToWorld.set(hostWorld).translate(box.x - hostScrollLeft, box.y - hostScrollTop, 0f);
        UITransform transform = box.transform();
        if (!transform.isIdentity()) {
            ComputedStyle style = box.node.computedStyle();
            LengthPercent originX = style.get(StylePropertyRegistry.TRANSFORM_ORIGIN_X);
            LengthPercent originY = style.get(StylePropertyRegistry.TRANSFORM_ORIGIN_Y);
            transform.applyTo(box.localToWorld, 0f, 0f, box.width, box.height,
                    originX == null ? 0f : originX.resolve(box.width),
                    originY == null ? 0f : originY.resolve(box.height));
        }
        box.localToWorld.invert(box.worldToLocal);
        for (Box child : box.hosted) compose(child, box.localToWorld, box.scrollLeft(), box.scrollTop());
    }

    // ── Dirtying ─────────────────────────────────────────────────────────────

    void structureChanged() {
        structureDirty = true;
    }

    void transformsChanged() {
        transformsDirty = true;
    }

    void markDirty(Box box) {
        if (taffy.containsNode(box.taffyId)) taffy.markDirty(box.taffyId);
    }

    int nextHostedSequence() {
        return ++hostedSequence;
    }
}
