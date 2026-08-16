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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
     * The capture a run's opening line is painted with.
     *
     * <p>Its own name rather than one of §10.1's, and scoped to the console in the sheet like
     * {@link #LINK_CAPTURE} is, because no colour scheme has an opinion about a run boundary — it is a
     * console affordance rather than a category of code. A rule must be added in the same edit as the
     * name: a capture with none is not an error, it simply takes the surface's own foreground, which
     * looks exactly like the boundary not being marked at all.</p>
     */
    public static final String RUN_START_CAPTURE = "run.start";

    /**
     * The capture an echoed input line is painted with.
     *
     * <p>Scoped to the console like the two above, and for the same reason: no colour scheme has an
     * opinion about a line the reader typed, because no editor has such a line.</p>
     */
    public static final String RUN_INPUT_CAPTURE = "run.input";

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

    /**
     * The filter the document on screen was built from, so a change to it can be noticed.
     *
     * <p>{@code RunConsole.drain()} answers whether the document changed, not <em>why</em> — and a filter
     * change is the one kind of change the view has to treat differently from output arriving. @see #drain
     */
    @Nullable private String shownFilter;

    /**
     * Where each tab was left, and whether it was following — <b>per filter, not per console</b>.
     *
     * <p>There is one editor and one scroll offset behind every rail row, so switching rows used to hand
     * the new tab whatever number the old one happened to be sitting at. Going to the tail every time was
     * the first fix and it is not right either: leave a long transcript half way up to read something,
     * glance at another script, come back, and you are at the bottom with no way to find your place. Each
     * tab owns its own position, which is what IntelliJ gets for free by giving each run its own
     * console.</p>
     *
     * <p>The <b>follow</b> flag rides along because it is part of where you were: a tab left at the tail
     * should keep being pulled down by new output, and one left half way up should not.</p>
     */
    private final Map<String, Place> places = new HashMap<>();

    /** One tab's place. */
    private record Place(float top, boolean following) {
    }

    /**
     * The key for <em>All output</em>, whose filter is null.
     *
     * <p>The empty string, because no script can be called that — a file name is never empty, so there is
     * nothing for it to collide with. A control character would do as well and would have to survive
     * every editor and diff between here and whoever reads it next.</p>
     */
    private static final String ALL_OUTPUT = "";

    /**
     * A place waiting to be applied, and why it is not simply written once.
     *
     * <p>A filter change rebuilds the document, and the editor cannot report a maximum scroll for a
     * document it has not laid out yet — so the write in the same frame is against a stale measurement
     * and can be clamped to the wrong number. Keeping the request until the offset it asked for is the
     * offset the editor holds is what makes it land, and it costs one comparison per frame afterwards.</p>
     */
    @Nullable private Place restoring;

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
            int offset = offsetOf(event);
            ConsoleFilter.Link link = linkAt(offset);
            if (link != null) {
                // A MARKED SPAN FOLLOWS A SINGLE CLICK, as IntelliJ's console hyperlinks do -- it is
                // underlined, so the click is asking for exactly what it looks like it will get.
                //
                // A DRAG IS A SELECTION GESTURE, though, and navigating away from text somebody just
                // selected is the opposite of what they asked for. Asked of the selection rather than
                // compared against the press offset, which is both unavailable (above) and wrong: the
                // editor reveals the caret on press and this console scrolls smoothly, so the same
                // screen point resolves to a different offset either side of it.
                if (!editor.selections().hasSelection()) activate(link, offset);
                return;
            }
            // AND UNMARKED TEXT NEEDS A DELIBERATE ONE. @see #activateOrigin
            if (event.getDetail() >= 2) activateOrigin(offset);
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

    /**
     * Opens the line of the script that <em>printed</em> this row — Unity's console gesture.
     *
     * <h4>The data was already there and nothing was asking for it</h4>
     *
     * <p>{@code ScriptOutput} walks the stack for every line to find the script's own deepest frame, so
     * every row a script printed already knows which of its lines caused it. Collapsing used to consume
     * that and went with the list, after which the walk was paid for on every {@code println} and read by
     * nothing but a redundant fallback in link resolution. This is the other end of that: the question
     * "which line printed this?" is the first one anybody asks of a scripting console, and Unity answers
     * it by double-clicking the entry.</p>
     *
     * <h4>Why a double-click, and why only here</h4>
     *
     * <p>A single click cannot have it: this is <b>unmarked</b> text — there is no underline, so a click
     * that navigated would be a click doing something the row never offered. A modifier would be the
     * usual answer and is unavailable: {@code MouseEvent} carries no modifier state and {@code
     * CgModifiers} lives in CrystalGraphics' platform module, which {@code core} takes as
     * {@code compileOnly} and does not pass on to {@code language/}.</p>
     *
     * <p>So it is restricted instead. It fires only on a row with an origin — output the user's own
     * script printed — which excludes engine output, run boundaries, and every stack frame (those carry a
     * {@link ConsoleFilter.Link} and are handled above, on a single click). Double-clicking still selects
     * the word as it always did; the jump is additional, exactly as it is in Unity.</p>
     */
    private void activateOrigin(int offset) {
        RunConsole showing = console;
        if (showing == null || offset < 0) return;
        TextBuffer buffer = editor.buffer();
        if (offset > buffer.length()) return;
        RunConsole.Line line = showing.lineAt(buffer.offsetToPoint(offset).row());
        if (line == null || !line.isNavigable()) return;

        // A SPAN OVER THE WHOLE ROW, so the one consumer -- which resolves a name to a workspace file and
        // opens it at a line -- needs no second entry point and no notion of "a link that is not really
        // a link". `Link` refuses an empty span, and a blank row is a real possibility.
        int end = Math.max(1, line.text().length());
        onLinkActivated.emit(line,
                new ConsoleFilter.Link(0, end, line.file().name(), line.line()));
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
        shownFilter = console == null ? null : console.filter();
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
        // SAMPLED FIRST, EVERY FRAME, whether or not anything arrived -- this is where the reader's own
        // position is read, and it has to happen before the document grows or the question can no longer
        // be answered.
        updateFollow();
        // A RESTORE OUTLIVES THE FRAME THAT ASKED FOR IT. @see #restoring
        if (restoring != null) {
            applyRestore();
            return false;
        }
        if (!pending || showing == null) {
            // AND THE LOCK IS ENFORCED EVEN ON AN IDLE FRAME. The layout can settle a frame or more after
            // the text lands: the panel is opened by the Run command and the first drain happens before
            // the editor has been measured at all, so the tail it was sent to was offset zero. Scrolling
            // only when something arrives means that first burst is never corrected -- the console opens
            // at the top and stays there, because by the next line the reader is "not at the tail".
            if (follow.isFollowing()) scrollToTail();
            return false;
        }
        pending = false;
        // WHERE THIS TAB WAS, read BEFORE the drain -- once the filter has been applied the offset on
        // screen belongs to a document that is already gone.
        String leaving = showing.filter();
        float leavingTop = editor.getScrollTop();
        boolean leavingFollow = follow.isFollowing();

        boolean changed = showing.drain();

        // READ AFTER THE DRAIN, which is where a filter change is actually applied.
        //
        // EACH TAB OWNS ITS OWN PLACE. There is one editor and one scroll offset behind every rail row,
        // so switching rows handed the new tab whatever number the old one was sitting at -- and since a
        // filtered document is nearly always SHORTER, that number was usually past its end and the
        // console showed an empty band. Going to the tail every time was the first fix and is not right
        // either: leave a long transcript half way up, glance at another script, come back, and your
        // place is gone. A tab nobody has visited has no place to return to, and for a console the
        // sensible first sight is the newest output. @see #places
        String applied = showing.filter();
        if (!Objects.equals(applied, shownFilter)) {
            shownFilter = applied;
            if (changed) editor.invalidateHighlights();
            if (Float.isFinite(leavingTop)) {
                places.put(key(leaving), new Place(leavingTop, leavingFollow));
            }
            Place remembered = places.get(key(applied));
            if (remembered == null) {
                scrollToEnd();
            } else {
                restoring = remembered;
                applyRestore();
            }
            return changed;
        }
        if (changed) {
            // THE DOCUMENT WAS WRITTEN BEHIND THE EDITOR'S BACK, and a filter change rewrites all of it.
            // The editor's own early-out compares the visible OFFSET RANGE, which a wholesale rebuild can
            // leave identical over completely different text -- so every realised row keeps the previous
            // transcript's ranges, and nothing dirties them again. Ten link ranges published, one still
            // painted a character short over the wrong word, and it never corrected itself.
            editor.invalidateHighlights();
        }
        if (follow.isFollowing()) scrollToTail();
        return changed;
    }

    /**
     * The scroll lock — extracted, because it was wrong twice and neither version was testable here.
     *
     * @see TailFollow
     */
    private final TailFollow follow = new TailFollow();

    private static String key(@Nullable String filter) {
        return filter == null ? ALL_OUTPUT : filter;
    }

    /**
     * Puts a tab back where it was, retrying until the offset it asked for is the offset it got.
     *
     * <p>The editor cannot report a maximum scroll for a document it has not laid out, so the write in
     * the frame that switched tabs is against a stale measurement and can be clamped to the wrong number.
     * One comparison per frame until it lands is cheaper than a wrong position that never corrects.</p>
     *
     * <p>Clamped to the maximum rather than refused past it: a tab whose transcript has since been
     * evicted or filtered down cannot go back to a place that no longer exists, and the end of what is
     * left is the honest substitute for it.</p>
     */
    private void applyRestore() {
        Place place = restoring;
        if (place == null) return;
        float max = editor.getMaxScrollTop();
        if (!Float.isFinite(max)) return;

        float target = Math.max(0f, Math.min(place.top(), max));
        editor.setScrollImmediate(editor.getScrollLeft(), target);
        // THE LOCK GOES BACK TOO. A tab left at the tail should keep being pulled down by new output and
        // one left half way up should not -- restoring the position without the lock would drag the
        // reader to the bottom of the very transcript they had scrolled up in, on its next line.
        follow.applied(target);
        if (place.following()) follow.rearm();
        else follow.release();

        float now = editor.getScrollTop();
        if (Float.isFinite(now) && Math.abs(now - target) <= 0.5f) restoring = null;
    }

    /** Reads the reader's position into the lock. Must run before anything grows the document. */
    private void updateFollow() {
        follow.sample(editor.getScrollTop(), editor.getMaxScrollTop());
    }

    /**
     * Jumps to the newest line — and thereby re-arms the tail follow.
     *
     * <p>Following stops the moment a reader scrolls away, which is what makes a console readable while
     * something is writing to it. This is the way back: there is otherwise no gesture that says "I have
     * finished reading, resume". IntelliJ's console has the same button for the same reason.</p>
     */
    public void scrollToEnd() {
        // RE-ARMS, and that is most of what the button is for. Reaching the bottom by dragging re-arms it
        // too -- `updateFollow` sees the position and latches -- but a reader who has scrolled far up a
        // long transcript has no gesture that means "resume" without travelling the whole way back.
        follow.rearm();
        scrollToTail();
    }

    /**
     * Whether new output will pull the view down with it.
     *
     * <p>Exposed because it is the only honest answer to a question the reader has constantly and the
     * panel could not previously show: <em>if I leave this open, will I keep seeing the newest line?</em>
     * The lock arms and disarms from scrolling rather than from any button, so without this the Scroll to
     * End control is a verb with no state — and a reader who has scrolled up has no way to tell whether
     * they have stopped following or the script has stopped printing.</p>
     */
    public boolean isFollowingTail() {
        return follow.isFollowing();
    }

    /** Whether the transcript wraps long lines. */
    public boolean isSoftWrap() {
        return editor.isSoftWrap();
    }

    public RunConsoleView setSoftWrap(boolean value) {
        editor.setSoftWrap(value);
        return this;
    }

    private void scrollToTail() {
        float max = editor.getMaxScrollTop();
        // An unmeasured viewport reports zero, and "scrolling to the tail" of a box that has not been laid
        // out yet puts the view at the TOP -- which is the shape of the original bug. Refusing leaves the
        // lock armed for a later frame that can actually answer.
        if (!Float.isFinite(max) || max <= 0f) return;
        // Recorded whether or not the set below is needed, because this is the value the lock compares
        // against: skipping it while skipping the write would leave a stale mark and read the next frame
        // as a reader gesture.
        follow.applied(max);
        float top = editor.getScrollTop();
        // Only when it would move -- this runs on every frame the lock is armed, and a setter called
        // sixty times a second with the value it already holds is worth not paying for.
        //
        // BUT "AT THE TAIL" IS AN EQUALITY, NOT A FLOOR. This read `top >= max - 0.5f`, which is true of
        // every offset PAST the end as well as of the end itself -- and switching to a shorter transcript
        // leaves exactly that: the offset survives the rebuild, the new maximum is smaller, and the view
        // is parked below the last line. The one frame that could have corrected it concluded it was
        // already there and wrote nothing, so the console showed an empty band until something else
        // scrolled. The scroll bug that outlived two attempts at this method was this comparison.
        if (Float.isFinite(top) && Math.abs(top - max) <= 0.5f) return;
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

            // THE STAMP IS NOT PART OF THE LINE. It belongs to the console rather than to the script, so
            // a line's colour begins after it -- an echoed input line painted its own timestamp green and
            // italic along with the words, and an error would have painted one red. Left UNCAPTURED
            // rather than given a colour of its own: it takes the surface's foreground, which is what it
            // already looked like, and one capture per row is enough. @see RunConsole#stampWidth
            int from = Math.min(end, start + showing.stampWidth(row));
            if (from >= end) return;

            if (links.isEmpty()) {
                // ORDINARY OUTPUT IS LEFT ALONE, not given a capture meaning "normal". An uncaptured
                // span takes the surface's own foreground, which is what plain output is -- and naming
                // it would put every line of every transcript through the cascade for nothing.
                if (capture != null) tokens.add(new SyntaxToken(from, end, capture));
                return;
            }

            int cursor = from;
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
            // A HEADING AND A FOOTNOTE, not two identical grey lines. The line that OPENS a run tells you
            // where to start reading and is coloured to be found while scrolling; the one that closes it
            // is a remark about a run already read, and stays quiet. @see RunConsole.Line#isRunStart
            if (line.isRunStart()) return RUN_START_CAPTURE;
            if (line.isDivider()) return "comment";
            // WHAT THE READER TYPED, drawn apart from what the program said -- otherwise a conversation
            // is indistinguishable from a monologue. @see RunConsole.Line#isTyped
            if (line.isTyped()) return RUN_INPUT_CAPTURE;
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
