package com.crystalgui.workbench.diff;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.diff.LineDiff;
import com.crystalgui.text.diff.LinesDiff;
import com.crystalgui.text.diff.ThreeWayMerge;
import com.crystalgui.text.diff.ThreeWayMerge.Region;
import com.crystalgui.text.diff.ThreeWayMerge.Kind;
import com.crystalgui.text.diff.ThreeWayMerge.Region;
import com.crystalgui.text.diff.RegionState;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.service.Animation;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.layout.SplitView;
import com.crystalgui.widget.overlay.Tooltip;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.widget.texteditor.diff.DiffDecorations;
import com.crystalgui.widget.texteditor.TextEditor;

import java.util.List;

import javax.annotation.Nullable;

/**
 * A three-way merge, on screen — Phase 6.7.
 *
 * <p>Three panes: <b>mine</b>, the <b>result</b>, and <b>theirs</b>, which is the layout every merge tool
 * converges on because it is the only one where the thing being produced is visible beside both of its
 * sources. A two-pane diff can show what differs; it cannot show what you are about to end up with.</p>
 *
 * <h3>Why the outer panes are read-only and the middle one is not</h3>
 *
 * <p>Mine and theirs are <em>evidence</em> — one is the buffer as it stands, the other is what the server
 * holds, and editing either would be editing a record of something that already happened. The result is the
 * only pane that is a document, so it is the only one that takes a caret.</p>
 *
 * <h3>The hand-edit latch</h3>
 *
 * <p>Resolution buttons rewrite the result pane wholesale. Somebody typing into that pane and then pressing
 * one would lose what they typed, silently and irrecoverably — the worst failure a merge tool has available
 * to it. So the first hand edit <b>latches</b>: the resolution controls disable and say why, and the text on
 * screen is from then on the answer. Nothing is lost and nothing is guessed at.</p>
 *
 * <p>The alternative — mapping a hand edit back onto the region it fell in — is what IntelliJ does and is a
 * great deal more machinery than a first merger needs. {@link Region#acceptCustom} is the seam it would use
 * when that arrives; the engine already carries it.</p>
 */
public final class MergeView extends UINode  {
    /** Three revisions, and the result. */
    public static final Name NAME = Name.of("mergeview");


    public static final String CLASS = "__merge__";
    public static final String TOOLBAR_CLASS = "__merge-toolbar__";
    public static final String STATUS_CLASS = "__merge-status__";
    public static final String PANES_CLASS = "__merge-panes__";
    public static final String PANE_CLASS = "__merge-pane__";
    public static final String PANE_TITLE_CLASS = "__merge-pane-title__";
    public static final String PANE_EDITOR_CLASS = "__merge-editor__";
    /** A dialog button row that is not ConflictDialog's — see the sheet for why that distinction exists. */
    public static final String DIALOG_ACTIONS_CLASS = "__dialog-actions__";
    public static final String ACTIONS_CLASS = "__merge-actions__";

    private final ThreeWayMerge merge;

    private final TextEditor minePane = new TextEditor();
    private final TextEditor resultPane = new TextEditor();
    private final TextEditor theirsPane = new TextEditor();

    private final UIText status = new UIText("");
    private final Button previous;
    private final Button next;
    private final Button takeMine;
    private final Button takeTheirs;
    private final Button takeBoth;
    private final Button autoResolve;

    /** Fires whenever the resolved state changes, so a host can gate its OK button. */
    public final Signal.Action onChanged = new Signal.Action();

    private int currentConflict;
    private boolean handEdited;

    /** Guards the ticker against the writes this view makes itself. @see #tickFrame */
    private boolean syncing;
    private float lastMine, lastResult, lastTheirs;
    private float lastMineFont, lastResultFont, lastTheirsFont;

