package com.crystalgui.app.uibuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import dev.vfyjxf.taffy.style.LengthPercentageAuto;
import dev.vfyjxf.taffy.style.TaffyPosition;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import com.crystalgui.app.uibuilder.canvas.BuilderEditor;
import com.crystalgui.app.uibuilder.style.BuilderStyleSections;
import com.crystalgui.app.uibuilder.canvas.BuilderToolbar;
import com.crystalgui.app.uibuilder.document.BuilderEdit;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.app.uibuilder.inspect.BoxModelEditor;
import com.crystalgui.app.uibuilder.inspect.HeaderFields;
import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.app.uibuilder.inspect.NodeFields;
import com.crystalgui.app.uibuilder.inspect.StyleScrub;
import com.crystalgui.ui.box.Box;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.data.DataContext;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.PseudoClasses;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.style.selector.CompoundSelector;
import com.crystalgui.style.selector.SelectorType;
import com.crystalgui.style.sheet.StyleRule;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.theme.ThemeRegistry;
import com.crystalgui.style.theme.UiTheme;
import com.crystalgui.template.TemplateInstance;
import com.crystalgui.ui.contract.State;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.WidgetContracts;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.ClassNames;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.widget.config.ConfigForm;
import com.crystalgui.widget.config.Configurator;
import com.crystalgui.widget.config.ValueControl;
import com.crystalgui.widget.config.control.ClassChips;
import com.crystalgui.widget.config.inspector.InspectorSection;
import com.crystalgui.widget.surface.extension.SectionSet;

/**
 * What the inspector shows for a UI document — the Element, Style, Layout and Document tabs.
 *
 * <p>Shaped on {@code ShaderInspectorSections} and keeping its three rules. <b>Instances, not
 * factories</b>: a section reads its subject out of the context and holds nothing, so one instance serves
 * every document in every window. A <b>counted</b> {@link #register()}: a second editor must not double
 * the forms, and the first one closing must not empty the inspector under the second. And <b>one
 * {@link #subject} method</b> that decides exclusivity, because sections within a tab are additive by the
 * engine's design — the graph learned that when four sections were four answers to one question and a
 * marquee that caught a wire rendered two stacked panels.</p>
 *
 * <p><b>A row edits only when there is a document to write into</b> — {@link NodeFields#of} answers — and
 * the node is in it. Over a live pick every row states what is, as before: there is nothing to save to.</p>
 *
 * <p>{@code subjectKey} is the identity of what is described, never a version: an edit made through a row
 * must not rebuild the form under the pointer. Each control follows the document through its own
 * property instead.</p>
 */
public final class BuilderInspectorSections {

    private BuilderInspectorSections() {
    }

    public static final String ELEMENT_TAB = "Element";

    public static final String STYLE_TAB = "Style";

    public static final String LAYOUT_TAB = "Layout";

    public static final String DOCUMENT_TAB = "Document";

    /** On a row whose value has no effect in the node's current state — Grow while it is absolute. */
    public static final String INACTIVE_CLASS = "__inactive__";

    /** On a row whose value was set — Unity's override bar: the rows a node changes stand out. */
    public static final String SET_CLASS = "__set__";

    /** The attributes worth a row of their own; the rest fold under Advanced. */
    private static final List<Attribute<?>> COMMON_ATTRIBUTES =
            List.of(Attribute.ENABLED, Attribute.HIDDEN, Attribute.HIT_TEST, Attribute.FOCUS_POLICY);

    /** Labels for names too long for the label column; the rest are the name made readable. */
    private static final Map<String, String> SHORT_LABELS = Map.of(
            "keeps-modifier-press", "Keeps modifiers",
            "session-persistent", "Session persist",
            "focus-policy", "Focus");

    /** What the current selection IS, decided once so no two sections can both claim it. */
    private enum Subject {
        NONE, CANVAS, NODE, MULTI, INSTANCE
    }

    private static final SectionSet SECTIONS = SectionSet.of(sections());

    /** The Element, Layout and Document sections, plus the Style tab's own. @see BuilderStyleSections */
    private static InspectorSection[] sections() {
        List<InspectorSection> all = new ArrayList<>(List.of(
                new NodeSection(), new MultiNodeSection(), new AttributesSection(), new StateSection(),
                new ForcedStatesSection(), new ComputedSection(),
                new BoxModelSection(), new PositionSection(), new FlexContextSection(),
                new CanvasSection(), new DocumentSheetsSection(), new ExportSection()));
        all.addAll(BuilderStyleSections.all());
        return all.toArray(new InspectorSection[0]);
    }

    /**
     * Registers the sections, counted.
     *
     * <p>The one thing a UI builder puts in a process-wide registry, so it is what a caller has to be
     * able to hand back — everything else the extension registers goes with the workbench.</p>
     */
    public static Disposable register() {
        return SECTIONS.register();
    }

    /** How many holders the counted registration currently has. For tests. */
    public static int holders() {
        return SECTIONS.holders();
    }

    @Nullable
    private static BuilderSelection selection(DataContext context) {
        return context.get(BuilderEditor.BUILDER_SELECTION);
    }

