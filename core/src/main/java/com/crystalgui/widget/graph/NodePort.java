package com.crystalgui.widget.graph;

import com.crystalgui.core.data.Transform2D;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.ui.box.Box;
import com.crystalgui.graph.port.PortType;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.graph.PortDirection;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.ui.event.DragEvent;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.input.UIDragController;
import lombok.Getter;
import org.joml.Vector2f;

import javax.annotation.Nullable;

/**
 * One connection point on a {@link GraphNode} — the dot and its label. For an unconnected input whose
 * {@link PortType} supplies one, a default-value editor exists too, but it is not a child of this
 * element — see {@link #getDefaultEditor()}.
 *
 * <pre>
 *   [ 0.9 ]╴╴ [•] A(3)
 *                       Out(3) [•]
 * </pre>
 *
 * <h3>The dot is decorative; the port is the target</h3>
 * <p>The dot is 8px, and a pointer is not. The whole port element is the hit target and the drag
 * source, which is why the label and the padding around it are part of it rather than siblings — a
 * connection you have to aim for is a connection you get wrong.</p>
 *
 * <h3>{@code :blank} means unconnected</h3>
 * <p>{@link #isBlank()} is overridden, so a stylesheet gets {@code nodeport:blank} for free — no new
 * engine concept. The hollow-versus-filled dot is CSS reading it directly; whether the default editor is
 * showing is {@link GraphView} reading it to decide whether the floating widget belongs on the plane at
 * all — see {@link #getDefaultEditor()} and {@link PortDefaultEditor}. It is a mild stretch of CSS's own
 * {@code :blank} (an empty user input), and the closest honest fit: an unconnected input <em>is</em> a
 * field waiting for a value.</p>
 *
 * <h3>Where the wire's endpoint comes from</h3>
 * <p>{@link #dotCenter()} reads the dot's <b>live</b> layout every time it is asked. Nothing caches an
 * anchor point: a cache is only correct until the node moves, the node resizes, a port is added, or the
 * theme changes a padding — and each of those is a thing a user does routinely. The same lesson as
 * {@code resizeOriginLeft()} reading the live Taffy inset rather than a field.</p>
 */
public class NodePort extends UINode {

    /**
     * This widget's kind.
     *
     * <p>Declared here rather than in a vocabulary class, and declared AT ALL because a subclass
     * inherits its parent's kind unless it is given its own: without this, NodePort reports
     * {@code crystalgui:element} (or its supertype's) and every rule the sheets write for
     * {@code nodeport} matches nothing at all — no background, no border, an unstyled widget that
     * reads as one that was never built.</p>
     */
    public static final Name NAME = Name.of("nodeport");

    public static final String DOT_CLASS = "__dot__";
    public static final String LABEL_CLASS = "__label__";
    /** {@link PortDefaultEditor}'s box — the rounded, panel-toned frame holding the axis label and the
     * bare control — see {@link #getDefaultEditor()}. */
    public static final String EDITOR_CLASS = "__editor__";
    /** {@link PortDefaultEditor}'s axis prefix — {@code "X"}, {@code "A"} — sitting plain (no field
     * chrome of its own) to the left of the control, inside the box carrying {@link #EDITOR_CLASS}. */
    public static final String EDITOR_LABEL_CLASS = "__editor-label__";
    /** {@link PortDefaultEditor}'s dot — see that class's own javadoc for its full construction (three
     * concentric layers) and for why it is never a descendant of the {@link #EDITOR_CLASS} box despite
     * reading as attached to it. */
    public static final String EDITOR_DOT_CLASS = "__editor-dot__";
    /** The middle, neutral-grey ring inside {@link #EDITOR_DOT_CLASS} — see {@link PortDefaultEditor}. */
    public static final String EDITOR_DOT_RING_CLASS = "__editor-dot-ring__";
    /** The coloured core at the centre of {@link #EDITOR_DOT_RING_CLASS} — see {@link PortDefaultEditor}.
     * Coloured by the port's own type in Java, same as {@link #DOT_CLASS} and the wire itself. */
    public static final String EDITOR_DOT_CORE_CLASS = "__editor-dot-core__";
    /** The small coloured core centred inside {@link #DOT_CLASS} itself — this port's own dot, not the
     * floating editor's. Unlike {@link #EDITOR_DOT_CORE_CLASS}, this one is coloured entirely by CSS
     * (via the port's own {@code type-*} class, already an ancestor) rather than by Java: {@link
     * #EDITOR_DOT_CLASS} lives outside the type-classed subtree (see {@link PortDefaultEditor}'s own
     * javadoc for why), but this dot never leaves it. */
    public static final String DOT_CORE_CLASS = "__dot-core__";
    /** On the port, so a theme can style the two sides differently without knowing about directions. */
    public static final String INPUT_CLASS = "__input__";
    public static final String OUTPUT_CLASS = "__output__";

