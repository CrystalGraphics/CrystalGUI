package com.crystalgui.app.uibuilder.canvas;

import com.crystalgui.app.uibuilder.BuilderSelection;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.widget.surface.SurfaceContext;

/**
 * What the UI builder offers a feature written against it — the engine's surface, plus the builder's own.
 *
 * <pre>{@code
 * public Disposable activate(SurfaceContext surface) {
 *     if (!(surface instanceof BuilderContext builder)) return () -> { };
 *     builder.getDocument().onChanged.connect(this::refresh);
 *     return ...;
 * }
 * }</pre>
 *
 * <p>The builder's half of the same rule the graph follows: a feature reaches the canvas through this and
 * never through {@link BuilderEditor} or {@code BuilderSurface}, so a feature package cannot reach a
 * method the builder did not mean to offer, and cannot be the reason the canvas cannot change.</p>
 *
 * <p><b>Deliberately small.</b> This is the L2 stub — the document and the artboard, which is what
 * {@link TreePolicy} already needs. The selection, the hierarchy and the insert routes arrive with the
 * features that use them (L3.4 onward), and each should be added here when its first consumer exists
 * rather than in anticipation of one.</p>
 */
public interface BuilderContext extends SurfaceContext {

    /** The tree being edited, and the one door every change to it goes through. */
    UiBuilderDocument getDocument();

    /**
     * The page the tree is laid out on.
     *
     * <p>A real element on the plane rather than a painted rectangle, so the tree inside it lays out
     * against a width somebody chose — which is the whole reason a builder needs one at all.</p>
     */
    Artboard artboard();

    /**
     * What is selected, as the hierarchy and the inspector see it.
     *
     * <p>Not {@link #selection()}, which is the ENGINE's set of items a gesture moves. The two are kept
     * in step by the plane; a feature writes to whichever it means and reads either.</p>
     */
    BuilderSelection builderSelection();

    /**
     * Design mode, or preview.
     *
     * <p>Design is the state a builder opens in: the artboard is {@code hit-test: false} and the
     * surface's mode owns presses, so nothing in the document reacts. Preview clears both and the UI is
     * simply used — buttons press, fields type, hover works. @see Artboard#setDesignMode</p>
     */
    boolean isDesignMode();

    /** @see #isDesignMode */
    void setDesignMode(boolean design);

    /** Fires after design mode is switched, however it was switched. */
    Signal.Value<Boolean> onDidChangeDesignMode();
}
