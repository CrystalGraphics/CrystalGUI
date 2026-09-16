package com.crystalgui.widget.config;

import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.style.property.visual.Overflow;
import dev.vfyjxf.taffy.geometry.FloatRect;
import com.crystalgui.ui.box.Box;
import com.crystalgui.widget.config.control.HeaderControl;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.scroll.ScrollerView;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.annotation.Nullable;

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
     * A section band spans the whole scrollable width, not just the viewport.
     *
     * <p>A header is the only thing in this panel that is a <em>surface</em> rather than a value: it says
     * "a section starts here", and a band that stops at the viewport's edge stops saying it the moment
     * the panel is scrolled sideways — the rows carry on past a strip that ran out. Rows are viewport-wide
     * by design (see {@code .__inline__} in ua/inspector.css for why that basis has to be definite), so
     * the width a band wants is the one thing CSS here cannot name: it belongs to the scroller, several
     * levels up.</p>
     *
     * <p><b>Counted by its contents, which is what stops this closing a loop.</b> The extent is the widest thing
     * in the panel; a band stretched TO the extent would then be that widest thing, and the extent would be
     * defined in terms of itself — so a band never narrowed again once the panel had been wide.
     * {@link #scrollExtent} counts a band by what it holds, so a long heading still widens the panel while the
     * stretch never does. Scroll-exempt as well, so a band does not slide away when the panel scrolls.</p>
     */
    @Override
    protected void connected() {
        super.connected();
        document().animation().afterLayout(this, delta -> {
            stretchSectionBands();
            return true;
        });
    }

    private void stretchSectionBands() {
        Box self = box();
        if (self == null) return;
        float span = Math.max(self.clientWidth(), self.scrollWidth());
        if (span <= 0f) return;
        for (UIElement node : composedSubtree()) {
            if (!(node instanceof HeaderControl)) continue;
            // Once, not per frame: an unchanged inline candidate is dropped by replaceOrPutCandidate,
            // so this settles rather than re-dirtying layout every pass.
            node.setScrollExempt(true);
            StyleGroup.inlinePipeline(node.getStyle().getLayoutGroup(), l -> l.width(span));
        }
    }

    /**
     * The width the content needs, counting a section band by what it holds rather than by its box — the box is
     * stretched TO this width, so counting it would hold the panel at the widest it has ever been. Heights are
     * read from the box as usual.
     */
    @Override
    public float scrollExtent(boolean horizontal) {
        if (!horizontal || box() == null) return -1f;
        return widestContent(this, 0f);
    }

    /** The furthest right edge under {@code parent}, in {@code parent}'s space offset by {@code left}. */
    private static float widestContent(UIElement parent, float left) {
        float widest = 0f;
        for (UIElement child : parent.composedChildren()) {
            // A bar pinned to the viewport is not content the rows need room for.
            if (child.isScrollExempt() && !(child instanceof HeaderControl)) continue;
            // NOR IS A POPUP: an open dropdown's menu is a part of the dropdown and hosted by the top layer, so its box
            // is placed in the top layer's space. Read here as the row's, the list stretched the panel and put a
            // horizontal scrollbar under it for as long as it was open.
            UIDocument window = child.document();
            if (window != null && window.isPromoted(child)) continue;
            Box box = child.box();
            if (box == null) continue;
            float x = left + box.x();
            if (child instanceof HeaderControl) {
                widest = Math.max(widest, x + naturalWidth(child, box));
                continue;
            }
            widest = Math.max(widest, x + box.width());
            // A NESTED SCROLLER keeps its overflow to itself: only its own box is this panel's business.
            if (child.getStyle().computed().get(StylePropertyRegistry.OVERFLOW) == Overflow.VISIBLE) {
                widest = Math.max(widest, widestContent(child, x));
            }
        }
        return widest;
    }

    /**
     * How wide a band's contents are laid end to end: its padding, and each part that does not grow with its
     * margins. A part that grows is taking the band's stretch, which is exactly what must not be counted.
     */
    private static float naturalWidth(UIElement band, Box box) {
        FloatRect padding = box.padding();
        float width = padding.left + padding.right;
        for (UIElement part : band.composedChildren()) {
            Box partBox = part.box();
            if (partBox == null || part.getStyle().computed().get(LayoutProperties.FLEX_GROW) > 0f) continue;
            FloatRect margin = partBox.margin();
            width += margin.left + partBox.width() + margin.right;
        }
        return width;
    }

    /**
     * A form over this panel — the way to fill it.
     *
     * <pre>{@code
     * PanelForm form = panel.form();
     * form.prop(ConfigDescriptor.bool("wrap", "Word wrap"), wordWrap);
     * }</pre>
     */
    public PanelForm form() {
        return new PanelForm(this);
    }

    /** Builds a row for {@code descriptor}, bound to {@code value}, and appends it. @see ConfigForm#prop */
    public <T> Configurator prop(ConfigDescriptor descriptor, Property<T> value) {
        return propTo(this, descriptor, value);
    }

    /** As {@link #prop}, into a group's content rather than the panel root. */
    public <T> Configurator propTo(UIElement parent, ConfigDescriptor descriptor, Property<T> value) {
        return place(parent, descriptor.id(), new Configurator(descriptor, ConfigControls.bound(descriptor, value)));
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
    public Configurator addTo(UIElement parent, ConfigDescriptor descriptor, @Nullable Object value) {
        ConfigControl control = ConfigControls.create(descriptor, value);
        if (control == null) return null;
        return place(parent, descriptor.id(), new Configurator(descriptor, control));
    }

    /**
     * Builds a whole tree of descriptors, groups included.
     *
     * <p>A {@link ConfigDescriptor.Kind#GROUP} becomes a {@link ConfiguratorGroup} and recurses;
     * anything else becomes a row.</p>
     */
    public ConfiguratorPanel build(List<ConfigDescriptor> descriptors,
                                   Function<String, Object> values) {
        for (ConfigDescriptor descriptor : descriptors) {
            buildInto(this, descriptor, values);
        }
        return this;
    }

    private void buildInto(UIElement parent, ConfigDescriptor descriptor,
                           Function<String, Object> values) {
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
    public Configurator addRow(UIElement parent, String label, String id, ConfigControl control) {
        return place(parent, id, new Configurator(label, control));
    }

    /**
     * A row for {@code descriptor}, bound to {@code value} and known to this panel, <b>not yet placed</b> —
     * for a list that decides where its rows go itself.
     *
     * <pre>{@code
     * ChildList.Keyed<String, Configurator> rows = new ChildList.Keyed<>(list, name -> panel.row(descriptorOf(name), valueOf(name)));
     * rows.onRemoved((name, row) -> panel.forget(row));
     * }</pre>
     */
    public <T> Configurator row(ConfigDescriptor descriptor, Property<T> value) {
        return register(descriptor.id(), new Configurator(descriptor, ConfigControls.bound(descriptor, value)));
    }

    /** As {@link #row(ConfigDescriptor, Property)}, around a control the caller already has. @see #addRow */
    public Configurator row(String label, String id, ConfigControl control) {
        return register(id, new Configurator(label, control));
    }

    /**
     * Drops a row taken out of the panel on its own, so {@link #control} stops answering with it. What
     * {@link #clearRows} does for every row.
     */
    public void forget(Configurator row) {
        controls.values().removeIf(control -> control == row.control());
    }

    private Configurator place(UIElement parent, String id, Configurator row) {
        parent.append(register(id, row));
        return row;
    }

    private Configurator register(String id, Configurator row) {
        ConfigControl control = row.control();
        controls.put(id, control);
        control.changed.connect(value -> changed.emit(id, value));
        return row;
    }

    /**
     * Empties the panel — every row, every group, and the control index with them.
     *
     * <p>For a panel that is <b>rebuilt</b> rather than merely updated, which any inspector bound to a
     * selection is. Clearing the children without clearing the index would leave {@link #control} handing
     * back widgets that are no longer on screen, and {@link #setValue} silently writing into them.</p>
     *
     * <p><b>Every light child goes, not only rows and groups</b>: an element a form placed with
     * {@code custom} is content too, and one left behind stacks a copy per rebuild. The scrollbars and
     * their corner are shadow parts, never light children, so nothing the panel owns is swept out.</p>
     */
    public void clearRows() {
        removeAll();
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
        return Collections.unmodifiableMap(controls);
    }
}
