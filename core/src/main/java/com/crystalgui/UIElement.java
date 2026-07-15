package com.crystalgui;

import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.ElementStyle;
import com.crystalgui.style.LayoutGroup;
import com.crystalgui.texture.CgUiDrawable;
import dev.vfyjxf.taffy.style.TaffyStyle;
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
@Accessors(chain = true)
public class UIElement {


    @Getter
    private final ElementStyle style = new ElementStyle(this);
    protected NodeId taffyNodeId;
    /** Set by UiRuntime once this element is attached; used so layout(...) can mark the node dirty. */
    @Getter
    @Nullable
    UiRuntime attachedRuntime;

    @Nullable
    private UIElement parent;
    @Getter
    private final List<UIElement> children = new ArrayList<>();

    @Getter
    private String id = "";

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

    // Absolute screen-space geometry, written by UiRuntime's layout pass (or manually
    // via setBounds for unattached elements).
    private float x, y, width, height;

    @Getter @Setter
    public CgUiDrawable background, overlay;


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

    public UIElement layout(Consumer<LayoutGroup> configurator) {
        configurator.accept(this.getStyle().getLayoutGroup());
        return this;
    }

    private void markLayoutDirty() {
        if (attachedRuntime != null && taffyNodeId != null) {
            attachedRuntime.markDirty(taffyNodeId);
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
//        if ( == TaffyDisplay.NONE || !isVisible() || opacity == 0) {
//            return;
//        }
//
//        var zIndex = getZIndex();
//        if (zIndex != 0) {
//            ctx.getPoseStack().pushPose();
//            ctx.getPoseStack().translate(0, 0, zIndex);
//        }



        paintSelf(ctx);

        if (!children.isEmpty()) {
            List<UIElement> sorted = new ArrayList<>(children);
//            sorted.sort(Comparator.comparingInt(UIElement::getZIndex));
            for (UIElement child : sorted) {
                child.drawSubtree(ctx);
            }
        }


//        if (zIndex != 0) {
//            ctx.getPoseStack().popPose();
//        }
    }

    /** Override for custom drawing beyond the generic box model (e.g. text glyphs, item icons). Called before children paint. */
    protected void paintSelf(CgUiPaintContext ctx) {
        if (background != null) {
            background.draw(ctx, x, y, width, height);
        }
    }

    /** Override for custom drawing that must appear above children. Called after children paint. */
    protected void paintOverlay(CgUiPaintContext ctx) {
        Matrix4f localToWorld = ctx.getPoseStack().last().pose();
        Matrix4f worldToLocal = localToWorld.invert(new Matrix4f());
        Vector4f v = new Vector4f();
        v.set(ctx.mouseX, ctx.mouseY, 0, 1.0f);
        worldToLocal.transform(v);
        final float mouseX = v.x(), mouseY = v.y();

        if (mouseX >= x && mouseX <= x+width && mouseY >= y && mouseY <= y+height)
            paintOverlay(ctx);
        if (overlay != null) {
            overlay.draw(ctx, x, y, width, height);
        }
    }


    public void onStyleChanged() {
        // no-op.
    }

    @Nullable
    public TaffyTree getTaffyTree() {
        return attachedRuntime == null ? null : attachedRuntime.getTaffyTree();
    }
    public void markTreeDirty() {
        var taffyTree = getTaffyTree();
        if (taffyTree != null) {
            taffyTree.markDirty(taffyNodeId);
        }
    }

}
