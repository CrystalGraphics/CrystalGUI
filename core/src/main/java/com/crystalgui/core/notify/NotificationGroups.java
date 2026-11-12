package com.crystalgui.core.notify;

import com.crystalgui.core.async.Broadcast;
import com.crystalgui.core.settings.Preferences;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Which producers a service knows and how loudly each is heard — IntelliJ's {@code NotificationGroup} and its
 * per-group display type. Owned by a {@link NotificationService}; the user's override of a group is a desktop-wide
 * {@link Preferences} value, so it holds in every application and survives a restart.
 *
 * <pre>{@code
 * service.groups().register("compiler", "Shader compilation", NotificationDisplay.LOG_ONLY);
 * service.groups().setDisplay("compiler", NotificationDisplay.BALLOON);   // the user's choice, persisted
 * NotificationDisplay how = service.groups().displayOf("compiler");
 * }</pre>
 *
 * <p>Easy to get wrong: a group nothing registered is shown as a balloon; {@code NONE} discards, so it is only ever
 * a user's choice, never a default.</p>
 */
public final class NotificationGroups {

    /** A producer. @param defaultDisplay what it does until the user says otherwise */
    public record Group(String id, String displayName, NotificationDisplay defaultDisplay) {
    }

    static final String DISPLAY_PREFIX = "notifications.display.";

    /** A group's effective display changed; the payload is its id. */
    public final Broadcast<String> onDidChangeDisplay = Broadcast.create("notifications.display");

    private final Map<String, Group> groups = new LinkedHashMap<>();

    /** Declares a producer. Registering an id again replaces it. */
    public synchronized void register(String id, String displayName, NotificationDisplay defaultDisplay) {
        if (id == null || id.isEmpty()) return;
        groups.put(id, new Group(id, displayName == null || displayName.isEmpty() ? id : displayName,
                defaultDisplay == null ? NotificationDisplay.BALLOON : defaultDisplay));
        onDidChangeDisplay.post(id);
    }

    public synchronized List<Group> registered() {
        return new ArrayList<>(groups.values());
    }

    @Nullable
    public synchronized Group group(String id) {
        return groups.get(id);
    }

    /** How this group is shown: the user's choice, else the group's own, else a balloon. */
    public NotificationDisplay displayOf(@Nullable String groupId) {
        String id = groupId == null || groupId.isEmpty() ? Notification.DEFAULT_GROUP : groupId;
        NotificationDisplay override = override(id);
        if (override != null) return override;
        Group group;
        synchronized (this) {
            group = groups.get(id);
        }
        return group == null ? NotificationDisplay.BALLOON : group.defaultDisplay();
    }

    /** The user's choice for a group; null returns it to the group's own. */
    public void setDisplay(String groupId, @Nullable NotificationDisplay display) {
        if (groupId == null || groupId.isEmpty()) return;
        NotificationDisplay previous = displayOf(groupId);
        Preferences.update(p -> p.with(DISPLAY_PREFIX + groupId, display == null ? null : display.name()));
        if (displayOf(groupId) != previous) onDidChangeDisplay.post(groupId);
    }

    public boolean isOverridden(String groupId) {
        return override(groupId) != null;
    }

    @Nullable
    private static NotificationDisplay override(String groupId) {
        String held = Preferences.current().raw(DISPLAY_PREFIX + groupId);
        if (held == null) return null;
        try {
            return NotificationDisplay.valueOf(held);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
