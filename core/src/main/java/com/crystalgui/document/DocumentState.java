package com.crystalgui.document;

/**
 * What state a document is in — <b>one enum, read by every surface</b>.
 *
 * <p>A tab's decoration, the save path, the conflict dialog and the session record all ask this, so
 * there is one answer rather than a set of flags each of them reads a different one of.</p>
 *
 * <p>The same set VS Code's {@code TextFileEditorModel} carries, under the same names: dirty,
 * orphaned, conflicting, error.</p>
 */
public enum DocumentState {

    /** The read is in flight. A tab shows a placeholder; a save waits. */
    LOADING,

    /** On screen and identical to the file. */
    CLEAN,

    /** Edited here and not yet written. {@code version() != savedVersion}. */
    DIRTY,

    /**
     * The file changed on the server under a document that was <b>clean</b>.
     *
     * <p>Transient in practice: nothing is at risk, so the document reloads through {@code adopt} and
     * returns to {@link #CLEAN}. It is a state rather than an event because the reload is asynchronous
     * and something has to describe the gap.</p>
     */
    STALE,

    /**
     * The file changed on the server under a document that was <b>dirty</b>.
     *
     * <p>The only state that needs a person. It suppresses auto-save for this document until it is
     * resolved, so the notification appears once rather than every delay interval.</p>
     */
    CONFLICTING,

    /** The file is gone on the server. The content is still here, and saving would recreate it. */
    ORPHANED,

    /** The bytes could not be read, or could not be applied by the model. Saving is refused. */
    FAILED;

    /** Whether a save may proceed without asking anybody anything. */
    public boolean isSaveable() {
        return this == CLEAN || this == DIRTY || this == ORPHANED;
    }
}
