package com.crystalgui;

import com.crystalgui.layout.LayoutStyle;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.texture.CgUiDrawable;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import dev.vfyjxf.taffy.style.TaffyStyle;
import dev.vfyjxf.taffy.tree.NodeId;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Base DOM node — every CgGui component extends this (a general-purpose, styleable, extensible container, conceptually
 * like an HTML {@code <div>}).
 *
 * <h3>Layout</h3>
 * <p>Every element owns a real {@link TaffyStyle} ({@code dev.vfyjxf.taffy} 1.1.4).
 * Configure it via {@link #layout(Consumer)}. {@link #getPositionX()}/{@link #getPositionY()}/
 * {@link #getSizeWidth()}/{@link #getSizeHeight()} return whatever {@link UiRuntime}'s last
 * {@code TaffyTree.computeLayout()} pass wrote via {@link #setBounds} — those are absolute
 * screen coordinates (Taffy's own {@code Layout.location()} is parent-relative; {@link UiRuntime}
 * accumulates the offset while walking the tree). {@link #setBounds} remains public for
 * elements not attached to any {@link UiRuntime} (e.g. unit tests, or manual placement).</p>
 *
 * <p><b>Known limitation:</b> the Taffy-side tree is built once when a {@link UiRuntime}
 * first attaches to a {@link Ui}, by walking whatever tree exists at that moment. Adding/
 * removing children via {@link #addChild}/{@link #removeChild} <i>after</i> attachment does
 * NOT yet incrementally sync into the live {@code TaffyTree}
 * Call {@code UiRuntime.rebuildTree()} after structural changes for now.</p>
 *
 * <p>Also deferred to a later phase (see CgGui phase plan): {@code transform-2d}, {@code clip}
 * (scissor/mask), input/events, styles/classes-as-cascade (only plain class tags exist here,
 * no selector matching yet).</p>
 */
public class UIElement {

    private String id = "";
    private final Set<String> classes = new LinkedHashSet<>();

    private UIElement parent;
    private final List<UIElement> children = new ArrayList<>();
    private final List<UIElement> childrenView = Collections.unmodifiableList(children);

    private boolean visible = true;
    private boolean active = true;

    // Absolute screen-space geometry, written by UiRuntime's layout pass (or manually
    // via setBounds for unattached elements).
    private float x, y, width, height;

    // ── Layout (Taffy) ───────────────────────────────────────────────────────
    private final TaffyStyle taffyStyle = new TaffyStyle();
    /** Set by UiRuntime once this element is attached to a live TaffyTree; null until then. */
    NodeId taffyNodeId;
    /** Set by UiRuntime once this element is attached; used so layout(...) can mark the node dirty. */
    UiRuntime attachedRuntime;

    // ── Generic box model ─────────────────────
    private CgUiDrawable background;
    private CgUiDrawable overlay;
    private float opacity = 1f;
    private int colorTint = 0xFFFFFFFF;
    private int zIndex = 0;

    // ── Tree structure ───────────────────────────────────────────────────────

    public UIElement getParent() {
        return parent;
    }

    public List<UIElement> getChildren() {
        return childrenView;
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

    // ── Id / classes ──────────────────────────────────────────────────────────

    public String getId() {
        return id;
    }

    public UIElement setId(String id) {
        this.id = id == null ? "" : id;
        return this;
    }

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

    // ── State ────────────────────────────────────────────────────────────────

    public boolean isVisible() {
        return visible;
    }

    public UIElement setVisible(boolean visible) {
        this.visible = visible;
        return this;
    }

    public boolean isActive() {
        return active;
    }

    public UIElement setActive(boolean active) {
        this.active = active;
        return this;
    }

    // ── Geometry (placeholder — see class javadoc) ──────────────────────────

    public float getPositionX() {
        return x;
    }

    public float getPositionY() {
        return y;
    }

    public float getSizeWidth() {
        return width;
    }

    public float getSizeHeight() {
        return height;
    }

    public UIElement setBounds(float x, float y, float width, float height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        return this;
    }

    // ── Layout ───────────────────────────────────────────────────────────────

    /**
     * Configures this element's {@link TaffyStyle} via the fluent {@link LayoutStyle}
     * wrapper. If this element is already attached to a {@link UiRuntime},
     * marks the Taffy node dirty so the next {@code computeLayout()} pass picks up the change.
     */
    public UIElement layout(Consumer<LayoutStyle> configurator) {
        configurator.accept(new LayoutStyle(taffyStyle, this::markLayoutDirty));
        return this;
    }

    /** The raw Taffy style backing this element, for direct field access beyond what {@link LayoutStyle} exposes. */
    public TaffyStyle getTaffyStyle() {
        return taffyStyle;
    }

    private void markLayoutDirty() {
        if (attachedRuntime != null && taffyNodeId != null) {
            attachedRuntime.markDirty(taffyNodeId);
        }
    }

    // ── Box model ────────────────────────────────────────────────────────────

    public UIElement setBackground(CgUiDrawable background) {
        this.background = background;
        return this;
    }

    public UIElement setOverlay(CgUiDrawable overlay) {
        this.overlay = overlay;
        return this;
    }

    public UIElement setOpacity(float opacity) {
        this.opacity = opacity;
        return this;
    }

    /** ARGB tint multiplied into background/overlay draws (and inherited visually by nothing else */
    public UIElement setColor(int argb) {
        this.colorTint = argb;
        return this;
    }

    public int getZIndex() {
        return zIndex;
    }

    public UIElement setZIndex(int zIndex) {
        this.zIndex = zIndex;
        return this;
    }

    // ── Paint ────────────────────────────────────────────────────────────────

    /**
     * Paints this element's background, then recurses into children (z-index-sorted,
     * DOM order as tiebreak), then paints this element's overlay. Fully synchronous —
     * every call in this chain issues real GPU draw calls immediately; nothing here
     * defers or accumulates work for later replay.
     */
    public final void drawSubtree(CgUiPaintContext ctx) {
        if (taffyStyle.display == TaffyDisplay.NONE || !isVisible() || opacity == 0) {
            return;
        }

        var zIndex = getZIndex();


        paintSelf(ctx);

        if (!children.isEmpty()) {
            List<UIElement> sorted = new ArrayList<>(children);
            sorted.sort(Comparator.comparingInt(UIElement::getZIndex));
            for (UIElement child : sorted) {
                child.drawSubtree(ctx);
            }
        }

        Matrix4f localToWorld = ctx.getPoseStack().last().pose();
        Matrix4f worldToLocal = localToWorld.invert(new Matrix4f());
        System.out.printf("%.2f, %.2f\n", ctx.mouseX, ctx.mouseY);
        Vector4f v = new Vector4f();
        v.set(ctx.mouseX, ctx.mouseY, 0, 1.0f);
        worldToLocal.transform(v);
        final float mouseX = v.x(), mouseY = v.y();

        if (mouseX >= x && mouseX <= x+width && mouseY >= y && mouseY <= y+height)
            paintOverlay(ctx);
    }

    /** Override for custom drawing beyond the generic box model (e.g. text glyphs, item icons). Called before children paint. */
    protected void paintSelf(CgUiPaintContext ctx) {
        if (background != null) {
            background.draw(ctx, x, y, width, height);
        }
    }

    /** Override for custom drawing that must appear above children. Called after children paint. */
    protected void paintOverlay(CgUiPaintContext ctx) {
        if (overlay != null) {
            overlay.draw(ctx, x, y, width, height);
        }
    }

    private int tintWithOpacity() {
        int a = (int) (((colorTint >>> 24) & 0xFF) * opacity);
        return (a << 24) | (colorTint & 0x00FFFFFF);
    }
}
