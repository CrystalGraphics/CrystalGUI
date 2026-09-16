package com.crystalgui.app.uibuilder.style;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.selector.Selector;
import com.crystalgui.style.sheet.StyleRule;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;

/**
 * A value shown while it is being dragged, without writing the file on every frame.
 *
 * <pre>{@code
 * SheetPreview preview = SheetPreview.of(target, node);
 * preview.show(StylePropertyRegistry.OPACITY, "0.4");   // every drag frame
 * preview.commit(fields, "opacity", "0.4");             // once, on release
 * }</pre>
 *
 * <p>The value goes into the cascade as an <b>animation slot</b> on every element the rule reaches —
 * the channel a transition uses, which sits above every other origin and is withdrawn cleanly. So a drag
 * over a rule shows on all of its elements at once, and a 6,000-line sheet is parsed once when the drag
 * ends rather than sixty times while it runs.</p>
 *
 * <p><b>Every preview must be ended.</b> {@link #commit} and {@link #cancel} both do it; a slot left
 * behind would outrank the sheet for the life of the window. An inline target needs none of this — an
 * inline write is already one cheap value on one element — so a preview over one is a no-op and the
 * ordinary write path runs per frame.</p>
 */
public final class SheetPreview {

    /** Its own order, above anything a sheet could have written. */
    private static final int PREVIEW_ORDER = Integer.MAX_VALUE;

    private final StyleTarget target;
    private final UIElement node;

    /** What is being previewed, so it can be withdrawn exactly. */
    private final List<UIElement> shown = new ArrayList<>();

    @Nullable
    private StyleProperty<?> property;

    private SheetPreview(StyleTarget target, UIElement node) {
        this.target = target;
        this.node = node;
    }

    public static SheetPreview of(StyleTarget target, UIElement node) {
        return new SheetPreview(target, node);
    }

    /**
     * Shows {@code css} as {@code property}'s value on everything the target reaches. Called per frame; a
     * value that does not parse is ignored rather than clearing what is on screen.
     */
    public <T> void show(StyleProperty<T> property, String css) {
        if (target.isInline()) return;
        T value = parse(property, css);
        if (value == null) return;
        if (this.property != null && this.property != property) end();
        this.property = property;
        if (shown.isEmpty()) {
            // STARTED ONCE, ticked after: a slot started twice is a slot the cascade holds twice.
            shown.addAll(reached());
            for (UIElement element : shown) element.getStyle().startAnimationSlot(property, value, PREVIEW_ORDER);
            return;
        }
        for (UIElement element : shown) element.getStyle().tickAnimationSlot(property, value, PREVIEW_ORDER);
    }

    /** Withdraws the preview and writes the value once, as one text edit. */
    public void commit(StyleFields fields, String propertyName, String css) {
        end();
        fields.value(propertyName).set(css);
    }

    /** Withdraws the preview, leaving the file as it was. */
    public void cancel() {
        end();
    }

    private void end() {
        if (property != null) {
            for (UIElement element : shown) element.getStyle().endAnimationSlot(property);
        }
        shown.clear();
        property = null;
    }

    /**
     * Every element the target's rule matches — the reason a rule edit is previewed at all: what changes is
     * not just the node in front of you.
     */
    private List<UIElement> reached() {
        UIDocument window = node.document();
        Selector selector = selectorOf();
        if (window == null || selector == null) return List.of(node);
        List<UIElement> out = new ArrayList<>();
        for (UIElement element : window.composedSubtree()) {
            if (selector.matches(element)) out.add(element);
        }
        return out.isEmpty() ? List.of(node) : out;
    }

    /** The parsed selector of the rule being edited, found by the number the cascade gave it. */
    @Nullable
    private Selector selectorOf() {
        SheetDocuments.Sheet sheet = target.sheet();
        StyleSheet live = sheet == null ? null : sheet.sheet();
        if (live == null) return null;
        for (StyleRule rule : live.getRules()) {
            if (rule.sourceOrder() == target.ruleOrder()) return rule.selector();
        }
        return null;
    }

    @Nullable
    private static <T> T parse(StyleProperty<T> property, String css) {
        try {
            return property.valueParser.parse(css).compute();
        } catch (RuntimeException malformed) {
            return null;
        }
    }
}
