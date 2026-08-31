package com.crystalgui.widget.texteditor;

import com.crystalgui.core.async.FrameProfile;
import com.crystalgui.core.async.JobKey;
import com.crystalgui.core.async.JobLane;
import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.text.Rope;
import com.crystalgui.text.Selection;
import com.crystalgui.text.fold.FoldingModel;
import com.crystalgui.text.fold.FoldingRangeProvider;
import com.crystalgui.text.fold.FoldingRegions;
import com.crystalgui.text.fold.IndentRangeProvider;
import com.crystalgui.text.wrap.ProjectedLines;

import java.util.Arrays;
import java.util.List;

/**
 * <b>Folding</b> — which blocks are foldable, which are closed, and everything that has to happen around a
 * fold changing.
 *
 * <p>The {@link FoldingModel} is the model and lives in {@code com.crystalgui.text.fold}; what is here is
 * the <em>view's</em> half, and it is not thin: every fold has to lift carets off rows that are about to
 * stop existing, re-push the hidden set into the projection, drop what the editor has realised, and put the
 * viewport back where it was. That sequence is {@link #afterFoldChange}, and it is the reason each of the
 * eight commands below is four lines rather than one.</p>
 *
 * <h3>Folding is view state</h3>
 *
 * <p>It never touches {@code UndoStack} — the same boundary the editor already draws for scroll and
 * selection, and where VS Code and IntelliJ both put it. Ctrl+Z unfolding instead of undoing is what the
 * rule prevents.</p>
 *
 * <h3>Two clocks, and the commands run on the wrong one</h3>
 *
 * <p>Regions are recomputed once a frame, from {@link #refreshFolding}. A command can fire between an edit
 * and that frame, so every command calls {@link #ensureFoldingCurrent} first — without it a fold right
 * after typing acts on the regions of the document as it was, off by however many rows the edit added.</p>
 */
final class EditorFolding {

    private final TextEditor editor;

    EditorFolding(TextEditor editor) {
        this.editor = editor;
    }

    /**
     * Which blocks are foldable and which are closed.
     *
     * <p>Fold state is keyed by region and survives an edit only as far as the regions do; a rewrite of a
     * block will not unfold — which is what VS Code and IntelliJ both do, and the same rule that keeps
     * scroll and selection out of the undo stack.</p>
     */
    private final FoldingModel folding = new FoldingModel();

    /**
     * Where foldable regions come from. Indentation by default — see {@link IndentRangeProvider} for why
     * that is Monaco's default too and deliberately not brackets.
     */
    private FoldingRangeProvider provider = IndentRangeProvider.plain();

    private boolean enabled = true;

    /** Set by anything that could change the region set; drained once per frame by {@link #refreshFolding}. */
    private boolean dirty = true;

    /** The folding model, for tests and for anything wanting to drive folds directly. */
    FoldingModel model() {
        return folding;
    }

    boolean isEnabled() {
        return enabled;
    }

    /** Says the region set no longer describes the document — an edit, a provider swap, a tab-size change. */
    void markDirty() {
        dirty = true;
    }

    // ── The frame ───────────────────────────────────────────────────────────────────────────────

    /**
     * Recomputes regions when something invalidated them, then pushes the hidden rows into the projection.
     *
     * <p>Runs every frame and is cheap when nothing moved: the recompute is gated on {@link #dirty}, and
     * {@link ProjectedLines#setHiddenAreas} reports whether it actually changed a row's visibility.</p>
     *
     * @return whether the set of visible rows changed, so the caller can drop what it has realised
     */
    boolean refreshFolding() {
        if (!enabled) return false;
        // TWO HALVES WITH NOTHING IN COMMON, and ed:refreshFolding measured 12-17ms without saying
        // which. Asking the provider is a query over the whole document; applying the answer rebuilds
        // per-row visibility across the projection. One is a language question and one is a view
        // question, and they would be fixed in different files.
        long asked = FrameProfile.begin();
        ensureFoldingCurrent();
        FrameProfile.step(asked, "fold:computeRegions ("
                + (provider == null ? "none" : provider.getClass().getSimpleName()) + ")");
        long applied = FrameProfile.begin();
        boolean changed = applyHiddenRows();
        FrameProfile.step(applied, "fold:applyHiddenRows");
        return changed;
    }

