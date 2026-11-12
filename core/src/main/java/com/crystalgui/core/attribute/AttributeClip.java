package com.crystalgui.core.attribute;

import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * What a <b>Copy Attributes</b> put down: the attributes, and what the paste of this copy chose to take. It travels
 * as one form of a {@code TransferData} on the {@code UiClipboard}, so it crosses documents and applications.
 *
 * <pre>{@code
 * // Copy
 * AttributeClip clip = AttributeClip.of(carrier.copyAttributes());           // null when there is nothing in it
 * if (clip != null) UiClipboard.process().put(TransferData.of(clip));
 *
 * // Paste
 * UiClipboard.process().read(AttributeClip.class).then(clip -> {
 *     if (clip == null || !clip.accepts(target.copyAttributes().domain())) return;
 *     if (clip.remembersAChoice()) target.pasteAttributes(clip.remembered());
 *     else PasteAttributesDialog.open(from, clip.attributes(), "target", target::pasteAttributes, clip::remember);
 * });
 * }</pre>
 *
 * <p>Resolve's <em>Don't show until next copy</em>: pasting the same handful of properties onto twenty elements
 * should not be twenty passes through a checkbox list. The remembered choice belongs to the COPY, so a new copy is a
 * new clip and the question is open again.</p>
 *
 * <p>Easy to get wrong: the choice is held on the clip, so a paste that remembers it must be given the clip it read,
 * not a fresh read of another copy.</p>
 */
public final class AttributeClip {

    private final AttributeSet attributes;

    @Nullable
    private volatile Set<String> chosen;

    private AttributeClip(AttributeSet attributes) {
        this.attributes = attributes;
    }

    /** A clip of {@code attributes}, or null when there are none to paste. */
    @Nullable
    public static AttributeClip of(@Nullable AttributeSet attributes) {
        return attributes == null || attributes.isEmpty() ? null : new AttributeClip(attributes);
    }

    public AttributeSet attributes() {
        return attributes;
    }

    /** Whether this came off the same kind of thing as {@code domain}. */
    public boolean accepts(String domain) {
        return attributes.domain().equals(domain);
    }

    /** Whether the last paste said not to ask again for this copy. */
    public boolean remembersAChoice() {
        return chosen != null;
    }

    /** Notes the chosen slots, so the next paste of this copy can skip the window. */
    public void remember(AttributeSet subset) {
        Set<String> ids = new HashSet<>();
        for (AttributeSet.Entry entry : subset.entries()) ids.add(entry.slot().id());
        chosen = ids;
    }

    /** The remembered subset, or all of it when nothing is remembered. */
    public AttributeSet remembered() {
        Set<String> ids = chosen;
        return ids == null ? attributes : attributes.keeping(slot -> ids.contains(slot.id()));
    }
}
