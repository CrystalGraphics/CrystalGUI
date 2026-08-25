package com.crystalgui.text;

import com.crystalgui.core.async.FrameProfile;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.core.undo.Edit;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.text.decoration.DecorationSet;
import com.crystalgui.text.diagnostic.DiagnosticSet;
import com.crystalgui.text.decoration.TrackedRange;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.LongSupplier;

/**
 * A mutable document: a {@link Rope} you can edit, with undo.
 *
 * <p>The buffer holds no view state — no caret, no selection, no scroll position. Those belong to the
 * widget looking at it, and keeping them out is what lets two views share one document without
 * negotiating whose caret is whose. It is also the 6.1.9 boundary in its most concrete form: what is in
 * here is what undo restores.</p>
 *
 * <p><b>With one exception, which both references also make: an undo ENTRY records the carets its edit
 * was made at</b> — see {@link #edit(ChangeSet, java.util.List)}. That is not a caret the buffer has;
 * it is a pair of offsets stored with a change, in the same way the change itself is, and it exists
 * because an undo that restores the text without restoring the caret has moved the text out from under
 * the user's hands. Nothing reads it but the undo path, and a view that records none still undoes.</p>
 *
 * <h3>Undo stores changes, not snapshots</h3>
 * <p>Each entry keeps the edit and its inverse, both {@link ChangeSet}s — plain data, so the stack
 * serialises and a server can hold one. Holding old {@link Rope}s instead would be cheaper (they share
 * structure) and would also make the history un-sendable, which is the thing 6.1.9 decided against.</p>
 *
 * <h3>Coalescing, and why the clock is injectable</h3>
 * <p>A run of keystrokes is one undo step, achieved by {@link ChangeSet#compose} rather than by a merge
 * rule per edit type. {@link #setClock} exists because the engine has been bitten by this before:
 * {@code TransitionEngine} reads {@code System.nanoTime()} directly and consequently cannot be stepped by
 * a test, so its behaviour is asserted indirectly forever. Time that decides behaviour is an input.</p>
 */
public final class TextBuffer {

    /** How long a pause breaks a run of typing into a second undo step. Matches the usual editor feel. */
    public static final long DEFAULT_COALESCE_WINDOW_MILLIS = 500L;

    /** Emitted after every applied edit, including undo and redo, with the change that was applied. */
    public final Signal.Value<ChangeSet> onChanged = new Signal.Value<>();

    private Rope document;

    /**
     * How many times this document has changed — the stamp every async result is compared against.
     *
     * <p>Monotonic, and deliberately not a hash or a timestamp: it only ever has to answer <em>"is this
     * still the document that was snapshotted?"</em>, and equality on a counter is the cheapest honest
     * answer. Two different edits can produce identical text (type a character, delete it) and a result
     * computed against the text in between is still stale, which is why identity of content is the wrong
     * question.</p>
     *
     * <p>It starts at 0 and is bumped by {@link #applied}, so a freshly loaded document and a heavily
     * edited one are never confused. Nothing outside this class writes it.</p>
     */
    private int version;

    /**
     * The history, shared with the rest of the engine rather than private to this class.
     *
     * <p>It used to be a pair of deques here. {@link UndoStack} is the same mechanism generalised, and
     * using it means a text document and a node graph have one notion of an undo step — which is what
     * lets {@code edit.undo} be a single command that finds the nearest {@code UndoScope} instead of
     * every document type shipping its own keystroke.</p>
     *
     * <p>The split is clean: <b>the stack owns the clock</b> (a pause breaks a run) and <b>the edit owns
     * the intent</b> (typing and deleting are different things, and a run must be contiguous). Neither
     * half changed meaning in the move.</p>
     */
    private final UndoStack history = new UndoStack().setMergeWindowMillis(DEFAULT_COALESCE_WINDOW_MILLIS);

    public TextBuffer() {
        this(Rope.EMPTY);
    }

