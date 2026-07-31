package com.crystalgui.ui.elements.graph;

import com.crystalgui.graph.NodeField;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.Checkbox;
import com.crystalgui.ui.elements.Dropdown;
import com.crystalgui.ui.elements.TextField;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Builds the editor widget for a {@link NodeField}, by kind.
 *
 * <h3>Extend by registering a widget, not by inventing a kind</h3>
 * <p>{@link NodeField.Kind} is a small closed set on purpose: it describes the <b>shape of a value</b>,
 * not the control that edits it. A consumer wanting a colour wheel instead of a text box registers a
 * factory for {@link NodeField.Kind#COLOR} and every existing document keeps working — whereas a new kind
 * would make documents unreadable by any editor that had not heard of it.</p>
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
        FACTORIES.put(NodeField.Kind.ENUM, NodeFieldWidgets::dropdown);
        FACTORIES.put(NodeField.Kind.BOOLEAN, NodeFieldWidgets::checkbox);
        // Text, number, colour and vector all edit as text today. They are separate KINDS regardless,
        // because the kind is what a better widget registers against later — collapsing them now would
        // throw away the only information that says which ones those are.
        FACTORIES.put(NodeField.Kind.TEXT, NodeFieldWidgets::textField);
        FACTORIES.put(NodeField.Kind.NUMBER, NodeFieldWidgets::textField);
        FACTORIES.put(NodeField.Kind.COLOR, NodeFieldWidgets::textField);
        FACTORIES.put(NodeField.Kind.VECTOR, NodeFieldWidgets::textField);
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

    private static UIElement dropdown(NodeField field, String value, Consumer<String> onChange) {
        Dropdown dropdown = new Dropdown(field.label());
        for (String option : field.options()) dropdown.addOption(option);
        dropdown.select(field.resolve(value));
        dropdown.attachSelectionListener(index -> {
            if (index >= 0 && index < field.options().size()) onChange.accept(field.options().get(index));
        });
        return dropdown;
    }

    private static UIElement checkbox(NodeField field, String value, Consumer<String> onChange) {
        Checkbox checkbox = new Checkbox("");
        checkbox.setChecked(Boolean.parseBoolean(field.resolve(value)));
        checkbox.attachListener(checked -> onChange.accept(String.valueOf(checked)));
        return checkbox;
    }

    private static UIElement textField(NodeField field, String value, Consumer<String> onChange) {
        TextField text = new TextField();
        text.setText(field.resolve(value));
        text.attachListener(onChange::accept);
        return text;
    }
}
