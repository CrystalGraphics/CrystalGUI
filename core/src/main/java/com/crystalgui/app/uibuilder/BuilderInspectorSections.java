package com.crystalgui.app.uibuilder;

import java.util.List;
import dev.vfyjxf.taffy.geometry.FloatRect;
import com.crystalgui.ui.box.Box;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.ComputedStyle;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.style.PseudoClasses;
import com.crystalgui.app.uibuilder.inspect.MatchedRules;
import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import java.util.Locale;
import java.util.ArrayList;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.canvas.BuilderEditor;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.data.DataContext;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.template.TemplateInstance;
import com.crystalgui.ui.contract.State;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.WidgetContracts;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.inspector.InspectorForm;
import com.crystalgui.widget.config.inspector.InspectorSection;
import com.crystalgui.widget.surface.extension.SectionSet;

/**
 * What the inspector shows for a UI document — the Element tab.
 *
 * <p>Shaped on {@code ShaderInspectorSections} and keeping its three rules. <b>Instances, not
 * factories</b>: a section reads its subject out of the context and holds nothing, so one instance serves
 * every document in every window. A <b>counted</b> {@link #register()}: a second editor must not double
 * the forms, and the first one closing must not empty the inspector under the second. And <b>one
 * {@link #subject} method</b> that decides exclusivity, because sections within a tab are additive by the
 * engine's design — the graph learned that when four sections were four answers to one question and a
 * marquee that caught a wire rendered two stacked panels.</p>
 *
 * <p>READ ONLY at L3.6. Every row here states what is; the editing half is L4.9.</p>
 */
public final class BuilderInspectorSections {

    private BuilderInspectorSections() {
    }

    public static final String ELEMENT_TAB = "Element";

    public static final String STYLE_TAB = "Style";

    public static final String LAYOUT_TAB = "Layout";

    /** What the current selection IS, decided once so no two sections can both claim it. */
    private enum Subject {
        NONE, CANVAS, NODE, MULTI, INSTANCE
    }

    private static final SectionSet SECTIONS = SectionSet.of(
            new NodeSection(), new AttributesSection(), new StateSection(), new ForcedStatesSection(),
            new MatchedRulesSection(), new InlineStyleSection(), new ComputedSection(),
            new BoxModelSection(), new FlexContextSection());

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

    /** Describes ONE node: what kind it is, and how it is identified. */
    private static final class NodeSection implements InspectorSection {

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
            Subject subject = subject(context);
            return subject == Subject.NODE || subject == Subject.INSTANCE;
        }

        @Override
        public String subjectKey(DataContext context) {
            UIElement node = node(context);
            return "uibuilder.node:" + (node == null ? "" : System.identityHashCode(node));
        }

