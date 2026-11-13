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

    /**
     * Whether a drag carrying this may leave its document: true only where every form is a value (resources, text, ids,
     * an encoded fragment) a document on another thread can read. A payload holding nodes stays in its own document.
     */
    default boolean portable() {
        return false;
    }

    /**
     * What of this another document may be offered: itself when {@link #portable}, the values of a {@link #carrying}
     * payload, else null.
     */
    @Nullable
    default TransferData shared() {
        return portable() ? this : null;
    }

    /** What of {@code payload} another document may be offered, or null: nothing, for a payload that is not a {@code TransferData}. */
    @Nullable
    static TransferData shared(@Nullable Object payload) {
        return payload instanceof TransferData data ? data.shared() : null;
    }

    /**
     * A payload that is nodes for its own document and values for any other: a tab dragged inside the editor is the
     * tab, and over another application it is the file it shows.
     *
     * <pre>{@code
     * Drag.startWithPayload(tab, x, y, TransferData.carrying(dockPayload, new DraggedResources(files)), listener);
     * }</pre>
     *
     * @param local what this document's targets read; may hold nodes
     * @param values what leaves it; every form a value
     */
    static TransferData carrying(Object local, TransferData values) {
        return new Carrying(local, values);
    }

    /** What {@link #carrying} holds. */
    record Carrying(Object local, TransferData values) implements TransferData {
        @Override
        public <D> D as(Class<D> type) {
            D found = find(local, type);
            return found != null ? found : values.as(type);
        }

        @Override
        public TransferData shared() {
            return values.shared();
        }
    }

    /** Offers each non-null form; the first of a type wins. Not portable: a form may hold nodes. */
    static TransferData of(Object... forms) {
        return forms(false, forms);
    }

    /**
     * {@link #of} for forms that are all values: the drag may be offered to other applications' documents.
     *
     * <pre>{@code
     * Drag.startWithPayload(row, x, y, TransferData.values(new DraggedResources(files), paths), listener);
     * }</pre>
     */
    static TransferData values(Object... forms) {
        return forms(true, forms);
    }

    private static TransferData forms(boolean portable, Object[] forms) {
        List<Object> held = new ArrayList<>(forms.length);
        for (Object form : forms) {
            if (form != null) held.add(form);
        }
        return new Forms(List.copyOf(held), portable);
    }

    /** The forms {@link #of} holds. */
    record Forms(List<Object> held, boolean portable) implements TransferData {
        @Override
        public <D> D as(Class<D> type) {
            for (Object form : held) {
                if (type.isInstance(form)) return type.cast(form);
            }
            return null;
        }
    }
}
