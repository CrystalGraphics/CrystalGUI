package com.crystalgui.app.uibuilder.inspect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;

import com.crystalgui.app.uibuilder.document.BuilderEdit;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.serialization.JsonOps;
import com.crystalgui.serialization.style.InlineStyleCodec;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.event.KeyboardEvent;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.overlay.Tooltip;
import com.crystalgui.widget.text.UIText;

import dev.vfyjxf.taffy.geometry.FloatRect;
import dev.vfyjxf.taffy.style.BoxSizing;

/**
 * A node's box model as nested boxes — margin, border, padding, content — with the laid-out value of every
 * edge, each editable in place.
 *
 * <pre>{@code
 * form.custom(new BoxModelEditor(node, document));   // edits write SetInlineStyle into the document
 * form.custom(new BoxModelEditor(node, null));       // read-only, for a live pick
 * }</pre>
 *
 * <p>Ported from Chromium DevTools' {@code MetricsSidebarPane.ts} (BSD-3-Clause): double-click a value to
 * edit it, Up and Down step it by 1 (Shift 10, Alt 0.1) and apply as they go, Enter or a click elsewhere
 * commits, Escape puts back what was there. A bare number is pixels; {@code 50%}, {@code auto} and
 * {@code 2em} are written as typed, and an empty value removes the inline declaration. The content size is
 * shown as the content box and, like DevTools, a typed content size has the padding and border added before
 * it is written under {@code box-sizing: border-box}.</p>
 *
 * <ul>
 *   <li>One edit session is one undo step, however many arrow presses it took.</li>
 *   <li>A node with no box — hidden, not laid out — shows dashes and edits nothing.</li>
 *   <li>A value set inline is drawn bold; the rest come from a sheet or the default.</li>
 * </ul>
 */
public final class BoxModelEditor extends UIElement {

    public static final String BOX_CLASS = "__boxmodel__";
    public static final String LAYER_CLASS = "__bm-layer__";
    public static final String LABEL_CLASS = "__bm-label__";
    public static final String ROW_CLASS = "__bm-row__";
    public static final String HEAD_CLASS = "__bm-head__";
    public static final String VALUE_CLASS = "__bm-value__";
    public static final String AUTHORED_CLASS = "__authored__";
    public static final String CONTENT_CLASS = "__bm-content__";
    public static final String EDITOR_CLASS = "__bm-editor__";

    /** One number on the diagram: where it is read from, and what property it writes. */
    public final class Cell {

        private final StyleProperty<?> property;
        private final Function<Box, Float> read;
        private final UIText text = new UIText("");
        private final boolean contentSize;

        private Cell(StyleProperty<?> property, Function<Box, Float> read, boolean contentSize) {
            this.property = property;
            this.read = read;
            this.contentSize = contentSize;
            text.addClass(VALUE_CLASS);
            Tooltip hint = Tooltip.attach(text, property.name);
            hint.addClass(Tooltip.WAIT_CLASS);
            hint.setDescription(contentSize
                    ? "The content box. Double-click to set the " + property.name + ", padding and border included."
                    : "Double-click to set it inline. Up and Down step by 1, Shift by 10, Alt by 0.1.");
            text.onMouseDown.attachListener((element, event) -> {
                if (event instanceof MouseEvent.Down down && down.getDetail() >= 2) {
                    beginEdit(this);
                    event.preventDefault();
                }
            }, false, true);
        }

        public StyleProperty<?> property() {
            return property;
        }

        /** What the cell shows. */
        public String shown() {
            return text.getText();
        }

        private void refresh() {
            Box box = node.box();
            text.setText(box == null ? "-" : format(read.apply(box)));
            if (LiveEdits.hasInline(node, property)) text.addClass(AUTHORED_CLASS);
            else text.removeClass(AUTHORED_CLASS);
        }
    }

    private final UIElement node;

    @Nullable
    private final UiBuilderDocument document;

    private final List<Cell> cells = new ArrayList<>();

    @Nullable
    private Cell editing;

    @Nullable
    private TextField field;

    /** The inline style when the edit began — what Escape restores and the undo step starts from. */
    @Nullable
    private JsonElement before;

    private boolean ticking;

