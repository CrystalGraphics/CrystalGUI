package com.crystalgui.ui;

import com.crystalgui.core.data.DataKey;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.ui.elements.chrome.MenuBarView;

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

    /**
     * The window this position is in.
     *
     * <p>Answered by {@code UIElement} from {@code getAttachedWindow()}, so every attached element
     * answers it and a detached one answers nothing — which is the correct answer, not a gap.</p>
     *
     * <p>Here rather than on {@code UIWindow} for the reason this class exists: it is what let the last
     * window-capturing command sets become global. A command that needed a window used to be registered
     * <em>per</em> window and hold a reference, which meant it could not be registered once — and a
     * registry keyed by nothing but id then handed every later invocation to whichever window was built
     * first.</p>
     */
    /**
     * The main menu bar, offered by whatever chrome owns one.
     *
     * <p>Reached through the ordinary outward walk, so a command that toggles the bar's presentation does
     * not have to be handed the widget — the same reason {@code WORKBENCH} exists. The <b>workbench</b>
     * answers this rather than the bar itself: the walk only finds ancestors, and a menu bar is a sibling
     * of the content everything else is focused inside.</p>
     */
    public static final DataKey<MenuBarView> MENU_BAR =
            DataKey.create("menuBar", MenuBarView.class);

    public static final DataKey<UIWindow> WINDOW =
            DataKey.create("window", UIWindow.class);
}
