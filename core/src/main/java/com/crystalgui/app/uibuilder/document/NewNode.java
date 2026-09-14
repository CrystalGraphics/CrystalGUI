package com.crystalgui.app.uibuilder.document;

import java.util.Objects;
import java.util.function.Supplier;

import com.crystalgui.ui.dom.UIElement;

/**
 * A node that is not in a document yet: what a Library card carries in a drag, and what a drop lands.
 *
 * <pre>{@code
 * Drag.start(card, x, y, LEFT_BUTTON, new NewNode("<button>", () -> new Button("Button")), threshold, listener);
 * // a drop target:
 * if (event.getPayload() instanceof NewNode created) document.apply(new BuilderEdit.Insert(parent, created.build().get(), index));
 * }</pre>
 *
 * @param label what a drag's ghost says
 * @param build a fresh node each call — a refused drop must not leave a built node behind in anything
 */
public record NewNode(String label, Supplier<? extends UIElement> build) {

    public NewNode {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(build, "build");
    }
}
