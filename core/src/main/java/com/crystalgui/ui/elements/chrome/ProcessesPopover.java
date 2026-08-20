package com.crystalgui.ui.elements.chrome;

import com.crystalgui.core.async.ActiveJob;
import com.crystalgui.core.async.JobKey;
import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.Popover;
import com.crystalgui.ui.elements.ProgressBar;
import com.crystalgui.ui.elements.UIText;

import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import dev.vfyjxf.taffy.style.FlexDirection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IntelliJ's <b>Processes</b> popup — every running job, each with its own bar and its own cancel.
 *
 * <p>{@link ProgressStatusItem} shows one; this shows all of them, so the chrome stays one line wide
 * however much is running. A {@code Mode.AUTO} popover, so a press anywhere else dismisses it and Escape
 * closes it, both for free.</p>
 *
 * <h3>Rows are reused, not rebuilt</h3>
 *
 * <p>Keyed by {@link JobKey} and updated in place. Rebuilding the list every frame would replace the very
 * element a press is being dispatched through — the trap this project has paid for at the table header,
 * the command palette's key chips, and the editor's gutter arrows. A row here carries a cancel button and
 * this list refreshes while a job runs, so it is the same hazard exactly.</p>
 */
public class ProcessesPopover extends Popover {

    public static final String POPOVER_CLASS = "__processes__";
    public static final String TITLE_CLASS = "__title__";
    public static final String ROW_CLASS = "__process-row__";
    public static final String LABEL_CLASS = "__label__";
    public static final String DETAIL_CLASS = "__detail__";
    public static final String CANCEL_CLASS = "__cancel__";

    /** On a row whose cancel has been asked for and not yet acknowledged. */
    public static final String CANCELLING_CLASS = "__cancelling__";

    /** The dismiss link at the foot — IntelliJ's {@code Hide processes (N)}. */
    public static final String HIDE_CLASS = "__hide__";

    private final UIElement rows = new UIElement();
    private final Map<JobKey, Row> byKey = new LinkedHashMap<>();

    public ProcessesPopover() {
        addClass(POPOVER_CLASS);
        setMode(Mode.AUTO);
        // ABOVE THE BAR, WITH A GAP. The default is BOTTOM and there is no room below a status bar, so it
        // relied on the flip -- which lands it flush against the bar, reading as a panel growing out of
        // the thing it covers. IntelliJ's Processes popup floats clear of its own status bar; this is that.
        setPreferredSide(com.crystalgui.ui.AnchoredPlacement.Side.TOP, 6f);
        StyleGroup.defaultPipeline(getStyle().getLayoutGroup(),
                l -> l.flexDirection(FlexDirection.COLUMN));

        UIText title = new UIText("Processes");
        title.addClass(TITLE_CLASS);
        addChild(title);

        StyleGroup.defaultPipeline(rows.getStyle().getLayoutGroup(),
                l -> l.flexDirection(FlexDirection.COLUMN));
        addChild(rows);

        // A LINK, not a button: it dismisses this popup and acts on nothing, which is the distinction
        // both references draw. The count is in the text because that is the only thing it tells you --
        // "hide" alone would be a control whose effect you cannot predict.
        hide.addClass(HIDE_CLASS);
        hide.onMouseUp.attachListener((source, event) -> {
            if (event.isWasPressTarget()) hide();
        }, false, false);
        addChild(hide);
    }

    private final UIText hide = new UIText("Hide processes");

    /**
     * Brings the list in line with {@code active}, reusing rows.
     *
     * <p>Order is the scheduler's — most recently begun first — so the row at the top is the one the
     * status bar is showing inline, and the eye does not have to find it again.</p>
     */
    public void refresh(List<ActiveJob> active) {
        List<JobKey> present = new ArrayList<>(active.size());
        for (ActiveJob job : active) {
            present.add(job.key());
            Row row = byKey.get(job.key());
            if (row == null) {
                row = new Row(job.key());
                byKey.put(job.key(), row);
                rows.addChild(row.element);
            }
            row.update(job);
        }

        byKey.entrySet().removeIf(entry -> {
            if (present.contains(entry.getKey())) return false;
            entry.getValue().element.removeSelf();
            return true;
        });

        hide.setText("Hide processes (" + active.size() + ")");

        // Keep the visual order the scheduler's, without rebuilding: a row that is already in the right
        // place is left alone, so nothing under the pointer moves unless the set itself changed.
        for (int index = 0; index < present.size(); index++) {
            Row row = byKey.get(present.get(index));
            if (row != null && rows.getChildren().indexOf(row.element) != index) {
                rows.addChildAt(row.element, index);
            }
        }
    }