    /**
     * Takes text in whatever line ending it arrived with, and normalises it.
     *
     * <p>See {@link LineEnding}: the buffer is always LF internally, because every offset in the engine
     * counts a break as ONE unit and a {@code 
} would make that sometimes two. The original ending
     * is remembered so {@link #textWithOriginalLineEndings()} can put it back, which is why editing a
     * Windows file does not silently convert it.</p>
     */
    public TextBuffer(CharSequence text) {
        this.lineEnding = LineEnding.detect(text == null ? "" : text);
        this.document = Rope.of(LineEnding.normalise(text == null ? "" : text));
    }

    public TextBuffer(Rope document) {
        this.document = document == null ? Rope.EMPTY : document;
    }

    private LineEnding lineEnding = LineEnding.LF;

    /** The ending this document arrived with — what a save should write back. */
    public LineEnding lineEnding() {
        return lineEnding;
    }

    public TextBuffer setLineEnding(LineEnding ending) {
        this.lineEnding = ending == null ? LineEnding.LF : ending;
        return this;
    }

    /** The document written back in the ending it came with. What a save writes; never what it edits. */
    public String textWithOriginalLineEndings() {
        return lineEnding.applyTo(document.toString());
    }

    /** Replaces the whole document, re-detecting the line ending — i.e. loading a file. */
    /**
     * A document's text with everything that can be computed from it alone already computed.
     *
     * @param document   the rope, built
     * @param normalised the text with every ending collapsed to {@code \n}
     * @param ending     what the file came with, so a save writes it back
     */
    public record Prepared(Rope document, String normalised, LineEnding ending) {
    }

    /**
     * Does the whole of {@link #load}'s arithmetic — <b>off the frame thread</b>.
     *
     * <h3>All of this is a pure function of the bytes</h3>
     *
     * <p>Detecting the ending is a scan, normalising allocates a second copy, and building the rope is a
     * chunk-and-build over the result. None of it touches the buffer, the editor or the tree, and all of
     * it ran on the frame that published a newly opened file. Measured in a client opening a 108KB
     * decompiled class: <b>detect 6.9ms, normalise 0.4ms, rope 2.7ms</b> of a 34ms frame — which is the
     * reported "Enter on Minecraft.class takes 120fps to 50".</p>
     *
     * <p>The caller reads the bytes on a worker already; this is that worker finishing the job rather
     * than handing a String to the frame and letting it do the work there.</p>
     */
    public static Prepared prepare(CharSequence text) {
        String incoming = text == null ? "" : text.toString();
        LineEnding ending = LineEnding.detect(incoming);
        String normalised = LineEnding.normalise(incoming);
        return new Prepared(Rope.of(normalised), normalised, ending);
    }

    /**
     * {@link #load}, given work {@link #prepare} already did.
     *
     * <p>Equivalent by construction: a full-document replace applies to exactly {@code Rope.of(normalised)},
     * so handing that rope in rather than recomputing it changes the cost and not the result. Both halves
     * come from one {@code prepare} call, so the rope and the change that describes it cannot disagree.</p>
     */
    public void load(Prepared prepared) {
        this.lineEnding = prepared.ending();
        ChangeSet change = ChangeSet.replace(document.length(), 0, document.length(),
                prepared.normalised());
        if (!change.isEmpty()) {
            ChangeSet inverse = change.invert(document);
            // THE PREPARED ROPE, instead of change.apply(document) -- the one line this exists for.
            document = prepared.document();
            history.push(new ChangeSetEdit(this, change, inverse, null));
            applied(change);
        }
        breakUndoCoalescing();
    }

    public void load(CharSequence text) {
        String incoming = text == null ? "" : text.toString();
        long timed = FrameProfile.begin();
        this.lineEnding = LineEnding.detect(incoming);
        FrameProfile.step(timed, "buf.detectEnding " + incoming.length() + " chars");
        timed = FrameProfile.begin();
        String normalised = LineEnding.normalise(incoming);
        FrameProfile.step(timed, "buf.normalise");
        timed = FrameProfile.begin();
        replace(0, length(), normalised);
        FrameProfile.step(timed, "buf.replace");
        breakUndoCoalescing();
    }

