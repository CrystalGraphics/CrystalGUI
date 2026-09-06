package com.crystalgui.ui.data;

import com.crystalgui.core.notify.StatusBar;
import com.crystalgui.core.data.ClipboardActions;
import com.crystalgui.core.data.DataKey;
import com.crystalgui.core.settings.Settings;
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
    /**
     * What cut, copy and paste mean at this position — the widget you are in, not the widget a command
     * happened to be written for. @see ClipboardActions
     */
    public static final DataKey<ClipboardActions> CLIPBOARD =
            DataKey.create("clipboard", ClipboardActions.class);



    /**
     * The store a <b>preference</b> is written to — the outermost scope this application resolves
     * through, which is not always the window's root element.
     *
     * <h3>Asked rather than derived, because "outermost" has two answers</h3>
     *
     * <p>Settings resolve outward, so a preference has to be written at the top or it reaches only one
     * subtree. The obvious way to find the top is {@code window.ui.rootElement.settings()}, and it was
     * right for exactly as long as the application WAS the root element.</p>
     *
     * <p>It stopped being right when a window compositor arrived: an application that opens itself as a
     * window sits inside a {@code WindowFrame} inside the desktop, so the window's root is the
     * compositor's root and the application's own store is several levels below it. The writer and
     * whatever listens for the change then compute "the top" separately and get different objects — the
     * value is stored, resolves correctly on the way out, and <b>nothing is ever told it changed</b>.</p>
     *
     * <p>Which reads as the preference not working at all, and is invisible to any host that still puts
     * its application at the root — a test, or a harness scene, has one store and cannot tell the two
     * apart. Whoever owns persistence answers this; the window root is the fallback for a host that has
     * nobody to ask.</p>
     */
    /**
     * The status bar of whatever surface this element is on — <b>an instance, since W5</b>.
     *
     * <p>It was a static, which was a shortcut from when there was one window: two applications on one
     * desktop cannot share one line of text, and the caret readout of whichever editor was focused last
     * would win. A widget that has something to say resolves this from where it IS, which is how it
     * says it to the right bar without naming a workbench — a text editor and a shader graph are both
     * below the workbench layer and may not import it.</p>
     *
     * <p>Null on a surface with no bar, which is an ordinary state: a widget in a bare desktop window
     * has nowhere to put a status entry and must not fail for it.</p>
     */
    public static final DataKey<StatusBar> STATUS_BAR =
            DataKey.create("ui.statusBar", StatusBar.class);

    public static final DataKey<Settings> SETTINGS_HOST =
            DataKey.create("settingsHost", Settings.class);
}
