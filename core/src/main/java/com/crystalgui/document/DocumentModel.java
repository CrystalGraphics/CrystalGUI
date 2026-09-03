package com.crystalgui.document;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.core.undo.UndoStack;

/**
 * <b>What an open document IS</b> — headless, and the one thing a {@link Document} holds.
 *
 * <h3>The layer this replaces did not have one</h3>
 *
 * <p>{@code plan_fs_rewrite.md} §0: there was no first-class "document the workspace holds". A text
 * document was a record wrapping a {@code TextEditor}, so the model <em>was</em> the widget — the
 * status subscription had to be {@code static} because a record has no instance state, and the whole
 * {@code document} package sat above {@code widget}, which left the one headless document model the
 * engine has ({@code TextBuffer}) sitting a package below, unused as one.</p>
 *
 * <p>So: a model knows its content and nothing about paths, tabs, saving, etags or windows. It can be
 * built and edited and asked whether it has changed with no display anywhere.</p>
 *
 * <h3>Version, not a comparison</h3>
 *
 * <p>{@link #version()} is a counter every change bumps, and it is what dirtiness is made of:
 * {@code version() != savedVersion}. Dirtiness used to be {@code encode()} compared against the bytes
 * read from disk, run for every open document on every change — which for a shader graph means
 * serialising the whole graph to JSON to decide whether a tab needs an asterisk.</p>
 *
 * @see AbstractDocumentModel for the base that makes {@code apply(Edit)} the one door
 */
public interface DocumentModel {

    /**
     * What a save writes.
     *
     * <p>Called on demand, so a model with no stored serial form encodes at this moment rather than
     * keeping one in step with itself. Must be <b>stable</b>: encoding an unchanged model twice has to
     * give equal bytes, or a file that nothing has touched reports itself modified for ever.</p>
     */
    byte[] encode();

    /**
     * Replaces the content wholesale with what came from the file — <b>a reload, never an edit</b>.
     *
     * <p>The version still bumps and {@link #onChanged()} still fires, because the document has
     * genuinely moved. What must not happen is an undo entry: Ctrl+Z after a file changed underneath
     * you would then restore the text the server had already replaced. {@code TextBuffer.load} did
     * exactly that until F0.</p>
     *
     * <p><b>Throw if the bytes cannot be applied.</b> A model that quietly ignores them shows empty,
     * reports itself modified against the file it failed to read, and the first save writes that
     * emptiness over the user's work.</p>
     */
    void adopt(byte[] bytes);

    /** Bumped by every edit, every undo and every redo, and by {@link #adopt}. Never decreases. */
    int version();

    /** This document's history. One per document, never per view — two panes on one file share it. */
    UndoStack history();

    /** Fired after the content moved. Read {@link #version()} for the stamp it moved to. */
    Signal.Action onChanged();

    /**
     * Whether a three-way merge of this content is meaningful.
     *
     * <p>False by default, and the default is the honest one: a line-based merge of a JSON graph
     * produces a JSON graph that does not parse. It gates the conflict dialog's third button, so a
     * model that says nothing is offered keep-mine and take-theirs and not a merge it cannot survive.
     * Text says true.</p>
     */
    default boolean mergeable() {
        return false;
    }

    /**
     * Releases whatever this model owns — a parse tree, a language engine, GPU resources.
     *
     * <p>Called when the last {@link DocumentReference} to the document goes, which is later than a tab
     * closing and never earlier: the Problems panel, a background compile and a diff view may each hold
     * one after every tab is gone.</p>
     */
    default void dispose() {
    }
}