    /** One job. Built once, updated in place. */
    private static final class Row {
        private final UIElement element = new UIElement();
        private final UIText label = new UIText("");
        private final UIText detail = new UIText("");
        private final ProgressBar bar = new ProgressBar();
        private final UIElement cancel = new UIElement();

        private String shownLabel = "";
        private String shownDetail = "";
        private float shownFraction = Float.NaN;
        private boolean shownCancelling;

        private Row(JobKey key) {
            element.addClass(ROW_CLASS);
            StyleGroup.defaultPipeline(element.getStyle().getLayoutGroup(),
                    l -> l.flexDirection(FlexDirection.COLUMN));

            label.addClass(LABEL_CLASS);
            detail.addClass(DETAIL_CLASS);
            cancel.addClass(CANCEL_CLASS);

            UIElement top = new UIElement();
            StyleGroup.defaultPipeline(top.getStyle().getLayoutGroup(),
                    l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER));
            top.addChild(label);
            element.addChild(top);
            element.addChild(detail);

            UIElement bottom = new UIElement();
            StyleGroup.defaultPipeline(bottom.getStyle().getLayoutGroup(),
                    l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER));
            bottom.addChild(bar);
            bottom.addChild(cancel);
            element.addChild(bottom);

            cancel.onMouseUp.attachListener((source, event) -> {
                if (!event.isWasPressTarget()) return;
                if (JobScheduler.hasShared()) JobScheduler.shared().cancel(key);
                event.stopPropagation();
            }, false, false);
        }

        private void update(ActiveJob job) {
            String what = job.state().what();
            if (!what.equals(shownLabel)) {
                shownLabel = what;
                label.setText(what);
            }

            // THE TRANSFER READOUT, COMPOSED INTO THE DETAIL LINE rather than given a widget of its own.
            //
            // A determinate bar with no numbers says how far along and not how big, how fast or how long
            // is left, which are the three things somebody watching a download wants. The state renders
            // it — the chrome cannot, because `done` and `total` are just longs here and a job stepping
            // through 1,178 files would come out as "12 of 1178 bytes". @see ProgressState#summary
            //
            // Into the existing line because the two are the same KIND of thing (secondary, about this
            // one job) and a third row would push the bar down and make every row taller for the jobs
            // that have no readout at all.
            String summary = job.state().summary();
            String detailed = job.state().detail();
            if (summary != null) {
                detailed = detailed.isEmpty() ? summary : detailed + " · " + summary;
            }
            final String item = detailed;
            if (!item.equals(shownDetail)) {
                shownDetail = item;
                detail.setText(item);
                StyleGroup.importantPipeline(detail.getStyle().getLayoutGroup(),
                        l -> l.display(item.isEmpty() ? TaffyDisplay.NONE : TaffyDisplay.FLEX));
            }

            float fraction = job.state().fraction();
            if (fraction != shownFraction) {
                shownFraction = fraction;
                bar.setFraction(fraction);
            }

            // A CLASS, not a pseudo-class. State a widget flips from its own listener is re-evaluated on
            // our terms, not the engine's -- :checked, :disabled and :hover have each cost a round here.
            if (job.cancelRequested() != shownCancelling) {
                shownCancelling = job.cancelRequested();
                if (shownCancelling) {
                    element.addClass(CANCELLING_CLASS);
                    cancel.setHitTest(false);
                } else {
                    element.removeClass(CANCELLING_CLASS);
                    cancel.setHitTest(true);
                }

            }
        }
    }
}
