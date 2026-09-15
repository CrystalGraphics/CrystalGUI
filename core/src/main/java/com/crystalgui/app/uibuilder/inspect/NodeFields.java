package com.crystalgui.app.uibuilder.inspect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.crystalgui.app.uibuilder.canvas.BuilderEditor;
import com.crystalgui.app.uibuilder.document.BuilderEdit;
import com.crystalgui.app.uibuilder.document.NodeIds;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.data.DataContext;
import com.crystalgui.core.property.Property;
import com.crystalgui.serialization.JsonOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.contract.State;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.WidgetContracts;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.ClassNames;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.input.DragScrub;

/**
 * What an inspector section binds a control to when it edits a node of an open {@code .cgui}: a
 * {@link Property} that reads the node and writes a {@link BuilderEdit} into the document.
 *
 * <pre>{@code
 * NodeFields fields = NodeFields.of(context);   // null over a live pick: there is no document to write
 * if (fields != null && fields.owns(node)) {
 *     form.prop(ConfigDescriptor.text("id", "id").validator(id -> fields.idProblem(node, id) == null),
 *             fields.id(node));
 *     Field hitTest = fields.attribute(node, Attribute.HIT_TEST);
 *     form.prop(hitTest.descriptor(), hitTest.value());
 * }
 * }</pre>
 *
 * <p>Every property it hands out is recorded in the document's history, so a scrub is one undo step, and
 * follows the document's announcements, so an undo refreshes the control without rebuilding the form.</p>
 *
 * <ul>
 *   <li>Ask {@link #owns} first: a live pick can select a node of another window while a document is open.</li>
 *   <li>A value the control offers in its own type (a {@code Double} from a number field) is converted to the
 *       slot's; one that cannot be is not written.</li>
 * </ul>
 */
public final class NodeFields {

    /** A control's descriptor and the value it edits, as one answer. */
    public record Field(ConfigDescriptor descriptor, Property<?> value) {
    }

    private final UiBuilderDocument document;

    private NodeFields(UiBuilderDocument document) {
        this.document = document;
    }

    /** The fields of the document {@code context} is editing, or null when it is editing none. */
    @Nullable
    public static NodeFields of(DataContext context) {
        UiBuilderDocument document = context.get(BuilderEditor.UI_DOCUMENT);
        return document == null ? null : new NodeFields(document);
    }

    /** For a caller that already holds the document. */
    public static NodeFields on(UiBuilderDocument document) {
        return new NodeFields(Objects.requireNonNull(document, "document"));
    }

    public UiBuilderDocument document() {
        return document;
    }

    /** Whether {@code node} is in this document's tree — the root included. */
    public boolean owns(@Nullable UIElement node) {
        for (UINode at = node; at != null; at = at.parent()) {
            if (at == document.root()) return true;
        }
        return false;
    }

    /**
     * A property over the document: {@code read} for its value, {@code edit} for the change a new value is,
     * or null when it is no change.
     */
    public <V> Property<V> bind(Supplier<V> read, Function<V, BuilderEdit> edit) {
        return Property.derived(read, value -> {
                    BuilderEdit change = edit.apply(value);
                    if (change != null) document.apply(change);
                })
                .editedIn(document.history())
                .announcedBy(refresh -> document.onChanged().connect(refresh::run));
    }

    /**
     * As {@link #bind}, for a change that is several edits — a value written to every selected node. The edits
     * are one undo step, named {@code label}; an empty list is no change.
     */
    public <V> Property<V> bindAll(String label, Supplier<V> read, Function<V, List<BuilderEdit>> edits) {
        return Property.derived(read, value -> document.applyAll(label, edits.apply(value)))
                .editedIn(document.history())
                .announcedBy(refresh -> document.onChanged().connect(refresh::run));
    }

    // ── Identity ────────────────────────────────────────────────────────────

    public Property<String> id(UIElement node) {
        return bind(node::id, id -> id.equals(node.id()) || idProblem(node, id) != null
                ? null : new BuilderEdit.SetId(node, node.id(), id));
    }

