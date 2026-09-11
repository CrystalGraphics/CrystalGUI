package com.crystalgui.app.uibuilder.canvas;

import java.util.List;
import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.BuilderOverlaysExtension;
import com.crystalgui.app.uibuilder.BuilderSelection;
import com.crystalgui.app.uibuilder.document.BuilderEdit;
import com.crystalgui.app.uibuilder.canvas.transform.FreeTransformTool;
import com.crystalgui.app.uibuilder.canvas.transform.TransformBox;
import com.crystalgui.app.uibuilder.canvas.transform.TransformOptionsBar;
import com.crystalgui.app.uibuilder.BuilderCommands;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.widget.overlay.ContextMenu;
import com.crystalgui.widget.surface.mode.ToolKind;
import com.crystalgui.core.undo.Edit;
import com.crystalgui.core.data.DataKey;
import com.crystalgui.document.DocumentEditor;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.template.UiTemplates;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.surface.mode.SelectExtension;

/**
 * The view onto a {@code .cgui}: one artboard on a pan-and-zoom surface, holding the document's real
 * tree.
 *
 * <p>What a tab shows. Built by the document kind, one per open document; the surface underneath is the
 * shared editing engine, so selection, marquee and the tool stack are the same ones the shader graph
 * runs on.</p>
 *
 * <pre>{@code
 * DocumentKind.of("cgui.file", "UI Document")
 *         .model((resource, bytes) -> new UiBuilderDocument(bytes, resource.toString()))
 *         .editor(document -> new BuilderEditor((UiBuilderDocument) document.model()));
 * }</pre>
 *
 * <p>The tree on the artboard is the document's own, laid out by the ordinary engine at the artboard's
 * size — so what is on screen is what a player gets, not a picture of it.</p>
 */
public final class BuilderEditor implements DocumentEditor {

    /** This builder, for a command that acts on one. */
    public static final DataKey<BuilderEditor> UI_BUILDER =
            DataKey.create("uiBuilder", BuilderEditor.class);

    /** The tree being edited. */
    public static final DataKey<UiBuilderDocument> UI_DOCUMENT =
            DataKey.create("uiBuilder.document", UiBuilderDocument.class);

    /** What is pointed at — nodes, and optionally a rule or a token. @see BuilderSelection */
    public static final DataKey<BuilderSelection> BUILDER_SELECTION =
            DataKey.create("uiBuilder.selection", BuilderSelection.class);

    private static final String ZOOM = "zoom";
    private static final String PAN_X = "panX";
    private static final String PAN_Y = "panY";

    private static final String PRESET = "preset";
    private static final String SCALE = "uiScale";

    private final UiBuilderDocument document;
    private final Artboard artboard;
    private final BuilderSurface surface;
    private final BuilderToolbar toolbar;
    private final ResizeHandles handles;

    private final TransformBox transformBox;

    private final TransformOptionsBar options;
    private final MoveOutOfFlow moveGesture;
    private final TextEditGesture textEditing;
    private final BuilderPane pane;

