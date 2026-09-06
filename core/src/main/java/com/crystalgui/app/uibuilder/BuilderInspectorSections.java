package com.crystalgui.app.uibuilder;

import java.util.List;

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

    /** What the current selection IS, decided once so no two sections can both claim it. */
    private enum Subject {
        NONE, CANVAS, NODE, MULTI, INSTANCE
    }

    private static final SectionSet SECTIONS =
            SectionSet.of(new NodeSection(), new AttributesSection(), new StateSection());

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