    // ── Reading ─────────────────────────────────────────────────────────────────────────────────

    public Rope document() {
        return document;
    }

    public int length() {
        return document.length();
    }

    public int lineCount() {
        return document.lineCount();
    }

    public String line(int row) {
        return document.line(row);
    }

    public TextPoint offsetToPoint(int offset) {
        return document.offsetToPoint(offset);
    }

    public int pointToOffset(TextPoint point) {
        return document.pointToOffset(point);
    }

    @Override
    public String toString() {
        return document.toString();
    }

    // ── Editing ─────────────────────────────────────────────────────────────────────────────────

    public void replace(int from, int to, String text) {
        edit(ChangeSet.replace(document.length(), from, to, text));
    }

    public void insert(int offset, String text) {
        replace(offset, offset, text);
    }

    public void delete(int from, int to) {
        replace(from, to, "");
    }

    /**
     * Applies an edit and records it for undo, coalescing it into the previous step when it reads as a
     * continuation of the same typing.
     */
    public void edit(ChangeSet change) {
        edit(change, null);
    }

    /**
     * The same, recording <b>where the carets were</b> so that undoing puts them back.
     *
     * <h3>Why the carets ride on the undo entry, when nothing else about the view does</h3>
     *
     * <p>The rule this codebase draws — document state through {@code Edit}s, view state mutated directly
     * — is what keeps Ctrl+Z from undoing a scroll. A selection looks like view state and is the one
     * exception both references make, because it is not the <em>view's</em>: it is a pair of offsets into
     * this document, of exactly the kind a {@link ChangeSet} maps, and an undo that puts the text back
     * without putting the caret back has moved the text out from under the user's hands. VS Code calls it
     * {@code beforeCursorState} on its undo elements and Monaco the same.</p>
     *
     * <p>Redo does not need a second recording: re-applying the change to the carets that preceded it is
     * where they ended up the first time, which is the same answer by construction rather than a
     * remembered one that could disagree.</p>
     *
     * @param carets the selections as they stand <em>before</em> this edit, or null to record none
     */
    public void edit(ChangeSet change, List<Selection> carets) {
        if (change == null || change.isEmpty()) return;
        if (change.lengthBefore() != document.length()) {
            throw new IllegalArgumentException("edit applies to a document of length "
                    + change.lengthBefore() + ", but this one is " + document.length());
        }

        long timed = FrameProfile.begin();
        ChangeSet inverse = change.invert(document);
        FrameProfile.step(timed, "buf.invert");
        timed = FrameProfile.begin();
        document = change.apply(document);
        FrameProfile.step(timed, "buf.applyToRope");
        // Applied here, then recorded — which is what UndoStack.push is for. Handing the stack an
        // unapplied edit would mean applying it twice. Push also clears the redo branch: keeping it
        // would let redo replay a change against a document it was never described against, which the
        // length check above would then reject at some arbitrary later point rather than here.
        timed = FrameProfile.begin();
        history.push(new ChangeSetEdit(this, change, inverse,
                carets == null ? null : List.copyOf(carets)));
        FrameProfile.step(timed, "buf.historyPush");
        timed = FrameProfile.begin();
        applied(change);
        FrameProfile.step(timed, "buf.applied (decorations + onChanged)");
    }

    /**
     * The single statement of "this document just changed": bump the version, then announce it.
     *
     * <p>Every mutation goes through here — the forward edit, and redo and undo on the recorded entry.
     * <b>Undo and redo count.</b> They move the text as surely as typing does, so anything computed
     * against the document before one of them is just as stale; a version that only advanced on forward
     * edits would let a diagnostic list survive Ctrl+Z and be re-attached to text it never described.</p>
     *
     * <p>Bumped <em>before</em> the signal, so a listener reading {@link #version()} from inside
     * {@code onChanged} sees the version its change produced rather than the one it replaced.</p>
     *
     * <p>And the decorations move <em>before</em> the signal too, for a stronger reason than tidiness: every
     * listener here repaints, and a listener that reads a {@link TrackedRange} before it has been adjusted
     * reads an offset into the document that this edit just replaced. There is no ordering among listeners
     * to rely on, so the adjustment cannot be one of them.</p>
     */
    private void applied(ChangeSet change) {
        version++;
        long timed = FrameProfile.begin();
        decorations.adjust(change);
        FrameProfile.step(timed, "buf.decorations.adjust");
        timed = FrameProfile.begin();
        onChanged.emit(change);
        FrameProfile.step(timed, "buf.onChanged.emit -> " + onChanged.connectionCount() + " listeners");
    }

