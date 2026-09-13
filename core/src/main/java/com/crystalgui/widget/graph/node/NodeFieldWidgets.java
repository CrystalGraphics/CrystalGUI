package com.crystalgui.widget.graph.node;

import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.graph.NodeField;
import com.crystalgui.widget.config.ConfigControl;
import com.crystalgui.widget.config.ConfigControls;
import com.crystalgui.widget.graph.GraphNode;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.Map;

/**
 * Builds the editor for a {@link NodeField}, by kind, over the field's stored text.
 *
 * <pre>{@code
 * Property<String> stored = NodeFieldBinder.property(field, document, nodeId, undo, recompile);
 * ConfigControl editor = NodeFieldWidgets.create(field, stored);
 * }</pre>
 *
 * <p>A node stores every value as text in {@link NodeField}'s own literal form. A factory turns that text
 * into the typed value its control edits with {@link Property#map} — a number parses, a boolean reads
 * {@code true} — and the control then both writes and follows it. Nothing here knows what a document is,
 * which is what lets the same editor serve a node's body, its floating port editor and an inspector row.</p>
 *
 * <h3>{@link NodeField.Kind#COLOR} and {@link NodeField.Kind#VECTOR} are deliberately NOT built in</h3>
 * <p>A colour's literal is the consumer's own — {@code vec4(1, 0.5, 0, 1)} to a shader graph,
 * {@code #FF8000} to something else — so the domain registers the codec:</p>
 *
 * <pre>{@code
 * NodeFieldWidgets.register(NodeField.Kind.COLOR, (field, stored) -> ConfigControls.bound(
 *         ConfigDescriptor.color(field.id(), field.label()), stored.map(Glsl::parseVec4, Glsl::formatVec4)));
 * }</pre>
 *
 * <p>A domain that never registers one gets no editor for that kind — the same as any unhandled kind.</p>
 *
 * <ul>
 *   <li>A port field lands what is typed on every keystroke: a port default is scrubbed and typed while
 *       the preview is watched.</li>
 *   <li>A self-labelling control spans a node's body (see {@link GraphNode#FULL_WIDTH_CLASS}).</li>
 * </ul>
 */
public final class NodeFieldWidgets {

    /** Builds the editor for one field. */
    @FunctionalInterface
    public interface Factory {
        /**
         * @param field  what is being edited
         * @param stored the field's stored text, already resolved against its default
         * @return a control bound to {@code stored}, or null to leave the field without an editor
         */
        @Nullable
        ConfigControl create(NodeField field, Property<String> stored);
    }

    private static final Map<NodeField.Kind, Factory> FACTORIES = new EnumMap<>(NodeField.Kind.class);

    static {
        FACTORIES.put(NodeField.Kind.ENUM, (field, stored) -> ConfigControls.bound(
                describe(field, ConfigDescriptor.select(field.id(), field.label(), field.options())), stored));
        FACTORIES.put(NodeField.Kind.BOOLEAN, (field, stored) -> ConfigControls.bound(
                describe(field, ConfigDescriptor.bool(field.id(), field.label())),
                stored.map(Boolean::parseBoolean, String::valueOf)));
        FACTORIES.put(NodeField.Kind.TEXT, (field, stored) -> ConfigControls.bound(
                describe(field, ConfigDescriptor.text(field.id(), field.label())), stored));
        FACTORIES.put(NodeField.Kind.NUMBER, (field, stored) -> ConfigControls.bound(
                describe(field, ConfigDescriptor.number(field.id(), field.label())),
                stored.map(NodeFieldWidgets::parseDouble, String::valueOf)));
        // COLOR and VECTOR: no default here — see the class javadoc.
    }

    private NodeFieldWidgets() {
    }

    /** Replaces the editor used for one kind, everywhere. */
    public static void register(NodeField.Kind kind, Factory factory) {
        FACTORIES.put(kind, factory);
    }

    /** The editor for a field, bound to its stored text, or null when its kind has no registered factory. */
    @Nullable
    public static ConfigControl create(NodeField field, Property<String> stored) {
        Factory factory = FACTORIES.get(field.kind());
        ConfigControl control = factory == null ? null : factory.create(field, stored);
        // THE ROW'S DECISION, SPELLED THE WAY A NODE ASKS FOR IT: a node row reads a class rather than
        // asking the control, having predated ConfigControl.
        if (control != null && control.selfLabelling()) control.addClass(GraphNode.FULL_WIDTH_CLASS);
        return control;
    }

    /**
     * What every field's descriptor shares: a port field lands every keystroke.
     *
     * <p>Public so a domain's own factory spells port fields the same way.</p>
     */
    public static ConfigDescriptor describe(NodeField field, ConfigDescriptor descriptor) {
        return descriptor.commitWhileTyping(field.isPortField());
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
