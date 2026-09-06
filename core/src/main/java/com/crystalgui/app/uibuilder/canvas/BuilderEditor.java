package com.crystalgui.app.uibuilder.canvas;

import java.util.List;

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

    private static final String ZOOM = "zoom";
    private static final String PAN_X = "panX";
    private static final String PAN_Y = "panY";

    private final UiBuilderDocument document;
    private final Artboard artboard;
    private final SurfaceEditor surface;

    public BuilderEditor(UiBuilderDocument document) {
        this.document = document;
        this.artboard = new Artboard(document);
        this.surface = new SurfaceEditor(new TreePolicy(artboard), List.of(SelectExtension.ID));
        surface.surface().place(artboard, 0f, 0f);
        // The document's own sheets, once there is a window to put them on. Installing them here would
        // reach a file from a constructor that a server also runs.
        surface.onDidConnect.connect(this::installSheets);
    }

    public UiBuilderDocument document() {
        return document;
    }

    public Artboard artboard() {
        return artboard;
    }

    /** The engine underneath, for the builder's own extensions. */
    public SurfaceEditor surface() {
        return surface;
    }

    @Override
    public UIElement view() {
        return surface;
    }

    @Override
    public <T> void writeViewState(StateMap<T> out) {
        out.putFloat(ZOOM, surface.surface().zoom());
    }

    @Override
    public <T> void readViewState(StateMap<T> in) {
        if (in.has(ZOOM)) surface.surface().setZoom(in.getFloat(ZOOM, 1f));
    }

    @Override
    public void disposeView() {
        surface.dispose();
    }

    private void installSheets() {
        UIDocument window = surface.document();
        UiTemplates.installSheets(window, document.stylesheets());
    }
}