    private static Subject subject(DataContext context) {
        BuilderSelection selection = selection(context);
        if (selection == null) return Subject.NONE;
        List<UIElement> nodes = selection.nodes();
        if (nodes.size() > 1) return Subject.MULTI;
        if (nodes.size() == 1) {
            return nodes.get(0) instanceof TemplateInstance ? Subject.INSTANCE : Subject.NODE;
        }
        return selection.canvasSelected() ? Subject.CANVAS : Subject.NONE;
    }

    @Nullable
    private static UIElement node(DataContext context) {
        BuilderSelection selection = selection(context);
        return selection == null ? null : selection.node();
    }

    /** The fields for {@code node} when it can be edited, else null — a live pick, or a node of another tree. */
    @Nullable
    private static NodeFields editable(DataContext context, @Nullable UIElement node) {
        NodeFields fields = NodeFields.of(context);
        return fields != null && fields.owns(node) ? fields : null;
    }

    /** Shared by every section that describes one node. */
    private abstract static class NodeAware implements InspectorSection {

        @Override
        public boolean accepts(DataContext context) {
            Subject subject = subject(context);
            return subject == Subject.NODE || subject == Subject.INSTANCE;
        }

        @Override
        public String subjectKey(DataContext context) {
            UIElement node = node(context);
            return getClass().getSimpleName() + ":" + (node == null ? "" : System.identityHashCode(node));
        }
    }

    // ── Element ─────────────────────────────────────────────────────────────

    /** Describes ONE node: what kind it is, and how it is identified. */
    private static final class NodeSection extends NodeAware {

        @Override
        public String tab() {
            return ELEMENT_TAB;
        }

        @Override
        public int order() {
            return 10;
        }

        @Override
        public void build(ConfigForm form, DataContext context) {
            UIElement node = node(context);
            if (node == null) return;
            // A ROW, as `header` makes one, so the band's rules reach it.
            form.control("kind", "", new KindHeader(node));
            NodeFields fields = editable(context, node);
            if (fields == null) {
                live(form, "id", "Id", () -> node.getId() == null ? "" : node.getId());
                live(form, "classes", "Classes", () -> String.join(" ", ClassNames.authored(node.classes())));
                return;
            }
            form.prop(ConfigDescriptor.text("id", "Id")
                            .tooltip("id")
                            .description("What #id selectors and code find it by. Letters, digits, - and _, and no other element's.")
                            .validator(id -> fields.idProblem(node, id) == null),
                    fields.id(node));
            form.control("classes", "Classes", classChips("classes", node, fields.classes(node)));
        }
    }

    /** Every attribute a node can carry — the ones set, and the ones at their initial, to set from. */
    private static final class AttributesSection extends NodeAware {

        @Override
        public String tab() {
            return ELEMENT_TAB;
        }

        @Override
        public int order() {
            return 30;
        }

        @Override
        public boolean accepts(DataContext context) {
            if (!super.accepts(context)) return false;
            UIElement node = node(context);
            return node != null && (editable(context, node) != null || !node.setAttributes().isEmpty());
        }

        @Override
        public void build(ConfigForm form, DataContext context) {
            UIElement node = node(context);
            if (node == null) return;
            form.header("Attributes");
            NodeFields fields = editable(context, node);
            if (fields == null) {
                for (Attribute<?> attribute : node.setAttributes()) {
                    live(form, "attr." + attribute.name(), labelOf(attribute), () -> String.valueOf(node.get(attribute)));
                }
                return;
            }
            // THE FOUR A DESIGNER SETS, then everything else folded away: most carried attributes are engine and
            // workbench plumbing, and a panel listing them all buries the ones that matter.
            ConfigForm advanced = null;
            for (Attribute<?> attribute : COMMON_ATTRIBUTES) attributeRow(form, fields, node, attribute);
            for (Attribute<?> attribute : editableAttributes()) {
                if (COMMON_ATTRIBUTES.contains(attribute)) continue;
                if (advanced == null) advanced = form.group("Advanced", true);
                attributeRow(advanced, fields, node, attribute);
            }
        }
    }

    /**
     * The kind's declared state slots, <b>in declaration order with the primary first</b>.
     *
     * <p>Declaration order is the contract's own — {@code State} slots are applied in it and several
     * widgets depend on that — so an inspector that sorted them alphabetically would describe a widget in
     * an order nothing else uses. The primary leads because it is the one a caller sets without naming
     * it.</p>
     */
    private static final class StateSection implements InspectorSection {

        @Override
        public String tab() {
            return ELEMENT_TAB;
        }

        @Override
        public int order() {
            return 20;
        }

        @Override
        public boolean accepts(DataContext context) {
            return subject(context) == Subject.NODE && contractOf(node(context)) != null;
        }

        @Override
        public String subjectKey(DataContext context) {
            UIElement node = node(context);
            return "uibuilder.state:" + (node == null ? "" : System.identityHashCode(node));
        }

