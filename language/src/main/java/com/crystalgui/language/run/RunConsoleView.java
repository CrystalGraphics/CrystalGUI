package com.crystalgui.language.run;

import com.crystalgui.core.signal.Connection;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.text.Rope;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.syntax.SyntaxToken;
import com.crystalgui.text.syntax.SyntaxTokenizer;
import com.crystalgui.ui.elements.editor.TextEditor;
import com.crystalgui.ui.event.MouseEvent;

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

    /**
     * The capture a navigable span is painted with.
     *
     * <p>A new name, so it must be given a rule in the shipped scheme in the same edit — a capture with no
     * rule is not an error, it simply takes the surface's own foreground, which looks exactly like the
     * links not working.</p>
     */
    public static final String LINK_CAPTURE = "link";

    /**
     * On the console while the pointer is over a navigable span — what turns the cursor into a pointer.
     *
     * <p>A class rather than a {@code cursor} written from Java, for two reasons that agree. The house
     * rule puts appearance in the sheet; and {@code CgCursor} lives in CrystalGraphics' platform module,
     * which {@code core} takes as {@code compileOnly} and therefore does not pass on — so {@code language/}
     * cannot name the type at all. The constraint and the convention point the same way.</p>
     */
    public static final String OVER_LINK_CLASS = "__over-link__";

    /**
     * Fired when a navigable span is clicked — the line it was on, and the span itself.
     *
     * <p>Both, because resolving a bare {@code RunTest.java} to a workspace file is easiest with the
     * line's own origin as the first candidate: a trace printed by a script very often names the script.
     * The consumer is whoever has a workspace; see {@code RunPanels}.</p>
     */
    public final Signal.Pair<RunConsole.Line, ConsoleFilter.Link> onLinkActivated = new Signal.Pair<>();

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
        installLinkHandling();
    }

    // ── Links ────────────────────────────────────────────────────────────────────────────────────────


    /** Whether the pointer is currently over a link, so the cursor is written only when it changes. */
    private boolean pointerOverLink;

    /**
     * Makes navigable spans clickable, and says so under the pointer.
     *
     * <h4>Follow on the RELEASE, not the press</h4>
     *
     * <p>A press in a console also places the caret and may begin a selection, and the editor's own
     * handler runs on the same event. Firing on the down steals a drag that had barely started — the same
     * rule a browser applies to a link, and for the same reason. So the release navigates only when it
     * lands on the offset the press did, which is exactly "the pointer did not move".</p>
     *
     * <p>No modifier. IntelliJ requires one in the <em>editor</em>, where a plain click is how you put the
     * caret somewhere you are about to type; a console is read-only, so there is nothing for a plain click
     * to conflict with, and its console follows a bare click too.</p>
     */
    private void installLinkHandling() {
        // ON THE RELEASE, AND ONLY THE RELEASE -- not because that is tidier but because THE PRESS IS
        // UNREACHABLE.
        //
        // TextEditor's own MouseEvent.Down handler ends with an unconditional stopPropagation(), and in
        // this engine that is DOM's stopIMMEDIATEPropagation: EventListenerGroup emits through
        // `continueEmittingUnderCondition(..., UIEvent::isPropagationStopped)`, so it halts the remaining
        // listeners ON THE SAME ELEMENT AND PHASE rather than merely the walk to the next element. The
        // editor subscribes from its own constructor and therefore always runs first, so nothing attached
        // to its Down afterwards can ever run. Its Up handler does not stop propagation, which is the only
        // reason this works at all.
        //
        // The symptom was precise and misleading: the caret moved and double-click selected a word --
        // every sign that events were arriving -- while the press handler recorded nothing, so the release
        // had no press to match and refused silently. Two rounds went to the offsets before the phase.
        editor.onMouseUp.attachListener((element, event) -> {
            // A DRAG IS A SELECTION GESTURE, and navigating away from text somebody just selected is the
            // opposite of what they asked for. This is also what makes a double-click safe -- it selects
            // the word, so it is refused, and a link follows a SINGLE click as IntelliJ's console does.
            //
            // Asked of the selection rather than compared against the press offset, which is both
            // unavailable (above) and wrong: the editor reveals the caret on press and this console
            // scrolls smoothly, so the same screen point resolves to a different offset either side of it.
            if (editor.selections().hasSelection()) return;
            int offset = offsetOf(event);
            ConsoleFilter.Link link = linkAt(offset);
            if (link != null) activate(link, offset);
        }, false, true);
        // THE ONLY THING HOVER CHANGES. The underline is permanent, as IntelliJ's console hyperlinks are,
        // because the token cache is per row and cleared wholesale -- restyling one span on hover would
        // discard every realised row's tokens on every pointer move.
        editor.onMouseMove.attachListener(
                (element, event) -> setPointerOverLink(linkAt(offsetOf(event)) != null), false, true);
        // TARGET PHASE IS RIGHT HERE. Leave does not bubble, but it is dispatched to every element in the
        // chain being left, so the editor receives its own.
        editor.onMouseLeave.attachListener((element, event) -> setPointerOverLink(false), false, false);
    }

    private int offsetOf(MouseEvent event) {
        return editor.offsetAt(event.getPosition().x(), event.getPosition().y());
    }

    private void setPointerOverLink(boolean over) {
        if (over == pointerOverLink) return;
        pointerOverLink = over;
        if (over) editor.addClass(OVER_LINK_CLASS);
        else editor.removeClass(OVER_LINK_CLASS);
    }

    private void activate(ConsoleFilter.Link link, int offset) {
        RunConsole showing = console;
        if (showing == null) return;
        int row = editor.buffer().offsetToPoint(offset).row();
        RunConsole.Line line = showing.lineAt(row);
        if (line != null) onLinkActivated.emit(line, link);
    }

    /** The navigable span under a document offset, or null. */
    @Nullable
    private ConsoleFilter.Link linkAt(int offset) {
        RunConsole showing = console;
        if (showing == null || offset < 0) return null;
        TextBuffer buffer = editor.buffer();
        if (offset > buffer.length()) return null;
        int row = buffer.offsetToPoint(offset).row();
        // COLUMN, because a filter answers in the line's own coordinates -- it was handed a string, not a
        // document, which is what keeps it testable and keeps eviction out of it.
        int column = offset - buffer.document().lineStartOffset(row);
        for (ConsoleFilter.Link link : showing.linksAt(row)) {
            if (link.contains(column)) return link;
        }
        return null;
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
        if (changed) {
            // THE DOCUMENT WAS WRITTEN BEHIND THE EDITOR'S BACK, and a filter change rewrites all of it.
            // The editor's own early-out compares the visible OFFSET RANGE, which a wholesale rebuild can
            // leave identical over completely different text -- so every realised row keeps the previous
            // transcript's ranges, and nothing dirties them again. Ten link ranges published, one still
            // painted a character short over the wrong word, and it never corrected itself.
            editor.invalidateHighlights();
        }
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
                if (end <= start) continue;
                emitRow(tokens, showing, row, start, end);
            }
            return tokens;
        }

        /**
         * One row's tokens: its level, <b>split around</b> any navigable spans.
         *
         * <p>Never overlapping, and that is the whole reason this is not two loops. Two tokens covering
         * the same characters under unrelated names leave the winner to paint order, and both names
         * resolve to real colours — so the wrong one reads as a broken colour scheme rather than as an
         * ordering mistake. That is exactly how the semantic-over-grammar precedence bug cost two
         * rounds. A stderr line containing a frame is three tokens, not two that intersect.</p>
         */
        private void emitRow(List<SyntaxToken> tokens, RunConsole showing, int row, int start, int end) {
            RunConsole.Line line = showing.lineAt(row);
            String capture = captureFor(line);
            List<ConsoleFilter.Link> links = showing.linksAt(row);

            if (links.isEmpty()) {
                // ORDINARY OUTPUT IS LEFT ALONE, not given a capture meaning "normal". An uncaptured
                // span takes the surface's own foreground, which is what plain output is -- and naming
                // it would put every line of every transcript through the cascade for nothing.
                if (capture != null) tokens.add(new SyntaxToken(start, end, capture));
                return;
            }

            int cursor = start;
            for (ConsoleFilter.Link link : links) {
                int linkStart = Math.min(end, start + link.start());
                int linkEnd = Math.min(end, start + link.end());
                // OVERLAPPING FILTERS ARE THE CALLER'S BUSINESS, not a crash. Two chains that both claim a
                // span would otherwise emit a reversed token; the first claim simply wins.
                if (linkEnd <= cursor) continue;
                if (capture != null && linkStart > cursor) {
                    tokens.add(new SyntaxToken(cursor, linkStart, capture));
                }
                tokens.add(new SyntaxToken(Math.max(cursor, linkStart), linkEnd, LINK_CAPTURE));
                cursor = linkEnd;
            }
            if (capture != null && cursor < end) tokens.add(new SyntaxToken(cursor, end, capture));
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
