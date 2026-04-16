package com.crystalgui.ui;

import com.crystalgui.core.input.FocusManager;
import com.crystalgui.core.input.UiInputManager;
import com.crystalgui.core.layout.LayoutContext;
import com.crystalgui.core.render.CgUiBatchSlots;
import com.crystalgui.core.render.CgUiDrawList;
import com.crystalgui.core.render.CgUiDrawListExecutor;
import com.crystalgui.core.render.CgUiPaintContext;
import com.crystalgui.core.render.CgUiRuntime;
import com.crystalgui.core.render.ScissorStack;
import io.github.somehussar.crystalgraphics.text.render.CgTextRenderContext;
import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;
import lombok.Getter;

import org.joml.Matrix4f;

import javax.annotation.Nullable;

@Getter
public final class UIContainer {

    private final LayoutContext layoutContext = new LayoutContext();
    private UIDocument document;

    @Nullable
    private UiInputManager inputManager;
    @Nullable
    private FocusManager focusManager;

    // ── Draw-list rendering ─────────────────────────────────────────────

    private final CgUiBatchSlots batchSlots;
    private final CgUiDrawList drawList;
    private final CgUiPaintContext paintContext;
    private final CgUiDrawListExecutor drawListExecutor;
    private final CgUiRuntime runtime;
    private CgTextRenderContext textRenderContext;

    /**
     * Creates a container with default batch slots, auto-fetching the global
     * {@link CgUiRuntime} for shared rendering services.
     *
     * <p>This is the preferred constructor for most callers — just provide a
     * document and start building UI.</p>
     *
     * @param document the UI document to attach
     */
    public UIContainer(UIDocument document) {
        this(document, CgUiBatchSlots.single(CgVertexFormat.POS2_UV2_COL4UB, 4096));
    }

    /**
     * Creates a container with custom batch slots, auto-fetching the global
     * {@link CgUiRuntime} for shared rendering services.
     *
     * @param document   the UI document to attach
     * @param batchSlots custom batch slot configuration
     */
    public UIContainer(UIDocument document, CgUiBatchSlots batchSlots) {
        if (batchSlots == null) throw new IllegalArgumentException("batchSlots must not be null");
        this.batchSlots = batchSlots;
        this.runtime = CgUiRuntime.get();
        this.drawList = new CgUiDrawList();
        ScissorStack scissorStack = new ScissorStack();
        this.paintContext = new CgUiPaintContext(drawList, batchSlots, scissorStack, runtime);
        this.drawListExecutor = new CgUiDrawListExecutor();
        attachDocument(document);
    }

    /**
     * Headless constructor for unit tests — bypasses all GL/rendering initialization.
     * Only layout, lifecycle, and event systems are available.
     */
    UIContainer(UIDocument document, @SuppressWarnings("unused") boolean headless) {
        this.batchSlots = null;
        this.runtime = null;
        this.drawList = null;
        this.paintContext = null;
        this.drawListExecutor = null;
        attachDocument(document);
    }

    /**
     * Creates a headless UIContainer for unit testing (no GL context required).
     * Only layout, lifecycle, event, and focus systems are functional.
     */
    public static UIContainer headless(UIDocument document) {
        return new UIContainer(document, true);
    }

    public UIElement getRoot() {
        if (document == null) {
            throw new IllegalStateException("No document is attached");
        }
        return document.getRoot();
    }

    public void attachDocument(UIDocument document) {
        if (document == null) {
            throw new IllegalArgumentException("document must not be null");
        }
        if (this.document != null) {
            throw new IllegalStateException("A document is already attached to this UIContainer");
        }

        this.document = document;
        document.getRoot().attachToContainer(this);
        layoutContext.attachSubtree(document.getRoot());

        this.inputManager = new UiInputManager(this);
        this.focusManager = new FocusManager(this);
    }

    public void detachDocument() {
        if (document == null) {
            return;
        }

        if (focusManager != null) {
            focusManager.clearFocusSilently();
        }

        UIElement root = document.getRoot();
        layoutContext.detachSubtree(root);
        root.detachFromContainer(this);
        document = null;
        inputManager = null;
        focusManager = null;
    }

    public void computeLayout(float availableWidth, float availableHeight) {
        if (document == null) {
            throw new IllegalStateException("No document is attached");
        }
        layoutContext.computeLayout(document.getRoot(), availableWidth, availableHeight);

        if (focusManager != null) {
            focusManager.validateFocus();
        }
    }

    // ── Draw-list render path ──────────────────────────────────────────

    /**
     * Executes the full draw-list UI render: records all UI geometry into the
     * draw list during DOM traversal, then replays the list sequentially through
     * the batch renderer backend.
     *
     * @param projection the orthographic projection matrix for this frame
     */
    public void render(Matrix4f projection) {
        if (document == null) {
            throw new IllegalStateException("No document is attached");
        }

        if (runtime.hasTextServices()) {
            if (textRenderContext == null) {
                textRenderContext = new CgTextRenderContext(projection);
                paintContext.configureText(runtime.getTextRenderer(), runtime.getDefaultFontFamily(), textRenderContext);
            } else {
                textRenderContext.getProjection().set(projection);
            }
        }

        paintContext.beginRecord();
        try {
            document.getRoot().drawSubtree(paintContext);
        } finally {
            paintContext.endRecord();
        }
        try {
            drawListExecutor.execute(drawList, batchSlots, projection);
        } finally {
            paintContext.finishFrame();
        }
    }

    public void dispose() {
        detachDocument();
        if (batchSlots != null) {
            batchSlots.delete();
        }
    }
}
