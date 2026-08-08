package com.crystalgui.core.notify;

import javax.annotation.Nullable;

/**
 * One change to the notification history — VS Code's {@code INotificationChangeEvent}.
 *
 * <h3>Why one event with a kind, and not four signals</h3>
 *
 * <p>This replaced {@code onDidNotify}, {@code onDidRepeat} and {@code onDidClear}: three separate
 * {@code Signal}s, each of which a view had to connect to individually and interpret on its own. Both
 * references model the notification list as <em>one</em> observable collection emitting typed changes —
 * VS Code's {@code onDidChangeNotification} carries {@code {kind, index, item}} and both its views
 * ({@code NotificationsToasts} and {@code NotificationsCenter}) subscribe to that single event.</p>
 *
 * <p><b>The missing kind was {@link Kind#REMOVED}, and its absence was a live bug.</b> The history is
 * bounded, so the oldest entry is dropped once it is full — but with only "something arrived" and
 * "everything went" to announce, an eviction was <em>unannouncable</em>. {@code NotificationsView} only
 * ever appended, so past the limit the panel kept showing cards for notifications the model had already
 * thrown away, and the column grew without bound while the history stayed at its cap. That is not a view
 * defect: there was no way for the view to find out.</p>
 *
 * <h3>{@link Kind#CLEARED} rather than one {@link Kind#REMOVED} per entry</h3>
 *
 * <p>"Clear all" empties a full history in one act. Announced entry by entry it is a hundred events, each
 * of which a view would splice out of its column individually — a hundred tree mutations to reach an empty
 * list it could have reached by rebuilding once. The kind exists so a view can tell "one went" from "they
 * all went" and pick the cheaper response to each.</p>
 *
 * @param kind         what happened
 * @param notification the notification it happened to, or null for {@link Kind#CLEARED}
 * @param index        where it sat in the history (oldest first), or -1 when there is no single place
 */
public record NotificationEvent(Kind kind, @Nullable Notification notification, int index) {

    public enum Kind {
        /** A new notification joined the history, at {@link NotificationEvent#index()}. */
        ADDED,
        /**
         * One already in the history changed in place — today, its repeat count went up.
         *
         * <p>The object is the same one a view is already showing, so the response is to re-read that
         * card rather than to add another. A repeat delivered as an arrival is precisely the flood the
         * collapse exists to stop.</p>
         */
        CHANGED,
        /** One left the history — it aged out of the bound. @see NotificationEvent */
        REMOVED,
        /** The whole history went at once. @see NotificationEvent */
        CLEARED
    }

    public static NotificationEvent added(Notification notification, int index) {
        return new NotificationEvent(Kind.ADDED, notification, index);
    }

    public static NotificationEvent changed(Notification notification, int index) {
        return new NotificationEvent(Kind.CHANGED, notification, index);
    }

    public static NotificationEvent removed(Notification notification, int index) {
        return new NotificationEvent(Kind.REMOVED, notification, index);
    }

    public static NotificationEvent cleared() {
        return new NotificationEvent(Kind.CLEARED, null, -1);
    }
}