        @Override
        public void build(ConfigForm form, DataContext context) {
            UIElement node = node(context);
            WidgetContract<Object> contract = contractOf(node);
            if (node == null || contract == null) return;
            form.header("State");
            NodeFields fields = editable(context, node);
            for (State<Object, ?> state : ordered(contract)) {
                if (fields == null) {
                    live(form, "state." + state.key(), NodeFields.humanize(state.key()), () -> String.valueOf(state.read(node)));
                } else {
                    NodeFields.Field field = fields.state(node, state);
                    Configurator row = prop(form, field.descriptor(), field.value());
                    UIElement pristine = pristineOf(node);
                    markSet(row, () -> !Objects.deepEquals(state.read(node),
                            pristine == null ? state.fallback() : state.read(pristine)));
                }
            }
        }
    }

    /**
     * Several nodes at once: what they are, and what they share — Figma's and Unity's multi-selection.
     *
     * <p>A class listed is one every node has; removing it removes it from all, adding one adds it to all.
     * An attribute row shows the first node's value and a set writes every node; the attributes whose values
     * differ are listed above them. Each change is one undo step for the lot.</p>
     */
    private static final class MultiNodeSection implements InspectorSection {

        @Override
        public String tab() {
            return ELEMENT_TAB;
        }

        @Override
        public int order() {
            return 10;
        }

        @Override
        public boolean accepts(DataContext context) {
            return subject(context) == Subject.MULTI;
        }

        @Override
        public String subjectKey(DataContext context) {
            BuilderSelection selection = selection(context);
            StringBuilder key = new StringBuilder("uibuilder.multi:");
            if (selection != null) {
                for (UIElement node : selection.nodes()) key.append(System.identityHashCode(node)).append(',');
            }
            return key.toString();
        }

        @Override
        public void build(ConfigForm form, DataContext context) {
            BuilderSelection selection = selection(context);
            if (selection == null) return;
            List<UIElement> nodes = selection.nodes();
            form.header(nodes.size() + " elements");
            Set<String> kinds = new LinkedHashSet<>();
            for (UIElement node : nodes) kinds.add(node.tagName());
            form.row(ConfigDescriptor.info("multi.kinds", "Kinds").tooltip("kinds")
                    .description("What the selected elements are."), String.join(", ", kinds));

            NodeFields fields = NodeFields.of(context);
            if (fields == null) return;
            for (UIElement node : nodes) {
                if (!fields.owns(node)) return;
            }
            form.control("multi.classes", "Shared classes", classChips("multi.classes", nodes.get(0), sharedClasses(nodes, fields)));

            form.header("Attributes");
            form.prop(ConfigDescriptor.info("multi.mixed", "Differ").tooltip("differ")
                    .description("The attributes whose values are not the same on every selected element."), Property.derived(() -> {
                List<String> mixed = new ArrayList<>();
                for (Attribute<?> attribute : editableAttributes()) {
                    for (UIElement node : nodes) {
                        if (!Objects.equals(node.get(attribute), nodes.get(0).get(attribute))) {
                            mixed.add(attribute.name());
                            break;
                        }
                    }
                }
                return mixed.isEmpty() ? "none" : String.join(", ", mixed);
            }));
            ConfigForm advanced = null;
            List<Attribute<?>> ordered = new ArrayList<>(COMMON_ATTRIBUTES);
            for (Attribute<?> attribute : editableAttributes()) if (!ordered.contains(attribute)) ordered.add(attribute);
            for (Attribute<?> attribute : ordered) {
                NodeFields.Field first = fields.attribute(nodes.get(0), attribute, labelOf(attribute));
                if (first.descriptor().kind() == ConfigDescriptor.Kind.INFO) continue;
                ConfigForm into = form;
                if (!COMMON_ATTRIBUTES.contains(attribute)) {
                    if (advanced == null) advanced = form.group("Advanced", true);
                    into = advanced;
                }
                attribute(into, new NodeFields.Field(first.descriptor(), allOf(first, nodes, attribute, fields)));
            }
        }

        /** The classes all of {@code nodes} have; a change adds or removes on each, as one step. */
        private static Property<List<String>> sharedClasses(List<UIElement> nodes, NodeFields fields) {
            Supplier<List<String>> shared = () -> {
                List<String> common = new ArrayList<>(ClassNames.authored(nodes.get(0).classes()));
                for (UIElement node : nodes) common.retainAll(node.classes());
                return common;
            };
            return fields.bindAll("set classes", shared, wanted -> {
                List<String> was = shared.get();
                List<BuilderEdit> edits = new ArrayList<>();
                for (UIElement node : nodes) {
                    List<String> before = ClassNames.authored(node.classes());
                    List<String> after = new ArrayList<>(before);
                    after.removeIf(name -> was.contains(name) && !wanted.contains(name));
                    for (String name : wanted) if (!after.contains(name)) after.add(name);
                    if (!after.equals(before)) edits.add(new BuilderEdit.SetClasses(node, before, after));
                }
                return edits;
            });
        }

        /** The first node's attribute row, written to every node as one step. */
        @SuppressWarnings("unchecked")
        private static Property<?> allOf(NodeFields.Field first, List<UIElement> nodes, Attribute<?> attribute,
                                         NodeFields fields) {
            Property<Object> read = (Property<Object>) first.value();
            return fields.bindAll("set " + attribute.name(), read::get, value -> {
                List<BuilderEdit> edits = new ArrayList<>();
                for (UIElement node : nodes) {
                    BuilderEdit edit = fields.attributeEdit(node, attribute, value);
                    if (edit != null) edits.add(edit);
                }
                return edits;
            });
        }
    }

