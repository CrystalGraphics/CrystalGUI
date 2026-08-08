package com.crystalgui.core.notify;

import com.crystalgui.core.signal.Signal;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What each notification group is allowed to do — IntelliJ's {@code NotificationGroupManager}.
 *
 * <p>Ported from {@code com.intellij.notification.NotificationGroupManager} and the
 * {@code notificationGroup} extension point, minus its XML.</p>
 *
 * <h3>This is what {@link Notification#getGroupId()} was for</h3>
 *
 * <p>The field was carried for a long time and read by exactly one thing —
 * {@link Notification#saysTheSameAs} — so it scoped deduplication and nothing else. The use it was
 * <em>added</em> for is this one: in both references a group is the unit at which a human decides how much
 * interruption a class of message is worth, and the routing decision hangs off it. Without that, "which
 * producer is this from" is a label rather than a control.</p>
 *
 * <h3>An unregistered group is not an error</h3>
 *
 * <p>It gets {@link NotificationDisplay#BALLOON}, because the alternative is that a producer which forgot
 * to register goes silent — a failure that looks exactly like the notification never being sent, and which
 * would be discovered by someone wondering why their error never appeared. Registration exists to
 * <em>change</em> the default and to give the group a readable name for a settings list, not to grant
 * permission to speak.</p>
 */
public final class NotificationGroups {

    private NotificationGroups() {
    }

    /** A registered group's identity and how loud it is allowed to be. */
    public record Group(String id, String displayName, NotificationDisplay defaultDisplay) {
    }

    /** A group's effective display changed — what a settings panel and the balloon layer both watch. */
    public static final Signal.Value<String> onDidChangeDisplay = new Signal.Value<>();

    private static final Map<String, Group> GROUPS = new LinkedHashMap<>();

    /**
     * The user's override, where they have one.
     *
     * <p>Separate from the registered default rather than overwriting it, so "reset to default" stays
     * answerable and so a build that changes its own default still reaches a user who never touched it.</p>
     */
    private static final Map<String, NotificationDisplay> OVERRIDES = new LinkedHashMap<>();

    /** Declares a group and how loud it is by default. Re-registering replaces the declaration. */
    public static void register(String id, String displayName, NotificationDisplay defaultDisplay) {
        if (id == null || id.isEmpty()) return;
        GROUPS.put(id, new Group(id, displayName == null || displayName.isEmpty() ? id : displayName,
                defaultDisplay == null ? NotificationDisplay.BALLOON : defaultDisplay));
        onDidChangeDisplay.emit(id);
    }

    /** Every declared group, in registration order — what a settings list enumerates. */
    public static List<Group> registered() {
        return new ArrayList<>(GROUPS.values());
    }

    @Nullable
    public static Group group(String id) {
        return GROUPS.get(id);
    }

    /** How loud this group is right now: the user's choice if they made one, else its declared default. */
    public static NotificationDisplay displayOf(@Nullable String groupId) {
        String id = groupId == null || groupId.isEmpty() ? Notification.DEFAULT_GROUP : groupId;
        NotificationDisplay override = OVERRIDES.get(id);
        if (override != null) return override;
        Group group = GROUPS.get(id);
        return group == null ? NotificationDisplay.BALLOON : group.defaultDisplay();
    }

    /** The user's choice for this group. Null clears it, restoring the declared default. */
    public static void setDisplay(String groupId, @Nullable NotificationDisplay display) {
        if (groupId == null || groupId.isEmpty()) return;
        NotificationDisplay previous = displayOf(groupId);
        if (display == null) OVERRIDES.remove(groupId);
        else OVERRIDES.put(groupId, display);
        if (displayOf(groupId) != previous) onDidChangeDisplay.emit(groupId);
    }

    /** Whether the user has overridden this group. */
    public static boolean isOverridden(String groupId) {
        return OVERRIDES.containsKey(groupId);
    }

    /** For tests that need isolation, never for production. */
    public static void resetForTesting() {
        GROUPS.clear();
        OVERRIDES.clear();
        onDidChangeDisplay.disconnectAll();
    }
}
