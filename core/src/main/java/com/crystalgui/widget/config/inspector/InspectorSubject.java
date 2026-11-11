package com.crystalgui.widget.config.inspector;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UINode;

import javax.annotation.Nullable;

/**
 * What a document says about what its inspectors show: that it changed, or that it closed. Local to the document,
 * since the subject is one of its nodes; every {@link Inspector} in the document listens.
 *
 * <pre>{@code
 * InspectorSubject.changed(graphView);                 // what graphView shows moved: inspectors re-ask
 * InspectorSubject.closed(document, editorElement);    // gone for good: an inspector holding it lets go
 *
 * whileConnected(() -> InspectorSubject.of(document()).onDidChange.connect(this::refresh));
 * }</pre>
 *
 * <p>Blender's notifiers, minus the categories: an operator changes data and posts a notifier, and the Properties
 * editor redraws because it was listening rather than because the operator knew it existed.</p>
 *
 * <ul>
 *   <li>{@link #changed} names any node in the document: one that is not in a document has no inspector to tell.</li>
 *   <li>{@link #closed} takes the document explicitly: the subject is often already out of the tree when it closes,
 *       and an inspector retains a detached subject on purpose.</li>
 * </ul>
 */
public final class InspectorSubject {

    private static final UIDocument.Local<InspectorSubject> LOCAL =
            UIDocument.Local.of("inspector-subject", document -> new InspectorSubject());

    /**
     * Something that is inspected has changed: re-ask. Every inspector listens, deferred to the next frame and
     * deduplicated by subject, so emitting on every mouse-move costs one rebuild at most.
     */
    public final Signal.Action onDidChange = new Signal.Action();

    /**
     * A subject was closed (its editor released, its document dropped). Distinct from {@link #onDidChange}: an
     * inspector retains what it shows when the source goes detached, and a close is the one time it must not.
     */
    public final Signal.Value<UIElement> onDidClose = new Signal.Value<>();

    private InspectorSubject() {
    }

    /** {@code document}'s. On its thread. */
    public static InspectorSubject of(UIDocument document) {
        return document.local(LOCAL);
    }

    /** Announces, in {@code within}'s document, that whatever is being inspected may have changed. Cheap. */
    public static void changed(@Nullable UINode within) {
        UIDocument document = within == null ? null : within.document();
        if (document != null) of(document).onDidChange.emit();
    }

    /** Announces, in {@code document}, that {@code subject} and anything inside it are gone for good. */
    public static void closed(@Nullable UIDocument document, @Nullable UIElement subject) {
        if (document != null && subject != null) of(document).onDidClose.emit(subject);
    }
}
