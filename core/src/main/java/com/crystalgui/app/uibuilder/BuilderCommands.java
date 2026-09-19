package com.crystalgui.app.uibuilder;

import com.crystalgui.app.uibuilder.canvas.BuilderContext;
import com.crystalgui.app.uibuilder.canvas.UIBuilderView;
import com.crystalgui.app.uibuilder.attributes.StyleAttributes;
import com.crystalgui.app.uibuilder.canvas.ReorderInFlow;
import com.crystalgui.app.uibuilder.canvas.TextEditGesture;
import com.crystalgui.app.uibuilder.canvas.transform.FreeTransformTool;
import com.crystalgui.app.uibuilder.canvas.transform.TransformGesture;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.app.uibuilder.canvas.transform.TransformBox;
import com.crystalgui.app.uibuilder.document.BuilderEdit;
import com.crystalgui.app.uibuilder.document.TreeMoves;
import com.crystalgui.app.uibuilder.live.PickMode;
import com.crystalgui.core.attribute.AttributeClipboard;
import com.crystalgui.core.attribute.AttributeSet;
import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.dnd.SortPlacement;
import com.crystalgui.widget.overlay.PasteAttributesDialog;
import java.util.ArrayList;
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

    /** @see #SELECT_ALL */
    private static final String SELECT_ALL_LABEL = "Select All";

    /**
     * Photoshop's Ctrl+T — the transform box over {@code transform}, not over the layout.
     *
     * <p>Beside the resize handles rather than instead of them, and the difference is what each one
     * writes: a resize changes what the box IS and reflows its siblings, a transform changes how it SITS
     * and Taffy never sees it. Bound in the surface keymap, since it means nothing anywhere else.</p>
     */
    public static final String FREE_TRANSFORM = "uibuilder.freeTransform";

    /**
     * Illustrator's Ctrl+D, Photoshop's Ctrl+Shift+T: give this element the last one's treatment.
     *
     * <p>The transform itself rather than a delta between two states — composing a delta out of an op
     * LIST is not subtraction, and the case anyone repeats is a fresh element wanting what the last one
     * got.</p>
     */
    public static final String TRANSFORM_AGAIN = "uibuilder.transformAgain";

    /**
     * Rewrites a scale as {@code width}/{@code height}.
     *
     * <p>A scale on a static element makes its box lie about its size to everything that reads one — the
     * layout, a sibling's alignment, the inspector — and scales its text with it. This is the one-click
     * way out, and it is deliberately a COMMAND rather than a lint row: the lint harness is L4.11/L4.12,
     * and a one-off Problems path here would be deleted by it.</p>
     */
    public static final String CONVERT_TO_SIZE = "uibuilder.convertToSize";

    /**
     * Picks up an element's inline style, for {@link #PASTE_ATTRIBUTES} to put on another.
     *
     * <p>The engine's {@code AttributeCarrier} does the carrying, so this is one line and every other
     * surface gets the same feature by answering the same seam.</p>
     */
    public static final String COPY_ATTRIBUTES = "uibuilder.copyAttributes";

    /**
     * Puts the copied properties on this element, asking which ones first.
     *
     * <p>Premiere's and Resolve's window. It asks once per copy: tick <em>Don't show until next copy</em>
     * and the same choice is applied silently until something else is copied.</p>
     */
    public static final String PASTE_ATTRIBUTES = "uibuilder.pasteAttributes";

    /** @see #SELECT_NEXT_SIBLING */
    public static final String SELECT_PREVIOUS_SIBLING = "uibuilder.selectPreviousSibling";

    /**
     * Along the row, which is the third direction a tree has and the one that was missing.
     *
     * <p>Parent and first-child alone walk a spine: you can go up and down but never across, so reaching
     * the fourth of five children means going up and clicking. Every tree UI binds all three.</p>
     */
    public static final String SELECT_NEXT_SIBLING = "uibuilder.selectNextSibling";

    /**
     * Before the previous in-flow sibling — VS Code's Move Line Up, for a node. {@link #MOVE_DOWN} goes after
     * the next. A selection moves together and stops at the end of its container.
     */
    public static final String MOVE_UP = "uibuilder.moveUp";

    /** @see #MOVE_UP */
    public static final String MOVE_DOWN = "uibuilder.moveDown";

    /** A copy of the selection before it — VS Code's Copy Line Up. The copy is selected. */
    public static final String DUPLICATE_UP = "uibuilder.duplicateUp";

    /** A copy of the selection after it. @see #DUPLICATE_UP */
    public static final String DUPLICATE_DOWN = "uibuilder.duplicateDown";

    /** The Insert menu under the selection — Blender's Shift+A, for a tree. Bound on the surface as Shift+Space. */
    public static final String INSERT = "uibuilder.insert";

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
                    UIBuilderView builder = builderOf(context);
                    // The toolbar's toggle follows the surface, so the key needs to tell it nothing.
                    builder.surface().setDesignMode(!builder.surface().isDesignMode());
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
        registry.register(Command.of(SELECT_ALL, SELECT_ALL_LABEL)
                .run(BuilderCommands::selectAll)
                .enabledWhen(BuilderCommands::hasBuilder));

        registry.register(Command.of(SELECT_PREVIOUS_SIBLING, "Select Previous Sibling")
                .run(context -> selectSibling(context, -1))
                .enabledWhen(context -> hasBuilder(context) && selectionOf(context) != null));

        registry.register(Command.of(SELECT_NEXT_SIBLING, "Select Next Sibling")
                .run(context -> selectSibling(context, 1))
                .enabledWhen(context -> hasBuilder(context) && selectionOf(context) != null));

        // BOUND ON THE SURFACE, like the arrows: Alt+Up is a text editor's Move Line everywhere else.
        registry.register(Command.of(MOVE_UP, "Move Up")
                .run(context -> shift(context, false, false))
                .enabledWhen(BuilderCommands::canShift));
        registry.register(Command.of(MOVE_DOWN, "Move Down")
                .run(context -> shift(context, true, false))
                .enabledWhen(BuilderCommands::canShift));
        registry.register(Command.of(DUPLICATE_UP, "Duplicate Up")
                .run(context -> shift(context, false, true))
                .enabledWhen(BuilderCommands::canShift));
        registry.register(Command.of(DUPLICATE_DOWN, "Duplicate Down")
                .run(context -> shift(context, true, true))
                .enabledWhen(BuilderCommands::canShift));

        registry.register(Command.of(INSERT, "Insert…")
                .run(context -> builderOf(context).insert().openForSelection())
                .enabledWhen(context -> hasBuilder(context) && builderOf(context).surface().isDesignMode()));

        // ONE NODE, AND A LAID-OUT ONE. The tool cannot refuse a bad selection from inside `activated`
        // without re-entering the mode stack mid-change, so the gate is here where it costs nothing.
        registry.register(Command.of(FREE_TRANSFORM, "Free Transform")
                .run(context -> builderOf(context).surface().modes().use(FreeTransformTool.ID))
                .enabledWhen(BuilderCommands::canFreeTransform));

        registry.register(Command.of(TRANSFORM_AGAIN, "Transform Again")
                .run(context -> builderOf(context).transformBox().transformAgain(selectionOf(context)))
                .enabledWhen(context -> hasBuilder(context)
                        && builderOf(context).transformBox().hasSomethingToRepeat()
                        && selectionOf(context) != null));

        registry.register(Command.of(COPY_ATTRIBUTES, "Copy Attributes")
                .run(context -> AttributeClipboard.put(
                        new StyleAttributes(selectionOf(context)).copyAttributes()))
                .enabledWhen(context -> hasBuilder(context) && selectionOf(context) != null));

        registry.register(Command.of(PASTE_ATTRIBUTES, "Paste Attributes")
                .run(BuilderCommands::pasteAttributes)
                .enabledWhen(context -> hasBuilder(context) && selectionOf(context) != null
                        && AttributeClipboard.pending(StyleAttributes.DOMAIN) != null));

        registry.register(Command.of(CONVERT_TO_SIZE, "Convert to Size")
                .run(context -> builderOf(context).transformBox().convertToSize(selectionOf(context)))
                .enabledWhen(context -> hasBuilder(context)
                        && TransformBox.isScaleStandingInForSize(drawnSelectionOf(context))));
    }

    /**
     * Asks which properties, unless the last paste of this copy said not to.
     *
     * <p>The window is opened rather than shown-and-waited-on: a modal dialog here would mean blocking
     * the frame thread that draws it.</p>
     */
    private static void pasteAttributes(CommandContext context) {
        UIBuilderView builder = builderOf(context);
        UIElement node = selectionOf(context);
        if (builder == null || node == null) return;
        AttributeSet copied = AttributeClipboard.pending(StyleAttributes.DOMAIN);
        if (copied == null) return;

        StyleAttributes target = new StyleAttributes(node);
        if (AttributeClipboard.remembersAChoice()) {
            target.applyAsEdit(builder.document(), AttributeClipboard.remembered(copied));
            return;
        }
        PasteAttributesDialog.open(builder.surface(), copied, StyleAttributes.describe(node),
                chosen -> target.applyAsEdit(builder.document(), chosen));
    }

    /**
     * Moves or copies the selection one place along its container, as one undo step.
     *
     * <p>In CHILD order, which is the order the hierarchy lists; a reversed flow draws it backwards and the
     * key still means the same place in the list. One container at a time: a selection spanning two has no
     * single "previous".</p>
     */
    private static void shift(CommandContext context, boolean later, boolean copy) {
        UIBuilderView builder = builderOf(context);
        List<UIElement> nodes = shiftable(builder);
        if (nodes.isEmpty()) return;
        UIElement parent = nodes.get(0).parentElement();
        List<UIElement> children = parent.children();
        int first = children.indexOf(nodes.get(0));
        int last = children.indexOf(nodes.get(nodes.size() - 1));
        List<BuilderEdit> edits;
        if (copy) {
            edits = TreeMoves.duplicate(builder.document(), parent, later ? last + 1 : first, nodes);
        } else {
            int neighbour = neighbourInFlow(builder.surface(), children, later ? last : first, later ? 1 : -1, nodes);
            if (neighbour < 0) return;
            edits = TreeMoves.move(parent, later ? neighbour + 1 : neighbour, nodes);
        }
        if (edits.isEmpty()) return;
        builder.document().applyAll(copy ? "duplicate" : "move", edits);
        if (copy) {
            List<UIElement> copies = new ArrayList<>(edits.size());
            for (BuilderEdit edit : edits) copies.add(edit.node());
            builder.selection().replaceWith(copies);
        }
    }

    /** The selection's outermost reorderable nodes, when they share one container; else empty. */
    private static List<UIElement> shiftable(@Nullable UIBuilderView builder) {
        if (builder == null || !builder.surface().isDesignMode()) return List.of();
        List<UIElement> nodes = new ArrayList<>();
        for (UIElement node : TreeMoves.outermost(builder.selection().nodes())) {
            if (!ReorderInFlow.isReorderable(builder.surface(), node)) return List.of();
            if (!nodes.isEmpty() && node.parentElement() != nodes.get(0).parentElement()) return List.of();
            nodes.add(node);
        }
        return nodes;
    }

    private static boolean canShift(CommandContext context) {
        return hasBuilder(context) && !shiftable(builderOf(context)).isEmpty();
    }

    /** The nearest in-flow child past {@code from} in {@code step}'s direction that is not one of {@code moving}, or -1. */
    private static int neighbourInFlow(BuilderContext pane, List<UIElement> children, int from, int step,
                                       List<UIElement> moving) {
        for (int i = from + step; i >= 0 && i < children.size(); i += step) {
            UIElement child = children.get(i);
            // IN FLOW IS A COMPUTED FACT, and the document's own tree has no cascade: asked of where it is drawn.
            UIElement drawn = pane.shown(child);
            if (!moving.contains(child) && drawn != null && SortPlacement.inFlow(drawn)) return i;
        }
        return -1;
    }

    /** @see #FREE_TRANSFORM */
    private static boolean canFreeTransform(CommandContext context) {
        if (!hasBuilder(context)) return false;
        UIBuilderView builder = builderOf(context);
        if (builder == null || !builder.surface().isDesignMode()) return false;
        // MEASURED AND CASCADED ON THE DRAWING: the document's own tree has neither a box nor a computed style.
        UIElement node = drawnSelectionOf(context);
        if (node == null || node.box() == null) return false;
        // AND A TRANSFORM THE BOX CAN ACTUALLY SHOW. The gesture describes translate/rotate/skew/scale
        // composed in that order; anything else opens at identity showing none of it, and committing
        // then writes the gesture over the property. Refusing the command is the only safe place to
        // catch it -- the tool's mode is already entered by the time the box opens.
        return TransformGesture.canDecompose(
                node.getStyle().computed().get(StylePropertyRegistry.TRANSFORM));
    }

    /**
     * Everything alongside what is selected, or the top level when nothing is.
     *
     * <h3>Siblings, not descendants</h3>
     *
     * <p>A selection holding both an ancestor and a descendant makes the next action apply twice: a move
     * shifts the parent, carrying the child, and then shifts the child again; a delete removes a node and
     * then its already-removed subtree. Editors keep a selection to its topmost members for exactly that
     * reason, so "everything in the document" is a hazard one keystroke away rather than a feature.</p>
     *
     * <h3>Scoped to where you are</h3>
     *
     * <p>Inside a container, "all" means the things beside you — which is what Figma's own Select All
     * takes, and what makes it useful more than once: pressing it at the top level and pressing it inside
     * a row should not give the same answer. With nothing selected there is no container to be in, and it
     * falls back to the document's top level.</p>
     */
    private static void selectAll(CommandContext context) {
        UIBuilderView builder = builderOf(context);
        if (builder == null) return;
        UIElement node = selectionOf(context);
        UIElement parent = node == null ? null : node.parentElement();
        // The artboard is the page rather than a container in the document, so its "children" are the
        // root -- selecting that is selecting everything by another name. The root's own children are
        // the top level either way.
        if (parent == null || parent == builder.artboard()) parent = builder.document().root();
        builder.selection().replaceWith(parent.children());
    }

    /** @see #SELECT_NEXT_SIBLING */
    private static void selectSibling(CommandContext context, int step) {
        UIBuilderView builder = builderOf(context);
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
        UIBuilderView builder = builderOf(context);
        UIElement node = selectionOf(context);
        if (node == null) return;
        UIElement next = up ? node.parentElement()
                : (node.children().isEmpty() ? null : node.children().get(0));
        if (next == null || next == builder.artboard()) return;
        builder.selection().selectOnly(next);
    }

    @Nullable
    private static UIElement selectionOf(CommandContext context) {
        BuilderSelection selection = context.data().get(UIBuilderView.BUILDER_SELECTION);
        return selection == null ? null : selection.node();
    }

    /** Where the selected node is drawn in the builder asked — what a box or a computed style is read from. */
    @Nullable
    private static UIElement drawnSelectionOf(CommandContext context) {
        UIBuilderView builder = builderOf(context);
        return builder == null ? null : builder.surface().shown(selectionOf(context));
    }

    private static boolean hasBuilder(CommandContext context) {
        return context.data().get(UIBuilderView.UI_BUILDER) != null;
    }

    /** Only ever called behind {@link #hasBuilder}. */
    private static UIBuilderView builderOf(CommandContext context) {
        return context.data().get(UIBuilderView.UI_BUILDER);
    }

    /** The window the command was invoked in, walked out of whatever had focus. */
    private static UIDocument windowOf(CommandContext context) {
        return context.data().source() instanceof UIElement element ? element.document() : null;
    }
}
