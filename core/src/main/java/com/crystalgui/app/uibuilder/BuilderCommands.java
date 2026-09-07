package com.crystalgui.app.uibuilder;

import com.crystalgui.app.uibuilder.canvas.BuilderEditor;
import com.crystalgui.app.uibuilder.canvas.TextEditGesture;
import com.crystalgui.app.uibuilder.live.PickMode;
import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.ui.dom.UIDocument;
import java.util.List;

import com.crystalgui.ui.dom.UIElement;

import javax.annotation.Nullable;

/**
 * The UI builder's commands.
 *
 * <p>L3 registers the one that needs no document open: <b>Inspect Element</b>, which turns the next click
 * anywhere in the window into a selection. The editing commands — duplicate, delete, wrap, nudge — arrive
 * with the canvas that performs them (L4.10).</p>
 *
 * <p>An APPLICATION-wide binding, unlike the graph's bare letters: Ctrl+Shift+C is a chord, so it cannot
 * fire while somebody is typing, and live inspect is deliberately reachable with no builder open at all.</p>
 */
public final class BuilderCommands {

    private BuilderCommands() {
    }

    public static final String INSPECT_ELEMENT = "uibuilder.inspectElement";

    /** Type into the selected text node, in place. */
    public static final String EDIT_TEXT = "uibuilder.editText";

    /** Design or preview — Unity's Preview, LDLib2's simulation. */
    public static final String TOGGLE_PREVIEW = "uibuilder.togglePreview";

    /** Up one, and down one: Figma's keyboard model for walking a tree without leaving the canvas. */
    public static final String SELECT_PARENT = "uibuilder.selectParent";

    public static final String SELECT_CHILD = "uibuilder.selectChild";

    /**
     * The builder's own Select All.
     *
     * <p>The engine's takes everything on the PLANE, which in a graph is the nodes and here is the
     * artboard and nothing else -- the document's tree lives inside the page rather than beside it. So
     * Ctrl+A put eight handles on the page and described it in the inspector, and filtering the page out
     * left it selecting nothing at all. "Everything" in a builder is the top level of the document,
     * which is what Figma's own Select All takes.</p>
     */
    public static final String SELECT_ALL = "uibuilder.selectAll";

    /** @see #SELECT_NEXT_SIBLING */
    public static final String SELECT_PREVIOUS_SIBLING = "uibuilder.selectPreviousSibling";

    /**
     * Along the row, which is the third direction a tree has and the one that was missing.
     *
     * <p>Parent and first-child alone walk a spine: you can go up and down but never across, so reaching
     * the fourth of five children means going up and clicking. Every tree UI binds all three.</p>
     */
    public static final String SELECT_NEXT_SIBLING = "uibuilder.selectNextSibling";

    /** Registers them, and hands back the way to withdraw them. */
    public static Disposable register() {
        CommandRegistry.global().contribute(BuilderCommands.class, BuilderCommands::declare);
        return () -> { };
    }

