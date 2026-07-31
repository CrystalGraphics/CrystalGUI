package com.crystalgui.ui.elements.graph;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.canvas.WorldRect;
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
public class GraphNode extends UIElement {

    public static final String TITLE_BAR_CLASS = "__title-bar__";
    public static final String TITLE_CLASS = "__title__";
    public static final String COLLAPSE_CLASS = "__collapse__";
    public static final String PORTS_CLASS = "__ports__";
    public static final String INPUTS_CLASS = "__inputs__";
    public static final String OUTPUTS_CLASS = "__outputs__";
    public static final String CONTROLS_CLASS = "__controls__";
    public static final String CONTROL_ROW_CLASS = "__control-row__";
    public static final String PREVIEW_CLASS = "__preview__";
    /** On the node while collapsed. */
    public static final String COLLAPSED_CLASS = "__collapsed__";
    /** On the node while it is being dragged, so a theme can lift it — the same shape as
     * {@code splitview.__dragging__}, which exists because the cursor resolves from the capture
     * target and must not revert mid-gesture. */
    public static final String MOVING_CLASS = "__moving__";

    private final UIElement titleBar = new UIElement();
    private final UIText title;
    private final UIElement collapseToggle = new UIElement();
    private final UIElement ports = new UIElement();
    private final UIElement inputs = new UIElement();
    private final UIElement outputs = new UIElement();
    private final UIElement controls = new UIElement();
    private final UIElement preview = new UIElement();

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

    private boolean selected;

    /** Fires when this node's collapsed state changes, carrying the new value. */
    public final Signal.Value<Boolean> onCollapsedChanged = new Signal.Value<>();

    public GraphNode(String titleText) {
        titleBar.addClass(TITLE_BAR_CLASS);
        title = new UIText(titleText);
        title.addClass(TITLE_CLASS);
        title.setHitTest(false);
        collapseToggle.addClass(COLLAPSE_CLASS);
        collapseToggle.addChild(new UIText("v").setHitTest(false));
        titleBar.addInternalChild(title);
        titleBar.addInternalChild(collapseToggle);

        ports.addClass(PORTS_CLASS);
        inputs.addClass(INPUTS_CLASS);
        outputs.addClass(OUTPUTS_CLASS);
        ports.addInternalChild(inputs);
        ports.addInternalChild(outputs);

        controls.addClass(CONTROLS_CLASS);
        preview.addClass(PREVIEW_CLASS);

        addInternalChild(titleBar);
        addInternalChild(ports);
        addInternalChild(controls);
        // NOT the preview: it is attached by the first preview() call. An always-present slot means a
        // node with nothing to show still reserves 78px of empty box — which is what the first build
        // looked like, four nodes each with a large dark hole where a render will one day be. A node
        // that never asks for a preview should be the height of its ports.

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
            if (isInsideControls(event.getTarget())) return;

            GraphView view = graphView();
            if (view != null) view.selectNode(this, isShiftHeld());
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
        UIWindow window = getAttachedWindow();
        // Runs after the handler's own focus pass, which has already blurred whatever was focused —
        // so this sets rather than fights.
        if (window != null) window.getInputHandler().requestFocus(this);
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
    private boolean isInsideControls(@Nullable UIElement target) {
        for (UIElement e = target; e != null && e != this; e = e.getParent()) {
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
        UIWindow window = getAttachedWindow();
        if (view == null || window == null) return false;

        WorldRect start = view.worldBoundsOf(this);
        final float startX = start.x(), startY = start.y();
        this.moving = true;
        addClass(MOVING_CLASS);
        window.getInputHandler().getDragController().startDrag(this, rawX, rawY,
                new UIDragController.DragListener() {
                    @Override
                    public void onDragUpdate(float mx, float my, float sx, float sy, float dx, float dy) {
                        // Moved in place: left/top are rewritten, nothing is rebuilt. Rebuilding here
                        // would detach the very element the drag is anchored to — the failure that
                        // froze the table header, and the reason that trap is in the plan for this item.
                        view.moveNode(GraphNode.this, startX + dx, startY + dy);
                    }

                    @Override
                    public void onDragEnd(float mx, float my) {
                        WorldRect end = view.worldBoundsOf(GraphNode.this);
                        // One step for the whole drag — see GraphView.recordMove.
                        view.recordMove(GraphNode.this, startX, startY, end.x(), end.y());
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
        for (UIElement e = getParent(); e != null; e = e.getParent()) {
            if (e instanceof GraphView view) return view;
        }
        return null;
    }

    /** Ports and chrome are structure. A caller's element goes in {@link #preview()} or through
     * {@link #addControl}. */
    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

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
            inputs.addInternalChild(port);
        } else {
            outputPorts.add(port);
            outputs.addInternalChild(port);
        }
        return port;
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
    public GraphNode addControl(String labelText, UIElement widget) {
        UIElement row = new UIElement();
        row.addClass(CONTROL_ROW_CLASS);
        UIText rowLabel = new UIText(labelText);
        rowLabel.addClass(NodePort.LABEL_CLASS);
        rowLabel.setHitTest(false);
        row.addChild(rowLabel);
        row.addChild(widget);
        controls.addInternalChild(row);
        return this;
    }

    /** A widget occupying the whole control row, for something that needs no label. */
    public GraphNode addControl(UIElement widget) {
        controls.addInternalChild(widget);
        return this;
    }

    /**
     * The preview slot — empty until something fills it.
     *
     * <p>Deliberately just an element. A node preview is a live render of the graph up to this node,
     * which is the graph compiler's business (6.2.7); from here it is a background, exactly like every
     * other background in this engine. Shipping the slot without a pipeline behind it is the honest
     * half, rather than inventing a preview system the compiler would replace.</p>
     */
    public UIElement preview() {
        if (!hasPreview) {
            hasPreview = true;
            addInternalChild(preview);
        }
        return preview;
    }

    /** Whether this node has been given a preview slot. */
    public boolean hasPreview() {
        return hasPreview;
    }

    public UIElement titleBar() {
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
        onCollapsedChanged.emit(value);
        return this;
    }

    /** @see #isChecked() */
    public GraphNode setSelected(boolean value) {
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
