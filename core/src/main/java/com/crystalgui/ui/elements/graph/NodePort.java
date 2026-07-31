package com.crystalgui.ui.elements.graph;

import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.graph.PortDirection;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.event.DragEvent;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.input.UIDragController;
import lombok.Getter;
import org.joml.Vector2f;

import javax.annotation.Nullable;

/**
 * One connection point on a {@link GraphNode} — the dot, its label, and (for an unconnected input) the
 * inline editor its {@link PortType} supplies.
 *
 * <pre>
 *   input:   [•] A(3)  [ 0.9 ]
 *   output:            Out(3) [•]
 * </pre>
 *
 * <h3>The dot is decorative; the port is the target</h3>
 * <p>The dot is 8px, and a pointer is not. The whole port element is the hit target and the drag
 * source, which is why the label and the padding around it are part of it rather than siblings — a
 * connection you have to aim for is a connection you get wrong.</p>
 *
 * <h3>{@code :blank} means unconnected</h3>
 * <p>{@link #isBlank()} is overridden, so a stylesheet gets {@code nodeport:blank} for free — no new
 * engine concept, and the two things that depend on connection state become CSS rather than Java:
 * the hollow-versus-filled dot, and whether the inline editor shows. It is a mild stretch of CSS's
 * own {@code :blank} (an empty user input), and the closest honest fit: an unconnected input <em>is</em>
 * a field waiting for a value.</p>
 *
 * <h3>Where the wire's endpoint comes from</h3>
 * <p>{@link #dotCenter()} reads the dot's <b>live</b> layout every time it is asked. Nothing caches an
 * anchor point: a cache is only correct until the node moves, the node resizes, a port is added, or the
 * theme changes a padding — and each of those is a thing a user does routinely. The same lesson as
 * {@code resizeOriginLeft()} reading the live Taffy inset rather than a field.</p>
 */
public class NodePort extends UIElement {

    public static final String DOT_CLASS = "__dot__";
    public static final String LABEL_CLASS = "__label__";
    public static final String EDITOR_CLASS = "__editor__";
    /** On the port, so a theme can style the two sides differently without knowing about directions. */
    public static final String INPUT_CLASS = "__input__";
    public static final String OUTPUT_CLASS = "__output__";

    @Getter
    private final PortDirection direction;

    @Getter
    private final PortType type;

    private final UIElement dot = new UIElement();
    private final UIText label;

    @Nullable
    @Getter
    private final UIElement inlineEditor;

    /**
     * How many wires end here. An int rather than a boolean because an output may feed many, and
     * because {@code isBlank()} then has one definition for both directions.
     *
     * <p>Owned by {@link GraphView}, which is the only thing that knows the graph's edges — a port
     * that counted its own wires would need to be told about every connection anyway, and there would
     * then be two places that could be wrong.</p>
     */
    private int connectionCount;

    public NodePort(PortDirection direction, PortType type, String name) {
        this.direction = direction;
        this.type = type;

        addClass(direction.isInput() ? INPUT_CLASS : OUTPUT_CLASS);
        // The type's CSS hook. This is what makes the palette a stylesheet's business — including the
        // wire's colour, which NodeWireLayer reads back off the dot's computed border-color.
        addClass(type.cssClass());

        dot.addClass(DOT_CLASS);
        // The dot must never be the drop target in its own right: the port is. Hit-testing off applies
        // to the subtree, so events land on the port element and the geometry stays one box.
        dot.setHitTest(false);

        this.label = new UIText(displayLabel(name, type));
        label.addClass(LABEL_CLASS);
        label.setHitTest(false);

        this.inlineEditor = direction.isInput() ? type.createInlineEditor() : null;
        if (inlineEditor != null) inlineEditor.addClass(EDITOR_CLASS);

        // Structure, not style: an input reads dot-then-label and an output label-then-dot, which is
        // the difference between a wire arriving and a wire leaving. Reversing it in CSS would need a
        // flex-direction override per direction and would still be lying about the reading order.
        if (direction.isInput()) {
            addInternalChild(dot);
            addInternalChild(label);
            if (inlineEditor != null) addInternalChild(inlineEditor);
        } else {
            addInternalChild(label);
            addInternalChild(dot);
        }

        this.events.getGroup(MouseEvent.Down.class).attachListener((el, event) -> {
            if (!isEnabled() || event.getButtonId() != CgMouseCodes.LEFT_BUTTON) return;
            if (beginConnectionDrag(event.getPosition().x(), event.getPosition().y())) {
                // The press belongs to the wire now. Without this the node underneath starts its own
                // move-drag and the pointer drags the node it was trying to wire up.
                event.stopPropagation();
            }
        }, false, true);

        this.events.getGroup(DragEvent.Over.class).attachListener((el, event) -> {
            // Rejection is the default and acceptance is re-read every frame — so a port that stops
            // being a legal target mid-drag stops accepting, with no state to unwind.
            if (accepts(event.getPayload())) event.preventDefault();
        }, false, true);

        this.events.getGroup(DragEvent.Drop.class).attachListener((el, event) -> {
            if (!accepts(event.getPayload())) return;
            GraphView view = graphView();
            if (view != null) view.connect((NodePort) event.getPayload(), this);
        }, false, true);
    }

