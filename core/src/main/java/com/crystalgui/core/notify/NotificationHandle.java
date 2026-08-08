package com.crystalgui.core.notify;

import com.crystalgui.core.signal.Connection;
import com.crystalgui.core.signal.Signal;

/**
 * A live handle on something already announced — VS Code's {@code INotificationHandle}, IntelliJ's
 * {@code Notification.expire()}.
 *
 * <p>Ported from {@code vs/platform/notification/common/notification.ts}.</p>
 *
 * <h3>Why a producer needs to be able to take it back</h3>
 *
 * <p>Announcing was one-way: {@code show()} returned nothing, so a message stood until the history aged it
 * out whether or not it was still true. That is fine for "saved notes.txt" and wrong for anything whose
 * subject outlives the sentence — "disconnected" after reconnecting, "compile failed" after it compiles,
 * a long operation that has since finished. The alternative producers reach for is a second notification
 * saying the first no longer applies, which is how a history fills with corrections.</p>
 *
 * <p>{@link #close()} is IntelliJ's {@code expire()} and VS Code's {@code close()}: it removes the entry,
 * which reaches every view as {@link NotificationEvent.Kind#REMOVED} — the same kind an eviction uses, so
 * no view needed a new case to handle it.</p>
 *
 * <p><b>No producer holds one yet.</b> Said plainly because this codebase has been burned by API kept
 * alive on speculation; the difference from that case is that this one is a complete mechanism with a
 * tested path through the model and the views, rather than a field nothing consults.</p>
 */
public final class NotificationHandle {

    private final Notification notification;

    /** Fires once, when this notification leaves the history — by {@link #close()} or by ageing out. */
    public final Signal.Action onDidClose = new Signal.Action();

    NotificationHandle(Notification notification) {
        this.notification = notification;
    }

    public Notification notification() {
        return notification;
    }

    /** Whether this is still in the history. */
    public boolean isOpen() {
        return Notifications.holds(notification);
    }

    /**
     * Revises what it says, in place.
     *
     * <p>Arrives as {@link NotificationEvent.Kind#CHANGED}, which every view already handles because a
     * repeat uses it — so a card is re-texted where it sits rather than moved to the top. A revision is not
     * a new event and should not reorder the list or re-ring the bell.</p>
     */
    public void updateMessage(String message) {
        if (message == null || message.equals(notification.getMessage())) return;
        if (!isOpen()) return;
        notification.setMessage(message);
        Notifications.announceChanged(notification);
    }

    /** Takes it out of the history. Idempotent. */
    public void close() {
        if (!isOpen()) return;
        Notifications.withdraw(notification);
    }

    /** Called by the service when the notification leaves for any reason. */
    void notifyClosed() {
        onDidClose.emit();
    }
}
