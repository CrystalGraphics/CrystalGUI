package com.crystalgui.app.uibuilder.library;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.crystalgui.core.search.SearchMatch;
import com.crystalgui.core.search.SearchMatcher;
import com.crystalgui.core.search.SearchQuery;
import com.crystalgui.ui.dom.KindInfo;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.Preview;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;

/**
 * Every kind a document can place, filed as the Library panel lists it: shipped groups first, then a tree of
 * categories, each kind with the preview a card draws.
 *
 * <pre>{@code
 * LibraryCatalog catalog = LibraryCatalog.current();
 * catalog.roots("");        // Common, then Controls ▸, Layout ▸, … and one folder per addon namespace
 * catalog.roots("press");   // a ranked flat list — Button first, by its synonym
 * catalog.entry(Button.NAME).preview();
 * }</pre>
 *
 * <p>A kind is listed when it can be built and its {@link KindInfo#listed} is true — an addon's widget needs
 * no declaration. One with no category files under a folder named for its namespace.</p>
 */
public final class LibraryCatalog {

    /** One placeable kind. */
    public record Entry(Name kind, String label, KindInfo info) {

        public Preview preview() {
            return info.preview();
        }

        /** A fresh node to place: the kind's starter, so a button arrives labelled, else its factory's plain build. */
        public UIElement build() {
            return info.starter() != null ? info.starter().get() : UIElementRegistry.create(kind);
        }

        /** A fresh node for a card: the declared sample, else what placing it would insert. */
        public UIElement sample() {
            return info.preview() instanceof Preview.Sample sample ? sample.sample().get() : build();
        }

        /** Where it files: its category's segments, else its namespace. */
        public List<String> path() {
            List<String> segments = info.categorySegments();
            return segments.isEmpty() ? List.of(kind.namespace()) : segments;
        }
    }

    /**
     * A row of the catalog: a folder with children, or one entry.
     *
     * @param group the group a folder lists, or null for a category or an entry
     */
    public record Node(String label, @Nullable Entry entry, List<Node> children, @Nullable Group group) {

        public Node(String label, @Nullable Entry entry, List<Node> children) {
            this(label, entry, children, null);
        }

        public boolean isCategory() {
            return entry == null;
        }
    }

    /**
     * A group listed ahead of the categories: a shipped one, or one a user made.
     *
     * @param user whether a user made it, and may rename, delete and fill it
     */
    public record Group(String label, List<Name> kinds, boolean user) {

        /** A shipped group. */
        public Group(String label, List<Name> kinds) {
            this(label, kinds, false);
        }
    }

    private final List<Entry> entries;
    private final Map<Name, Entry> byKind = new LinkedHashMap<>();
    private final List<Group> groups;

    private LibraryCatalog(List<Entry> entries, List<Group> groups) {
        this.entries = List.copyOf(entries);
        for (Entry entry : this.entries) byKind.put(entry.kind(), entry);
        this.groups = List.copyOf(groups);
    }

    /** The catalog of everything registered now, with the shipped groups. */
    public static LibraryCatalog current() {
        return current(List.of());
    }

    /** As {@link #current()}, with a user's groups after the shipped ones. */
    public static LibraryCatalog current(List<Group> userGroups) {
        List<Name> buildable = new ArrayList<>();
        for (Name name : UIElementRegistry.names()) {
            if (UIElementRegistry.isBuildable(name)) buildable.add(name);
        }
        List<Group> groups = new ArrayList<>(LibraryGroups.SHIPPED);
        groups.addAll(userGroups);
        return of(buildable, UIElementRegistry::infoOf, groups);
    }

    /** A catalog over {@code kinds}, each already known to be buildable, described by {@code info}. */
    public static LibraryCatalog of(Collection<Name> kinds, Function<Name, KindInfo> info, List<Group> groups) {
        List<Entry> entries = new ArrayList<>();
        for (Name kind : kinds) {
            KindInfo described = info.apply(kind);
            if (!described.listed()) continue;
            String label = described.displayName() != null ? described.displayName() : kind.local();
            entries.add(new Entry(kind, label, described));
        }
        entries.sort(Comparator.comparing(Entry::label, String.CASE_INSENSITIVE_ORDER));
        return new LibraryCatalog(entries, groups);
    }

    /** Every listed entry, by label. */
    public List<Entry> entries() {
        return entries;
    }

    @Nullable
    public Entry entry(Name kind) {
        return byKind.get(kind);
    }

    /** The browsing tree for a blank query; a ranked flat list for anything else. */
    public List<Node> roots(String query) {
        return query.trim().isEmpty() ? tree() : search(query);
    }

    /** Groups first, then categories by name, each holding its sub-categories before its entries. */
    public List<Node> tree() {
        List<Node> roots = new ArrayList<>();
        for (Group group : groups) {
            List<Node> members = new ArrayList<>();
            for (Name kind : group.kinds()) {
                Entry entry = byKind.get(kind);
                if (entry != null) members.add(leaf(entry));
            }
            // A USER'S EMPTY GROUP IS LISTED, or a group just made would have nowhere to drag a card to.
            if (!members.isEmpty() || group.user()) roots.add(new Node(group.label(), null, members, group));
        }

        Folder root = new Folder();
        for (Entry entry : entries) {
            Folder folder = root;
            for (String segment : entry.path()) folder = folder.child(segment);
            folder.entries.add(entry);
        }
        roots.addAll(root.nodes());
        return roots;
    }

    /** Every entry the query matches by label, synonym, description or category, best first. */
    public List<Node> search(String query) {
        SearchQuery parsed = SearchQuery.of(query);
        List<Ranked> ranked = new ArrayList<>();
        for (Entry entry : entries) {
            SearchMatch match = SearchMatcher.match(parsed, entry.label(), SearchMatch.FIELD_PRIMARY);
            match = SearchMatch.best(match, SearchMatcher.matchAny(parsed, entry.info().synonyms(), SearchMatch.FIELD_ALIAS));
            match = SearchMatch.best(match, SearchMatcher.matchAny(parsed, entry.path(), SearchMatch.FIELD_CONTEXT));
            if (match != null) ranked.add(new Ranked(entry, match));
        }
        ranked.sort(Comparator.comparing(Ranked::match));
        List<Node> flat = new ArrayList<>(ranked.size());
        for (Ranked each : ranked) flat.add(leaf(each.entry()));
        return flat;
    }

    private static Node leaf(Entry entry) {
        return new Node(entry.label(), entry, List.of());
    }

    private record Ranked(Entry entry, SearchMatch match) {
    }

    private static final class Folder {
        final Map<String, Folder> children = new LinkedHashMap<>();
        final List<Entry> entries = new ArrayList<>();

        Folder child(String name) {
            return children.computeIfAbsent(name, ignored -> new Folder());
        }

        List<Node> nodes() {
            List<String> names = new ArrayList<>(children.keySet());
            names.sort(String.CASE_INSENSITIVE_ORDER);
            List<Node> out = new ArrayList<>();
            for (String name : names) {
                Folder folder = children.get(name);
                List<Node> inside = folder.nodes();
                for (Entry entry : folder.entries) inside.add(leaf(entry));
                out.add(new Node(name, null, inside));
            }
            return out;
        }
    }
}
