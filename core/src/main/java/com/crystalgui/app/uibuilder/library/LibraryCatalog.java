package com.crystalgui.app.uibuilder.library;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.glyph.KindGlyphs;
import com.crystalgui.core.search.SearchMatch;
import com.crystalgui.core.search.SearchMatcher;
import com.crystalgui.core.search.SearchQuery;
import com.crystalgui.ui.dom.GlyphRole;
import com.crystalgui.ui.dom.KindInfo;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.Preview;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;

/**
 * Everything a document can place, filed as the Library panel lists it: the starters, then the groups, then a tree
 * of categories, each entry with the preview a card draws.
 *
 * <pre>{@code
 * LibraryCatalog catalog = LibraryCatalog.current();
 * catalog.roots("");        // Starters, Common, then Controls ▸, Layout ▸, … and one folder per addon namespace
 * catalog.roots("press");   // a ranked flat list — Button first, by its synonym
 * catalog.entry(Button.NAME).preview();
 * catalog.entry("starter:crystalgui:uibuilder/starters/card").build();
 * }</pre>
 *
 * <p>A kind is listed when it can be built and its {@link KindInfo#listed} is true — an addon's widget needs
 * no declaration. One with no category files under a folder named for its namespace.</p>
 */
public final class LibraryCatalog {

    /**
     * One thing to place: a kind, or a starter — a snippet of several nodes, which is no one kind.
     *
     * <pre>{@code
     * new Entry(Button.NAME, "Button", info);                             // id "kind:crystalgui:button"
     * Entry.starter("crystalgui:uibuilder/starters/card", "Card", info);   // kind null; info carries the build
     * }</pre>
     *
     * @param id   what a card, a selection and a recent pick know it by
     * @param kind the kind, or null for a starter
     */
    public record Entry(String id, @Nullable Name kind, String label, KindInfo info) {

        /** A kind. */
        public Entry(Name kind, String label, KindInfo info) {
            this("kind:" + kind, kind, label, info);
        }

        /** A starter, built by {@code info}'s starter and drawn by its glyph. */
        public static Entry starter(String asset, String label, KindInfo info) {
            if (info.starter() == null) throw new IllegalArgumentException("a starter's info must build it: " + asset);
            return new Entry("starter:" + asset, null, label, info);
        }

        public boolean isStarter() {
            return kind == null;
        }