    /**
     * <b>Forced pseudo-states</b> — Chrome's {@code :hov} panel.
     *
     * <p>A hover rule can only be seen while a pointer is on the element, which is exactly when nobody
     * can read the pane describing it. Forcing the state is how it becomes readable.</p>
     */
    private static final class ForcedStatesSection extends NodeAware {

        /** The states worth forcing. The rest are structural, and forcing one would describe a lie. */
        private static final PseudoClasses[] FORCEABLE = {
                PseudoClasses.HOVER, PseudoClasses.ACTIVE, PseudoClasses.FOCUS,
                PseudoClasses.FOCUS_VISIBLE, PseudoClasses.CHECKED, PseudoClasses.DISABLED};

        @Override
        public String tab() {
            return ELEMENT_TAB;
        }

        @Override
        public int order() {
            return 40;
        }

        @Override
        public void build(ConfigForm form, DataContext context) {
            UIElement node = node(context);
            if (node == null) return;
            form.header("Force state");
            for (PseudoClasses pseudo : FORCEABLE) {
                String name = ":" + pseudo.name().toLowerCase(Locale.ROOT).replace("_", "-");
                form.prop(ConfigDescriptor.bool("force" + pseudo.name(), name)
                                .tooltip(name)
                                .description("Styles the element as if it were " + name.substring(1)
                                        + ", so its rules can be seen. Shown only, never saved."),
                        Property.derived(() -> Boolean.TRUE.equals(node.forcedState(pseudo)),
                                on -> node.forceState(pseudo, Boolean.TRUE.equals(on) ? Boolean.TRUE : null)));
            }
        }
    }

    // ── Style ───────────────────────────────────────────────────────────────

    /** Every rule that reached this element, weakest first, with the beaten ones marked. */
    /** Every property with a value, and what it resolved to. Collapsed: it is long by design. */
    private static final class ComputedSection extends NodeAware {

        @Override
        public String tab() {
            return STYLE_TAB;
        }

        @Override
        public int order() {
            return 30;
        }

        @Override
        public void build(ConfigForm form, DataContext context) {
            UIElement node = node(context);
            if (node == null) return;
            // THE FORM IT HANDS BACK, which is the whole point of the return value: `group` writes into
            // the group's CONTENT, and rows added to `form` are siblings of the group rather than its
            // children. The group was therefore always empty -- collapsing it hid nothing and its
            // hundred-odd rows stayed on screen, so the twisty read as dead.
            ConfigForm computed = form.group("Computed", true);
            List<StyleProperty<?>> properties = new ArrayList<>(node.getStyle().candidates.keySet());
            properties.sort((a, b) -> a.name.compareTo(b.name));
            for (StyleProperty<?> property : properties) {
                live(computed, "computed." + property.name, property.name,
                        () -> String.valueOf(node.getStyle().getComputed(cast(property))));
            }
        }
    }

    // ── Layout ──────────────────────────────────────────────────────────────

    /**
     * The four box-model edges as the layout resolved them, as DevTools draws them — and in a document,
     * each editable in place.
     *
     * <p>Null-safe by construction: a node that is hidden, frozen or not laid out yet has no box at all,
     * which is an ordinary state rather than an error.</p>
     */
    private static final class BoxModelSection extends NodeAware {

        @Override
        public String tab() {
            return LAYOUT_TAB;
        }

        @Override
        public int order() {
            return 10;
        }

        @Override
        public void build(ConfigForm form, DataContext context) {
            UIElement node = node(context);
            if (node == null) return;
            form.header("Box");
            NodeFields fields = editable(context, node);
            form.custom(new BoxModelEditor(node, fields == null ? null : fields.document()));
        }
    }

    /**
     * The node's flex layout: how it lays out its children, and how its parent lays it out — every row written
     * inline in a document.
     *
     * <p>What the PARENT is doing leads the second half, because that is where a flex surprise comes from.</p>
     */
    private static final class FlexContextSection extends NodeAware {

        @Override
        public String tab() {
            return LAYOUT_TAB;
        }

        @Override
        public int order() {
            return 20;
        }

