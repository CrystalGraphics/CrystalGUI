package com.crystalgui.ui;

import com.crystalgraphics.api.text.CgTextConstraints;
import com.crystalgui.core.data.CacheCell;
import com.crystalgui.core.data.IntCacheCell;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.style.ElementStyle;
import com.crystalgui.style.GeneralGroup;
import com.crystalgui.style.LayoutGroup;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.ui.event.DOMEvent;
import com.crystalgui.ui.event.FocusEvent;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import dev.vfyjxf.taffy.tree.Layout;
import dev.vfyjxf.taffy.tree.NodeId;
import dev.vfyjxf.taffy.tree.TaffyTree;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Consumer;

import static com.crystalgui.ui.UIWindow.EMPTY_LAYOUT;

/**
 * Base DOM node — every CgGui component extends this (a general-purpose, styleable, extensible container, conceptually
 * like an HTML {@code <div>}).
 */
@Accessors(chain = true)
public class UIElement {
    private static final Comparator<UIElement> Z_INDEX_DESCENDING = (a, b) -> Integer.compare(b.style.generalGroup.zIndex(), a.style.generalGroup.zIndex());

    // ── Core state ───────────────────────────────────────────────────────────

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
    @Getter
    private final Set<String> classes = new LinkedHashSet<>();

    @Getter
    private FocusPolicy focusPolicy = FocusPolicy.CLICK;

    @Getter @Setter
    private boolean hitTest = true;

    @Getter
    private boolean isEnabled = true;

    @Getter
    private boolean isPressed = false;

    @Getter
    private boolean isFocused = false;

    @Getter
    private boolean isHovered = false;

    // Runtime only data.
    @Getter
    private final RuntimeCache runtimeCache = new RuntimeCache();

    // ── Events ───────────────────────────────────────────────────────────────

    public final EventListenerGroup.Map events = new EventListenerGroup.Map(this);

    // Mouse
    public final EventListenerGroup<MouseEvent.Down> onMouseDown = events.getGroup(MouseEvent.Down.class);
    public final EventListenerGroup<MouseEvent.Up> onMouseUp = events.getGroup(MouseEvent.Up.class);
    public final EventListenerGroup<MouseEvent.Scroll> onMouseScroll = events.getGroup(MouseEvent.Scroll.class);
    public final EventListenerGroup<MouseEvent.Move> onMouseMove = events.getGroup(MouseEvent.Move.class);
    public final EventListenerGroup<MouseEvent.Enter> onMouseEnter = events.getGroup(MouseEvent.Enter.class);
    public final EventListenerGroup<MouseEvent.Leave> onMouseLeave = events.getGroup(MouseEvent.Leave.class);

    // Focus
    public final EventListenerGroup<FocusEvent.Focus> onFocus = events.getGroup(FocusEvent.Focus.class);
    public final EventListenerGroup<FocusEvent.Blur> onBlur = events.getGroup(FocusEvent.Blur.class);

    public UIElement() {
//        onFocus.attachDefaultListener(((thisElement, event) -> style.generalGroup.overlay(CgUiDrawable.EMPTY).color(0xFFFF8888)));
//        onBlur.attachDefaultListener(((thisElement, event) -> style.generalGroup.overlay(CgUiDrawable.EMPTY).color(0xFFFFFFFF)));
    }

    // ── Identity ─────────────────────────────────────────────────────────────

    public UIElement setId(String id) {
        String newId = id == null ? "" : id;
        if (this.id.equals(newId)) return this;
        this.id = newId;
        invalidateStyleMatch();
        return this;
    }

    public UIElement addClass(String cls) {
        if (classes.add(cls)) invalidateStyleMatch();
        return this;
    }

    public UIElement removeClass(String cls) {
        if (classes.remove(cls)) invalidateStyleMatch();
        return this;
    }

    public boolean hasClass(String cls) {
        return classes.contains(cls);
    }

    /**
     * Lowercase tag/type used by selector-engine type selectors (e.g. {@code button { ... }}).
     * Defaults to the simple class name; widget subclasses may override to a stable public name.
     */
    public String tagName() {
        return getClass().getSimpleName().toLowerCase(Locale.ROOT);
    }


    // ── State ────────────────────────────────────────────────────────────────
    public void setEnabled(boolean enabled) {
        if (this.isEnabled == enabled) return;
        this.isEnabled = enabled;
        onStyleChanged();
        invalidateStyleMatch();
    }

    public void setPressed(boolean pressed) {
        if (!this.isEnabled()) return;
        if (this.isPressed == pressed) return;
        this.isPressed = pressed;
        onStyleChanged();
        invalidateStyleMatch();
    }

    public void setFocused(boolean focused) {
        if (this.isFocused == focused) return;
        this.isFocused = focused;
        onStyleChanged();
        invalidateStyleMatch();
    }

