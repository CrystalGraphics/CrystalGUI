package com.crystalgui.widget.graph;

import com.crystalgui.ui.dom.Name;
import com.crystalgui.core.data.DataProvider;
import com.crystalgui.ui.input.keymap.Keymap;
import com.crystalgui.widget.graph.node.NodeCreationMenu;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.ui.box.Box;
import com.crystalgui.core.data.ClipboardActions;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.core.settings.SettingsLayer;
import com.crystalgui.graph.EdgeData;
import com.crystalgui.graph.GraphProperty;
import com.crystalgui.graph.GraphChangeset;
import com.crystalgui.graph.GraphDocument;
import com.crystalgui.graph.GraphIds;
import com.crystalgui.graph.NodeData;
import com.crystalgui.graph.NodeType;
import com.crystalgui.graph.PortRef;
import com.crystalgui.graph.PortSpec;
import com.crystalgui.graph.NodeTypeRegistry;
import com.crystalgui.graph.TypeCompatibility;
import com.crystalgui.core.undo.Edit;
import com.crystalgui.core.undo.UndoCommands;
import com.crystalgui.core.undo.UndoScope;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.widget.surface.edit.Clipboard;
import com.crystalgui.widget.surface.edit.Edits;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.data.UiDataKeys;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.data.DataKey;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.ui.service.Input;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import dev.vfyjxf.taffy.style.TaffyPosition;
import org.joml.Vector2f;
import com.crystalgui.core.undo.CompositeEdit;
import com.crystalgui.widget.canvas.CanvasView;
import com.crystalgui.widget.surface.Surface;
import com.crystalgui.widget.surface.SurfaceEditor;
import com.crystalgui.widget.surface.select.SurfaceSelection;
import com.crystalgui.widget.surface.SurfacePolicy;
import com.crystalgui.widget.surface.mode.Marquee;
import com.crystalgui.widget.surface.mode.MoveGesture;
import com.crystalgui.widget.surface.select.Picking;
import com.crystalgui.widget.canvas.WorldRect;
import lombok.Getter;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A {@link CanvasView} that knows about nodes and wires: it owns the edge set, the wire layer that
 * paints it, and the rules about what may connect to what.
 *
 * <pre>{@code
 * GraphView graph = new GraphView();
 * GraphNode position = new GraphNode("Position");
 * NodePort out = position.addOutput(VEC3, "Out");
 * graph.addNode(position, 40f, 40f);
 * graph.connect(out, add.addInput(VEC3, "A"));
 * }</pre>
 *
 * <h3>Why the edges live here and not on the ports</h3>
 * <p>A port could hold its own list, and then "which edges exist" would have as many answers as there
 * are ports. One owner means the replace-on-occupied-input rule, the duplicate check and the
 * connection counts are all decided in one place — and it is the place a command (6.2.4) will call
 * into, so undo has a single seam rather than needing to walk the tree putting ports back.</p>
 *
 * <p><b>The tag trap applies here.</b> {@code GraphView extends SurfaceEditor} but reports the tag
 * {@code graphview}, and a {@code canvasview} rule matches none of it — a widget's cascade identity is
 * its tag, never its Java supertype. Anything the canvas needs from a stylesheet must name
 * {@code graphview} too; the viewport's structural styling is written from Java at DEFAULT origin
 * precisely so this class inherits it for real.</p>
 */
public class GraphView extends SurfaceEditor {

    /**
     * This widget's kind.
     *
     * <p>Declared here rather than in a vocabulary class, and declared AT ALL because a subclass
     * inherits its parent's kind unless it is given its own: without this, GraphView reports
     * {@code crystalgui:element} (or its supertype's) and every rule the sheets write for
     * {@code graphview} matches nothing at all — no background, no border, an unstyled widget that
     * reads as one that was never built.</p>
     */
    public static final Name NAME = Name.of("graphview");

    /** This graph, for a command that acts on one — replaces {@code GraphCommands.graphFor}'s walk. */
    // `.new` UNTIL 6.9, following the convention 6.3 set for `menuBar.new`. A DataKey is interned by
    // NAME and its TYPE is what it names, so the old engine's copy and this one cannot share a key --
    // `create` throws `already declared as ..., not ...` the moment both classes initialise, which is
    // any test that touches both. The OLD name is the one that must not move: `ContextKeys.find`
    // resolves a key by name out of a `when` expression, so renaming the shipped one would silently
    // break every command declaration naming it.
    public static final DataKey<GraphView> GRAPH_VIEW =
            DataKey.create("graphView.new", GraphView.class);

    /**
     * Every graph gets its commands, with nothing installing them.
     *
     * <p>The engine calls this once for this class — see {@link UIElement#registerCommands}. Before, a
     * host had to call {@code GraphCommands.install(window)} and a graph dropped into a scene that
     * forgot had no Delete, no Select All and no framing, silently.</p>
     */
    @Override
    protected void registerCommands(CommandRegistry registry) {
        GraphCommands.register();
        // Undo comes with any graph, because a graph is an UndoScope -- the stack is resolved from
        // focus, so this needs no element and binds to nothing.
        UndoCommands.register();
    }

    /**
     * The graph's own chords, on the graph.
     *
     * <p>Per instance, and it has to be: {@code F}, {@code A} and {@code Space} are <b>bare letters</b>,
     * so they may only be live while focus is inside a graph. Declaring them on the commands would make
     * them application-wide and cost every text field in the application three letters.</p>
     *
     * <p>This was missing for one commit and nothing failed: registration had moved to
     * {@link #registerCommands} while the binding half was still waiting on a host to call
     * {@code GraphCommands.install(window)} — which by then nothing did. Every graph command existed, was
     * enabled, showed in the palette, and answered no key at all.</p>
     */
    /**
     * This view's own key bindings.
     *
     * <p>{@code bindKeys()} was an engine hook on the old element and there is none here — a node
     * declares its keymap by ANSWERING for one ({@link com.crystalgui.ui.input.keymap.KeymapScope}),
     * so the binding happens once, in the constructor, and {@link #keymapOrNull()} hands it to the
     * resolver. The commit that lost this on the old engine lost it silently: every graph command
     * existed, was enabled, showed in the palette, and answered no key at all.</p>
     */
    private final Keymap keymap = defaultKeymap();

    private static Keymap defaultKeymap() {
        Keymap keymap = new Keymap();
        GraphCommands.bindDefaults(keymap);
        return keymap;
    }

    @Override
    public Keymap keymapOrNull() {
        return keymap;
    }

    /**
     * What this graph knows: itself, its selected nodes, and its undo history.
     *
     * <p>{@code super.getData(key)} last, so the generic {@code ELEMENT} answer stays reachable — the
     * rule every override of this method follows.</p>
     */
    /**
     * What Cut/Copy/Paste mean in a node graph — nodes and the wires between them.
     *
     * <p>Runs the registered graph commands rather than reaching for the clipboard itself, so the one
     * copy of "what does cutting a selection do" stays in {@code GraphCommands} where its undo grouping
     * and its paste offset already live. @see com.crystalgui.core.data.ClipboardActions</p>
     */
    private final ClipboardActions clipboardActions = new ClipboardActions() {
        @Override
        public boolean canCut() {
            return isEnabled(GraphCommands.CUT);
        }

        @Override
        public void cut() {
            run(GraphCommands.CUT);
        }

        @Override
        public boolean canCopy() {
            return isEnabled(GraphCommands.COPY);
        }

        @Override
        public void copy() {
            run(GraphCommands.COPY);
        }

        @Override
        public boolean canPaste() {
            return isEnabled(GraphCommands.PASTE);
        }

        @Override
        public void paste() {
            run(GraphCommands.PASTE);
        }

        private boolean isEnabled(String id) {
            com.crystalgui.core.command.Command command =
                    com.crystalgui.core.command.CommandRegistry.global().get(id);
            return command != null && command.isEnabled(
                    com.crystalgui.core.command.CommandContext.of(GraphView.this));
        }

        private void run(String id) {
            com.crystalgui.core.command.CommandRegistry.global().run(id,
                    com.crystalgui.core.command.CommandContext.of(GraphView.this));
        }
    };

    @Override
    public Object getData(DataKey<?> key) {
        if (key == GRAPH_VIEW) return this;
        if (key == UiDataKeys.CLIPBOARD) return clipboardActions;
        if (key == UiDataKeys.SELECTION) {
            return new java.util.ArrayList<Object>(getSelection().nodes());
        }
        Object undo = undoScopeData(key);
        // NO super: a UIElement is not a DataProvider, and the walk outward through
        // `commandParent()` is what reaches the next one. Answering null is how this one says
        // it has nothing, which is what lets an outer provider be found at all.
        return undo;
    }


    /**
     * Logical px, before zoom — Unity's wire is a hairline, and this used to be twice it.
     *
     * <p>The error was easy to make and worth recording: the reference screenshots are at 100%, while
     * the harness runs at {@code uiScale} 2, so a "2px" wire drew four physical pixels against Unity's
     * one and a half. A logical width compared against a physical reference is off by exactly the scale
     * factor, and looks merely "a bit heavy" rather than obviously wrong.</p>
     */
    private static final float DEFAULT_WIRE_WIDTH = 1f;

    /**
     * REMOVED — kept here as a record of why. This used to floor a wire's on-screen thickness at 1
     * physical-ish logical px so it survived zooming out; {@link #getWireWidth()} computed
     * {@code max(wireBaseWidth, MIN_WIRE_SCREEN_WIDTH / zoom)} against it. That is exactly what made a
     * wire read as the SAME thickness at every zoom level below 1 — the clamp was engineered to counteract
     * the pose's own scaling, so at zoom 0.5 the pre-pose width doubled and the pose halved it right back.
     * Unity's own wires genuinely get thinner zoomed out, same as a border under a CSS
     * {@code transform: scale()} would — a stroke is content, not chrome, and content shrinks with the
     * view. {@link #getWireWidth()} now returns {@link #wireBaseWidth} unclamped and lets the pose do
     * the whole job, the same as every other {@code curve()}/{@code quad()} caller in the engine.
     */

