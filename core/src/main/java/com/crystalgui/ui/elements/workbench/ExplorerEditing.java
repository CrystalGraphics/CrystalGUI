package com.crystalgui.ui.elements.workbench;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.fs.CgPath;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.TextField;
import dev.vfyjxf.taffy.style.TaffyDisplay;

import javax.annotation.Nullable;

/**
 * The explorer's inline edit — VS Code's {@code IExplorerService.setEditable} plus the
 * {@code FilesRenderer.renderInputBox} half that draws it.
 *
 * <h3>A part of the explorer, not a client of it</h3>
 *
 * <p>Beside {@link ProjectFileTree} in its own package and reaching it through package-private
 * accessors, which is the decomposition {@code TextEditor}'s ten view parts already use here. VS Code
 * needs a service interface between the two because its renderer may not touch the view; with one view
 * implementation in one package that indirection is a layer to keep in step rather than a seam.</p>
 *
 * <h3>Why it is one object and not two</h3>
 *
 * <p>VS Code splits it — the service holds {@code editableData}, the renderer draws the input — and the
 * split costs a round trip on every keystroke's validation. The state and the three rules that read it
 * (commit on blur, cancel on Escape, cancel on an invalid name) are one fact, and separating them here
 * would put the rules on the far side of an interface from the state they are about.</p>
 */
final class ExplorerEditing {

    private final ProjectFileTree tree;

    ExplorerEditing(ProjectFileTree tree) {
        this.tree = tree;
    }

    /**
     * An edit in progress -- VS Code's {@code editableData}.
     *
     * @param path     the row being edited; for a new entry, the placeholder the source inserted
     * @param onCommit handed the typed name, only when it is valid and changed
     */
    record Editing(CgPath path, java.util.function.Consumer<String> onCommit) {
    }

    @Nullable
    private Editing editing;

    /** True while a commit or cancel is running, so a blur raised by either cannot re-enter. */
    private boolean finishingEdit;

    /**
     * Renames {@code path} in place -- F2.
     *
     * <p>An input <b>in the row</b>, not a dialog over it. A dialog is what this used to do and what file
     * managers stopped doing decades ago: it hides the folder you are naming inside, it puts the answer
     * somewhere other than where the question is, and it cannot show the icon change as you type.</p>
     */
    void beginRename(CgPath path, java.util.function.Consumer<String> onCommit) {
        if (path == null || path.isProjectRoot()) return;
        startEditing(new Editing(path, onCommit));
    }

    /**
     * Adds a placeholder row under {@code parent} and edits it -- VS Code's {@code NewExplorerItem}.
     *
     * <p>The row exists before the file does, which is the whole point: you see where it will land, in
     * the folder you chose, before committing to a name.</p>
     */
    void beginNew(CgPath parent, boolean directory,
                         java.util.function.Consumer<String> onCommit) {
        if (parent == null) return;
        // EXPANDED FIRST, or the placeholder is a child of a folded folder and nothing appears at all --
        // which reads as New File doing nothing.
        if (!tree.treeView().isExpanded(parent)) tree.treeView().setExpanded(parent, true);
        CgPath placeholder = tree.source().beginPendingNew(parent, directory);
        startEditing(new Editing(placeholder, onCommit));
    }

    private void startEditing(Editing next) {
        cancelEdit();
        editing = next;
        // Deferred rather than immediate: this is routinely called from a menu row's activation or a key
        // press, and refreshing now would rebuild the element that dispatch is still walking.
        tree.requestRefresh();
    }

    /** Whether a row is being edited. */
    boolean isEditing() {
        return editing != null;
    }

    /** The row being edited, or null. */
    @Nullable
    CgPath editingPath() {
        return editing == null ? null : editing.path();
    }

    /** Drops the edit, and any placeholder with it. */
    void cancelEdit() {
        if (editing == null || finishingEdit) return;
        finishingEdit = true;
        editing = null;
        tree.source().endPendingNew();
        tree.requestRefresh();
        finishingEdit = false;
        returnFocusToTree();
    }