        @Override
        public void build(ConfigForm form, DataContext context) {
            UIElement node = node(context);
            if (node == null) return;
            UIElement parent = node.parentElement();
            NodeFields fields = editable(context, node);

            // AS A CONTAINER: how it lays out what it holds. Read through ComputedStyle, which is what BoxStyle
            // hands Taffy -- an unset property shows the value layout actually uses, never a null.
            form.header("Flex");
            styleRow(form, fields, node, LayoutProperties.FLEX_DIRECTION, "Direction",
                    "Whether children are laid out in a row or a column. A column here by default, unlike the web.");
            styleRow(form, fields, node, LayoutProperties.FLEX_WRAP, "Wrap",
                    "Whether children that do not fit start a new line.");
            styleRow(form, fields, node, LayoutProperties.JUSTIFY_CONTENT, "Justify",
                    "Where children sit along the direction, and how spare room is shared between them.");
            styleRow(form, fields, node, LayoutProperties.ALIGN_ITEMS, "Align items",
                    "Where children sit across the direction.");
            styleRow(form, fields, node, LayoutProperties.GAP, "Gap",
                    "The space between children, as a length: 4px, or 4px 8px for rows then columns.");

            if (parent == null) return;
            // AS A CHILD: what its parent does with it.
            form.header("In parent");
            form.prop(ConfigDescriptor.info("flex.parent", "Parent direction").tooltip("flex-direction")
                            .description("Whether the parent lays its children out in a row or a column."),
                    Property.derived(() -> NodeFields.humanize(String.valueOf(parent.getStyle().computed().get(LayoutProperties.FLEX_DIRECTION)))));
            Configurator grow = styleRow(form, fields, node, LayoutProperties.FLEX_GROW, "Grow",
                    "How much of the parent's spare room this takes, against its siblings. No effect while absolute.");
            Configurator shrink = styleRow(form, fields, node, LayoutProperties.FLEX_SHRINK, "Shrink",
                    "How much this gives up when the parent is too small. 0 here by default, unlike the web. No effect while absolute.");
            Configurator basis = styleRow(form, fields, node, LayoutProperties.FLEX_BASIS, "Basis",
                    "Its size along the parent's direction before growing or shrinking: auto, 40px or 50%. No effect while absolute.");
            // AN ABSOLUTE NODE IS NOT ONE OF THE CHILDREN ITS PARENT SHARES ROOM BETWEEN, so these three say nothing
            // while it is one. Greyed rather than hidden, so what it will do once relative stays readable. Align self
            // stays live: with no inset set, it still places the node.
            if (grow != null) {
                Configurator[] itemRows = {grow, shrink, basis};
                everyFrame(form, () -> {
                    boolean absolute = isAbsolute(node);
                    for (Configurator row : itemRows) {
                        row.control().set(Attribute.INERT, absolute);
                        if (absolute) row.addClass(INACTIVE_CLASS);
                        else row.removeClass(INACTIVE_CLASS);
                    }
                });
            }
            styleRow(form, fields, node, LayoutProperties.ALIGN_SELF, "Align self",
                    "Where it sits across the parent's direction, overriding the parent's Align items.");
            // A tenth per pixel: grow and shrink are ratios, and a unit a pixel reaches 40 in a flick.
            for (Configurator row : new Configurator[] {grow, shrink}) {
                if (row != null) row.control().descriptor().scrubRate(0.1d);
            }
        }
    }

    /**
     * Whether the node is in its parent's flow, and where it sits when it is not — {@code position} and the four
     * insets, each written inline.
     *
     * <p>An absolute node is placed against its parent and no longer takes part in the parent's layout, and the
     * canvas's move gesture works on it: dragging writes the insets.</p>
     */
    private static final class PositionSection extends NodeAware {

        @Override
        public String tab() {
            return LAYOUT_TAB;
        }

        @Override
        public int order() {
            return 20;
        }

        @Override
        public void build(ConfigForm form, DataContext context) {
            UIElement node = node(context);
            if (node == null) return;
            NodeFields fields = editable(context, node);
            form.header("Position");
            Configurator position = styleRow(form, fields, node, LayoutProperties.POSITION, "Position",
                    "Relative stays in the parent's flow. Absolute is placed against the parent by the insets below, "
                            + "takes no space in its layout, and can be dragged on the canvas.");
            Configurator[] insets = {
                    styleRow(form, fields, node, LayoutProperties.LEFT, "Left",
                            "The distance from the parent's left edge: auto, 20px or 10%. Drag the name to scrub it."),
                    styleRow(form, fields, node, LayoutProperties.TOP, "Top",
                            "The distance from the parent's top edge: auto, 20px or 10%. Drag the name to scrub it."),
                    styleRow(form, fields, node, LayoutProperties.RIGHT, "Right",
                            "The distance from the parent's right edge. With Left as well, it stretches between them."),
                    styleRow(form, fields, node, LayoutProperties.BOTTOM, "Bottom",
                            "The distance from the parent's bottom edge. With Top as well, it stretches between them.")};
            if (fields == null || position == null) return;
            StyleProperty<?>[] properties = {LayoutProperties.LEFT, LayoutProperties.TOP, LayoutProperties.RIGHT, LayoutProperties.BOTTOM};
            for (int i = 0; i < insets.length; i++) {
                StyleProperty<?> property = properties[i];
                Configurator row = insets[i];
                StyleScrub.on(row.label(), node, property, fields.document())
                        .measuring(() -> insetOf(node, property))
                        .rate(1d)
                        .signed(true)
                        .allowedWhen(() -> isAbsolute(node))
                        // THE FIELD FOLLOWS THE DRAG: it hears of the document, and the drag records only on release.
                        .onStep(() -> {
                            if (row.control() instanceof ValueControl<?> control) control.property().refresh();
                        })
                        .attach();
            }
            // THE INSETS ARE AN ABSOLUTE NODE'S, so a relative one does not list them -- followed each frame, since
            // choosing Absolute or undoing it restyles on the next one, and a rebuild would replace the dropdown just
            // clicked.
            everyFrame(form, () -> {
                boolean absolute = isAbsolute(node);
                for (Configurator inset : insets) inset.setDisplayed(absolute);
            });
        }
    }

    // ── Document ────────────────────────────────────────────────────────────

