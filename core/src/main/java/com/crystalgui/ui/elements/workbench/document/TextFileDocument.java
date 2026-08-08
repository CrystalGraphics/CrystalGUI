package com.crystalgui.ui.elements.workbench.document;

import com.crystalgui.core.notify.StatusBar;
import com.crystalgui.core.notify.StatusBarAlignment;
import com.crystalgui.core.notify.StatusBarEntry;
import com.crystalgui.core.notify.StatusBarEntryAccessor;
import com.crystalgui.text.LineEnding;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.diagnostic.DiagnosticSet;
import javax.annotation.Nullable;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.fs.Resource;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.editor.TextEditor;
import com.crystalgui.ui.elements.workbench.DocumentViewState;
import com.crystalgui.ui.elements.workbench.FileDocument;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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
public record TextFileDocument(TextEditor editor, Resource resource)
        implements FileDocument, DocumentViewState {

    /** Keys of the stored view state. Short because there is one entry per open file per session. */
    private static final String CARET = "caret";
    private static final String ANCHOR = "anchor";
    private static final String SCROLL = "scroll";
    private static final String FOLDS = "folds";

    /** Status-bar entry ids — what a "hide this entry" menu would name them by. */
    public static final String CARET_STATUS = "editor.caret";
    public static final String EOL_STATUS = "editor.lineEnding";
    public static final String ENCODING_STATUS = "editor.encoding";
    public static final String INDENT_STATUS = "editor.indent";

    /**
     * Where these four sit among each other, taken from VS Code's {@code editorStatus.ts}.
     *
     * <p>Higher is further left, so the right-hand group reads {@code 51:39  4 spaces  UTF-8  LF} — the
     * reference's own sequence. They used to render in whatever order the lines below happened to run in,
     * which is a layout decided by an implementation detail of this method.</p>
     */
    private static final int CARET_PRIORITY = 100;
    private static final int INDENT_PRIORITY = 99;
    private static final int ENCODING_PRIORITY = 98;
    private static final int EOL_PRIORITY = 97;

    /**
     * The active document's status entries — <b>static, for the same reason the caret subscription is.</b>
     *
     * <p>Held rather than looked up by id because an entry's lifetime is now its accessor: withdrawing is
     * {@code dispose()} on the handle, not a second call naming the string again. A missed
     * {@code setActive(false)} can only replace these, never leak them.</p>
     */
    private static final List<StatusBarEntryAccessor> ACTIVE_ENTRIES = new ArrayList<>();

    /** The one entry that is rewritten while the document stays active. */
    @Nullable
    private static StatusBarEntryAccessor caretEntry;

    /**
     * The active document's caret subscription — <b>static, because "active" is singular.</b>
     *
     * <p>A record cannot hold instance state, and this one deliberately holds none: its javadoc below
     * records that carrying anything beside the editor is how dirty-tracking drifted. But there is no
     * per-instance state to hold here either — exactly one document is in front at a time, which is the
     * same premise that makes {@code StatusBar} itself static. Two documents cannot both be active, so a
     * field per document would be a slot that is null for all but one of them.</p>
     *
     * <p>Reassigned by whichever document activates next, and the previous one is disconnected first, so
     * a missed {@code setActive(false)} cannot leak a subscription — it can only be replaced.</p>
     */
    @Nullable
    private static Connection caretSubscription;

    /**
     * Publishes this file's readouts while it is in front, and withdraws them when it is not.
     *
     * <p>The caret is the editor's own fact, and it is stated <b>here</b> rather than by the editor
     * because a {@code TextEditor} is reusable: a page holding three of them would have all three writing
     * one key, last mover winning. A document, by contrast, is exactly one tab.</p>
     */
    @Override
    public void setActive(boolean active) {
        if (caretSubscription != null) {
            caretSubscription.disconnect();
            caretSubscription = null;
        }
        for (StatusBarEntryAccessor entry : ACTIVE_ENTRIES) entry.dispose();
        ACTIVE_ENTRIES.clear();
        caretEntry = null;
        if (!active) return;

        caretSubscription = editor.onSelectionChanged.connect(this::writeCaret);
        caretEntry = add(caretEntry(), CARET_STATUS, CARET_PRIORITY);
        add(new StatusBarEntry("Indentation", editor.getTabSize() + " spaces",
                "Indentation: " + editor.getTabSize() + " spaces per level", null,
                StatusBarEntry.Kind.STANDARD), INDENT_STATUS, INDENT_PRIORITY);
        // UTF-8 is not a guess: this document reads and writes through it and nothing else, so naming any
        // other encoding would be reporting a setting that does not exist.
        add(new StatusBarEntry("File encoding", "UTF-8", "File encoding", null,
                StatusBarEntry.Kind.STANDARD), ENCODING_STATUS, ENCODING_PRIORITY);
        LineEnding ending = LineEnding.detect(editor.getText());
        add(new StatusBarEntry("Line separator", ending.name(),
                "Line separator: " + describe(ending), null,
                StatusBarEntry.Kind.STANDARD), EOL_STATUS, EOL_PRIORITY);
    }

    /** Registers one entry on the right-hand group and keeps it for withdrawal. */
    private static StatusBarEntryAccessor add(StatusBarEntry entry, String id, int priority) {
        StatusBarEntryAccessor accessor =
                StatusBar.addEntry(entry, id, StatusBarAlignment.RIGHT, priority);
        ACTIVE_ENTRIES.add(accessor);
        return accessor;
    }

    /** IntelliJ's {@code 111:32} — one-based, as every gutter and every error message already is. */
    private void writeCaret() {
        if (caretEntry != null) caretEntry.update(caretEntry());
    }

    private StatusBarEntry caretEntry() {
        TextPoint caret = editor.caretPoint();
        return new StatusBarEntry("Cursor position",
                (caret.row() + 1) + ":" + (caret.column() + 1),
                "Line and column of the caret", null, StatusBarEntry.Kind.STANDARD);
    }

    /** The name is the readout; this is the thing the letters stand for. */
    private static String describe(LineEnding ending) {
        return ending == LineEnding.CRLF ? "CRLF (Windows)" : "LF (Unix and macOS)";
    }

    /** The editor's own set — a text document's problems are the editor's to keep. */
    @Override
    public DiagnosticSet diagnostics() {
        return editor.diagnostics();
    }

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