    public BuilderEditor(UiBuilderDocument document) {
        this.document = document;
        this.artboard = new Artboard(document);
        this.surface = new BuilderSurface(document, artboard,
                List.of(SelectExtension.ID, BuilderOverlaysExtension.ID));
        surface.ownedBy(this);
        surface.surface().place(artboard, 0f, 0f);
        this.toolbar = new BuilderToolbar(new BuilderToolbar.BuilderSurfaceHost() {
            @Override
            public Artboard artboard() {
                return artboard;
            }

            @Override
            public boolean isDesignMode() {
                return surface.isDesignMode();
            }

            @Override
            public void setDesignMode(boolean design) {
                surface.setDesignMode(design);
            }
        });
        // UNDER THE HANDLES, so a guide through a corner never covers the dot on it.
        surface.surface().addOverlay(surface.smartGuides());
        this.handles = new ResizeHandles(surface, document);
        // DIRECTLY, not through OverlayLayer: that path sets `hit-test: false` on whatever it mounts,
        // which is right for something that only draws and fatal for eight handles that have to take a
        // press. They are not a toggle in any editor either, so nothing is lost by not being a kind.
        surface.surface().addOverlay(handles);
        this.moveGesture = new MoveOutOfFlow(surface, document);
        surface.surface().addOverlay(moveGesture);
        surface.movesWith(moveGesture);
        this.textEditing = new TextEditGesture(document);
        surface.surface().addOverlay(textEditing);
        // FREE TRANSFORM (L4.5a). Mounted directly like the handles rather than as an overlay kind: it
        // is a live gesture, not a view a designer turns on, and it draws nothing at all while down.
        this.transformBox = new TransformBox(surface, document);
        surface.surface().addOverlay(transformBox);
        // NO ICON: nothing reads one yet (the tool strip is L9.7), and naming a file that is not there
        // is a claim the build cannot check.
        surface.registerTool(ToolKind.of(FreeTransformTool.ID, "Free Transform")
                .tool(context -> new FreeTransformTool(context, transformBox)));
        // DESIGN-TIME CHROME, so it goes with the mode. The overlays registered as kinds are hidden by
        // BuilderOverlaysExtension; the handles are mounted directly and would otherwise stay on screen
        // over a UI that is being used.
        surface.onDidChangeDesignMode.connect(design -> {
            handles.setDisplayed(Boolean.TRUE.equals(design) && handles.target() != null);
            if (!Boolean.TRUE.equals(design)) {
                textEditing.cancel();
                // CANCEL, not commit: leaving design mode is not an intent to keep a half-made
                // transform, and the preview would otherwise sit over a UI being used.
                if (transformBox.isActive()) surface.modes().use(TreeSelectTool.ID);
                transformBox.cancel();
            }
        });
        // RIGHT-CLICK ON THE CANVAS. Attached to the plane rather than to each element: a node on the
        // artboard is the document's, not a widget of ours to hang listeners on, and in design mode it
        // does not take hits at all -- so the element the event carries is the artboard however precisely
        // the pointer is aimed. What was actually pointed at is the PICKER'S answer.
        // ON THE CAPTURE PHASE. What is on the artboard is a document being edited rather than widgets
        // being used, but a Button drawn there still consumes a press -- so listening after it meant the
        // menu opened over blank page and over nothing else.
        ContextMenu.attach(surface, CommandRegistry.global(), element -> menuFor(pointedAt()), true);
        this.options = new TransformOptionsBar(transformBox);
        transformBox.showNumbersIn(options);
        this.pane = new BuilderPane(toolbar, options, surface);
        // The document's own sheets, once there is a window to put them on. Installing them here would
        // reach a file from a constructor that a server also runs.
        surface.onDidConnect.connect(this::installSheets);
        // A RELOAD REPLACES THE TREE, and the frame is holding the old one. `adopt` mints a new root,
        // so everything reading document.root() -- the hierarchy above all -- moves to a tree the canvas
        // is not showing. resync() is a no-op unless the root instance actually changed, which is why it
        // can hang off the ordinary change signal.
        document.onChanged().connect(this::adoptNewTree);
        // AND SHOW WHAT AN UNDO JUST DID. A reversal you cannot see is indistinguishable from a key that
        // did nothing -- especially on a canvas, where the changed node may be scrolled off or simply
        // one of forty that look alike. Selecting it puts the outline, the handles and the inspector on
        // the thing that moved, which is the whole answer to "what did that undo?".
        document.history().onDidStep.connect(this::selectWhatStepped);
    }

    /** @see #BuilderEditor the note on the history's step signal */
    private void selectWhatStepped(Edit edit) {
        UIElement node = edit instanceof BuilderEdit builderEdit ? builderEdit.node() : null;
        // A REMOVED NODE IS NOT SELECTABLE, and an undone Insert is exactly that. `contains` is the
        // document's own light-tree question, so this asks whether the node is still in the tree at all
        // rather than trusting the edit to have left it there.
        if (node == null || !document.root().contains(node) && node != document.root()) {
            surface.builderSelection().selectOnly(null);
            return;
        }
        surface.builderSelection().selectOnly(node);
    }

    public UiBuilderDocument document() {
        return document;
    }

    public Artboard artboard() {
        return artboard;
    }

    /** The engine underneath, for the builder's own extensions. */
    public BuilderSurface surface() {
        return surface;
    }

    /** @see BuilderSelection */
    public BuilderSelection selection() {
        return surface.builderSelection();
    }

    /** Dragging an out-of-flow node, with snapping and its guides. */
    public MoveOutOfFlow moveGesture() {
        return moveGesture;
    }

    /** In-place text editing — the field that opens over a {@code text} node. */
    public TextEditGesture textEditing() {
        return textEditing;
    }