    /** Shared by the sections describing the document itself, shown when the canvas is selected. */
    private abstract static class DocumentAware implements InspectorSection {

        @Override
        public String tab() {
            return DOCUMENT_TAB;
        }

        @Override
        public boolean accepts(DataContext context) {
            return subject(context) == Subject.CANVAS && context.get(BuilderEditor.UI_DOCUMENT) != null;
        }

        @Override
        public String subjectKey(DataContext context) {
            UiBuilderDocument document = context.get(BuilderEditor.UI_DOCUMENT);
            return getClass().getSimpleName() + ":" + (document == null ? "" : System.identityHashCode(document));
        }

        @Override
        public void build(ConfigForm form, DataContext context) {
            UiBuilderDocument document = context.get(BuilderEditor.UI_DOCUMENT);
            if (document != null) build(form, document);
        }

        abstract void build(ConfigForm form, UiBuilderDocument document);
    }

    /**
     * How the document is previewed by default — its sizes, scale and theme, the header's {@code preview}.
     *
     * <p>The toolbar's choices are the viewer's and are never saved; these are what a document opens with.</p>
     */
    private static final class CanvasSection extends DocumentAware {

        @Override
        public int order() {
            return 10;
        }

        @Override
        void build(ConfigForm form, UiBuilderDocument document) {
            form.header("Canvas");
            // WHAT THE FILE SAYS, not what is on screen: the toolbar's size, scale and theme are the viewer's and
            // are never saved, and two rows naming the same three things read as one setting shown twice.
            form.note("What the document opens with, saved in the file. The toolbar changes only your view.");
            HeaderFields header = HeaderFields.on(document);
            form.prop(ConfigDescriptor.of("canvas.sizes", "Preview sizes", ConfigDescriptor.Kind.ARRAY)
                            .inlineList(true)
                            .emptyText("Default: " + String.join(", ", BuilderToolbar.defaultSizeLabels()))
                            .element(ConfigDescriptor.text("canvas.size", "").placeholder("800x480")
                                    .validator(text -> HeaderFields.parseSize(text) != null))
                            .tooltip("preview.sizes")
                            .description("Sizes to preview at, as width x height. The first is the artboard's."),
                    header.preview(() -> sizesOf(header), BuilderInspectorSections::sizesJson, "sizes"));
            form.prop(ConfigDescriptor.select("canvas.uiScale", "UI scale", List.of("1x", "2x", "3x", "4x"))
                            .tooltip("preview.uiScale")
                            .description("How many screen pixels one pixel of the design is, when the document opens."),
                    header.preview(() -> {
                        JsonElement scale = header.previewKey("uiScale");
                        return (scale != null && scale.isJsonPrimitive() ? Math.round(scale.getAsFloat()) : 1) + "x";
                    }, chosen -> {
                        int scale = chosen == null ? 1 : Integer.parseInt(chosen.replace("x", "").trim());
                        return scale <= 1 ? null : new JsonPrimitive(scale);
                    }, "uiScale"));
            List<String> themes = new ArrayList<>();
            themes.add(WORKBENCH_THEME);
            for (UiTheme theme : ThemeRegistry.themes()) themes.add(theme.id());
            form.prop(ConfigDescriptor.select("canvas.theme", "Theme", themes)
                            .tooltip("preview.theme")
                            .description("The theme the document opens in. Workbench theme keeps whichever the workbench has."),
                    header.preview(() -> {
                        JsonElement theme = header.previewKey("theme");
                        return theme != null && theme.isJsonPrimitive() ? theme.getAsString() : WORKBENCH_THEME;
                    }, chosen -> chosen == null || chosen.equals(WORKBENCH_THEME) ? null : new JsonPrimitive(chosen), "theme"));
        }

        /** The theme choice that writes no theme: the document opens in whichever the workbench has. */
        private static final String WORKBENCH_THEME = "Workbench theme";

        private static List<Object> sizesOf(HeaderFields header) {
            List<Object> out = new ArrayList<>();
            JsonElement sizes = header.previewKey("sizes");
            if (sizes == null || !sizes.isJsonArray()) return out;
            for (JsonElement each : sizes.getAsJsonArray()) {
                if (!each.isJsonArray() || each.getAsJsonArray().size() < 2) continue;
                JsonArray pair = each.getAsJsonArray();
                out.add(Math.round(pair.get(0).getAsFloat()) + "x" + Math.round(pair.get(1).getAsFloat()));
            }
            return out;
        }
    }

    /** The sheets the document names, in cascade order — the header's {@code stylesheets}. */
    private static final class DocumentSheetsSection extends DocumentAware {

        @Override
        public int order() {
            return 20;
        }

        @Override
        void build(ConfigForm form, UiBuilderDocument document) {
            form.header("Stylesheets");
            form.prop(ConfigDescriptor.of("document.sheets", "Sheets", ConfigDescriptor.Kind.ARRAY)
                            .inlineList(true)
                            .emptyText("None: drawn with the workbench's sheets")
                            .element(ConfigDescriptor.text("document.sheet", "").placeholder("menu.css"))
                            .tooltip("stylesheets")
                            .description("The stylesheets this document is drawn with. A file beside it (menu.css) or from the "
                                    + "project root (/ui/menu.css) is editable here; a shipped one (mymod:ui/status) is read-only. "
                                    + "A later sheet wins over an earlier one."),
                    HeaderFields.on(document).strings("stylesheets"));
        }
    }

