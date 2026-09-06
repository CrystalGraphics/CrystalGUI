package com.crystalgui.workbench.dock.panel;

/**
 * Decides whether it can show an input, and builds the pane that does — IntelliJ's
 * {@code FileEditorProvider}, VS Code's {@code EditorPaneRegistry} entry.
 *
 * <p>The indirection is what lets a feature claim a kind of input without the dock learning about it, and
 * what lets two features claim the same one: a diff view and a plain editor both accept a file, and
 * {@link #priority()} decides — IntelliJ spells that {@code FileEditorPolicy}.</p>
 */
public interface DockPaneProvider {

    /** Whether this provider can show {@code input}. Asked before {@link #create}, never after. */
    boolean accepts(DockInput input);

    /** A fresh pane. One is built per group that needs one, not per input. */
    DockPane create();

    /** Higher wins when several providers accept the same input. */
    default int priority() {
        return 0;
    }
}
