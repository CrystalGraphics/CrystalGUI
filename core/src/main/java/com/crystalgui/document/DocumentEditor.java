package com.crystalgui.document;

import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.dom.UIElement;

/**
 * A view onto a {@link Document} — <b>the only thing in this package that names a node</b>.
 *
 * <p>And it names {@code UIElement}, not a widget: a view is an element, and which widget it is made of
 * is the editor's business. That is what lets the document layer sit below {@code widget} once the
 * document layer sit below {@code widget}, and it is why {@link DocumentModel} and this are separate —
 * the model is what the document IS, this is what one way of looking at it happens to be.</p>
 *
 * <h3>Several of these may exist for one document</h3>
 *
 * <p>Two split panes onto one file, a diff view, a preview. They share the model, the history and the
 * parse tree; each holds its own caret, scroll and folds, which is the document/view boundary the
 * engine already draws for undo.</p>
 */
public interface DocumentEditor {

    /** The element the dock shows. */
    UIElement view();

    /**
     * Told when this view becomes, or stops being, the one in front.
     *
     * <p>Where a status contribution goes: a text editor reports a caret, a line ending and an
     * encoding; a shader graph reports a compile summary; a viewer reports neither.
     * <b>Deactivation is not optional</b> — a view that publishes on activation and never withdraws
     * leaves its numbers on screen underneath somebody else's tab, which is exactly how the shader
     * graph's compile summary came to sit over a plain text file.</p>
     */
    default void activated(boolean active) {
    }

    /** Where you were looking — caret, scroll, folds, pan, zoom. Restored by the session. */
    default <T> void writeViewState(StateMap<T> out) {
    }

    /** @see #writeViewState */
    default <T> void readViewState(StateMap<T> in) {
    }

    /**
     * Releases what this VIEW owns — never what the document owns.
     *
     * <p>The dock rebuilds every panel on every split and drag, so anything freed here is freed for a
     * document that is still open. The parse tree, the language engine and the model's own resources
     * belong to {@link DocumentModel#dispose}, which runs when the last reference goes.</p>
     */
    default void disposeView() {
    }
}
