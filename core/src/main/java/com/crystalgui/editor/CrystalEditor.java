package com.crystalgui.editor;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.dispose.Disposer;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.WorkspaceClient;
import com.crystalgui.graph.shader.ShaderGraphEditor;
import com.crystalgui.graph.shader.ShaderGraphInspector;
import com.crystalgui.core.settings.Settings;
import com.crystalgui.core.settings.SettingsCodec;
import com.crystalgui.core.settings.SettingsLayer;
import com.crystalgui.core.settings.SettingsModel;
import com.crystalgui.fs.ConfigStorage;
import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.ui.elements.workbench.WorkbenchSession;
import com.crystalgui.ui.elements.workbench.WorkbenchSettings;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.chrome.ChromeCommands;
import com.crystalgui.ui.elements.dock.DockCommands;
import com.crystalgui.ui.elements.dock.DockDropZone;
import com.crystalgui.ui.elements.dock.DockGroup;
import com.crystalgui.ui.elements.dock.DockLayout;
import com.crystalgui.ui.elements.dock.DockLeaf;
import com.crystalgui.ui.elements.dock.DockLayoutCodec;
import com.crystalgui.ui.elements.dock.DockPanelDescriptor;
import com.crystalgui.ui.elements.dock.DockPanelRef;
import com.crystalgui.ui.elements.workbench.FileDocument;
import com.crystalgui.ui.elements.workbench.Workbench;
import com.crystalgui.ui.input.FocusPolicy;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.crystalgui.core.data.DataKey;
import com.crystalgui.core.command.CommandRegistry;

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
public class CrystalEditor extends UIElement implements Disposable {

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
     * The GLSL a graph emits, as a document of its own — one per graph.
     *
     * <h3>A document, not a singleton, and it was the other thing</h3>
     *
     * <p>The old reasoning was that "there is one emit and it is a <em>view of</em> the graph rather than a
     * thing you can have two of". That is true of one graph and false of the editor: <b>five open graphs
     * have five different generated shaders</b>, and a single panel showing whichever is in front cannot be
     * left open beside a second graph, cannot be compared with one, and loses its scroll position every
     * time the front tab changes. Its own title gave the answer away — {@code compiled_graph.shader} reads
     * as a file because it is one.</p>
     *
     * <p>So it is a derived document, keyed by the graph it came from: Unity's Shader Graph opens "View
     * Generated Shader" per graph, and it is the same shape as IntelliJ's decompiled-class view and VS
     * Code's diff editor. {@link #showCompiled(ShaderGraphEditor)} opens one.</p>
     *
     * <p>Being a document also keeps it off the activity bar without anyone saying so, since that lists
     * singletons only — which is right: it is not a tool window, and there is no single one of it to
     * toggle.</p>
     */
    public static final String SHADER_SOURCE_TYPE = "shadersource";

    /**
     * The scheme a generated shader lives under — {@code shader-generated://<the graph it came from>}.
     *
     * <p>The panel type says which widget draws it; the scheme says what it <b>is</b>, and is what
     * carries the origin. Two names for what looks like one thing, and they are genuinely different
     * questions: a diff view of the same generated source would share the scheme and not the type.</p>
     */
    public static final String SHADER_SOURCE_SCHEME = "shader-generated";

    /** The descriptor's fallback title. A real tab is named for its graph — see {@link #compiledTitleFor}. */
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
    private final UIElement inspectorHost = fillingHost();

    /** Graph document path -> its editor, so a generated-source tab can find what it was derived from. */

    /**
     * A host that fills its panel and lets its one child fill it in turn.
     *
     * <p>Not decoration. The dock sizes the element the registry hands it, and it used to hand over the
     * editor's own {@code source()} — which therefore got the panel's box directly. A bare wrapper in
     * between takes that box and then gives its child <b>content height</b>, because Taffy's default
     * {@code flex-shrink} is 0 and a column child with no basis keeps its own size: the panel looks
     * empty, and nothing about the widget inside is wrong.</p>
     */
    private static UIElement fillingHost() {
        return new UIElement().addClass(PANEL_HOST_CLASS);
    }

