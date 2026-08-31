package com.crystalgui.chrome.notification;

import com.crystalgui.chrome.status.StatusBarView;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.core.notify.Notification;
import com.crystalgui.core.notify.NotificationEvent;
import com.crystalgui.core.notify.Notifications;
import com.crystalgui.core.signal.ConnectionGroup;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.scroll.ScrollerView;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.ui.input.FocusPolicy;

import javax.annotation.Nullable;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The notification history, as a tool window — IntelliJ's <b>Notifications</b> (formerly its Event Log),
 * VS Code's {@code NotificationsCenter}.
 *
 * <h3>The durable half of a two-surface idea</h3>
 *
 * <p>Both references show a message twice: once transiently, where you cannot miss it, and once in a list
 * you can go back to. This is the second, {@link NotificationBalloons} is the first, and both read the
 * same {@link Notifications} model through the same {@link NotificationEvent} — which is exactly how VS
 * Code hangs its toasts and its centre off one {@code NotificationsModel}.</p>
 *
 * <h3>Spliced, never rebuilt</h3>
 *
 * <p>Entries carry <b>action links</b>, so the list is something the user clicks on — and a notification
 * can arrive at any moment, including from a handler running inside one of those clicks. Rebuilding the
 * column on every arrival would detach the element being clicked, which this engine has paid for three
 * times over (the table header, the palette's key chips, the editor's gutter arrows). So each event moves
 * exactly what it names: an arrival adds one card, an eviction removes one, a repeat re-texts one. Only
 * {@link NotificationEvent.Kind#CLEARED} empties the column, and only because everything went at once.</p>
 *
 * <h3>Not a {@code ListView}</h3>
 *
 * <p>Virtualisation here would buy nothing and cost correctness: {@code ListView} sizes rows through a
 * strategy that must know a row's height, and these are variable — a title, any number of detail lines,
 * and an optional row of actions. With a history bounded at a hundred short cards, a plain scroller is
 * both simpler and honest about what it is. (VS Code does virtualise its centre, because its history is
 * unbounded.)</p>
 */
public class NotificationsView extends UINode {

    public static final Name NAME = Name.of("notificationsview");

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
    /** A quieter action, and the "Don't show again" link. @see com.crystalgui.core.notify.Notification */
    public static final String ACTION_SECONDARY_CLASS = "__action-secondary__";
    /** The line holding the title and the timestamp, which sit at opposite ends. */
    public static final String ENTRY_HEAD_CLASS = "__entry-head__";
    /** Shown only while there is nothing to show. */
    public static final String EMPTY_CLASS = "__notifications-empty__";
    /** A balloon's dismiss button. Not drawn in the list — see the class note. */
    public static final String CLOSE_CLASS = "__close__";

    /** Severity, as a class the sheet colours — never a colour written from here. */
    public static final String SEVERITY_PREFIX = "severity-";

    private final UINode head = new UINode();
    private final UIText title = new UIText("Timeline");
    private final UIText clearAll = new UIText("Clear all");
    private final ScrollerView list = new ScrollerView();
    private final UIText empty = new UIText("No notifications");

    private final ConnectionGroup subscriptions = new ConnectionGroup();

    /**
     * The card showing each notification, so a repeat or an eviction can reach the one already on screen.
     *
     * <p>Keyed by identity — the service hands back the very object it is holding — and cleared with the
     * column, so it cannot outlive the elements it points at.</p>
     */
    private final Map<Notification, NotificationCard> cards = new IdentityHashMap<>();

    public NotificationsView() {
        super(NAME);
        addClass(PANEL_CLASS);

        head.addClass(HEAD_CLASS);
        title.addClass(TITLE_CLASS);
        title.setHitTest(false);
        clearAll.addClass(CLEAR_CLASS);
        clearAll.setFocusPolicy(FocusPolicy.CLICK);
        clearAll.onMouseDown.attachListener((element, event) -> {
            event.stopPropagation();
            Notifications.clear();
        }, false, true);
        head.append(title);
        head.append(clearAll);

        list.addClass(LIST_CLASS);
        empty.addClass(EMPTY_CLASS);
        empty.setHitTest(false);

        append(head);
        append(list);
        append(empty);

        rebuild();
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
    protected void disconnected() {
        subscriptions.disconnectAll();
    }

    /**
     * <p>{@code onWindowChanged(previous, current)} has no counterpart — the node tree reports
     * connect and disconnect separately — and the split is faithful: the old hook released
     * unconditionally and re-subscribed only when there was a window.</p>
     */
    @Override
    protected void connected() {
        subscriptions.disconnectAll();
        UIDocument current = document();
        if (current == null) return;
        subscriptions.add(Notifications.onDidChange.connect(this::apply));
        rebuild();
        Notifications.markAllRead();
    }

    /** One change, applied where it landed. @see NotificationsView */
    private void apply(NotificationEvent event) {
        switch (event.kind()) {
            case ADDED -> {
                Notification notification = event.notification();
                if (notification == null) return;
                NotificationCard card = new NotificationCard(notification);
                cards.put(notification, card);
                // NEWEST FIRST, which is the opposite of the history's own order -- see rebuild().
                list.insertAt(0, card);
            }
            case CHANGED -> {
                NotificationCard card = cards.get(event.notification());
                if (card != null) {
                    // LEFT WHERE IT IS. Collapsing exists so a repeated message stops burying the rest of
                    // the list, and moving it back to the top on every repeat would undo half of that.
                    card.restate();
                }
            }
            case REMOVED -> {
                NotificationCard card = cards.remove(event.notification());
                if (card != null) card.removeSelf();
            }
            case CLEARED -> rebuild();
        }
        refreshEmptyState();
        // READ ON ARRIVAL TOO, not only on open. The panel is subscribed exactly while it is attached, so
        // reaching here means it is on screen — and a bell that ticks up for a message sitting visible in
        // front of the user is counting something they have already seen. This used to fall out of running
        // on every layout pass; with the window hook it has to be said.
        Notifications.markAllRead();
    }

    /** Builds the column from scratch. Only on open and on Clear all — see the class note. */
    private void rebuild() {
        list.removeAll();
        cards.clear();
        List<Notification> history = Notifications.history();
        // NEWEST FIRST, which is the opposite of the history's own order. What you want on opening the
        // panel is what just happened, and a list that grows downwards puts it off the bottom of a full
        // one -- the reason both references lead with the most recent.
        for (int i = history.size() - 1; i >= 0; i--) {
            Notification notification = history.get(i);
            NotificationCard card = new NotificationCard(notification);
            cards.put(notification, card);
            list.append(card);
        }
        refreshEmptyState();
    }

    private void refreshEmptyState() {
        boolean nothing = Notifications.isEmpty();
        empty.setDisplayed(nothing);
        list.setDisplayed(!nothing);
    }
}
