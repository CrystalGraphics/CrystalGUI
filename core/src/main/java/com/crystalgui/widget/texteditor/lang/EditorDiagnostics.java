package com.crystalgui.widget.texteditor.lang;

import com.crystalgui.text.Rope;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.decoration.DecorationSet;
import com.crystalgui.text.decoration.Stickiness;
import com.crystalgui.text.decoration.TrackedRange;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.text.diagnostic.DiagnosticSet;
import com.crystalgui.text.lang.Versioned;

import com.crystalgui.widget.texteditor.TextEditor;
import com.crystalgui.widget.texteditor.part.SquigglesPart;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>Problems</b> — filing an announced list, keeping every mark under its word, and navigating to one.
 *
 * <p>The {@link DiagnosticSet} itself belongs to the <b>buffer</b>, not to this and not to the widget: a
 * diagnostic describes a document exactly as an undo stack does, so two views onto one file would otherwise
 * have two sets publishing two competing slices into one Problems panel. What is here is everything that
 * has to happen <em>around</em> that set for the marks to be usable.</p>
 *
 * <h3>Two mechanisms that look like one, and confusing them is the whole difficulty</h3>
 *
 * <p>A diagnostic is <b>row/column</b> and a squiggle is <b>offsets</b>, and the conversion between them is
 * only legal against the document the analysis actually saw. So there are two gates, at two different
 * moments:</p>
 *
 * <ul>
 *   <li>{@link #install} drops a list that describes an older document — <em>at the point of entry</em>,
 *       because such a list is as wrong in the Problems panel as it is under the text, so one rule covers
 *       both consumers.</li>
 *   <li>{@link #retrack} then keeps the offsets right <em>afterwards</em>, through the 300ms of typing
 *       before the next compile. Without it every mark below the caret pointed at whatever had shifted
 *       into its offsets, and corrected itself on the next compile — which is why it read as the analyser
 *       lagging rather than as a broken mark.</li>
 * </ul>
 */
public final class EditorDiagnostics {

    private final TextEditor editor;

    public EditorDiagnostics(TextEditor editor) {
        this.editor = editor;
    }

    /** The problems reported about this document — <b>the buffer's</b>, not this widget's. */
    DiagnosticSet set() {
        return editor.buffer().diagnostics();
    }

    /**
     * Files an announced list under its engine's owner key, if it still describes this document.
     *
     * <h3>The version gate decides whether a list is shown at all</h3>
     *
     * <p>Deliberately here rather than at the tracking below, because it is not really a question about
     * offsets — it is a question about whether these problems are <em>about</em> the text on screen. A list
     * computed against three keystrokes ago is as wrong in the Problems panel as it is under the text, so
     * gating at the point of entry means one rule covers both. The list is dropped rather than reconciled;
     * the job is debounced and keyed, so a fresh one is already queued.</p>
     */
    public void install(String owner, @Nullable Versioned<List<Diagnostic>> announced) {
        if (announced == null) return;
        int now = editor.buffer().version();
        if (!announced.isFresh(now)) {
            // SAID OUT LOUD, ONCE PER RUN OF REFUSALS.
            //
            // A refused list and a list that was never announced produce the identical picture: the
            // panel keeps whatever it last accepted, which is a correct-looking set of problems about
            // text that no longer exists. Reported as "the Problems aren't updating" on a file whose
            // warnings described a version several edits old -- and from outside there is no way to tell
            // which of the two it is, or whether the engine ran at all.
            //
            // Logged on the TRANSITION rather than per announcement: a refusal while typing is ordinary
            // -- the analysis started before the last keystroke and a fresh one is already queued -- and
            // a line per keystroke would be the console spam this exists to diagnose. What is not
            // ordinary is refusals that never stop, and one line followed by silence says exactly that.
            if (lastAccepted != now && !refusing) {
                refusing = true;
                CrystalGuiCore.LOGGER.info("[cgui-lang] {} announced problems for v{} but the buffer is "
                        + "at v{}; dropped, and a fresh analysis should follow", owner,
                        announced.version(), now);
            }
            return;
        }
        if (refusing) {
            refusing = false;
            CrystalGuiCore.LOGGER.info("[cgui-lang] {} caught up at v{}: {} problem(s)", owner, now,
                    announced.orElse(List.of()).size());
        }
        lastAccepted = now;
        set().changeOne(owner, announced.orElse(List.of()));
    }

    /** The version of the last list that was accepted, so a run of refusals can be reported once. */
    private int lastAccepted = -1;

    /** Whether the last announcement was refused. @see #install */
    private boolean refusing;

    /**
     * Rebuilds the tracked range behind every diagnostic — §17.1's primitive, applied.
     *
     * <h3>Driven by the SET, not by the engine that announced</h3>
     *
     * <p>Subscribing to the engine's push instead would track only engine-reported problems, and this
     * document has other producers: the shader graph writes four owners of its own on every compile, and a
     * future linter will write a fifth. Every one of them wants its marks to stay under their words, and
     * none of them has a version to offer. Keying the tracking to the set means one path covers all of
     * them and there is no producer that silently gets the untracked behaviour.</p>
     *
     * <p>Rebuilt wholesale rather than diffed. The set replaces per owner and announces once, so there is no
     * such thing as one diagnostic changing on its own — and a list is tens of entries.</p>
     */
    public void retrack() {
        List<Diagnostic> problems = set().all();
        List<DecorationSet.Entry> entries = new ArrayList<>(problems.size());
        for (Diagnostic problem : problems) {
            int from = editor.offsetOfPoint(problem.start());
            int to = Math.max(from, editor.offsetOfPoint(problem.end()));
            entries.add(DecorationSet.Entry.of(pastIndentIfWholeRow(from, to), to, problem));
        }
        // ALWAYS_GROWS: type at the start or the end of an underlined word and the new character is part of
        // the same mistake -- the mark should cover it, not sit beside it. Every other stickiness makes the
        // squiggle drift off the token it is about as the token is extended.
        editor.buffer().decorations().replaceLane(TextEditor.DIAGNOSTIC_LANE,
                Stickiness.ALWAYS_GROWS_WHEN_TYPING_AT_EDGES, entries);
        // AND THE HIGHLIGHTS, because a diagnostic now changes the TEXT and not only the marks under it.
        // The two consumers of this lane cache differently: SquigglesPart re-reads it every frame, while
        // refreshHighlights answers from a cache keyed on the visible range and would happily keep
        // publishing the previous analysis's fades until something else happened to scroll or type.
        editor.markHighlightsDirty();
    }

    /**
     * Where {@code problem} is <b>now</b>, or null when nothing is tracking it.
     *
     * <p>The round-trip a Problems row needs: the row holds a diagnostic reported at some past version, and
     * this answers where that text has since moved to. Null is a real answer — a diagnostic can be handed
     * in from outside the set — and a caller falls back to the reported row/column, which is what it would
     * have used anyway.</p>
     */
    @Nullable
    public TrackedRange trackedRangeFor(@Nullable Diagnostic problem) {
        if (problem == null) return null;
        for (TrackedRange range : editor.buffer().decorations().inLane(TextEditor.DIAGNOSTIC_LANE)) {
            if (range.payload() == problem) return range;
        }
        return null;
    }

    /**
     * The problems covering {@code offset} <b>right now</b>, nearest-first.
     *
     * <p>Read from the decoration lane rather than from {@link DiagnosticSet}, and that is the whole
     * point: a diagnostic's own row/column describe the document that was compiled, while the tracked
     * range has been carried through every edit since. Asking the set would put the hover on a problem
     * whose text has moved, which is the failure the tracking was built to end.</p>
     *
     * <p>Zero-width diagnostics are widened by one before the test — "expected ';'" points <em>between</em>
     * two characters, and a range that contains nothing contains no offset either.</p>
     */
    public List<Diagnostic> at(int offset) {
        List<Diagnostic> found = new ArrayList<>();
        for (TrackedRange range : editor.buffer().decorations().inLane(TextEditor.DIAGNOSTIC_LANE)) {
            int from = range.from();
            int to = Math.max(range.to(), from + 1);
            if (offset >= from && offset <= to && range.payload() instanceof Diagnostic) {
                found.add((Diagnostic) range.payload());
            }
        }
        return found;
    }

    /**
     * A mark that covers a whole row starts at that row's first non-whitespace character.
     *
     * <p>A producer that says "this row" — {@code Diagnostic.onRow}, which is what a compiler reporting
     * only a line number produces, and what a runtime error out of a script engine produces — is pointing
     * at the <em>statement</em>. Underlining the indentation in front of it marks text nobody claimed is
     * wrong, and on a deeply nested line most of the squiggle is empty space, which reads as the mark
     * being misaligned. IntelliJ trims to the first non-whitespace character for exactly this; VS Code
     * draws the column-0 range literally, and its line-only producers are the ones people complain look
     * off.</p>
     *
     * <p><b>Gated on the range covering the entire row</b>, so an explicit range that genuinely begins at
     * column 0 — a producer that measured its own span and found it starts there — is left alone. A row
     * that is nothing but whitespace is left alone too: there is no content to move onto, and collapsing
     * the range would hide the mark completely.</p>
     */
    private int pastIndentIfWholeRow(int from, int to) {
        Rope document = editor.buffer().document();
        int row = document.offsetToPoint(from).row();
        if (from != document.lineStartOffset(row) || to != document.lineEndOffset(row)) return from;
        int at = from;
        while (at < to && Character.isWhitespace(document.charAt(at))) at++;
        return at == to ? from : at;
    }

    // ── Navigation ──────────────────────────────────────────────────────────────────────────────

    /** Moves the caret to the next problem after it, wrapping. False when there are none. */
    public boolean goToNext(TextPoint from) {
        return goTo(set().nextFrom(from));
    }

    /** The mirror of {@link #goToNext}, wrapping to the last. */
    public boolean goToPrevious(TextPoint from) {
        return goTo(set().previousFrom(from));
    }

    /**
     * Puts the caret on a diagnostic, revealing it first.
     *
     * <p><b>The unfold is not a convenience.</b> A row inside a collapsed region has no view line, so a
     * caret placed on it cannot be painted, scrolled to or typed at — the same reason folding a block the
     * caret is in has to move the caret to the block's header. Jumping to a problem hidden inside a fold
     * without opening it leaves the editor looking focused and doing nothing, which is the worst possible
     * answer to "take me to the error".</p>
     *
     * <p>The row is clamped to the live document because diagnostics are inherently stale: the set on
     * screen describes whatever was last compiled, and the buffer may since have shrunk.</p>
     */
    public boolean goTo(@Nullable Diagnostic target) {
        if (target == null) return false;
        // THE TRACKED RANGE FIRST -- it is where the text went, and the reported row/column is where it was
        // when the compiler last looked. They differ by exactly the edits made since, so "take me to the
        // error" landed a few lines off during the 300ms before a recompile, which is the moment somebody is
        // most likely to be using it.
        TrackedRange tracked = trackedRangeFor(target);
        if (tracked != null && !tracked.isRemoved()) {
            int offset = Math.min(tracked.from(), editor.buffer().length());
            editor.revealRow(editor.buffer().offsetToPoint(offset).row());
            editor.setCaret(offset);
            editor.revealCaretCentred();
            return true;
        }
        int row = Math.max(0, Math.min(target.start().row(), editor.buffer().lineCount() - 1));
        editor.revealRow(row);
        int rowStart = editor.buffer().document().lineStartOffset(row);
        int rowEnd = editor.buffer().document().lineEndOffset(row);
        editor.setCaret(Math.min(rowEnd, rowStart + Math.max(0, target.start().column())));
        // AND SCROLLED TO. `revealRow` only UNFOLDS -- its whole job is making the row exist as a view line
        // -- and `setCaret` deliberately never scrolls, so between them this moved the caret to a problem
        // and left the viewport exactly where it was. Reported as "the up/down arrows do nothing", which is
        // precisely what a jump you cannot see looks like. Centred, because this is navigation: see
        // revealCaretCentred on why that is a different question from following a caret.
        editor.revealCaretCentred();
        return true;
    }
}