    private static void declare(CommandRegistry registry) {
        registry.register(Command.of(INSPECT_ELEMENT, "Inspect Element")
                .binding("Ctrl+Shift+C")
                .run(context -> PickMode.start(windowOf(context)))
                .enabledWhen(context -> windowOf(context) != null));

        // F2 RATHER THAN A DOUBLE-CLICK. The engine's Tool SPI carries no click count -- pointerDown
        // takes a button and modifiers -- so a double-click on the canvas cannot reach a tool without
        // widening it. F2 is the rename key in both references anyway, and the hierarchy will want the
        // same command.
        registry.register(Command.of(EDIT_TEXT, "Edit Text")
                .binding("F2")
                .run(context -> builderOf(context).editSelectedText())
                .enabledWhen(context -> hasBuilder(context)
                        && TextEditGesture.isEditable(selectionOf(context))));

        registry.register(Command.of(TOGGLE_PREVIEW, "Toggle Preview")
                .binding("Ctrl+Alt+P")
                .run(context -> {
                    BuilderEditor builder = builderOf(context);
                    builder.surface().setDesignMode(!builder.surface().isDesignMode());
                    // The toolbar shows the state, and the key is the other way of changing it.
                    builder.toolbar().syncPreviewState();
                })
                .enabledWhen(BuilderCommands::hasBuilder));

        // NO `binding` ON ANY OF THE FOUR. A bare key declared on a command is application-wide: Escape
        // and Enter here took them from every dialog and text field, and an arrow would take them from
        // every list. They are bound in the builder surface's keymap, live only while focus is on the
        // canvas -- the same reason the engine's own `F` and `A` are bound there.
        registry.register(Command.of(SELECT_PARENT, "Select Parent")
                .run(context -> selectRelative(context, true))
                .enabledWhen(context -> hasBuilder(context) && selectionOf(context) != null));

        registry.register(Command.of(SELECT_CHILD, "Select First Child")
                .run(context -> selectRelative(context, false))
                .enabledWhen(context -> hasBuilder(context) && selectionOf(context) != null));

        // NO `binding` ON THESE TWO. A bare arrow declared on a command is application-wide, which would
        // cost every list and every text field its own arrows. They are bound in the builder surface's
        // keymap instead, where they are live only while focus is on the canvas -- the same reason the
        // engine's `F` and `A` are bound there rather than here.
        registry.register(Command.of(SELECT_ALL, "Select All")
                .run(context -> {
                    BuilderEditor builder = builderOf(context);
                    if (builder != null) {
                        builder.selection().replaceWith(builder.document().root().children());
                    }
                })
                .enabledWhen(BuilderCommands::hasBuilder));

        registry.register(Command.of(SELECT_PREVIOUS_SIBLING, "Select Previous Sibling")
                .run(context -> selectSibling(context, -1))
                .enabledWhen(context -> hasBuilder(context) && selectionOf(context) != null));

        registry.register(Command.of(SELECT_NEXT_SIBLING, "Select Next Sibling")
                .run(context -> selectSibling(context, 1))
                .enabledWhen(context -> hasBuilder(context) && selectionOf(context) != null));
    }

    /** @see #SELECT_NEXT_SIBLING */
    private static void selectSibling(CommandContext context, int step) {
        BuilderEditor builder = builderOf(context);
        UIElement node = selectionOf(context);
        if (builder == null || node == null) return;
        UIElement parent = node.parentElement();
        if (parent == null) return;
        List<UIElement> siblings = parent.children();
        int at = siblings.indexOf(node);
        if (at < 0) return;
        // CLAMPED, NOT WRAPPED. Wrapping in a tree means the last child's "next" is its own first
        // sibling, which reads as the selection jumping backwards for no reason.
        int next = at + step;
        if (next < 0 || next >= siblings.size()) return;
        builder.selection().selectOnly(siblings.get(next));
    }

    /**
     * Up to the parent, or down to the first child.
     *
     * <p>Stops at the document root, clamped exactly as the siblings are: selecting the artboard would
     * be selecting the page rather than anything in the document, and there is no edit that means. It
     * does NOT deselect there — Escape is the key that clears, and an arrow that empties the selection
     * when it runs out of tree is a different verb wearing a navigation key.</p>
     */
    private static void selectRelative(CommandContext context, boolean up) {
        BuilderEditor builder = builderOf(context);
        UIElement node = selectionOf(context);
        if (node == null) return;
        UIElement next = up ? node.parentElement()
                : (node.children().isEmpty() ? null : node.children().get(0));
        if (next == null || next == builder.artboard()) return;
        builder.selection().selectOnly(next);
    }

    @Nullable
    private static UIElement selectionOf(CommandContext context) {
        BuilderSelection selection = context.data().get(BuilderEditor.BUILDER_SELECTION);
        return selection == null ? null : selection.node();
    }

    private static boolean hasBuilder(CommandContext context) {
        return context.data().get(BuilderEditor.UI_BUILDER) != null;
    }

    /** Only ever called behind {@link #hasBuilder}. */
    private static BuilderEditor builderOf(CommandContext context) {
        return context.data().get(BuilderEditor.UI_BUILDER);
    }

    /** The window the command was invoked in, walked out of whatever had focus. */
    private static UIDocument windowOf(CommandContext context) {
        return context.data().source() instanceof UIElement element ? element.document() : null;
    }
}
