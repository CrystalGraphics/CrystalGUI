package com.crystalgui.app.uibuilder;

import com.crystalgui.app.uibuilder.canvas.BuilderEditor;
import com.crystalgui.app.uibuilder.canvas.TextEditGesture;
import com.crystalgui.app.uibuilder.live.PickMode;
import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.ui.dom.UIDocument;
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

        registry.register(Command.of(SELECT_PARENT, "Select Parent")
                .binding("Escape")
                .run(context -> selectRelative(context, true))
                .enabledWhen(context -> hasBuilder(context) && selectionOf(context) != null));

        registry.register(Command.of(SELECT_CHILD, "Select First Child")
                .binding("Enter")
                .run(context -> selectRelative(context, false))
                .enabledWhen(context -> hasBuilder(context) && selectionOf(context) != null));
    }

    /**
     * Up to the parent, or down to the first child.
     *
     * <p>Stops at the document root going up — selecting the artboard would be selecting the page rather
     * than anything in the document, and there is no edit that means.</p>
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
