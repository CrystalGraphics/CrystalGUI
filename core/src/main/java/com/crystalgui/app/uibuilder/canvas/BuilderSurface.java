package com.crystalgui.app.uibuilder.canvas;

import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.widget.surface.SurfaceEditor;

/**
 * The UI builder's plane: a {@link SurfaceEditor} that answers {@link BuilderContext}.
 *
 * <p>The same shape the graph takes — {@code GraphView} is a surface that answers {@code GraphContext} —
 * and for the same reason: a feature is written against the CONTEXT, so it needs something that is both
 * a surface and a builder to activate against.</p>
 *
 * <p>Built by {@link BuilderEditor}; nothing else constructs one.</p>
 */
public final class BuilderSurface extends SurfaceEditor implements BuilderContext {

    public static final Name NAME = Name.of("buildersurface");

    private final UiBuilderDocument document;

    private final Artboard artboard;

    BuilderSurface(UiBuilderDocument document, Artboard artboard, @Nullable List<String> enabled) {
        super(NAME, new TreePolicy(artboard), enabled);
        this.document = document;
        this.artboard = artboard;
        // LAST: an extension activated any earlier gets a surface whose document and artboard are null.
        ensureExtensions();
    }

    @Override
    public UiBuilderDocument getDocument() {
        return document;
    }

    @Override
    public Artboard artboard() {
        return artboard;
    }
}