    private final List<GraphConnection> connections = new ArrayList<>();
    private final List<GraphConnection> connectionsView = Collections.unmodifiableList(connections);

    /**
     * The data this view projects. <b>The document is the model; the widgets are the projection.</b>
     *
     * <p>Every mutation here writes through to it, so the two can never disagree — which is what makes
     * saving, loading, duplicating and a server-authored graph ordinary rather than special. The view
     * keeps its own {@link GraphConnection} list because the wire layer draws from widget geometry, but
     * that list is derived: {@link #load} rebuilds it from the document and nothing else may.</p>
     */
    @Getter
    private GraphDocument document = new GraphDocument();

    /** id → widget. The only way back from document data to the thing on screen. */
    private final Map<String, GraphNode> widgetsById = new LinkedHashMap<>();

    private final NodeWireLayer wireLayer;

    /**
     * One {@link PortDefaultEditor} per port whose {@link NodePort#getDefaultEditor()} is currently
     * non-null — rebuilt via {@link #rebuildPortEditor} every time that control is replaced, kept
     * mounted/unmounted as the port connects and disconnects. See {@link PortDefaultEditor}'s own class
     * javadoc for what it owns and why it is a class of its own rather than a handful of parallel maps
     * here.
     */
    private final Map<NodePort, PortDefaultEditor> portEditors = new LinkedHashMap<>();

    /** Ports {@link #watchPort} has already wired a listener onto — the once-only guard now
     * that discovery itself is push-based. See {@link #rebuildPortEditor}'s own javadoc for why a port is
     * watched from the moment it is first seen, not from the moment it first has a non-null editor. */
    private final Set<NodePort> watchedPorts = new LinkedHashSet<>();

    /**
     * This document's history.
     *
     * <p>Owned here because the edges are owned here: a command has one seam to call into rather than
     * needing to walk the tree putting ports back. Implementing {@link UndoScope} is what lets
     * {@code edit.undo} find it — the nearest scope outward from whatever has focus, so a graph in one
     * tab and an editor in another never share a history.</p>
     *
     * <p><b>Only document state goes through it.</b> Pan, zoom, selection and collapse are view state
     * and are mutated directly; Ctrl+Z after wiring up a graph must undo the wire, not the scroll.</p>
     */
    /**
     * The one door every change to this graph goes through — the engine's.
     *
     * <p>The stack under it is the surface's own, resolved from focus by {@code UndoScope}, so a graph
     * in one tab and an editor in another never share a history. A transaction opened here is one undo
     * step however many nodes it moved.</p>
     *
     * <p><b>Only document state goes through it.</b> Pan, zoom, selection and collapse are view state
     * and are mutated directly; Ctrl+Z after wiring up a graph must undo the wire, not the scroll.</p>
     */
    private final Edits edits = edits();

    /** The wire under the pointer, or null. Drives the hover thickening — a wire cannot carry {@code
     * :hover} itself, having no element. */
    @Getter
    @Nullable
    private GraphConnection hoveredWire;

    @Getter
    private float wireBaseWidth = DEFAULT_WIRE_WIDTH;

    /** Fires after any change to the edge set — connect, disconnect, or a node leaving with wires on it. */
    public final Signal.Action onConnectionsChanged = new Signal.Action();


    /** What a graph means by the engine's questions. @see GraphPolicy */
    @Override
    protected SurfacePolicy createPolicy() {
        return new GraphPolicy();
    }

    /** A graph's selection answers two typed questions the engine's does not. @see GraphSelection */
    @Override
    protected SurfaceSelection createSelection(SurfacePolicy policy) {
        return new GraphSelection();
    }

    public GraphView() {
        super(NAME, List.of());
        wireLayer = new NodeWireLayer(this, connections);
        // First, so it paints under every node: equal z-index siblings paint in insertion order.
        addNode(wireLayer, 0f, 0f);
        // A painter is not a node. Culling tests an element's box, and this one's box says nothing
        // about where its wires are — left cullable, it would vanish the moment the view left world
        // origin, taking every wire with it. It culls per wire instead, where the endpoints are known.
        setCullExempt(wireLayer, true);

        // The graph must be able to HOLD focus, or none of its keys work.
        //
        // requestFocus refuses anything whose policy is NONE, which is the default — so the canvas took
        // no focus, every graph command resolved no GraphView from the focused element, and Delete,
        // Ctrl+A and Escape disabled themselves while the widget looked entirely alive. Pressing a node
        // happened to work because a node is CLICK-focusable, which made the failure look like "some
        // keys work and some do not".
        //
        // CLICK rather than FOCUSABLE: a canvas is not a tab stop. You reach it by pressing it, the way
        // you reach one in every editor.
        setFocusPolicy(FocusPolicy.CLICK);


        this.events.getGroup(MouseEvent.Down.class).attachListener((el, event) -> {
            if (!isEnabled() || event.getButtonId() != CgMouseCodes.LEFT_BUTTON) return;
            // Not a real pointer press. Space/Enter on a focused element synthesize a mouse press so
            // Button and friends get keyboard activation for free, and this view has to be focusable for
            // its command keys to resolve at all — so Enter arrived here as a left-click at wherever the
            // cursor happened to be and started a rubber band. It could not be dismissed either: a
            // marquee ends through the real pointer-up path, which a synthesized Up never reaches.
            //
            // A marquee means "the pointer went down HERE", which is not something a key can mean.
            if (event.getDetail() == Input.KEYBOARD_DETAIL) return;
            // A press that reached the graph itself landed on empty canvas: a node claims its own press
            // in the capture phase, and a port claims one before that. So this is the marquee's press —
            // unless a wire is under it, which is the only thing here that is drawn but not an element.
            if (isBackgroundGestureExempt(((UIElement) event.getTarget()))) return;
            // A press inside a NODE is never the canvas's, even when the node did not claim it.
            //
            // GraphNode stops propagation only for presses it turns into a move-drag; a press on its
            // controls it deliberately ignores, so the widget underneath can have it — and that press
            // then arrived here and was read as "empty canvas". This view would take pointer focus and
            // start a marquee WITH POINTER CAPTURE, which is fatal to any control in a node: the capture
            // swallows the release, and the focus change closes whatever popover the press just opened.
            // A Dropdown in a node looked completely dead because of it.
            //
            // Asking about the target rather than relying on every node to stop propagation is the
            // robust half: a widget that legitimately wants a press to pass through should not have to
            // know that letting it through starts a rubber band three levels up.
            if (isInsideNode(((UIElement) event.getTarget()))) return;
            if (beginMarqueeOrPickWire(event.getPosition().x(), event.getPosition().y())) {
                event.stopPropagation();
            }
        }, false, true);

        // A wire is painted, not laid out, so nothing in the hit-test tree knows where it is and it can
        // never receive a :hover of its own — the same trade that makes pickWire exist for presses. The
        // pointer position has to be re-tested against the curves each time it moves.
        this.events.getGroup(MouseEvent.Move.class).attachListener((el, event) -> {
            GraphConnection was = hoveredWire;
            hoveredWire = isBackgroundGestureExempt(((UIElement) event.getTarget()))
                    ? null
                    : wireAt(event.getPosition().x(), event.getPosition().y());
            // Nothing to invalidate — the layer repaints every frame and reads this directly. Kept as a
            // field rather than recomputed in paint so the pick runs once per move, not once per frame.
            if (was != hoveredWire) markTreeDirty();
        }, false, true);
    }

    /** Whether {@code target} is this graph's own node, or anything inside one — or a floating
     * default-value editor, which is neither: it is a plane child of its own, sitting beside its node
     * rather than inside it (see {@link NodePort#getDefaultEditor()}). Without the second check, a press
     * on one fell through as "empty canvas" and started a marquee under whatever the user was trying to
     * type into — the exact conflict {@code NodePort} used to guard against back when this editor lived
     * inside the port. */
    private boolean isInsideNode(@Nullable UIElement target) {
        for (UIElement element = target; element != null && element != this; element = element.parentElement()) {
            if (element instanceof GraphNode) return true;
            if (element.hasClass(NodePort.EDITOR_CLASS)) return true;
        }
        return false;
    }

    /** The wire under a viewport-space point, or null. */
    @Nullable
    public GraphConnection wireAt(float rawX, float rawY) {
        Vector2f world = screenToWorld(rawX, rawY);
        return wireLayer.pickWire(world.x(), world.y());
    }

    /** On the rubber band, so a theme owns its look. */
    public static final String MARQUEE_CLASS = "__marquee__";

    /** The rubber-band element, for a theme or a test. */
    public UIElement marqueeElement() {
        return marquee().element();
    }

    public boolean isMarqueeActive() {
        return marquee().isActive();
    }

    /** The layer that draws the wires. Exposed for a theme or a test to reach; it owns no state a
     * caller should be setting. */
    public NodeWireLayer wireLayer() {
        return wireLayer;
    }

    // ── Port default editors ────────────────────────────────────────────────

    /**
     * Repositions the mounted port editors, every frame.
     *
     * <p><b>Discovery is not here any more</b> — see {@link #watchPort}. What remains genuinely is
     * per-frame: a floating editor is positioned in world space off its port's live layout, so it moves
     * whenever the plane pans, zooms or reflows, and there is no single announcement for "the geometry
     * under me settled". This is a position sync, not a scan for work.</p>
     *
     * <p>Always ticking, regardless of {@link #setCullingEnabled}: a floating editor still has to track
     * its port even in a huge graph where node culling is doing real work, so this cannot piggyback on
     * {@link CanvasView#tickFrame}'s own early-out.</p>
     */
        public boolean tickFrame(float deltaSeconds) {
        super.tickFrame(deltaSeconds);
        for (PortDefaultEditor editor : portEditors.values()) {
            if (editor.isMounted()) editor.reposition();
        }
        return true;
    }

