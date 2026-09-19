package com.crystalgui.app.uibuilder.document;

import javax.annotation.Nullable;

import com.crystalgui.ui.dom.UIElement;

/**
 * A pane's drawing of a {@link UiBuilderDocument}: which document node one of its drawn nodes stands for. A watcher
 * that is one lets the document take an edit naming a node as that pane draws it. @see UiBuilderDocument#resolve
 */
public interface DocumentDrawing {

    /** The document node {@code drawn} stands for, or null when it is not one of this drawing's. */
    @Nullable
    UIElement source(@Nullable UIElement drawn);
}
