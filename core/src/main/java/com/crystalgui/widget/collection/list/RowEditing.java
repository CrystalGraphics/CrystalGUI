package com.crystalgui.widget.collection.list;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.overlay.InputDialog;

import dev.vfyjxf.taffy.style.TaffyDisplay;

/**
 * Editing a list or tree row's name in the row — F2 rename, and a new entry named where it will appear.
 * VS Code's {@code IExplorerService.setEditable} plus the {@code renderInputBox} half that draws it, for any
 * {@link ListView}.
 *
 * <pre>{@code
 * // once, beside the list:
 * RowEditing<Node> editing = new RowEditing<>(tree, this::itemForRow, tree::refresh);
 *
 * // in the renderer: a field built with the row, and put into or out of editing on every bind
 * public UIElement createTemplate() {
 *     ...
 *     TextField field = new TextField();
 *     editing.installEditor(row, field);
 * }
 * public void bind(Node item, TreeRow<Node> row, int index, UIElement template) {
 *     editing.apply(template, label, field, item);
 * }
 *
 * // and from the command:
 * editing.begin(RowEditing.Edit.of(node, node.name(), name -> rename(node, name)));
 * }</pre>
 *
 * <pre>{@code
 * // a file: the stem selected, a malformed name refused, a taken one offered a free name, a placeholder
 * // row dropped however it ends
 * editing.begin(RowEditing.Edit.of(path, path.name(), name -> rename(path, name))
 *         .selecting(0, stem)
 *         .accepting(name -> !name.contains("/"))
 *         .conflicting("file", name -> siblingHas(name) ? numbered(name) : null)
 *         .onEnd(source::endPendingNew));
 * }</pre>
 *
 * <ul>
 *   <li>Enter or a blur commits; Escape cancels. A value {@link Edit#accepting} refuses cancels rather than
 *       commits, and the value the edit opened with commits nothing — F2 then Enter is how people check a name.</li>
 *   <li>A value {@link Edit#conflicting} names is marked on the field as it is typed
 *       ({@link TextField#setConflicting}); committing it asks, as Windows' Explorer does, whether to take the
 *       free name offered instead. Yes renames to it and No leaves the name as it was; both end the edit.</li>
 *   <li>Build the field in {@code createTemplate}, never in {@code bind}: an element created during bind lands
 *       after that frame's layout, and the edit begins from a key press on the row the bind would rebuild.</li>
 *   <li>{@code rebuild} may be deferred, and should be where an edit can begin from a handler on a row.</li>
 * </ul>
 */
public final class RowEditing<T> {

    /** On the row while it is being edited, so a theme can quiet the rest of it. */
    public static final String EDITING_CLASS = "__editing__";

    private final ListView<?> list;

    private final Function<UIElement, T> itemForRow;

    private final Runnable rebuild;

    @Nullable
    private Edit<T> editing;

    /** True while a commit or cancel runs, so a blur raised by either cannot re-enter. */
    private boolean finishing;

    /** True while the conflict question is up: the field has lost focus to it, which is not a commit. */
    private boolean asking;

    /** The row element already primed for the current edit, and its field. @see #apply */
    @Nullable
    private UIElement primed;

    @Nullable
    private TextField primedField;

    /**
     * @param itemForRow the item a realised row element shows now, or null
     * @param rebuild    rebinds the visible rows
     */
    public RowEditing(ListView<?> list, Function<UIElement, T> itemForRow, Runnable rebuild) {
        this.list = Objects.requireNonNull(list, "list");
        this.itemForRow = Objects.requireNonNull(itemForRow, "itemForRow");
        this.rebuild = Objects.requireNonNull(rebuild, "rebuild");
    }