    /**
     * Announces the carets an undo or redo restores — <b>after</b> {@link #applied}, never instead of it.
     *
     * <p>After, because every listener on {@code onChanged} reconciles itself with the new text first, and
     * one of them clamps selections to the document's length. Restoring before that would hand the clamp
     * the answer to overwrite.</p>
     */
    private void restore(@Nullable List<Selection> carets) {
        if (carets != null) onSelectionsRestored.emit(carets);
    }

    /**
     * Emitted by an undo or a redo with the carets that belong to the state it just produced.
     *
     * <p>Separate from {@link #onChanged} rather than folded into its payload, because the two are not
     * the same announcement: every edit changes text, and only an undo or a redo has a recorded answer
     * about where the carets go. A listener that reads the text does not want to be told about carets it
     * has already reconciled, and a widget with no history of its own has nothing to do here at all.</p>
     */
    public final Signal.Value<List<Selection>> onSelectionsRestored = new Signal.Value<>();

    /**
     * The ranges tracking this document — §17.1's primitive.
     *
     * <p>On the <b>document</b>, not on the editor, and it is the same boundary the undo stack draws: two
     * split panes onto one file are one document, so a diagnostic squiggle exists once and both views paint
     * the same one. Putting it on the widget would give the two views separate sets that drift apart the
     * moment either is typed in.</p>
     */
    public DecorationSet decorations() {
        return decorations;
    }

    private final DecorationSet decorations = new DecorationSet();

    /**
     * The problems reported about this document.
     *
     * <p>Here for the reason {@link #decorations} is, and it is the same reason: a diagnostic describes a
     * <b>document</b>, exactly as an undo stack does. It lived on {@code TextEditor}, where its own
     * javadoc called that a known compromise — two views onto one file would have had two sets, publishing
     * two competing slices into one Problems panel and disagreeing about which version they described.</p>
     *
     * <p>It is also where the tracking already is: the squiggles are {@link TrackedRange}s in this
     * buffer's decoration set, so keeping the list that produces them one layer up meant the list and its
     * marks had different owners and different lifetimes.</p>
     */
    public DiagnosticSet diagnostics() {
        return diagnostics;
    }

    private final DiagnosticSet diagnostics = new DiagnosticSet();

    /**
     * The document's current version — see the field note. Compare with {@code ==} against the version an
     * async job snapshotted; anything else is stale and must be discarded rather than reconciled.
     */
    public int version() {
        return version;
    }

    /**
     * One recorded edit: the change, and its inverse taken against the document it applied to.
     *
     * <p>Data rather than a closure — two {@code ChangeSet}s — so it survives serialization and cannot
     * capture a document that has since moved on. {@link #apply()} is what redo runs, and it is valid to
     * run again because undo has put the document back to the state the change was described
     * against.</p>
     */
    private record ChangeSetEdit(TextBuffer buffer, ChangeSet forward, ChangeSet inverse,
                                 @Nullable List<Selection> caretsBefore) implements Edit {
        @Override
        public void apply() {
            buffer.document = forward.apply(buffer.document);
            buffer.applied(forward);
            // WHERE THEY ENDED UP THE FIRST TIME, derived rather than remembered: carrying the carets
            // that preceded the edit through the edit is the same answer, and cannot drift from it.
            buffer.restore(caretsBefore == null ? null
                    : SelectionModel.mapThrough(caretsBefore, forward));
        }

        @Override
        public void undo() {
            buffer.document = inverse.apply(buffer.document);
            buffer.applied(inverse);
            buffer.restore(caretsBefore);
        }

        @Override
        public String label() {
            return "typing";
        }

        @Override
        public Edit mergeWith(Edit next) {
            if (!(next instanceof ChangeSetEdit other) || other.buffer() != buffer) return null;
            if (!continues(forward, other.forward())) return null;
            return new ChangeSetEdit(buffer, forward.compose(other.forward()),
                    // The inverse of "a then b" is "invert b, then invert a" — the new inverse runs
                    // first, because it is the one expressed against the document we are now in.
                    other.inverse().compose(inverse),
                    // THE FIRST ONE'S CARETS. A merged step is one step, and undoing a run of typing puts
                    // the caret where the run STARTED -- taking the later entry's would land it in the
                    // middle of text this undo has just removed.
                    caretsBefore);
        }
    }