        @Override
        public void build(InspectorForm form, DataContext context) {
            UIElement node = node(context);
            if (node == null) return;
            form.header(node.tagName());
            form.row(ConfigDescriptor.info("id", "id"), node.getId() == null ? "" : node.getId());
            form.row(ConfigDescriptor.info("classes", "classes"), String.join(" ", node.classes()));
        }
    }

    /** Every attribute the node actually carries — the ones it was given, not every one it could have. */
    private static final class AttributesSection implements InspectorSection {

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
            Subject subject = subject(context);
            if (subject != Subject.NODE && subject != Subject.INSTANCE) return false;
            UIElement node = node(context);
            return node != null && !node.setAttributes().isEmpty();
        }

        @Override
        public String subjectKey(DataContext context) {
            UIElement node = node(context);
            return "uibuilder.attributes:" + (node == null ? "" : System.identityHashCode(node));
        }

        @Override
        public void build(InspectorForm form, DataContext context) {
            UIElement node = node(context);
            if (node == null) return;
            form.header("Attributes");
            for (Attribute<?> attribute : node.setAttributes()) {
                form.row(ConfigDescriptor.info("attr." + attribute.name(), attribute.name()),
                        String.valueOf(node.get(attribute)));
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
            return 30;
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
        public void build(InspectorForm form, DataContext context) {
            UIElement node = node(context);
            WidgetContract<Object> contract = contractOf(node);
            if (node == null || contract == null) return;
            form.header("State");
            for (State<Object, ?> state : ordered(contract)) {
                form.row(ConfigDescriptor.info("state." + state.key(), state.key()),
                        String.valueOf(state.read(node)));
            }
        }
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
        public void build(InspectorForm form, DataContext context) {
            UIElement node = node(context);
            if (node == null) return;
            form.header("Force state");
            for (PseudoClasses pseudo : FORCEABLE) {
                Boolean forced = node.forcedState(pseudo);
                String name = ":" + pseudo.name().toLowerCase(Locale.ROOT).replace("_", "-");
                form.row(ConfigDescriptor.bool("force" + pseudo.name(), name),
                                Boolean.TRUE.equals(forced))
                        .control().changed.connect(value ->
                                node.forceState(pseudo, Boolean.TRUE.equals(value) ? Boolean.TRUE : null));
            }
        }
    }

    /** Every rule that reached this element, weakest first, with the beaten ones marked. */
    private static final class MatchedRulesSection extends NodeAware {

        @Override
        public String tab() {
            return STYLE_TAB;
        }

        @Override
        public int order() {
            return 10;
        }

        @Override
        public void build(InspectorForm form, DataContext context) {
            UIElement node = node(context);
            if (node == null) return;
            for (MatchedRules.Rule rule : MatchedRules.of(node)) {
                form.header(rule.origin().name().toLowerCase(Locale.ROOT)
                        + (rule.sheetIndex() < 0 ? ""
                                : "  sheet " + rule.sheetIndex() + " rule " + rule.ruleOrder()));
                for (MatchedRules.Declaration declaration : rule.declarations()) {
                    // OVERRIDDEN, not hidden: that a declaration matched and lost is the fact this pane
                    // exists to show. The strikethrough is the theme's; this says which rows get it.
                    String label = declaration.won()
                            ? declaration.property().name
                            : declaration.property().name + "  (overridden)";
                    form.row(ConfigDescriptor.info("matched." + rule.origin() + "." + rule.ruleOrder()
                                    + "." + declaration.property().name, label),
                            String.valueOf(declaration.value()));
                }
            }
        }
    }

    /**
     * What has been set inline on this element, editable — and gone at the next launch.
     *
     * <p>Unity's caveat, stated where it applies: a live pick has no document behind it, so an edit here
     * changes the running screen and nothing else.</p>
     */
    private static final class InlineStyleSection extends NodeAware {

        @Override
        public String tab() {
            return STYLE_TAB;
        }

        @Override
        public int order() {
            return 20;
        }

        @Override
        public void build(InspectorForm form, DataContext context) {
            UIElement node = node(context);
            if (node == null) return;
            List<StyleProperty<?>> inline = new ArrayList<>();
            for (StyleProperty<?> property : node.getStyle().candidates.keySet()) {
                if (LiveEdits.hasInline(node, property)) inline.add(property);
            }
            if (inline.isEmpty()) return;

            form.header("Inline (this session only)");
            for (StyleProperty<?> property : inline) {
                form.row(ConfigDescriptor.text("inline." + property.name, property.name),
                                String.valueOf(node.getStyle().getComputed(cast(property))))
                        .control().changed.connect(value ->
                                LiveEdits.setInline(node, cast(property), String.valueOf(value)));
            }
        }
    }

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
        public void build(InspectorForm form, DataContext context) {
            UIElement node = node(context);
            if (node == null) return;
            form.group("Computed", true);
            List<StyleProperty<?>> properties = new ArrayList<>(node.getStyle().candidates.keySet());
            properties.sort((a, b) -> a.name.compareTo(b.name));
            for (StyleProperty<?> property : properties) {
                form.row(ConfigDescriptor.info("computed." + property.name, property.name),
                        String.valueOf(node.getStyle().getComputed(cast(property))));
            }
        }
    }

    /**
     * The four box-model edges, as the layout RESOLVED them.
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
        public void build(InspectorForm form, DataContext context) {
            UIElement node = node(context);
            Box box = node == null ? null : node.box();
            if (box == null) {
                form.row(ConfigDescriptor.info("box.none", "box"), "not laid out");
                return;
            }
            form.header("Box");
            form.row(ConfigDescriptor.info("box.size", "size"),
                    round(box.width()) + " x " + round(box.height()));
            form.row(ConfigDescriptor.info("box.margin", "margin"), edges(box.margin()));
            form.row(ConfigDescriptor.info("box.border", "border"), edges(box.border()));
            form.row(ConfigDescriptor.info("box.padding", "padding"), edges(box.padding()));
            // THE CONTENT BOX, not contentWidth(): those are different questions and this panel is
            // asking the box model's. contentWidth() is the extent of what is INSIDE, which for a leaf
            // that draws its own glyphs is zero -- so a text node reported "0.0 x 0.0" for a row every
            // reader takes to mean the box its text is laid out in.
            form.row(ConfigDescriptor.info("box.content", "content"),
                    round(box.contentBoxWidth()) + " x " + round(box.contentBoxHeight()));
        }
    }

    /** What the PARENT is doing to this node, which is where a flex surprise always comes from. */
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
        public void build(InspectorForm form, DataContext context) {
            UIElement node = node(context);
            if (node == null) return;
            UIElement parent = node.parentElement();
            form.header("Flex");
            if (parent == null) {
                form.row(ConfigDescriptor.info("flex.parent", "parent"), "none");
                return;
            }
            // THROUGH ComputedStyle, which is what BoxStyle hands Taffy. getComputed answers the
            // cascade SLOT and is null when no rule declared the property -- true, and not the question:
            // every one of these has an initial the layout actually uses, so three untouched defaults
            // were reported as three nulls.
            ComputedStyle parentStyle = parent.getStyle().computed();
            ComputedStyle own = node.getStyle().computed();
            form.row(ConfigDescriptor.info("flex.direction", "parent direction"),
                    String.valueOf(parentStyle.get(LayoutProperties.FLEX_DIRECTION)));
            form.row(ConfigDescriptor.info("flex.grow", "grow"),
                    String.valueOf(own.get(LayoutProperties.FLEX_GROW)));
            form.row(ConfigDescriptor.info("flex.shrink", "shrink"),
                    String.valueOf(own.get(LayoutProperties.FLEX_SHRINK)));
        }
    }

    private static String edges(FloatRect rect) {
        return round(rect.top) + " " + round(rect.right) + " "
                + round(rect.bottom) + " " + round(rect.left);
    }

    private static String round(float value) {
        return String.valueOf(Math.round(value * 100f) / 100f);
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
        List<State<Object, ?>> out = new java.util.ArrayList<>(declared.size());
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
