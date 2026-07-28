package com.crystalgui.ui;

import com.crystalgraphics.api.vertex.CgVertexTransformUtil;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgui.core.data.CacheCell;
import com.crystalgui.core.data.IntCacheCell;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.render.texture.CgUiCrossFade;
import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.render.texture.CgUiLayerBox;
import com.crystalgui.render.texture.CgUiQuad;
import com.crystalgui.render.texture.CgUiRoundedRect;
import com.crystalgui.render.texture.CgUiSprite;
import com.crystalgui.style.ElementStyle;
import com.crystalgui.style.GeneralGroup;
import com.crystalgui.style.LayoutGroup;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.BoxOrigin;
import com.crystalgui.style.property.visual.Overflow;
import com.crystalgui.style.property.visual.OverflowClip;
import com.crystalgui.style.property.visual.border.BorderRadiusProperties;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.ui.event.DOMEvent;
import com.crystalgui.ui.event.FocusEvent;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.event.UIEvent;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import dev.vfyjxf.taffy.tree.Layout;
import dev.vfyjxf.taffy.tree.NodeId;
import dev.vfyjxf.taffy.tree.TaffyTree;
import dev.vfyjxf.taffy.util.MeasureFunc;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.joml.Matrix4f;
import org.joml.Vector2f;

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
    private FocusPolicy focusPolicy = FocusPolicy.NONE;

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

    /** True once {@link #markAsInternal()} has run — structural content owned by a composite widget,
     * excluded from the public child-mutation API ({@link #removeChild}/{@link #clearAllChildren}). */
    @Getter
    private boolean isInternalUI = false;

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
        // focusable() reads isEnabled, so the cached focus chain is now stale. Without this, tab
        // traversal keeps believing a disabled element is focusable and can walk into a subtree
        // with nothing focusable left in it.
        invalidateFocusableChain();
        if (!enabled) {
            // setPressed() bails on !isEnabled(), so a press held at the moment of disabling would
            // otherwise latch :active on permanently with no way to ever clear it. Clear before the
            // flag can trap it.
            if (this.isPressed) {
                this.isPressed = false;
            }
            // A disabled element must not keep keyboard focus — it would go on receiving key events
            // and Space/Enter-synthesised mouse events, and Tab would resume from a dead node.
            if (this.isFocused && attachedWindow != null) {
                attachedWindow.getInputHandler().blurIfFocused(this);
            }
        }
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
        if (!acceptsPublicChildren()) {
            throw new UnsupportedOperationException(
                    getClass().getSimpleName() + " does not accept public children — see its typed accessor methods instead");
        }
        return addChildAtInternal(child, index);
    }

    private UIElement addChildAtInternal(UIElement child, int index) {
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

    /** Whether this element accepts children through the public {@link #addChild}/{@link #addChildAt}
     * API. Ordinary elements accept public children exactly as before; composite widgets (Button,
     * Checkbox, ...) that own private structural children override this to return {@code false} and
     * build their internals via {@link #addInternalChild} instead. Widgets that legitimately host
     * external content alongside internal structure (e.g. a future ScrollerView) leave this {@code true}
     * at their own root and expose a separate typed accessor that delegates into a real content-host
     * child. */
    protected boolean acceptsPublicChildren() {
        return true;
    }

    /** Marks this element (and its current subtree) as internal — structural content owned by a
     * composite widget, not addressable via the public child-mutation API ({@link #removeChild}/
     * {@link #clearAllChildren} silently refuse to touch it). */
    public final void markAsInternal() {
        this.isInternalUI = true;
        for (UIElement child : children) child.markAsInternal();
    }

    /** Adds {@code child} bypassing the {@link #acceptsPublicChildren()} guard, then marks it internal.
     * Composite widgets call this (never {@code addChild}/{@code addChildAt}) to build their own
     * privately-owned structural children. */
    protected final UIElement addInternalChild(UIElement child) {
        addChildAtInternal(child, children.size());
        child.markAsInternal();
        return this;
    }

    public boolean removeChild(UIElement child) {
        if (child == null) return false;
        if (!hasChild(child)) return false;
        if (child.isInternalUI()) return false;
        return removeChildInternal(child);
    }

    private boolean removeChildInternal(UIElement child) {
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
            if (child.isInternalUI()) continue;
            removeChild(child);
        }
    }

    /** Inserts {@code child} at {@code index}, bypassing {@link #acceptsPublicChildren()}, then marks
     * it internal — the indexed counterpart to {@link #addInternalChild}. */
    protected final UIElement insertInternalChildAt(UIElement child, int index) {
        addChildAtInternal(child, index);
        child.markAsInternal();
        return this;
    }

    /** Removes a previously-{@link #markAsInternal() internal} child that this element itself owns,
     * bypassing the internal-child guard in {@link #removeChild}. Clears the child's internal flag on
     * the way out, since it's no longer owned by this widget once detached. */
    protected final boolean removeInternalChild(UIElement child) {
        if (child == null || !hasChild(child)) return false;
        boolean removed = removeChildInternal(child);
        if (removed) child.isInternalUI = false;
        return removed;
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
        // Drop focus before detaching — otherwise UIInputHandler keeps a reference into a subtree
        // that's no longer in the tree, and tab traversal from it silently restarts from the top.
        if (this.isFocused && attachedWindow != null) {
            attachedWindow.getInputHandler().blurIfFocused(this);
        }
        children.forEach(UIElement::onRemoved);
        events.emitToGroup(new DOMEvent.ElementRemoved(this));
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

    /** Marks this element and every ancestor as needing their cached "is anything here focusable"
     * answer recomputed. Must be called by anything that can change {@link #focusable()} — enabled
     * state, focus policy, or {@code display}. Public because {@code display} is driven from the
     * style layer ({@code LayoutProperties}), outside this package. */
    public void invalidateFocusableChain() {
        UIElement el = this;
        while (el != null) {
            el.getRuntimeCache().hasFocusableDescendant.invalidate();
            el = el.getParent();
        }
    }

    // ── Hit-testing ──────────────────────────────────────────────────────────

    /**
     * Converts a raw pointer position into this element's local space.
     *
     * <p><b>Widgets doing their own pointer arithmetic must call this first.</b> Raw input — and
     * therefore {@link com.crystalgui.ui.event.MouseEvent#getPosition()}, which hands the untouched
     * platform position straight through — arrives in <em>physical</em> pixels, while all element
     * geometry ({@link RuntimeCache#getX()}, {@code getTaffyLayout()}, …) is in <em>logical</em>
     * units. At the default {@code uiScale} of 2 those differ by a factor of two, so comparing one
     * against the other silently misses over most of the screen.</p>
     *
     * <p>This runs the same {@code worldToLocal} transform {@link UIWindow#getHoveredElement} uses,
     * so it stays correct under any transform rather than only a uniform scale.</p>
     */
    public Vector2f screenToLocal(float screenX, float screenY) {
        var local = CgVertexTransformUtil.transformPosition(runtimeCache.worldToLocal.get(), screenX, screenY);
        return new Vector2f(local.x(), local.y());
    }

    /**
     * Whether a raw (physical) pointer position falls inside this element's outer box — the public
     * counterpart to {@link #isMouseOverElement}, which is package-private and takes local
     * coordinates. Corner-radius aware, so it agrees with the real hit-tester.
     */
    public boolean containsScreenPoint(float screenX, float screenY) {
        Vector2f local = screenToLocal(screenX, screenY);
        return isMouseOverElement(local.x(), local.y());
    }

    boolean isMouseOverElement(float localMouseX, float localMouseY) {
        float rectX = runtimeCache.getX(), rectY = runtimeCache.getY();
        float rectWidth = runtimeCache.getWidth(), rectHeight = runtimeCache.getHeight();
        // Taffy's Layout.size() is always the full outer (content + padding + border) box
        // regardless of box-sizing, so this rect already matches the "outer" box
        // CgUiRoundedRect renders/border-radius describes.
        CornerRadii radii = resolveCornerRadii(rectWidth, rectHeight);
        return isInsideRoundedBox(localMouseX, localMouseY, rectX, rectY, rectWidth, rectHeight, radii);
    }

    /** Shared AABB-early-reject + per-corner elliptical-SDF hit test, used by both
     * {@link #isMouseOverElement} (outer box) and {@link #isMouseOverContent} (content box) so
     * rounded corners are never clipped by one and not the other. */
    private static boolean isInsideRoundedBox(float mouseX, float mouseY, float rectX, float rectY,
                                               float rectWidth, float rectHeight, CornerRadii radii) {
        // Cheap AABB early-reject first.
        if (!insideRectangle(mouseX, mouseY, rectX, rectY, rectWidth, rectHeight)) return false;
        if (radii.isZero()) return true;

        // Only when actually rounded: same elliptical rounded-box SDF as gui_rounded_rect.shader's
        // sdf_rounded_box (crystalgraphics:shaders/lib/sdf.glsl), evaluated in plain Java —
        // rendering and hit-testing must never disagree about the element's shape.
        float halfW = rectWidth * 0.5f, halfH = rectHeight * 0.5f;
        float localX = mouseX - (rectX + halfW);
        float localY = mouseY - (rectY + halfH);

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

    protected <T extends UIEvent> void attachDefaultListener(EventListenerGroup<T> handler, UIEvent.Listener<T> defaultAction) {
        handler.attachDefaultListener(defaultAction);
    }

    private record CornerRadii(float rxTL, float ryTL, float rxTR, float ryTR,
                                float rxBR, float ryBR, float rxBL, float ryBL) {
        boolean isZero() {
            return rxTL == 0f && ryTL == 0f && rxTR == 0f && ryTR == 0f
                    && rxBR == 0f && ryBR == 0f && rxBL == 0f && ryBL == 0f;
        }

        /** Grows every non-zero radius outward, for a shape drawn around this one (an outline ring).
         * Per-axis because the caller's inset already resolves per-axis. A zero radius stays zero —
         * a square corner offset outward is still square, matching CSS. */
        CornerRadii expand(float dx, float dy) {
            return new CornerRadii(
                    rxTL > 0f ? rxTL + dx : 0f, ryTL > 0f ? ryTL + dy : 0f,
                    rxTR > 0f ? rxTR + dx : 0f, ryTR > 0f ? ryTR + dy : 0f,
                    rxBR > 0f ? rxBR + dx : 0f, ryBR > 0f ? ryBR + dy : 0f,
                    rxBL > 0f ? rxBL + dx : 0f, ryBL > 0f ? ryBL + dy : 0f);
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

    /** Resolves the CSS-facing {@code overflow: visible|hidden} value into the actual clip
     * mechanism to render/hit-test with. {@code VISIBLE} is always {@link OverflowClip#NONE}.
     * {@code HIDDEN} auto-detects {@link OverflowClip#MASK} vs {@link OverflowClip#SCISSOR} from
     * this element's own resolved shape — real CSS never lets an author pick the clip mechanism
     * directly, only whether clipping happens at all. Requires layout to have run (reads the
     * outer box size to resolve percent corner radii), so this can't live in {@code GeneralGroup}
     * alone. */
    /** A sprite/9-slice background alone does NOT trigger {@link OverflowClip#MASK} — most sprites
     * are fine with a plain rectangular {@link OverflowClip#SCISSOR} clip (cheap, no FBO
     * compositing), including mid-{@code background}-transition: a scissor clip doesn't consult
     * the sprite's alpha at all, so it behaves exactly as if masked by a full opaque rectangle
     * around the padding box, without the compositing cost. {@code MASK} stays reserved for when
     * the shape genuinely isn't a rectangle (nonzero corner radius) or the author explicitly opts
     * in via {@code mask:} (which also correctly triggers mid-crossfade — a {@link CgUiCrossFade}
     * instance is never {@code == CgUiDrawable.EMPTY}). */
    OverflowClip resolveOverflowClip() {
        GeneralGroup styleGen = style.getGeneralGroup();
        if (styleGen.overflow() == Overflow.VISIBLE) return OverflowClip.NONE;

        CornerRadii radii = resolveCornerRadii(runtimeCache.getWidth(), runtimeCache.getHeight());
        boolean hasRadius = !radii.isZero();
        boolean hasExplicitMask = styleGen.mask() != CgUiDrawable.EMPTY;
        return (hasRadius || hasExplicitMask) ? OverflowClip.MASK : OverflowClip.SCISSOR;
    }

    /**
     * Clippable-region hit test, used by {@link com.crystalgui.ui.UIWindow#getHoveredElement} as the
     * gate for recursing into a clipping element's children — {@code overflow} decides which shape
     * that gate should test against, since the {@link OverflowClip#SCISSOR}/{@link OverflowClip#MASK}
     * mechanisms (auto-detected from {@code overflow: hidden} — see {@link #resolveOverflowClip()})
     * don't clip the same way. Despite the name, this tests the <strong>padding box</strong> (border excluded,
     * padding included) rather than the literal CSS content box — that's deliberate: it's what
     * {@link #paintChildren}'s real scissor rect and {@code paintDefaultMask}'s real mask reveal
     * region both actually clip to (standard CSS {@code overflow} semantics clip at the padding edge,
     * not the content edge — padding is part of the visible/scrollable area). Gating on the tighter
     * literal content box here previously left a dead zone in the padding gap where content was
     * visibly rendered (revealed by the real clip) but unreachably by hover.
     * <ul>
     *   <li>{@link OverflowClip#SCISSOR}: {@link #paintChildren}'s real scissor rect is a plain
     *       axis-aligned rectangle — never rounded, regardless of {@code border-radius} — so this
     *       tests a plain AABB, matching the real clip exactly.</li>
     *   <li>{@link OverflowClip#MASK}: tests a rounded-rectangle approximation (padding-box radii
     *       inset from the outer radii by border only). This is only an <strong>approximation</strong>
     *       — the real mask shape (see {@code paintDefaultMask}) can be an arbitrary sprite/9-slice
     *       alpha shape (a custom {@code mask:} override, or a 9-slice background with transparent
     *       regions), which this rounded-rect test cannot represent exactly. Exact per-mask-shape hit
     *       testing would need CPU-side sampling of the rendered mask's alpha — not implemented; this
     *       approximation is still meaningfully better than a plain AABB for the common case (solid
     *       color / single-texture backgrounds, which really do render as a rounded rect).</li>
     * </ul>
     */
    boolean isMouseOverContent(float localMouseX, float localMouseY, OverflowClip overflow) {
        var layout = getTaffyLayout();
        float borderWidthPx = layout.border().left;
        float outerWidth = runtimeCache.getWidth(), outerHeight = runtimeCache.getHeight();

        // Padding box: border excluded, padding included — matches paintChildren's real scissor rect
        // and paintDefaultMask's real border-only mask band, not Taffy's literal (border+padding
        // excluded) content box.
        final float
                contentX = runtimeCache.getX() + borderWidthPx,
                contentY = runtimeCache.getY() + borderWidthPx,
                contentWidth = outerWidth - 2f * borderWidthPx,
                contentHeight = outerHeight - 2f * borderWidthPx;

        if (overflow.isScissor()) {
            return insideRectangle(localMouseX, localMouseY, contentX, contentY, contentWidth, contentHeight);
        }

        // MASK (or no clip at all, though this method is only ever called when clipped): approximate
        // with a rounded rect. Padding-box corners are inset from the outer radii by border only —
        // e.g. an element with border-radius:20 and a 3px border has an effective ~17px radius at the
        // padding box. Border width is simplified to one scalar per axis here, same simplification
        // already used elsewhere (paintDefaultMask/paintSelf/paintOverlay all read a single
        // layout.border().left) — asymmetric border widths aren't fully modeled, a pre-existing latent
        // limitation, not something introduced here.
        CornerRadii outerRadii = resolveCornerRadii(outerWidth, outerHeight);
        CornerRadii contentRadii = new CornerRadii(
                Math.max(0f, outerRadii.rxTL() - borderWidthPx), Math.max(0f, outerRadii.ryTL() - borderWidthPx),
                Math.max(0f, outerRadii.rxTR() - borderWidthPx), Math.max(0f, outerRadii.ryTR() - borderWidthPx),
                Math.max(0f, outerRadii.rxBR() - borderWidthPx), Math.max(0f, outerRadii.ryBR() - borderWidthPx),
                Math.max(0f, outerRadii.rxBL() - borderWidthPx), Math.max(0f, outerRadii.ryBL() - borderWidthPx)
        );

        return isInsideRoundedBox(localMouseX, localMouseY, contentX, contentY, contentWidth, contentHeight, contentRadii);
    }

    private static boolean insideRectangle(float mouseX, float mouseY, float rectX, float rectY, float rectWidth, float rectHeight) {
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
        if (attachedWindow == null) return;
        attachedWindow.getStyleEngine().markDirty(this);
        // Descendants must be re-matched too: a descendant selector can key off THIS element's
        // state (e.g. `checkbox:checked .__mark__`, `button:hover .__icon__`), so a change here
        // can change which rules apply further down. Without this, a composite widget's internal
        // children keep a stale match forever and never visually react to the root's
        // hover/press/checked state.
        for (UIElement child : children) child.invalidateStyleMatch();
    }

    // ── Paint ────────────────────────────────────────────────────────────────

    /**
     * Paints this element's background (fill + border together, matching normal CSS stacking —
     * border stays under/before children so an overlapping child still visually covers it, exactly
     * like a real browser), then recurses into children (z-index-sorted, DOM order as tiebreak),
     * then paints this element's overlay. Fully synchronous — every call in this chain issues real
     * GPU draw calls immediately; nothing here defers or accumulates work for later replay.
     *
     * <p>When {@code opacity} is fractional or {@code overflow: hidden} auto-detects to
     * {@link OverflowClip#MASK} (see {@link #resolveOverflowClip()}), background/children/
     * overlay instead paint into an offscreen "visual layer" (a screen-sized FBO from
     * {@link CgUiPaintContext}'s pool) so overlapping translucent children blend as one unit before
     * opacity applies. When masked, only the children get a further nested layer that's actually
     * multiplied by the mask — this element's own background (painted by {@link #paintSelf}) is
     * composited into the outer layer unmasked, then the masked children are composited over it, so
     * masking only ever clips descendants, never this element's own background/border —
     * see {@link CgUiPaintContext#beginLayerFbo()}/{@code compositeMask}/{@code blitLayer}. Ordinary
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
        OverflowClip overflow = resolveOverflowClip();
        boolean needsLayer = opacity < 1f || overflow.isMask();

        if (!needsLayer) {
            paintSelf(ctx);
            paintChildren(ctx, overflow);
            paintOverlay(ctx);
            paintOutline(ctx);
            return;
        }

        CgFrameBuffer subtreeFbo = ctx.beginLayerFbo();
        paintSelf(ctx); // background (fill + border) — must NOT go through the mask below

        if (overflow.isMask() && !children.isEmpty()) {
            // Children get their own nested layer so the mask multiplies only THEM, not the
            // background already painted into subtreeFbo above.
            CgFrameBuffer childrenFbo = ctx.beginLayerFbo();
            paintChildren(ctx, overflow);

            CgFrameBuffer maskFbo = ctx.beginLayerFbo();
            paintDefaultMask(ctx, runtimeCache.getX(), runtimeCache.getY(), runtimeCache.getWidth(), runtimeCache.getHeight());
            ctx.endLayerFbo();

            ctx.compositeMask(childrenFbo, maskFbo); // multiply children-layer by mask alpha, in place
            ctx.endLayerFbo(); // back to subtreeFbo bound
            ctx.blitLayer(childrenFbo, 1f); // composite masked children OVER the unmasked background
        } else {
            paintChildren(ctx, overflow);
        }

        paintOverlay(ctx); // unchanged: unmasked, drawn after children composite
        // Inside the layer, so the outline is multiplied by this element's own opacity in the blit
        // below — matching CSS, where an outline belongs to the element's opacity group. (Drawing it
        // after blitLayer instead would keep a focus ring fully legible on a faded element; that's a
        // deliberate non-choice, since it would break `opacity` as a uniform whole-subtree fade.)
        paintOutline(ctx);
        ctx.endLayerFbo();
        ctx.blitLayer(subtreeFbo, opacity);
    }

    private void paintChildren(CgUiPaintContext ctx, OverflowClip overflow) {
        if (children.isEmpty()) return;
        boolean scissored = overflow.isScissor();
        if (scissored) {
            // Padding box (border excluded, padding included) — matches real CSS overflow:hidden
            // semantics (clips at the padding edge, not the content edge) and paintDefaultMask's
            // real border-only mask band. Previously insetting by border+padding (the literal content
            // box) clipped away the padding gap, one box-model layer too tight — see isMouseOverContent.
            float borderWidthPx = getTaffyLayout().border().left;
            int contentX = Math.round(runtimeCache.getX() + borderWidthPx);
            int contentY = Math.round(runtimeCache.getY() + borderWidthPx);
            int contentWidth = Math.round(runtimeCache.getWidth() - 2f * borderWidthPx);
            int contentHeight = Math.round(runtimeCache.getHeight() - 2f * borderWidthPx);
            ctx.pushScissor(contentX, contentY, contentWidth, contentHeight);
        }

        // Paint in the reverse of hit-test order (UIWindow.elementHitTest walks sortedChildren
        // highest-z-index-first and returns the first hit) — lowest z-index first, highest last, so
        // the highest-z-index child ends up visually on top, matching which child hit-testing
        // prioritizes. Previously this painted in plain DOM order regardless of z-index, so a
        // non-default z-index could make hit-testing and visual stacking disagree about which
        // overlapping sibling is "on top".
        UIElement[] sorted = runtimeCache.sortedChildren.get();
        for (int i = sorted.length - 1; i >= 0; i--) {
            sorted[i].drawSubtree(ctx);
        }

        if (scissored) ctx.popScissor();
    }

    /** Paints the default {@code overflow: hidden} mask shape directly into whatever's currently bound (the
     * transient mask FBO {@code drawSubtree} sets up) — this element's own resolved rounded-rect
     * shape, with the border band's alpha forced to 0 so only the inner (content) region masks
     * anything in (matches how a rounded {@code overflow: hidden} normally clips at the border's
     * inner edge). Follows the {@code mask:} style property when explicitly set (an authored
     * override — a different texture/shape from the actual background); otherwise defaults to
     * <strong>re-rendering this element's own resolved background fill</strong> (color, texture, or
     * 9-slice sprite) rather than a synthesized solid rounded rect — so a texture/sprite background's
     * own transparency (rounded/notched art baked into the image, not just {@code border-radius})
     * naturally becomes part of the mask with zero extra authoring.
     *
     * <p>Draws (not builds-and-returns) specifically so a {@link CgUiCrossFade} mask/background — a
     * background transition mid-flight — can be handled the same way {@link #paintRoundedLayer}
     * already handles it for the visual layer: both sides of the fade drawn into the SAME target at
     * complementary {@link CgUiPaintContext#withLayerOpacity} weights, so the mask tracks the
     * transition continuously instead of falling back to solid white for its whole duration and only
     * picking up the real end shape once the transition fully completes.</p> */
    private void paintDefaultMask(CgUiPaintContext ctx, float x, float y, float width, float height) {
        CornerRadii radii = resolveCornerRadii(width, height);
        float borderWidthPx = getTaffyLayout().border().left;
        GeneralGroup styleGen = style.getGeneralGroup();
        CgUiDrawable maskDrawable = styleGen.mask();
        CgUiDrawable maskSource = maskDrawable != CgUiDrawable.EMPTY ? maskDrawable : styleGen.background();

        ctx.setColor(0xFFFFFFFF);
        paintDefaultMaskShape(ctx, maskSource, x, y, width, height, radii, borderWidthPx);
    }

    /** Only called from {@link #paintDefaultMask}; recurses into {@link CgUiCrossFade} the same way
     * {@link #paintRoundedLayer} does — falls back to a solid-white fill for whichever leaf(ves)
     * don't resolve to a paintable fill (same "documented gap" cases {@link #canPaintRounded}
     * already covers, e.g. an unresolvable {@link CgUiCrossFade} leaf). */
    private static void paintDefaultMaskShape(CgUiPaintContext ctx, CgUiDrawable d, float x, float y, float width, float height,
                                               CornerRadii radii, float borderWidthPx) {
        if (d instanceof CgUiCrossFade cf) {
            ctx.withLayerOpacity(1f - cf.getT(), () ->
                    paintDefaultMaskShape(ctx, cf.getFrom(), x, y, width, height, radii, borderWidthPx));
            ctx.withLayerOpacity(cf.getT(), () ->
                    paintDefaultMaskShape(ctx, cf.getTo(), x, y, width, height, radii, borderWidthPx));
            return;
        }

        RectFill fill = resolveRoundedFill(d);
        if (fill == null) fill = new ColorFill(0xFFFFFFFF);

        CgUiRoundedRect mask = buildFillOnlyRoundedRect(radii, fill);
        if (borderWidthPx > 0f) {
            mask.setBorder(borderWidthPx, 0x00000000);
        }
        mask.draw(ctx, x, y, width, height);
    }

    /** Override for custom drawing beyond the generic box model (e.g. text glyphs, item icons).
     * Called before children paint. Paints the background — fill and border together, matching
     * normal CSS stacking (border stays under/before children). */
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
        // not reparsed independently.
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
    }

    /** Builds and draws a {@link CgUiRoundedRect} wrapping the resolved background, when possible.
     * @return {@code true} if it painted (caller must not also run the plain background path);
     *         {@code false} if {@code background} isn't a type this layer can clip/stroke (a
     *         {@link CgUiCrossFade} tree with an unresolvable leaf) — border-radius/border-width
     *         still resolve for hit-testing/layout growth in that case, just without visual clipping
     *         (documented gap). */
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

    /** A resolved fill for the rounded-wrap layer — a flat color, a single stretched texture, or a
     * 9-slice sprite (never more than one at once). */
    private sealed interface RectFill permits ColorFill, TextureFill, NineSliceFill {
    }

    private record ColorFill(int colorArgb) implements RectFill {
    }

    private record TextureFill(CgTexture2D texture) implements RectFill {
    }

    private record NineSliceFill(CgUiSprite sprite) implements RectFill {
    }

    /** @return the fill this drawable would paint as, or {@code null} if it isn't a type the
     * rounded-wrap layer can clip (anything unrecognized, or a sprite with no texture set). */
    private static @Nullable RectFill resolveRoundedFill(CgUiDrawable drawable) {
        if (drawable == CgUiDrawable.EMPTY) return new ColorFill(0xFFFFFFFF);
        if (drawable instanceof CgUiQuad quad) return new ColorFill(quad.getColorArgb());
        if (drawable instanceof CgUiSprite sprite) {
            var texture = sprite.getTexture();
            if (texture == null) return null;
            return sprite.hasBorder() ? new NineSliceFill(sprite) : new TextureFill(texture);
        }
        return null;
    }

    /** Builds a fill-only {@link CgUiRoundedRect} (no border) — used for the mask
     * shape, which handles its own border-band alpha separately (see {@link #buildDefaultMask}). */
    private static CgUiRoundedRect buildFillOnlyRoundedRect(CornerRadii radii, RectFill fill) {
        CgUiRoundedRect rect = new CgUiRoundedRect();
        rect.setCornerRadius(radii.rxTL(), radii.ryTL(), radii.rxTR(), radii.ryTR(),
                radii.rxBR(), radii.ryBR(), radii.rxBL(), radii.ryBL());
        switch (fill) {
            case ColorFill(int colorArgb) -> rect.setFillColor(colorArgb);
            case TextureFill(CgTexture2D texture) -> rect.setFillTexture(texture);
            case NineSliceFill(CgUiSprite sprite) -> rect.setFillSprite(sprite);
        }
        return rect;
    }

    private static CgUiRoundedRect buildRoundedRect(CornerRadii radii, float borderWidthPx, int borderColor, RectFill fill) {
        CgUiRoundedRect rect = buildFillOnlyRoundedRect(radii, fill);
        if (borderWidthPx > 0f) {
            rect.setBorder(borderWidthPx, borderColor);
        }
        return rect;
    }

    /** Override for custom drawing that must appear above children. Called after children paint. */
    protected void paintOverlay(CgUiPaintContext ctx) {
        // Reset ambient tint — a descendant's own paintSelf/paintOverlay may have left it non-white.
        ctx.setColor(0xFFFFFFFF);

        GeneralGroup styleGen = style.getGeneralGroup();
        CgUiDrawable overlay = styleGen.overlay();
        if (overlay == CgUiDrawable.EMPTY) return;

        // `overlay-origin` picks which box the layer is laid into, then `overlay-fit`/`-position`
        // size and place the drawable inside it. Defaults (border-box + fill) reproduce the
        // pre-longhand behaviour of stretching across the element's whole outer box exactly.
        CgUiLayerBox originBox = resolveOriginBox(styleGen.overlayOrigin());
        CgUiLayerBox box = CgUiLayerBox.resolve(overlay,
                originBox.x(), originBox.y(), originBox.width(), originBox.height(),
                styleGen.overlayFit(), styleGen.overlayPosition());

        overlay.draw(ctx, box.x(), box.y(), box.width(), box.height());
    }

    /**
     * Paints the {@code outline} layer — drawn last, above {@code overlay} and above all children.
     *
     * <p>Deliberately layout-free: the rect comes from the already-resolved border box expanded by
     * {@code outline-offset}, and nothing here feeds Taffy. That's the whole reason this layer
     * exists — {@code border-width} <em>does</em> feed layout, so it can't be used to mark focus
     * without resizing the element, and {@code overlay} is typically already spoken for by a
     * widget's own decoration (a checkbox's check mark, say).</p>
     *
     * <p>Not clipped by this element's own {@code overflow: hidden} (its scissor is popped inside
     * {@code paintChildren}, and the mask only multiplies the children layer). It IS clipped by an
     * <em>ancestor</em>'s scissor/mask, so a positive {@code outline-offset} inside a scroll view
     * will be cut — real CSS behaves the same way, which is why the offset defaults to 0.</p>
     */
    protected void paintOutline(CgUiPaintContext ctx) {
        GeneralGroup styleGen = style.getGeneralGroup();
        CgUiDrawable outline = styleGen.outline();
        float strokeWidth = styleGen.outlineWidth().resolve(runtimeCache.getWidth());
        boolean hasDrawable = outline != CgUiDrawable.EMPTY;
        if (!hasDrawable && strokeWidth <= 0f) return;

        // Reset ambient tint — children painted arbitrary things before we got here.
        ctx.setColor(0xFFFFFFFF);

        final float x = runtimeCache.getX(), y = runtimeCache.getY();
        final float width = runtimeCache.getWidth(), height = runtimeCache.getHeight();
        // Percent offsets resolve per-axis against the element's own box, same convention as
        // border-radius (CSS itself only allows <length> here, so a percent is an author error we
        // interpret rather than reject).
        LengthPercent offset = styleGen.outlineOffset();
        float offsetX = offset.resolve(width);
        float offsetY = offset.resolve(height);

        if (hasDrawable) {
            // Drawable wins when both are set — same precedence CSS gives border-image over border.
            outline.draw(ctx, x - offsetX, y - offsetY,
                    Math.max(0f, width + 2f * offsetX), Math.max(0f, height + 2f * offsetY));
            return;
        }

        // SDF stroke form. The shader measures _BorderWidth INWARD from the shape's outer edge,
        // while a CSS outline grows OUTWARD from the offset edge — so inflate by offset+width and
        // let the inward stroke land exactly in the band between offset and offset+width.
        float insetX = offsetX + strokeWidth;
        float insetY = offsetY + strokeWidth;
        // Resolve radii against the element's OWN box, then expand. Resolving against the inflated
        // box instead would re-scale percentage radii and produce a visibly over-curved ring.
        CornerRadii radii = resolveCornerRadii(width, height).expand(insetX, insetY);

        int color = styleGen.outlineColor();
        // Transparent fill that keeps the stroke's RGB: the shader mixes border->fill on straight
        // (non-premultiplied) alpha, so a plain 0x00000000 fill would drag the inner anti-aliased
        // edge toward black and leave a dark fringe. Same RGB, zero alpha = a clean alpha ramp.
        CgUiRoundedRect ring = buildFillOnlyRoundedRect(radii, new ColorFill(color & 0x00FFFFFF));
        ring.setBorder(strokeWidth, color);
        ring.draw(ctx, x - insetX, y - insetY, width + 2f * insetX, height + 2f * insetY);
    }

    /** Absolute-screen rect for one of the CSS box-model boxes.
     *
     * <p>Uses the real per-edge {@code border()}/{@code padding()} values rather than the
     * {@code border().left}-for-every-edge shortcut taken elsewhere in this class, so an asymmetric
     * box resolves correctly. Note Taffy's own {@code Layout.contentBoxX()/contentBoxY()} are
     * <em>parent-relative</em> and unusable here — hence accumulating from
     * {@code runtimeCache.getX()/getY()}, matching what {@code UIText.paintOverlay} already does. */
    private CgUiLayerBox resolveOriginBox(BoxOrigin origin) {
        final float x = runtimeCache.getX(), y = runtimeCache.getY();
        final float width = runtimeCache.getWidth(), height = runtimeCache.getHeight();
        if (origin == BoxOrigin.BORDER_BOX) {
            return new CgUiLayerBox(x, y, width, height);
        }

        var layout = getTaffyLayout();
        var border = layout.border();
        float insetLeft = border.left, insetTop = border.top;
        float insetRight = border.right, insetBottom = border.bottom;
        if (origin == BoxOrigin.CONTENT_BOX) {
            var padding = layout.padding();
            insetLeft += padding.left;
            insetTop += padding.top;
            insetRight += padding.right;
            insetBottom += padding.bottom;
        }
        return new CgUiLayerBox(x + insetLeft, y + insetTop,
                Math.max(0f, width - insetLeft - insetRight),
                Math.max(0f, height - insetTop - insetBottom));
    }

    /** Non-null for elements whose size depends on their content (e.g. text) — Taffy calls this
     * during layout to resolve intrinsic size. Ordinary elements return {@code null} (pure CSS
     * sizing, the default) and are registered as plain leaves; see {@link UIWindow#registerElement}.
     * Mixed trees (some leaves measured, most not) are fully supported by Taffy itself — no
     * special-casing needed here beyond returning non-null when an override wants one. */
    protected MeasureFunc measureFunc() {
        return null;
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