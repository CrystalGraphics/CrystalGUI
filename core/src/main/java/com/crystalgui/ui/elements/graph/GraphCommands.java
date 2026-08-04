package com.crystalgui.ui.elements.graph;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.graph.GraphDocument;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.input.keymap.Keymap;

import javax.annotation.Nullable;
import java.util.function.Consumer;

/**
 * The graph editing commands and their default bindings — delete, select all, and Unity's framing keys.
 *
 * <h3>Commands, for the same three reasons undo is</h3>
 * <p>A keystroke, a context menu and a command palette all need one enablement answer, and a user who
 * wants Delete on some other key should edit a keymap rather than Java. {@code Command.isEnabled} is
 * already that mechanism, so <i>Delete</i> greys itself out with nothing selected for free.</p>
 *
 * <h3>Enablement doubles as scoping</h3>
 * <p>Every command here resolves the nearest enclosing {@link GraphView} from the focused element, and
 * is <b>disabled when there is none</b>. That matters because two of the bindings are bare letters:
 * Unity uses {@code F} to frame the selection and {@code A} to frame everything, which would be
 * intolerable as application-wide keys. Disabled commands do not fire, so pressing {@code A} anywhere
 * outside a graph does nothing here and the keystroke carries on to whatever wanted it.</p>
 *
 * <p>The keymap's own text-input guard is the second layer: a single-key binding never fires while
 * something that consumes text has focus, which is what keeps {@code A} from being unusable in a field
 * inside a node.</p>
 *
 * <h3>Installed explicitly</h3>
 * <p>As with {@code UndoCommands}, and for the reason recorded there: this engine does not inject its
 * own defaults, and a registry that quietly acquired commands nobody registered surprises anything that
 * enumerates it.</p>
 */
public final class GraphCommands {

    public static final String DELETE = "graph.delete";
    public static final String SELECT_ALL = "graph.selectAll";
    public static final String CLEAR_SELECTION = "graph.clearSelection";
    public static final String FRAME_SELECTION = "graph.frameSelection";
    public static final String FRAME_ALL = "graph.frameAll";
    public static final String CREATE_NODE = "graph.createNode";
    public static final String CUT = "graph.cut";
    public static final String COPY = "graph.copy";
    public static final String PASTE = "graph.paste";
    public static final String DUPLICATE = "graph.duplicate";

    /**
     * Where a pasted or duplicated copy lands, relative to its original, in world units.
     *
     * <p>Offset rather than exactly on top: a copy hidden behind its source looks like nothing happened,
     * and the first thing anyone does is drag it away — so it starts already moved. Down and right, which
     * is what every editor does and matches the direction a cascade of repeated pastes should run.</p>
     */
    private static final float PASTE_OFFSET = 24f;

    /**
     * The clipboard, shared by every graph in the process.
     *
     * <p>Static, deliberately, and against this file's own instinct elsewhere: a clipboard that lived on
     * a {@code GraphView} could not copy from one shader graph and paste into another, which is most of
     * the reason to have one. It is the same call the system clipboard makes, and the leak-between-tests
     * worry {@code CommandRegistry} records does not apply the same way — a stale clipboard changes what
     * a paste produces, not what a command resolves to, and {@link #clearClipboard()} exists for a test
     * that cares.</p>
     */
    @Nullable
    private static GraphDocument clipboard;

    /** Forgets the shared clipboard. For a test that must not see what another one copied. */
    public static void clearClipboard() {
        clipboard = null;
    }

    /** World units of breathing room when framing. Not a pixel value in a widget — framing is a view
     * operation with no stylesheet to read, and the number is a margin in the document's own units. */
    private static final float FRAME_PADDING = 24f;

    private GraphCommands() {
    }