    /**
     * Geometry that can only be settled once layout has run.
     *
     * <p>{@code onLayoutChanged()} on the old engine; there is no such override here, because layout
     * is ONE pass with no feedback into it. A post-layout hook may move a box and read a box and may
     * not add one — a structural change would need a second pass, and there is not one.</p>
     */
    private void onLayoutSettled() {
        // super's own ensureTicking() only registers while culling is enabled — this view needs to tick
        // unconditionally, for the reason tickFrame's javadoc gives. registerTicker is HashSet-backed, so
        // calling it again every layout pass is idempotent rather than wasteful.
        UIDocument window = document();
        if (window != null) document().animation().every(this, this::tickFrame);
    }

    /**
     * Registers this widget's own commands, bound on <b>itself</b>, once it has a window.
     *
     * <p><b>The widget owns them, exactly as {@code TextEditor} owns {@link
     * com.crystalgui.ui.elements.editor.EditorCommands}.</b> Delete, Space to create a node, F to frame,
     * Ctrl+Z — those are not a host's choices, they are what a node graph <em>is</em>, and a graph that
     * does nothing on Delete is broken rather than neutral. Leaving it to the host meant one harness scene
     * installed them and nothing else did, so the shader graph in the dock took focus, drew a selection,
     * and answered no key at all. Twice: once for {@code GraphCommands}, once for undo.</p>
     *
     * <h3>Bound on this element, never on the window root</h3>
     *
     * <p>The defaults include bare {@code A}, {@code F}, {@code Space} and {@code Backspace}. A keymap
     * resolves from the focused element outward, so at the root those would fire while typing into any
     * text editor that happens to share the window — which, in a dock, is most of them. Scoped here they
     * exist exactly while focus is inside the graph.</p>
     *
     * <h3>Undo binds {@code edit.undo}; it does not invent {@code graph.undo}</h3>
     *
     * <p>{@link UndoCommands}' own rule, and the reason it stays correct: {@link UndoScope#nearest} walks
     * outward from whatever was focused and finds <em>this</em> view's stack, so one command id serves
     * every history in the window and the palette shows one Undo rather than one per widget. This is the
     * same thing {@code TextEditor} does with the same ids.</p>
     *
     * <p>Which is also why binding it here does not conflict with a host that installs undo application-
     * wide: the inner scope wins while focus is inside the graph, and both routes end at the same lookup.</p>
     */

    /**
     * Finds input ports that have never been seen before and starts watching them — {@code onBlankChanged}
     * for mount state, {@code onDefaultEditorChanged} for the control itself — then does one initial
     * {@link #rebuildPortEditor} in case the port already has an editor.
     *
     * <p>A per-tick scan over the (small) node set, the same shape as {@code ShaderGraphPreviews}'s own
     * new-node discovery — there is no signal for "a node was added" any more than there is one for "a
     * port exists now", so this is still how a port is FOUND at all. What no longer happens here is
     * deciding whether it has an editor worth keeping: {@link #rebuildPortEditor} owns that, and it is
     * reachable from {@code onDefaultEditorChanged} too, not just from this scan.</p>
     */
    /**
     * Gives every input port on {@code node} an editor, once.
     *
     * <p>Called when a node <b>joins the view</b> — the two places a widget is registered — rather than
     * from a frame. See {@link #watchPort}.</p>
     */
    private void watchPortsOf(GraphNode node) {
        for (NodePort port : node.getInputPorts()) watchPort(port);
    }

    /**
     * Gives one input port its {@link PortDefaultEditor}, once.
     *
     * <h3>Why this is not a per-frame scan any more</h3>
     *
     * <p>It was: {@code tickFrame} walked every node and every input port of every node, every frame,
     * to notice the ones it had not seen — the same shape plan step 3 deleted five times over, and the
     * last one left in the engine. The cost is not the {@code Set.add} but the walk itself, which is
     * O(nodes × ports) on a graph where the answer changes a handful of times in a session.</p>
     *
     * <p>A port becomes visible to this view at exactly <b>two</b> moments, and both are already known
     * here: a node is registered with ports already on it, or {@link GraphNode#addPort} adds one to a
     * node that is already in a view — which was the reason for the scan, since a node built by a factory
     * gains its ports before it joins anything and {@code graphView()} is null throughout. {@code addPort}
     * already calls back into this view for {@code syncPorts}; it now says this too.</p>
     *
     * <p>Idempotent through {@code watchedPorts}, so overlapping calls cost a set lookup. Ports are never
     * removed from a node — only whole nodes are, through {@code forgetPortEditor} — so the watch set
     * needs no pruning beyond that.</p>
     */
    void watchPort(NodePort port) {
        if (port == null || !port.getDirection().isInput()) return;
        if (!watchedPorts.add(port)) return;
        PortDefaultEditor editor = new PortDefaultEditor(port, this);
        portEditors.put(port, editor);
        port.onBlankChanged.connect(() -> refreshPortEditor(port));
        port.onDefaultEditorChanged.connect(() -> refreshPortEditor(port));
        refreshPortEditor(port);
    }

    /**
     * Brings {@code port}'s editor back in step with the port — the control it wraps, and whether it
     * should be on the plane at all.
     *
     * <p><b>One {@link PortDefaultEditor} per port for that port's whole life, never rebuilt.</b> The
     * control genuinely can arrive after the widget exists: {@code NodeFieldBinder} binds a
     * document-declared field on whatever tick the owning node is first seen, and for a node added from
     * the create menu that is a different {@code Animation.Hook} from the one {@link #tickFrame} discovers
     * the port on, with no ordering between them. Rebuilding on that change is what previously broke a
     * vector editor: the replacement box adopted a control the old box still held, and two elements
     * claiming one child put it in two Taffy parents at once — laid out under both, wrong pass winning,
     * so the X/Y fields drew hundreds of pixels from their own frame. {@link PortDefaultEditor#syncControl}
     * swaps it in place with an explicit detach instead, so there is only ever one owner.</p>
     */
    private void refreshPortEditor(NodePort port) {
        PortDefaultEditor editor = portEditors.get(port);
        if (editor == null) return;
        editor.syncControl();
        // A port with no control yet has nothing to show — mounting an empty box would draw a stray
        // frame beside the port until the binder catches up.
        editor.setMounted(editor.hasControl() && port.isBlank());
    }

    /**
     * Draws the stub joining {@code port}'s floating default editor to its own dot — called from
     * {@link GraphNode#paintDecoration}, never invoked directly by anything on the plane. See
     * {@link PortDefaultEditor#paintStub} for why the paint call has to originate from the TARGET node
     * itself: {@code paintOverlay} is the only hook that runs after a node's own children and before its
     * own outline, which is what makes "over the body, under the ring" possible at all — no sibling
     * element, however it is z-ordered, can land between two steps of one other element's own atomic
     * paint call.
     *
     * <p>A no-op when {@code port} has no mounted editor (connected, or no default at all) — the common
     * case for most ports on most nodes, checked once via a map lookup rather than by every node walking
     * its own ports' state.</p>
     */
    void paintPortEditorStub(CgUiPaintContext ctx, NodePort port, UIElement space) {
        PortDefaultEditor editor = portEditors.get(port);
        if (editor != null && editor.isMounted()) editor.paintStub(ctx, space);
    }

    /** Drops a port's default editor from the plane and forgets it entirely — called when the port's own
     * node leaves the view, since {@link #detachNode} and {@link #load} otherwise have no way to reach a
     * floating box or dot that was never their descendant. */
    private void forgetPortEditor(NodePort port) {
        PortDefaultEditor editor = portEditors.remove(port);
        if (editor != null) editor.setMounted(false);
        watchedPorts.remove(port);
    }

    /** The {@link PortDefaultEditor} tracked for {@code port}, or {@code null} if it never had one
     * (connected output, or a {@link PortType} with no default at all). Package-private: tests are the
     * only consumer, reaching into the mechanism to assert on it directly rather than through pixels. */
    @Nullable
    PortDefaultEditor portEditorFor(NodePort port) {
        return portEditors.get(port);
    }

    // ── The document seam ───────────────────────────────────────────────────

    /**
     * Adds a node, binding it to the document.
     *
     * <p>A widget arriving without a {@code nodeId} — anything built by hand rather than from a
     * {@link NodeData} — gets one here, along with {@link NodeData} <b>derived from its own ports</b>.
     * That keeps the 6.2.3 API working unchanged while making the document complete either way: there is
     * no such thing as a node on this canvas the document does not know about, which is the property
     * every later feature (save, duplicate, a server sending a graph) depends on.</p>
     */
    @Override
    public GraphView addNode(UIElement node, float worldX, float worldY) {
        if (node instanceof GraphNode graphNode) {
            attachNode(graphNode, dataFor(graphNode, worldX, worldY));
            return this;
        }
        super.addNode(node, worldX, worldY);
        return this;
    }

    @Override
    public GraphView moveNode(UIElement node, float worldX, float worldY) {
        super.moveNode(node, worldX, worldY);
        // Position is document data — a reload has to give a moved node back where it was left.
        if (node instanceof GraphNode graphNode && graphNode.getNodeId() != null) {
            document.moveNode(graphNode.getNodeId(), worldX, worldY);
        }
        markSynced();
        return this;
    }

