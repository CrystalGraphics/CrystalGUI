package com.crystalgui.app.uibuilder.canvas;

import com.crystalgui.app.uibuilder.BuilderSelection;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.dom.UIElement;

import javax.annotation.Nullable;
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
 * never through {@link UIBuilderView} or {@code BuilderSurface}, so a feature package cannot reach a
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
     * Where a gesture shows what it snapped to — one layer for every gesture that snaps.
     *
     * <pre>{@code
     * ctx.smartGuides().show(node.parentElement(), snap.indicators());   // each update
     * ctx.smartGuides().clear();                                          // when the gesture ends
     * }</pre>
     */
    SmartGuides smartGuides();

    /**
     * Where a gesture shows where a drop would land — and the space it resolves the drop in.
     *
     * <pre>{@code
     * ctx.dropIndicator().show(DropResolver.forPane(ctx).resolve(sources, rawX, rawY));
     * ctx.dropIndicator().clear();   // when the gesture ends
     * }</pre>
     */
    DropIndicator dropIndicator();

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

    /**
     * Where a document node is drawn on THIS canvas, or null. The document's own tree is never on screen, so its
     * nodes have no box and no computed style: anything measuring or reading the cascade asks about this instead.
     *
     * <pre>{@code
     * UIElement drawn = ctx.shown(ctx.builderSelection().node());
     * Box box = drawn == null ? null : drawn.box();
     * }</pre>
     */
    @Nullable
    UIElement shown(@Nullable UIElement node);

    /** The document node a node of this canvas stands for, or null — what a hit on the canvas is about. */
    @Nullable
    UIElement sourceOf(@Nullable UIElement drawn);
}
