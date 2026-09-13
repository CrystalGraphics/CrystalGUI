package com.crystalgui.app.uibuilder.canvas;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import org.joml.Vector2f;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgraphics.platform.input.CgMouseCodes;

import com.crystalgui.app.uibuilder.document.BuilderEdit;
import com.crystalgui.app.uibuilder.document.TreeDropRules;
import com.crystalgui.app.uibuilder.document.TreeMoves;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.core.cursor.Cursor;
import com.crystalgui.core.data.Transform2D;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.widget.dnd.DragGhost;
import com.crystalgui.widget.surface.EdgePan;

import dev.vfyjxf.taffy.style.TaffyPosition;

/**
 * Dragging an <b>in-flow</b> node to another place in the tree — before or after a sibling, or into another
 * container. The out-of-flow counterpart is {@link MoveOutOfFlow}.
 *
 * <pre>{@code
 * // from the select tool's press, after the selection has been decided:
 * if (reorder.begin(pressed, rawX, rawY)) return true;
 * }</pre>
 *
 * <p>A press arms a drag at the engine's threshold, so a press that does not travel stays a click. Once it
 * travels, the carried nodes dim in their cells and a label follows the pointer; each update resolves the
 * drop ({@link DropResolver}), shows it ({@link DropIndicator}) and pans the plane near its edge
 * ({@link EdgePan}). Release commits one undo step — the moves, or copies while Alt is held — and selects
 * what landed. Escape, or a release where nothing may land, puts everything back. Nothing is written to the
 * document before the release.</p>
 *
 * <p>The carried set is the selection when the press was on a selected node or inside one, else the
 * pressed node; nodes inside another carried node travel with it. Absolutely positioned nodes and the root
 * are never carried.</p>
 */
public final class ReorderInFlow extends UIElement {

    public static final Name NAME = Name.of("reorderinflow");

    public static final String LAYER_CLASS = "__reorder-in-flow__";

    /** How a carried node is drawn in the cell it still holds. */
    public static final float CARRIED_OPACITY = 0.4f;

    private final BuilderContext ctx;

    private final UiBuilderDocument document;

    private final DragGhost ghost = new DragGhost();

    private final EdgePan pan;

    private List<UIElement> carried = List.of();

    @Nullable
    private DropResolver.Drop drop;

    private boolean activated;

    private boolean duplicating;

    private float lastRawX;
    private float lastRawY;