    /**
     * Accepts what was typed, if it is usable.
     *
     * <p><b>An invalid name cancels rather than commits.</b> That is the same answer a blur gives, and the
     * only one that cannot destroy anything: committing a name the validator has already refused is
     * worse, and the alternative to both is trapping the user in a row they cannot leave.</p>
     */
    private void commitEdit(String typed) {
        Editing current = editing;
        if (current == null || finishingEdit) return;
        String name = typed == null ? "" : typed.trim();
        boolean usable = isValidName(current.path(), name);
        finishingEdit = true;
        editing = null;
        tree.source().endPendingNew();
        tree.requestRefresh();
        finishingEdit = false;
        returnFocusToTree();
        // The unchanged case is not a failure and must not be reported as one -- pressing F2 then Enter is
        // how people check what a file is called.
        if (usable && !name.equals(current.path().name())) current.onCommit().accept(name);
    }

    /**
     * Whether {@code name} may be committed for {@code path}.
     *
     * <p>Three refusals, each a real one rather than a guess: empty, a path separator (which would
     * silently create the entry in another directory), and a sibling that already exists. The last is
     * checked against what has been <b>listed</b>, so it catches the case that matters -- a name you can
     * see on screen.</p>
     */
    boolean isValidName(CgPath path, String name) {
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) return false;
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) return false;
        CgPath parent = path.parent();
        if (parent == null) return true;
        for (CgPath sibling : tree.source().listedChildren(parent)) {
            if (!sibling.equals(path) && sibling.name().equalsIgnoreCase(name)) return false;
        }
        return true;
    }

    /** Focus goes back to the tree, or the next key press lands nowhere. */
    private void returnFocusToTree() {
        UIWindow window = tree.getAttachedWindow();
        if (window != null) {
            window.getInputHandler().requestPointerFocus(tree.treeView());
        }
    }

    /**
     * Wires one row's input. Once, in {@code createTemplate} -- a listener may only be attached once, and
     * a recycled row keeps the one it was built with.
     */
    void installEditor(UIElement row, TextField editor) {
        editor.onSubmit.connect(this::commitEdit);
        editor.onBlur.attachListener((element, event) -> {
            // BLUR COMMITS, as VS Code's does. Cancelling on blur means clicking away from a name you have
            // finished typing throws it away, which is the more expensive of the two mistakes.
            CgPath item = tree.itemForRow(row);
            if (editing != null && item != null && item.equals(editing.path())) {
                commitEdit(editor.getText());
            }
        }, false, true);
        editor.onKeyDown.attachListener((element, event) -> {
            if (event.getKeyCode() == CgKeyCodes.KEY_ESCAPE) {
                cancelEdit();
                event.stopPropagation();
            }
        }, false, true);
    }

    /**
     * Puts {@code row} into or out of edit mode.
     *
     * <p>Driven from {@code bind}, because a recycled row may arrive still showing the previous
     * occupant's editor -- the same reason every other data-driven class here is swapped rather than
     * added.</p>
     */
    void applyEditing(UIElement row, ProjectFileTree.RowParts parts, CgPath item) {
        boolean active = editing != null && editing.path().equals(item);
        if (active) row.addClass(ProjectFileTree.EDITING_CLASS);
        else row.removeClass(ProjectFileTree.EDITING_CLASS);
        StyleGroup.importantPipeline(parts.editor().getStyle().getLayoutGroup(),
                l -> l.display(active ? TaffyDisplay.FLEX : TaffyDisplay.NONE));
        StyleGroup.importantPipeline(parts.label().getStyle().getLayoutGroup(),
                l -> l.display(active ? TaffyDisplay.NONE : TaffyDisplay.FLEX));
        if (!active) return;

        String current = tree.source().isPendingNew(item) ? "" : item.name();
        parts.editor().setText(current);
        UIWindow window = tree.getAttachedWindow();
        if (window != null) window.getInputHandler().requestFocus(parts.editor());
        // THE STEM IS SELECTED, NOT THE WHOLE NAME, which is what F2 does everywhere: the extension is
        // almost never what you are changing, and selecting it means the first keystroke destroys it.
        int dot = current.lastIndexOf('.');
        if (dot > 0) parts.editor().setSelection(0, dot);
        else parts.editor().selectAll();
    }

}