    public void setHovered(boolean hovered) {
        if (this.isHovered == hovered) return;
        this.isHovered = hovered;
        onStyleChanged();
        invalidateStyleMatch();
    }

    /**
     * Reserved for checkboxes / on-off sliders
     * @return Is element checked.
     */
    public boolean isChecked() {
        return false;
    }

    /**
     * Reserved for text fields
     * @return If the element is blank
     */
    public boolean isBlank() {
        return false;
    }

    // ── Tree structure ───────────────────────────────────────────────────────

    public UIElement addChild(UIElement child) {
        return addChildAt(child, children.size());
    }

    public UIElement addChildAt(UIElement child, int index) {
        if (child == null) return this;
        if (child == this) throw new IllegalArgumentException("Cannot add self as a child");
        if (hasChild(child)) throw new IllegalArgumentException("Cannot add the same child twice");

        if (child.hasParent()) {
            assert child.getParent() != null;
            child.getParent().removeChild(child);
        }

        child.parent = this;
        children.add(index, child);
        child.setAttachedWindow(this.attachedWindow);
        this.runtimeCache.sortedChildren.invalidate();
        this.invalidateFocusableChain();
        child.onAdded();
        return this;
    }

    public UIElement addChildren(UIElement... elements) {
        for (UIElement e : elements) addChild(e);
        return this;
    }

    public boolean removeChild(UIElement child) {
        if (child == null) return false;
        if (!hasChild(child)) return false;

        children.remove(child);
        child.onRemoved();
        child.setAttachedWindow(null);
        child.parent = null;
        this.runtimeCache.sortedChildren.invalidate();
        this.invalidateFocusableChain();
        return true;
    }

    public void removeSelf() {
        if (parent != null) parent.removeChild(this);
    }

    public void clearAllChildren() {
        for (UIElement child : new ArrayList<>(children)) {
            removeChild(child);
        }
    }

    public final int getSiblingIndex() {
        if (parent == null) return -1;
        return parent.children.indexOf(this);
    }

    private void onAdded() {
        this.runtimeCache.depth.invalidate().get();
        children.forEach(UIElement::onAdded);
        events.emitToGroup(new DOMEvent.ElementAdded(this));
    }

    private void onRemoved() {
        this.runtimeCache.depth.invalidate();
        children.forEach(UIElement::onRemoved);
        events.emitToGroup(new DOMEvent.ElementAdded(this));
    }

    private boolean hasParent() {
        return this.parent != null;
    }

    private boolean hasChild(UIElement child) {
        return children.contains(child);
    }

    // ── Focus ────────────────────────────────────────────────────────────────

    public UIElement setFocusPolicy(FocusPolicy newPolicy) {
        if (newPolicy == null) return setFocusPolicy(FocusPolicy.NONE);
        if (this.focusPolicy.isFocusable() != newPolicy.isFocusable()) invalidateFocusableChain();
        this.focusPolicy = newPolicy;
        return this;
    }

    public boolean focusable() {
        return this.isEnabled() && this.getFocusPolicy() != FocusPolicy.NONE && this.style.taffyBridge.style.display != TaffyDisplay.NONE;
    }

    private void invalidateFocusableChain() {
        UIElement el = this;
        while (el != null) {
            el.getRuntimeCache().hasFocusableDescendant.invalidate();
            el = el.getParent();
        }
    }

    // ── Hit-testing ──────────────────────────────────────────────────────────

    boolean isMouseOverElement(float localMouseX, float localMouseY) {
        float rectX = runtimeCache.getX(), rectY = runtimeCache.getY();
        float rectWidth = runtimeCache.getWidth(), rectHeight = runtimeCache.getHeight();

        // Cheap AABB early-reject first — Taffy's Layout.size() is always the full outer
        // (content + padding + border) box regardless of box-sizing, so this rect already
        // matches the "outer" box CgUiRoundedRect renders/border-radius describes.
        if (!insideRectangle(localMouseX, localMouseY, rectX, rectY, rectWidth, rectHeight)) return false;

        float radius = style.getGeneralGroup().borderRadius();
        if (radius <= 0f) return true;

        // Only when actually rounded: same rounded-box SDF as gui_rounded_rect.shader's
        // sdf_rounded_box (crystalgraphics:shaders/lib/sdf.glsl), evaluated in plain Java —
        // rendering and hit-testing must never disagree about the element's shape.
        float halfW = rectWidth * 0.5f, halfH = rectHeight * 0.5f;
        float localX = localMouseX - (rectX + halfW);
        float localY = localMouseY - (rectY + halfH);
        return sdfRoundedBox(localX, localY, halfW, halfH, radius) <= 0f;
    }

