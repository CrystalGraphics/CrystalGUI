package com.crystalgui;

import com.crystalgraphics.api.vertex.CgVertexTransformUtil;
import com.crystalgui.core.CacheCell;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.ElementStyle;
import com.crystalgui.style.GeneralGroup;
import com.crystalgui.style.LayoutGroup;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import dev.vfyjxf.taffy.tree.Layout;
import dev.vfyjxf.taffy.tree.NodeId;
import dev.vfyjxf.taffy.tree.TaffyTree;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Consumer;

import static com.crystalgui.UIWindow.EMPTY_LAYOUT;

/**
 * Base DOM node — every CgGui component extends this (a general-purpose, styleable, extensible container, conceptually
 * like an HTML {@code <div>}).
 */
@Accessors(chain = true)
public class UIElement {
    private static final Comparator<UIElement> Z_INDEX_DESCENDING = (a, b) -> Integer.compare(b.style.generalGroup.zIndex(), a.style.generalGroup.zIndex());

    @Getter
    private final ElementStyle style = new ElementStyle(this);
    protected NodeId taffyNodeId;
    /** Set by UiRuntime once this element is attached; used so layout(...) can mark the node dirty. */
    @Getter
    @Nullable
    UIWindow attachedWindow;

    @Nullable @Getter @Setter
    private UIElement parent;
    @Getter
    private final List<UIElement> children = new ArrayList<>();

    @Getter
    private String id = "";

    // Runtime only data.
    @Getter
    private final RuntimeCache runtimeCache = new RuntimeCache();

    @Getter @Setter
    private boolean hitTest = true;

    public UIElement setId(String id) {
        this.id = id == null ? "" : id;
        return this;
    }

    @Getter
    private final Set<String> classes = new LinkedHashSet<>();

    public UIElement addClass(String cls) {
        classes.add(cls);
        return this;
    }

    public UIElement removeClass(String cls) {
        classes.remove(cls);
        return this;
    }

    public boolean hasClass(String cls) {
        return classes.contains(cls);
    }

    public UIElement addChild(UIElement child) {
        if (child == null) return this;
        if (child == this) throw new IllegalArgumentException("Cannot add self as a child");
        if (hasChild(child)) throw new IllegalArgumentException("Cannot add the same child twice");

        if (child.hasParent()) {
            assert child.getParent() != null;
            child.getParent().removeChild(child);
        }

        child.parent = this;
        children.add(child);
        this.runtimeCache.sortedChildren.invalidate();
        return this;
    }

    private boolean hasParent() {
        return this.parent != null;
    }

    public UIElement addChildren(UIElement... elements) {
        for (UIElement e : elements) addChild(e);
        return this;
    }

    public boolean removeChild(UIElement child) {
        if (child == null) return false;
        if (!hasChild(child)) return false;

        children.remove(child);
        //child.onRemoved();
        child.setAttachedWindow(null);
        child.parent = null;
        this.runtimeCache.sortedChildren.invalidate();
        return true;
    }

    private boolean hasChild(UIElement child) {
        return children.contains(child);
    }

    public void removeSelf() {
        if (parent != null) parent.removeChild(this);
    }

    public void clearAllChildren() {
        for (UIElement child : new ArrayList<>(children)) {
            removeChild(child);
        }
    }

    public final UIElement getHoveredElement(float mouseX, float mouseY) {
        if (style.taffyBridge.style.display == TaffyDisplay.NONE) return null;

        Matrix4f transform = runtimeCache.worldToLocal.get();
        var local = CgVertexTransformUtil.transformPosition(transform, mouseX, mouseY);
        float localX = local.x(), localY = local.y();
        boolean contentCanClipOut = true;
        if (isMouseOverContent(localX, localY) || contentCanClipOut) {
            for (var child : runtimeCache.sortedChildren.get()) {
                var result = child.getHoveredElement(mouseX, mouseY);
                if (result != null) {
                    return result;
                }
            }
        }
        if (isHitTest() && isMouseOverElement(localX, localY)) {
            return this;
        }
        return null;
    }

