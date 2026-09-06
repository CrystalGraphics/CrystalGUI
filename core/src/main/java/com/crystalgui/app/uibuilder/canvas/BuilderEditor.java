package com.crystalgui.app.uibuilder.canvas;

import java.util.List;

import com.crystalgui.app.uibuilder.BuilderOverlaysExtension;
import com.crystalgui.app.uibuilder.BuilderSelection;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.core.data.DataKey;
import com.crystalgui.document.DocumentEditor;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.template.UiTemplates;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.surface.SurfaceEditor;
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
        this.handles = new ResizeHandles(surface, document);
        // DIRECTLY, not through OverlayLayer: that path sets `hit-test: false` on whatever it mounts,
        // which is right for something that only draws and fatal for eight handles that have to take a
        // press. They are not a toggle in any editor either, so nothing is lost by not being a kind.
        surface.surface().addOverlay(handles);
        this.textEditing = new TextEditGesture(document);
        surface.surface().addOverlay(textEditing);
        // DESIGN-TIME CHROME, so it goes with the mode. The overlays registered as kinds are hidden by
        // BuilderOverlaysExtension; the handles are mounted directly and would otherwise stay on screen
        // over a UI that is being used.
        surface.onDidChangeDesignMode.connect(design -> {
            handles.setDisplayed(Boolean.TRUE.equals(design) && handles.target() != null);
            if (!Boolean.TRUE.equals(design)) textEditing.cancel();
        });
        this.pane = new BuilderPane(toolbar, surface);
        // The document's own sheets, once there is a window to put them on. Installing them here would
        // reach a file from a constructor that a server also runs.
        surface.onDidConnect.connect(this::installSheets);
        // A RELOAD REPLACES THE TREE, and the frame is holding the old one. `adopt` mints a new root,
        // so everything reading document.root() -- the hierarchy above all -- moves to a tree the canvas
        // is not showing. resync() is a no-op unless the root instance actually changed, which is why it
        // can hang off the ordinary change signal.
        document.onChanged().connect(this::adoptNewTree);
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

    /** The eight resize handles on the selection. */
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
