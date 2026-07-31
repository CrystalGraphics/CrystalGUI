package com.crystalgui.ui.elements.graph;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
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
        keymap.bind("A", FRAME_ALL);
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