    /** The {@link NodeData} for a widget: its own if it already has one, otherwise derived from it. */
    private NodeData dataFor(GraphNode widget, float worldX, float worldY) {
        String existing = widget.getNodeId();
        if (existing != null) {
            NodeData known = document.node(existing);
            if (known != null) return known.movedTo(worldX, worldY);
        }
        List<PortSpec> ports = new ArrayList<>();
        for (NodePort port : widget.getPorts()) {
            ports.add(new PortSpec(port.getPortId(), port.getDirection(), port.getType().id()));
        }
        String typeId = widget.getTypeId() != null ? widget.getTypeId() : WIDGET_AUTHORED_TYPE;
        // A widget-authored node stores its own title, because there is no library type to take one
        // from and reloading it as "crystalgui:widget" is true but useless. Its controls and preview
        // still cannot come back — those are Java the document never saw — which is the honest limit of
        // building a node as a widget rather than registering a type for it.
        Map<String, String> properties = widget.getTypeId() != null
                ? Map.of()
                : Map.of(NodeWidgetFactory.TITLE_PROPERTY, widget.getTitle());
        return new NodeData(existing != null ? existing : GraphIds.generate(),
                typeId, worldX, worldY, ports, properties);
    }

    /**
     * The {@code typeId} given to a node built as a widget rather than from a document.
     *
     * <p>Honest rather than empty: it says the node was authored in the UI and has no library type
     * behind it, which is exactly what a loader needs to know to rebuild it as a placeholder.</p>
     */
    public static final String WIDGET_AUTHORED_TYPE = "crystalgui:widget";

    /**
     * The view has just made this change itself, so the changeset must not report it again.
     *
     * <p>Without this the two directions fight, and they fight <em>quietly</em>. A changeset records the
     * NET change since it was last drained, so an add the view had already applied sat pending — and a
     * later remove of that same node cancelled the add instead of recording a removal. The changeset
     * then said "nothing happened" while the view still held the widget, so
     * {@link #syncFromDocument()} left a node on screen that the document no longer had.</p>
     */
    private void markSynced() {
        document.changeset().clear();
    }

    /** Puts a node into both the document and the tree. The one path; {@link AddNodeEdit} uses it too,
     * which is what makes an undone delete restore the SAME id rather than a new one. */
    private void attachNode(GraphNode widget, NodeData data) {
        if (!document.hasNode(data.id())) document.addNode(data);
        widget.bindToDocument(data.id(), data.typeId());
        widgetsById.put(data.id(), widget);
        super.addNode(widget, data.x(), data.y());
        watchPortsOf(widget);
        markSynced();
    }

    /** Removes a node from both. */
    private void detachNode(GraphNode widget) {
        String id = widget.getNodeId();
        if (id != null) {
            document.removeNode(id);
            widgetsById.remove(id);
        }
        // A port's default editor is a SEPARATE plane child, not a descendant of the node — removing the
        // node does not take it with it. Forgotten explicitly, or a deleted node's floating field is
        // orphaned on screen forever, pointing at a port that no longer exists anywhere.
        for (NodePort port : widget.getPorts()) forgetPortEditor(port);
        content().remove(widget);
        markSynced();
    }

    /**
     * Re-derives a bound node's declared ports from its widget.
     *
     * <p>Called by {@link GraphNode#addPort}, because a node may gain ports after it joins the view.
     * Only for widget-authored nodes: one built from a library type already has the ports its type
     * declared, and re-deriving them would throw away anything the type knew that the widget does not.</p>
     */
    void syncPorts(GraphNode widget) {
        String id = widget.getNodeId();
        if (id == null) return;
        NodeData current = document.node(id);
        // The DOCUMENT decides whether this is widget-authored, not the widget. Binding sets the
        // widget's typeId to WIDGET_AUTHORED_TYPE, so a "typeId == null" test here rejected precisely
        // the nodes it existed to serve — every one of them, silently.
        if (current == null || !WIDGET_AUTHORED_TYPE.equals(current.typeId())) return;
        List<PortSpec> ports = new ArrayList<>();
        for (NodePort port : widget.getPorts()) {
            ports.add(new PortSpec(port.getPortId(), port.getDirection(), port.getType().id()));
        }
        document.replaceNode(new NodeData(id, current.typeId(), current.x(), current.y(),
                ports, current.properties()));
        markSynced();
    }

    /** The widget projecting {@code nodeId}, or null. */
    @Nullable
    public GraphNode widgetFor(String nodeId) {
        return widgetsById.get(nodeId);
    }

    /** The port a {@link PortRef} names, or null if the node or the port is not on screen. */
    @Nullable
    public NodePort portFor(PortRef ref) {
        GraphNode widget = widgetsById.get(ref.nodeId());
        if (widget == null) return null;
        for (NodePort port : widget.getPorts()) {
            if (port.getPortId().equals(ref.portId())) return port;
        }
        return null;
    }

    /** The {@link PortRef} naming a live port, or null before its node has been added. */
    @Nullable
    public static PortRef refFor(NodePort port) {
        GraphNode owner = port.node();
        if (owner == null || owner.getNodeId() == null) return null;
        return new PortRef(owner.getNodeId(), port.getPortId());
    }

    /**
     * Replaces everything this view is showing with {@code source} — opening a file, or receiving a graph.
     *
     * <h3>It copies the CONTENTS in; it does not adopt the object</h3>
     * <p>This used to end in {@code this.document = source}, and that is the one line that made a
     * per-file editor impossible. A host wires its panels to {@code getDocument()} once, at construction
     * — {@code ShaderGraphEditor} hands the same instance to its Main Preview, its Blackboard and its own
     * {@code onChanged} listener. Swapping the field left every one of them bound to an <b>orphan</b>:
     * the board would go on listing the previous graph's properties and write its edits into a document
     * nobody was showing, with both halves individually working and no error anywhere.</p>
     *
     * <p>So a view owns one document for its whole life, and loading changes what is in it. The cost is
     * the mirror-image trap, which is the lesser one and at least has an obvious right answer: a caller
     * that holds {@code source} afterwards is holding a spent template, and further edits to it reach
     * nothing. Mutate {@code getDocument()}.</p>
     *
     * <h3>Through the changeset, not a second rebuild routine</h3>
     * <p>Clearing and repopulating produces exactly the changeset {@link #syncFromDocument()} already
     * consumes, so the widget work is the same path a paste or a server sync takes — retiring floating
     * port editors, pruning the selection, emitting {@code onConnectionsChanged} once. The hand-rolled
     * rebuild this replaced had to remember each of those separately, and a fourth thing added to the
     * view later would have had to be remembered in both places.</p>
     *
     * <h3>Loading is not an edit, so the undo stack is CLEARED</h3>
     * <p>Not appended to, and not left alone. Appending would make the first {@code Ctrl+Z} after an open
     * unpick the file a node at a time — the file is the starting state, not something the user did.
     * Leaving the old history is worse: those entries describe a graph that is no longer here, so undoing
     * one applies an edit to nodes that never existed in this document.</p>
     *
     * <p>Edges are <b>restored</b> rather than reconnected, for the reason {@code GraphCodecs} gives:
     * re-validating on load silently drops every wire whose types this build has no rule for — the
     * "opened without the plugin" case the whole model is arranged to survive. And nodes whose type is
     * not in the library still appear, because {@link NodeWidgetFactory} builds them from the ports the
     * document stored, which is why the document stores them.</p>
     */
    public GraphView load(GraphDocument source) {
        document.clear();
        // The DOCUMENT layer alone, mirroring what the codec writes — the user and workspace layers come
        // from other files entirely and are not this graph's to carry.
        document.settings().replaceLayer(SettingsLayer.DOCUMENT,
                source.settings().layer(SettingsLayer.DOCUMENT).asMap());
        for (GraphProperty property : source.properties()) document.addProperty(property);
        for (NodeData node : source.nodes()) document.addNode(node);
        for (EdgeData edge : source.edges()) document.restoreEdge(edge);

        syncFromDocument();
        edits.history().clear();
        // ONE emit at the end, and it is not belt and braces. `restoreEdge` deliberately only records in
        // the changeset, and `GraphDocument.clear()` empties the property list AFTER its last removeNode
        // — so loading a graph with no nodes, or one whose last act is an edge, would tell nothing
        // downstream that anything had happened and the Blackboard would still be listing the previous
        // file's properties. Listeners re-read the document rather than taking a payload, so a spare emit
        // is a no-op and a missing one is a stale panel.
        document.onChanged.emit();
        return this;
    }