    /**
     * Opens in-place editing on the selected node.
     *
     * @return whether there was a text node selected to edit
     */
    public boolean editSelectedText() {
        return textEditing.begin(selection().node());
    }

    /** What the pointer is over, which is what a right-click is about. */
    @Nullable
    private UIElement pointedAt() {
        return surface.picking().hovered();
    }

    /**
     * The menu for whatever was right-clicked, or <b>null for nothing</b>.
     *
     * <p>The commands resolve from the SELECTION, so a menu offered over empty plane describes an element
     * somewhere else entirely — you would be acting on something you cannot see from where you clicked.
     * Right-click therefore acts on what is under the pointer, and selects it first when it is not
     * already selected: the rule every file manager, Figma and Photoshop use.</p>
     *
     * <p><b>Already selected means left alone</b>, which is the half that is easy to miss — right-clicking
     * inside a multi-selection to act on all of it must not collapse it to the one row under the
     * pointer.</p>
     */
    @Nullable
    public ContextMenu menuFor(@Nullable UIElement item) {
        if (item == null) return null;
        if (!selection().nodes().contains(item)) selection().selectOnly(item);
        return ContextMenu.builder()
                .item(BuilderCommands.COPY_ATTRIBUTES)
                .item(BuilderCommands.PASTE_ATTRIBUTES)
                .separator()
                .item(BuilderCommands.FREE_TRANSFORM)
                .item(BuilderCommands.CONVERT_TO_SIZE);
    }

    /** The numbers behind a Free Transform, for a test. */
    public TransformOptionsBar options() {
        return options;
    }

    /** The Free Transform box, for a test and for the options bar. */
    public TransformBox transformBox() {
        return transformBox;
    }

    public ResizeHandles handles() {
        return handles;
    }

    /** The toolbar above the canvas. */
    public BuilderToolbar toolbar() {
        return toolbar;
    }

    @Override
    public UIElement view() {
        return pane;
    }

    /**
     * Where you were looking, so reopening a document does not put you back at the origin.
     *
     * <p>The camera and the two viewer settings, and deliberately <b>not</b> the selection: a selection
     * is about the tree and a tree is what the file already carries, so restoring one means holding an
     * id path that may no longer resolve. Zero and 1x are the defaults, so a document that was never
     * moved writes almost nothing.</p>
     */
    @Override
    public <T> void writeViewState(StateMap<T> out) {
        out.putFloat(ZOOM, surface.surface().zoom());
        if (surface.surface().panX() != 0f) out.putFloat(PAN_X, surface.surface().panX());
        if (surface.surface().panY() != 0f) out.putFloat(PAN_Y, surface.surface().panY());
        if (artboard.uiScale() != 1f) out.putFloat(SCALE, artboard.uiScale());
        out.putString(PRESET, Math.round(artboard.boardWidth()) + "x"
                + Math.round(artboard.boardHeight()));
    }

    @Override
    public <T> void readViewState(StateMap<T> in) {
        if (in.has(ZOOM)) surface.surface().setZoom(in.getFloat(ZOOM, 1f));
        surface.surface().setPan(in.getFloat(PAN_X, 0f), in.getFloat(PAN_Y, 0f));
        if (in.has(SCALE)) artboard.setUiScale(in.getFloat(SCALE, 1f));
        readPreset(in.getString(PRESET, ""));
    }

    /** {@code 800x480} back into a page size; anything else is ignored rather than refused. */
    private void readPreset(String preset) {
        int cross = preset.indexOf('x');
        if (cross <= 0) return;
        try {
            artboard.setSize(Float.parseFloat(preset.substring(0, cross)),
                    Float.parseFloat(preset.substring(cross + 1)));
        } catch (NumberFormatException malformed) {
            // A session record somebody edited, or one from a build that wrote it differently. The
            // camera is a convenience; refusing to open the document over one is not a trade worth making.
        }
    }

    @Override
    public void disposeView() {
        surface.dispose();
    }

    /**
     * Re-points the canvas at the document's current root, and drops a selection that no longer exists.
     *
     * <p>The stale selection is not a detail: it holds elements from the replaced tree, which are in no
     * document and have no boxes, so everything drawn from it points at nothing.</p>
     */
    private void adoptNewTree() {
        if (!artboard.resync()) return;
        selection().clear();
        surface.selection().clear();
    }

    private void installSheets() {
        UIDocument window = surface.document();
        UiTemplates.installSheets(window, document.stylesheets());
    }
}
