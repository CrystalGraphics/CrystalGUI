package com.crystalgui.workbench.diff;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.diff.DetailedDiff;
import com.crystalgui.text.diff.LineDiff;
import com.crystalgui.text.diff.LinesDiff;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.layout.SplitView;
import com.crystalgui.widget.overlay.Tooltip;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.widget.texteditor.diff.DiffDecorations;
import com.crystalgui.widget.texteditor.TextEditor;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

/**
 * A two-way diff: one revision beside another, with the differences marked and revertible.
 *
 * <p>Shaped after IntelliJ's {@code SimpleDiffViewer} and VS Code's {@code diffEditorWidget}. The left
 * pane is the older revision and is <b>read-only</b>; the right is the current one and is not. That
 * asymmetry is the whole interaction model — the {@code »} beside each difference pushes the left's
 * version onto the right, which is the only direction that makes sense when the left is a commit.</p>
 *
 * <h3>Shared with {@link MergeView}, and deliberately not merged with it</h3>
 *
 * <p>Both draw panes of text with difference bands, and it is tempting to make one widget with a pane
 * count. They answer different questions: a diff asks <em>what changed</em> and a merge asks <em>what do I
 * end up with</em>. The merge has a result document, states per region, and conflicts; the diff has none
 * of those and gains nothing but dead controls from carrying them. What they genuinely share —
 * {@code DiffDecorations}, the differ, and the alignment arithmetic — is shared as those pieces.</p>
 */
public final class DiffView extends UIElement {
    /** Two revisions, side by side. */
    public static final Name NAME = Name.of("diffview");


    public static final String CLASS = "__diff__";
    public static final String TOOLBAR_CLASS = "__diff-toolbar__";
    public static final String STATUS_CLASS = "__diff-status__";
    public static final String PANES_CLASS = "__diff-panes__";
    public static final String PANE_CLASS = "__diff-pane__";
    public static final String PANE_TITLE_CLASS = "__diff-pane-title__";
    public static final String PANE_EDITOR_CLASS = "__diff-editor__";
    public static final String ACTIONS_CLASS = "__diff-actions__";

    private final List<String> left;
    private List<DetailedDiff> diffs = List.of();

    private final TextEditor leftPane = new TextEditor();
    private final TextEditor rightPane = new TextEditor();
    private final UIText status = new UIText("");
    private final Button previous;
    private final Button next;

    /** Fires when the right-hand text changes — a host gates its Save on this. */
    public final Signal.Action onChanged = new Signal.Action();

    private int current;
    private boolean syncing;
    private float lastLeft, lastRight;

    public DiffView(String leftTitle, String leftText, String rightTitle, String rightText) {
        super(NAME);
        this.left = LineDiff.lines(leftText);
        addClass(CLASS);

        UIElement toolbar = new UIElement();
        toolbar.addClass(TOOLBAR_CLASS);
        append(toolbar);

        status.addClass(STATUS_CLASS);
        toolbar.append(status);

        UIElement actions = new UIElement();
        actions.addClass(ACTIONS_CLASS);
        toolbar.append(actions);
        previous = action(actions, "↑", "Previous difference");
        next = action(actions, "↓", "Next difference");

        SplitView panes = new SplitView();
        panes.addClass(PANES_CLASS);
        append(panes);
        panes.paneContent(0, pane(leftTitle, leftPane));
        panes.paneContent(1, pane(rightTitle, rightPane));

        // THE LEFT PANE'S GUTTER MIRRORS, so the two panes' line numbers meet in the middle and a
        // reader comparing one line against another looks at one place instead of sweeping the width.
        leftPane.setGutterOnRight(true);
        leftPane.setReadOnly(true).setText(leftText);
        rightPane.setText(rightText);

        // ON THE LEFT PANE, pointing right: it pushes the older revision onto the current one. Offering it
        // on the right as well would be offering to edit the commit, which is not a thing.
        leftPane.setDiffRevertHandler(this::revert);

        previous.onPressed.connect(() -> step(-1));
        next.onPressed.connect(() -> step(1));

        rightPane.buffer().onChanged.connect(change -> {
            if (syncing) return;
            recompute();
            onChanged.emit();
        });

        recompute();
    }

    /** @see MergeView#connected */
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

    private UIElement pane(String title, TextEditor editor) {
        UIElement pane = new UIElement();
        pane.addClass(PANE_CLASS);
        UIText label = new UIText(title);
        label.addClass(PANE_TITLE_CLASS);
        pane.append(label);
        editor.addClass(PANE_EDITOR_CLASS);
        pane.append(editor);
        return pane;
    }

    private static Button action(UIElement row, String label, String tooltip) {
        Button button = new Button(label);
        Tooltip.attach(button, tooltip);
        row.append(button);
        return button;
    }

    /** The right-hand text as it now stands — what a host would save. */
    public String modifiedText() {
        return rightPane.getText();
    }

    public TextEditor modifiedEditor() {
        return rightPane;
    }

    public int differenceCount() {
        return diffs.size();
    }

