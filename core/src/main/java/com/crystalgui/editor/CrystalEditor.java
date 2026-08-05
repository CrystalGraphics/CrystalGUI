package com.crystalgui.editor;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.fs.WorkspaceClient;
import com.crystalgui.graph.shader.ShaderGraphEditor;
import com.crystalgui.graph.shader.ShaderGraphInspector;
import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.chrome.ChromeCommands;
import com.crystalgui.ui.elements.dock.DockCommands;
import com.crystalgui.ui.elements.dock.DockDropZone;
import com.crystalgui.ui.elements.dock.DockGroup;
import com.crystalgui.ui.elements.dock.DockLayout;
import com.crystalgui.ui.elements.dock.DockLayoutCodec;
import com.crystalgui.ui.elements.dock.DockPanelDescriptor;
import com.crystalgui.ui.elements.dock.DockPanelRef;
import com.crystalgui.ui.elements.workbench.Workbench;
import com.crystalgui.ui.input.FocusPolicy;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The editor — the whole application, as one element.
 *
 * <p>A {@link Workbench} is the <em>shell</em>: a dock, a file tree, editors, a Problems panel. This is
 * the <b>product</b> built on it — which panels exist, what the default arrangement is, which commands
 * the application answers to, and where focus starts. A host supplies a {@link WorkspaceClient} and a
 * window; everything else is decided here.</p>
 *
 * <h3>Why this is not a harness scene</h3>
 *
 * <p>Because it was one, and that meant the only assembled editor in the project lived in a debug tool.
 * Every line of it is application behaviour: that {@code Ctrl+S} writes the active file and the layout
 * lives on {@code Ctrl+Shift+S}, that the shader graph opens in the work area, that an application decides
 * where focus starts. None of it demonstrates anything. What a scene legitimately keeps is the
 * <b>fake half</b> — a workspace client backed by something other than a real server — plus whatever
 * diagnostics that scene wants to draw on top.</p>
 *
 * <h3>Keys are commands, not key handling</h3>
 *
 * <p>The scene used to intercept raw key codes. Everything here is a registered {@link
 * com.crystalgui.core.command.Command} instead, which means each one is rebindable, shows up in the
 * palette with its accelerator, and can be greyed when it does not apply — none of which a
 * {@code switch} on a scan code can offer. See {@link CrystalEditorCommands}.</p>
 */
public class CrystalEditor extends UIElement {

    /**
     * A shader graph, one editor per path. <b>Every</b> shader graph — there is no other kind.
     *
     * <p>There was, briefly: a pathless {@code "shadergraph"} scratch panel that the editor opened with,
     * left over from before graphs were files. It has been removed rather than kept alongside, because it
     * was indistinguishable from a file tab and could not be saved — {@code Ctrl+S} found no path and said
     * so to a status line nobody watches — so it silently swallowed whatever was built in it. A new file
     * is two keystrokes and is a real document.</p>
     */
    public static final String SHADER_GRAPH_FILE_TYPE = "shadergraph.file";

    /**
     * The GLSL the graph emits, as a panel of its own.
     *
     * <p><b>Singleton, not a document.</b> There is one emit and it is a <em>view of</em> the graph rather
     * than a thing you can have two of; closing it means "hide it", and opening it again must find the same
     * editor — with its scroll position and its caret — rather than a second copy showing the same text.</p>
     */
    public static final String SHADER_SOURCE_TYPE = "shadersource";

    /** What the tab says. A generated file still reads best as a file name. */
    public static final String SHADER_SOURCE_TITLE = "compiled_graph.shader";

    /**
     * The node inspector, sharing the emitted source's strip.
     *
     * <p>A tab rather than a third pane because the two are <b>alternatives</b>: reading the generated GLSL
     * and adjusting a node's properties are different things to be doing, and giving each a permanent
     * column would spend the work area on whichever you are not using.</p>
     */
    public static final String INSPECTOR_TYPE = "inspector";

    /** How much of the work area the emitted source takes when it is first opened. */
    private static final float SOURCE_SHARE = 0.28f;

    /** Whatever a status line should say: an open, a save, a compile summary, or a refusal. */
    public final Signal.Value<String> onStatus = new Signal.Value<>();

    private final Workbench workbench;

    /** Marked internal exactly ONCE, while empty. {@code markAsInternal()} RECURSES, and stamping a
     * populated subtree makes {@code removeChild} silently refuse everything below it. */
    private final UIElement content = new UIElement();

    /**
     * The panels that follow whichever shader graph is in front.
     *
     * <p>Containers rather than the editors' own parts, because there is no longer <em>one</em> graph to
     * bind them to — every graph is a file now, and several can be open. {@code DockArea} asks the
     * registry for a panel's content on every rebuild and caches nothing, so a factory returning the
     * active editor's own {@code source()} would hand back a different element each time the active tab
     * changed, which the dock has no way to notice. A stable host whose child is swapped does.</p>
     */
    private final UIElement sourceHost = new UIElement();
    private final UIElement inspectorHost = new UIElement();

