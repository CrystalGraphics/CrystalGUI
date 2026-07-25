package com.crystalgui.ui;

import com.crystalgraphics.api.PoseStack;
import com.crystalgraphics.api.vertex.CgVertexTransformUtil;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.StyleEngine;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.ui.input.UIInputHandler;
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
    private static final CgUiPaintContext paintContext = new CgUiPaintContext();

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
    @Setter
    private float uiScale = 4;
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
        final var rootStyle = rootElement.getStyle();

        var isRelative = Optional.ofNullable(rootStyle.computeCandidate(LayoutProperties.POSITION))
                .orElse(TaffyPosition.RELATIVE) != TaffyPosition.ABSOLUTE;

        var width = Optional.ofNullable(rootStyle.computeCandidate(LayoutProperties.WIDTH))
                .orElseGet(TaffyDimension::auto);
        var height = Optional.ofNullable(rootStyle.computeCandidate(LayoutProperties.HEIGHT))
                .orElseGet(TaffyDimension::auto);

        this.width = switch (width.getType()) {
            case PERCENT -> width.getValue() * this.screenWidth;
            case LENGTH -> width.getValue();
            default -> 0;
        };
        this.height = switch (height.getType()) {
            case PERCENT -> height.getValue() * this.screenHeight;
            case LENGTH -> height.getValue();
            default -> 0;
        };
        if (width.isPercent()) {
            this.layoutWidth = this.screenWidth;
        } else {
            this.layoutWidth = Float.NaN;
        }
        if (height.isPercent()) {
            this.layoutHeight = this.screenHeight;
        } else {
            this.layoutHeight = Float.NaN;
        }

        if (rootElement.getAttachedWindow() != this)
            rootElement.setAttachedWindow(this);

        rootElement.initScreen(this.screenWidth, this.screenHeight);
        rootStyle.markTaffyStyleDirty();
        calculateLayout();

        var rootTaffyLocation = rootElement.getTaffyLayout().location();
        var elementBounds = rootElement.getRuntimeCache();
        if (width.isAuto()) {
            this.width = elementBounds.getWidth();
            this.leftPos = isRelative ? (this.screenWidth - this.width) / 2 : rootTaffyLocation.x;
        } else {
            this.leftPos = isRelative ? (this.screenWidth - this.width) / 2 : rootTaffyLocation.x;
        }
        if (height.isAuto()) {
            this.height = elementBounds.getHeight();
            this.topPos = isRelative ? (this.screenHeight - this.height) / 2 : rootTaffyLocation.y;
        } else {
            this.topPos = isRelative ? (this.screenHeight - this.height) / 2 : rootTaffyLocation.y;
        }

        this.leftPos = Math.round(this.leftPos);
        this.topPos = Math.round(this.topPos);
        this.ui.rootElement.clearLayoutCache();
    }


    void calculateLayout() {
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
    public void paintFrame() {
        long now = System.nanoTime();
        float deltaSeconds = (now - lastFrameNanos) / 1_000_000_000f;
        lastFrameNanos = now;

        styleEngine.calculateStyle(deltaSeconds);
        calculateLayout();

        paintContext.beginFrame(actualScreenWidth, actualScreenHeight);

        PoseStack pose = paintContext.getPoseStack();
        pose.pushPose();

        pose.scale(uiScale, uiScale, 1f);

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

    public UIElement getHoveredElement(float mouseX, float mouseY) {
        return elementHitTest(ui.rootElement, mouseX, mouseY);
    }

    private UIElement elementHitTest(UIElement element, float mouseX, float mouseY) {
        if (element.getStyle().taffyBridge.style.display == TaffyDisplay.NONE) return null;

        Matrix4f transform = element.getRuntimeCache().worldToLocal.get();
        var local = CgVertexTransformUtil.transformPosition(transform, mouseX, mouseY);
        float localX = local.x(), localY = local.y();
        boolean contentCanClipOut = element.getStyle().generalGroup.overflow().isClipped();
        if (!contentCanClipOut || element.isMouseOverContent(localX, localY)) {
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
