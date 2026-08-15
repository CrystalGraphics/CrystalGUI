package com.crystalgui.language.run;

import com.crystalgui.core.signal.Connection;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.text.Rope;
import com.crystalgui.text.syntax.SyntaxToken;
import com.crystalgui.text.syntax.SyntaxTokenizer;
import com.crystalgui.ui.elements.editor.TextEditor;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The console surface — a {@link TextEditor} configured to be read-only output.
 *
 * <h3>Repurposed rather than built, and it was not close</h3>
 *
 * <p>A console needs character-level selection across lines, drag-selection, copy of exactly what was
 * dragged, virtualised rendering of a document that may be a megabyte, and scrolling. {@code TextEditor}
 * has all of it — the mouse selection ported from VS Code, the clipboard actions, the realised-line
 * rendering — so writing a text area for this would reimplement the one thing this repository's rules
 * most explicitly refuse to reimplement, with the thing to port from sitting in the next package.</p>
 *
 * <h3>A controller BESIDE a TextEditor, never a subclass of one</h3>
 *
 * <p>This was {@code extends TextEditor} first, and it rendered a blank panel. <b>A widget's cascade
 * identity is its tag, and a tag is the lowercased class name — never the Java supertype.</b> So the
 * subclass answered to {@code runconsoleview} and not one of the twelve {@code texteditor} rules in the
 * user-agent sheet reached it: no background, no monospace family, no line height, no caret and no
 * selection colour. The same trap {@code Dropdown extends Button} paid for, where the fix was to name the
 * new tag in the sheet — not available here, because that would put a {@code language/} widget's name
 * permanently into {@code core}'s stylesheet, and again for every later subclass.</p>
 *
 * <p>Holding a plain {@code TextEditor} makes the tag {@code texteditor} and every rule apply for free.
 * It also removes a second fault that had no symptom of its own: {@code TextEditor} already implements
 * {@link com.crystalgui.ui.UIFrameTicker} and registers itself, so an override that forgot to call
 * {@code super.tickFrame} silently replaced the editor's own per-frame work.</p>
 *
 * <h3>Colour goes through the tokenizer seam</h3>
 *
 * <p>Not a second colour path. {@link SyntaxTokenizer} answers tokens in document offsets over the range
 * the editor actually realised, so a console tokenizer reads {@link RunConsole}'s per-line level and
 * names a capture per line — and stderr, warnings and run boundaries are then coloured by the same
 * {@code .__syntax__::highlight()} rules and the same editor colour scheme as code. A per-row colour of
 * its own would give the console a palette that drifts from the squiggles describing the same run.</p>
 */
public final class RunConsoleView {

    public static final String CONSOLE_CLASS = "__run-console__";

    /** Fired when a line with somewhere to go is activated. */
    public final Signal.Value<RunConsole.Line> onLineActivated = new Signal.Value<>();

    private final TextEditor editor = new TextEditor("");

    @Nullable private RunConsole console;
    @Nullable private Connection watch;

    /** Set from whatever thread appended, read and cleared on the UI thread. */
    private volatile boolean pending;

    public RunConsoleView() {
        editor.addClass(CONSOLE_CLASS);
        // READ-ONLY, WHICH IS NOT THE SAME AS UNSELECTABLE -- and is the whole reason a text area was the
        // right shape. Selection, drag-selection and copy all remain; only editing is refused.
        editor.setReadOnly(true);
        // NO GUTTER. Output has no line numbers to speak of: a console's line 400 is not a place, it is
        // just how much has scrolled past, and IntelliJ shows none.
        editor.setGutterVisible(false);
        editor.setTokenizer(new Levels());
        // NO SCROLLING PAST THE LAST LINE. On by default because a FILE's last line deserves to be read
        // somewhere other than jammed against the bottom edge -- but a console's last line is the newest
        // output, which is the one thing that is always about to be replaced. A viewport of empty space
        // under it means the tail-follow parks the transcript at the top of an empty screen, and a reader
        // who scrolls down finds nothing there. Neither IntelliJ's console nor VS Code's terminal does it.
        editor.setScrollBeyondLastLine(false);
    }