    public BoxModelEditor(UIElement node, @Nullable UiBuilderDocument document) {
        this.node = node;
        this.document = document;
        addClass(BOX_CLASS);

        UIElement content = new UIElement();
        content.addClass(CONTENT_CLASS);
        content.addClass(ROW_CLASS);
        content.append(cell(LayoutProperties.WIDTH, Box::contentBoxWidth, true).text);
        UIText times = new UIText("×");
        times.addClass(LABEL_CLASS);
        content.append(times);
        content.append(cell(LayoutProperties.HEIGHT, Box::contentBoxHeight, true).text);

        UIElement padding = layer("padding", content, Box::padding, LayoutProperties.PADDING_TOP,
                LayoutProperties.PADDING_RIGHT, LayoutProperties.PADDING_BOTTOM, LayoutProperties.PADDING_LEFT);
        UIElement border = layer("border", padding, Box::border, LayoutProperties.BORDER_TOP,
                LayoutProperties.BORDER_RIGHT, LayoutProperties.BORDER_BOTTOM, LayoutProperties.BORDER_LEFT);
        UIElement margin = layer("margin", border, Box::margin, LayoutProperties.MARGIN_TOP,
                LayoutProperties.MARGIN_RIGHT, LayoutProperties.MARGIN_BOTTOM, LayoutProperties.MARGIN_LEFT);
        append(margin);

        onConnected(this::startTicking);
    }

    /** Whether edits are possible — a document is behind the node. */
    public boolean isEditable() {
        return document != null;
    }

    /** Every number on the diagram, outermost edges first, then the content size. */
    public List<Cell> cells() {
        return List.copyOf(cells);
    }

    /** The cell writing {@code property}, or null. */
    @Nullable
    public Cell cellFor(StyleProperty<?> property) {
        for (Cell cell : cells) {
            if (cell.property == property) return cell;
        }
        return null;
    }

    // ── Structure ───────────────────────────────────────────────────────────

    private Cell cell(StyleProperty<?> property, Function<Box, Float> read, boolean contentSize) {
        Cell cell = new Cell(property, read, contentSize);
        cells.add(cell);
        return cell;
    }

    private UIElement layer(String name, UIElement inner, Function<Box, FloatRect> edges,
                            StyleProperty<?> top, StyleProperty<?> right, StyleProperty<?> bottom, StyleProperty<?> left) {
        UIElement layer = new UIElement();
        layer.addClass(LAYER_CLASS);
        layer.addClass("__bm-" + name + "__");

        UIElement head = new UIElement();
        head.addClass(ROW_CLASS);
        head.addClass(HEAD_CLASS);
        UIText label = new UIText(name);
        label.addClass(LABEL_CLASS);
        head.append(label);
        head.append(cell(top, box -> edges.apply(box).top, false).text);

        UIElement middle = new UIElement();
        middle.addClass(ROW_CLASS);
        middle.append(cell(left, box -> edges.apply(box).left, false).text);
        middle.append(inner);
        middle.append(cell(right, box -> edges.apply(box).right, false).text);

        UIElement foot = new UIElement();
        foot.addClass(ROW_CLASS);
        foot.append(cell(bottom, box -> edges.apply(box).bottom, false).text);

        layer.append(head);
        layer.append(middle);
        layer.append(foot);
        return layer;
    }

    private void startTicking() {
        UIDocument window = document();
        if (window == null || ticking) return;
        ticking = true;
        refresh();
        // AFTER LAYOUT: every number here is read from a box, and an ordinary hook runs before this frame's.
        window.animation().afterLayout(this, delta -> {
            if (!isConnected()) {
                ticking = false;
                return false;
            }
            refresh();
            return true;
        });
    }

    /** Re-reads every value but the one being typed. */
    public void refresh() {
        for (Cell cell : cells) {
            if (cell != editing) cell.refresh();
        }
    }

    // ── Editing ─────────────────────────────────────────────────────────────

    /** Opens {@code cell} for typing, as a double-click does. A no-op when nothing can be written. */
    public void beginEdit(Cell cell) {
        if (document == null || node.box() == null || editing == cell) return;
        if (editing != null) commit();
        editing = cell;
        before = InlineStyleCodec.encode(JsonOps.INSTANCE, node);

        TextField input = new TextField();
        input.addClass(EDITOR_CLASS);
        input.setText(cell.text.getText());
        input.onKeyDown.attachListener((element, event) -> {
            if (handleKey(event)) {
                event.stopPropagation();
                event.preventDefault();
            }
        }, false, true);
        input.onBlur.attachListener((element, event) -> commit(), false, true);
        field = input;

        UIElement row = cell.text.parentElement();
        if (row == null) return;
        row.insertAt(row.indexOf(cell.text), input);
        cell.text.setDisplayed(false);
        UIDocument window = document();
        if (window != null) window.focus().requestFocus(input);
        input.selectAll();
    }

