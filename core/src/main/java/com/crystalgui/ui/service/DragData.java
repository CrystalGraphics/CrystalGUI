package com.crystalgui.ui.service;

import javax.annotation.Nullable;

/**
 * A drag payload that offers what it carries to targets that do not know it — the DOM's {@code DataTransfer}, by
 * type rather than by MIME string.
 *
 * <p>A target asks for the type it understands and never names the payload's own class, so a tree's private drag
 * record can still be dropped on a dock:</p>
 *
 * <pre>{@code
 * DraggedFiles files = DragData.find(event.getPayload(), DraggedFiles.class);
 * if (files != null) event.preventDefault();   // accept
 * }</pre>
 *
 * <p>A payload that simply IS the type is found too, so a source with one representation implements nothing.</p>
 */
public interface DragData {

    /** What this payload offers as a {@code type}, or null when it offers none. */
    @Nullable
    <D> D as(Class<D> type);

    /** {@code payload} as a {@code type}: itself if it is one, else what it offers through {@link #as}, else null. */
    @Nullable
    static <D> D find(@Nullable Object payload, Class<D> type) {
        if (type.isInstance(payload)) return type.cast(payload);
        return payload instanceof DragData data ? data.as(type) : null;
    }
}
