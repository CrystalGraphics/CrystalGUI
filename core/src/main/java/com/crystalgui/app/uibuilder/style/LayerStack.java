package com.crystalgui.app.uibuilder.style;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.data.DataContext;
import com.crystalgui.core.data.DataKey;
import com.crystalgui.core.data.DataProvider;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.ChildList;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.PropertyWatch;
import com.crystalgui.widget.config.ValueControl;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.overlay.ContextMenu;
import com.crystalgui.widget.scroll.ScrollerView;
import com.crystalgui.widget.text.UIText;

/**
 * A composite value as the stack it is: one row per layer, each drawn as itself, reorderable and removable.
 *
 * <pre>{@code
 * Property<Integer> selected = Property.of(0);
 * LayerStack stack = new LayerStack("layers", TEXT_SHADOW, selected).titled("Shadows");
 * stack.adding("+ Add", () -> stack.add("0 1px 2px #000000"));   // a button in the stack's own header
 * stack.bind(css.map(CssValues::layers, CssValues::join));        // the rows ARE the declaration's layers
 * }</pre>
 *
 * <p>One inset box: a header naming the list with its count and add buttons, then the rows, which scroll past
 * three. A right-click on a row opens its menu — Move to Top, Move Up, Move Down, Move to Bottom, Visible,
 * Duplicate, Remove — as commands resolving the row through {@link #STACK} and {@link #LAYER}.</p>
 *
 * <p><b>{@link #hideable}</b> gives each row an eye that switches its layer off without deleting it: the layer is
 * kept as a comment in the declaration ({@link CssValues#layerStack}). Bind a stack that can hide to a value read
 * with {@code layerStack} or {@code functionStack}. A rule keeps the comment in its text, an element in its inline
 * style's written text.</p>
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
    /** What a layer is applied to when no {@link #sample} is given. */
    public static final String PLATE_CLASS = "__layer-sample-plate__";
    public static final String ACTIVE_CLASS = "__active__";
    /** The inset box inside the stack, holding the header and the rows. */
    public static final String BOX_CLASS = "__layer-box__";
    public static final String HEADER_CLASS = "__layer-header__";
    public static final String TITLE_CLASS = "__layer-title__";
    public static final String COUNT_CLASS = "__layer-count__";
    public static final String ADD_CLASS = "__layer-add__";
    public static final String LIST_CLASS = "__layer-list__";
    /** On a row's reorder and remove buttons. */
    public static final String ACTION_CLASS = "__layer-action__";

    /** The stack a right-clicked row belongs to. */
    public static final DataKey<LayerStack> STACK = DataKey.create("uibuilder.layerStack", LayerStack.class);
    /** Which layer a right-clicked row is, first on top. */
    public static final DataKey<Integer> LAYER = DataKey.create("uibuilder.layerStack.layer", Integer.class);

    public static final String MOVE_TO_TOP = "uibuilder.layers.moveToTop";
    public static final String MOVE_UP = "uibuilder.layers.moveUp";
    public static final String MOVE_DOWN = "uibuilder.layers.moveDown";
    public static final String MOVE_TO_BOTTOM = "uibuilder.layers.moveToBottom";
    public static final String DUPLICATE = "uibuilder.layers.duplicate";
    public static final String VISIBLE = "uibuilder.layers.visible";
    /** On a row whose layer is switched off, and on its eye. */
    public static final String OFF_CLASS = "__off__";
    /** On a row's eye. */
    public static final String EYE_CLASS = "__layer-eye__";
    public static final String REMOVE = "uibuilder.layers.remove";

    private static final ContextMenu ROW_MENU = ContextMenu.builder()
            .item(MOVE_TO_TOP).item(MOVE_UP).item(MOVE_DOWN).item(MOVE_TO_BOTTOM)
            .separator()
            .item(VISIBLE)
            .item(DUPLICATE).item(REMOVE);

    private boolean hideable;

    /** False for a list whose order is not the value's to set by hand — a gradient's stops, ordered by position. */
    private boolean reorderable = true;

    private final StyleProperty<?> property;
    private final Property<Integer> selected;
    private final UIElement header = new UIElement();
    private final UIText title = new UIText("");
    private final UIText count = new UIText("");
    private final ScrollerView list = new ScrollerView();
    private final ChildList<Row> rows = new ChildList<>(list, this::row);

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
        header.addClass(HEADER_CLASS);
        title.addClass(TITLE_CLASS);
        count.addClass(COUNT_CLASS);
        header.append(title);
        header.append(count);
        UIElement box = new UIElement();
        box.addClass(BOX_CLASS);
        box.append(header);
        list.addClass(LIST_CLASS);
        box.append(list);
        append(box);
        PropertyWatch.follow(this, selected, index -> {
            paintSelection();
            revealSelection();
        });
        CommandRegistry.global().contribute(LayerStack.class, LayerStack::declare);
        ContextMenu.attach(list, CommandRegistry.global(), pressed -> rowOf(pressed) == null ? null : ROW_MENU);
    }

    private static void declare(CommandRegistry registry) {
        registry.register(Command.of(MOVE_TO_TOP, "Move to Top")
                .enabledWhereData(data -> layer(data) > 0 && data.get(STACK).reorderable)
                .runWithData(data -> data.get(STACK).moveTo(layer(data), 0)));
        registry.register(Command.of(MOVE_UP, "Move Up")
                .enabledWhereData(data -> layer(data) > 0 && data.get(STACK).reorderable)
                .runWithData(data -> data.get(STACK).move(layer(data), -1)));
        registry.register(Command.of(MOVE_DOWN, "Move Down")
                .enabledWhereData(data -> layer(data) >= 0 && layer(data) < data.get(STACK).size() - 1
                        && data.get(STACK).reorderable)
                .runWithData(data -> data.get(STACK).move(layer(data), 1)));
        registry.register(Command.of(MOVE_TO_BOTTOM, "Move to Bottom")
                .enabledWhereData(data -> layer(data) >= 0 && layer(data) < data.get(STACK).size() - 1
                        && data.get(STACK).reorderable)
                .runWithData(data -> data.get(STACK).moveTo(layer(data), data.get(STACK).size() - 1)));
        registry.register(Command.of(VISIBLE, "Visible")
                .enabledWhereData(data -> layer(data) >= 0 && data.get(STACK).hideable)
                .toggledWhereData(data -> layer(data) >= 0 && !data.get(STACK).isOff(layer(data)))
                .runWithData(data -> data.get(STACK).toggleVisible(layer(data))));
        registry.register(Command.of(DUPLICATE, "Duplicate")
                .enabledWhereData(data -> layer(data) >= 0)
                .runWithData(data -> data.get(STACK).duplicate(layer(data))));
        registry.register(Command.of(REMOVE, "Remove")
                .enabledWhereData(data -> layer(data) >= 0)
                .runWithData(data -> data.get(STACK).remove(layer(data))));
    }

    /** The right-clicked layer, or -1 when the context holds none. */
    private static int layer(DataContext data) {
        Integer at = data.get(LAYER);
        return data.get(STACK) == null || at == null ? -1 : at;
    }

    @Nullable
    private static Row rowOf(@Nullable UIElement pressed) {
        for (UIElement at = pressed; at != null; at = at.parentElement()) {
            if (at instanceof Row row) return row;
        }
        return null;
    }

    /** How many layers there are. */
    public int size() {
        List<String> now = getValue();
        return now == null ? 0 : now.size();
    }

    /** Whether rows carry the up and down arrows and the menu offers moving them. True by default. */
    public LayerStack reorderable(boolean reorderable) {
        this.reorderable = reorderable;
        return this;
    }

    /** Whether a layer may be switched off rather than deleted: a value somewhere writable holds the comment. */
    public LayerStack hideable(boolean hideable) {
        this.hideable = hideable;
        writeToWidgets(getValue());
        return this;
    }

    /** Whether the layer at {@code index} is switched off. */
    public boolean isOff(int index) {
        List<String> now = getValue();
        return now != null && index >= 0 && index < now.size() && CssValues.isOff(now.get(index));
    }

    /** Switches a layer off, or back on — what its eye does. */
    public void toggleVisible(int index) {
        List<String> next = layers();
        if (!hideable || index < 0 || index >= next.size()) return;
        next.set(index, CssValues.switched(next.get(index), CssValues.isOff(next.get(index))));
        commitAndShow(next);
    }

    /** What the header calls the list: {@code Shadows}, {@code Transforms}. */
    public LayerStack titled(String name) {
        title.setText(name);
        return this;
    }

    /** Puts a button in the header, at its end — where a layer is added from. */
    public LayerStack adding(String label, Runnable add) {
        Button button = new Button(label);
        button.addClass(ADD_CLASS);
        button.attachListener(add);
        header.append(button);
        return this;
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
            // THE LAYER ITSELF, switched off or not: a hidden layer's row still shows what it would be.
            String layer = CssValues.bodyOf(shown.get(i));
            if (samplePainter != null) {
                samplePainter.accept(row.sample, layer);
            } else {
                LiveEdits.setInline(row.plate, property, layer);
            }
            // AS A PERSON READS IT: #CF0600 rather than the writer's #CF0600FF, 24.5px rather than 24.49px.
            row.text.setText(CssValues.readable(layer));
            boolean off = CssValues.isOff(shown.get(i));
            row.toggleClass(OFF_CLASS, off);
            row.eye.toggleClass(OFF_CLASS, off);
            row.eye.setDisplayed(hideable);
        }
        count.setText(String.valueOf(shown.size()));
        paintSelection();
    }

    private void paintSelection() {
        int at = selected.get() == null ? 0 : selected.get();
        for (int i = 0; i < rows.size(); i++) rows.get(i).toggleClass(ACTIVE_CLASS, i == at);
    }

    /**
     * Scrolls the picked row into the list's view once it is laid out: a move can carry it past the third. The list's
     * own view only — picking a stop elsewhere scrolled the whole lab down to its row.
     */
    private void revealSelection() {
        UIDocument window = document();
        if (window == null) return;
        window.animation().afterLayout(this, delta -> {
            int at = selected.get() == null ? 0 : selected.get();
            if (at >= 0 && at < rows.size() && rows.get(at).box() != null) rows.get(at).box().scrollIntoView(Box.Reach.NEAREST);
            return false;
        });
    }

    /** One layer's row: its sample, its text and its actions, and what a command run on it is acting on. */
    private final class Row extends UIElement implements DataProvider {
        final int index;
        final UIElement sample = new UIElement();
        final UIElement plate = new UIElement();
        final UIText text = new UIText("");
        final UIElement eye = new UIElement();

        Row(int index) {
            this.index = index;
        }

        @Override
        @Nullable
        public Object getData(DataKey<?> key) {
            if (key == STACK) return LayerStack.this;
            return key == LAYER ? index : null;
        }
    }

    private Row row(int index) {
        Row row = new Row(index);
        row.addClass(ROW_CLASS);
        row.setHitTest(true);
        row.onMouseDown.attachListener((element, event) -> selected.set(index), false, true);

        row.sample.addClass(SAMPLE_CLASS);
        if (sampleBuilder != null) {
            sampleBuilder.accept(row.sample);
        } else {
            row.plate.addClass(PLATE_CLASS);
            row.sample.append(row.plate);
        }
        row.append(row.sample);

        row.text.addClass(TEXT_CLASS);
        row.append(row.text);

        // THE EYE, as a design tool's layer row has one: hides without deleting, and stays up while hidden.
        row.eye.addClass(EYE_CLASS);
        row.eye.setHitTest(true);
        row.eye.onMouseDown.attachListener((element, event) -> {
            if (event.getButtonId() != CgMouseCodes.LEFT_BUTTON) return;
            toggleVisible(index);
            event.preventDefault();
            event.stopPropagation();
        }, false, true);
        row.append(row.eye);

        if (reorderable) {
            row.append(action("↑", () -> move(index, -1)));
            row.append(action("↓", () -> move(index, 1)));
        }
        row.append(action("×", () -> remove(index)));
        return row;
    }

    private Button action(String glyph, Runnable done) {
        Button button = new Button(glyph);
        button.addClass(ACTION_CLASS);
        button.attachListener(done);
        return button;
    }

    /** Adds a layer on top — the first entry, since first is what a stack paints over the rest. */
    public void add(String layer) {
        List<String> next = layers();
        next.add(0, layer);
        commitAndShow(next);
        selected.set(0);
    }

    /** Moves a layer by {@code by} places, keeping it selected — what the row's arrows do. */
    public void move(int index, int by) {
        moveTo(index, index + by);
    }

    /** Moves a layer to {@code to}, keeping it selected: 0 is the top. */
    public void moveTo(int index, int to) {
        List<String> next = layers();
        if (index < 0 || index >= next.size() || to < 0 || to >= next.size() || to == index) return;
        next.add(to, next.remove(index));
        commitAndShow(next);
        selected.set(to);
    }

    /** Puts a copy of a layer directly above it and picks the copy, as a design tool's Duplicate does. */
    public void duplicate(int index) {
        List<String> next = layers();
        if (index < 0 || index >= next.size()) return;
        next.add(index, next.get(index));
        commitAndShow(next);
        selected.set(index);
    }

    /** Takes a layer out, selecting the one above it. */
    public void remove(int index) {
        List<String> next = layers();
        if (index < 0 || index >= next.size()) return;
        next.remove(index);
        commitAndShow(next);
        selected.set(Math.max(0, index - 1));
    }

    private List<String> layers() {
        List<String> now = getValue();
        return now == null ? new ArrayList<>() : new ArrayList<>(now);
    }
}