    /**
     * One edit: the row's item, the text it opens with, and what an accepted value does.
     *
     * @param conflict   a value's free alternative when the value is taken, or null when it is not
     * @param noun       what the rows are, for the conflict question — {@code "file"}
     * @param selectFrom where the opening selection starts; {@code selectTo} of -1 selects everything
     */
    public record Edit<T>(T item, String text, Consumer<String> onCommit, Predicate<String> accepts,
                          Function<String, String> conflict, String noun, Runnable onEnd,
                          int selectFrom, int selectTo) {

        public Edit {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(onCommit, "onCommit");
            Objects.requireNonNull(accepts, "accepts");
            Objects.requireNonNull(conflict, "conflict");
            Objects.requireNonNull(noun, "noun");
            Objects.requireNonNull(onEnd, "onEnd");
        }

        /** Opens with {@code text} selected, accepts anything non-empty, and hands a changed value to {@code onCommit}. */
        public static <T> Edit<T> of(T item, String text, Consumer<String> onCommit) {
            return new Edit<>(item, text, onCommit, value -> !value.isEmpty(), value -> null, "item",
                    () -> { }, 0, -1);
        }

        /** Only values {@code accepts} passes commit. Replaces the default, which refuses an empty value. */
        public Edit<T> accepting(Predicate<String> accepts) {
            return new Edit<>(item, text, onCommit, accepts, conflict, noun, onEnd, selectFrom, selectTo);
        }

        /**
         * A value another row already has: {@code alternative} answers the free name to offer for it, or null
         * when the value is free.
         *
         * @param noun what a row is, as the question names it — {@code "file"}, {@code "element"}
         */
        public Edit<T> conflicting(String noun, Function<String, String> alternative) {
            return new Edit<>(item, text, onCommit, accepts, alternative, noun, onEnd, selectFrom, selectTo);
        }

        /** Opens with {@code [from, to)} selected rather than the whole text — a file's stem. */
        public Edit<T> selecting(int from, int to) {
            return new Edit<>(item, text, onCommit, accepts, conflict, noun, onEnd, from, to);
        }

        /** Runs however the edit ends, before a commit is handed on — a placeholder row withdrawn. */
        public Edit<T> onEnd(Runnable onEnd) {
            return new Edit<>(item, text, onCommit, accepts, conflict, noun, onEnd, selectFrom, selectTo);
        }
    }

    /** Opens {@code edit}, ending any edit already open. */
    public void begin(Edit<T> edit) {
        cancel();
        editing = edit;
        primed = null;
        primedField = null;
        rebuild.run();
    }

    /** Whether a row is being edited. */
    public boolean isEditing() {
        return editing != null;
    }

    /** The item being edited, or null. */
    @Nullable
    public T item() {
        return editing == null ? null : editing.item();
    }

    /** Ends the edit without committing. */
    public void cancel() {
        Edit<T> current = editing;
        if (current == null || finishing || asking) return;
        finish(current);
    }

    private void commit(String typed) {
        Edit<T> current = editing;
        if (current == null || finishing || asking) return;
        String value = typed == null ? "" : typed.trim();
        if (value.equals(current.text()) || !current.accepts().test(value)) {
            finish(current);
            return;
        }
        String alternative = current.conflict().apply(value);
        if (alternative == null) {
            finish(current);
            current.onCommit().accept(value);
            return;
        }
        ask(current, alternative);
    }

    /** Windows' question for a taken name: take the free one, or leave the name as it was. Either ends the edit. */
    private void ask(Edit<T> current, String alternative) {
        asking = true;
        String noun = current.noun();
        String question = current.text().isEmpty()
                ? "Do you want to name it \"" + alternative + "\"?"
                : "Do you want to rename \"" + current.text() + "\" to \"" + alternative + "\"?";
        String detail = "There is already " + article(noun) + " " + noun + " with the same name in this location.";
        UIElement from = primedField != null ? primedField : list;
        InputDialog.askYesNo(from, "Rename " + Character.toUpperCase(noun.charAt(0)) + noun.substring(1),
                question, detail,
                () -> {
                    asking = false;
                    if (editing != current) return;
                    finish(current);
                    current.onCommit().accept(alternative);
                },
                () -> {
                    asking = false;
                    if (editing == current) finish(current);
                });
    }