    private boolean applyHiddenRows() {
        List<FoldingModel.RowRange> hidden = folding.hiddenRows();
        int[][] ranges = new int[hidden.size()][];
        for (int i = 0; i < hidden.size(); i++) {
            ranges[i] = new int[] { hidden.get(i).startRow(), hidden.get(i).endRow() };
        }
        return editor.projections().setHiddenAreas(ranges);
    }

    /**
     * Ensures the regions are current before a command reads them.
     *
     * <p>See the class note: a command runs on the keystroke's clock, the region set on the frame's.</p>
     */
    private void ensureFoldingCurrent() {
        if (!dirty) return;
        dirty = false;
        Rope document = editor.buffer().document();
        int tabSize = editor.getTabSize();
        // ON A WORKER WHEN THE PROVIDER SAYS IT MAY BE. Folding is a whole-document pass and the only
        // part of a frame whose cost scales with the FILE rather than the screen -- 25.7ms on a
        // 2,020-line class, on the frame that opens it. @see FoldingRangeProvider#computesOffThread
        //
        // No callback is needed when it lands: refreshFolding runs applyHiddenRows every frame, and that
        // is what notices the new regions and reports the visibility change. The frames in between show
        // the document unfolded, which is the correct default rather than a wrong picture.
        if (!provider.computesOffThread() || !JobScheduler.hasShared()) {
            folding.update(document, provider, tabSize);
            return;
        }
        JobScheduler.shared()
                .job(JobKey.of(this, "folding"), JobLane.LATENCY,
                        context -> provider.compute(document, tabSize))
                .onDone(next -> {
                    // Null is a cancelled or superseded job -- an edit landed and re-dirtied us, so a
                    // newer compute is already queued and this answer describes text nobody is showing.
                    if (next != null) folding.install(next);
                })
                .submit();
    }

    // ── State in and out ────────────────────────────────────────────────────────────────────────

    /**
     * The first row of every collapsed region — the whole fold state, as something storable.
     *
     * <p>Rows rather than region indexes, because an index means nothing once the document changes: the
     * regions are recomputed from the text, so index 3 is a different block after an edit while row 42 is
     * still row 42 or is simply not foldable any more. IntelliJ and VS Code both persist folds by
     * position for the same reason.</p>
     *
     * <p>A pure read, deliberately — it does <b>not</b> recompute stale regions. A getter that quietly
     * runs a document-wide scan is the trap {@code getScrollWidth} already documents; anything that has
     * been painting has current regions, and anything that has not has no folds to report.</p>
     */
    int[] collapsedRows() {
        FoldingRegions regions = folding.regions();
        int[] found = new int[regions.length()];
        int count = 0;
        for (int i = 0; i < regions.length(); i++) {
            if (regions.isCollapsed(i)) found[count++] = regions.getStartLineNumber(i);
        }
        return Arrays.copyOf(found, count);
    }

    /**
     * Sets the fold state outright: every region starting on one of {@code startRows} is collapsed and
     * every other region is opened.
     *
     * <p><b>Recomputes the regions first</b>, which is the entire reason this is a method rather than
     * something a caller does through {@link #model()}. Regions are rebuilt from the text one frame
     * <em>after</em> the text arrives, so a restore running straight after the content lands would collapse
     * against an empty region set and silently do nothing — the failure would look like folds never having
     * been saved.</p>
     *
     * <p>Goes through the same anchor-and-lift path every interactive fold does, so a caret left inside a
     * region being closed is moved onto its header rather than becoming unpaintable. Restore the caret
     * <em>before</em> calling this and that lift does the right thing for free.</p>
     */
    void setCollapsedRows(int... startRows) {
        if (!enabled) return;
        TextEditor.StableViewport anchor = editor.captureFoldAnchor();
        ensureFoldingCurrent();
        FoldingRegions regions = folding.regions();
        for (int i = 0; i < regions.length(); i++) {
            int start = regions.getStartLineNumber(i);
            boolean wanted = false;
            for (int row : startRows) {
                if (row == start) {
                    wanted = true;
                    break;
                }
            }
            regions.setCollapsed(i, wanted);
        }
        afterFoldChange(anchor);
    }

