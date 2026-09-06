package com.crystalgui.app.uibuilder.inspect;

import javax.annotation.Nullable;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.style.ElementStyle;
import com.crystalgui.style.StyleOrigin;
import com.crystalgui.style.Styleable;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StyleSlot;
import com.crystalgui.style.property.StyleValue;

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

        StyleValue<T> parsed = property.valueParser.parse(rawValue);
        T value = parsed == null ? null : parsed.compute();
        if (value == null) {
            // StyleValue already logs WHY; this says which edit it cost, which the pane can show.
            CrystalGuiCore.LOGGER.info("[cgui] '{}' is not a value for {}", rawValue, property.name);
            return false;
        }
        style.replaceOrPutCandidate(property,
                StyleSlot.of(property, StyleOrigin.INLINE, SPECIFICITY, 0L, value));
        return true;
    }

    /** Drops the inline value, so whatever the sheets say wins again. */
    public static void clearInline(@Nullable Styleable element, StyleProperty<?> property) {
        if (element == null || property == null) return;
        ElementStyle style = element.getStyle();
        if (style == null) return;
        style.removeCandidates(property, slot -> slot.origin() == StyleOrigin.INLINE);
    }

    /** Whether an inline value is currently set — what tells an edited row from an untouched one. */
    public static boolean hasInline(@Nullable Styleable element, StyleProperty<?> property) {
        if (element == null || property == null) return false;
        ElementStyle style = element.getStyle();
        return style != null
                && style.containsCandidate(property, slot -> slot.origin() == StyleOrigin.INLINE);
    }
}
