package com.crystalgui.ui.elements.graph;

import com.crystalgui.graph.NodeField;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.config.ConfigControl;
import com.crystalgui.ui.elements.config.ConfigControls;
import com.crystalgui.ui.elements.config.ConfigDescriptor;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Builds the editor widget for a {@link NodeField}, by kind.
 *
 * <h3>A codec layer over {@code ConfigControls}, not a widget library of its own</h3>
 * <p>Before P6.1.8's control kit existed, this class hand-built every widget — a bare {@code Dropdown},
 * a bare {@code Checkbox}, a bare {@code TextField} standing in for text, numbers, colours and vectors
 * alike. That was the second copy of "what does a partly-typed number mean", "how does a dropdown
 * report a selection" and so on — the first copy is {@code ConfigControls}, and a fix or a visual pass
 * made there used to need a matching one made here. It no longer does: {@link #select}, {@link #bool},
 * {@link #text} and {@link #number} each build a {@link NodeField.Kind}'s row through the exact same
 * {@link ConfigControls} registry the inspector uses, and {@link #wrap} is the one seam that adapts a
 * {@link ConfigControl}'s typed {@code Object} to this package's {@code String} contract.</p>
 *
 * <h3>{@link NodeField.Kind#COLOR} and {@link NodeField.Kind#VECTOR} are deliberately NOT built in</h3>
 * <p>{@link NodeField}'s own javadoc calls the stored value "the consumer's own literal form" — this
 * package is domain-agnostic (see the class-level warning on {@code ShaderGraphBridge}: it does not
 * know GLSL exists), so it has no business deciding that a colour is spelled {@code vec4(1, 0.5, 0, 1)}
 * rather than {@code #FF8000} or anything else a different graph domain might choose. That parsing is
 * genuinely graph-specific and has nowhere else to live, so it stays where it always has —
 * {@code ShaderColorFieldWidget} and {@code ShaderVectorFieldWidget} register the real codec for the
 * shader domain, each now building through {@link com.crystalgui.ui.elements.config.control.ColorControl}
 * / {@link com.crystalgui.ui.elements.config.control.VectorControl} instead of a hand-rolled widget. A
 * domain that never registers one simply gets no editor for these two kinds — the same fallback every
 * other unhandled kind already gets.</p>
 *
 * <h3>The widget reports a string, and nothing else</h3>
 * <p>A factory is handed the field, its current value, and a sink to call when the user changes it. It
 * does not know what a document is, cannot write one, and cannot make something undoable by accident —
 * the caller owns all of that. That is what lets the same widget serve a live editor, a preview pane, or
 * a read-only inspector.</p>
 */
public final class NodeFieldWidgets {

    /** Builds the control for one field. */
    @FunctionalInterface
    public interface Factory {
        /**
         * @param field   what is being edited
         * @param value   the current stored value, already resolved against the field's default
         * @param onChange call with the new value when the user commits a change
         */
        UIElement create(NodeField field, String value, Consumer<String> onChange);
    }

    private static final Map<NodeField.Kind, Factory> FACTORIES = new EnumMap<>(NodeField.Kind.class);

    static {
        FACTORIES.put(NodeField.Kind.ENUM, NodeFieldWidgets::select);
        FACTORIES.put(NodeField.Kind.BOOLEAN, NodeFieldWidgets::bool);
        FACTORIES.put(NodeField.Kind.TEXT, NodeFieldWidgets::text);
        FACTORIES.put(NodeField.Kind.NUMBER, NodeFieldWidgets::number);
        // COLOR and VECTOR: no default here — see the class javadoc.
    }

    private NodeFieldWidgets() {
    }

    /** Replaces the widget used for one kind, everywhere. */
    public static void register(NodeField.Kind kind, Factory factory) {
        FACTORIES.put(kind, factory);
    }

    /** The control for a field, or null when its kind has no registered widget. */
    @Nullable
    public static UIElement create(NodeField field, String value, Consumer<String> onChange) {
        Factory factory = FACTORIES.get(field.kind());
        return factory == null ? null : factory.create(field, value, onChange);
    }

    // ── The built-in widgets ────────────────────────────────────────────────

    private static UIElement select(NodeField field, String value, Consumer<String> onChange) {
        ConfigDescriptor descriptor = ConfigDescriptor.select(field.id(), field.label(), field.options());
        ConfigControl control = ConfigControls.create(descriptor, field.resolve(value));
        return wrap(control, v -> onChange.accept((String) v));
    }

    private static UIElement bool(NodeField field, String value, Consumer<String> onChange) {
        ConfigDescriptor descriptor = ConfigDescriptor.bool(field.id(), field.label());
        boolean initial = Boolean.parseBoolean(field.resolve(value));
        ConfigControl control = ConfigControls.create(descriptor, initial);
        return wrap(control, v -> onChange.accept(String.valueOf((Boolean) v)));
    }

    private static UIElement text(NodeField field, String value, Consumer<String> onChange) {
        ConfigDescriptor descriptor = ConfigDescriptor.text(field.id(), field.label());
        ConfigControl control = ConfigControls.create(descriptor, field.resolve(value));
        return wrap(control, v -> onChange.accept((String) v));
    }

    private static UIElement number(NodeField field, String value, Consumer<String> onChange) {
        ConfigDescriptor descriptor = ConfigDescriptor.number(field.id(), field.label());
        double initial = parseDouble(field.resolve(value));
        ConfigControl control = ConfigControls.create(descriptor, initial);
        return wrap(control, v -> onChange.accept(String.valueOf((Double) v)));
    }

    /**
     * Connects a control's typed {@link ConfigControl#changed} to this package's {@code String} sink,
     * and carries {@link ConfigControl#selfLabelling()} across to {@link GraphNode#FULL_WIDTH_CLASS}.
     *
     * <p>Two hosts, one decision, spelled the way each asks for it: {@code Configurator} reads the flag
     * off the control directly; {@code GraphNode.addControl} reads a CSS class instead, because a node
     * row predates {@code ConfigControl} and was never rebuilt to know the interface exists. Bridging
     * it here means a control never has to know which host it ended up in — the alternative is every
     * self-labelling control re-deciding "am I in a node or a panel?", which is exactly the kind of
     * per-host special case P6.1.8 exists to remove.</p>
     */
    private static UIElement wrap(ConfigControl control, Consumer<Object> onChange) {
        if (control.selfLabelling()) control.addClass(GraphNode.FULL_WIDTH_CLASS);
        control.changed.connect(onChange::accept);
        return control;
    }

    private static double parseDouble(@Nullable String text) {
        if (text == null) return 0d;
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException malformed) {
            return 0d;
        }
    }
}