    /**
     * Applies the document's pending changes to the widgets <b>in place</b>, and clears them.
     *
     * <p>The other direction from everything above: this is how a change made to the document by
     * something that is not this view — a server, a command, a paste — reaches the screen. Mutations
     * made <em>through</em> the view already updated both sides, and this is idempotent, so calling it
     * afterwards is harmless.</p>
     *
     * <p><b>In place, never a rebuild</b>, and that is the whole reason a changeset exists rather than a
     * "something changed" flag. Rebuilding detaches the element under the pointer: a drag's source
     * would go stale on its first update and every later frame would feed it garbage — the same defect
     * that froze the table header. Untouched nodes here keep their widget, and therefore their drag,
     * their focus and their scroll position.</p>
     *
     * @return how many individual changes were applied
     */
    public int syncFromDocument() {
        GraphChangeset pending = document.changeset();
        if (pending.isEmpty()) return 0;

        // Snapshot EVERYTHING, then clear, then apply — because applying re-enters. `CanvasView.addNode`
        // calls `moveNode` polymorphically, which reaches this class's override, which writes through to
        // the document and drains the changeset. Reading the lists as it went meant adding the first
        // node wiped the pending edges, and the wires simply never appeared.
        List<String> removedNodes = List.copyOf(pending.removedNodes());
        List<String> addedNodes = List.copyOf(pending.addedNodes());
        List<String> movedNodes = List.copyOf(pending.movedNodes());
        List<EdgeData> removedEdges = List.copyOf(pending.removedEdges());
        List<EdgeData> addedEdges = List.copyOf(pending.addedEdges());
        pending.clear();
        int applied = 0;

        for (String id : removedNodes) {
            GraphNode widget = widgetsById.remove(id);
            if (widget == null) continue;
            // Same reason detachNode forgets them: a floating default editor is not a descendant of its
            // node, so removeChild below never reaches it. Missing here left every port's box/dot
            // permanently orphaned on the plane whenever a removal arrived through the document's
            // changeset instead of through removeNode directly — undo of an add, a server sync, or a
            // delete-then-recreate. Still mounted, still hit-testable, frozen at whatever position it
            // last had, because its own port and dot went stale with it and never laid out again.
            for (NodePort port : widget.getPorts()) forgetPortEditor(port);
            content().remove(widget);
            applied++;
        }
        for (String id : addedNodes) {
            NodeData data = document.node(id);
            if (data == null || widgetsById.containsKey(id)) continue;
            NodeWidgetFactory factory = nodeFactory != null
                    ? nodeFactory : NodeWidgetFactory.of(nodeLibrary).build();
            NodeType type = nodeLibrary != null ? nodeLibrary.get(data.typeId()) : null;
            GraphNode widget = factory.create(type, data);
            widget.bindToDocument(data.id(), data.typeId());
            widgetsById.put(id, widget);
            super.addNode(widget, data.x(), data.y());
            watchPortsOf(widget);
            applied++;
        }
        for (String id : movedNodes) {
            GraphNode widget = widgetsById.get(id);
            NodeData data = document.node(id);
            // super, not this: the position is already what the document says, and going back through
            // the override would write it straight back with no effect but a second changeset entry.
            if (widget != null && data != null) {
                super.moveNode(widget, data.x(), data.y());
                applied++;
            }
        }
        for (EdgeData edge : removedEdges) {
            NodePort from = portFor(edge.from());
            NodePort to = portFor(edge.to());
            if (connections.removeIf(c -> c.from() == from && c.to() == to)) applied++;
            if (from != null && to != null) refreshCounts(from, to);
        }
        for (EdgeData edge : addedEdges) {
            int before = connections.size();
            linkWidgets(edge);
            if (connections.size() != before) applied++;
        }

        getSelection().prune(this);
        if (applied > 0) onConnectionsChanged.emit();
        return applied;
    }

    /** Builds the view-side {@link GraphConnection} for a document edge. Silent when either end is
     * missing: a document may legitimately outrun its widgets mid-load. */
    private void linkWidgets(EdgeData edge) {
        NodePort from = portFor(edge.from());
        NodePort to = portFor(edge.to());
        if (from == null || to == null) return;
        GraphConnection connection = new GraphConnection(from, to);
        if (!connections.contains(connection)) connections.add(connection);
        refreshCounts(from, to);
    }

    // ── Nodes ───────────────────────────────────────────────────────────────

    /**
     * Removes a node <b>and every wire attached to it</b>, as one undoable step.
     *
     * <p>Removing it as a plain element would leave edges pointing at ports that are no longer in the
     * tree, which paints wires to nowhere. Wrapping the wires and the node in one transaction is what
     * makes the undo whole: unwound in reverse, the node comes back first and its wires reconnect to
     * ports that exist again.</p>
     */
    public GraphView removeNode(GraphNode node) {
        String id = node.getNodeId();
        NodeData data = id == null ? null : document.node(id);
        if (data == null) {
            // Never bound — nothing for the DOCUMENT to forget, but its ports may still have floating
            // default editors mounted on the plane; see detachNode's own note on why removeChild alone
            // never reaches them.
            for (NodePort port : node.getPorts()) forgetPortEditor(port);
            content().remove(node);
            getSelection().prune(this);
            return this;
        }
        edits.begin("delete node");
        try {
            for (NodePort port : node.getPorts()) disconnectAll(port);
            edits.apply(new AddNodeEdit(this, node, data, false));
        } finally {
            edits.end();
        }
        getSelection().prune(this);
        return this;
    }

    /**
     * Deletes everything selected, as <b>one</b> undo step.
     *
     * <p>One transaction rather than one per node for the reason the whole transaction mechanism
     * exists: a user who selected six nodes and pressed Delete did one thing, and six presses of Ctrl+Z
     * to get back is not undo, it is arithmetic.</p>
     *
     * @return how many nodes and wires went
     */
    public int deleteSelection() {
        List<GraphNode> doomedNodes = getSelection().nodes();
        GraphConnection doomedWire = getSelection().wire();
        if (doomedNodes.isEmpty() && doomedWire == null) return 0;

        edits.begin("delete");
        try {
            if (doomedWire != null) disconnect(doomedWire);
            for (GraphNode node : doomedNodes) removeNode(node);
        } finally {
            edits.end();
        }
        getSelection().clear();
        return doomedNodes.size() + (doomedWire == null ? 0 : 1);
    }

    // ── Copy / paste / duplicate ────────────────────────────────────────────

    /**
     * The selected nodes and every wire <b>between</b> them, as a detached document.
     *
     * <p>Wires to nodes outside the selection are dropped, which is {@link GraphDocument#copyOf}'s own
     * rule and the right one: an edge needs both ends, and half an edge is not a thing a paste could
     * restore. Copying two ends of a chain without its middle gives you the two ends.</p>
     *
     * @return null when nothing is selected, so a caller can leave the clipboard alone rather than
     *         emptying it — copying nothing should not lose what you copied a minute ago
     */
    @Nullable
    public GraphDocument copySelection() {
        List<String> ids = new ArrayList<>();
        for (GraphNode node : getSelection().nodes()) {
            if (node.getNodeId() != null) ids.add(node.getNodeId());
        }
        if (ids.isEmpty()) return null;
        return document.copyOf(ids, 0f, 0f);
    }

    /**
     * Adds a copy of {@code clip} at an offset, as ONE undo step, and selects what arrived.
     *
     * <p>Fresh ids for everything, so pasting the same clipboard repeatedly is legal — the clipboard is
     * a template, not a handle on the nodes it came from. The edges are remapped through the same table,
     * which is what keeps a pasted subgraph wired to itself rather than back to the original.</p>
     *
     * <p>Selecting the result is what makes paste-then-drag work, and it is also how you can tell what
     * arrived when it landed on top of something else.</p>
     */
    public List<GraphNode> paste(@Nullable GraphDocument clip, float offsetX, float offsetY) {
        if (clip == null || clip.nodeCount() == 0) return List.of();

        Map<String, String> remap = new LinkedHashMap<>();
        List<GraphNode> pasted = new ArrayList<>();
        NodeWidgetFactory factory = nodeFactory != null
                ? nodeFactory : NodeWidgetFactory.of(nodeLibrary).build();

        edits.begin("paste");
        try {
            for (NodeData source : clip.nodes()) {
                String id = GraphIds.generate();
                remap.put(source.id(), id);

                NodeData placed = source.withId(id).movedTo(source.x() + offsetX, source.y() + offsetY);
                NodeType type = nodeLibrary != null ? nodeLibrary.get(placed.typeId()) : null;
                GraphNode widget = factory.create(type, placed);
                // Bound and registered BEFORE the add, so addNode adopts the stored ports and properties
                // rather than deriving a second set from the widget -- which is how a node's instance
                // state gets silently dropped. See dataFor.
                widget.bindToDocument(placed.id(), placed.typeId());
                document.addNode(placed);
                addNode(widget, placed.x(), placed.y());

                NodeData stored = document.node(id);
                if (stored != null) edits.record(new AddNodeEdit(this, widget, stored, true));
                pasted.add(widget);
            }
            for (EdgeData edge : clip.edges()) {
                String from = remap.get(edge.from().nodeId());
                String to = remap.get(edge.to().nodeId());
                if (from == null || to == null) continue;
                NodePort out = portFor(new PortRef(from, edge.from().portId()));
                NodePort in = portFor(new PortRef(to, edge.to().portId()));
                if (out != null && in != null) connect(out, in);
            }
        } finally {
            edits.end();
        }

        getSelection().replaceWith(pasted);
        return pasted;
    }

    /**
     * Adds a copy of {@code clip} with its top-left corner at a world point.
     *
     * <p>What "paste at the cursor" means, and the anchor is deliberate: the group's <b>bounding box
     * corner</b> lands on the point, so everything pasted appears down and right of the pointer and the
     * whole of it is where you were looking. Anchoring on the centre instead scatters half the group
     * behind the cursor, and anchoring on the first node makes the result depend on which node happened
     * to be copied first — invisible from the outside, and different every time.</p>
     *
     * <p>Relative positions inside the group are preserved, because only one offset is applied to all
     * of them: a pasted subgraph keeps its shape.</p>
     */
    public List<GraphNode> pasteAt(@Nullable GraphDocument clip, float worldX, float worldY) {
        if (clip == null || clip.nodeCount() == 0) return List.of();

        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        for (NodeData data : clip.nodes()) {
            minX = Math.min(minX, data.x());
            minY = Math.min(minY, data.y());
        }
        return paste(clip, worldX - minX, worldY - minY);
    }

    /**
     * Copies the selection and pastes it at an offset — one gesture, one undo step.
     *
     * <p>Deliberately does <b>not</b> touch the clipboard. Duplicating is not copying: a user who
     * duplicated something would otherwise lose whatever they had copied earlier, which every editor
     * that gets this right keeps separate.</p>
     */
    public List<GraphNode> duplicateSelection(float offsetX, float offsetY) {
        return paste(copySelection(), offsetX, offsetY);
    }

