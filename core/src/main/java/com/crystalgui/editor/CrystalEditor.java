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

    /** The node graph, as a document panel — several may be open at once. */
    public static final String SHADER_GRAPH_TYPE = "shadergraph";

    /**
     * A shader graph opened <b>from a file</b>, one instance per path.
     *
     * <p>Separate from {@link #SHADER_GRAPH_TYPE}, which is the scratch graph the editor opens with and
     * which has no path at all. The same split VS Code draws between an untitled editor and a file
     * editor, and it is what lets the two coexist while the starter graph is still pathless — a single
     * type would have to build a document for the empty path.</p>
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

    @Nullable
    private ShaderGraphEditor shaderGraph;

    @Nullable
    private ShaderGraphInspector inspector;

    /** The last {@link #saveLayout} result, so {@link #restoreLayout()} has something to restore. */
    @Nullable
    private Object savedLayout;

    private boolean focusGiven;

    public CrystalEditor(WorkspaceClient<?> client) {
        setFocusPolicy(FocusPolicy.NONE);
        workbench = new Workbench(client);
        workbench.onStatus.connect(onStatus::emit);

        workbench.registerPanel(DockPanelDescriptor.document(SHADER_GRAPH_TYPE, "Shader Graph"),
                ref -> shaderGraph());
        // A .shadergraph FILE opens as a graph rather than as its own JSON. One editor per path, built by
        // the workbench and cached with the document, so two open graphs are two graphs -- the scratch
        // panel above is a lazily-created singleton and could never be that.
        //
        // No starter graph here: a file-backed document is whatever the file says, and seeding it would
        // put nodes on the canvas that are not in the file and mark it modified before it was touched.
        workbench.registerDocumentType(SHADER_GRAPH_FILE_TYPE, "Shader Graph", path -> {
            ShaderGraphEditor editor = new ShaderGraphEditor();
            editor.onStatusChanged.connect(onStatus::emit);
            editor.onLineOwnerChanged.connect(onStatus::emit);
            return editor;
        });
        workbench.bindEditorExtensions(SHADER_GRAPH_FILE_TYPE, "shadergraph");
        workbench.registerPanel(
                DockPanelDescriptor.singleton(SHADER_SOURCE_TYPE, SHADER_SOURCE_TITLE),
                ref -> shaderGraph().source());
        workbench.openPanel(new DockPanelRef(SHADER_GRAPH_TYPE));
        // BESIDE the canvas, not in its strip. A tab in the same group would hide the graph, and the whole
        // point of the emitted source is watching it change as you wire -- a panel you have to switch away
        // from the graph to read is a panel that is never read.
        workbench.registerPanel(DockPanelDescriptor.singleton(INSPECTOR_TYPE, "Inspector"),
                ref -> inspector());
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
        if (inspector == null) inspector = new ShaderGraphInspector(shaderGraph());
        return inspector;
    }

    /** Built on first use, so an editor that never opens the graph never pays for its previews. */
    public ShaderGraphEditor shaderGraph() {
        if (shaderGraph == null) {
            shaderGraph = new ShaderGraphEditor().addStarterGraph();
            shaderGraph.onStatusChanged.connect(onStatus::emit);
            shaderGraph.onLineOwnerChanged.connect(onStatus::emit);
        }
        return shaderGraph;
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

    /** Releases the shader graph's preview renderers. Safe to call more than once. */
    public void delete() {
        if (shaderGraph != null) shaderGraph.delete();
    }
}