    /** The element to put in a tree. */
    public TextEditor element() {
        return editor;
    }

    /** Shows {@code console}, taking ownership of what it writes into. */
    public RunConsoleView bindTo(@Nullable RunConsole console) {
        if (watch != null) {
            watch.disconnect();
            watch = null;
        }
        this.console = console;
        if (console != null) {
            console.attach(editor.buffer());
            // ONLY A FLAG, and it must be: this fires from whatever thread printed, which may not touch
            // a document at all. @see RunConsole#onDidChange
            watch = console.onDidChange.connect(() -> pending = true);
        }
        this.pending = true;
        return this;
    }

    @Nullable
    public RunConsole console() {
        return console;
    }

    /**
     * Writes whatever has been printed since the last frame. <b>UI thread only.</b>
     *
     * <p>Driven by {@link RunPanel}'s ticker rather than one of this class's own, now that this is not a
     * widget. One ticker is also the honest arrangement: the transcript and the eviction notice are two
     * views of the same drain, and running them from separate ticks let the notice describe a document
     * the reader had not been shown yet.</p>
     */
    public boolean drain() {
        RunConsole showing = console;
        if (!pending || showing == null) return false;
        pending = false;
        boolean wasAtTail = isAtTail();
        boolean changed = showing.drain();
        if (changed && wasAtTail) scrollToTail();
        return changed;
    }

    /**
     * Whether the newest line is on screen — asked <b>before</b> the drain.
     *
     * <p>A console that always jumps to the bottom cannot be read while anything is running, and one
     * that never does is not a console. Both references resolve it the same way: follow while the tail is
     * visible, and stop the moment the reader scrolls away. Asked first because afterwards the document
     * is longer and the question can no longer be answered.</p>
     */
    private boolean isAtTail() {
        float max = editor.getMaxScrollTop();
        float top = editor.getScrollTop();
        // A NON-FINITE OFFSET IS TREATED AS THE TOP, never propagated. TextEditor.getScrollTop() can be
        // NaN, and NaN loses every comparison -- so following the tail would simply stop, with nothing to
        // look at that says why.
        if (!Float.isFinite(top) || !Float.isFinite(max)) return true;
        return max <= 0f || top >= max - 1f;
    }

    private void scrollToTail() {
        float max = editor.getMaxScrollTop();
        if (!Float.isFinite(max)) return;
        editor.setScrollImmediate(editor.getScrollLeft(), max);
    }

    /**
     * A line's colour, by where it came from.
     *
     * <p>Capture names from §10.1's vocabulary rather than invented ones, so every shipped scheme already
     * has a colour for them and a console never needs a palette of its own.</p>
     */
    private final class Levels implements SyntaxTokenizer {

        @Override
        public List<SyntaxToken> tokenize(Rope document, int from, int to) {
            RunConsole showing = console;
            if (showing == null || document == null) return List.of();

            List<SyntaxToken> tokens = new ArrayList<>();
            int firstRow = document.offsetToPoint(clamp(document, from)).row();
            for (int row = firstRow; row < document.lineCount(); row++) {
                int start = document.lineStartOffset(row);
                if (start >= to) break;
                int end = document.lineEndOffset(row);
                RunConsole.Line line = showing.lineAt(row);
                String capture = captureFor(line);
                // ORDINARY OUTPUT IS LEFT ALONE, not given a capture meaning "normal". An uncaptured
                // span takes the surface's own foreground, which is what plain output is -- and naming
                // it would put every line of every transcript through the cascade for nothing.
                if (capture != null && end > start) {
                    tokens.add(new SyntaxToken(start, end, capture));
                }
            }
            return tokens;
        }

        @Nullable
        private String captureFor(@Nullable RunConsole.Line line) {
            if (line == null) return null;
            if (line.isDivider()) return "comment";
            switch (line.level()) {
                case ERROR: return "diagnostic.error";
                case WARN: return "diagnostic.warning";
                default: return null;
            }
        }

        private int clamp(Rope document, int offset) {
            return Math.max(0, Math.min(offset, document.length()));
        }
    }
}
