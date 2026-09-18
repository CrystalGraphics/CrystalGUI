package com.crystalgui.app.uibuilder.inspect;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;

import com.crystalgui.app.uibuilder.document.BuilderEdit;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.serialization.JsonOps;
import com.crystalgui.serialization.style.InlineStyleCodec;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.ui.dom.UIElement;

/**
 * A node's own inline style as {@link Declarations} — what the box model and the scrub always wrote, lifted out of
 * them so the same gizmos can write a rule instead.
 *
 * <p>The whole inline style is the unit of undo, as {@code BuilderEdit.SetInlineStyle} is: a gesture snapshots it,
 * writes live, and records the difference once. A value dragged back to what the node has without the declaration
 * leaves nothing inline — {@code dropIfRedundant}, so the file carries no line that changes nothing.</p>
 */
final class InlineDeclarations implements Declarations {

    private final UIElement node;

    @Nullable
    private final UiBuilderDocument document;

    /** The inline style a gesture began with, and null between gestures. */
    @Nullable
    private JsonElement before;

    /** What the open gesture has written, for the redundancy drop that closes it. */
    @Nullable
    private StyleProperty<?> touched;

    InlineDeclarations(UIElement node, @Nullable UiBuilderDocument document) {
        this.node = node;
        this.document = document;
    }

    @Override
    public String valueOf(StyleProperty<?> property) {
        String text = node.getStyle().inlineText(property);
        return text == null ? "" : text;
    }

    @Override
    public boolean set(StyleProperty<?> property, String css) {
        touched = property;
        if (css.isEmpty()) {
            LiveEdits.clearInline(node, property);
            return true;
        }
        if (before != null || document == null) return LiveEdits.setInline(node, cast(property), css);
        // OUTSIDE A GESTURE A WRITE RECORDS ITSELF, which is what a typed value and a picked keyword are.
        BuilderEdit edit = NodeFields.on(document).inlineEdit(node, property, css);
        if (edit != null) document.apply(edit);
        return edit != null;
    }

    @Override
    public boolean declares(StyleProperty<?> property) {
        return LiveEdits.hasInline(node, property);
    }

    @Override
    public boolean canWrite() {
        return document != null;
    }

    @Override
    public void beginGesture() {
        before = NodeFields.inlineStyleOf(node);
        touched = null;
    }

    @Override
    public void endGesture(boolean keep) {
        JsonElement was = before;
        StyleProperty<?> property = touched;
        before = null;
        touched = null;
        if (was == null) return;
        if (!keep) {
            InlineStyleCodec.replaceInto(JsonOps.INSTANCE, was, node);
            return;
        }
        if (property != null) LiveEdits.dropIfRedundant(node, property);
        JsonElement after = NodeFields.inlineStyleOf(node);
        if (document != null && !after.equals(was)) document.apply(new BuilderEdit.SetInlineStyle(node, was, after));
    }

    @SuppressWarnings("unchecked")
    private static StyleProperty<Object> cast(StyleProperty<?> property) {
        return (StyleProperty<Object>) property;
    }
}
