package com.crystalgui.core.notify;

import com.crystalgui.core.signal.Signal;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Where anything says that something happened — VS Code's {@code INotificationService}, IntelliJ's
 * {@code Notifications.Bus}.
 *
 * <h3>Why this is global, and why that is the point</h3>
 *
 * <p>It is the last thing a contribution had to be <b>handed</b>. {@code ShaderGraphContribution.register}
 * took a {@code Signal.Value<String> status} parameter, with a javadoc apologising for it — "the one
 * thing the application still supplies, because where a status line lives is its decision". That reason
 * is real and the conclusion was wrong: <em>where</em> a message is displayed is the application's
 * decision, but <em>that a message exists</em> is not, and passing a sink in couples every contribution to
 * an application that has one.</p>
 *
 * <p>So a contribution announces, and an application decides what to do about it — exactly the split
 * {@link com.crystalgui.core.command.CommandRegistry} and
 * {@code InspectorRegistry} already make. {@code register(workbench)} now needs nothing else.</p>
 *
 * <h3>Not the status bar</h3>
 *
 * <p>{@link StatusBar} is the other half and they are deliberately separate. A notification is an
 * <b>event</b> — it happened once, it may deserve an action, and it belongs in a history. A status item is
 * <b>ambient</b> — it describes how things are right now and is replaced rather than accumulated. The
 * shader graph produces both: a compile failure is an event, and "line 42 came from Multiply" is ambient
 * and fires on every caret move. Routed through one channel, the second buries the first within seconds.
 * VS Code separates them for this reason and so does every editor that has both.</p>
 */
public final class Notifications {

    private Notifications() {
    }

    /**
     * Something happened. Connect to display it.
     *
     * <p>Nothing is displayed by default, deliberately: a core that drew its own toast would be deciding
     * a piece of application layout, and there is no window here to draw it in.</p>
     */
    public static final Signal.Value<Notification> onDidNotify = new Signal.Value<>();

    /**
     * The last {@value #HISTORY_LIMIT}, newest last.
     *
     * <p>Bounded because it is a convenience, not a log. The value that matters is that a message which
     * arrived while the user was looking elsewhere is still findable — the single most common complaint
     * about a status line, and the reason IntelliJ's event log exists.</p>
     */
    private static final Deque<Notification> HISTORY = new ArrayDeque<>();

    private static final int HISTORY_LIMIT = 100;

    public static void info(String message) {
        show(Notification.info(message));
    }

    public static void warning(String message) {
        show(Notification.warning(message));
    }

    public static void error(String message) {
        show(Notification.error(message));
    }

    public static void show(Notification notification) {
        if (notification == null) return;
        HISTORY.addLast(notification);
        while (HISTORY.size() > HISTORY_LIMIT) HISTORY.removeFirst();
        onDidNotify.emit(notification);
    }

    /** Oldest first. A copy, so a consumer cannot edit the history it is reading. */
    public static List<Notification> history() {
        return List.copyOf(HISTORY);
    }

    /** Empties the history. For tests that need isolation, never for production. */
    public static void resetForTesting() {
        HISTORY.clear();
        onDidNotify.disconnectAll();
    }
}
