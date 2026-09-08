package com.crystalgui.app.uibuilder.attributes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.crystalgui.app.uibuilder.document.BuilderEdit;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.core.attribute.AttributeCarrier;
import com.crystalgui.core.attribute.AttributeSet;
import com.crystalgui.core.attribute.AttributeSlot;
import com.crystalgui.serialization.Codec;
import com.crystalgui.serialization.JsonOps;
import com.crystalgui.serialization.style.InlineStyleCodec;
import com.crystalgui.serialization.style.StylePartRegistry;
import com.crystalgui.serialization.style.StyleParts;
import com.crystalgui.serialization.style.StyleValueCodecs;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
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
 *
 * <h3>Composites come apart</h3>
 *
 * <p>A property whose value {@link StylePartRegistry declares parts} is offered a piece at a time rather
 * than whole — {@code transform} as its translate, rotate, scale and skew instead of as one checkbox
 * nobody can half-tick. A part's slot is keyed {@code property/part}, and the property is still offered
 * whole whenever its value cannot be divided.</p>
 */
public final class StyleAttributes implements AttributeCarrier {

    /** What can be pasted onto what. Any consumer holding CSS-ish inline style may share it. */
    public static final String DOMAIN = "crystalgui:style";

    /** Separates a property from one of its parts in a slot id — {@code transform/rotate}. */
    private static final char PART = '/';

    private final UIElement node;

    public StyleAttributes(UIElement node) {
        this.node = node;
    }

    @Override
    public AttributeSet copyAttributes() {
        AttributeSet.Builder builder = AttributeSet.builder(DOMAIN, describe(node));
        JsonElement encoded = InlineStyleCodec.encode(JsonOps.INSTANCE, node);
        if (encoded == null || !encoded.isJsonObject()) return builder.build();

        List<Pending> pending = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : encoded.getAsJsonObject().entrySet()) {
            if (addParts(pending, entry.getKey(), entry.getValue())) continue;
            // THE CSS NAME AS THE LABEL. Prettifying `border-radius` into "Corner radius" loses the
            // one thing a designer can look up, and there are sixty of them to invent names for.
            pending.add(new Pending(
                    AttributeSlot.of(entry.getKey(), AttributeGroup.of(entry.getKey()).label()),
                    entry.getValue()));
        }

        // SORTED BY SECTION, because a set's group order is the order its entries arrive in and they
        // arrive in property order -- so a section's place was decided by the NAME of whichever property
        // happened to come first in it. `background` put Glass at the top, `height` put Layout second and
        // `mask` put Gradient third: Layout sandwiched between two value-kind sections by alphabetical
        // accident, on a window whose whole job is to be scanned.
        //
        // STABLE, so within a section the properties stay in the order they were encoded.
        pending.sort(Comparator.comparingInt(p -> AttributeGroup.orderOf(p.slot().group())));
        for (Pending entry : pending) builder.add(entry.slot(), entry.value());
        return builder.build();
    }

    /** One slot and its value, held back so the sections can be ordered before anything is added. */
    private record Pending(AttributeSlot slot, JsonElement value) {
    }

    /**
     * Offers {@code property} a part at a time, or answers false to have it offered whole.
     *
     * <p>False covers three cases that all deserve the same answer: nothing declared parts for this
     * property, the value carries none of them, or the value is one the parts cannot describe — a
     * transform whose functions interleave. @see StyleParts#encodePart</p>
     */
    private static boolean addParts(List<Pending> into, String property, JsonElement value) {
        StyleProperty<?> declared = StylePropertyRegistry.byName(property);
        return declared != null && addParts(into, property, value, declared);
    }

    private static <V> boolean addParts(List<Pending> into, String property, JsonElement value,
                                        StyleProperty<V> declared) {
        StyleParts<V> parts = StylePartRegistry.forProperty(declared);
        Codec<V> codec = StyleValueCodecs.forProperty(declared);
        if (parts == null || codec == null) return false;

        V decoded = codec.decode(JsonOps.INSTANCE, value);
        // ALL OF IT OR NONE OF IT. A value only half of which divides would offer the half that does and
        // drop the rest in silence, so ticking every box would not reproduce the source.
        if (!parts.divides(decoded)) return false;
        boolean any = false;
        for (StyleParts.Part part : parts.parts()) {
            JsonElement encoded = parts.encodePart(JsonOps.INSTANCE, decoded, part.id());
            if (encoded == null) continue;
            // NAMED AFTER THE PROPERTY when the part has no label of its own: the section already says
            // what kind of value it is, so the row says which property holds it. @see StyleParts.Part
            String label = part.label().isEmpty() ? property : part.label();
            into.add(new Pending(new AttributeSlot(property + PART + part.id(), part.group(), label),
                    encoded));
            any = true;
        }
        return any;
    }

    /**
     * <b>Merges</b>, deliberately.
     *
     * <p>Paste Attributes adds the chosen properties to whatever the target already has; it is not
     * "make this element like that one". So this is {@code decodeInto} and not the replacing variant the
     * undo path uses — the properties nobody ticked are the ones being kept.</p>
     *
     * <p>A chosen PART merges into whatever the target already holds for its property, so pasting a
     * rotation onto an element that is translated leaves the translation where it was. Same promise, one
     * property further in, and it is why the merge starts from the target's value and not from nothing.</p>
     */
    @Override
    public void pasteAttributes(AttributeSet chosen) {
        JsonElement current = InlineStyleCodec.encode(JsonOps.INSTANCE, node);
        JsonObject held = current != null && current.isJsonObject()
                ? current.getAsJsonObject() : new JsonObject();

        JsonObject subset = new JsonObject();
        // ACCUMULATED PER PROPERTY, because several parts of one property can be ticked and each has to
        // merge onto the result of the last rather than onto the target's original value.
        Map<String, JsonElement> merged = new LinkedHashMap<>();
        for (AttributeSet.Entry entry : chosen.entries()) {
            if (!(entry.value() instanceof JsonElement value)) continue;
            String id = entry.slot().id();
            int cut = id.indexOf(PART);
            if (cut < 0) {
                subset.add(id, value);
                continue;
            }
            String property = id.substring(0, cut);
            JsonElement base = merged.containsKey(property) ? merged.get(property) : held.get(property);
            JsonElement result = mergePart(property, base, id.substring(cut + 1), value);
            if (result != null) merged.put(property, result);
        }
        merged.forEach(subset::add);
        InlineStyleCodec.decodeInto(JsonOps.INSTANCE, subset, node);
    }

    @Nullable
    private static JsonElement mergePart(String property, @Nullable JsonElement base, String part,
                                         JsonElement value) {
        StyleProperty<?> declared = StylePropertyRegistry.byName(property);
        return declared == null ? null : mergePart(declared, base, part, value);
    }

    @Nullable
    private static <V> JsonElement mergePart(StyleProperty<V> declared, @Nullable JsonElement base,
                                             String part, JsonElement value) {
        StyleParts<V> parts = StylePartRegistry.forProperty(declared);
        Codec<V> codec = StyleValueCodecs.forProperty(declared);
        if (parts == null || codec == null) return null;
        // THE TARGET'S OWN VALUE IS THE BASE, and the property's INITIAL when it holds none: merging a
        // rotation onto an element with no transform at all has to start from something, and the initial
        // is the only starting point that contributes nothing of its own.
        V from = base == null ? declared.initialValue : codec.decode(JsonOps.INSTANCE, base);
        return codec.encode(JsonOps.INSTANCE, parts.mergePart(JsonOps.INSTANCE, from, part, value));
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