    /** Why {@code id} cannot be {@code node}'s, or null when it can. */
    @Nullable
    public String idProblem(UIElement node, String id) {
        if (!NodeIds.isSpellable(id)) return "An id is letters, digits, - and _";
        if (!id.isEmpty() && !id.equals(node.id()) && NodeIds.taken(document.root()).contains(id)) {
            return "Another element is already #" + id;
        }
        return null;
    }

    /** The authored classes; setting replaces them and leaves the engine's alone. */
    public Property<List<String>> classes(UIElement node) {
        return bind(() -> ClassNames.authored(node.classes()), wanted -> {
            List<String> was = ClassNames.authored(node.classes());
            List<String> now = ClassNames.authored(wanted);
            return was.equals(now) ? null : new BuilderEdit.SetClasses(node, was, now);
        });
    }

    // ── Attributes ──────────────────────────────────────────────────────────

    /** A control for one attribute, chosen by its type, labelled with its name made readable. */
    public Field attribute(UIElement node, Attribute<?> attribute) {
        return attribute(node, attribute, humanize(attribute.name()));
    }

    /** As {@link #attribute(UIElement, Attribute)}, under {@code label}; the attribute's own name is the tooltip. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Field attribute(UIElement node, Attribute<?> attribute, String label) {
        Attribute<Object> key = (Attribute<Object>) attribute;
        Class<?> type = attribute.type();
        String id = "attr." + attribute.name();
        ConfigDescriptor descriptor = descriptorFor(id, label, type, null)
                .tooltip(attribute.name())
                .description(attribute.description());
        if (descriptor.kind() == ConfigDescriptor.Kind.INFO) {
            return new Field(descriptor, Property.derived(() -> key.write(node.get(key))));
        }
        Property<Object> value = bind(() -> toControl(node.get(key), type, null),
                offered -> attributeEdit(node, attribute, offered));
        return new Field(descriptor, value);
    }

    /**
     * The edit that sets {@code attribute} on {@code node} to what a control offered, or null when the value
     * is no change or not one of the attribute's type — for a caller writing several nodes as one step.
     */
    @Nullable
    @SuppressWarnings({"unchecked", "rawtypes"})
    public BuilderEdit attributeEdit(UIElement node, Attribute<?> attribute, @Nullable Object offered) {
        Attribute<Object> key = (Attribute<Object>) attribute;
        Object next = fromControl(offered, attribute.type());
        Object was = node.get(key);
        return next == null || Objects.equals(next, was) ? null : new BuilderEdit.SetAttribute(node, key, was, next);
    }

    // ── State ───────────────────────────────────────────────────────────────

    /** A control for one state slot, chosen by its type and hint; a slot with no control is a read-only row. */
    @SuppressWarnings("unchecked")
    public Field state(UIElement node, State<Object, ?> slot) {
        State<Object, Object> state = (State<Object, Object>) slot;
        Class<?> type = state.type().valueClass();
        State.Hint hint = state.hint();
        String label = hint != null && hint.label() != null ? hint.label() : humanize(state.key());
        ConfigDescriptor descriptor = (type == null
                ? ConfigDescriptor.info("state." + state.key(), label)
                : descriptorFor("state." + state.key(), label, type, hint))
                .tooltip(state.key())
                .description(hint == null ? null : hint.description());
        if (hint != null && hint.spanMin() != null && hint.spanMax() != null) {
            State<Object, ?> low = slot(node, hint.spanMin());
            State<Object, ?> high = slot(node, hint.spanMax());
            if (low != null && high != null) {
                // A HUNDREDTH OF THE SPAN, asked per drag so it follows the bounds as they are edited.
                descriptor.scrubRate(() -> {
                    Object min = low.read(node);
                    Object max = high.read(node);
                    return min instanceof Number a && max instanceof Number b
                            ? Math.abs(b.doubleValue() - a.doubleValue()) * DragScrub.RANGE_FRACTION : Double.NaN;
                });
            }
        }
        if (descriptor.kind() == ConfigDescriptor.Kind.INFO) {
            return new Field(descriptor, Property.derived(() -> {
                JsonElement encoded = encode(state, state.read(node));
                return encoded == null ? "" : encoded.toString();
            }));
        }
        Property<Object> value = bind(() -> toControl(state.read(node), type, hint), offered -> {
            Object next = fromControl(offered, type);
            if (next == null) return null;
            JsonElement from = encode(state, state.read(node));
            JsonElement to = encode(state, next);
            return Objects.equals(from, to) ? null : new BuilderEdit.SetState(node, state.key(), from, to);
        });
        return new Field(descriptor, value);
    }