    private static float sdfRoundedBox(float px, float py, float halfW, float halfH, float radius) {
        radius = Math.min(radius, Math.min(halfW, halfH));
        float qx = Math.abs(px) - halfW + radius;
        float qy = Math.abs(py) - halfH + radius;
        float outsideX = Math.max(qx, 0f);
        float outsideY = Math.max(qy, 0f);
        float outsideLen = (float) Math.sqrt(outsideX * outsideX + outsideY * outsideY);
        return outsideLen + Math.min(Math.max(qx, qy), 0f) - radius;
    }

    boolean isMouseOverContent(float localMouseX, float localMouseY) {
        var layout = getTaffyLayout();

        final float
                contentX = runtimeCache.getX() + layout.border().left + layout.padding().left,
                contentY = runtimeCache.getY() + layout.border().top + layout.padding().top,
                contentWidth = layout.contentBoxWidth(),
                contentHeight = layout.contentBoxHeight();

        return insideRectangle(localMouseX, localMouseY, contentX, contentY, contentWidth, contentHeight);
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

    public void initScreen(int screenWidth, int screenHeight) {
        runtimeCache.resetCache();
        children.forEach(el -> el.initScreen(screenWidth, screenHeight));
    }

    public void clearLayoutCache() {
        // No early-return here: UIWindow.calculateLayout() invalidates nodesWithNewLayout in
        // arbitrary HashSet order, so a node can already be NaN-marked (from an earlier, unrelated
        // call this same pass) without its children ever having been walked — an early-return
        // "already dirty, skip" guard here would leave those children's cached positions stale for
        // this frame's hit-testing. Redundant re-invalidation within one frame is cheap; a stale
        // cached position feeding into hit-testing is not.
        runtimeCache.resetPoseCache();
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

    protected Layout getTaffyLayout() {
        if (getTaffyTree() == null)
            return EMPTY_LAYOUT;
        return getTaffyTree().getLayout(this.taffyNodeId);
    }

    protected final float getLayoutY() {
        return (parent == null ? (attachedWindow == null ? 0 : attachedWindow.getTopPos()) : getTaffyLayout().location().y);
    }

    protected final float getLayoutX() {
        return (parent == null ? (attachedWindow == null ? 0 : attachedWindow.getLeftPos() ): getTaffyLayout().location().x);
    }

    // ── Style ────────────────────────────────────────────────────────────────

    public UIElement style(Consumer<ElementStyle> configurator) {
        configurator.accept(this.getStyle());
        return this;
    }

    public UIElement generalStyle(Consumer<GeneralGroup> configurator) {
        configurator.accept(this.getStyle().getGeneralGroup());
        return this;
    }

    /**
     * Demotes everything set so far via {@code .layout()}/{@code .generalStyle()} (INLINE-origin by
     * default) down to {@link com.crystalgui.style.StyleOrigin#DEFAULT}. Meant to be called once, at
     * the end of a widget's own construction chain, so its baseline styling can be freely overridden
     * by a stylesheet or by the widget's actual user: {@code new UiButton().layout(l ->
     * l.width(80)).moveInlineAsDefault()}.
     */
    public UIElement moveInlineAsDefault() {
        style.moveInlineAsDefault();
        return this;
    }

    public void onStyleChanged() {
        // no-op.
    }

    /**
     * Marks this element as needing its stylesheet selectors re-matched (id/class/pseudo-class
     * state changed). Deliberately separate from {@link #onStyleChanged()} — that hook fires on
     * every candidate-value push (including the ones a re-match itself produces), so folding this
     * into it would re-trigger selector matching on every single style write.
     */
    protected void invalidateStyleMatch() {
        if (attachedWindow != null) {
            attachedWindow.getStyleEngine().markDirty(this);
        }
    }

    // ── Paint ────────────────────────────────────────────────────────────────

    /**
     * Paints this element's background, then recurses into children (z-index-sorted,
     * DOM order as tiebreak), then paints this element's overlay. Fully synchronous —
     * every call in this chain issues real GPU draw calls immediately; nothing here
     * defers or accumulates work for later replay.
     */
    public final void drawSubtree(CgUiPaintContext ctx) {
        if (style.taffyBridge.style.display == TaffyDisplay.NONE)
            return;
        if (runtimeCache.localToWorld.isDirty()) {
            this.runtimeCache.localToWorld.set(ctx.getPoseStack().last().pose());
            this.runtimeCache.worldToLocal.invalidate();
        }

        paintSelf(ctx);

        if (!children.isEmpty()) {
            boolean scissored = style.getGeneralGroup().overflow().isScissor();
            if (scissored) {
                var layout = getTaffyLayout();
                int contentX = Math.round(runtimeCache.getX() + layout.border().left + layout.padding().left);
                int contentY = Math.round(runtimeCache.getY() + layout.border().top + layout.padding().top);
                int contentWidth = Math.round(layout.contentBoxWidth());
                int contentHeight = Math.round(layout.contentBoxHeight());
                ctx.pushScissor(contentX, contentY, contentWidth, contentHeight);
            }

            for (UIElement child : getChildren()) {
                child.drawSubtree(ctx);
            }

            if (scissored) ctx.popScissor();
        }
        paintOverlay(ctx);
    }

    /** Override for custom drawing beyond the generic box model (e.g. text glyphs, item icons). Called before children paint. */
    protected void paintSelf(CgUiPaintContext ctx) {
        GeneralGroup styleGen = style.getGeneralGroup();
        final float x = runtimeCache.getX(), y = runtimeCache.getY(), width = runtimeCache.getWidth(), height = runtimeCache.getHeight();

        // `color` is text-only (inheritable, meant for glyph tint) — it must NOT tint the background
        // drawable. background-color instead acts as the ambient tint the background drawable is
        // painted with (every CgUiDrawable already multiplies ctx.getColor() into its own output),
        // so it visibly recolors whatever background is set — a plain color, a sprite/9-slice's own
        // shading, an SDF rounded rect's fill+border — rather than being silently invisible behind
        // an opaque drawable the way a literal underlay-fill layer would be. When there is no real
        // background drawable set at all, tinting has nothing to multiply against (EMPTY is fully
        // transparent), so background-color instead paints as a flat fill directly.
        CgUiDrawable background = styleGen.background();
        int backgroundColor = styleGen.backgroundColor();

        if (background == CgUiDrawable.EMPTY) {
            ctx.setColor(0xFFFFFFFF);
            // background-color now defaults to white (a no-op tint) — so whether to paint a flat
            // fill here can't be decided from the resolved value anymore (it's white either way when
            // unset). Check for an explicit candidate instead.
            if (style.containsCandidate(StylePropertyRegistry.BACKGROUND_COLOR, slot -> true)) {
                ctx.fillRect(x, y, width, height, backgroundColor);
            }
        } else {
            ctx.setColor(backgroundColor);
            background.draw(ctx, x, y, width, height);
        }
//        if (this.parent == null) {
//            ctx.text().draw()
//                    .text("Chuj ci w dupasddd asdasdasdasdasd asd asd sdaddddddddddddddddasd addddddddddddddddddddddddddddddsd as eFfffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff ffffff")
//                    .color(0xFFFFFFFF)
//                    .at(runtimeCache.getX(), runtimeCache.getY())
//                    .constraints(new CgTextConstraints(runtimeCache.getWidth(), runtimeCache.getHeight()))
//                    .font(ctx.getFont()).submit();
//        }
    }

    /** Override for custom drawing that must appear above children. Called after children paint. */
    protected void paintOverlay(CgUiPaintContext ctx) {
        final float x = runtimeCache.getX(), y = runtimeCache.getY(), width = runtimeCache.getWidth(), height = runtimeCache.getHeight();
        // Reset ambient tint — a descendant's own paintSelf/paintOverlay may have left it non-white.
        ctx.setColor(0xFFFFFFFF);

        style.getGeneralGroup().overlay().draw(ctx, x, y, width, height);
    }

    // ── Window attachment / Taffy tree ──────────────────────────────────────

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

    // ── Runtime cache ────────────────────────────────────────────────────────

    public class RuntimeCache {
        public final CacheCell<Boolean> hasFocusableDescendant = new CacheCell<Boolean>().setCalculator(ignored -> {
            UIElement element = UIElement.this;
            if (element.focusable()) return true;
            for (UIElement child : element.getChildren()) {
                if (child.getRuntimeCache().hasFocusableDescendant.get()) return true;
            }
            return false;
        });

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

        public final CacheCell<Matrix4f> localToWorld = new CacheCell<>(new Matrix4f()).setCalculator(old -> {
            var element = UIElement.this;
            var parent = element.getParent();
            if (parent == null) {
                if (element.attachedWindow == null) return old.identity();
                return old.identity();
            }
            old.set(parent.getRuntimeCache().localToWorld.get());
            // TODO: Style transforms
            return old;
        });

        public final CacheCell<Matrix4f> worldToLocal = new CacheCell<>(new Matrix4f()).setCalculator(old -> localToWorld.get().invert(old));

        public final IntCacheCell depth = new IntCacheCell().setCalculator((old) -> {
            UIElement parent = UIElement.this.parent;
            if (parent == null) return 1;
            return parent.getRuntimeCache().getDepth() + 1;
        });

        private float x, y;

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
            if (Float.isNaN(x)) {
                UIElement element = UIElement.this;
                x = element.getLayoutX() + (element.getParent() == null ? 0 : element.getParent().getRuntimeCache().getX());
            }
            return x;
        }

        public float getY() {
            if (Float.isNaN(y)) {
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

        public int getDepth() {
            return depth.get();
        }
    }
}