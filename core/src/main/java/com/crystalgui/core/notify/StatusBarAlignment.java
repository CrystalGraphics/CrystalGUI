package com.crystalgui.core.notify;

/**
 * Which end of the status bar an entry belongs to — VS Code's {@code StatusbarAlignment}.
 *
 * <p>Ported from {@code vs/workbench/services/statusbar/browser/statusbar.ts}.</p>
 *
 * <h3>Why the model carries this and the view does not decide it</h3>
 *
 * <p>Only the writer knows. "Ln 51, Col 39" belongs on the right because it is <em>about the thing you
 * are looking at</em> and sits in a fixed place you can glance at; "created notes.txt" belongs on the
 * left because it is about what just happened and is read as prose. A view sorting by id prefix, or by
 * guessing at the text, would be inventing an answer the writer already has.</p>
 */
public enum StatusBarAlignment {
    /** The reading half: ambient prose, breadcrumbs, what just happened. */
    LEFT,
    /** The glancing half: caret position, line ending, encoding, indent. */
    RIGHT
}