    /** Swaps the region source — a syntax-aware provider layers over the indent one this way. */
    void setProvider(FoldingRangeProvider value) {
        this.provider = value == null ? FoldingRangeProvider.none() : value;
        this.dirty = true;
    }

    void setEnabled(boolean value) {
        if (this.enabled == value) return;
        this.enabled = value;
        if (!value) {
            folding.setCollapseStateForAll(false);
            applyHiddenRows();
        }
        this.dirty = true;
    }

    /** The rows currently hidden by collapsed folds. */
    List<FoldingModel.RowRange> hiddenRowRanges() {
        return folding.hiddenRows();
    }

    /** Opens every fold hiding {@code row}, and nothing else. @see TextEditor#revealOffset */
    void revealRow(int row) {
        for (FoldingModel.RowRange range : folding.hiddenRows()) {
            if (!range.contains(row)) continue;
            TextEditor.StableViewport anchor = editor.captureFoldAnchor();
            ensureFoldingCurrent();
            folding.setCollapseStateUp(false, row);
            afterFoldChange(anchor);
            return;
        }
    }

    // ── The commands ────────────────────────────────────────────────────────────────────────────

    /** Folds or unfolds the innermost region at the caret, stepping outwards when already in that state. */
    void fold() {
        TextEditor.StableViewport anchor = editor.captureFoldAnchor();
        ensureFoldingCurrent();
        folding.setCollapseStateUp(true, editor.caretRow());
        afterFoldChange(anchor);
    }

    void unfold() {
        TextEditor.StableViewport anchor = editor.captureFoldAnchor();
        ensureFoldingCurrent();
        folding.setCollapseStateUp(false, editor.caretRow());
        afterFoldChange(anchor);
    }

    /** Folds or unfolds the region at the caret and everything inside it. */
    void foldRecursively() {
        TextEditor.StableViewport anchor = editor.captureFoldAnchor();
        ensureFoldingCurrent();
        FoldingRegions.Region region = folding.getRegionAtLine(editor.caretRow());
        if (region != null && !region.isCollapsed()) {
            folding.toggleCollapseState(Integer.MAX_VALUE, editor.caretRow());
        }
        afterFoldChange(anchor);
    }

    void foldAll() {
        TextEditor.StableViewport anchor = editor.captureFoldAnchor();
        ensureFoldingCurrent();
        folding.collapseAllKeepingDocumentVisible(editor.buffer().lineCount());
        afterFoldChange(anchor);
    }

    void unfoldAll() {
        TextEditor.StableViewport anchor = editor.captureFoldAnchor();
        ensureFoldingCurrent();
        folding.setCollapseStateForAll(false);
        afterFoldChange(anchor);
    }

    /** Folds every region at exactly {@code level}, leaving the block the caret is in open. */
    void foldLevel(int level) {
        TextEditor.StableViewport anchor = editor.captureFoldAnchor();
        ensureFoldingCurrent();
        folding.setCollapseStateAtLevel(level, true, editor.caretRow());
        afterFoldChange(anchor);
    }

    /** Toggles the region whose first row is {@code row} — what clicking a gutter arrow does. */
    void toggleFoldAt(int row) {
        ensureFoldingCurrent();
        FoldingRegions.Region region = folding.getRegionStartingAt(row);
        if (region == null) return;
        TextEditor.StableViewport anchor = editor.captureFoldAnchor();
        region.setCollapsed(!region.isCollapsed());
        afterFoldChange(anchor);
    }

