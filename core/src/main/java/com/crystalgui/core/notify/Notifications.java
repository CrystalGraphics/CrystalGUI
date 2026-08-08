package com.crystalgui.core.notify;

import com.crystalgui.core.signal.Signal;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

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
 * <h3>The history is the model, and it announces itself with one event</h3>
 *
 * <p>Both references keep the notification list as a single observable collection that emits typed
 * changes, and hang every view off it — VS Code's {@code NotificationsModel} feeds both its toasts and its
 * notification centre from one {@code onDidChangeNotification}. {@link #onDidChange} is that event; see
 * {@link NotificationEvent} for why the four separate signals it replaced could not express an eviction,
 * and what that cost.</p>
 *
 * <h3>Not the status bar</h3>
 *
 * <p>{@link StatusBar} is the other half and they are deliberately separate. A notification is an
 * <b>event</b> — it happened once, it may deserve an action, and it belongs in a history. A status item is
 * <b>ambient</b> — it describes how things are right now and is replaced rather than accumulated. The
 * shader graph produces both: a compile failure is an event, and "line 42 came from Multiply" is ambient
 * and fires on every caret move. Routed through one channel, the second buries the first within seconds.
 * VS Code separates them for this reason and so does every editor that has both.</p>
 *
 * <h3>A group decides how loudly this is said</h3>
 *
 * <p>{@link NotificationGroups} carries IntelliJ's {@code NotificationDisplayType} per group, overridable
 * by the user. It is what {@link Notification#getGroupId()} was always for, and until it existed the
 * routing was hard-coded in the balloon layer — everything got a balloon, because the balloon layer was
 * the thing subscribing.</p>
 *
 * <h3>Announcing is two-way</h3>
 *
 * <p>{@link #show} hands back a {@link NotificationHandle}, so a producer can revise or withdraw what it
 * said rather than posting a correction beneath it.</p>
 *
 * <h3>Still missing: progress</h3>
 *
 * <p>VS Code's handle carries an {@code IProgress} sink, so a long operation reports into the notification
 * it already posted. Not built, because nothing here runs a long enough operation to report on — the file
 * service is synchronous and the compiler finishes within a frame.</p>
 */
public final class Notifications {

    private Notifications() {
    }

    /**
     * The history changed. One channel, typed by {@link NotificationEvent.Kind}.
     *
     * <p>Nothing is displayed by default, deliberately: a core that drew its own toast would be deciding
     * a piece of application layout, and there is no window here to draw it in.</p>
     */
    public static final Signal.Value<NotificationEvent> onDidChange = new Signal.Value<>();

    /**
     * How many have arrived since the user last looked — what the bell's badge shows.
     *
     * <p>Read state is the view's fact rather than the notification's: the <em>same</em> message is unread
     * until someone opens the panel, and which panel that is has nothing to do with what happened. Kept
     * here rather than per-notification for that reason, and because the badge only ever needs a count.</p>
     *
     * <p>Separate from {@link #onDidChange} because it is not a change to the <em>list</em>: marking
     * everything read alters no entry, and a view that redrew its column on it would be repainting for
     * something that only the bell can see.</p>
     */
    public static final Signal.Value<Integer> onDidChangeUnread = new Signal.Value<>();

    private static int unread;

    /**
     * The last {@link #HISTORY_LIMIT}, oldest first.
     *
     * <p>Bounded because it is a convenience, not a log. The value that matters is that a message which
     * arrived while the user was looking elsewhere is still findable — the single most common complaint
     * about a status line, and the reason IntelliJ's event log exists.</p>
     *
     * <p>A list rather than a deque so an entry has an <b>index</b>, which is what
     * {@link NotificationEvent} carries. Removing from the front shifts, and at a hundred short entries
     * that is not worth a data structure.</p>
     */
    private static final List<Notification> HISTORY = new ArrayList<>();

    /**
     * The handle handed back for each held notification.
     *
     * <p>Kept beside the history rather than on the notification so that "is this still open" is one
     * question with one answer — a flag on the object would have to be cleared by everything that can
     * remove it, and an eviction is not a place anyone remembers to look.</p>
     */
    private static final Map<Notification, NotificationHandle> HANDLES = new IdentityHashMap<>();

    /** How many are kept. The oldest is dropped past this, announced as {@link NotificationEvent.Kind#REMOVED}. */
    public static final int HISTORY_LIMIT = 100;

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
    public static NotificationHandle show(Notification notification) {
        if (notification == null) return null;
        // THE GROUP DECIDES WHETHER THIS IS SAID AT ALL. IntelliJ's NONE, and the only value that discards
        // information -- which is why it is never a default and only ever a user's choice.
        if (NotificationGroups.displayOf(notification.getGroupId()) == NotificationDisplay.NONE) {
            return new NotificationHandle(notification);
        }
        Notification newest = HISTORY.isEmpty() ? null : HISTORY.get(HISTORY.size() - 1);
        if (notification.saysTheSameAs(newest)) {
            newest.markRepeated();
            onDidChange.emit(NotificationEvent.changed(newest, HISTORY.size() - 1));
            return HANDLES.get(newest);
        }
        HISTORY.add(notification);
        HANDLES.put(notification, new NotificationHandle(notification));
        // EVICTION IS ANNOUNCED. Silently dropping the oldest is what let the panel's column outgrow the
        // history it was showing -- see NotificationEvent. Emitted BEFORE the arrival so a view applies the
        // changes in the order they happened, and never briefly holds more than the model does.
        while (HISTORY.size() > HISTORY_LIMIT) {
            Notification dropped = HISTORY.remove(0);
            onDidChange.emit(NotificationEvent.removed(dropped, 0));
            closeHandle(dropped);
        }
        unread++;
        onDidChange.emit(NotificationEvent.added(notification, HISTORY.size() - 1));
        onDidChangeUnread.emit(unread);
        return HANDLES.get(notification);
    }

    /** Whether this is still held. @see NotificationHandle#isOpen */
    static boolean holds(Notification notification) {
        return HANDLES.containsKey(notification);
    }

    /** @see NotificationHandle#updateMessage */
    static void announceChanged(Notification notification) {
        int index = HISTORY.indexOf(notification);
        if (index < 0) return;
        onDidChange.emit(NotificationEvent.changed(notification, index));
    }

    /** @see NotificationHandle#close */
    static void withdraw(Notification notification) {
        int index = HISTORY.indexOf(notification);
        if (index < 0) return;
        HISTORY.remove(index);
        onDidChange.emit(NotificationEvent.removed(notification, index));
        closeHandle(notification);
    }

    private static void closeHandle(Notification notification) {
        NotificationHandle handle = HANDLES.remove(notification);
        if (handle != null) handle.notifyClosed();
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
        for (NotificationHandle handle : new ArrayList<>(HANDLES.values())) handle.notifyClosed();
        HANDLES.clear();
        unread = 0;
        onDidChange.emit(NotificationEvent.cleared());
        onDidChangeUnread.emit(0);
    }

    /** How many are held. @see #isEmpty() */
    public static int size() {
        return HISTORY.size();
    }

    /**
     * Whether anything is held.
     *
     * <p>Here rather than left to {@code history().isEmpty()} because that copies the whole list to answer
     * a question about its size — and the empty-state check runs on <em>every</em> arrival, so a full
     * history allocated a hundred-element copy per notification to find out it was not empty.</p>
     */
    public static boolean isEmpty() {
        return HISTORY.isEmpty();
    }

    /** Oldest first. A copy, so a consumer cannot edit the history it is reading. */
    public static List<Notification> history() {
        return List.copyOf(HISTORY);
    }

    /** Empties the history. For tests that need isolation, never for production. */
    public static void resetForTesting() {
        HISTORY.clear();
        for (NotificationHandle handle : new ArrayList<>(HANDLES.values())) handle.notifyClosed();
        HANDLES.clear();
        unread = 0;
        onDidChange.disconnectAll();
        onDidChangeUnread.disconnectAll();
    }
}