    /**
     * One inspector per graph, kept because building it is not free and because it holds view state — the
     * open tab, the fold states — that a user expects to still be there when they switch back.
     */
    private final Map<ShaderGraphEditor, ShaderGraphInspector> inspectors = new HashMap<>();

    /**
     * Every graph editor this editor has built, so {@link #delete()} can free all of their preview
     * renderers rather than only the one in front — each holds an FBO per node.
     *
     * <p>Recorded where they are created rather than read back from the workbench: it exposes no list of
     * open documents, and adding one to it would widen a class another agent is actively editing.</p>
     */
    private final List<ShaderGraphEditor> graphs = new ArrayList<>();

    /** What {@link #followActiveGraph} last put in the hosts, so it can tell when nothing has changed. */
    @Nullable
    private ShaderGraphEditor shownGraph;

    /** The last {@link #saveLayout} result, so {@link #restoreLayout()} has something to restore. */
    @Nullable
    private Object savedLayout;

    private boolean focusGiven;

    public CrystalEditor(WorkspaceClient<?> client) {
        setFocusPolicy(FocusPolicy.NONE);
        workbench = new Workbench(client);
        workbench.onStatus.connect(onStatus::emit);

        // A .shadergraph FILE opens as a graph rather than as its own JSON. One editor per path, built by
        // the workbench and cached with the document, so two open graphs are two graphs.
        //
        // THIS IS THE ONLY KIND OF SHADER GRAPH. There used to be a pathless "Shader Graph" scratch panel
        // in the default layout as well, from before files existed, and it was a trap: it looked exactly
        // like a file tab, could not be saved -- Ctrl+S found no path and said so to a status line nobody
        // watches -- and so quietly swallowed whatever was built in it. Someone lost a graph to it within
        // an hour of files working. A new file is two keystrokes and is a real document; that is the
        // scratch pad now.
        workbench.registerDocumentType(SHADER_GRAPH_FILE_TYPE, "Shader Graph", path -> {
            ShaderGraphEditor editor = new ShaderGraphEditor();
            editor.onStatusChanged.connect(onStatus::emit);
            editor.onLineOwnerChanged.connect(onStatus::emit);
            graphs.add(editor);
            return editor;
        });
        workbench.bindEditorExtensions(SHADER_GRAPH_FILE_TYPE, "shadergraph");
        // HOSTS, not the editors' own parts. There is no longer one graph to bind these to, so each is a
        // container that follows whichever shader graph is in front -- see followActiveGraph().
        workbench.registerPanel(
                DockPanelDescriptor.singleton(SHADER_SOURCE_TYPE, SHADER_SOURCE_TITLE),
                ref -> sourceHost);
        // BESIDE the canvas, not in its strip. A tab in the same group would hide the graph, and the whole
        // point of the emitted source is watching it change as you wire -- a panel you have to switch away
        // from the graph to read is a panel that is never read.
        workbench.registerPanel(DockPanelDescriptor.singleton(INSPECTOR_TYPE, "Inspector"),
                ref -> inspectorHost);
        DockPanelRef source = new DockPanelRef(SHADER_SOURCE_TYPE);
        workbench.openPanelBeside(source, DockDropZone.SPLIT_RIGHT, SOURCE_SHARE);
        workbench.openPanelWith(source, new DockPanelRef(INSPECTOR_TYPE));

        content.addClass(CONTENT_CLASS);
        addInternalChild(content);
        content.addChild(workbench);
    }

    /** UNIQUE, never the shared {@code "__content__"} — {@code CanvasView} uses that name for its
     * transformed world plane, so a descendant rule naming it also styles every graph plane below. */
    public static final String CONTENT_CLASS = "__editor-content__";

    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    public Workbench workbench() {
        return workbench;
    }

    /**
     * The Graph Inspector, built on first use — a {@code Node} tab and a {@code Graph} tab.
     *
     * <p>Bound to the graph's selection and to the document's own settings, so it needs the graph — which
     * is why it is built here rather than in the panel factory: {@link #shaderGraph()} is itself lazy, and
     * asking for the inspector first has to bring the graph into being rather than binding to nothing.</p>
     *
     * <p>The editor's {@code recompile} becomes the change hook, so editing a value in the inspector
     * re-emits the shader exactly as editing the same field on the node does. They are two bindings of
     * one field.</p>
     */
    public ShaderGraphInspector inspector() {
        ShaderGraphEditor active = activeGraph();
        return active == null ? null : inspectorFor(active);
    }

    /** The shader graph in front, or null when the active tab is not one (or nothing is open). */
    @Nullable
    public ShaderGraphEditor activeGraph() {
        return workbench.activeDocument() instanceof ShaderGraphEditor graph ? graph : null;
    }

