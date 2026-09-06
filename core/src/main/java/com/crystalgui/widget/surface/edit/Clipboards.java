package com.crystalgui.widget.surface.edit;

import javax.annotation.Nullable;

/**
 * What was last copied, for the whole process — so a fragment copied in one surface pastes into
 * another.
 *
 * <pre>{@code
 * Clipboards.store(clipboard.copy());
 * GraphDocument clip = Clipboards.stored(GraphDocument.class);
 * }</pre>
 *
 * <p>One slot, and it is typed on the way out: a graph fragment offered to a UI builder is simply not
 * there, which is what {@link Clipboard#type} exists for. Deliberately separate from the system
 * clipboard — a subtree of widgets has no text form worth putting on it.</p>
 */
public final class Clipboards {

    private Clipboards() {
    }

    @Nullable
    private static Object stored;

    /** Replaces what is held. Null is ignored, so copying nothing keeps what was copied before. */
    public static void store(@Nullable Object clip) {
        if (clip != null) stored = clip;
    }

    /** What is held, if it is a {@code type}; null otherwise. */
    @Nullable
    public static <T> T stored(Class<T> type) {
        return type.isInstance(stored) ? type.cast(stored) : null;
    }

    public static boolean has(Class<?> type) {
        return type.isInstance(stored);
    }

    public static void clear() {
        stored = null;
    }
}