    private boolean isMouseOverElement(float mouseX, float mouseY) {
        return insideRectangle(mouseX, mouseY, runtimeCache.getX(), runtimeCache.getY(), runtimeCache.getWidth(), runtimeCache.getHeight());
    }

    private boolean isMouseOverContent(float mouseX, float mouseY) {
        var layout = getTaffyLayout();

        final float
                contentX = runtimeCache.getX() + layout.border().left + layout.padding().left,
                contentY = runtimeCache.getY() + layout.border().top + layout.padding().top,
                contentWidth = layout.contentBoxWidth(),
                contentHeight = layout.contentBoxHeight();

        return insideRectangle(mouseX, mouseY, contentX, contentY, contentWidth, contentHeight);

    }

    private boolean insideRectangle(float mouseX, float mouseY, float rectX, float rectY, float rectWidth, float rectHeight) {
        return mouseX >= rectX
                && mouseY >= rectY
                && rectX + rectWidth >= mouseX
                && rectY + rectHeight >= mouseY;
    }

    // ── Layout ───────────────────────────────────────────────────────────────

    public UIElement layout(Consumer<LayoutGroup> configurator) {
        configurator.accept(this.getStyle().getLayoutGroup());
        return this;
    }

    private void markLayoutDirty() {
        if (attachedWindow != null && taffyNodeId != null) {
            attachedWindow.markDirty(taffyNodeId);
        }
    }

    // ── Paint ────────────────────────────────────────────────────────────────

    public UIElement style(Consumer<ElementStyle> configurator) {
        configurator.accept(this.getStyle());
        return this;
    }

    public UIElement generalStyle(Consumer<GeneralGroup> configurator) {
        configurator.accept(this.getStyle().getGeneralGroup());
        return this;
    }
    /**
     * Paints this element's background, then recurses into children (z-index-sorted,
     * DOM order as tiebreak), then paints this element's overlay. Fully synchronous —
     * every call in this chain issues real GPU draw calls immediately; nothing here
     * defers or accumulates work for later replay.
     */
    public final void drawSubtree(CgUiPaintContext ctx) {
//        if ( == TaffyDisplay.NONE || !isVisible() || opacity == 0) {
//            return;
//        }
//
        if (runtimeCache.localToWorld.isDirty()) {
            this.runtimeCache.localToWorld.set(ctx.getPoseStack().last().pose());
            this.runtimeCache.worldToLocal.invalidate();
        }

        paintSelf(ctx);

        if (!children.isEmpty()) {
            for (UIElement child : getChildren()) {
                child.drawSubtree(ctx);
            }
        }
        paintOverlay(ctx);


    }

    /** Override for custom drawing beyond the generic box model (e.g. text glyphs, item icons). Called before children paint. */
    protected void paintSelf(CgUiPaintContext ctx) {
        GeneralGroup styleGen = style.getGeneralGroup();
        ctx.setColor(styleGen.color());
        styleGen.background().draw(ctx, runtimeCache.getX(), runtimeCache.getY(), runtimeCache.getWidth(), runtimeCache.getHeight());
        ctx.setColor(0xFFFFFFFF);
    }

    /** Override for custom drawing that must appear above children. Called after children paint. */
    protected void paintOverlay(CgUiPaintContext ctx) {
        Matrix4f worldToLocal = runtimeCache.worldToLocal.get();
        Vector4f v = new Vector4f();
        v.set(ctx.mouseX, ctx.mouseY, 0, 1.0f);
        worldToLocal.transform(v);
        final float mouseX = v.x(), mouseY = v.y();
        final float x = runtimeCache.getX(), y = runtimeCache.getY(), width = runtimeCache.getWidth(), height = runtimeCache.getHeight();

        if (mouseX >= x && mouseX <= x+width && mouseY >= y && mouseY <= y+height)
            style.getGeneralGroup().overlay().draw(ctx, x, y, width, height);
    }


    public void onStyleChanged() {
        // no-op.
    }

    @Nullable
    public TaffyTree getTaffyTree() {
        return attachedWindow == null ? null : attachedWindow.getTaffyTree();
    }
    public void markTreeDirty() {
        var taffyTree = getTaffyTree();
        if (taffyTree != null) {
            taffyTree.markDirty(taffyNodeId);
        }
    }