    /** What is being typed, or null when nothing is open. */
    @Nullable
    public TextField field() {
        return field;
    }

    private boolean handleKey(KeyboardEvent event) {
        int key = event.getKeyCode();
        if (key == CgKeyCodes.KEY_RETURN) {
            commit();
            return true;
        }
        if (key == CgKeyCodes.KEY_ESCAPE) {
            cancel();
            return true;
        }
        if (key == CgKeyCodes.KEY_UP || key == CgKeyCodes.KEY_DOWN) {
            step(key == CgKeyCodes.KEY_UP ? 1 : -1, event.getModifiers());
            return true;
        }
        return false;
    }

    /** Steps the typed number, DevTools' way, and applies it at once. */
    public void step(int direction, int modifiers) {
        if (field == null) return;
        double amount = CgModifiers.hasShift(modifiers) ? 10 : CgModifiers.hasAlt(modifiers) ? 0.1 : 1;
        String text = field.getText().trim();
        double value;
        try {
            value = Double.parseDouble(text.endsWith("px") ? text.substring(0, text.length() - 2).trim() : text);
        } catch (NumberFormatException notANumber) {
            return;
        }
        double next = Math.round((value + direction * amount) * 10.0) / 10.0;
        field.setText(format((float) next));
        preview(field.getText());
    }

    /** Writes the typed value onto the node, without recording it — what arrow presses do mid-edit. */
    private boolean preview(String raw) {
        if (editing == null) return false;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            LiveEdits.clearInline(node, editing.property);
            return true;
        }
        return LiveEdits.setInline(node, editing.property, cssValue(editing, trimmed));
    }

    /** Ends the edit, recording what it changed as one step. A value that does not parse changes nothing. */
    public void commit() {
        if (editing == null || field == null) return;
        Cell cell = editing;
        TextField input = field;
        JsonElement was = before;
        boolean parsed = preview(input.getText());
        close(cell, input);
        if (!parsed) {
            InlineStyleCodec.replaceInto(JsonOps.INSTANCE, was, node);
            return;
        }
        JsonElement after = InlineStyleCodec.encode(JsonOps.INSTANCE, node);
        if (document != null && !after.equals(was)) document.apply(new BuilderEdit.SetInlineStyle(node, was, after));
    }

    /** Ends the edit and puts back the inline style it began with. */
    public void cancel() {
        if (editing == null || field == null) return;
        Cell cell = editing;
        TextField input = field;
        JsonElement was = before;
        close(cell, input);
        InlineStyleCodec.replaceInto(JsonOps.INSTANCE, was, node);
    }

    private void close(Cell cell, TextField input) {
        editing = null;
        field = null;
        before = null;
        UIElement row = input.parentElement();
        if (row != null) row.remove(input);
        cell.text.setDisplayed(true);
        cell.refresh();
    }

    /**
     * What to write for {@code typed}: a bare number is pixels, and a content size under border-box sizing
     * has the padding and border on that axis added — DevTools' {@code MetricsSidebarPane} rule.
     */
    private String cssValue(Cell cell, String typed) {
        double number;
        try {
            number = Double.parseDouble(typed.endsWith("px") ? typed.substring(0, typed.length() - 2).trim() : typed);
        } catch (NumberFormatException keyword) {
            return typed;
        }
        Box box = node.box();
        if (cell.contentSize && box != null && node.getStyle().computed().get(LayoutProperties.BOX_SIZING) != BoxSizing.CONTENT_BOX) {
            FloatRect padding = box.padding();
            FloatRect border = box.border();
            number += cell.property == LayoutProperties.WIDTH
                    ? padding.left + padding.right + border.left + border.right
                    : padding.top + padding.bottom + border.top + border.bottom;
        }
        return format((float) number) + "px";
    }

    private static String format(float value) {
        float rounded = Math.round(value * 10f) / 10f;
        return rounded == (int) rounded ? String.valueOf((int) rounded) : String.format(Locale.ROOT, "%.1f", rounded);
    }
}
