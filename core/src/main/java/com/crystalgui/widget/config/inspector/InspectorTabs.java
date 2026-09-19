package com.crystalgui.widget.config.inspector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.crystalgui.core.data.DataContext;
import com.crystalgui.widget.config.ConfigControl;
import com.crystalgui.widget.config.ConfiguratorPanel;
import com.crystalgui.widget.layout.Tab;
import com.crystalgui.widget.layout.TabView;

/**
 * What the {@link Inspector} shows once it knows its subject: a panel per tab, refilled from that tab's sections, and
 * a tab strip that changes only when the set of tabs does.
 *
 * <h3>Refilled, not rebuilt</h3>
 *
 * <p>Selecting the next node of the same kind asks every section for the same rows over a different subject, so the
 * panels are {@linkplain ConfiguratorPanel#refill refilled}: each row the sections place again is the one already on
 * screen, bound to the new values. Nothing is torn down, built, restyled or laid out anew — a click costs what its
 * values do. A node of another kind still places different rows, and those are built as before.</p>
 *
 * <h3>A panel outlives its tab</h3>
 *
 * <p>What a panel remembers is view state — which foldouts are open, where it is scrolled to — so it has to survive
 * a subject change, and a panel discarded with its tab remembers nothing. So panels are kept by tab name and never
 * pruned: a tab that goes when its sections stop answering finds its foldouts as it left them when it comes back.
 * The map is bounded by the number of distinct tab names, which is a handful.</p>
 *
 * <h3>A hidden tab is kept out of the tree</h3>
 *
 * <p>An unselected pane is {@code display: none}, which lays out nothing and still leaves every element in it to be
 * matched against every sheet. So a tab's panel goes into its pane the first time the tab is shown; until then it
 * is refilled detached, which costs no cascade at all. A row follows its property only while connected, so a panel
 * attached later is current when it appears.</p>
 */
final class InspectorTabs {

    private final TabView tabs;

    /** The tabs shown, in section order. */
    private final Map<String, Tab> tabsByName = new LinkedHashMap<>();

    /** One per tab name ever shown, kept. @see InspectorTabs the class note */
    private final Map<String, ConfiguratorPanel> panels = new LinkedHashMap<>();

    /** Shown tabs whose panel is not in its pane yet. */
    private final Set<String> waiting = new LinkedHashSet<>();

    InspectorTabs(TabView tabs) {
        this.tabs = tabs;
        tabs.onTabSelected.connect(tab -> attach(nameOf(tab)));
    }

    /**
     * Refills each tab's panel from its sections and shows the tabs that wrote something.
     *
     * @return whether any did — false leaves nothing shown
     */
    boolean show(DataContext context, List<InspectorSection> sections) {
        String wasSelected = selectedName();
        Set<String> before = new LinkedHashSet<>(tabsByName.keySet());

        List<String> shown = new ArrayList<>();
        for (Map.Entry<String, List<InspectorSection>> tab : byTab(sections).entrySet()) {
            ConfiguratorPanel panel = panels.computeIfAbsent(tab.getKey(), name -> {
                ConfiguratorPanel made = new ConfiguratorPanel();
                made.addClass(Inspector.SCROLL_CLASS);
                return made;
            });
            // A SECTION MAY ACCEPT AND WRITE NOTHING -- accepts() answers about a kind of subject -- and a tab
            // holding an empty panel reads as broken, which is why Blender hides a panel whose poll fails.
            if (panel.refill(form -> {
                for (InspectorSection section : tab.getValue()) section.build(form, context);
            })) {
                shown.add(tab.getKey());
            }
        }

        if (!shown.equals(new ArrayList<>(tabsByName.keySet()))) restrip(shown);
        if (shown.isEmpty()) return false;
        tabs.selectTab(tabToSelect(wasSelected, before));
        // WHETHER OR NOT THE SELECTION CHANGED: a tab kept across the refill selects nothing new and fires nothing.
        attach(selectedName());
        return true;
    }

    /** Shows no tabs. The panels are kept. */
    void clear() {
        restrip(List.of());
    }

    Set<String> names() {
        return tabsByName.keySet();
    }

    boolean select(String name) {
        Tab tab = tabsByName.get(name);
        if (tab == null) return false;
        tabs.selectTab(tab);
        return true;
    }

    /** Whether a control in a shown panel is mid-gesture. */
    boolean isInteracting() {
        for (String name : tabsByName.keySet()) {
            for (ConfigControl control : panels.get(name).controls().values()) {
                if (control.isInteracting()) return true;
            }
        }
        return false;
    }

    /** The sections by the tab each writes into, in the order they came. */
    private static Map<String, List<InspectorSection>> byTab(List<InspectorSection> sections) {
        Map<String, List<InspectorSection>> byTab = new LinkedHashMap<>();
        for (InspectorSection section : sections) byTab.computeIfAbsent(section.tab(), tab -> new ArrayList<>()).add(section);
        return byTab;
    }

    /** A new strip for a new set of tabs, each panel waiting for its tab to be shown. */
    private void restrip(List<String> shown) {
        for (ConfiguratorPanel panel : panels.values()) panel.removeSelf();
        tabs.clearTabs();
        tabsByName.clear();
        waiting.clear();
        for (String name : shown) {
            tabsByName.put(name, tabs.addTab(name));
            waiting.add(name);
        }
    }

    /** Puts {@code name}'s panel into its pane, the first time its tab is shown on this strip. */
    private void attach(@Nullable String name) {
        if (name == null || !waiting.remove(name)) return;
        tabsByName.get(name).content().append(panels.get(name));
    }

    /**
     * <b>A tab that has just appeared wins the selection</b>; otherwise the tab you were on, and only then the first.
     *
     * <p>A tab exists only because a section polled true, so a new one is the engine's own evidence that the subject
     * gained something it could not describe a moment ago — which is the thing worth looking at, as Unity's Shader
     * Graph focuses Node Settings on selection. Self-limiting: it fires once per appearance, so switching between
     * two nodes leaves you where you were.</p>
     */
    private Tab tabToSelect(@Nullable String wasSelected, Set<String> before) {
        for (Map.Entry<String, Tab> entry : tabsByName.entrySet()) {
            if (!before.contains(entry.getKey())) return entry.getValue();
        }
        Tab remembered = wasSelected == null ? null : tabsByName.get(wasSelected);
        return remembered != null ? remembered : tabsByName.values().iterator().next();
    }

    @Nullable
    private String selectedName() {
        return nameOf(tabs.getSelectedTab());
    }

    @Nullable
    private String nameOf(@Nullable Tab tab) {
        for (Map.Entry<String, Tab> entry : tabsByName.entrySet()) {
            if (entry.getValue() == tab) return entry.getKey();
        }
        return null;
    }
}