    protected final void setAttachedWindow(UIWindow uiWindow) {
        if (this.attachedWindow == uiWindow) return;

        var previousWindow = this.attachedWindow;

        if (this.attachedWindow != null) {
            this.attachedWindow.unregisterElement(this);
        }

        this.attachedWindow = uiWindow;

        if (uiWindow != null) {
            uiWindow.registerElement(this);
        }

        // TODO: Fire event for Window change

        children.forEach(child -> child.setAttachedWindow(uiWindow));

    }

    public void initScreen(int screenWidth, int screenHeight) {
        runtimeCache.resetCache();
        children.forEach(el -> el.initScreen(screenWidth, screenHeight));
    }

    public void clearLayoutCache() {
        runtimeCache.resetPoseCache();
        if (runtimeCache.isPositionDirty()) return;
        runtimeCache.resetLayoutCache();
        children.forEach(UIElement::clearLayoutCache);
    }

    public void onLayoutChanged(boolean hasGeometryChanged) {
        if (hasGeometryChanged) {
            onLayoutChanged();
        }
    }

    protected void onLayoutChanged() {
        clearLayoutCache();
        // TODO: Fire DOM Events
    }

    public final int getSiblingIndex() {
        if (parent == null) return -1;
        return parent.children.indexOf(this);
    }

    protected Layout getTaffyLayout() {
        if (getTaffyTree() == null)
            return EMPTY_LAYOUT;
        return getTaffyTree().getLayout(this.taffyNodeId);
    }

    protected final float getLayoutY() {
        return (parent == null ? attachedWindow == null ? 0 : attachedWindow.getTopPos() : getTaffyLayout().location().y);
    }

    protected final float getLayoutX() {
        return (parent == null ? attachedWindow == null ? 0 : attachedWindow.getLeftPos() : getTaffyLayout().location().x);
    }

    public class RuntimeCache {
        private float x, y;

        public final CacheCell<UIElement[]> sortedChildren = new CacheCell<UIElement[]>().setCalculator(ignored -> {
            int n = children.size();
            UIElement[] sorted = new UIElement[n];

            // Fill in reverse insertion order — stable sort then preserves
            // "later-inserted first" for any equal-zIndex ties, with no index tracking needed.
            for (int i = 0; i < n; i++) {
                sorted[i] = children.get(n - 1 - i);
            }

            Arrays.sort(sorted, Z_INDEX_DESCENDING);
            return sorted;
        });

        public final CacheCell<Matrix4f> localToWorld = new CacheCell<>(new Matrix4f()).setCalculator( old -> {
            var element = UIElement.this;
            var parent = element.getParent();
            if (parent == null) {
                if (element.attachedWindow == null) return old.identity();
//                return old.set()
                return old.identity();
            }
            old.set(parent.getRuntimeCache().localToWorld.get());
            // TODO: Style transforms
            return old;
        });
        public final CacheCell<Matrix4f> worldToLocal = new CacheCell<>(new Matrix4f()).setCalculator(old -> localToWorld.get().invert(old));

        private RuntimeCache() {
            resetCache();
        }

        public void resetCache() {
            resetLayoutCache();
            resetPoseCache();
        }

        public void resetLayoutCache() {
            x = Float.NaN;
            y = Float.NaN;
        }

        public void resetPoseCache() {
            localToWorld.invalidate();
            worldToLocal.invalidate();
        }

        public float getX() {
            if (Float.isNaN(x)){
                UIElement element = UIElement.this;
                x = element.getLayoutX() + (element.getParent() == null ? 0 : element.getParent().getRuntimeCache().getX());
            }
            return x;
        }

        public float getY() {
            if (Float.isNaN(y)){
                UIElement element = UIElement.this;
                y = element.getLayoutY() + (element.getParent() == null ? 0 : element.getParent().getRuntimeCache().getY());
            }
            return y;
        }

        public float getWidth() {
            return UIElement.this.getTaffyLayout().size().width;
        }
        public float getHeight() {
            return UIElement.this.getTaffyLayout().size().height;
        }


        public boolean isPositionDirty() {
            return Float.isNaN(x) && Float.isNaN(y);
        }
    }

}
