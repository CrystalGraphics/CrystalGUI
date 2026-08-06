package com.crystalgui.ui;

import com.crystalgui.core.data.DataKey;
import com.crystalgui.core.undo.UndoStack;

import java.util.List;

/**
 * The questions the engine itself knows how to ask — IntelliJ's {@code CommonDataKeys} /
 * {@code PlatformDataKeys}.
 *
 * <h3>Keys live with the layer that defines the concept, not with the widget that answers</h3>
 *
 * <p>{@link #SELECTION} is here rather than on {@code GraphView} because the file tree answers it too,
 * and a key owned by one of its answerers is a key the others have to depend on that widget to use.
 * A key belonging to a single feature belongs with that feature — {@code ShaderGraphEditor} declares
 * its own — and this class is only for the ones the engine has an opinion about.</p>
 */
public final class UiDataKeys {

    private UiDataKeys() {
    }

    /**
     * The element the walk started at — the focused one.
     *
     * <p>Answered by {@code UIElement} itself, so it is never null in a non-empty context. Mostly for a
     * command that needs somewhere to anchor a popup rather than a subject to act on.</p>
     */
    public static final DataKey<UIElement> ELEMENT =
            DataKey.create("element", UIElement.class);

    /**
     * What is selected here, whatever "here" is.
     *
     * <p>Deliberately untyped in its elements: the tree answers with paths, a graph with nodes, an
     * editor with ranges. A command that can act on several kinds asks for this and checks; one that
     * cannot asks for its own key instead.</p>
     */
    @SuppressWarnings("unchecked")
    public static final DataKey<List<Object>> SELECTION =
            DataKey.create("selection", (Class<List<Object>>) (Class<?>) List.class);

    /**
     * The undo history this position belongs to.
     *
     * <p>The same answer {@code UndoScope.nearest} gives, reached the same way — see that interface for
     * why it is the innermost one and not a global. Exposed as a key so a command can ask for it
     * alongside everything else rather than through a second lookup mechanism.</p>
     */
    public static final DataKey<UndoStack> UNDO_STACK =
            DataKey.create("undoStack", UndoStack.class);
}