    /**
     * What the Java exporter reads — the model class, the package, and the kind name a template registers
     * under. <i>Export…</i> itself is L8.2.
     */
    private static final class ExportSection extends DocumentAware {

        @Override
        public int order() {
            return 30;
        }

        @Override
        void build(ConfigForm form, UiBuilderDocument document) {
            form.header("Export");
            headerText(form, document, "model", "Model class", "com.example.StatusModel",
                    "The class a networked export binds its fields to.");
            headerText(form, document, "package", "Package", "com.example.ui",
                    "The Java package the generated class goes in.");
            headerText(form, document, "kind-name", "Kind name", "mymod:status_page",
                    "A namespace:name tag, so other documents can place this one by it.");
        }

        private static void headerText(ConfigForm form, UiBuilderDocument document, String key, String label, String example,
                                       String description) {
            form.prop(ConfigDescriptor.text("export." + key, label).placeholder(example).tooltip(key).description(description),
                    HeaderFields.on(document).text(key));
        }
    }

    // ── Shared ──────────────────────────────────────────────────────────────

    /**
     * A fact that follows what it describes.
     *
     * <p>These change without the subject changing — dragging a resize handle rewrites the box sixty times a
     * second and never touches which node is selected, and the panel rebuilds only on a subject change. So
     * each is a polled {@link Property}: nothing announces a box, and a bound fact re-reads after every
     * layout for as long as it is on screen.</p>
     */
    private static void live(ConfigForm form, String id, String label, Supplier<String> value) {
        form.prop(ConfigDescriptor.info(id, label), Property.derived(value));
    }

    @SuppressWarnings("unchecked")
    private static Configurator prop(ConfigForm form, ConfigDescriptor descriptor, Property<?> value) {
        return form.prop(descriptor, (Property<Object>) value);
    }

    private static Configurator attribute(ConfigForm form, NodeFields.Field field) {
        return prop(form, field.descriptor(), field.value());
    }

    private static void attributeRow(ConfigForm form, NodeFields fields, UIElement node, Attribute<?> attribute) {
        Configurator row = attribute(form, fields.attribute(node, attribute, labelOf(attribute)));
        UIElement pristine = pristineOf(node);
        markSet(row, () -> !Objects.equals(node.get(attribute), pristine == null ? attribute.initial() : pristine.get(attribute)));
    }

    private static String labelOf(Attribute<?> attribute) {
        return SHORT_LABELS.getOrDefault(attribute.name(), NodeFields.humanize(attribute.name()));
    }

    /** One untouched instance per kind, what "set" is measured against. */
    private static final Map<Name, UIElement> PRISTINE = new HashMap<>();

    /**
     * A fresh node of {@code node}'s kind, or null when the kind cannot be built.
     *
     * <p>"Set" means different from what this KIND starts with, not from the attribute's global initial: a
     * Button takes click focus in its own constructor, and marking that row would mark every button.</p>
     */
    @Nullable
    private static UIElement pristineOf(UIElement node) {
        Name kind = node.name();
        if (!UIElementRegistry.isBuildable(kind)) return null;
        return PRISTINE.computeIfAbsent(kind, UIElementRegistry::create);
    }

    /**
     * One style row: a control over the property's inline value in a document, marked while set on this node, or
     * the computed value as a fact over a live pick. Null for the fact.
     */
    @Nullable
    private static Configurator styleRow(ConfigForm form, @Nullable NodeFields fields, UIElement node,
                                        StyleProperty<?> property, String label, String description) {
        if (fields == null) {
            form.prop(ConfigDescriptor.info("style." + property.name, label).tooltip(property.name).description(description),
                    Property.derived(() -> String.valueOf(node.getStyle().computed().get(cast(property)))));
            return null;
        }
        NodeFields.Field field = fields.style(node, property, label);
        field.descriptor().description(description);
        Configurator row = prop(form, field.descriptor(), field.value());
        markSet(row, () -> LiveEdits.hasInline(node, property));
        return row;
    }

    private static boolean isAbsolute(UIElement node) {
        return node.getStyle().computed().get(LayoutProperties.POSITION) == TaffyPosition.ABSOLUTE;
    }

    /**
     * Where {@code node} sits from its parent's edge on {@code inset}'s side: the length its style gives, else what
     * layout placed it at. NaN with nothing laid out.
     */
    private static double insetOf(UIElement node, StyleProperty<?> inset) {
        Object declared = node.getStyle().computed().get(inset);
        if (declared instanceof LengthPercentageAuto length && length.getType() == LengthPercentageAuto.Type.LENGTH) {
            return length.getValue();
        }
        Box box = node.box();
        UIElement parent = node.parentElement();
        Box host = parent == null ? null : parent.box();
        if (box == null || host == null) return Double.NaN;
        if (inset == LayoutProperties.LEFT) return box.x() - host.border().left;
        if (inset == LayoutProperties.TOP) return box.y() - host.border().top;
        if (inset == LayoutProperties.RIGHT) return host.width() - host.border().right - box.x() - box.width();
        return host.height() - host.border().bottom - box.y() - box.height();
    }

