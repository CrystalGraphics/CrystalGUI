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
        mirrorRoot.mirrorRoot = true;
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
        for (Mirror mirror : mirrors) {
            refreshStyles(mirror.root);
            pinMirrorSize(mirror);
        }
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
            clampScrolls(root);
            transformsDirty = true;
        }
        composeIfDirty();
    }

    /**
     * Whether another {@link #layout} pass would actually compute anything.
     *
     * <p>What a post-layout hook leaves behind when it writes geometry: the style resolves, the
     * property listener reaches {@code TaffyBridge}, and the node is marked dirty — with this frame's
     * layout already over. Asked by {@code UIDocument.frame} so the write lands on the frame that made
     * it rather than the one after.</p>
     */
    public boolean isLayoutDirty() {
        return root == null || structureDirty || taffy.isDirty(root.taffyId);
    }

    /**
     * Re-composes every world matrix if anything has dirtied them since the last pass.
     *
     * <p><b>Called again after the post-layout hooks, and that is not tidiness.</b> The compose is
     * part of {@link #layout}, so a hook that runs after layout and moves a box — which is precisely
     * what {@code Animation.afterLayout} is documented for — wrote into a tree whose matrices were
     * already composed, and the paint that followed drew the box where layout had left it. The move
     * then appeared on the NEXT frame, one frame late, every time.</p>
     *
     * <p>It cost a window's restore animation a visible rest frame: {@code show()} reattaches from an
     * input handler, which is dispatched after layout, so the frame had no box at all; the animation's
     * first write landed after the following frame's compose, and the window painted once at its
     * resting geometry before anything moved.</p>
     *
     * <p>Free when nothing moved — the flag is false and this returns.</p>
     */
    public void composeIfDirty() {
        if (!transformsDirty) return;
        compose(root, rootTransform, 0f, 0f);
        transformsDirty = false;
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
        // THE DOCUMENT'S PROMOTIONS, re-applied on every sync. Recorded on the node rather than
        // written onto a box (@see UIDocument#promote), because a box is rebuilt whenever its
        // subtree is hidden or restructured -- so a host written onto one is lost, and a popup
        // hidden and reshown would come back unpromoted.
        Box topLayer = boxes.get(document.topLayerNodeIfPresent());
        if (topLayer != null) {
            topLayer.setStacksByInsertion(true);
            topLayer.stackingOnly = true;
            // BOTH DIRECTIONS, and the withdrawal is the half that is easy to miss: this pass is the
            // only writer of a top-layer override, so it must also be the only eraser. Applying
            // promotions alone leaves a demoted box hosted where the LAST sync put it -- demote()
            // would appear to do nothing, and only for a node that had been promoted before.
            // Scoped to overrides pointing at the top layer, so a mirror's or an owned window's
            // host -- set through Box.setHost, which is still the general mechanism -- is untouched.
            for (Box box : inOrder) {
                if (box.hostOverride == topLayer && !document.isPromoted(box.node)) {
                    box.hostOverride = null;
                    box.hostedSequence = 0;
                }
            }
            int sequence = 0;
            for (UINode node : document.promotedNodes()) {
                Box box = boxes.get(node);
                if (box == null || box == topLayer) continue;
                box.hostOverride = topLayer;
                box.hostedSequence = ++sequence;
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
        // `hidden` is the SAME answer through a different door, and it is structural rather than a
        // stylesheet rule because this selector engine has no attribute selectors -- HTML's own
        // `[hidden] { display: none }` cannot be written here. It is also what the old engine
        // effectively did: `setDisplayed` wrote `display` at IMPORTANT origin from 74 sites, which no
        // author sheet could override either. @see com.crystalgui.ui.dom.Attribute#HIDDEN
        if (node != document && !node.isDisplayed()) return null;
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
        // THE HOST THAT LOST A CHILD MUST BE RE-LAID OUT, and nothing else says so.
        //
        // `TaffyTree.remove` takes the node out of its parent's child list and marks NOTHING dirty
        // (unlike `setChildren`, which marks the parent) -- and because it has already updated the
        // list, the `sameChildren` check below skips the `setChildren` that would have. So a subtree
        // that goes away leaves its former siblings exactly where they were until something
        // unrelated dirties layout: a row removed from a list leaves a gap, a hidden panel keeps its
        // space, and both correct themselves the next time anything else moves, which is what makes
        // it read as intermittent. Found by the first `hidden` test in M6.0; it was equally true of
        // an ordinary `remove()` since 5.3 and nothing had removed a node between two layouts.
        Box host = box.host();
        if (host != null && taffy.containsNode(host.taffyId)) taffy.markDirty(host.taffyId);
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

    /**
     * Gives a mirror root the size its source settled on, so the copy does not re-flow.
     *
     * <p><b>A mirror is a PICTURE of a layout, not a second participant in one.</b> Its root shares the
     * source's node and therefore its computed style, and a style is full of things that resolve
     * against whatever contains the box: {@code max-width: 100%} is on every window in the shipped
     * sheet, and against a thumbnail a hundred pixels wide it squeezed a 200px window down to its own
     * min-content — so the taskbar preview showed a NARROWER window than the one it was picturing, with
     * its text re-wrapped and its content cut off at the bottom. Which reads as a clipped or badly
     * scaled preview, when the picture was in fact drawn perfectly at a size nobody wanted.</p>
     *
     * <p>So the root is pinned to the source's measured border box and its minimums and maximums are
     * cleared: nothing about the host may reach the copy. The scale down to thumbnail size is the
     * caller's TRANSFORM, which is layout-free and cannot reflow anything — see {@link #mirror}.</p>
     *
     * <p>Pinned every pass rather than on a style change, because the source's size moves without its
     * style moving: a window resized by a drag writes insets, and everything inside it settles from
     * layout alone.</p>
     */
    private void pinMirrorSize(Mirror mirror) {
        Box source = boxes.get(mirror.subtree);
        if (source == null) return;
        Box root = mirror.root;
        float width = source.width();
        float height = source.height();
        if (width == root.pinnedWidth && height == root.pinnedHeight) return;
        root.pinnedWidth = width;
        root.pinnedHeight = height;
        root.bridge.setWidth(TaffyDimension.length(width));
        root.bridge.setHeight(TaffyDimension.length(height));
        root.bridge.setMinWidth(TaffyDimension.length(0f));
        root.bridge.setMinHeight(TaffyDimension.length(0f));
        root.bridge.setMaxWidth(TaffyDimension.auto());
        root.bridge.setMaxHeight(TaffyDimension.auto());
        taffy.markDirty(root.taffyId);
    }

    private void refreshStyles(Box box) {
        ComputedStyle computed = box.node.computedStyle();
        // The HOSTING is an input to the layout style as well as the computed style -- see
        // BoxStyle.apply(.., hosted). Promoting a node changes no style of its own, so comparing the
        // computed style alone would leave a freshly promoted popup in flow.
        boolean hosted = box.hostOverride != null;
        if (computed != box.appliedStyle || hosted != box.appliedHosted) {
            box.appliedHosted = hosted;
            int zBefore = box.appliedStyle == null ? 0 : box.appliedStyle.get(StylePropertyRegistry.Z_INDEX);
            BoxStyle.apply(box.bridge, computed, hosted, box.mirrorRoot);
            // AND ANY PIN IS GONE WITH IT. `BoxStyle.apply` writes width, height and the minimums and
            // maximums straight from the source's computed style, so re-applying a style silently
            // undoes `pinMirrorSize` -- which then early-outs, because the SOURCE's size has not
            // changed and that is all it was comparing. The mirror was left clamped by
            // `window { max-width: 100% }` against its thumbnail, exactly as it was before the pin
            // existed.
            //
            // It presents as intermittent, which is the tell: the pin holds until the first restyle of
            // that node -- a hover, a selection class, the reveal -- and a switcher tile gets one
            // within a frame or two of opening. Reported as "it sizes fine for the first frame or two,
            // then breaks", and measured as a mirror alternating between its source's size and its
            // host's on otherwise identical inputs.
            box.pinnedWidth = Float.NaN;
            box.pinnedHeight = Float.NaN;
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

    /**
     * Re-clamps every box's scroll against the content it has just laid out.
     *
     * <p>Runs after the read and before composition, which is the only order that works: the clamp
     * needs the settled content size, and composition bakes the offset into the world matrices. A
     * folder collapsing, a list filtering, a panel narrowing all shrink content under an offset that
     * was legal a frame ago — without this the view sits past its own end, showing a strip of the
     * last rows against a screenful of nothing, with the scrollbar gone.</p>
     *
     * <p>Free when nothing is out of range: {@link Box#clampScroll} compares before it writes.</p>
     */
    private void clampScrolls(Box box) {
        box.clampScroll();
        for (Box child : box.hosted) clampScrolls(child);
    }

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
        // SCROLL-EXEMPT: this box does not move with what hosts it. A scroller's own bars, an
        // editor's gutter and its find bar are all children of the thing that scrolls, and without
        // this they scroll away with the content they are for. It is applied HERE because this is
        // the only place a host's offset is ever applied -- an element that wanted to opt out any
        // further down would be undoing an offset already baked into its parent's matrix.
        if (box.node.isScrollExempt()) {
            hostScrollLeft = 0f;
            hostScrollTop = 0f;
        }
        box.localToWorld.set(hostWorld).translate(box.x - hostScrollLeft, box.y - hostScrollTop, 0f);
        UITransform transform = box.transform();
        if (!transform.isIdentity()) {
            if (box.mirrorRoot) {
                // A MIRROR ROOT SCALES ABOUT ITS OWN CORNER, never about its source's
                // `transform-origin`. The transform on a mirror is a PLACEMENT written by whoever owns
                // the copy -- fit it, then centre it in my box -- and it is expressed in the host's
                // space from the corner out. Applied about the source's origin instead, the picture
                // pivots about wherever that node happens to put it: a window's origin is written by
                // the window animations (pinned for an animation's whole life, because it is not
                // interpolable), so a taskbar preview scaled correctly and then hung outside its own
                // panel, over the taskbar.
                //
                // The old engine had no way to get this wrong and that is the tell: it composed the
                // pose by hand -- `translate(left, top); scale(s, s); translate(-src.getX(), -src.getY())`
                // -- and a pose scales about ITS origin, with `transform-origin` nowhere in it.
                transform.applyTo(box.localToWorld, 0f, 0f, box.width, box.height, 0f, 0f);
                box.localToWorld.invert(box.worldToLocal);
                for (Box child : box.hosted) compose(child, box.localToWorld, box.scrollLeft(), box.scrollTop());
                return;
            }
            ComputedStyle style = box.node.computedStyle();
            // THE COMPOSITOR'S ORIGIN OUTRANKS THE CASCADE'S, and is pinned for its animation's whole
            // life -- @see Box#setTransformOrigin, which records what a re-resolved one cost.
            Float pinnedX = box.transformOriginX();
            Float pinnedY = box.transformOriginY();
            LengthPercent originX = style.get(StylePropertyRegistry.TRANSFORM_ORIGIN_X);
            LengthPercent originY = style.get(StylePropertyRegistry.TRANSFORM_ORIGIN_Y);
            transform.applyTo(box.localToWorld, 0f, 0f, box.width, box.height,
                    pinnedX != null ? pinnedX : originX == null ? 0f : originX.resolve(box.width),
                    pinnedY != null ? pinnedY : originY == null ? 0f : originY.resolve(box.height));
        }
        box.localToWorld.invert(box.worldToLocal);
        for (Box child : box.hosted) compose(child, box.localToWorld, box.scrollLeft(), box.scrollTop());
    }

    // ── Dirtying ─────────────────────────────────────────────────────────────

    void structureChanged() {
        structureDirty = true;
    }

    /** Public because a node's {@code scroll-exempt} changes composition without changing layout. */
    public void transformsChanged() {
        transformsDirty = true;
    }

    void markDirty(Box box) {
        if (taffy.containsNode(box.taffyId)) taffy.markDirty(box.taffyId);
    }

    int nextHostedSequence() {
        return ++hostedSequence;
    }
}
