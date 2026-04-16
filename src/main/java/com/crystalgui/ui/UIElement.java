package com.crystalgui.ui;

import dev.vfyjxf.taffy.style.TaffyStyle;
import dev.vfyjxf.taffy.util.MeasureFunc;
import com.crystalgui.core.event.UiEvent;
import com.crystalgui.core.event.UiEventListener;
import com.crystalgui.core.event.UiEventType;
import com.crystalgui.core.input.FocusPolicy;
import com.crystalgui.core.layout.LayoutNodeState;
import com.crystalgui.core.render.CgUiPaintContext;
import com.crystalgui.core.signal.ConnectionGroup;
import lombok.Getter;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class UIElement implements CgUiDrawable {

    @Getter
    private final LayoutNodeState layoutState = new LayoutNodeState();
    @Getter
    private final TaffyStyle layoutStyle = new TaffyStyle();

    /** Connection group for lifecycle-safe signal cleanup on detach. */
    @Getter
    private final ConnectionGroup connections = new ConnectionGroup();

    @Getter @Nullable
    private String id;
    private final Set<String> styleClasses = new LinkedHashSet<>();

    @Getter
    private boolean visible = true;
    @Getter
    private boolean enabled = true;
    @Getter
    private boolean hitTestVisible = true;

    @Getter
    private boolean layoutDirty = true;
    @Getter
    private boolean styleDirty = true;
    @Getter
    private boolean renderDirty = true;

    /** Focus policy controlling how this element participates in focus navigation. */
    @Getter
    private FocusPolicy focusPolicy = FocusPolicy.NONE;

    @Getter @Nullable
    private UIContainer container;

    @Getter @Nullable
    private UIElement parent;
    private final List<UIElement> children = new ArrayList<UIElement>();
    private final Map<UiEventType, List<UiEventListener>> bubbleListeners = new EnumMap<UiEventType, List<UiEventListener>>(UiEventType.class);
    private final Map<UiEventType, List<UiEventListener>> captureListeners = new EnumMap<UiEventType, List<UiEventListener>>(UiEventType.class);

    public List<UIElement> getChildren() {
        return Collections.unmodifiableList(children);
    }

    public UIElement setId(@Nullable String id) {
        this.id = id;
        markStyleDirty();
        return this;
    }

    public Set<String> getStyleClasses() {
        return Collections.unmodifiableSet(styleClasses);
    }

    public UIElement addStyleClass(String styleClass) {
        if (styleClass == null || styleClass.isEmpty()) {
            throw new IllegalArgumentException("styleClass must not be null or empty");
        }
        if (styleClasses.add(styleClass)) {
            markStyleDirty();
        }
        return this;
    }

    public UIElement removeStyleClass(String styleClass) {
        if (styleClasses.remove(styleClass)) {
            markStyleDirty();
        }
        return this;
    }

    public UIElement setVisible(boolean visible) {
        if (this.visible != visible) {
            this.visible = visible;
            markRenderDirty();
        }
        return this;
    }

    public UIElement setEnabled(boolean enabled) {
        if (this.enabled != enabled) {
            this.enabled = enabled;
            markRenderDirty();
        }
        return this;
    }

    public UIElement setHitTestVisible(boolean hitTestVisible) {
        this.hitTestVisible = hitTestVisible;
        return this;
    }

    public UIElement setFocusPolicy(FocusPolicy focusPolicy) {
        if (focusPolicy == null) throw new IllegalArgumentException("focusPolicy must not be null");
        this.focusPolicy = focusPolicy;
        return this;
    }

    public boolean isAttached() {
        return container != null;
    }

    public boolean containsPoint(float x, float y) {
        return visible && hitTestVisible && layoutState.getLayoutBox().contains(x, y);
    }

    /**
     * Returns the measure function for this element, if it provides intrinsic sizing.
     * Override in leaf elements like {@code UiLabel} that need measured layout.
     *
     * @return a MeasureFunc, or null if this element does not provide intrinsic sizing
     */
    @Nullable
    public MeasureFunc getMeasureFunc() {
        return null;
    }

    // ── Event listener registration ─────────────────────────────────────

    //TODO: use UIEventType.bubbles for default instead of false
    public UIElement addEventListener(UiEventType eventType, UiEventListener listener) {
        return addEventListener(eventType, listener, false);
    }

    public UIElement addEventListener(UiEventType eventType, UiEventListener listener, boolean useCapture) {
        if (eventType == null) {
            throw new IllegalArgumentException("eventType must not be null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        Map<UiEventType, List<UiEventListener>> listeners = useCapture ? captureListeners : bubbleListeners;
        List<UiEventListener> typedListeners = listeners.get(eventType);
        if (typedListeners == null) {
            typedListeners = new ArrayList<UiEventListener>();
            listeners.put(eventType, typedListeners);
        }
        typedListeners.add(listener);
        return this;
    }

    public List<UiEventListener> getCaptureListeners(UiEventType eventType) {
        return getListeners(captureListeners, eventType);
    }

    public List<UiEventListener> getBubbleListeners(UiEventType eventType) {
        return getListeners(bubbleListeners, eventType);
    }

    // ── Tree manipulation ───────────────────────────────────────────────

    public UIElement addChild(UIElement child) {
        return addChild(children.size(), child);
    }

    public UIElement addChild(int index, UIElement child) {
        if (child == null) {
            throw new IllegalArgumentException("child must not be null");
        }
        if (child == this) {
            throw new IllegalArgumentException("An element cannot be added as a child of itself");
        }
        if (child.parent != null) {
            throw new IllegalStateException("Child already has a parent");
        }
        if (index < 0 || index > children.size()) {
            throw new IndexOutOfBoundsException("index: " + index + ", size: " + children.size());
        }

        children.add(index, child);
        child.parent = this;

        if (container != null) {
            child.attachToContainer(container);
            container.getLayoutContext().attachSubtree(child);
            container.getLayoutContext().syncChildren(this);
        }

        markLayoutDirty();
        return this;
    }

    public UIElement removeChild(UIElement child) {
        if (child == null) {
            throw new IllegalArgumentException("child must not be null");
        }
        if (!children.remove(child)) {
            return this;
        }

        if (container != null) {
            container.getLayoutContext().detachSubtree(child);
            child.detachFromContainer(container);
            container.getLayoutContext().syncChildren(this);
        }

        child.parent = null;
        markLayoutDirty();
        return this;
    }

    public UIElement removeFromParent() {
        if (parent != null) {
            parent.removeChild(this);
        }
        return this;
    }

    // ── Dirty flags ─────────────────────────────────────────────────────

    public void markLayoutDirty() {
        layoutDirty = true;
        layoutState.markLayoutDirty();

        if (container != null) {
            container.getLayoutContext().markDirty(this);
        }
        if (parent != null) {
            parent.markLayoutDirty();
        }
    }

    public void markStyleDirty() {
        styleDirty = true;
        markLayoutDirty();
    }

    public void markRenderDirty() {
        renderDirty = true;
    }

    public void clearDirtyFlags() {
        layoutDirty = false;
        styleDirty = false;
        renderDirty = false;
        layoutState.clearLayoutDirty();
    }

    // ── Drawable integration (plan §12.6) ─────────────────────────────

    /**
     * Default no-op draw implementation for the draw-list path.
     * Subclasses that produce visual output override this to paint into
     * the paint context's draw list.
     */
    @Override
    public void draw(CgUiPaintContext ctx) {
        // No-op by default — override in concrete visual element subclasses
    }

    /**
     * Recursively draws this element and all visible children in document order
     * using the draw-list paint context.
     *
     * <p>This is the render traversal entry point called by {@link UIContainer}.
     * The traversal skips invisible elements and their entire subtrees.</p>
     *
     * @param ctx the UI paint context for this frame
     */
    public void drawSubtree(CgUiPaintContext ctx) {
        if (!visible) {
            return;
        }
        draw(ctx);
        for (UIElement child : children) {
            child.drawSubtree(ctx);
        }
    }

    // ── Container lifecycle ─────────────────────────────────────────────

    void attachToContainer(UIContainer container) {
        if (this.container == container) {
            return;
        }
        if (this.container != null && this.container != container) {
            throw new IllegalStateException("Element is already attached to another UIContainer");
        }

        this.container = container;
        onAttached(container);
        for (UIElement child : children) {
            child.attachToContainer(container);
        }
    }

    /**
     * Detaches this element from its container. Connection group is cleaned
     * up unconditionally AFTER onDetached — subclasses cannot accidentally
     * skip cleanup by forgetting super.onDetached().
     */
    void detachFromContainer(UIContainer container) {
        if (this.container != container) {
            return;
        }
        for (UIElement child : children) {
            child.detachFromContainer(container);
        }
        onDetached(container);
        // Unconditional connection cleanup after subclass hook
        connections.disconnectAll();
        this.container = null;
    }

    protected void onAttached(UIContainer container) {
    }

    protected void onDetached(UIContainer container) {
    }

    // ── Event dispatch helpers ───────────────────────────────────────────

    /**
     * Invokes capture listeners for the event, using a snapshot of the listener
     * list to protect against concurrent modification during dispatch.
     */
    public void invokeCaptureListeners(UiEvent event) {
        invokeListeners(getCaptureListeners(event.getType()), event);
    }

    /**
     * Invokes bubble listeners for the event, using a snapshot of the listener
     * list to protect against concurrent modification during dispatch.
     */
    public void invokeBubbleListeners(UiEvent event) {
        invokeListeners(getBubbleListeners(event.getType()), event);
    }

    private List<UiEventListener> getListeners(Map<UiEventType, List<UiEventListener>> listeners, UiEventType eventType) {
        List<UiEventListener> typedListeners = listeners.get(eventType);
        if (typedListeners == null || typedListeners.isEmpty()) {
            return Collections.emptyList();
        }
        // Return a snapshot to make dispatch safe against listener mutation
        return new ArrayList<UiEventListener>(typedListeners);
    }

    private void invokeListeners(List<UiEventListener> listeners, UiEvent event) {
        for (int i = 0, n = listeners.size(); i < n; i++) {
            if (event.isImmediatePropagationStopped()) {
                return;
            }
            listeners.get(i).handle(event);
            if (event.isPropagationStopped()) {
                return;
            }
        }
    }

    // ── Document-order traversal helper ─────────────────────────────────

    /**
     * Collects all elements in this subtree in document order (depth-first pre-order).
     *
     * @param out the list to append elements to
     */
    public void collectSubtreeDocumentOrder(List<UIElement> out) {
        out.add(this);
        for (UIElement child : children) {
            child.collectSubtreeDocumentOrder(out);
        }
    }

    /**
     * Finds the first element with the given id in this subtree (depth-first).
     *
     * @param targetId the id to search for
     * @return the element, or null if not found
     */
    @Nullable
    public UIElement findById(String targetId) {
        if (targetId == null) return null;
        if (targetId.equals(this.id)) return this;
        for (UIElement child : children) {
            UIElement found = child.findById(targetId);
            if (found != null) return found;
        }
        return null;
    }
}