    /**
     * Re-diffs and re-decorates both panes.
     *
     * <p>Called after <b>every</b> change to the right-hand text, including one the user typed. A diff
     * whose block coordinates describe an older revision of the text is worse than no diff: the bands mark
     * innocent lines and a revert splices at the wrong place.</p>
     */
    private void recompute() {
        List<String> right = LineDiff.lines(rightPane.getText());
        diffs = LinesDiff.computeDetailed(left, right);
        leftPane.setDiffDecorations(DiffDecorations.forOriginal(diffs));
        rightPane.setDiffDecorations(DiffDecorations.forModified(diffs));
        if (current >= diffs.size()) current = Math.max(0, diffs.size() - 1);
        refresh();
    }

    private void refresh() {
        if (diffs.isEmpty()) {
            status.setText("No differences.");
        } else {
            status.setText("Difference " + (current + 1) + " of " + diffs.size());
        }
        boolean navigable = diffs.size() > 1;
        for (Button button : new Button[] {previous, next}) {
            button.setEnabled(navigable);
            button.setHitTest(navigable);
        }
    }

    private void step(int delta) {
        if (diffs.isEmpty()) return;
        current = Math.floorMod(current + delta, diffs.size());
        DetailedDiff block = diffs.get(current);
        leftPane.revealAt(new TextPoint(Math.min(block.lines().start1(), lastLine(left)), 0));
        rightPane.revealAt(new TextPoint(block.lines().start2(), 0));
        refresh();
    }

    private static int lastLine(List<String> lines) {
        return Math.max(0, lines.size() - 1);
    }

    /**
     * Replaces one difference in the right-hand text with the left's version of it.
     *
     * <p>Whole lines, spliced by index rather than by a text search — the two sides may contain identical
     * text elsewhere, and a search would find the wrong copy. The coordinates come from the diff that is
     * currently on screen, which is why {@link #recompute} runs after every edit.</p>
     */
    /**
     * Replaces one difference with the left-hand side's version of it.
     *
     * <p>Public as well as being what the chevron calls, because it is a real action a host may want on a
     * toolbar — and because a behaviour reachable only by clicking a pooled decoration is one no test can
     * drive.</p>
     */
    public void revertDifference(int index) {
        revert(index);
    }

    private void revert(int index) {
        if (index < 0 || index >= diffs.size()) return;
        DetailedDiff block = diffs.get(index);

        List<String> right = LineDiff.lines(rightPane.getText());
        int from = Math.min(block.lines().start2(), right.size());
        int to = Math.min(block.lines().end2(), right.size());

        List<String> updated = new ArrayList<>(right.subList(0, from));
        updated.addAll(left.subList(Math.min(block.lines().start1(), left.size()),
                Math.min(block.lines().end1(), left.size())));
        updated.addAll(right.subList(to, right.size()));

        // The write raises the buffer's change signal, which would re-enter recompute -- once here is
        // enough, and re-entering it mid-splice would diff against half-written text.
        syncing = true;
        try {
            rightPane.setText(join(updated));
        } finally {
            syncing = false;
        }
        recompute();
        onChanged.emit();
    }

    private static String join(List<String> lines) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) text.append(line).append('\n');
        return text.toString();
    }

    // ── Alignment ───────────────────────────────────────────────────────────────────────────────

    /**
     * Keeps the two panes showing the same part of the diff.
     *
     * <p>The same argument as {@code MergeView}'s, one text fewer: the two revisions have different line
     * counts, so holding them at the same pixel — or the same line — drifts them apart below the first
     * insertion. The blocks are the anchors.</p>
     */
    /** The per-frame hook, registered from {@link #connected()}. */
    public boolean tickFrame(float delta) {
        float leftTop = leftPane.scrollTop();
        float rightTop = rightPane.scrollTop();

        TextEditor moved = null;
        if (leftTop != lastLeft) moved = leftPane;
        else if (rightTop != lastRight) moved = rightPane;

        if (moved != null) {
            TextEditor follower = moved == leftPane ? rightPane : leftPane;
            float lineHeight = Math.max(1f, moved.lineHeight());
            int topLine = (int) Math.floor(moved.scrollTop() / lineHeight);
            float fraction = moved.scrollTop() - topLine * lineHeight;
            int mapped = mapLine(topLine, moved == leftPane);
            Box followerBox = follower.box();
            if (followerBox != null) {
                followerBox.setScroll(follower.scrollLeft(),
                        mapped * Math.max(1f, follower.lineHeight()) + fraction);
            }
        }

        lastLeft = leftPane.scrollTop();
        lastRight = rightPane.scrollTop();
        return true;
    }

    /** Translates a line between the two revisions, using the diff blocks as anchors. */
    private int mapLine(int line, boolean fromLeft) {
        int fromAt = 0;
        int toAt = 0;
        for (DetailedDiff block : diffs) {
            int blockFrom = fromLeft ? block.lines().start1() : block.lines().start2();
            int blockTo = fromLeft ? block.lines().end1() : block.lines().end2();
            if (blockFrom > line) break;
            if (blockTo <= line) {
                fromAt = blockTo;
                toAt = fromLeft ? block.lines().end2() : block.lines().end1();
            } else {
                // Inside a block: there is no finer correspondence than the block itself.
                return fromLeft ? block.lines().start2() : block.lines().start1();
            }
        }
        return Math.max(0, toAt + (line - fromAt));
    }

    /** The block currently navigated to, or null when there are none. */
    @Nullable
    public DetailedDiff currentDifference() {
        return diffs.isEmpty() ? null : diffs.get(current);
    }
}
