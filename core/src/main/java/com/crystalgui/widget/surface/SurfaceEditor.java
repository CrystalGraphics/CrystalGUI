package com.crystalgui.widget.surface;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.undo.UndoCommands;
import com.crystalgui.core.undo.UndoScope;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.core.data.DataKey;
import com.crystalgui.core.data.DataProvider;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.data.UiDataKeys;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.input.keymap.Keymap;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.canvas.CanvasView;
import com.crystalgui.widget.config.inspector.InspectorRegistry;
import com.crystalgui.widget.config.inspector.InspectorSection;
import com.crystalgui.widget.surface.edit.Clipboard;
import com.crystalgui.widget.surface.edit.Edits;
import com.crystalgui.widget.canvas.WorldRect;
import com.crystalgui.widget.surface.extension.SurfaceExtensions;
import com.crystalgui.widget.surface.mode.Cursors;
import com.crystalgui.widget.surface.mode.Modes;
import com.crystalgui.widget.surface.overlay.Geometry;
import com.crystalgui.widget.surface.overlay.OverlayLayer;
import com.crystalgui.widget.surface.overlay.Snapping;
import com.crystalgui.widget.surface.select.Picking;
import com.crystalgui.widget.surface.insert.InsertSource;
import com.crystalgui.widget.surface.mode.ToolKind;
import com.crystalgui.widget.surface.overlay.OverlayKind;
import com.crystalgui.widget.surface.overlay.ViewModeKind;
import com.crystalgui.widget.surface.select.SurfaceSelection;

/**
 * <b>An editing surface</b>: a plane of items you select, move, insert into and look at through tools
 * and overlays. One per open document, and the thing a node graph and a UI builder are both built on.
 *
 * <p>It ships nothing. {@code new SurfaceEditor(policy, List.of())} is a plane and nothing else — every
 * tool, overlay, view mode, insert source, drop handler and inspector section arrives as a
 * {@link com.crystalgui.widget.surface.extension.SurfaceExtension}, discovered on the classpath and
 * enabled by id.</p>
 *
 * <pre>{@code
 * SurfaceEditor surface = new SurfaceEditor(new GraphPolicy(document),
 *         List.of("crystalgui:select", "mymod:grid"));
 * editorArea.append(surface);
 * surface.surface().place(node, 40f, 40f);
 * }</pre>
 *
 * <p>A consumer supplies one {@link SurfacePolicy} — what an item is, who owns a press, what a move
 * writes — and everything the engine does asks that rather than knowing. Dispose the editor when the
 * document closes: every extension handle goes with it.</p>
 *
 * <p>Extensions never name this class. They are written against {@link SurfaceContext}, which this
 * implements: an engine that can be named can be reached into.</p>
 */
