package com.crystalgui.workbench.editor;

import com.crystalgui.core.notify.StatusBar;
import com.crystalgui.core.notify.StatusBarAlignment;
import com.crystalgui.core.notify.StatusBarEntry;
import com.crystalgui.core.notify.StatusBarEntryAccessor;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.document.DocumentEditor;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.text.LineEnding;
import com.crystalgui.text.TextPoint;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.texteditor.TextEditor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.jetbrains.annotations.Nullable;

/**
 * A {@link TextEditor} as a view onto a text document.
 *
 * <p>Deliberately a wrapper rather than {@code TextEditor implements DocumentEditor}: the editor is a
 * widget that knows nothing about documents, saving or workspaces, and is used in places with no
 * document behind them at all — a harness scene, a settings page's code sample. Making it implement a
 * document interface would give every one of those a {@code writeViewState} nobody calls.</p>
 *
 * <h3>Several of these may exist for one document</h3>
 *
 * <p>Two split panes are two of these over one {@code TextBuffer}, which is what makes a keystroke in
 * either one edit with one undo history. Each holds its own caret, scroll and folds — the document/view
 * boundary the engine already draws for undo.</p>
 */
public final class TextEditorView implements DocumentEditor {

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
     * reference's own sequence. They used to render in whatever order the lines below happened to run
     * in, which is a layout decided by an implementation detail of one method.</p>
     */
    private static final int CARET_PRIORITY = 100;
    private static final int INDENT_PRIORITY = 99;
    private static final int ENCODING_PRIORITY = 98;
    private static final int EOL_PRIORITY = 97;

    /**
     * The active view's status entries — <b>static, because "active" is singular.</b>
     *
     * <p>Exactly one view is in front at a time, which is the same premise that makes {@code StatusBar}
     * itself static. Held rather than looked up by id because an entry's lifetime is its accessor:
     * withdrawing is {@code dispose()} on the handle, not a second call naming the string again. A
     * missed deactivation can only replace these, never leak them.</p>
     */
    private static final List<StatusBarEntryAccessor> ACTIVE_ENTRIES = new ArrayList<>();

    /** The one entry that is rewritten while the view stays active. */
    @Nullable
    private static StatusBarEntryAccessor caretEntry;

    /** The active view's caret subscription. Static for the reason above; replaced, never accumulated. */
    @Nullable
    private static Connection caretSubscription;

    private final TextEditor editor;

    public TextEditorView(TextEditor editor) {
        this.editor = editor;
    }

    @Override
    public UIElement view() {
        return editor;
    }

    public TextEditor editor() {
        return editor;
    }

    // ── The status readouts ─────────────────────────────────────────────────────────────────────

    /**
     * Publishes this file's readouts while it is in front, and withdraws them when it is not.
     *
     * <p>The caret is the editor's own fact, and it is stated <b>here</b> rather than by the editor
     * because a {@code TextEditor} is reusable: a page holding three of them would have all three
     * writing one key, last mover winning. A view of a document, by contrast, is exactly one tab.</p>
     */
    @Override
    public void activated(boolean active) {
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
        // ASKED OF THE BUFFER, never detected from the text -- for both of these. The buffer is
        // normalised to LF the moment it loads and its charset is sniffed on the way in, so detecting on
        // the way out reports LF and UTF-8 for every file in the workspace, including the CRLF one and
        // the UTF-16 one these readouts exist to tell you about.
        add(new StatusBarEntry("File encoding", editor.buffer().encoding().toString(),
                "File encoding", null, StatusBarEntry.Kind.STANDARD),
                ENCODING_STATUS, ENCODING_PRIORITY);
        LineEnding ending = editor.buffer().lineEnding();
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

    // ── Where you were looking ──────────────────────────────────────────────────────────────────

    @Override
    public <T> void writeViewState(StateMap<T> out) {
        out.putInt(CARET, editor.getCaret());
        // The anchor as well as the head, so a selection comes back as a selection rather than as a
        // collapsed caret at one of its ends. Omitted when they are equal -- most files have no
        // selection, and a session record is written on every close.
        int anchor = editor.getSelectionStart() == editor.getCaret()
                ? editor.getSelectionEnd() : editor.getSelectionStart();
        if (anchor != editor.getCaret()) out.putInt(ANCHOR, anchor);
        if (editor.scrollTop() > 0f) out.putFloat(SCROLL, editor.scrollTop());

        int[] folds = editor.collapsedRows();
        if (folds.length > 0) out.putString(FOLDS, join(folds));
    }

    /**
     * <p>Order matters and is not arbitrary: <b>caret, then folds, then scroll.</b> Folding runs a lift
     * that moves a caret out of a row it is about to hide, so setting the caret first lets that
     * machinery handle a stale record whose block no longer contains the position it did. Scroll goes
     * last because collapsing rows changes the projection every scroll offset is measured against.</p>
     */
    @Override
    public <T> void readViewState(StateMap<T> in) {
        int length = editor.buffer().length();
        int caret = Math.max(0, Math.min(length, in.getInt(CARET, 0)));
        int anchor = Math.max(0, Math.min(length, in.getInt(ANCHOR, caret)));
        if (anchor == caret) editor.setCaret(caret);
        else editor.setSelection(anchor, caret);

        String folds = in.getString(FOLDS, "");
        if (!folds.isEmpty()) editor.setCollapsedRows(split(folds));

        float scroll = in.getFloat(SCROLL, 0f);
        // A DOCUMENT IS RESTORED BEFORE IT IS SHOWN, so the editor routinely has no box here -- a
        // session comes back on the frame the workbench joins a document and nothing has been laid out.
        // The offset is the node's, not the box's, which is why there is a setter to fall back to.
        Box box = editor.box();
        if (scroll > 0f) {
            if (box != null) box.setScroll(editor.scrollLeft(), scroll);
            else editor.setScrollOffsets(editor.scrollLeft(), scroll);
        }
    }

    /**
     * Releases what this VIEW owns, which is nothing.
     *
     * <p>The parse tree, the language engine and the buffer belong to {@code TextDocumentModel} and are
     * released when the last reference to the document goes. Freeing them here would release them on
     * every split and drag, for a document that is still open — the "Parser is closed" defect.</p>
     */
    @Override
    public void disposeView() {
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
        return Arrays.copyOf(rows, count);
    }
}
