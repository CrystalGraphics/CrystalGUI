package com.crystalgui.core.undo;

import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UiDataKeys;
import com.crystalgui.core.data.DataKey;

import javax.annotation.Nullable;

/**
 * An element that owns a document, and therefore an undo history — a text editor, a graph view, a
 * canvas. Implementing it is what puts a document's history on the map.
 *
 * <h3>Resolution is the keymap's, deliberately</h3>
 * <p>{@link #nearest} walks outward from the focused element and takes the <b>first</b> scope it finds,
 * which is exactly how {@code KeymapResolver} matches a binding. Copying that shape is the point: a user
 * already has a mental model for "the innermost thing that cares about this keystroke handles it", and
 * undo now obeys it. Two editor tabs are two scopes, focus decides which one Ctrl+Z reaches, and the
 * per-document rule stops being advice in a javadoc and becomes how lookup works.</p>
 *
 * <p>An element with no scope above it resolves to {@code null}, and {@code edit.undo} is simply
 * disabled — which a menu item and the command palette both render for free, since enablement already
 * has one mechanism.</p>
 */
public interface UndoScope {

    /** This document's history. Never {@code null} — a scope that might not have a stack is not a scope. */
    UndoStack undoStack();

    /**
     * The nearest enclosing scope's stack, starting at {@code from} itself, or {@code null} if there is
     * none.
     *
     * @param from typically the focused element — {@code CommandContext.source()}
     */
    @Nullable
    static UndoStack nearest(@Nullable UIElement from) {
        UndoScope scope = nearestScope(from);
        return scope == null ? null : scope.undoStack();
    }

    /** @see #nearest */
    @Nullable
    static UndoScope nearestScope(@Nullable UIElement from) {
        for (UIElement element = from; element != null; element = element.getParent()) {
            if (element instanceof UndoScope scope) return scope;
        }
        return null;
    }

    /**
     * Answers {@link UiDataKeys#UNDO_STACK} for a scope, so a command can ask for the stack alongside
     * everything else instead of through a second lookup.
     *
     * <p>A {@code UIElement} implementing this interface should call it from its own
     * {@code getData}. The walk in {@link com.crystalgui.core.data.DataContext} and the walk in
     * {@link #nearestScope} are then the same walk, which is the point — a keystroke, an undo and a
     * command all agree about which document they are addressing.</p>
     */
    @Nullable
    default Object undoScopeData(DataKey<?> key) {
        return key == UiDataKeys.UNDO_STACK ? undoStack() : null;
    }
}
