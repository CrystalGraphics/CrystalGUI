package com.crystalgui.widget.config;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.widget.scroll.ScrollerView;

import com.crystalgui.core.config.ConfigDescriptor;
import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import com.crystalgui.ui.dom.Name;

/**
 * <b>The inspector surface: a scrolling stack of rows.</b>
 *
 * <p>Unity reference: marker <b>F</b> in {@code docs/research/unity-inspector/07-full-window.png}.</p>
 *
 * <h3>A {@link ScrollerView}, and that is not incidental</h3>
 * <p>Setting {@code overflow} alone does not make something take the wheel in this engine — a bare
 * overflow box scrolls by API and ignores the mouse. Unity's inspector has a scrollbar in the
 * reference shot for a reason: a real panel is taller than its pane the moment a target has more than
 * a handful of properties.</p>
 *
 * <h3>One signal for the whole panel</h3>
 * <p>Ported from LDLib2, which emits a single {@code configurator.change} rather than a listener per
 * row. A host wiring twenty rows individually writes twenty closures that all do the same thing, and
 * the twenty-first row silently does nothing when someone forgets. {@link #changed} carries the id, so
 * one listener can serve a whole panel — and an id is also what an {@code Edit} needs, which is the
 * shape undo wants when a host gets around to recording it.</p>
 */
public class ConfiguratorPanel extends ScrollerView {

    public static final Name NAME = Name.of("configuratorpanel");

    public static final String PANEL_CLASS = "__configurator-panel__";

    /** {@code (id, newValue)} for any row in this panel, however deeply grouped. */
    public final Signal.Pair<String, Object> changed = new Signal.Pair<>();

    private final Map<String, ConfigControl> controls = new LinkedHashMap<>();

    /**
     * Which groups the user has opened or closed, by title — and <b>deliberately outlives
     * {@link #clearRows}</b>.
     *
     * <p>A foldout is view state: it says how you are looking at the thing rather than what the thing is,
     * so it belongs on the same side of the line as scroll position and selection, and it must survive a
     * rebuild. Without this, a panel bound to a selection re-collapses every group each time you click
     * something else — you open {@code About} to read a node's type, click the next node, and it has shut
     * itself again.</p>
     *
     * <p>Keyed by <b>title</b> rather than by identity, because the group object is destroyed and
     * rebuilt; the title is the only thing that survives, and it is also what the user recognises. Two
     * groups sharing a title in one panel would share a state, which is the correct answer anyway.</p>
     */
    private final Map<String, Boolean> groupCollapsed = new LinkedHashMap<>();

    public ConfiguratorPanel() {
        super(NAME);
        addClass(PANEL_CLASS);
    }

    /**
     * Builds a row for {@code descriptor} and appends it, or returns null when the kind has no control.
     *
     * @param value the current value, or null to take the descriptor's default
     */
    @Nullable
    public Configurator add(ConfigDescriptor descriptor, @Nullable Object value) {
        return addTo(this, descriptor, value);
    }

    /** As {@link #add}, into a group's content rather than the panel root. */
    @Nullable
    public Configurator addTo(UINode parent, ConfigDescriptor descriptor, @Nullable Object value) {
        ConfigControl control = ConfigControls.create(descriptor, value);
        if (control == null) return null;
        Configurator row = new Configurator(descriptor, control);
        controls.put(descriptor.id(), control);
        control.changed.connect(v -> changed.emit(descriptor.id(), v));
        parent.append(row);
        return row;
    }

    /**
     * Builds a whole tree of descriptors, groups included.
     *
     * <p>A {@link ConfigDescriptor.Kind#GROUP} becomes a {@link ConfiguratorGroup} and recurses;
     * anything else becomes a row.</p>
     */
    public ConfiguratorPanel build(java.util.List<ConfigDescriptor> descriptors,
                                   java.util.function.Function<String, Object> values) {
        for (ConfigDescriptor descriptor : descriptors) {
            buildInto(this, descriptor, values);
        }
        return this;
    }

