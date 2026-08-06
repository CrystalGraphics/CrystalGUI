package com.crystalgui.core.data;

import javax.annotation.Nullable;

/**
 * Something that can answer questions about itself or its subject — IntelliJ's {@code DataProvider}.
 *
 * <p>Implemented by a {@code UIElement} that knows something a command might want: the file tree knows
 * the selected path, a graph knows its selection, an editor knows its document. {@link DataContext}
 * walks outward from the focused element and takes the <b>first</b> answer, so an implementation only
 * has to say what <em>it</em> knows and may return null for everything else.</p>
 *
 * <h3>Answer for yourself, not for your children</h3>
 *
 * <p>A provider that tries to answer on behalf of things inside it defeats the walk: the inner element
 * is asked first precisely so the innermost answer wins, and an outer provider guessing produces the
 * outer answer whenever the inner one happens to be null. Return null and let the walk continue.</p>
 *
 * <h3>Cheap, and free of side effects</h3>
 *
 * <p>This is called during command enablement, which runs while a menu is being built and a palette is
 * being filtered — often, and for many keys. It must not compute, allocate a list per call, or change
 * anything. If an answer is expensive, cache it where it changes rather than here.</p>
 */
@FunctionalInterface
public interface DataProvider {

    /**
     * What this element knows about {@code key}, or null if it does not know.
     *
     * <p>Returning the wrong type is dropped by {@link DataKey#cast} rather than thrown, so one bad
     * provider cannot break a command that would have found a good answer further out.</p>
     */
    @Nullable
    Object getData(DataKey<?> key);
}