    @Getter
    private final PortDirection direction;

    @Getter
    private final PortType type;

    private final UINode dot = new UINode();
    private final UIText label;

    /**
     * The control shown while this input is unconnected — Unity's little floating {@code X 0} field.
     *
     * <p><b>Stored here, but never mounted here, and never the whole visible widget.</b> Unity does not
     * draw this inside the node at all: it floats outside the node's left edge, labelled with the port's
     * own name and joined to a small dot by a connecting stub — see {@link PortDefaultEditor}, which
     * wraps whatever this getter returns in that presentation. A port only owns the bare editable control
     * and the connection state that decides whether it should be showing; everything about how it is
     * presented is {@link GraphView}'s job (via {@code PortDefaultEditor}), because only the view knows
     * the plane's world space and only the view ticks every frame.</p>
     */
    @Nullable
    @Getter
    private UINode defaultEditor;

    /**
     * Fires whenever {@link #setDefaultEditor} actually changes which control is stored — never on the
     * no-op case. {@link GraphView} listens from the moment it first sees this port (not from the moment
     * it first sees a NON-NULL editor) so it can rebuild a live {@link PortDefaultEditor} when the control
     * underneath it is swapped out from under it.
     *
     * <p><b>Why this has to exist at all.</b> A port is born with no editor and
     * {@link com.crystalgui.ui.elements.graph.NodeFieldBinder#attach} gives it the real,
     * document-declared one <em>later</em>, on whatever tick the node's fields get bound. For a node built
     * at scene-construction time that happens synchronously, before anything is watching, and is invisible.
     * For a node added later — Space's create menu, mid-session — the swap happens lazily inside
     * {@code ShaderGraphPreviews.tickFrame()}, a {@code Animation.Hook} registered on the same
     * hash-ordered set as {@code GraphView} itself. Without this signal, {@code GraphView}'s own discovery
     * only ever looks at the port ONCE, and if its tick happened to run first, it would snapshot the
     * throwaway generic control into a {@code PortDefaultEditor} forever — mounted, hit-testable, and
     * never updated, while the real control silently took over this getter and was never shown at all.
     * Reproduced as exactly that: a floating vector editor frozen at its very first (pre-layout, 0×0)
     * position because the {@code PortDefaultEditor} wrapping it was built one tick before the box's true
     * content ever existed, and nothing ever told it to rebuild.</p>
     */
    public final Signal.Action onDefaultEditorChanged = new Signal.Action();

    /**
     * Replaces this port's default-value editor.
     *
     * <p>The slot itself is not new: a {@link PortType} may supply a default editor at construction, and
     * {@link #isBlank()} already decides when it should be showing. This is how a
     * <b>document-declared</b> {@link com.crystalgui.graph.NodeField} takes that slot instead, so the
     * value the user types is the one stored on the node rather than something the port type invented.</p>
     *
     * <p>Outputs are refused: a value flows <em>out</em> of one, so there is nothing to type.</p>
     */
    public NodePort setDefaultEditor(@Nullable UINode editor) {
        if (!direction.isInput()) return this;
        if (defaultEditor == editor) return this;
        defaultEditor = editor;
        onDefaultEditorChanged.emit();
        return this;
    }

    /**
     * How many wires end here. An int rather than a boolean because an output may feed many, and
     * because {@code isBlank()} then has one definition for both directions.
     *
     * <p>Owned by {@link GraphView}, which is the only thing that knows the graph's edges — a port
     * that counted its own wires would need to be told about every connection anyway, and there would
     * then be two places that could be wrong.</p>
     */
    private int connectionCount;

    /** Fires whenever {@link #isBlank()} flips — {@link GraphNode} listens, to know when a collapsed
     * column has nothing left in it to show. See {@link #setConnectionCount}. */
    public final Signal.Action onBlankChanged = new Signal.Action();