    /**
     * Ends the current undo step, so the next edit starts a new one.
     *
     * <p>Called by the view when the caret is moved deliberately, the selection changes, or the document
     * is saved — the moments a user would consider one thing finished and another begun. The buffer
     * cannot detect those itself, which is exactly why this is public rather than inferred.</p>
     */
    public void breakUndoCoalescing() {
        history.breakMergeRun();
    }

    /** This document's history — what {@code TextEditor} hands to {@code UndoScope}, so a menu item and
     * the command palette reach the same stack the keystroke does. */
    public UndoStack history() {
        return history;
    }

    public boolean canUndo() {
        return history.canUndo();
    }

    public boolean canRedo() {
        return history.canRedo();
    }

    /** @return false when there was nothing to undo */
    public boolean undo() {
        return history.undo();
    }

    /** @return false when there was nothing to redo */
    public boolean redo() {
        return history.redo();
    }

    /** Undo history depth — for a history panel, and for tests that assert coalescing happened. */
    public int undoDepth() {
        return history.undoDepth();
    }

    // ── Coalescing policy ───────────────────────────────────────────────────────────────────────

    /**
     * Whether an edit continues the previous one.
     *
     * <p>Two conditions here, plus one the stack applies before ever asking: it only offers a merge when
     * the two edits arrived within its window, because a pause means the user stopped and started again.
     * What is left is intent, which only this class can judge:</p>
     * <ul>
     *   <li><b>Same kind</b> — typing and deleting are different intents, so a backspace ends a run of
     *       typing rather than joining it. Otherwise one undo both restores what was deleted and removes
     *       what was typed, which is never what was wanted.</li>
     *   <li><b>Contiguous</b> — the new edit begins exactly where the previous one ended. A click
     *       elsewhere and a keystroke there is a separate step even if it lands inside the window.</li>
     * </ul>
     *
     * <p>A newline also breaks the run: a line is the unit people expect undo to work in, and it is the
     * one boundary that is worth special-casing because it is the one users can see.</p>
     */
    private static boolean continues(ChangeSet previous, ChangeSet next) {
        if (previous.changes().size() != 1 || next.changes().size() != 1) return false;

        Change before = previous.changes().get(0);
        Change after = next.changes().get(0);
        if (after.insert().indexOf('\n') >= 0) return false;
        if (kindOf(before) != kindOf(after)) return false;

        if (kindOf(after) == Kind.INSERT) {
            // The previous insertion ended where this one begins — i.e. still typing forwards.
            return after.from() == before.from() + before.inserted();
        }
        // Backspacing: each deletion ends where the previous one began.
        return after.to() == before.from();
    }

    private enum Kind { INSERT, DELETE, OTHER }

    private static Kind kindOf(Change change) {
        if (change.removed() == 0 && change.inserted() > 0) return Kind.INSERT;
        if (change.inserted() == 0 && change.removed() > 0) return Kind.DELETE;
        return Kind.OTHER;
    }

    public TextBuffer setCoalesceWindowMillis(long millis) {
        history.setMergeWindowMillis(millis);
        return this;
    }

    /** Replaces the clock. For tests — see the class note on why this is not read directly. */
    public TextBuffer setClock(LongSupplier clock) {
        history.setClock(clock);
        return this;
    }

}