    /**
     * Adding or removing a node, as data: the {@link NodeData} and the widget projecting it.
     *
     * <p><b>It carries the NodeData, not a position</b>, and that is what makes delete-then-undo safe.
     * The id has to come back <em>unchanged</em>, or every edge that referenced the node points at
     * nothing — and since the edges are restored by the same transaction, one fresh id would silently
     * drop every wire the node had. Re-adding the stored data restores the id, the ports and the
     * properties together.</p>
     */
    private record AddNodeEdit(GraphView view, GraphNode node, NodeData data,
                               boolean adding) implements Edit {
        @Override public void apply() {
            if (adding) view.attachNode(node, data);
            else view.detachNode(node);
        }
        @Override public void undo() {
            if (adding) view.detachNode(node);
            else view.attachNode(node, data);
        }
        @Override public String label() { return adding ? "add node" : "delete node"; }
    }

    /**
     * What a fragment is here: a detached {@link GraphDocument} of the selected nodes and the wires
     * between them.
     *
     * <p>The engine holds what was copied and owns the commands; this says what copying and pasting
     * <em>mean</em> in a graph. @see Clipboard</p>
     */
    private final Clipboard<GraphDocument> clipboard = new Clipboard<GraphDocument>() {
        @Override
        public Class<GraphDocument> type() {
            return GraphDocument.class;
        }

        @Override
        @Nullable
        public GraphDocument copy() {
            return copySelection();
        }

        @Override
        public void paste(GraphDocument clip, float worldX, float worldY) {
            pasteAt(clip, worldX, worldY);
        }

        @Override
        public void pasteBy(GraphDocument clip, float offsetX, float offsetY) {
            GraphView.this.paste(clip, offsetX, offsetY);
        }

        @Override
        public boolean isEmpty(GraphDocument clip) {
            return clip == null || clip.nodeCount() == 0;
        }
    };

    /** @see #clipboard */
    public Clipboard<GraphDocument> clipboard() {
        return clipboard;
    }

    /** Every node currently on the plane, in insertion order. */
    public List<GraphNode> nodes() {
        List<GraphNode> found = new ArrayList<>();
        for (UIElement child : content().children()) {
            if (child instanceof GraphNode node) found.add(node);
        }
        return found;
    }

    // ── Stacking ────────────────────────────────────────────────────────────

    /** Monotonic, so the most recently raised node always outranks every node raised before it. */
    private int raiseCounter;

    /**
     * Brings {@code node} to the front, permanently.
     *
     * <p><b>Raising is interaction history, not selection state.</b> The first attempt keyed stacking off
     * {@code :checked} in the theme, which put a selected node on top and then dropped it back the moment
     * something else was selected — so a node you had deliberately brought forward sank behind a
     * neighbour again as soon as you clicked away. Every editor treats "the last node you touched" as
     * the top one and leaves it there.</p>
     *
     * <p>An ever-increasing {@code z-index} rather than reordering the children: the engine already sorts
     * and hit-tests by it, so paint order and click order stay the same answer, and nothing about the
     * tree moves — which matters because the node is under the pointer at the exact moment it is raised.
     * Written at IMPORTANT because it is runtime state, the same as a scrollbar thumb's position.</p>
     */
    public GraphView raise(GraphNode node) {
        final int next = ++raiseCounter;
        StyleGroup.inlinePipeline(node.getStyle().getGeneralGroup(), g -> g.zIndex(next));
        return this;
    }

    // ── Selection ───────────────────────────────────────────────────────────

    /**
     * What is selected. A model rather than a flag per node, so a marquee, a delete command and an
     * inspector all read one answer — see {@link GraphSelection}, including why selection is not
     * undoable.
     *
     * <p>The set itself is the surface's; this is the typed read of it.</p>
     */
    public GraphSelection getSelection() {
        return (GraphSelection) selection();
    }

    /**
     * The press rule every graph editor uses, and the one a naive implementation gets wrong.
     *
     * <p>Clicking one of five selected nodes in order to drag all five is the most common gesture there
     * is. "A press selects only that node" breaks it — the other four deselect and the drag moves one.
     * So on <b>press</b> a node that is already selected leaves the selection alone; only an unselected
     * one replaces it. Shift always toggles.</p>
     */
    public GraphView selectNode(GraphNode node, boolean additive) {
        if (additive) getSelection().toggle(node);
        else if (!getSelection().contains(node)) getSelection().selectOnly(node);
        return this;
    }

    public GraphView clearSelection() {
        getSelection().clear();
        return this;
    }

    public List<GraphNode> selectedNodes() {
        return getSelection().nodes();
    }

    /** Every node on the plane, selected. */
    public GraphView selectAll() {
        getSelection().replaceWith(nodes());
        return this;
    }

    /** The nodes whose world rect touches {@code region} — the marquee's question.
     *
     * <p><b>Touched, not enclosed.</b> No vendor documents which they use, so it is a decision: at any
     * zoom where a node is larger than the viewport, an enclose-only rule makes it unselectable by
     * marquee at all. CAD's direction-dependent convention (drag right for enclose, left for cross) is
     * powerful, unguessable, and belongs to a domain where precision beats discoverability.</p> */
    public List<GraphNode> nodesTouching(WorldRect region) {
        List<GraphNode> found = new ArrayList<>();
        for (GraphNode node : nodes()) {
            if (region.intersects(worldBoundsOf(node))) found.add(node);
        }
        return found;
    }

    // ── Connections ─────────────────────────────────────────────────────────

    public List<GraphConnection> getConnections() {
        return connectionsView;
    }

    /**
     * Whether a wire may join these two ports, in either drag order.
     *
     * <p>Re-read every frame by {@code NodePort}'s {@code DragOver} handler rather than latched, so a
     * target that stops being legal mid-drag stops accepting with no state to unwind. The rules:
     * one of each direction, not the same node, the source type accepting the target's, and no
     * duplicate.</p>
     *
     * <p>Note what is <b>not</b> here: an occupied input is still connectable. Unity allows one edge per
     * input and many per output, so dropping onto a taken input is a <em>replace</em>, not a rejection —
     * refusing it would make rewiring a node mean two deliberate gestures instead of one.</p>
     */
    public boolean canConnect(@Nullable NodePort a, @Nullable NodePort b) {
        if (a == null || b == null || a == b) return false;
        if (a.getDirection() == b.getDirection()) return false;
        NodePort output = a.getDirection().isOutput() ? a : b;
        NodePort input = output == a ? b : a;
        if (output.node() != null && output.node() == input.node()) return false;
        if (!output.getType().isCompatibleWith(input.getType())) return false;
        return findConnection(output, input) == null;
    }

    /**
     * Connects two ports, in either drag order. Returns the new edge, or {@code null} if the pair is
     * not connectable.
     *
     * <p><b>An occupied input is replaced</b>, and the displaced edge goes out through the same
     * {@link #disconnect} every other removal uses. That matters more than it looks: when 6.2.4 makes
     * this a command, the implicit disconnect has to be part of the same undoable step as the connect,
     * and it will be — because there is only one code path that removes an edge.</p>
     */
    @Nullable
    public GraphConnection connect(NodePort a, NodePort b) {
        if (!canConnect(a, b)) return null;
        NodePort output = a.getDirection().isOutput() ? a : b;
        NodePort input = output == a ? b : a;

        GraphConnection connection = new GraphConnection(output, input);
        EdgeData edge = edgeDataOf(connection);
        // Unbound ports have no document identity, so there is nothing to record — this is a view built
        // outside a document, which the tests do and a caller may.
        if (edge == null) return null;

        GraphConnection existing = firstConnectionTo(input);
        if (existing == null) {
            edits.apply(new ConnectEdit(this, edge, true));
            return connection;
        }
        EdgeData existingEdge = edgeDataOf(existing);
        // The replace is ONE undo step, and that is the whole reason transactions exist: a user who
        // rewires an input did one thing, and a Ctrl+Z that put the old wire back while leaving the new
        // one would leave the input holding two edges — a state the model forbids.
        edits.begin("reconnect");
        try {
            if (existingEdge != null) edits.apply(new ConnectEdit(this, existingEdge, false));
            edits.apply(new ConnectEdit(this, edge, true));
        } finally {
            edits.end();
        }
        return connection;
    }

    /**
     * Adding or removing one edge.
     *
     * <p>Data, not a closure: the two ports and a direction. That is what makes it invertible without
     * remembering anything, and what would let it be sent to a server if 6.2.5 wants that later — a
     * captured lambda could be neither.</p>
     */
    private record ConnectEdit(GraphView view, EdgeData edge, boolean adding) implements Edit {
        @Override public void apply() {
            if (adding) view.addEdge(edge);
            else view.removeEdge(edge);
        }
        @Override public void undo() {
            if (adding) view.removeEdge(edge);
            else view.addEdge(edge);
        }
        @Override public String label() { return adding ? "connect" : "disconnect"; }
    }

    /**
     * The raw mutation both directions of {@link ConnectEdit} share.
     *
     * <p>{@code restoreEdge} rather than {@code connect}: an undo must put back exactly the edge that
     * was there, and re-running validation at that point can only ever refuse it — the graph it was
     * legal in is precisely the graph the undo is restoring.</p>
     */
    private void addEdge(EdgeData edge) {
        document.restoreEdge(edge);
        linkWidgets(edge);
        markSynced();
        onConnectionsChanged.emit();
    }

    private void removeEdge(EdgeData edge) {
        document.disconnect(edge);
        NodePort from = portFor(edge.from());
        NodePort to = portFor(edge.to());
        connections.removeIf(c -> c.from() == from && c.to() == to);
        if (from != null && to != null) refreshCounts(from, to);
        markSynced();
        onConnectionsChanged.emit();
    }