    public static void register(CommandRegistry registry) {
        if (registry.contains(DELETE)) return;

        registry.register(Command.of(DELETE, "Delete")
                .run(context -> withGraph(context, GraphView::deleteSelection))
                .enabledWhen(context -> {
                    GraphView graph = graphFor(context);
                    return graph != null && !graph.getSelection().isEmpty();
                }));

        registry.register(Command.of(SELECT_ALL, "Select All")
                .run(context -> withGraph(context, GraphView::selectAll))
                .enabledWhen(context -> graphFor(context) != null));

        registry.register(Command.of(CLEAR_SELECTION, "Deselect")
                .run(context -> withGraph(context, GraphView::clearSelection))
                .enabledWhen(context -> {
                    GraphView graph = graphFor(context);
                    return graph != null && !graph.getSelection().isEmpty();
                }));

        registry.register(Command.of(FRAME_SELECTION, "Frame Selection")
                .run(context -> withGraph(context, graph -> graph.frameSelection(FRAME_PADDING)))
                .enabledWhen(context -> graphFor(context) != null));

        registry.register(Command.of(CREATE_NODE, "Create Node")
                // At the POINTER, as Unity does. An earlier version opened at the middle of the view on
                // the reasoning that a command knows who invoked it rather than where the mouse is --
                // which is wrong: the input handler holds the live pointer position, and a command can
                // simply ask. The centre of the view is only the fallback for a pointer that is not over
                // this graph at all, which is possible when the binding fires from elsewhere.
                .run(context -> withGraph(context, graph -> {
                    var window = graph.getAttachedWindow();
                    if (window != null) {
                        var pointer = window.getInputHandler().pointerPosition();
                        if (graph.containsScreenPoint(pointer.x(), pointer.y())) {
                            var world = graph.screenToWorld(pointer.x(), pointer.y());
                            graph.openCreationMenu(world.x(), world.y());
                            return;
                        }
                    }
                    var visible = graph.visibleWorldRect();
                    graph.openCreationMenu(visible.centerX(), visible.centerY());
                }))
                .enabledWhen(context -> {
                    GraphView graph = graphFor(context);
                    return graph != null && graph.creationMenu() != null;
                }));

        registry.register(Command.of(COPY, "Copy")
                .run(context -> withGraph(context, graph -> {
                    // Left ALONE when nothing is selected -- see GraphView.copySelection. Copying
                    // nothing must not throw away what was copied a minute ago.
                    GraphDocument copied = graph.copySelection();
                    if (copied != null) clipboard = copied;
                }))
                .enabledWhen(context -> hasNodes(graphFor(context))));

        registry.register(Command.of(CUT, "Cut")
                .run(context -> withGraph(context, graph -> {
                    GraphDocument copied = graph.copySelection();
                    if (copied == null) return;
                    clipboard = copied;
                    graph.deleteSelection();
                }))
                .enabledWhen(context -> hasNodes(graphFor(context))));

        registry.register(Command.of(PASTE, "Paste")
                .run(context -> withGraph(context, graph -> pasteInto(graph, clipboard)))
                // Disabled with an empty clipboard, so the key falls through rather than doing nothing
                // visible -- and the palette and any menu grey it, which is the same answer.
                .enabledWhen(context -> graphFor(context) != null
                        && clipboard != null && clipboard.nodeCount() > 0));

        registry.register(Command.of(DUPLICATE, "Duplicate")
                .run(context -> withGraph(context, graph -> pasteInto(graph, graph.copySelection())))
                .enabledWhen(context -> hasNodes(graphFor(context))));

        registry.register(Command.of(FRAME_ALL, "Frame All")
                .run(context -> withGraph(context, graph -> graph.fitToContent(FRAME_PADDING)))
                .enabledWhen(context -> graphFor(context) != null));
    }

    /**
     * Binds the defaults — Unity's set, since that is the reference for everything else here.
     *
     * <p>Both {@code Delete} and {@code Backspace} delete: Unity's own shortcut reference lists no
     * dedicated delete key because it inherits the platform convention, and the platform convention
     * differs between a full keyboard and a laptop one.</p>
     */
    public static void bindDefaults(Keymap keymap) {
        keymap.bind("Delete", DELETE);
        keymap.bind("Backspace", DELETE);
        keymap.bind("Mod+A", SELECT_ALL);
        keymap.bind("Escape", CLEAR_SELECTION);
        keymap.bind("F", FRAME_SELECTION);
        keymap.bind("Space", CREATE_NODE);
        keymap.bind("A", FRAME_ALL);
        // Mod+, not raw letters: these are the platform's own clipboard keys, and unlike F and A they
        // must keep working while something inside a node has focus. The keymap's typing guard only
        // exempts single-key bindings, so a modified chord reaches a text field's neighbours anyway.
        keymap.bind("Mod+C", COPY);
        keymap.bind("Mod+X", CUT);
        keymap.bind("Mod+V", PASTE);
        keymap.bind("Mod+D", DUPLICATE);
    }

    /**
     * Pastes at the pointer when it is over this graph, and beside the original when it is not.
     *
     * <p>At the cursor is what every node editor does, and it is the only placement that answers "where
     * did it go?" before you have to look. The command cannot be told where the mouse is — it is invoked
     * by a keystroke, a menu or the palette — so it asks the input handler, which holds the live pointer.
     * {@code CREATE_NODE} above already reaches for the same thing for the same reason.</p>
     *
     * <p>The fallback matters as much as the rule: a binding can fire while the pointer is over another
     * panel entirely, and pasting at a point outside the view would drop the copy somewhere off screen.
     * A fixed offset from the original at least lands it next to what it came from.</p>
     */
    private static void pasteInto(GraphView graph, @Nullable GraphDocument clip) {
        if (clip == null || clip.nodeCount() == 0) return;

        UIWindow window = graph.getAttachedWindow();
        if (window != null) {
            var pointer = window.getInputHandler().pointerPosition();
            if (graph.containsScreenPoint(pointer.x(), pointer.y())) {
                var world = graph.screenToWorld(pointer.x(), pointer.y());
                graph.pasteAt(clip, world.x(), world.y());
                return;
            }
        }
        graph.paste(clip, PASTE_OFFSET, PASTE_OFFSET);
    }

    /** Whether {@code graph} has nodes selected — a wire alone is not something to copy. */
    private static boolean hasNodes(@Nullable GraphView graph) {
        return graph != null && !graph.getSelection().nodes().isEmpty();
    }

    public static void install(CommandRegistry registry, UIElement root) {
        register(registry);
        bindDefaults(root.keymap());
    }

    /** Installs into {@code window} — its registry, bound on its root. */
    public static void install(UIWindow window) {
        install(window.getCommands(), window.ui.rootElement);
    }

    @Nullable
    private static GraphView graphFor(CommandContext context) {
        for (UIElement element = context.source(); element != null; element = element.getParent()) {
            if (element instanceof GraphView graph) return graph;
        }
        return null;
    }

    private static void withGraph(CommandContext context, Consumer<GraphView> action) {
        GraphView graph = graphFor(context);
        if (graph != null) action.accept(graph);
    }
}
