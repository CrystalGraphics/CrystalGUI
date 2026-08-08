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
     * The history was emptied — "Clear all".
     *
     * <p>Separate from {@link #onDidNotify} because a view cannot infer it: nothing arrived, so there is
     * no notification to hand over, and a view watching only arrivals would keep showing a list the user
     * has just dismissed.</p>
     */
    public static final Signal.Action onDidClear = new Signal.Action();

    /**
     * The same message arrived again — its {@link Notification#getRepeats()} has gone up.
     *
     * <p>A separate channel from {@link #onDidNotify} because a view must do something different with it:
     * the notification is one it is already showing, so it updates that card rather than adding another. A
     * repeat delivered as an arrival is precisely the flood this collapse exists to stop.</p>
     */
    public static final Signal.Value<Notification> onDidRepeat = new Signal.Value<>();

    /**
     * How many have arrived since the user last looked — what the bell's badge shows.
     *
     * <p>Read state is the view's fact rather than the notification's: the <em>same</em> message is unread
     * until someone opens the panel, and which panel that is has nothing to do with what happened. Kept
     * here rather than per-notification for that reason, and because the badge only ever needs a count.</p>
     */
    public static final Signal.Value<Integer> onDidChangeUnread = new Signal.Value<>();

    private static int unread;

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

    /**
     * Announces something that happened, <b>collapsing an immediate repeat</b> of it.
     *
     * <h3>Only against the newest entry, deliberately</h3>
     *
     * <p>The case worth catching is a producer on a path that fires more than once — a retry loop, a
     * recompile, anything reached from a frame — where the same sentence lands several times in a row and
     * buries everything else in a hundred-deep history. Searching the <em>whole</em> history instead would
     * fold together two failures ten minutes apart, which are two things that happened and should read as
     * two, and it would make "arrived just now" unanswerable.</p>
     *
     * <p>A repeat does not bump the unread count either: the same message twice is not new information, and
     * a bell that ticks up while nothing new has been said is worse than one that stays put.</p>
     */
    public static void show(Notification notification) {
        if (notification == null) return;
        Notification newest = HISTORY.peekLast();
        if (notification.saysTheSameAs(newest)) {
            newest.markRepeated();
            onDidRepeat.emit(newest);
            return;
        }
        HISTORY.addLast(notification);
        while (HISTORY.size() > HISTORY_LIMIT) HISTORY.removeFirst();
        unread++;
        onDidNotify.emit(notification);
        onDidChangeUnread.emit(unread);
    }

    /** @see #onDidChangeUnread */
    public static int unread() {
        return unread;
    }

    /**
     * The user has seen what is there — clears the badge without touching the history.
     *
     * <p>Two separate verbs on purpose. Reading a list is not the same as dismissing it, and folding them
     * together is how a panel that you opened once quietly threw away the message you opened it for.</p>
     */
    public static void markAllRead() {
        if (unread == 0) return;
        unread = 0;
        onDidChangeUnread.emit(0);
    }

    /**
     * Empties the history — IntelliJ's "Clear all".
     *
     * <p>Distinct from {@link #resetForTesting()}, which also tears down every subscription: doing that in
     * production would leave the panel that invoked it deaf to everything afterwards.</p>
     */
    public static void clear() {
        if (HISTORY.isEmpty() && unread == 0) return;
        HISTORY.clear();
        unread = 0;
        onDidClear.emit();
        onDidChangeUnread.emit(0);
    }

    /** Oldest first. A copy, so a consumer cannot edit the history it is reading. */
    public static List<Notification> history() {
        return List.copyOf(HISTORY);
    }

    /** Empties the history. For tests that need isolation, never for production. */
    public static void resetForTesting() {
        HISTORY.clear();
        unread = 0;
        onDidNotify.disconnectAll();
        onDidClear.disconnectAll();
        onDidRepeat.disconnectAll();
        onDidChangeUnread.disconnectAll();
    }
}
