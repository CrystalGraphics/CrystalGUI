package com.crystalgui.render;

import com.crystalgraphics.compute.ops.CgGpuOps;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.world.CgPipelinePrecache;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import com.crystalgraphics.platform.rhi.format.CgFormat;

/**
 * The UI's renderer: what every document's painting shares, held once for the process. It names the UI's pipelines to
 * the precache, hands out a {@link View} for each place a document is drawn, and frees every view's GPU resources when
 * the graphics context goes. What a frame records is the view's: each document records on its own thread, into a
 * target of its own size.
 *
 * <pre>{@code
 * CgUiRenderer.precache();                         // client init: CgUiClientLifecycle.onPreInit
 * CgUiRenderer.parseAhead();                       // graphics start: onContextInit
 * CgUiRenderer.View view = CgUiRenderer.openView(); // one per document; a DocumentDriver opens its own
 * ...
 * view.close();                                    // render thread, when the document goes
 * CgUiRenderer.contextDestroyed();                 // CgUiClientLifecycle.onContextDestroy
 * }</pre>
 *
 * <ul>
 *   <li>A host never calls these: {@code CgUiClientLifecycle} does, and a {@code DocumentDriver} opens and closes its
 *       document's view.</li>
 *   <li>A view not closed keeps its textures until the context goes. Close it with its document.</li>
 * </ul>
 */
public final class CgUiRenderer {

    private static final String[] QUAD_SHADERS = {"crystalgui:shaders/gui_color_field.shader"};

    /** Every view with a paint context made and not closed: what {@link #contextDestroyed} frees. */
    private static final Set<View> PAINTING = new LinkedHashSet<>();

    private CgUiRenderer() {
    }

    /**
     * Names every shader and kernel the UI draws to CrystalGraphics' precache, so the desktop's first frame compiles
     * nothing. Once, at client init; any thread. The canvas materials are CrystalGraphics', precached there.
     */
    public static void precache() {
        CgPipelinePrecache.register("crystalgui:ui", pipelines -> {
            for (String quad : QUAD_SHADERS) pipelines.addPass(quad, CgInstanceKind.QUAD);
            // The backdrop's blur and a thumbnail's shrink on the layer format, and an icon shadow's blur on the atlas's.
            pipelines.kernels(() -> {
                CgGpuOps.precacheBlur(CgFormat.RGBA8_UNORM);
                CgGpuOps.precacheBlur(CgFormat.RGBA16_FLOAT);
                CgGpuOps.precacheShrink(CgFormat.RGBA8_UNORM);
            });
        });
    }

    /** Parses the UI's own shaders on a worker, from the graphics start: the precache's first collect then parses none. */
    public static void parseAhead() {
        CgPipelinePrecache.parseAhead(QUAD_SHADERS);
    }

    /** A place to draw a document: its recorder and its presenter. Any thread; nothing is made until it paints. */
    public static View openView() {
        return new View();
    }

    /**
     * Frees what every view's paint context made: frame targets, surfaces, backdrop and icon-raster textures, requested
     * from the graph, which no registry sweeps. Views stay open and make them again at their next frame. At graphics
     * context destruction, before CrystalGraphics frees its own; render thread, idempotent.
     */
    public static void contextDestroyed() {
        List<View> views;
        synchronized (PAINTING) {
            views = new ArrayList<>(PAINTING);
        }
        for (View view : views) view.releaseGpu();
    }

    /**
     * One place a document is drawn: the {@link CgUiPaintContext} its frames are recorded with, and the
     * {@link UiPresenter} that shows them at a GUI stage. The two live and die together, since a presented frame shows
     * the context's target. A {@code DocumentDriver} owns its document's; anything drawing a document without one opens
     * its own.
     *
     * <pre>{@code
     * CgUiRenderer.View view = CgUiRenderer.openView();
     *
     * CgUiPaintContext ctx = view.paintContext();   // the thread the document is framed on
     * ctx.recordFrame(w, h);
     * document.paint(ctx);
     * UiFrame frame = ctx.seal();
     *
     * view.presenter().present(stage, frame);       // render thread, at CgRenderStage.SCREEN or HUD
     * view.close();                                 // render thread, when the document goes
     * }</pre>
     *
     * <ul>
     *   <li>The context is made at the first {@link #paintContext()}: a view that never paints holds no GPU
     *       resource.</li>
     *   <li>{@link #close()} frees the context's frame target, surfaces, backdrop and icon raster at once. A view left
     *       open keeps them until the graphics context goes.</li>
     *   <li>Closed is final: {@link #paintContext()} throws after it.</li>
     * </ul>
     */
    public static final class View {

        private final UiPresenter presenter = new UiPresenter();
        @Nullable
        private volatile CgUiPaintContext context;
        private volatile boolean closed;

        private View() {
        }

        /** What this view's frames are recorded with, made at the first call. The thread the document is framed on. */
        public CgUiPaintContext paintContext() {
            CgUiPaintContext made = context;
            if (made != null) return made;
            synchronized (this) {
                if (closed) throw new IllegalStateException("The view is closed");
                if (context == null) {
                    context = new CgUiPaintContext();
                    synchronized (PAINTING) {
                        PAINTING.add(this);
                    }
                }
                return context;
            }
        }

        /** What shows this view's frames at a stage, and keeps the last. Render thread. */
        public UiPresenter presenter() {
            return presenter;
        }

        /** Releases the shown frame and frees everything the context made. Render thread; idempotent. */
        public synchronized void close() {
            if (closed) return;
            closed = true;
            presenter.release();
            CgUiPaintContext made = context;
            context = null;
            if (made == null) return;
            made.release();
            synchronized (PAINTING) {
                PAINTING.remove(this);
            }
        }

        public boolean isClosed() {
            return closed;
        }

        /** The context's GPU resources, freed for a graphics context going away; the view stays open. Render thread. */
        private synchronized void releaseGpu() {
            presenter.release();
            CgUiPaintContext made = context;
            if (made != null) made.release();
        }
    }
}
