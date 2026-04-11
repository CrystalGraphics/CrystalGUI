package com.crystalgui.ui;

import com.crystalgui.core.layout.LayoutContext;
import lombok.Getter;

import javax.annotation.Nullable;

public final class UIContainer {

    @Getter
    private final LayoutContext layoutContext = new LayoutContext();
    @Getter @Nullable
    private UIDocument document;

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

    public void dispose() {
        detachDocument();
    }
}
