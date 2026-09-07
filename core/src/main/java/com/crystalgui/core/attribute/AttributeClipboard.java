package com.crystalgui.core.attribute;

import java.util.HashSet;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * What a <b>Copy Attributes</b> put down, and what the last paste chose to take.
 *
 * <pre>{@code
 * AttributeClipboard.put(carrier.copyAttributes());
 *
 * AttributeSet copied = AttributeClipboard.pending(target.copyAttributes().domain());
 * if (copied != null) {
 *     if (AttributeClipboard.remembersAChoice()) target.pasteAttributes(AttributeClipboard.remembered(copied));
 *     else new PasteAttributesDialog(copied, target::pasteAttributes).show(from);
 * }
 * }</pre>
 *
 * <h3>Static, like every other clipboard</h3>
 *
 * <p>One that belonged to a document could not carry attributes BETWEEN documents, which is most of what
 * anyone wants it for. The scope is the process, exactly as the system clipboard's is.</p>
 *
 * <h3>It remembers the choice, and forgets it on the next copy</h3>
 *
 * <p>Resolve's <em>Don't show until next copy</em>. Pasting the same handful of properties onto twenty
 * elements should not be twenty passes through a checkbox list — but the remembered choice belongs to the
 * COPY, not to the session: put something else on the clipboard and the question is open again, because
 * the tick boxes are about what was copied and there is no reason to think the answer carries over.</p>
 */
public final class AttributeClipboard {

    @Nullable
    private static AttributeSet copied;

    @Nullable
    private static Set<String> chosen;

    private AttributeClipboard() {
    }

    /** Replaces whatever was there, and forgets the last choice with it. */
    public static void put(@Nullable AttributeSet set) {
        copied = set != null && !set.isEmpty() ? set : null;
        chosen = null;
    }

    public static void clear() {
        put(null);
    }

    /** Whether anything at all has been copied. */
    public static boolean hasSomething() {
        return copied != null;
    }

    /**
     * What is waiting, if it can be pasted onto {@code domain}.
     *
     * @return null when nothing was copied, or when it came off a different kind of thing
     */
    @Nullable
    public static AttributeSet pending(String domain) {
        return copied != null && copied.domain().equals(domain) ? copied : null;
    }

    /** Whether the last paste said not to ask again for this copy. */
    public static boolean remembersAChoice() {
        return chosen != null;
    }

    /** Notes the chosen slots, so the next paste of the same copy can skip the window. */
    public static void remember(AttributeSet subset) {
        Set<String> ids = new HashSet<>();
        for (AttributeSet.Entry entry : subset.entries()) ids.add(entry.slot().id());
        chosen = ids;
    }

    /** The remembered subset of a set, or the whole of it when nothing is remembered. */
    public static AttributeSet remembered(AttributeSet set) {
        Set<String> ids = chosen;
        return ids == null ? set : set.keeping(slot -> ids.contains(slot.id()));
    }
}