    public ReorderInFlow(BuilderContext ctx, UiBuilderDocument document) {
        super(NAME);
        this.ctx = ctx;
        this.document = document;
        this.pan = new EdgePan(ctx.surface());
        addClass(LAYER_CLASS);
        set(Attribute.HIT_TEST, false);
        set(Attribute.HIT_TRANSPARENT, true);
        StyleGroup.defaultPipeline(getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE).left(0f).top(0f)
                        .widthPercent(100f).heightPercent(100f));
        // IN THE TREE BEFORE A DRAG CAN PROMOTE IT. @see DragGhost
        ghost.parkIn(this);
    }

    /** Whether a press on {@code node} starts this gesture: a laid-out, in-flow document node other than the root. */
    public static boolean isReorderable(UIElement root, @Nullable UIElement node) {
        return TreeDropRules.isSource(root, node) && node.box() != null && !MoveOutOfFlow.isMovable(node);
    }

    /** What is being carried, or empty. */
    public List<UIElement> carried() {
        return carried;
    }

    /** What the last update resolved, or null. For a test. */
    @Nullable
    public DropResolver.Drop drop() {
        return drop;
    }

    /**
     * Arms the gesture from a press on {@code pressed}, at raw pointer pixels.
     *
     * @return whether one was armed — false for a node this gesture does not carry
     */
    public boolean begin(@Nullable UIElement pressed, float rawX, float rawY) {
        UIElement root = document.root();
        if (!isReorderable(root, pressed)) return false;
        List<UIElement> sources = sourcesFor(root, pressed);
        UIDocument window = document();
        if (sources.isEmpty() || window == null) return false;
        activated = false;
        carried = sources;
        // BEFORE THE DRAG STARTS, which is the moment the drag takes its ghost. @see DragGhost#follow
        ghost.follow(window, labelFor(sources, false));
        Drag.start(this, rawX, rawY, CgMouseCodes.LEFT_BUTTON, null, Drag.DEFAULT_THRESHOLD_PX,
                new Drag.Listener() {
                    @Override
                    public void onDragUpdate(float x, float y, float sx, float sy, float dx, float dy) {
                        Vector2f raw = toRaw(x, y);
                        if (!activated) activate();
                        update(raw.x, raw.y, modifiersNow());
                    }

                    @Override
                    public void onDragEnd(float x, float y) {
                        if (activated) commit();
                        finish();
                    }

                    @Override
                    public void onDragCancel() {
                        finish();
                    }
                });
        return true;
    }

    private void activate() {
        activated = true;
        UIDocument window = document();
        if (window != null) window.input().setCursorOverride(Cursor.GRABBING);
        pan.start(() -> update(lastRawX, lastRawY, modifiersNow()));
    }

    /**
     * One update at raw pointer pixels, with the modifiers passed in rather than read.
     *
     * <p>Package-private so a test can drive the resolution and the Alt state without a pointer.</p>
     */
    void update(float rawX, float rawY, int modifiers) {
        lastRawX = rawX;
        lastRawY = rawY;
        pan.pointerAt(rawX, rawY);
        // RE-APPLIED EVERY UPDATE: an override lives on a box, and a box is rebuilt whenever its subtree
        // is restructured -- which a pan or a hover highlight can cause.
        for (UIElement node : carried) {
            Box box = node.box();
            if (box != null) box.setOpacity(CARRIED_OPACITY);
        }
        boolean copy = CgModifiers.hasAlt(modifiers);
        if (copy != duplicating) {
            duplicating = copy;
            ghost.text(labelFor(carried, copy));
        }
        drop = new DropResolver(document.root(), ctx.dropIndicator()).resolve(carried, rawX, rawY);
        ctx.dropIndicator().show(drop);
        UIDocument window = document();
        if (window != null) {
            window.input().setCursorOverride(drop == null ? Cursor.NOT_ALLOWED
                    : duplicating ? Cursor.COPY : Cursor.GRABBING);
        }
    }

    /** Lands the carried nodes where the last update resolved, as one undo step, and selects what landed. */
    private void commit() {
        DropResolver.Drop at = drop;
        if (at == null) return;
        List<BuilderEdit> edits = duplicating
                ? TreeMoves.duplicate(document, at.target(), at.index(), carried)
                : TreeMoves.move(at.target(), at.index(), carried);
        if (edits.isEmpty()) return;
        document.applyAll(duplicating ? "duplicate" : "move", edits);
        List<UIElement> landed = carried;
        if (duplicating) {
            landed = new ArrayList<>(edits.size());
            for (BuilderEdit edit : edits) landed.add(edit.node());
        }
        ctx.builderSelection().replaceWith(landed);
    }

    private void finish() {
        for (UIElement node : carried) {
            Box box = node.box();
            if (box != null) box.setOpacity(null);
        }
        carried = List.of();
        drop = null;
        activated = false;
        duplicating = false;
        ctx.dropIndicator().clear();
        pan.stop();
        UIDocument window = document();
        if (window != null) window.input().setCursorOverride(null);
    }

    /** The selection when the press was on or inside a selected node, else the pressed node — outermost, carriable. */
    private List<UIElement> sourcesFor(UIElement root, UIElement pressed) {
        List<UIElement> selected = ctx.builderSelection().nodes();
        boolean withSelection = false;
        for (UIElement node : selected) {
            if (TreeDropRules.isInside(pressed, node)) {
                withSelection = true;
                break;
            }
        }
        List<UIElement> candidates = withSelection ? selected : List.of(pressed);
        List<UIElement> sources = new ArrayList<>();
        for (UIElement node : TreeMoves.outermost(candidates)) {
            if (isReorderable(root, node)) sources.add(node);
        }
        return sources;
    }

    /** {@code <button> #save}, or {@code 3 elements}; a {@code +} in front while copying. */
    public static String labelFor(List<UIElement> nodes, boolean copy) {
        String what;
        if (nodes.size() == 1) {
            UIElement node = nodes.get(0);
            // A BUILT-IN KIND BARE, as a type selector may spell it: `crystalgui:` on every label is noise.
            Name kind = node.name();
            String tag = Name.DEFAULT_NAMESPACE.equals(kind.namespace()) ? kind.local() : kind.toString();
            what = "<" + tag + ">" + (node.id().isEmpty() ? "" : " #" + node.id());
        } else {
            what = nodes.size() + " elements";
        }
        return copy ? "+ " + what : what;
    }

    private Vector2f toRaw(float x, float y) {
        Box box = box();
        return box == null ? new Vector2f(x, y) : Transform2D.apply(box.localToWorld(), x, y);
    }

    private static int modifiersNow() {
        var input = CgPlatform.input();
        return input == null ? 0 : input.getCurrentModifiers();
    }
}
