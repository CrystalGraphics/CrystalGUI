package com.crystalgui.ui;

import com.crystalgui.core.layout.LayoutContext;
import com.crystalgui.core.render.CgUILayers;
import com.crystalgui.core.render.CgUIRenderContext;
import io.github.somehussar.crystalgraphics.gl.render.CgBufferSource;
import lombok.Getter;

import org.joml.Matrix4f;

import javax.annotation.Nullable;

public final class UIContainer {

    @Getter
    private final LayoutContext layoutContext = new LayoutContext();
    @Getter @Nullable
    private UIDocument document;

    /**
     * The render context for batched UI drawing. Set via
     * {@link #setRenderContext(CgUIRenderContext)} once the buffer source
     * has been assembled. Null until then.
     */
    @Getter @Nullable
    private CgUIRenderContext renderContext;

    public UIContainer(UIDocument document) {
        attachDocument(document);
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
    }

    public void detachDocument() {
        if (document == null) {
            return;
        }

        UIElement root = document.getRoot();
        layoutContext.detachSubtree(root);
        root.detachFromContainer(this);
        document = null;
    }

    public void computeLayout(float availableWidth, float availableHeight) {
        if (document == null) {
            throw new IllegalStateException("No document is attached");
        }
        layoutContext.computeLayout(document.getRoot(), availableWidth, availableHeight);
    }

    // ── Render context + draw traversal (plan §12.4, §12.6) ─────────

    /**
     * Sets the render context used by {@link #render(Matrix4f)}.
     *
     * <p>This must be called after assembling the {@link CgBufferSource}
     * via {@link CgUILayers} and wrapping it in a
     * {@link CgUIRenderContext}.</p>
     *
     * @param renderContext the render context, or {@code null} to disconnect rendering
     */
    public void setRenderContext(@Nullable CgUIRenderContext renderContext) {
        this.renderContext = renderContext;
    }

    /**
     * Executes the full UI draw traversal: begins the render context, walks
     * the DOM tree in document order painting visible {@link CgUIDrawable} nodes,
     * then ends the context (flushing all remaining geometry).
     *
     * <p>This is the primary render entry point. Call after
     * {@link #computeLayout(float, float)} has resolved layout positions.</p>
     *
     * @param projection the orthographic projection matrix for this frame
     * @throws IllegalStateException if no document is attached or no render
     *                               context has been set
     */
    public void render(Matrix4f projection) {
        if (document == null) {
            throw new IllegalStateException("No document is attached");
        }
        if (renderContext == null) {
            throw new IllegalStateException("No render context has been set — call setRenderContext() first");
        }

        renderContext.begin(projection);
        try {
            document.getRoot().drawSubtree(renderContext);
        } finally {
            renderContext.end();
        }
    }

    public void dispose() {
        detachDocument();
    }
}
