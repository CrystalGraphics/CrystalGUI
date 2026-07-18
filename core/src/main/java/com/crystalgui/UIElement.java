package com.crystalgui;

import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.ElementStyle;
import com.crystalgui.style.GeneralGroup;
import com.crystalgui.style.LayoutGroup;
import dev.vfyjxf.taffy.tree.Layout;
import dev.vfyjxf.taffy.tree.NodeId;
import dev.vfyjxf.taffy.tree.TaffyTree;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static com.crystalgui.UIWindow.EMPTY_LAYOUT;

/**
 * Base DOM node — every CgGui component extends this (a general-purpose, styleable, extensible container, conceptually
 * like an HTML {@code <div>}).
 */
@Accessors(chain = true)
public class UIElement {


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
    private final BoundsCache runtimeBounds = new BoundsCache(this);

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
        if (child.parent != null) {
            child.parent.removeChild(child);
        }
        child.parent = this;
        children.add(child);
        return this;
    }

    public UIElement addChildren(UIElement... elements) {
        for (UIElement e : elements) addChild(e);
        return this;
    }

    public boolean removeChild(UIElement child) {
        if (children.remove(child)) {
            child.parent = null;
            return true;
        }
        return false;
    }

    public void removeSelf() {
        if (parent != null) parent.removeChild(this);
    }

    public void clearAllChildren() {
        for (UIElement child : new ArrayList<>(children)) {
            removeChild(child);
        }
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
        styleGen.background().draw(ctx, runtimeBounds.getX(), runtimeBounds.getY(), runtimeBounds.getWidth(), runtimeBounds.getHeight());
        ctx.setColor(0xFFFFFFFF);
    }

    /** Override for custom drawing that must appear above children. Called after children paint. */
    protected void paintOverlay(CgUiPaintContext ctx) {
        Matrix4f localToWorld = ctx.getPoseStack().last().pose();
        Matrix4f worldToLocal = localToWorld.invert(new Matrix4f());
        Vector4f v = new Vector4f();
        v.set(ctx.mouseX, ctx.mouseY, 0, 1.0f);
        worldToLocal.transform(v);
        final float mouseX = v.x(), mouseY = v.y();
        final float x = runtimeBounds.getX(), y = runtimeBounds.getY(), width = runtimeBounds.getWidth(), height = runtimeBounds.getHeight();

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
        runtimeBounds.resetCache();
        children.forEach(el -> el.initScreen(screenWidth, screenHeight));
    }

    public void clearLayoutCache() {
        runtimeBounds.resetCache();
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

    public static class BoundsCache {
        private final UIElement element;
        private float x, y;

        private BoundsCache(UIElement element) {
            this.element = element;
            resetCache();
        }

        private void resetCache() {
            x = Float.NaN;
            y = Float.NaN;
        }

        public float getX() {
            if (Float.isNaN(x)){
                x = element.getLayoutX() + (element.getParent() == null ? 0 : element.getParent().getRuntimeBounds().getX());
            }
            return x;
        }

        public float getY() {
            if (Float.isNaN(y)){
                y = element.getLayoutY() + (element.getParent() == null ? 0 : element.getParent().getRuntimeBounds().getY());
            }
            return y;
        }

        public float getWidth() {
            return element.getTaffyLayout().size().width;
        }
        public float getHeight() {
            return element.getTaffyLayout().size().height;
        }


    }

    public final float getLayoutY() {
        return (parent == null ? attachedWindow == null ? 0 : attachedWindow.getTopPos() : getTaffyLayout().location().y);
    }

    public final float getLayoutX() {
        return (parent == null ? attachedWindow == null ? 0 : attachedWindow.getLeftPos() : getTaffyLayout().location().x);
    }

}
