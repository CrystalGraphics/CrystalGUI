package com.crystalgui.app.uibuilder.style;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.ui.dom.ChildList;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.PropertyWatch;
import com.crystalgui.widget.config.ValueControl;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.text.UIText;

/**
 * A composite value as the stack it is: one row per layer, each drawn as itself, reorderable and removable.
 *
 * <pre>{@code
 * Property<Integer> selected = Property.of(0);
 * LayerStack stack = new LayerStack("layers", TEXT_SHADOW, selected);
 * stack.bind(css.map(CssValues::layers, CssValues::join));   // the rows ARE the declaration's layers
 * }</pre>
 *
 * <p><b>Order is the value.</b> {@code translate} then {@code scale} is not the reverse, and the first shadow
 * is the one on top, so moving a row is an edit. Pressing a row sets {@code selected}, which is what a lab's
 * other controls map through to edit that one layer.</p>
 */
public final class LayerStack extends ValueControl<List<String>> {

    public static final Name NAME = Name.of("layerstack");

    public static final String STACK_CLASS = "__layer-stack__";
    public static final String ROW_CLASS = "__layer-row__";
    public static final String SAMPLE_CLASS = "__layer-sample__";
    /** What a sample holds when the layer needs something to act on. @see #sample */
    public static final String SAMPLE_TEXT_CLASS = "__layer-sample-text__";
    public static final String TEXT_CLASS = "__layer-text__";
    public static final String ACTIVE_CLASS = "__active__";

    private final StyleProperty<?> property;
    private final Property<Integer> selected;
    private final ChildList<Row> rows = new ChildList<>(this, this::row);

    @Nullable
    private Consumer<UIElement> sampleBuilder;

    @Nullable
    private BiConsumer<UIElement, String> samplePainter;

    /**
     * @param property what a row's sample draws with — the property whose layers these are
     * @param selected which layer is picked, shared with the controls that edit it
     */
    public LayerStack(String id, StyleProperty<?> property, Property<Integer> selected) {
        super(NAME, ConfigDescriptor.of(id, "", ConfigDescriptor.Kind.ARRAY), List.of());
        this.property = property;
        this.selected = selected;
        addClass(STACK_CLASS);
        PropertyWatch.follow(this, selected, index -> paintSelection());
    }

    /**
     * How a row's sample is drawn, when applying the layer to an empty box says nothing.
     *
     * <pre>{@code
     * stack.sample(patch -> patch.append(new UIText("Ag")),                  // once, when the row is made
     *         (patch, layer) -> LiveEdits.setInline(patch, TEXT_SHADOW, fitted(layer)));   // on every change
     * }</pre>
     */
    public LayerStack sample(Consumer<UIElement> build, BiConsumer<UIElement, String> paint) {
        this.sampleBuilder = build;
        this.samplePainter = paint;
        return this;
    }

    @Override
    public boolean selfLabelling() {
        return true;
    }

    @Override
    protected void writeToWidgets(@Nullable List<String> layers) {
        List<String> shown = layers == null ? List.of() : layers;
        rows.resize(shown.size());
        for (int i = 0; i < shown.size(); i++) {
            Row row = rows.get(i);
            if (samplePainter != null) {
                samplePainter.accept(row.sample, shown.get(i));
            } else {
                LiveEdits.setInline(row.sample, property, shown.get(i));
            }
            row.text.setText(shown.get(i));
        }
        paintSelection();
    }

    private void paintSelection() {
        int at = selected.get() == null ? 0 : selected.get();
        for (int i = 0; i < rows.size(); i++) rows.get(i).toggleClass(ACTIVE_CLASS, i == at);
    }

    /** One layer's row: its sample, its text and its actions. */
    private static final class Row extends UIElement {
        final UIElement sample = new UIElement();
        final UIText text = new UIText("");
    }

    private Row row(int index) {
        Row row = new Row();
        row.addClass(ROW_CLASS);
        row.setHitTest(true);
        row.onMouseDown.attachListener((element, event) -> selected.set(index), false, true);

        row.sample.addClass(SAMPLE_CLASS);
        if (sampleBuilder != null) sampleBuilder.accept(row.sample);
        row.append(row.sample);

        row.text.addClass(TEXT_CLASS);
        row.append(row.text);

        row.append(action("↑", () -> move(index, -1)));
        row.append(action("↓", () -> move(index, 1)));
        row.append(action("×", () -> remove(index)));
        return row;
    }

    private Button action(String glyph, Runnable done) {
        Button button = new Button(glyph);
        button.addClass(BuilderStyleSections.ROW_ACTION_CLASS);
        button.attachListener(done);
        return button;
    }

    /** Adds a layer on top — the first entry, since first is what a stack paints over the rest. */
    public void add(String layer) {
        List<String> next = layers();
        next.add(0, layer);
        commit(next);
        selected.set(0);
    }

    /** Moves a layer by {@code by} places, keeping it selected — what the row's arrows do. */
    public void move(int index, int by) {
        List<String> next = layers();
        int to = index + by;
        if (to < 0 || to >= next.size()) return;
        next.add(to, next.remove(index));
        commit(next);
        selected.set(to);
    }

    /** Takes a layer out, selecting the one above it. */
    public void remove(int index) {
        List<String> next = layers();
        if (index < 0 || index >= next.size()) return;
        next.remove(index);
        commit(next);
        selected.set(Math.max(0, index - 1));
    }

    private List<String> layers() {
        List<String> now = getValue();
        return now == null ? new ArrayList<>() : new ArrayList<>(now);
    }
}
