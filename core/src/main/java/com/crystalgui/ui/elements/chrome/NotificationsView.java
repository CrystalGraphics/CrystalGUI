package com.crystalgui.ui.elements.chrome;

import com.crystalgui.core.notify.Notification;
import com.crystalgui.core.notify.Notifications;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.ScrollerView;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.input.FocusPolicy;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The notification history, as a tool window — IntelliJ's <b>Notifications</b> (formerly its Event Log).
 *
 * <h3>The durable half of a two-surface idea</h3>
 *
 * <p>Both references show a message twice: once transiently, where you cannot miss it, and once in a list
 * you can go back to. Only the second is built here, and it is the half that matters — a balloon you were
 * not looking at is indistinguishable from no message at all, which is the single most common complaint
 * about a status line and the reason IntelliJ's event log exists. Balloons are a second surface and a
 * placement problem; they can be added over this model without changing it.</p>
 *
 * <h3>Appended, never rebuilt</h3>
 *
 * <p>Entries carry <b>action links</b>, so the list is something the user clicks on — and a notification
 * can arrive at any moment, including from a handler running inside one of those clicks. Rebuilding the
 * column on every arrival would detach the element being clicked, which this engine has paid for three
 * times over (the table header, the palette's key chips, the editor's gutter arrows). So an arrival
 * appends one card and touches nothing else; only "Clear all" empties the column.</p>
 *
 * <h3>Not a {@code ListView}</h3>
 *
 * <p>Virtualisation here would buy nothing and cost correctness: {@code ListView} sizes rows through a
 * strategy that must know a row's height, and these are variable — a title, any number of detail lines,
 * and an optional row of actions. With a history bounded at a hundred short cards, a plain scroller is
 * both simpler and honest about what it is.</p>
 */
public class NotificationsView extends UIElement {

    public static final String PANEL_CLASS = "__notifications__";
    /** The strip above the list, holding the section name and Clear all. */
    public static final String HEAD_CLASS = "__head__";
    public static final String TITLE_CLASS = "__title__";
    /** The Clear all link. A link rather than a button, matching the entries' own actions. */
    public static final String CLEAR_CLASS = "__clear__";
    public static final String LIST_CLASS = "__notification-list__";

    /** One notification. */
    public static final String ENTRY_CLASS = "__notification__";
    public static final String ICON_CLASS = "__severity__";
    public static final String MESSAGE_CLASS = "__message__";
    public static final String TIME_CLASS = "__time__";
    public static final String DETAIL_CLASS = "__detail__";
    public static final String ACTIONS_CLASS = "__actions__";
    public static final String ACTION_CLASS = "__action__";
    /** The line holding the title and the timestamp, which sit at opposite ends. */
    public static final String ENTRY_HEAD_CLASS = "__entry-head__";
    /** Shown only while there is nothing to show. */
    public static final String EMPTY_CLASS = "__notifications-empty__";
    /** A balloon's dismiss button. Not drawn in the list — see {@link #entryFor}. */
    public static final String CLOSE_CLASS = "__close__";

    /** Severity, as a class the sheet colours — never a colour written from here. */
    public static final String SEVERITY_PREFIX = "severity-";

    private final UIElement head = new UIElement();
    private final UIText title = new UIText("Timeline");
    private final UIText clearAll = new UIText("Clear all");
    private final ScrollerView list = new ScrollerView();
    private final UIText empty = new UIText("No notifications");

    private Connection arrivals;
    private Connection cleared;
    private Connection repeats;

    /**
     * The card showing each notification, so a repeat can update the one already on screen.
     *
     * <p>Keyed by identity — the service hands back the very object it is holding — and cleared with the
     * column, so it cannot outlive the elements it points at.</p>
     */
    private final Map<Notification, UIElement> cards = new IdentityHashMap<>();