    private ShaderGraphInspector inspectorFor(ShaderGraphEditor graph) {
        return inspectors.computeIfAbsent(graph, ShaderGraphInspector::new);
    }

    /**
     * Points the source and inspector panels at whichever shader graph is in front. Cheap and idempotent;
     * call once a frame.
     *
     * <p>Polled rather than driven by a signal because the workbench has no "the active tab changed" event
     * and inventing one would mean a second thing to keep in step — the dock changes the active panel from
     * a click, a close, a drag, a split and a layout restore, and a signal that missed any of those would
     * leave these panels showing a graph that is no longer in front, which is precisely the confusion this
     * whole change is removing.</p>
     *
     * <p><b>A non-graph tab leaves them alone.</b> Clicking a README must not blank the inspector — there
     * is still a shader graph open and it is still what these panels are about. They follow the last graph
     * until a different one comes forward.</p>
     */
    /**
     * Starts the per-frame follow, once a window exists to tick from.
     *
     * <p>The same shape {@code ShaderGraphEditor} uses for its previews, and for the same reason: this is
     * the widget's own business rather than something a host must remember to call, and there is no
     * earlier moment — a constructor has no window.</p>
     */
    @Override
    protected void onLayoutChanged() {
        super.onLayoutChanged();
        if (ticking || getAttachedWindow() == null) return;
        ticking = true;
        getAttachedWindow().registerTicker(delta -> {
            followActiveGraph();
            return true;
        });
    }

    private boolean ticking;

    private void followActiveGraph() {
        ShaderGraphEditor active = activeGraph();
        if (active == null || active == shownGraph) return;
        shownGraph = active;

        sourceHost.clearAllChildren();
        sourceHost.addChild(active.source());
        inspectorHost.clearAllChildren();
        inspectorHost.addChild(inspectorFor(active));
    }

    /**
     * Registers every command set the editor answers to, and binds their defaults.
     *
     * <p>Explicit, like every other command set in this engine: nothing here injects itself, because a
     * registry that quietly acquired commands nobody registered surprises anything that enumerates it —
     * and the command palette is precisely such a thing.</p>
     */
    public CrystalEditor install(UIWindow window) {
        DockCommands.install(window);
        ChromeCommands.install(window);
        CrystalEditorCommands.install(window, this);
        return this;
    }

    // ── Layout ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Serialises the pane arrangement.
     *
     * <p>Reads the divider positions back out of the widgets first, or the blob records the weights the
     * layout was <em>built</em> with rather than the ones on screen.</p>
     */
    public <T> T saveLayout(DynamicOps<T> ops, int screenWidth, int screenHeight) {
        workbench.dock().pullWeightsIntoLayout();
        T encoded = DockLayoutCodec.encode(workbench.dock().layout(), ops, screenWidth, screenHeight);
        savedLayout = encoded;
        return encoded;
    }

    /** Restores whatever {@link #saveLayout} last produced. False when there is nothing to restore or the
     * codec refuses the blob — which is a normal outcome, not an error path. */
    @SuppressWarnings("unchecked")
    public <T> boolean restoreLayout(DynamicOps<T> ops) {
        if (savedLayout == null) {
            onStatus.emit("nothing saved yet");
            return false;
        }
        DockLayout restored = DockLayoutCodec.decode((T) savedLayout, ops, workbench.panels());
        if (restored == null) {
            onStatus.emit("saved layout refused");
            return false;
        }
        workbench.dock().setLayout(restored);
        onStatus.emit("layout restored");
        return true;
    }

    // ── Focus ───────────────────────────────────────────────────────────────────────────────────

    /**
     * Hands focus to the dock once there is a group to hand it to. Idempotent; call it per frame.
     *
     * <p>An application decides where focus starts, and an IDE opens with its editor focused. Without this
     * the window opens with focus <b>null</b>, and every command whose {@code enabledWhen} walks up from
     * the focused element reports unavailable — so the palette opens almost entirely dimmed and reads as
     * broken.</p>
     *
     * <p>{@code requestPointerFocus}, never {@code requestFocus}: the latter is PROGRAMMATIC and therefore
     * rings, so the editor would open with a focus outline nobody asked for.</p>
     */
    public void giveInitialFocus() {
        if (focusGiven) return;
        UIWindow window = getAttachedWindow();
        if (window == null) return;
        DockGroup group = workbench.dock().activeGroup();
        if (group == null) return;
        if (window.getInputHandler().getFocusedElement() == null) {
            window.getInputHandler().requestPointerFocus(group);
        }
        focusGiven = true;
    }

    /**
     * Releases every open shader graph's preview renderers. Safe to call more than once.
     *
     * <p>Every one of them, not "the graph": each open file has its own editor and its own
     * {@code CgPreviewRenderer} holding an FBO per node, so freeing only the one in front would leak the
     * rest — which the single scratch graph this replaced could never do.</p>
     */
    public void delete() {
        for (ShaderGraphEditor graph : graphs) graph.delete();
    }
}
