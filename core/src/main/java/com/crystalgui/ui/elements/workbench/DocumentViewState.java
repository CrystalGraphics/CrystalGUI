package com.crystalgui.ui.elements.workbench;

import com.crystalgui.serialization.StateMap;

/**
 * A document that has a <em>place you were looking at</em> worth putting back — caret, scroll, folds.
 *
 * <h3>A second interface, not two more methods on {@link FileDocument}</h3>
 *
 * <p>{@code FileDocument} is three stateless methods and stays that way. "This document has no caret" is a
 * real and common answer — a graph editor genuinely has none — rather than an implementation somebody
 * forgot, which is the distinction {@code AGENTS.md} draws when it refuses defaults on the platform SPI.
 * IntelliJ splits it the same way: {@code FileEditorState} is a separate thing a provider may or may not
 * produce, not a method every editor must answer.</p>
 *
 * <h3>Keyed by file, never by panel — this is the call worth arguing about</h3>
 *
 * <p>The tempting place for this is {@code DockPanelRef.state}, which is already serialised with the
 * layout and already carries the file's path. It is wrong, observably: close a file and reopen it and the
 * caret should still be where you left it, even though the panel that held it is gone. {@code
 * DockPanelRef}'s own documentation says its payload is <em>identity</em> — which file this is — and that a
 * panel's richer state is the panel's business.</p>
 *
 * <p>Both editors agree. VS Code keeps an {@code IEditorMemento} keyed by resource URI (LRU-capped, so a
 * long session does not accumulate them forever); IntelliJ writes per-file {@code <state>} elements under
 * {@code FileEditorManager} in {@code workspace.xml}. Neither hangs it off the tab.</p>
 *
 * <h3>It is applied after the content lands, not when the panel is built</h3>
 *
 * <p>A read is asynchronous. Restoring a caret at line 400 into a document that is still empty silently
 * clamps it to 0, and the failure looks like the caret never being saved. {@code Workbench} applies view
 * state from the one place a read completes, for exactly this reason.</p>
 */
public interface DocumentViewState {

    /** Writes where the reader was. Nothing is required — an empty map means "no opinion". */
    <T> void writeViewState(StateMap<T> out);

    /**
     * Puts the reader back.
     *
     * <p>Must tolerate a map written by an older build, one describing a longer file than the one now on
     * disk, and one that is simply empty. Every value therefore comes with a fallback, and a position past
     * the end is clamped rather than refused: the file changed underneath the record, which is ordinary,
     * and losing the whole restore over one stale number helps nobody.</p>
     */
    <T> void readViewState(StateMap<T> in);
}