    private void buildInto(UINode parent, ConfigDescriptor descriptor,
                           java.util.function.Function<String, Object> values) {
        if (descriptor.kind() == ConfigDescriptor.Kind.GROUP) {
            ConfiguratorGroup group = new ConfiguratorGroup(descriptor.label());
            parent.append(group);
            for (ConfigDescriptor child : descriptor.children()) {
                buildInto(group.content(), child, values);
            }
            return;
        }
        addTo(parent, descriptor, values.apply(descriptor.id()));
    }

    /**
     * Appends a row built around a control the caller already has.
     *
     * <p>The seam a host needs when the widget cannot come from {@link ConfigControls} — a shader
     * inspector builds its rows through {@code NodeFieldWidgets} so that a value has exactly one writer
     * whether it is edited on the node or in the panel, and it still wants this panel's row rhythm,
     * label column and change signal.</p>
     */
    public Configurator addRow(UINode parent, String label, String id, ConfigControl control) {
        Configurator row = new Configurator(label, control);
        controls.put(id, control);
        control.changed.connect(value -> changed.emit(id, value));
        parent.append(row);
        return row;
    }

    /**
     * Empties the panel — every row, every group, and the control index with them.
     *
     * <p>For a panel that is <b>rebuilt</b> rather than merely updated, which any inspector bound to a
     * selection is. Clearing the children without clearing the index would leave {@link #control} handing
     * back widgets that are no longer on screen, and {@link #setValue} silently writing into them.</p>
     *
     * <h3>Why this cannot be {@code clearAllChildren()}</h3>
     * <p>It was, and it removed <b>nothing at all</b>. {@link Configurator} and {@link ConfiguratorGroup}
     * each call {@code markAsInternal()} on themselves — they are assembled widgets whose parts an
     * inspector should not walk into — and {@code clearAllChildren()} deliberately skips internal
     * children. So every rebuild appended, and a panel bound to a selection grew a fresh copy of itself
     * on each change while showing every previous one above it.</p>
     *
     * <p>{@code removeInternalChild} is the documented escape hatch for exactly this: a widget removing a
     * part it owns. It is applied <b>by type</b> rather than to every internal child, because a
     * {@link ScrollerView} owns internal children too — its two scrollbars and their corner — and
     * sweeping those out would leave the panel unable to scroll and unable to get them back.</p>
     */
    public void clearRows() {
        for (UINode child : new java.util.ArrayList<>(children())) {
            if (child instanceof Configurator || child instanceof ConfiguratorGroup) {
                remove(child);
            }
        }
        controls.clear();
    }


    /**
     * A group whose open state is <b>remembered across rebuilds</b>. Use this instead of
     * {@code new ConfiguratorGroup(...)} in any panel that rebuilds itself.
     *
     * <p>{@code defaultCollapsed} applies only the first time this panel sees the title; after that the
     * user's own answer wins. It is not added to the panel for you — a group may belong inside another
     * group, and only the caller knows.</p>
     *
     * @see #groupCollapsed
     */
    public ConfiguratorGroup group(String title, boolean defaultCollapsed) {
        ConfiguratorGroup group =
                new ConfiguratorGroup(title, groupCollapsed.getOrDefault(title, defaultCollapsed));
        group.collapsedChanged.connect(collapsed -> groupCollapsed.put(title, collapsed));
        return group;
    }

    /** As {@link #group(String, boolean)}, starting open. */
    public ConfiguratorGroup group(String title) {
        return group(title, false);
    }

    /** Forgets every remembered foldout state — for a panel switching to an unrelated subject. */
    public void forgetGroupStates() {
        groupCollapsed.clear();
    }

    /** The control for an id, or null. */
    @Nullable
    public ConfigControl control(String id) {
        return controls.get(id);
    }

    /** Pushes a value into a row without echoing back out through {@link #changed}. */
    public void setValue(String id, @Nullable Object value) {
        ConfigControl control = controls.get(id);
        if (control != null) control.setValueObject(value);
    }

    public Map<String, ConfigControl> controls() {
        return java.util.Collections.unmodifiableMap(controls);
    }
}
