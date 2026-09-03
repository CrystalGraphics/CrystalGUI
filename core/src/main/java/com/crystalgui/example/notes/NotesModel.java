package com.crystalgui.example.notes;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.core.undo.Edit;
import com.crystalgui.document.AbstractDocumentModel;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A checklist — <b>what a document model is, with nothing else in the way</b>.
 *
 * <p>One line per item, {@code [x] } for a ticked one. It exists to show the whole of what a kind has
 * to supply: bytes in, bytes out, and every change through {@link #apply}.</p>
 *
 * <pre>{@code
 * NotesModel notes = NotesModel.decode(bytes);
 * notes.add("buy milk");        // undoable
 * notes.toggle(0);              // undoable
 * byte[] out = notes.encode();  // what a save writes
 * }</pre>
 *
 * <h3>Headless, and that is the point</h3>
 *
 * <p>Nothing here names a widget, a tab, a path or a connection. So this document analyses, holds
 * diagnostics, can be saved and can be searched with no view of it anywhere — which is what lets the
 * Problems panel and a background pass work on a file nobody has opened. {@code WorkspaceApiTest}
 * asserts it against the class file rather than trusting the reading.</p>
 *
 * <h3>Every change is an {@link Edit}</h3>
 *
 * <p>{@link AbstractDocumentModel#apply} is the one door: it runs the edit, bumps the version and puts
 * it on the history. So Ctrl+Z reaches {@link #add} and {@link #toggle} with nothing else written, and
 * dirtiness is {@code version() != savedVersion} rather than a serialise-and-compare.</p>
 *
 * <p><b>{@link #adopt} is not an edit</b> and deliberately does not go through it: a file changing
 * underneath you is not something you did, and putting it on the stack would let Ctrl+Z resurrect the
 * text the reload replaced.</p>
 */
public final class NotesModel extends AbstractDocumentModel {

    /** What a ticked line starts with. Anything else is an unticked item. */
    private static final String TICK = "[x] ";

    private final List<Item> items = new ArrayList<>();

    /** An item changed, was added or was removed — what a view redraws from. */
    public final Signal.Action onItemsChanged = new Signal.Action();

    /** One line. A record, so a snapshot of the list cannot be edited through what it handed back. */
    public record Item(String text, boolean done) {
    }

    public static NotesModel decode(byte[] bytes) {
        NotesModel model = new NotesModel();
        model.load(bytes);
        return model;
    }

    public List<Item> items() {
        return List.copyOf(items);
    }

    // ── Editing ─────────────────────────────────────────────────────────────────────────────────

    public void add(String text) {
        apply(new AddEdit(text));
    }

    public void toggle(int index) {
        if (index < 0 || index >= items.size()) return;
        apply(new ToggleEdit(index));
    }

    public void remove(int index) {
        if (index < 0 || index >= items.size()) return;
        apply(new RemoveEdit(index, items.get(index)));
    }

    // ── DocumentModel ───────────────────────────────────────────────────────────────────────────

    @Override
    public byte[] encode() {
        StringBuilder out = new StringBuilder();
        for (Item item : items) {
            if (item.done()) out.append(TICK);
            out.append(item.text()).append('\n');
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Takes the file's bytes, with no undo step.
     *
     * <p>{@code adopted()} is what tells the base class this happened: it bumps the version and
     * announces, so a view redraws and the document goes clean, without anything landing on the
     * history.</p>
     */
    @Override
    public void adopt(byte[] bytes) {
        load(bytes);
        adopted();
    }

    private void load(byte[] bytes) {
        items.clear();
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n", -1)) {
            // A TRAILING EMPTY LINE IS THE TERMINATOR, not an item: every line this writes ends with a
            // newline, so splitting always yields one more piece than there are items.
            if (line.isEmpty()) continue;
            boolean done = line.startsWith(TICK);
            items.add(new Item(done ? line.substring(TICK.length()) : line, done));
        }
        onItemsChanged.emit();
    }

    // ── The edits ───────────────────────────────────────────────────────────────────────────────

    private final class AddEdit implements Edit {
        private final String text;

        AddEdit(String text) {
            this.text = text;
        }

        @Override
        public void apply() {
            items.add(new Item(text, false));
            onItemsChanged.emit();
        }

        @Override
        public void undo() {
            items.remove(items.size() - 1);
            onItemsChanged.emit();
        }

        @Override
        public String label() {
            return "add item";
        }
    }

    private final class ToggleEdit implements Edit {
        private final int index;

        ToggleEdit(int index) {
            this.index = index;
        }

        @Override
        public void apply() {
            flip();
        }

        /** Its own inverse, which is what makes this the whole of undo for it. */
        @Override
        public void undo() {
            flip();
        }

        private void flip() {
            Item was = items.get(index);
            items.set(index, new Item(was.text(), !was.done()));
            onItemsChanged.emit();
        }

        @Override
        public String label() {
            return "tick item";
        }
    }

    private final class RemoveEdit implements Edit {
        private final int index;
        private final Item removed;

        RemoveEdit(int index, Item removed) {
            this.index = index;
            this.removed = removed;
        }

        @Override
        public void apply() {
            items.remove(index);
            onItemsChanged.emit();
        }

        @Override
        public void undo() {
            // AT ITS INDEX, not appended: an undo that put it back at the end would reorder the list,
            // and a CompositeEdit unwinding several removals in reverse would scramble it.
            items.add(index, removed);
            onItemsChanged.emit();
        }

        @Override
        public String label() {
            return "remove item";
        }
    }
}
