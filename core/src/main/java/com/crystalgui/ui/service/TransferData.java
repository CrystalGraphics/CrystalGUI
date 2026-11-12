package com.crystalgui.ui.service;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * One piece of data in the forms it can be handed over in — the DOM's {@code DataTransfer}, Android's
 * {@code ClipData}, OLE's {@code IDataObject}. The clipboard ({@link UiClipboard}) and drag and drop carry this one
 * type, found by Java type rather than by MIME string.
 *
 * <p>A receiver asks for the type it understands and never names the carrier's class, so a tree's private drag record
 * can still be dropped on a dock:</p>
 *
 * <pre>{@code
 * // A source offers every form it has
 * TransferData data = TransferData.of(fragment, fragmentAsJson);   // a typed value, and its text
 * UiClipboard.process().put(data);
 *
 * // A receiver takes the one it knows
 * DraggedResources files = TransferData.find(event.getPayload(), DraggedResources.class);
 * if (files != null) event.preventDefault();   // accept
 * }</pre>
 *
 * <p>Easy to get wrong: a payload that simply IS the type is found too, so a source with one form implements
 * nothing. The plain-text form is the {@link String} one ({@link #text}); it is what reaches the system clipboard, so
 * a typed value with no text form stays inside this process. Hold immutable values: a copy is read after the
 * source has changed.</p>
 */
public interface TransferData {

    /** What this offers as a {@code type}, or null when it offers none. */
    @Nullable
    <D> D as(Class<D> type);

    /** The plain-text form, or null. */
    @Nullable
    default String text() {
        return as(String.class);
    }

    /** {@code payload} as a {@code type}: itself if it is one, else what it offers through {@link #as}, else null. */
    @Nullable
    static <D> D find(@Nullable Object payload, Class<D> type) {
        if (type.isInstance(payload)) return type.cast(payload);
        return payload instanceof TransferData data ? data.as(type) : null;
    }

    /** Offers each non-null form; the first of a type wins. */
    static TransferData of(Object... forms) {
        List<Object> held = new ArrayList<>(forms.length);
        for (Object form : forms) {
            if (form != null) held.add(form);
        }
        return new Forms(List.copyOf(held));
    }

    /** The forms {@link #of} holds. */
    record Forms(List<Object> held) implements TransferData {
        @Override
        public <D> D as(Class<D> type) {
            for (Object form : held) {
                if (type.isInstance(form)) return type.cast(form);
            }
            return null;
        }
    }
}
