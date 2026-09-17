package com.crystalgui.app.uibuilder.inspect;

import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.CssComments;
import com.crystalgui.style.ElementStyle;
import com.crystalgui.style.StyleOrigin;
import com.crystalgui.style.Styleable;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StyleSlot;
import com.crystalgui.style.property.StyleValue;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.PropertyWatch;

/**
 * Writing a value straight onto a live element — what a picked element's inspector edits do.
 *
 * <pre>{@code
 * LiveEdits.setInline(element, StylePropertyRegistry.COLOR, "#FF0000");
 * LiveEdits.clearInline(element, StylePropertyRegistry.COLOR);
 * }</pre>
 *
 * <p><b>Inline, and only for as long as the process lives — Unity's caveat, and the pane says so.</b> A
 * live pick has no document behind it: the thing being inspected is somebody else's running screen, so
 * there is nothing to save into and the edit is gone at the next launch. Making a change permanent is
 * write-back (L3.11), which edits the SHEET the rule came from; this is the tweak you make first to find
 * out what the value should be.</p>
 *
 * <p>Written at {@link StyleOrigin#INLINE}, which beats every sheet and loses to {@code !important} and
 * to a running transition — exactly what an inline style attribute does on the web, so what is seen here
 * is what would be seen from a real one.</p>
 */
public final class LiveEdits {

    private LiveEdits() {
    }

    /** An inline edit outranks any selector, so specificity between two of them is meaningless. */
    private static final int SPECIFICITY = 0;

    /**
     * Parses {@code rawValue} the way a stylesheet would and writes it inline.
     *
     * @return whether it parsed. A malformed value changes nothing and is reported, rather than clearing
     *         the property — which would look like the edit worked and the value was empty.
     */
    public static <T> boolean setInline(@Nullable Styleable element, StyleProperty<T> property,
                                        String rawValue) {
        if (element == null || property == null || rawValue == null) return false;
        ElementStyle style = element.getStyle();
        if (style == null) return false;

        // A VALUE AS WRITTEN, which may hold a switched-off layer or be switched off whole: the cascade gets what is
        // left once the comments are stripped, and the style keeps the text for the save.
        String live = CssComments.strip(rawValue).trim();
        if (live.isEmpty()) {
            if (!CssComments.has(rawValue)) return false;
            style.removeCandidates(property, slot -> slot.origin() == StyleOrigin.INLINE);
            style.setInlineText(property, rawValue.trim());
            return true;
        }
        StyleValue<T> parsed = property.valueParser.parse(live);
        T value = parsed == null ? null : parsed.compute();
        if (value == null) {
            // StyleValue already logs WHY; this says which edit it cost, which the pane can show.
            CrystalGuiCore.LOGGER.info("[cgui] '{}' is not a value for {}", rawValue, property.name);
            return false;
        }
        style.replaceOrPutCandidate(property,
                StyleSlot.of(property, StyleOrigin.INLINE, SPECIFICITY, 0L, value));
        style.setInlineText(property, CssComments.has(rawValue) ? rawValue.trim() : null);
        return true;
    }

    /**
     * Keeps {@code property} on {@code element} equal to {@code css} for as long as the element is on screen:
     * set inline while it holds a value, cleared while it is blank.
     *
     * <pre>{@code
     * LiveEdits.follow(specimen, StylePropertyRegistry.TEXT_SHADOW, fields.value("text-shadow"));
     * }</pre>
     *
     * <p>A preview of a declaration is this one call. A value that does not parse leaves the last one
     * showing, as {@link #setInline} does.</p>
     */
    public static void follow(UIElement element, StyleProperty<?> property, Property<String> css) {
        PropertyWatch.follow(element, css, value -> {
            if (value == null || value.isBlank()) {
                clearInline(element, property);
            } else {
                setInline(element, property, value);
            }
        });
    }

    /** Drops the inline value, so whatever the sheets say wins again. */
    public static void clearInline(@Nullable Styleable element, StyleProperty<?> property) {
        if (element == null || property == null) return;
        ElementStyle style = element.getStyle();
        if (style == null) return;
        style.removeCandidates(property, slot -> slot.origin() == StyleOrigin.INLINE);
        style.setInlineText(property, null);
    }

    /**
     * Removes {@code property}'s inline value when it equals what the element has without it, so choosing a value
     * back leaves no declaration behind rather than a copy of what the sheets already say.
     *
     * <pre>{@code
     * LiveEdits.setInline(node, LayoutProperties.FLEX_WRAP, "nowrap");
     * LiveEdits.dropIfRedundant(node, LayoutProperties.FLEX_WRAP);   // gone again if the sheets say nowrap too
     * }</pre>
     *
     * @return whether it was dropped
     */
    public static <T> boolean dropIfRedundant(@Nullable Styleable element, StyleProperty<T> property) {
        if (!hasInline(element, property)) return false;
        ElementStyle style = element.getStyle();
        // A VALUE HOLDING A SWITCHED-OFF LAYER is not a copy of what the sheets say, whatever its live layers are.
        if (style.inlineText(property) != null) return false;
        T inline = style.getComputed(property);
        clearInline(element, property);
        if (Objects.equals(inline, style.computed().get(property))) return true;
        style.replaceOrPutCandidate(property, StyleSlot.of(property, StyleOrigin.INLINE, SPECIFICITY, 0L, inline));
        return false;
    }

    /** Whether an inline value is currently set — what tells an edited row from an untouched one. */
    public static boolean hasInline(@Nullable Styleable element, StyleProperty<?> property) {
        if (element == null || property == null) return false;
        ElementStyle style = element.getStyle();
        return style != null
                && style.containsCandidate(property, slot -> slot.origin() == StyleOrigin.INLINE);
    }
}
