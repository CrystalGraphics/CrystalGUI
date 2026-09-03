package com.crystalgui.core.data;


/**
 * What cut, copy and paste mean <em>here</em> — IntelliJ's {@code CutProvider}/{@code CopyProvider}/
 * {@code PasteProvider}, reached the same way its are: through the data context, innermost answer wins.
 *
 * <h3>One action, many providers</h3>
 *
 * <p>Cut means something in a text editor, something else in a file tree, and something else again in a
 * node graph. Three commands is the obvious answer and it is wrong in a specific way: a menu bar has one
 * <b>Cut</b> row, so three commands means either three rows — two of them permanently greyed — or a menu
 * that decides which to show, which is a fourth place to keep in step with the other three.</p>
 *
 * <p>Both references answer it identically. IntelliJ's {@code $Cut} is a single action that asks the
 * focused component for a {@code CutProvider}; VS Code scopes three commands with {@code when} clauses so
 * exactly one is ever live. Ours is IntelliJ's, because this engine already has the mechanism:
 * {@code DataProvider.getData} walks outward from focus and stops at the first answer, so the widget you
 * are in is the widget that decides.</p>
 *
 * <p>The specific commands do not go away and should not — {@code editor.cut} keeps its own binding and
 * its place in the palette. What changes is that the <em>menu</em> stops naming one of them.</p>
 *
 * <h3>No defaults</h3>
 *
 * <p>Six abstract methods, and a widget that only copies still has to say so with three {@code return
 * false}s. A default here would be an answer chosen for someone who never saw the question — the same
 * argument {@code CgPlatformService} makes about sound and cursor — and the failure it produces is
 * invisible: a provider that silently inherits "cannot paste" is indistinguishable from one that
 * considered paste and refused it.</p>
 *
 * <p>Reached through the key {@code ui.data.UiDataKeys.CLIPBOARD} — named in prose rather than
 * linked, because a {@code @see} across this boundary is still an import, and the import is the
 * whole reason this interface moved out of {@code ui}.</p>
 */
public interface ClipboardActions {

    /** Whether there is something to cut. Asked when a menu is built and again when a row is chosen. */
    boolean canCut();

    void cut();

    boolean canCopy();

    void copy();

    /**
     * Whether there is something to paste <b>here</b>.
     *
     * <p>Deliberately not "is the clipboard non-empty": a file tree cannot paste text, and an editor
     * cannot paste files. The provider knows which clipboard it means; nothing above it does.</p>
     */
    boolean canPaste();

    void paste();
}
