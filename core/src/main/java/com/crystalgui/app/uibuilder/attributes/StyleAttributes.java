package com.crystalgui.app.uibuilder.attributes;

import java.util.Map;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.crystalgui.app.uibuilder.document.BuilderEdit;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.core.attribute.AttributeCarrier;
import com.crystalgui.core.attribute.AttributeSet;
import com.crystalgui.core.attribute.AttributeSlot;
import com.crystalgui.serialization.JsonOps;
import com.crystalgui.serialization.style.InlineStyleCodec;
import com.crystalgui.ui.dom.UIElement;

/**
 * An element's inline style, as something Copy Attributes can pick up.
 *
 * <pre>{@code
 * AttributeClipboard.put(new StyleAttributes(node).copyAttributes());
 * new StyleAttributes(target).applyAsEdit(document, chosen);
 * }</pre>
 *
 * <h3>Inline style, and only inline style</h3>
 *
 * <p>Not the computed values. Copying what a STYLESHEET says would paste a rule's effect onto the target
 * as a pile of inline overrides: it would look right and stop answering the theme, which is the opposite
 * of what somebody reaching for this wants. What a designer means by "these attributes" is what they
 * typed onto the element, and that is exactly the inline layer.</p>
 */
public final class StyleAttributes implements AttributeCarrier {

    /** What can be pasted onto what. Any consumer holding CSS-ish inline style may share it. */
    public static final String DOMAIN = "crystalgui:style";

    private final UIElement node;

    public StyleAttributes(UIElement node) {
        this.node = node;
    }

    @Override
    public AttributeSet copyAttributes() {
        AttributeSet.Builder builder = AttributeSet.builder(DOMAIN, describe(node));
        JsonElement encoded = InlineStyleCodec.encode(JsonOps.INSTANCE, node);
        if (encoded != null && encoded.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : encoded.getAsJsonObject().entrySet()) {
                // THE CSS NAME AS THE LABEL. Prettifying `border-radius` into "Corner radius" loses the
                // one thing a designer can look up, and there are sixty of them to invent names for.
                builder.add(AttributeSlot.of(entry.getKey(), AttributeGroup.of(entry.getKey()).label()),
                        entry.getValue());
            }
        }
        return builder.build();
    }

    /**
     * <b>Merges</b>, deliberately.
     *
     * <p>Paste Attributes adds the chosen properties to whatever the target already has; it is not
     * "make this element like that one". So this is {@code decodeInto} and not the replacing variant the
     * undo path uses — the properties nobody ticked are the ones being kept.</p>
     */
    @Override
    public void pasteAttributes(AttributeSet chosen) {
        JsonObject subset = new JsonObject();
        for (AttributeSet.Entry entry : chosen.entries()) {
            if (entry.value() instanceof JsonElement value) subset.add(entry.slot().id(), value);
        }
        InlineStyleCodec.decodeInto(JsonOps.INSTANCE, subset, node);
    }

    /** The paste as one undoable step, which is the consumer's job rather than the engine's. */
    public boolean applyAsEdit(UiBuilderDocument document, AttributeSet chosen) {
        JsonElement was = InlineStyleCodec.encode(JsonOps.INSTANCE, node);
        pasteAttributes(chosen);
        JsonElement after = InlineStyleCodec.encode(JsonOps.INSTANCE, node);
        if (after.equals(was)) return false;
        document.apply(new BuilderEdit.SetInlineStyle(node, was, after));
        return true;
    }

    /** {@code #id} where there is one, else the tag — what a person calls what they copied. */
    public static String describe(@Nullable UIElement node) {
        if (node == null) return "nothing";
        String id = node.getId();
        return id != null && !id.isEmpty() ? "#" + id : node.name().toString();
    }
}
