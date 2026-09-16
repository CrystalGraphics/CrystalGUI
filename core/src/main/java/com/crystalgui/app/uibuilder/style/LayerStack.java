package com.crystalgui.app.uibuilder.style;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.text.UIText;

/**
 * A composite value as the stack it is: one row per layer, reorderable, each drawn as itself.
 *
 * <pre>{@code
 * LayerStack stack = new LayerStack(StylePropertyRegistry.TEXT_SHADOW);
 * stack.onSelect(index -> edit(index));
 * stack.onChange(layers -> css.set(CssValues.join(layers)));
 * stack.show(CssValues.layers(css.get()), selected);
 * }</pre>
 *
 * <p><b>Order is the value.</b> Every composite the engine has is an ordered list where the order decides
 * the result — {@code translate} then {@code scale} is not the reverse, and the first background layer is
 * the one on top — so moving a row is an edit, not a view preference. Photoshop's layer styles, at the size
 * a lab has room for.</p>
 *
 * <p>Each row carries a sample with just that layer applied, so a stack of three shadows reads as three
 * shadows rather than three strings.</p>
 */
public final class LayerStack extends UIElement {

    public static final String STACK_CLASS = "__layer-stack__";
    public static final String ROW_CLASS = "__layer-row__";
    public static final String SAMPLE_CLASS = "__layer-sample__";
    /** What a painter puts inside a sample when the layer needs something to act on. */
    public static final String SAMPLE_TEXT_CLASS = "__layer-sample-text__";
    public static final String TEXT_CLASS = "__layer-text__";
    public static final String ACTIVE_CLASS = "__active__";

    private final StyleProperty<?> property;

    private final List<String> layers = new ArrayList<>();
    private int selected;

    @Nullable
    private IntConsumer onSelect;

    @Nullable
    private Consumer<List<String>> onChange;

    /** How a layer is drawn in its row, or null to apply the layer itself. @see #sample(BiConsumer) */
    @Nullable
    private BiConsumer<UIElement, String> painter;

    /** @param property what a row's sample draws with — the property whose layers these are */
    public LayerStack(StyleProperty<?> property) {
        this.property = property;
        addClass(STACK_CLASS);
    }

    /**
     * How a layer is drawn in its row, when applying the value itself says nothing.
     *
     * <pre>{@code
     * stack.sample((patch, layer) -> StyleChip.paintColour(patch, colourOf(layer)));
     * }</pre>
     *
     * <p>The default applies the layer, which is right for a transform: a 20x14 chip genuinely moves.
     * A shadow is the case it fails on -- a chip that size cannot hold a 16px blur, and with no text in
     * it a {@code text-shadow} draws nothing at all, so five different shadows came out as five
     * identical grey boxes.</p>
     */
    public LayerStack sample(BiConsumer<UIElement, String> painter) {
        this.painter = painter;
        return this;
    }

    /** Told when a row is picked: which one a lab's own gizmos then edit. */
    public LayerStack onSelect(IntConsumer listener) {
        this.onSelect = listener;
        return this;
    }

    /** Told when the layers themselves change — reordered, removed, added. */
    public LayerStack onChange(Consumer<List<String>> listener) {
        this.onChange = listener;
        return this;
    }

    /** The layers this is showing, in order. */
    public List<String> layers() {
        return List.copyOf(layers);
    }

    public int selected() {
        return selected;
    }

    /** Draws {@code values}, with {@code selected} picked out. */
    public void show(List<String> values, int selected) {
        // COPIED FIRST: `changed` shows the list it just edited, and clearing the argument emptied it.
        List<String> shown = new ArrayList<>(values);
        layers.clear();
        layers.addAll(shown);
        this.selected = Math.max(0, Math.min(selected, layers.size() - 1));
        removeAll();
        for (int i = 0; i < layers.size(); i++) append(row(i));
    }

    private UIElement row(int index) {
        UIElement row = new UIElement();
        row.addClass(ROW_CLASS);
        if (index == selected) row.addClass(ACTIVE_CLASS);
        row.setHitTest(true);
        row.onMouseDown.attachListener((element, event) -> select(index), false, true);

        UIElement sample = new UIElement();
        sample.addClass(SAMPLE_CLASS);
        if (painter != null) {
            painter.accept(sample, layers.get(index));
        } else {
            LiveEdits.setInline(sample, cast(property), layers.get(index));
        }
        row.append(sample);

        UIText text = new UIText(layers.get(index));
        text.addClass(TEXT_CLASS);
        row.append(text);

        row.append(action("↑", () -> move(index, -1)));
        row.append(action("↓", () -> move(index, 1)));
        row.append(action("×", () -> remove(index)));
        return row;
    }

    private Button action(String glyph, Runnable done) {
        Button button = new Button(glyph);
        button.addClass(BuilderStyleSections.ROW_ACTION_CLASS);
        button.attachListener(done);
        // THE PRESS STOPS HERE, and without that none of these could ever fire. Bubbling to the row
        // selects it, selecting rebuilds every row, and the button the press landed on is destroyed
        // before its own release arrives -- so it never activates. Reordering is not selecting anyway.
        button.onMouseDown.attachListener((element, event) -> event.stopPropagation(), false, true);
        return button;
    }

    private void select(int index) {
        selected = index;
        show(layers, index);
        if (onSelect != null) onSelect.accept(index);
    }

    /** Adds a layer on top — the first entry, since first is what a stack paints over the rest. */
    public void add(String layer) {
        layers.add(0, layer);
        changed(0);
    }

    /** Replaces the selected layer, which is what a lab's gizmos do as they are dragged. */
    public void replaceSelected(String layer) {
        if (selected < 0 || selected >= layers.size()) return;
        layers.set(selected, layer);
        changed(selected);
    }

    /** Moves a layer by {@code by} places — what the row's arrows do, and an edit either way. */
    public void move(int index, int by) {
        int to = index + by;
        if (to < 0 || to >= layers.size()) return;
        layers.add(to, layers.remove(index));
        changed(to);
    }

    /** Takes a layer out of the stack. */
    public void remove(int index) {
        if (index < 0 || index >= layers.size()) return;
        layers.remove(index);
        changed(Math.max(0, index - 1));
    }

    private void changed(int nowSelected) {
        show(layers, nowSelected);
        if (onChange != null) onChange.accept(layers());
    }

    @SuppressWarnings("unchecked")
    private static StyleProperty<Object> cast(StyleProperty<?> property) {
        return (StyleProperty<Object>) property;
    }
}