    /** Unity's {@code Out(3)}: the name, then the arity, unless the type has none worth printing. */
    private static String displayLabel(String name, PortType type) {
        return type.arity() > 0 ? name + "(" + type.arity() + ")" : name;
    }

    public String getName() {
        return label.getText();
    }

    /** The whole port is one control; its parts are internal. */
    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    /** Drives {@code nodeport:blank} — see the class javadoc. */
    @Override
    public boolean isBlank() {
        return connectionCount == 0;
    }

    public boolean isConnected() {
        return connectionCount > 0;
    }

    public int getConnectionCount() {
        return connectionCount;
    }

    /**
     * Called by {@link GraphView} when an edge is added or removed.
     *
     * <p>Invalidates the style match, which is what makes {@code :blank} live: a pseudo-class is only
     * re-evaluated when something says the element's identity changed. Without it the dot stays hollow
     * after connecting and the inline editor stays visible under a wire — both of which look like
     * rendering bugs and are really a missing invalidation. Same three-call idiom as
     * {@code Checkbox.setChecked}.</p>
     */
    void setConnectionCount(int count) {
        int clamped = Math.max(0, count);
        if (this.connectionCount == clamped) return;
        boolean blankChanged = (this.connectionCount == 0) != (clamped == 0);
        this.connectionCount = clamped;
        if (blankChanged) {
            onStyleChanged();
            invalidateStyleMatch();
            notifyStateChanged();
        }
    }

    /** The dot's centre, in the plane's coordinate space — i.e. the same space a paint call inside the
     * canvas uses, and the space {@link UIElement#screenToLocal} reports in. Read live; never cached. */
    public Vector2f dotCenter() {
        var cache = dot.getRuntimeCache();
        return new Vector2f(cache.getX() + cache.getWidth() * 0.5f,
                cache.getY() + cache.getHeight() * 0.5f);
    }

    /** The colour of this port's type, taken from the dot's computed {@code border-color}.
     *
     * <p>This is the seam that keeps Unity's per-type palette in CSS. A wire is drawn by
     * {@code CgCurveRenderer}, which needs an ARGB int, so <em>something</em> has to hand it a number —
     * reading it back out of the cascade means the number's source is still a stylesheet.
     * {@code border-color} rather than {@code background-color} because the dot is hollow while
     * unconnected, so its fill is transparent exactly when a theme author would be surprised to find
     * the wire colour missing.</p> */
    public int typeColor() {
        return dot.getStyle().getGeneralGroup().borderColor();
    }

    /** The node this port belongs to, or {@code null} if it is not on one. */
    @Nullable
    public GraphNode node() {
        for (UIElement e = getParent(); e != null; e = e.getParent()) {
            if (e instanceof GraphNode node) return node;
        }
        return null;
    }

    @Nullable
    GraphView graphView() {
        for (UIElement e = getParent(); e != null; e = e.getParent()) {
            if (e instanceof GraphView view) return view;
        }
        return null;
    }

    private boolean accepts(@Nullable Object payload) {
        if (!(payload instanceof NodePort other)) return false;
        GraphView view = graphView();
        return view != null && view.canConnect(other, this);
    }

    /**
     * Starts a wire drag from this port. Returns whether one actually began.
     *
     * <p>The drag source is <b>this port</b>, and that is safe here in a way it would not be for a pan:
     * {@code UIDragController} converts every coordinate through the source's own transform, so a
     * source whose transform is being changed by the drag feeds itself garbage. A wire drag changes
     * nothing about the plane, so the port's own space is stable — and it is the convenient one,
     * because it is the space the wire is drawn in.</p>
     */
    private boolean beginConnectionDrag(float rawX, float rawY) {
        GraphView view = graphView();
        UIWindow window = getAttachedWindow();
        if (view == null || window == null) return false;

        view.beginPendingWire(this);
        // Snapshotted so onDragEnd can tell a wire that landed from one that did not: a drop on a port
        // fires DragEvent.Drop (and therefore connects) before the drag ends.
        final int startingConnections = getConnectionCount();
        window.getInputHandler().getDragController().startDrag(this, rawX, rawY, this,
                new UIDragController.DragListener() {
                    @Override
                    public void onDragUpdate(float mx, float my, float sx, float sy, float dx, float dy) {
                        view.updatePendingWire(mx, my);
                    }

                    @Override
                    public void onDragEnd(float mx, float my) {
                        view.endPendingWire(NodePort.this, mx, my,
                                getConnectionCount() > startingConnections);
                    }

                    @Override
                    public void onDragCancel() {
                        // Escape is a cancel, never an invitation to create something.
                        view.endPendingWire(NodePort.this, 0f, 0f, true);
                    }
                });
        return true;
    }
}