    public NotificationsView() {
        addClass(PANEL_CLASS);

        head.addClass(HEAD_CLASS);
        title.addClass(TITLE_CLASS);
        title.setHitTest(false);
        clearAll.addClass(CLEAR_CLASS);
        clearAll.forceSelfSizeWidth();   // @see entryFor's timestamp
        clearAll.setFocusPolicy(FocusPolicy.CLICK);
        clearAll.onMouseDown.attachListener((element, event) -> {
            event.stopPropagation();
            Notifications.clear();
        }, false, true);
        head.addChild(title);
        head.addChild(clearAll);

        list.addClass(LIST_CLASS);
        empty.addClass(EMPTY_CLASS);
        empty.setHitTest(false);

        addInternalChild(head);
        addInternalChild(list);
        addInternalChild(empty);

        rebuild();
    }

    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    /**
     * Subscribes while attached, and marks what is there as read.
     *
     * <p>{@link Notifications} is static and outlives every view of it, so one that never unsubscribed
     * would keep itself and its whole subtree alive for the rest of the process — the same leak
     * {@code ListView} and {@code StatusBarView} both guard.</p>
     *
     * <p>Reading is deliberately <em>not</em> dismissing: opening the panel clears the bell, and the
     * messages stay until "Clear all". Folding the two together is how a panel you opened once quietly
     * threw away the thing you opened it for.</p>
     */
    @Override
    protected void onLayoutChanged() {
        super.onLayoutChanged();
        if (getAttachedWindow() != null) {
            if (arrivals == null) {
                arrivals = Notifications.onDidNotify.connect(this::append);
                cleared = Notifications.onDidClear.connect(this::rebuild);
                repeats = Notifications.onDidRepeat.connect(this::restate);
                rebuild();
            }
            Notifications.markAllRead();
        } else if (arrivals != null) {
            arrivals.disconnect();
            arrivals = null;
            if (cleared != null) cleared.disconnect();
            cleared = null;
            if (repeats != null) repeats.disconnect();
            repeats = null;
        }
    }

    /** Builds the column from scratch. Only on open and on Clear all — see the class note. */
    private void rebuild() {
        list.clearAllChildren();
        cards.clear();
        List<Notification> history = new ArrayList<>(Notifications.history());
        // NEWEST FIRST, which is the opposite of the history's own order. What you want on opening the
        // panel is what just happened, and a list that grows downwards puts it off the bottom of a full
        // one -- the reason both references lead with the most recent.
        for (int i = history.size() - 1; i >= 0; i--) {
            Notification notification = history.get(i);
            UIElement card = entryFor(notification);
            cards.put(notification, card);
            list.addChild(card);
        }
        refreshEmptyState();
    }

    /** One arrival, on top, leaving every other card alone. @see NotificationsView */
    private void append(Notification notification) {
        UIElement card = entryFor(notification);
        cards.put(notification, card);
        list.addChildAt(card, 0);
        refreshEmptyState();
    }

    /**
     * A repeat re-texts the card already on screen — it does not add one, and it does not move it.
     *
     * <p>Leaving it in place is the point: collapsing exists so a repeated message stops burying the rest
     * of the list, and re-ordering on every repeat would put it back to the top and undo half of that.</p>
     */
    private void restate(Notification notification) {
        UIElement card = cards.get(notification);
        if (card == null) return;
        UIText label = NotificationCard.titleLabelOf(card);
        if (label != null) label.setText(NotificationCard.titleOf(notification));
    }

    private void refreshEmptyState() {
        boolean nothing = Notifications.history().isEmpty();
        empty.setDisplayed(nothing);
        list.setDisplayed(!nothing);
    }

    /** @see NotificationCard */
    private UIElement entryFor(Notification notification) {
        // NO CLOSE BUTTON in the list: "Clear all" is the dismissal here, and a per-row close would be a
        // second way to say the same thing. A balloon passes one, because it has no Clear all.
        return NotificationCard.build(notification, null);
    }
}