public class SurfaceEditor extends UIElement
        implements SurfaceContext, DataProvider, UndoScope, Disposable {

    /**
     * This widget's kind.
     *
     * <p>Declared because a subclass inherits its parent's kind unless it is given its own: without it
     * every rule a sheet writes for {@code surface} matches nothing at all.</p>
     */
    public static final Name NAME = Name.of("surface");

    /**
     * This surface, for a command that acts on one.
     *
     * <p>Not {@code "surface"}: {@code CommandPalette} declared that name for the {@code UIDocument} it
     * opens over, and a key is interned by NAME with its TYPE as part of the declaration — so the second
     * class to initialise throws. {@code ContextKeys} resolves by name out of a {@code when} expression,
     * which is why the shipped one is the one that must not move.</p>
     */
    public static final DataKey<SurfaceEditor> SURFACE =
            DataKey.create("editingSurface", SurfaceEditor.class);

    private final SurfacePolicy policy;
    private final CanvasView canvas = new CanvasView();
    private final Surface surface;
    private final SurfaceSelection selection;
    private final Edits edits;
    private final Picking picking;
    private final Geometry geometry;
    private final OverlayLayer overlays;
    private final Snapping snapping = new Snapping();
    private final Cursors cursors;
    private final Modes modes;

    @Nullable
    private Clipboard<?> clipboard;

    private final List<ToolKind> tools = new ArrayList<>();
    private final List<OverlayKind> overlayKinds = new ArrayList<>();
    private final List<ViewModeKind> viewModes = new ArrayList<>();
    private final List<InsertSource> insertSources = new ArrayList<>();
    private final List<DropHandler> dropHandlers = new ArrayList<>();
    private final List<InspectorSection> sections = new ArrayList<>();
    private final List<Command> commands = new ArrayList<>();

    private final List<Disposable> extensions;

    private boolean disposed;

    /**
     * Fires when this surface joins a window — the first moment anything needing one may run.
     *
     * <p>A consumer's theme goes here: resolving a stylesheet id reads a file, and a constructor is also
     * run by a server.</p>
     */
    public final Signal.Action onDidConnect = new Signal.Action();

    /** Everything on the classpath. What a test or a bare surface means. */
    public SurfaceEditor(SurfacePolicy policy) {
        this(NAME, policy, null);
    }

    /** @param enabled the extension ids this surface wants, or null for everything contributed */
    public SurfaceEditor(SurfacePolicy policy, @Nullable List<String> enabled) {
        this(NAME, policy, enabled);
    }

    /** For a subclass that declares its own kind. @see #NAME */
    protected SurfaceEditor(Name name, SurfacePolicy policy, @Nullable List<String> enabled) {
        super(name);
        this.policy = Objects.requireNonNull(policy, "a surface needs a policy");
        // Content goes through surface().place(). A child of the editor would sit outside the plane
        // transform and stay nailed to the screen while everything else panned.
        refusePublicChildren();
        StyleGroup.defaultPipeline(getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).heightPercent(100f));
        StyleGroup.defaultPipeline(canvas.getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).heightPercent(100f));
        appendStructural(canvas);
        this.surface = new Surface(canvas);
        this.selection = new SurfaceSelection(policy::markSelected);
        this.edits = new Edits(policy.history());
        this.picking = new Picking(new Picking.Surfaces() {
            @Override
            public UIDocument window() {
                return document();
            }

            @Override
            public List<UIElement> items() {
                return surface.items();
            }

            @Override
            public WorldRect boundsOf(UIElement item) {
                return surface.boundsOf(item);
            }

            @Override
            public UIElement itemFor(UIElement hit) {
                return policy.itemFor(hit);
            }
        });
        this.geometry = new Geometry(surface);
        this.overlays = new OverlayLayer(this);
        this.cursors = new Cursors(this::document);
        this.modes = new Modes(this);
        this.extensions = SurfaceExtensions.activate(this, enabled);
    }

    /**
     * Every surface gets the engine's commands, with nothing installing them.
     *
     * <p>Called once for this class by the engine. Before a hook like this existed, a host had to install
     * them and a surface dropped into a scene that forgot had no Delete and no Select All, silently.</p>
     */
    @Override
    protected void registerCommands(CommandRegistry registry) {
        SurfaceCommands.register();
        // Undo comes with any surface, because a surface IS an undo scope -- the stack is resolved from
        // focus, so this needs no element and binds to nothing.
        UndoCommands.register();
    }

    /**
     * The engine's chords, on this surface.
     *
     * <p>Per instance, and it has to be: {@code F} and {@code A} are <b>bare letters</b>, so they may
     * only be live while focus is inside a surface. Declaring them on the commands would make them
     * application-wide and cost every text field three letters.</p>
     */
    private final Keymap keymap = defaultKeymap();

    private static Keymap defaultKeymap() {
        Keymap keymap = new Keymap();
        SurfaceCommands.bindDefaults(keymap);
        return keymap;
    }

    @Override
    public Keymap keymapOrNull() {
        return keymap;
    }

    /** The consumer's history, so Ctrl+Z resolved from focus finds the right one. */
    @Override
    public UndoStack undoStack() {
        return edits.history();
    }

    // ── The seam ────────────────────────────────────────────────────────────

    @Override
    public Surface surface() {
        return surface;
    }

    @Override
    public SurfaceSelection selection() {
        return selection;
    }

    @Override
    public Edits edits() {
        return edits;
    }

    @Override
    public Picking picking() {
        return picking;
    }

    @Override
    public Modes modes() {
        return modes;
    }

    @Override
    public Geometry geometry() {
        return geometry;
    }

    @Override
    public OverlayLayer overlays() {
        return overlays;
    }

    @Override
    public Snapping snapping() {
        return snapping;
    }

    @Override
    public Cursors cursors() {
        return cursors;
    }

    @Override
    @Nullable
    public Clipboard<?> clipboard() {
        return clipboard;
    }

    /**
     * Says what a fragment is here, so cut, copy and paste work.
     *
     * <p>The consumer sets this once; a surface with no notion of a fragment leaves it unset and the
     * clipboard commands stay disabled.</p>
     */
    public SurfaceEditor setClipboard(@Nullable Clipboard<?> clipboard) {
        this.clipboard = clipboard;
        return this;
    }

    @Override
    public Disposable registerTool(ToolKind kind) {
        if (kind.factory() == null) {
            throw new IllegalArgumentException("the tool " + kind.id() + " has no factory, so picking "
                    + "it would do nothing -- call ToolKind.tool(...)");
        }
        return add(tools, kind);
    }

    @Override
    public Disposable registerOverlay(OverlayKind kind) {
        if (kind.factory() == null) {
            throw new IllegalArgumentException("the overlay " + kind.id() + " has nothing to draw -- "
                    + "call OverlayKind.element(...)");
        }
        return add(overlayKinds, kind);
    }

    @Override
    public Disposable registerViewMode(ViewModeKind kind) {
        if (kind.factory() == null) {
            throw new IllegalArgumentException("the view mode " + kind.id() + " has no factory -- "
                    + "call ViewModeKind.mode(...)");
        }
        return add(viewModes, kind);
    }

    @Override
    public Disposable registerInsertSource(InsertSource source) {
        return add(insertSources, source);
    }

    @Override
    public Disposable registerDropHandler(DropHandler handler) {
        return add(dropHandlers, handler);
    }

    @Override
    public Disposable registerSection(InspectorSection section) {
        sections.add(section);
        InspectorRegistry.register(section);
        return () -> {
            if (sections.remove(section)) InspectorRegistry.remove(section);
        };
    }

    @Override
    public Disposable registerCommand(Command command) {
        commands.add(command);
        CommandRegistry.global().register(command);
        return () -> {
            if (commands.remove(command)) CommandRegistry.global().unregister(command.getId());
        };
    }

    @Override
    public <T> T policy(Class<T> type) {
        if (!type.isInstance(policy)) {
            throw new IllegalArgumentException("this surface has a " + policy.getClass().getName()
                    + " policy, not a " + type.getName()
                    + " -- a feature is registered on the wrong consumer");
        }
        return type.cast(policy);
    }

    /** The policy this surface was built with. */
    public SurfacePolicy surfacePolicy() {
        return policy;
    }

    // ── What is registered ──────────────────────────────────────────────────

    public List<ToolKind> tools() {
        return List.copyOf(tools);
    }

    @Override
    public List<OverlayKind> overlayKinds() {
        return List.copyOf(overlayKinds);
    }

    public List<ViewModeKind> viewModes() {
        return List.copyOf(viewModes);
    }

    public List<InsertSource> insertSources() {
        return List.copyOf(insertSources);
    }

    public List<DropHandler> dropHandlers() {
        return List.copyOf(dropHandlers);
    }

    public List<InspectorSection> sections() {
        return List.copyOf(sections);
    }

    public List<Command> commands() {
        return List.copyOf(commands);
    }

    @Override
    public Object getData(DataKey<?> key) {
        if (key == SURFACE) return this;
        // CUT/COPY/PASTE FOR THE SHELL, derived rather than hand-written: a surface says what a fragment
        // is through its Clipboard and gets the Edit menu's four for nothing.
        if (key == UiDataKeys.CLIPBOARD) return clipboard == null ? null : SurfaceCommands.actionsFor(this);
        if (key == UiDataKeys.SELECTION) return new ArrayList<Object>(selection.items());
        // No super: a UIElement is not a DataProvider, and the walk outward through commandParent() is
        // what reaches the next one.
        return undoScopeData(key);
    }

    /** Releases every extension handle. Idempotent. */
    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        // Backwards, so a feature registered later goes before what it was registered against.
        modes.detach();
        for (int i = extensions.size() - 1; i >= 0; i--) {
            extensions.get(i).dispose();
        }
        extensions.clear();
    }

    @Override
    protected void connected() {
        super.connected();
        // The mode joins the window's input stack here rather than at construction: a surface with no
        // window has nothing to push onto, and a tool must not be live before the tree it acts on is.
        modes.attach(document());
        onDidConnect.emit();
    }

    @Override
    protected void disconnected() {
        super.disconnected();
        modes.detach();
    }

    private <T> Disposable add(List<T> into, T what) {
        into.add(what);
        return () -> into.remove(what);
    }
}
