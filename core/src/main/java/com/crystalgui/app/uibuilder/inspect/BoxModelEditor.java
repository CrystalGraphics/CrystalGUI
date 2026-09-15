package com.crystalgui.app.uibuilder.inspect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.crystalgraphics.platform.CgPlatform;
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
import com.crystalgui.ui.input.DragScrub;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.widget.config.control.NumberControl;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.overlay.Tooltip;
import com.crystalgui.widget.text.UIText;

import dev.vfyjxf.taffy.geometry.FloatRect;
import dev.vfyjxf.taffy.style.BoxSizing;
import dev.vfyjxf.taffy.style.LengthPercentageAuto;
import dev.vfyjxf.taffy.style.TaffyDimension;

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
 * commits, Escape puts back what was there. Dragging a value scrubs it, as every number field in the kit does
 * ({@link DragScrub}): a whole unit every three pixels or so, Shift ten times faster, Escape mid-drag restores it. A bare number is pixels; {@code 50%}, {@code auto} and
 * {@code 2em} are written as typed, and an empty value removes the inline declaration. The content size is
 * shown as the content box and, like DevTools, a typed content size has the padding and border added before
 * it is written under {@code box-sizing: border-box}.</p>
 *
 * <ul>
 *   <li>One edit session, or one scrub, is one undo step, however many values it passed through.</li>
 *   <li>A node with no box — hidden, or under something hidden — shows what its style says instead, greyed, and
 *       edits nothing: there is no layout to measure a typed size against.</li>
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
    /** On the diagram while its node has no box, so what it shows is the style rather than the layout. */
    public static final String NO_BOX_CLASS = "__no-box__";

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
            text.addClass(NumberControl.SCRUB_HANDLE_CLASS);
            // A gesture needs the press, and a label is scenery with hit-testing off by default.
            text.setHitTest(true);
            Tooltip hint = Tooltip.attach(text, property.name);
            hint.addClass(Tooltip.WAIT_CLASS);
            hint.setDescription(contentSize
                    ? "The content box. Double-click to set the " + property.name + ", padding and border included."
                    : "Drag to scrub, or double-click to type. Up and Down step by 1, Shift by 10, Alt by 0.1.");
            text.onMouseDown.attachListener((element, event) -> {
                if (!(event instanceof MouseEvent.Down down)) return;
                if (down.getDetail() >= 2) {
                    beginEdit(this);
                } else {
                    beginScrub(this, down.getPosition().x(), down.getPosition().y());
                }
                event.preventDefault();
            }, false, true);
        }

        public StyleProperty<?> property() {
            return property;
        }

        /** The number's element — what a press lands on. */
        public UIText element() {
            return text;
        }

        /** What the cell shows. */
        public String shown() {
            return text.getText();
        }

        /** The laid-out value this cell writes from, or NaN with nothing laid out. */
        private double measured() {
            Box box = node.box();
            return box == null ? Double.NaN : read.apply(box);
        }

        private void refresh() {
            Box box = node.box();
            text.setText(box == null ? declared(property) : format(read.apply(box)));
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

    /** What had focus when the field opened, handed back when it closes. */
    @Nullable
    private UIElement focusBeforeEdit;

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
        if (node.box() == null) {
            if (editing != null) cancel();
            addClass(NO_BOX_CLASS);
        } else {
            removeClass(NO_BOX_CLASS);
        }
        for (Cell cell : cells) {
            if (cell != editing) cell.refresh();
        }
    }

    /**
     * What {@code property}'s computed value says, for a node with nothing laid out: a length as its number, a
     * percentage with its sign, and {@code auto} where a margin or size is automatic. An automatic padding or
     * border is no space at all, and reads as 0.
     */
    private String declared(StyleProperty<?> property) {
        Object value = node.getStyle().computed().get(property);
        boolean autoIsZero = property != LayoutProperties.WIDTH && property != LayoutProperties.HEIGHT
                && property != LayoutProperties.MARGIN_TOP && property != LayoutProperties.MARGIN_RIGHT
                && property != LayoutProperties.MARGIN_BOTTOM && property != LayoutProperties.MARGIN_LEFT;
        if (value instanceof LengthPercentageAuto length) {
            return switch (length.getType()) {
                case LENGTH -> format(length.getValue());
                case PERCENT -> format(length.getValue() * 100f) + "%";
                case AUTO -> autoIsZero ? "0" : "auto";
                default -> length.getType().name().toLowerCase(Locale.ROOT).replace('_', '-');
            };
        }
        if (value instanceof TaffyDimension dimension) {
            return switch (dimension.getType()) {
                case LENGTH -> format(dimension.getValue());
                case PERCENT -> format(dimension.getValue() * 100f) + "%";
                default -> dimension.getType().name().toLowerCase(Locale.ROOT).replace('_', '-');
            };
        }
        return value == null ? (autoIsZero ? "0" : "auto") : String.valueOf(value);
    }

    // ── Editing ─────────────────────────────────────────────────────────────

    /** Opens {@code cell} for typing, as a double-click does. A no-op when nothing can be written. */
    public void beginEdit(Cell cell) {
        if (document == null || node.box() == null || editing == cell) return;
        if (editing != null) commit();
        editing = cell;
        before = inlineStyle();
        UIDocument opened = document();
        focusBeforeEdit = opened == null ? null : opened.focus().focused();

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

    // ── Scrubbing ───────────────────────────────────────────────────────────

    /**
     * Units per pixel, in whole units: a step a little over three pixels of travel, where a number field takes one.
     * An edge wants a pixel's precision rather than reach.
     */
    private static final double SCRUB_RATE = 0.3;

    /** Where a scrub began, what it has moved since, and the style to put back or record from. */
    @Nullable
    private Cell scrubbing;
    private boolean scrubPassedThreshold;
    private double scrubStart;
    private double scrubAnchor;
    private int scrubModifiers;
    private float scrubAnchoredAtX;
    private float scrubAnchoredAtY;
    private float scrubPixelsPerUnit = 1f;
    @Nullable
    private JsonElement scrubBefore;

    /**
     * Starts a drag on {@code cell}; below the threshold it is a click and writes nothing. The same shape as
     * {@code NumberControl.scrubWith}, anchored on the value at the press so out-and-back returns exactly.
     */
    private void beginScrub(Cell cell, float surfaceX, float surfaceY) {
        if (document == null || editing != null || Double.isNaN(cell.measured())) return;
        scrubbing = cell;
        scrubPassedThreshold = false;
        scrubStart = cell.measured();
        scrubAnchor = scrubStart;
        scrubModifiers = modifiersNow();
        scrubAnchoredAtX = 0f;
        scrubAnchoredAtY = 0f;
        scrubPixelsPerUnit = Drag.pixelsPerLocalUnit(cell.text);
        scrubBefore = inlineStyle();
        Drag.start(cell.text, surfaceX, surfaceY, new Drag.Listener() {
            @Override
            public void onDragUpdate(float mouseX, float mouseY, float startX, float startY, float deltaX, float deltaY) {
                scrubTo(deltaX * scrubPixelsPerUnit, deltaY * scrubPixelsPerUnit);
            }

            @Override
            public void onDragEnd(float mouseX, float mouseY) {
                endScrub(true);
            }

            @Override
            public void onDragCancel() {
                endScrub(false);
            }
        });
    }

    private void scrubTo(float dxPixels, float dyPixels) {
        Cell cell = scrubbing;
        if (cell == null) return;
        if (!scrubPassedThreshold) {
            if (!DragScrub.passesThreshold(dxPixels, dyPixels, DragScrub.DEFAULT_THRESHOLD_PX)) return;
            scrubPassedThreshold = true;
            // FROM HERE, not from the press: pricing the travel spent reaching the threshold made the first
            // update leap by it.
            scrubAnchoredAtX = dxPixels;
            scrubAnchoredAtY = dyPixels;
        }
        // A modifier pressed mid-drag re-anchors, so it prices only the travel still to come. @see NumberControl
        int modifiers = modifiersNow();
        if (modifiers != scrubModifiers) {
            scrubAnchor = cell.measured();
            scrubAnchoredAtX = dxPixels;
            scrubAnchoredAtY = dyPixels;
            scrubModifiers = modifiers;
        }
        // A margin may go negative; padding, border and a size may not.
        boolean signed = cell.property == LayoutProperties.MARGIN_TOP || cell.property == LayoutProperties.MARGIN_RIGHT
                || cell.property == LayoutProperties.MARGIN_BOTTOM || cell.property == LayoutProperties.MARGIN_LEFT;
        DragScrub.Spec spec = DragScrub.Spec.INTEGRAL.withRate(SCRUB_RATE);
        if (!signed) spec = spec.withRange(0d, Double.POSITIVE_INFINITY);
        double value = DragScrub.value(scrubAnchor, dxPixels - scrubAnchoredAtX, dyPixels - scrubAnchoredAtY, modifiers, spec);
        LiveEdits.setInline(node, cell.property, cssValue(cell, format((float) value)));
    }

    /** Records the scrub as one step, or puts the style back when it was cancelled or never moved. */
    private void endScrub(boolean keep) {
        Cell cell = scrubbing;
        JsonElement was = scrubBefore;
        scrubbing = null;
        scrubBefore = null;
        if (cell == null || was == null) return;
        if (!keep || !scrubPassedThreshold) {
            if (scrubPassedThreshold) InlineStyleCodec.replaceInto(JsonOps.INSTANCE, was, node);
            return;
        }
        JsonElement after = inlineStyle();
        if (document != null && !after.equals(was)) document.apply(new BuilderEdit.SetInlineStyle(node, was, after));
    }

    /** The node's inline style as the codec writes it — an empty object, never null, for a node with none. */
    private JsonElement inlineStyle() {
        JsonElement encoded = InlineStyleCodec.encode(JsonOps.INSTANCE, node);
        return encoded == null ? new JsonObject() : encoded;
    }

    /** Whether a scrub has passed its threshold and is writing values. */
    public boolean isScrubbing() {
        return scrubbing != null && scrubPassedThreshold;
    }

    /** What is held down now; none when there is no platform to ask, as in a headless tree. */
    private static int modifiersNow() {
        var input = CgPlatform.input();
        return input == null ? 0 : input.getCurrentModifiers();
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
        JsonElement after = inlineStyle();
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
        // FOCUS GOES BACK BEFORE THE FIELD GOES: removing the focused element leaves nothing focused, and undo
        // resolves outward from focus, so Ctrl+Z right after Enter reached no history at all.
        UIElement returnTo = focusBeforeEdit;
        focusBeforeEdit = null;
        UIDocument window = document();
        if (window != null && input.isFocused()) {
            UIElement target = returnTo != null && returnTo.isConnected() ? returnTo : null;
            // Nothing had focus: the nearest focusable panel around the diagram, which answers the same history.
            for (UIElement at = parentElement(); target == null && at != null; at = at.parentElement()) {
                if (window.focus().focusable(at)) target = at;
            }
            if (target != null) window.focus().requestPointerFocus(target);
        }
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
