package com.crystalgui.app.uibuilder.library;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.library.LibraryCatalog.Group;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.core.storage.ConfigRecord;
import com.crystalgui.core.storage.ConfigStorage;
import com.crystalgui.serialization.Codec;
import com.crystalgui.serialization.Codecs;
import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.dom.Name;

/**
 * What one user made of the Library: their own groups of kinds, and whether they browse it as cards or rows.
 *
 * <pre>{@code
 * UserLibrary mine = UserLibrary.in(workbench.extensionStore(UiBuilderContribution.ID));   // null: the session's
 * mine.createGroup("Forms");
 * mine.addToGroup("Forms", TextField.NAME);
 * mine.onChanged.connect(() -> panel.setCatalog(LibraryCatalog.current(mine.groups())));
 * }</pre>
 *
 * <ul>
 *   <li>Kept as {@link #FILE}, a {@link ConfigRecord} in the UI builder extension's own store — the same groups in
 *       every application and workspace.</li>
 *   <li>A kind may sit in several groups. A group name is unique, and may not be a shipped group's.</li>
 *   <li>A kind no longer registered stays in its group and simply lists nothing, so uninstalling a mod loses no
 *       group that reinstalling it would restore.</li>
 * </ul>
 */
public final class UserLibrary {

    /** The record's file in the store. */
    static final String FILE = "library.json";

    /** What is kept: the view and the groups, in the order they were made. */
    record State(boolean rows, List<Group> groups) {

        static final State EMPTY = new State(false, List.of());

        State {
            groups = List.copyOf(groups);
        }

        static final Codec<State> CODEC = new Codec<>() {
            @Override
            public <T> T encode(DynamicOps<T> ops, State state) {
                return new StateMap<>(ops)
                        .putBool("rows", state.rows())
                        .putList("groups", state.groups(), (entry, group) -> entry
                                .putString("name", group.label())
                                .putRaw("kinds", KINDS.encode(ops, group.kinds())))
                        .encode();
            }

            @Override
            public <T> State decode(DynamicOps<T> ops, T input) {
                StateMap<T> map = new StateMap<>(ops, input);
                List<Group> groups = map.getList("groups", entry -> new Group(entry.getString("name", ""),
                        entry.has("kinds") ? KINDS.decode(ops, entry.getRaw("kinds")) : List.of(), true));
                return new State(map.getBool("rows", false), groups);
            }
        };

        private static final Codec<List<Name>> KINDS = new Codec<>() {
            @Override
            public <T> T encode(DynamicOps<T> ops, List<Name> kinds) {
                List<String> text = new ArrayList<>(kinds.size());
                for (Name kind : kinds) text.add(kind.toString());
                return Codecs.listOf(Codecs.STRING).encode(ops, text);
            }

            @Override
            public <T> List<Name> decode(DynamicOps<T> ops, T input) {
                List<Name> kinds = new ArrayList<>();
                for (String text : Codecs.listOf(Codecs.STRING).decode(ops, input)) kinds.add(Name.parse(text));
                return List.copyOf(kinds);
            }
        };
    }

    /** After any change to the groups or the view. */
    public final Signal.Action onChanged = new Signal.Action();

    private final ConfigRecord<State> record;

    private UserLibrary(@Nullable ConfigStorage store) {
        record = ConfigRecord.in(store, FILE, State.CODEC, State.EMPTY);
        record.onChanged.connect(state -> onChanged.emit());
    }

    /** The user's library kept in {@code store}, or for this session alone when there is no store. */
    public static UserLibrary in(@Nullable ConfigStorage store) {
        return new UserLibrary(store);
    }

    /** The user's groups, in the order they were made. */
    public List<Group> groups() {
        return record.get().groups();
    }

    @Nullable
    public Group group(String name) {
        int at = indexOf(name);
        return at < 0 ? null : groups().get(at);
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
        return changeGroups(groups -> append(groups, new Group(name.trim(), List.of(), true)));
    }

    public boolean renameGroup(String from, String to) {
        int at = indexOf(from);
        if (at < 0 || !isFreeName(to)) return false;
        return changeGroups(groups -> replace(groups, at, new Group(to.trim(), groups.get(at).kinds(), true)));
    }

    public boolean deleteGroup(String name) {
        int at = indexOf(name);
        if (at < 0) return false;
        return changeGroups(groups -> {
            List<Group> out = new ArrayList<>(groups);
            out.remove(at);
            return out;
        });
    }

    /** Adds {@code kind} to the end of the group; false when it is already there or there is no such group. */
    public boolean addToGroup(String name, Name kind) {
        int at = indexOf(name);
        if (at < 0 || groups().get(at).kinds().contains(kind)) return false;
        return changeGroups(groups -> replace(groups, at, new Group(name, append(groups.get(at).kinds(), kind), true)));
    }

    public boolean removeFromGroup(String name, Name kind) {
        int at = indexOf(name);
        if (at < 0 || !groups().get(at).kinds().contains(kind)) return false;
        List<Name> kinds = new ArrayList<>(groups().get(at).kinds());
        kinds.remove(kind);
        return changeGroups(groups -> replace(groups, at, new Group(name, kinds, true)));
    }

    /** Whether the Library shows compact rows rather than cards. */
    public boolean isRows() {
        return record.get().rows();
    }

    public void setRows(boolean compact) {
        record.update(state -> new State(compact, state.groups()));
    }

    private boolean changeGroups(UnaryOperator<List<Group>> change) {
        record.update(state -> new State(state.rows(), change.apply(state.groups())));
        return true;
    }

    private int indexOf(String name) {
        List<Group> groups = groups();
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).label().equals(name)) return i;
        }
        return -1;
    }

    private static <E> List<E> append(List<E> list, E element) {
        List<E> out = new ArrayList<>(list);
        out.add(element);
        return out;
    }

    private static <E> List<E> replace(List<E> list, int at, E element) {
        List<E> out = new ArrayList<>(list);
        out.set(at, element);
        return out;
    }
}
