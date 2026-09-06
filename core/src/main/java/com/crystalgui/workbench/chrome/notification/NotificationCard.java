package com.crystalgui.workbench.chrome.notification;

import com.crystalgui.core.notify.Notification;
import com.crystalgui.core.notify.Notifications;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.ui.input.FocusPolicy;

import java.util.Calendar;
import java.util.Locale;

/**
 * How a notification looks, in one place — the card the history and the balloons both draw.
 *
 * <p>The renderer half of VS Code's {@code NotificationsList}, whose {@code NotificationRenderer} is
 * likewise shared by its toasts and its notification centre.</p>
 *
 * <h3>Extracted because the second consumer arrived</h3>
 *
 * <p>The two surfaces show the <b>same</b> thing: both references draw a message once transiently and
 * once in a list, and they are deliberately the same object with the same severity glyph, the same title
 * and timestamp line, the same detail and the same action links. Two builders would look identical on the
 * day they were written and drift on the first change to either — which is the failure {@code
 * CanvasOverlayMove} was extracted to end and {@code gui_curve.shader} is a standing monument to.</p>
 *
 * <p>The only difference is the close button, which a balloon has and a list entry does not: in a list,
 * "Clear all" is the dismissal and a per-row close would be a second way to say it.</p>
 *
 * <h3>An element that owns its parts, not a builder that returns a tree</h3>
 *
 * <p>This was a static {@code build()} handing back an opaque {@link UIElement}, which meant a consumer
 * wanting to update a card had to <b>find its way back in through the selector engine</b> — a
 * {@code querySelector(".__message__")} per repeat, matching on a CSS class as though from outside. Both
 * consumers did it, and both then had to cope with it returning null. Holding the label in a field makes
 * a repeat a {@code setText} and makes the class name a styling detail again rather than a lookup key.</p>
 */
class NotificationCard extends UIElement {

    private final Notification notification;
    private final UIText message;
    private final UIElement head;

    NotificationCard(Notification notification) {
        this.notification = notification;
        addClass(NotificationsView.ENTRY_CLASS);

        UIElement icon = new UIElement();
        icon.addClass(NotificationsView.ICON_CLASS);
        // THE CLASS CARRIES THE SEVERITY, and the sheet turns it into a glyph and a colour. Writing either
        // from here would put the palette in Java, which is the split graph.css already makes for port
        // types, and the file icons carry their own palette.
        icon.addClass(NotificationsView.SEVERITY_PREFIX
                + notification.getSeverity().name().toLowerCase(Locale.ROOT));
        icon.setHitTest(false);

        message = new UIText(titleOf(notification));
        message.addClass(NotificationsView.MESSAGE_CLASS);
        message.setHitTest(false);
        // SIZED BY ITS BOX, AND IT HAS TO SAY SO. The sheet gives this `flex-grow: 1; flex-shrink: 1;
        // min-width: 0` -- it takes the row's width and wraps against it. But the auto-detect decides
        // self-sizing from the FIRST recompute, which runs before this card has ever been laid out, reads
        // a content box of zero, and latches "self-sizing" permanently. A self-sizing UIText shapes ONE
        // line from the whole string, so `white-space: normal` on the message did nothing at all: the
        // card measured one line tall while the text drew across two and was clipped by the card's own
        // bottom edge. The timestamp beside it needs the opposite lock for the same race.

        UIText time = new UIText(describeTime(notification.getTimestamp()));
        time.addClass(NotificationsView.TIME_CLASS);
        time.setHitTest(false);
        // MUST REPORT ITS OWN WIDTH. UIText latches whether it self-sizes from its FIRST measurement,
        // which happens before any rule here has matched -- and a card built while its row is momentarily
        // narrow latches "does not size itself" permanently, taking whatever width it is handed. Not
        // hypothetical: the timestamp rendered as "7:" with the rest cut off, while `flex-shrink: 0` said
        // it should never have shrunk at all.

        UIElement head = new UIElement();
        head.addClass(NotificationsView.ENTRY_HEAD_CLASS);
        head.append(icon);
        head.append(message);
        head.append(time);
        append(head);
        this.head = head;

        if (!notification.getDetail().isEmpty()) {
            UIText detail = new UIText(notification.getDetail());
            detail.addClass(NotificationsView.DETAIL_CLASS);
            detail.setHitTest(false);
            // THE SAME LOCK THE MESSAGE NEEDS, and for the same race. The sheet gives this `width: 100%`,
            // which the latched self-sizing width overrides at IMPORTANT -- so the detail took the width
            // of its whole unwrapped string and ran out past the card's own border rather than wrapping
            // inside it. A balloon is sized to its content, so this also made the CARD too narrow to
            // contain the text it was measuring itself from.
            append(detail);
        }

        boolean silenceable = notification.getNeverShowAgainId() != null;
        if (!notification.actions().isEmpty() || !notification.secondaryActions().isEmpty()
                || silenceable) {
            UIElement actions = new UIElement();
            actions.addClass(NotificationsView.ACTIONS_CLASS);
            for (Notification.Action action : notification.actions()) {
                actions.append(link(action.label(), action.run(), false));
            }
            // QUIETER, not hidden. VS Code puts its secondaries behind a `...` menu, which costs a click
            // to discover a verb that already fits on the card -- and these cards are wider than its
            // toasts. @see Notification#secondaryActions
            for (Notification.Action action : notification.secondaryActions()) {
                actions.append(link(action.label(), action.run(), true));
            }
            if (silenceable) {
                // LAST, and secondary. It is the one link that acts on every FUTURE message rather than on
                // this one, so it must not sit where "Retry" is expected.
                actions.append(link("Don't show again",
                        () -> Notifications.suppress(notification.getNeverShowAgainId()), true));
            }
            append(actions);
        }
    }

