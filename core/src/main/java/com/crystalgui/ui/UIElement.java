package com.crystalgui.ui;

import com.crystalgraphics.api.text.CgTextConstraints;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgui.core.data.CacheCell;
import com.crystalgui.core.data.IntCacheCell;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.render.texture.CgUiCrossFade;
import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.render.texture.CgUiQuad;
import com.crystalgui.render.texture.CgUiRoundedRect;
import com.crystalgui.render.texture.CgUiSprite;
import com.crystalgui.style.ElementStyle;
import com.crystalgui.style.GeneralGroup;
import com.crystalgui.style.LayoutGroup;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.OverflowClip;
import com.crystalgui.style.property.visual.border.BorderRadiusProperties;
import com.crystalgui.style.property.visual.border.LengthPercent;
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

        CornerRadii radii = resolveCornerRadii(rectWidth, rectHeight);
        if (radii.isZero()) return true;

        // Only when actually rounded: same elliptical rounded-box SDF as gui_rounded_rect.shader's
        // sdf_rounded_box (crystalgraphics:shaders/lib/sdf.glsl), evaluated in plain Java —
        // rendering and hit-testing must never disagree about the element's shape.
        float halfW = rectWidth * 0.5f, halfH = rectHeight * 0.5f;
        float localX = localMouseX - (rectX + halfW);
        float localY = localMouseY - (rectY + halfH);

        // Y-down local space (matches gui_rounded_rect.shader's UV convention): localY < 0 is "top".
        float rx, ry;
        if (localY < 0f) {
            if (localX < 0f) { rx = radii.rxTL(); ry = radii.ryTL(); }
            else { rx = radii.rxTR(); ry = radii.ryTR(); }
        } else {
            if (localX < 0f) { rx = radii.rxBL(); ry = radii.ryBL(); }
            else { rx = radii.rxBR(); ry = radii.ryBR(); }
        }

        return sdfRoundedBoxElliptical(localX, localY, halfW, halfH, rx, ry) <= 0f;
    }

    /** Independent per-axis (rx,ry) rounded-box SDF — same approximate technique as
     * {@code sdf_rounded_box}'s elliptical overload in {@code sdf.glsl}: normalize the corner-region
     * offset by (rx,ry) before the circular distance evaluation, scale back by {@code min(rx,ry)}.
     * Exact for rx==ry, visually correct otherwise. */
    private static float sdfRoundedBoxElliptical(float px, float py, float halfW, float halfH, float rx, float ry) {
        rx = Math.min(rx, halfW);
        ry = Math.min(ry, halfH);
        float qx = Math.abs(px) - halfW + rx;
        float qy = Math.abs(py) - halfH + ry;
        if (rx <= 0f || ry <= 0f) {
            float outsideX = Math.max(qx, 0f), outsideY = Math.max(qy, 0f);
            return (float) Math.sqrt(outsideX * outsideX + outsideY * outsideY) + Math.min(Math.max(qx, qy), 0f);
        }
        float nx = Math.max(qx, 0f) / rx;
        float ny = Math.max(qy, 0f) / ry;
        float outsideLen = (float) Math.sqrt(nx * nx + ny * ny);
        return (outsideLen - 1f) * Math.min(rx, ry) + Math.min(Math.max(qx, qy), 0f);
    }

    private record CornerRadii(float rxTL, float ryTL, float rxTR, float ryTR,
                                float rxBR, float ryBR, float rxBL, float ryBL) {
        boolean isZero() {
            return rxTL == 0f && ryTL == 0f && rxTR == 0f && ryTR == 0f
                    && rxBR == 0f && ryBR == 0f && rxBL == 0f && ryBL == 0f;
        }
    }

    private CornerRadii resolveCornerRadii(float width, float height) {
        GeneralGroup g = style.getGeneralGroup();
        return new CornerRadii(
                g.getValueSave(BorderRadiusProperties.TOP_LEFT_X).resolve(width),
                g.getValueSave(BorderRadiusProperties.TOP_LEFT_Y).resolve(height),
                g.getValueSave(BorderRadiusProperties.TOP_RIGHT_X).resolve(width),
                g.getValueSave(BorderRadiusProperties.TOP_RIGHT_Y).resolve(height),
                g.getValueSave(BorderRadiusProperties.BOTTOM_RIGHT_X).resolve(width),
                g.getValueSave(BorderRadiusProperties.BOTTOM_RIGHT_Y).resolve(height),
                g.getValueSave(BorderRadiusProperties.BOTTOM_LEFT_X).resolve(width),
                g.getValueSave(BorderRadiusProperties.BOTTOM_LEFT_Y).resolve(height)
        );
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
     *
     * <p>When {@code opacity} is fractional or {@code clip: mask} is set, background/children/
     * overlay instead paint into an offscreen "visual layer" (a screen-sized FBO from
     * {@link CgUiPaintContext}'s pool) so overlapping translucent children blend as one unit before
     * opacity applies, and/or so a mask can be composited over just this subtree's own output — see
     * {@link CgUiPaintContext#beginLayerFbo()}/{@code compositeMask}/{@code blitLayer}. Ordinary
     * elements (opacity 1, no mask) skip all of this — same direct-draw path as before.</p>
     */
    public final void drawSubtree(CgUiPaintContext ctx) {
        if (style.taffyBridge.style.display == TaffyDisplay.NONE || style.generalGroup.opacity() == 0)
            return;
        if (runtimeCache.localToWorld.isDirty()) {
            this.runtimeCache.localToWorld.set(ctx.getPoseStack().last().pose());
            this.runtimeCache.worldToLocal.invalidate();
        }

        GeneralGroup styleGen = style.getGeneralGroup();
        float opacity = styleGen.opacity();
        OverflowClip overflow = styleGen.overflow();
        boolean needsLayer = opacity < 1f || overflow.isMask();

        if (!needsLayer) {
            paintSelf(ctx);
            paintChildren(ctx, overflow);
            paintOverlay(ctx);
            return;
        }

//        System.out.println("DEBUGLAYER begin needsLayer=" + needsLayer + " opacity=" + opacity
//                + " mask=" + overflow.isMask() + " x=" + runtimeCache.getX() + " y=" + runtimeCache.getY()
//                + " w=" + runtimeCache.getWidth() + " h=" + runtimeCache.getHeight());
//        System.out.println("DEBUGLAYER subtreeFbo=" + subtreeFbo.getId() + " " + subtreeFbo.getWidth() + "x" + subtreeFbo.getHeight());
        CgFrameBuffer subtreeFbo = ctx.beginLayerFbo();
        paintSelf(ctx);
        paintChildren(ctx, overflow);
        if (overflow.isMask()) {
            CgUiRoundedRect mask = buildDefaultMask();
            CgFrameBuffer maskFbo = ctx.beginLayerFbo();
//            System.out.println("DEBUGLAYER maskFbo=" + maskFbo.getId());
            ctx.setColor(0xFFFFFFFF);
            mask.draw(ctx, runtimeCache.getX(), runtimeCache.getY(), runtimeCache.getWidth(), runtimeCache.getHeight());
            ctx.endLayerFbo();
            ctx.compositeMask(subtreeFbo, maskFbo);
//            System.out.println("DEBUGLAYER compositeMask done");
        }
        paintOverlay(ctx);
        ctx.endLayerFbo();
//        System.out.println("DEBUGLAYER endLayerFbo done, blitting with opacity=" + opacity);
        ctx.blitLayer(subtreeFbo, opacity);
//        System.out.println("DEBUGLAYER blitLayer done");
    }

    private void paintChildren(CgUiPaintContext ctx, OverflowClip overflow) {
        if (children.isEmpty()) return;
        boolean scissored = overflow.isScissor();
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

    /** Default {@code clip: mask} shape — this element's own resolved rounded-rect shape, with
     * the border band's alpha forced to 0 so only the inner (content) region masks anything in —
     * matches the same "border color to #00000000" idea directly, no new shader/coverage variant
     * needed: {@code CgUiRoundedRect} already blends {@code mix(borderColor, fillColor, innerCoverage)}
     * then multiplies the whole shape by {@code coverage}, so a transparent border color alone
     * already zeroes alpha across the border band while staying opaque across the inner region. */
    private CgUiRoundedRect buildDefaultMask() {
        float width = runtimeCache.getWidth(), height = runtimeCache.getHeight();
        CornerRadii radii = resolveCornerRadii(width, height);
        float borderWidthPx = getTaffyLayout().border().left;

        CgUiRoundedRect mask = new CgUiRoundedRect();
        mask.setCornerRadius(radii.rxTL(), radii.ryTL(), radii.rxTR(), radii.ryTR(),
                radii.rxBR(), radii.ryBR(), radii.rxBL(), radii.ryBL());
        if (borderWidthPx > 0f) {
            mask.setBorder(borderWidthPx, 0x00000000);
        }
        mask.setFillColor(0xFFFFFFFF);
        return mask;
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

        // Universal border-radius/border-width/border-color wrapping layer — applies on top of
        // whatever `background` resolves to, matching real CSS (rounding/border is orthogonal to
        // what the background *is*, not a special background value type). Border-width is sourced
        // straight from Taffy's already-resolved layout (same pipeline width/height come from),
        // not reparsed independently. Only a flat color or a non-9-slice sprite can actually be
        // clipped/stroked this way today — a 9-slice background falls through to the plain path
        // below (border-radius/border-width still grow the layout box and resolve for hit-testing,
        // just without visually clipping the sprite — documented gap).
        CornerRadii radii = resolveCornerRadii(width, height);
        float borderWidthPx = getTaffyLayout().border().left;
        boolean needsRoundedWrap = !radii.isZero() || borderWidthPx > 0f;
        if (needsRoundedWrap && paintRoundedBackground(ctx, x, y, width, height, radii, borderWidthPx, background, backgroundColor)) {
            return;
        }

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
        if (this.hasClass("mask-child")) {
            ctx.text().draw()
                    .text("Testing testing")
                    .color(-1)
                    .at(runtimeCache.getX(), runtimeCache.getY())
                    .font(ctx.getFont()).submit();
        }
    }

    /** Builds and draws a {@link CgUiRoundedRect} wrapping the resolved background, when possible.
     * @return {@code true} if it painted (caller must not also run the plain background path);
     *         {@code false} if {@code background} isn't a type this layer can clip/stroke (e.g. a
     *         9-slice sprite, or a {@link CgUiCrossFade} tree with an unresolvable leaf) —
     *         border-radius/border-width still resolve for hit-testing/layout growth in that case,
     *         just without visual clipping (documented gap). */
    private boolean paintRoundedBackground(CgUiPaintContext ctx, float x, float y, float width, float height,
                                            CornerRadii radii, float borderWidthPx,
                                            CgUiDrawable background, int backgroundColor) {
        // Immediate-mode drawing can't be undone once issued, so resolvability must be checked in a
        // side-effect-free pass BEFORE any drawing starts — an interrupted-and-retargeted background
        // transition nests CgUiCrossFade arbitrarily deep (TextureProperty.interpolate always
        // returns one, so retargeting a transition already in flight feeds a live CgUiCrossFade back
        // in as the new fromValue), and only fully resolving that whole tree first lets this mirror
        // CgUiCrossFade.draw()'s own unlimited recursion instead of bailing after one level.
        if (!canPaintRounded(background)) return false;

        ctx.setColor(backgroundColor);
        int borderColor = style.getGeneralGroup().borderColor();
        paintRoundedLayer(ctx, background, x, y, width, height, radii, borderWidthPx, borderColor);
        return true;
    }

    /** Pure, side-effect-free: true iff every leaf in this (possibly {@link CgUiCrossFade}-nested) drawable resolves to a fill. */
    private static boolean canPaintRounded(CgUiDrawable d) {
        if (d instanceof CgUiCrossFade cf) return canPaintRounded(cf.getFrom()) && canPaintRounded(cf.getTo());
        return resolveRoundedFill(d) != null;
    }

    /** Only called after {@link #canPaintRounded} confirmed every leaf resolves. */
    private static void paintRoundedLayer(CgUiPaintContext ctx, CgUiDrawable d, float x, float y, float width, float height,
                                           CornerRadii radii, float borderWidthPx, int borderColor) {
        if (d instanceof CgUiCrossFade cf) {
            ctx.withLayerOpacity(1f - cf.getT(), () ->
                    paintRoundedLayer(ctx, cf.getFrom(), x, y, width, height, radii, borderWidthPx, borderColor));
            ctx.withLayerOpacity(cf.getT(), () ->
                    paintRoundedLayer(ctx, cf.getTo(), x, y, width, height, radii, borderWidthPx, borderColor));
            return;
        }
        buildRoundedRect(radii, borderWidthPx, borderColor, resolveRoundedFill(d)).draw(ctx, x, y, width, height);
    }

    /** A resolved fill for the rounded-wrap layer — either a flat color or a texture, never both. */
    private record RectFill(int colorArgb, CgTexture2D texture) {
    }

    /** @return the fill this drawable would paint as, or {@code null} if it isn't a type the
     * rounded-wrap layer can clip/stroke (a 9-slice sprite, or anything else unrecognized). */
    private static @Nullable RectFill resolveRoundedFill(CgUiDrawable drawable) {
        if (drawable == CgUiDrawable.EMPTY) return new RectFill(0xFFFFFFFF, null);
        if (drawable instanceof CgUiQuad quad) return new RectFill(quad.getColorArgb(), null);
        if (drawable instanceof CgUiSprite sprite && !sprite.hasBorder()) {
            var texture = sprite.getTexture();
            return texture == null ? null : new RectFill(0xFFFFFFFF, texture);
        }
        return null;
    }

    private static CgUiRoundedRect buildRoundedRect(CornerRadii radii, float borderWidthPx, int borderColor, RectFill fill) {
        CgUiRoundedRect rect = new CgUiRoundedRect();
        rect.setCornerRadius(radii.rxTL(), radii.ryTL(), radii.rxTR(), radii.ryTR(),
                radii.rxBR(), radii.ryBR(), radii.rxBL(), radii.ryBL());
        if (borderWidthPx > 0f) {
            rect.setBorder(borderWidthPx, borderColor);
        }
        if (fill.texture() != null) {
            rect.setFillTexture(fill.texture());
        } else {
            rect.setFillColor(fill.colorArgb());
        }
        return rect;
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