        /** What a row and a card draw for it: a kind's own glyph, or the one a starter declares, else a component. */
        public KindGlyphs.Glyph glyph() {
            if (kind != null) return KindGlyphs.ofKind(kind);
            String icon = info.glyphIcon() != null ? info.glyphIcon() : KindGlyphs.COMPONENT_ICON;
            return new KindGlyphs.Glyph(icon, info.glyphRole() != null ? info.glyphRole() : GlyphRole.LAYOUT, label);
        }

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
            return segments.isEmpty() && kind != null ? List.of(kind.namespace()) : segments;
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
     * <pre>{@code
     * new Group("Mine", kinds, true);           // a group
     * new Group("Mine/Buttons", kinds, true);   // listed inside "Mine", as "Buttons"
     * }</pre>
     *
     * @param label its path: {@link #SEPARATOR}-separated, and a group nests inside the group its parent path names
     * @param user  whether a user made it, and may rename, delete and fill it
     */
    public record Group(String label, List<Name> kinds, boolean user) {

        /** Between a group's name and its parent's, as a category path's. */
        public static final String SEPARATOR = "/";

        /** A shipped group. */
        public Group(String label, List<Name> kinds) {
            this(label, kinds, false);
        }

        /** Its own name, without its parents'. */
        public String name() {
            return label.substring(label.lastIndexOf(SEPARATOR) + 1);
        }

        /** Its parent's path, or null at the top. */
        @Nullable
        public String parent() {
            return parentOf(label);
        }

        /** The parent of the group at {@code path}, or null at the top. */
        @Nullable
        public static String parentOf(String path) {
            int at = path.lastIndexOf(SEPARATOR);
            return at < 0 ? null : path.substring(0, at);
        }
    }

    private final List<Entry> entries;
    private final List<Entry> starters;
    private final Map<Name, Entry> byKind = new LinkedHashMap<>();
    /** Starters then kinds, which is also the order a search considers them in. */
    private final Map<String, Entry> byId = new LinkedHashMap<>();
    private final List<Group> groups;

    private LibraryCatalog(List<Entry> entries, List<Entry> starters, List<Group> groups) {
        this.entries = List.copyOf(entries);
        this.starters = List.copyOf(starters);
        for (Entry entry : this.entries) byKind.put(entry.kind(), entry);
        for (Entry entry : this.starters) byId.put(entry.id(), entry);
        for (Entry entry : this.entries) byId.put(entry.id(), entry);
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
        return of(buildable, UIElementRegistry::infoOf, groups).withStarters(LibraryStarters.ALL);
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
        return new LibraryCatalog(entries, List.of(), groups);
    }

    /** This catalog with {@code starters} listed ahead of everything, in the order given. */
    public LibraryCatalog withStarters(List<Entry> starters) {
        return new LibraryCatalog(entries, starters, groups);
    }

    /** Every listed kind, by label. */
    public List<Entry> entries() {
        return entries;
    }

    /** The starters, in the order they are listed. */
    public List<Entry> starters() {
        return starters;
    }

    /** The shipped groups, then the user's, in the order the Library lists them. */
    public List<Group> groups() {
        return groups;
    }

    @Nullable
    public Entry entry(Name kind) {
        return byKind.get(kind);
    }

    /** A kind or a starter by its {@link Entry#id}. */
    @Nullable
    public Entry entry(String id) {
        return byId.get(id);
    }

    /** The browsing tree for a blank query; a ranked flat list for anything else. */
    public List<Node> roots(String query) {
        return query.trim().isEmpty() ? tree() : search(query);
    }

    /** Starters first, then groups, then categories by name, each holding its sub-categories before its entries. */
    public List<Node> tree() {
        List<Node> roots = new ArrayList<>();
        if (!starters.isEmpty()) {
            // FILED BY THEIR PATH UNDER STARTERS, as a category is: `Starters/Layout` lists inside it as Layout.
            Folder snippets = new Folder();
            for (Entry starter : starters) {
                Folder folder = snippets;
                List<String> path = starter.path();
                int from = !path.isEmpty() && path.get(0).equals(LibraryStarters.FOLDER) ? 1 : 0;
                for (String segment : path.subList(from, path.size())) folder = folder.child(segment);
                folder.entries.add(starter);
            }
            List<Node> inside = snippets.nodes();
            for (Entry starter : snippets.entries) inside.add(leaf(starter));
            roots.add(new Node(LibraryStarters.FOLDER, null, inside));
        }
        roots.addAll(groupNodes(null));

        Folder root = new Folder();
        for (Entry entry : entries) {
            Folder folder = root;
            for (String segment : entry.path()) folder = folder.child(segment);
            folder.entries.add(entry);
        }
        roots.addAll(root.nodes());
        return roots;
    }

    /** The groups inside the one at {@code parent}, or the top ones for null: subgroups before cards, as a category. */
    private List<Node> groupNodes(@Nullable String parent) {
        Set<String> labels = new HashSet<>();
        for (Group group : groups) labels.add(group.label());
        List<Node> out = new ArrayList<>();
        for (Group group : groups) {
            // A GROUP WHOSE PARENT IS GONE LISTS AT THE TOP rather than vanishing with it.
            String listedUnder = group.parent() != null && labels.contains(group.parent()) ? group.parent() : null;
            if (!Objects.equals(listedUnder, parent)) continue;
            List<Node> inside = groupNodes(group.label());
            for (Name kind : group.kinds()) {
                Entry entry = byKind.get(kind);
                if (entry != null) inside.add(leaf(entry));
            }
            // A USER'S EMPTY GROUP IS LISTED, or a group just made would have nowhere to drag a card to.
            if (!inside.isEmpty() || group.user()) out.add(new Node(group.name(), null, inside, group));
        }
        return out;
    }

    /** Every entry the query matches by label, synonym, description or category, best first. */
    public List<Node> search(String query) {
        SearchQuery parsed = SearchQuery.of(query);
        List<Ranked> ranked = new ArrayList<>();
        for (Entry entry : byId.values()) {
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