    public MergeView(ThreeWayMerge merge) {
        this.merge = merge;
        addClass(CLASS);
        // NOT markAsInternal() on this: it makes the widget unstyleable as a SELECTOR SUBJECT, so
        // .__merge__ would match nothing while .__merge__ .__merge-pane__ kept working -- a view with no
        // geometry and correctly styled children. addInternalChild below is the correct half of the pair.

        UINode toolbar = new UINode();
        toolbar.addClass(TOOLBAR_CLASS);
        append(toolbar);

        status.addClass(STATUS_CLASS);
        toolbar.append(status);

        UINode actions = new UINode();
        actions.addClass(ACTIONS_CLASS);
        toolbar.append(actions);

        previous = action(actions, "↑", "Previous conflict");
        next = action(actions, "↓", "Next conflict");
        takeMine = action(actions, "Take mine", "Resolve this conflict with your version");
        takeTheirs = action(actions, "Take theirs", "Resolve this conflict with the server's version");
        takeBoth = action(actions, "Take both", "Combine both sides, interleaved where they do not clash");
        autoResolve = action(actions, "Auto-resolve",
                "Settle every conflict whose two edits do not actually touch");

        SplitView panes = new SplitView();
        panes.addClass(PANES_CLASS);
        panes.addPane();
        panes.setWeights(1f, 1f, 1f);
        append(panes);

        panes.paneContent(0, pane("Mine", minePane));
        panes.paneContent(1, pane("Result", resultPane));
        panes.paneContent(2, pane("Theirs", theirsPane));

        minePane.setReadOnly(true).setText(join(merge.mineLines()));
        theirsPane.setReadOnly(true).setText(join(merge.theirsLines()));
        resultPane.setText(merge.merged());

        // EACH SIDE AGAINST THE ANCESTOR, which is what a merge view is asking about: not "how do these
        // two differ from each other" but "what did each of you do". The two are not the same question and
        // the second is the one a person can act on.
        minePane.setDiffDecorations(DiffDecorations.forModified(
                LinesDiff.computeDetailed(merge.baseLines(), merge.mineLines())));
        theirsPane.setDiffDecorations(DiffDecorations.forModified(
                LinesDiff.computeDetailed(merge.baseLines(), merge.theirsLines())));
        refreshResultDecorations();

        // A hand edit is the final say. Latching on the buffer's own signal rather than on a key handler
        // catches paste and undo too -- anything that changes the text is a hand edit, however it arrived.
        resultPane.buffer().onChanged.connect(change -> {
            if (syncing) return;
            // ATTRIBUTED TO REGIONS, not latched globally. The edit is located by comparing what the merge
            // expected to produce against what is on screen, so paste and undo count exactly as typing
            // does -- and every region the edit did not touch keeps its controls.
            int touched = merge.attributeHandEdit(LineDiff.lines(resultPane.getText()));
            if (touched > 0) handEdited = true;
            refresh();
            onChanged.emit();
        });

        previous.onPressed.connect(() -> step(-1));
        next.onPressed.connect(() -> step(1));
        takeMine.onPressed.connect(() -> resolve(new RegionState.Mine()));
        takeTheirs.onPressed.connect(() -> resolve(new RegionState.Theirs()));
        // SMART by default: concatenating two edits separated by unchanged text duplicates that text.
        takeBoth.onPressed.connect(() -> resolve(new RegionState.Both(true, true)));
        autoResolve.onPressed.connect(this::resolveAutomatically);

        refresh();
    }

    /** Composites own their structure. */

    /**
     * Registers the scroll-sync ticker.
     *
     * <p>Here rather than in the constructor because there is no window yet at construction, and
     * {@code registerTicker} is a {@code HashSet} insert so repeating it every layout is free — the same
     * reason {@code TextEditor} registers from its own layout pass rather than tracking attachment.</p>
     */
    @Override
    protected void connected() {
        super.connected();
        UIDocument window = document();
        // The flag is not the old one's: `registerTicker` was HashSet-backed and idempotent, and
        // `Animation.every` is a plain add, so a second attach without it is a second hook.
        if (ticking || window == null) return;
        ticking = true;
        window.animation().every(this, this::tickFrame);
    }

    @Override
    protected void disconnected() {
        super.disconnected();
        ticking = false;
    }

    private boolean ticking;

    private UINode pane(String title, TextEditor editor) {
        UINode pane = new UINode();
        pane.addClass(PANE_CLASS);

        UIText label = new UIText(title);
        label.addClass(PANE_TITLE_CLASS);
        pane.append(label);
        editor.addClass(PANE_EDITOR_CLASS);
        pane.append(editor);
        return pane;
    }

    private static Button action(UINode row, String label, String tooltip) {
        Button button = new Button(label);
        Tooltip.attach(button, tooltip);
        row.append(button);
        return button;
    }

