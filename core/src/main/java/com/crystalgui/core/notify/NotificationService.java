package com.crystalgui.core.notify;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.async.Broadcast;
import com.crystalgui.core.settings.Preferences;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * Where anything says that something happened — VS Code's {@code INotificationService}, IntelliJ's
 * {@code Notifications.Bus}. One per desktop, owning the history, the handles, the unread count and the groups, and
 * safe to post to from any thread or sequence: an application, a job and a language module all say things here, and
 * every application's list and the shell's toasts hear the same events.
 *
 * <pre>{@code
 * // Anyone: a producer announces and the shell decides how it is shown
 * Notifications.show(Notification.error("Save failed").withDetail(reason).inGroup("files"));
 *
 * // The shell's toasts and an application's list: one model, one event
 * service.onDidChange.subscribe(event -> redraw(event));
 * service.onDidChangeUnread.subscribe(count -> bell.setBadge(count));
 *
 * // A producer that wants to revise or withdraw what it said
 * NotificationHandle handle = service.show(Notification.info("Indexing…"));
 * handle.updateMessage("Indexed 400 files");
 * handle.close();
 * }</pre>
 *
 * <p>A notification is an <b>event</b> that may deserve an action and belongs in a history; a status item is
 * ambient and replaces itself ({@link StatusBar}). The group decides how loudly this is said
 * ({@link NotificationGroups}); a kind the user silenced ("never show again") is a desktop-wide
 * {@link Preferences} value, persisted with them.</p>
 *
 * <p>Every notification has a <b>source application</b> ({@link Notification#getSourceId}): the toast activates it on
 * a click, an application's sticky ones close with it ({@link #closeFrom}), and a list may filter by it.</p>
 *
 * <p>Easy to get wrong: {@link #current()} is what code with no desktop in hand posts through; with none installed it
 * logs and drops, so a headless caller loses nothing it could have shown.</p>
 */
public final class NotificationService {

    /** How many are kept. The oldest is dropped past this, announced as {@link NotificationEvent.Kind#REMOVED}. */
    public static final int HISTORY_LIMIT = 100;

    private static final String SILENCED_PREFIX = "notifications.silenced.";

    /** The history changed. One channel, typed by {@link NotificationEvent.Kind}. */
    public final Broadcast<NotificationEvent> onDidChange = Broadcast.create("notifications");

    /** The unread count changed: what the bell's badge shows. Not a change to the list. */
    public final Broadcast<Integer> onDidChangeUnread = Broadcast.create("notifications.unread");

    private final NotificationGroups groups = new NotificationGroups();
    private final List<Notification> history = new ArrayList<>();
    private final Map<Notification, NotificationHandle> handles = new IdentityHashMap<>();
    private int unread;

    private static volatile Supplier<NotificationService> locator = () -> null;
    /** Weak: the desktop owns its service, and a static must not keep a closed screen alive through its subscribers. */
    private static volatile WeakReference<NotificationService> installed = new WeakReference<>(null);
    private static volatile Supplier<String[]> sourceOfFocus = () -> null;

    /**
     * Makes {@code service} the one code without a desktop in hand posts to (the latest installed wins), and lets the
     * desktop route the running document and name the focused application: {@code {id, name}}, or null. The owner of
     * a desktop calls this; nothing else should.
     */
    public static void install(NotificationService service, Supplier<NotificationService> forRunningDocument,
                               Supplier<String[]> focusedApplication) {
        installed = new WeakReference<>(service);
        locator = forRunningDocument;
        sourceOfFocus = focusedApplication;
    }

    /** The service the caller's document belongs to, else the installed one, else null. */
    @Nullable
    public static NotificationService current() {
        NotificationService here = locator.get();
        return here != null ? here : installed.get();
    }

    public NotificationGroups groups() {
        return groups;
    }

    // ── Posting ─────────────────────────────────────────────────────────────────────────────────

    public NotificationHandle info(String message) {
        return show(Notification.info(message));
    }

    public NotificationHandle warning(String message) {
        return show(Notification.warning(message));
    }

    public NotificationHandle error(String message) {
        return show(Notification.error(message));
    }

    /**
     * Announces a notification, answering a handle to revise or withdraw it. A notification that says what the
     * newest already says is that one again, counted; one from a group set to {@code NONE}, or silenced by the user,
     * is not kept (its handle is already closed).
     */
    public synchronized NotificationHandle show(Notification notification) {
        if (notification == null) return null;
        if (notification.getSourceId().isEmpty()) {
            String[] focus = sourceOfFocus.get();
            if (focus != null) notification.from(focus[0], focus[1]);
        }
        // THE GROUP DECIDES WHETHER THIS IS SAID AT ALL: IntelliJ's NONE, the only value that discards
        // information, which is why it is never a default and only ever a user's choice.
        if (groups.displayOf(notification.getGroupId()) == NotificationDisplay.NONE) {
            return new NotificationHandle(this, notification);
        }
        // SILENCED BY THE USER, for this kind of message rather than this instance of it.
        String silenced = notification.getNeverShowAgainId();
        if (silenced != null && isSuppressed(silenced)) return new NotificationHandle(this, notification);
        Notification newest = history.isEmpty() ? null : history.get(history.size() - 1);
        if (notification.saysTheSameAs(newest)) {
            newest.markRepeated();
            onDidChange.post(NotificationEvent.changed(newest, history.size() - 1));
            return handles.get(newest);
        }
        history.add(notification);
        handles.put(notification, new NotificationHandle(this, notification));
        // EVICTION IS ANNOUNCED, before the arrival, so a view applies the changes in the order they happened and
        // never briefly holds more than the model does. @see NotificationEvent
        while (history.size() > HISTORY_LIMIT) {
            Notification dropped = history.remove(0);
            onDidChange.post(NotificationEvent.removed(dropped, 0));
            closeHandle(dropped);
        }
        unread++;
        onDidChange.post(NotificationEvent.added(notification, history.size() - 1));
        onDidChangeUnread.post(unread);
        return handles.get(notification);
    }

    // ── What handles ask ────────────────────────────────────────────────────────────────────────

    synchronized boolean holds(Notification notification) {
        return handles.containsKey(notification);
    }

    synchronized void announceChanged(Notification notification) {
        int index = history.indexOf(notification);
        if (index < 0) return;
        onDidChange.post(NotificationEvent.changed(notification, index));
    }

    synchronized void withdraw(Notification notification) {
        int index = history.indexOf(notification);
        if (index < 0) return;
        history.remove(index);
        onDidChange.post(NotificationEvent.removed(notification, index));
        closeHandle(notification);
    }

    private void closeHandle(Notification notification) {
        NotificationHandle handle = handles.remove(notification);
        if (handle != null) handle.notifyClosed();
    }

    /** Takes out every sticky notification an application said: its window is gone, so nothing is left to act on. */
    public synchronized void closeFrom(String sourceId) {
        for (Notification each : new ArrayList<>(history)) {
            if (each.getSourceId().equals(sourceId)
                    && groups.displayOf(each.getGroupId()) == NotificationDisplay.STICKY_BALLOON) {
                withdraw(each);
            }
        }
    }

    // ── The list ────────────────────────────────────────────────────────────────────────────────

    /** The last {@link #HISTORY_LIMIT}, oldest first. */
    public synchronized List<Notification> history() {
        return List.copyOf(history);
    }

    /** The history of one application. */
    public synchronized List<Notification> historyFrom(String sourceId) {
        List<Notification> out = new ArrayList<>();
        for (Notification each : history) {
            if (each.getSourceId().equals(sourceId)) out.add(each);
        }
        return out;
    }

    public synchronized int size() {
        return history.size();
    }

    public synchronized boolean isEmpty() {
        return history.isEmpty();
    }

    /** How many arrived since the user last looked. */
    public synchronized int unread() {
        return unread;
    }

    public synchronized void markAllRead() {
        if (unread == 0) return;
        unread = 0;
        onDidChangeUnread.post(0);
    }

    /** Empties the history. Silenced kinds stay silenced: "clear all" is not a request to be interrupted again. */
    public synchronized void clear() {
        if (history.isEmpty() && unread == 0) return;
        history.clear();
        for (NotificationHandle handle : new ArrayList<>(handles.values())) handle.notifyClosed();
        handles.clear();
        unread = 0;
        onDidChange.post(NotificationEvent.cleared());
        onDidChangeUnread.post(0);
    }

    // ── Silenced kinds: the user's, desktop-wide ────────────────────────────────────────────────

    /** Silences every notification carrying this id ({@link Notification#getNeverShowAgainId}). */
    public void suppress(String neverShowAgainId) {
        if (neverShowAgainId == null || neverShowAgainId.isEmpty()) return;
        Preferences.update(p -> p.with(SILENCED_PREFIX + neverShowAgainId, "true"));
    }

    public void unsuppress(String neverShowAgainId) {
        Preferences.update(p -> p.with(SILENCED_PREFIX + neverShowAgainId, null));
    }

    public boolean isSuppressed(String neverShowAgainId) {
        return Preferences.current().raw(SILENCED_PREFIX + neverShowAgainId) != null;
    }

    public List<String> suppressed() {
        List<String> out = new ArrayList<>();
        for (String key : Preferences.current().asMap().keySet()) {
            if (key.startsWith(SILENCED_PREFIX)) out.add(key.substring(SILENCED_PREFIX.length()));
        }
        return out;
    }

    /** Drops the history and the subscribers. For tests, which share one process. */
    public synchronized void resetForTesting() {
        history.clear();
        for (NotificationHandle handle : new ArrayList<>(handles.values())) handle.notifyClosed();
        handles.clear();
        unread = 0;
        onDidChange.disconnectAll();
        onDidChangeUnread.disconnectAll();
        groups.onDidChangeDisplay.disconnectAll();
    }

    /** Logs a notification nowhere could show. */
    static void dropped(Notification notification) {
        CrystalGuiCore.LOGGER.info("[cgui] no desktop to show: {} {}", notification.getSeverity(),
                notification.getMessage());
    }
}
