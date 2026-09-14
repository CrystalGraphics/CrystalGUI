package com.crystalgui.app.uibuilder.library;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import com.crystalgui.app.uibuilder.library.LibraryCatalog.Group;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.core.storage.ConfigStorage;
import com.crystalgui.core.storage.InMemoryConfigStorage;
import com.crystalgui.ui.dom.Name;

/**
 * What one user made of the Library: their own groups of kinds, and whether they browse it as cards or rows.
 *
 * <pre>{@code
 * UserLibrary mine = UserLibrary.in(workbench.config("uibuilder.library"));   // null store: kept for the session
 * mine.createGroup("Forms");
 * mine.addToGroup("Forms", TextField.NAME);
 * mine.onChanged.connect(() -> panel.setCatalog(LibraryCatalog.current(mine.groups())));
 * }</pre>
 *
 * <ul>
 *   <li>One JSON record, rewritten whole on every change; a store that cannot be read starts empty, never throws.</li>
 *   <li>A kind may sit in several groups. A group name is unique, and may not be a shipped group's.</li>
 *   <li>A kind no longer registered stays in its group and simply lists nothing, so uninstalling a mod loses no
 *       group that reinstalling it would restore.</li>
 * </ul>
 */
public final class UserLibrary {

    /** The record's file in the store. */
    static final String FILE = "library.json";

    /** After any change to the groups or the view. */
    public final Signal.Action onChanged = new Signal.Action();

    private final ConfigStorage store;
    private final List<Group> groups = new ArrayList<>();
    private boolean rows;

    private UserLibrary(ConfigStorage store) {
        this.store = store;
        read();
    }

    /** The user's library kept in {@code store}, or for this session alone when there is no store. */
    public static UserLibrary in(@Nullable ConfigStorage store) {
        return new UserLibrary(store == null ? new InMemoryConfigStorage() : store);
    }

    /** The user's groups, in the order they were made. */
    public List<Group> groups() {
        return List.copyOf(groups);
    }

    @Nullable
    public Group group(String name) {
        for (Group group : groups) {
            if (group.label().equals(name)) return group;
        }
        return null;
    }

    /** Whether {@code name} could name a new group: not blank, not taken, not a shipped group's. */
    public boolean isFreeName(String name) {
        String trimmed = name.trim();
        if (trimmed.isEmpty() || group(trimmed) != null) return false;
        for (Group shipped : LibraryGroups.SHIPPED) {
            if (shipped.label().equals(trimmed)) return false;
        }
        return true;
    }

    /** Makes an empty group; false when the name is not free. */
    public boolean createGroup(String name) {
        if (!isFreeName(name)) return false;
        groups.add(new Group(name.trim(), List.of(), true));
        changed();
        return true;
    }

    public boolean renameGroup(String from, String to) {
        int at = indexOf(from);
        if (at < 0 || !isFreeName(to)) return false;
        groups.set(at, new Group(to.trim(), groups.get(at).kinds(), true));
        changed();
        return true;
    }

    public boolean deleteGroup(String name) {
        int at = indexOf(name);
        if (at < 0) return false;
        groups.remove(at);
        changed();
        return true;
    }

    /** Adds {@code kind} to the end of the group; false when it is already there or there is no such group. */
    public boolean addToGroup(String name, Name kind) {
        int at = indexOf(name);
        if (at < 0 || groups.get(at).kinds().contains(kind)) return false;
        List<Name> kinds = new ArrayList<>(groups.get(at).kinds());
        kinds.add(kind);
        groups.set(at, new Group(name, kinds, true));
        changed();
        return true;
    }

    public boolean removeFromGroup(String name, Name kind) {
        int at = indexOf(name);
        if (at < 0 || !groups.get(at).kinds().contains(kind)) return false;
        List<Name> kinds = new ArrayList<>(groups.get(at).kinds());
        kinds.remove(kind);
        groups.set(at, new Group(name, kinds, true));
        changed();
        return true;
    }

    /** Whether the Library shows compact rows rather than cards. */
    public boolean isRows() {
        return rows;
    }

    public void setRows(boolean compact) {
        if (compact == rows) return;
        rows = compact;
        changed();
    }

    private int indexOf(String name) {
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).label().equals(name)) return i;
        }
        return -1;
    }

    private void changed() {
        write();
        onChanged.emit();
    }

    private void read() {
        String json = store.read(FILE);
        if (json == null || json.trim().isEmpty()) return;
        try {
            // INSTANCE parse, not the static parseString: 1.7.10 ships a gson without it.
            JsonObject root = new JsonParser().parse(json).getAsJsonObject();
            rows = root.has("rows") && root.get("rows").getAsBoolean();
            if (!root.has("groups")) return;
            for (JsonElement each : root.getAsJsonArray("groups")) {
                JsonObject group = each.getAsJsonObject();
                List<Name> kinds = new ArrayList<>();
                for (JsonElement kind : group.getAsJsonArray("kinds")) kinds.add(Name.parse(kind.getAsString()));
                String name = group.get("name").getAsString();
                if (indexOf(name) < 0) groups.add(new Group(name, List.copyOf(kinds), true));
            }
        } catch (RuntimeException unreadable) {
            // A HAND-EDITED OR TRUNCATED FILE costs the user their groups for this session, not the panel.
            CrystalGuiCore.LOGGER.warn("[cgui] the Library's groups could not be read; starting without them", unreadable);
            groups.clear();
        }
    }

    private void write() {
        JsonObject root = new JsonObject();
        root.addProperty("rows", rows);
        JsonArray list = new JsonArray();
        for (Group group : groups) {
            JsonObject each = new JsonObject();
            each.addProperty("name", group.label());
            JsonArray kinds = new JsonArray();
            for (Name kind : group.kinds()) kinds.add(new JsonPrimitive(kind.toString()));
            each.add("kinds", kinds);
            list.add(each);
        }
        root.add("groups", list);
        store.write(FILE, root.toString());
    }
}
