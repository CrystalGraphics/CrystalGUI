package com.crystalgui.ui.elements.chrome;

import com.crystalgui.core.async.ActiveJob;
import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIFrameTicker;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.ProgressBar;
import com.crystalgui.ui.elements.UIText;

import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import dev.vfyjxf.taffy.style.FlexDirection;

import java.util.List;

/**
 * The status bar's progress indicator — <b>one job inline, the rest behind a count</b>.
 *
 * <p>IntelliJ's shape, and the reason is that the chrome's cost must not depend on how much is running.
 * One line shows the most recently begun job; a {@code (N)} says how many others there are; clicking opens
 * {@link ProcessesPopover} with all of them. VS Code stacks a notification per operation instead, which is
 * fine for work the user asked for and noise for work they did not.</p>
 *
 * <h3>It PULLS, every frame, and that is not a detail</h3>
 *
 * <p>Nothing pushes into this widget. It reads {@link JobScheduler#active()} from its own
 * {@link UIFrameTicker}, on the UI thread, like any other widget reading its model.</p>
 *
 * <p>A push would be the documented crash: a signal emitted by a worker carries that thread into every
 * listener, and one {@code setText} here reaches {@code invalidateStyleMatch()} and mutates the style
 * engine's dirty-match set while the frame is copying it — an {@code ArrayIndexOutOfBoundsException} out
 * of {@code advanceFrame} with nothing about progress anywhere in the trace. The scheduler's snapshot is
 * immutable and taken on this thread, so there is nothing to race.</p>
 */
public class ProgressStatusItem extends UIElement {

    public static final String ITEM_CLASS = "__progress-item__";
    public static final String LABEL_CLASS = "__label__";
    public static final String COUNT_CLASS = "__count__";
    public static final String CANCEL_CLASS = "__cancel__";

    private final UIText label = new UIText("");
    private final ProgressBar bar = new ProgressBar();
    private final UIText count = new UIText("");
    private final UIElement cancel = new UIElement();

    private final ProcessesPopover popover = new ProcessesPopover();

    /** What is being shown, so a frame that changes nothing writes nothing. */
    private String shownWhat = "";
    private float shownFraction = Float.NaN;
    private int shownCount = -1;
    private boolean ticking;

    public ProgressStatusItem() {
        addClass(ITEM_CLASS);
        // NOT StatusBarView.ITEM_CLASS. That class marks a REGISTRY ENTRY, and every query for the bar
        // contents collects by it -- so wearing it made this reserved slot count as an entry, shifting
        // every index and count in the bar. A reserved slot is styled by its own class, as breadcrumbs is.
        addClass(StatusBarView.CLICKABLE_CLASS);
        StyleGroup.defaultPipeline(getStyle().getLayoutGroup(),
                l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER));

        label.addClass(LABEL_CLASS);
        count.addClass(COUNT_CLASS);
        cancel.addClass(CANCEL_CLASS);

        addInternalChild(label);
        addInternalChild(bar);
        addInternalChild(cancel);
        addInternalChild(count);

        // The whole item opens the list; only the glyph cancels. Two targets, because "cancel" and "show
        // me the rest" are different intentions and the × is the smaller of the two.
        this.onMouseUp.attachListener((element, event) -> {
            if (event.isWasPressTarget()) togglePopover();
        }, false, false);
        cancel.onMouseUp.attachListener((element, event) -> {
            if (event.isWasPressTarget()) cancelInline();
            event.stopPropagation();
        }, false, false);

        setVisible(false);
    }

    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    /**
     * Whether anything is on screen — the only observable that matters, and the one that was wrong.
     *
     * <p>A widget fed nothing looks exactly like a widget correctly showing nothing, so this exists to be
     * asserted rather than looked at. @see com.crystalgui.ui.ProgressChromeTest</p>
     */
    public boolean isShowing() {
        return shownCount > 0;
    }

    /** The primary line as drawn, for the same reason. */
    public String shownLabel() {
        return shownWhat;
    }

    /** The list this item opens. Public so a test can assert against it without a click. */
    public ProcessesPopover popover() {
        return popover;
    }

    /**
     * <b>The attach hook, and {@code ElementAdded} is not it.</b>
     *
     * <p>{@code registerTicker} lives on {@link UIWindow}, so a ticker can only be registered once there
     * is one. {@code DOMEvent.ElementAdded} fires when an element gains a <em>parent</em> — which for
     * anything a widget builds in its own constructor is long before any window exists, so the one
     * registration attempt found none and never retried. Nothing threw; the indicator simply never
     * appeared. {@code onWindowChanged} is the moment that actually matters.</p>
     */
    @Override
    protected void onWindowChanged(UIWindow previous, UIWindow current) {
        if (current != null) startTicking();
    }

    private void startTicking() {
        UIWindow window = getAttachedWindow();
        if (window == null || ticking) return;
        ticking = true;
        window.registerTicker(new Ticker());
    }

    /** Separate object so the item's own public surface has no {@code tickFrame} to call by mistake. */
    private final class Ticker implements UIFrameTicker {
        @Override
        public boolean tickFrame(float deltaSeconds) {
            if (getAttachedWindow() == null) return false;
            refresh();
            return true;
        }
    }

    /**
     * Reads the scheduler and writes only what changed.
     *
     * <p>{@code hasShared()} rather than {@code shared()}, so that a process which never scheduled
     * anything does not get a thread pool because its status bar exists — the same guard the drain site
     * uses, and for the same reason.</p>
     */
    private void refresh() {
        List<ActiveJob> active = JobScheduler.hasShared()
                ? JobScheduler.shared().active() : List.of();

        if (active.isEmpty()) {
            if (shownCount != 0) {
                shownCount = 0;
                shownWhat = "";
                shownFraction = Float.NaN;
                setVisible(false);
                popover.hide();
            }
            return;
        }

        ActiveJob first = active.get(0);
        if (shownCount == 0) setVisible(true);

        String what = first.state().what();
        if (!what.equals(shownWhat)) {
            shownWhat = what;
            label.setText(what);
        }

        float fraction = first.state().fraction();
        if (fraction != shownFraction) {
            shownFraction = fraction;
            bar.setFraction(fraction);
        }

        if (active.size() != shownCount) {
            shownCount = active.size();
            count.setText(active.size() > 1 ? "(" + active.size() + ")" : "");
            boolean many = active.size() > 1;
            StyleGroup.importantPipeline(count.getStyle().getLayoutGroup(),
                    l -> l.display(many ? TaffyDisplay.FLEX : TaffyDisplay.NONE));
        }

        if (popover.isOpen()) popover.refresh(active);
    }

    private void togglePopover() {
        if (popover.isOpen()) {
            popover.hide();
            return;
        }
        UIWindow window = getAttachedWindow();
        if (window == null || !JobScheduler.hasShared()) return;
        popover.refresh(JobScheduler.shared().active());
        popover.showFor(this, this);
    }

    /** Cancels the job the inline line is showing — the one the × sits beside. */
    private void cancelInline() {
        if (!JobScheduler.hasShared()) return;
        List<ActiveJob> active = JobScheduler.shared().active();
        if (active.isEmpty()) return;
        JobScheduler.shared().cancel(active.get(0).key());
    }

    private void setVisible(boolean visible) {
        StyleGroup.importantPipeline(getStyle().getLayoutGroup(),
                l -> l.display(visible ? TaffyDisplay.FLEX : TaffyDisplay.NONE));
    }
}