    /** The slot of {@code node}'s contract under {@code key}, or null. */
    @Nullable
    private static State<Object, ?> slot(UIElement node, String key) {
        WidgetContract<Object> contract;
        try {
            contract = WidgetContracts.of(node);
        } catch (RuntimeException none) {
            return null;
        }
        if (contract == null) return null;
        for (State<Object, ?> each : contract.states()) {
            if (each.key().equals(key)) return each;
        }
        return null;
    }

    /** A value in its wire form, as {@code SetState} carries it. */
    @Nullable
    private static JsonElement encode(State<Object, Object> state, Object value) {
        StateMap<JsonElement> out = new StateMap<>(JsonOps.INSTANCE);
        state.type().put(out, state.key(), value);
        JsonElement encoded = out.encode();
        return encoded instanceof JsonObject object ? object.get(state.key()) : null;
    }

    // ── Choosing a control ──────────────────────────────────────────────────

    /**
     * The descriptor for a value of {@code type}: a checkbox, a number (a slider with a range), a colour, a
     * dropdown over an enum, text, or a list. {@code INFO} for a type no control edits.
     */
    public static ConfigDescriptor descriptorFor(String id, String label, Class<?> type, @Nullable State.Hint hint) {
        boolean color = hint != null && hint.color();
        if (type == Boolean.class) return ConfigDescriptor.bool(id, label);
        if (type == Integer.class && color) return ConfigDescriptor.color(id, label);
        if (type == Integer.class || type == Float.class || type == Double.class) {
            ConfigDescriptor number = ConfigDescriptor.number(id, label).integral(type == Integer.class);
            return hint != null && hint.hasRange() ? number.range(hint.min(), hint.max()) : number;
        }
        if (type.isEnum()) {
            List<String> names = new ArrayList<>();
            for (Object constant : type.getEnumConstants()) names.add(labelOf((Enum<?>) constant));
            return ConfigDescriptor.select(id, label, names);
        }
        if (type == String.class) {
            return hint != null && hint.asset() != null ? ConfigDescriptor.asset(id, label) : ConfigDescriptor.text(id, label);
        }
        if (type == List.class) return ConfigDescriptor.of(id, label, ConfigDescriptor.Kind.ARRAY)
                .element(ConfigDescriptor.text(id + ".entry", ""));
        if (type == int[].class) return ConfigDescriptor.of(id, label, ConfigDescriptor.Kind.ARRAY)
                .element(color ? ConfigDescriptor.color(id + ".entry", "") : ConfigDescriptor.number(id + ".entry", "").integral(true));
        if (type == float[].class || type == double[].class) return ConfigDescriptor.of(id, label, ConfigDescriptor.Kind.ARRAY)
                .element(ConfigDescriptor.number(id + ".entry", ""));
        return ConfigDescriptor.info(id, label);
    }

