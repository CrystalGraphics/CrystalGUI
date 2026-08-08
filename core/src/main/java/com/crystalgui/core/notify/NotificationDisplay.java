package com.crystalgui.core.notify;

/**
 * How loudly a notification group speaks — IntelliJ's {@code NotificationDisplayType}.
 *
 * <p>Ported from {@code com.intellij.notification.NotificationDisplayType}, which is registered per
 * {@code NotificationGroup} in plugin XML and then <b>overridable by the user</b> in Settings → Appearance
 * &amp; Behavior → Notifications. That user override is the entire point: it is how a chatty producer stops
 * owning the screen without its author having to agree.</p>
 *
 * <h3>Why this belongs to the group and not to the notification</h3>
 *
 * <p>A producer knows what happened; it does not know how much of your attention that is worth. The same
 * message is worth a balloon to someone debugging a shader and worth nothing to someone who has seen it
 * four hundred times. Putting the choice on the <em>group</em> is what makes it settable once, for a whole
 * class of message, by the person being interrupted.</p>
 *
 * <p>It also retires the hard-coded routing that lived in {@code NotificationBalloons}: every notification
 * got a balloon because the balloon layer was the thing subscribing, so there was no way to express "log
 * this one" short of not sending it.</p>
 */
public enum NotificationDisplay {

    /** A balloon that leaves on its own, and the history. The default. */
    BALLOON,

    /**
     * A balloon that stays until it is dismissed, and the history.
     *
     * <p>Deliberately reachable but not the default, and the reasoning is recorded on
     * {@code NotificationBalloons.LINGER_MS}: a failure that demands a click before the screen is usable
     * again is its own kind of noise, and the history plus the unread mark already carry it. This exists so
     * a group whose messages genuinely must be acknowledged can say so — not so errors can be sticky
     * again.</p>
     */
    STICKY_BALLOON,

    /** The history only. Nothing interrupts; the bell still counts it. IntelliJ's {@code TOOL_WINDOW}. */
    LOG_ONLY,

    /**
     * Nothing at all — not shown, not logged, not counted.
     *
     * <p>The only value that discards information, which is why it is never a default. It is what a user
     * chooses when a producer is wrong rather than merely noisy.</p>
     */
    NONE
}