    /** @see #fillingHost() */
    public static final String PANEL_HOST_CLASS = "__panel-host__";

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

    /**
     * Owns every graph this editor built, so closing the editor releases their preview renderers.
     *
     * <p>Registration replaces what {@code delete()} used to do by looping {@link #graphs}. The list
     * survives only as "which graphs exist"; it is no longer what keeps them alive, which is the
     * distinction that was quietly wrong before — nothing pruned it, so it retained every graph ever
     * opened for the whole session.</p>
     */
    private void own(ShaderGraphEditor graph) {
        Disposer.register(this, graph);
    }

    /** The last {@link #saveLayout} result, so {@link #restoreLayout()} has something to restore. */
    @Nullable
    private Object savedLayout;

    private boolean focusGiven;

    /**
     * This editor, for a command that acts on one.
     *
     * <p>What let {@code CrystalEditorCommands} stop capturing an editor and a window, and with them the
     * last reason {@code install(window)} existed.</p>
     */
    public static final DataKey<CrystalEditor> CRYSTAL_EDITOR =
            DataKey.create("crystalEditor", CrystalEditor.class);

    @Override
    public Object getData(DataKey<?> key) {
        if (key == CRYSTAL_EDITOR) return this;
        return super.getData(key);
    }

    /** Names this editor at the window level too — {@code Mod+S} is pressed with nothing focused as often
     * as not. Same reason {@code Workbench} does it; see {@code DataContext}. */
    @Override
    protected void onWindowChanged(@Nullable UIWindow previous, @Nullable UIWindow current) {
        if (previous != null) previous.removeDataProvider(this);
        if (current != null) current.addDataProvider(this);
    }

    /**
     * The application's own verbs — saving, layout, and the command palette.
     *
     * <p>These are the <em>product's</em> offerings rather than a widget's, which is why they sit on the
     * shell element and not on a generic one. They are still per class and context-resolved like every
     * other set; nothing here is registered per window any more.</p>
     */
    @Override
    protected void registerCommands(CommandRegistry registry) {
        CrystalEditorCommands.register();
        ChromeCommands.register();
    }

