package com.crystalgui.ui;

import com.crystalgraphics.api.PoseStack;
import com.crystalgui.core.data.Transform2D;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.StyleEngine;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.ui.input.UIInputHandler;
import com.crystalgui.ui.tree.UITreeTraversal;
import dev.vfyjxf.taffy.geometry.TaffySize;
import dev.vfyjxf.taffy.style.AvailableSpace;
import dev.vfyjxf.taffy.style.TaffyDimension;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import dev.vfyjxf.taffy.style.TaffyPosition;
import dev.vfyjxf.taffy.tree.Layout;
import dev.vfyjxf.taffy.tree.NodeId;
import dev.vfyjxf.taffy.tree.TaffyTree;
import lombok.Getter;
import lombok.Setter;
import org.joml.Matrix4f;

import java.util.*;

/**
 * Runtime engine. Owns the paint context, the live
 * {@link TaffyTree}, and drives the per-frame layout + paint entry points.
 *
 * <p>Deliberately does NOT implement any platform (LWJGL2/LWJGL3/MC) widget or Screen
 * interface itself. Need MC-Sided adapters.</p>
 */
public final class UIWindow {
    public static final Layout EMPTY_LAYOUT = new Layout();

    public final Ui ui;

    @Getter
    private final TaffyTree taffyTree;
    private NodeId rootNodeId;

    @Getter
    private final UIInputHandler inputHandler = new UIInputHandler(this);

    @Getter
    private final StyleEngine styleEngine = new StyleEngine(this);
    private long lastFrameNanos = System.nanoTime();

    private final List<UIElement> elements = new ArrayList<>();

    private int actualScreenWidth;
    private int actualScreenHeight;

    @Getter
    private float uiScale = 2;
    /** @see #getRootTransform() */
    private final Matrix4f rootTransform = new Matrix4f().scale(2, 2, 1f);
    @Getter
    private float leftPos, topPos, width, height;
    @Getter
    private float layoutWidth = Float.NaN, layoutHeight = Float.NaN;
    @Getter @Setter
    private int screenWidth, screenHeight;


    private final Map<NodeId, UIElement> elementByNode = new HashMap<>();
    private final Set<NodeId> nodesWithNewLayout = new HashSet<>();
    private final Set<NodeId> nodesWithNewGeometry = new HashSet<>();

    public UIWindow(Ui ui) {
        this.ui = ui;
        this.taffyTree = new TaffyTree();
        this.taffyTree.disableRounding();
        this.taffyTree.setLayoutChangeListener(((nodeId, oldLayout, newLayout) -> {
            nodesWithNewLayout.add(nodeId);
            if (Objects.equals(oldLayout, newLayout)) return;
            nodesWithNewGeometry.add(nodeId);
        }));
    }

    public void init(int screenWidth, int screenHeight) {
        if (this.actualScreenWidth == screenWidth && this.actualScreenHeight == screenHeight)
            return;

        inputHandler.resetHandler();
        this.actualScreenWidth = screenWidth;
        this.actualScreenHeight = screenHeight;

        this.screenWidth = Math.round(actualScreenWidth / uiScale);
        this.screenHeight = Math.round(actualScreenHeight / uiScale);

        final var rootElement = ui.rootElement;

        if (rootElement.getAttachedWindow() != this)
            rootElement.setAttachedWindow(this);

        rootElement.initScreen(this.screenWidth, this.screenHeight);
        rootElement.getStyle().markTaffyStyleDirty();
        calculateLayout();
    }

    /** The root's declared width/height, or {@code auto} when unset. */
    private TaffyDimension rootDimension(StyleProperty<TaffyDimension> property) {
        return Optional.ofNullable(ui.rootElement.getStyle().computeCandidate(property))
                .orElseGet(TaffyDimension::auto);
    }

    /**
     * Refreshes the available space handed to Taffy from the root's <em>current</em> declared size.
     *
     * <p>Only a percentage root gets definite available space; anything else sizes to content. This
     * has to be re-read per layout rather than once at {@link #init}, because {@code init} runs before
     * any stylesheet has been applied (scenes call {@code init} then {@code paintFrame}, and
     * {@code drainDirtyMatch} only runs inside {@code calculateStyle}) and then early-returns forever
     * after. A root that becomes percentage-sized via CSS would otherwise keep {@code MAX_CONTENT}
     * available space for the rest of the run and size to its content instead.</p>
     */
    private void resolveRootAvailableSpace() {
        this.layoutWidth = rootDimension(LayoutProperties.WIDTH).isPercent() ? this.screenWidth : Float.NaN;
        this.layoutHeight = rootDimension(LayoutProperties.HEIGHT).isPercent() ? this.screenHeight : Float.NaN;
    }