    /**
     * The document's {@code PortSpec.portId} for this port — what an {@code EdgeData} points at.
     *
     * <p>Distinct from {@link #getName()}, which is the <em>drawn</em> label and carries the arity
     * ({@code "Out(3)"}). An edge that referenced the label would break the moment a type's arity
     * changed, or a theme decided to render the name differently.</p>
     */
    @Getter
    private final String portId;

    /** A concrete width resolved for a port whose TYPE has none — see {@link #setResolvedArity}.
     * {@code 0} means "no override", so the type's own arity is shown. */
    private int resolvedArity;

    /** The {@code type-*} class currently styling this port, when it is not the declared type's own —
     * see {@link #setResolvedTypeClass}. Null means "no override". */
    @Nullable
    private String resolvedTypeClass;

    public NodePort(PortDirection direction, PortType type, String name) {
        super(NAME);
        this.direction = direction;
        this.type = type;
        this.portId = name;

        addClass(direction.isInput() ? INPUT_CLASS : OUTPUT_CLASS);
        // The type's CSS hook. This is what makes the palette a stylesheet's business — including the
        // wire's colour, which NodeWireLayer reads back off the dot's computed border-color.
        addClass(type.cssClass());

        dot.addClass(DOT_CLASS);
        // The dot must never be the drop target in its own right: the port is. Hit-testing off applies
        // to the subtree, so events land on the port element and the geometry stays one box.
        dot.setHitTest(false);
        // The ring-gap-core "target" look Unity's own port dots have — see PortDefaultEditor's own
        // three-layer dot for the same construction. There it takes three flat elements because it sits
        // outside the type-classed subtree; here the outer ring is DOT_CLASS's own border (still what
        // typeColor() reads), so only the core needs a child at all — the "gap" is just DOT_CLASS's own
        // background showing between its border and this core.
        UINode core = new UINode();
        core.addClass(DOT_CORE_CLASS);
        core.setHitTest(false);
        dot.append(core);

        this.label = new UIText(displayLabel(humanise(name), type.arityLabel()));
        label.addClass(LABEL_CLASS);
        label.setHitTest(false);
        // Same reasoning as `GraphNode.title`'s identical call: this label has to drive its column's
        // (and therefore the node's own) growth, and the auto-detect heuristic races the ancestor
        // chain's first, not-yet-converged layout pass — see `UIText.forceSelfSizeWidth()`.

        // Not mounted here — see the field javadoc. GraphView discovers it and places it on the plane.

        // Structure, not style: an input reads dot-then-label and an output label-then-dot, which is
        // the difference between a wire arriving and a wire leaving. Reversing it in CSS would need a
        // flex-direction override per direction and would still be lying about the reading order.
        if (direction.isInput()) {
            append(dot);
            append(label);
        } else {
            append(label);
            append(dot);
        }

        this.events.getGroup(MouseEvent.Down.class).attachListener((el, event) -> {
            if (!isEnabled() || event.getButtonId() != CgMouseCodes.LEFT_BUTTON) return;
            // The default editor is no longer a descendant of this port — it floats on the plane as its
            // own element — so a press on it never reaches here at all, and the click-vs-drag conflict
            // that used to require a target check does not exist any more.
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

    /**
     * A port id as a reader sees it — {@code RadialScale} is drawn {@code Radial Scale}.
     *
     * <p>The id itself is untouched: it is a GLSL template key ({@code {RadialScale}}), the document's
     * {@code PortSpec.portId}, and what an edge points at, so it has to stay a single identifier. Only
     * the LABEL is spaced, which is the split {@link #getName()} already documents against
     * {@link #getPortId()}.</p>
     *
     * <p>Derived rather than declared, so 93 built-in nodes did not each have to carry a second string
     * that says the same thing with a space in it — and so a node added later reads correctly without
     * anyone remembering to. The rule is the usual one: a boundary before an upper-case letter that
     * follows a lower-case one ({@code RadialScale}), and before the last upper-case of a run that
     * starts a new word ({@code RFlip} → {@code R Flip}). An acronym on its own survives intact, which
     * is the case that matters here — {@code UV} must not become {@code U V}.</p>
     */
    static String humanise(String id) {
        if (id == null || id.length() < 2) return id;
        StringBuilder out = new StringBuilder(id.length() + 4);
        for (int i = 0; i < id.length(); i++) {
            char current = id.charAt(i);
            if (i > 0 && Character.isUpperCase(current)) {
                char previous = id.charAt(i - 1);
                boolean afterLower = Character.isLowerCase(previous);
                boolean startsWordAfterAcronym = Character.isUpperCase(previous)
                        && i + 1 < id.length() && Character.isLowerCase(id.charAt(i + 1));
                if (afterLower || startsWordAfterAcronym) out.append(' ');
            }
            out.append(current);
        }
        return out.toString();
    }

    /** Unity's {@code Out(3)}: the name, then the arity, unless there is none worth printing. Takes the
     * printed FORM rather than the number, so a matrix can read {@code (2x2)} — see
     * {@link PortType#arityLabel()}. */
    private static String displayLabel(String name, @Nullable String arityLabel) {
        return arityLabel == null ? name : name + "(" + arityLabel + ")";
    }

    /**
     * The arity this port currently <em>displays</em> — its type's own, unless something has resolved a
     * concrete one for it (see {@link #setResolvedArity}).
     */
    public int displayedArity() {
        return resolvedArity > 0 ? resolvedArity : type.arity();
    }

    /**
     * Overrides the arity shown in this port's label, for a type that has none of its own.
     *
     * <p><b>Why a port can have an arity its TYPE does not.</b> A {@code dynamic} port has no width until
     * something is wired into it — that is the whole point of the type — so {@link PortType#arity()},
     * being a property of the type and therefore shared by every port of it, can only ever answer 0.
     * Unity still prints one: an unwired {@code Multiply} reads {@code A(1) B(1) Out(1)}, and feeding it
     * a vec3 turns all three into {@code (3)} at once. That number is per-PORT and changes as the graph
     * is rewired, so it cannot live on the type.</p>
     *
     * <p>Kept as an override rather than replacing {@link PortType#arity()} so a concretely-typed port is
     * untouched: {@code vec2} is 2 whatever is connected, and nothing should be able to relabel it.</p>
     *
     * @param arity the resolved width, or {@code 0} to fall back to the type's own
     * @return whether this actually changed anything — a caller that has more work to do on a width
     *         change (rebuilding the inline editor to N fields) can hang it off this rather than
     *         redoing it on every resolve pass
     */
    public boolean setResolvedArity(int arity) {
        int clamped = Math.max(0, arity);
        if (resolvedArity == clamped) return false;
        resolvedArity = clamped;
        // A resolved width is always a plain vector count — the NxN form belongs to a DECLARED matrix
        // type, which is never what a dynamic port resolves to.
        label.setText(displayLabel(humanise(portId),
                clamped > 0 ? String.valueOf(clamped) : type.arityLabel()));
        return true;
    }

    /**
     * Swaps the {@code type-*} class this port is styled through, for a type resolved at runtime.
     *
     * <p><b>Colour, like arity, is per-port for a dynamic port.</b> {@link PortType#cssClass()} is fixed
     * at construction and shared by every port of that type, so a {@code dynamic} port could only ever be
     * the one flat "unknown" grey — while Unity colours it by whatever it actually resolved to, defaulting
     * to the scalar colour before anything is wired in. The class is the only lever: the palette lives
     * entirely in {@code graph.css} ({@code nodeport.type-vec3 .__dot__ { border-color: ... }}), which is
     * the whole reason the wire follows for free — {@link #typeColor()} reads the dot's <em>computed</em>
     * border-colour back out of the cascade rather than being told a number.</p>
     *
     * <p>The declared class is <b>removed</b> rather than merely overlaid: {@code type-dynamic} and
     * {@code type-vec2} are equal-specificity selectors, so leaving both on would hand the decision to
     * stylesheet source order — which happens to give the right answer today and would silently stop
     * doing so the moment someone reordered the palette.</p>
     *
     * @param cssClass the class to style through, or {@code null} to restore the port's declared type
     */
    public void setResolvedTypeClass(@Nullable String cssClass) {
        String next = cssClass == null ? type.cssClass() : cssClass;
        String current = resolvedTypeClass == null ? type.cssClass() : resolvedTypeClass;
        if (next.equals(current)) return;
        removeClass(current);
        addClass(next);
        resolvedTypeClass = cssClass;
    }

    public String getName() {
        return label.getText();
    }

    /** The whole port is one control; its parts are internal. */

    /** Drives {@code nodeport:blank} — see the class javadoc. */
    @Override
    public boolean isBlank() {
        return connectionCount == 0;
    }

    /**
     * Whether a wire is attached.
     *
     * <p>Was {@code isConnected()}, which {@link UINode} now declares as {@code final} and uses for
     * something else entirely — whether the node is in a document. Two meanings of "connected" one
     * method apart is exactly the collision worth renaming out of: a port that is in the tree and has
     * no wire would have answered both true and false to the same word.</p>
     */
    public boolean hasConnection() {
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
            onBlankChanged.emit();
        }
    }

    /** The dot's centre, in the plane's coordinate space — i.e. the same space a paint call inside the
     * canvas uses, and the space {@link UINode#screenToLocal} reports in. Read live; never cached. */
    public Vector2f dotCenterIn(@Nullable UINode space) {
        return Box.centreIn(dot.box(), space == null ? null : space.box());
    }

    /** The dot's live outer radius (half its width) — what {@link NodeWireLayer} and {@link
     * PortDefaultEditor} trim a wire/stub endpoint back by, so the line stops at the ring's edge like
     * Unity's own rather than running under it into the centre. Same "read the live layout, never a
     * theme constant" reasoning as {@link #dotCenter()} — a themed {@code --graph-dot} change must not
     * need a matching Java constant kept in step by hand. */
    public float dotRadius() {
        Box d = dot.box();
        return d == null ? 0f : d.width() * 0.5f;
    }

    /** The colour of this port's type, taken from the dot's computed {@code border-color}.
     *
     * <p>This is the seam that keeps Unity's per-type palette in CSS. A wire is drawn by
     * {@code CgVectorRenderer}, which needs an ARGB int, so <em>something</em> has to hand it a number —
     * reading it back out of the cascade means the number's source is still a stylesheet.
     * {@code border-color} rather than {@code background-color} because the dot is hollow while
     * unconnected, so its fill is transparent exactly when a theme author would be surprised to find
     * the wire colour missing.</p> */
    public int typeColor() {
        return dot.getStyle().getGeneralGroup().borderColor();
    }


    /**
     * A pointer position a {@link Drag} reported, converted from THIS port's space into the plane's.
     *
     * <p><b>A drag callback's coordinates are relative to the SOURCE, and since M6.1 that means the
     * source's own origin is zero.</b> The old engine's {@code screenToLocal} converted out of surface
     * pixels without subtracting the element's own position, so a listener on a port received what was
     * effectively an absolute layout coordinate — near enough the plane's space that both the pending
     * wire and the create menu could use it directly, which is what they did and what
     * {@code NodeWireLayer.updatePending}'s javadoc still described.</p>
     *
     * <p>So the live wire's pointer end was drawn a whole node's width from the pointer, and dropping
     * on empty canvas would have opened the create menu somewhere else again. Adding the port's own
     * origin within the plane is the whole conversion: there is no scale between a port and the plane
     * it sits on — the zoom is the plane's own transform, which neither coordinate carries.</p>
     */
    private Vector2f pointerInPlane(float localX, float localY) {
        GraphView graph = graphView();
        if (graph == null) return new Vector2f(localX, localY);
        Vector2f origin = Box.originIn(box(), graph.content().box());
        return origin.add(localX, localY);
    }

    /** The node this port belongs to, or {@code null} if it is not on one. */
    @Nullable
    public GraphNode node() {
        for (UINode e = parent(); e != null; e = e.parent()) {
            if (e instanceof GraphNode node) return node;
        }
        return null;
    }

    @Nullable
    GraphView graphView() {
        for (UINode e = parent(); e != null; e = e.parent()) {
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
        UIDocument window = document();
        if (view == null || window == null) return false;

        view.beginPendingWire(this);
        // Snapshotted so onDragEnd can tell a wire that landed from one that did not: a drop on a port
        // fires DragEvent.Drop (and therefore connects) before the drag ends.
        //
        // That ordering is a real guarantee of UIDragController and not an assumption — it was written
        // here first and was FALSE, which made a wire dropped on a valid port connect and open the
        // create-node menu at the same time. See UIDragController.endDrag.
        final int startingConnections = getConnectionCount();
        Drag.startWithPayload(this, rawX, rawY, this,
                new Drag.Listener() {
                    @Override
                    public void onDragUpdate(float mx, float my, float sx, float sy, float dx, float dy) {
                        Vector2f plane = pointerInPlane(mx, my);
                        view.updatePendingWire(plane.x(), plane.y());
                    }

                    @Override
                    public void onDragEnd(float mx, float my) {
                        Vector2f plane = pointerInPlane(mx, my);
                        view.endPendingWire(NodePort.this, plane.x(), plane.y(),
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