    private static String join(List<String> lines) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) text.append(line).append('\n');
        return text.toString();
    }

    /** The merge this view is over. */
    public ThreeWayMerge merge() {
        return merge;
    }

    /**
     * The text to save.
     *
     * <p>Read off the <b>result pane</b>, not off the merge, so a hand edit is included. Asking the merge
     * would silently discard whatever was typed — the latch stops the buttons overwriting it and this stops
     * the save ignoring it; both halves are needed.</p>
     */
    public String mergedText() {
        return resultPane.getText();
    }

    /** Whether every conflict has been decided — what a host gates its OK button on. */
    public boolean isResolved() {
        return handEdited || merge.isResolved();
    }

    public int conflictCount() {
        return merge.conflictCount();
    }

    private void step(int delta) {
        List<Region> conflicts = merge.conflicts();
        if (conflicts.isEmpty()) return;
        currentConflict = Math.floorMod(currentConflict + delta, conflicts.size());
        refresh();
        revealCurrent();
    }

    /**
     * The result pane — the one document here.
     *
     * <p>Exposed because a host legitimately needs it: focusing the merge, placing a caret, reading the
     * text as it is typed. The two read-only panes are deliberately not exposed; they are evidence, and
     * nothing outside should be reaching for a handle on them.</p>
     */
    public TextEditor resultEditor() {
        return resultPane;
    }

    /** Resolves the conflict currently under the cursor. No-op once a hand edit has latched. */
    public void resolveCurrent(RegionState choice) {
        resolve(choice);
    }

    /** The result pane's own marks, against the ancestor. Recomputed whenever the result changes. */
    private void refreshResultDecorations() {
        resultPane.setDiffDecorations(DiffDecorations.forModified(
                LinesDiff.computeDetailed(merge.baseLines(), merge.mergedLines())));
    }

    private void resolve(RegionState choice) {
        List<Region> conflicts = merge.conflicts();
        if (handEdited || conflicts.isEmpty()) return;
        conflicts.get(currentConflict).accept(choice);

        // The result pane is rebuilt from the merge, so the buffer signal that write raises is this view's
        // own and must not be read as a hand edit.
        syncing = true;
        try {
            resultPane.setText(merge.merged());
        } finally {
            syncing = false;
        }

        // Move to the next thing that still needs a decision, so resolving a run of conflicts is one
        // button per conflict rather than an alternating press-and-navigate.
        for (int i = 1; i <= conflicts.size(); i++) {
            int candidate = (currentConflict + i) % conflicts.size();
            if (!conflicts.get(candidate).isResolved()) {
                currentConflict = candidate;
                break;
            }
        }
        refreshResultDecorations();
        refresh();
        revealCurrent();
        onChanged.emit();
    }

    /**
     * Settles every conflict whose two sides only <em>appear</em> to clash.
     *
     * <p>Line granularity reports a conflict whenever both sides touched the same rows, which includes the
     * common case of one person editing the start of a line and another the end. Asked again one
     * granularity down, most of those are not disagreements at all — see {@code MagicResolve}. The ones
     * that survive are the real ones, and they are what a person should spend their attention on.</p>
     */
    private void resolveAutomatically() {
        if (handEdited) return;
        int settled = merge.resolveConflictsAutomatically();
        if (settled == 0) {
            status.setText("Nothing to resolve automatically — every conflict is a real disagreement.");
            return;
        }
        syncing = true;
        try {
            resultPane.setText(merge.merged());
        } finally {
            syncing = false;
        }
        refreshResultDecorations();
        refresh();
        onChanged.emit();
    }

    private void revealCurrent() {
        Region region = currentRegion();
        if (region == null) return;
        minePane.revealAt(new TextPoint(region.mineFrom(), 0));
        theirsPane.revealAt(new TextPoint(region.theirsFrom(), 0));
    }

    private @Nullable Region currentRegion() {
        List<Region> conflicts = merge.conflicts();
        if (conflicts.isEmpty() || currentConflict >= conflicts.size()) return null;
        return conflicts.get(currentConflict);
    }

    private void refresh() {
        int total = merge.conflictCount();
        int outstanding = 0;
        for (Region region : merge.regions()) {
            if (region.kind() == Kind.CONFLICT && !region.isResolved()) outstanding++;
        }

        if (handEdited) {
            status.setText("Editing the result directly — the text on screen is the answer.");
        } else if (total == 0) {
            status.setText("Merged cleanly — no conflicts.");
        } else {
            status.setText(outstanding == 0
                    ? total + (total == 1 ? " conflict, resolved." : " conflicts, all resolved.")
                    : "Conflict " + (currentConflict + 1) + " of " + total + " — " + outstanding
                            + " still to decide.");
        }

        boolean canResolve = !handEdited && total > 0;
        // setEnabled AND setHitTest together: :disabled and :hover tie on specificity, so a disabled
        // button otherwise lights up under the pointer and still shows its tooltip -- a dead control
        // explaining what it would have done.
        for (Button button : new Button[] {previous, next, takeMine, takeTheirs, takeBoth, autoResolve}) {
            button.setEnabled(canResolve);
            button.setHitTest(canResolve);
        }
        if (handEdited) {
            Tooltip.attach(takeMine, "The result has been edited by hand; resolution would discard it.");
        }
    }

    /**
     * Keeps the three panes scrolled together.
     *
     * <p>Done per frame rather than through a scroll signal because there are three of them and any one may
     * be the one that moved: a listener each would have to suppress the two writes it causes, which is the
     * same guard as this and harder to see. The last-known values are the whole state.</p>
     */
    /** The per-frame hook, registered from {@link #connected()}. */
    public boolean tickFrame(float delta) {
        // FONT FIRST, THEN SCROLL, and the order is load-bearing: setFontSize re-anchors the viewport it
        // is called on, so a zoom moves scrollTop as a side effect. Syncing scroll first would align the
        // panes and then have two of them jump underneath the alignment.
        TextEditor zoomed = syncFont();
        syncScroll(zoomed);
        return true;
    }

    private static float fontOf(TextEditor editor) {
        return editor.getStyle().getGeneralGroup().fontSize();
    }

    /**
     * Keeps the three panes at one zoom level, and answers which one was zoomed.
     *
     * <p>Zoom belongs to the <b>comparison</b>, not to a pane. Two versions of a file at two sizes cannot
     * be read against each other at all — the lines no longer sit at the same heights, so every visual
     * correspondence the view exists to provide is gone, and the scroll sync makes it worse rather than
     * better because it aligns them by PIXEL and the pixel means a different line in each.</p>
     */
    private TextEditor syncFont() {
        float mine = fontOf(minePane), result = fontOf(resultPane), theirs = fontOf(theirsPane);

        TextEditor zoomed = null;
        if (mine != lastMineFont) zoomed = minePane;
        else if (result != lastResultFont) zoomed = resultPane;
        else if (theirs != lastTheirsFont) zoomed = theirsPane;

        if (zoomed != null) {
            float size = fontOf(zoomed);
            // Guarded per pane: setFontSize clears every row measurement, drops the digit width and
            // reprojects the wrap, so calling it with the size already in place is a whole re-layout for
            // nothing -- every frame, since this runs on the ticker.
            for (TextEditor pane : new TextEditor[] {minePane, resultPane, theirsPane}) {
                if (fontOf(pane) != size) pane.setFontSize(size);
            }
            // RECORDED AS THE SIZE ASKED FOR, not as what the panes currently report. setFontSize writes
            // an IMPORTANT candidate and the computed value only lands in the next calculateStyle -- which
            // runs BEFORE this ticker, so re-reading here still sees the old size on the two followers.
            // They would then look changed on the next tick and be mistaken for the pane the user zoomed,
            // handing the scroll sync the wrong anchor one frame after every zoom.
            lastMineFont = lastResultFont = lastTheirsFont = size;
            return zoomed;
        }
        lastMineFont = mine;
        lastResultFont = result;
        lastTheirsFont = theirs;
        return null;
    }

    /**
     * Keeps the three panes scrolled together.
     *
     * <p>Done per frame rather than through a scroll signal because there are three of them and any one may
     * be the one that moved: a listener each would have to suppress the two writes it causes, which is the
     * same guard as this and harder to see. The last-known values are the whole state.</p>
     *
     * @param zoomed the pane just zoomed, whose position wins outright — after a zoom all three have moved,
     *               so "which one changed" has three answers and only the one the user acted on is right
     */
    private void syncScroll(@Nullable TextEditor zoomed) {
        float mine = minePane.scrollTop();
        float result = resultPane.scrollTop();
        float theirs = theirsPane.scrollTop();

        TextEditor moved = null;
        if (zoomed != null) moved = zoomed;
        else if (mine != lastMine) moved = minePane;
        else if (result != lastResult) moved = resultPane;
        else if (theirs != lastTheirs) moved = theirsPane;

        if (moved != null) alignTo(moved);

        lastMine = minePane.scrollTop();
        lastResult = resultPane.scrollTop();
        lastTheirs = theirsPane.scrollTop();
    }

    /**
     * Scrolls the other two panes so they are showing the <b>same part of the merge</b>.
     *
     * <p>Not the same pixel offset, and not even the same line number. The three texts have different
     * line counts, so copying either drifts them apart the moment anything is inserted or deleted above:
     * one pane's {@code public long total()} sits a row higher than the other's before the reader has
     * scrolled at all, and the gap grows with every edit. What has to be held level is the
     * <em>region</em> — see {@link ThreeWayMerge#mapLine}.</p>
     *
     * <p>The fractional part of the scroll is carried across so a half-scrolled row stays half-scrolled;
     * rounding to whole lines makes the followers jump a row at a time while the pane under the wheel
     * moves smoothly, which reads as the sync being broken rather than as rounding.</p>
     */
    private void alignTo(TextEditor moved) {
        float lineHeight = Math.max(1f, moved.lineHeight());
        float top = moved.scrollTop();
        int topLine = (int) Math.floor(top / lineHeight);
        float fraction = top - topLine * lineHeight;

        int baseLine = moved == resultPane
                ? resultToBase(topLine)
                : merge.mapLine(topLine, sideOf(moved), ThreeWayMerge.Side.BASE);

        alignPane(minePane, moved, merge.mapLine(baseLine, ThreeWayMerge.Side.BASE,
                ThreeWayMerge.Side.MINE), fraction);
        alignPane(theirsPane, moved, merge.mapLine(baseLine, ThreeWayMerge.Side.BASE,
                ThreeWayMerge.Side.THEIRS), fraction);
        alignPane(resultPane, moved, baseToResult(baseLine), fraction);
    }

    private void alignPane(TextEditor pane, TextEditor moved, int line, float fraction) {
        // THE PANE THAT MOVED IS LEFT ALONE. Writing its own position back re-anchors it to a whole line
        // and fights the wheel -- the pane under the cursor would stutter while the two following it
        // moved smoothly, which is the opposite of what the sync is for.
        if (pane == moved) return;
        Box paneBox = pane.box();
        if (paneBox != null) paneBox.setScroll(pane.scrollLeft(), line * Math.max(1f, pane.lineHeight()) + fraction);
    }

    private ThreeWayMerge.Side sideOf(TextEditor pane) {
        return pane == theirsPane ? ThreeWayMerge.Side.THEIRS : ThreeWayMerge.Side.MINE;
    }

    /**
     * The result pane's own numbering, which is neither the base's nor either side's.
     *
     * <p>{@link ThreeWayMerge#mapLine} cannot answer for it: the result is assembled from whichever states
     * the regions currently hold, so its line numbers move whenever a conflict is decided.
     * {@link ThreeWayMerge#resultRanges()} is recomputed for the same reason.</p>
     */
    private int baseToResult(int baseLine) {
        List<Region> regions = merge.regions();
        List<int[]> ranges = merge.resultRanges();
        int resultAt = 0;
        int baseAt = 0;
        for (int i = 0; i < regions.size(); i++) {
            Region region = regions.get(i);
            if (region.baseFrom() > baseLine) break;
            if (region.baseTo() <= baseLine) {
                resultAt = ranges.get(i)[1];
                baseAt = region.baseTo();
            } else {
                return ranges.get(i)[0];
            }
        }
        return Math.max(0, resultAt + (baseLine - baseAt));
    }

    private int resultToBase(int resultLine) {
        List<Region> regions = merge.regions();
        List<int[]> ranges = merge.resultRanges();
        int resultAt = 0;
        int baseAt = 0;
        for (int i = 0; i < regions.size(); i++) {
            int[] range = ranges.get(i);
            if (range[0] > resultLine) break;
            if (range[1] <= resultLine) {
                resultAt = range[1];
                baseAt = regions.get(i).baseTo();
            } else {
                return regions.get(i).baseFrom();
            }
        }
        return Math.max(0, baseAt + (resultLine - resultAt));
    }
}