    /**
     * Recomputes the root's on-screen box and centring offset from its <em>resolved</em> layout.
     *
     * <p>Must run per layout, not once per screen resize. {@link UIElement#getLayoutX()} returns
     * {@link #getLeftPos()} for the root and every other element's absolute position accumulates from
     * there, so a stale offset here silently mis-positions the entire tree — which is exactly what
     * happened to any window whose root was sized from a stylesheet rather than from Java.</p>
     */
    private void resolveRootPlacement() {
        final var rootElement = ui.rootElement;
        var width = rootDimension(LayoutProperties.WIDTH);
        var height = rootDimension(LayoutProperties.HEIGHT);

        boolean isRelative = Optional.ofNullable(
                        rootElement.getStyle().computeCandidate(LayoutProperties.POSITION))
                .orElse(TaffyPosition.RELATIVE) != TaffyPosition.ABSOLUTE;

        var bounds = rootElement.getRuntimeCache();
        this.width = switch (width.getType()) {
            case PERCENT -> width.getValue() * this.screenWidth;
            case LENGTH -> width.getValue();
            default -> bounds.getWidth(); // auto — take whatever the layout resolved to
        };
        this.height = switch (height.getType()) {
            case PERCENT -> height.getValue() * this.screenHeight;
            case LENGTH -> height.getValue();
            default -> bounds.getHeight();
        };

        var rootTaffyLocation = rootElement.getTaffyLayout().location();
        float newLeft = Math.round(isRelative ? (this.screenWidth - this.width) / 2 : rootTaffyLocation.x);
        float newTop = Math.round(isRelative ? (this.screenHeight - this.height) / 2 : rootTaffyLocation.y);

        // Only invalidate on a real change: every element's cached absolute position derives from
        // these, so clearing unconditionally would throw the whole tree's layout cache away each frame.
        if (newLeft != this.leftPos || newTop != this.topPos) {
            this.leftPos = newLeft;
            this.topPos = newTop;
            rootElement.clearLayoutCache();
        }
    }

    void calculateLayout() {
        resolveRootAvailableSpace();

        TaffySize<AvailableSpace> availableSpace = new TaffySize<>(
                Float.isNaN(layoutWidth) ? AvailableSpace.MAX_CONTENT : AvailableSpace.definite(layoutWidth),
                Float.isNaN(layoutHeight) ? AvailableSpace.MAX_CONTENT : AvailableSpace.definite(layoutHeight)
        );

        while (isLayoutDirty()) {
            if (taffyTree.isDirty(ui.rootElement.taffyNodeId)) {
                taffyTree.computeLayout(ui.rootElement.taffyNodeId, availableSpace);

                for (var nodeId : nodesWithNewLayout) {
                    var element = elementByNode.get(nodeId);
                    if (element != null) {
                        element.onLayoutChanged(nodesWithNewGeometry.contains(nodeId));
                    }
                }
                nodesWithNewLayout.clear();
                nodesWithNewGeometry.clear();
            }

        }

        resolveRootPlacement();
    }

    public boolean isLayoutDirty() {
        return taffyTree.isDirty(ui.rootElement.taffyNodeId);
    }

    /**
     * Lays out and paints the whole tree, once, synchronously, right now. Call this from
     * wherever your per-frame render hook lives (harness scene, or later the platform
     * adapter's render callback). No batching, no queued commands — by the time this method
     * returns, every visible element's GPU draw calls have already been issued in painter's
     * order, using bounds computed by this same call.
     */
    /**
     * Everything {@link #paintFrame()} does <em>except</em> painting: advance the frame clock,
     * resolve styles, tick animations, and run layout.
     *
     * <p>Exists so layout can be driven without a GL surface or a draw — headless tests, and
     * benchmarks that need to isolate layout/shaping cost from rendering cost. A UI with many text
     * elements pays a per-element material bind at draw time that can dwarf everything else, and
     * measuring layout through {@code paintFrame()} therefore measures mostly the renderer.
     *
     * <p>Deliberately does not touch the input handler: no frame was presented, so there is nothing
     * for hover/click state to be relative to.
     */
    public void updateWithoutPainting() {
        advanceFrame();
    }

