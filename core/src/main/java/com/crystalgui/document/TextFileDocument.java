package com.crystalgui.document;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.notify.StatusBar;
import com.crystalgui.core.notify.StatusBarAlignment;
import com.crystalgui.core.notify.StatusBarEntry;
import com.crystalgui.core.notify.StatusBarEntryAccessor;
import com.crystalgui.text.LineEnding;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.diagnostic.DiagnosticSet;
import com.crystalgui.ui.box.Box;
import javax.annotation.Nullable;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.fs.Resource;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.texteditor.TextEditor;

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
        implements FileDocument, DocumentViewState, Disposable {

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
        // ASKED OF THE BUFFER, never detected from the text. The buffer is normalised to LF the moment it
        // loads, so detecting on the way out reports LF for every file in the workspace -- including the
        // CRLF one this readout exists to tell you about. The ending the file arrived with is a fact the
        // buffer remembers; there is nothing left in the text to detect.
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

    /** The BUFFER's set, reached through the editor — a document's problems belong to its document. */
    @Override
    public DiagnosticSet diagnostics() {
        return editor.diagnostics();
    }

    @Override
    public UIElement view() {
        return editor;
    }

    /**
     * The document, written back <b>in the ending it arrived with</b>.
     *
     * <h3>This was {@code editor.getText()}, and both halves of that were wrong</h3>
     *
     * <p>The buffer is always LF internally — every offset in the engine counts a break as one unit — and
     * {@code TextBuffer.textWithOriginalLineEndings()} has existed to put the original back since the
     * buffer did. <b>Nothing called it.</b> So a CRLF file saved as LF, silently converting every line of
     * somebody's file the first time they touched it.</p>
     *
     * <p>And the second half is worse, because it needs no save at all: dirtiness is this method's output
     * compared against the bytes read from disk, so a CRLF file was <b>dirty the moment it opened</b> —
     * asterisk on the tab, a prompt on close, and an offer to save a file nobody had edited.</p>
     */
    @Override
    public byte[] encode() {
        return editor.buffer().textWithOriginalLineEndings().getBytes(StandardCharsets.UTF_8);
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
        if (editor.scrollTop() > 0f) out.putFloat(SCROLL, editor.scrollTop());

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
        // A DOCUMENT IS RESTORED BEFORE IT IS SHOWN, so the editor routinely has no box here -- a
        // session comes back on the frame the workbench joins a document and nothing has been laid
        // out. The offset is the node's, not the box's, which is why there is a setter to fall back to.
        Box box = editor.box();
        if (scroll > 0f) {
            if (box != null) box.setScroll(editor.scrollLeft(), scroll);
            else editor.setScrollOffsets(editor.scrollLeft(), scroll);
        }
    }

    /**
     * Releases the parse tree and the engine when the document ends.
     *
     * <h3>Nothing did this, and the leak was invisible for the same reason all of these are</h3>
     *
     * <p>{@code SyntaxTokenizer.close()} has existed since the seam did, and the tree-sitter backend's
     * own test opens and closes a hundred documents to prove it releases natives. Nothing in the
     * application ever called it: {@code OpenDocuments.close} disposes a document that implements
     * {@link Disposable}, and this record did not — so a text document's parse tree, its query cursor and
     * its parser survived until the process ended. The graph editor was covered because it holds GPU
     * memory somebody noticed; a native parse tree is the same problem without a visible symptom.</p>
     *
     * <p><b>Here rather than on the widget</b>, because a widget's teardown is the wrong event: the dock
     * rebuilds every panel on every split and drag, so freeing the tree there would release it for a
     * document that is still open and rebuild it on the next frame. The document is what ends.</p>
     *
     * <p><b>What is still not covered</b>, stated because it would otherwise read as fixed: closing a tab
     * does not reach {@code OpenDocuments.close} yet — only deleting or moving the file does — so this
     * runs on the paths that exist today and will cover tab-close when the dock routes it.</p>
     */
    @Override
    public void dispose() {
        editor.disposeLanguage();
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
