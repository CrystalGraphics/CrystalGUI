package com.crystalgui.widget.graph;

import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.graph.port.PortType;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.graph.PortDirection;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.widget.canvas.WorldRect;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.ui.input.UIDragController;
import lombok.Getter;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One box in the graph: a title bar, two columns of ports, any number of controls, and a preview slot.
 *
 * <pre>
 * ┌───────────────────────────────┐  __title-bar__   (title + collapse chevron)
 * │ Position                    v │
 * ├───────────────────────────────┤  __ports__       (__inputs__ | __outputs__)
 * │ ● A(3)               Out(3) ● │
 * │ ● B(3)                        │
 * ├───────────────────────────────┤  __controls__    (widgets with no port)
 * │ Space            [ World  v ] │
 * ├───────────────────────────────┤  __preview__     (empty until 6.2.7)
 * │ ░░░░░░░ preview ░░░░░░░░░░░░░ │
 * └───────────────────────────────┘
 * </pre>
 *
 * <h3>Two columns, not zipped rows</h3>
 * <p>Unity draws an input and an output on one baseline when a node has both. Two flex columns produce
 * exactly that and nothing has to pair anything: the third input simply has no output beside it. Zipping
 * inputs to outputs into shared rows would mean rebuilding rows whenever a port is added — and
 * rebuilding a node's structure is the thing this file's siblings keep warning about, because the
 * pointer is routinely <em>on</em> a port when the graph changes.</p>
 *
 * <h3>Selection is {@code :checked}</h3>
 * <p>{@link #isChecked()} is overridden, so the cyan ring is {@code graphnode:checked} in the theme
 * rather than a colour in Java. {@code ListView} reached for a class instead only because its rows are
 * arbitrary caller elements with no getter to override; a real widget takes the pseudo-class.</p>
 *
 * <p><b>The selected flag here is view state, not the selection model.</b> 6.2.4 owns the model — a set
 * plus a signal, so an inspector and the canvas can both read it — and will drive this. Storing it here
 * is deliberately the dumbest thing that renders correctly, so that decision stays open.</p>
 *
 * <h3>Collapse hides unconnected ports, not just the body</h3>
 * <p>Unity's rule, and it is the one that makes the feature worth having: a collapsed node in a busy
 * graph should be a title bar and the wires that actually attach to it. The rule itself is a stylesheet
 * selector — {@code graphnode.__collapsed__ nodeport:blank { display: none; }} — because
 * {@link NodePort#isBlank()} already publishes connection state to the cascade.</p>
 */
public class GraphNode extends UINode {

    /**
     * This widget's kind.
     *
     * <p>Declared here rather than in a vocabulary class, and declared AT ALL because a subclass
     * inherits its parent's kind unless it is given its own: without this, GraphNode reports
     * {@code crystalgui:element} (or its supertype's) and every rule the sheets write for
     * {@code graphnode} matches nothing at all — no background, no border, an unstyled widget that
     * reads as one that was never built.</p>
     */
    public static final Name NAME = Name.of("graphnode");

    public static final String TITLE_BAR_CLASS = "__title-bar__";
    public static final String TITLE_CLASS = "__title__";
    public static final String COLLAPSE_CLASS = "__collapse__";
    public static final String PORTS_CLASS = "__ports__";
    public static final String INPUTS_CLASS = "__inputs__";
    /** On {@link #INPUTS_CLASS} when the node has zero input ports — see {@link #addPort}. */
    public static final String NO_INPUTS_CLASS = "__no-inputs__";
    /** On {@link #OUTPUTS_CLASS} when the node declares no outputs. The mirror of
     * {@link #NO_INPUTS_CLASS}, and it is on the node for the same reason: the selector subset cannot
     * count children. */
    public static final String NO_OUTPUTS_CLASS = "__no-outputs__";
    /** On {@link #INPUTS_CLASS} or {@link #OUTPUTS_CLASS} whenever the node is collapsed AND every port
     * in that column is blank — see {@link #recomputeEmptyOnCollapse}. Distinct from
     * {@link #NO_INPUTS_CLASS}: that is structural (this node was never given an input at all) and
     * permanent; this is connection state and only matters while collapsed — a node like {@code Split}
     * genuinely has an input port, it is simply unconnected right now, and {@code nodeport:blank} already
     * hides its row when folded. Without this the column that row sat in keeps its width/padding for a
     * row that is no longer there. */
    public static final String EMPTY_WHEN_COLLAPSED_CLASS = "__empty-collapsed__";
    /** On {@link #PORTS_CLASS} when BOTH columns carry {@link #EMPTY_WHEN_COLLAPSED_CLASS} — see
     * {@link #recomputeEmptyOnCollapse}. */
    public static final String PORTS_EMPTY_CLASS = "__ports-empty__";
    /** On {@link #PORTS_CLASS} whenever the input column has nothing visible in it (structurally
     * portless, or collapsed with every input blank) — zeroes `.__ports__`'s own column gap, which
     * otherwise stood as a stray 1px left edge in front of a column with nothing to its left. */
    public static final String NO_INPUT_GAP_CLASS = "__no-input-gap__";
    public static final String OUTPUTS_CLASS = "__outputs__";
    public static final String CONTROLS_CLASS = "__controls__";
    public static final String CONTROL_ROW_CLASS = "__control-row__";
    /** A control that carries this takes the whole row and {@link #addControl} writes it no label. */
    public static final String FULL_WIDTH_CLASS = "__full-width__";
    public static final String PREVIEW_CLASS = "__preview__";
    /** On the node while collapsed. */
    public static final String COLLAPSED_CLASS = "__collapsed__";
    /** On the node while it is being dragged, so a theme can lift it — the same shape as
     * {@code splitview.__dragging__}, which exists because the cursor resolves from the capture
     * target and must not revert mid-gesture. */
    public static final String MOVING_CLASS = "__moving__";

    private final UINode titleBar = new UINode();
    private final UIText title;
    private final UINode collapseToggle = new UINode();
    private final UINode ports = new UINode();
    private final UINode inputs = new UINode();
    private final UINode outputs = new UINode();
    private final UINode controls = new UINode();
    private final UINode preview = new UINode();

    private final List<NodePort> inputPorts = new ArrayList<>();
    private final List<NodePort> outputPorts = new ArrayList<>();

    @Getter
    private boolean collapsed;

    /** Whether a left-drag on this node's chrome moves it. On by default; a fixed node (a comment
     * frame, a pinned output) turns it off. */
    @Getter
    private boolean movable = true;

    @Getter
    private boolean moving;

    /** @see #preview() */
    private boolean hasPreview;

    /** @see #addControl(UINode) */
    private boolean hasControls;

    private boolean selected;

    /**
     * The {@link com.crystalgui.graph.NodeData} id this widget projects, or null until a
     * {@link GraphView} binds it.
     *
     * <p>This is the only durable name a node has. The widget is a projection — it can be detached by a
     * delete and re-attached by an undo, rebuilt wholesale when a document is loaded, or never exist at
     * all on a server — so every edge, every {@code Edit} and every changeset refers to the id instead.
     * A reference to the widget would survive none of those.</p>
     */
    @Getter
    @Nullable
    private String nodeId;

    /**
     * The document {@code typeId} this node was built from, or null when it was hand-built as a widget.
     *
     * <p>Kept so a widget-authored node still produces honest {@code NodeData} when the view binds it —
     * see {@link GraphView#addNode}.</p>
     */
    @Getter
    @Nullable
    private String typeId;

    /** Bound by {@link GraphView} when the widget joins a document; package-private so nothing else can
     * rename a node behind the document's back. */
    void bindToDocument(@Nullable String nodeId, @Nullable String typeId) {
        this.nodeId = nodeId;
        if (typeId != null) this.typeId = typeId;
    }

    /** Fires when this node's collapsed state changes, carrying the new value. */
    public final Signal.Value<Boolean> onCollapsedChanged = new Signal.Value<>();

    public GraphNode(String titleText) {
        super(NAME);
        titleBar.addClass(TITLE_BAR_CLASS);
        title = new UIText(titleText);
        title.addClass(TITLE_CLASS);
        title.setHitTest(false);
        // The title is what has to drive `graphnode`'s own growth for a short-port node ("Time", "UV")
        // whose port rows alone would settle narrower than the title needs — see the note on
        // `forceSelfSizeWidth()`. Left to auto-detect, this raced the node's own not-yet-converged first
        // layout pass and intermittently latched the wrong (non-self-sizing) mode, which is why the
        // truncation this fixes only ever showed up on a page's FIRST open and never again once its
        // elements were torn down and rebuilt.
        collapseToggle.addClass(COLLAPSE_CLASS);
        // A real vector chevron (overlay: shape("chevron-down") in default.css) rather than a text
        // glyph — same reasoning as ConfiguratorGroup's identical arrow, which this used to imitate
        // in letter form only. Rotated by .__collapsed__ on the node itself, not swapped, so the two
        // states are one drawable that can transition rather than a jump cut.
        titleBar.append(title);
        titleBar.append(collapseToggle);

        ports.addClass(PORTS_CLASS);
        inputs.addClass(INPUTS_CLASS);
        outputs.addClass(OUTPUTS_CLASS);
        ports.append(inputs);
        ports.append(outputs);
        syncEmptyPortColumns();

        controls.addClass(CONTROLS_CLASS);
        preview.addClass(PREVIEW_CLASS);

        append(titleBar);
        append(ports);
        // NOT controls, NOT preview: both are attached lazily, by the first addControl()/preview() call
        // — see each. An always-present EMPTY slot still contributes a box: `.__controls__` carries its
        // own darker seam-tint and a real `padding-bottom` (see default.css), so a node with zero controls
        // used to render that padding as a phantom 1px band stacked on top of `.__ports__`'s own bottom
        // seam — a stray 2px gap in front of `Multiply`'s preview, which has no controls at all. Preview
        // learned this lesson first (a node with nothing to show used to still reserve 78px of empty box);
        // controls just never got the same treatment until the phantom seam surfaced it.

        collapseToggle.events.getGroup(MouseEvent.Down.class).attachListener((el, event) -> {
            setCollapsed(!collapsed);
            // Or the press falls through and also selects/drags the node, so collapsing would always
            // start a move of the thing that just changed size under the cursor.
            event.stopPropagation();
        }, false, true);

        // Clicking a node focuses it, which is what makes it a thing you have selected rather than a
        // picture you pressed — Delete, arrow-nudge and the inspector in 6.2.4 all key off focus.
        setFocusPolicy(FocusPolicy.CLICK);

        this.events.getGroup(MouseEvent.Down.class).attachListener((el, event) -> {
            if (!isEnabled() || event.getButtonId() != CgMouseCodes.LEFT_BUTTON) return;
            if (isInsideControls(((UINode) event.getTarget()))) return;

            GraphView view = graphView();
            if (view != null) {
                view.selectNode(this, isShiftHeld());
                // Touching a node brings it forward and LEAVES it there -- see GraphView.raise.
                view.raise(this);
            }
            focusOnPress();

            if (!movable) return;
            if (beginMoveDrag(event.getPosition().x(), event.getPosition().y())) event.stopPropagation();
        }, false, true);
    }

    /**
     * Takes focus on a press that landed on one of this node's own parts.
     *
     * <p><b>Why the widget has to ask, when {@code FocusPolicy.CLICK} is supposed to do this.</b> The
     * input handler focuses the <em>exact</em> element the press hit, and a press on a node lands on
     * its title bar, its port column, or a label — never on the node itself. So the node's own policy
     * is never consulted, the previously focused element is blurred, and nothing takes its place.</p>
     *
     * <p>Every other composite in this engine avoids it by accident: their inner parts are
     * {@code setHitTest(false)}, so the press really does land on the widget. That is not available
     * here — the title bar carries the collapse chevron, and hit-testing off applies to the whole
     * subtree.</p>
     *
     * <p><b>This is an engine gap, not a widget quirk.</b> The DOM focuses the nearest focusable
     * <em>ancestor</em> of what was clicked, which is why clicking the text inside a {@code <button>}
     * focuses the button. Making {@code emitMouseDown} walk up would fix this for every composite at
     * once; it is recorded as an open question in the P6 plan rather than done here, because it changes
     * focus behaviour for every widget in the engine and one of them is mid-rewrite.</p>
     */
    private void focusOnPress() {
        UIDocument window = document();
        // The POINTER variant, so this does not ring — see the matching note in GraphView.
        // Runs after the handler's own focus pass, which has already blurred whatever was focused —
        // so this sets rather than fights.
        if (window != null) window.focus().requestPointerFocus(this);
    }

    private static boolean isShiftHeld() {
        var input = CgPlatform.input();
        return input != null && CgModifiers.hasShift(input.getCurrentModifiers());
    }

    /**
     * Whether the press landed on something that handles its own dragging.
     *
     * <p>Ports stop the event themselves — a press on one starts a wire — so they never reach here.
     * Controls do not: a {@code Dropdown} opens a popover on press and lets the event carry on, so
     * without this check nudging the mouse while picking "Object" from a Space dropdown would drag the
     * node out from under the menu. Cheap to test for, and the alternative (asking every control to
     * stop propagation) puts the burden on whatever a caller happens to drop in.</p>
     */
    private boolean isInsideControls(@Nullable UINode target) {
        for (UINode e = target; e != null && e != this; e = e.parent()) {
            if (e == controls) return true;
        }
        return false;
    }

    /**
     * Starts a move.
     *
     * <p><b>The node is a safe drag source, unlike the plane in a pan.</b> {@code UIDragController}
     * converts every coordinate through the source's own transform — so a source whose <em>transform</em>
     * the drag is changing feeds itself garbage, which is why a pan drags from the viewport. Moving a
     * node changes its {@code left}/{@code top}, which are layout, not transform, and layout is not in
     * the matrix. The node's own space therefore stands still while the node moves through it.</p>
     *
     * <p>The delta arrives in plane space, whose units <em>are</em> world units, so the node tracks the
     * pointer exactly at any zoom with no division: the inverse transform has already done it.</p>
     */
    private boolean beginMoveDrag(float rawX, float rawY) {
        GraphView view = graphView();
        UIDocument window = document();
        if (view == null || window == null) return false;

        // Everything selected moves, not just the node under the cursor — and if this node is not in
        // the selection, the press already made it the selection (see GraphView.selectNode).
        final List<GraphNode> moving = new ArrayList<>();
        final List<float[]> origins = new ArrayList<>();
        for (GraphNode node : view.getSelection().contains(this) ? view.selectedNodes() : List.of(this)) {
            WorldRect at = view.worldBoundsOf(node);
            moving.add(node);
            origins.add(new float[]{at.x(), at.y()});
        }
        // The drag's own delta, not a re-read of the layout at the end.
        //
        // worldBoundsOf() reports the LAST COMPUTED layout, and the final moveNode of a drag writes
        // left/top that Taffy has not resolved yet — so asking at drag end returns the position from
        // before the last move, and a short drag reports a delta of zero and records nothing at all.
        // The listener is handed the delta directly; keeping it is both simpler and the only thing
        // here that is not one frame stale.
        final float[] delta = {0f, 0f};
        this.moving = true;
        addClass(MOVING_CLASS);
        Drag.start(this, rawX, rawY,
                new Drag.Listener() {
                    @Override
                    public void onDragUpdate(float mx, float my, float sx, float sy, float dx, float dy) {
                        // Moved in place: left/top are rewritten, nothing is rebuilt. Rebuilding here
                        // would detach the very element the drag is anchored to — the failure that
                        // froze the table header, and the reason that trap is in the plan for this item.
                        //
                        // Every node moves by the SAME delta from its own origin, rather than each
                        // tracking the pointer: a selection dragged by one member must keep its shape.
                        delta[0] = dx;
                        delta[1] = dy;
                        for (int i = 0; i < moving.size(); i++) {
                            float[] origin = origins.get(i);
                            view.moveNode(moving.get(i), origin[0] + dx, origin[1] + dy);
                        }
                    }

                    @Override
                    public void onDragEnd(float mx, float my) {
                        // One step for the whole drag, however many nodes it moved — the case
                        // transactions exist for. Forty nodes is one Ctrl+Z.
                        view.recordMoves(moving, origins, delta[0], delta[1]);
                        endMove();
                    }

                    @Override
                    public void onDragCancel() {
                        endMove();
                    }
                });
        return true;
    }

    private void endMove() {
        moving = false;
        removeClass(MOVING_CLASS);
    }

    @Nullable
    private GraphView graphView() {
        for (UINode e = parent(); e != null; e = e.parent()) {
            if (e instanceof GraphView view) return view;
        }
        return null;
    }

    /**
     * Draws the stub for each of this node's own input ports that currently has a floating default
     * editor mounted — the one paint slot that is genuinely "sandwiched" between a node's own body and
     * its selection ring.
     *
     * <p>{@code drawSubtreeTransformed} calls {@code paintSelf}, then every child, then
     * {@code paintOverlay} (here), then {@code paintOutline} — all as ONE atomic unit for this element,
     * before any sibling with a different z gets a turn. That is exactly why a SIBLING element (a
     * separate stub layer on the plane) could never land "above the body, below the ring" no matter what
     * z-index it was given: the ring is the last step of the SAME atomic paint call as the body, so any
     * z comparison against a sibling answers for both at once. Overlay is the only hook that runs
     * strictly after this node's own children and strictly before its own outline — so drawing the stub
     * from here, rather than from a plane sibling, is what actually produces "over the body, under the
     * ring" instead of only ever being able to choose one side of that pair.</p>
     */
    @Override
    public void paintDecoration(CgUiPaintContext ctx, Box box) {
        super.paintDecoration(ctx, box);
        GraphView view = graphView();
        if (view == null) return;
        for (NodePort port : inputPorts) view.paintPortEditorStub(ctx, port, this);
    }

    /** Ports and chrome are structure. A caller's element goes in {@link #preview()} or through
     * {@link #addControl}. */

    // ── Ports ───────────────────────────────────────────────────────────────

    public NodePort addInput(PortType type, String name) {
        return addPort(new NodePort(PortDirection.INPUT, type, name));
    }

    public NodePort addOutput(PortType type, String name) {
        return addPort(new NodePort(PortDirection.OUTPUT, type, name));
    }

    public NodePort addPort(NodePort port) {
        if (port.getDirection().isInput()) {
            inputPorts.add(port);
            inputs.append(port);
        } else {
            outputPorts.add(port);
            outputs.append(port);
        }
        // An even 50/50 split matches Unity on every node that HAS inputs — but an inputless node
        // (UV, Position, Normal Vector, Time) has nothing in the input column to justify it a half, and
        // an even split there shows a bare empty panel beside Out. `NO_INPUTS_CLASS` is what lets
        // the sheet zero the input column's grow ONLY in that one case, without the sheet needing to
        // count children (which its selector subset cannot do).
        syncEmptyPortColumns();
        // Connection state can flip long after this port is added, and it has to be re-checked every time
        // it does — see recomputeEmptyOnCollapse.
        port.onBlankChanged.connect(this::recomputeEmptyOnCollapse);
        recomputeEmptyOnCollapse();
        // The document has to learn about it, because ports may be added AFTER the node joins the view —
        // `addNode(node, x, y); node.addOutput(...)` is the order 6.2.3's own examples use. Deriving the
        // NodeData once at add time captured whatever ports existed at that instant, so a port declared
        // afterwards existed on screen, could be wired, and was absent from every save.
        GraphView view = graphView();
        if (view != null) {
            view.syncPorts(this);
            // AND the port's own floating default editor. This is the case that used to need a per-frame
            // scan: a node built by a factory gains its ports before it joins anything, so graphView() is
            // null throughout and the view cannot learn about them here -- it picks those up when the node
            // is registered instead. This branch is the other half, a port arriving on a node that is
            // already on the plane, which is what dynamic arity does.
            view.watchPort(port);
        }
        return port;
    }

    /**
     * The port with this {@code portId}, either direction, or null.
     *
     * <p>Matched on the <b>id</b>, never {@link NodePort#getName()} — that returns the drawn label, which
     * carries the arity ({@code "Value(4)"}) and is whatever a theme decided to render. Comparing it
     * silently matches nothing, which is not an error anywhere: a caller looking up a port just gets null
     * and quietly falls back.</p>
     */
    @Nullable
    public NodePort portNamed(String portId) {
        for (NodePort port : getInputPorts()) {
            if (port.getPortId().equals(portId)) return port;
        }
        for (NodePort port : getOutputPorts()) {
            if (port.getPortId().equals(portId)) return port;
        }
        return null;
    }

    public List<NodePort> getInputPorts() {
        return Collections.unmodifiableList(inputPorts);
    }

    public List<NodePort> getOutputPorts() {
        return Collections.unmodifiableList(outputPorts);
    }

    /** Every port on this node, inputs first. */
    public List<NodePort> getPorts() {
        List<NodePort> all = new ArrayList<>(inputPorts.size() + outputPorts.size());
        all.addAll(inputPorts);
        all.addAll(outputPorts);
        return all;
    }

    // ── Controls and preview ────────────────────────────────────────────────

    /**
     * A widget with no port — Unity's "Controls". The label sits left, the widget right, matching the
     * {@code Space [ World v ]} row in the reference.
     */
    public GraphNode addControl(String labelText, UINode widget) {
        ensureControlsAttached();
        UINode row = new UINode();
        row.addClass(CONTROL_ROW_CLASS);
        // A widget may decline the label, and the decision belongs to the WIDGET rather than to this
        // method or its caller: only the control knows whether it describes itself. A colour swatch
        // does — it is the value, drawn — so a word beside it buys nothing and costs the row's width,
        // which for a swatch is the whole of what makes it readable.
        if (!widget.hasClass(FULL_WIDTH_CLASS)) {
            UIText rowLabel = new UIText(labelText);
            rowLabel.addClass(NodePort.LABEL_CLASS);
            rowLabel.setHitTest(false);
            // Same reasoning as `GraphNode.title`/`NodePort.label` — see `UIText.forceSelfSizeWidth()`.
            row.append(rowLabel);
        }
        row.append(widget);
        controls.append(row);
        return this;
    }

    /** A widget occupying the whole control row, for something that needs no label. */
    public GraphNode addControl(UINode widget) {
        ensureControlsAttached();
        controls.append(widget);
        return this;
    }

    /** See {@link #hasControls}. Inserted right after {@link #ports} — not appended — so a caller free to
     * call {@link #preview()} before its first {@code addControl} still ends up with the fixed visual
     * order (title, ports, controls, preview) rather than whichever was asked for first. */
    private void ensureControlsAttached() {
        if (hasControls) return;
        hasControls = true;
        insertAt(indexOf(ports) + 1, controls);
    }

    /**
     * The preview slot — empty until something fills it.
     *
     * <p>Deliberately just an element. A node preview is a live render of the graph up to this node,
     * which is the graph compiler's business (6.2.7); from here it is a background, exactly like every
     * other background in this engine. Shipping the slot without a pipeline behind it is the honest
     * half, rather than inventing a preview system the compiler would replace.</p>
     */
    public UINode preview() {
        if (!hasPreview) {
            hasPreview = true;
            append(preview);
        }
        return preview;
    }

    /** Whether this node has been given a preview slot. */
    public boolean hasPreview() {
        return hasPreview;
    }

    public UINode titleBar() {
        return titleBar;
    }

    public String getTitle() {
        return title.getText();
    }

    public GraphNode setTitle(String text) {
        title.setText(text);
        return this;
    }

    // ── State ───────────────────────────────────────────────────────────────

    public GraphNode setCollapsed(boolean value) {
        if (this.collapsed == value) return this;
        this.collapsed = value;
        if (value) addClass(COLLAPSED_CLASS);
        else removeClass(COLLAPSED_CLASS);
        recomputeEmptyOnCollapse();
        onCollapsedChanged.emit(value);
        return this;
    }


    /**
     * Zeroes the grow of whichever port column has nothing in it.
     *
     * <p>An even 50/50 split matches Unity on every node that has BOTH — but a column with no ports has
     * nothing to justify it a half, and each column carries its own background, so an empty one is
     * drawn: a bare panel beside {@code Out} on an inputless node, and a darker strip down the inside
     * of the right border on the master node every graph ends at. The sheet cannot count children, so
     * the class is what lets it zero the grow in exactly those cases.</p>
     *
     * <p><b>Called from the constructor as well as from {@link #addPort}</b>, because a node with NO
     * ports at all never reaches the add path and both its columns are empty from the start. That is
     * also the only shape a fixture builds, so a rule for it would otherwise match nothing anywhere --
     * which is what {@code StyleParityTest} says when the maintenance lives only in the adder.</p>
     */
    private void syncEmptyPortColumns() {
        if (inputPorts.isEmpty()) inputs.addClass(NO_INPUTS_CLASS);
        else inputs.removeClass(NO_INPUTS_CLASS);
        if (outputPorts.isEmpty()) outputs.addClass(NO_OUTPUTS_CLASS);
        else outputs.removeClass(NO_OUTPUTS_CLASS);
    }

    /** See {@link #EMPTY_WHEN_COLLAPSED_CLASS}. Run on every collapse toggle and every port blank-state
     * change, since either can flip whether a column's visible rows just went to zero. An empty list is
     * vacuously all-blank, so this also covers a genuinely portless column with no special case. */
    private void recomputeEmptyOnCollapse() {
        setEmptyOnCollapse(inputs, inputPorts);
        setEmptyOnCollapse(outputs, outputPorts);
        // Visibly empty EITHER because there is structurally nothing here (a portless column, permanent)
        // OR because collapse just hid every row in it — `.__ports__`'s own `gap-all: 1px` runs between
        // the two columns regardless of either one's width, so a genuinely 0-width input column still
        // left 1px of `.__ports__`'s darker tint standing before the output content, reading as a stray
        // left edge on an otherwise inputless row (`Position`'s `Out` row is the case that surfaced it).
        boolean inputsVisiblyEmpty = visiblyEmpty(inputPorts);
        boolean outputsVisiblyEmpty = visiblyEmpty(outputPorts);
        if (inputsVisiblyEmpty) ports.addClass(NO_INPUT_GAP_CLASS);
        else ports.removeClass(NO_INPUT_GAP_CLASS);
        // Both columns empty leaves `.__ports__` itself with nothing in it but its own 1px-top/1px-bottom
        // padding — the two seams default.css's own comment describes collapse into ONE two-tone band
        // with nothing between them to divide, which is the extra "gap" a fully-collapsed, fully-blank
        // node showed instead of Unity's single hairline. `__ports-empty__` lets the sheet collapse that
        // padding to a single fixed-height line instead of losing the seam entirely — `.__ports__` still
        // owns the only divider between the title bar and whatever comes next.
        if (inputsVisiblyEmpty && outputsVisiblyEmpty) ports.addClass(PORTS_EMPTY_CLASS);
        else ports.removeClass(PORTS_EMPTY_CLASS);
    }

    private boolean visiblyEmpty(List<NodePort> ports) {
        return ports.isEmpty() || (collapsed && allBlank(ports));
    }

    private void setEmptyOnCollapse(UINode column, List<NodePort> ports) {
        boolean empty = collapsed && allBlank(ports);
        if (empty) column.addClass(EMPTY_WHEN_COLLAPSED_CLASS);
        else column.removeClass(EMPTY_WHEN_COLLAPSED_CLASS);
    }

    private static boolean allBlank(List<NodePort> ports) {
        return ports.stream().allMatch(NodePort::isBlank);
    }

    /** @see #isChecked() */
    /**
     * <b>Package-private on purpose: {@link GraphSelection} is the only legitimate caller.</b>
     *
     * <p>This writes the node's own flag and nothing else, so calling it directly desynchronises the
     * widget from the model — and the failure is not symmetric. A node set selected this way is drawn
     * with the ring while the selection is <em>empty</em>, and can never be cleared afterwards, because
     * {@code clearSilently()} only deselects the nodes the set actually holds. The gallery opened its
     * graph page this way and the ring then survived every click on every other node; only a marquee
     * cleared it, since a marquee adds the node to the set before dropping it properly.</p>
     *
     * <p>Same shape as {@code Tab.setSelected}, which is package-private for the same reason: state that
     * a collection owns must not be settable on the member.</p>
     */
    GraphNode setSelected(boolean value) {
        if (this.selected == value) return this;
        this.selected = value;
        // The pseudo-class idiom: tell the cascade its identity changed, or graphnode:checked never
        // re-matches and the ring never appears.
        onStyleChanged();
        invalidateStyleMatch();
        notifyStateChanged();
        return this;
    }

    public boolean isSelected() {
        return selected;
    }

    /** Selection, published to the cascade as {@code graphnode:checked}. */
    @Override
    public boolean isChecked() {
        return selected;
    }
}