    public CrystalEditor(WorkspaceClient<?> client) {
        setFocusPolicy(FocusPolicy.NONE);
        workbench = new Workbench(client);
        workbench.onStatus.connect(onStatus::emit);
        // The inspector and the generated source follow the front tab. Was a per-frame poll; the dock
        // announces it now. Subscribed here rather than on attach because the dock exists as soon as the
        // workbench does, and this editor owns the workbench -- there is nothing to wait for and nothing
        // that can outlive it.
        workbench.dock().onDidChangeActivePanel.connect(panel -> followActiveGraph());
        // A restore waits on listings, which arrive over several frames -- a folder cannot be expanded
        // before the listing revealing it lands. Retried per LISTING rather than per frame: fewer
        // attempts, and every one of them at a moment when the answer may actually have changed.
        workbench.fileTree().source().onDidLoadListing.connect(directory -> {
            if (session != null) session.tick();
        });

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
            own(editor);
            // The graph knows its own address now. What used to be here -- graphPaths.put(...) -- was an
            // application-side map from path to graph, plus a reverse linear scan over it, existing only
            // because a document could not say what it was.
            editor.setResource(Resource.of(path));
            // THE GRAPH ASKS, THE SHELL DECIDES. The graph knows it can emit GLSL and nothing about docks;
            // wiring it here is what keeps a graph usable outside an editor -- and testable without one.
            editor.onViewGeneratedRequested.connect(() -> showCompiled(editor));
            // FOLLOW THE SELECTION, not only the active tab. Clicking a node is the gesture that means
            // "inspect this", and it is a truer signal than which panel the dock considers focused --
            // pressing a node in a graph that is not the active tab must still fill the inspector, and
            // the Inspector panel itself becoming focused must not count as a graph coming forward.
            editor.graph().getSelection().onChanged.connect(() -> show(editor));
            return editor;
        });
        workbench.bindEditorExtensions(SHADER_GRAPH_FILE_TYPE, "shadergraph");
        // HOSTS, not the editors' own parts. There is no longer one graph to bind these to, so each is a
        // container that follows whichever shader graph is in front -- see followActiveGraph().
        // Icons and anchors, so the activity bar can draw them and reopen them where they belong. A
        // singleton with no icon still gets a button -- it just draws as a bare accent block, which is
        // what the rail looked like before these two were given one.
        // A DOCUMENT, one per graph -- not a singleton view that follows the front tab.
        //
        // It was the latter, and the tell was in its own title: "compiled_graph.shader" reads as a file
        // because it IS one, conceptually. Five open graphs have five different generated shaders, and one
        // shared panel showing whichever is in front cannot be diffed against another, cannot be left open
        // beside a second graph, and loses its scroll position every time the front tab changes.
        //
        // Unity's Shader Graph does exactly this -- "View Generated Shader" opens a separate window per
        // graph -- and it is the same shape as IntelliJ's decompiled-class view and VS Code's diff editor:
        // a DERIVED document, keyed by what it was derived from.
        //
        // Being a document also takes it off the activity bar for free, since that lists singletons only.
        workbench.registerPanel(DockPanelDescriptor.document(SHADER_SOURCE_TYPE, SHADER_SOURCE_TITLE),
                ref -> {
                    ShaderGraphEditor graph = graphFor(ref.state(Workbench.PATH_STATE, ""));
                    // An empty box rather than null when the graph has since closed: a tab with nothing
                    // behind it is visible and reportable, a silently absent one looks like a failed
                    // restore. Same reasoning DockGroup.contentFor already uses.
                    return graph == null ? new UIElement() : graph.source();
                });
        // BESIDE the canvas, not in its strip. A tab in the same group would hide the graph, and the whole
        // point of the emitted source is watching it change as you wire -- a panel you have to switch away
        // from the graph to read is a panel that is never read.
        workbench.registerPanel(DockPanelDescriptor.singleton(INSPECTOR_TYPE, "Inspector")
                        .icon("crystalgui:package").anchor(DockDropZone.SPLIT_RIGHT),
                ref -> inspectorHost);
        // The Inspector opens with the workbench; the generated source does not. It is now a document
        // opened on demand by showCompiled(), so putting one in the default layout would mean a tab for a
        // graph nobody has opened yet.
        workbench.openPanelBeside(new DockPanelRef(INSPECTOR_TYPE),
                DockDropZone.SPLIT_RIGHT, SOURCE_SHARE);

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

    // This widget registers NO per-frame ticker, and that is the point of step 3.
    //
    // It used to own one, started from onLayoutChanged behind a `ticking` latch, doing two things:
    // followActiveGraph() -- now driven by DockArea.onDidChangeActivePanel -- and WorkbenchSession.tick(),
    // which re-attempted a restore that was waiting on listings. Both are subscriptions in the
    // constructor now, and the latch, the override and the ticker went with them.

    /**
     * Points the source and inspector panels at whichever shader graph is in front.
     *
     * <h3>Driven by the dock, no longer polled</h3>
     *
     * <p>This ran every frame, and its own comment argued for that: the dock changes the active panel from
     * a click, a close, a drag, a split and a layout restore, and a signal that missed any of those would
     * leave these panels showing a graph that is no longer in front. The objection was sound and the
     * answer was to make the announcement exact rather than to keep polling —
     * {@code DockArea.announceActivePanel} compares against the last value it announced, so <em>every</em>
     * one of those paths can call it and none of them has to know whether it changed anything.</p>
     *
     * <p><b>A non-graph tab leaves them alone.</b> Clicking a README must not blank the inspector — there
     * is still a shader graph open and it is still what these panels are about. That is what
     * {@link #followed} latches, and it matters more now, not less: the signal legitimately emits null
     * when chrome takes focus, and reopening the Inspector is exactly that gesture.</p>
     */
    private void followActiveGraph() {
        // LATCHED. activeGraph() means "the graph in front", and focusing chrome legitimately means there
        // is none -- reopening the Inspector makes the INSPECTOR'S group active, so the very gesture that
        // asks to see a graph is the one that reports none. IntelliJ has the same requirement and answers
        // it the same way: FileEditorManagerListener.selectionChanged fires on EDITOR selection, so
        // clicking a tool window never clears what a tool window is looking at.
        ShaderGraphEditor active = activeGraph();
        if (active != null) followed = active;
        if (followed != null) show(followed);
    }

    /**
     * The graph the panels are pointed at, which outlives any moment when nothing is in front.
     *
     * <p>Separate from {@link #activeGraph()} rather than folded into it, because the two answer different
     * questions and only one of them should be sticky: a command asking "which graph am I acting on"
     * wants the honest null, and a panel asking "what am I displaying" wants the last real answer.</p>
     */
    @Nullable
    private ShaderGraphEditor followed;

    /**
     * Opens the generated shader for a graph, or focuses the tab that is already showing it.
     *
     * <h3>Keyed by the graph's path, which is what makes them distinct</h3>
     *
     * <p>The ref carries the graph's own path in {@code PATH_STATE}, so five open graphs produce five
     * refs and therefore five tabs -- and re-invoking on a graph that already has one finds it rather
     * than opening a second, because {@code DockPanelRef} equality is over type and state.</p>
     *
     * <p>Opened <b>in the graph's own strip</b>, as a sibling tab. It used to be a pane beside it, on the
     * reasoning that the point of the generated source is watching it change as you wire — but that was an
     * argument for the old <em>singleton</em>, which had nowhere else to be. Now that a graph has its own,
     * they belong together: the pair travels as one when the strip is dragged, and anyone who does want
     * them side by side drags the tab out, which is one gesture and their choice rather than ours.</p>
     *
     * @return whether anything was opened -- false when the graph has no path, which is not a document
     */
    public boolean showCompiled(@Nullable ShaderGraphEditor graph) {
        if (graph == null || graph.resource() == null) return false;
        Resource origin = graph.resource();
        // The tab's input IS the derived resource -- "the generated source of that graph" -- rather than
        // the graph's path plus a convention about what this panel type means by it.
        Resource generated = Resource.derived(SHADER_SOURCE_SCHEME, origin);

        DockPanelRef ref = new DockPanelRef(SHADER_SOURCE_TYPE)
                .withState(Workbench.PATH_STATE, generated.toString())
                .withState(DockPanelRef.TITLE, compiledTitleFor(generated));
        DockPanelRef graphRef = workbench.refFor(origin.asPath());
        if (workbench.dock().layout().leafContaining(graphRef) != null) {
            workbench.openPanelWith(graphRef, ref);
            // openPanelWith deliberately restores the previous selection -- right for its original caller
            // and wrong here, since this tab is open because someone just asked to see it.
            DockLeaf strip = workbench.dock().layout().leafContaining(ref);
            if (strip != null) strip.activate(ref);
            workbench.dock().syncGroups();
        } else {
            workbench.openPanel(ref);
        }
        return true;
    }

    /** Opens the generated shader for whichever graph is in front. */
    public boolean showCompiled() {
        return showCompiled(activeGraph());
    }

    /**
     * {@code shader-generated://proj:shaders/fire.shadergraph} to {@code fire_compiled.shader}.
     *
     * <p>A label rule over a resource, which is what VS Code's {@code ILabelService} is. It reads the
     * <b>origin's</b> name because that is what the document is about — {@code Resource.name()} already
     * answers with the origin for a derived resource, so this only has to strip the extension.</p>
     */
    public static String compiledTitleFor(Resource generated) {
        String name = generated.name();
        int dot = name.lastIndexOf('.');
        return (dot < 0 ? name : name.substring(0, dot)) + "_compiled.shader";
    }

    /**
     * The graph a generated-source tab is showing, resolved from its own input.
     *
     * <p>{@code shader-generated://<graph path>} carries the origin, so this is a parse and a lookup in
     * the document store rather than a map maintained beside it. A restored session therefore resolves
     * with nothing having been rebuilt first, which the map could not promise.</p>
     */
    @Nullable
    private ShaderGraphEditor graphFor(String rawResource) {
        if (rawResource.isEmpty()) return null;
        Resource parsed;
        try {
            parsed = Resource.parse(rawResource);
        } catch (RuntimeException unparseable) {
            return null;
        }
        // A session saved BEFORE this panel's state became a derived resource stored the graph's bare
        // path. That parses as a project resource with no origin, and reading it as the origin itself
        // costs one line -- against invalidating every saved layout that had this tab open, which is what
        // a version bump would have meant. The two forms are unambiguous: a derived resource always has
        // an origin and a bare path never does.
        Resource origin = parsed.origin() != null ? parsed.origin() : parsed;
        if (!origin.isProject()) return null;
        FileDocument document = workbench.documentFor(origin.asPath());
        return document instanceof ShaderGraphEditor graph ? graph : null;
    }

    /** Points both panels at {@code graph} — <b>by asserting the result, not by remembering the act</b>.
     *
     * <h3>Why there is no "unchanged, skip it" guard any more</h3>
     *
     * <p>There was one, keyed on a {@code shownGraph} field, and it was the bug: it memoised a <em>side
     * effect that something else could undo</em>. The hosts are ordinary elements in a dock that rebuilds
     * itself on every open, close, split and restore, so anything that emptied one left the guard still
     * claiming it was populated — and the panel came back blank with nothing able to notice. That it
     * happened only sometimes is the signature of the bug rather than a mitigating detail: it depended on
     * tick ordering.</p>
     *
     * <p>Comparing the host's actual child instead is <b>self-healing</b>: it does not need to know which
     * paths can empty a host, only what the host should contain. That is the difference between a fix for
     * the cause you found and a fix for the class of causes.</p>
     *
     * <p>Still cheap enough for the per-frame poll it is called from — a size check and one reference
     * comparison per host, and it touches no element in the settled case.</p>
     */
    private void show(@Nullable ShaderGraphEditor graph) {
        if (graph == null) return;
        // THE ONE PLACE THE FOLLOWED GRAPH CHANGES. show() is called by the per-frame poll and directly by
        // a graph's own selection handler, and only the poll used to update the latch -- so a selection in
        // a graph that was not the front tab pointed the panels at it for exactly one frame before the
        // poll pulled them back. Setting it here makes the two agree by construction rather than by both
        // happening to want the same thing.
        followed = graph;
        assertOnlyChild(inspectorHost, inspectorFor(graph));
    }

    /**
     * Makes {@code wanted} the host's child, doing nothing when it already is.
     *
     * <h3>Asks about PARENTAGE, not about the child list</h3>
     *
     * <p>This read {@code children.size() == 1 && children.get(0) == wanted}, and that threw
     * {@code "Cannot add the same child twice"} — because {@code clearAllChildren()} <b>silently refuses
     * internal children</b>, so a host whose subtree had been stamped by a {@code markAsInternal()}
     * somewhere above it kept its child through the clear and then rejected the add. The list said one
     * thing and the tree another.</p>
     *
     * <p>Parentage cannot disagree with itself. It is also the question actually being asked: "is the host
     * already showing this?" — and the size check was answering a stricter one that happens to coincide
     * most of the time, which is the worst kind of check.</p>
     */
    private static void assertOnlyChild(UIElement host, UIElement wanted) {
        if (wanted.getParent() == host) return;
        // NOT clearAllChildren(). It skips internal children by design, and a ShaderGraphInspector calls
        // markAsInternal() on ITSELF in its constructor -- so the outgoing one could never leave, and the
        // incoming one stacked underneath it. Two inspectors in one tab, which is what that looked like.
        //
        // Removing through the matching API is the fix rather than un-marking the inspector: internal is
        // the inspector's own statement about its parts, and it is right -- nobody should be able to
        // reach into it with removeChild. What was wrong was the host assuming one kind of child.
        for (UIElement child : new ArrayList<>(host.getChildren())) {
            if (child.isInternalUI()) {
                host.removeInternalChild(child);
            } else {
                host.removeChild(child);
            }
        }
        host.addChild(wanted);
    }

    // install(UIWindow) is gone, and nothing replaced it.
    //
    // Every command set in the application now arrives with the element that owns it -- DockArea the
    // dock's, GraphView the graph's, TextEditor the editor's, Workbench the explorer's, and this class
    // its own -- each through UIElement.registerCommands, once per class. Their chords are either
    // declared on the commands (application-wide) or bound in bindKeys on the element that scopes them.
    // Constructing the editor is what wires it; there is nothing for a host to remember.

    // ── Persistence ─────────────────────────────────────────────────────────────────────────────

    /**
     * Where preferences and session records go. Null until {@link #useConfig} is called, and everything
     * below is then a no-op — an editor with nowhere to save is a valid one, and is what a test is.
     */
    @Nullable
    private ConfigStorage storage;

    @Nullable
    private WorkbenchSession session;

    /**
     * Gives the editor somewhere to keep the user's preferences and its session records, and loads the
     * preferences immediately.
     *
     * <p>Loading here rather than at first paint because the values decide how things are built:
     * {@code editor.tabSize} is read when a document is created, so arriving late would apply it to every
     * file except the ones already open.</p>
     */
    public CrystalEditor useConfig(ConfigStorage storage) {
        this.storage = storage;
        this.session = new WorkbenchSession(workbench, storage);
        loadPreferences();
        return this;
    }

    @Nullable
    public WorkbenchSession session() {
        return session;
    }

    /** The user layer, read into the ROOT scope so it applies to every panel. @see WorkbenchSettings */
    public void loadPreferences() {
        if (storage == null) return;
        SettingsModel loaded = SettingsCodec.fromJson(storage.read(USER_SETTINGS_FILE));
        settingsHost().replaceLayer(SettingsLayer.USER, loaded.asMap());
        WorkbenchSettings.install(workbench, settingsHost());
        // Written on change rather than only at shutdown. A preferences window that applies immediately
        // and saves only on a clean exit loses everything to a crash -- and the file is a few hundred
        // bytes, so there is nothing to batch. VS Code writes settings.json the same way.
        settingsHost().onChanged.connect(change -> {
            if (change.layer() == SettingsLayer.USER) savePreferences();
        });
    }

    public void savePreferences() {
        if (storage == null || !storage.isWritable()) return;
        storage.write(USER_SETTINGS_FILE,
                SettingsCodec.toJson(settingsHost().layer(SettingsLayer.USER)));
    }

    /**
     * The scope preferences live in: this element, which is the outermost thing every panel resolves
     * through.
     *
     * <p>Not the workbench's own store. Settings resolve <em>outward</em>, so a value written on the
     * workbench would be invisible to anything outside it, and a value written here reaches everything —
     * which is what "a preference" means.</p>
     */
    public Settings settingsHost() {
        return settings();
    }

    public static final String USER_SETTINGS_FILE = "settings.json";

    /** Restores the last session for {@code projectId}, unless the user has turned that off. */
    public boolean restoreSession(String projectId) {
        if (session == null || !workbench.resolve(WorkbenchSettings.RESTORE_SESSION)) return false;
        return session.restore(projectId);
    }

    public void saveSession(String projectId, int screenWidth, int screenHeight) {
        if (session != null) session.save(projectId, screenWidth, screenHeight);
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
    @Override
    public void dispose() {
        // Nothing of its own: every graph is registered as a child when it is built, so the tree
        // releases them. The list this replaced was never pruned -- every graph ever opened stayed
        // reachable for the session, and that retention was the only reason its GL pool was freed at
        // exit at all.
    }

}