    /** A value as the control for its type holds it. */
    @Nullable
    private static Object toControl(@Nullable Object value, Class<?> type, @Nullable State.Hint hint) {
        if (value == null) return null;
        if (type == Float.class || type == Double.class || (type == Integer.class && (hint == null || !hint.color()))) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof Enum<?> constant) return labelOf(constant);
        if (value instanceof int[] ints) {
            List<Object> out = new ArrayList<>(ints.length);
            boolean color = hint != null && hint.color();
            for (int each : ints) out.add(color ? (Object) each : (Object) (double) each);
            return out;
        }
        if (value instanceof float[] floats) {
            List<Object> out = new ArrayList<>(floats.length);
            for (float each : floats) out.add((double) each);
            return out;
        }
        if (value instanceof double[] doubles) {
            List<Object> out = new ArrayList<>(doubles.length);
            for (double each : doubles) out.add(each);
            return out;
        }
        if (value instanceof List<?> list) return new ArrayList<Object>(list);
        return value;
    }

    /** A control's value as {@code type}, or null when it is not one. */
    @Nullable
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object fromControl(@Nullable Object offered, Class<?> type) {
        if (offered == null) return null;
        try {
            if (type == Boolean.class) return offered instanceof Boolean ? offered : null;
            if (type == Integer.class) return offered instanceof Number n ? (Object) (int) Math.round(n.doubleValue()) : null;
            if (type == Float.class) return offered instanceof Number n ? (Object) n.floatValue() : null;
            if (type == Double.class) return offered instanceof Number n ? (Object) n.doubleValue() : null;
            if (type == String.class) return String.valueOf(offered);
            if (type.isEnum()) {
                for (Object constant : type.getEnumConstants()) {
                    String name = ((Enum<?>) constant).name();
                    if (name.equals(offered) || labelOf((Enum<?>) constant).equals(offered)) return constant;
                }
                return null;
            }
            if (!(offered instanceof List<?> list)) return null;
            if (type == List.class) {
                List<String> out = new ArrayList<>(list.size());
                for (Object each : list) out.add(each == null ? "" : String.valueOf(each));
                return out;
            }
            if (type == int[].class) {
                int[] out = new int[list.size()];
                for (int i = 0; i < out.length; i++) out[i] = number(list.get(i)).intValue();
                return out;
            }
            if (type == float[].class) {
                float[] out = new float[list.size()];
                for (int i = 0; i < out.length; i++) out[i] = number(list.get(i)).floatValue();
                return out;
            }
            if (type == double[].class) {
                double[] out = new double[list.size()];
                for (int i = 0; i < out.length; i++) out[i] = number(list.get(i)).doubleValue();
                return out;
            }
        } catch (RuntimeException unparsable) {
            return null;
        }
        return null;
    }

    /**
     * A key or constant as a label: {@code hit-test}, {@code updateMode} and {@code ON_COMMIT} read
     * {@code Hit test}, {@code Update mode} and {@code On commit}.
     */
    public static String humanize(String key) {
        StringBuilder out = new StringBuilder(key.length() + 4);
        boolean shout = key.equals(key.toUpperCase(Locale.ROOT));
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '-' || c == '_') {
                if (out.length() > 0 && out.charAt(out.length() - 1) != ' ') out.append(' ');
                continue;
            }
            if (!shout && Character.isUpperCase(c) && out.length() > 0 && out.charAt(out.length() - 1) != ' ') {
                out.append(' ');
            }
            out.append(out.length() == 0 ? Character.toUpperCase(c) : Character.toLowerCase(c));
        }
        return out.toString();
    }

    /** An enum constant as a dropdown shows it: its own {@code toString} when the enum declares one, else its name made readable. */
    private static String labelOf(Enum<?> constant) {
        try {
            // THE DECLARATION, not the answer: HSV's own label is its name, and comparing the two humanized it to Hsv.
            if (constant.getClass().getMethod("toString").getDeclaringClass() != Enum.class) return constant.toString();
        } catch (NoSuchMethodException unreachable) {
            // Every object has toString.
        }
        return humanize(constant.name());
    }

    private static Number number(@Nullable Object value) {
        if (value instanceof Number n) return n;
        return Double.parseDouble(String.valueOf(value).trim());
    }
}
