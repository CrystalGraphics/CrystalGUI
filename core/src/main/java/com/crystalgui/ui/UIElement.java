package com.crystalgui.ui;

import com.crystalgui.core.data.Transform2D;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgui.core.data.CacheCell;
import com.crystalgui.core.data.IntCacheCell;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.render.texture.CgUiCrossFade;
import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.serialization.StateMap;
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
import com.crystalgui.style.property.visual.ScrollBehavior;
import com.crystalgui.style.property.visual.border.BorderRadiusProperties;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.ui.event.DOMEvent;
import com.crystalgui.ui.event.FocusEvent;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.event.UIEvent;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.ui.tree.UITreeTraversal;
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

    @Getter
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
        notifyIdentityChanged();
        return this;
    }

    public UIElement addClass(String cls) {
        if (classes.add(cls)) {
            invalidateStyleMatch();
            notifyIdentityChanged();
        }
        return this;
    }

    public UIElement removeClass(String cls) {
        if (classes.remove(cls)) {
            invalidateStyleMatch();
            notifyIdentityChanged();
        }
        return this;
    }

    public boolean hasClass(String cls) {
        return classes.contains(cls);
    }

    /**
     * Lowercase tag/type used by selector-engine type selectors (e.g. {@code button { ... }}) and by
     * serialization.
     *
     * <p>Comes from {@link ElementRegistry}, keyed by class, so an element built with
     * {@code new Button(...)} reports exactly what {@code ElementRegistry.create("button")} would.
     * Falls back to the simple class name for an unregistered type.</p>
     *
     * <p>This used to be the simple class name unconditionally, which quietly broke {@code UIText}:
     * it registers as {@code "text"} but reported {@code "uitext"}, so a {@code text { }} rule had
     * never matched anything. Shipped sheets use neither name, so nothing visible changes today —
     * but a downstream sheet targeting {@code uitext} would.</p>
     */
    public String tagName() {
        String registered = ElementRegistry.tagOf(getClass());
        return registered != null ? registered : getClass().getSimpleName().toLowerCase(Locale.ROOT);
    }


    // ── State ────────────────────────────────────────────────────────────────
    public void setEnabled(boolean enabled) {
        if (this.isEnabled == enabled) return;
        this.isEnabled = enabled;
        notifyIdentityChanged();
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

    /**
     * Whether this element's current value fails its own validation — CSS's {@code :invalid}.
     *
     * @return {@code false} for everything that has no notion of validity.
     */
    public boolean isInvalid() {
        return false;
    }

    /**
     * Whether this element edits text, and so must receive Space and Enter as characters rather than
     * as activation.
     *
     * <p>{@code UIInputHandler} turns Space/Enter on a focused element into a synthesized mouse
     * press, which is how a Button gains keyboard activation for free. A text field has to opt out,
     * or every space typed would fire its press handlers — and the synthesized event carries the
     * physical cursor position, so it would also act wherever the mouse happened to be.</p>
     */
    public boolean consumesTextInput() {
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
        child.setObserver(this.observer);
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
    public boolean acceptsPublicChildren() {
        return true;
    }

    // ── Serializable state ───────────────────────────────────────────────────

    /**
     * Writes this widget's own serializable state — content and configuration.
     *
     * <p>Exists so the element codec never has to know about individual widgets: it asks each
     * element what it wants preserved and gets a flat bag back. A widget adding state adds it here,
     * and nothing in {@code serialization} changes.</p>
     *
     * <p><b>Authored state only.</b> Never write runtime input state — pressed, hovered, focused,
     * caret position, scroll offset. Those belong to whichever side the user's pointer is on, and a
     * server pushing them would fight the person using the UI.</p>
     *
     * <p>Must be symmetric with {@link #readState}, and every key should be written conditionally
     * (see {@code StateMap.putBoolIfNot}) so a default-valued widget carries nothing.</p>
     */
    protected <T> void writeState(StateMap<T> out) {
    }

    /**
     * Restores what {@link #writeState} wrote.
     *
     * <p><b>Go through the public mutators</b> — {@code setChecked}, {@code setText} — rather than
     * assigning fields. Their side effects (pane visibility, {@code IMPORTANT}-origin style writes,
     * {@code invalidateStyleMatch}) are exactly what has to happen on the receiving side, and
     * bypassing them produces a widget that holds the right value while looking wrong.</p>
     *
     * <p>Every read takes a default, so a description written before a key existed still decodes.</p>
     */
    protected <T> void readState(StateMap<T> in) {
    }

    /** Codec-facing entry points — {@link #writeState}/{@link #readState} stay protected so they
     * read as widget-authoring hooks rather than public API. */
    public final <T> void writeStateTo(StateMap<T> out) {
        writeState(out);
    }

    public final <T> void readStateFrom(StateMap<T> in) {
        readState(in);
    }

    // ── Networking ───────────────────────────────────────────────────────────

    /** Assigned by a session in document order; {@code -1} until then. See {@code NetworkIds}. */
    @Getter @Setter
    private int networkId = -1;

    /** Null on every element in a local UI. Lazily created — most elements report nothing. */
    @Nullable
    private Set<String> reportedEvents;

    /**
     * Declares that this element's {@code kind} interactions should be reported to whoever owns the
     * session — the client half installs a listener for each of these when it rebuilds the tree.
     *
     * <p>Only the <em>name</em> lives here. The handler itself stays on the server session, which is
     * what lets behaviour be a lambda that never leaves the JVM it was written in.</p>
     */
    public UIElement addReportedEvent(String kind) {
        if (reportedEvents == null) reportedEvents = new LinkedHashSet<>();
        reportedEvents.add(kind);
        return this;
    }

    /** Unmodifiable, and empty for the overwhelming majority of elements. */
    public Set<String> getReportedEvents() {
        return reportedEvents == null ? Set.of() : Collections.unmodifiableSet(reportedEvents);
    }

    // ── Tree observation ─────────────────────────────────────────────────────

    /** Null for every element in a purely client-side UI, which is the common case. */
    @Nullable
    private UITreeObserver observer;

    @Nullable
    public UITreeObserver getObserver() {
        return observer;
    }

    /**
     * Installs {@code observer} on this element and its whole subtree.
     *
     * <p>Propagated exactly like {@link #attachedWindow}: set when an element is added, cleared when
     * it is removed. That symmetry is what makes a grafted subtree report itself correctly without
     * the session ever walking the tree.</p>
     */
    public final void setObserver(@Nullable UITreeObserver observer) {
        if (this.observer == observer) return;
        if (this.observer != null) this.observer.onDetached(this);
        this.observer = observer;
        if (observer != null) observer.onAttached(this);
        for (UIElement child : children) child.setObserver(observer);
    }

    /**
     * Reports that this widget's serializable state changed.
     *
     * <p>Attributed to the nearest <b>non-internal</b> ancestor. A Button's label is an internal
     * {@code UIText} that never travels as an element of its own, so {@code button.setText(...)} has
     * to dirty the Button — whose {@code writeState} carries the text — rather than a child the far
     * side has never heard of.</p>
     */
    protected final void notifyStateChanged() {
        UIElement target = this;
        while (target.isInternalUI() && target.parent != null) target = target.parent;
        if (target.observer != null) target.observer.onStateDirty(target);
    }

    /** Reports an id/class/enabled/focus change — the inputs to the far side's selector matching. */
    private void notifyIdentityChanged() {
        if (observer != null) observer.onIdentityDirty(this);
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
        // Before the parent link is cleared, so an observer can still see where it was.
        child.setObserver(null);
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

    public boolean hasChild(UIElement child) {
        return children.contains(child);
    }

    // ── Focus ────────────────────────────────────────────────────────────────

    public UIElement setFocusPolicy(FocusPolicy newPolicy) {
        if (newPolicy == null) return setFocusPolicy(FocusPolicy.NONE);
        if (this.focusPolicy == newPolicy) return this;
        if (this.focusPolicy.isFocusable() != newPolicy.isFocusable()) invalidateFocusableChain();
        this.focusPolicy = newPolicy;
        notifyIdentityChanged();
        return this;
    }

    /** Hand-written rather than Lombok's {@code @Setter} so a real change can be reported. */
    public UIElement setHitTest(boolean hitTest) {
        if (this.hitTest == hitTest) return this;
        this.hitTest = hitTest;
        notifyIdentityChanged();
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

    // ── Scrolling ────────────────────────────────────────────────────────────

    /**
     * Scroll offset, in logical px, exactly like the DOM's {@code element.scrollTop}/
     * {@code scrollLeft}: state on the element, applied when painting its descendants. There is no
     * viewport or content wrapper — children of a scroll container are ordinary direct children.
     */
    @Getter
    private float scrollLeft, scrollTop;

    /** @see #setScrollExempt(boolean) */
    @Getter
    private boolean scrollExempt = false;

    /** The cascaded {@code transform}. @see #setTransform(UITransform) */
    public UITransform getTransform() {
        return style.getGeneralGroup().transform();
    }

    /**
     * Applies a paint-time affine — CSS's {@code transform} — to this element and everything under it.
     *
     * <p>Sugar over the style system: this writes {@code transform} at {@link StyleOrigin#INLINE},
     * exactly as {@code layout(l -> ...)} does, so a stylesheet rule and this setter compete through
     * the normal cascade rather than one silently shadowing the other. Read it back with
     * {@link #getTransform()}, or set the pivot with
     * {@code style(s -> s.transformOrigin(x, y))} — the origin is {@code transform-origin}, its own
     * cascading property.</p>
     *
     * <p><b>Layout-free.</b> Taffy never sees it, so scaling an element cannot reflow its siblings or
     * resize its parent. That is what makes it the right tool for a zoomable canvas: put one scale on
     * a container and its whole subtree zooms, with layout frozen underneath — the same thing LDLib2's
     * node graph does, and the reason it can zoom continuously without relaying out on every wheel
     * notch.</p>
     *
     * <p><b>Hit-testing follows automatically.</b> The transform goes into
     * {@code RuntimeCache.localToWorld}, which {@code UIWindow.elementHitTest} inverts — so a pointer
     * is mapped back through the transform before any box test, and a scaled or rotated subtree stays
     * clickable where it is drawn, with no special-casing. Nothing else needs to know.</p>
     *
     * @param transform {@code null} resets to {@link UITransform#IDENTITY}
     */
    public UIElement setTransform(UITransform transform) {
        style.getGeneralGroup().transform(transform);
        return this;
    }

    /**
     * Dirties this element's world matrix and every descendant's.
     *
     * <p>Public because the {@code transform}/{@code transform-origin} properties invalidate through it
     * from {@code StylePropertyRegistry}'s change listeners — the whole subtree's matrices derive from
     * this element's, so a transform change that only dirtied this node would leave hit-testing
     * inverting the pre-transform matrix for every descendant, while rendering looked correct.</p>
     */
    public void invalidatePoseCachesRecursively() {
        runtimeCache.resetPoseCache();
        for (UIElement child : children) child.invalidatePoseCachesRecursively();
    }

    /** {@code transform-origin} resolved against this element's current box, in local pixels. */
    private float transformOriginPxX() {
        return style.getGeneralGroup().transformOriginX().resolve(runtimeCache.getWidth());
    }

    /** @see #transformOriginPxX() */
    private float transformOriginPxY() {
        return style.getGeneralGroup().transformOriginY().resolve(runtimeCache.getHeight());
    }

    /**
     * Exempts this element from its parent's scroll offset — it stays pinned while siblings scroll,
     * and is excluded from the parent's {@code scrollWidth}/{@code scrollHeight}.
     *
     * <p>For a scroll container's own chrome: its scrollbars must not scroll away with the content,
     * and must not themselves count as content to scroll to. Browsers get this free by not making
     * scrollbars DOM nodes at all; ours are real elements (so they stay CSS-styleable with real
     * {@code :hover}) and opt out explicitly instead.</p>
     *
     * <p>Not something ordinary content should set.</p>
     */
    public UIElement setScrollExempt(boolean scrollExempt) {
        if (this.scrollExempt == scrollExempt) return this;
        this.scrollExempt = scrollExempt;
        runtimeCache.resetPoseCache();
        return this;
    }

    /** Whether {@code overflow} makes this a scroll container ({@code hidden}/{@code scroll}/
     * {@code auto} — not {@code clip}, and not {@code visible}). */
    public boolean isScrollContainer() {
        return style.getGeneralGroup().overflow().isScrollContainer();
    }

    /** Whether the <em>user</em> may scroll this with the wheel. Narrower than
     * {@link #isScrollContainer()} — see {@link Overflow#allowsUserScrolling()}. */
    public boolean allowsUserScrolling() {
        return style.getGeneralGroup().overflow().allowsUserScrolling();
    }

    /** Total width of the content, i.e. the furthest right edge any child reaches. DOM's
     * {@code scrollWidth}. Excludes scroll-exempt children — a scrollbar pinned to the right edge
     * must not itself count as content to scroll to. */
    public float getScrollWidth() {
        float max = 0f;
        for (UIElement child : children) {
            if (child.scrollExempt) continue;
            max = Math.max(max, child.getLayoutX() + child.getRuntimeCache().getWidth());
        }
        return max;
    }

    /** Total height of the content. DOM's {@code scrollHeight}. */
    public float getScrollHeight() {
        float max = 0f;
        for (UIElement child : children) {
            if (child.scrollExempt) continue;
            max = Math.max(max, child.getLayoutY() + child.getRuntimeCache().getHeight());
        }
        return max;
    }

    /** Visible content width — the padding box, which is what the clip actually clips to. */
    public float getClientWidth() {
        return Math.max(0f, runtimeCache.getWidth() - 2f * getTaffyLayout().border().left);
    }

    public float getClientHeight() {
        return Math.max(0f, runtimeCache.getHeight() - 2f * getTaffyLayout().border().left);
    }

    /** How far this can scroll before hitting the end; 0 when the content fits. */
    public float getMaxScrollLeft() {
        return Math.max(0f, getScrollWidth() - getClientWidth());
    }

    public float getMaxScrollTop() {
        return Math.max(0f, getScrollHeight() - getClientHeight());
    }

    /** Where the scroll is heading. Equal to {@link #getScrollTop()} unless a smooth scroll is in
     * flight — {@code scroll-behavior: smooth} eases the rendered offset toward this. */
    @Getter
    private float targetScrollLeft, targetScrollTop;

    /** Clamped to {@code [0, maxScrollLeft]}, and a no-op on a non-scroll-container — matching the DOM,
     * where assigning {@code scrollTop} to an unscrollable element silently does nothing. Honours
     * {@code scroll-behavior}. */
    public UIElement setScrollLeft(float value) {
        return setScroll(value, targetScrollTop);
    }

    public UIElement setScrollTop(float value) {
        return setScroll(targetScrollLeft, value);
    }

    public UIElement setScroll(float left, float top) {
        if (!isScrollContainer()) return this;
        this.targetScrollLeft = Math.max(0f, Math.min(getMaxScrollLeft(), left));
        this.targetScrollTop = Math.max(0f, Math.min(getMaxScrollTop(), top));

        if (style.getGeneralGroup().scrollBehavior() == ScrollBehavior.SMOOTH && attachedWindow != null) {
            attachedWindow.registerScrollAnimation(this);
            return this;               // the tick eases the rendered offset toward the target
        }
        return applyScrollOffset(targetScrollLeft, targetScrollTop);
    }

    /**
     * Jumps straight to an offset, ignoring {@code scroll-behavior}.
     *
     * <p>For dragging a scrollbar thumb: the thumb must stay under the cursor, so easing toward it
     * would make it lag by the animation's duration — the same reason {@code Slider} and
     * {@code SplitView} refuse to animate their drags.</p>
     */
    public UIElement setScrollImmediate(float left, float top) {
        if (!isScrollContainer()) return this;
        this.targetScrollLeft = Math.max(0f, Math.min(getMaxScrollLeft(), left));
        this.targetScrollTop = Math.max(0f, Math.min(getMaxScrollTop(), top));
        return applyScrollOffset(targetScrollLeft, targetScrollTop);
    }

    /**
     * Advances a smooth scroll. Returns true while still animating.
     *
     * <p>Exponential ease rather than a fixed-duration curve so it is frame-rate independent: the
     * same wall-clock time produces the same motion at 30fps or 200fps, and a new target mid-flight
     * simply re-aims from wherever it currently is instead of restarting.</p>
     */
    boolean tickScrollAnimation(float deltaSeconds) {
        float duration = Math.max(0.01f, style.getGeneralGroup().scrollDuration());
        // Time constant chosen so ~95% of the distance is covered within `duration`.
        float t = 1f - (float) Math.exp(-deltaSeconds * 3f / duration);
        float nextLeft = scrollLeft + (targetScrollLeft - scrollLeft) * t;
        float nextTop = scrollTop + (targetScrollTop - scrollTop) * t;

        // Snap and finish once sub-pixel, otherwise it creeps forever and never releases the tick.
        if (Math.abs(targetScrollLeft - nextLeft) < 0.5f && Math.abs(targetScrollTop - nextTop) < 0.5f) {
            applyScrollOffset(targetScrollLeft, targetScrollTop);
            return false;
        }
        applyScrollOffset(nextLeft, nextTop);
        return true;
    }

    private UIElement applyScrollOffset(float clampedLeft, float clampedTop) {
        if (clampedLeft == scrollLeft && clampedTop == scrollTop) return this;
        this.scrollLeft = clampedLeft;
        this.scrollTop = clampedTop;
        // Descendants' world transforms are derived from this offset, so they're now stale. Without
        // this, hit-testing would keep resolving against the pre-scroll positions.
        for (UIElement child : children) invalidateSubtreeTransforms(child);
        // Position only — no relayout. The offset never reaches Taffy; it lives purely in the
        // transform chain, which is what makes scrolling free of any wrapper element.
        onStyleChanged();
        return this;
    }

    private static void invalidateSubtreeTransforms(UIElement element) {
        element.getRuntimeCache().resetPoseCache();
        for (UIElement child : element.getChildren()) invalidateSubtreeTransforms(child);
    }

    /** Re-applies the clamp against current content/box sizes. Call after the content changes, so a
     * shrinking child can't leave the view scrolled past the end. Instant — a clamp is a correction,
     * not a scroll the user asked for, so it shouldn't animate. */
    public void clampScroll() {
        setScrollImmediate(scrollLeft, scrollTop);
    }

    /**
     * Scrolls every ancestor so this element is visible — the DOM's {@code scrollIntoView}.
     *
     * <p>Instant, never eased, even under {@code scroll-behavior: smooth}: this runs when focus lands
     * somewhere off-screen, and the element needs to be <em>there</em> by the time the user looks,
     * not gliding into place afterwards.</p>
     *
     * <p>Scrolls the minimum distance — an element already in view doesn't move, and one just off the
     * edge is brought flush to that edge rather than centred. Walking outward is safe because
     * {@code RuntimeCache} positions are scroll-independent layout coordinates (the offset lives in
     * the transform chain), so scrolling an outer container doesn't invalidate the inner numbers.</p>
     */
    public void scrollIntoView() {
        var self = getRuntimeCache();
        for (UIElement ancestor = getParent(); ancestor != null; ancestor = ancestor.getParent()) {
            if (!ancestor.isScrollContainer()) continue;

            var box = ancestor.getRuntimeCache();
            float border = ancestor.getTaffyLayout().border().left;

            float relLeft = self.getX() - box.getX() - border;
            float relTop = self.getY() - box.getY() - border;

            ancestor.setScrollImmediate(
                    minimalScroll(ancestor.getScrollLeft(), ancestor.getClientWidth(),
                            relLeft, self.getWidth()),
                    minimalScroll(ancestor.getScrollTop(), ancestor.getClientHeight(),
                            relTop, self.getHeight()));
        }
    }

    /** The nearest scroll offset that brings {@code [start, start+length]} fully inside the view.
     * Returns {@code current} unchanged when it already is. */
    private static float minimalScroll(float current, float viewLength, float start, float length) {
        float end = start + length;
        if (start < current) return start;                       // off the near edge
        if (end > current + viewLength) return end - viewLength; // off the far edge
        return current;
    }

    // ── Tree queries ─────────────────────────────────────────────────────────

    /**
     * First <em>descendant</em> matching {@code selector}, in document order, or {@code null}.
     *
     * <p>Excludes this element, matching {@code Element.querySelector} in the DOM. Backed by the same
     * {@link com.crystalgui.style.selector.Selector} the stylesheet cascade uses, so only that subset
     * of selector syntax works — and combinators are resolved against the <em>live tree</em>, meaning
     * {@code ".a .b"} can match here via an ancestor above this element. See
     * {@link UITreeTraversal#querySelector} for the full contract.</p>
     */
    public UIElement querySelector(String selector) {
        return UITreeTraversal.querySelector(this, selector, false);
    }

    /** Every matching descendant, in document order. Excludes this element. */
    public List<UIElement> querySelectorAll(String selector) {
        return UITreeTraversal.querySelectorAll(this, selector, false);
    }

    /** First descendant with this id, or {@code null}. Excludes this element. */
    public UIElement getElementById(String id) {
        return UITreeTraversal.getElementById(this, id, false);
    }

    /** Every descendant carrying this class, in document order. Excludes this element. */
    public List<UIElement> getElementsByClassName(String className) {
        return UITreeTraversal.getElementsByClassName(this, className, false);
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
        return Transform2D.apply(runtimeCache.worldToLocal.get(), screenX, screenY);
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
        if (!styleGen.overflow().clips()) return OverflowClip.NONE;

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
        // Pushed BEFORE the localToWorld snapshot below, so that snapshot includes this element's own
        // transform — which is what the RuntimeCache calculator produces too. Get this order wrong and
        // hit-testing silently disagrees with rendering by exactly the transform.
        UITransform transform = getTransform();
        boolean pushedTransform = !transform.isIdentity();
        if (pushedTransform) {
            ctx.getPoseStack().pushPose();
            var pose = ctx.getPoseStack().last().pose();
            transform.applyTo(pose, runtimeCache.getX(), runtimeCache.getY(),
                    runtimeCache.getWidth(), runtimeCache.getHeight(),
                    transformOriginPxX(), transformOriginPxY());
        }
        try {
            drawSubtreeTransformed(ctx);
        } finally {
            if (pushedTransform) ctx.getPoseStack().popPose();
        }
    }

    /** The body of {@link #drawSubtree}, with this element's own transform already on the pose. */
    private void drawSubtreeTransformed(CgUiPaintContext ctx) {
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
            paintDefaultMask(ctx);
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
            //
            // Passed unrounded. `pushScissor` quantises once, in physical space, growing outward —
            // rounding here would quantise in LOGICAL space, which at a fractional uiScale throws
            // away a fraction of a physical pixel before the transform has even run.
            float borderWidthPx = getTaffyLayout().border().left;
            ctx.pushScissor(runtimeCache.getX() + borderWidthPx,
                    runtimeCache.getY() + borderWidthPx,
                    runtimeCache.getWidth() - 2f * borderWidthPx,
                    runtimeCache.getHeight() - 2f * borderWidthPx);
        }

        // Paint in the reverse of hit-test order (UIWindow.elementHitTest walks sortedChildren
        // highest-z-index-first and returns the first hit) — lowest z-index first, highest last, so
        // the highest-z-index child ends up visually on top, matching which child hit-testing
        // prioritizes. Previously this painted in plain DOM order regardless of z-index, so a
        // non-default z-index could make hit-testing and visual stacking disagree about which
        // overlapping sibling is "on top".
        UIElement[] sorted = runtimeCache.sortedChildren.get();

        // Scrolling, the whole of it. Translating the pose here is enough for INPUT as well as paint:
        // drawSubtree snapshots the live pose into each child's localToWorld, and
        // UIWindow.elementHitTest inverts that same matrix — so hit-testing picks the offset up with
        // no second code path. This is why scrolling needs no wrapper element the way LDLib's does.
        boolean scrolled = scrollLeft != 0f || scrollTop != 0f;
        if (scrolled) {
            ctx.getPoseStack().pushPose();
            ctx.getPoseStack().translate(-scrollLeft, -scrollTop, 0f);
        }

        for (int i = sorted.length - 1; i >= 0; i--) {
            // Scroll-exempt children (a scroll container's own scrollbars) are painted after the
            // translate is popped, below — otherwise they would scroll away with the content.
            if (scrolled && sorted[i].scrollExempt) continue;
            sorted[i].drawSubtree(ctx);
        }

        if (scrolled) {
            ctx.getPoseStack().popPose();
            for (int i = sorted.length - 1; i >= 0; i--) {
                if (sorted[i].scrollExempt) sorted[i].drawSubtree(ctx);
            }
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
    private void paintDefaultMask(CgUiPaintContext ctx) {
        float borderWidthPx = getTaffyLayout().border().left;
        GeneralGroup styleGen = style.getGeneralGroup();
        CgUiDrawable maskDrawable = styleGen.mask();
        CgUiDrawable maskSource = maskDrawable != CgUiDrawable.EMPTY ? maskDrawable : styleGen.background();

        // `mask-origin`/`-fit`/`-position`, resolved exactly like the `overlay-*` trio in
        // paintOverlay. Because the mask's alpha is what compositeMask multiplies the children layer
        // by, re-boxing the mask directly moves and resizes the clip region — which is the point:
        // the reveal area no longer has to be the element's own border box.
        //
        // Defaults (border-box + fill + center) resolve to precisely the rect this used to be called
        // with — runtimeCache's x/y/width/height — so nothing changes until a stylesheet opts in.
        CgUiLayerBox originBox = resolveOriginBox(styleGen.maskOrigin());

        // `mask-offset` grows the origin box on all four sides before fit/position run, so a
        // positive value reveals a margin beyond the element and a negative one insets the clip.
        // Percent resolves per-axis against the origin box, matching outline-offset's convention.
        LengthPercent offset = styleGen.maskOffset();
        float offsetX = offset.resolve(originBox.width());
        float offsetY = offset.resolve(originBox.height());

        CgUiLayerBox box = CgUiLayerBox.resolve(maskSource,
                originBox.x() - offsetX, originBox.y() - offsetY,
                Math.max(0f, originBox.width() + 2f * offsetX),
                Math.max(0f, originBox.height() + 2f * offsetY),
                styleGen.maskFit(), styleGen.maskPosition());

        // Radii resolve against the mask's own box, not the element's, so percentage radii stay
        // proportional to the shape actually being drawn once it's been re-boxed.
        CornerRadii radii = resolveCornerRadii(box.width(), box.height());

        ctx.setColor(0xFFFFFFFF);
        paintDefaultMaskShape(ctx, maskSource, box.x(), box.y(), box.width(), box.height(), radii, borderWidthPx);
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
        // Percent offsets resolve against the element's own box on their own axis, same convention as
        // border-radius (CSS itself only allows <length> here, so a percent is an author error we
        // interpret rather than reject).
        //
        // Per-edge, unlike CSS's single scalar — see OutlineOffsetShorthand. A 9-slice ring has to hug
        // a sprite whose transparent padding need not be symmetric.
        float offsetTop = styleGen.outlineOffsetTop().resolve(height);
        float offsetBottom = styleGen.outlineOffsetBottom().resolve(height);
        float offsetLeft = styleGen.outlineOffsetLeft().resolve(width);
        float offsetRight = styleGen.outlineOffsetRight().resolve(width);

        if (hasDrawable) {
            // Drawable wins when both are set — same precedence CSS gives border-image over border.
            outline.draw(ctx, x - offsetLeft, y - offsetTop,
                    Math.max(0f, width + offsetLeft + offsetRight),
                    Math.max(0f, height + offsetTop + offsetBottom));
            return;
        }

        // SDF stroke form. The shader measures _BorderWidth INWARD from the shape's outer edge,
        // while a CSS outline grows OUTWARD from the offset edge — so inflate by offset+width and
        // let the inward stroke land exactly in the band between offset and offset+width.
        float insetTop = offsetTop + strokeWidth;
        float insetBottom = offsetBottom + strokeWidth;
        float insetLeft = offsetLeft + strokeWidth;
        float insetRight = offsetRight + strokeWidth;
        // Resolve radii against the element's OWN box, then expand. Resolving against the inflated
        // box instead would re-scale percentage radii and produce a visibly over-curved ring.
        //
        // The radii expansion takes one amount per axis, so asymmetric offsets use the mean of the
        // two edges on that axis. Deliberate: a rounded corner joins two edges that have been pushed
        // out by different amounts, so there is no single correct radius for it — and the case this
        // per-edge support exists for (a 9-slice sprite ring) never reaches this branch at all, since
        // a drawable outline returns above.
        CornerRadii radii = resolveCornerRadii(width, height)
                .expand((insetLeft + insetRight) * 0.5f, (insetTop + insetBottom) * 0.5f);

        int color = styleGen.outlineColor();
        // Transparent fill that keeps the stroke's RGB: the shader mixes border->fill on straight
        // (non-premultiplied) alpha, so a plain 0x00000000 fill would drag the inner anti-aliased
        // edge toward black and leave a dark fringe. Same RGB, zero alpha = a clean alpha ramp.
        CgUiRoundedRect ring = buildFillOnlyRoundedRect(radii, new ColorFill(color & 0x00FFFFFF));
        ring.setBorder(strokeWidth, color);
        ring.draw(ctx, x - insetLeft, y - insetTop,
                width + insetLeft + insetRight, height + insetTop + insetBottom);
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
            // A `display: none` subtree is unreachable IN ITS ENTIRETY, not just at its root — the
            // same rule HTML applies. focusable() only consults an element's OWN display, so without
            // this the visible children of a hidden parent stay keyboard-reachable and Tab lands on
            // something nobody can see. Found via TabView, whose inactive panes are hidden exactly
            // this way while their content stays display:flex.
            if (element.style.taffyBridge.style.display == TaffyDisplay.NONE) return false;
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
                // The window's scale, NOT identity. drawSubtree overwrites this from the live
                // PoseStack, which UIWindow.paintFrame seeds from the very same matrix — so the two
                // agree. Returning identity here (as this did) left every transform wrong by exactly
                // uiScale until the first paint, which silently broke hit-testing for anything that
                // did pointer maths before then (and permanently, in headless/layout-only use).
                if (element.attachedWindow == null) return old.identity();
                return old.set(element.attachedWindow.getRootTransform());
            }
            old.set(parent.getRuntimeCache().localToWorld.get());
            // A scroll container offsets its children here, in the transform chain itself, rather
            // than only via the PoseStack at paint time. That matters for correctness, not tidiness:
            // UIWindow.elementHitTest inverts this matrix, so input follows the scroll immediately
            // and without a paint having happened. Deriving it from the pose alone would leave
            // hit-testing a frame stale and unverifiable headlessly.
            if (!element.scrollExempt && (parent.scrollLeft != 0f || parent.scrollTop != 0f)) {
                old.translate(-parent.scrollLeft, -parent.scrollTop, 0f);
            }
            // This element's own transform, applied last so it composes inside the parent's space —
            // the same call drawSubtree makes against the PoseStack, so the matrix hit-testing
            // inverts and the matrix rendering uses are the same matrix by construction.
            element.getTransform().applyTo(old, getX(), getY(), getWidth(), getHeight(),
                    element.transformOriginPxX(), element.transformOriginPxY());
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