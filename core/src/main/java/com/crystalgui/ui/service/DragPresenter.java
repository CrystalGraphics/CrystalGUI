package com.crystalgui.ui.service;

import javax.annotation.Nullable;

import com.crystalgui.ui.dom.UIElement;

/**
 * Draws a drag's {@link DragPreview} in the document that runs the drag. The shell draws it instead while the document
 * is a window's client ({@link Input#handOverGhost}).
 *
 * <p>One per document, made by {@link Input#presenterFactory} on the first drag with a preview. The widget layer
 * installs the factory; the service layer names no widget.</p>
 */
public interface DragPresenter {

    /** Shows {@code preview} with its corner under the surface pixel ({@code x}, {@code y}). Called every move; cheap when nothing changed. */
    void show(DragPreview preview, float surfaceX, float surfaceY);

    /** The element that draws it, for the drag to declare as following the pointer; null when there is none. */
    @Nullable
    UIElement element();

    /** Takes it away, and leaves nothing behind. */
    void hide();
}
