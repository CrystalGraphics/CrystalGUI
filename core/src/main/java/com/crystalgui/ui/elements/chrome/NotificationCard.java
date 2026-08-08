package com.crystalgui.ui.elements.chrome;

import com.crystalgui.core.notify.Notification;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.input.FocusPolicy;

import javax.annotation.Nullable;
import java.util.Calendar;
import java.util.Locale;

/**
 * How a notification looks, in one place — the card the history and the balloons both draw.
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
 */
final class NotificationCard {

    private NotificationCard() {
    }

    static UIElement build(Notification notification, @Nullable Runnable onClose) {
        UIElement entry = new UIElement();
        entry.addClass(NotificationsView.ENTRY_CLASS);

        UIElement icon = new UIElement();
        icon.addClass(NotificationsView.ICON_CLASS);
        // THE CLASS CARRIES THE SEVERITY, and the sheet turns it into a glyph and a colour. Writing either
        // from here would put the palette in Java, which is the split graph.css already makes for port
        // types and filetypes.css for file icons.
        icon.addClass(NotificationsView.SEVERITY_PREFIX
                + notification.getSeverity().name().toLowerCase(Locale.ROOT));
        icon.setHitTest(false);

        UIText message = new UIText(titleOf(notification));
        message.addClass(NotificationsView.MESSAGE_CLASS);
        message.setHitTest(false);

        UIText time = new UIText(describeTime(notification.getTimestamp()));
        time.addClass(NotificationsView.TIME_CLASS);
        time.setHitTest(false);
        // MUST REPORT ITS OWN WIDTH. UIText latches whether it self-sizes from its FIRST measurement,
        // which happens before any rule here has matched -- and a card built while its row is momentarily
        // narrow latches "does not size itself" permanently, taking whatever width it is handed. Not
        // hypothetical: the timestamp rendered as "7:" with the rest cut off, while `flex-shrink: 0` said
        // it should never have shrunk at all.
        time.forceSelfSizeWidth();

        UIElement head = new UIElement();
        head.addClass(NotificationsView.ENTRY_HEAD_CLASS);
        head.addChild(icon);
        head.addChild(message);
        head.addChild(time);
        if (onClose != null) {
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
            head.addChild(close);
        }
        entry.addChild(head);

        if (!notification.getDetail().isEmpty()) {
            UIText detail = new UIText(notification.getDetail());
            detail.addClass(NotificationsView.DETAIL_CLASS);
            detail.setHitTest(false);
            entry.addChild(detail);
        }

        if (!notification.actions().isEmpty()) {
            UIElement actions = new UIElement();
            actions.addClass(NotificationsView.ACTIONS_CLASS);
            for (Notification.Action action : notification.actions()) {
                UIText link = new UIText(action.label());
                link.addClass(NotificationsView.ACTION_CLASS);
                link.forceSelfSizeWidth();   // @see the timestamp above
                link.setFocusPolicy(FocusPolicy.CLICK);
                link.onMouseDown.attachListener((element, event) -> {
                    event.stopPropagation();
                    action.run().run();
                }, false, true);
                actions.addChild(link);
            }
            entry.addChild(actions);
        }
        return entry;
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

    /** The title label of a card built here, so a repeat can re-text it in place. */
    @Nullable
    static UIText titleLabelOf(UIElement card) {
        UIElement found = card.querySelector("." + NotificationsView.MESSAGE_CLASS);
        return found instanceof UIText ? (UIText) found : null;
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
