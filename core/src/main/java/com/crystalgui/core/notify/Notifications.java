package com.crystalgui.core.notify;

/**
 * How code with no desktop in hand says something: a contribution, a job, a language module. It holds nothing — the
 * history, the unread count and the groups are the desktop's {@link NotificationService}, and this posts to the one
 * the caller's document belongs to.
 *
 * <pre>{@code
 * Notifications.info("JDK sources downloaded");
 * Notifications.show(Notification.error("Save failed").withDetail(reason));
 * }</pre>
 *
 * <p>Easy to get wrong: with no service installed (a bare headless run) it logs and drops, so it answers null. Code
 * that needs the history, the handle or the events asks the service, never this.</p>
 */
public final class Notifications {

    private Notifications() {
    }

    public static void info(String message) {
        show(Notification.info(message));
    }

    public static void warning(String message) {
        show(Notification.warning(message));
    }

    public static void error(String message) {
        show(Notification.error(message));
    }

    /** Posts to the caller's desktop; the handle, or null when there is none to post to. */
    public static NotificationHandle show(Notification notification) {
        if (notification == null) return null;
        NotificationService service = NotificationService.current();
        if (service == null) {
            NotificationService.dropped(notification);
            return null;
        }
        return service.show(notification);
    }
}
