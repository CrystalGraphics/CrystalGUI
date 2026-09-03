package com.crystalgui.example.notes;

import com.crystalgui.core.signal.Connection;
import com.crystalgui.document.Document;
import com.crystalgui.document.DocumentEditor;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.control.Checkbox;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.widget.control.TextField;

import java.util.List;

/**
 * The checklist on screen — <b>a view of a {@link NotesModel}, and it owns none of the state</b>.
 *
 * <p>Every gesture calls a method on the model; nothing is written back here. That is what lets two of
 * these exist over one document with no synchronisation between them: each redraws from
 * {@link NotesModel#onItemsChanged}, so a tick in one appears in the other because both are looking at
 * the same list.</p>
 *
 * <p>It is the half of an example that <em>does</em> name widgets, and it is the only half. The model
 * beside it names none, which is what {@code WorkspaceApiTest} pins.</p>
 */
public final class NotesView implements DocumentEditor {

    /** The panel. `ua/panels.css` would size it; the example ships no sheet of its own. */
    public static final String CLASS = "__notes__";

    /** One row — a checkbox, its text, and a way to drop it. */
    public static final String ROW_CLASS = "__notes-row__";

    /** The line along the bottom that adds one. */
    public static final String ADD_CLASS = "__notes-add__";

    private final NotesModel model;
    private final UIElement root = new UIElement();
    private final UIElement rows = new UIElement();
    private final Connection subscription;

    public NotesView(Document document) {
        // AS, not a cast at every call site: `Document.as` says what this view is a view OF and fails
        // loudly rather than at the first field access if a kind was declared with the wrong model.
        this.model = document.as(NotesModel.class);

        root.addClass(CLASS);
        root.append(rows);
        root.append(adder());

        // FOLLOW THE MODEL, never the widgets. An edit made anywhere -- another view, an undo, a reload
        // because the file changed on the server -- reaches this the same way, so there is one path in
        // rather than one per gesture.
        subscription = model.onItemsChanged.connect(this::rebuild);
        rebuild();
    }

    @Override
    public UIElement view() {
        return root;
    }

    /**
     * Releases what this VIEW owns, which is one subscription.
     *
     * <p>The model is the document's and outlives every view of it — it is disposed when the last
     * {@code DocumentReference} is released, never when a tab closes.</p>
     */
    @Override
    public void disposeView() {
        subscription.disconnect();
    }

    private void rebuild() {
        rows.removeAll();
        List<NotesModel.Item> items = model.items();
        for (int i = 0; i < items.size(); i++) {
            rows.append(row(i, items.get(i)));
        }
    }

    private UIElement row(int index, NotesModel.Item item) {
        UIElement row = new UIElement();
        row.addClass(ROW_CLASS);

        Checkbox tick = new Checkbox();
        tick.setChecked(item.done());
        tick.onCheckedChanged.connect(checked -> model.toggle(index));
        row.append(tick);

        row.append(new UIText(item.text()));

        Button remove = new Button("Remove");
        remove.onPressed.connect(() -> model.remove(index));
        row.append(remove);
        return row;
    }

    private UIElement adder() {
        UIElement line = new UIElement();
        line.addClass(ADD_CLASS);

        TextField typed = new TextField();
        line.append(typed);

        Button add = new Button("Add");
        add.onPressed.connect(() -> {
            String text = typed.getText().trim();
            if (text.isEmpty()) return;
            model.add(text);
            typed.setText("");
        });
        line.append(add);
        return line;
    }
}