    /** Shared prologue of {@link #paintFrame()} and {@link #updateWithoutPainting()}. */
    private float advanceFrame() {
        long now = System.nanoTime();
        float deltaSeconds = (now - lastFrameNanos) / 1_000_000_000f;
        lastFrameNanos = now;

        styleEngine.calculateStyle(deltaSeconds);
        tickAnimations(deltaSeconds);
        calculateLayout();
        return deltaSeconds;
    }

    public void paintFrame() {
        advanceFrame();

        CgUiPaintContext paintContext = CgUiPaintContext.getInstance();
        paintContext.beginFrame(actualScreenWidth, actualScreenHeight);

        PoseStack pose = paintContext.getPoseStack();
        pose.pushPose();

        // Same matrix RuntimeCache.localToWorld falls back to, so painted and not-yet-painted
        // frames agree on what uiScale means. Don't inline a scale() here — that's how the two
        // definitions drifted before.
        pose.mulPoseMatrix(rootTransform);

        ui.rootElement.drawSubtree(paintContext);

        pose.popPose();

        paintContext.endFrame();
        inputHandler.beginFrame();
        inputHandler.endFrame();
    }


    public void unregisterElement(UIElement element) {
        if (element == null) return;


        elementByNode.remove(element.taffyNodeId);
        if (element.taffyNodeId != null) {
            if (element.getParent() != null) {
                var parentID = element.getParent().taffyNodeId;
                // parent may already belong to other tree.
                if (parentID != null && taffyTree.containsNode(parentID)) {
                    taffyTree.removeChild(parentID, element.taffyNodeId);
                }
            }
            taffyTree.remove(element.taffyNodeId);
            element.taffyNodeId = null;
        }

        elements.remove(element);
        styleEngine.onElementDetached(element);
    }

    public void registerElement(UIElement element) {
        if (element == null) return;

        elements.add(element);

        element.taffyNodeId = taffyTree.newLeaf(element.getStyle().getTaffyBridge().style);
        var measureFunc = element.measureFunc();
        if (measureFunc != null) {
            taffyTree.setMeasureFunc(element.taffyNodeId, measureFunc);
        }
        elementByNode.put(element.taffyNodeId, element);
        if (element.getParent() != null) {
            var parentID = element.getParent().taffyNodeId;
            if (taffyTree.containsNode(parentID)) {
                taffyTree.insertChildAtIndex(parentID, element.getSiblingIndex(), element.taffyNodeId);
            }
        }
        styleEngine.markDirty(element);
    }

    /** Every element currently attached to this window's tree. Read-only — used by {@link StyleEngine}
     * to re-match the whole tree when a stylesheet is added or removed. */
    public List<UIElement> getElements() {
        return Collections.unmodifiableList(elements);
    }

    /**
     * The transform every element's {@code localToWorld} chain hangs off: physical pixels per
     * logical layout unit.
     *
     * <p><b>Single source of truth for what {@code uiScale} means.</b> {@link #paintFrame} seeds the
     * {@code PoseStack} from this, and {@link UIElement.RuntimeCache#localToWorld} falls back to it
     * for the root — so hit-testing is correct <em>before</em> anything has ever been painted.
     * Previously the two were defined independently (the pose scaled itself, the cache fell back to
     * identity) and disagreed by exactly {@code uiScale} until the first paint installed the real
     * matrix, which made pointer maths silently wrong in that window.</p>
     *
     * <p>The scale deliberately lives here and in the {@code PoseStack} rather than in the ortho
     * projection: {@code CgTextRenderer} picks its glyph raster size from the pose scale
     * ({@code baseTargetPx * extractMaxScale(pose)}), so moving it would rasterize glyphs at logical
     * size and let the projection magnify them — blurry text. {@code CgUiPaintContext.pushScissor}
     * also reads this matrix to reach physical {@code glScissor} pixels, which the projection has no
     * effect on.</p>
     *
     * @return the live internal matrix — treat as read-only.
     */
    public Matrix4f getRootTransform() {
        return rootTransform;
    }