    // ── What has to happen around every one of them ─────────────────────────────────────────────

    /**
     * Finishes a fold change, keeping the viewport where it was.
     *
     * <p><b>The anchor is captured before the change, by the caller.</b> Folding removes rows above the
     * viewport as readily as below it, and {@code scrollTop} is a pixel count — so collapsing everything
     * while scrolled into a file silently pulls the whole document up past the top of the view, and
     * fold-all near the end leaves the editor apparently empty. IntelliJ keeps the line you are on exactly
     * where it is, which is the same guarantee zooming already makes here and the same
     * {@code StableViewport} that makes it.</p>
     */
    private void afterFoldChange(TextEditor.StableViewport anchor) {
        liftCaretsOutOfHiddenRows();
        if (applyHiddenRows()) editor.dropRealisedLines();
        // AFTER the hidden rows are applied, or the anchor is resolved against the projection the fold
        // just invalidated.
        editor.restoreStableViewport(anchor);
        editor.invalidateStyles();
    }

    /**
     * Moves every caret out of a row that is about to be hidden, onto its region's header.
     *
     * <p>Not cosmetic: a caret on a hidden row has no view line, so it cannot be drawn where it actually
     * is. {@code ProjectedLines.toViewPosition} walks it to the nearest visible row instead, and the caret
     * is then painted on a line it is not on — typing inserts somewhere other than where it appears. VS
     * Code does the same lift, which is why folding a block you are inside leaves the caret on the block's
     * first line.</p>
     *
     * <p><b>EVERY caret, which this did not used to do.</b> It read {@code selections.primary()} inside the
     * loop and returned after the first fix, so a secondary caret inside a folded block stayed there. With
     * one caret that is indistinguishable from correct, and every folding test had one — the plural in the
     * name and in this javadoc was the only evidence of the intent.</p>
     */
    private void liftCaretsOutOfHiddenRows() {
        List<FoldingModel.RowRange> hidden = folding.hiddenRows();
        if (hidden.isEmpty()) return;
        boolean[] moved = { false };
        editor.selections().transform(selection -> {
            int row = editor.buffer().document().offsetToPoint(selection.head()).row();
            for (FoldingModel.RowRange range : hidden) {
                if (!range.contains(row)) continue;
                moved[0] = true;
                // The region's HEADER. hiddenRows() starts at startLineNumber + 1 -- the first row stays
                // visible because it carries the fold arrow -- so startRow - 1 is that header, and is
                // never negative.
                return Selection.caret(editor.buffer().document().lineStartOffset(range.startRow() - 1));
            }
            return selection;
        });
        // Several carets in one folded block all land on its header; setAll normalises them into one.
        if (moved[0]) editor.afterSelectionChange();
    }

    /**
     * What a collapsed region's chip reads.
     *
     * <p>{@code "...}"} rather than plain {@code "..."} whenever the region's last row is the one that
     * closes it — so the header {@code void f() {} plus the chip renders as {@code void f() {...}}, which
     * is IntelliJ's collapsed form and the whole point of swallowing the closing row. The closer is taken
     * from the DOCUMENT rather than assumed, so {@code });} comes back intact instead of being guessed at
     * as a bare brace.</p>
     */
    String placeholderTextFor(FoldingRegions.Region region) {
        int endRow = region.endLineNumber();
        if (endRow <= region.startLineNumber() || endRow >= editor.buffer().lineCount()) {
            return TextEditor.FOLD_PLACEHOLDER_TEXT;
        }
        String closing = editor.buffer().document().line(endRow).trim();
        if (closing.isEmpty()) return TextEditor.FOLD_PLACEHOLDER_TEXT;
        char first = closing.charAt(0);
        if (first != '}' && first != ')' && first != ']') return TextEditor.FOLD_PLACEHOLDER_TEXT;
        return TextEditor.FOLD_PLACEHOLDER_TEXT + closing;
    }
}
