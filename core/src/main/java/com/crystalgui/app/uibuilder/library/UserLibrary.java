package com.crystalgui.app.uibuilder.library;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * mine.createGroup("Forms/Inputs");                  // nested inside Forms
 * mine.addToGroup("Forms/Inputs", TextField.NAME);
 * mine.onChanged.connect(() -> panel.setCatalog(LibraryCatalog.current(mine.groups())));
 * }</pre>
 *
 * <ul>
 *   <li>Kept as {@link #FILE}, a {@link ConfigRecord} in the UI builder extension's own store — the same groups in
 *       every application and workspace.</li>
 *   <li>A group's name is its path, {@code /}-separated: making {@code "A/B"} makes {@code "A"} first if it is
 *       missing, and renaming or deleting {@code "A"} takes {@code "A/B"} with it. A group may sit inside a shipped
 *       one — {@code "Common/Mine"} — which is never made or renamed.</li>
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
            groups = repaired(groups);
        }

        /**
         * {@code groups} as they may be kept: none named like a shipped group, and one per name, a duplicate's kinds
         * merged into the first. Earlier builds kept both — a shipped name as the parent of a group inside it, and a
         * second group with the name of the first.
         */
        private static List<Group> repaired(List<Group> groups) {
            Map<String, Integer> at = new HashMap<>();
            List<Group> out = new ArrayList<>(groups.size());
            for (Group group : groups) {
                if (isShipped(group.label())) continue;
                Integer first = at.get(group.label());
                if (first == null) {
                    at.put(group.label(), out.size());
                    out.add(group);
                    continue;
                }
                List<Name> kinds = new ArrayList<>(out.get(first).kinds());
                for (Name kind : group.kinds()) {
                    if (!kinds.contains(kind)) kinds.add(kind);
                }
                out.set(first, new Group(group.label(), kinds, true));
            }
            return List.copyOf(out);
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

    private static boolean isShipped(String label) {
        for (Group shipped : LibraryGroups.SHIPPED) {
            if (shipped.label().equals(label)) return true;
        }
        return false;
    }

    @Nullable
    public Group group(String name) {
        int at = indexOf(name);
        return at < 0 ? null : groups().get(at);
    }

    /** {@code name} as a path: each segment trimmed, blank ones dropped — {@code " A / B/"} is {@code "A/B"}. */
    public static String path(String name) {
        List<String> segments = new ArrayList<>();
        for (String segment : name.split(Group.SEPARATOR)) {
            if (!segment.isBlank()) segments.add(segment.trim());
        }
        return String.join(Group.SEPARATOR, segments);
    }

    /** {@code name} inside the group at {@code parent}, or at the top for null. */
    public static String pathIn(@Nullable String parent, String name) {
        return parent == null ? path(name) : path(parent + Group.SEPARATOR + name);
    }

    /** Whether {@code name} could name a new group: not blank, not taken, not a shipped group's. */
    public boolean isFreeName(String name) {
        String wanted = path(name);
        return !wanted.isEmpty() && group(wanted) == null && !isShipped(wanted);
    }

    /** Makes an empty group, and any parent it names that is missing; false when the name is not free. */
    public boolean createGroup(String name) {
        if (!isFreeName(name)) return false;
        return changeGroups(groups -> withParents(append(groups, new Group(path(name), List.of(), true))));
    }

    /** Renames or moves a group, its subgroups going with it; false when {@code to} is taken or inside {@code from}. */
    public boolean renameGroup(String from, String to) {
        String target = path(to);
        if (indexOf(from) < 0 || !isFreeName(target) || isWithin(target, from)) return false;
        return changeGroups(groups -> {
            List<Group> out = new ArrayList<>(groups.size());
            for (Group group : groups) {
                String label = group.label();
                out.add(isWithin(label, from)
                        ? new Group(target + label.substring(from.length()), group.kinds(), true)
                        : group);
            }
            return withParents(out);
        });
    }

    /** Deletes a group and its subgroups. Their kinds stay in the Library. */
    public boolean deleteGroup(String name) {
        if (indexOf(name) < 0) return false;
        return changeGroups(groups -> {
            List<Group> out = new ArrayList<>(groups);
            out.removeIf(group -> isWithin(group.label(), name));
            return out;
        });
    }

    /** How many groups sit inside {@code name}, at any depth. */
    public int subgroupCount(String name) {
        int count = 0;
        for (Group group : groups()) {
            if (!group.label().equals(name) && isWithin(group.label(), name)) count++;
        }
        return count;
    }

    /** Whether {@code label} is {@code group} or inside it. */
    private static boolean isWithin(String label, String group) {
        return label.equals(group) || label.startsWith(group + Group.SEPARATOR);
    }

    /** {@code groups} with every missing parent made, each just before its first child. A shipped parent is not made. */
    private static List<Group> withParents(List<Group> groups) {
        Set<String> labels = new HashSet<>();
        for (Group group : groups) labels.add(group.label());
        List<Group> out = new ArrayList<>(groups.size());
        for (Group group : groups) {
            List<Group> missing = new ArrayList<>();
            for (String parent = group.parent(); parent != null; parent = Group.parentOf(parent)) {
                if (!isShipped(parent) && labels.add(parent)) missing.add(0, new Group(parent, List.of(), true));
            }
            out.addAll(missing);
            out.add(group);
        }
        return out;
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