    private static String article(String noun) {
        return !noun.isEmpty() && "aeiou".indexOf(Character.toLowerCase(noun.charAt(0))) >= 0 ? "an" : "a";
    }

    private void finish(Edit<T> current) {
        finishing = true;
        editing = null;
        primed = null;
        primedField = null;
        try {
            current.onEnd().run();
            rebuild.run();
        } finally {
            finishing = false;
        }
        // BACK TO THE LIST, or the next key press lands nowhere.
        UIDocument window = list.document();
        if (window != null) window.focus().requestPointerFocus(list);
    }

    /** Wires one row's field. Once, from {@code createTemplate}: a recycled row keeps the listeners it was built with. */
    public void installEditor(UIElement row, TextField field) {
        // THE CONFLICT IS CHECKED AS IT IS TYPED; the name commits only on Enter or a blur.
        field.onTextChanged.connect(text -> {
            if (field == primedField) markConflict(field, text);
        });
        field.onSubmit.connect(text -> {
            if (field == primedField) commit(field.getText());
        });
        field.onBlur.attachListener((element, event) -> {
            // BLUR COMMITS, as VS Code's does: cancelling on blur throws away a name finished and clicked away
            // from, the more expensive of the two mistakes.
            //
            // BUT NOT A BLUR THE LIST RAISED ON ITSELF. Recycling blurs a row before pooling it, and read as a
            // user gesture that ended every edit in the frame it opened. Asked of the list, because anyone may
            // start a refresh.
            if (list.isRecyclingRow() || asking) return;
            Edit<T> current = editing;
            if (current != null && Objects.equals(itemForRow.apply(row), current.item())) commit(field.getText());
        }, false, true);
        field.onKeyDown.attachListener((element, event) -> {
            if (event.getKeyCode() == CgKeyCodes.KEY_ESCAPE && editing != null) {
                cancel();
                event.stopPropagation();
            }
        }, false, true);
    }

    private void markConflict(TextField field, String typed) {
        Edit<T> current = editing;
        String value = typed == null ? "" : typed.trim();
        field.setConflicting(current != null && !value.equals(current.text()) && current.accepts().test(value)
                && current.conflict().apply(value) != null);
    }

    /**
     * Puts {@code row} into or out of editing — from {@code bind}, because a recycled row may arrive still
     * showing its last occupant's field.
     *
     * @param label what the field stands in for while the row is being edited
     */
    public void apply(UIElement row, UIElement label, TextField field, T item) {
        Edit<T> current = editing;
        boolean active = current != null && Objects.equals(current.item(), item);
        if (active != row.hasClass(EDITING_CLASS)) {
            if (active) row.addClass(EDITING_CLASS);
            else row.removeClass(EDITING_CLASS);
        }
        StyleGroup.inlinePipeline(field.getStyle().getLayoutGroup(),
                l -> l.display(active ? TaffyDisplay.FLEX : TaffyDisplay.NONE));
        StyleGroup.inlinePipeline(label.getStyle().getLayoutGroup(),
                l -> l.display(active ? TaffyDisplay.NONE : TaffyDisplay.FLEX));
        if (!active) {
            field.setConflicting(false);
            if (primed == row) {
                primed = null;
                primedField = null;
            }
            return;
        }
        // ONCE PER EDIT, NOT ONCE PER BIND. A bind runs on every refresh, for reasons unrelated to the edit,
        // and priming each time threw away what was typed and put the caret back several times a second.
        // Keyed on the row ELEMENT: a row that scrolls out and back is a new template, and needs priming.
        if (primed == row) return;
        primed = row;
        primedField = field;
        field.setText(current.text());
        field.setConflicting(false);
        UIDocument window = list.document();
        if (window != null) window.focus().requestFocus(field);
        if (current.selectTo() < 0) field.selectAll();
        else field.setSelection(current.selectFrom(), current.selectTo());
    }
}