    /** The document edge a view-side connection stands for, or null before either end is bound. */
    @Nullable
    private static EdgeData edgeDataOf(GraphConnection connection) {
        PortRef from = refFor(connection.from());
        PortRef to = refFor(connection.to());
        return from == null || to == null ? null : new EdgeData(from, to);
    }

    public boolean disconnect(GraphConnection connection) {
        if (!connections.contains(connection)) return false;
        EdgeData edge = edgeDataOf(connection);
        if (edge == null) return false;
        edits.apply(new ConnectEdit(this, edge, false));
        return true;
    }

    /** Drops every edge touching {@code port}. */
    public int disconnectAll(NodePort port) {
        List<GraphConnection> doomed = new ArrayList<>();
        for (GraphConnection connection : connections) {
            if (connection.touches(port)) doomed.add(connection);
        }
        if (doomed.isEmpty()) return 0;
        // One step: pulling a node's wires is one action, and undoing it half way would be a graph the
        // user never saw.
        edits.begin("disconnect all");
        try {
            for (GraphConnection connection : doomed) {
                EdgeData edge = edgeDataOf(connection);
                if (edge != null) edits.apply(new ConnectEdit(this, edge, false));
            }
        } finally {
            edits.end();
        }
        return doomed.size();
    }

    /** Edges touching {@code port}, in insertion order. */
    public List<GraphConnection> connectionsOf(NodePort port) {
        List<GraphConnection> found = new ArrayList<>();
        for (GraphConnection connection : connections) {
            if (connection.touches(port)) found.add(connection);
        }
        return found;
    }

    @Nullable
    private GraphConnection findConnection(NodePort output, NodePort input) {
        for (GraphConnection connection : connections) {
            if (connection.from() == output && connection.to() == input) return connection;
        }
        return null;
    }

    @Nullable
    private GraphConnection firstConnectionTo(NodePort input) {
        for (GraphConnection connection : connections) {
            if (connection.to() == input) return connection;
        }
        return null;
    }

    /**
     * Recounts from the edge list rather than incrementing.
     *
     * <p>A counter that is bumped up and down drifts the first time a removal path is added that
     * forgets to decrement — and the symptom is a port that stays visually connected forever, which
     * reads as a paint bug. Recomputing is O(edges) on a change no user makes faster than they can
     * click.</p>
     */
    private void refreshCounts(NodePort... ports) {
        for (NodePort port : ports) {
            int count = 0;
            for (GraphConnection connection : connections) {
                if (connection.touches(port)) count++;
            }
            port.setConnectionCount(count);
        }
    }

    /** Data, not a closure: two positions and the node's ID. Invertible by swapping them, and it keeps
     * working across a delete-then-undo because the id is what comes back, not the widget. */
    private record MoveNodeEdit(GraphView view, String nodeId,
                                float fromX, float fromY, float toX, float toY) implements Edit {
        @Override public void apply() { move(toX, toY); }
        @Override public void undo() { move(fromX, fromY); }
        private void move(float x, float y) {
            GraphNode widget = view.widgetFor(nodeId);
            if (widget != null) view.moveNode(widget, x, y);
            else view.document.moveNode(nodeId, x, y);
        }
        @Override public String label() { return "move"; }
    }

    // ── Marquee ─────────────────────────────────────────────────────────────

    /**
     * Starts a rubber-band selection, or selects a wire if one is under the press.
     *
     * <p>The wire check comes first and is the reason the layer can stay {@code hitTest(false)}: a wire
     * is painted, not laid out, so nothing in the hit-test tree knows where it is. Asking the layer
     * directly keeps that true rather than inventing an element per edge — which is the trade 6.2.3
     * recorded.</p>
     *
     * @return whether the press was claimed
     */
    private boolean beginMarqueeOrPickWire(float rawX, float rawY) {
        UIDocument window = document();
        if (window == null) return false;

        // Pressing the canvas focuses it, exactly as pressing a node does.
        //
        // Every graph command resolves the nearest GraphView from the FOCUSED element, so without this
        // a click on empty canvas or on a wire leaves focus wherever it was — and Delete, Ctrl+A and
        // Escape are all silently disabled while the graph looks and feels active. Selecting a wire and
        // pressing Delete did nothing at all, which reads as a broken command rather than as a focus
        // problem.
        //
        // requestPOINTERFocus, not requestFocus: the latter is PROGRAMMATIC, which is a focus source
        // `:focus-visible` deliberately rings — so every click on the canvas drew a focus ring around the
        // entire viewport. You already know where your pointer is; that is the whole carve-out
        // `:focus-visible` exists for, and the click path takes it too.
        window.focus().requestPointerFocus(this);

        Vector2f world = screenToWorld(rawX, rawY);
        GraphConnection hit = wireLayer.pickWire(world.x(), world.y());
        if (hit != null) {
            getSelection().selectOnly(hit);
            return true;
        }

        marquee().begin(rawX, rawY, isShiftHeld(), isAltHeld());
        return true;
    }

    @Nullable
    private Marquee marquee;

    @Nullable
    private MoveGesture moveGesture;

    /** The band. Built on first use, because it adds an overlay and a constructor is not the place. */
    Marquee marquee() {
        if (marquee == null) marquee = new Marquee(surface(), picking(), selection());
        return marquee;
    }

    /** Dragging what is selected, as one undo step. @see MoveGesture */
    public MoveGesture moveGesture() {
        if (moveGesture == null) moveGesture = new MoveGesture(surface(), policy(GraphPolicy.class), edits);
        return moveGesture;
    }

    /**
     * What a graph means by the engine's questions.
     *
     * <p>An item is a node; a press on a node's chrome is the surface's and one inside it is the tree's,
     * or a port's value editor could not be typed in; and a move writes one position edit per node,
     * composed into one step.</p>
     */
    private final class GraphPolicy implements SurfacePolicy {

        @Override
        @Nullable
        public UIElement itemFor(@Nullable UIElement hit) {
            for (UIElement each = hit; each != null; each = each.parentElement()) {
                if (each instanceof GraphNode node && node.parent() == content()) return node;
            }
            return null;
        }

        @Override
        public PressOwner ownerOf(UIElement hit) {
            // A NODE'S CHROME IS THE SURFACE'S AND WHAT IS INSIDE IT IS NOT. A port's default-value
            // editor has to keep taking clicks, or a field inside a node cannot be typed in at all.
            return hit instanceof GraphNode ? PressOwner.SURFACE : PressOwner.TREE;
        }

        @Override
        public void markSelected(UIElement item, boolean selected) {
            if (item instanceof GraphNode node) node.setSelected(selected);
        }

        /**
         * Deleting is the graph's own: nodes go with the wires that touched them.
         *
         * <p>{@code deleteSelection} already does exactly that as one transaction, so this returns null
         * and the command path stays where the edge cases already live.</p>
         */
        @Override
        @Nullable
        public Edit deleteEdit(List<UIElement> items) {
            return null;
        }

        @Override
        @Nullable
        public Edit moveEdit(List<Move> moves) {
            List<Edit> each = new ArrayList<>(moves.size());
            for (Move move : moves) {
                if (!(move.item() instanceof GraphNode node) || node.getNodeId() == null) continue;
                each.add(new MoveNodeEdit(GraphView.this, node.getNodeId(),
                        move.fromX(), move.fromY(), move.toX(), move.toY()));
            }
            return each.isEmpty() ? null : CompositeEdit.of("move", each.toArray(new Edit[0]));
        }
    }

    /** World point -> the wire under it, or null. Delegates to the layer, which is the only thing that
     * knows where a wire was drawn. */
    @Nullable
    public GraphConnection pickWire(float worldX, float worldY) {
        return wireLayer.pickWire(worldX, worldY);
    }

    private static boolean isShiftHeld() {
        var input = CgPlatform.input();
        return input != null && CgModifiers.hasShift(input.getCurrentModifiers());
    }

    private static boolean isAltHeld() {
        var input = CgPlatform.input();
        return input != null && CgModifiers.hasAlt(input.getCurrentModifiers());
    }

    // ── Framing ─────────────────────────────────────────────────────────────

    /** Frames the selection, or everything when nothing is selected — Unity binds these to F and A. */
    public GraphView frameSelection(float padding) {
        List<GraphNode> selected = getSelection().nodes();
        if (selected.isEmpty()) {
            fitToContent(padding);
            return this;
        }
        WorldRect union = null;
        for (GraphNode node : selected) {
            WorldRect rect = worldBoundsOf(node);
            union = union == null ? rect : union.union(rect);
        }
        return frameRect(union, padding);
    }

    /** Fits the view to an arbitrary world rect. {@code fitToContent} is this over everything. */
    public GraphView frameRect(WorldRect rect, float padding) {
        if (rect == null) return this;
        Box cache = box();
        float viewW = cache.width(), viewH = cache.height();
        if (viewW <= 0f || viewH <= 0f) return this;
        WorldRect padded = rect.expand(padding);
        // Never magnifies past 1:1. Framing means "make this fit", and for one small node in a large
        // viewport the literal fit is an eight-times blow-up that fills the screen with a single box —
        // which is what it did, and is useless: the point of framing a selection is to see it in
        // context, not to inspect its pixels.
        float fit = Math.min(viewW / Math.max(1e-4f, padded.width()), viewH / Math.max(1e-4f, padded.height()));
        setZoom(Math.min(1f, fit));
        centerOnWorld(padded.centerX(), padded.centerY());
        return this;
    }

    // ── The wire being dragged ──────────────────────────────────────────────

    void beginPendingWire(NodePort from) {
        wireLayer.beginPending(from);
    }

    void updatePendingWire(float planeX, float planeY) {
        wireLayer.updatePending(planeX, planeY);
    }