    /**
     * Runs {@code follow} now and after every layout for as long as the form is on screen — for rows that follow a fact
     * the form does not rebuild on, such as the node's position changing under them.
     */
    private static void everyFrame(ConfigForm form, Runnable follow) {
        follow.run();
        form.custom(new FrameFollower(follow));
    }

    /**
     * An invisible part of a form that owns a per-frame hook: placed with the rows, cleared with them, and the hook
     * stops when it leaves the tree. A section cannot hang a hook on a row it did not build the class of.
     */
    private static final class FrameFollower extends UIElement {

        FrameFollower(Runnable follow) {
            setDisplayed(false);
            onConnected(() -> {
                UIDocument window = document();
                if (window != null) window.animation().afterLayout(this, delta -> {
                    follow.run();
                    return true;
                });
            });
        }
    }

    /** Marks a row while {@code isSet} holds, following the value as it changes. */
    private static void markSet(Configurator row, Supplier<Boolean> isSet) {
        Runnable follow = () -> {
            if (isSet.get()) row.addClass(SET_CLASS);
            else row.removeClass(SET_CLASS);
        };
        follow.run();
        if (row.control() instanceof ValueControl<?> control) {
            control.property().changed.connect((was, now) -> follow.run());
        }
    }

    /** The attributes a person edits: every carried one. */
    private static List<Attribute<?>> editableAttributes() {
        List<Attribute<?>> out = new ArrayList<>();
        for (Attribute<?> attribute : Attribute.declared()) {
            if (attribute.isCarried()) out.add(attribute);
        }
        return out;
    }

    /** Chips over {@code value}, completing from the classes the window's sheets mention. */
    @SuppressWarnings("unchecked")
    private static ClassChips classChips(String id, UIElement node, Property<List<String>> value) {
        ClassChips chips = new ClassChips(ConfigDescriptor.of(id, "Classes", ConfigDescriptor.Kind.ARRAY)
                .tooltip("class")
                .description("The names .class selectors match. An outlined chip is a class no sheet mentions."), value.get());
        chips.setAccepts(name -> !ClassNames.isEngine(name) && name.matches("[\\w-]+"));
        // ONCE PER FORM: every rule of every installed sheet is walked, and the flag is asked per chip per redraw.
        // The sheets do not change while a form is on screen; a rebuild asks again.
        Set<String>[] known = new Set[1];
        Supplier<Set<String>> names = () -> known[0] != null ? known[0] : (known[0] = sheetClassNames(node));
        chips.setSuggestions(names);
        chips.setFlagged(name -> !names.get().isEmpty() && !names.get().contains(name));
        chips.bind(value);
        return chips;
    }

    /**
     * Every class a sheet installed on {@code node}'s window mentions in a selector, the engine's left out.
     * Empty when the node is in no window, which flags nothing.
     */
    public static Set<String> sheetClassNames(UIElement node) {
        Set<String> names = new LinkedHashSet<>();
        UIDocument window = node.document();
        if (window == null) return names;
        for (StyleSheet sheet : window.styles().getSheets()) {
            for (StyleRule rule : sheet.getRules()) {
                for (CompoundSelector compound : rule.selector().compounds()) {
                    for (CompoundSelector.Part part : compound.parts()) {
                        if (part.type() == SelectorType.CLASS && !ClassNames.isEngine(part.identity())) {
                            names.add(part.identity());
                        }
                    }
                }
            }
        }
        return names;
    }

    /** Sizes as the array control holds them, as the header's {@code [[w, h], ...]}; unreadable ones dropped. */
    @Nullable
    private static JsonElement sizesJson(List<Object> wanted) {
        JsonArray sizes = new JsonArray();
        for (Object each : wanted) {
            float[] size = HeaderFields.parseSize(String.valueOf(each));
            if (size == null) continue;
            JsonArray pair = new JsonArray();
            pair.add(new JsonPrimitive(size[0]));
            pair.add(new JsonPrimitive(size[1]));
            sizes.add(pair);
        }
        return sizes.size() == 0 ? null : sizes;
    }

    @SuppressWarnings("unchecked")
    private static StyleProperty<Object> cast(StyleProperty<?> property) {
        return (StyleProperty<Object>) property;
    }

    /** The primary first, then the rest as declared. Package-visible so the order is asserted
     * directly rather than inferred from a rendered form. */
    static List<State<Object, ?>> ordered(WidgetContract<Object> contract) {
        List<State<Object, ?>> declared = contract.states();
        State<Object, ?> primary = contract.primary();
        if (primary == null || declared.isEmpty() || declared.get(0) == primary) return declared;
        List<State<Object, ?>> out = new ArrayList<>(declared.size());
        out.add(primary);
        for (State<Object, ?> state : declared) {
            if (state != primary) out.add(state);
        }
        return out;
    }

    /** The contract for a widget's kind, or null when it declares none. */
    @Nullable
    private static WidgetContract<Object> contractOf(@Nullable UIElement node) {
        if (node == null) return null;
        try {
            return WidgetContracts.of(node);
        } catch (RuntimeException none) {
            return null;
        }
    }
}