    private static UIText link(String label, Runnable run, boolean secondary) {
        UIText link = new UIText(label);
        link.addClass(NotificationsView.ACTION_CLASS);
        if (secondary) link.addClass(NotificationsView.ACTION_SECONDARY_CLASS);
        link.setFocusPolicy(FocusPolicy.CLICK);
        link.onMouseDown.attachListener((element, event) -> {
            event.stopPropagation();
            run.run();
        }, false, true);
        return link;
    }

    /**
     * Adds the dismiss button. Balloons only — see the class note.
     *
     * <p>Separate from the constructor because the handler usually needs the card itself, and a callback
     * passed in at construction cannot refer to something that does not exist yet. The balloon layer used
     * to work around that with a one-element array.</p>
     */
    NotificationCard withClose(Runnable onClose) {
        // AN ELEMENT WITH A SHAPE, not a UIText carrying "✕". The bundled font has no U+2715 and drew
        // tofu -- the same reason the breadcrumb separator is a shape and UIText carries an ellipsis
        // fallback. A glyph in Java is also a look the cascade cannot reach.
        UIElement close = new UIElement();
        close.addClass(NotificationsView.CLOSE_CLASS);
        close.setFocusPolicy(FocusPolicy.CLICK);
        close.onMouseDown.attachListener((element, event) -> {
            event.stopPropagation();
            onClose.run();
        }, false, true);
        head.append(close);
        return this;
    }

    Notification notification() {
        return notification;
    }

    /** Re-reads the notification this card is about — today, its repeat count. @see #titleOf */
    void restate() {
        message.setText(titleOf(notification));
    }

    /**
     * The title, with a repeat count when the same message has arrived more than once.
     *
     * <p>Appended to the title rather than given a slot of its own: it is a property <em>of</em> the
     * message, it is two characters wide, and a fourth element in the head row would have to be sized and
     * positioned for a case that usually does not occur.</p>
     */
    static String titleOf(Notification notification) {
        int repeats = notification.getRepeats();
        return repeats > 1 ? notification.getMessage() + "  ×" + repeats : notification.getMessage();
    }

    /**
     * IntelliJ's own scheme: a clock time for today, a word for yesterday, a date before that.
     *
     * <p>Relative labels are what make a list readable at a glance, and they are only useful near the
     * present — "3 days ago" is harder to place than the date it stands for.</p>
     *
     * <p><b>Computed when the card is built, not live.</b> A card made just before midnight goes on saying
     * a clock time into the next day until something rebuilds the column. Both references have the same
     * property; the alternative is a per-frame pass over every entry to restate something nobody is
     * looking at.</p>
     */
    static String describeTime(long timestamp) {
        Calendar when = Calendar.getInstance();
        when.setTimeInMillis(timestamp);
        Calendar now = Calendar.getInstance();

        if (sameDay(when, now)) {
            return String.format(Locale.ROOT, "%d:%02d %s",
                    hour12(when), when.get(Calendar.MINUTE),
                    when.get(Calendar.AM_PM) == Calendar.AM ? "AM" : "PM");
        }
        Calendar yesterday = Calendar.getInstance();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(when, yesterday)) return "Yesterday";

        return String.format(Locale.ROOT, "%d/%d/%d",
                when.get(Calendar.DAY_OF_MONTH), when.get(Calendar.MONTH) + 1, when.get(Calendar.YEAR));
    }

    private static int hour12(Calendar calendar) {
        int hour = calendar.get(Calendar.HOUR);
        return hour == 0 ? 12 : hour;
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }
}