    /**
     * Ends the wire drag. When it landed on nothing and a library is set, this is where the contextual
     * create-node menu opens - the path 6.2.3 deliberately left room for.
     *
     * @param planeX where the wire was dropped, in the plane's own space
     */
    void endPendingWire(NodePort from, float planeX, float planeY, boolean connected) {
        wireLayer.endPending();
        if (connected || creationMenu == null) return;
        offerNodeFor(from, planeX, planeY);
    }

    // -- The node library ----------------------------------------------------

    @Nullable
    private NodeCreationMenu creationMenu;

    @Nullable
    @Getter
    private NodeTypeRegistry nodeLibrary;

    @Nullable
    @Getter
    private NodeWidgetFactory nodeFactory;

    private TypeCompatibility typeRule = TypeCompatibility.EXACT;

    /** Where the node the menu is about to create will land, and what it should wire to. */
    private float pendingWorldX, pendingWorldY;

    @Nullable
    private NodePort pendingFrom;

    /**
     * Gives this graph a library to create nodes from, and a factory to build their widgets.
     *
     * <p>Both belong to the consumer: the library is the thing a shader graph and a dialogue graph
     * disagree about, and the factory is what turns a type id into the particular box somebody designed.
     * With neither set the graph still works entirely - you simply cannot add a node from inside it.</p>
     */
    public GraphView setNodeLibrary(NodeTypeRegistry library, NodeWidgetFactory factory,
                                    TypeCompatibility rule) {
        this.nodeLibrary = library;
        this.nodeFactory = factory;
        this.typeRule = rule == null ? TypeCompatibility.EXACT : rule;
        NodeCreationMenu menu = new NodeCreationMenu(library);
        menu.onChosen.connect(this::createFromOffer);
        append(menu);
        this.creationMenu = menu;
        return this;
    }

    /** The create-node menu, once a library has been set. */
    @Nullable
    public NodeCreationMenu creationMenu() {
        return creationMenu;
    }

    /** Opens the menu unfiltered, at a world position - what Space does. */
    public GraphView openCreationMenu(float worldX, float worldY) {
        if (creationMenu == null) return this;
        pendingFrom = null;
        pendingWorldX = worldX;
        pendingWorldY = worldY;
        Vector2f at = rootPositionOfWorld(worldX, worldY);
        // NO invoker. The invoker is deliberately treated as part of its own popover -- that carve-out
        // exists so a dropdown button is not dismissed by the very press that opens it -- and naming the
        // graph as invoker therefore made every press anywhere on the canvas count as a press INSIDE the
        // menu, so light dismiss never fired. This menu has no invoker: a gesture opened it, not a button.
        creationMenu.openAll(at.x(), at.y(), null);
        return this;
    }

    private void offerNodeFor(NodePort from, float planeX, float planeY) {
        if (creationMenu == null) return;
        pendingFrom = from;
        // The drag reports PLANE space (the port's own), which is world plus the plane's origin - and the
        // wire layer sits at world (0,0), so its origin is that offset. The same conversion the layer
        // itself uses, rather than a second one that could drift from it.
        Box layerBox = wireLayer.box();
        float ox = layerBox == null ? 0f : layerBox.x();
        float oy = layerBox == null ? 0f : layerBox.y();
        pendingWorldX = planeX - ox;
        pendingWorldY = planeY - oy;

        Vector2f at = rootPositionOfWorld(pendingWorldX, pendingWorldY);
        // Invoker null -- see openCreationMenu.
        if (from.getDirection().isOutput()) {
            creationMenu.openForOutput(from.getType().id(), typeRule, at.x(), at.y(), null);
        } else {
            creationMenu.openForInput(from.getType().id(), typeRule, at.x(), at.y(), null);
        }
    }

    /**
     * World -> the root-relative logical coordinates a promoted popover is positioned in.
     *
     * <p>Through {@code worldToViewport}, so the menu opens where the wire was dropped <em>on screen</em>
     * rather than where it would be at zoom 1. A promoted element's containing block is the root, which
     * is why the root's own origin comes off at the end.</p>
     */
    private Vector2f rootPositionOfWorld(float worldX, float worldY) {
        Vector2f onScreen = worldToViewport(worldX, worldY);
        UIDocument window = document();
        if (window == null) return onScreen;
        // `worldToViewport` answers in THIS view's local space; a promoted element's containing block
        // is the root. Two different spaces, so the conversion goes through the world matrix rather
        // than by subtracting the root's own `x()` -- which is the root's offset in its own parent and
        // has nothing to do with this view's position.
        Box self = box();
        Box rootCache = window.box();
        if (self == null || rootCache == null) return onScreen;
        Vector2f origin = Box.originIn(self, rootCache);
        return new Vector2f(onScreen.x() + origin.x(), onScreen.y() + origin.y());
    }

    /**
     * Creates the chosen node and, when the menu was opened by a dropped wire, connects it - as
     * <b>one</b> undo step.
     *
     * <p>Two presses to undo a node you just made is the same failure as forty presses to undo one drag,
     * and for the same reason: the user did one thing.</p>
     */
    private void createFromOffer(NodeTypeRegistry.Offer offer) {
        if (nodeFactory == null) return;
        NodeData data = offer.type().create(pendingWorldX, pendingWorldY);
        GraphNode node = nodeFactory.create(offer.type(), data);
        // Bound BEFORE it is added, so the node keeps the id and ports the library built rather than
        // having a second set derived from the widget.
        node.bindToDocument(data.id(), data.typeId());
        // Into the document first, so addNode adopts the library's ports and properties instead of
        // deriving a second set from the widget.
        document.addNode(data);

        edits.begin("create " + offer.type().label());
        try {
            addNode(node, pendingWorldX, pendingWorldY);
            NodeData placed = document.node(node.getNodeId());
            if (placed != null) edits.record(new AddNodeEdit(this, node, placed, true));
            NodePort source = pendingFrom;
            if (source != null && offer.port() != null) {
                for (NodePort port : node.getPorts()) {
                    if (port.getPortId().equals(offer.port().portId())) {
                        connect(source, port);
                        break;
                    }
                }
            }
        } finally {
            edits.end();
        }
        pendingFrom = null;
        getSelection().selectOnly(node);
    }

    // ── Wire geometry ───────────────────────────────────────────────────────

    public GraphView setWireBaseWidth(float width) {
        this.wireBaseWidth = Math.max(0.1f, width);
        return this;
    }

    /** The width handed to {@code ctx.curve().width(...)}, in pre-pose units. Unclamped — the pose
     * (which already includes the plane's own zoom) is what makes this thicker zoomed in and thinner
     * zoomed out, same as {@link #DEFAULT_WIRE_WIDTH}'s own note about matching a real border's
     * behaviour under scale. See {@link #getWireFeather()} for why the WIDTH staying unclamped does not
     * reintroduce the sub-pixel dropout an earlier version of this class floored it against. */
    public float getWireWidth() {
        return wireBaseWidth;
    }

    /** {@code stroke_coverage}'s edge ramp at zoom 1 — see {@code CgVectorRenderer.Curve#feather} and
     * {@code stroke.glsl}. Same value {@link NodeWireLayer#WIRE_FEATHER} already used before this
     * needed to vary with zoom at all. */
    private static final float BASE_WIRE_FEATHER = 0.5f;

    /** The feather actually handed to {@code ctx.curve().feather(...)}: {@link #BASE_WIRE_FEATHER}
     * divided by zoom, so the ANTIALIASING RAMP stays a constant width on screen (in device-ish pixels)
     * regardless of zoom — the opposite of {@link #getWireWidth()}, which is deliberately left to shrink
     * with zoom unclamped.
     *
     * <h3>Why the width shrinking and the feather NOT shrinking is correct, not a contradiction</h3>
     * <p>{@code stroke_coverage} computes {@code signedDist = dist - halfWidth} and returns {@code 1 -
     * smoothstep(-ramp/2, ramp/2, signedDist)}. When the ramp was left to shrink alongside the width (an
     * earlier version), a curve zoomed out below a device pixel wide made the WHOLE transition band
     * narrower than the space between two sampled pixel centres — every sample fell on one side of that
     * band or the other, evaluating to a hard 0 or 1 rather than a fraction, which is a per-pixel
     * dropout: the exact "missing pixels" a side-by-side against Unity's smooth thin line caught.
     * Flooring the ramp against zoom instead keeps it at least ~1 real screen pixel wide always, so
     * EVERY sample near the centreline lands inside a genuinely smooth gradient and gets a fractional,
     * antialiased coverage value — never a coin flip.</p>
     *
     * <p>Once the ramp is a real screen pixel and the width keeps shrinking past it, {@code halfWidth}
     * eventually sits INSIDE the ramp's own span at the centreline itself, so peak coverage there drops
     * below 1.0 too — the stroke reads as thinner AND fainter, not because anything multiplies its
     * colour's alpha (an earlier version tried exactly that, which is what desaturated a colour wire
     * toward the dark canvas behind it into ash-grey rather than a dim but still-hued line — the fade has
     * to happen in the SAME coverage computation the ramp already drives, or the colour and the
     * shrinking disagree about what's happening). This is the ordinary analytic-SDF answer to sub-pixel
     * line antialiasing, and needs no MSAA framebuffer to get right — the curve renderer already draws
     * every pixel from an exact distance field; it only needed the ramp width to stop shrinking past the
     * point where the pixel grid can resolve it.</p>
     */
    public float getWireFeather() {
        float zoom = Math.max(1e-4f, getZoom());
        return BASE_WIRE_FEATHER / zoom;
    }
    @Override
    protected void connected() {
        super.connected();
        document().animation().afterLayout(this, delta -> {
            onLayoutSettled();
            return true;
        });
    }

}
