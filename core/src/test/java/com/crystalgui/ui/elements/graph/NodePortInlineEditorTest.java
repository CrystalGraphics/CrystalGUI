package com.crystalgui.ui.elements.graph;

import com.crystalgui.graph.GraphDocument;
import com.crystalgui.graph.NodeData;
import com.crystalgui.graph.NodeField;
import com.crystalgui.graph.NodeType;
import com.crystalgui.graph.shader.ShaderColorFieldWidget;
import com.crystalgui.graph.shader.ShaderVectorFieldWidget;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.Ui;
import com.crystalgui.ui.elements.config.control.ColorControl;
import com.crystalgui.ui.elements.config.control.NumberControl;
import com.crystalgui.ui.elements.config.control.VectorControl;
import com.crystalgui.core.config.ConfigDescriptor;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * <b>P6.1.8 step 8 / P6.3.9, end to end: an unconnected input's default-value editor.</b>
 *
 * <p>Unity draws this OUTSIDE the node — a small floating field beside the port, joined by a short stub
 * — not as a row inside the node's own box. {@code NodePort} only holds the reference
 * ({@link NodePort#getDefaultEditor()}); {@link GraphView} is what discovers it, mounts it onto
 * {@link GraphView#content()} while the port is blank, repositions it against the port's live
 * {@link NodePort#dotCenter()} every tick, and takes it back off the instant a wire lands. What this
 * exercises is the actual widget path: {@link NodeFieldBinder#attach} building a REAL
 * {@link ConfigControl} through {@link NodeFieldWidgets} and {@link GraphView} placing it for real, in a
 * REAL {@link GraphView} built the way the editor actually builds one
 * ({@link NodeWidgetFactory#placeholder}), not a hand-assembled stand-in.</p>
 */
public class NodePortInlineEditorTest extends UiTestBase {

    private UIWindow window;
    private GraphView view;

    private void openWindow() {
        view = new GraphView();
        view.layout(l -> l.width(600).height(400));
        UIElement root = new UIElement().layout(l -> l.width(800).height(600));
        root.addChild(view);
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.getStyleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:graph"));
        window.init(800, 600);
        ShaderColorFieldWidget.install();
        ShaderVectorFieldWidget.install();
    }

    /** Ticks style, layout AND the view — {@code GraphView.tickFrame} is what discovers a newly-bound
     * default editor and mounts/repositions it, so a single {@code updateWithoutPainting} is not enough
     * the very first time (discovery itself lags one tick, same as {@code ShaderGraphPreviews}). */
    private void frame() {
        window.updateWithoutPainting();
        window.updateWithoutPainting();
    }

    /** A node with one port field (a scalar default on an unconnected input) and one body field. */
    private static NodeType numberPortType() {
        return NodeType.of("t:number-port").label("Node")
                .in("Value", "float", NodeField.number("Value", "Value", "0.5").onPort("Value"))
                .out("Out", "float")
                .field(NodeField.enumOf("Space", "Space", "Object", "World"))
                .build();
    }

    private GraphNode buildNode(NodeType type, GraphDocument document) {
        NodeData data = document.addNode(type.create(0f, 0f));
        GraphNode node = NodeWidgetFactory.placeholder(type, data,
                NodeWidgetFactory.PortTypeRegistryLookup.DEFAULT);
        node.bindToDocument(data.id(), data.typeId());
        view.addNode(node, 0f, 0f);
        return node;
    }

    /**
     * <b>The editor's FIRST drawn position is its final one.</b>
     *
     * <p>Reported as the fields flying in from across the screen when a node is created. The cause is an
     * ordering one, and the flicker's size is the giveaway: {@link PortDefaultEditor#reposition} anchors
     * the box by its RIGHT edge, so the left it writes is {@code portDot - GAP - width}. A width of zero
     * therefore does not put the box near where it belongs — it puts it <em>on the port</em>, a whole box
     * width to the right, and every intermediate width on the way to the real one draws somewhere in
     * between.</p>
     *
     * <p>Zero was what it read, because {@code GraphView}'s tick runs during {@code tickAnimations} —
     * <b>before</b> {@code calculateLayout}. So every position it computed was one layout behind by
     * construction, and a control whose {@code UIText} settles its own width over two or three passes was
     * drawn once at each stale value.</p>
     *
     * <p>Asserted by recording the position on every frame from the first one the widget is mounted, and
     * requiring them all to agree. A test that only checked the settled position passes against the
     * unfixed code — the flicker is entirely in the frames before it.</p>
     */
    @Test
    public void theEditorIsNeverDrawnAtAnUnsettledPosition() {
        openWindow();
        GraphDocument document = new GraphDocument();
        NodeType type = numberPortType();
        GraphNode node = buildNode(type, document);
        // Away from the world origin, where an off-by-a-box-width is indistinguishable from correct.
        view.moveNode(node, 240f, 160f);
        NodeFieldBinder.attach(node, type, document, null, null);

        NodePort port = node.portNamed("Value");
        java.util.List<Float> drawnAt = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            window.updateWithoutPainting();
            PortDefaultEditor editor = view.portEditorFor(port);
            // Only frames that would actually PAINT the widget count -- before it is mounted there is
            // nothing on screen to be in the wrong place.
            if (editor == null || !editor.isMounted() || editor.control() == null) continue;
            drawnAt.add(editor.control().getParent().getRuntimeCache().getX());
        }

        assertFalse("the editor never mounted, so this asserts nothing", drawnAt.isEmpty());
        float settled = drawnAt.get(drawnAt.size() - 1);
        UIElement box = view.portEditorFor(port).control().getParent();
        // The claim below is "every frame agrees", which a widget stuck at 0x0 would satisfy trivially.
        assertTrue("the box must have a real width by the end; got " + box.getRuntimeCache().getWidth(),
                box.getRuntimeCache().getWidth() > 0f);
        assertTrue("...and must sit LEFT of the port it belongs to; box=" + settled
                        + " port=" + port.dotCenter().x(),
                settled + box.getRuntimeCache().getWidth() < port.dotCenter().x());
        for (int i = 0; i < drawnAt.size(); i++) {
            assertEquals("frame " + i + " drew the editor at " + drawnAt.get(i)
                            + ", which is not where it settles (" + settled + "): " + drawnAt,
                    settled, drawnAt.get(i), 0.5f);
        }
    }

    /**
     * The whole point of the mechanism: the port field's control becomes the port's
     * {@link NodePort#getDefaultEditor()}, and the body field's lands in the node's controls row —
     * never crossed. Neither assertion here needs the view to have ticked; the binding is synchronous.
     */
    @Test
    public void aPortFieldBecomesThePortsEditorAndABodyFieldGoesInTheBody() {
        openWindow();
        GraphDocument document = new GraphDocument();
        NodeType type = numberPortType();
        GraphNode node = buildNode(type, document);

        NodeFieldBinder.attach(node, type, document, null, null);
        window.updateWithoutPainting();

        NodePort port = node.portNamed("Value");
        assertNotNull(port);
        assertNotNull("the port field's control must be the port's default editor", port.getDefaultEditor());
        assertTrue("it must be a real ConfigControl, not a bare widget",
                port.getDefaultEditor() instanceof NumberControl);

        UIElement controls = node.querySelector("." + GraphNode.CONTROLS_CLASS);
        assertNotNull(controls);
        assertTrue("the port field's control must NOT have leaked into the node's controls row",
                controls.querySelectorAll(".__config-control__").stream()
                        .noneMatch(c -> c == port.getDefaultEditor()));
        assertFalse("and the node must actually HAVE a body control for Space",
                controls.querySelectorAll(".__config-control__").isEmpty());
    }

    /**
     * Mount state is what shows the editor at all — verified here against a REAL control, mounted onto
     * a REAL {@link GraphView} plane, not the placeholder {@code UIElement} other tests use.
     *
     * <p>Checked via {@code getAttachedWindow()}, not {@code getParent()}: {@code GraphView} wraps the
     * control in a label+dot row (Unity's {@code X 0 •}) and mounts/unmounts THAT, so the control's own
     * {@code getParent()} is the row and stays non-null even while the row itself is off the plane — a
     * detached subtree keeps its own internal structure. {@code getAttachedWindow()} is cleared and
     * restored recursively by {@code removeChild}/{@code addChild}, which is what actually answers "is
     * this live right now".</p>
     */
    @Test
    public void theEditorIsMountedOnlyWhileThePortIsUnconnected() {
        openWindow();
        GraphDocument document = new GraphDocument();
        NodeType type = numberPortType();
        GraphNode node = buildNode(type, document);
        NodeFieldBinder.attach(node, type, document, null, null);
        frame();

        NodePort port = node.portNamed("Value");
        UIElement editor = port.getDefaultEditor();
        assertNotNull("blank: the editor must actually be on the plane, not merely present",
                editor.getAttachedWindow());

        port.setConnectionCount(1);
        frame();
        assertNull("connected: taken off the plane entirely", editor.getAttachedWindow());

        port.setConnectionCount(0);
        frame();
        assertNotNull("disconnected again: it must come back", editor.getAttachedWindow());
    }

    /**
     * <b>The editor must land beside its OWN node, not off in the corner of the plane.</b>
     *
     * <p>Placed at world (0, 0) — as every other test in this file does, for simplicity — the bug this
     * pins is invisible: a coordinate that has been offset by the plane's own on-screen origin lands in
     * the same place as one that has not, because the origin being wrongly added is itself zero-ish at
     * world (0, 0). Only a node parked away from the origin exposes it, which is exactly what a real
     * graph looks like and exactly what a screenshot caught that this suite did not.</p>
     */
    @Test
    public void theEditorLandsBesideItsOwnNodeEvenFarFromWorldOrigin() {
        openWindow();
        GraphDocument document = new GraphDocument();
        NodeType type = numberPortType();
        NodeData data = document.addNode(type.create(400f, 260f));
        GraphNode node = NodeWidgetFactory.placeholder(type, data,
                NodeWidgetFactory.PortTypeRegistryLookup.DEFAULT);
        node.bindToDocument(data.id(), data.typeId());
        view.addNode(node, 400f, 260f);
        NodeFieldBinder.attach(node, type, document, null, null);
        frame();

        UIElement editor = node.portNamed("Value").getDefaultEditor();
        var nodeBounds = view.worldBoundsOf(node);
        var editorBounds = view.worldBoundsOf(editor);

        // A floor and a ceiling, not an exact pixel: the editor sits a small gap to the LEFT of the
        // node and roughly level with its port row, never hundreds of world units away in any
        // direction — which is what "the plane's own origin got added a second time" looks like.
        assertTrue("editor must be near the node horizontally: dx=" + (nodeBounds.x() - editorBounds.x()),
                Math.abs(nodeBounds.x() - editorBounds.x()) < 200f);
        assertTrue("editor must be near the node vertically: dy=" + (nodeBounds.y() - editorBounds.y()),
                Math.abs(nodeBounds.y() - editorBounds.y()) < 200f);
    }

    /**
     * A deleted node must take its port's floating editor with it — the failure mode
     * {@code GraphView.forgetPortEditor} exists for: the editor is not the node's descendant, so
     * removing the node alone cannot reach it.
     */
    @Test
    public void removingTheNodeRemovesItsFloatingEditorToo() {
        openWindow();
        GraphDocument document = new GraphDocument();
        NodeType type = numberPortType();
        GraphNode node = buildNode(type, document);
        NodeFieldBinder.attach(node, type, document, null, null);
        frame();

        UIElement editor = node.portNamed("Value").getDefaultEditor();
        assertNotNull(editor.getAttachedWindow());

        view.removeNode(node);
        frame();
        assertNull("the floating editor must not survive its node", editor.getAttachedWindow());
    }

    /**
     * <b>Unity's {@code X [0] •}, structurally.</b> {@link PortDefaultEditor} wraps the axis label and
     * the bare control in a rounded box — the control's own {@code getParent()}, since
     * {@code NodePort.getDefaultEditor()} still returns the bare {@link NumberControl} — and mounts a
     * SEPARATE dot directly on {@link GraphView#content()}, never as a descendant of the box. See
     * {@code PortDefaultEditor}'s own class javadoc for why: independent positioning is what lets the dot
     * visually overlap the box without a flex-layout coupling that would move the whole box instead the
     * moment the dot's offset changes.
     */
    @Test
    public void theEditorBoxAndItsDotAreSeparateElements() {
        openWindow();
        GraphDocument document = new GraphDocument();
        NodeType type = numberPortType();
        GraphNode node = buildNode(type, document);
        NodeFieldBinder.attach(node, type, document, null, null);
        frame();

        NodePort port = node.portNamed("Value");
        UIElement control = port.getDefaultEditor();
        UIElement box = control.getParent();
        assertNotNull("the control must be parented into the editor's own box", box);
        assertTrue("the control's parent must carry EDITOR_CLASS", box.hasClass(NodePort.EDITOR_CLASS));
        assertSame("the box is mounted straight onto the plane", view.content(), box.getParent());

        boolean hasLabel = false;
        for (UIElement child : box.getChildren()) {
            if (child instanceof com.crystalgui.ui.elements.UIText text
                    && text.hasClass(NodePort.EDITOR_LABEL_CLASS)) {
                // NOT the port's own id ("Value") — Unity's own axis prefix is generic for a lone
                // scalar field, always "X", regardless of what the port is actually called. See
                // axisLabelIsGenericXUnlessThePortIsAlreadyNamedAnAxisLetter for the full rule.
                assertEquals("a lone scalar field's axis label is the generic \"X\", not the port's "
                        + "own name", "X", text.getText());
                hasLabel = true;
            }
        }
        assertTrue("box must carry the axis label", hasLabel);

        PortDefaultEditor editor = view.portEditorFor(port);
        assertNotNull("an editor must be tracked for this port", editor);
        assertTrue("it must be mounted while the port is blank", editor.isMounted());
        UIElement dot = editor.dot();
        assertTrue(dot.hasClass(NodePort.EDITOR_DOT_CLASS));
        assertSame("the dot is ALSO mounted straight onto the plane, never inside the box",
                view.content(), dot.getParent());
        assertNotSame("box and dot are two distinct elements", box, dot);
        boolean hasRing = dot.getChildren().stream()
                .anyMatch(c -> c.hasClass(NodePort.EDITOR_DOT_RING_CLASS));
        assertTrue("the dot must hold its grey ring layer", hasRing);
        boolean hasCore = dot.getChildren().stream()
                .filter(c -> c.hasClass(NodePort.EDITOR_DOT_RING_CLASS))
                .flatMap(ring -> ring.getChildren().stream())
                .anyMatch(c -> c.hasClass(NodePort.EDITOR_DOT_CORE_CLASS));
        assertTrue("the ring must hold its coloured core layer", hasCore);
    }

    /**
     * <b>A lone scalar port's axis label is always "X" — Unity's convention, not the port's own real
     * name — with exactly one exception: a port already named X/Y/Z/W keeps its real id.</b>
     *
     * <p>Both halves matter and neither is provable by the other: a node with a semantically-named port
     * ({@code RadialScale}) proves the generic-"X" rule fires at all; a node with a port genuinely
     * named one of the four axis letters (the shape {@code Vector4}'s own four constituent float ports
     * take) proves the exception does not get clobbered by a blanket "always X" implementation.</p>
     */
    @Test
    public void axisLabelIsGenericXUnlessThePortIsAlreadyNamedAnAxisLetter() {
        openWindow();
        GraphDocument document = new GraphDocument();
        NodeType type = NodeType.of("t:mixed-scalar-names").label("Node")
                .in("RadialScale", "float", NodeField.number("RadialScale", "RadialScale", "1.0")
                        .onPort("RadialScale"))
                .in("Y", "float", NodeField.number("Y", "Y", "0.0").onPort("Y"))
                .out("Out", "float")
                .build();
        GraphNode node = buildNode(type, document);
        NodeFieldBinder.attach(node, type, document, null, null);
        frame();

        assertEquals("a semantically-named scalar port is relabelled to the generic axis prefix",
                "X", axisLabelTextFor(node, "RadialScale"));
        assertEquals("a port already named an axis letter keeps its own real id",
                "Y", axisLabelTextFor(node, "Y"));
    }

    /**
     * <b>A vector port editor gets NO outer axis-prefix label at all.</b> {@link VectorControl} already
     * draws its own per-axis X/Y sub-labels internally — an outer "Center" prefix in front of them would
     * be a second, redundant label Unity's own reference never shows.
     */
    @Test
    public void vectorPortEditorsHaveNoOuterAxisLabel() {
        openWindow();
        GraphDocument document = new GraphDocument();
        NodeType type = NodeType.of("t:vector-no-outer-label").label("Node")
                .in("Center", "vec2", new NodeField("Center", "Center", NodeField.Kind.VECTOR,
                        java.util.List.of(), "vec2(0.500, 0.500)", null))
                .out("Out", "vec2")
                .build();
        GraphNode node = buildNode(type, document);
        NodeFieldBinder.attach(node, type, document, null, null);
        frame();

        assertNull("a vector port editor's box must carry no EDITOR_LABEL_CLASS text at all",
                axisLabelTextFor(node, "Center"));
    }

    /** The axis-prefix label text on {@code portId}'s floating editor box, or {@code null} if it has
     * none (the vector case). */
    private String axisLabelTextFor(GraphNode node, String portId) {
        UIElement control = node.portNamed(portId).getDefaultEditor();
        UIElement box = control.getParent();
        for (UIElement child : box.getChildren()) {
            if (child instanceof com.crystalgui.ui.elements.UIText text
                    && text.hasClass(NodePort.EDITOR_LABEL_CLASS)) {
                return text.getText();
            }
        }
        return null;
    }

    /** Typing into the port's control writes back through the SAME undo-aware path a body field uses —
     * {@code NodeFieldBinder.write}, not a shortcut that skips {@code SetNodeFieldEdit}. */
    @Test
    public void editingThePortControlWritesToTheDocument() {
        openWindow();
        GraphDocument document = new GraphDocument();
        NodeType type = numberPortType();
        GraphNode node = buildNode(type, document);
        NodeFieldBinder.attach(node, type, document, null, null);
        window.updateWithoutPainting();

        NumberControl control = (NumberControl) node.portNamed("Value").getDefaultEditor();
        control.field().setText("2.5");

        assertEquals("2.5", document.node(node.getNodeId()).properties().get("Value"));
    }

    /**
     * A vec2 port default is real VECTOR (two cells), and a vec4 default is COLOR (a swatch) — both
     * per {@code ShaderGraphBridge.widgetKindFor}. Neither is a bare textfield any more, and both must
     * fit once actually mounted and laid out on the plane, not merely constructed.
     */
    @Test
    public void aVectorAndAColorPortEditorBothFitOnceMounted() {
        openWindow();
        GraphDocument document = new GraphDocument();
        NodeType type = NodeType.of("t:composite-ports").label("Node")
                .in("UV", "vec2", new NodeField("UV", "UV", NodeField.Kind.VECTOR,
                        java.util.List.of(), "vec2(0.000, 0.000)", null))
                .in("Tint", "vec4", NodeField.color("Tint", "Tint", "vec4(1.000, 1.000, 1.000, 1.000)"))
                .out("Out", "vec4")
                .build();
        GraphNode node = buildNode(type, document);
        NodeFieldBinder.attach(node, type, document, null, null);
        frame();

        UIElement uvEditor = node.portNamed("UV").getDefaultEditor();
        UIElement tintEditor = node.portNamed("Tint").getDefaultEditor();
        assertTrue(uvEditor instanceof VectorControl);
        assertTrue(tintEditor instanceof ColorControl);

        // A floor, not an exact match — VECTOR/COLOR sizing here is intentionally looser than the kit
        // height enforced elsewhere (see graph.css's own note on this being a third, tighter scale).
        // What must never happen is either collapsing to zero, which is what "the CSS never reached the
        // new element" looks like.
        assertTrue("a vector port editor must have real height", height(uvEditor) > 0f);
        assertTrue("a colour port editor must have real height", height(tintEditor) > 0f);
        assertTrue("a vector port editor must have real width", uvEditor.getRuntimeCache().getWidth() > 0f);
        assertTrue("a colour port editor must have real width", tintEditor.getRuntimeCache().getWidth() > 0f);
    }

    private static float height(UIElement e) {
        return e.getRuntimeCache().getHeight();
    }

    /**
     * <b>A vector port editor's own X/Y fields must not collapse to zero width — and the single-vector
     * case above cannot catch this.</b>
     *
     * <p>{@code .__config-control__.__vector__ .__number__} is {@code width: 0; flex-grow: 1} — correct
     * everywhere else a {@code VectorControl} lives (the inspector column, a node's own control row),
     * both genuinely bounded flex contexts where {@code flex-grow} has real slack to distribute. {@code
     * PortDefaultEditor}'s floating box is not: it is {@code position: absolute}, sized to its own
     * content, so a {@code flex-grow: 1} item inside it has nothing to grow into. In real CSS a flex
     * item's default {@code min-width} is {@code auto} — it floors at its own content's minimum size
     * regardless — but this engine's Taffy default is {@code min-size: 0} (see AGENTS.md's own "Taffy
     * defaults diverge from CSS" table), so nothing stopped the collapse. graph.css now floors {@code
     * .__vector-cell__}/{@code .__number__} specifically inside {@code graphview .__editor__}.</p>
     *
     * <p>Two vector ports on one node, not one: {@link #aVectorAndAColorPortEditorBothFitOnceMounted}
     * already has a single vector port and was already green while this bug was live — the collapse
     * only reproduced with a second one present, so a fix that only re-tested the single-port shape
     * would not have proven anything.</p>
     */
    @Test
    public void twoVectorPortEditorsOnOneNodeBothKeepRealComponentWidths() {
        openWindow();
        GraphDocument document = new GraphDocument();
        NodeType type = NodeType.of("t:two-vectors").label("Node")
                .in("UV", "vec2", new NodeField("UV", "UV", NodeField.Kind.VECTOR,
                        java.util.List.of(), "vec2(0.500, 0.500)", null))
                .in("Center", "vec2", new NodeField("Center", "Center", NodeField.Kind.VECTOR,
                        java.util.List.of(), "vec2(0.500, 0.500)", null))
                .out("Out", "vec2")
                .build();
        GraphNode node = buildNode(type, document);
        NodeFieldBinder.attach(node, type, document, null, null);
        frame();
        frame(); // extra settle — the collapse this pins showed up steady-state, not just frame one

        for (String portId : new String[] {"UV", "Center"}) {
            VectorControl control = (VectorControl) node.portNamed(portId).getDefaultEditor();
            for (NumberControl component : control.components()) {
                assertTrue(portId + "'s component collapsed to zero width",
                        component.getRuntimeCache().getWidth() > 0f);
            }
        }
    }

    /**
     * <b>The actual live-session bug: a port's default editor gets replaced AFTER {@code GraphView} has
     * already discovered and mounted one for it.</b>
     *
     * <p>{@code NodePort}'s constructor already calls {@code setDefaultEditor} with whatever the port's
     * {@code PortType} supplies (null for the plain types this file's helpers use, but real shader ports
     * can supply their own), and {@link NodeFieldBinder#attach} calls it again later with the real,
     * document-declared control — on a node added after the scene is already running, that second call
     * happens lazily on a DIFFERENT ticker than {@code GraphView}'s own discovery, so which one runs first
     * on any given frame is a coin flip. If discovery wins, the old code snapshotted whatever control was
     * live at that instant into a {@link PortDefaultEditor} FOREVER — {@code portEditors.containsKey(port)}
     * refused to ever look again — so the box kept showing the FIRST control, frozen, while the port's own
     * {@code getDefaultEditor()} quietly pointed at a second control nobody ever mounted. That is exactly
     * what a floating vector editor "stuck at its very first (pre-layout) position, forever" looks like.</p>
     *
     * <p>Simulated directly rather than raced: {@code setDefaultEditor} is called twice with two distinct
     * controls, with a real discovery tick in between — deterministic, and exercises the same code path
     * {@code onDefaultEditorChanged} exists to close.</p>
     */
    @Test
    public void aDefaultEditorReplacedAfterDiscoveryStillGetsShown() {
        openWindow();
        GraphDocument document = new GraphDocument();
        NodeType type = numberPortType();
        GraphNode node = buildNode(type, document);
        NodePort port = node.portNamed("Value");

        // NodeFieldBinder hasn't run yet; simulate a PortType-level placeholder existing before it does.
        assertNull("nothing should exist yet — NodeFieldBinder was never asked to attach",
                port.getDefaultEditor());
        NumberControl placeholder = new NumberControl(
                com.crystalgui.core.config.ConfigDescriptor.number("Value", "Value"), 0d);
        port.setDefaultEditor(placeholder);
        frame(); // GraphView discovers and mounts the placeholder, exactly as if its ticker ran first

        PortDefaultEditor editorBefore = view.portEditorFor(port);
        assertNotNull(editorBefore);
        assertSame("discovery must have wrapped the placeholder", placeholder, editorBefore.control());

        // The real field binder runs late, as it would on a node added via the create menu mid-session.
        NodeFieldBinder.attach(node, type, document, null, null);
        NumberControl real = (NumberControl) port.getDefaultEditor();
        assertNotSame("the field binder must have produced a DIFFERENT control instance",
                placeholder, real);
        frame();

        PortDefaultEditor editorAfter = view.portEditorFor(port);
        assertNotNull("the port must still have a tracked editor after the swap", editorAfter);
        assertSame("the floating editor must now wrap the REAL, document-declared control — not the "
                + "placeholder frozen at discovery time", real, editorAfter.control());
        assertTrue("the real control must actually be mounted and laid out",
                editorAfter.isMounted());
    }

    /**
     * <b>A vector editor's axis label and number field must sit INSIDE their own cell.</b>
     *
     * <p>The bug this pins rendered the {@code X 0.5  Y 0.5} pair hundreds of pixels below the box that
     * owns it, while that box drew empty beside the port. Everything about the pair was right except its
     * y: correct size, correct x, correct parent. It was vertically centred in a cell that had been
     * ~550px tall for one layout pass and had not been that tall since.</p>
     *
     * <p><b>Cause:</b> this engine's flex {@code align-items} default is {@code stretch} (Taffy's, not
     * CSS's), and {@code .__vector__} declared none — so each {@code __vector-cell__} took its cross size
     * from the box. The box is {@code position: absolute} with an auto height, so on the pass right after
     * mounting (before {@code reposition()}'s inline {@code top} lands) it was laid out against the whole
     * plane. The cells stretched with it, their own {@code align-items: center} parked the grandchildren
     * at {@code (550-12)/2 ≈ 269}, and when the box settled to ~18px Taffy repositioned the cells but not
     * the grandchildren two levels down. graph.css now pins {@code align-items: center} on
     * {@code .__vector__} so a cell is content-sized and no transient box height can reach inside it.</p>
     *
     * <p>Asserted as "the cell is content-sized, and each grandchild is within its bounds" rather than by
     * reproducing the transient pass: the stretch is the thing that made the staleness reachable, so
     * removing it is what the test should defend. The scalar editor is checked alongside it because it was
     * always correct — its parts are direct children of the box, one level up, and that level does get
     * repositioned — so it is the control case proving the assertion is about nesting depth.</p>
     */
    @Test
    public void aVectorPortEditorsFieldsSitInsideTheirOwnCell() {
        openWindow();
        GraphDocument document = new GraphDocument();
        NodeType type = NodeType.of("t:vector-cell-fit").label("Node")
                .in("Center", "vec2", new NodeField("Center", "Center", NodeField.Kind.VECTOR,
                        java.util.List.of(), "vec2(0.500, 0.500)", null))
                .in("Scale", "float", NodeField.number("Scale", "Scale", "1.0").onPort("Scale"))
                .out("Out", "vec2")
                .build();
        GraphNode node = buildNode(type, document);
        NodeFieldBinder.attach(node, type, document, null, null);
        frame();
        frame();

        VectorControl vector = (VectorControl) node.portNamed("Center").getDefaultEditor();
        UIElement box = vector.getParent();
        float boxHeight = box.getRuntimeCache().getHeight();
        assertTrue("the box must be content-sized, not stretched to the plane: " + boxHeight,
                boxHeight > 0f && boxHeight < 80f);

        for (UIElement cell : vector.getChildren()) {
            float cellTop = cell.getRuntimeCache().getY();
            float cellHeight = cell.getRuntimeCache().getHeight();
            // A stretched cell is the precondition for the whole failure — it is what gives the
            // grandchildren a huge box to be centred in.
            assertTrue("a vector cell must size to its content, not stretch: " + cellHeight,
                    cellHeight > 0f && cellHeight <= boxHeight);

            for (UIElement inner : cell.getChildren()) {
                float top = inner.getRuntimeCache().getY();
                float bottom = top + inner.getRuntimeCache().getHeight();
                assertTrue(inner.getClass().getSimpleName() + " sits " + (top - cellTop)
                                + "px below its cell instead of inside it",
                        top >= cellTop - 1f && bottom <= cellTop + cellHeight + 1f);
            }
        }
    }

    /**
     * <b>The bug actually seen live: a node removed through the document's changeset, not through
     * {@link GraphView#removeNode}, leaves its floating default editor orphaned on the plane forever.</b>
     *
     * <p>{@link GraphView#detachNode} and the "never bound" branch of {@link GraphView#removeNode} both
     * call {@link GraphView#portEditorFor its own} port-editor cleanup before detaching a widget — but
     * {@link GraphView#syncFromDocument} used to skip it for nodes it discovered as removed via the
     * document's own changeset (an undo of an add, a server sync, or — the case that matters here — a
     * {@code document.removeNode} call that does not go through the view). The box and dot are mounted
     * straight onto {@link GraphView#content()}, never as descendants of the node, so
     * {@code content().removeChild(widget)} alone never reaches them: they stayed mounted, stayed
     * hit-testable, and never repositioned again, because the port and dot they were tracking left the
     * tree with the node and stopped laying out. A NEW node built afterwards gets its own, correctly
     * tracked editor — so the symptom is two widgets where the live document only has one.</p>
     *
     * <p>Removes through {@link GraphView#getDocument()}, not a separately constructed
     * {@code GraphDocument} — {@code GraphView} owns its own document instance, and every other test in
     * this file only ever hands its own local one to {@link NodeFieldBinder} for property reads/writes,
     * never to the view. Removing on that local copy would be invisible to {@link GraphView#syncFromDocument},
     * which reads {@code getDocument().changeset()} — proving nothing about the bug this pins.</p>
     */
    @Test
    public void aNodeRemovedThroughTheChangesetTakesItsFloatingEditorWithIt() {
        openWindow();
        NodeType type = numberPortType();
        GraphDocument document = view.getDocument();
        GraphNode node = buildNode(type, document);
        NodeFieldBinder.attach(node, type, document, null, null);
        frame();

        NodePort port = node.portNamed("Value");
        UIElement editor = port.getDefaultEditor();
        assertNotNull("must be mounted before the removal this test exercises",
                editor.getAttachedWindow());
        assertNotNull(view.portEditorFor(port));

        // Bypasses view.removeNode entirely — exactly what an undo, a server push, or any other
        // document-side removal looks like from GraphView's perspective.
        document.removeNode(node.getNodeId());
        view.syncFromDocument();
        frame();

        assertNull("the floating editor must not survive a removal that arrived through the changeset "
                + "any more than one that arrived through removeNode", editor.getAttachedWindow());
        assertNull("GraphView must stop tracking it too, or the next tick keeps trying to reposition a "
                + "port that no longer lays out", view.portEditorFor(port));
    }
}