    /** Rescales the whole tree. Invalidates every cached transform, since they all derive from
     * {@link #getRootTransform()} — without that, hit-testing would keep using the old scale. */
    public void setUiScale(float uiScale) {
        if (this.uiScale == uiScale) return;
        this.uiScale = uiScale;
        this.rootTransform.identity().scale(uiScale, uiScale, 1f);
        invalidatePoseCaches(ui.rootElement);
    }

    private static void invalidatePoseCaches(UIElement element) {
        element.getRuntimeCache().resetPoseCache();
        for (UIElement child : element.getChildren()) {
            invalidatePoseCaches(child);
        }
    }

    // ── Smooth scrolling ────────────────────────────────────────────────────

    /** Elements with a smooth scroll in flight. Only these are ticked, so the cost is zero on a
     * window with nothing animating. */
    private final Set<UIElement> scrollAnimations = new HashSet<>();

    void registerScrollAnimation(UIElement element) {
        scrollAnimations.add(element);
    }

    /**
     * Advances every in-flight smooth scroll. Driven from {@link #paintFrame()}; call it directly if
     * you drive layout yourself (as the headless tests do).
     */
    public void tickScrollAnimations(float deltaSeconds) {
        if (scrollAnimations.isEmpty()) return;
        scrollAnimations.removeIf(element ->
                element.getAttachedWindow() != this || !element.tickScrollAnimation(deltaSeconds));
    }

    /** Per-frame callbacks that aren't scroll animations — press-and-hold repeats, blinking carets. */
    private final Set<UIFrameTicker> tickers = new HashSet<>();

    /** Registers a per-frame callback; it is dropped as soon as it reports it's done. */
    public void registerTicker(UIFrameTicker ticker) {
        tickers.add(ticker);
    }

    /** Everything that wants a per-frame callback: smooth scrolls plus registered tickers. Driven
     * from {@link #paintFrame()}; call it directly if you drive frames yourself. */
    public void tickAnimations(float deltaSeconds) {
        tickScrollAnimations(deltaSeconds);
        if (!tickers.isEmpty()) {
            // Snapshot: a ticker may register another (or itself) while running.
            for (UIFrameTicker ticker : new ArrayList<>(tickers)) {
                if (!ticker.tickFrame(deltaSeconds)) tickers.remove(ticker);
            }
        }
    }

    // ── Tree queries ────────────────────────────────────────────────────────

    /**
     * First element in the window matching {@code selector}, in document order, or {@code null}.
     *
     * <p>Unlike {@link UIElement#querySelector}, the root element <em>is</em> a candidate — it plays
     * the part of the document here, so a window-level query considering it matches
     * {@code document.querySelector}. Same selector subset and same live-tree combinator semantics;
     * see {@link UITreeTraversal#querySelector}.</p>
     */
    public UIElement querySelector(String selector) {
        return UITreeTraversal.querySelector(ui.rootElement, selector, true);
    }

    /** Every match in the window, in document order, root included. */
    public List<UIElement> querySelectorAll(String selector) {
        return UITreeTraversal.querySelectorAll(ui.rootElement, selector, true);
    }

    /** First element in the window with this id, or {@code null}. Root included. */
    public UIElement getElementById(String id) {
        return UITreeTraversal.getElementById(ui.rootElement, id, true);
    }

    /** Every element in the window carrying this class, in document order. Root included. */
    public List<UIElement> getElementsByClassName(String className) {
        return UITreeTraversal.getElementsByClassName(ui.rootElement, className, true);
    }

    public UIElement getHoveredElement(float mouseX, float mouseY) {
        return elementHitTest(ui.rootElement, mouseX, mouseY);
    }

    private UIElement elementHitTest(UIElement element, float mouseX, float mouseY) {
        if (element.getStyle().taffyBridge.style.display == TaffyDisplay.NONE) return null;

        Matrix4f transform = element.getRuntimeCache().worldToLocal.get();
        var local = Transform2D.apply(transform, mouseX, mouseY);
        float localX = local.x(), localY = local.y();
        var overflow = element.resolveOverflowClip();
        boolean contentCanClipOut = overflow.isClipped();
        if (!contentCanClipOut || element.isMouseOverContent(localX, localY, overflow)) {
            for (var child : element.getRuntimeCache().sortedChildren.get()) {
                var result = elementHitTest(child, mouseX, mouseY);
                if (result != null) {
                    return result;
                }
            }
        }
        if (element.isHitTest() && element.isMouseOverElement(localX, localY)) {
            return element;
        }
        return null;
    }

}
