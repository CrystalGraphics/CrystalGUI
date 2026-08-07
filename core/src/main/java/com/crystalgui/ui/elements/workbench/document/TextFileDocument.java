package com.crystalgui.ui.elements.workbench.document;

import com.crystalgui.serialization.StateMap;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.editor.TextEditor;
import com.crystalgui.ui.elements.workbench.DocumentViewState;
import com.crystalgui.ui.elements.workbench.FileDocument;

import java.nio.charset.StandardCharsets;

/**
 * A {@link FileDocument} backed by a {@link TextEditor} — what every file opened before the seam existed.
 *
 * <p>Deliberately a wrapper rather than {@code TextEditor implements FileDocument}: the editor is a widget
 * that knows nothing about paths, saving or workspaces, and is used in places that have no file behind
 * them at all — the shader graph's emitted source, a harness scene. Making it implement a file interface
 * would give every one of those an {@code encode()} nobody calls.</p>
 *
 * <p><b>No fields beyond the editor.</b> This carried the bytes last read from disk and compared against
 * them to answer "am I modified", which every other document kind would have had to repeat. The workbench
 * keeps that baseline now, because it is the thing that does the reading and writing.</p>
 */
public record TextFileDocument(TextEditor editor) implements FileDocument, DocumentViewState {

    /** Keys of the stored view state. Short because there is one entry per open file per session. */
    private static final String CARET = "caret";
    private static final String ANCHOR = "anchor";
    private static final String SCROLL = "scroll";
    private static final String FOLDS = "folds";

    @Override
    public UIElement view() {
        return editor;
    }

    @Override
    public byte[] encode() {
        return editor.getText().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The editor's own change signal, adapted.
     *
     * <p>Nothing is stored: a record has no room for a signal, and there is no need for one — the editor
     * already announces this and inventing a second source would be a copy to keep in step.</p>
     */
    @Override
    public Connection onDidChange(Runnable listener) {
        return editor.onChanged.connect(text -> listener.run());
    }

    @Override
    public void adopt(byte[] bytes) {
        // setText already suppresses an identical write, so re-reading an unchanged file costs nothing and
        // does not disturb the caret -- see TextEditor.setText.
        editor.setText(new String(bytes, StandardCharsets.UTF_8));
    }

    // ── Where you were looking ──────────────────────────────────────────────────────────────────

    @Override
    public <T> void writeViewState(StateMap<T> out) {
        out.putInt(CARET, editor.getCaret());
        // The anchor as well as the head, so a selection comes back as a selection rather than as a
        // collapsed caret at one of its ends. Omitted when they are equal -- most files have no selection,
        // and a session record is written on every close.
        int anchor = editor.getSelectionStart() == editor.getCaret()
                ? editor.getSelectionEnd() : editor.getSelectionStart();
        if (anchor != editor.getCaret()) out.putInt(ANCHOR, anchor);
        if (editor.getScrollTop() > 0f) out.putFloat(SCROLL, editor.getScrollTop());

        int[] folds = editor.collapsedRows();
        if (folds.length > 0) out.putString(FOLDS, join(folds));
    }

    /**
     * <p>Order matters and is not arbitrary: <b>caret, then folds, then scroll.</b> Folding runs a lift
     * that moves a caret out of a row it is about to hide, so setting the caret first lets that machinery
     * handle a stale record whose block no longer contains the position it did. Scroll goes last because
     * collapsing rows changes the projection every scroll offset is measured against.</p>
     */
    @Override
    public <T> void readViewState(StateMap<T> in) {
        int caret = Math.max(0, Math.min(editor.getText().length(), in.getInt(CARET, 0)));
        int anchor = Math.max(0, Math.min(editor.getText().length(), in.getInt(ANCHOR, caret)));
        if (anchor == caret) editor.setCaret(caret);
        else editor.setSelection(anchor, caret);

        String folds = in.getString(FOLDS, "");
        if (!folds.isEmpty()) editor.setCollapsedRows(split(folds));

        float scroll = in.getFloat(SCROLL, 0f);
        // setScrollImmediate, not setScrollTop: the smooth-scroll path would animate from 0 to wherever
        // the file was left, so reopening a file scrolls itself down in front of you.
        if (scroll > 0f) editor.setScrollImmediate(editor.getScrollLeft(), scroll);
    }

    /** {@code "3,17,42"} — one string rather than a list of one-field maps, which is what a list costs. */
    private static String join(int[] rows) {
        StringBuilder text = new StringBuilder();
        for (int row : rows) {
            if (text.length() > 0) text.append(',');
            text.append(row);
        }
        return text.toString();
    }

    private static int[] split(String text) {
        String[] parts = text.split(",");
        int[] rows = new int[parts.length];
        int count = 0;
        for (String part : parts) {
            try {
                rows[count] = Integer.parseInt(part.trim());
                count++;
            } catch (NumberFormatException skip) {
                // A malformed entry loses one fold, not the whole restore. Only reachable for a record
                // somebody hand-edited, since this is the one thing that writes the field.
            }
        }
        return java.util.Arrays.copyOf(rows, count);
    }
}
